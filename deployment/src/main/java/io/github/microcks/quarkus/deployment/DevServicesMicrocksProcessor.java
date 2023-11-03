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

import io.github.microcks.quarkus.deployment.DevServicesConfig.ArtifactsConfiguration;
import io.github.microcks.quarkus.deployment.MicrocksBuildTimeConfig.DevServiceConfiguration;
import io.github.microcks.testcontainers.MicrocksContainer;

import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;
import io.quarkus.deployment.builditem.DevServicesSharedNetworkBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.console.ConsoleInstalledBuildItem;
import io.quarkus.deployment.console.StartupLogCompressor;
import io.quarkus.deployment.dev.devservices.GlobalDevServicesConfig;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.devservices.common.ConfigureUtil;
import io.quarkus.devservices.common.ContainerLocator;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;
import io.quarkus.runtime.LaunchMode;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.logging.Logger;
import org.testcontainers.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.quarkus.runtime.LaunchMode.DEVELOPMENT;

/**
 * BuildSteps processor that takes care of starting/registring a Microcks container devservice
 * and its DevUI custom card.
 * @author laurent
 */
@BuildSteps(onlyIfNot = IsNormal.class, onlyIf = { GlobalDevServicesConfig.Enabled.class })
public class DevServicesMicrocksProcessor {

   private static final Logger log = Logger.getLogger(DevServicesMicrocksProcessor.class);
   private static final String MICROCKS_UBER_LATEST = "quay.io/microcks/microcks-uber:latest";
   private static final String MICROCKS_SCHEME = "http://";

   private static final String DEV_SERVICE_NAME = "microcks";
   /**
    * Label to add to shared Dev Service for Microcks running in containers.
    * This allows other applications to discover the running service and use it instead of starting a new instance.
    */
   private static final String DEV_SERVICE_LABEL = "quarkus-dev-service-microcks";

   private static final ContainerLocator microcksContainerLocator = new ContainerLocator(DEV_SERVICE_LABEL, MicrocksContainer.MICROCKS_HTTP_PORT);

   private static final String CONFIG_PREFIX = "quarkus.microcks.";
   private static final String HTTP_SUFFIX = ".http";
   private static final String HTTP_HOST_SUFFIX = ".http.host";
   private static final String HTTP_PORT_SUFFIX = ".http.port";
   private static final String GRPC_SUFFIX = ".grpc";
   private static final String GRPC_HOST_SUFFIX = ".grpc.host";
   private static final String GRPC_PORT_SUFFIX = ".grpc.port";

   /** List of extensions for detecting artifacts to import as primary ones. */
   private static final List<String> PRIMARY_ARTIFACTS_EXTENSIONS = Arrays.asList("-openapi.yml", "-openapi.yaml", "-openapi.json",
         ".proto", ".graphql", "-asyncapi.yml", "-asyncapi.yaml", "-asyncapi.json", "-soapui-project.xml");
   /** List of extensions for detecting artifacts to import as secondary ones. */
   private static final List<String> SECONDARY_ARTIFACTS_EXTENSIONS = Arrays.asList("postman-collection.json", "postman_collection.json",
         "-metadata.yml", "-metadata.yaml", ".har");

   private static volatile DevServiceConfiguration capturedDevServicesConfig;
   private static volatile List<RunningDevService> devServices;
   private static volatile Map<RunningDevService, MicrocksContainer> devServiceMicrocksContainerMap;
   private static volatile boolean first = true;

