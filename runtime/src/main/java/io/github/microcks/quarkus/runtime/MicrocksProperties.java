/*
 * Copyright The Microcks Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.microcks.quarkus.runtime;

/**
 * {@code }MicrocksProperties} are contributed by Dev Service Processor to Quarkus global configuration.
 * They may be reused at runtime so are defined in this runtime module.
 * @author laurent
 */
public class MicrocksProperties {

   private MicrocksProperties() {
      // Hide the implicit default constructor.
   }

   public static final String MICROCKS = "microcks";
   public static final String CONFIG_PREFIX = "quarkus." + MICROCKS + ".";
   public static final String HTTP_SUFFIX = ".http";
   public static final String HTTP_HOST_SUFFIX = ".http.host";
   public static final String HTTP_PORT_SUFFIX = ".http.port";
   public static final String GRPC_SUFFIX = ".grpc";
   public static final String GRPC_HOST_SUFFIX = ".grpc.host";
   public static final String GRPC_PORT_SUFFIX = ".grpc.port";
   public static final String LOADED_PRIMARY_ARTIFACTS = ".primary-artifacts";
   public static final String LOADED_SECONDARY_ARTIFACTS = ".secondary-artifacts";

   /**
    * Get configuration properties prefix for a Microcks service.
    * @param serviceName The name of the Microcks service - 'default' is the default ;-)
    * @return The prefix of configuration properties
    */
   public static String getConfigPrefix(String serviceName) {
      return CONFIG_PREFIX + serviceName;
   }
}
