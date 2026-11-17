// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Applicative
import cats.effect.{IO, Ref}
import cats.mtl.Tell
import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.node.log.{EventKind, Line, Log}

/** The network configuration as cardano-node 11.1.3 reads it at start-up
  * (`cardano-node/src/Cardano/Node/Run.hs` 349-379): the topology, what its reading says of it, the
  * warning of a producer with peer sharing on, then the peer snapshot.
  */
class NetworkConfigSuite extends CatsEffectSuite:
  private val temporary = fs2.io.file.Files.forAsync[IO].tempDirectory.map(_.toNioPath)
  private val preview = Path.of("fixtures", "preview", "genesis")
  private val forge = List("--forge", "--vrf-skey", "v", "--kes-skey", "k", "--opcert", "o")

  /** The preview genesis in `root`, with its configuration in `mode` and `PeerSharing` as given
    * (absent: the reference's default).
    */
  private def configured(root: Path, mode: String, peerSharing: Option[Boolean]): Path =
    List("byron", "shelley", "alonzo", "conway").foreach(n =>
      Files.copy(preview.resolve(s"$n-genesis.json"), root.resolve(s"$n-genesis.json"))
    )
    Files.copy(preview.resolve("checkpoints.json"), root.resolve("checkpoints.json"))
    val base =
      parser
        .parse(Files.readString(preview.resolve("config.json")))
        .fold(e => fail(e.toString), identity)
    val sharing = peerSharing.fold(Json.obj())(p => Json.obj("PeerSharing" -> Json.fromBoolean(p)))
    val config = root.resolve("config.json")
    Files.writeString(
      config,
      base.deepMerge(Json.obj("ConsensusMode" -> Json.fromString(mode))).deepMerge(sharing).noSpaces
    )
    config

  private def options(args: List[String]): Main.Options =
    Main.parse("follow" :: args).fold(error => fail(error.message), identity)

  private def collecting(lines: Ref[IO, Vector[Line]]): Log[IO] = new Tell[IO, Line]:
    def functor = Applicative[IO]
    def tell(line: Line) = lines.update(_ :+ line)

  /** The kind and `message` of each line. */
  private def said(lines: Vector[Line]): Vector[(EventKind, Option[String])] =
    lines.map(l => l.kind -> l.fields.collectFirst { case ("message", j) => j.asString }.flatten)

  /** The start-up's network configuration of `requested`, and what it logged. */
  private def atStart(requested: Main.Options) =
    for
      lines <- Ref.of[IO, Vector[Line]](Vector.empty)
      setup <- IO.blocking(Main.nodeSetup(requested)).map(_.fold(e => fail(e.message), identity))
      result <- Main.networkAtStart(requested, setup)(using collecting(lines))
      logged <- lines.get
    yield (result, said(logged))

  private val peerSharingWarning =
    EventKind.NetworkConfigUpdateWarning -> Some(PeerSharingWarning.message)

  test(
    "a producer with peer sharing on is warned after the topology's notices and before the peer snapshot is read"
  ) {
    temporary.use { root =>
      // the preview topology names `peer-snapshot.json`, which is not there: a Genesis start stops
      // on it (`Run.hs` 733-736), after the warning
      val topology = root.resolve("topology.json")
      IO.blocking {
        val config = configured(root, "GenesisMode", peerSharing = Some(true))
        Files.copy(preview.resolve("topology.json"), topology)
        options(List("--config", config.toString, "--topology", topology.toString) ++ forge)
      }.flatMap(atStart)
        .map { (result, lines) =>
          assertEquals(
            lines,
            Vector(
              EventKind.NetworkConfigUpdateWarning -> Some(
                TopologyNotice.LedgerPeersOnProducer.message
              ),
              EventKind.NetworkConfigUpdateWarning ->
                Some(TopologyNotice.BootstrapPeersInGenesisMode.message),
              peerSharingWarning
            ),
            "the topology's notices, then the warning"
          )
          assert(
            result.left.exists {
              case ConfigError(
                    ConfigProblem.PeerSnapshot(
                      PeerSnapshotRefusal.UnreadableSnapshot(PeerSnapshotError.Unreadable(_, _))
                    )
                  ) =>
                true
              case _ => false
            },
            s"the start stops on the snapshot, after the warning: $result"
          )
        }
    }
  }

  test("only a producer with peer sharing on is warned, with a topology or without one") {
    temporary.use { root =>
      val on = root.resolve("on")
      val default = root.resolve("default")
      val topology = root.resolve("topology.json")
      def warned(args: List[String]) =
        atStart(options(args)).map { (result, lines) =>
          assert(result.isRight, s"the start goes on: $result")
          lines.contains(peerSharingWarning)
        }
      for
        _ <- IO.blocking {
          configured(Files.createDirectory(on), "PraosMode", Some(true))
          configured(Files.createDirectory(default), "PraosMode", None)
          Files.writeString(
            topology,
            """{"localRoots": [], "publicRoots": [{"accessPoints": [{"address": "127.0.0.1", "port": 3001}]}]}"""
          )
        }
        producer <- warned(
          List(
            "--config",
            on.resolve("config.json").toString,
            "--topology",
            topology.toString
          ) ++ forge
        )
        noTopology <- warned(
          List("--config", on.resolve("config.json").toString, "--peer", "127.0.0.1:9") ++ forge
        )
        byDefault <- warned(
          List(
            "--config",
            default.resolve("config.json").toString,
            "--topology",
            topology.toString
          ) ++ forge
        )
        relay <- warned(
          List("--config", on.resolve("config.json").toString, "--topology", topology.toString)
        )
      yield
        assertEquals(producer, true, "a producer with PeerSharing true")
        assertEquals(noTopology, true, "the warning does not depend on the topology")
        assertEquals(byDefault, false, "a producer's peer sharing is off by default")
        assertEquals(relay, false, "a relay is not warned")
    }
  }

  // -------------------------------------------------------------------------- SIGHUP reloads

  private val pools =
    """[{"accumulatedStake": 1, "relativeStake": 1, "relays": [{"address": "127.0.0.1", "port": 3001}]}]"""

  private def snapshot(slot: String, magic: Long = 2L): String =
    val point =
      if slot == "origin" then "\"origin\""
      else s"""{"blockPointSlot": $slot, "blockPointHash": "${"ab" * 32}"}"""
    s"""{"NodeToClientVersion": 23, "NetworkMagic": $magic, "Point": $point, "bigLedgerPools": $pools}"""

  private val legacy = s"""{"version": 2, "slotNo": 100, "bigLedgerPools": $pools}"""

  /** A relay's topology: one public root, ledger peers after `after` (0: always), and a snapshot
    * file.
    */
  private def topology(root: String, after: Int, file: String = "peer-snapshot.json"): String =
    s"""{"localRoots": [], "publicRoots": [{"accessPoints": [{"address": "$root", "port": 3001}]}],
       | "useLedgerAfterSlot": $after, "peerSnapshotFile": "$file"}""".stripMargin

  /** A node in `root` started with the topology `A` (`127.0.0.1`) and a snapshot at slot 100: its
    * completed options, its setup, and the configuration in force.
    */
  private final case class Started(
      root: Path,
      options: Main.Options,
      setup: NodeSetup,
      roots: PeerNetwork.Roots
  ):
    def write(name: String, text: String): IO[Unit] =
      IO.blocking(Files.writeString(root.resolve(name), text)).void
    def remove(name: String): IO[Unit] = IO.blocking(Files.deleteIfExists(root.resolve(name))).void

  private def started(root: Path, mode: String, args: List[String] = Nil): IO[Started] =
    IO.blocking {
      val config = configured(root, mode, peerSharing = Some(true))
      Files.writeString(root.resolve("topology.json"), topology("127.0.0.1", 0))
      Files.writeString(root.resolve("peer-snapshot.json"), snapshot("100"))
      val requested = options(
        List("--config", config.toString, "--topology", root.resolve("topology.json").toString) ++
          args
      )
      val (completed, setup, _) = Main.configured(requested).fold(e => fail(e.message), identity)
      val roots = completed.roots.getOrElse(fail("no roots"))
      assert(roots.snapshot.snapshot.isDefined, "the start loaded the snapshot")
      Started(root, completed, setup, roots)
    }

  /** What a reload did: whether it got to its end, the configuration in force after it, what it
    * installed, and what it logged.
    */
  private final case class Reloaded(
      completed: Boolean,
      inForce: PeerNetwork.Roots,
      installed: Vector[PeerNetwork.Roots],
      lines: Vector[(EventKind, Option[String])]
  )

  private def reload(node: Started, phase: SnapshotPhase): IO[Reloaded] =
    for
      network <- Ref.of[IO, PeerNetwork.Roots](node.roots)
      installed <- Ref.of[IO, Vector[PeerNetwork.Roots]](Vector.empty)
      lines <- Ref.of[IO, Vector[Line]](Vector.empty)
      completed <- Main.reloadNetwork(
        node.options,
        Nil,
        node.setup,
        phase,
        network,
        r => installed.update(_ :+ r)
      )(using collecting(lines))
      inForce <- network.get
      done <- installed.get
      logged <- lines.get
    yield Reloaded(completed, inForce, done, said(logged))

  private def publicRoots(roots: PeerNetwork.Roots): Set[String] = roots.public.map(_.toString)

  private val refusalLine = EventKind.Slf4j

  /** The topology `B` (`127.0.0.2`) installed with `snapshot`, the reload gone to its end, and
    * nothing said, as the reference says nothing.
    */
  private def droppedWith(reloaded: Reloaded)(expected: PartialFunction[SnapshotUse, Boolean]) =
    assertEquals(reloaded.completed, true, "the reload goes on")
    assertEquals(publicRoots(reloaded.inForce), Set("127.0.0.2:3001"), "the new topology")
    assert(expected.applyOrElse(reloaded.inForce.snapshot, _ => false), reloaded.inForce.snapshot)
    assertEquals(reloaded.installed, Vector(reloaded.inForce), "installed once")
    assertEquals(reloaded.lines, Vector.empty, "nothing said")

  /** The topology `B` installed with the snapshot in force, and the reload stopped there, said. */
  private def keptWith(node: Started, reloaded: Reloaded) =
    assertEquals(reloaded.completed, false, "the reload stops")
    assertEquals(publicRoots(reloaded.inForce), Set("127.0.0.2:3001"), "the new topology")
    assertEquals(reloaded.inForce.snapshot, node.roots.snapshot, "the snapshot in force")
    assertEquals(reloaded.installed, Vector(reloaded.inForce), "the topology installed")
    assertEquals(reloaded.lines.map(_._1), Vector(refusalLine), "the refusal said")

  test(
    "a runtime reload installs the new topology and drops a snapshot the start refuses, saying nothing"
  ) {
    import SnapshotPhase.Runtime
    temporary.use { root =>
      for
        node <- started(root, "GenesisMode")
        _ <- node.write("topology.json", topology("127.0.0.2", 0))
        // Genesis mode: a file it cannot read
        _ <- node.remove("peer-snapshot.json")
        unreadable <- reload(node, Runtime)
        _ <- node.write("peer-snapshot.json", legacy)
        legacyVersion <- reload(node, Runtime)
        _ <- node.write("peer-snapshot.json", snapshot("origin"))
        atOrigin <- reload(node, Runtime)
        _ <- node.write("peer-snapshot.json", snapshot("100", magic = 1L))
        otherNetwork <- reload(node, Runtime)
        _ <- node.write("peer-snapshot.json", snapshot("200"))
        newer <- reload(node, Runtime)
      yield
        droppedWith(unreadable) {
          case SnapshotUse.Dropped(
                PeerSnapshotRefusal.UnreadableSnapshot(PeerSnapshotError.Unreadable(_, _))
              ) =>
            true
        }
        droppedWith(legacyVersion) { case SnapshotUse.Dropped(PeerSnapshotRefusal.LegacyVersion) =>
          true
        }
        droppedWith(atOrigin) { case SnapshotUse.Dropped(PeerSnapshotRefusal.AtOrigin) => true }
        droppedWith(otherNetwork) {
          case SnapshotUse.Dropped(PeerSnapshotRefusal.WrongMagic(2L, 1L)) => true
        }
        assertEquals(newer.completed, true)
        assertEquals(
          newer.inForce.snapshot.snapshot.map(_.slot),
          Some(xyz.kd5ujc.scarab.network.selection.WithOrigin.At(BigInt(200))),
          "the snapshot read again"
        )
    }
  }

  test("in Praos mode a runtime reload ignores a file it cannot read and drops a legacy one") {
    import SnapshotPhase.Runtime
    temporary.use { root =>
      for
        node <- started(root, "PraosMode")
        _ <- node.write("topology.json", topology("127.0.0.2", 0))
        _ <- node.remove("peer-snapshot.json")
        unreadable <- reload(node, Runtime)
        _ <- node.write("peer-snapshot.json", legacy)
        legacyVersion <- reload(node, Runtime)
      yield
        droppedWith(unreadable) { case SnapshotUse.Ignored(PeerSnapshotError.Unreadable(_, _)) =>
          true
        }
        droppedWith(legacyVersion) { case SnapshotUse.Dropped(PeerSnapshotRefusal.LegacyVersion) =>
          true
        }
    }
  }

  test(
    "a snapshot older than useLedgerAfterSlot stops a runtime reload after the topology is installed"
  ) {
    temporary.use { root =>
      for
        node <- started(root, "GenesisMode")
        _ <- node.write("topology.json", topology("127.0.0.2", 1000))
        tooOld <- reload(node, SnapshotPhase.Runtime)
      yield
        keptWith(node, tooOld)
        assertEquals(tooOld.inForce.ledger.isEnabled, true)
    }
  }

  test(
    "a topology a reload cannot use changes nothing, and the snapshot named before is not read again"
  ) {
    temporary.use { root =>
      def unchanged(node: Started, reloaded: Reloaded, what: String) =
        assertEquals(reloaded.completed, false, s"$what: the reload stops")
        assertEquals(reloaded.inForce, node.roots, s"$what: the configuration in force")
        assertEquals(reloaded.installed, Vector.empty, s"$what: nothing installed")
        assertEquals(reloaded.lines.map(_._1), Vector(refusalLine), s"$what: the refusal said")
      for
        node <- started(root, "GenesisMode")
        // read again, the snapshot would now be dropped
        _ <- node.write("peer-snapshot.json", legacy)
        _ <- node.write("topology.json", "this is not json")
        unparsed <- reload(node, SnapshotPhase.Runtime)
        // a name the JDK cannot make a path of (#953)
        _ <- node.write("topology.json", topology("127.0.0.2", 0, file = "snap\\u0000.json"))
        unnamed <- reload(node, SnapshotPhase.Runtime)
        startUp <- reload(node, SnapshotPhase.Startup)
      yield
        unchanged(node, unparsed, "unparseable")
        unchanged(node, unnamed, "no path")
        unchanged(node, startUp, "before consensus")
    }
  }

  test(
    "the reload before consensus has initialised refuses as the start does, after the topology is installed"
  ) {
    import SnapshotPhase.Startup
    temporary.use { root =>
      for
        node <- started(root, "GenesisMode")
        _ <- node.write("topology.json", topology("127.0.0.2", 0))
        _ <- node.remove("peer-snapshot.json")
        unreadable <- reload(node, Startup)
        _ <- node.write("peer-snapshot.json", legacy)
        legacyVersion <- reload(node, Startup)
        _ <- node.write("peer-snapshot.json", snapshot("origin"))
        atOrigin <- reload(node, Startup)
        _ <- node.write("peer-snapshot.json", snapshot("100", magic = 1L))
        otherNetwork <- reload(node, Startup)
      yield List(unreadable, legacyVersion, atOrigin, otherNetwork).foreach(keptWith(node, _))
    }
  }

  test(
    "the initial handler says NotEffective only when the reload got to its end, and hands the network what it installed"
  ) {
    temporary.use { root =>
      def hangup(node: Started) =
        for
          pending <- Ref.of[IO, Option[PeerNetwork.Roots]](None)
          network <- Ref.of[IO, PeerNetwork.Roots](node.roots)
          said <- Ref.of[IO, Vector[xyz.kd5ujc.scarab.node.forge.ForgeEvent]](Vector.empty)
          events <- Ref.of[IO, xyz.kd5ujc.scarab.node.forge.ForgeEvents[IO]](e =>
            said.update(_ :+ e)
          )
          _ <- Main.startupHangup(
            node.options,
            Nil,
            node.setup,
            Main.StartupHangup(pending, network, events)
          )(using Log.none[IO])
          handed <- pending.get
          forged <- said.get
        yield (handed.map(publicRoots), forged)
      val notEffective = xyz.kd5ujc.scarab.node.forge.ForgeEvent.BlockForgingUpdate(
        xyz.kd5ujc.scarab.node.forge.BlockForgingState.NotEffective
      )
      for
        node <- started(root, "GenesisMode")
        _ <- node.write("topology.json", topology("127.0.0.2", 0))
        good <- hangup(node)
        _ <- node.write("peer-snapshot.json", legacy)
        refusedSnapshot <- hangup(node)
        _ <- node.write("topology.json", "this is not json")
        refusedTopology <- hangup(node)
      yield
        assertEquals(good, (Some(Set("127.0.0.2:3001")), Vector(notEffective)), "a good reload")
        assertEquals(
          refusedSnapshot,
          (Some(Set("127.0.0.2:3001")), Vector.empty),
          "the topology handed on, no NotEffective"
        )
        assertEquals(refusedTopology, (None, Vector.empty), "nothing handed on, no NotEffective")
    }
  }

  test("a reload does not repeat the warning of a producer with peer sharing on") {
    temporary.use { root =>
      for
        node <- started(root, "PraosMode", forge)
        reloaded <- reload(node, SnapshotPhase.Runtime)
      yield
        assertEquals(reloaded.completed, true)
        assert(!reloaded.lines.contains(peerSharingWarning), reloaded.lines)
        assertEquals(
          reloaded.lines,
          Vector(
            EventKind.NetworkConfigUpdateWarning -> Some(
              TopologyNotice.LedgerPeersOnProducer.message
            )
          ),
          "only what the reading of the topology says"
        )
    }
  }
