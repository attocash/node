# Transaction Processing

This document describes the hot path a transaction takes during normal processing, from ingestion to confirmation.
Bootstrap paths are excluded.

## Entry Points

There are two entry points for transaction processing:

### 1. REST API — `TransactionController`

- **Endpoint:** `POST /transactions` or `POST /transactions/stream`
- The controller validates basic transaction structure (`isValid()`) and network match.
- Wraps the `AttoTransaction` in an `AttoTransactionPush` message and publishes it as an `InboundNetworkMessage` with `MessageSource.REST`.

### 2. Network — `AttoTransactionPush`

- Received from peer nodes over the P2P network.
- Arrives as an `InboundNetworkMessage<AttoTransactionPush>`.

Both entry points converge at the same downstream listener.

---

## Processing Pipeline

```
TransactionController (REST)  ──┐
                                 ├──► InboundNetworkMessage<AttoTransactionPush>
Network (P2P)  ─────────────────┘
        │
        ▼
┌─────────────────────────────┐
│  TransactionPrioritizer     │  (dedup + priority queue)
│  @EventListener             │
│  InboundNetworkMessage      │
│  <AttoTransactionPush>      │
└──────────┬──────────────────┘
           │ @Scheduled poll()
           ▼
   TransactionReceived event
           │
           ▼
┌─────────────────────────────┐
│  TransactionValidationMgr   │  (runs all TransactionValidators)
│  @EventListener             │
│  TransactionReceived        │
└──────────┬──────────────────┘
           │
     ┌─────┴──────┐
     ▼            ▼
 REJECTED     VALIDATED
     │            │
     ▼            ▼
TransactionRejected   TransactionValidated event
                          │
           ┌──────────────┼──────────────────┐
           ▼              ▼                  ▼
┌──────────────┐  ┌──────────────┐  ┌────────────────────┐
│  Election    │  │ ElectionVoter│  │TransactionRebroadc. │
│  .start()   │  │  .process()  │  │  .process()         │
│  @EventList. │  │  @EventList. │  │  @EventListener     │
│  Tx Validated│  │  ElectionSt. │  │  TransactionValid.  │
└──────┬───────┘  └──────┬───────┘  └────────┬────────────┘
       │                 │                   │
       │                 │          Queues tx for rebroadcast
       │                 │          to peers (excluding those
       │                 │          that already sent it)
       │                 ▼
       │         Casts vote (AttoVotePush)
       │         → Broadcasts to VOTERS
       │         → Publishes VoteValidated
       │                 │
       ▼                 ▼
┌─────────────────────────────┐
│  Election.process()         │  (tracks votes per PublicKeyHeight)
│  @EventListener             │
│  VoteValidated              │
└──────────┬──────────────────┘
           │
     ┌─────┴──────────────┐
     ▼                    ▼
 Provisional           Consensus
 leader changed        reached
     │                    │
     ▼                    ▼
ElectionConsensus    ElectionConsensusReached event
Changed event              │
     │                     ▼
     ▼            ┌────────────────────┐
ElectionVoter     │  ElectionProcessor │  (batched persistence)
re-votes with     │  @EventListener    │
new consensus     │  ElectionConsensus │
                  │  Reached           │
                  └────────┬───────────┘
                           │
                           ▼
                  ┌────────────────────┐
                  │  AccountService    │  (@Transactional)
                  │  .add()            │
                  └────────┬───────────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        Save Account  Save Tx +    Save Account
        (update       Receivables  Entries
         balance,     (TransactionService) (if historical)
         height,
         representative)
                           │
                           ▼
                  AccountUpdated event
                  (published after commit)
                           │
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
  TransactionPrioritizer  Election     TransactionController
  re-processes buffered   removes      emits on transactionFlow
  dependent txs           election     (for streaming endpoints)
```

---

## Parallel Vote Path (votes from other nodes)

Votes arriving from the network follow a parallel path:

