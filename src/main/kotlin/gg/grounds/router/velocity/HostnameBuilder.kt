package gg.grounds.router.velocity

import gg.grounds.router.config.RouterMode

object HostnameBuilder {

    private const val STAGING_NS_PREFIX = "preview-"

    /**
     * Compose a forced-host hostname for a [namespace]/[fleetName] in [mode].
     * - Platform: `{fleet}.{cluster}.{suffix}` — `clusterName` must be set.
     * - Staging: `{namespace-suffix}.{suffix}` — namespace must start with `preview-`; `fleetName`
     *   is unused.
     */
    fun build(
        mode: RouterMode,
        namespace: String,
        fleetName: String,
        clusterName: String?,
        hostnameSuffix: String,
    ): String =
        when (mode) {
            RouterMode.PLATFORM -> {
                require(!clusterName.isNullOrBlank()) {
                    "platform mode requires a non-blank clusterName"
                }
                "$fleetName.$clusterName.$hostnameSuffix".lowercase()
            }
            RouterMode.STAGING -> {
                require(namespace.startsWith(STAGING_NS_PREFIX)) {
                    "staging mode expects namespace to start with '$STAGING_NS_PREFIX' (got '$namespace')"
                }
                val suffix = namespace.removePrefix(STAGING_NS_PREFIX)
                require(suffix.isNotEmpty()) {
                    "staging mode namespace '$namespace' has no suffix after '$STAGING_NS_PREFIX'"
                }
                "$suffix.$hostnameSuffix".lowercase()
            }
        }
}
