package gg.grounds.router.config

import java.io.Reader
import java.nio.file.Path
import java.time.Duration
import org.slf4j.Logger
import org.tomlj.Toml

/**
 * Plugin configuration loaded from a TOML file. See [Companion.load] for the supported keys.
 *
 * Validation rules (enforced in [Companion.from]):
 * - `routerMode = platform` requires a non-empty `clusterName`.
 * - `routerMode = staging` ignores `clusterName` (warns if set).
 * - `namespaceSelector` must be non-empty.
 * - `hostnameSuffix` must be non-empty.
 */
data class RouterConfig(
    val routerMode: RouterMode,
    val clusterName: String?,
    val namespaceSelector: String,
    val hostnameSuffix: String,
    val resyncInterval: Duration,
) {
    companion object {
        private val KNOWN_KEYS =
            setOf(
                "routerMode",
                "clusterName",
                "namespaceSelector",
                "hostnameSuffix",
                "resyncInterval",
            )

        private val ENV_PATTERN = Regex("""\$\{([A-Z_][A-Z0-9_]*)\}""")

        fun load(path: Path, logger: Logger? = null, env: Map<String, String> = System.getenv()) =
            path.toFile().reader().use { from(it, logger, env) }

        fun from(
            reader: Reader,
            logger: Logger? = null,
            env: Map<String, String> = System.getenv(),
        ): RouterConfig {
            val parsed = Toml.parse(reader)
            if (parsed.hasErrors()) {
                throw IllegalArgumentException(
                    "TOML parse errors: " + parsed.errors().joinToString(", ") { it.toString() }
                )
            }

            parsed
                .dottedKeySet(true)
                .filter { it !in KNOWN_KEYS }
                .forEach { logger?.warn("router config: unknown key '{}' (ignored)", it) }

            val routerMode = RouterMode.parse(string(parsed, "routerMode") ?: "platform")
            val rawClusterName = string(parsed, "clusterName")?.let { interpolate(it, env) }
            val namespaceSelector =
                string(parsed, "namespaceSelector")?.takeIf { it.isNotEmpty() }
                    ?: defaultNamespaceSelector(routerMode)
            val hostnameSuffix =
                string(parsed, "hostnameSuffix")?.takeIf { it.isNotEmpty() }
                    ?: defaultHostnameSuffix(routerMode)
            val resyncInterval =
                string(parsed, "resyncInterval")?.let { parseDuration(it) }
                    ?: Duration.ofSeconds(30)

            val clusterName =
                when (routerMode) {
                    RouterMode.PLATFORM -> {
                        val effective = rawClusterName?.takeIf { it.isNotEmpty() }
                        require(effective != null) {
                            "routerMode=platform requires non-empty clusterName " +
                                "(got '${rawClusterName ?: ""}'); " +
                                "set GROUNDS_CLUSTER_NAME or pin clusterName in config"
                        }
                        effective
                    }
                    RouterMode.STAGING -> {
                        if (!rawClusterName.isNullOrEmpty()) {
                            logger?.warn(
                                "router config: clusterName='{}' is ignored in staging mode",
                                rawClusterName,
                            )
                        }
                        null
                    }
                }

            return RouterConfig(
                routerMode = routerMode,
                clusterName = clusterName,
                namespaceSelector = namespaceSelector,
                hostnameSuffix = hostnameSuffix,
                resyncInterval = resyncInterval,
            )
        }

        private fun string(parsed: org.tomlj.TomlParseResult, key: String): String? =
            if (parsed.contains(key) && parsed.isString(key)) parsed.getString(key) else null

        private fun interpolate(raw: String, env: Map<String, String>): String =
            ENV_PATTERN.replace(raw) { match -> env[match.groupValues[1]] ?: "" }

        private fun parseDuration(raw: String): Duration {
            val match =
                Regex("""^(\d+)\s*(s|m|h)$""").matchEntire(raw.trim())
                    ?: throw IllegalArgumentException(
                        "resyncInterval '$raw' must look like '30s', '5m', or '1h'"
                    )
            val n = match.groupValues[1].toLong()
            return when (match.groupValues[2]) {
                "s" -> Duration.ofSeconds(n)
                "m" -> Duration.ofMinutes(n)
                "h" -> Duration.ofHours(n)
                else -> error("unreachable")
            }
        }

        private fun defaultNamespaceSelector(mode: RouterMode): String =
            when (mode) {
                RouterMode.PLATFORM -> "default"
                RouterMode.STAGING -> "preview-*"
            }

        private fun defaultHostnameSuffix(mode: RouterMode): String =
            when (mode) {
                RouterMode.PLATFORM -> "platform.mc.grnds.io"
                RouterMode.STAGING -> "preview.mc.grnds.io"
            }
    }
}
