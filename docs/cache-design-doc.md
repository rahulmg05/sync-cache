# sync-cache — Design Document

**Status:** Draft v3 · **Scope:** Single-node to distributed, tested via redis-cli through Phase 5
**Author:** Drafted with Claude · **Date:** July 2026

---

## 1. Context and Goals

sync-cache is an in-memory key-value cache in the spirit of Redis, differentiated by four first-class features: rich value types, durable HTTP webhooks fired on key changes, scheduled snapshots with full crash recovery, and declarative bootstrapping of an empty cache. It is built in Java, speaks the RESP wire protocol so any Redis client library works against it on day one, and is structured from the start so that clustering and replication can be added without a rewrite.

**Functional requirements.** Store values that are plain objects, lists, sets, or maps under simple keys. Let a user register a webhook URL that is called whenever a given key (or key pattern) changes, with at-least-once delivery even if the subscriber is down. Take snapshots on a schedule so the cache rebuilds itself after a crash. Load seed data into an empty cache at startup or on demand.

**Non-functional requirements.** Sub-millisecond reads and writes for in-memory operations. Zero data loss on clean shutdown and bounded loss (ideally none) on crash. Enforced limits on key count, key size, and value size to prevent runaway memory growth. A codebase that extends cleanly to clustering without a rewrite.

**Positioning.** The single defensible differentiator is *"a cache where change notification is a durable, HTTP-native, at-least-once contract, not a best-effort side channel."* Redis keyspace notifications are fire-and-forget pub/sub — if the subscriber is disconnected, the event is gone. Infinispan and Hazelcast fire in-JVM callbacks, not HTTP. etcd's watch API resumes from a revision number (a design we borrow), but etcd is a consensus store and watches are gRPC streams. The combination of RESP compatibility and durable HTTP webhooks is the gap.

Note: crash recovery (WAL + snapshots), rich value types, and JSONL bootstrapping are features Redis already has (AOF, RDB, `redis-cli --pipe`, native types). sync-cache builds them from scratch as the foundation required to make the webhook story work, and as the educational core of the project. They are not claimed as novel.

---

## 2. Architecture Overview

A single JVM process containing six components. The write-ahead log (WAL) is the spine of the system: it provides both crash recovery and the durable webhook outbox from one mechanism.

```
                    ┌──────────────────────────────────────────────┐
                    │               sync-cache daemon              │
                    │                                              │
 redis-cli / clients►  │  Network layer (TCP + virtual threads)   │
 (TCP, RESP)        │        │                                     │
                    │        ▼                                     │
                    │  Command engine (single writer thread)       │
                    │        │ 1. append          │ 2. apply       │
                    │        ▼                    ▼                │
                    │   WAL (disk) ─────►   In-memory store        │
                    │        │                    │                │
                    │        │ tail               │ handoff        │
                    │        ▼                    ▼                │
                    │  Webhook dispatcher    Snapshotter (disk)    │
                    │   (virtual threads,                          │
                    │    per-hook offsets)                         │
                    └────────│─────────────────────────────────────┘
                             ▼
                     Subscriber HTTP endpoints
```

**Write path, step by step.** A client sends `SET user:42 {...}`. The network layer parses the RESP frame and enqueues a command object. The single writer thread dequeues it, validates limits (key size, value size, max key count), appends a record to the WAL, waits for durability per the fsync policy, applies the mutation to the in-memory map, and replies OK. Independently, the webhook dispatcher tails the WAL, sees the committed record at offset N, finds any hooks registered for `user:42`, and POSTs the event. Only when the subscriber returns 2xx does the dispatcher advance that hook's persisted offset to N.

Log first, memory second, notify from the log. The WAL is the single source of truth; the in-memory store and webhook delivery are both derived views of it.

---

## 3. Wire Protocol

sync-cache speaks **RESP2** (Redis Serialization Protocol, version 2) — the same openly documented, plaintext-framed protocol used by Redis itself. This was selected over a custom binary protocol and over gRPC for one reason: instant ecosystem. Every Redis client library in every language — Java Lettuce, Python redis-py, Node ioredis, Go go-redis — works against sync-cache on day one with no changes, and so does `redis-cli`.

Our four novel features become namespaced extension commands (`HOOK.ADD`, `SNAP.NOW`, `BOOT.IMPORT`) — RESP explicitly supports arbitrary commands, so this is idiomatic. A compatibility matrix will document which standard Redis commands are supported in each phase and which are not; this prevents surprise rather than creating it.

