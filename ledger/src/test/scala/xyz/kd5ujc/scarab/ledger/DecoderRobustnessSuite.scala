// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import scala.annotation.unused
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.PlutusData
import munit.FunSuite

/** Regressions from fuzzing the decoders (roadmap K3, docs/findings.md): each input is the
  * minimised form of what the fuzzer found.
  */
class DecoderRobustnessSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)
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

  test("a pointer address with 100001 continuation groups preserves Word64 wrapping") {
    // header 0x40 (pointer address, key payment), a 28-byte hash, then 100000 bytes 0x81 and a last byte
    val result = Address.decode(pointer(filled(100000, 0x81) ++ bytes(0x01)))
    // 100001 groups of 1 wrap at 64 bits to a value of the form 0x...0101...: the top bits are
    // lost, what is left does not fit a Word32, so the pointer is Ptr 0 0 0 (B29)
    assertEquals(result.map(_.kind), Right(shelleyPointer(Ptr.zero)))
  }

  private def shelleyPointer(ptr: Ptr): Address.Kind =
    Address.Kind.Shelley(0, Credential.Key(hash), None, Some(ptr))

  test("pointer naturals equal the shift-and-add of their 7-bit groups wrapped at 64 bits") {
    val random = new scala.util.Random(42)
    val modulus = BigInt(1) << 64
    (1 to 70).foreach { n =>
      val groups = Vector.fill(n)(random.nextInt(128))
      val encoded = groups.zipWithIndex.map((g, i) => if i < n - 1 then g | 0x80 else g)
      val expected = groups.foldLeft(BigInt(0))((acc, g) => (acc << 7) + g).mod(modulus)
      val decoded = Address.decode(pointer(bytes(encoded*))).map(_.kind)
      assertEquals(
        decoded,
        Right(shelleyPointer(Ptr(expected, 1, 2).normalised)),
        s"$n groups"
      )
    }
  }

  test("regression (fuzz K3): Plutus data nested beyond the stack decodes, on a small stack") {
    // 1000 maps nested as values {0: {0: ... 0}}: a 64 KiB stack held neither the recursive
    // decoder nor the conversion; the nesting is now on the heap, as deep as a block allows
    val nested = hex("a100" * 1000 + "00")
    assert(onSmallStack(PlutusData.decode(nested), 64 * 1024).isRight)
    val deeper = hex("a100" * 40000 + "00")
    assert(onSmallStack(PlutusData.decode(deeper), 64 * 1024).isRight)
  }