```
Network (P2P) ──► InboundNetworkMessage<AttoVotePush>
        │
        ▼
┌─────────────────────────────┐
│  VoteConverter              │  (converts AttoSignedVote → Vote
│  @EventListener             │   with current weight)
│  InboundNetworkMessage      │
│  <AttoVotePush>             │
└──────────┬──────────────────┘
           │
           ▼
   VoteReceived event
           │
     ┌─────┴──────────────────┐
     ▼                        ▼
┌──────────────┐     ┌────────────────────┐
│VotePrioritizer│    │ VoteRebroadcaster  │
│ @EventListener│    │ @EventListener     │
│ VoteReceived  │    │ VoteReceived       │
└──────┬────────┘    └────────────────────┘
       │ (dedup, validate weight,
       │  buffer if no election yet,
       │  queue if election active)
       │
       │ @Scheduled poll()
       ▼
  VoteValidated event ──► Election.process() (see above)
                     └──► VoteRebroadcaster queues for rebroadcast
```

---

## Key Events Summary

| Event | Published By | Consumed By |
|---|---|---|
| `InboundNetworkMessage<AttoTransactionPush>` | `TransactionController` / Network | `TransactionPrioritizer`, `TransactionRebroadcaster` |
| `TransactionReceived` | `TransactionPrioritizer` | `TransactionValidationManager` |
| `TransactionValidated` | `TransactionValidationManager` | `Election`, `ElectionVoter`, `TransactionRebroadcaster` |
| `TransactionRejected` | `TransactionValidationManager` | `TransactionRebroadcaster`, `VotePrioritizer`, `TransactionController` |
| `ElectionStarted` | `Election` | `ElectionVoter`, `TransactionPrioritizer`, `VotePrioritizer` |
| `ElectionConsensusChanged` | `Election` | `ElectionVoter` |
| `ElectionConsensusReached` | `Election` | `ElectionProcessor` |
| `ElectionExpiring` | `Election` (scheduled) | `ElectionVoter`, `ElectionProcessor` |
| `ElectionExpired` | `Election` (scheduled) | `ElectionVoter`, `TransactionPrioritizer`, `VotePrioritizer`, `TransactionController` |
| `VoteReceived` | `VoteConverter` | `VotePrioritizer`, `VoteRebroadcaster` |
| `VoteValidated` | `VotePrioritizer` / `ElectionVoter` | `Election`, `VoteRebroadcaster` |
| `AccountUpdated` | `AccountService` | `Election`, `ElectionVoter`, `TransactionPrioritizer`, `VotePrioritizer`, `TransactionRebroadcaster`, `TransactionController` |

---

## Key Components

| Component | Responsibility |
|---|---|
| `TransactionController` | REST entry point; publishes `InboundNetworkMessage<AttoTransactionPush>` |
| `TransactionPrioritizer` | Deduplicates, priority-queues, and buffers transactions waiting on dependencies |
| `TransactionValidationManager` | Runs all `TransactionValidator` implementations against the transaction |
| `TransactionRebroadcaster` | Tracks which peers saw a transaction; rebroadcasts after validation (if validated before being seen, rebroadcasts without exclusions) |
| `Election` | Tracks votes per `PublicKeyHeight`; determines provisional leader and consensus |
| `ElectionVoter` | Casts and broadcasts this node's vote; re-votes on consensus changes |
| `ElectionProcessor` | Batches confirmed transactions and persists them via `AccountService` |
| `AccountService` | Updates account state, saves transactions and receivables in a single DB transaction |
| `TransactionService` | Persists transactions and manages receivables (send → receivable, receive → delete) |
| `VoteConverter` | Converts network `AttoVotePush` messages into internal `VoteReceived` events |
| `VotePrioritizer` | Deduplicates, validates, buffers, and priority-queues incoming votes |
| `VoteRebroadcaster` | Rebroadcasts validated votes to peers (voter nodes only) |
