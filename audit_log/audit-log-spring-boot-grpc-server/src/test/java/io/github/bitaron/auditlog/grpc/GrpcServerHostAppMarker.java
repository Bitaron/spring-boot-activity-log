package io.github.bitaron.auditlog.grpc;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Configuration;

/** Same role as {@code HostAppMarker}/{@code ServerHostAppMarker} in the other modules' own
 * tests - not reusable directly since test sources aren't published as a dependency. */
@AutoConfigurationPackage
@Configuration(proxyBeanMethods = false)
class GrpcServerHostAppMarker {
}
