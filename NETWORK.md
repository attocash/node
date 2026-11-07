### Network Layer Overview

This document describes how the networking subsystem works in the Atto node, focusing on `NetworkProcessor`,
`NodeConnectionManager`, and the Guardian rate-limiting system.
---

### NetworkProcessor

`NetworkProcessor` is a Spring `@Component` responsible for establishing and accepting peer-to-peer WebSocket
connections with a mutual challenge-based handshake.

#### Embedded Server

An embedded Ktor CIO server exposes two endpoints:

- **`POST /handshakes`** – Receives a `CounterChallengeResponse` from a connecting peer. Rejects requests from banned
  addresses (via `BannedMonitor`), calls `Guardian.requestInboundConnection` for rate-limiting, validates the
  challenge, verifies the cryptographic signature and genesis hash, signs the counter-challenge, and emits the peer's
  `AttoNode` into a `MutableSharedFlow` so the WebSocket handler (or the outbound connector) can proceed.
- **`WS /`** – Accepts inbound WebSocket connections. The connecting client must provide `Atto-Public-Uri` and
  `Atto-Http-Challenge` headers. The server rejects banned addresses, validates the challenge prefix, performs a mutual
  handshake by calling the client's `/handshakes` endpoint over HTTP, verifies the response signature and `publicUri`
  match, and then delegates the live WebSocket session to `NodeConnectionManager.manage()`.

#### Outbound Connections (Client Side)

- **`bootstrap()`** – Runs every 1 second (`@Scheduled(fixedRate = 1_000)`). Iterates over configured default nodes and
  calls `connectAsync()` for each.
- **`onKeepAlive()`** – Listens for inbound `AttoKeepAlive` messages. If a neighbour URI is present, attempts to connect
  to it (peer discovery via gossip).
- **`connectAsync()` / `connection()`** – Opens a WebSocket to the remote peer, sends challenge headers, waits (with a
  5-second timeout) for the handshake flow to complete, verifies the `publicUri` matches, and then hands the session to
  `NodeConnectionManager.manage()`.

#### Handshake Protocol

