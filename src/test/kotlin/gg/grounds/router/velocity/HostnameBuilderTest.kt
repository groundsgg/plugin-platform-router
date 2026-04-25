package gg.grounds.router.velocity

import gg.grounds.router.config.RouterMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HostnameBuilderTest {

    @Test
    fun `platform renders fleet-cluster-suffix`() {
        val host =
            HostnameBuilder.build(
                mode = RouterMode.PLATFORM,
                namespace = "default",
                fleetName = "lobby",
                clusterName = "alice",
                hostnameSuffix = "platform.mc.grnds.io",
            )
        assertEquals("lobby.alice.platform.mc.grnds.io", host)
    }

    @Test
    fun `staging renders namespace-suffix-suffix and ignores fleet`() {
        val host =
            HostnameBuilder.build(
                mode = RouterMode.STAGING,
                namespace = "preview-pr-42",
                fleetName = "lobby",
                clusterName = null,
                hostnameSuffix = "preview.mc.grnds.io",
            )
        assertEquals("pr-42.preview.mc.grnds.io", host)
    }

    @Test
    fun `platform mode rejects null clusterName`() {
        assertThrows(IllegalArgumentException::class.java) {
            HostnameBuilder.build(
                mode = RouterMode.PLATFORM,
                namespace = "default",
                fleetName = "lobby",
                clusterName = null,
                hostnameSuffix = "platform.mc.grnds.io",
            )
        }
    }

    @Test
    fun `platform mode rejects blank clusterName`() {
        assertThrows(IllegalArgumentException::class.java) {
            HostnameBuilder.build(
                mode = RouterMode.PLATFORM,
                namespace = "default",
                fleetName = "lobby",
                clusterName = "  ",
                hostnameSuffix = "platform.mc.grnds.io",
            )
        }
    }

    @Test
    fun `staging mode rejects namespace without preview prefix`() {
        assertThrows(IllegalArgumentException::class.java) {
            HostnameBuilder.build(
                mode = RouterMode.STAGING,
                namespace = "default",
                fleetName = "lobby",
                clusterName = null,
                hostnameSuffix = "preview.mc.grnds.io",
            )
        }
    }

    @Test
    fun `staging mode rejects empty namespace suffix`() {
        assertThrows(IllegalArgumentException::class.java) {
            HostnameBuilder.build(
                mode = RouterMode.STAGING,
                namespace = "preview-",
                fleetName = "lobby",
                clusterName = null,
                hostnameSuffix = "preview.mc.grnds.io",
            )
        }
    }

    @Test
    fun `lowercases output`() {
        val host =
            HostnameBuilder.build(
                mode = RouterMode.PLATFORM,
                namespace = "default",
                fleetName = "Lobby",
                clusterName = "Alice",
                hostnameSuffix = "PLATFORM.MC.grnds.io",
            )
        assertEquals("lobby.alice.platform.mc.grnds.io", host)
    }
}
