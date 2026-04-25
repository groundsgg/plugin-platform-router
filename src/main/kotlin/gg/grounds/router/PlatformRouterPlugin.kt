package gg.grounds.router

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import gg.grounds.router.config.RouterConfig
import java.nio.file.Files
import java.nio.file.Path
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
constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path,
) {
    private var config: RouterConfig? = null

    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        val configPath = ensureConfig()
        config =
            try {
                RouterConfig.load(configPath, logger).also { cfg ->
                    logger.info(
                        "platform-router enabled (version={}, mode={}, namespace={}, hostnameSuffix={}, resync={}s)",
                        BuildInfo.VERSION,
                        cfg.routerMode,
                        cfg.namespaceSelector,
                        cfg.hostnameSuffix,
                        cfg.resyncInterval.toSeconds(),
                    )
                }
            } catch (t: Throwable) {
                logger.error(
                    "platform-router disabled — invalid config at {}: {}",
                    configPath,
                    t.message,
                )
                null
            }
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        if (config != null) {
            logger.info("platform-router disabled")
        }
    }

    private fun ensureConfig(): Path {
        Files.createDirectories(dataDirectory)
        val target = dataDirectory.resolve("config.toml")
        if (Files.notExists(target)) {
            javaClass.getResourceAsStream("/config.toml").use { input ->
                requireNotNull(input) { "bundled default config.toml is missing from the JAR" }
                Files.copy(input, target)
            }
            logger.info("platform-router: wrote default config to {}", target)
        }
        return target
    }
}
