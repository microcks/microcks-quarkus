/*
 * Licensed to Laurent Broudoux (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.github.microcks.quarkus.deployment;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Configuration description for the DevServices of the Microcks Quarkus extension.
 * @author laurent
 */
@ConfigGroup
public interface DevServicesConfig {

   /**
    * If DevServices has been explicitly enabled or disabled. DevServices is generally enabled
    * by default, unless there is an existing configuration present.
    * <p>
    * When DevServices is enabled Quarkus will attempt to automatically configure and start
    * a database when running in Dev or Test mode and when Docker is running.
    */
   @WithDefault("true")
   boolean enabled();

   /**
    * The container image name to use, for container based DevServices providers.
    * Use an image based on or derived from: {@code quay.io/microcks/microcks-uber:latest}.
    */
   Optional<String> imageName();

   /**
    * Optional fixed port the dev service will listen to for Http endpoint.
    * <p>
    * If not defined, the port will be chosen randomly.
    */
   OptionalInt httpPort();

   /**
    * Optional fixed port the dev service will listen to for gRPC endpoint.
    * <p>
    * If not defined, the port will be chosen randomly.
    */
   OptionalInt grpcPort();

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
   @WithDefault("microcks")
   String serviceName();

   /**
    * Environment variables that are passed to the container.
    */
   Map<String, String> containerEnv();

   /**
    * The Artifacts to load withing Microcks container.
    */
   Optional<ArtifactsConfiguration> artifacts();


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
}
