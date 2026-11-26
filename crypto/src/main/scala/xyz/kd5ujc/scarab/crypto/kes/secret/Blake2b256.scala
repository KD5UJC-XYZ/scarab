// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.kes.secret

import java.nio.{ByteBuffer, ByteOrder}
import java.util.Arrays
import xyz.kd5ujc.scarab.crypto.secret.Scratch

/** Blake2b with a 32-byte digest and no key (RFC 7693), the hash of cardano-base's `SumKES`
  * (`Blake2b_256`), computed in a [[Scratch]]: the chained state, the working vector and the
  * message block are buffers of the scratch, so a seed it hashes is copied nowhere but storage that
  * is zeroed when the computation ends. Bouncy Castle's digest, which the KES key used before,
  * copies each block it compresses into an array of its own that it does not clear.
  *
  * The words are read and written in place through little-endian views of the buffers. The
  * computation is additions, rotations and exclusive ors of words, with no branch and no index that
  * depends on the input's bytes; the number of blocks depends on its length alone.
  */
private[kes] object Blake2b256:
  val DigestBytes: Int = 32

  private val BlockBytes = 128
  private val Rounds = 12

  private val Iv: Vector[Long] = Vector(
    0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
    0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L
  )

  /** The message schedule of each round (RFC 7693 section 2.7); rounds 10 and 11 repeat 0 and 1. */
  private val Sigma: Vector[Vector[Int]] = Vector(
    Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
    Vector(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
    Vector(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
    Vector(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
    Vector(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
    Vector(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
    Vector(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
    Vector(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
    Vector(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
    Vector(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0)
  )

  /** Blake2b-256 of the `length` bytes of `input` from `from`, into `out` at `at`. An empty input
    * is one block of zeros, as the RFC pads it.
    */
  def hash(
      s: Scratch
  )(input: Array[Byte], from: Int, length: Int, out: Array[Byte], at: Int): Unit =
    val h = s.buffer(64)
    val v = s.buffer(128)
    val block = s.buffer(BlockBytes)
    val state = words(s.array(h))
    Iv.indices.foreach(i => state.putLong(8 * i, Iv(i)))
    // the parameter block: digest length 32, no key, fanout 1, depth 1
    state.putLong(0, Iv(0) ^ 0x01010000L ^ DigestBytes.toLong)
    val blocks = math.max(1, (length + BlockBytes - 1) / BlockBytes)
    (0 until blocks).foreach { i =>
      val start = i * BlockBytes
      val n = math.min(BlockBytes, length - start)
      Arrays.fill(s.array(block), 0.toByte)
      System.arraycopy(input, from + start, s.array(block), 0, n)
      compress(state, words(s.array(v)), words(s.array(block)), (start + n).toLong, i == blocks - 1)
    }
    System.arraycopy(s.array(h), 0, out, at, DigestBytes)

  private def words(buffer: Array[Byte]): ByteBuffer =
    ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

  /** The compression function F (RFC 7693 section 3.2) of the block `m`, with `t` bytes counted so
    * far (below 2^64, so the counter's high word stays zero), into the chained state `h`.
    */
  private def compress(h: ByteBuffer, v: ByteBuffer, m: ByteBuffer, t: Long, last: Boolean): Unit =
    (0 until 8).foreach { i =>
      v.putLong(8 * i, h.getLong(8 * i))
      v.putLong(8 * (i + 8), Iv(i))
    }
    v.putLong(8 * 12, v.getLong(8 * 12) ^ t)
    if last then v.putLong(8 * 14, ~v.getLong(8 * 14))
    (0 until Rounds).foreach { r =>
      val schedule = Sigma(r % 10)
      def word(i: Int): Long = m.getLong(8 * schedule(i))
      mix(v, 0, 4, 8, 12, word(0), word(1))
      mix(v, 1, 5, 9, 13, word(2), word(3))
      mix(v, 2, 6, 10, 14, word(4), word(5))
      mix(v, 3, 7, 11, 15, word(6), word(7))
      mix(v, 0, 5, 10, 15, word(8), word(9))
      mix(v, 1, 6, 11, 12, word(10), word(11))
      mix(v, 2, 7, 8, 13, word(12), word(13))
      mix(v, 3, 4, 9, 14, word(14), word(15))
    }
    (0 until 8).foreach(i =>
      h.putLong(8 * i, h.getLong(8 * i) ^ v.getLong(8 * i) ^ v.getLong(8 * (i + 8)))
    )

  /** The mixing function G (RFC 7693 section 3.1) on the words `a`, `b`, `c` and `d` of `v`. */
  private def mix(v: ByteBuffer, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long): Unit =
    def update(i: Int, value: Long): Unit = v.putLong(8 * i, value)
    def at(i: Int): Long = v.getLong(8 * i)
    update(a, at(a) + at(b) + x)
    update(d, java.lang.Long.rotateRight(at(d) ^ at(a), 32))
    update(c, at(c) + at(d))
    update(b, java.lang.Long.rotateRight(at(b) ^ at(c), 24))
    update(a, at(a) + at(b) + y)
    update(d, java.lang.Long.rotateRight(at(d) ^ at(a), 16))
    update(c, at(c) + at(d))
    update(b, java.lang.Long.rotateRight(at(b) ^ at(c), 63))
