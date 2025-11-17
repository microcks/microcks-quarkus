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
package io.github.microcks.quarkus.deployment;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithParentName;

/**
 * Configuration description for the Microcks Quarkus extension.
 * @author laurent
 */
@ConfigMapping(prefix = "quarkus.microcks")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface MicrocksBuildTimeConfig {

   /**
    * Default Dev services configuration.
    */
   @WithParentName
   DevServiceConfiguration defaultDevService();

   @ConfigGroup
   public interface DevServiceConfiguration {
      /**
       * Configuration for DevServices
       * <p>
       * DevServices allows Quarkus to automatically start Microcks in dev and test mode.
       */
      MicrocksDevServicesConfig devservices();
   }
}