1. **Client** opens a WebSocket to the server, sending its `publicUri` and a generated challenge (prefixed with the
   server's URI) in headers.
2. **Server** validates the challenge prefix, signs the client's challenge, generates its own counter-challenge, and
   POSTs a `CounterChallengeResponse` (including the genesis hash) to the client's `/handshakes` endpoint.
3. **Client's `/handshakes`** handler validates the counter-challenge, verifies the signature and genesis hash, signs
   the counter-challenge, and responds with a `ChallengeResponse`.
4. **Server** verifies the client's signature on the counter-challenge and that the `publicUri` matches. If valid, the
   WebSocket is promoted to a managed connection. Both sides prove ownership of their private key by signing challenges,
   preventing impersonation. The genesis hash exchange ensures both peers are on the same network.

#### Key Data Structures

- **`connectingMap`** – A Caffeine cache (`URI → MutableSharedFlow<AttoNode>`) that coordinates the async handshake.
  Entries expire after 5 seconds.
- **`ChallengeStore`** – Generates and validates one-time challenges prefixed with the target node's URI.

---

### NodeConnectionManager

`NodeConnectionManager` is a Spring `@Component` that manages the lifecycle of all active peer connections.

#### Connection Storage

- **`connectionMap`** – A Caffeine cache (`URI → NodeConnection`) with a 60-second expiry. When an entry is evicted
  (timeout, explicit removal, or replacement), the removal listener disconnects the session and publishes a
  `NodeDisconnected` event.

#### Connection Lifecycle (`manage()`)

1. Wraps the `AttoNode`, socket address, and `WebSocketSession` into a `NodeConnection`.
2. Inserts into `connectionMap` via `put`. If a connection already exists for that URI, the previous one is disconnected
   and replaced (last connection wins).
3. Consumes the WebSocket's incoming binary frames as a Kotlin `Flow`.
4. On flow start, publishes a `NodeConnected` event.
5. Each frame is deserialized; invalid messages cause disconnection. `AttoKeepAlive` messages refresh the cache entry
   (resetting the 60-second TTL).
6. On flow completion or exception, the connection is removed from the map and the session is disconnected.

Connection events are delivered by independent asynchronous listener tasks; publishing `NodeConnected` before
`NodeDisconnected` does not guarantee that their listeners finish in that order. The peer projections in
`TransactionNetworkProvider`, `GapDiscoverer`, and `SendDiscoverer` currently use unconditional add/remove operations.
A delayed connect listener can therefore add a stale URI/public-key entry after disconnect cleanup has run.
These projections have no expiry or reconciliation, so that metadata remains until a later removal or process restart.
The authoritative connection map and WebSocket lifecycle are separate from these retained peer entries.

#### Sending Messages

- **`send(publicUri, message)`** – Serializes and sends a message to a specific peer. On failure, removes the
  connection.
- **`send(DirectNetworkMessage)`** – Event listener that sends a message to a single peer.
- **`send(BroadcastNetworkMessage)`** – Event listener that broadcasts a message to all connected peers matching the
  broadcast filter. `BroadcastNetworkMessage` carries a `BroadcastStrategy` (`EVERYONE` or `VOTERS`) and an optional
  `exceptions` set of URIs to exclude. The `accepts(target, node)` method determines whether each peer should receive
  the message.

#### Banning

- **`ban(NodeBanned)`** – Event listener that disconnects all connections whose address matches the banned address.

#### Keep-Alive

- **`keepAlive()`** – Runs every 10 seconds. Picks a random connected peer as a "neighbour" sample and broadcasts an
  `AttoKeepAlive` message to all peers. This serves two purposes: resetting the 60-second connection TTL and enabling
  peer discovery (recipients learn about the sampled neighbour).

---

### Guardian (Rate Limiting)

The `guardian` package provides automatic rate-limiting and banning of misbehaving peers.

#### Guardian

`Guardian` is a Spring `@Service` that monitors inbound message rates and bans peers that exceed a threshold derived
from voter behaviour.

- **Message Counting** – Tracks per-socket-address message counts in `statisticsMap`. Counting and window rotation
  share a lock; `guard()` swaps out the map and retains only the latest completed window as its snapshot.
  Outbound `DirectNetworkMessage` events with positive expected-response counts add per-URI credits so that solicited
  responses are not counted against the peer. Consuming the final credit removes the entry immediately. Unanswered
  credits expire one minute after the latest request's original timestamp; inbound responses do not extend that deadline.
  Expired entries are removed when a response is counted or during the next guard sweep, including credits added by
  a delayed outbound listener after disconnect.
- **Voter Tracking** – Maintains a `voterMap` of connected voters (peers above minimal rebroadcast weight). Updated on
  `NodeConnected` / `NodeDisconnected` events.
- **`guard()`** – Runs every 15 seconds. Drains the current counts, calculates the median rate among connected eligible
  voters (including zero for idle voters), and bans any address whose merged rate exceeds `median × toleranceMultiplier`
  (configurable via `GuardianProperties`). A `minimalMedian` threshold prevents banning when overall traffic is too low.
- **Connection Requests** – `NetworkProcessor` calls `requestInboundConnection` directly for handshake/WebSocket
  admission. Guardian counts non-banned attempts by IP and applies the configured per-minute connection limit.

#### BannedMonitor

`BannedMonitor` is a singleton `@Component` that maintains an in-memory `ConcurrentHashSet` of banned `InetAddress`
entries. It listens for `NodeBanned` and `NodeUnbanned` events and exposes `isBanned(address)` for use by
`NetworkProcessor` to reject connections early.

#### Unbanner

`Unbanner` is a
`@Component` that automatically lifts bans after 1 hour. It tracks ban timestamps and runs a review every 1 minute, publishing
`NodeUnbanned` events for expired bans.
---

### Network Events Summary

| Event                        | Published By            | Consumed By                                          |
|------------------------------|-------------------------|------------------------------------------------------|
| `NodeConnected`              | `NodeConnectionManager` | `Guardian`                                           |
| `NodeDisconnected`           | `NodeConnectionManager` | `Guardian`                                           |
| `NodeBanned`                 | `Guardian`              | `BannedMonitor`, `NodeConnectionManager`, `Unbanner` |
| `NodeUnbanned`               | `Unbanner`              | `BannedMonitor`                                      |