   /**
    * Start one (or many in the future) MicrocksContainer(s) depending on extension configuration.
    * We also take care of locating an re-using existing container if configured in shared modeL
    */
   @BuildStep
   public List<DevServicesResultBuildItem> startMicrocksContainers(LaunchModeBuildItem launchMode,
         DockerStatusBuildItem dockerStatusBuildItem,
         List<DevServicesSharedNetworkBuildItem> devServicesSharedNetworkBuildItem,
         MicrocksBuildTimeConfig config,
         Optional<ConsoleInstalledBuildItem> consoleInstalledBuildItem,
         CuratedApplicationShutdownBuildItem closeBuildItem,
         CurateOutcomeBuildItem outcomeBuildItem,
         LoggingSetupBuildItem loggingSetupBuildItem,
         GlobalDevServicesConfig devServicesConfig) {

      // Retrieve DevServices config. Only manage a default one at the moment.
      DevServiceConfiguration currentDevServicesConfig = config.defaultDevService();

      // Figure out if we need to shut down and restart existing microcks containers
      // if not and the microcks containers have already started we just return
      if (devServices != null) {
         boolean restartRequired = !currentDevServicesConfig.equals(capturedDevServicesConfig);
         if (!restartRequired) {
            return devServices.stream().map(RunningDevService::toBuildItem).collect(Collectors.toList());
         }
         for (Closeable closeable : devServices) {
            try {
               closeable.close();
            } catch (Throwable e) {
               log.error("Failed to stop microcks container", e);
            }
         }
         devServices = null;
         capturedDevServicesConfig = null;
         devServiceMicrocksContainerMap = null;
      }

      // Re-initialize captured config and dev services.
      capturedDevServicesConfig = currentDevServicesConfig;
      List<RunningDevService> newDevServices = new ArrayList<>();
      devServiceMicrocksContainerMap = new HashMap<>();

      StartupLogCompressor compressor = new StartupLogCompressor(
            (launchMode.isTest() ? "(test) " : "") + "Microcks Dev Services Starting:", consoleInstalledBuildItem,
            loggingSetupBuildItem);
      try {
         RunningDevService devService = startContainer(currentDevServicesConfig.devservices(), dockerStatusBuildItem,
               launchMode.getLaunchMode(), outcomeBuildItem, !devServicesSharedNetworkBuildItem.isEmpty(), devServicesConfig.timeout);

         if (devService == null) {
            compressor.closeAndDumpCaptured();
         } else {
            compressor.close();
            newDevServices.add(devService);
            String configKey = CONFIG_PREFIX + "default" + HTTP_SUFFIX;
            log.infof("The '%s' microcks container is ready on %s", "default", devService.getConfig().get(configKey));
         }
      } catch (Throwable t) {
         compressor.closeAndDumpCaptured();
         throw new RuntimeException(t);
      }

      // Save started Dev Services and containers.s
      devServices = newDevServices;

      if (first) {
         first = false;
         // Add close tasks on first run only.
         Runnable closeTask = () -> {
            if (devServices != null) {
               for (Closeable closeable : devServices) {
                  try {
                     closeable.close();
                  } catch (Throwable t) {
                     log.error("Failed to stop microcks", t);
                  }
               }
            }
            first = true;
            devServices = null;
            capturedDevServicesConfig = null;
         };
         closeBuildItem.addCloseTask(closeTask, true);
      }

      return devServices.stream().map(RunningDevService::toBuildItem).collect(Collectors.toList());
   }

   /**
    * Customize the extension card in DevUI with a link to running Microcks containers UI.
    */
   @BuildStep(onlyIf = IsDevelopment.class)
   public CardPageBuildItem pages(List<DevServicesResultBuildItem> devServicesResultBuildItems) {
      CardPageBuildItem cardPageBuildItem = new CardPageBuildItem();

      String microcksUIUrl = null;
      if (!devServices.isEmpty()) {
         microcksUIUrl = devServices.get(0).getConfig().get(CONFIG_PREFIX + "default" + HTTP_SUFFIX);
      }

      if (microcksUIUrl != null) {
         cardPageBuildItem.addPage(Page.externalPageBuilder("Microcks UI")
               .url(microcksUIUrl)
               .isHtmlContent()
               .icon("font-awesome-solid:plug-circle-bolt"));
      }

      return cardPageBuildItem;
   }

