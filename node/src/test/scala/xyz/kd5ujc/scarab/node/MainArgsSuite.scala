// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.ConfigProblem
import xyz.kd5ujc.scarab.network.OutboundLimit

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
      assert(Main.parse(base ++ List("--chain-sync-low", low, "--chain-sync-high", high)) match {
        case Left(
              xyz.kd5ujc.scarab.ledger
                .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
            ) =>
          true
        case _ => false
      })
    }
  }

  test("known flags parse with their defaults") {
    val options = Main.parse(base).toOption.get
    assertEquals(options.query, QueryListen.Loopback(QueryListen.DefaultPort))
    assertEquals(options.keepSnapshots, 2)
    assertEquals(options.snapshotEvery, None)
    assertEquals(options.mempoolBytes, None)
    assertEquals(options.assertionsFile, None)
  }

  test("--assertions-file names the file of the assertions, once") {
    val file = java.nio.file.Path.of("/out/sdk.jsonl")
    assertEquals(
      Main.parse(base ++ List("--assertions-file", "/out/sdk.jsonl")).map(_.assertionsFile),
      Right(Some(file))
    )
    Main.parse(base ++ List("--assertions-file", "/a", "--assertions-file", "/b")) match
      case Left(
            xyz.kd5ujc.scarab.ledger.ConfigError(
              xyz.kd5ujc.scarab.ledger.ConfigProblem.WithUsage(
                xyz.kd5ujc.scarab.ledger.ConfigProblem.DuplicateFlag(flag),
                _
              )
            )
          ) =>
        assertEquals(flag, "--assertions-file")
      case other => fail(s"a repeated --assertions-file is refused, got $other")
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
    assert(query("--query-listen", "maybe") match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
    assert(query("--query-listen", "70000") match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
    assert(query("--query-port", "off") match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
    assert(query("--query-listen", "off", "--query-port", "7100") match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
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
    assert(Main.parse(base ++ List("--forge", "--forge")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.DuplicateFlag(_), _))
          ) =>
        true
      case _ => false
    })
  }

  test("a flag without a value is a ConfigError") {
    assert(Main.parse(base ++ List("--seconds")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.MissingFlagValue(_), _))
          ) =>
        true
      case _ => false
    })
  }

  test("legacy identity adoption is explicit, unique, and requires a database") {
    val flag = "--adopt-legacy-network-identity"
    assert(!Main.parse(base).toOption.get.adoptLegacyNetworkIdentity)
    assert(Main.parse(base ++ List(flag)) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagDependency(_, _), _))
          ) =>
        true
      case _ => false
    })
    val adopted = Main.parse(base ++ List("--db", "/db", flag))
    assert(adopted.exists(_.adoptLegacyNetworkIdentity))
    assert(Main.parse(base ++ List("--db", "/db", flag, flag)) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.DuplicateFlag(_), _))
          ) =>
        true
      case _ => false
    })
  }

  test("--start-as-non-producing-node and its deprecated alias start with forging disabled") {
    def nonProducing(flags: String*) =
      Main.parse(base ++ flags.toList).map(_.startAsNonProducingNode)
    assertEquals(nonProducing(), Right(false))
    assertEquals(nonProducing("--start-as-non-producing-node"), Right(true))
    assertEquals(nonProducing("--non-producing-node"), Right(true))
    assertEquals(nonProducing("--non-producing-node", "--start-as-non-producing-node"), Right(true))
    assert(nonProducing("--start-as-non-producing-node", "--start-as-non-producing-node") match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.DuplicateFlag(_), _))
          ) =>
        true
      case _ => false
    })
    assert(nonProducing("--non-producing-node", "--non-producing-node") match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.DuplicateFlag(_), _))
          ) =>
        true
      case _ => false
    })
  }

  test("the flag wins over StartAsNonProducingNode, which wins over the default") {
    def starts(flag: Boolean, key: Option[Boolean]) =
      Main
        .parse(base)
        .toOption
        .get
        .copy(startAsNonProducingNode = flag)
        .startsNonProducing(ConfigSettings(startAsNonProducingNode = key))
    assert(!starts(flag = false, None))
    assert(starts(flag = false, Some(true)))
    assert(!starts(flag = false, Some(false)))
    assert(starts(flag = true, Some(false)))
    assert(starts(flag = true, None))
  }

  test("--key-lock defaults to on and refuses other values") {
    assert(Main.parse(base).toOption.get.keyLock)
    assert(!Main.parse(base ++ List("--key-lock", "off")).toOption.get.keyLock)
    assert(Main.parse(base ++ List("--key-lock", "maybe")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
  }

  test("--producer-id defaults to Scarab's identifier and refuses other values (#1348)") {
    import xyz.kd5ujc.scarab.node.forge.ProducerSignal
    assertEquals(Main.parse(base).map(_.producerSignal), Right(ProducerSignal.Identified))
    assertEquals(
      Main.parse(base ++ List("--producer-id", "opt-out")).map(_.producerSignal),
      Right(ProducerSignal.OptedOut)
    )
    assertEquals(
      Main.parse(base ++ List("--producer-id", "scarab")).map(_.producerSignal),
      Right(ProducerSignal.Identified)
    )
    assert(Main.parse(base ++ List("--producer-id", "42")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
  }

  test(
    "--header-first and --max-inbound have defaults and refuse bad values; --inbound-sync is gone"
  ) {
    val defaults = Main.parse(base).toOption.get
    assert(defaults.headerFirst)
    assertEquals(defaults.maxInbound, None)
    assert(!Main.parse(base ++ List("--header-first", "off")).toOption.get.headerFirst)
    // our clients run only where the outbound governor promotes a peer (issue 854)
    assert(Main.parse(base ++ List("--inbound-sync", "on")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.UnknownFlag(_), _))
          ) =>
        true
      case _ => false
    })
    assertEquals(
      Main.parse(base ++ List("--max-inbound", "5")).toOption.get.maxInbound.map(_.toLong),
      Some(5L)
    )
    assert(Main.parse(base ++ List("--header-first", "maybe")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
    assert(Main.parse(base ++ List("--max-inbound", "0")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
  }

  test(
    "--max-outbound is off by default, takes a positive number and wins over the configuration"
  ) {
    val defaults = Main.parse(base).toOption.get
    assertEquals(defaults.maxOutbound, None)
    assertEquals(defaults.outboundLimit(ConfigSettings()), OutboundLimit.Unlimited)
    val flagged = Main.parse(base ++ List("--max-outbound", "50")).toOption.get
    assertEquals(flagged.maxOutbound.map(_.value), Some(50))
    val fifty = OutboundLimit.AtMost(OutboundLimit.Connections.from(50).toOption.get)
    val sixty = OutboundLimit.Connections.from(60).toOption.get
    assertEquals(flagged.outboundLimit(ConfigSettings()), fifty)
    assertEquals(flagged.outboundLimit(ConfigSettings(maxOutboundConnections = Some(sixty))), fifty)
    assertEquals(
      defaults.outboundLimit(ConfigSettings(maxOutboundConnections = Some(sixty))),
      OutboundLimit.AtMost(sixty)
    )
    List("0", "-1", "fifty", "1.5", "4294967296").foreach(v =>
      Main.parse(base ++ List("--max-outbound", v)).left.map(_.problem) match
        case Left(ConfigProblem.WithUsage(ConfigProblem.FlagValue(flag, value), _)) =>
          assertEquals((flag, value), ("max-outbound", v))
        case other => fail(s"--max-outbound $v was not refused: $other")
    )
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

  test(
    "--utxo-warm-mb is absent for the heap's default, 0 for no tier, or a budget, nothing else"
  ) {
    assertEquals(Main.parse(base).map(_.utxoWarmMb), Right(None))
    assertEquals(Main.parse(base ++ List("--utxo-warm-mb", "0")).map(_.utxoWarmMb), Right(Some(0)))
    assertEquals(
      Main.parse(base ++ List("--utxo-warm-mb", "64")).map(_.utxoWarmMb),
      Right(Some(64))
    )
    List("-1", "many").foreach { value =>
      assert(
        Main.parse(base ++ List("--utxo-warm-mb", value)) match {
          case Left(
                xyz.kd5ujc.scarab.ledger
                  .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
              ) =>
            true
          case _ => false
        },
        value
      )
    }
  }

  test("--cpu-threads takes zero (no pool) or a positive number, nothing else") {
    assertEquals(Main.parse(base ++ List("--cpu-threads", "0")).toOption.get.cpuThreads, 0)
    assertEquals(Main.parse(base ++ List("--cpu-threads", "3")).toOption.get.cpuThreads, 3)
    assert(Main.parse(base ++ List("--cpu-threads", "-1")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
    assert(Main.parse(base ++ List("--cpu-threads", "many")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
    assert(Main.parse(base).toOption.get.cpuThreads >= 1)
  }

  test("--stall-minutes defaults to ten, takes zero (off) or more, nothing else") {
    assertEquals(Main.parse(base).toOption.get.stallMinutes, 10)
    assertEquals(Main.parse(base ++ List("--stall-minutes", "0")).toOption.get.stallMinutes, 0)
    assertEquals(Main.parse(base ++ List("--stall-minutes", "3")).toOption.get.stallMinutes, 3)
    assert(Main.parse(base ++ List("--stall-minutes", "-1")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
    assert(Main.parse(base ++ List("--stall-minutes", "soon")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
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
    assert(Main.parse(base ++ List("--stall-exit-after", "5m")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    }) // not after the warning
    assert(Main.parse(base ++ List("--stall-exit-after", "30")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
    assert(Main.parse(base ++ List("--stall-exit-after", "0m")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
    assert(Main.parse(base ++ List("--stall-minutes", "0", "--stall-exit-after", "30m")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
  }

  test("--churn-interval is a test-only duration, off by default") {
    import scala.concurrent.duration.*
    assertEquals(Main.parse(base).toOption.get.churnInterval, None)
    assertEquals(
      Main.parse(base ++ List("--churn-interval", "20s")).toOption.get.churnInterval,
      Some(20.seconds)
    )
    assert(Main.parse(base ++ List("--churn-interval", "0s")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
    assert(Main.parse(base ++ List("--churn-interval", "soon")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
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
    assert(Main.parse(base ++ List("--dump-state-at", "100")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
    assert(Main.parse(base ++ List("--dump-state", "/d", "--dump-state-at", "later")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
          ) =>
        true
      case _ => false
    })
  }

  test("--metrics-listen is off by default and takes an address, a bad one is a ConfigError") {
    assertEquals(Main.parse(base).toOption.get.metricsListen, None)
    val listening = Main.parse(base ++ List("--metrics-listen", "0.0.0.0:12798")).toOption.get
    assertEquals(listening.metricsListen.map(_.toString), Some("0.0.0.0:12798"))
    assert(Main.parse(base ++ List("--metrics-listen", "12798")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.PeerAddress(Peer.AddressError.Invalid(_)))
          ) =>
        true
      case _ => false
    })
    assert(Main.parse(base ++ List("--metrics-listen", "host:port")) match {
      case Left(
            xyz.kd5ujc.scarab.ledger
              .ConfigError(ConfigProblem.PeerAddress(Peer.AddressError.Invalid(_)))
          ) =>
        true
      case _ => false
    })
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
      assert(Main.parse(base ++ List("--plutus-workers", value)) match {
        case Left(
              xyz.kd5ujc.scarab.ledger
                .ConfigError(ConfigProblem.WithUsage(ConfigProblem.FlagValue(_, _), _))
            ) =>
          true
        case _ => false
      })
  }

  test("--shelley-bls-key names the voting keys of a forging node, and nothing without --forge") {
    val keys = List("--forge", "--vrf-skey", "v", "--kes-skey", "k", "--opcert", "o")
    val bls = List("--shelley-bls-key", "bls.skey")
    assertEquals(
      Main.parse(base ++ keys ++ bls).map(_.forge.flatMap(_.blsSkey)),
      Right(Some(java.nio.file.Path.of("bls.skey")))
    )
    assertEquals(Main.parse(base ++ keys).map(_.forge.flatMap(_.blsSkey)), Right(None))
    assertEquals(Main.parse(base ++ bls).map(_.forge), Right(None))
  }

  test(
    "--plutus-vm is refused as a removed flag that says what to do: the node has one evaluator"
  ) {
    import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
    val keys = List("--forge", "--vrf-skey", "v", "--kes-skey", "k", "--opcert", "o")
    val named = List("scalus", "scarab").flatMap { value =>
      List(base ++ List("--plutus-vm", value), base ++ keys ++ List("--plutus-vm", value))
    }
    for flags <- named :+ (base ++ List("--plutus-vm")) do
      Main.parse(flags) match
        case Left(
              e @ ConfigError(
                ConfigProblem.WithUsage(ConfigProblem.RemovedFlag("plutus-vm"), _)
              )
            ) =>
          assert(e.message.contains("--plutus-vm was removed"), e.message)
          assert(e.message.contains("only Plutus evaluator; drop the flag"), e.message)
        case other => fail(s"$flags: $other")
    assert(Main.parse(base ++ keys).exists(_.forge.isDefined))
  }
