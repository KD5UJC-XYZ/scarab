// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  CborRead,
  DecodeField,
  DecoderVersion,
  DetachedRecords,
  LedgerDecodeError,
  TxBody,
  Update as StoredUpdate
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.state.Babbage
import xyz.kd5ujc.scarab.protocol.Era as ProtocolEra

import BabbageCbor.*

/** The update proposal of a Babbage body's key 6, read the typed way ([[KeptUpdate.decode]])
  * against the flat decoder (`ParamUpdate.read` through `TxBody.decode`): the same stored form,
  * byte for byte, in any form its values are written in; the stored form read back into the same
  * typed update; and the same refusals.
  */
class BabbageUpdateSuite extends ScalaCheckSuite:
  private val version = DecoderVersion.of(ProtocolEra.Babbage)
  private given DecodeAt = DecodeAt(version, DecodeField.ParameterUpdate)

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def hex(s: String): Bytes = right("hex")(Bytes.fromHex(s))

  /** A Babbage body with no input, no output, no fee and the update `update`. */
  private def body(update: String): Bytes = hex("a4" + "0080" + "0180" + "0200" + "06" + update)

  private def flat(update: String): Either[LedgerDecodeError, StoredUpdate] =
    TxBody.decode(body(update), ProtocolEra.Babbage).map(_.update.getOrElse(fail("no update")))

  /** The update of the same body read the typed way, its key 6 taken from the body as the flat
    * decoder reads it, its detached records among its items (`DetachedRecords.Shapes.body`).
    */
  private def typed(update: String): Either[LedgerDecodeError, KeptUpdate[Babbage]] =
    DetachedRecords.read(
      body(update),
      Cbor.Limits.Unbounded,
      DetachedRecords.Shapes.body(ProtocolEra.Babbage, version)
    )(LedgerDecodeError.Syntax(_))(root =>
      root.value match
        case Value.Map(kvs) =>
          kvs
            .collectFirst { case (k, v) if k.value == Value.UInt(6) => v }
            .toRight(
              LedgerDecodeError.MissingField(DecodeField.TransactionBody, DecodeField.Update)
            )
            .flatMap(KeptUpdate.decode[Babbage](_))
        case other =>
          Left(
            LedgerDecodeError.Expected(
              DecodeField.TransactionBody,
              LedgerDecodeError.Shape.Map,
              other
            )
          )
    )

  property(
    "a body's update read the typed way is stored as the flat decoder stores it, byte for byte"
  ):
    forAll(genUpdate) { (update, fields) =>
      val stored = right("flat")(flat(update))
      val kept = right("typed")(typed(update))
      assertEquals(kept.stored, stored)
      // each value as it was written, the wide forms among them
      assertEquals(
        stored.proposals.toVector.sortBy(_._1.hex).map(_._2.fields),
        fields.map(_.map((k, v) => k -> hex(v)).toMap)
      )
      val back = right("from stored")(KeptUpdate.fromStored[Babbage](stored))
      assertEquals(back.update, kept.update)
      assertEquals(back.stored, stored)
    }

  property("an update proposal's typed values are the encoder's, read again"):
    forAll(genUpdate) { (update, _) =>
      right("typed")(typed(update)).update.proposals.updates.values.foreach { ppu =>
        val bytes = PParamsUpdate.encodePParamsUpdate(ppu).bytes
        assertEquals(
          PParamsUpdate.decodePParamsUpdate[Babbage](right("cbor")(CborRead.decode(bytes))),
          Right(ppu)
        )
      }
    }

  test("a wide value is stored as written, and is the same typed value as the short one"):
    val short = array(Seq(map(Seq(genesisKey(1) -> map(Seq(uint(8) -> uint(500))))), uint(7)))
    val wide =
      array(Seq(map(Seq(genesisKey(1) -> map(Seq(uint(8) -> uint(500, wide = true))))), uint(7)))
    val (s, w) = (right("short")(typed(short)), right("wide")(typed(wide)))
    assertEquals(s.update, w.update)
    assertNotEquals(s.stored, w.stored)
    assertEquals(w.stored, right("flat")(flat(wide)))
    assertEquals(w.stored.proposals.values.head.fields(8), hex("1b00000000000001f4"))

  test("what the flat decoder refuses the typed decoder refuses, with the same refusal but two"):
    def one(fields: (String, String)*): String =
      array(Seq(map(Seq(genesisKey(2) -> map(fields))), uint(3)))
    val same = Vector(
      "the decentralisation" -> one(uint(12) -> ratio(1, 2, false)),
      "the extra entropy" -> one(uint(13) -> "8100"),
      "the minimum UTxO value" -> one(uint(15) -> uint(1)),
      "a major above Babbage's" -> one(uint(14) -> array(Seq(uint(10), uint(0)))),
      "a version of three" -> one(uint(14) -> array(Seq(uint(8), uint(0), uint(0)))),
      "a genesis key of 27 bytes" -> array(
        Seq(map(Seq(("581b" + "00" * 27) -> map(Seq()))), uint(3))
      )
    )
    same.foreach { (what, u) =>
      assert(flat(u).isLeft, s"$what: flat ${flat(u)}")
      assertEquals(typed(u).map(_.stored), flat(u), what)
    }
    // Known diagnostic gap (#85), the flat decoder's as well: the shared decoder of an update names
    // the key a duplicate repeats, as the reference's `duplicateKey` does, where the flat one does
    // not; both name a range for a value out of its width, at another site each, where cborg's
    // `decodeWord16` refuses the item as not a `word16`.
    val twice = one(uint(8) -> uint(1), uint(8) -> uint(2))
    assertEquals(flat(twice), Left(LedgerDecodeError.DuplicateKey(DecodeField.ParameterUpdate)))
    assertEquals(
      typed(twice).map(_.stored),
      Left(
        LedgerDecodeError.PParamsCodec(
          PParamsDecodeError.DuplicateKey(DecodeField.ParameterUpdate, 8)
        )
      )
    )
    // The reference reads a nonempty indefinite outer record as `RecD` does, with "Excess terms in
    // array" (`decodeRecordNamed` under `RecD`, cardano-ledger f649f975 `Coders.hs:478-483`); both
    // decoders here name its indefinite length.
    val indefinite = "9f" + map(Seq(genesisKey(1) -> map(Seq(uint(8) -> uint(1))))) + uint(3) + "ff"
    assertEquals(flat(indefinite), Left(LedgerDecodeError.IndefiniteLength(DecodeField.Update)))
    assertEquals(
      typed(indefinite).map(_.stored),
      Left(LedgerDecodeError.IndefiniteLength(DecodeField.Update))
    )
    val wide = one(uint(8) -> uint(0x10000))
    assertEquals(
      flat(wide),
      Left(LedgerDecodeError.NumberOutOfRange(DecodeField.NOpt, 0x10000, 0, 0xffff))
    )
    assertEquals(
      typed(wide).map(_.stored),
      Left(LedgerDecodeError.NumberOutOfRange(DecodeField.ParameterUpdate, 0x10000, 0, 0xffff))
    )
    assertEquals(
      typed(one(uint(14) -> array(Seq(uint(9), uint(0))))).map(_.update.proposals.updates.size),
      Right(1)
    )

  /** The outcomes of the forms at the edges of what the two decoders read, each run both ways. */
  private val edges: Vector[(String, String)] =
    def one(fields: (String, String)*): String =
      array(Seq(map(Seq(genesisKey(4) -> map(fields))), uint(5)))
    val widths =
      Vector(0, 1, 5, 6, 16, 17).map(_ -> word64) ++ Vector(2, 3, 7, 22).map(_ -> word32) ++
        Vector(4, 8, 23, 24).map(_ -> word16)
    val half = ratio(1, 2, false)
    // one over a Word64 is a bignum, tag 2 over its nine bytes
    def over(max: BigInt) = if max == word64 then "c249" + "01" + "00" * 8 else uint(max + 1)
    widths.map((k, max) => s"key $k one over its width" -> one(uint(k) -> over(max))) ++
      widths.map((k, max) => s"key $k at its width" -> one(uint(k) -> uint(max))) ++
      Vector(
        "a minor over Word32" -> one(uint(14) -> array(Seq(uint(8), uint(word32 + 1)))),
        "a version, indefinite" -> one(uint(14) -> "9f0800ff"),
        "cost models, an indefinite model" -> one(
          uint(18) -> map(Seq(uint(0) -> ("9f" + Seq.tabulate(166)(i => uint(i)).mkString + "ff")))
        ),
        "cost models, an indefinite map" -> one(
          uint(18) -> ("bf" + uint(0) + array(Seq.tabulate(166)(i => uint(i))) + "ff")
        ),
        "prices, indefinite" -> one(uint(19) -> ("9f" + half + half + "ff")),
        "prices, detached" -> one(uint(19) -> ("9fff" + half + half)),
        "units, indefinite" -> one(uint(20) -> "9f0a0bff"),
        "units, detached" -> one(uint(21) -> "9fff0a0b"),
        "a ratio, its list indefinite" -> one(uint(10) -> "d81e9f0102ff"),
        "a ratio above one" -> one(uint(10) -> ratio(3, 2, false)),
        "a ratio over zero" -> one(uint(9) -> ratio(1, 0, false)),
        "a genesis key twice" -> array(
          Seq(
            map(
              Seq(
                genesisKey(4) -> map(Seq(uint(8) -> uint(1))),
                genesisKey(4) -> map(Seq(uint(8) -> uint(2)))
              )
            ),
            uint(5)
          )
        ),
        "an update map, indefinite" -> array(
          Seq(map(Seq(genesisKey(4) -> ("bf" + uint(8) + uint(1) + "ff"))), uint(5))
        )
      )

  test("at the edges of what they read, the two decoders accept alike and store alike"):
    edges.foreach { (what, u) =>
      (flat(u), typed(u)) match
        case (Right(f), Right(t)) => assertEquals(t.stored, f, what)
        case (Left(_), Left(_))   => ()
        case (f, t)               => fail(s"$what: flat $f, typed ${t.map(_.stored)}")
    }

  test("only an era whose update sets the protocol version reads an update proposal"):
    assertEquals(compileErrors("(n: Node) => KeptUpdate.decode[Babbage](n)"), "")
    assert(
      compileErrors(
        "(n: Node) => KeptUpdate.decode[xyz.kd5ujc.scarab.ledger.state.Conway](n)"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "(n: Node) => KeptUpdate.decode[xyz.kd5ujc.scarab.ledger.state.Dijkstra](n)"
      ).nonEmpty
    )

  test("a ratio written as 2/4 is the same typed value as 1/2"):
    def one(r: String) = array(Seq(map(Seq(genesisKey(3) -> map(Seq(uint(10) -> r)))), uint(1)))
    val (a, b) =
      (right("1/2")(typed(one(ratio(1, 2, false)))), right("2/4")(typed(one(ratio(2, 4, false)))))
    assertEquals(a.update, b.update)
    assertNotEquals(a.stored, b.stored)

  test(
    "the recorded era walk's Babbage update proposals read the typed way as the flat decoder stores them"
  ):
    val compared = java.nio.file.Files
      .readString(java.nio.file.Path.of("fixtures/eras/blocks.hex"))
      .linesIterator
      .zipWithIndex
      .filter(_._1.nonEmpty)
      .map((l, i) =>
        (i + 1) -> right("block")(xyz.kd5ujc.scarab.ledger.Block.decode(hex(l), BigInt(50)))
      )
      .collect {
        case (line, b: xyz.kd5ujc.scarab.ledger.Block.Shelley) if b.era == ProtocolEra.Babbage =>
          line -> b
      }
      .flatMap((line, b) =>
        b.transactions.flatMap { t =>
          val flatTx = right(s"${t.id.hex} flat")(xyz.kd5ujc.scarab.ledger.Tx.fromBlock(t, b.era))
          val key6 = right("body")(CborRead.decode(t.body)).value match
            case xyz.kd5ujc.scarab.codec.Value.Map(kvs) =>
              kvs.collectFirst {
                case (k, v) if k.value == xyz.kd5ujc.scarab.codec.Value.UInt(6) => v
              }
            case other => fail(s"not a body: $other")
          key6.map { n =>
            val kept = right(s"${t.id.hex} typed")(KeptUpdate.decode[Babbage](n))
            assertEquals(Some(kept.stored), flatTx.body.update, t.id.hex)
            assertEquals(
              KeptUpdate.fromStored[Babbage](kept.stored).map(_.update),
              Right(kept.update)
            )
            line
          }
        }
      )
      .toVector
    // the walk's two Babbage update proposals, at these lines of the fixture
    assertEquals(compared, Vector(543, 633))
