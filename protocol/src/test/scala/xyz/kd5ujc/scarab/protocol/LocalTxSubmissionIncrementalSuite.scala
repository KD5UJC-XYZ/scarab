// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.lang.management.ManagementFactory
import munit.FunSuite
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.{DecodeResult, Role}
import LocalTxSubmission.{Message, State}

class LocalTxSubmissionIncrementalSuite extends FunSuite:
  private def hex(value: String): Bytes = Bytes.fromHex(value).fold(e => fail(e.toString), identity)

  private def fragmented(input: Bytes, chunk: Int): DecodeResult[Message] =
    val decoder = LocalTxSubmission.incremental(State.Idle, Role.Client)
    @tailrec def go(end: Int, cursor: decoder.Cursor): DecodeResult[Message] =
      val (next, result) = decoder.resume(cursor, input.take(end))
      if end == input.size || result != DecodeResult.NeedMore then result
      else go(math.min(input.size, end + chunk), next)
    go(0, decoder.initial)

  test("every byte boundary has the same verdict, including early malformed prefixes") {
    val payloads = Vector(
      "00",
      "1818",
      "1bffffffffffffffff",
      "f93c00",
      "fa00000000",
      "fb0000000000000000",
      "f81f",
      "ff",
      "5f4043616263ff",
      "7f60626364ff",
      "7f6180ff",
      "7f61c261a0ff",
      "5f5fffff",
      "5f19",
      "7f5b",
      "9f00a201020304ff",
      "bf0102ff",
      "bf01ff",
      "9fc0820060ff",
      "9f7f6060ff00ff",
      "9f7f6180ff",
      "9f1c",
      "9f5bffffffffffffffff",
      "9f9bffffffffffffffff",
      "9b000000007fffffff",
      "9b000000007ffffffe",
      "819b000000007ffffffe",
      "bb0000000040000000",
      "bb000000003fffffff",
      "81bb000000003fffffff",
      "9f5f40ff9b000000007ffffffc"
    )
    val messages = payloads.map(p => hex("8200" + p)) ++
      Vector("8103", "820500", "8100", "9f0000ff", "98021800820000").map(hex)
    for input <- messages; state <- State.values; role <- Role.values do
      val decoder = LocalTxSubmission.incremental(state, role)
      (0 to input.size).foldLeft(decoder.initial) { (cursor, size) =>
        val prefix = input.take(size)
        val (next, result) = decoder.resume(cursor, prefix)
        assertEquals(
          result,
          LocalTxSubmission.decodePrefix(state, role, prefix),
          s"${prefix.hex}: $state/$role"
        )
        next
      }
  }

  test("a complete message leaves the following message outside its consumed span") {
    val input = hex("82009f0000ff8103")
    assertEquals(fragmented(input, 1), DecodeResult.Decoded(Message.SubmitTx(hex("9f0000ff")), 6))
  }

  test("fragmented text chunks allocate linearly, without rescanning completed chunks") {
    val bean = ManagementFactory.getThreadMXBean match
      case value: com.sun.management.ThreadMXBean => value
      case _ => fail("thread allocation measurement unavailable")
    def input(size: Int) = hex("82007f") ++ Bytes.wrap(Array.fill[Byte](size)(0x60)) ++ hex("ff")
    val small = input(8000)
    val large = input(32000)
    (0 until 3).foreach(_ => fragmented(small, 16))
    def allocated(bytes: Bytes): Long =
      val thread = Thread.currentThread().threadId()
      val before = bean.getThreadAllocatedBytes(thread)
      val result = fragmented(bytes, 16)
      val consumed = bean.getThreadAllocatedBytes(thread) - before
      assertEquals(result, DecodeResult.Decoded(Message.SubmitTx(bytes.drop(2)), bytes.size))
      consumed
    val first = allocated(small)
    val second = allocated(large)
    assert(second < first * 6, s"four times the input allocated $second bytes versus $first")
    assert(
      second < large.size.toLong * 1200,
      s"$second bytes allocated for ${large.size} wire bytes"
    )
  }
