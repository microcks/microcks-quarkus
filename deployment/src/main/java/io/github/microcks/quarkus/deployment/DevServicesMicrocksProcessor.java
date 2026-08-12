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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.Supplier;

import io.github.microcks.quarkus.deployment.MicrocksDevServicesConfig.ArtifactsConfiguration;
import io.github.microcks.quarkus.runtime.MicrocksJsonRPCService;
import io.github.microcks.quarkus.runtime.MicrocksProperties;
import io.github.microcks.testcontainers.MicrocksAsyncMinionContainer;
import io.github.microcks.testcontainers.MicrocksContainer;
import io.github.microcks.testcontainers.connection.KafkaConnection;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.IsDevServicesSupportedByLaunchMode;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.IsLocalDevelopment;
import io.quarkus.deployment.IsProduction;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesSharedNetworkBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.Startable;
import io.quarkus.deployment.dev.devservices.DevServicesConfig;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.devservices.common.ConfigureUtil;
import io.quarkus.devservices.common.ContainerAddress;
import io.quarkus.devservices.common.ContainerLocator;
import io.quarkus.devui.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;
import io.quarkus.runtime.LaunchMode;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.logging.Logger;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.Base58;
import org.testcontainers.utility.DockerImageName;

import static io.quarkus.runtime.LaunchMode.DEVELOPMENT;

/**
 * BuildSteps processor that takes care of starting/registering a Microcks container devservice
 * and its DevUI custom card.
 *
 * @author laurent
 */
