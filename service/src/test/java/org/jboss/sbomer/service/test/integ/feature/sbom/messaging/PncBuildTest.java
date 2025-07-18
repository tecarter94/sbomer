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
package org.jboss.sbomer.service.test.integ.feature.sbom.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;
import org.jboss.sbomer.core.config.request.PncBuildRequestConfig;
import org.jboss.sbomer.core.config.request.PncOperationRequestConfig;
import org.jboss.sbomer.core.config.request.RequestConfig;
import org.jboss.sbomer.core.features.sbom.enums.GenerationRequestType;
import org.jboss.sbomer.core.features.sbom.enums.GenerationResult;
import org.jboss.sbomer.core.features.sbom.enums.RequestEventStatus;
import org.jboss.sbomer.core.features.sbom.enums.RequestEventType;
import org.jboss.sbomer.core.features.sbom.utils.ObjectMapperProvider;
import org.jboss.sbomer.core.test.TestResources;
import org.jboss.sbomer.service.feature.sbom.features.umb.consumer.AmqpMessageConsumer;
import org.jboss.sbomer.service.feature.sbom.features.umb.consumer.PncNotificationHandler;
import org.jboss.sbomer.service.feature.sbom.features.umb.consumer.model.PncBuildNotificationMessageBody;
import org.jboss.sbomer.service.feature.sbom.features.umb.consumer.model.PncDelAnalysisNotificationMessageBody;
import org.jboss.sbomer.service.feature.sbom.features.umb.producer.AmqpMessageProducer;
import org.jboss.sbomer.service.feature.sbom.k8s.model.SbomGenerationStatus;
import org.jboss.sbomer.service.feature.sbom.model.RequestEvent;
import org.jboss.sbomer.service.feature.sbom.model.SbomGenerationRequest;
import org.jboss.sbomer.service.feature.sbom.service.RequestEventRepository;
import org.jboss.sbomer.service.feature.sbom.service.SbomGenerationRequestRepository;
import org.jboss.sbomer.service.test.PncWireMock;
import org.jboss.sbomer.service.test.utils.AmqpMessageHelper;
import org.jboss.sbomer.service.test.utils.umb.TestUmbProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySource;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@QuarkusTest
@TestProfile(TestUmbProfile.class)
@WithTestResource(PncWireMock.class)
@Slf4j
class PncBuildTest {

    @Inject
    AmqpMessageConsumer consumer;

    @Inject
    AmqpMessageProducer producer;

    @InjectSpy
    PncNotificationHandler handler;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    SbomGenerationRequestRepository sbomGenerationRequestRepository;

    @Inject
    RequestEventRepository requestEventRepository;

    @Inject
    @Connector("smallrye-in-memory")
    InMemoryConnector connector;

    @Test
    void testUMBProducerPNCBuild() throws Exception {
        log.info("Running testUMBProducerPNCBuild...");

        InMemorySource<Message<String>> builds = connector.source("builds");
        Message<String> txgMsg = preparePNCBuildMsg();

        builds.send(txgMsg);

        ArgumentCaptor<RequestEvent> requestEventArgumentCaptor = ArgumentCaptor.forClass(RequestEvent.class);

        Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() -> {
            List<SbomGenerationRequest> requests = sbomGenerationRequestRepository.listByIdentifier("AX5TJMYHQAIAE");

            if (requests.size() == 1) {
                log.info("Generation request for PNC Build was found!");
                return true;
            }

            return false;
        });

        verify(handler, times(1)).handle(requestEventArgumentCaptor.capture());

        // Verify the request event type
        List<RequestEvent> requestEvents = requestEventArgumentCaptor.getAllValues();
        assertEquals(1, requestEvents.size());
        RequestEvent requestEvent = requestEvents.get(0);
        assertEquals(RequestEventType.UMB, requestEvent.getEventType());

        // Refresh the request event because it was updated in its own transaction
        requestEvent = RequestEvent.findById(requestEvent.getId()); // NOSONAR
        // Verify request config
        RequestConfig requestConfig = requestEvent.getRequestConfig();
        assertInstanceOf(PncBuildRequestConfig.class, requestConfig);
        PncBuildRequestConfig pncBuildRequestConfig = (PncBuildRequestConfig) requestConfig;
        assertEquals("AX5TJMYHQAIAE", pncBuildRequestConfig.getBuildId());

