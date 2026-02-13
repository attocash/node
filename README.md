# Atto Node

Atto Node is the network-facing service for the **Atto** cryptocurrency. It exposes a public HTTP API for submitting/querying blocks (transactions), maintains account state in a MySQL database, connects to other nodes over WebSockets, and participates in consensus by producing and relaying votes.

This repository is a **Kotlin + Spring Boot (WebFlux)** application with a small embedded **Ktor WebSocket/HTTP server** for node-to-node communication.

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

- **Java 24** (the Gradle toolchain is pinned to 24 in `build.gradle.kts`)
- **Docker** available locally (required by Testcontainers)

### 1) Run the quickest local node (`TestApplication`)

The quickest way to run the node locally is `TestApplication` (`src/test/kotlin/cash/atto/node/TestApplication.kt`).
Run that class directly from your IDE; it starts the node and spins up MySQL automatically through Testcontainers.

On startup, the node will:

- run Flyway migrations against the configured MySQL
- start the REST API
- start the node-to-node WebSocket server

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
- **8082**: node-to-node WebSocket server (Ktor)

Swagger UI is configured at:

- `:8080/` (see `springdoc.swagger-ui.path: /`)

## API

This project exposes endpoints for:

- **Transactions**: submit, query, and stream
  - `POST /transactions`
  - `POST /transactions/stream` (publish + stream confirmation)
  - `GET /transactions/{hash}`
  - `GET /transactions/stream` (NDJSON live stream)
- **Accounts**: query and stream account snapshots
  - `POST /accounts`
  - `GET /accounts/{publicKey}`
  - `POST /accounts/stream` (NDJSON)
- **Receivables**, **Account Entries**, **Vote weight**, and **time** utilities

All streaming endpoints use **NDJSON** (`application/x-ndjson`) and combine:

- database results (historical)
- in-memory flows of live updates (reactive push)

## How consensus works (high level)

Atto’s consensus in this codebase is vote-driven:

- Nodes observe competing transactions for the same `(account publicKey, height)`.
- A node that is configured as a **voter** and has enough **voting weight** produces signed votes.
- Votes are gossiped across the network; once cumulative weight passes a configurable threshold, the network considers consensus reached.

Key parts in this repo:

- `cash.atto.node.election.*`
  - **ElectionVoter**: decides when/what to vote for, signs votes, broadcasts them.
    - Includes a stability delay to avoid chasing rapidly changing provisional consensus.
  - **ElectionProcessor**: when consensus is reached, it commits the winning transactions into account state (and stores final votes if this node is historical).
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

When the node learns about a transaction it cannot immediately apply (missing dependencies, out-of-order, etc.), it stores it as an **unchecked transaction**:

- `cash.atto.node.bootstrap.unchecked.*`
  - `UncheckedTransactionProcessorStarter` runs every second, pulls oldest unchecked txs, validates them against current account state, and applies what it can.

### Discovery flows

- **Dependency discovery** (`DependencyDiscoverer`)
  - When a transaction is rejected for a *recoverable* reason, it is tracked.
  - If enough final votes accumulate for that transaction, it is re-introduced as “discovered” even though it was initially rejected.

- **Gap discovery** (`GapDiscoverer`)
  - For accounts with gaps, the node requests a range of missing transactions from *historical peers* using `AttoTransactionStreamRequest/Response`.
  - Handles out-of-order deliveries with an in-memory buffer.

- **Head/last discovery** (`LastDiscoverer`)
  - Historical nodes periodically broadcast a sample of recent “head” transactions (`AttoBootstrapTransactionPush`).
  - If a receiving node is behind, it starts a lightweight vote stream for the head to confirm it’s real, then turns it into a `TransactionDiscovered` event.

- **Persistence batching** (`DiscoveryProcessor`)
  - Buffers discovered transactions and periodically persists them into the unchecked table in bulk.

The overall effect: nodes can reconnect, observe the network’s latest heads, request missing ranges, and replay into their ledger state.

## Code map

Common entry points:

- `src/main/kotlin/cash/atto/node/Application.kt` — Spring Boot entry
- `src/main/kotlin/cash/atto/node/ApplicationConfiguration.kt` — scheduling, OpenAPI, runtime hints

Major modules (packages):

- `network/` — peer connections, handshake, WebSocket server/client, keep-alives
- `transaction/` — transaction ingest, validation, prioritization, REST controller
- `account/` — account snapshot storage and streaming
- `election/` — elections, voting, and applying consensus results
- `vote/` — vote persistence and weight calculations
- `bootstrap/` — gap/dependency/head discovery and unchecked transaction recovery

## Docker images

This repo includes multiple Dockerfiles:

- `Dockerfile` / `Dockerfile.*` variants

Example: `Dockerfile.dev` builds on `ghcr.io/attocash/node` and sets:

- `SPRING_PROFILES_ACTIVE=dev,json`

If you want a “container-first” run, pick the profile and pass env vars for DB + `ATTO_PUBLIC_URI`.

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
