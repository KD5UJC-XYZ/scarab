// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}

class VotingProceduresSuite extends FunSuite:
  private def head(major: Int, n: Int): String =
    if n < 24 then f"${(major << 5) | n}%02x"
    else if n < 256 then f"${(major << 5) | 24}%02x$n%02x"
    else f"${(major << 5) | 25}%02x${n >> 8}%02x${n & 0xff}%02x"
  private val voter = "8202581c" + "11" * 28
  private def action(index: Int): String = "825820" + "22" * 32 + head(0, index)
  private def decode(raw: String) =
    val node = CborRead
      .decode(
        Bytes.fromHex(raw).fold(e => fail(e.toString), identity),
        Cbor.Limits(maxInputBytes = 2500000, maxItems = 1000000)
      )
      .fold(e => fail(e.toString), identity)
    VotingProcedures.decode(node, DecoderVersion.of(xyz.kd5ujc.scarab.protocol.Era.Conway))

  test("an invalid ballot is a typed value failure, independent of the optional anchor") {
    assertEquals(
      decode("a1" + voter + "a1" + action(0) + "8203f6"),
      Left(VotingProcedures.DecodeError.InvalidVote(3))
    )
  }

  test("a malformed anchor is refused rather than discarded") {
    decode("a1" + voter + "a1" + action(0) + "820100") match
      case Left(VotingProcedures.DecodeError.InvalidStructure(_)) => ()
      case other => fail(s"not a malformed anchor: $other")
  }

  test("twenty thousand voting procedures decode and retain anchors on a 256 KiB stack") {
    val anchor = "8261785820" + "33" * 32
    val raw = "a1" + voter + head(5, 20000) +
      (0 until 20000).map(i => action(i) + "8201" + anchor).mkString
    val result = PreprodDeepTx.onSmallStack(decode(raw)).fold(e => fail(e.message), identity)
    val procedures = result.flatMap(_._2).map(_._2)
    assertEquals(procedures.size, 20000)
    assert(procedures.forall(_.anchor.contains(Anchor("x", Bytes(Vector.fill(32)(0x33.toByte))))))
  }
