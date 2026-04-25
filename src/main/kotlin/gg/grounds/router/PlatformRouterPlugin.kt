package gg.grounds.router

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger

@Plugin(
    id = "platform-router",
    name = "Platform Router",
    version = BuildInfo.VERSION,
    description =
        "Watches Agones GameServer CRDs and exposes them as Velocity forced-host backends.",
    authors = ["Grounds Development Team"],
    url = "https://github.com/groundsgg/plugin-platform-router",
)
class PlatformRouterPlugin
@Inject
constructor(private val proxy: ProxyServer, private val logger: Logger) {

    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        logger.info("platform-router enabled (version={})", BuildInfo.VERSION)
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        logger.info("platform-router disabled")
    }
}
