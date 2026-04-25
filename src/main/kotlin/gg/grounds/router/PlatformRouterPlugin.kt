package gg.grounds.router

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import gg.grounds.router.config.RouterConfig
import gg.grounds.router.config.RouterMode
import gg.grounds.router.k8s.GameServerWatcher
import gg.grounds.router.velocity.BackendRegistry
import gg.grounds.router.velocity.PlayerRoutingListener
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
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
    private var k8sClient: KubernetesClient? = null
    private var watcher: GameServerWatcher? = null

    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        val config =
            try {
                RouterConfig.load(ensureConfig(), logger)
            } catch (t: Throwable) {
                logger.error("platform-router disabled — invalid config: {}", t.message)
                return
            }

        logger.info(
            "platform-router enabled (version={}, mode={}, namespace={}, hostnameSuffix={}, resync={}s)",
            BuildInfo.VERSION,
            config.routerMode,
            config.namespaceSelector,
            config.hostnameSuffix,
            config.resyncInterval.toSeconds(),
        )

        val client =
            try {
                KubernetesClientBuilder().build()
            } catch (t: Throwable) {
                logger.error(
                    "platform-router disabled — could not build kubernetes client: {}",
                    t.message,
                )
                return
            }
        k8sClient = client

        val registry =
            BackendRegistry(
                proxy = proxy,
                mode = config.routerMode,
                clusterName = config.clusterName,
                hostnameSuffix = config.hostnameSuffix,
                logger = logger,
            )

        proxy.eventManager.register(this, PlayerRoutingListener(registry, logger))

        watcher =
            GameServerWatcher(
                    client = client,
                    mode = watchMode(config.routerMode),
                    namespaceSelector = config.namespaceSelector,
                    resyncInterval = config.resyncInterval,
                    onUpsert = { registry.upsert(it) },
                    onDelete = { key, gsName -> registry.remove(key, gsName) },
                    logger = logger,
                )
                .also { it.start() }
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        try {
            watcher?.stop()
        } catch (t: Throwable) {
            logger.warn("platform-router: error stopping watcher: {}", t.message)
        }
        try {
            k8sClient?.close()
        } catch (t: Throwable) {
            logger.warn("platform-router: error closing k8s client: {}", t.message)
        }
        watcher = null
        k8sClient = null
        logger.info("platform-router disabled")
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

    private fun watchMode(mode: RouterMode): GameServerWatcher.WatchMode =
        when (mode) {
            RouterMode.PLATFORM -> GameServerWatcher.WatchMode.PLATFORM
            RouterMode.STAGING -> GameServerWatcher.WatchMode.STAGING
        }
}
