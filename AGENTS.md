# AGENTS.md — sync-cache Agent Guidance

This file defines the agentic loop workflow, architectural invariants, and project standards for sync-cache.

---

## Agentic loop workflow

Run every phase through **Claude Code in an agentic loop**: compile → checkstyle → test → fix → repeat. Do not use a chat window for implementation.

For phases involving WAL recovery, crash-mid-delivery correctness, or distributed consensus, have a stronger model review the diff for race conditions and recovery edge cases before merging.

---

## Architectural invariants — never break these

1. **Log first, memory second.** The WAL is written and fsynced before the in-memory store is updated. Any change that reverses this order is a correctness bug.
2. **Single writer thread owns all mutations.** No component other than the command engine's writer thread may mutate the in-memory store directly.
3. **Webhook cursor advances only after 2xx.** The `acked_seq` for a hook must not advance until the subscriber HTTP response is confirmed 2xx. This is the at-least-once contract.
4. **Snapshots are published atomically.** Always write to a temp file and rename with `ATOMIC_MOVE`. Never write to the final path in place.
5. **Limits are enforced on every write.** Key size, value size, and max-key-count checks happen in the command engine before WAL append, not after.

---

## Project standards

### Formatting and style

- **Indentation:** 2 spaces. No tabs anywhere — Java source, XML, YAML, all of it.
- **Indentation:** 2 spaces. No tabs anywhere — Java source, XML, YAML, all of it.
- **Checkstyle** is enforced on every build via the Maven Checkstyle plugin. Config lives at `checkstyle/checkstyle.xml`. The build **must fail** on any violation — do not suppress warnings without a comment explaining why.
- **Line length:** 120 characters max.
- **Braces:** always on the same line (K&R style). Braces are required even for single-line `if`/`for`/`while` bodies.
- **Blank lines:** one blank line between methods; no trailing whitespace.

The Maven Checkstyle plugin **must** be wired into `pom.xml` as follows so that `mvn verify` fails on violations:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-checkstyle-plugin</artifactId>
  <version>3.3.1</version>
  <configuration>
    <configLocation>checkstyle/checkstyle.xml</configLocation>
    <consoleOutput>true</consoleOutput>
    <failsOnError>true</failsOnError>
    <failOnViolation>true</failOnViolation>
    <violationSeverity>error</violationSeverity>
  </configuration>
  <executions>
    <execution>
      <id>checkstyle</id>
      <phase>verify</phase>
      <goals>
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

Run checkstyle manually:
```bash
mvn checkstyle:check
```

### Java conventions

- **Java 21 features** — use records for immutable data carriers, sealed interfaces + pattern matching for type dispatch (e.g. `TypedValue` variants), virtual threads for I/O concurrency. Do not use these features where plain classes are clearer.
- **No frameworks** — no Spring, no Netty, no Guice through Phase 5. Plain Java sockets, `java.net.http.HttpClient`, and `java.util.concurrent` only.
- **Checked exceptions** — do not declare checked exceptions across package boundaries; wrap in unchecked domain exceptions (`SyncCacheException`) at the boundary.
- **Nulls** — prefer `Optional<T>` at public API boundaries. Do not pass or return `null` from public methods.
- **Immutability** — value objects (command arguments, WAL records, config) must be immutable. Use `final` fields and no setters.
- **Naming** — follow standard Java conventions: `PascalCase` for types, `camelCase` for methods and fields, `UPPER_SNAKE` for constants. Package names are all lowercase with no underscores.

### Code structure

- **Single-module Maven** until a genuine boundary demands splitting (Phase 6 clustering is the likely trigger).
- **Package layout** — one package per architectural layer; classes do not reach across layers except through defined interfaces:

```
src/main/java/io/synccache/
  protocol/     RESP2 encoder + decoder
  store/        In-memory store, TypedValue, value type implementations
  server/       TCP server, command engine, command handlers
  config/       Config file parsing and validation
  wal/          (Phase 3) WAL writer, segment files, recovery
  webhook/      (Phase 4) Hook registry, dispatcher, retry, HMAC
  bootstrap/    (Phase 5) BOOT.IMPORT loader
  cluster/      (Phase 6) Replication, leader election
```

- **Command handlers** — each command is a separate class implementing a `CommandHandler` interface. No `switch` on command name strings scattered across the engine.
- **Config** — all tunable values come from `config.yml`, parsed once at startup into an immutable `ServerConfig` record. No magic constants in application code; reference `config.fieldName`.

### Testing standards

- **Framework:** JUnit 5 + AssertJ. No Mockito unless mocking an external HTTP endpoint in webhook tests.
- **Test layout:** `src/test/java/` mirrors the main package layout exactly.
- **Coverage target:** every public method in `protocol/`, `store/`, and `wal/` must have at least one test. Command handler tests must cover the happy path and at least one error path (wrong type, limit exceeded).
- **Integration tests** — any test that starts the server must use a random port (`port: 0`) and shut the server down in `@AfterEach`. No tests may assume port 6379 is free.
- **Crash-recovery tests (Phase 3+)** — must spawn a real subprocess via `ProcessBuilder`, not a mocked one. Mocking `kill -9` is not a crash test.
- **Test naming:** `methodName_whenCondition_thenExpectedBehavior` (e.g. `set_whenValueExceedsLimit_returnsError`).

### Error handling

- **RESP errors** — all client-visible errors are returned as RESP error responses (`-ERR ...` or `-WRONGTYPE ...`). Never close the connection on a command error.
- **Internal errors** — unrecoverable errors (WAL write failure, OOM) log at ERROR level and shut down the server cleanly via the shutdown hook. Do not swallow them.
- **No silent fallbacks** — if a config value is missing or invalid, fail at startup with a clear message. Do not silently substitute a default at runtime.

### Logging

- Use `System.Logger` (JEP 264, built into Java 9+). No SLF4J or Log4j dependency through Phase 5.
- Log levels: DEBUG for per-command tracing (off by default), INFO for server lifecycle events, WARN for recoverable anomalies (e.g. hook delivery failure before retry), ERROR for unrecoverable failures.
- Never log key values or value content at INFO or above — they may contain PII.

### Build and CI hygiene

- `mvn verify` must pass (compile + checkstyle + tests) with zero failures before any commit.
- No compiler warnings suppressed with `@SuppressWarnings` without a comment explaining why.
- Dependencies added to `pom.xml` must be justified; prefer the Java standard library.

---

## Build commands

```bash
mvn compile            # compile
mvn checkstyle:check   # lint only
mvn test               # run all tests
mvn verify             # compile + checkstyle + test (run this before committing)
mvn package -q         # build fat jar → target/sync-cache.jar

# Run the server
java -jar target/sync-cache.jar --config config.yml

# Smoke test (requires running server)
redis-cli -p 6379 PING
redis-cli -p 6379 SET foo bar
redis-cli -p 6379 GET foo
```
