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

import static io.quarkus.runtime.LaunchMode.DEVELOPMENT;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.logging.Logger;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.Base58;
import org.testcontainers.utility.DockerImageName;

import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.builder.item.EmptyBuildItem;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.annotations.Consume;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.DevServicesLauncherConfigResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;
import io.quarkus.deployment.builditem.DevServicesSharedNetworkBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.console.ConsoleInstalledBuildItem;
import io.quarkus.deployment.console.StartupLogCompressor;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.devservices.common.ConfigureUtil;
import io.quarkus.devservices.common.ContainerLocator;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;
import io.quarkus.runtime.LaunchMode;

import io.github.microcks.quarkus.deployment.DevServicesConfig.ArtifactsConfiguration;
import io.github.microcks.quarkus.deployment.MicrocksBuildTimeConfig.DevServiceConfiguration;
import io.github.microcks.quarkus.runtime.MicrocksProperties;
import io.github.microcks.quarkus.runtime.MicrocksRecorder;
import io.github.microcks.testcontainers.MicrocksAsyncMinionContainer;
import io.github.microcks.testcontainers.MicrocksContainer;
import io.github.microcks.testcontainers.connection.KafkaConnection;

/**
 * BuildSteps processor that takes care of starting/registering a Microcks container devservice
 * and its DevUI custom card.
 * @author laurent
 */
@BuildSteps(onlyIfNot = IsNormal.class, onlyIf = { io.quarkus.deployment.dev.devservices.DevServicesConfig.Enabled.class })
public class DevServicesMicrocksProcessor {

   private static final Logger log = Logger.getLogger(DevServicesMicrocksProcessor.class);

   private static final String MICROCKS = "microcks";
   private static final String HTTP_SCHEME = "http://";

   /**
    * Label to add to shared Dev Service for Microcks running in containers.
    * This allows other applications to discover the running service and use it instead of starting a new instance.
    */
   private static final String DEV_SERVICE_LABEL = "quarkus-dev-service-" + MICROCKS;

   private static final ContainerLocator microcksContainerLocator = new ContainerLocator(DEV_SERVICE_LABEL, MicrocksContainer.MICROCKS_HTTP_PORT);
   private static final ContainerLocator microcksContainerLocatorForGRPC = new ContainerLocator(DEV_SERVICE_LABEL, MicrocksContainer.MICROCKS_GRPC_PORT);

   /** List of extensions for detecting artifacts to import as primary ones. */
   private static final List<String> PRIMARY_ARTIFACTS_EXTENSIONS = Arrays.asList("-openapi.yml", "-openapi.yaml", "-openapi.json",
         ".proto", ".graphql", "-asyncapi.yml", "-asyncapi.yaml", "-asyncapi.json", "-soapui-project.xml");
   /** List of extensions for detecting artifacts to import as secondary ones. */
   private static final List<String> SECONDARY_ARTIFACTS_EXTENSIONS = Arrays.asList("postman-collection.json", "postman_collection.json",
         "-metadata.yml", "-metadata.yaml", "-examples.yml", "-examples.yaml", ".har");
   /** List of extensions corresponding to Postman collection artifacts. */
   private static final List<String> POSTMAN_COLLECTION_EXTENSIONS = Arrays.asList("postman-collection.json", "postman_collection.json");


   private static volatile List<RunningDevService> devServices;
   private static volatile DevServiceConfiguration capturedDevServicesConfig;
   private static volatile boolean first = true;
   private static volatile MicrocksContainersEnsembleHosts ensembleHosts;
   private static volatile boolean aPostmanCollectionIsPresent = false;

   /** An empty build item triggering the end of Microcks ensemble build process. */
   public static final class MicrocksEnsembleBuildItem extends EmptyBuildItem {}

   /**
    * Prepare a Shared Network for Microcks containers and others (like Kafka) if enabled.
    */
   @BuildStep
   public Optional<DevServicesSharedNetworkBuildItem> prepareSharedNetwork(MicrocksBuildTimeConfig config) {
      // Retrieve DevServices config. Only manage a default one at the moment.
      DevServiceConfiguration devServicesConfiguration = config.defaultDevService();

      if (!devServicesConfiguration.devservices().enabled()) {
         // Explicitly disabled
         log.debug("Not preparing a shared network as Microcks devservices has been disabled in the config");
         return Optional.empty();
      }
      return Optional.of(new DevServicesSharedNetworkBuildItem());
   }

