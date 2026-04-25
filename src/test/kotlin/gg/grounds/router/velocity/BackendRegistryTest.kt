package gg.grounds.router.velocity

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import gg.grounds.router.config.RouterMode
import gg.grounds.router.k8s.Backend
import gg.grounds.router.k8s.FleetKey
import gg.grounds.router.k8s.GsView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory

class BackendRegistryTest {

    private lateinit var proxy: ProxyServer
    private lateinit var registry: BackendRegistry

    @BeforeEach
    fun setup() {
        proxy = mock()
        whenever(proxy.registerServer(any())).thenAnswer { invocation ->
            val info = invocation.arguments[0] as ServerInfo
            mock<RegisteredServer> { on { serverInfo } doReturn info }
        }
        registry =
            BackendRegistry(
                proxy = proxy,
                mode = RouterMode.PLATFORM,
                clusterName = "alice",
                hostnameSuffix = "platform.mc.grnds.io",
                logger = LoggerFactory.getLogger("test"),
            )
    }

    private fun key(fleet: String = "lobby", ns: String = "default") = FleetKey(ns, fleet)

    private fun view(
        gsName: String,
        ready: Boolean = true,
        fleet: FleetKey = key(),
        addr: String = "10.0.0.1",
        port: Int = 7000,
    ) = GsView(key = fleet, backend = Backend(gsName, addr, port), ready = ready)

    @Test
    fun `first ready GS exposes hostname and chooses it`() {
        registry.upsert(view("gs-1"))

        val server = registry.chooseBackend("lobby.alice.platform.mc.grnds.io")
        assertNotNull(server)
        assertEquals("default--lobby--gs-1", server!!.serverInfo.name)
        verify(proxy).registerServer(any())
    }

    @Test
    fun `multiple ready GSs round-robin`() {
        registry.upsert(view("gs-1", port = 7000))
        registry.upsert(view("gs-2", port = 7001))

        val choices =
            (1..6).map {
                registry.chooseBackend("lobby.alice.platform.mc.grnds.io")?.serverInfo?.name
            }
        assertEquals(2, choices.toSet().size)
        assertFalse(
            choices.zipWithNext().any { (a, b) -> a == b },
            "consecutive picks repeated: $choices",
        )
    }

    @Test
    fun `Unhealthy GS removes itself from rotation but keeps hostname while peers exist`() {
        registry.upsert(view("gs-1", port = 7000))
        registry.upsert(view("gs-2", port = 7001))

        registry.upsert(view("gs-1", ready = false, port = 7000))

        val choices =
            (1..3).map {
                registry.chooseBackend("lobby.alice.platform.mc.grnds.io")?.serverInfo?.name
            }
        assertEquals(setOf("default--lobby--gs-2"), choices.toSet())
    }

    @Test
    fun `last ready GS becoming Unhealthy removes the hostname`() {
        registry.upsert(view("gs-1"))
        registry.upsert(view("gs-1", ready = false))

        assertNull(registry.chooseBackend("lobby.alice.platform.mc.grnds.io"))
    }

    @Test
    fun `delete event removes backend and unregisters server`() {
        registry.upsert(view("gs-1"))
        registry.remove(key(), "gs-1")

        assertNull(registry.chooseBackend("lobby.alice.platform.mc.grnds.io"))
        verify(proxy).unregisterServer(any())
    }

    @Test
    fun `unknown hostname returns null`() {
        registry.upsert(view("gs-1"))
        assertNull(registry.chooseBackend("other.alice.platform.mc.grnds.io"))
    }

    @Test
    fun `staging mode uses namespace-derived hostname`() {
        val staging =
            BackendRegistry(
                proxy = proxy,
                mode = RouterMode.STAGING,
                clusterName = null,
                hostnameSuffix = "preview.mc.grnds.io",
                logger = LoggerFactory.getLogger("test"),
            )
        staging.upsert(
            GsView(
                key = FleetKey("preview-pr-42", "lobby"),
                backend = Backend("gs-1", "10.0.0.5", 7000),
                ready = true,
            )
        )

        assertNotNull(staging.chooseBackend("pr-42.preview.mc.grnds.io"))
        assertNull(staging.chooseBackend("lobby.preview.mc.grnds.io"))
    }

    @Test
    fun `address change re-registers backend`() {
        registry.upsert(view("gs-1", addr = "10.0.0.1", port = 7000))
        registry.upsert(view("gs-1", addr = "10.0.0.2", port = 7000))

        verify(proxy, times(2)).registerServer(any())
    }

    @Test
    fun `concurrent upserts converge to a valid state`() {
        val threads =
            (1..16).map { idx ->
                Thread {
                    repeat(50) { iter ->
                        val name = "gs-${idx % 4}"
                        val ready = (iter and 1) == 0
                        registry.upsert(view(name, ready = ready, port = 7000 + (idx % 4)))
                    }
                }
            }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        val snap = registry.snapshot()[key()].orEmpty()
        assertTrue(snap.size in 0..4, "unexpected ready set: $snap")
    }

    @Test
    fun `forced-host lookup is case-insensitive`() {
        registry.upsert(view("gs-1"))

        assertNotNull(registry.chooseBackend("LOBBY.alice.platform.mc.grnds.io"))
    }
}
