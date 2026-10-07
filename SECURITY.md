# Security

## Supported versions

Security fixes target the latest stable release. Backports to older releases are not guaranteed.

## Reporting a vulnerability

Report suspected vulnerabilities privately through [GitHub's vulnerability reporting form](https://github.com/attocash/node/security/advisories/new).

Include the affected version, a description of the issue, its potential impact and enough detail to reproduce it. Please keep vulnerability details out of public issues and pull requests while we assess the report.

## Response and disclosure

Maintainers will assess reports and coordinate any fixes and public disclosure with the reporter. Response and remediation times depend on severity and maintainer availability.

## Accepted risks

This section records owner-accepted security risks and their reassessment conditions. Consult it before a security audit or review so an unchanged known mechanism is not presented as a new finding. Acceptance means remediation is deferred within the stated scope; it does not mean the mechanism is fixed, disproven or safe in every deployment.

### Audit handling

- Match by mechanism, affected behavior and prerequisites, and reference the stable risk ID below. A renamed finding or shifted source line is not a new issue.
- Verify that the record's assumptions still apply to the current source and configuration. Cross-reference unchanged findings under known accepted limitations; exclude them from new actionable finding counts. A complete findings inventory may include each accepted finding once with its disposition.
- Reopen the existing record when new evidence or a material source/configuration/deployment change alters its prerequisites, impact or severity. State the specific change and update the assessment; do not silently suppress it or create a duplicate ID.
- Distinct mechanisms and interactions retain their own assessments. Acceptance of one constituent finding does not accept every related finding.
- Add or broaden an acceptance only at the repository owner's explicit direction. Preserve the risk ID and decision history when a record is reclassified, reopened or resolved.

### SEC-L001

**Node-local mutable representative views can certify conflicting same-height successors.**

| Field | Decision |
|---|---|
| Status | Accepted limitation; remediation deferred |
| Decision date | 2026-10-06 |
| Decision authority | Repository owner |
| Overall severity | Medium, provisional |
| Potential impact | High consensus-integrity impact |
| Practical exploitability | Low/uncertain on the available evidence; deployed likelihood has not been measured |
| Evidence boundary | Opposite local quorum outcomes and isolated persistence have been observed; simultaneous multi-node/network finality and economic harm have not been established |

#### Accepted scope and rationale

Election quorum evaluation uses each node's mutable representative weights and locally calculated threshold; signed votes do not bind a shared authoritative weight checkpoint. Different weight views can therefore yield different same-height local quorum decisions. Bootstrap/catch-up and ordinary live delegation changes can both create such views.

The accepted scenario requires competing transactions at the same account height, differing representative-weight views, sufficient delegation movement and favorable vote/propagation timing. Large weight-movement prerequisites and the absence of demonstrated simultaneous multi-node/network finality support provisional Medium overall despite High potential impact. No guaranteed short divergence window or permanent network split is established.

For illustration, with conserved relevant voting weight `W`, disjoint supporting representatives and a common threshold `Q`, the lower bound on redistributed weight is `max(0, 2Q - W)`. With `W = 18B`, this is 5.4B at `Q = 11.7B` (65%), or 2B at `Q = 10B`. These conditional bounds are not measured attacker resources; the relevant weight pool and equal-threshold assumption must be checked for actual node views.

The deferred remedy is a shared authoritative weight checkpoint, signed votes bound to that checkpoint, and safe rules for delegation activation and checkpoint transitions. Freezing each node's own local snapshot alone would still allow nodes to use different weight views.

Acceptance covers the scoped disagreement between nodes' representative-weight views under ledger propagation, catch-up and live delegation changes. Independently incorrect or reordered weight-projection updates, accumulation of weights from incompatible views within one certificate, and other distinct defects require separate assessment. Signature, transaction-validation, canonical-ledger and conservation requirements remain in force.

The interaction between asynchronous weight-projection updates and conflicting local quorum decisions is recorded separately under [SEC-L002](#sec-l002).

Relevant implementation boundaries:

- [Election](src/main/kotlin/cash/atto/node/election/Election.kt): live vote-weight and threshold providers, candidate tallying and consensus event publication.
- [VoteWeighter](src/main/kotlin/cash/atto/node/vote/weight/VoteWeighter.kt): representative-weight updates and locally observed online-weight thresholds.
- [Vote](src/main/kotlin/cash/atto/node/vote/Vote.kt): signed vote conversion without a shared weight checkpoint.
- [ElectionProcessor](src/main/kotlin/cash/atto/node/election/ElectionProcessor.kt): consensus-event persistence.

#### Reopen when

- Realistic independent-node/database evidence demonstrates durable conflicting decisions under attainable stake and timing conditions, including restart/bootstrap behavior.
- New evidence materially reduces the required stake movement or timing constraints, or deployment measurements establish that those prerequisites are realistically reachable.
- Economic harm, network-level finality conflict or another materially stronger impact is established.
- Relevant changes to quorum thresholds, delegation activation, vote formats, election/persistence behavior or deployment assumptions invalidate this assessment.

An unchanged source rediscovery, a new label or the same isolated local reproduction does not by itself reopen this record. New independently significant evidence or interactions must still be assessed and reported.

### SEC-L002

**Asynchronous representative-weight projection updates can trigger conflicting local quorum decisions.**

| Field | Decision |
|---|---|
| Status | Accepted limitation; remediation deferred within the interaction scope below |
| Decision date | 2026-10-07 |
| Decision authority | Repository owner |
| Overall severity | Medium, provisional |
| Potential impact | High consensus-integrity impact |
| Practical exploitability | Low/uncertain; realistic stake requirements and reliable attacker influence over scheduling have not been established |
| Evidence boundary | Opposite local election outcomes under controlled event scheduling have been observed; full vote admission, simultaneous two-node conflicting persistence, network finality and economic harm have not been demonstrated |

#### Accepted scope and rationale

Committed transactions and their account snapshots supply the inputs to the representative-weight projection. Account-update listeners run asynchronously, while the projection applies incremental deltas and updates the old and new representatives separately. Delayed, reordered or partially applied updates can therefore supply incompatible weight views to the mutable quorum evaluation described in [SEC-L001](#sec-l001), even when the transactions themselves are valid.

The accepted interaction requires competing same-height candidates, sufficient affected voting weight and favorable event/vote timing. The controlled local results establish the mechanism, but do not establish a cheaper practical attack, reliable hostile scheduling or durable conflicting network finality. These evidence limits support provisional Medium overall despite High potential impact.

The weight-movement bounds in SEC-L001 assume conserved weight and a common threshold. Incorrect projections can violate those assumptions, so those bounds are not guaranteed minimum attack costs for this interaction. Neither practical exploitability nor unexploitable status is established.

Acceptance covers this projection/quorum interaction. The underlying projection correctness, ordering, idempotency and recovery defects remain separately actionable; their independent effects must still be assessed. Signature, transaction-validation, canonical-ledger and conservation requirements remain in force.

The local remedy is versioned, idempotent account-contribution updates, atomic representative changes, coherent quorum reads and coordinated reconciliation with canonical account state. Eliminating cross-node weight-view disagreement also requires the shared protocol checkpoint and transition rules described in SEC-L001.

Relevant implementation boundaries:

- [ApplicationConfiguration](src/main/kotlin/cash/atto/node/ApplicationConfiguration.kt): asynchronous event dispatch.
- [EventPublisher](src/main/kotlin/cash/atto/node/Event.kt) and [AccountService](src/main/kotlin/cash/atto/node/account/AccountService.kt): publication of account updates after commit.
- [VoteWeighter](src/main/kotlin/cash/atto/node/vote/weight/VoteWeighter.kt): incremental representative-weight projection and threshold calculation.
- [Election](src/main/kotlin/cash/atto/node/election/Election.kt): live weight reads and local quorum decisions.

#### Reopen when

- Realistic full-admission, independent-node/database evidence demonstrates durable conflicting commits under attainable stake and timing conditions.
- New evidence establishes a materially lower weight requirement or reliable attacker influence over the required event scheduling.
- Economic harm, network-level finality conflict or another materially stronger impact is demonstrated.
- Changes to projection updates, event delivery, delegation activation, quorum thresholds or persistence invalidate this assessment's assumptions.
