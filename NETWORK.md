# Network Layer

The networking subsystem authenticates peers in [NetworkProcessor][processor], manages their WebSocket sessions in
[NodeConnectionManager][connections], and counts traffic in [Guardian][guardian]. These owners communicate through direct
queries and asynchronous network-message and lifecycle events.

## Peer Authentication

`NetworkProcessor` starts an embedded Ktor CIO server with two endpoints:

- **`POST /handshakes`** accepts a `CounterChallengeResponse`. Guardian admits the remote address, the processor consumes
  a one-time challenge, checks the expected public URI, genesis hash, signature and counter-challenge prefix, then signs
  a `ChallengeResponse`. Successful authentication completes the waiting outbound attempt before the HTTP response is sent.
- **`WS /`** accepts a WebSocket with `Atto-Public-Uri` and `Atto-Http-Challenge` headers. Guardian admits the remote address,
  the processor validates the headers and challenge prefix, then posts a counter-challenge to the peer's `/handshakes`
  endpoint. It checks the returned URI and signature before handing the session to `NodeConnectionManager.manage()`.

An inbound HTTP handshake has a five-second preparation deadline covering admission, body reading, verification and signing.
A separate semaphore limits concurrent HTTP handshakes to 64. The [callback service][callback] applies a five-second deadline
covering URI validation, request construction/signing and the callback exchange. Both handshake payload directions have bounded
body readers in [HandshakePayloadLimit][payload-limit]. Cancellation propagates to ongoing handshake work.

### Outbound Connections

`bootstrap()` runs every second and tries the configured default nodes. An inbound `AttoKeepAlive` can also supply a neighbour
URI for discovery. `connectAsync()` reserves the URI in `connectingMap` before starting a connection coroutine; non-default
outbound work also needs a permit from a pool of 64.

`connection()` validates the peer URI, checks whether it is already connected, and opens a WebSocket with the challenge
headers. It waits for its [OutboundConnectionAttempt][attempt] to produce the authenticated node. A timeout task expires an
unauthenticated attempt and cancels the connection coroutine after five seconds. Authentication and expiration compete through
the same `CompletableDeferred`; expiration cannot win after authentication. After authentication, the timeout is cancelled and
`manage()` owns the established session. The coroutine's `finally` block releases its reservation and any outbound permit.

### Handshake State

- `connectingMap` is a Caffeine cache of `URI → OutboundConnectionAttempt`. It reserves pending peer work and lets an HTTP
  callback complete the outbound attempt's deferred node. Entries expire after five seconds.
- [ChallengeStore][challenges] generates random one-time challenges prefixed with the target URI. Entries expire after five
  seconds; consuming a challenge removes it.
- [PeerUriValidator][uri-validator] owns callback/outbound URI policy, including configured default-node exceptions and
  address checks. [NetworkDnsResolver][dns] owns the dispatcher used for blocking DNS calls.

## Active Connections and Peer Queries

`NodeConnectionManager` is the sole owner of active connections. Its Caffeine map stores `URI → NodeConnection`, with a
60-second expiry refreshed by keepalives from the current session. Registering another connection for the same URI replaces
and disconnects the previous session: the last registration wins.

The manager implements [ConnectedPeers][connected-peers], exposing `snapshot()` and `isConnected(publicUri)`. A snapshot
contains detached `ConnectedPeer(node, socketAddress)` metadata, without transport objects. These queries describe current
local state; a peer may disconnect immediately after a query. They do not reserve a connection or promise delivery.

Consumers query this owner instead of reconstructing membership from connection events:

| Consumer | Query and feature-owned policy |
| --- | --- |
| `TransactionNetworkProvider` | Checks membership before transaction lookup/streaming and between streamed responses. Historical-node eligibility, concurrent lookup permits, stream serialization and request deadlines remain with the provider. |
| `GapDiscoverer` | Selects connected historical peers and excludes peers with active gap sessions. It owns session budgets and cancellation; a disconnect event releases sessions only when the URI is currently disconnected. |
| `SendDiscoverer` | Finds a connected representative from the supplied votes, preserving vote order; otherwise broadcasts the missing-send request. |
| `VoteKeeper` | Finds a connected historical representative for missing votes. Local vote signing and missing-vote tracking remain with the keeper. |
| `Guardian` | Derives the current voter socket/public-key view, then applies its own current-weight eligibility check. |
| `NetworkInfoContributor` | Builds the `paired` diagnostic output from current connection metadata. |

### Session Lifecycle

`manage()` registers the connection and consumes binary frames from its incoming channel. It deserializes each binary
message through [NetworkSerializer][serializer], which also checks protocol validity. Invalid messages remove the connection. Valid messages become `InboundNetworkMessage` events with the peer URI,
socket address and `WEBSOCKET` source.

A current session's keepalive renews its map entry without replacing a newer session. Flow completion, decoding rejection and
send failures remove entries conditionally using the session identity, so an old session cannot remove its replacement.
The `finally` block initiates transport close. Cache removal also schedules close and then publishes `NodeDisconnected`;
expiry, explicit removal and replacement use this same cleanup path.