**Client strategy by phase.** Phases 1–5 are tested exclusively with `redis-cli`. Because sync-cache speaks RESP2, `redis-cli` works against it with zero changes — including custom commands like `HOOK.ADD`, which it sends and receives as plain RESP arrays. A branded `sync-cache-cli` with custom formatting, tab completion, and sync-cache-specific help text is a Phase 6 deliverable, built only when the product surface is stable enough to justify it.

RESP3 (which adds push messages and richer types) is a future phase item; RESP2 is sufficient through Phase 4.

---

## 4. Data Model

### Keys

Keys are UTF-8 strings on the wire. Integers and UUIDs are string encodings of themselves, keeping the protocol and storage uniform. Floating-point keys are not supported: float equality is unreliable, so lookups would fail unpredictably; users needing decimal keys pass them as strings.

| Constraint | Default | Configurable |
|---|---|---|
| Maximum key length | 512 bytes | Yes |
| Maximum number of keys in the store | 10,000,000 | Yes |
| Allowed key characters | Any valid UTF-8 | No |
| Float keys | Not supported | No |

### Values

Every key maps to a typed value. The five supported types, in order of introduction across phases:

| Type | Description | Introduced |
|---|---|---|
| **String / blob** | Opaque bytes; also the natural container for JSON objects | Phase 1 |
| **List** | Ordered sequence, duplicates allowed; push/pop from either end | Phase 2 |
| **Set** | Unordered collection, unique members | Phase 2 |
| **Map (Hash)** | Field → string dictionary | Phase 2 |
| **Sorted Set** | Set with a float score per member for ranked retrieval | Phase 5 |

### Value size limits

| Constraint | Default | Configurable | Phase |
|---|---|---|---|
| Maximum string/blob value size | 512 MB | Yes | Phase 1 |
| Maximum list length | 2^32 − 1 elements | Yes | Phase 2 |
| Maximum set cardinality | 2^32 − 1 members | Yes | Phase 2 |
| Maximum map field count | 2^32 − 1 fields | Yes | Phase 2 |
| Maximum individual field value size (map) | 512 MB | Yes | Phase 2 |

### TTL

Every key may carry an optional TTL (time-to-live in seconds). Expiry uses lazy evaluation (checked on read) combined with a background sweep thread, the standard design. Keys that exceed the store's max-key limit when the TTL sweep has not yet run do not block writes; they are expired on the next sweep cycle or on next access, whichever comes first.

---

## 5. Phased Delivery Plan

### Phase 1 — Foundation and Core Commands

**Goal:** a working sync-cache daemon that any RESP client can connect to, with string key-value storage and the complete project skeleton in place. Every subsequent phase builds on top of this without structural changes.

**What gets built:**
- Single-module Maven project (`pom.xml` at root, standard `src/main/java` layout); split into Maven modules only when a genuine boundary demands it
- Java packages: `protocol` (RESP encoder + decoder), `store` (in-memory store + value types), `server` (TCP server + command engine)
- RESP2 encoder and decoder — the accumulator pattern from the study plan wired directly into blocking socket reads
- TCP server: plain `ServerSocket` + one virtual thread per connection (`Thread.startVirtualThread(...)`) — no Netty, no NIO boilerplate; Java 21 virtual threads give the same connection scalability with far simpler code
- Single writer thread command engine backed by `ArrayBlockingQueue`
- In-memory store: `ConcurrentHashMap<String, TypedValue>` for reads, mutated only by the writer thread
- String/blob value type with validation against size limits
- Configuration file (YAML): port, max-keys, max-key-size, max-value-size
- **No CLI built.** `redis-cli` is the Phase 1 client; it works against sync-cache out of the box.

**Commands supported:**

| Command | Semantics |
|---|---|
| `PING [message]` | Returns PONG or echoes message |
| `ECHO message` | Returns message |
| `SET key value [EX seconds]` | Store a string value, optional TTL |
| `GET key` | Return value or null bulk string |
| `DEL key [key ...]` | Delete one or more keys, return count deleted |
| `EXISTS key [key ...]` | Return count of keys that exist |
| `TYPE key` | Return the type name of the value |
| `DBSIZE` | Return number of keys in the store |
| `FLUSHDB` | Delete all keys |
| `CONFIG GET parameter` | Return current config values |

