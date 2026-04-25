package gg.grounds.router.k8s

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.Logger

/**
 * Watches Agones `GameServer` CRDs via fabric8's [SharedIndexInformer]. Maintains an
 * auto-reconnecting watch with periodic resync; events are translated through
 * [GameServerExtractor.extract] before being dispatched to the supplied callbacks.
 *
 * Lifecycle:
 * - [start] kicks off the informer asynchronously; it is safe to call once per instance.
 * - [stop] tears the informer down; idempotent.
 *
 * Mode handling:
 * - `PLATFORM` watches a single namespace (the configured selector).
 * - `STAGING` watches cluster-wide and filters to namespaces starting with `preview-`. Cluster-
 *   wide watch is the simplest correct option since fabric8 does not support namespace globs at the
 *   API level.
 */
class GameServerWatcher(
    private val client: KubernetesClient,
    private val mode: WatchMode,
    private val namespaceSelector: String,
    private val resyncInterval: Duration,
    private val onUpsert: (GsView) -> Unit,
    private val onDelete: (FleetKey, String) -> Unit,
    private val logger: Logger,
) {
    enum class WatchMode {
        PLATFORM,
        STAGING,
    }

    private var informer: SharedIndexInformer<GameServer>? = null
    private val started = AtomicBoolean(false)

    fun start() {
        check(started.compareAndSet(false, true)) { "watcher already started" }

        val operation =
            when (mode) {
                WatchMode.PLATFORM ->
                    client.resources(GameServer::class.java).inNamespace(namespaceSelector)
                WatchMode.STAGING -> client.resources(GameServer::class.java).inAnyNamespace()
            }

        informer =
            operation
                .runnableInformer(resyncInterval.toMillis())
                .apply { addEventHandler(eventHandler()) }
                .also { it.run() }

        logger.info(
            "platform-router: gameserver watch started (mode={}, scope={}, resync={}s)",
            mode,
            if (mode == WatchMode.PLATFORM) "ns=$namespaceSelector" else "all-namespaces",
            resyncInterval.toSeconds(),
        )
    }

    fun stop() {
        if (started.compareAndSet(true, false)) {
            try {
                informer?.close()
            } catch (t: Throwable) {
                logger.warn("platform-router: error closing informer: {}", t.message)
            } finally {
                informer = null
                logger.info("platform-router: gameserver watch stopped")
            }
        }
    }

    private fun eventHandler() =
        object : ResourceEventHandler<GameServer> {
            override fun onAdd(obj: GameServer) = dispatchUpsert(obj)

            override fun onUpdate(oldObj: GameServer, newObj: GameServer) = dispatchUpsert(newObj)

            override fun onDelete(obj: GameServer, deletedFinalStateUnknown: Boolean) {
                if (mode == WatchMode.STAGING && !isStagingNamespace(obj.metadata?.namespace))
                    return

                val name = obj.metadata?.name ?: return
                val namespace = obj.metadata?.namespace ?: return
                val fleetName = obj.metadata?.labels?.get(AGONES_FLEET_LABEL) ?: return
                onDelete(FleetKey(namespace, fleetName), name)
            }
        }

    private fun dispatchUpsert(obj: GameServer) {
        if (mode == WatchMode.STAGING && !isStagingNamespace(obj.metadata?.namespace)) return

        try {
            val view = GameServerExtractor.extract(obj) ?: return
            onUpsert(view)
        } catch (t: Throwable) {
            // One bad event must not stall the watch — informer keeps running.
            logger.warn(
                "platform-router: skipped malformed gameserver {}/{}: {}",
                obj.metadata?.namespace,
                obj.metadata?.name,
                t.message,
            )
        }
    }

    private fun isStagingNamespace(namespace: String?): Boolean =
        namespace?.startsWith("preview-") == true
}
