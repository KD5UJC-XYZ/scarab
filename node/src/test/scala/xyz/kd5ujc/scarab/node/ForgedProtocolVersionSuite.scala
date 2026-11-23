// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Block,
  HeaderVersionInfo,
  LeiosBlock,
  LeiosHeader,
  Mismatch,
  PlutusStats,
  TestLedger
}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraBbodyFailure
import xyz.kd5ujc.scarab.ledger.rules.{
  BbodyEnv,
  Counted,
  DijkstraBbody,
  DijkstraBbodyState,
  DijkstraSts,
  Globals,
  Judgment
}
import xyz.kd5ujc.scarab.ledger.state.{BlocksMade, Coin, Dijkstra, NewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.forge.ProducerSignal
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{HfcHeader, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellLedgerFile

/** The protocol version the node's forged headers carry, by build (`cardanoProtocolVersion`,
  * `Cardano.Node.Protocol.Cardano` lines 192-194 of cardano-node 11.1.3 and b004fe0c), with the
  * producer signal as its minor (CIP-0203, #1348), and what the public Leios testnet makes of it
  * (#54): its configuration sets `ExperimentalHardForksEnabled`, so the node writes the major 12 of
  * the headers the reference's forgers wrote there after slot 2,168,639 (`fixtures/leios/testnet`;
  * `docs/leios.md` section 11.3 counts the testnet's other version info, `[12, 67]`, another
  * implementation's identifier, in older headers), and Dijkstra's BBODY accepts it with either
  * signal against the testnet's parameters, where the major without the flag is refused
  * (`HeaderProtVerTooLow`, cardano-ledger 9df9f98c `Dijkstra/Rules/Bbody.hs:366-376`).
  *
  * The node does not forge a Dijkstra block yet (its forger forges Conway blocks only), so the
  * block BBODY reads here is one the reference forged on the devnet of `fixtures/dijkstra-devnet`,
  * with its version info replaced by the node's.
  */
class ForgedProtocolVersionSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)
  private def rows(file: Path): Vector[Array[String]] =
    Files
      .readAllLines(file)
      .asScala
      .toVector
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .map(_.split('\t'))

  private def forged(build: ReferenceBuild, experimental: Boolean): (BigInt, BigInt) =
    ConfigSettings(experimentalHardForksEnabled = experimental).protocolVersion(build)

  test("12.0 with ExperimentalHardForksEnabled; without it 11.0 under Leios, the releases' 11.2") {
    val v12 = (BigInt(12), BigInt(0))
    // the release builds keep the values they had before the Leios build's was selected
    assertEquals(
      ReferenceBuild.values.toVector.map(b => b -> (forged(b, true), forged(b, false))),
      Vector(
        ReferenceBuild.Release11_1 -> (v12, (BigInt(11), BigInt(2))),
        ReferenceBuild.Release11_2 -> (v12, (BigInt(11), BigInt(2))),
        ReferenceBuild.LeiosB004fe0c -> (v12, (BigInt(11), BigInt(0)))
      )
    )
  }

  private val testnet = Path.of("fixtures", "leios")
  private lazy val testnetSetup = NodeSetup
    .fromConfigFile(ReferenceBuild.LeiosB004fe0c)(testnet.resolve("testnet/config/config.json"))
    .fold(e => fail(e.message), identity)

  test("under the testnet's configuration the node forges 12.42, its maximum major follows it") {
    assertEquals(testnetSetup.config.settings.experimentalHardForksEnabled, true)
    assertEquals(
      testnetSetup.forgedProtocolVersion(ProducerSignal.Default),
      (BigInt(12), BigInt(42))
    )
    assertEquals(
      testnetSetup.forgedProtocolVersion(ProducerSignal.OptedOut),
      (BigInt(12), BigInt(255))
    )
    assertEquals(
      BigInt(testnetSetup.praos.forks.maxMajorProtVer),
      testnetSetup.forgedProtocolVersion(ProducerSignal.Default)._1
    )
  }

  test("the testnet headers after slot 2,168,639 carry the reference's version, the major forged") {
    val headers = rows(testnet.resolve("testnet/headers-after-2168639.tsv")).map(r =>
      HfcHeader.decode(hex(r(4))) match
        case Right(HfcHeader.Dijkstra(raw)) =>
          LeiosHeader.decode(raw).fold(e => fail(e.message), identity)
        case other => fail(other.toString)
    )
    assertEquals(headers.size, 8)
    val (major, minor) = testnetSetup.config.settings.protocolVersion(ReferenceBuild.LeiosB004fe0c)
    headers.foreach(h =>
      assertEquals(h.leios.versionInfo, HeaderVersionInfo(major, minor), h.slot.toString)
    )
    assertEquals(testnetSetup.forgedProtocolVersion(ProducerSignal.Default)._1, major)
  }

  /** The testnet's ledger state at slot 2,168,639, in the Leios layout. */
  private lazy val testnetState: NewEpochState[Dijkstra] =
    val raw = Bytes.wrap(Files.readAllBytes(testnet.resolve("state/testnet-2168639/state")))
    val file = HaskellLedgerFile.decode(raw, StateLayout.Leios).fold(e => fail(e.render), identity)
    val ext = file.ext.getOrElse(fail("no Shelley-based ledger"))
    testnetSetup.mirrorStart.copy(ext = ext).ledger(None).toOption.flatten match
      case Some(i: InEra.Of[e]) =>
        i.era match
          case ShelleyBasedEra.SDijkstra => i.read.nes.fold(e => fail(e.toString), identity)
          case other                     => fail(s"not in Dijkstra: $other")
      case other => fail(s"no Shelley-based ledger: $other")

  /** An empty Dijkstra block the reference forged on the devnet. */
  private lazy val devnetBlock: LeiosBlock =
    rows(Path.of("fixtures", "dijkstra-devnet", "blocks.tsv"))
      .drop(1)
      .filter(_(0) == "8")
      .map(p => Block.decodeShelley(hex(p(4))).fold(e => fail(e.message), identity))
      .collectFirst { case Block.Dijkstra(b) if b.transactions.isEmpty => b }
      .getOrElse(fail("no empty Dijkstra block"))

  private def bbody(version: (BigInt, BigInt)) =
    val header = devnetBlock.header
    val block = devnetBlock.copy(header =
      header.copy(leios =
        header.leios.copy(versionInfo = HeaderVersionInfo(version._1, version._2))
      )
    )
    val pp =
      xyz.kd5ujc.scarab.ledger.era.GovStateRecord.view(testnetState.es.ls.utxo.gov.cgsCurPParams)
    val issuer = DijkstraBbody.issuer(block).getOrElse(fail("issuer"))
    DijkstraSts
      .judge(DijkstraBbody, Judgment.Policy.Accumulate)(
        Globals(testnetSetup.globals, testnetSetup.praos.geometry),
        BbodyEnv(
          TestLedger.env.copy(slot = header.slot, epoch = testnetState.epoch.toBigInt),
          pp,
          Coin(0)
        ),
        DijkstraBbodyState(Counted(testnetState.es.ls, PlutusStats.zero), BlocksMade.empty),
        DijkstraBbody.Signal(block, issuer)
      )
      .map(_.left.map(_.toChain.toVector))

  test(
    "Dijkstra's BBODY takes the forged version with either signal; the one without the flag not"
  ) {
    assertEquals(
      xyz.kd5ujc.scarab.ledger.era.GovStateRecord
        .view(testnetState.es.ls.utxo.gov.cgsCurPParams)
        .protocolMajor,
      BigInt(12)
    )
    ProducerSignal.values.foreach(signal =>
      assert(bbody(testnetSetup.forgedProtocolVersion(signal)).exists(_.isRight), signal.toString)
    )
    assertEquals(
      bbody(forged(ReferenceBuild.LeiosB004fe0c, experimental = false)),
      Right(
        Left(Vector(DijkstraBbodyFailure.HeaderProtVerTooLow(Mismatch(BigInt(11), BigInt(12)))))
      )
    )
  }
