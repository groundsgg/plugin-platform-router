# plugin-platform-router

Velocity plugin that watches Agones `GameServer` CRDs in Kubernetes and exposes
them as Velocity backends with virtual-host-driven routing. Sub-project of
[grounds-platform](https://github.com/groundsgg/grounds-platform) Phase 3.1a.

The same plugin runs in two modes:

| Mode       | Watch scope                                  | Hostname format                                |
| ---------- | -------------------------------------------- | ---------------------------------------------- |
| `platform` | one namespace inside a per-dev vCluster      | `{fleet}.{cluster}.platform.mc.grnds.io`       |
| `staging`  | all `preview-*` namespaces in shared staging | `{namespace-suffix}.preview.mc.grnds.io`       |

Mode is selected via the bundled `config.toml` (mounted by the Helm chart
in production).

## How it works

1. On `ProxyInitializeEvent`, the plugin loads its config, builds an in-cluster
   `KubernetesClient`, and starts a fabric8 `SharedIndexInformer` against
   `gameservers.agones.dev/v1`.
2. Every Agones `GameServer` reaching `Ready` (or `Allocated`) is registered
   with Velocity via `proxy.registerServer(ServerInfo)`. The forced-host
   hostname is computed from the GS's namespace + fleet label.
3. A `PlayerChooseInitialServerEvent` listener (PostOrder.LATE) inspects the
   connecting player's virtual host, looks up the matching fleet, and picks a
   ready backend round-robin.
4. State changes (Unhealthy / Reserved / Shutdown) deregister the backend.
   When the last ready backend in a fleet goes away, the hostname is removed
   and any further connection to it is rejected with "no available servers".

## Configuration

```toml
# Mode selector. "platform" or "staging".
routerMode = "platform"

# Cluster identifier — populated from GROUNDS_CLUSTER_NAME env by the chart.
# Required in platform mode. Ignored (warned) in staging mode.
clusterName = "${GROUNDS_CLUSTER_NAME}"

# Namespace watch scope.
# Platform: a single namespace name.
# Staging: a glob (currently honoured client-side as a "preview-" prefix).
namespaceSelector = "default"

# DNS suffix appended after {fleet}.{cluster} (platform) or
# {namespace-suffix} (staging).
hostnameSuffix = "platform.mc.grnds.io"

# Re-sync interval for the informer. Accepts 30s / 5m / 1h.
resyncInterval = "30s"
```

`${ENV_VAR}` interpolation is supported. `clusterName` is the typical
hand-off point with the chart.

## RBAC

The plugin needs `get`, `list`, `watch` on `gameservers.agones.dev`:

- Platform mode: scoped to the configured namespace via `Role` + `RoleBinding`.
- Staging mode: cluster-wide via `ClusterRole` + `ClusterRoleBinding` (the
  `preview-*` filter happens client-side).

The Helm chart [grounds-platform-template](https://github.com/groundsgg/grounds-platform-template)
provisions both.

## Build

```sh
./gradlew build      # compiles, runs tests, produces the shadow JAR
./gradlew shadowJar  # just the artifact at build/libs/plugin-platform-router-<version>-all.jar
./gradlew spotlessApply # auto-format Kotlin sources
```

JDK 25 toolchain, JVM 24 target. Velocity API 3.5.0-SNAPSHOT.

## Local smoke (kind + Agones)

End-to-end smoke:

1. `kind create cluster`
2. Install Agones via Helm (`helm install agones agones/agones --namespace agones-system --create-namespace`).
3. Apply a sample Fleet (e.g. the `simple-game-server` fleet from Agones examples).
4. `docker run --rm -v $PWD/build/libs:/plugins -v $HOME/.kube/config:/root/.kube/config:ro itzg/mc-proxy ...`
   (or run Velocity locally with `KUBECONFIG` pointed at kind).
5. Connect a Minecraft client to `<fleet>.<cluster>.platform.mc.grnds.io`
   (resolve via `/etc/hosts` for local testing) and verify the connection
   reaches the GS.

## Status

| Phase | What | State |
| --- | --- | --- |
| 3.1a sub-project 1 | plugin-platform-router | In progress (this repo) |

## License

Apache-2.0.
