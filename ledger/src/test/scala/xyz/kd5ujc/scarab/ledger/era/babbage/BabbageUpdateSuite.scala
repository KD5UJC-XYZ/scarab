// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  CborRead,
  DecodeField,
  DecoderVersion,
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

  private def typed(update: String): Either[LedgerDecodeError, KeptUpdate[Babbage]] =
    CborRead.decode(hex(update)).flatMap(KeptUpdate.decode[Babbage](_))

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
    // The shared decoder of an update names the key a duplicate repeats, and the update, not the
    // field, as the site of a value out of its width; the flat decoder does the reverse.
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