Lifecycle events run through independent asynchronous listener tasks. Their publication order does not guarantee consumer
completion order, and an old session's disconnect event can arrive after its replacement is registered. Current peer queries
avoid relying on that order. `GapDiscoverer` retains a disconnect reaction because cancelling its feature-owned work is a real
side effect; its connectivity check prevents a delayed disconnect from releasing sessions solely because the URI reconnected.
Gap sessions identify peers by URI and retain their existing expiry; this is not connection-generation isolation.

`clear()` removes connections and waits for already scheduled cleanup jobs. `stop()` clears the map and cancels the manager's
scope. `NetworkProcessor.stop()` separately closes its client, cancels outbound work and stops its embedded server.

### Sending and Keepalives

- `send(publicUri, message)` serializes and sends to the current session, returning if the URI is absent. Failure removes
  that session conditionally.
- `DirectNetworkMessage` listeners send to one URI. `BroadcastNetworkMessage` selects all connected peers matching its
  `EVERYONE` or `VOTERS` strategy and optional URI exceptions.
- `NodeBanned` disconnects active connections using the banned IP address.
- Every ten seconds, `keepAlive()` broadcasts `AttoKeepAlive` with a random connected neighbour URI, if one is available.
  Recipients refresh their connection lifetime and may discover that neighbour.

## Traffic Admission and Banning

Guardian owns traffic statistics, threshold decisions and inbound connection request counters. [PeerBans][peer-bans] owns
active bans and their expiry; [ExpectedResponses][expected-responses] owns per-URI response credits. `NetworkProcessor` calls
Guardian's `requestInboundConnection()` directly for HTTP handshake and WebSocket admission. Guardian queries current ban state
before counting; other attempts contribute to statistics and the configured per-minute connection limit.

Message counting and window rotation share a lock, so each counted hit belongs to one window. Every 15 seconds, `guard()`
swaps out the current window and retains it as the latest diagnostic snapshot. It queries connected voters, applies current
minimal rebroadcast weight, and includes zero for idle eligible voters. If the median meets `minimalMedian`, addresses whose
merged count reaches `median × toleranceMultiplier` are banned through `PeerBans`. The owner updates admission state before
publishing `NodeBanned`; its asynchronous consumers cannot delay that state transition.

`NetworkMessagePublisher` records positive expected-response counts synchronously in `ExpectedResponses` before dispatching
an outbound `DirectNetworkMessage`. A transport listener can therefore send a request and receive a fast response without
racing a separate credit-allocation listener. Guardian consumes credits instead of counting solicited inbound responses;
consuming the final credit immediately removes the entry. Only outbound request timestamps extend credit expiry. Inbound
responses do not, and delayed publication retains the request's original deadline. Expired credits are removed during response
counting or the next guard sweep. Credit lifetime is independent of peer metadata and survives reconnects until consumption
or expiry. Credits retain the existing publication-based contract: publication does not promise successful transport delivery.

`PeerBans` reviews active bans every minute and removes them after one hour from the latest ban. Renewal, expiry, admission
queries and snapshots share its lock, so an expiry cannot remove a concurrent renewal. `NodeBanned` and `NodeUnbanned` report
completed state changes; they do not update the owner's state. Diagnostic banned output queries this same owner directly,
so delayed lifecycle listeners cannot leave admission and diagnostics with conflicting ban projections.

## Events

Network messages use the asynchronous application event dispatcher. Their publisher returns without waiting for every
transport, feature, traffic-accounting or metrics listener to finish.

| Event | Producer | Current lifecycle consumers |
| --- | --- | --- |
| `NodeDisconnected` | `NodeConnectionManager` | `GapDiscoverer` session cleanup. |
| `NodeBanned` | `PeerBans`, called by `Guardian` | `NodeConnectionManager` disconnects affected sessions. |
| `NodeUnbanned` | `PeerBans` expiry review | No current consumers. |

[processor]: src/main/kotlin/cash/atto/node/network/NetworkProcessor.kt
[connections]: src/main/kotlin/cash/atto/node/network/NodeConnectionManager.kt
[connected-peers]: src/main/kotlin/cash/atto/node/network/ConnectedPeers.kt
[guardian]: src/main/kotlin/cash/atto/node/network/guardian/Guardian.kt
[callback]: src/main/kotlin/cash/atto/node/network/HandshakeCallback.kt
[payload-limit]: src/main/kotlin/cash/atto/node/network/HandshakePayloadLimit.kt
[attempt]: src/main/kotlin/cash/atto/node/network/OutboundConnectionAttempt.kt
[challenges]: src/main/kotlin/cash/atto/node/network/ChallengeStore.kt
[uri-validator]: src/main/kotlin/cash/atto/node/network/PeerUriValidator.kt
[dns]: src/main/kotlin/cash/atto/node/network/NetworkDnsResolver.kt
[serializer]: src/main/kotlin/cash/atto/node/network/NetworkSerializer.kt
[peer-bans]: src/main/kotlin/cash/atto/node/network/guardian/PeerBans.kt
[expected-responses]: src/main/kotlin/cash/atto/node/network/guardian/ExpectedResponses.kt
