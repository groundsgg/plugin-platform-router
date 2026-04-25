package gg.grounds.router.config

import java.io.StringReader
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RouterConfigTest {

    @Test
    fun `loads valid platform config`() {
        val toml =
            """
            routerMode = "platform"
            clusterName = "alice"
            namespaceSelector = "default"
            hostnameSuffix = "platform.mc.grnds.io"
            resyncInterval = "30s"
            """
                .trimIndent()

        val cfg = RouterConfig.from(StringReader(toml), env = emptyMap())

        assertEquals(RouterMode.PLATFORM, cfg.routerMode)
        assertEquals("alice", cfg.clusterName)
        assertEquals("default", cfg.namespaceSelector)
        assertEquals("platform.mc.grnds.io", cfg.hostnameSuffix)
        assertEquals(Duration.ofSeconds(30), cfg.resyncInterval)
    }

    @Test
    fun `loads valid staging config and ignores clusterName`() {
        val toml =
            """
            routerMode = "staging"
            namespaceSelector = "preview-*"
            hostnameSuffix = "preview.mc.grnds.io"
            """
                .trimIndent()

        val cfg = RouterConfig.from(StringReader(toml), env = emptyMap())

        assertEquals(RouterMode.STAGING, cfg.routerMode)
        assertNull(cfg.clusterName)
        assertEquals("preview-*", cfg.namespaceSelector)
    }

    @Test
    fun `interpolates env variables in clusterName`() {
        val toml =
            """
            routerMode = "platform"
            clusterName = "${'$'}{GROUNDS_CLUSTER_NAME}"
            """
                .trimIndent()

        val cfg =
            RouterConfig.from(StringReader(toml), env = mapOf("GROUNDS_CLUSTER_NAME" to "bob"))

        assertEquals("bob", cfg.clusterName)
    }

    @Test
    fun `platform mode requires non-empty clusterName`() {
        val toml =
            """
            routerMode = "platform"
            clusterName = "${'$'}{MISSING_VAR}"
            """
                .trimIndent()

        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                RouterConfig.from(StringReader(toml), env = emptyMap())
            }
        assertEquals(true, ex.message?.contains("clusterName"))
    }

    @Test
    fun `unknown routerMode value rejected`() {
        val toml = """routerMode = "magic""""

        assertThrows(IllegalArgumentException::class.java) {
            RouterConfig.from(StringReader(toml), env = emptyMap())
        }
    }

    @Test
    fun `defaults applied for staging when keys omitted`() {
        val cfg = RouterConfig.from(StringReader("""routerMode = "staging""""), env = emptyMap())

        assertEquals("preview-*", cfg.namespaceSelector)
        assertEquals("preview.mc.grnds.io", cfg.hostnameSuffix)
        assertEquals(Duration.ofSeconds(30), cfg.resyncInterval)
    }

    @Test
    fun `parses resyncInterval in minutes and hours`() {
        val cfgM =
            RouterConfig.from(
                StringReader(
                    """
                    routerMode = "platform"
                    clusterName = "x"
                    resyncInterval = "5m"
                    """
                        .trimIndent()
                ),
                env = emptyMap(),
            )
        val cfgH =
            RouterConfig.from(
                StringReader(
                    """
                    routerMode = "platform"
                    clusterName = "x"
                    resyncInterval = "1h"
                    """
                        .trimIndent()
                ),
                env = emptyMap(),
            )

        assertEquals(Duration.ofMinutes(5), cfgM.resyncInterval)
        assertEquals(Duration.ofHours(1), cfgH.resyncInterval)
    }

    @Test
    fun `malformed resyncInterval rejected`() {
        val toml =
            """
            routerMode = "platform"
            clusterName = "x"
            resyncInterval = "thirty seconds"
            """
                .trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            RouterConfig.from(StringReader(toml), env = emptyMap())
        }
    }

    @Test
    fun `invalid TOML rejected`() {
        val toml = "routerMode ="

        assertThrows(IllegalArgumentException::class.java) {
            RouterConfig.from(StringReader(toml), env = emptyMap())
        }
    }

    @Test
    fun `unknown keys do not fail the load`() {
        val toml =
            """
            routerMode = "platform"
            clusterName = "x"
            mysteryKey = "ignored"
            """
                .trimIndent()

        // Should not throw — unknown keys are warned but not fatal.
        val cfg = RouterConfig.from(StringReader(toml), env = emptyMap())
        assertEquals(RouterMode.PLATFORM, cfg.routerMode)
    }
}
