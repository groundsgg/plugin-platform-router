package gg.grounds.router.velocity

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import org.slf4j.Logger

/**
 * Routes a connecting player to a backend in the matching fleet. We run at [PostOrder.LATE] so any
 * plugin ahead of us (matchmaker, lobby chooser) keeps precedence — we only intervene when
 * `initialServer` is still empty by then.
 */
class PlayerRoutingListener(private val registry: BackendRegistry, private val logger: Logger) {
    @Subscribe(order = PostOrder.LATE)
    fun onChoose(event: PlayerChooseInitialServerEvent) {
        if (event.initialServer.isPresent) return

        val virtualHost =
            event.player.virtualHost.orElse(null)?.hostString
                ?: run {
                    logger.debug(
                        "platform-router: player {} has no virtual host",
                        event.player.username,
                    )
                    return
                }

        val server = registry.chooseBackend(virtualHost)
        if (server != null) {
            event.setInitialServer(server)
            logger.info(
                "platform-router: routed {} via {} -> {}",
                event.player.username,
                virtualHost,
                server.serverInfo.name,
            )
        } else {
            logger.info(
                "platform-router: no backend for virtualHost={} (player={})",
                virtualHost,
                event.player.username,
            )
        }
    }
}
