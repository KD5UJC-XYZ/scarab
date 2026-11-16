// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational}
import xyz.kd5ujc.scarab.ledger.dijkstra.*
import xyz.kd5ujc.scarab.models.runtime.Codec

/** Hand-written encodings worked out from cardano-ledger 9df9f98c, independent of the corpus. They
  * check serialization choices that a value round trip alone cannot distinguish.
  */
class DijkstraCodecsSuite extends FunSuite:
  private def hex(s: String): Bytes =
    Bytes.wrap(s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)
  private val hash28 = "581c" + "00" * 28
  private val hash32 = "5820" + "00" * 32
  private val account = "581de0" + "00" * 28
  private val address = "581d61" + "00" * 28
  private val anchor = "8260" + hash32

  private def reencode[A](codec: Codec[A], input: String, expected: String): A =
    val value =
      codec.decodeBytes(hex(input), Cbor.Limits.Unbounded).fold(e => fail(e.toString), identity)
    assertEquals(codec.write(value), Right(hex(expected)))
    value

  test("pool registration drops the explicit absent BLS slot, and retains owner set tagging") {
    val fields = hash28 + hash32
    val rest = "0000d81e820001" + account + "d901028080f6"
    reencode(DijkstraCodecs.certificate, "8b03" + fields + "f6" + rest, "8a03" + fields + rest)
  }

  test("Dijkstra delegation and governance certificate alternatives retain their tags") {
    val credential = "8200" + hash28
    for sample <- Vector(
        "8307" + credential + "00",
        "8308" + credential + "00",
        "8309" + credential + "8102",
        "840a" + credential + hash28 + "8103",
        "840b" + credential + hash28 + "01",
        "840c" + credential + "8102" + "01",
        "850d" + credential + hash28 + "8103" + "01",
        "830e" + credential + credential,
        "830f" + credential + "f6",
        "8410" + credential + "00" + anchor,
        "8311" + credential + "01",
        "8312" + credential + "f6"
      )
    do reencode(DijkstraCodecs.certificate, sample, sample)
  }

  test("parameter updates sort sparse keys, shorten heads and keep the null bootstrap round") {
    reencode(DijkstraCodecs.paramUpdate, "a21835f600190001", "a200011835f6")
    reencode(DijkstraCodecs.paramUpdate, "a112a118038219000120", "a112a103820120")
    reencode(DijkstraCodecs.paramUpdate, "a11826d81e820408", "a11826d81e820102")
    reencode(DijkstraCodecs.paramUpdate, "a11826f6", "a11826f6")
  }

  test("cost model lists above 23 parameters use the reference's indefinite framing") {
    val values = "00" * 24
    reencode(DijkstraCodecs.paramUpdate, "a112a1009818" + values, "a112a1009f" + values + "ff")
  }

  test("all governance action alternatives write their decoded fields") {
    for action <- Vector(
        "8400f6a10001f6",
        "8301f6820d00",
        "8302a1" + account + "01f6",
        "8203f6",
        "8504f6d9010280a0d81e820102",
        "8305f682" + anchor + "f6",
        "8106"
      )
    do
      val sample = "8400" + account + action + anchor
      reencode(DijkstraCodecs.proposal, sample, sample)
  }

  test("relay bytes and native-script bytes survive, while a Plutus payload joins its chunks") {
    reencode(
      DijkstraCodecs.relay,
      "8400191234440102030450000102030405060708090a0b0c0d0e0f",
      "8400191234440102030450000102030405060708090a0b0c0d0e0f"
    )
    reencode(DijkstraCodecs.relay, "8301f6626162", "8301f6626162")
    reencode(DijkstraCodecs.relay, "8202626162", "8202626162")
    reencode(DijkstraCodecs.script, "82009f041800ff", "82009f041800ff")
    reencode(DijkstraCodecs.script, "82045f41014102ff", "8204420102")
  }

  test("outputs choose their form from the value and preserve inline data and native scripts") {
    reencode(DijkstraCodecs.txOut, "a200" + address + "0100", "82" + address + "00")
    reencode(
      DijkstraCodecs.txOut,
      "a300" + address + "0100028200" + hash32,
      "83" + address + "00" + hash32
    )
    val inline = "a300" + address + "0100028201d818421800"
    reencode(DijkstraCodecs.txOut, inline, inline)
    // Seven bytes in the nested script: array(2), tag(0), indefinite [4, 0] with a wide zero.
    val script = "82009f041800ff"
    val withScript = "a300" + address + "010003d81847" + script
    reencode(DijkstraCodecs.txOut, withScript, withScript)
  }

  test("standalone intervals keep all four alternatives and refuse a pair with neither bound") {
    for (input, expected) <- Vector(
        "190001" -> "01",
        "9f01f6ff" -> "8201f6",
        "82f602" -> "82f602",
        "820102" -> "820102"
      )
    do reencode(DijkstraCodecs.balanceInterval, input, expected)
    assert(DijkstraCodecs.balanceInterval.decodeBytes(hex("82f6f6")).isLeft)
  }

  test("checked writers refuse constructed out-of-range values and wrong parameter field types") {
    import DijkstraParamValue.*
    for value <- Vector(
        DijkstraParamUpdate(Map(0 -> Number(-1))),
        DijkstraParamUpdate(Map(0 -> Number(BigInt(1) << 64))),
        DijkstraParamUpdate(Map(0 -> Ratio(Rational.one))),
        DijkstraParamUpdate(Map(10 -> Ratio(Rational(2, 1)))),
        DijkstraParamUpdate(Map(57 -> Number(0)))
      )
    do assert(DijkstraCodecs.paramUpdate.write(value).isLeft, value.toString)
    assert(DijkstraCodecs.relay.write(DijkstraRelay.SingleHostAddr(Some(-1), None, None)).isLeft)
    assert(DijkstraCodecs.balanceInterval.write(DijkstraBalanceInterval.Exact(-1)).isLeft)
    assert(DijkstraCodecs.datum.write(DijkstraDatum.NoDatum).isLeft)
    assert(DijkstraCodecs.script.write(DijkstraScript.Native(hex("01"))).isLeft)
  }
