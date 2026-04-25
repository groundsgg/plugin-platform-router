package gg.grounds.router.velocity

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import gg.grounds.router.config.RouterMode
import gg.grounds.router.k8s.Backend
import gg.grounds.router.k8s.FleetKey
import gg.grounds.router.k8s.GsView
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.Logger

/**
 * Bridge from `GsView` events to Velocity's `RegisteredServer` set + a hostname → fleet lookup
 * consumed by the [PlayerRoutingListener].
 * - All public methods are thread-safe (informer callback threads vs Velocity event threads).
 * - We register every backend (ready or not) so they show up in `/server`-style admin views;
 *   ready-state gates whether players are routed.
 * - Per-fleet state is held in a single map; transitions (register/unregister forced-host hostname)
 *   happen in [withFleet] so concurrent upserts can't race the hostname-lookup bookkeeping.
 */
class BackendRegistry(
    private val proxy: ProxyServer,
    private val mode: RouterMode,
    private val clusterName: String?,
    private val hostnameSuffix: String,
    private val logger: Logger,
) {
    private data class FleetState(
        val backends: MutableMap<String, Backend> = mutableMapOf(),
        val ready: MutableSet<String> = mutableSetOf(),
        val rrIndex: AtomicInteger = AtomicInteger(0),
        var hostname: String? = null,
    )

    private val state = ConcurrentHashMap<FleetKey, FleetState>()
    private val hostnameIndex = ConcurrentHashMap<String, FleetKey>()
    private val registered = ConcurrentHashMap<String, RegisteredServer>()

    /** Apply a GameServer signal — register/refresh, mark ready/not-ready. */
    fun upsert(view: GsView) {
        withFleet(view.key) { fs ->
            val serverName = serverName(view.key, view.backend.name)
            val previous = fs.backends.put(view.backend.name, view.backend)

            if (previous == null) {
                ensureRegistered(serverName, view.backend)
            } else if (previous != view.backend) {
                // address or port changed — unregister and re-register so Velocity picks up the
                // new socket. Server name stays stable.
                unregister(serverName)
                ensureRegistered(serverName, view.backend)
            }

            val wasEmpty = fs.ready.isEmpty()
            if (view.ready) fs.ready.add(view.backend.name) else fs.ready.remove(view.backend.name)
            ensureHostnameMembership(view.key, fs, wasEmpty)
        }
    }

    /** Remove a single GameServer (delete event). */
    fun remove(key: FleetKey, gameServerName: String) {
        withFleet(key) { fs ->
            val backend = fs.backends.remove(gameServerName) ?: return@withFleet
            fs.ready.remove(gameServerName)
            unregister(serverName(key, gameServerName))
            logger.info(
                "platform-router: deregistered backend {} (fleet={}/{})",
                backend.name,
                key.namespace,
                key.fleetName,
            )
            ensureHostnameMembership(key, fs, wasEmpty = false)
        }
    }

    /**
     * Look up the (round-robin) backend Velocity should route a connecting player to. Returns null
     * when the hostname is unknown or no ready backends remain.
     */
    fun chooseBackend(virtualHost: String): RegisteredServer? {
        val key = hostnameIndex[virtualHost.lowercase()] ?: return null
        val fs = state[key] ?: return null
        synchronized(fs) {
            if (fs.ready.isEmpty()) return null
            val readyList = fs.ready.toList()
            val pick = readyList[(fs.rrIndex.getAndIncrement() % readyList.size).coerceAtLeast(0)]
            return registered[serverName(key, pick)]
        }
    }

    /** Test hook — exposes a frozen snapshot of `(FleetKey -> backend names)` for assertions. */
    fun snapshot(): Map<FleetKey, Set<String>> =
        state.mapValues { (_, fs) -> synchronized(fs) { fs.ready.toSet() } }

    private fun ensureRegistered(serverName: String, backend: Backend) {
        registered.computeIfAbsent(serverName) {
            val info = ServerInfo(serverName, InetSocketAddress(backend.address, backend.port))
            logger.info(
                "platform-router: registered backend {} -> {}:{}",
                serverName,
                backend.address,
                backend.port,
            )
            proxy.registerServer(info)
        }
    }

    private fun unregister(serverName: String) {
        val server = registered.remove(serverName) ?: return
        proxy.unregisterServer(server.serverInfo)
    }

    private fun ensureHostnameMembership(key: FleetKey, fs: FleetState, wasEmpty: Boolean) {
        if (fs.ready.isEmpty()) {
            fs.hostname?.let { host ->
                hostnameIndex.remove(host)
                logger.info("platform-router: forced-host {} removed (no ready backends)", host)
            }
            fs.hostname = null
            fs.rrIndex.set(0)
            return
        }
        if (wasEmpty || fs.hostname == null) {
            val host =
                HostnameBuilder.build(
                    mode = mode,
                    namespace = key.namespace,
                    fleetName = key.fleetName,
                    clusterName = clusterName,
                    hostnameSuffix = hostnameSuffix,
                )
            fs.hostname = host
            hostnameIndex[host] = key
            logger.info(
                "platform-router: forced-host {} -> fleet {}/{}",
                host,
                key.namespace,
                key.fleetName,
            )
        }
    }

    private inline fun withFleet(key: FleetKey, block: (FleetState) -> Unit) {
        val fs = state.computeIfAbsent(key) { FleetState() }
        synchronized(fs) { block(fs) }
    }

    private fun serverName(key: FleetKey, gameServerName: String): String =
        "${key.namespace}--${key.fleetName}--$gameServerName"
}
