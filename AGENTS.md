# Repository Guidelines

## Project Structure & Module Organization

This single Gradle module implements an Atto node using Kotlin, Spring Boot/WebFlux, Ktor, and MySQL.

- `src/main/kotlin/cash/atto/node/`: account, transaction, election, network, vote, and bootstrap packages; `src/main/kotlin/cash/atto/protocol/` contains peer messages.
- `src/main/resources/`: profile YAML, logging, GraalVM metadata, and Flyway migrations (`db/migration/V<number>__description.sql`).
- `src/test/kotlin/` and `src/test/resources/features/`: tests and Cucumber features. `src/benchmark/kotlin/` contains benchmarks.

## Repository References

- [README.md](README.md): setup, configuration, API, and package overview.
- [TRANSACTION-PROCESSING.md](TRANSACTION-PROCESSING.md): transaction/event flow, state owners, persistence, and tracing procedure.
- [NETWORK.md](NETWORK.md): peer connections, handshake, and network message transport.

## Build, Test, and Development Commands

Use Java 25 and the Gradle wrapper. Integration tests require Docker for Testcontainers.

- `./gradlew build`: compile, package, and run checks.
- `./gradlew test`: run the test suite.
- `./gradlew ktlintFormat`: apply formatting.
- `./gradlew bootRun`: start with configured MySQL.

For automatic MySQL setup, run `cash.atto.node.TestApplication` from your IDE with the test runtime classpath.

## Coding Style & Naming Conventions

Follow `.editorconfig`: four-space Kotlin indentation, 140-character lines, explicit imports, and ktlint's official style. Use `UpperCamelCase` classes and `lowerCamelCase` functions/properties.

## Testing Guidelines

Use JUnit Platform, MockK, Cucumber, and Testcontainers. Mirror production packages, use `*Test.kt` classes and descriptive backtick method names, and cover changed behavior. No numeric coverage gate is configured.

Focused run: `./gradlew test --tests cash.atto.node.transaction.TransactionQueueTest`.

## Commit & Pull Request Guidelines

Use short imperative summaries, e.g. `Fix election persistence races and cache invalidation`. PRs should explain behavior changes, link relevant issues, report validation commands/results, and pass CI.

## Security & Configuration

Configure `ATTO_DB_*`, `ATTO_PRIVATE_KEY`, and remote signer tokens through environment variables; never commit credentials.

## Security Audit Dispositions

Before security audits or reviews, read [Accepted risks in SECURITY.md](SECURITY.md#accepted-risks). Match accepted risks by mechanism and prerequisites, verify that their assumptions still hold, and cross-reference unchanged items by their stable risk ID instead of reporting them as new actionable findings. Reopen the existing record when meaningful new evidence or material source/configuration/deployment changes alter the assessment; explain what changed. Assess distinct mechanisms and interactions separately. Do not add or broaden risk acceptance without the repository owner's explicit direction.

## Event & Concurrency Safety

For changes to event flow, shared state, ordering, or persistence:

1. State the affected invariant and a concrete input or event order that could violate it.
2. Follow [Trace an affected flow](TRANSACTION-PROCESSING.md#trace-an-affected-flow). Verify the relevant producers, listeners, direct calls, validation/predecessor checks, queues, and completion callbacks in current source. Include bootstrap paths when they touch the same state.
3. Identify the owner of each mutable state and its lock, coroutine/worker, and transaction boundary. Event delivery is asynchronous here: publication order does not prove listener completion order; after-commit publication does not wait for consumers.
4. Fix the demonstrated transition at its owner. Before adding a guard, identify the reachable path and existing validators/cleanup; add HTTP outcomes, database lookups, state, or validation only for a demonstrated need.
5. Test reachable event orders with consistent heights, account snapshots, and previous hashes. Update the affected map entries when the implementation changes their meaning.

Preserve these invariants:

- Allow one current election height per public key; distinguish completed-height work from valid next-height transactions. Verify admission and predecessor checks rather than inferring this guarantee from a map key.
- Deduplicate by hash. One public key per persistence batch constrains batching without dropping distinct hashes. Preserve persistence FIFO and failed work until outcomes are known.
- Delayed/duplicate account updates must preserve newer work; saved historical candidates must not be reported as election losers.
- Inspect database-cache commit/rollback/unknown callbacks: evict affected entries on uncertain completion, block stale restoration, and preserve committed updates.

## Documentation Changes

Keep required guidance in repository files with relative links so it works from a fresh checkout. Keep agent instructions scoped to the relevant work, and put detailed flow explanations in the existing reference documents. Source code is authoritative; diagrams show selected scenarios, not a global execution order.
