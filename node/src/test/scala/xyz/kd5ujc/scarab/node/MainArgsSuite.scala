// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

class MainArgsSuite extends munit.FunSuite:
  private val base = List("follow", "--genesis-dir", "/g", "--peer", "127.0.0.1:3001")

  test("native request windows are independent of body-publication chunks") {
    val defaults = Main.parse(base).fold(e => fail(e.message), identity)
    assertEquals((defaults.chainSyncWindow.low, defaults.chainSyncWindow.high), (200, 300))
    assertEquals(defaults.readAhead, 128)
    val custom = Main
      .parse(base ++ List("--read-ahead", "2", "--chain-sync-low", "3", "--chain-sync-high", "5"))
      .fold(e => fail(e.message), identity)
    assertEquals((custom.chainSyncWindow.low, custom.chainSyncWindow.high), (3, 5))
    assertEquals(custom.readAhead, 2)
    List(("-1", "300"), ("301", "300"), ("0", "0"), ("200", "65536")).foreach { (low, high) =>
      assert(Main.parse(base ++ List("--chain-sync-low", low, "--chain-sync-high", high)).isLeft)
    }
  }

  test("known flags parse with their defaults") {
    val options = Main.parse(base).toOption.get
    assertEquals(options.query, QueryListen.Loopback(QueryListen.DefaultPort))
    assertEquals(options.keepSnapshots, 2)
    assertEquals(options.snapshotEvery, None)
    assertEquals(options.mempoolBytes, None)
  }

  test(
    "--query-listen turns the query server off or names its port, and conflicts with --query-port"
  ) {
    def query(extra: String*) = Main.parse(base ++ extra).map(_.query)
    assertEquals(query("--query-listen", "off"), Right(QueryListen.Off))
    assertEquals(
      query("--query-listen", "7100"),
      Right(
        QueryListen.Loopback(
          QueryListen
            .parse("7100")
            .flatMap {
              case QueryListen.Loopback(p) => Some(p)
              case QueryListen.Off         => None
            }
            .get
        )
      )
    )
    assertEquals(query("--query-port", "7100"), query("--query-listen", "7100"))
    assert(query("--query-listen", "maybe").isLeft)
    assert(query("--query-listen", "70000").isLeft)
    assert(query("--query-port", "off").isLeft)
    assert(query("--query-listen", "off", "--query-port", "7100").isLeft)
  }

  test("a repeated --peer is allowed") {
    val options = Main.parse(base ++ List("--peer", "127.0.0.1:3002")).toOption.get
    assertEquals(options.peers.size, 2)
  }

  test("an unknown flag is a ConfigError with the usage") {
    val result = Main.parse(base ++ List("--db-dir", "/x"))
    assert(
      result.left.exists(e =>
        e.message.contains("unknown flag --db-dir") && e.message.contains("usage:")
      )
    )
  }

  test("a duplicated flag is a ConfigError") {
    val result = Main.parse(base ++ List("--seconds", "1", "--seconds", "2"))
    assert(result.left.exists(_.message.contains("duplicate flag --seconds")))
    assert(Main.parse(base ++ List("--forge", "--forge")).isLeft)
  }

  test("a flag without a value is a ConfigError") {
    assert(Main.parse(base ++ List("--seconds")).isLeft)
  }

  test("legacy identity adoption is explicit, unique, and requires a database") {
    val flag = "--adopt-legacy-network-identity"
    assert(!Main.parse(base).toOption.get.adoptLegacyNetworkIdentity)
    assert(Main.parse(base ++ List(flag)).isLeft)
    val adopted = Main.parse(base ++ List("--db", "/db", flag))
    assert(adopted.exists(_.adoptLegacyNetworkIdentity))
    assert(Main.parse(base ++ List("--db", "/db", flag, flag)).isLeft)
  }

  test("--key-lock defaults to on and refuses other values") {
    assert(Main.parse(base).toOption.get.keyLock)
    assert(!Main.parse(base ++ List("--key-lock", "off")).toOption.get.keyLock)
    assert(Main.parse(base ++ List("--key-lock", "maybe")).isLeft)
  }

  test("--header-first, --max-inbound and --inbound-sync have defaults and refuse bad values") {
    val defaults = Main.parse(base).toOption.get
    assert(defaults.headerFirst && !defaults.inboundSync)
    assertEquals(defaults.maxInbound, 64)
    assert(!Main.parse(base ++ List("--header-first", "off")).toOption.get.headerFirst)
    assert(!Main.parse(base ++ List("--inbound-sync", "off")).toOption.get.inboundSync)
    assert(Main.parse(base ++ List("--inbound-sync", "on")).toOption.get.inboundSync)
    assertEquals(Main.parse(base ++ List("--max-inbound", "5")).toOption.get.maxInbound, 5)
    assert(Main.parse(base ++ List("--header-first", "maybe")).isLeft)
    assert(Main.parse(base ++ List("--max-inbound", "0")).isLeft)
  }

  test("the flags of the removed rules modes are refused with a typed error that says what to do") {
    List(
      List("--rules", "new") -> "rules",
      List("--rules", "differential", "--on-divergence", "reseed") -> "rules",
      List("--mirror-check", "on") -> "mirror-check",
      List("--on-divergence", "halt") -> "on-divergence",
      List("--repro-dir", "/r") -> "repro-dir"
    ).foreach { (flags, name) =>
      val result = Main.parse(base ++ flags)
      assert(
        result.left.exists { e =>
          e.problem match
            case xyz.kd5ujc.scarab.ledger.ConfigProblem
                  .WithUsage(xyz.kd5ujc.scarab.ledger.ConfigProblem.RemovedFlag(flag), _) =>
              flag == name && e.message.contains(s"--$name was removed")
            case _ => false
        },
        s"$flags: $result"
      )
    }
  }

  test("--cpu-threads takes zero (no pool) or a positive number, nothing else") {
    assertEquals(Main.parse(base ++ List("--cpu-threads", "0")).toOption.get.cpuThreads, 0)
    assertEquals(Main.parse(base ++ List("--cpu-threads", "3")).toOption.get.cpuThreads, 3)
    assert(Main.parse(base ++ List("--cpu-threads", "-1")).isLeft)
    assert(Main.parse(base ++ List("--cpu-threads", "many")).isLeft)
    assert(Main.parse(base).toOption.get.cpuThreads >= 1)
  }

  test("--stall-minutes defaults to ten, takes zero (off) or more, nothing else") {
    assertEquals(Main.parse(base).toOption.get.stallMinutes, 10)
    assertEquals(Main.parse(base ++ List("--stall-minutes", "0")).toOption.get.stallMinutes, 0)
    assertEquals(Main.parse(base ++ List("--stall-minutes", "3")).toOption.get.stallMinutes, 3)
    assert(Main.parse(base ++ List("--stall-minutes", "-1")).isLeft)
    assert(Main.parse(base ++ List("--stall-minutes", "soon")).isLeft)
  }

  test("--stall-exit-after is a typed duration, longer than --stall-minutes, off by default") {
    import scala.concurrent.duration.*
    assertEquals(Main.parse(base).toOption.get.stallExitAfter, None)
    assertEquals(
      Main.parse(base ++ List("--stall-exit-after", "30m")).toOption.get.stallExitAfter,
      Some(30.minutes)
    )
    assertEquals(
      Main.parse(base ++ List("--stall-exit-after", "2h")).toOption.get.stallExitAfter,
      Some(2.hours)
    )
    assert(Main.parse(base ++ List("--stall-exit-after", "5m")).isLeft) // not after the warning
    assert(Main.parse(base ++ List("--stall-exit-after", "30")).isLeft)
    assert(Main.parse(base ++ List("--stall-exit-after", "0m")).isLeft)
    assert(Main.parse(base ++ List("--stall-minutes", "0", "--stall-exit-after", "30m")).isLeft)
  }

  test("--churn-interval is a test-only duration, off by default") {
    import scala.concurrent.duration.*
    assertEquals(Main.parse(base).toOption.get.churnInterval, None)
    assertEquals(
      Main.parse(base ++ List("--churn-interval", "20s")).toOption.get.churnInterval,
      Some(20.seconds)
    )
    assert(Main.parse(base ++ List("--churn-interval", "0s")).isLeft)
    assert(Main.parse(base ++ List("--churn-interval", "soon")).isLeft)
  }

  test("--dump-state takes a directory and optional points; the points alone are refused") {
    val on = Main.parse(base ++ List("--dump-state", "/d")).toOption.get
    assertEquals(
      on.dumpState,
      Some((java.nio.file.Path.of("/d"), StateDump.Targets.EpochBoundaries(1)))
    )
    assertEquals(on.dumpStateFull, false)
    val slots = Main
      .parse(base ++ List("--dump-state", "/d", "--dump-state-at", "100,200", "--dump-state-full"))
      .toOption
      .get
    assertEquals(
      slots.dumpState.map(_._2),
      Some(StateDump.Targets.Slots(List(BigInt(100), BigInt(200))))
    )
    assertEquals(slots.dumpStateFull, true)
    assert(Main.parse(base ++ List("--dump-state-at", "100")).isLeft)
    assert(Main.parse(base ++ List("--dump-state", "/d", "--dump-state-at", "later")).isLeft)
  }

  test("--metrics-listen is off by default and takes an address, a bad one is a ConfigError") {
    assertEquals(Main.parse(base).toOption.get.metricsListen, None)
    val listening = Main.parse(base ++ List("--metrics-listen", "0.0.0.0:12798")).toOption.get
    assertEquals(listening.metricsListen.map(_.toString), Some("0.0.0.0:12798"))
    assert(Main.parse(base ++ List("--metrics-listen", "12798")).isLeft)
    assert(Main.parse(base ++ List("--metrics-listen", "host:port")).isLeft)
  }

  test("the log flags: sinks, repeated thresholds and samplings, refusals with the usage") {
    import xyz.kd5ujc.scarab.node.log.*
    assertEquals(Main.parse(base).toOption.get.log, LogSettings.default)
    val log = Main
      .parse(
        base ++ List(
          "--log-file",
          "/data/sync.jsonl",
          "--log-file-size",
          "64M",
          "--log-severity",
          "stdout:Chain=Notice",
          "--log-severity",
          "stdout:Chain.Reconnecting=Warning",
          "--log-every",
          "stdout:Chain.Extended=1000"
        )
      )
      .toOption
      .get
      .log
    assertEquals(
      log.file.map(f => (f.path.toString, f.maxBytes)),
      Some("/data/sync.jsonl" -> 64L * 1024 * 1024)
    )
    assertEquals(log.threshold(Sink.Stdout, EventKind.Epoch), Threshold.AtLeast(Severity.Notice))
    assertEquals(log.threshold(Sink.File, EventKind.Extended), Threshold.AtLeast(Severity.Debug))
    assertEquals(log.sampling(Sink.Stdout, EventKind.Extended).map(_._2), Some(1000L))
    val refused = Main.parse(base ++ List("--log-severity", "Chain.Nope=Info"))
    assert(
      refused.left.exists(e => e.message.contains("no namespace") && e.message.contains("usage:"))
    )
  }

  test("--plutus-workers admits a positive bound and supports exact serial validation") {
    assertEquals(
      Main.parse(base ++ List("--plutus-workers", "1")).toOption.get.plutusWorkers.value,
      1
    )
    for value <- List("0", "-1", "many", "2147483648") do
      assert(Main.parse(base ++ List("--plutus-workers", value)).isLeft)
  }
