package gg.grounds.router.k8s

data class FleetKey(val namespace: String, val fleetName: String)

data class Backend(val name: String, val address: String, val port: Int)

/**
 * Snapshot of the relevant signals from a GameServer:
 * - [key] groups backends by (namespace, fleet)
 * - [backend] is the address:port the proxy should connect to
 * - [ready] = true when the GS is in a state where players should be sent to it (`Ready` or
 *   `Allocated`); false for transient/dead states (`Reserved`, `Unhealthy`, `Shutdown`)
 */
data class GsView(val key: FleetKey, val backend: Backend, val ready: Boolean)

object GameServerExtractor {

    private val READY_STATES = setOf("Ready", "Allocated")
    private val DEREGISTER_STATES = setOf("Reserved", "Unhealthy", "Shutdown", "Error")
    private val PREFERRED_PORT_NAMES = listOf("mc", "default", "minecraft")
    private const val POD_IP = "PodIP"

    /**
     * Reduce a GameServer to a [GsView], or null if the GS should be skipped entirely (no fleet
     * label, no usable address/port, or pre-Ready states like `Scheduled`).
     */
    fun extract(gs: GameServer): GsView? {
        val name = gs.metadata?.name ?: return null
        val namespace = gs.metadata?.namespace ?: return null
        val fleetName = gs.metadata?.labels?.get(AGONES_FLEET_LABEL) ?: return null

        val state = gs.status?.state
        val ready = state in READY_STATES
        val terminal = state in DEREGISTER_STATES

        // Skip resources that haven't been scheduled yet — nothing to register.
        if (!ready && !terminal) return null

        val backend = resolveBackend(name, gs) ?: return null

        return GsView(
            key = FleetKey(namespace = namespace, fleetName = fleetName),
            backend = backend,
            ready = ready,
        )
    }

    /**
     * Prefer the game server's **pod IP + containerPort** over the node IP + allocated hostPort.
     *
     * Velocity reaches a game server east-west, inside the cluster — it never needs the host
     * network — so the pod IP is both sufficient and strictly better: it is what lets the fleets
     * drop `portPolicy: Dynamic` (and with it the hostPort), which on a multi-tenant cluster is a
     * shared, collidable resource. Each vCluster runs its own Agones over the same 7000-8000
     * range on the same shared nodes, so two tenants can be handed the same hostPort and the
     * second game server then sticks in `Pending` forever.
     *
     * The node-address fallback keeps legacy `Dynamic`/`Static` fleets working unchanged while
     * they roll over, so this is safe to ship ahead of the chart flip.
     */
    private fun resolveBackend(name: String, gs: GameServer): Backend? {
        val podIp = gs.status?.addresses.orEmpty().firstOrNull { it.type == POD_IP }?.address
        val specPorts = gs.spec?.ports.orEmpty()
        val statusPorts = gs.status?.ports.orEmpty()

        if (!podIp.isNullOrEmpty()) {
            val containerPort = pickPort(specPorts) { it.containerPort }
            if (containerPort != null) return Backend(name, podIp, containerPort)
        }

        // Legacy: node address + the hostPort Agones allocated.
        val nodeAddress = gs.status?.address
        if (nodeAddress.isNullOrEmpty()) return null
        val hostPort =
            pickPort(statusPorts) { it.hostPort ?: it.port }
                ?: pickPort(specPorts) { it.hostPort ?: it.port ?: it.containerPort }
                ?: return null
        return Backend(name, nodeAddress, hostPort)
    }

    private fun pickPort(ports: List<GameServerPort>, select: (GameServerPort) -> Int?): Int? {
        // Only ever route a port we recognise by name — an unnamed or differently-named port
        // (e.g. `metrics`) must never receive players.
        for (preferred in PREFERRED_PORT_NAMES) {
            val match = ports.firstOrNull { it.name == preferred } ?: continue
            select(match)?.let { return it }
        }
        return null
    }
}
