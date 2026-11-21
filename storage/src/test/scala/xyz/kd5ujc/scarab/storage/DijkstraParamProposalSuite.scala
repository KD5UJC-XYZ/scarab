// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{DecodeField, DijkstraKeysUpdate, GovAction, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.dijkstra.{DijkstraParamUpdate, DijkstraParamValue}
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, ShelleyBasedEra, Word64}
import xyz.kd5ujc.scarab.protocol.DijkstraNotYet
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, LeiosStateCodecs, Versions}
import xyz.kd5ujc.scarab.models.runtime.DecodeError

/** A proposal's parameter update in a Dijkstra state of the Leios build's layout (#826): the keys
  * Dijkstra adds, 34 to 56, read and written as the reference's `EncCBOR PParamsUpdate` writes them
  * (cardano-ledger 9df9f98c `libs/cardano-ledger-core/src/Cardano/Ledger/Core/PParams.hs:
  * 250-262`), and an update of Conway's keys alone held as before. Every update the reference wrote
  * in `fixtures/reference/dijkstra-pparams` is read in a parameter change and written back byte for
  * byte, as are the two updates the Leios testnet proposed (keys 45 and 46 in epochs 22 to 24, keys
  * 47 and 48 in epochs 71 to 73). The 11.1 layout, whose Dijkstra parameters are not the Leios
  * build's, still refuses them, and a Conway state cannot hold one.
  */
class DijkstraParamProposalSuite extends FunSuite:
  private val v10 = ProtVer(Word64.fromUnsigned(10), Word64.zero)
  private val v12 = Versions.eraLow(ShelleyBasedEra.SDijkstra)
  private val leios = LeiosStateCodecs.govAction(using v12)

  private def hex(text: String): Bytes =
    Bytes.fromHex(text.replace(" ", "")).fold(e => fail(e.toString), identity)

  /** `[0, null, update, null]`: a parameter change of no parent and no policy (`Procedures.hs:
    * 987-993`, cardano-ledger 9df9f98c).
    */
  private def parameterChange(update: Bytes): Bytes = hex("8400f6" + update.hex + "f6")

  private def read(bytes: Bytes): GovAction =
    leios.decodeBytes(bytes).fold(e => fail(e.render), identity)

  private def roundTrip(name: String, update: Bytes): GovAction =
    val raw = parameterChange(update)
    val action = read(raw)
    assertEquals(leios.write(action), Right(raw), name)
    action

  private def dijkstraKeys(a: GovAction): Option[DijkstraParamUpdate] = a match
    case GovAction.ParameterChange(_, d: DijkstraKeysUpdate, _) => Some(d.update)
    case _                                                      => None

  /** A case of the corpus: its name, the update's bytes, and whether the reference refused them. */
  private final case class Row(name: String, update: Bytes, refused: Boolean)

  private val rows: Vector[Row] =
    Files
      .readAllLines(Path.of("fixtures/reference/dijkstra-pparams/cases.jsonl"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map { line =>
        val c = parse(line).fold(e => fail(e.message), identity).hcursor
        Row(
          c.get[String]("id").fold(e => fail(e.message), identity),
          hex(c.get[String]("update").fold(e => fail(e.message), identity)),
          c.downField("reference").downField("refused").succeeded
        )
      }

  test(
    "every update the reference wrote reads in a proposal and writes back; its refusals refused"
  ) {
    assertEquals(rows.size, 113)
    val (refused, accepted) = rows.partition(_.refused)
    // a zero `NonZero`, two zero `PositiveInterval` s and the unknown key (the corpus's README)
    assertEquals(refused.size, 4)
    refused.foreach(r => assert(leios.decodeBytes(parameterChange(r.update)).isLeft, r.name))
    val (dijkstra, conway) =
      accepted.partition(r => dijkstraKeys(roundTrip(r.name, r.update)).isDefined)
    assert(dijkstra.nonEmpty && conway.nonEmpty)
    // an update is Dijkstra's exactly when it sets a key of Dijkstra's
    accepted.foreach { r =>
      val keys = Cbor.decode(r.update).toOption.map(_.value) match
        case Some(Value.Map(pairs)) => pairs.map(_._1.value).collect { case Value.UInt(k) => k }
        case _                      => fail(s"${r.name}: not a map")
      assertEquals(
        dijkstraKeys(read(parameterChange(r.update))).isDefined,
        keys.exists(_ >= DijkstraKeysUpdate.FirstKey),
        r.name
      )
    }
  }

  test("the Leios testnet's two updates read as Dijkstra's and write their bytes") {
    val first = roundTrip("keys 45 and 46", hex("a2182d1a0004b320182e1a001e8480"))
    assertEquals(
      dijkstraKeys(first).map(_.fields),
      Some(
        Map(
          45 -> DijkstraParamValue.Number(BigInt(308_000)),
          46 -> DijkstraParamValue.Number(BigInt(2_000_000))
        )
      )
    )
    val second =
      roundTrip("keys 47 and 48", hex("a2182f821a80befc001b0000008bb2c9700018301a00b71b00"))
    assertEquals(
      dijkstraKeys(second).map(_.fields),
      Some(
        Map(
          47 -> DijkstraParamValue.ExUnits(BigInt(2_160_000_000L), BigInt(600_000_000_000L)),
          48 -> DijkstraParamValue.Number(BigInt(12_000_000))
        )
      )
    )
  }

  test("an update of Conway's keys and Dijkstra's is one Dijkstra update, its ratios reduced") {
    // {0: 44, 9: 2/4, 37: 6/4}: tag 30 ratios are written reduced, as the reference's `Ratio`
    val mixed = parameterChange(hex("a3 00 182c 09 d81e820204 1825 d81e820604"))
    val action = read(mixed)
    assertEquals(
      dijkstraKeys(action).map(_.fields),
      Some(
        Map(
          0 -> DijkstraParamValue.Number(BigInt(44)),
          9 -> DijkstraParamValue.Ratio(Rational(1, 2)),
          37 -> DijkstraParamValue.Ratio(Rational(3, 2))
        )
      )
    )
    assertEquals(
      leios.write(action),
      Right(parameterChange(hex("a3 00 182c 09 d81e820102 1825 d81e820302")))
    )
  }

  test("entries are refused in order: a bad Conway value after a Dijkstra key, a key past 56") {
    // the decoder of Conway's keys stops at key 34; the update read again fails at key 10's -1
    val afterKey = parameterChange(hex("a2 1822 01 0a d81e822001"))
    assert(leios.decodeBytes(afterKey).isLeft)
    val past = parameterChange(hex("a2 1822 01 1839 01"))
    assertEquals(
      leios.decodeBytes(past).left.map(_.kind),
      Left(
        DecodeError.Kind.Domain(
          LedgerDecodeError.UnknownKey(DecodeField.ParameterUpdate, BigInt(57))
        )
      )
    )
  }

  test("the 11.1 layout refuses Dijkstra's keys, and a Conway state cannot write them") {
    val raw = parameterChange(hex("a2182d1a0004b320182e1a001e8480"))
    assertEquals(
      HaskellStateCodecs.govAction(using v12).decodeBytes(raw).left.map(_.kind),
      Left(DecodeError.Kind.Domain(DijkstraNotYet.ParameterUpdate))
    )
    val action = read(raw)
    assert(LeiosStateCodecs.govAction(using v10).write(action).isLeft)
    assert(HaskellStateCodecs.govAction(using v12).write(action).isLeft)
  }