@BuildSteps(onlyIfNot = IsProduction.class,
      onlyIf = {IsDevServicesSupportedByLaunchMode.class, DevServicesConfig.Enabled.class})
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

   public static final String KAFKA_BOOTSTRAP_SERVERS = "kafka.bootstrap.servers";


   /**
    * Prepare a Shared Network for Microcks containers and others (like Kafka) if enabled.
    */
   @BuildStep
   public Optional<DevServicesSharedNetworkBuildItem> prepareSharedNetwork(MicrocksBuildTimeConfig config) {
      // Retrieve DevServices config. Only manage a default one at the moment.
      MicrocksDevServicesConfig devServicesConfiguration = config.defaultDevService().devservices();

      log.info("================================");
      log.info("Checking whether we need to prepare a shared network for Microcks DevServices...");

      if (!devServicesConfiguration.enabled()) {
         // Explicitly disabled
         log.debug("Not preparing a shared network as Microcks devservices has been disabled in the config");
         return Optional.empty();
      }

      log.info("Preparing a shared network for Microcks DevServices");
      log.info("================================");

      return Optional.of(new DevServicesSharedNetworkBuildItem());
   }

   public static final class MicrocksContainerBuildItem extends MultiBuildItem {
      private final boolean isOwned;
      private final ContainerAddress containerAddress;
      private final ContainerAddress containerAddressForGRPC;
      private final MicrocksContainerStartable container;
      private final String label;

      public MicrocksContainerBuildItem(ContainerAddress containerAddress, ContainerAddress containerAddressForGRPC, String label) {
         isOwned = false;
         this.containerAddress = containerAddress;
         this.containerAddressForGRPC = containerAddressForGRPC;
         this.container = null;
         this.label = label;
      }

      public MicrocksContainerBuildItem(MicrocksContainerStartable container, String label) {
         isOwned = true;
         this.container = container;
         this.containerAddressForGRPC = null;
         this.containerAddress = null;
         this.label = label;
      }


      public boolean isOwned() {
         return isOwned;
      }

      public MicrocksContainerStartable getContainer() {
         return this.container;
      }

      public ContainerAddress getContainerAddress() {
         return this.containerAddress;
      }

      public ContainerAddress getContainerAddressForGRPC() {
         return this.containerAddressForGRPC;
      }

      public String label() {
         return label;
      }
   }

   /**
    * Start one (or many in the future) MicrocksContainer(s) depending on extension configuration.
    * We also take care of locating and re-using existing container if configured in shared modeL
    */
   @BuildStep
   public void startMicrocksDevService(BuildProducer<DevServicesResultBuildItem> producer, List<MicrocksContainerBuildItem> containers, MicrocksBuildTimeConfig microcksBuildTimeConfig, ScanResultsBuildItem scanResults) {
      MicrocksDevServicesConfig config = microcksBuildTimeConfig.defaultDevService().devservices();

      for (MicrocksContainerBuildItem container : containers) {
         if (container.isOwned()) {
            Supplier<MicrocksContainerStartable> microcksSupplier = () -> container.getContainer();
            producer.produce(DevServicesResultBuildItem.owned()
                  .serviceName("microcks-" + config.serviceName())
                  .name(MicrocksQuarkusProcessor.FEATURE)
                  .serviceConfig(config)
                  .startable(microcksSupplier)
                  .postStartHook(s -> importArtifacts(scanResults, config, s.getConnectionInfo()))
                  .configProvider(getDevServiceExposedConfig(config.serviceName()))
                  .build());
         } else {
            ContainerAddress containerAddress = container.getContainerAddress();

            ContainerAddress containerAddressForGRPC = container.getContainerAddressForGRPC();
            producer.produce(DevServicesResultBuildItem.discovered()
                  .name(config.serviceName())
                  .containerId(containerAddress.getId())
                  .config(getDevServiceExposedConfig(config.serviceName(), containerAddress.getHost(),
                        containerAddress.getPort(), containerAddressForGRPC.getPort()))
                  .build());
         }
      }
   }

   @BuildStep
   public MicrocksContainerBuildItem makeTheContainer(
         BuildProducer<MicrocksContainersEnsembleHostsBuildItem> ensembleConfigBuildItemProducer,
         LaunchModeBuildItem launchMode,
         DockerStatusBuildItem dockerStatusBuildItem,
         MicrocksBuildTimeConfig microcksBuildTimeConfig,
         List<DevServicesSharedNetworkBuildItem> sharedNetworks,
         DevServicesConfig devServicesConfig) {

      // If the dev service is disabled, we return null to indicate that no dev service was started.
      MicrocksDevServicesConfig config = microcksBuildTimeConfig.defaultDevService().devservices();
      if (!config.enabled()) {
         log.debug("Not starting dev services for Microcks as it has been disabled in the config.");
         return null;
      }

      if (!dockerStatusBuildItem.isContainerRuntimeAvailable()) {
         log.warn("Please configure quarkus.microcks.hosts or get a working docker instance");
         return null;
      }

      log.info("================================");
      log.info("Shared networks for DevServices: " + sharedNetworks.size());
      log.info("devServicesConfig.launchOnSharedNetwork(): " + devServicesConfig.launchOnSharedNetwork());
      log.info("devServicesSharedNetworkBuildItem.get(0).getSource(): " + sharedNetworks.get(0).getSource());

      boolean useSharedNetwork = DevServicesSharedNetworkBuildItem.isSharedNetworkRequired(devServicesConfig, sharedNetworks);

      log.info("Starting Microcks DevServices" + (useSharedNetwork ? " using shared network" : ""));
      log.info("================================");


      Supplier<MicrocksContainerBuildItem> thing = () -> new MicrocksContainerBuildItem(createMicrocksContainer(config, launchMode.getLaunchMode(), ensembleConfigBuildItemProducer), config.serviceName());

      return microcksContainerLocator.locateContainer(config.serviceName(), config.shared(), launchMode.getLaunchMode())
            .map(containerAddress -> microcksContainerLocatorForGRPC.locateContainer(config.serviceName(), config.shared(), launchMode.getLaunchMode())
                  .map(containerAddressForGRPC -> new MicrocksContainerBuildItem(containerAddress, containerAddressForGRPC, "discovered")).orElseGet(thing))
            .orElseGet(thing);
   }

   /**
    * Depending on other started dev services, complement the MicrocksContainer with some other forming an Ensemble.
    * This will only be called if an ensemble hosts build item is produced,
    * which only happens if the Microcks container is started.
    */
   @BuildStep
   public void completeMicrocksEnsembleForMinion(Capabilities capabilities, MicrocksBuildTimeConfig microcksBuildTimeConfig,
                                                 List<MicrocksContainersEnsembleHostsBuildItem> ensembleHostses, BuildProducer<DevServicesResultBuildItem> producer) {

      log.info("================================");
      log.info("Adding Minion to Microcks DevServices Ensemble if required...");

      for (MicrocksContainersEnsembleHostsBuildItem ensembleHosts : ensembleHostses) {

         // Get the ensemble configuration.
         MicrocksDevServicesConfig.EnsembleConfiguration ensembleConfiguration = microcksBuildTimeConfig.defaultDevService().devservices().ensemble();

         if (ensembleConfiguration.asyncEnabled() || kafkaBrokerIsPresent(capabilities)) {
            log.debugf("Starting a MicrocksAsyncMinionContainer with image '%s'", ensembleConfiguration.asyncImageName());

            // Force compatibility of configured image.
            DockerImageName imageName = DockerImageName.parse(ensembleConfiguration.asyncImageName())
                  .asCompatibleSubstituteFor(MicrocksDevServicesConfig.MICROCKS_UBER_ASYNC_MINION_LATEST);

            // We've got the conditions for launching a new MicrocksAsyncMinionContainer !
            MicrocksAsyncMinionContainer asyncMinionContainer = new MicrocksAsyncMinionContainer(Network.SHARED,
                  imageName, ensembleHosts.getMicrocksHost()).withAccessToHost(true);


            // Update network aliases with ensembleHosts before starting it.
            List<String> aliases = asyncMinionContainer.getNetworkAliases();
            aliases.add(ensembleHosts.getAsyncMinionHost());
            asyncMinionContainer.setNetworkAliases(aliases);

            MicrocksDevServicesConfig config = microcksBuildTimeConfig.defaultDevService().devservices();

            // It would be nice not to hardcode this port
            Supplier<? extends MinionContainerStartable> microcksSupplier = () -> new MinionContainerStartable(asyncMinionContainer, 8081);
            producer.produce(DevServicesResultBuildItem.owned()
                  .feature(MicrocksQuarkusProcessor.FEATURE)
                  .serviceName(MicrocksQuarkusProcessor.FEATURE + "-" + config.serviceName() + "minion")
                  .serviceConfig(config) // the lifecycle of the postman container should be the same as of the microcks container
                  .startable(microcksSupplier)
                  .dependsOnConfig(KAFKA_BOOTSTRAP_SERVERS, MinionContainerStartable::setKafkaBootstrapServersFromDevService) // the minion shouldn't be started until kafka is started
                  .build());
         }
      }

      log.info("================================");
   }

   private static boolean kafkaBrokerIsPresent(Capabilities capabilities) {

      // Now we need to figure out if there is a kafka broker, either configured by a user or instantiated as a dev service
      // Scenario 1 - an external kafka service is configured
      if (ConfigProvider.getConfig().getOptionalValue(KAFKA_BOOTSTRAP_SERVERS, String.class).isPresent()) {
         return true;
      }

      // Scenario 2 - an extension has done a dev service
      // This re-creation of the logic from the Kafka extension is not ideal, but the container will not actually start unless config is present

      // We can't use DevServicesLauncherConfigResultBuildItem in build steps which produce dev services, because it will create a cyclic dependency
      // Instead, look at what extensions are installed and config to work out if there is likely to be a dev service
      if (capabilities.isPresent(Capability.KAFKA)) {
         boolean allDevServicesEnabled = ConfigProvider.getConfig().getOptionalValue("quarkus.devservices.enabled", Boolean.class).orElse(true);
         boolean kafkaDevServicesEnabled = ConfigProvider.getConfig().getOptionalValue("quarkus.kafka.devservices.enabled", Boolean.class).orElse(true);

         if (allDevServicesEnabled && kafkaDevServicesEnabled) {
            return true;
         }

         // These checks miss the "all the Reactive Messaging Kafka channels have the bootstrap.servers attribute set", but this extension doesn't look at that config
      }
      return false;
   }

   @BuildStep
   public DevServicesResultBuildItem completePostmanMicrocksEnsemble(MicrocksBuildTimeConfig microcksBuildTimeConfig, List<MicrocksContainersEnsembleHostsBuildItem> ensembleHostses, ScanResultsBuildItem scanResults) {

      log.info("================================");
      log.info("Completing Postman part of Microcks DevServices Ensemble if required...");

      for (MicrocksContainersEnsembleHostsBuildItem ensembleHosts : ensembleHostses) {

         // Get the ensemble configuration.
         MicrocksDevServicesConfig.EnsembleConfiguration ensembleConfiguration = microcksBuildTimeConfig.defaultDevService().devservices().ensemble();

         if (ensembleConfiguration.postmanEnabled() || scanResults.aPostmanCollectionIsPresent()) {
            log.debugf("Starting a GenericContainer with Postman image '%s'", ensembleConfiguration.postmanImageName());

            // Force compatibility of configured image.
            DockerImageName imageName = DockerImageName.parse(ensembleConfiguration.postmanImageName())
                  .asCompatibleSubstituteFor(MicrocksDevServicesConfig.MICROCKS_UBER_ASYNC_MINION_LATEST);

            // We've got the conditions for launching a new GenericContainer with Postman !
            GenericContainer<?> postmanContainer = new GenericContainer<>(imageName)
                  .withNetwork(Network.SHARED)
                  .withNetworkAliases(ensembleHosts.getPostmanHost())
                  .withAccessToHost(true)
                  .waitingFor(Wait.forLogMessage(".*postman-runtime wrapper listening on port.*", 1));


            MicrocksDevServicesConfig config = microcksBuildTimeConfig.defaultDevService().devservices();

            Supplier<? extends Startable> microcksSupplier = () -> new GenericContainerStartable(postmanContainer);
            return DevServicesResultBuildItem.owned()
                  .name(MicrocksQuarkusProcessor.FEATURE)
                  .serviceName(MicrocksQuarkusProcessor.FEATURE + "-" + config.serviceName() + "-postman")
                  .serviceConfig(config) // the lifecycle of the postman container should be the same as of the microcks container
                  .startable(microcksSupplier)
                  .build();
         }

      }
      log.info("================================");
      return null;
   }


   /**
    * Customize the extension card in DevUI with a link to running Microcks containers UI.
    */
   @BuildStep(onlyIf = IsDevelopment.class)
   public CardPageBuildItem pages(List<MicrocksContainerBuildItem> containers, MicrocksBuildTimeConfig config) {
      CardPageBuildItem cardPageBuildItem = new CardPageBuildItem();

      for (MicrocksContainerBuildItem container : containers) {
         cardPageBuildItem.addPage(Page.externalPageBuilder("Microcks UI")
               .dynamicUrlJsonRPCMethodName("getConsoleDefaultLink")
               .doNotEmbed()
               .isHtmlContent()
               .staticLabel(container.label())
               .icon("font-awesome-solid:plug-circle-bolt"));
      }

      return cardPageBuildItem;
   }


   @BuildStep(onlyIf = IsLocalDevelopment.class)
   public JsonRPCProvidersBuildItem createJsonRPCService() {
      return new JsonRPCProvidersBuildItem(MicrocksJsonRPCService.class, BuiltinScope.SINGLETON.getName());
   }

   private MicrocksContainerStartable createMicrocksContainer(MicrocksDevServicesConfig config, LaunchMode launchMode, BuildProducer<MicrocksContainersEnsembleHostsBuildItem> ensembleConfigBuildItemProducer) {
      DockerImageName dockerImageName = DockerImageName.parse(config.imageName())
            .asCompatibleSubstituteFor(MicrocksDevServicesConfig.MICROCKS_UBER_LATEST);

      MicrocksContainer microcksContainer = new MicrocksContainer(dockerImageName);
      microcksContainer.withAccessToHost(true);

      // Adding access to the Quarkus app test port.
      Config globalConfig = ConfigProviderResolver.instance().getConfig();
      int testPort = globalConfig.getValue("quarkus.http.test-port", OptionalInt.class).orElse(8081);
      if (testPort > 0) {
         Testcontainers.exposeHostPorts(testPort);
      }

      microcksContainer.withEnv(config.containerEnv());

      // Finalize label and shared network.
      if (launchMode == DEVELOPMENT) {
         microcksContainer.withLabel(DEV_SERVICE_LABEL, config.serviceName());
      }

      // Always launch microcks in a shared network to be able to access possible ensemble containers.
      String microcksHost = ConfigureUtil.configureSharedNetwork(microcksContainer, config.serviceName());

      // Build and store configuration for possible other hosts of the ensemble.
      String postmanHost = String.format("%s-%s-%s-%s", MICROCKS,
            config.serviceName(), "postman", Base58.randomString(5));
      String asyncMinionHost = String.format("%s-%s-%s-%s", MICROCKS,
            config.serviceName(), "async-minion", Base58.randomString(5));
      MicrocksContainersEnsembleHostsBuildItem ensembleHosts = new MicrocksContainersEnsembleHostsBuildItem(microcksHost, postmanHost, asyncMinionHost);

      // Set the required environment variables for running as an Ensemble.
      microcksContainer.withEnv("POSTMAN_RUNNER_URL", HTTP_SCHEME + postmanHost + ":3000")
            .withEnv("TEST_CALLBACK_URL", HTTP_SCHEME + microcksHost + ":" + MicrocksContainer.MICROCKS_HTTP_PORT)
            .withEnv("ASYNC_MINION_URL", HTTP_SCHEME + asyncMinionHost + ":" + MicrocksAsyncMinionContainer.MICROCKS_ASYNC_MINION_HTTP_PORT);

      ensembleConfigBuildItemProducer.produce(ensembleHosts);
      return new MicrocksContainerStartable(microcksContainer);
   }

   /**
    *
    */
   static class MicrocksContainerStartable extends GenericContainerStartable {

      MicrocksContainerStartable(MicrocksContainer container) {
         super(container, MicrocksContainer.MICROCKS_HTTP_PORT);
      }

      public Integer getGrpcPort() {
         return container.getMappedPort(MicrocksContainer.MICROCKS_GRPC_PORT);
      }
   }

   static class MinionContainerStartable extends GenericContainerStartable {

      private String kafkaBootstrapServersFromDevService = null;

      MinionContainerStartable(MicrocksAsyncMinionContainer container, int portNumber) {
         super(container, portNumber);
      }

      public void setKafkaBootstrapServersFromDevService(String c) {
         this.kafkaBootstrapServersFromDevService = c;
      }

      @Override
      public void start() {


         String kafkaBootstrapServers = kafkaBootstrapServersFromDevService != null ? kafkaBootstrapServersFromDevService : ConfigProvider.getConfig().getOptionalValue(KAFKA_BOOTSTRAP_SERVERS, String.class).orElse(null);

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
            ((MicrocksAsyncMinionContainer) container).withKafkaConnection(new KafkaConnection(
                  kafkaBootstrapServers.replace("localhost", GenericContainer.INTERNAL_HOST_HOSTNAME)));
         }
         super.start();
      }
   }

   static class GenericContainerStartable<T extends GenericContainer<T>> implements Startable {
      protected final GenericContainer<T> container;
      private final int portNumber;

      GenericContainerStartable(GenericContainer container) {
         this(container, -1);
      }

      GenericContainerStartable(GenericContainer container, int portNumber) {
         this.container = container;
         this.portNumber = portNumber;
      }

      @Override
      public void start() {
         container.start();
      }

      @Override
      public String getConnectionInfo() {
         if (portNumber > 0) {
            return HTTP_SCHEME + "localhost:" + container.getMappedPort(portNumber);
         } else {
            return "Isolated";
         }
      }

      @Override
      public String getContainerId() {
         return container.getContainerId();
      }

      @Override
      public void close() throws IOException {
         container.close();
      }

   }

   private Map<String, Function<MicrocksContainerStartable, String>> getDevServiceExposedConfig(String serviceName) {
      String configPrefix = MicrocksProperties.getConfigPrefix(serviceName);

      Map<String, Function<MicrocksContainerStartable, String>> configFunctions = new HashMap<>();
      configFunctions.put(configPrefix + MicrocksProperties.HTTP_SUFFIX, Startable::getConnectionInfo);
      configFunctions.put(configPrefix + MicrocksProperties.HTTP_HOST_SUFFIX, s -> "localhost");
      configFunctions.put(configPrefix + MicrocksProperties.HTTP_PORT_SUFFIX, s -> s.getConnectionInfo().substring(s.getConnectionInfo().lastIndexOf(":") + 1));
      configFunctions.put(configPrefix + MicrocksProperties.GRPC_HOST_SUFFIX, s -> "localhost");
      configFunctions.put(configPrefix + MicrocksProperties.GRPC_PORT_SUFFIX, s -> s.getGrpcPort().toString());

      return configFunctions;
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

   // Used in configProvider
   private Map<String, String> getDevServiceExposedConfig(String serviceName, String visibleHostName, Integer httpPort,
                                                          Integer grpcPort, String internalHostName, LoadedArtifacts loadedArtifacts) {
      String configPrefix = MicrocksProperties.getConfigPrefix(serviceName);

      return Map.of(
            configPrefix + MicrocksProperties.HTTP_SUFFIX, HTTP_SCHEME + visibleHostName + ":" + httpPort.toString(),
            configPrefix + MicrocksProperties.HTTP_HOST_SUFFIX, visibleHostName,
            configPrefix + MicrocksProperties.HTTP_PORT_SUFFIX, httpPort.toString(),
            configPrefix + MicrocksProperties.GRPC_SUFFIX, HTTP_SCHEME + visibleHostName + ":" + grpcPort.toString(),
            configPrefix + MicrocksProperties.GRPC_HOST_SUFFIX, visibleHostName,
            configPrefix + MicrocksProperties.GRPC_PORT_SUFFIX, grpcPort.toString(),
            configPrefix + MicrocksProperties.INTERNAL_HOST_SUFFIX, internalHostName,
            configPrefix + MicrocksProperties.LOADED_PRIMARY_ARTIFACTS, String.join(",", loadedArtifacts.primaryArtifacts),
            configPrefix + MicrocksProperties.LOADED_SECONDARY_ARTIFACTS, String.join(",", loadedArtifacts.secondaryArtifacts));
   }

   @BuildStep
   public ScanResultsBuildItem scanForArtifacts(MicrocksBuildTimeConfig microcksBuildTimeConfig, CurateOutcomeBuildItem outcomeBuildItem) {
      MicrocksDevServicesConfig devServicesConfig = microcksBuildTimeConfig.defaultDevService().devservices();


      // Then, load or scan the local artifacts if any.
      if (devServicesConfig.artifacts().isPresent()) {
         // No scan results, because config is present
         // Just return an empty one so that consuming build steps still run
         return new ScanResultsBuildItem();
      } else {
         try {
            ArtifactScanner scanner = new ArtifactScanner(outcomeBuildItem);
            return scanner.toBuildItem();
         } catch (Exception e) {
            log.error("Failed to load Artifacts in microcks", e);
            return new ScanResultsBuildItem();
         }
      }
   }

   private void importArtifacts(ScanResultsBuildItem scanResults, MicrocksDevServicesConfig devServicesConfig, String connectionInfo) {
      LoadedArtifacts loadedArtifacts = new LoadedArtifacts();
      log.infof("Importing artifacts into Microcks running at '%s'", connectionInfo);

      // First, load the remote artifacts if any.
      if (devServicesConfig.remoteArtifacts().isPresent()) {
         ArtifactsConfiguration remoteArtifactsConfig = devServicesConfig.remoteArtifacts().get();
         for (String remoteArtifactUrl : remoteArtifactsConfig.primaries()) {
            log.infof("Load '%s' as primary remote artifact", remoteArtifactUrl);
            try {
               MicrocksContainer.downloadAsMainRemoteArtifact(connectionInfo, remoteArtifactUrl);
            } catch (Exception e) {
               log.error("Failed to load Remote Artifacts in microcks", e);
            }
         }
         if (remoteArtifactsConfig.secondaries().isPresent()) {
            for (String remoteArtifactUrl : remoteArtifactsConfig.secondaries().get()) {
               log.infof("Load '%s' as secondary remote artifact", remoteArtifactUrl);
               try {
                  MicrocksContainer.downloadAsSecondaryRemoteArtifact(connectionInfo, remoteArtifactUrl);
               } catch (Exception e) {
                  log.error("Failed to load Remote Artifacts in microcks", e);
               }
            }
         }
      }
      // Then, load or scan the local artifacts if any.
      if (devServicesConfig.artifacts().isPresent()) {
         ArtifactsConfiguration artifactsConfig = devServicesConfig.artifacts().get();
         try {
            for (String primaryArtifact : artifactsConfig.primaries()) {
               loadArtifact(connectionInfo, new File(primaryArtifact), true);
               addToLoadedArtifacts(primaryArtifact, loadedArtifacts, true);
            }
            if (artifactsConfig.secondaries().isPresent()) {
               for (String secondaryArtifact : artifactsConfig.secondaries().get()) {
                  loadArtifact(connectionInfo, new File(secondaryArtifact), false);
                  addToLoadedArtifacts(secondaryArtifact, loadedArtifacts, false);
               }
            }
         } catch (Exception e) {
            log.error("Failed to load Artifacts in microcks", e);
         }
      } else {
         try {
            loadedArtifacts.primaryArtifacts = loadPrimaryArtifacts(connectionInfo, scanResults);
            // Continue with secondary artifacts only if we found something.
            if (!loadedArtifacts.primaryArtifacts.isEmpty()) {
               loadedArtifacts.secondaryArtifacts = loadSecondaryArtifacts(connectionInfo, scanResults);
            }
         } catch (Exception e) {
            log.error("Failed to load Artifacts in microcks", e);
         }
      }
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


   private List<String> loadPrimaryArtifacts(String connectionInfo, ScanResultsBuildItem scanResultsBuildItem) throws IOException {
      return loadArtifacts(scanResultsBuildItem.primary(), connectionInfo, true);
   }

   private List<String> loadSecondaryArtifacts(String connectionInfo, ScanResultsBuildItem scanResultsBuildItem) throws IOException {
      return loadArtifacts(scanResultsBuildItem.secondary(), connectionInfo, false);
   }

   private List<String> loadArtifacts(Map<File, String> filesAndRelativePath, String connectionInfo, boolean primary) throws IOException {
      List<String> loadedArtifacts = new ArrayList<>();

      for (Map.Entry<File, String> entry : filesAndRelativePath.entrySet()) {
         // Record loaded even if import will fail. That way, it will be
         // reloaded by the Hot replacement when fixed.
         loadedArtifacts.add(entry.getValue());
         loadArtifact(connectionInfo, entry.getKey(), primary);
      }
      return loadedArtifacts;
   }


   private void loadArtifact(String connectionInfo, File artifactFile, boolean primary) {
      try {
         log.infof("Load '%s' as %s artifact", artifactFile.getName(), primary ? "primary" : "secondary");
         MicrocksContainer.importArtifact(connectionInfo, artifactFile, primary);
      } catch (Exception e) {
         log.errorf("Failed to import %s artifact '%s' in microcks", primary ? "primary" : "secondary", artifactFile.getName(), e);
      }
   }


   /**
    * A simple class to keep track of loaded artifacts.
    */
   static class LoadedArtifacts {
      List<String> primaryArtifacts = new ArrayList<>();
      List<String> secondaryArtifacts = new ArrayList<>();
   }
}

