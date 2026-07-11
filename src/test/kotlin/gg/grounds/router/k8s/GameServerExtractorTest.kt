package gg.grounds.router.k8s

import io.fabric8.kubernetes.api.model.ObjectMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameServerExtractorTest {

    private fun gs(
        name: String = "lobby-1",
        namespace: String = "default",
        fleet: String? = "lobby",
        state: String? = "Ready",
        address: String? = "10.0.0.1",
        addresses: List<GameServerAddress> = emptyList(),
        statusPorts: List<GameServerPort> = listOf(GameServerPort(name = "mc", hostPort = 7000)),
        specPorts: List<GameServerPort> = emptyList(),
    ): GameServer =
        GameServer().apply {
            metadata =
                ObjectMeta().apply {
                    this.name = name
                    this.namespace = namespace
                    if (fleet != null) {
                        this.labels = mapOf(AGONES_FLEET_LABEL to fleet)
                    }
                }
            status =
                GameServerStatus(
                    state = state,
                    address = address,
                    addresses = addresses,
                    ports = statusPorts,
                )
            spec = GameServerSpec(ports = specPorts)
        }

    /** What Agones publishes for a `portPolicy: None` fleet: a PodIP, and no allocated hostPort. */
    private fun podIpFleet(podIp: String? = "10.244.3.7", containerPort: Int? = 25565): GameServer =
        gs(
            addresses =
                listOfNotNull(
                    GameServerAddress(address = "node-1", type = "Hostname"),
                    GameServerAddress(address = "10.0.0.1", type = "InternalIP"),
                    podIp?.let { GameServerAddress(address = it, type = "PodIP") },
                ),
            statusPorts = emptyList(),
            specPorts = listOf(GameServerPort(name = "minecraft", containerPort = containerPort)),
        )

    @Test
    fun `portPolicy None fleet routes to the pod IP and containerPort`() {
        val view = GameServerExtractor.extract(podIpFleet())

        assertNotNull(view)
        assertEquals(Backend("lobby-1", "10.244.3.7", 25565), view!!.backend)
        assertTrue(view.ready)
    }

    @Test
    fun `pod IP is preferred over the node address even when a hostPort exists`() {
        // A legacy Dynamic fleet still carries a hostPort; the pod IP is the better route and must
        // win, so fleets migrate without a flag day.
        val view =
            GameServerExtractor.extract(
                gs(
                    addresses = listOf(GameServerAddress(address = "10.244.3.7", type = "PodIP")),
                    statusPorts = listOf(GameServerPort(name = "mc", hostPort = 7000)),
                    specPorts = listOf(GameServerPort(name = "mc", containerPort = 25565)),
                )
            )

        assertEquals(Backend("lobby-1", "10.244.3.7", 25565), view?.backend)
    }

    @Test
    fun `falls back to node address and hostPort when no pod IP is published`() {
        val view = GameServerExtractor.extract(gs())

        assertEquals(Backend("lobby-1", "10.0.0.1", 7000), view?.backend)
    }

    @Test
    fun `pod IP without a containerPort falls back to the node address`() {
        val view =
            GameServerExtractor.extract(
                gs(
                    addresses = listOf(GameServerAddress(address = "10.244.3.7", type = "PodIP")),
                    statusPorts = listOf(GameServerPort(name = "mc", hostPort = 7000)),
                    specPorts = emptyList(),
                )
            )

        assertEquals(Backend("lobby-1", "10.0.0.1", 7000), view?.backend)
    }

    @Test
    fun `GS with neither pod IP nor node address is skipped`() {
        val view =
            GameServerExtractor.extract(podIpFleet(podIp = null).apply { status?.address = null })

        assertNull(view)
    }

    @Test
    fun `Ready GS with mc port produces ready view`() {
        val view = GameServerExtractor.extract(gs())

        assertNotNull(view)
        view!!
        assertEquals(FleetKey("default", "lobby"), view.key)
        assertEquals(Backend("lobby-1", "10.0.0.1", 7000), view.backend)
        assertTrue(view.ready)
    }

    @Test
    fun `Allocated GS is treated as ready`() {
        val view = GameServerExtractor.extract(gs(state = "Allocated"))
        assertEquals(true, view?.ready)
    }

    @Test
    fun `Reserved GS is not ready but still emitted`() {
        val view = GameServerExtractor.extract(gs(state = "Reserved"))
        assertNotNull(view)
        assertEquals(false, view?.ready)
    }

    @Test
    fun `Unhealthy GS is not ready but still emitted`() {
        val view = GameServerExtractor.extract(gs(state = "Unhealthy"))
        assertNotNull(view)
        assertEquals(false, view?.ready)
    }

    @Test
    fun `Shutdown GS is not ready but still emitted`() {
        val view = GameServerExtractor.extract(gs(state = "Shutdown"))
        assertNotNull(view)
        assertEquals(false, view?.ready)
    }

    @Test
    fun `Scheduled GS with no address is skipped entirely`() {
        val view = GameServerExtractor.extract(gs(state = "Scheduled", address = null))
        assertNull(view)
    }

    @Test
    fun `GS with empty address is skipped`() {
        val view = GameServerExtractor.extract(gs(address = ""))
        assertNull(view)
    }

    @Test
    fun `GS without fleet label is skipped`() {
        val view = GameServerExtractor.extract(gs(fleet = null))
        assertNull(view)
    }

    @Test
    fun `picks mc when both mc and default ports are present`() {
        val view =
            GameServerExtractor.extract(
                gs(
                    statusPorts =
                        listOf(
                            GameServerPort(name = "default", hostPort = 6000),
                            GameServerPort(name = "mc", hostPort = 7000),
                        )
                )
            )

        assertEquals(7000, view?.backend?.port)
    }

    @Test
    fun `falls back to default port when mc is absent`() {
        val view =
            GameServerExtractor.extract(
                gs(
                    statusPorts =
                        listOf(
                            GameServerPort(name = "metrics", hostPort = 9000),
                            GameServerPort(name = "default", hostPort = 6000),
                        )
                )
            )

        assertEquals(6000, view?.backend?.port)
    }

    @Test
    fun `skips GS with no mc and no default port`() {
        val view =
            GameServerExtractor.extract(
                gs(statusPorts = listOf(GameServerPort(name = "metrics", hostPort = 9000)))
            )

        assertNull(view)
    }

    @Test
    fun `falls back to spec ports when status has no usable port`() {
        val view =
            GameServerExtractor.extract(
                gs(
                    statusPorts = emptyList(),
                    specPorts = listOf(GameServerPort(name = "mc", containerPort = 25565)),
                )
            )

        assertEquals(25565, view?.backend?.port)
    }

    @Test
    fun `prefers hostPort over port over containerPort`() {
        val view =
            GameServerExtractor.extract(
                gs(
                    statusPorts =
                        listOf(
                            GameServerPort(
                                name = "mc",
                                hostPort = 7001,
                                port = 7002,
                                containerPort = 25565,
                            )
                        )
                )
            )

        assertEquals(7001, view?.backend?.port)
    }
}