   /**
    * Start one (or many in the future) MicrocksContainer(s) depending on extension configuration.
    * We also take care of locating and re-using existing container if configured in shared modeL
    */
   @BuildStep
   public List<DevServicesResultBuildItem> startMicrocksContainers(LaunchModeBuildItem launchMode,
         DockerStatusBuildItem dockerStatusBuildItem,
         MicrocksBuildTimeConfig config,
         Optional<ConsoleInstalledBuildItem> consoleInstalledBuildItem,
         CuratedApplicationShutdownBuildItem closeBuildItem,
         CurateOutcomeBuildItem outcomeBuildItem,
         LoggingSetupBuildItem loggingSetupBuildItem,
         io.quarkus.deployment.dev.devservices.DevServicesConfig devServicesConfig) {

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
      }

      // Re-initialize captured config and dev services.
      capturedDevServicesConfig = currentDevServicesConfig;
      List<RunningDevService> newDevServices = new ArrayList<>();

      StartupLogCompressor compressor = new StartupLogCompressor(
            (launchMode.isTest() ? "(test) " : "") + "Microcks Dev Services Starting:", consoleInstalledBuildItem,
            loggingSetupBuildItem);
      try {
         RunningDevService devService = startContainer(currentDevServicesConfig.devservices(), dockerStatusBuildItem,
               launchMode.getLaunchMode(), outcomeBuildItem, devServicesConfig.timeout());

         if (devService == null) {
            compressor.closeAndDumpCaptured();
         } else {
            compressor.close();
            newDevServices.add(devService);
            String configKey = MicrocksProperties.getConfigPrefix(currentDevServicesConfig.devservices().serviceName())
                  + MicrocksProperties.HTTP_SUFFIX;
            log.infof("The '%s' microcks container is ready on %s", currentDevServicesConfig.devservices().serviceName(),
                  devService.getConfig().get(configKey));
         }
      } catch (Throwable t) {
         compressor.closeAndDumpCaptured();
         throw new RuntimeException(t);
      }

