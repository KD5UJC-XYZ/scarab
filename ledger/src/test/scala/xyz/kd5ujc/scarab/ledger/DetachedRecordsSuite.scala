// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.protocol.Era

import DetachedRecords.Shape.*

/** The walk that finds the `RecD` records written as an empty indefinite list with their fields
  * after it, the readers of such a record, and what is kept of one (#687). The verdicts against the
  * reference's are [[DetachedRecordsReferenceSuite]]'s.
  */
class DetachedRecordsSuite extends FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
  private val pair = Record(List(Item, Item))
  private def found(hex: String, shape: DetachedRecords.Shape): Option[Vector[(Int, Int)]] =
    DetachedRecords.detached(bytes(hex), shape).map(d => d.offsets.zip(d.fields))

  test("the walk finds a record where the shape has one, and only there") {
    assertEquals(
      found("82019fff0203", DetachedRecords.Shape.Fields(List(Item, pair), Item)),
      Some(Vector(2 -> 2))
    )
    // the same bytes read as a list of anything: no record, and the items do not fit the list
    assertEquals(found("82019fff0203", ListOf(Item)), None)
    // an empty indefinite list read as a list is the empty list
    assertEquals(found("829fff01", ListOf(Item)), Some(Vector.empty))
    // a definite record
    assertEquals(found("8201820203", Fields(List(Item, pair), Item)), Some(Vector.empty))
  }

  test("the walk gives up where the reference fails") {
    // bytes after the item
    assertEquals(found("9fff010200", pair), None)
    // a definite record of the wrong length (`matchSize` before any field)
    assertEquals(found("83010203", pair), None)
    // a non-empty indefinite record ("Excess terms in array")
    assertEquals(found("9f0102ff", pair), None)
    // the fields run past the end of the input, or into a break
    assertEquals(found("9fff01", pair), None)
    assertEquals(found("9f9fff01ffff", ListOf(pair)), None)
    // a key of a sparse map that is not a `Word`
    assertEquals(found("a1200a", Keyed(_ => Item)), None)
  }

  test("the walk keeps its stack on the heap") {
    // a list nested 200,000 deep, read as one item on a small stack
    val depth = 200000
    val raw = Bytes.wrap(Array.fill[Byte](depth)(0x81.toByte) :+ 0.toByte)
    assertEquals(
      PreprodDeepTx.onSmallStack(DetachedRecords.detached(raw, Item).map(_.isEmpty)),
      Some(true)
    )
  }

  test("only a record's reader takes a detached record; it is kept as a definite list") {
    val raw = bytes("82019fff0203")
    val root = DetachedRecords
      .read(raw, Cbor.Limits(), Fields(List(Item, pair), Item))(LedgerDecodeError.Syntax(_))(
        Right(_)
      )
      .fold(e => fail(e.toString), identity)
    val record =
      CborRead.array(root, DecodeField.Block).map(_(1)).fold(e => fail(e.toString), identity)
    assert(record.isDetached)
    assertEquals(
      CborRead.array(record, DecodeField.Block),
      Left(LedgerDecodeError.DetachedList(DecodeField.Block))
    )
    assertEquals(
      CborRead.arrayN(record, 2, DecodeField.Block),
      Left(LedgerDecodeError.DetachedList(DecodeField.Block))
    )
    assertEquals(
      CborRead.record(record, 2, DecodeField.Block).map(_.map(_.original)),
      Right(Vector(bytes("02"), bytes("03")))
    )
    assertEquals(
      CborRead.record(record, 3, DecodeField.Block),
      Left(LedgerDecodeError.ArrayLength(DecodeField.Block, 3, 2))
    )
    assertEquals(CborRead.kept(record), bytes("820203"))
    assertEquals(CborRead.kept(root), raw)
  }

  // A Babbage body: one input, an output in the map form whose value is detached and which holds a
  // native reference script, a fee, and an update proposing detached execution units.
  private val address = "581d61" + "22" * 28
  private val value = "9fff" + "1a001e8480" + "a1581c" + "88" * 28 + "a1416101"
  private val script = "d81858228200" + "8200581c" + "33" * 28
  private val genesis = "581c" + "5a" * 28
  private def babbageBody(output: String, units: String): Bytes = bytes(
    "a4" + "00818258201111111111111111111111111111111111111111111111111111111111111111" + "00" +
      "0181" + output + "021a00030d40" + "0682a1" + genesis + "a114" + units + "0a"
  )

  test("a stored output with a detached value reads again as it was read") {
    val body = TxBody
      .decode(
        babbageBody("a3" + "00" + address + "01" + value + "03" + script, "820102"),
        Era.Babbage
      )
      .fold(e => fail(e.toString), identity)
    val twin = TxBody
      .decode(
        babbageBody(
          "a3" + "00" + address + "01" + "82" + value.drop(4) + "03" + script,
          "820102"
        ),
        Era.Babbage
      )
      .fold(e => fail(e.toString), identity)
    val out = body.outputs.head
    assertEquals(out.raw.take(3), bytes("a30058"))
    assertEquals(TxOut.fromBytes(out.raw)(using ByteStrings.Chunked), Right(out))
    assert(out.referenceScriptSize > 0)
    assertEquals(out.referenceScriptSize, twin.outputs.head.referenceScriptSize)
    assertEquals(out.referenceScript, twin.outputs.head.referenceScript)
    assert(TxOut.node(out.raw).exists(_.original == out.raw))
  }

  test("a parameter update keeps a detached record as a definite list") {
    val body = TxBody
      .decode(babbageBody("82" + address + "1a001e8480", "9fff0102"), Era.Babbage)
      .fold(e => fail(e.toString), identity)
    val update = body.update.flatMap(_.proposals.values.headOption)
    assertEquals(update.flatMap(_.fields.get(20)), Some(bytes("820102")))
    // and the definite form keeps its original bytes
    val definite = TxBody
      .decode(babbageBody("82" + address + "1a001e8480", "820102"), Era.Babbage)
      .fold(e => fail(e.toString), identity)
    assertEquals(definite.update.flatMap(_.proposals.values.headOption), update)
  }

  test("a Conway parameter change keeps a detached record as a definite list, read again as it") {
    val proposal = "84" + "1a000f4240" + "581de1" + "55" * 28 +
      "8400" + "f6" + "a114" + "9fff0102" + "f6" + "82" + "7819" + "https://example.invalid/a".getBytes
        .map(b => f"$b%02x")
        .mkString + "5820" + "44" * 32
    val raw = bytes(
      "a4" + "00818258201111111111111111111111111111111111111111111111111111111111111111" + "00" +
        "0181" + "82" + address + "1a001e8480" + "021a00030d40" + "1481" + proposal
    )
    val body = TxBody.decode(raw, Era.Conway).fold(e => fail(e.toString), identity)
    val update = body.proposals.headOption.map(_.action) match
      case Some(GovAction.ParameterChange(_, u, _)) => u
      case other                                    => fail(s"not a parameter change: $other")
    assertEquals(update.encodings, Vector(20 -> bytes("820102")))
    assertEquals(
      ConwayParamUpdate.fromEncoded(update.encodings.map((k, v) => BigInt(k) -> v)).map(_.params),
      Right(update.params)
    )
  }
