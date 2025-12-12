package io.github.microcks.quarkus.runtime;

import io.smallrye.common.annotation.NonBlocking;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;

public class MicrocksJsonRPCService {

    @NonBlocking
    public String getConsoleDefaultLink() {
        Config globalConfig = ConfigProviderResolver.instance().getConfig();
        // TODO this should be parameterised, but that needs https://github.com/quarkusio/quarkus/pull/51659
        String configPrefix = MicrocksProperties.getConfigPrefix("default");
        String microcksContainerUrl = globalConfig.getValue(configPrefix
                + MicrocksProperties.HTTP_SUFFIX, String.class);

        if (microcksContainerUrl != null) {
            return microcksContainerUrl;
        }
        // Should never be called unless there is a container configured, but ...
        return "";
    }
}