   private RunningDevService startContainer(DevServicesConfig devServicesConfig, DockerStatusBuildItem dockerStatusBuildItem,
                                            LaunchMode launchMode, CurateOutcomeBuildItem outcomeBuildItem, boolean useSharedNetwork, Optional<Duration> timeout) {
      if (!devServicesConfig.enabled()) {
         // explicitly disabled
         log.debug("Not starting devservices for Microcks as it has been disabled in the config");
         return null;
      }

      if (!dockerStatusBuildItem.isDockerAvailable()) {
         log.warn("Please configure quarkus.microcks.hosts or get a working docker instance");
         return null;
      }

      DockerImageName dockerImageName = DockerImageName.parse(devServicesConfig.imageName().orElse(MICROCKS_UBER_LATEST))
            .asCompatibleSubstituteFor(MICROCKS_UBER_LATEST);

      Supplier<RunningDevService> defaultMicrocksSupplier = () -> {
         MicrocksContainer microcksContainer = new MicrocksContainer(dockerImageName);

         // Configure access to host - getting test-port from config or defaulting to 8081.
         microcksContainer.withAccessToHost(true);
         Config globalConfig = ConfigProviderResolver.instance().getConfig();
         int testPort = globalConfig.getValue("quarkus.http.test-port", OptionalInt.class)
               .orElse(8081);

         if (testPort > 0) {
            Testcontainers.exposeHostPorts(testPort);
         }

         // Add envs and timeout if provided.
         microcksContainer.withEnv(devServicesConfig.containerEnv());
         timeout.ifPresent(microcksContainer::withStartupTimeout);

         // Finalize label and shared network.
         if (launchMode == DEVELOPMENT) {
            microcksContainer.withLabel(DEV_SERVICE_LABEL, devServicesConfig.serviceName());
         }
         String hostName = null;
         if (useSharedNetwork) {
            hostName = ConfigureUtil.configureSharedNetwork(microcksContainer, DEV_SERVICE_NAME);
         }
         microcksContainer.start();

         // Now importing artifacts into running container.
         if (devServicesConfig.artifacts().isPresent()) {
            try {
               ArtifactsConfiguration artifactsConfig = devServicesConfig.artifacts().get();
               for (String primaryArtifact : artifactsConfig.primaries()) {
                  log.infof("Load '%s' as primary artifact", primaryArtifact);
                  microcksContainer.importAsMainArtifact(new File(primaryArtifact));
               }
               if (artifactsConfig.secondaries().isPresent()) {
                  for (String secondaryArtifact : artifactsConfig.secondaries().get()) {
                     log.infof("Load '%s' as secondary artifact", secondaryArtifact);
                     microcksContainer.importAsSecondaryArtifact(new File(secondaryArtifact));
                  }
               }
            } catch (Exception e) {
               log.error("Failed to load Artifacts in microcks", e);
            }
         } else {
            try {
               boolean found = scanAndLoadPrimaryArtifacts(microcksContainer, outcomeBuildItem);
               // Continue with secondary artifacts only if we found something.
               if (found) scanAndLoadSecondaryArtifacts(microcksContainer, outcomeBuildItem);
            } catch (Exception e) {
               log.error("Failed to load Artifacts in microcks", e);
            }
         }

         // Build the Microcks Host URL + gRPC to put in properties.
         String visiblehost = (hostName != null ? hostName : microcksContainer.getHost());
         String microcksHttpHost = MICROCKS_SCHEME + visiblehost + ":" + microcksContainer.getMappedPort(MicrocksContainer.MICROCKS_HTTP_PORT);
         String microcksGrpcHost = MICROCKS_SCHEME + visiblehost + ":" + microcksContainer.getMappedPort(MicrocksContainer.MICROCKS_GRPC_PORT);

         RunningDevService devService = new RunningDevService(DEV_SERVICE_NAME, microcksContainer.getContainerId(), microcksContainer::close,
               Map.of(CONFIG_PREFIX + "default" + HTTP_SUFFIX, microcksHttpHost,
                     CONFIG_PREFIX + "default" + HTTP_HOST_SUFFIX, visiblehost,
                     CONFIG_PREFIX + "default" + HTTP_PORT_SUFFIX, microcksContainer.getMappedPort(MicrocksContainer.MICROCKS_HTTP_PORT).toString(),
                     CONFIG_PREFIX + "default" + GRPC_SUFFIX, microcksGrpcHost,
                     CONFIG_PREFIX + "default" + GRPC_HOST_SUFFIX, visiblehost,
                     CONFIG_PREFIX + "default" + GRPC_PORT_SUFFIX, microcksContainer.getMappedPort(MicrocksContainer.MICROCKS_GRPC_PORT).toString()));
         devServiceMicrocksContainerMap.put(devService, microcksContainer);

         return devService;
      };

      return microcksContainerLocator.locateContainer(devServicesConfig.serviceName(), devServicesConfig.shared(), launchMode)
            .map(containerAddress -> {
               String microcksUrl = MICROCKS_SCHEME + containerAddress.getUrl();
               return new RunningDevService(DEV_SERVICE_NAME, containerAddress.getId(), null, CONFIG_PREFIX + "default" + HTTP_SUFFIX, microcksUrl);
            })
            .orElseGet(defaultMicrocksSupplier);
   }

   private boolean scanAndLoadPrimaryArtifacts(MicrocksContainer microcksContainer, CurateOutcomeBuildItem outcomeBuildItem) throws Exception {
      return scanAndLoadArtifacts(microcksContainer, outcomeBuildItem, PRIMARY_ARTIFACTS_EXTENSIONS, true);
   }

   private boolean scanAndLoadSecondaryArtifacts(MicrocksContainer microcksContainer, CurateOutcomeBuildItem outcomeBuildItem) throws Exception {
      return scanAndLoadArtifacts(microcksContainer, outcomeBuildItem, SECONDARY_ARTIFACTS_EXTENSIONS, false);
   }

   private boolean scanAndLoadArtifacts(MicrocksContainer microcksContainer, CurateOutcomeBuildItem outcomeBuildItem,
                                        List<String> validSuffixes, boolean primary) throws Exception {
      boolean foundSomething = false;
      Collection<SourceDir> resourceDirs = outcomeBuildItem.getApplicationModel().getApplicationModule().getMainSources().getResourceDirs();
      resourceDirs.addAll(outcomeBuildItem.getApplicationModel().getApplicationModule().getTestSources().getResourceDirs());
      for (SourceDir resourceDir : resourceDirs) {
         Set<String> filesPaths = collectFiles(resourceDir.getDir(), validSuffixes);
         for (String filePath : filesPaths) {
            if (primary) {
               log.infof("Load '%s' as primary artifact", filePath);
               microcksContainer.importAsMainArtifact(new File(filePath));
            } else {
               log.infof("Load '%s' as secondary artifact", filePath);
               microcksContainer.importAsSecondaryArtifact(new File(filePath));
            }
            foundSomething = true;
         }
      }
      return foundSomething;
   }

   private Set<String> collectFiles(Path dir, List<String> validSuffixes) throws IOException {
      try (Stream<Path> stream = Files.walk(dir, 2)) {
         return stream
               .filter(Files::isRegularFile)
               .map(Path::toString)
               .filter(candidate -> endsWithOneOf(candidate, validSuffixes))
               .collect(Collectors.toSet());
      }
   }

   private boolean endsWithOneOf(String candidate, List<String> validSuffixes) {
      for (String validSuffix : validSuffixes) {
         if (candidate.endsWith(validSuffix)) {
            return true;
         }
      }
      return false;
   }
}