**Error responses** for oversized keys, oversized values, and store-full conditions (max keys reached) are part of Phase 1 — limits must be enforced from the first write, not bolted on later.

**Done when:** `redis-cli -p 6379` connects to the running daemon, sets and gets string keys, receives correct RESP error responses when limits are violated, and `DBSIZE` returns the correct count. No custom client code required.

**Claude model:** Sonnet 4.6 is fully sufficient. Haiku 4.5 can handle boilerplate (config parsing, command stub classes). RESP, virtual threads, and plain sockets are extremely well-represented in training data.

---

### Phase 2 — Rich Data Structures and TTL

**Goal:** match Redis's core data structure surface so sync-cache is a credible drop-in for the most common use cases.

**What gets built:**
- List, Set, and Map value types with their per-type size limits
- Lazy TTL expiry on read + background sweep thread (configurable sweep interval)
- `KEYS pattern` with glob matching
- Per-type storage using `LinkedList`, `LinkedHashSet`, `LinkedHashMap` internally

**Commands added:**

| Group | Commands |
|---|---|
| Expiry | `EXPIRE key seconds`, `TTL key`, `PERSIST key` |
| Keys | `KEYS pattern`, `RENAME key newkey`, `RANDOMKEY` |
| Lists | `LPUSH key value [value ...]`, `RPUSH`, `LPOP`, `RPOP`, `LRANGE key start stop`, `LLEN key` |
| Sets | `SADD key member [member ...]`, `SREM`, `SMEMBERS`, `SISMEMBER`, `SCARD` |
| Maps | `HSET key field value [field value ...]`, `HGET`, `HDEL`, `HGETALL`, `HKEYS`, `HVALS`, `HLEN` |

**Done when:** all five data types round-trip correctly; a key with a 1-second TTL expires and returns null on the next GET; `KEYS user:*` returns only matching keys.

**Claude model:** Sonnet 4.6 throughout.

---

### Phase 3 — Durability: WAL and Snapshots

**Goal:** sync-cache survives process crashes with zero acknowledged-write loss.

**What gets built:**
- Write-ahead log: append-only segment files (64 MB each), each record containing `[length | CRC32C | seq | timestamp | opcode | key | value]`
- Fsync policy (configurable): `always`, `everysec` (default), `os`
- Snapshot format: a custom versioned binary file (`snap-<seq>.sc`) — no Java native serialization; header contains magic bytes (`SC01`), version, entry count, and a trailing CRC32C over the full file
- Atomic snapshot publish via temp-file + `Files.move(ATOMIC_MOVE)`
- Recovery sequence: load newest valid snapshot → replay WAL records with seq > snapshot's seq, verifying checksums → truncate on first bad record
- `SNAP.NOW`, `SNAP.SCHEDULE <cron-expr>`, `SNAP.LIST`
- WAL segment garbage collection after a new snapshot covers them

**Recovery guarantee:** with `fsync=always`, zero acknowledged writes are lost on crash. With `fsync=everysec` (default), at most ~1 second of acknowledged writes may be lost — this is documented and matches Redis's AOF default behavior.

**Done when:** run `kill -9` on the daemon mid-traffic, restart it, and prove via `DBSIZE` and spot-checked GETs that no acknowledged write was lost (with `fsync=always`). The crash test must be automated and run 50 iterations without failure.

**Claude model:** Fable 5 or Opus 4.8. Torn-write recovery, fsync semantics, and checksum-based truncation are where subtle bugs hide. Insist on kill-process crash tests, not just happy-path tests.

---

### Phase 4 — Webhook Subsystem

**Goal:** any registered HTTP endpoint receives a durable, at-least-once notification every time a matching key changes.

**What gets built:**
- Hook registry: `{name, glob pattern, URL, optional HMAC-SHA256 secret}`, persisted through the WAL so registrations survive crashes
- WAL dispatcher: one virtual thread per registered hook tails the WAL from its persisted `acked_seq`; on match, POSTs a JSON event body: `{id, key, operation, value, timestamp}`
- `X-SyncCache-Signature` header: `HMAC-SHA256(secret, raw-body)` in hex, verified by the subscriber
- Retry policy: exponential backoff with jitter (1s → 2s → 4s → … capped at 5 min)
- Lag bound: hooks inactive for more than a configurable duration (default 24h) or more than a configurable event count (default 10,000) are marked `LAGGED` and their cursor is force-advanced — a visible, alertable event, not a silent drop
- `acked_seq` per hook is written to a small dedicated file, fsynced after each successful delivery
- Commands: `HOOK.ADD name pattern url [secret]`, `HOOK.DEL name`, `HOOK.LIST`, `HOOK.STATUS name`

