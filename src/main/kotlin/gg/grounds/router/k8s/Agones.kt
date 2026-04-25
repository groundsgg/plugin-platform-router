package gg.grounds.router.k8s

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Kind
import io.fabric8.kubernetes.model.annotation.Plural
import io.fabric8.kubernetes.model.annotation.Version

const val AGONES_GROUP = "agones.dev"
const val AGONES_VERSION = "v1"
const val AGONES_FLEET_LABEL = "agones.dev/fleet"

/**
 * Minimal hand-written model of the Agones `GameServer` CRD. We only deserialise the fields the
 * router actually uses; the rest of the resource is ignored. If Agones evolves the schema we can
 * add fields here without touching the watch wiring.
 */
@Group(AGONES_GROUP)
@Version(AGONES_VERSION)
@Kind("GameServer")
@Plural("gameservers")
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
class GameServer : CustomResource<GameServerSpec, GameServerStatus>(), Namespaced

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
class GameServerSpec(var ports: List<GameServerPort> = emptyList())

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
class GameServerStatus(
    var state: String? = null,
    var address: String? = null,
    var ports: List<GameServerPort> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
class GameServerPort(
    var name: String? = null,
    var port: Int? = null,
    var hostPort: Int? = null,
    var containerPort: Int? = null,
    var protocol: String? = null,
)
