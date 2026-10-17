// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

class MainArgsSuite extends munit.FunSuite:
  private val base = List("follow", "--genesis-dir", "/g", "--peer", "127.0.0.1:3001")

  test("known flags parse with their defaults") {
    val options = Main.parse(base).toOption.get
    assertEquals(options.queryPort, 7071)
    assertEquals(options.keepSnapshots, 2)
    assertEquals(options.snapshotEvery, None)
    assertEquals(options.mempoolBytes, None)
    assertEquals(Main.DefaultMempoolBytes, 1048576L)
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

  test("--header-first, --max-inbound and --inbound-sync have defaults and refuse bad values") {
    val defaults = Main.parse(base).toOption.get
    assert(defaults.headerFirst && defaults.inboundSync)
    assertEquals(defaults.maxInbound, 64)
    assert(!Main.parse(base ++ List("--header-first", "off")).toOption.get.headerFirst)
    assert(!Main.parse(base ++ List("--inbound-sync", "off")).toOption.get.inboundSync)
    assertEquals(Main.parse(base ++ List("--max-inbound", "5")).toOption.get.maxInbound, 5)
    assert(Main.parse(base ++ List("--header-first", "maybe")).isLeft)
    assert(Main.parse(base ++ List("--max-inbound", "0")).isLeft)
  }

  test("--rules defaults to the old rules; differential takes its policy and repro directory") {
    val defaults = Main.parse(base).toOption.get
    assertEquals(defaults.rules, RulesMode.Old)
    assertEquals(defaults.onDivergence, differential.Policy.Halt)
    assertEquals(defaults.reproDir, None)
    assertEquals(Main.parse(base ++ List("--rules", "new")).toOption.get.rules, RulesMode.New)
    val d = Main
      .parse(
        base ++ List("--rules", "differential", "--on-divergence", "reseed", "--repro-dir", "/r")
      )
      .toOption
      .get
    assertEquals(d.rules, RulesMode.Differential)
    assertEquals(d.onDivergence, differential.Policy.Reseed)
    assertEquals(d.reproDir, Some(java.nio.file.Path.of("/r")))
  }

  test("--rules refuses an unknown mode, and the divergence flags need the differential mode") {
    assert(Main.parse(base ++ List("--rules", "both")).isLeft)
    assert(Main.parse(base ++ List("--rules", "differential", "--on-divergence", "stop")).isLeft)
    assert(Main.parse(base ++ List("--on-divergence", "halt")).isLeft)
    assert(Main.parse(base ++ List("--rules", "old", "--repro-dir", "/r")).isLeft)
    assert(Main.parse(base ++ List("--rules", "old", "--rules", "new")).isLeft)
  }

  test("--cpu-threads takes zero (no pool) or a positive number, nothing else") {
    assertEquals(Main.parse(base ++ List("--cpu-threads", "0")).toOption.get.cpuThreads, 0)
    assertEquals(Main.parse(base ++ List("--cpu-threads", "3")).toOption.get.cpuThreads, 3)
    assert(Main.parse(base ++ List("--cpu-threads", "-1")).isLeft)
    assert(Main.parse(base ++ List("--cpu-threads", "many")).isLeft)
    assert(Main.parse(base).toOption.get.cpuThreads >= 1)
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
