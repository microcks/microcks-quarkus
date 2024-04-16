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

import io.github.microcks.testcontainers.MicrocksContainer;

import io.quarkus.dev.spi.HotReplacementContext;
import io.quarkus.dev.spi.HotReplacementSetup;
import io.quarkus.logging.Log;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.Optional;

/**
 * Hot replacement setup for Microcks Dev Service.
 * It reloads the artifacts files that have been marked as changed.
 * @author laurent
 */
public class ArtifactsHotReplacementSetup implements HotReplacementSetup {

   @Override
   public void setupHotDeployment(HotReplacementContext context) {

      context.consumeNoRestartChanges(files -> {
         // Get Microcks container URL and artifacts from configuration.
         Config globalConfig = ConfigProviderResolver.instance().getConfig();
         String configPrefix = MicrocksProperties.getConfigPrefix("default");
         String microcksContainerUrl = globalConfig.getValue(configPrefix
               + MicrocksProperties.HTTP_SUFFIX, String.class);
         Optional<String> primaryArtifacts = globalConfig.getOptionalValue(configPrefix
               + MicrocksProperties.LOADED_PRIMARY_ARTIFACTS, String.class);
         Optional<String> secondaryArtifacts = globalConfig.getOptionalValue(configPrefix
               + MicrocksProperties.LOADED_SECONDARY_ARTIFACTS, String.class);

         if (Log.isDebugEnabled()) {
            Log.debugf("Microcks container Url for hot replacement: %s", microcksContainerUrl);
            Log.debugf("Microcks primary artifacts: %s", primaryArtifacts);
            Log.debugf("Microcks secondary artifacts: %s", secondaryArtifacts);
            Log.debugf("Changed files: %s", String.join(",", files));
         }

         // Import changed files in Microcks as primary artifacts.
         primaryArtifacts.ifPresent(s -> Arrays.stream(s.split(",")) // Split the comma-separated list
               .filter(files::contains) // Filter out empty strings and files not in the list
               .forEach(file -> {
                  URL fileUrl = Thread.currentThread().getContextClassLoader().getResource(file);
                  importArtifactInMicrocks(microcksContainerUrl, new File(fileUrl.getFile()), true);
               }));

         // Import changed files in Microcks as secondary artifacts.
         secondaryArtifacts.ifPresent(s -> Arrays.stream(s.split(",")) // Split the comma-separated list
               .filter(files::contains) // Filter out empty strings and files not in the list
               .forEach(file -> {
                  URL fileUrl = Thread.currentThread().getContextClassLoader().getResource(file);
                  importArtifactInMicrocks(microcksContainerUrl, new File(fileUrl.getFile()), false);
               }));
      });
   }

   private void importArtifactInMicrocks(String microcksContainerUrl, File artifactFile, boolean mainArtifact) {
      try {
         MicrocksContainer.importArtifact(microcksContainerUrl, artifactFile, mainArtifact);
      } catch (Exception e) {
         Log.errorf("Error while importing artifact %s in Microcks: %s", artifactFile.getName(), e.getMessage());
      }
   }
}
