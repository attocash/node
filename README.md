# Atto Node

Atto Node is the network-facing service for the **Atto** cryptocurrency. It exposes a public HTTP API for
submitting/querying blocks (transactions), maintains account state in a MySQL database, connects to other nodes over
WebSockets, and participates in consensus by producing and relaying votes.

This repository is a **Kotlin + Spring Boot (WebFlux)** application with dedicated **Reactor Netty WebSocket/HTTP transports**
for node-to-node communication and remote signing. Peer server, peer client and signer I/O resources are isolated from each
other and from the public HTTP API. See [Network Layer](NETWORK.md) for transport ownership and authentication.

[Website](https://atto.cash/) | [Docs](https://atto.cash/docs/integration) | [Commons](https://github.com/attocash/commons)

## Table of contents

- [What this node does](#what-this-node-does)
- [Quick start (local dev)](#quick-start-local-dev)
- [Configuration](#configuration)
- [Ports](#ports)
- [API](#api)
- [How consensus works (high level)](#how-consensus-works-high-level)
- [Bootstrapping / catching up](#bootstrapping--catching-up)
- [Code map](#code-map)
- [Docker images](#docker-images)
- [Native image metadata](#native-image-metadata)
- [Troubleshooting](#troubleshooting)

## What this node does

At a high level, this service:

1. **Accepts transactions** via REST (`POST /transactions`) and via the peer network (WebSocket messages).
2. **Validates** incoming transactions against account state.
3. If valid and confirmed by the network, it **applies** them to the account ledger state stored in MySQL.
4. **Participates in voting/consensus** (if configured as a voter) by producing signed votes and gossiping them.
5. **Bootstraps / discovers missing data** by requesting and replaying missing transactions from peers.

The node can behave as:

- **Voter**: signs and broadcasts votes (requires sufficient voting weight and a configured signer key).
- **Historical**: stores final votes / serves as a data source for bootstrap flows.
- **API node**: exposes the REST API (this project does by default).

(Some roles can be forced via config; see `application-default.yaml` and `application.yaml`.)

## Quick start (local dev)

### Prerequisites

- **Java 25** (the Gradle toolchain is pinned to 25 in `build.gradle.kts`)
- **Docker** available locally (required by Testcontainers)

### 1) Run the quickest local node (`TestApplication`)

The quickest way to run the node locally is `TestApplication` (`src/test/kotlin/cash/atto/node/TestApplication.kt`). Run
that class directly from your IDE; it starts the node and spins up MySQL automatically through Testcontainers.

On startup, the node will:

- run Flyway migrations against the configured MySQL
- start the REST API
- start the node-to-node WebSocket server

Cucumber uses random HTTP and management ports for its test node. Run `./gradlew test` to test with neighbours built from the
current checkout. For compatibility with the released node, run
`./gradlew test -Datto.test.neighbour-mode=live`. This mode pulls `ghcr.io/attocash/node:live` once, reuses the resolved image
throughout the run, and logs its digest and release metadata. It requires a local Linux container engine with host networking.
Released neighbours use their production APIs and scheduled bootstrap processing; test-only controls remain on classpath nodes.

CI runs the full suite through the Terraform-managed classpath workflow. The node-owned
[compatibility workflow](.github/workflows/compatibility.yaml) runs Cucumber against `:live` for pull requests and is also
called by the [pipeline](.github/workflows/pipeline.yaml). Builds require both checks; native-image metadata comes from the
classpath run. Keep released-node checks in the node-owned workflow so Terraform can regenerate the shared workflows.

## Configuration

Configuration is split across `application.yaml` and profile overrides such as:

- `application-default.yaml` (opinionated defaults for local)
- `application-local.yaml` (local developer settings)
- `application-dev.yaml`, `application-beta.yaml`, `application-live.yaml`

The main config namespace is `atto.*`.

### Database

The service uses **R2DBC** for runtime access and **JDBC** for Flyway:

- `ATTO_DB_HOST` (default `localhost` in local/default profiles)
- `ATTO_DB_PORT` (default `3306`)
- `ATTO_DB_NAME` (default `atto`)
- `ATTO_DB_USER` (default `root`)
- `ATTO_DB_PASSWORD` (default empty)

### Node identity / public URI

The node announces its public URI to peers:

- `ATTO_PUBLIC_URI` (required by `application.yaml`)

In `application-default.yaml` the default is:

- `atto.node.public-uri = ws://localhost:${websocket.port}`

### Signer (how the node signs votes)

Voting requires a signer backend:

- `ATTO_SIGNER_BACKEND` (default `local`)
- `ATTO_PRIVATE_KEY` (used by local signer backend)

There is also support for a remote signer:

- `ATTO_SIGNER_REMOTE_URL`
- `ATTO_SIGNER_REMOTE_TOKEN`

### Network seeds

To peer with other nodes, configure seed URIs:

- `ATTO_DEFAULT_NODES`

In `application-dev.yaml` there are example `wss://...` nodes.

## Ports

From `application.yaml`:

- **8080**: public REST API (Spring WebFlux)
- **8081**: management/metrics (Actuator + Prometheus)
- **8082**: node-to-node WebSocket server (Reactor Netty)

Swagger UI is configured at:

- `:8080/` (see `springdoc.swagger-ui.path: /`)

The container image defines `/app/node healthcheck` as its Docker health check. The command probes the aggregate
`:8081/health` endpoint. Podman ignores the Docker health-check configuration embedded in OCI images, so
Podman Compose deployments must declare it explicitly:

```yaml
healthcheck:
  test: ["CMD", "/app/node", "healthcheck"]
  interval: 60s
  timeout: 5s
  start_period: 180s
  retries: 5
```

Kubernetes deployments should use `:8081/health/liveness` for startup and liveness probes and
`:8081/health/readiness` for readiness probes.

## API

This project exposes endpoints for:

- **Transactions**: submit, query, and stream
  - `POST /transactions`
  - `POST /v2/transactions` (publish and wait for confirmation)
  - `GET /transactions/{hash}`
  - `GET /v2/transactions/stream` (NDJSON live stream)
- **Accounts**: query and stream account snapshots
  - `POST /accounts` (legacy response format)
  - `POST /v2/accounts` (finite batch snapshots)
  - `GET /v2/accounts/{address}`
  - `POST /v2/accounts/stream` (NDJSON)
- **Receivables**, **Account Entries**, **Vote weight**, and **time** utilities

Single-account v2 routes accept a full account address encoded as one path segment: `atto://...` becomes
`atto%3A%2F%2F...`. Encode it once; raw slashes or double encoding do not identify an account route.

| Operation | Route |
| --- | --- |
| Account snapshot | `GET /v2/accounts/{address}` |
| Batch account snapshots | `POST /v2/accounts` with an `addresses` array |
| Account updates | `GET /v2/accounts/{address}/stream` |
| Receivables | `GET /v2/accounts/{address}/receivables/stream?minAmount=1` |
| Account-entry history | `GET /v2/accounts/{address}/entries/stream?fromHeight=1&toHeight=...` |
| Transaction history | `GET /v2/accounts/{address}/transactions/stream?fromHeight=1&toHeight=...` |

V2 responses return the Commons models directly, with account, representative, receiver, and subject
addresses instead of public-key and address-algorithm fields, including inside transaction blocks.
Hash-algorithm fields such as `sendHashAlgorithm` remain. Amounts, heights, and timestamps use decimal
strings in v2 JSON. `POST /v2/accounts` returns a finite JSON array using the same account lookup and
batching as the legacy endpoint. Legacy HTTP response adapters preserve the original field sets and JSON numbers.
Legacy transaction publication endpoints also accept key-only block identities through a local request adapter;
canonical addresses take precedence when supplied. V2 publication requires canonical addresses for the account
and each receiver or representative. Commons JSON decoding uses canonical models directly.
Signatures, work, hashes, and range semantics retain their meaning. Legacy stream and public-key routes
remain callable but are hidden from Swagger; v2 routes are documented there.

All v2 NDJSON streams, including GET, POST, publication, hash, and height-range streams, attempt to flush
a blank newline every second while open, including before the first record. Heartbeats stay between
complete records and stop when the source completes, fails, or is cancelled. They add no domain events
and do not extend publication deadlines. Healthy streams retain their existing lifetime. Legacy streams
receive no heartbeat bytes.

Transaction collection routes use `GET /v2/transactions/stream` for live updates and
`POST /v2/transactions` to publish and wait for confirmation. `POST /v2/transactions/stream` is not exposed.

HTTP streaming endpoints use **NDJSON** (`application/x-ndjson`) and combine:

- database results (historical)
- in-memory flows of live updates (reactive push)

Address-specific account streams subscribe to live updates before reading account snapshots. Live flow emissions
wait for that subscriber to finish the snapshot, then continue through the same subscription.
Each collection forwards only increasing heights for each requested address.
Transaction and account-entry hash/range streams use the same subscription-before-query handoff. Hash streams
complete after one result; range streams order and deduplicate results by height before completing the requested range.
Slow snapshot reads or clients backpressure the shared live feed; disconnecting cancels that subscriber and its query.

Receivable streams subscribe to live updates before reading the database. They scan the database before draining
a bounded live-update queue and continuing on the same subscription. Only hashes from the initial
database scan are retained to suppress overlapping or delayed live notifications; new live hashes do not grow
that set. Slow database reads or clients apply backpressure, and disconnecting cancels both the scan and live subscription.

### Public WebSocket streams

Upgrade `GET /v2/stream` on the HTTP API port to use WebSocket transport. Each connection carries one
logical stream. Send a text `subscribe` command with the existing v2 stream `method`, `path` (including
query parameters), and `body` for POST operations:

```json
{"type":"subscribe","request":{"method":"GET","path":"/v2/transactions/stream"}}
```

The 13 read stream operations are supported, including address, hash, height-range and POST search streams.
Encode an `atto://` address as one percent-encoded path segment. Transaction publication and finite JSON
queries remain HTTP-only. Commons always uses HTTP for `publish(transaction)`, including in WebSocket mode. Existing HTTP/NDJSON routes remain available and are the default
Commons client transport; callers can explicitly select WebSocket streams.

The server acknowledges subscriptions with `subscribed`. Its text JSON envelopes use `type`: `data`
carries the unchanged endpoint record and a decimal-string `sequence`; `heartbeat` reports connection
liveness every second while waiting; `complete` ends a successful operation; and `error` reports numeric
`status`, `message` and whether the error is `terminal`. For example:

```json
{"type":"heartbeat"}
{"type":"data","data":{"address":"atto://..."},"sequence":"1"}
{"type":"complete"}
```

After accepting a data record, send `{"type":"ack","sequence":"1"}`. Acknowledgements are cumulative:
the server sends at most 32 unacknowledged data records and waits for the final acknowledgement before
`complete`. Continue reading heartbeats and control messages while processing data. Commons handles
this protocol automatically.

To change account membership without reconnecting, use `"mutable":true` on a subscription to one of
the four POST streams: `/v2/accounts/stream`, `/v2/accounts/receivables/stream`,
`/v2/accounts/entries/stream`, or `/v2/accounts/transactions/stream`. Send `update` with the same method
and path and the full next body. Existing account subscriptions continue, newly added accounts receive
their normal snapshot and live handoff, and removed accounts are cancelled. The server replies `updated`
after applying the change. Filters and height ranges of retained accounts cannot change. An invalid
update returns a nonterminal error and leaves the previous membership running. An empty mutable
membership stays open with heartbeats. Send `{"type":"seal"}` when no more updates will follow; a sealed
stream completes after all remaining members finish. Account and receivable streams remain live until
the client disconnects.

Heartbeats carry no ledger meaning. After an upgrade, read-operation errors use an `error` envelope
rather than an HTTP status. A disconnect before `complete` or `error` is an interrupted stream.
HTTP transaction publication retains its 40-second confirmation deadline.
Closing the socket cancels its stream subscription. Slow consumers apply backpressure; domain records
are never dropped to make room for heartbeats. A second `subscribe` command is a protocol error.
The initial subscription must arrive within ten seconds. Each command must fit in a 256 KiB WebSocket message.

## How consensus works (high level)

Atto’s consensus in this codebase is vote-driven:

- Nodes observe competing transactions for the same `(account publicKey, height)`.
- A node that is configured as a **voter** and has enough **voting weight** produces signed votes.
- Votes are gossiped across the network; once cumulative weight passes a configurable threshold, the network considers
  consensus reached.

Key parts in this repo:

- `cash.atto.node.election.*`
  - **ElectionVoter**: decides when/what to vote for, signs votes, broadcasts them.
    - Includes a stability delay to avoid chasing rapidly changing provisional consensus.
  - **ElectionProcessor**: when consensus is reached, it commits the winning transactions into account state (and stores
    final votes if this node is historical).
- `cash.atto.node.vote.weight.VoteWeighter`
  - Maintains the weight map and computes thresholds like minimal confirmation weight.

Important thresholds (see `application.yaml` under `atto.vote.weight.*`):

- `minimal-rebroadcast-weight`
- `minimal-confirmation-weight`
- `confirmation-threshold` (percentage of online weight)

## Bootstrapping / catching up

A node must be able to recover when:

- it missed messages
- it receives transactions without dependencies
- it is behind the head of an account chain

This repo implements bootstrap as a set of *discoverers* and an *unchecked transaction* pipeline.

### Unchecked transactions

When the node learns about a transaction it cannot immediately apply (missing dependencies, out-of-order, etc.), it
stores it as an **unchecked transaction**:

- `cash.atto.node.bootstrap.unchecked.*`
  - `UncheckedTransactionProcessor` pulls the oldest unchecked transactions, validates them against current account
    state, and applies what it can.
  - `BootstrapController` schedules resolution alongside discovery and cleanup, using a shared work budget based on
    persistence load.

### Discovery flows

- **Dependency discovery** (`DependencyDiscoverer`)
  - When a transaction is rejected for a *recoverable* reason, it is tracked.
  - If enough final votes accumulate for that transaction, it is re-introduced as “discovered” even though it was
    initially rejected.

- **Gap discovery** (`GapDiscoverer`)
  - For accounts with gaps, the node requests a range of missing transactions from *historical peers* using
    `AttoTransactionStreamRequest/Response`.
  - Handles out-of-order deliveries with an in-memory buffer.

- **Head/last discovery** (`BootstrapHeadBroadcaster`, `LastDiscoverer`)
  - `BootstrapHeadBroadcaster` periodically broadcasts a sample of recent “head” transactions
    (`AttoBootstrapTransactionPush`) from historical nodes with no unchecked backlog.
  - If a receiving node is behind, it starts a lightweight vote stream for the head to confirm it’s real, then turns it
    into a `TransactionDiscovered` event once the votes reach quorum.
  - Partial vote collections expire without starting a live election. A later head advertisement can retry discovery.

- **Bounded persistence queue** (`DiscoveryQueue`, `DiscoveryPersistenceWorker`)
  - `DiscoveryQueue` deduplicates and buffers discovered transactions.
  - New gap, head, and send discovery pauses at the configured target capacity. Replies already in flight can use the
    configured headroom and suspend without being discarded when the bounded queue is full.
  - `DiscoveryPersistenceWorker` persists batches of up to 1,000 transactions and retries the same batch after a
    database failure.

The overall effect: nodes can reconnect, observe the network’s latest heads, request missing ranges, and replay into
their ledger state.

## Code map

For implementation work, use these repository guides:

- [Transaction processing and event flow](TRANSACTION-PROCESSING.md): live confirmation, vote processing, bootstrap intersections, state ownership, and how to trace an affected flow.
- [Network layer](NETWORK.md): peer connections, handshake, and message transport.
- [Contributor and agent guidelines](AGENTS.md): build/test commands, repository conventions, and concurrency invariants.

Common entry points:

- `src/main/kotlin/cash/atto/node/Application.kt` — Spring Boot entry
- `src/main/kotlin/cash/atto/node/ApplicationConfiguration.kt` — asynchronous event dispatch, scheduling, OpenAPI, runtime hints

Major modules (packages):

- `network/` — peer connections, handshake, WebSocket server/client, keep-alives
- `transaction/` — transaction ingest, validation, prioritization, REST controller
- `account/` — account snapshot storage and streaming
- `election/` — elections, voting, and applying consensus results
- `vote/` — vote persistence and weight calculations
- `bootstrap/` — gap/dependency/head discovery and unchecked transaction recovery

Feature-owned `AccountQueries`, `AccountEntryQueries`, `TransactionQueries`, and `ReceivableQueries` share query and
snapshot/live stream behavior between HTTP controllers and the public WebSocket dispatcher.
`network/HandshakeProtocol` owns peer challenge verification and signing; `NetworkProcessor` owns admission and connection lifecycle.

## Docker images

This repo includes multiple Dockerfiles:

- `Dockerfile` / `Dockerfile.*` variants

Example: `Dockerfile.dev` builds on `ghcr.io/attocash/node` and sets:

- `SPRING_PROFILES_ACTIVE=dev,json`

If you want a “container-first” run, pick the profile and pass env vars for DB + `ATTO_PUBLIC_URI`.

## Native image metadata

CI uses committed GraalVM reachability metadata from
`src/main/resources/META-INF/native-image/cash.atto/node/reachability-metadata.json`. It does not run the native-image
agent in the release pipeline because the GraalVM 25 agent is too slow for the full integration suite.

Regenerate the metadata locally only from a successful full agent run:

```bash
rm -rf build/native/agent-output/test
JAVA_HOME=/var/home/felipe/.jdks/graalvm-jdk-25 \
  GRADLE_USER_HOME=/tmp/gradle-home \
  ./gradlew -Pagent test --no-daemon --info --fail-fast
mkdir -p src/main/resources/META-INF/native-image/cash.atto/node
cp build/native/agent-output/test/reachability-metadata.json \
  src/main/resources/META-INF/native-image/cash.atto/node/reachability-metadata.json
```

Do not commit metadata from a failed test run. After copying the file, verify it with:

```bash
grep -n 'CounterChallengeResponse\$\$serializer' \
  src/main/resources/META-INF/native-image/cash.atto/node/reachability-metadata.json
./gradlew test --no-daemon --fail-fast
NATIVE_IMAGE_OPTIONS="--static --libc=musl --gc=G1 -R:MaxRAMPercentage=90" \
  ./gradlew nativeCompile --no-daemon
```

## Troubleshooting

### No peers / network health is DOWN

The Actuator health indicator marks the node down if it has been disconnected for too long:

- `cash.atto.node.network.NetworkHealthIndicator`

Fixes:

- set `ATTO_DEFAULT_NODES` to valid `ws://` or `wss://` seed URIs
- ensure `ATTO_PUBLIC_URI` is correct and reachable
- check that port **8082** is reachable from other peers

### Transactions never confirm

Common causes:

- You’re on `LOCAL` network without a private key set (not a VOTER node)

## License

See [LICENSE](./LICENSE).
