/**
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
package org.jboss.sbomer.service.test.integ.feature.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;

import org.jboss.sbomer.core.dto.v1alpha1.SbomRecord;
import org.jboss.sbomer.core.features.sbom.rest.Page;
import org.jboss.sbomer.service.feature.sbom.model.Sbom;
import org.jboss.sbomer.service.feature.sbom.service.SbomService;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@QuarkusTest
@WithKubernetesTestServer
@Slf4j
public class SBOMServiceTestIT {

    @Inject
    SbomService sbomService;

    private static final String INITIAL_BUILD_ID = "ARYT3LBXDVYAC";

    @Test
    public void testGetBaseSbom() throws IOException {
        log.info("testGetBaseSbom ...");
        String rsqlQuery = "buildId=eq=" + INITIAL_BUILD_ID;
        Collection<SbomRecord> sboms = sbomService.searchSbomsByQueryPaginated(0, 1, rsqlQuery, null).getContent();
        assertTrue(sboms.size() > 0);
    }

    @Test
    public void testListBaseSboms() throws IOException {
        log.info("testListBaseSboms ...");

        Sbom dummySbom = new Sbom();
        dummySbom.setBuildId(INITIAL_BUILD_ID);

        sbomService.save(dummySbom);

        Page<SbomRecord> page = sbomService.searchSbomsByQueryPaginated(0, 50, null, null);
        assertEquals(0, page.getPageIndex());
        assertEquals(50, page.getPageSize());
        assertTrue(page.getTotalHits() > 0);
        assertEquals(1, page.getTotalPages());
        assertTrue(page.getContent().size() > 0);

        SbomRecord foundSbom = null;
        Iterator<SbomRecord> contentIterator = page.getContent().iterator();
        while (contentIterator.hasNext()) {
            SbomRecord sbom = contentIterator.next();
            if (sbom.buildId().equals(INITIAL_BUILD_ID)) {
                foundSbom = sbom;
                break;
            }
        }

        assertNotNull(foundSbom);
    }
}