**At-least-once guarantee explained:** the cursor advances only after a confirmed 2xx response. If sync-cache crashes after the subscriber processed the event but before the ack file was written, the event will be re-delivered on restart. Subscribers must deduplicate on the event `id` field (which is the WAL sequence number and therefore globally unique and monotonic).

**Slow subscribers cannot block writes.** The write path only appends to the WAL and updates memory. Dispatch is fully asynchronous.

**Done when:** register a hook, kill the subscriber for 10 minutes, restart it, and verify every event that fired during the outage is delivered in order with no gaps.

**Claude model:** Fable 5 / Opus 4.8 for the cursor/ack state machine and the "survive crash mid-delivery" logic. Sonnet 4.6 for HTTP plumbing, retry implementation, and HMAC signing.

---

### Phase 5 — Bootstrap, Sorted Sets, and Operational Completeness

**Goal:** production-ready single-node release. An operator can seed an empty cache, tune all limits, inspect the daemon's state, and ship it with confidence.

**What gets built:**
- `BOOT.IMPORT file [--if-empty | --merge | --overwrite]`: loads a JSONL seed file (one `{"key":..., "type":..., "value":..., "ttl":...}` per line); `--if-empty` is the startup default; imports flow through the WAL (durable) and fire webhooks (suppressed with `--silent`)
- Sorted set type: `ZADD`, `ZRANGE`, `ZRANK`, `ZREM`, `ZSCORE`, `ZCARD`
- `INFO` command: server stats, memory usage, key count, WAL position, hook statuses
- `MEMORY USAGE key`: per-key memory estimate
- `DEBUG SLEEP seconds`: for testing timeout behaviour
- `CLIENT LIST`, `CLIENT KILL`: connection management
- Configurable eviction policy when max-keys is reached: `noeviction` (default, returns error), `allkeys-lru`, `allkeys-lfu`, `volatile-lru` (only evict TTL-bearing keys)
- jlink-trimmed runtime packaging and a Docker image

**Done when:** a fresh node with no data runs `BOOT.IMPORT seed.jsonl --if-empty` on startup, `INFO` reports correct stats, eviction policies are tested under load, and the Docker image starts in under two seconds.

**Claude model:** Sonnet 4.6 throughout; Haiku 4.5 for docs, Dockerfile, and packaging scripts.

---

### Phase 6 — Replication and Clustering

**Goal:** sync-cache scales horizontally and survives node failures without data loss.

**Key insight:** the WAL is already a replication stream in disguise. A replica is just another WAL consumer — identical in structure to a webhook dispatcher but consuming from a remote primary instead of a local file. This is why the single-node architecture was designed the way it was; clustering does not require a rewrite.

**What gets built:**
- Leader/follower replication: followers connect to the leader, request WAL from a given offset, and receive a stream of WAL records to apply to their own in-memory store
- Replication cursor per follower, identical in concept to the webhook dispatcher's `acked_seq`
- Automatic leader election on primary failure (Raft or a simpler primary-takeover protocol TBD at design time)
- Client routing: a smart client or a proxy (like Envoy) routes reads to any replica, writes to the leader
- `CLUSTER INFO`, `CLUSTER NODES`, `REPLICAOF host port`

**Constraints that expand in this phase:**

| Constraint | Single-node default | Cluster behaviour |
|---|---|---|
| Max keys | Configured per node | Sharded across nodes via consistent hashing |
| Max value size | 512 MB | Same per shard |
| Total capacity | RAM of one node | Sum of RAM across all shard primaries |

**Claude model:** Fable 5 / Opus 4.8 for the replication protocol design and leader election. Sonnet 4.6 for the client routing layer and cluster management commands.

---

## 6. Limits Reference

All limits are configurable via the YAML config file and reloadable with `CONFIG SET` (where safe to do so at runtime).

