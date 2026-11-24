// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import io.circe.parser
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}
import xyz.kd5ujc.scarab.storage.haskell.state.{LeiosStateCodecs, UtxoCodecs}

/** The public Leios testnet's chain starts in Dijkstra, as the build starts it: its configuration
  * sets `ExperimentalHardForksEnabled` and `TestDijkstraHardForkAtEpoch: 0`, and under the Leios
  * build the node's maximum major protocol version is its own protocol version, 12
  * (`Cardano/Node.hs:548-549`, ouroboros-consensus 2416493b; owner decision on #54, 2026-11-19).
  * The genesis state the node starts from equals the build's own
  * (`fixtures/reference/dijkstra-epoch` `cases.json` `genesis.fromByron`, oracle
  * `scripts/oracle/dijkstra-epoch`): Byron's genesis UTxO translated era by era up to Dijkstra
  * (`mkInitExtLedgerStateCardano`, `Cardano/Node.hs:846-892`) and Dijkstra's injection
  * (`Dijkstra/Transition.hs:59-101`). The state is compared without its UTxO, which the build keeps
  * in its tables, and the UTxO apart.
  */
class DijkstraGenesisStartSuite extends FunSuite:
  private given ShelleyBasedEra[Dijkstra] = ShelleyBasedEra.SDijkstra

  private def right[E, A](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)

  private val dir = Path.of("fixtures/leios/testnet/config")
  private val setup: NodeSetup =
    val config = right(
      NodeConfig
        .parse(ReferenceBuild.Leios)(Files.readString(dir.resolve("config.json")), true)
    )
    right(
      NodeSetup.assemble(ReferenceBuild.Leios)(
        config,
        name => Option.when(Files.exists(dir.resolve(name)))(Files.readAllBytes(dir.resolve(name)))
      )
    )

  private val fixture = Path.of("fixtures/reference/dijkstra-epoch")
  private val genesis =
    right(parser.parse(Files.readString(fixture.resolve("cases.json")))).hcursor
      .downField("genesis")
      .downField("fromByron")

  test("the testnet's chain starts in Dijkstra under the Leios build") {
    assertEquals(setup.mirrorStart.era, Era.Dijkstra)
  }

  test("its genesis state, and its UTxO, are the build's own") {
    val nes = right(setup.mirrorStart.ledger(None)) match
      case Some(InEra.Of(ShelleyBasedEra.SDijkstra, l)) => right(l.nes)
      case other                                        => fail(s"not a Dijkstra state: $other")
    val utxo = nes.es.ls.utxo.utxo.utxo.toMap
    val expected = Bytes.wrap(
      Files.readAllBytes(fixture.resolve(right(genesis.get[String]("file"))))
    )
    // the state as the build writes it, its UTxO in the tables
    assertEquals(right(LeiosStateCodecs.newEpochState[Dijkstra].write(nes)).hex, expected.hex)
    assertEquals(
      right(UtxoCodecs.utxoIn(ShelleyBasedEra.SDijkstra).write(utxo)).hex,
      right(genesis.get[String]("utxo"))
    )
  }
