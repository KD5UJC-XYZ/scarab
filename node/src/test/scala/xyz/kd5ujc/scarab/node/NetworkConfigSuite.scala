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
