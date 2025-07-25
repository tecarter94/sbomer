/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2023 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.sbomer.service.feature.sbom.features.generator.rpm.controller;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.cyclonedx.model.Bom;
import org.jboss.sbomer.core.errors.ApplicationException;
import org.jboss.sbomer.core.features.sbom.enums.GenerationRequestType;
import org.jboss.sbomer.core.features.sbom.enums.GenerationResult;
import org.jboss.sbomer.core.features.sbom.utils.FileUtils;
import org.jboss.sbomer.core.features.sbom.utils.OtelHelper;
import org.jboss.sbomer.service.feature.sbom.features.generator.AbstractController;
import org.jboss.sbomer.service.feature.sbom.k8s.model.GenerationRequest;
import org.jboss.sbomer.service.feature.sbom.k8s.model.SbomGenerationPhase;
import org.jboss.sbomer.service.feature.sbom.k8s.model.SbomGenerationStatus;
import org.jboss.sbomer.service.feature.sbom.k8s.resources.Labels;
import org.jboss.sbomer.service.feature.sbom.model.Sbom;
import org.slf4j.MDC;

import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.tekton.v1beta1.StepState;
import io.fabric8.tekton.v1beta1.TaskRun;
import io.fabric8.tekton.v1beta1.TaskRunStatus;
import io.javaoperatorsdk.operator.api.config.informer.Informer;
import io.javaoperatorsdk.operator.api.reconciler.Constants;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.Workflow;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Dependent;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Reconciler working on the {@link GenerationRequest} entity and the {@link GenerationRequestType#BREW_RPM} type.
 * </p>
 *
 * <p>
 * This reconciler acts only on resources marked with the following labels (all of them must exist on the resource):
 *
 * <ul>
 * <li>{@code app.kubernetes.io/part-of=sbomer}</li>
 * <li>{@code app.kubernetes.io/component=sbom}</li>
 * <li>{@code app.kubernetes.io/managed-by=sbom}</li>
 * <li>{@code sbomer.jboss.org/generation-request}</li>
 * <li>{@code sbomer.jboss.org/generation-request-type}</li>
 * </ul>
 * </p>
 */
@ControllerConfiguration(
        informer = @Informer(
                namespaces = { Constants.WATCH_CURRENT_NAMESPACE },
                labelSelector = "app.kubernetes.io/part-of=sbomer,app.kubernetes.io/managed-by=sbomer,app.kubernetes.io/component=generator,sbomer.jboss.org/type=generation-request,sbomer.jboss.org/generation-request-type=brew-rpm"))
@Workflow(
        dependents = { @Dependent(
                useEventSourceWithName = "tekton-generation-request-brew-rpm",
                type = TaskRunBrewRPMGenerateDependentResource.class) })
@Slf4j
public class BrewRPMController extends AbstractController {

    private static Integer UNSUPPORTED_EXIT_CODE = 10;

    @Override
    protected GenerationRequestType generationRequestType() {
        return GenerationRequestType.BREW_RPM;
    }

    @Override
    protected void setPhaseLabel(GenerationRequest generationRequest) {
        if (SbomGenerationStatus.GENERATING.equals(generationRequest.getStatus())) {
            generationRequest.getMetadata()
                    .getLabels()
                    .put(Labels.LABEL_PHASE, SbomGenerationPhase.GENERATE.name().toLowerCase());
        }
    }

    /**
     * <p>
     * Handles updates to {@link GenerationRequest} being in progress.
     * </p>
     *
     * @param generationRequest the generation request
     * @param secondaryResources the secondary resources
     * @return the update control for the generation request
     */
    @Override
    protected UpdateControl<GenerationRequest> reconcileGenerating(
            GenerationRequest generationRequest,
            Set<TaskRun> secondaryResources) {

        log.debug("Reconcile GENERATING for '{}'...", generationRequest.getName());
        Map<String, String> attributes = createBaseGenerationSpanAttibutes(generationRequest);

        return OtelHelper
                .withSpan(this.getClass(), ".reconcile-generating", attributes, MDC.getCopyOfContextMap(), () -> {
                    TaskRun generateTaskRun = findTaskRun(secondaryResources, SbomGenerationPhase.GENERATE);

                    if (generateTaskRun == null) {
                        log.error(
                                "There is no generation TaskRun related to GenerationRequest '{}'",
                                generationRequest.getName());

                        return updateRequest(
                                generationRequest,
                                SbomGenerationStatus.FAILED,
                                GenerationResult.ERR_SYSTEM,
                                "Generation failed. Unable to find related TaskRun. See logs for more information.");
                    }

                    // In case the TaskRun hasn't finished yet, wait for the next update.
                    if (!isFinished(generateTaskRun)) {
                        return UpdateControl.noUpdate();
                    }

                    // In case the Task Run is not successful, fail the generation
                    if (!Boolean.TRUE.equals(isSuccessful(generateTaskRun))) {
                        String detailedFailureMessage = getDetailedFailureMessage(generateTaskRun);

                        log.error("Generation failed, the TaskRun returned failure: {}", detailedFailureMessage);

                        // If we get return code 10 then its going to be related to unsupported
                        if (UNSUPPORTED_EXIT_CODE.equals(getFirstFailedStepExitCode(generateTaskRun))) {
                            return updateRequest(
                                    generationRequest,
                                    SbomGenerationStatus.FAILED,
                                    GenerationResult.ERR_GENERATION,
                                    "Generation failed. TaskRun responsible for generation failed: {}",
                                    detailedFailureMessage);

                        }

                        return updateRequest(
                                generationRequest,
                                SbomGenerationStatus.FAILED,
                                GenerationResult.ERR_SYSTEM,
                                "Generation failed. TaskRun responsible for generation failed: {}",
                                detailedFailureMessage);
                    }

                    // Construct the path to the working directory of the generator
                    Path generationDir = Path.of(controllerConfig.sbomDir(), generationRequest.getMetadata().getName());

                    log.debug("Reading manifests from '{}'...", generationDir.toAbsolutePath());

                    List<Path> manifestPaths;

                    try {
                        manifestPaths = FileUtils.findManifests(generationDir);
                    } catch (IOException e) {
                        log.error("Unexpected IO exception occurred while trying to find generated manifests", e);

                        return updateRequest(
                                generationRequest,
                                SbomGenerationStatus.FAILED,
                                GenerationResult.ERR_SYSTEM,
                                "Generation succeeded, but reading generated SBOMs failed due IO exception. See logs for more information.");
                    }

                    if (manifestPaths.isEmpty()) {
                        log.error("No manifests found, this is unexpected");

                        return updateRequest(
                                generationRequest,
                                SbomGenerationStatus.FAILED,
                                GenerationResult.ERR_SYSTEM,
                                "Generation succeed, but no manifests could be found. At least one was expected. See logs for more information.");
                    }

                    List<Bom> boms;

                    try {
                        boms = readManifests(manifestPaths);
                    } catch (Exception e) {
                        log.error("Unable to read one or more manifests", e);

                        return updateRequest(
                                generationRequest,
                                SbomGenerationStatus.FAILED,
                                GenerationResult.ERR_SYSTEM,
                                "Generation succeeded, but reading generated manifests failed was not successful. See logs for more information.");
                    }

                    List<Sbom> sboms;

                    try {
                        sboms = storeBoms(generationRequest, boms);
                    } catch (ValidationException e) {
                        // There was an error when validating the entity, most probably the SBOM is not valid
                        log.error("Unable to validate generated SBOMs: {}", e.getMessage(), e);

                        return updateRequest(
                                generationRequest,
                                SbomGenerationStatus.FAILED,
                                GenerationResult.ERR_GENERATION,
                                "Generation failed. One or more generated SBOMs failed validation: {}. See logs for more information.",
                                e.getMessage());
                    }

                    try {
                        performPost(sboms);
                    } catch (ApplicationException e) {
                        return updateRequest(
                                generationRequest,
                                SbomGenerationStatus.FAILED,
                                GenerationResult.ERR_POST,
                                e.getMessage());
                    }

                    return updateRequest(
                            generationRequest,
                            SbomGenerationStatus.FINISHED,
                            GenerationResult.SUCCESS,
                            String.format(
                                    "Generation finished successfully. Generated SBOMs: %s",
                                    sboms.stream().map(Sbom::getId).collect(Collectors.joining(", "))));
                });
    }

    public static Integer getFirstFailedStepExitCode(TaskRun taskRun) {
        TaskRunStatus status = taskRun.getStatus();

        for (StepState stepState : status.getSteps()) {
            ContainerStateTerminated terminatedState = stepState.getTerminated();
            if (terminatedState != null) {
                Integer exitCode = terminatedState.getExitCode();
                // We only want failed non-zero exit code
                if (exitCode != null && exitCode != 0) {
                    return exitCode;
                }
            }
        }
        return null;
    }
}
