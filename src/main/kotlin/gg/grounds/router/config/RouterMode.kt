package gg.grounds.router.config

enum class RouterMode {
    PLATFORM,
    STAGING;

    companion object {
        fun parse(raw: String): RouterMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown routerMode '$raw' — expected one of: ${entries.joinToString(",") { it.name.lowercase() }}"
                )
    }
}
