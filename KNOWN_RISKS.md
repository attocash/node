# Known risks and audit decisions

This document is a NIST-aligned Markdown risk register and audit decision log.
It is not a schema-conformant data exchange. Where the review did not establish
a defensible quantitative value, the field is recorded as `Not assessed`
instead of assigning a number.

The risk register contains concerns for which a response has been selected.
The decision log contains concerns that still require a protocol or operational
decision; those entries are not accepted risks and are not resolved findings.
Remediated findings remain traceable through their implementation, focused tests,
and commit history.

## Risk register

`Accepted / Monitoring` means the documented limitation is accepted under its
current assumptions and must be reassessed when a listed trigger occurs.

### VOTE-WEIGHT-CACHE

| Field | Value |
| --- | --- |
| Risk description | An election can retain the representative weight observed when a vote enters the election even if the live representative-weight table changes before the election completes. |
| Risk category | Protocol integrity |
| Likelihood | Not assessed |
| Impact | Medium (qualitative; only under an unproven multi-node ordering scenario) |
| Exposure | Not assessed |
| Risk response type | Accept |
| Risk response description | Accept the bounded use of cached ingress weight under current network conditions. Startup reconstructs representative weights, normal election turnover limits the lifetime of cached vote data, and no realistic finality divergence has been reproduced. |
| Risk response cost | Not assessed |
| Risk owner | `@attocash/core` (protocol responsibility) |
| Status | `Accepted / Monitoring` |
| Review date | 2026-07-10 |
| Current controls | Startup weight reconstruction and normal election turnover. |
| Monitoring and reassessment trigger | Reassess after a valid multi-node finality-divergence reproduction, a material change in live representative-weight mobility, or adoption of a protocol weight epoch. |
| Audit reference | Archived `AUDIT-001` |

### VOTE-PERSISTENCE-BEST-EFFORT

| Field | Value |
| --- | --- |
| Risk description | A failed drained persistence batch can leave historical final-vote data incomplete. Persisted votes support archival and network streams and are not read when applying the ledger. |
| Risk category | Operational resilience and historical completeness |
| Likelihood | Not assessed |
| Impact | Not assessed |
| Exposure | Not assessed |
| Risk response type | Accept |
| Risk response description | Accept best-effort historical vote persistence while it remains non-authoritative for ledger finality and recovery. Dropping a failed persistence batch is acceptable in this context because retrying it can delay newer work without improving consensus correctness. |
| Risk response cost | Not assessed |
| Risk owner | `@attocash/core` (operator responsibility) |
| Status | `Accepted / Monitoring` |
| Review date | 2026-07-10 |
| Current controls | `VoteKeeper` can reacquire missing votes for current account heads; recovery of lost intermediate history is not guaranteed. |
| Monitoring and reassessment trigger | Reassess if stored votes become authoritative for finality or recovery, complete archival history gains an SLA, or persistence failures are observed in production. |
| Audit reference | `MEDIUM-VPDL-01` |

### CONSENSUS-PERSISTENCE-ABANDONMENT

| Field | Value |
| --- | --- |
| Risk description | After a terminal consensus-persistence failure, a node can abandon the affected local work and require replay, bootstrap, an account update, or restart to recover it. |
| Risk category | Operational resilience and node liveness |
| Likelihood | Not assessed |
| Impact | Not assessed |
| Exposure | Not assessed |
| Risk response type | Accept |
| Risk response description | Accept the node-local operational limitation. The reported security characterization is rejected because the ledger transaction rolls back atomically and the audit did not demonstrate an attacker-controlled persistence failure, partial ledger commit, or consensus divergence. |
| Risk response cost | Not assessed |
| Risk owner | `@attocash/core` (operator responsibility) |
| Status | `Accepted / Monitoring` |
| Review date | 2026-07-10 |
| Current controls | Replay, bootstrap, account updates, and restart are expected node-local recovery paths. |
| Monitoring and reassessment trigger | Reassess after a deterministic external fault trigger is demonstrated, a documented recovery path fails, or retry or pending-state transaction ownership changes. |
| Audit reference | `AUDIT-002` |

## Audit decision log

These entries require a decision before a risk response can be selected. An
entry moves to the risk register only after its owner chooses and documents a
response.

### ONLINE-WEIGHT-EPOCH

| Field | Value |
| --- | --- |
| Decision required | Define the protocol epoch boundary for combining representative weights, online observations, and election decisions. |
| Status | `Pending decision` |
| Owner | `@attocash/core` (protocol responsibility) |
| Current handling | Existing hourly recalculation remains unchanged while compatibility is assessed. |
| Evidence required | A protocol invariant, compatibility analysis, and migration plan. |
| Decision trigger | Approve a weight-epoch invariant and compatible rollout plan before changing calculation semantics. |
| Audit reference | `AUDIT-003` |

### PEER-SESSION-CAPACITY

| Field | Value |
| --- | --- |
| Decision required | Define measured capacity, trusted-peer treatment, address-diversity rules, and rollout policy for authenticated peer-session admission. |
| Status | `Pending decision` |
| Owner | `@attocash/core` (operator responsibility) |
| Current handling | Existing authentication and operator monitoring remain in place; no capacity policy has been accepted. |
| Evidence required | Representative capacity measurements and an admission policy. |
| Decision trigger | Approve capacity, trusted-peer behavior, address-diversity rules, and rollout criteria before implementation. |
| Audit reference | `AUDIT-005` |

### HISTORICAL-STREAM-SCHEDULING

| Field | Value |
| --- | --- |
| Decision required | Define fairness, admission, cancellation, and trusted-peer policy for historical peer streams. |
| Status | `Pending decision` |
| Owner | `@attocash/core` (operator responsibility) |
| Current handling | Existing stream lifecycle and operator recovery remain unchanged; no fairness policy has been accepted. |
| Evidence required | Workload measurements, cancellation guarantees, and trusted-peer requirements. |
| Decision trigger | Approve scheduling requirements and rollout criteria before implementation. |
| Audit reference | `AUDIT-007` |

## Audit cross-reference policy

Future audits must cite the stable descriptive ID for an existing entry and
identify changed evidence, assumptions, or code before reopening it. Audit task
IDs are supporting references, not register IDs. Pending decisions are neither
accepted nor resolved. Detailed reproduction timing, stake distributions,
payload recipes, retry parameters, and other operational exploit instructions
do not belong in this register.
