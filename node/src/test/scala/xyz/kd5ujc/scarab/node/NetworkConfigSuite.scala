// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Applicative
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.mtl.{Handle, Tell}
import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem, NodeError}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, SocketAddress, ipv4, port}
import xyz.kd5ujc.scarab.network.{ConnectionManager, FailsOnUncaught, NodeToNode, Settled}
import xyz.kd5ujc.scarab.ledger.LedgerRelay
import xyz.kd5ujc.scarab.network.selection.{
  AccPoolStake,
  ConsensusMode,
  FetchMode,
  Gsm,
  LedgerPeerSnapshot,
  LedgerStateJudgement,
  PoolStake,
  SnapshotPoint,
  SnapshotPool,
  Targets,
  WithOrigin
}
import xyz.kd5ujc.scarab.node.assertions.Assertions
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.node.log.{EventKind, Line, Log}

/** The network configuration as cardano-node 11.1.3 reads it at start-up
  * (`cardano-node/src/Cardano/Node/Run.hs` 349-379): the topology, what its reading says of it, the
  * warning of a producer with peer sharing on, then the peer snapshot.
  */
class NetworkConfigSuite extends FailsOnUncaught:
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

  /** The kind and `message` of each line, or its `action` when it has no message. */
  private def said(lines: Vector[Line]): Vector[(EventKind, Option[String])] =
    def field(l: Line, name: String) = l.fields.collectFirst { case (`name`, j) =>
      j.asString
    }.flatten
    lines.map(l => l.kind -> field(l, "message").orElse(field(l, "action")))

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

  test("the start says what it made of the peer snapshot where it reads it, after the topology") {
    temporary.use { root =>
      val genesis = root.resolve("genesis")
      val praos = root.resolve("praos")
      def at(dir: Path, mode: String, snapshotText: Option[String]) =
        IO.blocking {
          val config = configured(Files.createDirectory(dir), mode, peerSharing = None)
          Files.writeString(dir.resolve("topology.json"), topology("127.0.0.1", 0))
          snapshotText.foreach(t => Files.writeString(dir.resolve("peer-snapshot.json"), t))
          options(
            List("--config", config.toString, "--topology", dir.resolve("topology.json").toString)
          )
        }.flatMap(atStart)
      for
        loaded <- at(genesis, "GenesisMode", Some(snapshot("100")))
        ignored <- at(praos, "PraosMode", None)
      yield
        assert(loaded._1.isRight, loaded._1)
        assertEquals(loaded._2, Vector(snapshotLoaded), "a Genesis relay: the snapshot loaded")
        assert(ignored._1.isRight, ignored._1)
        assertEquals(ignored._2, Vector(snapshotIgnored), "Praos: a file it cannot read ignored")
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
      config: Main.NetworkConfig
  ):
    def roots: PeerNetwork.Roots = config.roots
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
      assert(completed.snapshot.snapshot.isDefined, "the start loaded the snapshot")
      Started(root, completed, setup, Main.NetworkConfig(roots, completed.snapshot.snapshot))
    }

  private type Snapshot = Option[LedgerPeerSnapshot[LedgerRelay]]

  /** What a reload did: how it ended, the configuration in force after it, the topologies it
    * installed, the snapshots it wrote, and what it logged.
    */
  private final case class Reloaded(
      outcome: Main.NetworkReload,
      inForce: Main.NetworkConfig,
      installed: Vector[PeerNetwork.Roots],
      written: Vector[Snapshot],
      lines: Vector[(EventKind, Option[String])]
  )

  private def reload(node: Started, phase: SnapshotPhase): IO[Reloaded] =
    for
      network <- Ref.of[IO, Main.NetworkConfig](node.config)
      installed <- Ref.of[IO, Vector[PeerNetwork.Roots]](Vector.empty)
      written <- Ref.of[IO, Vector[Snapshot]](Vector.empty)
      lines <- Ref.of[IO, Vector[Line]](Vector.empty)
      outcome <- Main.reloadNetwork(
        node.options,
        Nil,
        node.setup,
        phase,
        network,
        r => installed.update(_ :+ r),
        w => written.update(_ :+ w)
      )(using collecting(lines))
      inForce <- network.get
      done <- installed.get
      wrote <- written.get
      logged <- lines.get
    yield Reloaded(outcome, inForce, done, wrote, said(logged))

  private def slotOf(s: Snapshot) = s.map(_.slot)
  private def at(slot: Int) = Some(WithOrigin.At(BigInt(slot)))

  private def publicRoots(roots: PeerNetwork.Roots): Set[String] = roots.public.map(_.toString)

  private val refusalLine = EventKind.Slf4j
  private val snapshotLoaded = EventKind.LedgerPeers -> Some("snapshotLoaded")
  private val snapshotIgnored = EventKind.LedgerPeers -> Some("snapshotIgnored")

  /** The topology `B` (`127.0.0.2`) installed once, then the snapshot read written, at `slot` or
    * `None`, the reload gone to its end, and `lines` said: nothing for a dropped snapshot, as the
    * reference says nothing.
    */
  private def writtenWith(
      reloaded: Reloaded,
      slot: Option[WithOrigin[BigInt]],
      lines: Vector[(EventKind, Option[String])]
  ) =
    assertEquals(reloaded.outcome, Main.NetworkReload.Completed, "the reload goes on")
    assertEquals(publicRoots(reloaded.inForce.roots), Set("127.0.0.2:3001"), "the new topology")
    assertEquals(reloaded.installed, Vector(reloaded.inForce.roots), "the topology installed, once")
    assertEquals(reloaded.written.map(slotOf), Vector(slot), "the snapshot read, written once")
    assertEquals(slotOf(reloaded.inForce.snapshot), slot, "the snapshot in force")
    assertEquals(reloaded.lines, lines, "what is said")

  /** The topology `B` installed, the reload stopped at the snapshot, said, and nothing written. */
  private def keptWith(node: Started, reloaded: Reloaded) =
    assertEquals(reloaded.outcome, Main.NetworkReload.SnapshotRefused, "the reload stops")
    assertEquals(publicRoots(reloaded.inForce.roots), Set("127.0.0.2:3001"), "the new topology")
    assertEquals(reloaded.inForce.snapshot, node.config.snapshot, "the snapshot in force")
    assertEquals(reloaded.installed, Vector(reloaded.inForce.roots), "the topology installed, once")
    assertEquals(reloaded.written, Vector.empty, "no snapshot written")
    assertEquals(reloaded.lines.map(_._1), Vector(refusalLine), "the refusal said, nothing else")

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
        writtenWith(unreadable, None, Vector.empty)
        writtenWith(legacyVersion, None, Vector.empty)
        writtenWith(atOrigin, None, Vector.empty)
        writtenWith(otherNetwork, None, Vector.empty)
        writtenWith(newer, at(200), Vector(snapshotLoaded))
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
        // a file Praos mode cannot read writes `None` (`Run.hs` 736, 757), and is said ignored
        writtenWith(unreadable, None, Vector(snapshotIgnored))
        writtenWith(legacyVersion, None, Vector.empty)
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
        assertEquals(tooOld.inForce.roots.ledger.isEnabled, true)
    }
  }

  test(
    "a topology a reload cannot use changes nothing, and the snapshot named before is not read again"
  ) {
    temporary.use { root =>
      def unchanged(node: Started, reloaded: Reloaded, what: String) =
        assertEquals(
          reloaded.outcome,
          Main.NetworkReload.TopologyRefused,
          s"$what: the reload stops"
        )
        assertEquals(reloaded.inForce, node.config, s"$what: the configuration in force")
        assertEquals(reloaded.installed, Vector.empty, s"$what: nothing installed")
        assertEquals(reloaded.written, Vector.empty, s"$what: nothing written")
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
          pending <- Ref.of[IO, Option[Main.NetworkConfig]](None)
          network <- Ref.of[IO, Main.NetworkConfig](node.config)
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
        yield (handed.map(c => (publicRoots(c.roots), slotOf(c.snapshot))), forged)
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
        assertEquals(
          good,
          (Some(Set("127.0.0.2:3001") -> at(100)), Vector(notEffective)),
          "a good reload"
        )
        assertEquals(
          refusedSnapshot,
          (Some(Set("127.0.0.2:3001") -> at(100)), Vector.empty),
          "the topology handed on with the snapshot in force, no NotEffective"
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
        assertEquals(reloaded.outcome, Main.NetworkReload.Completed)
        assert(!reloaded.lines.contains(peerSharingWarning), reloaded.lines)
        assertEquals(
          reloaded.lines,
          Vector(
            EventKind.NetworkConfigUpdateWarning -> Some(
              TopologyNotice.LedgerPeersOnProducer.message
            ),
            snapshotLoaded
          ),
          "what the reading of the topology says, and the snapshot read again"
        )
        assertEquals(reloaded.installed, Vector(reloaded.inForce.roots), "the topology, once")
        assertEquals(
          reloaded.written.map(slotOf),
          Vector(at(100)),
          "the same snapshot read again is written again"
        )
    }
  }

  test("the topology is installed, with the snapshot in force, before the snapshot is read again") {
    temporary.use { root =>
      for
        node <- started(root, "GenesisMode")
        _ <- node.write("topology.json", topology("127.0.0.2", 0))
        _ <- node.write("peer-snapshot.json", snapshot("200"))
        network <- Ref.of[IO, Main.NetworkConfig](node.config)
        installed <- Ref.of[IO, Vector[PeerNetwork.Roots]](Vector.empty)
        written <- Ref.of[IO, Vector[Snapshot]](Vector.empty)
        // the installation rewrites the file: a read made before it would see slot 200
        _ <- Main.reloadNetwork(
          node.options,
          Nil,
          node.setup,
          SnapshotPhase.Runtime,
          network,
          r =>
            installed
              .getAndUpdate(_ :+ r)
              .flatMap(before =>
                node.write("peer-snapshot.json", snapshot("300")).whenA(before.isEmpty)
              ),
          w => written.update(_ :+ w)
        )(using Log.none[IO])
        done <- installed.get
        wrote <- written.get
      yield
        assertEquals(done.map(publicRoots), Vector(Set("127.0.0.2:3001")), "the topology, once")
        assertEquals(wrote.map(slotOf), Vector(at(300)), "the snapshot read after it")
    }
  }

  /** A running network of `roots` and `snapshot` in `consensus` mode, whose outputs go to `emit`,
    * as [[Main]] runs it.
    */
  private def running(
      roots: PeerNetwork.Roots,
      emit: Output[PeerNetwork.Addr] => IO[Unit],
      snapshot: Snapshot,
      consensus: ConsensusMode = ConsensusMode.PraosMode,
      immutable: Option[xyz.kd5ujc.scarab.storage.ImmutableStore[IO]] = None
  ): Resource[IO, PeerNetwork.Running[IO, PeerNetwork.Addr]] =
    given Assertions[IO] = Assertions.noop[IO]
    given Resource[IO, PeerDns[IO]] = Resource.pure[IO, PeerDns[IO]](new PeerDns[IO]:
      def addresses(query: DnsName) = IO.never
      def services(query: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty))))
    NodeScope.resource.flatMap { scope =>
      given Handle[IO, NodeError] = scope
      for
        transport <- Resource.eval(InMemoryPeers.create(42L))
        manager <- Resource.eval(ConnectionManager.create[IO]())
        pool <- Resource.eval(BlockPool.create[IO])
        _ <- FetchTesting.decisions(pool)
        mode <- Resource.eval(Ref.of[IO, FetchMode](FetchMode.BulkSync))
        hot = OutboundPeers.Hot[IO](
          magic = 42L,
          network = NodeToNode.Settings(),
          peer = Peer.Settings(
            roots.public.headOption.getOrElse(fail("no public root")),
            42L,
            byronEpochSlots = ForgedChain.geometry.byron.epochSlots
          ),
          follow = (_, _) => IO.never,
          heightSnapshot = ChainSyncHeights.origin,
          headers = None,
          transactions = None,
          observe = None,
          pool = pool,
          versus = _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
          lost = _ => IO.unit,
          fatal = cause => IO(fail(s"unexpected session failure: $cause"))
        )
        view = new PeerNetwork.ChainView[IO]:
          def tip = IO.pure(None)
          def geometry = IO.pure(ForgedChain.geometry)
        network <- PeerNetwork.resource[IO](
          PeerNetwork.Settings(
            roots,
            Targets.relay,
            consensusMode = consensus,
            seed = 279L,
            snapshot = snapshot
          ),
          transport,
          manager,
          OutboundPeers.Config(),
          hot,
          view,
          mode,
          emit,
          (_, _) => IO.unit,
          _ => IO.unit,
          _ => IO.unit,
          GsmRunner.Marker.none[IO],
          LedgerPeersSource.none[IO],
          immutable = immutable
        )
      yield network
    }

  private def snapshotLines(outputs: Vector[Output[PeerNetwork.Addr]]) =
    outputs.collect {
      case Output.LedgerPeers(t @ LedgerPeersTrace.SnapshotLoaded(_, _)) => t
      case Output.LedgerPeers(t @ LedgerPeersTrace.SnapshotIgnored(_))   => t
    }

  test(
    "a snapshot a runtime reload refuses as too old is kept without being said loaded, on the node's own path"
  ) {
    temporary.use { root =>
      for
        node <- started(root, "GenesisMode")
        outputs <- Ref.of[IO, Vector[Output[PeerNetwork.Addr]]](Vector.empty)
        lines <- Ref.of[IO, Vector[Line]](Vector.empty)
        network <- Ref.of[IO, Main.NetworkConfig](node.config)
        _ <- running(node.roots, o => outputs.update(_ :+ o), node.config.snapshot).use { net =>
          net.run.background.use { _ =>
            for
              _ <- outputs.set(Vector.empty)
              _ <- node.write("topology.json", topology("127.0.0.2", 1000))
              _ <- Main
                .reloadNetwork(
                  node.options,
                  Nil,
                  node.setup,
                  SnapshotPhase.Runtime,
                  network,
                  net.reloadRoots,
                  net.setSnapshot
                )(using collecting(lines))
                .timeout(10.seconds)
            yield ()
          }
        }
        emitted <- outputs.get
        logged <- lines.get
      yield
        assertEquals(snapshotLines(emitted), Vector.empty, "no snapshot said loaded by the network")
        assertEquals(
          said(logged).map(_._1),
          Vector(EventKind.Slf4j),
          "only the refusal in the node's log"
        )
    }
  }

  test("a reload whose snapshot changes publishes exactly one root generation") {
    temporary.use { root =>
      for
        node <- started(root, "GenesisMode")
        network <- Ref.of[IO, Main.NetworkConfig](node.config)
        generations <- running(node.roots, _ => IO.unit, node.config.snapshot).use { net =>
          net.run.background.use { _ =>
            for
              before <- net.state.get.map(_.rootsGeneration)
              _ <- node.write("topology.json", topology("127.0.0.2", 0))
              _ <- node.write("peer-snapshot.json", snapshot("200"))
              _ <- Main
                .reloadNetwork(
                  node.options,
                  Nil,
                  node.setup,
                  SnapshotPhase.Runtime,
                  network,
                  net.reloadRoots,
                  net.setSnapshot
                )(using Log.none[IO])
                .timeout(10.seconds)
              after <- net.state.get.map(_.rootsGeneration)
            yield after - before
          }
        }
      yield assertEquals(generations, BigInt(1), "one root generation, for the topology")
    }
  }

  /** A snapshot of this network at `slot`, with one pool behind `relay`. */
  private def snapshotAt(
      slot: Int,
      relay: String = "snap.example."
  ): LedgerPeerSnapshot[LedgerRelay] =
    LedgerPeerSnapshot.V23(
      SnapshotPoint.Block(
        BigInt(slot),
        xyz.kd5ujc.scarab.codec.Bytes.fromArray(Array.fill(32)(slot.toByte))
      ),
      2L,
      Vector(
        SnapshotPool(
          AccPoolStake(xyz.kd5ujc.scarab.codec.Rational.one),
          PoolStake(xyz.kd5ujc.scarab.codec.Rational.one),
          cats.data.NonEmptyVector.one[LedgerRelay](
            LedgerRelay.Domain(
              relay,
              xyz.kd5ujc.scarab.ledger.RelayPort.fromInt(3001).getOrElse(fail("port"))
            )
          )
        )
      )
    )

  test(
    "the governor takes a snapshot at another block point, and neither None nor the same point"
  ) {
    val p100 = Some(snapshotAt(100))
    val p200 = Some(snapshotAt(200))
    assertEquals(PeerNetwork.snapshotTaken(p200, p100), true, "another point")
    assertEquals(PeerNetwork.snapshotTaken(p100, None), true, "a first one")
    assertEquals(PeerNetwork.snapshotTaken(None, p100), false, "None")
    assertEquals(
      PeerNetwork.snapshotTaken(Some(snapshotAt(100, "other.example.")), p100),
      false,
      "the same point, other pools"
    )
    import PeerNetwork.SnapshotCheck
    val (afterNone, noneTaken) = SnapshotCheck.initial(p100).take(None)
    val (afterSame, sameTaken) = afterNone.take(p100)
    val (afterOther, otherTaken) = afterSame.take(p200)
    assertEquals((noneTaken, sameTaken, otherTaken), (false, false, true), "taken: another point")
    assertEquals(afterSame, SnapshotCheck.initial(p100), "None and the same point change nothing")
    assertEquals((afterOther.taken, afterOther.revision), (p200, BigInt(1)), "taken, a revision")
  }

  test(
    "a check reads the snapshot it checks where it is claimed, once per generation and snapshot"
  ) {
    import PeerNetwork.SnapshotCheck
    import SnapshotCheck.Key
    val p100 = Some(snapshotAt(100))
    val p200 = Some(snapshotAt(200))
    def slotOf(c: Option[SnapshotCheck.Claim]) = c.map(_.slot)
    // a check of the snapshot at 100 claimed in generation 0, then the snapshot at 200 taken
    val (claimedA, a) = SnapshotCheck.initial(p100).claim(BigInt(0))
    val (tookB, _) = claimedA.take(p200)
    // a check claimed now, whatever it read before, is of the snapshot taken: 200
    val (claimedB, b) = tookB.claim(BigInt(0))
    assertEquals((slotOf(a), slotOf(b)), (Some(BigInt(100)), Some(BigInt(200))), "each its own")
    assertEquals(
      (a.map(_.key), b.map(_.key)),
      (Some(Key(BigInt(0), BigInt(0))), Some(Key(BigInt(0), BigInt(1)))),
      "one generation, two snapshots"
    )
    assertEquals(claimedB.claim(BigInt(0))._2, None, "no second check of 200 in generation 0")
    val (nextGeneration, again) = claimedB.claim(BigInt(1))
    assertEquals(slotOf(again), Some(BigInt(200)), "a generation of its own arms it again")
    assertEquals(nextGeneration.claim(BigInt(0))._2, None, "no check for an older generation")
  }

  private def hashAt(n: Int) = xyz.kd5ujc.scarab.codec.Bytes.fromArray(Array.fill(32)(n.toByte))

  /** An immutable chain at slot 1000 whose block at slot 200 is the one [[snapshotAt]] 200 names,
    * and at any other slot another: a check says `true` for the snapshot at 200 only. Each lookup
    * is recorded, and waits for `held` first.
    */
  private def immutableAt(
      lookups: Ref[IO, Vector[BigInt]],
      held: BigInt => IO[Unit]
  ): xyz.kd5ujc.scarab.storage.ImmutableStore[IO] =
    import xyz.kd5ujc.scarab.consensus.BlockRef
    type Fails = cats.mtl.Raise[IO, xyz.kd5ujc.scarab.ledger.StorageError]
    new xyz.kd5ujc.scarab.storage.ImmutableStore[IO]:
      def append(ref: BlockRef, raw: xyz.kd5ujc.scarab.codec.Bytes)(using Fails) = IO.unit
      def sync(using Fails) = IO.unit
      def tip(using Fails) = IO.pure(Some(BlockRef(BigInt(1000), hashAt(0), BigInt(1000))))
      def get(point: xyz.kd5ujc.scarab.protocol.ChainSync.Point)(using Fails) = IO.pure(None)
      def pointsAtSlot(slot: BigInt)(using Fails) = IO.pure(Vector.empty)
      def pointAtOrAfterSlot(slot: BigInt)(using Fails) =
        lookups.update(_ :+ slot) *> held(slot) *>
          IO.pure(
            Some(BlockRef(slot, if slot == BigInt(200) then hashAt(200) else hashAt(0), slot))
          )
      def byBlockNo(n: BigInt)(using Fails) = IO.pure(None)
      def stream(after: xyz.kd5ujc.scarab.protocol.ChainSync.Point)(using Fails) = fs2.Stream.empty

  private val genesisRoots = PeerNetwork.Roots(
    Nil,
    Set(SocketAddress[Host](ipv4"127.0.0.1", port"3001")),
    None,
    xyz.kd5ujc.scarab.network.selection.UseLedgerPeers.Disabled
  )

  private def verdicts(into: Ref[IO, Vector[Boolean]]) = (o: Output[PeerNetwork.Addr]) =>
    o match
      case Output.LedgerPeers(LedgerPeersTrace.SnapshotVerified(m)) => into.update(_ :+ m)
      case _                                                        => IO.unit

  test(
    "in Genesis mode a snapshot at another point arms the verification again, in the same root generation"
  ) {
    Settled { settle =>
      for
        said <- Ref.of[IO, Vector[Boolean]](Vector.empty)
        lookups <- Ref.of[IO, Vector[BigInt]](Vector.empty)
        store = immutableAt(lookups, _ => IO.unit)
        seen <- running(
          genesisRoots,
          verdicts(said),
          Some(snapshotAt(100)),
          ConsensusMode.GenesisMode,
          Some(store)
        ).use { net =>
          net.run.background.use { _ =>
            def after(write: IO[Unit]) =
              write *> settle *>
                (lookups.get, said.get, net.state.get.map(_.rootsGeneration)).tupled
            for
              start <- after(IO.unit)
              judgement <- net.judgement.get
              none <- after(net.setSnapshot(None))
              same <- after(net.setSnapshot(Some(snapshotAt(100))))
              other <- after(net.setSnapshot(Some(snapshotAt(200))))
              // a topology installed arms the check once per root generation, as before #924's
              // reload work (`beforeEventApplied` on `RootsConfigured`): the scope it keeps
              installed <- after(net.reloadRoots(genesisRoots))
            yield (judgement, start, none, same, other, installed)
          }
        }
      yield
        val (judgement, start, none, same, other, installed) = seen
        assertEquals(judgement, LedgerStateJudgement.TooOld, "the judgement the check waits for")
        assertEquals(none, start, "None arms nothing")
        assertEquals(same, start, "the same point arms nothing")
        assertEquals(
          other,
          (start._1 :+ BigInt(200), start._2 :+ true, start._3),
          "another point: the snapshot at 200 checked, and found, in the same generation"
        )
        assertEquals(
          installed,
          (other._1 :+ BigInt(200), other._2 :+ true, other._3 + 1),
          "a topology installed: a generation, and the snapshot taken, at 200, checked again"
        )
    }
  }

  test(
    "in Genesis mode a check of a snapshot replaced still says its verdict, once, and the snapshot taken is checked once"
  ) {
    // `jobVerifyPeerSnapshot` traces every result and changes no state (cardano-diffusion c45735a5
    // `Governor/Monitor.hs` 689-708); `ledgerPeerSnapshotChange` cancels no job (ouroboros-network
    // c45735a5 `Governor/Monitor.hs` 432-459)
    Settled { settle =>
      for
        said <- Ref.of[IO, Vector[Boolean]](Vector.empty)
        lookups <- Ref.of[IO, Vector[BigInt]](Vector.empty)
        releaseA <- Deferred[IO, Unit]
        releaseB <- Deferred[IO, Unit]
        // each check is held in its lookup until the test releases it: the chain's block at 100
        // is not the snapshot's (false), the one at 200 is (true)
        store = immutableAt(
          lookups,
          slot =>
            if slot == BigInt(100) then releaseA.get
            else if slot == BigInt(200) then releaseB.get
            else IO.unit
        )
        seen <- running(
          genesisRoots,
          verdicts(said),
          Some(snapshotAt(100)),
          ConsensusMode.GenesisMode,
          Some(store)
        ).use { net =>
          net.run.background.use { _ =>
            for
              _ <- settle
              // a generation of its own, so that a check of the snapshot at 100 is in flight
              _ <- net.reloadRoots(genesisRoots) *> settle
              heldBefore <- lookups.get
              _ <- net.setSnapshot(Some(snapshotAt(200))) *> settle
              _ <- releaseA.complete(()) *> settle
              afterA <- said.get
              _ <- releaseB.complete(()) *> settle
              // written again, the snapshot at 200 is not checked again in this generation
              _ <- net.setSnapshot(Some(snapshotAt(200))) *> settle
              done <- (lookups.get, said.get).tupled
            yield (heldBefore, afterA, done)
          }
        }
      yield
        val (heldBefore, afterA, (looked, verdict)) = seen
        assert(
          heldBefore.lastOption.contains(BigInt(100)),
          s"a check of 100 in flight: $heldBefore"
        )
        assertEquals(afterA, Vector(false), "the check of 100, replaced, says its verdict")
        assertEquals(looked.count(_ == BigInt(200)), 1, "the snapshot at 200 checked once")
        assertEquals(verdict, Vector(false, true), "each verdict once: 100's, then 200's")
    }
  }
