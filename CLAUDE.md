# CLAUDE.md — sync-cache

## What this project is

sync-cache is an in-memory key-value cache built in Java 21, speaking the RESP2 wire protocol so any Redis client works against it on day one. Its one genuine differentiator from Redis is durable, HTTP-native, at-least-once webhooks — Redis keyspace notifications are fire-and-forget pub/sub; sync-cache uses the WAL as the webhook outbox so subscribers that go down miss nothing. Everything else (rich types, WAL + snapshot durability, JSONL bootstrapping) is sync-cache building the same foundation Redis already has, implemented from scratch as the educational core of the project.

See [`docs/cache-design-doc.md`](docs/cache-design-doc.md) for the full design document.

## Agent and model guidance

See [`AGENTS.md`](AGENTS.md) for:
- Which Claude model to use in each phase
- The agentic loop workflow (design → implement → review)
- Architectural invariants that must never be broken
- Package layout and mandatory phase tests

## Build and test

```bash
mvn compile            # compile
mvn checkstyle:check   # lint (2-space indent, no tabs, 120-char lines)
mvn test               # run all tests
mvn verify             # compile + checkstyle + test — run before every commit
mvn package -q         # build fat jar → target/sync-cache.jar
```

Requires Java 21 and Maven 3.9+.

## Running locally

```bash
java -jar target/sync-cache.jar --config config.yml
redis-cli -p 6379 PING
```

## Key architectural facts

- **Single writer thread** — all mutations go through one `ArrayBlockingQueue`-backed thread; no locks on the hot path
- **Virtual threads** — one per TCP connection via `Thread.startVirtualThread(...)`; no Netty, no NIO
- **WAL is the source of truth** — the in-memory store and webhook delivery are both derived views of the WAL
- **RESP2 only** through Phase 4; RESP3 is a future item

## Phase overview

| Phase | Status | Key deliverable |
|-------|--------|----------------|
| 1 | Planned | Core string cache, RESP server, 10 commands |
| 2 | Planned | Rich types (List/Set/Map) + TTL |
| 3 | Planned | WAL + snapshots + crash recovery |
| 4 | Planned | Durable HTTP webhooks |
| 5 | Planned | Sorted sets, eviction, bootstrap, packaging |
| 6 | Planned | Replication and clustering |

## Conventions

- Java 21 — use virtual threads, records, sealed interfaces, pattern matching where they clarify intent
- Maven single-module until a genuine boundary demands splitting
- No framework (no Spring, no Netty) through Phase 5; plain Java sockets and virtual threads
- YAML config file at `config.yml`; all limits configurable and documented in the design doc
- Tests must use real subprocesses for crash-recovery scenarios (Phase 3+), not mocks