| Limit | Default | Notes |
|---|---|---|
| `max-keys` | 10,000,000 | Applies to the whole store; error on violation (or eviction per policy) |
| `max-key-size` | 512 bytes | Enforced on every write; RESP error returned |
| `max-value-size` | 512 MB | Applies to string/blob and to each field value in a map |
| `max-list-length` | 4,294,967,295 | Effectively unlimited in v1 |
| `max-set-cardinality` | 4,294,967,295 | Effectively unlimited in v1 |
| `max-map-fields` | 4,294,967,295 | Effectively unlimited in v1 |
| `fsync-policy` | `everysec` | `always` / `everysec` / `os` |
| `snapshot-schedule` | (none) | Cron expression |
| `hook-lag-hours` | 24 | Hours before a lagging hook is force-advanced |
| `hook-lag-events` | 10,000 | Event count before a lagging hook is force-advanced |
| `eviction-policy` | `noeviction` | `noeviction` / `allkeys-lru` / `allkeys-lfu` / `volatile-lru` |

---

## 7. Durability Deep Dive

### WAL record format

```
┌──────────────────────────────────────────────────────┐
│ length    4 bytes  big-endian int, covers all fields  │
│ CRC32C    4 bytes  checksum of everything after this  │
│ seq       8 bytes  monotonically increasing           │
│ timestamp 8 bytes  epoch milliseconds                 │
│ opcode    1 byte   SET=1 DEL=2 EXPIRE=3 HOOK=4 …     │
│ key-len   2 bytes  byte length of key                 │
│ key       N bytes  raw UTF-8                          │
│ value-len 4 bytes  byte length of serialised value    │
│ value     M bytes  type-tagged binary payload         │
└──────────────────────────────────────────────────────┘
```

### Snapshot file format

```
┌─────────────────────────────────────────────────────┐
│ magic     4 bytes  ASCII "SC01"                      │
│ version   2 bytes  format version (currently 1)      │
│ count     8 bytes  number of entries                 │
│ wal-seq   8 bytes  WAL seq this snapshot reflects    │
│ entries   …        repeated: [key-len][key][value]   │
│ crc32c    4 bytes  checksum of everything above      │
└─────────────────────────────────────────────────────┘
```

The magic number (`SC01`) makes the file self-identifying and lets recovery reject non-snapshot files cleanly. The version field enables format evolution without breaking existing snapshots.

### Recovery sequence

1. Scan the snapshot directory; find the file with the highest `wal-seq` whose CRC32C passes.
2. Load that snapshot into memory.
3. Open the WAL; seek to the first record with `seq > snapshot.wal-seq`.
4. Replay records in order, verifying each CRC32C; stop and truncate at the first bad record.
5. If no valid snapshot exists, replay the WAL from record 1.
6. If neither exists, start empty; run bootstrap if configured.

---

## 8. Webhook Subsystem Deep Dive

### Event payload (JSON, POSTed to the subscriber URL)

```json
{
  "id": 451023,
  "key": "user:42",
  "operation": "SET",
  "value": "{...}",
  "timestamp": 1720000000000,
  "hook": "user-changes"
}
```

`id` is the WAL sequence number — globally unique, monotonically increasing, and suitable as an idempotency key. For large values (above a configurable threshold, default 64 KB), `value` is omitted and a `fetch_url` field is included instead, pointing to a `GET /keys/{key}` endpoint the subscriber can call to retrieve the full value.

### Delivery state machine per hook

```
PENDING → DELIVERING → ACKED (cursor advances, move to next record)
                    ↘ FAILED  → retry with backoff → DELIVERING
                                  (after lag bound)  → LAGGED (cursor force-advanced, alert fired)
```

### HMAC signature verification (subscriber side, pseudocode)

```
expected = HMAC-SHA256(secret, raw_request_body)
actual   = hex_decode(request.header("X-SyncCache-Signature"))
if not constant_time_equals(expected, actual): reject 401
```

---

## 9. Concurrency Model

All mutations are executed by a single writer thread consuming an `ArrayBlockingQueue` — the Redis model. This buys total ordering of the WAL for free, no locks on the hot path, and trivially consistent snapshots. It is not the bottleneck it sounds like: in-memory operations are nanoseconds; millions of ops/sec are achievable on a single thread.

Reads in Phase 1 go through the same queue (simplest, strictly ordered). A later optimisation (Phase 5+) serves reads concurrently off a `ConcurrentHashMap` snapshot, with the writer thread publishing a new reference after each mutation.

Everything else runs on separate threads and communicates with the writer only through queues and immutable handoffs:

| Component | Threading model |
|---|---|
| Network I/O | Plain `ServerSocket` + one virtual thread per connection (Java 21) |
| Command engine | Single writer thread |
| Snapshot serialisation | One background thread, handed an immutable map reference |
| WAL dispatch | One virtual thread per registered hook |
| TTL sweep | Single background thread, wakes on configurable interval |
| Bootstrap import | Single background thread, writes through the command queue |

