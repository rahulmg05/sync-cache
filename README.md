# sync-cache

An in-memory key-value cache built in Java 21 that speaks **RESP2** — the same wire protocol as Redis — so every Redis client library and `redis-cli` work against it with zero changes.

sync-cache is a learning project and a Redis-compatible cache server. Its one genuine differentiator from Redis is **durable, HTTP-native, at-least-once webhooks**: Redis keyspace notifications are fire-and-forget pub/sub — if the subscriber is disconnected, the event is lost. sync-cache uses an append-only WAL as the webhook outbox, with a per-hook cursor that advances only after the subscriber confirms delivery. Subscribers that go down for hours receive every missed event in order when they come back.

Everything else — rich value types, crash recovery via WAL + snapshots, JSONL bootstrapping — is sync-cache implementing the same features Redis already has, as the foundation needed to build the webhook story credibly. Building these from scratch against the RESP protocol is the educational core of the project.

---

## Architecture in brief

```
redis-cli / clients (TCP, RESP)
        │
        ▼
  Network layer  (virtual threads, one per connection)
        │
        ▼
  Command engine  (single writer thread + ArrayBlockingQueue)
        │               │
        ▼               ▼
   WAL (disk) ──► In-memory store (ConcurrentHashMap)
        │               │
        ▼               ▼
 Webhook dispatcher  Snapshotter
 (per-hook cursors,  (background thread,
  virtual threads)    immutable handoff)
```

The WAL is the spine: it provides both crash recovery and the durable webhook outbox from one mechanism. Log first, memory second, notify from the log.

---

## Phases

| Phase | What ships | Key commands |
|-------|-----------|--------------|
| 1 | RESP server, string store, size limits | `PING` `SET` `GET` `DEL` `EXISTS` `TYPE` `DBSIZE` `FLUSHDB` `CONFIG GET` |
| 2 | Rich types (List/Set/Map) + TTL sweep | `EXPIRE` `TTL` `KEYS` `LPUSH/RPUSH` `SADD` `HSET` … |
| 3 | WAL + snapshots + crash recovery | `SNAP.NOW` `SNAP.SCHEDULE` `SNAP.LIST` |
| 4 | Durable HTTP webhooks | `HOOK.ADD` `HOOK.DEL` `HOOK.LIST` `HOOK.STATUS` |
| 5 | Sorted sets, eviction, bootstrap, packaging | `ZADD` `ZRANGE` `INFO` `BOOT.IMPORT` `CLIENT LIST` |
| 6 | Replication, clustering, custom CLI | `REPLICAOF` `CLUSTER INFO/NODES` |

---

## Quickstart (once Phase 1 is complete)

```bash
# Build
mvn package -q

# Run
java -jar target/sync-cache.jar --config config.yml

# Connect with redis-cli
redis-cli -p 6379 PING
redis-cli -p 6379 SET user:1 '{"name":"Alice"}'
redis-cli -p 6379 GET user:1
```

---

## Limits (all configurable in `config.yml`)

| Limit | Default |
|-------|---------|
| Max keys | 10,000,000 |
| Max key size | 256 bytes |
| Max value size | 1 MB |
| Fsync policy | `everysec` |
| Eviction policy | `noeviction` |

---

## Design document

See [`docs/cache-design-doc.md`](docs/cache-design-doc.md) for the full design — wire protocol rationale, WAL record format, snapshot format, webhook delivery semantics, concurrency model, and phased delivery plan.

## Agent guidance

See [`AGENTS.md`](AGENTS.md) for which Claude model to use in each phase and how to run the agentic implementation loop.