      // Save started Dev Services.
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
    * Depending on other started dev services, complement the MicrocksContainer with some other forming an Ensemble.
    */
   @BuildStep
   @Produce(MicrocksEnsembleBuildItem.class)
   public void completeMicrocksEnsemble(MicrocksBuildTimeConfig config, DevServicesLauncherConfigResultBuildItem devServicesConfigResult,
         CuratedApplicationShutdownBuildItem closeBuildItem) {

      // ensembleHosts may be null if container has not been started
      // (If devservices are disabled or we couldn't locate existing containers)
      if (ensembleHosts != null) {
         String microcksHost = ensembleHosts.getMicrocksHost();

         boolean aBrokerIsPresent = false;
         String kafkaBootstrapServers = null;

         for (Map.Entry configEntry : devServicesConfigResult.getConfig().entrySet()) {
            log.debugf("DevServices config: %s=%s", configEntry.getKey(), configEntry.getValue());
            if ("kafka.bootstrap.servers".equals(configEntry.getKey())) {
               kafkaBootstrapServers = configEntry.getValue().toString();
               aBrokerIsPresent = true;
            }
         }

         // Get the ensemble configuration.
         DevServicesConfig devServiceConfig = config.defaultDevService().devservices();
         DevServicesConfig.EnsembleConfiguration ensembleConfiguration = devServiceConfig.ensemble();

         if (ensembleConfiguration.postmanEnabled() || aPostmanCollectionIsPresent) {
            log.debugf("Starting a GenericContainer with Postman image '%s'", ensembleConfiguration.postmanImageName());

            // Force compatibility of configured image.
            DockerImageName imageName = DockerImageName.parse(ensembleConfiguration.postmanImageName())
                  .asCompatibleSubstituteFor(DevServicesConfig.MICROCKS_UBER_ASYNC_MINION_LATEST);

            // We've got the conditions for launching a new GenericContainer with Postman !
            GenericContainer<?> postmanContainer = new GenericContainer<>(imageName)
                  .withNetwork(Network.SHARED)
                  .withNetworkAliases(ensembleHosts.getPostmanHost())
                  .withAccessToHost(true)
                  .waitingFor(Wait.forLogMessage(".*postman-runtime wrapper listening on port.*", 1));

            postmanContainer.start();

            closeBuildItem.addCloseTask(postmanContainer::stop, true);
         }

         if (ensembleConfiguration.asyncEnabled() || aBrokerIsPresent) {
            log.debugf("Starting a MicrocksAsyncMinionContainer with image '%s'", ensembleConfiguration.asyncImageName());

            // Force compatibility of configured image.
            DockerImageName imageName = DockerImageName.parse(ensembleConfiguration.asyncImageName())
                  .asCompatibleSubstituteFor(DevServicesConfig.MICROCKS_UBER_ASYNC_MINION_LATEST);

            // We've got the conditions for launching a new MicrocksAsyncMinionContainer !
            MicrocksAsyncMinionContainer asyncMinionContainer = new MicrocksAsyncMinionContainer(Network.SHARED,
                  imageName, microcksHost).withAccessToHost(true);

            // Configure connection to a Kafka broker if any.
            if (kafkaBootstrapServers != null) {
               if (kafkaBootstrapServers.contains(",")) {
                  String[] kafkaAddresses = kafkaBootstrapServers.split(",");
                  for (String kafkaAddress : kafkaAddresses) {
                     if (kafkaAddress.startsWith("PLAINTEXT://")) {
                        kafkaBootstrapServers = kafkaAddress.replace("PLAINTEXT://", "");
                     }
                  }
               }

               log.debugf("Adding a KafkaConnection to '%s' for MicrocksAsyncMinionContainer", kafkaBootstrapServers);
               asyncMinionContainer.withKafkaConnection(new KafkaConnection(
                     kafkaBootstrapServers.replace("localhost", GenericContainer.INTERNAL_HOST_HOSTNAME)));
            }

            // Update network aliases with ensembleHosts before starting it.
            List<String> aliases = asyncMinionContainer.getNetworkAliases();
            aliases.add(ensembleHosts.getAsyncMinionHost());
            asyncMinionContainer.setNetworkAliases(aliases);
            asyncMinionContainer.start();

            closeBuildItem.addCloseTask(asyncMinionContainer::stop, true);
         }
      }
   }

   /**
    * Finalize configuration by writing it to a Recorder.
    */
   @BuildStep
   @Record(ExecutionTime.RUNTIME_INIT)
   @Consume(MicrocksEnsembleBuildItem.class)
   public void finalizeMicrocksEnsemble(MicrocksRecorder recorder) {
      recorder.record();
   }

