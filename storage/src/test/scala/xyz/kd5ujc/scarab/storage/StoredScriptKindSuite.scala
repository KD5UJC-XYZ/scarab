// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  Address,
  ByteStrings,
  Datum,
  DecodeField,
  LedgerDecodeError,
  Script,
  ScriptKind,
  TxOut,
  Value
}
import xyz.kd5ujc.scarab.models.runtime.ConstraintViolation

/** PlutusV4 among the kinds of a stored script (V10, changed in place before any deployment, #85,
  * #54): the stores keep an output whose reference script is PlutusV4, the language of a Dijkstra
  * one, while every reader of an era before Dijkstra still refuses it as it did.
  */
class StoredScriptKindSuite extends FunSuite:
  private val plutus = Bytes.fromHex("46010000220011").fold(e => fail(e.toString), identity)
  private def hashOf(kind: ScriptKind) =
    Blake2b.hash224.hash(Bytes(kind.tag.toByte +: plutus.value))
  private val keyHash = Bytes(Vector.tabulate(28)(i => (0xa0 + i).toByte))
  private val address = Address
    .decode(Bytes(0x61.toByte +: keyHash.value), Address.Mode.Stored)
    .fold(e => fail(e.message), identity)

  test("the store's value coding keeps an output whose reference script is PlutusV4"):
    val script = Script(ScriptKind.PlutusV4, hashOf(ScriptKind.PlutusV4), None)
    val out = TxOut(Bytes.empty, address, Value.coin(2000000), Datum.NoDatum, Some(script), 0)
    val stored = UtxoStore.Coding.encodeValue(out)
    // no original encoding: the codec's form, tag 0
    assertEquals(stored(0), 0.toByte)
    assertEquals(UtxoStore.Coding.decodeValue(stored), Right(out))

  test("the flat reader of an era before Dijkstra refuses a script of tag 4 as before"):
    given ByteStrings = ByteStrings.Chunked
    val node = Cbor
      .decode(Bytes.fromHex("8204" + "47" + plutus.hex).fold(e => fail(e.toString), identity))
      .fold(e => fail(e.toString), identity)
    assertEquals(
      Script.decode(node, ScriptKind.newestKnown),
      Left(LedgerDecodeError.UnknownTag(DecodeField.ScriptType, BigInt(4)))
    )

  test("Conway's Haskell state refuses a PlutusV4 script, which Conway's languages do not hold"):
    val v4 = Script(ScriptKind.PlutusV4, hashOf(ScriptKind.PlutusV4), None, Some(plutus))
    assertEquals(
      haskell.state.ConwayCodecs.script.write(v4),
      Left(ConstraintViolation("a Conway script", "PlutusV1 to PlutusV3", "PlutusV4"))
    )
    val v3 = Script(ScriptKind.PlutusV3, hashOf(ScriptKind.PlutusV3), None, Some(plutus))
    assert(haskell.state.ConwayCodecs.script.write(v3).isRight)
