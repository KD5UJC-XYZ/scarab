// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  HeaderVersionInfo,
  Horizon,
  LeiosHeader,
  TxEnv,
  Unsigned,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.W32
import xyz.kd5ujc.scarab.ledger.era.{EraGovRun, Mismatch}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraBbodyRun, DijkstraBlock}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  BbodyEnv,
  DijkstraBbodyPredFailure,
  DijkstraUtxoContext,
  ShelleyBbodyState
}
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  ChainAccountState,
  Coin,
  Dijkstra,
  NewEpochState,
  ShelleyBasedEra
}
import xyz.kd5ujc.scarab.plutus.ScarabEvaluator
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
  * implementation's identifier, in older headers), and Dijkstra's BBODY on the era types
  * (`era.rules.DijkstraBbody`) accepts it with either signal against the testnet's parameters,
  * where the major without the flag is refused (`HeaderProtVerTooLow`, cardano-ledger 9df9f98c
  * `Dijkstra/Rules/Bbody.hs:366-376`).
  *
  * The node does not forge a Dijkstra block yet (its forger forges Conway blocks only), so the
  * block BBODY reads here is one the reference forged on the devnet of `fixtures/dijkstra-devnet`,
  * with its header's version info (the header body's field 9) replaced by the node's.
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
        ReferenceBuild.Leios -> (v12, (BigInt(11), BigInt(0)))
      )
    )
  }

  private val testnet = Path.of("fixtures", "leios")
  private lazy val testnetSetup = NodeSetup
    .fromConfigFile(ReferenceBuild.Leios)(testnet.resolve("testnet/config/config.json"))
    .fold(e => fail(e.message), identity)

  test("under the testnet's configuration the node forges 12.73, its maximum major follows it") {
    assertEquals(testnetSetup.config.settings.experimentalHardForksEnabled, true)
    assertEquals(
      testnetSetup.forgedProtocolVersion(ProducerSignal.Default),
      (BigInt(12), BigInt(73))
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
    val (major, minor) = testnetSetup.config.settings.protocolVersion(ReferenceBuild.Leios)
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

  /** An empty Dijkstra block the reference forged on the devnet: its bytes. */
  private lazy val devnetBlock: Bytes =
    rows(Path.of("fixtures", "dijkstra-devnet", "blocks.tsv"))
      .drop(1)
      .filter(_(0) == "8")
      .map(p => hex(p(4)))
      .find(raw =>
        Block.decodeShelley(raw).fold(e => fail(e.message), identity) match
          case Block.Dijkstra(b) => b.transactions.isEmpty
          case _                 => false
      )
      .getOrElse(fail("no empty Dijkstra block"))

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  /** A definite array of fewer than 24 items, each encoded as `parts`. */
  private def array(parts: Vector[Bytes]): Bytes =
    parts.foldLeft(Bytes.fromArray(Array((0x80 + parts.size).toByte)))(_ ++ _)

  /** The devnet block, `[8, [[header body, signature], body]]`, with the header body's version info
    * (field 9, `[major, minor]`) as `version`, every other item the bytes it was read as: the body,
    * which its size and hash in the header commit to, is unchanged.
    */
  private def withVersion(version: (BigInt, BigInt)): Bytes =
    def uint(n: BigInt) = Cbor.encode(Value.UInt(n)).fold(e => fail(e.toString), identity)
    val outer = items(Cbor.decode(devnetBlock).fold(e => fail(e.toString), identity))
    val inner = items(outer(1))
    val signed = items(inner(0))
    val fields = items(signed(0)).map(_.original)
    val header =
      array(
        Vector(
          array(fields.updated(9, array(Vector(uint(version._1), uint(version._2))))),
          signed(1).original
        )
      )
    array(Vector(outer(0).original, array(Vector(header, inner(1).original))))

  /** An era of history far past the block's slot: an empty block reads no forecast. */
  private val history: Horizon =
    val params = Horizon.Params(100, 1000, 30, 30)
    Horizon
      .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
      .at(Horizon.Transition.Unknown(Some(BigInt(0))))

  private def bbody(version: (BigInt, BigInt)) =
    DijkstraBbodyRun
      .bbody(
        Globals(testnetSetup.globals, testnetSetup.praos.geometry),
        BbodyEnv[Dijkstra](
          testnetState.es.ls.utxo.gov.cgsCurPParams,
          ChainAccountState(Coin(0), Coin(0))
        ),
        DijkstraBlock.decode(withVersion(version)).fold(e => fail(e.toString), identity),
        DijkstraUtxoContext(
          testnetSetup.globals.network,
          testnetSetup.globals.systemStart,
          history,
          TxEnv.Validation.All
        ),
        ShelleyBbodyState[Dijkstra](testnetState.es.ls, BlocksMade.empty),
        EraGovRun.dijkstraEmpty,
        ScarabEvaluator.default,
        Verdicts.Direct,
        ScarabEvaluator.defaultDecoder,
        slot => Right(slot * 1000)
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
      bbody(forged(ReferenceBuild.Leios, experimental = false)),
      Right(
        Left(
          Vector[DijkstraBbodyPredFailure[Dijkstra]](
            DijkstraBbodyPredFailure.HeaderProtVerTooLow[Dijkstra](
              Mismatch(Unsigned.wrapping[W32](11), Unsigned.wrapping[W32](12))
            )
          )
        )
      )
    )
  }