---

## 10. Key Decisions

| Decision | Choice | Rationale | Revisit when |
|---|---|---|---|
| Wire protocol | RESP2 | Instant client ecosystem; extension commands for novel features | Add RESP3 push in Phase 4+ |
| Single writer thread | Yes | Correctness, ordering, no hot-path locks | Only if profiling shows it is a bottleneck |
| WAL as webhook outbox | Yes | One durability mechanism serves two features | If WAL retention becomes too large due to lagging hooks |
| Snapshot strategy | Pause-and-handoff in Phase 3 | Simple and correct; brief write stall is acceptable | Switch to copy-on-write above ~1 GB store size |
| Java | Yes | Virtual threads give Netty-class connection scalability with blocking-style code; team familiarity | Ship with jlink or GraalVM native-image to fix startup time |
| Eviction | `noeviction` default | Safe default; operators opt in to eviction | Phase 5 |
| License | Decide before first public commit | Apache-2.0 = adoption; AGPL/BSL = cloud-resale protection | Redis's license saga shows how consequential this is |

---

## 11. Claude Model Recommendations per Phase

Run every phase through **Claude Code** in an agentic loop (compile → test → fix) rather than a chat window. The current lineup, strongest to lightest: **Fable 5** (Mythos-class, above Opus) → **Opus 4.8** → **Sonnet 4.6** → **Haiku 4.5**. Check https://docs.claude.com before starting each phase as the lineup evolves.

| Phase | Deliverable | Model | Why |
|---|---|---|---|
| 1 | RESP encoder/decoder, TCP server (virtual threads), command engine, string type | **Sonnet 4.6**; Haiku 4.5 for boilerplate | Well-trodden; RESP and plain sockets are heavily in training data; tested with redis-cli |
| 2 | List, Set, Map types, TTL sweep | **Sonnet 4.6** | Straightforward data structure work |
| 3 | WAL, snapshots, crash recovery | **Fable 5** (Opus 4.8 if unavailable) | Torn writes, fsync ordering, and replay correctness are where subtle bugs hide; insist on kill-process crash tests |
| 4 | Webhook registry, dispatcher, at-least-once delivery | **Fable 5 / Opus 4.8** for cursor/ack logic; **Sonnet 4.6** for HTTP and retry plumbing | Crash-mid-delivery correctness is Phase-3-grade reasoning |
| 5 | Bootstrap, sorted sets, eviction, packaging | **Sonnet 4.6**; Haiku 4.5 for Dockerfile and docs | Mostly assembly and polish |
| 6 | Replication, clustering, leader election | **Fable 5** | Distributed consensus and replication ordering are the hardest problems in this codebase |

**Workflow that works:** use Fable 5 to design the interfaces and invariants for hard phases and write the test plan; use Sonnet 4.6 to implement against that spec; bring Fable 5 back to review the diff for race conditions and recovery edge cases before merging.

---

## 12. Phase Summary Table

| Phase | Key deliverable | Commands added | Novel infra |
|---|---|---|---|
| 1 | Core string cache, RESP server | PING, ECHO, SET, GET, DEL, EXISTS, TYPE, DBSIZE, FLUSHDB, CONFIG GET | TCP server (virtual threads), RESP codec, command engine, in-memory store, size limits — tested with redis-cli |
| 2 | Rich types + TTL | EXPIRE, TTL, PERSIST, KEYS, RENAME, RANDOMKEY, L*/S*/H* commands | List/Set/Map types, TTL sweep |
| 3 | Crash recovery | SNAP.NOW, SNAP.SCHEDULE, SNAP.LIST | WAL, snapshot format, recovery sequence |
| 4 | Durable webhooks | HOOK.ADD, HOOK.DEL, HOOK.LIST, HOOK.STATUS | Dispatcher, per-hook cursors, HMAC, retry |
| 5 | Production readiness | ZADD/ZRANGE/… (sorted sets), INFO, MEMORY USAGE, CLIENT LIST/KILL, BOOT.IMPORT | Eviction policies, bootstrap, packaging |
| 6 | Distributed + custom CLI | CLUSTER INFO/NODES, REPLICAOF | WAL-based replication, leader election, consistent hashing; branded sync-cache-cli with custom formatting and tab completion |
