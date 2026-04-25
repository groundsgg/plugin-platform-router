package gg.grounds.router.k8s

import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

@EnableKubernetesMockClient(crud = true)
class GameServerWatcherTest {

    // Injected by @EnableKubernetesMockClient
    private lateinit var server: KubernetesMockServer
    private lateinit var client: io.fabric8.kubernetes.client.KubernetesClient

    private val upserts = ConcurrentLinkedQueue<GsView>()
    private val deletes = ConcurrentLinkedQueue<Pair<FleetKey, String>>()
    private var watcher: GameServerWatcher? = null

    @AfterEach
    fun tearDown() {
        watcher?.stop()
    }

    private fun startWatcher(
        mode: GameServerWatcher.WatchMode = GameServerWatcher.WatchMode.PLATFORM,
        namespaceSelector: String = "default",
    ) {
        watcher =
            GameServerWatcher(
                client = client,
                mode = mode,
                namespaceSelector = namespaceSelector,
                resyncInterval = Duration.ofSeconds(60),
                onUpsert = { upserts.add(it) },
                onDelete = { key, name -> deletes.add(key to name) },
                logger = LoggerFactory.getLogger("test"),
            )
        watcher!!.start()
    }

    private fun gs(
        name: String,
        namespace: String = "default",
        fleet: String = "lobby",
        state: String = "Ready",
        address: String = "10.0.0.1",
        port: Int = 7000,
    ) =
        GameServer().apply {
            metadata =
                ObjectMeta().apply {
                    this.name = name
                    this.namespace = namespace
                    this.labels = mapOf(AGONES_FLEET_LABEL to fleet)
                }
            status =
                GameServerStatus(
                    state = state,
                    address = address,
                    ports = listOf(GameServerPort(name = "mc", hostPort = port)),
                )
            spec = GameServerSpec()
        }

    @Test
    fun `add event surfaces ready GsView`() {
        startWatcher()

        client
            .resources(GameServer::class.java)
            .inNamespace("default")
            .resource(gs("gs-1"))
            .create()

        await().atMost(5, TimeUnit.SECONDS).until { upserts.isNotEmpty() }
        val view = upserts.first()
        assertEquals("gs-1", view.backend.name)
        assertEquals(true, view.ready)
    }

    @Test
    fun `update Ready to Unhealthy emits ready=false`() {
        startWatcher()

        val ops = client.resources(GameServer::class.java).inNamespace("default")
        ops.resource(gs("gs-1", state = "Ready")).create()
        await().atMost(5, TimeUnit.SECONDS).until { upserts.isNotEmpty() }

        val readyEvents = upserts.size
        ops.resource(gs("gs-1", state = "Unhealthy")).update()

        await().atMost(5, TimeUnit.SECONDS).until { upserts.size > readyEvents }
        val terminal = upserts.last()
        assertEquals(false, terminal.ready)
    }

    @Test
    fun `delete event invokes onDelete with FleetKey`() {
        startWatcher()

        val ops = client.resources(GameServer::class.java).inNamespace("default")
        ops.resource(gs("gs-1")).create()
        await().atMost(5, TimeUnit.SECONDS).until { upserts.isNotEmpty() }

        ops.withName("gs-1").delete()
        await().atMost(5, TimeUnit.SECONDS).until { deletes.isNotEmpty() }

        val (key, name) = deletes.first()
        assertEquals(FleetKey("default", "lobby"), key)
        assertEquals("gs-1", name)
    }

    @Test
    fun `staging mode filters out non-preview namespaces`() {
        startWatcher(mode = GameServerWatcher.WatchMode.STAGING, namespaceSelector = "preview-*")

        client
            .resources(GameServer::class.java)
            .inNamespace("default")
            .resource(gs("gs-default"))
            .create()
        client
            .resources(GameServer::class.java)
            .inNamespace("preview-pr-42")
            .resource(gs("gs-preview", namespace = "preview-pr-42"))
            .create()

        await().atMost(5, TimeUnit.SECONDS).until { upserts.isNotEmpty() }
        // Allow the second event a moment to arrive (or be filtered out)
        Thread.sleep(500)

        val seen = upserts.map { it.backend.name }.toSet()
        assertTrue("gs-preview" in seen, "expected preview namespace event, got: $seen")
        assertTrue("gs-default" !in seen, "default namespace must not be visible in staging: $seen")
    }

    @Test
    fun `malformed gameserver does not stall the watch`() {
        startWatcher()

        // Missing fleet label — extractor will return null but watch must keep running.
        val malformed =
            GameServer().apply {
                metadata =
                    ObjectMeta().apply {
                        name = "no-fleet"
                        namespace = "default"
                    }
                status = GameServerStatus(state = "Ready", address = "10.0.0.9")
                spec = GameServerSpec()
            }

        val ops = client.resources(GameServer::class.java).inNamespace("default")
        ops.resource(malformed).create()
        ops.resource(gs("gs-good")).create()

        await().atMost(5, TimeUnit.SECONDS).until { upserts.any { it.backend.name == "gs-good" } }
        assertTrue(
            upserts.none { it.backend.name == "no-fleet" },
            "extractor should have skipped fleet-less GS",
        )
    }

    @Test
    fun `multiple fleets in same namespace are all surfaced`() {
        startWatcher()

        val ops = client.resources(GameServer::class.java).inNamespace("default")
        ops.resource(gs("gs-l1", fleet = "lobby")).create()
        ops.resource(gs("gs-a1", fleet = "arena")).create()

        await().atMost(5, TimeUnit.SECONDS).until { upserts.size >= 2 }
        val byFleet = upserts.groupBy { it.key.fleetName }.mapValues { it.value.size }
        assertTrue(byFleet["lobby"]?.let { it >= 1 } == true, "expected lobby events: $byFleet")
        assertTrue(byFleet["arena"]?.let { it >= 1 } == true, "expected arena events: $byFleet")
    }

    @Test
    fun `stop is idempotent`() {
        startWatcher()
        watcher!!.stop()
        watcher!!.stop()
        // No exception = pass
    }

    companion object {
        // Suppress the unused-but-injected fields warning
        @Suppress("unused") private val tracker = ConcurrentHashMap<String, Boolean>()
    }
}