        // Verify event
        JsonNode event = requestEvent.getEvent();
        assertEquals(PncBuildRequestConfig.TYPE_NAME, event.get(RequestEvent.EVENT_KEY_UMB_MSG_TYPE).asText());
        JsonNode msgNode = event.get(RequestEvent.EVENT_KEY_UMB_MSG);

        // Verify msg
        String msg = msgNode.isTextual() ? msgNode.textValue() : msgNode.toString();
        PncBuildNotificationMessageBody buildMsgBody = ObjectMapperProvider.json()
                .readValue(msg, PncBuildNotificationMessageBody.class);

        // See "payloads/umb-pnc-build-body.json" file
        assertEquals("AX5TJMYHQAIAE", buildMsgBody.getBuild().getId());
    }

    @Test
    void testUMBProducerDelAnalysisOperation() throws Exception {
        log.info("Running testUMBProducerDelAnalysisOperation...");

        InMemorySource<Message<String>> builds = connector.source("builds");
        Message<String> txgMsg = preparePNCDelAnalysisMsg();

        builds.send(txgMsg);

        ArgumentCaptor<RequestEvent> requestEventArgumentCaptor = ArgumentCaptor.forClass(RequestEvent.class);

        Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() -> {
            List<SbomGenerationRequest> requests = sbomGenerationRequestRepository.listByIdentifier("A6DFVW2SACIAA");

            if (requests.size() == 1) {
                log.info("Generation request for PNC Build was found!");
                return true;
            }

            return false;
        });

        verify(handler, times(1)).handle(requestEventArgumentCaptor.capture());

        // Verify the request event type
        List<RequestEvent> requestEvents = requestEventArgumentCaptor.getAllValues();
        assertEquals(1, requestEvents.size());
        RequestEvent requestEvent = requestEvents.get(0);
        assertEquals(RequestEventType.UMB, requestEvent.getEventType());
        // Refresh the request event because it was updated in its own transaction
        requestEvent = RequestEvent.findById(requestEvent.getId()); // NOSONAR

        // Verify request config
        RequestConfig requestConfig = requestEvent.getRequestConfig();
        assertInstanceOf(PncOperationRequestConfig.class, requestConfig);
        PncOperationRequestConfig pncOperationRequestConfig = (PncOperationRequestConfig) requestConfig;
        assertEquals("A6DFVW2SACIAA", pncOperationRequestConfig.getOperationId());

        // Verify event
        JsonNode event = requestEvent.getEvent();
        assertEquals(PncOperationRequestConfig.TYPE_NAME, event.get(RequestEvent.EVENT_KEY_UMB_MSG_TYPE).asText());
        JsonNode msgNode = event.get(RequestEvent.EVENT_KEY_UMB_MSG);

        // Verify msg
        String msg = msgNode.isTextual() ? msgNode.textValue() : msgNode.toString();
        PncDelAnalysisNotificationMessageBody operationMsgBody = ObjectMapperProvider.json()
                .readValue(msg, PncDelAnalysisNotificationMessageBody.class);

        // See "payloads/umb-pnc-del-analysis-body.json" file
        assertEquals("A6DFVW2SACIAA", operationMsgBody.getOperationId());
        assertEquals("2712", operationMsgBody.getMilestoneId());
    }

    @Test
    void testUMBConsumerFailedDelAnalysisOperation() throws Exception {
        log.info("Running testUMBConsumerFailedDelAnalysisOperation...");

        // Prepare initial NO_OP request.
        SbomGenerationRequest request = SbomGenerationRequest.builder()
                .withId("FAILEDOPERATION")
                .withIdentifier("BAQZMQOS7YIAA")
                .withType(GenerationRequestType.OPERATION)
                .withStatus(SbomGenerationStatus.NO_OP)
                .build();

        sbomGenerationRequestRepository.save(request);

        InMemorySource<Message<String>> builds = connector.source("builds");
        Message<String> txgMsg = prepareFailedPNCDelAnalysisMsg();

        // Send the message to notify that the build failed.
        builds.send(txgMsg);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() -> {
            SbomGenerationRequest updated = sbomGenerationRequestRepository.findById("FAILEDOPERATION");

            return updated != null && updated.getStatus().equals(SbomGenerationStatus.FAILED);
        });

        SbomGenerationRequest updatedRequest = sbomGenerationRequestRepository.findById("FAILEDOPERATION");

        assertEquals(SbomGenerationStatus.FAILED, updatedRequest.getStatus());
        assertEquals("Deliverable analyzer operation failed in PNC", updatedRequest.getReason());
        assertEquals(GenerationResult.ERR_GENERAL, updatedRequest.getResult());
    }

    @Test
    void testUMBConsumeSuccessfulDelAnalysisOperationWithPendingRequest() throws Exception {
        log.info("Running testUMBConsumeSuccessfulDelAnalysisOperationWithPendingRequest...");

        // --- ARRANGE (Pre-conditions) ---
        final String TEST_OPERATION_ID = "A6DFVW2SACABC"; // Must match preparePNCDelAnalysisMsg()
        final String TEST_SBOM_REQUEST_ID = "TEST_PENDING_REQ_ID"; // Unique ID for our pending request

        // 1. Create a RequestEvent using the provided method
        ObjectNode dummyEventPayload = ObjectMapperProvider.json().createObjectNode();
        dummyEventPayload.put("type", "InitialApiCall");
        dummyEventPayload.put("data", "some-initial-data");

        RequestEvent initialRequestEvent = requestEventRepository
                .createRequestEvent(RequestEventStatus.IN_PROGRESS, dummyEventPayload, "Created by UMB Message");

        // 2. Prepare initial NO_OP SbomGenerationRequest linked to the RequestEvent
        SbomGenerationRequest pendingSbomRequest = SbomGenerationRequest.builder()
                .withId(TEST_SBOM_REQUEST_ID)
                .withIdentifier(TEST_OPERATION_ID)
                .withType(GenerationRequestType.OPERATION)
                .withStatus(SbomGenerationStatus.NO_OP)
                .withRequest(initialRequestEvent)
                .build();
        sbomGenerationRequestRepository.save(pendingSbomRequest);

        log.info(
                "Pre-created SbomGenerationRequest ID: {} with status {} and linked to RequestEvent ID: {}",
                pendingSbomRequest.getId(),
                pendingSbomRequest.getStatus(),
                pendingSbomRequest.getRequest().getId());

        InMemorySource<Message<String>> builds = connector.source("builds");
        Message<String> txgMsg = preparePNCDelAnalysisMsgPending();

        // Send the message to notify that the build failed.
        builds.send(txgMsg);

        ArgumentCaptor<RequestEvent> requestEventArgumentCaptor = ArgumentCaptor.forClass(RequestEvent.class);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() -> {
            SbomGenerationRequest updated = sbomGenerationRequestRepository.findById(TEST_SBOM_REQUEST_ID);

            return updated != null && updated.getStatus().equals(SbomGenerationStatus.INITIALIZED);
        });

        // Verify we handled a message
        verify(handler, times(1)).handle(requestEventArgumentCaptor.capture());

        // Check if the sbom generation request is now initialized and ready to be picked up
        SbomGenerationRequest updatedRequest = sbomGenerationRequestRepository.findById(TEST_SBOM_REQUEST_ID);
        assertEquals(SbomGenerationStatus.INITIALIZED, updatedRequest.getStatus());

        // Check if the initial event request is the same as the one that ends up attached to the sbom generation
        // request
        RequestEvent initialRequestEventUpdated = requestEventRepository.findById(initialRequestEvent.getId());
        RequestEvent requestEventConnectedToGenerationRequest = updatedRequest.getRequest();
        assertNotNull(initialRequestEventUpdated, "The original RequestEvent should still exist in the database.");
        assertNotNull(requestEventConnectedToGenerationRequest, "RequestEvent of Generation should not be null");
        assertEquals(
                initialRequestEventUpdated.getId(),
                requestEventConnectedToGenerationRequest.getId(),
                "They should be the same request event, thus the IDs should be the same, but got different:\n"
                        + initialRequestEventUpdated.toString() + "\n"
                        + requestEventConnectedToGenerationRequest.toString());

    }

    private Message<String> preparePNCBuildMsg() throws IOException {
        JsonObject headers = new JsonObject();

        headers.put("type", "BuildStateChange");
        headers.put("attribute", "state-change");
        headers.put("name", "org.kie-kie-jpmml-integration-7.67.0.Final-7.13.3");
        headers.put("configurationId", "10884");
        headers.put("configurationRevision", "2653483");
        headers.put("oldStatus", "BUILDING");
        headers.put("newStatus", "SUCCESS");
        headers.put("JMSXUserID", "projectnewcastle");
        headers.put("amq6100_originalDestination", "topic://VirtualTopic.eng.pnc.builds");
        headers.put("correlationId", "a420416c-d184-4ced-9277-500667305139");
        headers.put("destination", "/topic/VirtualTopic.eng.pnc.builds");
        headers.put("messageId", "ID:orch-70-z4tx6-43917-1678809685060-25:1:8557:1:1");
        headers.put("persistent", "true");
        headers.put("producer", "PNC");
        headers.put("timestamp", 1698076061381L);

        return AmqpMessageHelper.toMessage(TestResources.asString("payloads/umb-pnc-build-body.json"), headers);
    }

    private Message<String> preparePNCDelAnalysisMsg() throws IOException {
        JsonObject headers = new JsonObject();
        headers.put("type", "DeliverableAnalysisStateChange");
        headers.put("attribute", "deliverable-analysis-state-change");
        headers.put("name", "org.kie-kie-jpmml-integration-7.67.0.Final-7.13.3");
        headers.put("milestoneId", "2712");
        headers.put("operationId", "A6DFVW2SACIAA");
        headers.put("status", "FINISHED");
        headers.put("JMSXUserID", "projectnewcastle");
        headers.put("amq6100_originalDestination", "topic://VirtualTopic.eng.pnc.builds");
        headers.put("correlationId", "a420416c-d184-4ced-9277-500667305139");
        headers.put("destination", "/topic/VirtualTopic.eng.pnc.builds");
        headers.put("messageId", "ID:analysis-70-z4tx6-43917-1678809685060-25:1:8557:1:1");
        headers.put("persistent", "true");
        headers.put("producer", "PNC");
        headers.put("timestamp", 1698076061381L);

        return AmqpMessageHelper.toMessage(TestResources.asString("payloads/umb-pnc-del-analysis-body.json"), headers);
    }

    private Message<String> preparePNCDelAnalysisMsgPending() throws IOException {
        JsonObject headers = new JsonObject();
        headers.put("type", "DeliverableAnalysisStateChange");
        headers.put("attribute", "deliverable-analysis-state-change");
        headers.put("name", "org.kie-kie-jpmml-integration-7.67.0.Final-7.13.3");
        headers.put("milestoneId", "2712");
        headers.put("operationId", "A6DFVW2SACABC");
        headers.put("status", "FINISHED");
        headers.put("JMSXUserID", "projectnewcastle");
        headers.put("amq6100_originalDestination", "topic://VirtualTopic.eng.pnc.builds");
        headers.put("correlationId", "a420416c-d184-4ced-9277-500667305139");
        headers.put("destination", "/topic/VirtualTopic.eng.pnc.builds");
        headers.put("messageId", "ID:analysis-70-z4tx6-43917-1678809685060-25:1:8557:1:5");
        headers.put("persistent", "true");
        headers.put("producer", "PNC");
        headers.put("timestamp", 1698076061381L);

        return AmqpMessageHelper
                .toMessage(TestResources.asString("payloads/umb-pnc-del-analysis-body-pending.json"), headers);
    }

    private Message<String> prepareFailedPNCDelAnalysisMsg() throws IOException {
        JsonObject headers = new JsonObject();
        headers.put("type", "DeliverableAnalysisStateChange");
        headers.put("attribute", "deliverable-analysis-state-change");
        headers.put("name", "org.kie-kie-jpmml-integration-7.67.0.Final-7.13.3");
        headers.put("milestoneId", "2712");
        headers.put("operationId", "A6DFVW2SACIAA");
        headers.put("status", "FINISHED");
        headers.put("JMSXUserID", "projectnewcastle");
        headers.put("amq6100_originalDestination", "topic://VirtualTopic.eng.pnc.builds");
        headers.put("correlationId", "a420416c-d184-4ced-9277-500667305139");
        headers.put("destination", "/topic/VirtualTopic.eng.pnc.builds");
        headers.put("messageId", "ID:analysis-70-z4tx6-43917-1678809685060-25:1:8557:1:3");
        headers.put("persistent", "true");
        headers.put("producer", "PNC");
        headers.put("timestamp", 1698076061381L);

        return AmqpMessageHelper
                .toMessage(TestResources.asString("payloads/umb-pnc-failed-del-analysis-body.json"), headers);
    }

}
