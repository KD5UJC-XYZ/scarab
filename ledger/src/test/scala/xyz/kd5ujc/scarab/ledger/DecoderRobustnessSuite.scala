// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import scala.annotation.unused

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.plutus.PlutusData
import munit.FunSuite

/** Regressions from fuzzing the decoders (roadmap K3, docs/findings.md): each input is the
  * minimised form of what the fuzzer found.
  */
class DecoderRobustnessSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(fail(_), identity)
  private def filled(n: Int, b: Int): Bytes = Bytes.fromArray(Array.fill(n)(b.toByte))
  private def bytes(xs: Int*): Bytes = Bytes.fromArray(xs.map(_.toByte).toArray)

  /** Run `body` on a thread with a small stack, so that a deep item overflows it for certain. */
  @unused private def onSmallStack[A](body: => A, stack: Long): A =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "small",
      stack
    )
    thread.start()
    thread.join()
    result.get.fold(throw _, identity)

  private val hash = filled(28, 0xab)

  private def pointer(first: Bytes): Bytes = bytes(0x40) ++ hash ++ first ++ bytes(0x01, 0x02)

  test("a pointer address with a long run of continuation bytes decodes in linear time") {
    // header 0x40 (pointer address, key payment), a 28-byte hash, then 100000 bytes 0x81 and a last byte
    val started = System.nanoTime()
    val result = Address.decode(pointer(filled(100000, 0x81) ++ bytes(0x01)))
    val millis = (System.nanoTime() - started) / 1000000
    val slot = result.toOption.flatMap(_.kind match
      case Address.Kind.Shelley(_, _, _, Some(ptr)) => Some(ptr.slot)
      case _                                        => None)
    // 100001 groups of 1: the top group sits at bit 7 * 100000
    assertEquals(slot.map(_.bitLength), Some(7 * 100000 + 1))
    assert(millis < 1000, s"took $millis ms")
  }

  test("pointer naturals equal the shift-and-add of their 7-bit groups, at every length") {
    val random = new scala.util.Random(42)
    (1 to 70).foreach { n =>
      val groups = Vector.fill(n)(random.nextInt(128))
      val encoded = groups.zipWithIndex.map((g, i) => if i < n - 1 then g | 0x80 else g)
      val expected = groups.foldLeft(BigInt(0))((acc, g) => (acc << 7) + g)
      val decoded = Address.decode(pointer(bytes(encoded*))).map(_.kind)
      assertEquals(
        decoded,
        Right(Address.Kind.Shelley(0, Credential.Key(hash), None, Some(Ptr(expected, 1, 2)))),
        s"$n groups"
      )
    }
  }

  test("Plutus data nested beyond the stack is an error value, not a StackOverflowError") {
    // 1000 maps nested as values {0: {0: ... 0}}, within PlutusData's depth limit of 1024; a 64 KiB stack
    // holds neither the decoder nor the conversion of that
    val nested = hex("a100" * 1000 + "00")
    val result = onSmallStack(PlutusData.decode(nested), 64 * 1024)
    assertEquals(result.left.toOption, Some(Cbor.TooDeep))
  }
