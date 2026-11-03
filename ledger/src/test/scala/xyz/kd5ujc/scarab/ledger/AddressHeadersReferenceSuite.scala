// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.protocol.Era

/** The reference refuses a Shelley address whose header sets a bit of `headerNonShelleyBits =
  * 0b10001110` other than a bootstrap address's `0b10000010` (`decodeAddrStateLenientT`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Address.hs:655-666`, cardano-ledger f649f975), and
  * a reward account whose header is not `0b111x000y` (`headerIsAccountAddress`, `:930-932`): only
  * bit 0 of either names the network.
  *
  * `fixtures/reference/address-headers/cases.jsonl` holds, for each era from Shelley to Conway,
  * every header of an address and of a reward account read alone (sixteen types, sixteen low
  * nibbles each), and bodies that hold an address or an account of network 1 and 2 in every place a
  * body reads one, with what the unchanged released decoders did with each. Scarab must give the
  * same verdict. Evidence for #723.
  */
class AddressHeadersReferenceSuite extends FunSuite:
  import AddressHeadersReferenceSuite.*

  private val rows: Vector[Row] = load

  /** The address or account a row's line holds for `nibble`: its bytes with the header changed. */
  private def alone(row: Row.Alone, nibble: Int): Bytes =
    val item = Cbor.decode(row.raw).fold(e => fail(e.toString), identity)
    val raw = CborRead.bytes(item, DecodeField.Address)(using ByteStrings.Definite)
    val bytes = raw.fold(e => fail(e.toString), identity)
    Bytes(((row.kind << 4) | nibble).toByte +: bytes.value.drop(1))

  private def verdict(row: Row.Alone, raw: Bytes): Either[LedgerDecodeError, Unit] = row.place match
    case Place.Address => Address.decode(raw, Address.Mode.of(row.era)).void
    case Place.Account => RewardAccount.decode(raw).void

  test("the corpus covers every era, both places of a header, and the bodies") {
    val alone = rows.collect { case a: Row.Alone => a }
    for era <- Era.values.toVector if era != Era.Byron do
      for place <- Place.values do
        assertEquals(
          alone.filter(r => r.era == era && r.place == place).map(_.kind).sorted,
          (0 until 16).toVector,
          s"$era $place"
        )
    val bodies = rows.collect { case b: Row.Body => b }
    assert(bodies.exists(_.accepted) && bodies.exists(!_.accepted))
    assertEquals(bodies.map(_.era).toSet, Era.values.toSet - Era.Byron)
  }

  for row <- rows do
    row match
      case a: Row.Alone =>
        test(a.name) {
          for nibble <- 0 until 16 do
            val raw = alone(a, nibble)
            val expected = a.accepted(nibble)
            verdict(a, raw) match
              case Right(()) => assert(expected, s"network $nibble: the reference refuses it")
              case Left(e) =>
                assert(!expected, s"network $nibble: the reference accepts it, Scarab gave $e")
        }
      case b: Row.Body =>
        test(b.name) {
          TxBody.decode(b.raw, b.era) match
            case Right(_) => assert(b.accepted, s"the reference refuses this body: ${b.failure}")
            case Left(e)  => assert(!b.accepted, s"the reference accepts this body, Scarab gave $e")
        }

  /** The stores read what an earlier build stored (ADR 0006), and an earlier build took an output
    * whose address header names a network of 2 to 15. [[Address.Mode.Stored]] still reads every
    * Shelley header, while every era's reader of a block refuses those the reference refuses.
    */
  test("a stored output keeps reading a header the reference refuses in a block") {
    val shelleyTypes = rows.collect {
      case a: Row.Alone if a.place == Place.Address && a.era == Era.Conway && a.kind < 8 => a
    }
    assertEquals(shelleyTypes.size, 8)
    for row <- shelleyTypes; nibble <- 0 until 16 do
      val raw = alone(row, nibble)
      assert(Address.decode(raw, Address.Mode.Stored).isRight, s"${row.name} network $nibble")
      val output = Bytes(Vector(0x82.toByte, 0x58.toByte, raw.size.toByte)) ++ raw ++
        Bytes(Vector(0x1a.toByte, 0, 0x1e.toByte, 0x84.toByte, 0x80.toByte))
      assertEquals(TxOut.fromBytes(output)(using ByteStrings.Chunked).map(_.raw), Right(output))
      for era <- Era.values.toVector if era != Era.Byron do
        assertEquals(
          Address.decode(raw, Address.Mode.of(era)).isRight,
          (nibble & 0x0e) == 0,
          s"$era ${row.name} network $nibble"
        )
  }

object AddressHeadersReferenceSuite:
  /** Where a header is read: an address (`DecCBOR Addr`) or a reward account (`DecCBOR
    * AccountAddress`).
    */
  enum Place(val wire: String):
    case Address extends Place("address")
    case Account extends Place("account")

  /** A line of the corpus. */
  sealed trait Row:
    def name: String
    def era: Era

  object Row:
    /** The sixteen headers of one type read alone: `raw` is the CBOR byte string of nibble 0. */
    final case class Alone(
        name: String,
        era: Era,
        place: Place,
        kind: Int,
        accepted: Vector[Boolean],
        raw: Bytes
    ) extends Row

    /** A body that holds an address or an account. */
    final case class Body(
        name: String,
        era: Era,
        accepted: Boolean,
        failure: Option[String],
        raw: Bytes
    ) extends Row

  private val eras: Map[String, Era] = Era.values.map(e => e.toString.toLowerCase -> e).toMap

  private def failure(c: io.circe.HCursor, what: String) =
    io.circe.DecodingFailure(s"unknown $what", c.history)

  private given Decoder[Row] = Decoder.instance { c =>
    for
      name <- c.get[String]("name")
      era <- c.get[String]("era").flatMap(e => eras.get(e).toRight(failure(c, s"era $e")))
      kind <- c.get[String]("kind")
      raw <- c
        .get[String]("bytes")
        .flatMap(h => Bytes.fromHex(h).leftMap(e => failure(c, e.toString)))
      row <- kind match
        case "body" =>
          (c.get[Boolean]("accepted"), c.get[Option[String]]("failure")).mapN((a, f) =>
            Row.Body(name, era, a, f, raw)
          )
        case other =>
          for
            place <- Place.values.find(_.wire == other).toRight(failure(c, s"kind $other"))
            tpe <- c.get[Int]("type")
            accepted <- c.get[Vector[Boolean]]("accepted")
          yield Row.Alone(name, era, place, tpe, accepted, raw)
    yield row
  }

  private def load: Vector[Row] =
    val path = Path.of("fixtures/reference/address-headers/cases.jsonl")
    Files
      .readAllLines(path)
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(line => parser.decode[Row](line).fold(e => sys.error(s"$path: $e"), identity))
