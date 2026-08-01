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

### ONLINE-WEIGHT-EPOCH

| Field | Value |
| --- | --- |
| Risk description | Representative weight eligibility changes immediately while the online-weight-derived confirmation threshold is recomputed hourly, without a defined common protocol epoch. |
| Risk category | Protocol integrity and finality threshold consistency |
| Likelihood | Not assessed |
| Impact | Medium (qualitative; meaningful live impact is unlikely under the current vote distribution) |
| Exposure | Not assessed |
| Risk response type | Accept |
| Risk response description | Accept and monitor the current inconsistency because no realistic finality divergence has been reproduced and the present vote distribution makes material live impact unlikely. Retain protocol epoch work as future remediation after normative semantics and compatibility are defined. |
| Risk response cost | Not assessed |
| Risk owner | `@attocash/core` (protocol responsibility) |
| Status | `Accepted / Monitoring` |
| Review date | 2026-07-13 |
| Current controls | Hourly online-weight threshold recomputation and the configured minimum-confirmation floor. |
| Monitoring and reassessment trigger | Reassess after a material change in vote-weight concentration or mobility, a valid multi-node finality-divergence reproduction, or approval or activation of normative epoch and compatibility semantics. |
| Audit reference | `AUDIT-003` |

## Audit decision log

No unresolved audit decisions are currently recorded.

## Audit cross-reference policy

Future audits must cite the stable descriptive ID for an existing entry and
identify changed evidence, assumptions, or code before reopening it. Audit task
IDs are supporting references, not register IDs. Pending decisions are neither
accepted nor resolved. Detailed reproduction timing, stake distributions,
payload recipes, retry parameters, and other operational exploit instructions
do not belong in this register.
