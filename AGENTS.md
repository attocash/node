# Repository Guidelines

## Project Structure & Module Organization

This single Gradle module implements an Atto node using Kotlin, Spring Boot/WebFlux, Ktor, and MySQL.

- `src/main/kotlin/cash/atto/node/`: account, transaction, election, network, vote, and bootstrap packages; `src/main/kotlin/cash/atto/protocol/` contains peer messages.
- `src/main/resources/`: profile YAML, logging, GraalVM metadata, and Flyway migrations (`db/migration/V<number>__description.sql`).
- `src/test/kotlin/` and `src/test/resources/features/`: tests and Cucumber features. `src/benchmark/kotlin/` contains benchmarks.

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

Before security audits or reviews, read [SECURITY_LIMITATIONS.md](SECURITY_LIMITATIONS.md). Match accepted findings by mechanism and aliases, verify that their assumptions still hold, and cross-reference unchanged items as known accepted limitations instead of reporting them as new actionable findings. Reopen the existing record when meaningful new evidence or material source/configuration/deployment changes alter the assessment; explain what changed. Assess distinct mechanisms and interactions separately. Do not add or broaden risk acceptance without the repository owner's explicit direction.

## Event & Concurrency Safety

Before changes, state invariants and trace producers, validation, predecessor checks, state ownership, dispatcher, persistence, transaction boundaries, and completion listeners. Re-check current source. Use `component-collaboration-architecture` for ownership/collaboration changes.

Allow one current election height per public key; distinguish completed-height work from valid next-height transactions. Deduplicate by hash; one-public-key-per-save-batch constrains batching without dropping distinct hashes. For each guard, identify its reachable transition and existing validators/listeners; prefer fixing owners/cleanup over duplicating policy.

Preserve FIFO and failed work until outcomes are known. Delayed/duplicate account updates must preserve newer work; saved historical candidates must not be reported as election losers. Inspect database-cache commit/rollback/unknown callbacks: evict affected entries on uncertain completion, block stale restoration, preserve committed updates.

Test reachable event orders with consistent heights, account snapshots, and previous hashes. Add HTTP outcome handling, database lookups, state, or validation paths only for a demonstrated need.
