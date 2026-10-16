# 0014. A typed log: Tell of lines built from typed events, routed by namespace and severity

- **Status**: Accepted
- **Date**: 2026-10-16
- **Issue**: #92

## Context

The node wrote one JSON object per line to stdout through `JsonLog`, about 50 kinds of line, each named by a string
inside the function that printed it. A line had no severity and no namespace, so an error and a per-block line
looked alike, and there was one sink: the per-block `extended` line alone reaches several GB on mainnet, and the
long runs wrapped the JVM in a shell pipe (`tee` to a file, a filter for stdout) that also kept `docker stop`'s
signal from reaching the JVM. The libraries' logs (http4s, fs2) went to SLF4J 1.7 with no backend bound and were
dropped, with the `StaticLoggerBinder` warning at every start. The devnet scenarios and the run watchers parse the
JSON lines, so their bytes must not change.

The reference (ADR 0001) is trace-dispatcher, the Haskell node's tracing (hermod-tracing
`trace-dispatcher/src/Cardano/Logging/Types.hs`): every message has a namespace and a severity (`MetaTrace`, lines
170-179; `SeverityS`, 266-277) and a machine and a human form (`LogFormatting`, 149-167); the configuration gives
per namespace a severity filter, backends and a frequency limit, looked up from the longest prefix
(`Configuration.hs` 420-425); the limiter passes at most so many messages per second (`FrequencyLimiter.hs` 48-88).

## Decision

- **Events are typed, lines are built only from them.** `Traced[E]` (a type class; instances in the companions of the
  node's event types, and in `Traced`'s for the event types of other modules) gives the `Line` of an event: its
  `EventKind`, its severity and its JSON fields. `EventKind` is a closed enum: each case names the namespace
  (`Area.Kind`, two closed levels) and the usual severity, and the JSON `event` name is the case name with a
  lower-case initial. `Line`'s constructor is private to the log package, so nothing logs an untyped line, and an
  instance matches on its event type, so a new case does not compile until it has a line. `NodeEvent` is the closed
  type of the node's own lines (start, stop, the state dump, the mirror check, the inbound and local-socket
  connections, the libraries' lines, ...).
- **The capability is cats-mtl's `Tell`.** `type Log[F[_]] = Tell[F, Line]`, given through `using`; `LogRouter` is
  the interpreter and `IO` appears only in `Main` and the tools. A test collects the lines with any `Tell`.
- **Routing as trace-dispatcher, with a count for the limit.** Sinks are stdout, stderr and a size-rotated file, each
  JSON or human. Per sink and namespace a `Threshold` (a severity, or `Silence`) and a sampling (one line in `N`),
  looked up from the most specific namespace that sets them. The sampling is a count, not a rate: the lines kept do
  not depend on timing (two runs of a chain keep the same lines, a test can say which), and during a sync one
  `extended` line in `N` is a readable rate whatever the speed. The configuration is on the command line
  (`--log-*`), not the configuration file's `TraceOptions`, whose namespaces are the Haskell node's.
- **Defaults are the old log**: every line, JSON, on stdout. The JSON is `JsonLog`'s byte for byte, held by a
  golden of one line per event (`fixtures/log/golden.jsonl`), which also checks that the samples cover every case
  of every event type and every kind.
- **SLF4J 2.0 with the node's own provider.** `LibraryLogs` implements `SLF4JServiceProvider` (found by
  `ServiceLoader`) and turns each library line into a `NodeEvent.Library` (`Library.Slf4j`, the SLF4J level as
  the severity) sent to the same sinks. slf4j-simple, Logback or Log4j 2 would each be a second logging pipeline
  with their own outputs and configuration; the provider is about 60 lines. The 2.0 API replaces the 1.7.36 that
  log4cats and log4s bring (their calls are kept by 2.0), because 1.7's binding is a class with static members,
  which Scala does not express directly. SLF4J's `LoggerFactory` is static, so the route from it to the log is one
  `AtomicReference` that `LogRouter.resource` sets and clears: the only global state of the log, and documented
  there.
- **Writes are serialised.** One lock over all sinks, a line written before `tell` returns, as `println` was: lines
  are whole and in the same order everywhere, and the last lines of a run are out before the process exits.

## Consequences

- An event's name, namespace and severity are checked by the compiler; the documentation's catalogue
  (`docs/running.md`, "Log events") is checked against the enum by `LogDocsSuite`.
- Three lines differ from `JsonLog`'s, on purpose: the `kind` of an `error` whose error is a case without fields is
  its name (it was the empty string, `getSimpleName` of an anonymous class); `stateDump` has an `at` like every other
  line; and there are new lines (`verificationCache`, which was a text line on stderr, and `slf4j`). The http4s
  server now logs one `slf4j` line at start ("service bound").
- The long-run scripts can drop the shell pipe and `exec` the JVM (running.md, "Logging").
- The usual severity of each kind is the node's own choice: the Haskell node's per-message severities (cardano-node
  `Cardano/Node/Tracing/Tracers/*.hs`) were not compared one by one; the levels, the namespaces, the lookup and
  the limiter are what is mirrored.
- Not mirrored: deeper namespaces, detail levels, privacy, the forwarder to `cardano-tracer`, EKG, the Haskell
  node's JSON shape (`ns`, `sev`, `data`) and its configuration keys. A sink that cannot be written (a full disk)
  fails the run as `println` did. Revisit when a tool that reads the Haskell node's trace format must read ours, or
  when per-event detail levels are needed.
