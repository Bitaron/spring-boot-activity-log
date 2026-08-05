package io.github.bitaron.auditlog.server;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Configuration;

/** Same role as {@code HostAppMarker} in the autoconfigure module's own tests - not reusable
 * directly since it lives in that module's test sources, not a published artifact. */
@AutoConfigurationPackage
@Configuration(proxyBeanMethods = false)
class ServerHostAppMarker {
}
