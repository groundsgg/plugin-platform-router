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
    private val PREFERRED_PORT_NAMES = listOf("mc", "default")

    /**
     * Reduce a GameServer to a [GsView], or null if the GS should be skipped entirely (no fleet
     * label, no usable port, or pre-Ready states like `Scheduled` where the address is missing).
     */
    fun extract(gs: GameServer): GsView? {
        val name = gs.metadata?.name ?: return null
        val namespace = gs.metadata?.namespace ?: return null
        val fleetName = gs.metadata?.labels?.get(AGONES_FLEET_LABEL) ?: return null

        val state = gs.status?.state
        val address = gs.status?.address
        val statusPorts = gs.status?.ports.orEmpty()
        val specPorts = gs.spec?.ports.orEmpty()

        val ready = state in READY_STATES
        val terminal = state in DEREGISTER_STATES

        // Skip resources that haven't been scheduled to a node yet — without an address there is
        // nothing to register.
        if (!ready && !terminal) return null
        if (address.isNullOrEmpty()) return null

        val port = pickPort(statusPorts, specPorts) ?: return null

        return GsView(
            key = FleetKey(namespace = namespace, fleetName = fleetName),
            backend = Backend(name = name, address = address, port = port),
            ready = ready,
        )
    }

    private fun pickPort(status: List<GameServerPort>, spec: List<GameServerPort>): Int? {
        // Prefer the post-allocation status port (Agones rewrites it for `Dynamic` portPolicy);
        // fall back to spec port for `Static` setups.
        for (preferred in PREFERRED_PORT_NAMES) {
            val statusMatch = status.firstOrNull { it.name == preferred }
            val specMatch = spec.firstOrNull { it.name == preferred }
            val resolved =
                statusMatch?.let { resolvePort(it) } ?: specMatch?.let { resolvePort(it) }
            if (resolved != null) return resolved
        }
        return null
    }

    private fun resolvePort(p: GameServerPort): Int? = p.hostPort ?: p.port ?: p.containerPort
}
