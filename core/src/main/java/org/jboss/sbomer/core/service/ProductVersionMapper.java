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
package org.jboss.sbomer.core.service;

import java.io.IOException;
import java.util.HashMap;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;

import org.jboss.sbomer.core.errors.ApplicationException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;
import lombok.Getter;

/**
 * <p>
 * Bean providing a mapping between a PNC Product Version and other systems for a given build.
 * </p>
 *
 */
@ApplicationScoped
public class ProductVersionMapper {

    @Data
    public static class ErrataMapping {
        String productName;
        String productVersion;
        String ProductVariant;
    }

    @Data
    public static class ProductVersionMapping {
        ErrataMapping errata;
    }

    public static class Mapping extends HashMap<String, ProductVersionMapping> {

    }

    /**
     * <p>
     * The mapping between the product version defined in the PNC BuildConfig and other systems. The id being the key is
     * the PNC Product Version identifier.
     * </p>
     */
    @Getter
    Mapping mapping;

    @PostConstruct
    void init() {
        try {
            mapping = new ObjectMapper()
                    .readValue(getClass().getClassLoader().getResourceAsStream("product-mapping.json"), Mapping.class);
        } catch (IOException e) {
            throw new ApplicationException("Could not read product mappings", e);
        }
    }
}