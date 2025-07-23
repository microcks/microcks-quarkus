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
package io.github.microcks.quarkus.test;

import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Collections;
import java.util.Map;

/**
 * MicrocksTestCompaniong aims to be initialized as a QuarkusTestResource to provide access to configuration properties
 * such as broker endpoints or other magical stuff done by Microcks DevService.
 *
 * @author laurent
 */
public class MicrocksTestCompanion implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {

   private String kafkaInternalEndpoint;

   @Override
   public void setIntegrationTestContext(DevServicesContext context) {
      Map<String, String> devServicesProperties = context.devServicesProperties();
      String kafkaBootstrapServers = devServicesProperties.get("kafka.bootstrap.servers");
      if (kafkaBootstrapServers != null) {
         if (kafkaBootstrapServers.contains(",")) {
            String[] kafkaAddresses = kafkaBootstrapServers.split(",");
            for (String kafkaAddress : kafkaAddresses) {
               if (kafkaAddress.startsWith("PLAINTEXT://")) {
                  kafkaInternalEndpoint = kafkaAddress.replace("PLAINTEXT://", "");
               }
            }
         } else {
            kafkaInternalEndpoint = kafkaBootstrapServers.substring(kafkaBootstrapServers.indexOf("://") + 3);
         }
      }
   }

   @Override
   public Map<String, String> start() {
      return Collections.emptyMap();
   }

   @Override
   public void stop() {
      // Nothing to stop here.
   }

   @Override
   public void inject(TestInjector testInjector) {
      testInjector.injectIntoFields(this.kafkaInternalEndpoint,
            new TestInjector.AnnotatedAndMatchesType(InjectKafkaInternalEndpoint.class, String.class));
   }
}