   /**
    * Customize the extension card in DevUI with a link to running Microcks containers UI.
    */
   @BuildStep(onlyIf = IsDevelopment.class)
   public CardPageBuildItem pages(List<DevServicesResultBuildItem> devServicesResultBuildItems, MicrocksBuildTimeConfig config) {
      CardPageBuildItem cardPageBuildItem = new CardPageBuildItem();

      String microcksUIUrl = null;
      String serviceName = config.defaultDevService().devservices().serviceName();
      if (!devServices.isEmpty() && serviceName != null) {
         microcksUIUrl = devServices.get(0).getConfig().get(MicrocksProperties.CONFIG_PREFIX + serviceName + MicrocksProperties.HTTP_SUFFIX);
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
                                            LaunchMode launchMode, CurateOutcomeBuildItem outcomeBuildItem, Optional<Duration> timeout) {
      if (!devServicesConfig.enabled()) {
         // explicitly disabled
         log.info("Not starting devservices for Microcks as it has been disabled in the config");
         return null;
      }

      if (!dockerStatusBuildItem.isContainerRuntimeAvailable()) {
         log.warn("Please configure quarkus.microcks.hosts or get a working docker instance");
         return null;
      }

      DockerImageName dockerImageName = DockerImageName.parse(devServicesConfig.imageName())
            .asCompatibleSubstituteFor(DevServicesConfig.MICROCKS_UBER_LATEST);

      Supplier<RunningDevService> defaultMicrocksSupplier = () -> {
         MicrocksContainer microcksContainer = new MicrocksContainer(dockerImageName);

         // Configure access to host - getting test-port from config or defaulting to 8081.
         microcksContainer.withAccessToHost(true);
         Config globalConfig = ConfigProviderResolver.instance().getConfig();
         int testPort = globalConfig.getValue("quarkus.http.test-port", OptionalInt.class).orElse(8081);

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

         // Always launch microcks in a shared network to be able to access possible ensemble containers.
         String microcksHost = ConfigureUtil.configureSharedNetwork(microcksContainer, devServicesConfig.serviceName());

         // Build and store configuration for possible other hosts of the ensemble.
         String postmanHost = String.format("%s-%s-%s-%s", MICROCKS,
               devServicesConfig.serviceName(), "postman", Base58.randomString(5));
         String asyncMinionHost = String.format("%s-%s-%s-%s", MICROCKS,
               devServicesConfig.serviceName(), "async-minion", Base58.randomString(5));
         ensembleHosts = new MicrocksContainersEnsembleHosts(microcksHost, postmanHost, asyncMinionHost);

         // Set the required environment variables for running as an Ensemble.
         microcksContainer.withEnv("POSTMAN_RUNNER_URL", HTTP_SCHEME + postmanHost + ":3000")
               .withEnv("TEST_CALLBACK_URL", HTTP_SCHEME + microcksHost + ":" + MicrocksContainer.MICROCKS_HTTP_PORT)
               .withEnv("ASYNC_MINION_URL", HTTP_SCHEME + asyncMinionHost + ":" + MicrocksAsyncMinionContainer.MICROCKS_ASYNC_MINION_HTTP_PORT);

         microcksContainer.start();

         // Now importing artifacts into running container.
         LoadedArtifacts loadedArtifacts = initializeArtifacts(microcksContainer, devServicesConfig, outcomeBuildItem);

         return new RunningDevService(devServicesConfig.serviceName(), microcksContainer.getContainerId(), microcksContainer::close,
               getDevServiceExposedConfig(devServicesConfig.serviceName(), "localhost",
                     microcksContainer.getMappedPort(MicrocksContainer.MICROCKS_HTTP_PORT),
                     microcksContainer.getMappedPort(MicrocksContainer.MICROCKS_GRPC_PORT),
                     loadedArtifacts)
         );
      };

      return microcksContainerLocator.locateContainer(devServicesConfig.serviceName(), devServicesConfig.shared(), launchMode)
            .map(containerAddress -> microcksContainerLocatorForGRPC.locateContainer(devServicesConfig.serviceName(), devServicesConfig.shared(), launchMode)
                  .map(containerAddressForGRPC -> new RunningDevService(devServicesConfig.serviceName(), containerAddress.getId(), null,
                        getDevServiceExposedConfig(devServicesConfig.serviceName(), containerAddress.getHost(), containerAddress.getPort(), containerAddressForGRPC.getPort())))
                  .orElseGet(defaultMicrocksSupplier))
            .orElseGet(defaultMicrocksSupplier);
   }

   private Map<String, String> getDevServiceExposedConfig(String serviceName, String visibleHostName, Integer httpPort, Integer grpcPort) {
      String configPrefix = MicrocksProperties.getConfigPrefix(serviceName);

      return Map.of(
            configPrefix + MicrocksProperties.HTTP_SUFFIX, HTTP_SCHEME + visibleHostName + ":" + httpPort.toString(),
            configPrefix + MicrocksProperties.HTTP_HOST_SUFFIX, visibleHostName,
            configPrefix + MicrocksProperties.HTTP_PORT_SUFFIX, httpPort.toString(),
            configPrefix + MicrocksProperties.GRPC_SUFFIX, HTTP_SCHEME + visibleHostName + ":" + grpcPort.toString(),
            configPrefix + MicrocksProperties.GRPC_HOST_SUFFIX, visibleHostName,
            configPrefix + MicrocksProperties.GRPC_PORT_SUFFIX, grpcPort.toString());
   }

   private Map<String, String> getDevServiceExposedConfig(String serviceName, String visibleHostName, Integer httpPort,
                                                          Integer grpcPort, LoadedArtifacts loadedArtifacts) {
      String configPrefix = MicrocksProperties.getConfigPrefix(serviceName);

      return Map.of(
            configPrefix + MicrocksProperties.HTTP_SUFFIX, HTTP_SCHEME + visibleHostName + ":" + httpPort.toString(),
            configPrefix + MicrocksProperties.HTTP_HOST_SUFFIX, visibleHostName,
            configPrefix + MicrocksProperties.HTTP_PORT_SUFFIX, httpPort.toString(),
            configPrefix + MicrocksProperties.GRPC_SUFFIX, HTTP_SCHEME + visibleHostName + ":" + grpcPort.toString(),
            configPrefix + MicrocksProperties.GRPC_HOST_SUFFIX, visibleHostName,
            configPrefix + MicrocksProperties.GRPC_PORT_SUFFIX, grpcPort.toString(),
            configPrefix + MicrocksProperties.LOADED_PRIMARY_ARTIFACTS, String.join(",", loadedArtifacts.primaryArtifacts),
            configPrefix + MicrocksProperties.LOADED_SECONDARY_ARTIFACTS, String.join(",", loadedArtifacts.secondaryArtifacts));
   }

   private LoadedArtifacts initializeArtifacts(MicrocksContainer microcksContainer, DevServicesConfig devServicesConfig, CurateOutcomeBuildItem outcomeBuildItem) {
      LoadedArtifacts loadedArtifacts = new LoadedArtifacts();

      // First, load the remote artifacts if any.
      if (devServicesConfig.remoteArtifacts().isPresent()) {
         ArtifactsConfiguration remoteArtifactsConfig = devServicesConfig.remoteArtifacts().get();
         try {
            for (String remoteArtifactUrl : remoteArtifactsConfig.primaries()) {
               log.infof("Load '%s' as primary remote artifact", remoteArtifactUrl);
               microcksContainer.downloadAsMainRemoteArtifact(remoteArtifactUrl);
            }
            if (remoteArtifactsConfig.secondaries().isPresent()) {
               for (String remoteArtifactUrl : remoteArtifactsConfig.secondaries().get()) {
                  log.infof("Load '%s' as secondary remote artifact", remoteArtifactUrl);
                  microcksContainer.downloadAsSecondaryRemoteArtifact(remoteArtifactUrl);
               }
            }
         } catch (Exception e) {
            log.error("Failed to load Remote Artifacts in microcks", e);
         }
      }
      // Then, load or scan the local artifacts if any.
      if (devServicesConfig.artifacts().isPresent()) {
         ArtifactsConfiguration artifactsConfig = devServicesConfig.artifacts().get();
         try {
            for (String primaryArtifact : artifactsConfig.primaries()) {
               log.infof("Load '%s' as primary artifact", primaryArtifact);
               addToLoadedArtifacts(primaryArtifact, loadedArtifacts, true);
               microcksContainer.importAsMainArtifact(new File(primaryArtifact));
            }
            if (artifactsConfig.secondaries().isPresent()) {
               for (String secondaryArtifact : artifactsConfig.secondaries().get()) {
                  log.infof("Load '%s' as secondary artifact", secondaryArtifact);
                  addToLoadedArtifacts(secondaryArtifact, loadedArtifacts, false);
                  microcksContainer.importAsSecondaryArtifact(new File(secondaryArtifact));
               }
            }
         } catch (Exception e) {
            log.error("Failed to load Artifacts in microcks", e);
         }
      } else {
         try {
            loadedArtifacts.primaryArtifacts = scanAndLoadPrimaryArtifacts(microcksContainer, outcomeBuildItem);
            // Continue with secondary artifacts only if we found something.
            if (!loadedArtifacts.primaryArtifacts.isEmpty()) {
               loadedArtifacts.secondaryArtifacts = scanAndLoadSecondaryArtifacts(microcksContainer, outcomeBuildItem);
            }
         } catch (Exception e) {
            log.error("Failed to load Artifacts in microcks", e);
         }
      }
      return loadedArtifacts;
   }

   private void addToLoadedArtifacts(String artifact, LoadedArtifacts loadedArtifacts, boolean primary) {
      String targetName = artifact;
      if (artifact.contains("target/classes/")) {
         targetName = artifact.substring(artifact.indexOf("target/classes/") + "target/classes/".length() + 1);
      } else if (artifact.contains("target/test-classes")) {
         targetName = artifact.substring(artifact.indexOf("target/test-classes/") + "target/test-classes/".length() + 1);
      }

      if (primary) {
         loadedArtifacts.primaryArtifacts.add(targetName);
      } else {
         loadedArtifacts.secondaryArtifacts.add(targetName);
      }
   }

   private List<String> scanAndLoadPrimaryArtifacts(MicrocksContainer microcksContainer, CurateOutcomeBuildItem outcomeBuildItem) throws Exception {
      return scanAndLoadArtifacts(microcksContainer, outcomeBuildItem, PRIMARY_ARTIFACTS_EXTENSIONS, true);
   }

   private List<String> scanAndLoadSecondaryArtifacts(MicrocksContainer microcksContainer, CurateOutcomeBuildItem outcomeBuildItem) throws Exception {
      return scanAndLoadArtifacts(microcksContainer, outcomeBuildItem, SECONDARY_ARTIFACTS_EXTENSIONS, false);
   }

   private List<String> scanAndLoadArtifacts(MicrocksContainer microcksContainer, CurateOutcomeBuildItem outcomeBuildItem,
                                        List<String> validSuffixes, boolean primary) throws Exception {
      List<String> loadedArtifacts = new ArrayList<>();
      List<SourceDir> resourceDirs = new ArrayList<>();
      resourceDirs.addAll(outcomeBuildItem.getApplicationModel().getApplicationModule().getMainSources().getResourceDirs());
      resourceDirs.addAll(outcomeBuildItem.getApplicationModel().getApplicationModule().getTestSources().getResourceDirs());

      Map<File, String> filesAndRelativePath = new HashMap<>();
      // Extract all the files and their relative path from resource dir.
      // This path is the one that will be used for hot reloading so we should compute it now.
      for (SourceDir resourceDir : resourceDirs) {
         filesAndRelativePath.putAll(collectFilesAndRelativePaths(resourceDir.getDir(), validSuffixes));
      }

      for (Map.Entry<File, String> entry : filesAndRelativePath.entrySet()) {
         // Record loaded even if import will fail. That way, it will be
         // reloaded by the Hot replacement when fixed.
         loadedArtifacts.add(entry.getValue());
         if (primary) {
            log.infof("Load '%s' as primary artifact", entry.getKey().getName());
            microcksContainer.importAsMainArtifact(entry.getKey());
         } else {
            log.infof("Load '%s' as secondary artifact", entry.getKey().getName());
            microcksContainer.importAsSecondaryArtifact(entry.getKey());
         }
      }
      return loadedArtifacts;
   }

   private Map<File, String> collectFilesAndRelativePaths(Path dir, List<String> validSuffixes) throws IOException {
      Map<File, String> filesPaths = new HashMap<>();
      if (Files.isDirectory(dir)) {
         try (Stream<Path> stream = Files.walk(dir, 2)) {
            stream.filter(Files::isRegularFile)
                  .map(Path::toFile)
                  .filter(candidate -> endsWithOneOf(candidate.getName(), validSuffixes))
                  .forEach(file -> filesPaths.put(file, dir.relativize(file.toPath()).toString()));
         }
      }
      return filesPaths;
   }

   private static boolean endsWithOneOf(String candidate, List<String> validSuffixes) {
      for (String validSuffix : validSuffixes) {
         if (candidate.endsWith(validSuffix)) {
            if (isAPostmanCollection(candidate)) {
               aPostmanCollectionIsPresent = true;
            }
            return true;
         }
      }
      return false;
   }

   private static boolean isAPostmanCollection(String candidate) {
      for (String postmanSuffix : POSTMAN_COLLECTION_EXTENSIONS) {
         if (candidate.endsWith(postmanSuffix)) {
            return true;
         }
      }
      return false;
   }

   /** A simple class to keep track of loaded artifacts. */
   static class LoadedArtifacts {
      List<String> primaryArtifacts = new ArrayList<>();
      List<String> secondaryArtifacts = new ArrayList<>();
   }
}
