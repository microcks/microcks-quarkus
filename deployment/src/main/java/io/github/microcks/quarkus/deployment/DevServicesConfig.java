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
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration description for the DevServices of the Microcks Quarkus extension.
 * @author laurent
 */
@ConfigGroup
public interface DevServicesConfig {

   /** Default image name for Microcks container. */
   String MICROCKS_UBER_LATEST = "quay.io/microcks/microcks-uber:latest";
   /** Default image name for Microcks Async minion container. */
   String MICROCKS_UBER_ASYNC_MINION_LATEST = "quay.io/microcks/microcks-uber-async-minion:latest";
   /** Default image name for Microcks Postman runtime container. */
   String MICROCKS_POSTMAN_LATEST = "quay.io/microcks/microcks-postman-runtime:latest";


   /**
    * If DevServices has been explicitly enabled or disabled. DevServices is generally enabled
    * by default, unless there is an existing configuration present.
    * <p>
    * When DevServices is enabled Quarkus will attempt to automatically configure and start
    * a Microcks server when running in Dev or Test mode and when Docker is running.
    */
   @WithDefault("true")
   boolean enabled();
   
    /**
     * Option to disable host access - can be used for issues with kubernetes-client dev services compatability
     */
   @WithDefault("true")
   boolean hostAccess();
   /**
    * The container image name to use, for container based DevServices providers.
    * Use an image based on or derived from: {@code quay.io/microcks/microcks-uber:latest}.
    */
   @WithDefault(MICROCKS_UBER_LATEST)
   String imageName();

   /**
    * Indicates if the Microcks server managed by Quarkus Dev Services is shared.
    * When shared, Quarkus looks for running containers using label-based service discovery.
    * If a matching container is found, it is used, and so a second one is not started.
    * Otherwise, Dev Services for Microcks starts a new container.
    * <p>
    * The discovery uses the {@code quarkus-dev-service-microcks} label.
    * The value is configured using the {@code service-name} property.
    * <p>
    * Container sharing is only used in dev mode.
    */
   @WithDefault("true")
   boolean shared();

   /**
    * The value of the {@code quarkus-dev-service-microcks} label attached to the started container.
    * This property is used when {@code shared} is set to {@code true}.
    * In this case, before starting a container, Dev Services for Microcks looks for a container with the
    * {@code quarkus-dev-service-microcks} label
    * set to the configured value. If found, it will use this container instead of starting a new one. Otherwise, it
    * starts a new container with the {@code quarkus-dev-service-microcks} label set to the specified value.
    * <p>
    * This property is used when you need multiple shared Microcks servers.
    */
   @WithDefault("default")
   String serviceName();

   /**
    * Environment variables that are passed to the container.
    */
   Map<String, String> containerEnv();

   /**
    * The Artifacts to load within Microcks container.
    */
   Optional<ArtifactsConfiguration> artifacts();

   /**
    * The remote Artifacts to load within Microcks container.
    */
   Optional<ArtifactsConfiguration> remoteArtifacts();

   /**
    * The secrets to load within Microcks container.
    */
   Map<String, SecretConfiguration> secrets();

   /**
    * The Ensemble configuration for optional additional features.
    */
   EnsembleConfiguration ensemble();

   /**
    * Configuration for Artifacts to load within Microcks container.
    */
   @ConfigGroup
   public interface ArtifactsConfiguration {
      /**
       * This list is for artifacts to load as main or primary ones.
       */
      List<String> primaries();

      /**
       * This list is for artifacts to load as secondary ones.
       */
      Optional<List<String>> secondaries();
   }

   /**
    * Configuration for Secrets to load within Microcks container.
    */
   @ConfigGroup
   public interface SecretConfiguration {
      /**
       * The secret description.
       */
      Optional<String> description();

      /**
       * Optional secret username if basic authentication is used.
       */
      Optional<String> username();
      /**
       * Optional secret password if basic authentication is used.
       */
      Optional<String> password();
      /**
       * Optional secret token if token-based authentication is used.
       */
      Optional<String> token();
      /**
       * Optional secret token header if not using default 'Authorization: Bearer' header form.
       */
      Optional<String> tokenHeader();
   }

   /**
    * Configuration for optional Ensemble features to load within Microcks DevService.
    */
   @ConfigGroup
   public interface EnsembleConfiguration {

      /**
       * Whether we should enable AsyncAPI related features. This will result in the creation and
       * management of a {@code MicrocksContainerEnsemble} with the async features.
       */
      @WithDefault("false")
      boolean asyncEnabled();

      /**
       * The container image name to use for the Microcks Async Minion component.
       * Use an image based on or derived from: {@code quay.io/microcks/microcks-uber-async-minion:latest}.
       */
      @WithDefault(MICROCKS_UBER_ASYNC_MINION_LATEST)
      String asyncImageName();

      /**
       * Whether we should enable Postman testing related features. This will result in the creation and
       * management of a {@code MicrocksContainerEnsemble} with the Postman features.
       */
      @WithDefault("false")
      boolean postmanEnabled();

      /**
       * The container image name to use for the Microcks Postman component.
       * Use an image based on or derived from: {@code quay.io/microcks/microcks-postman-runner:latest}.
       */
      @WithDefault(MICROCKS_POSTMAN_LATEST)
      String postmanImageName();

      /**
       * Is Ensemble has been forced/explicitly enabled?
       * @return Whether one of the feature of the ensemable has been enabled.
       */
      default boolean enabled() {
         return asyncEnabled() || postmanEnabled();
      }
   }
}
