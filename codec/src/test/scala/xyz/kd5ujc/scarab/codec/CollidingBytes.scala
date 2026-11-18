// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

/** Byte strings that share one `Bytes.hashCode`, for the suites that check a cost does not depend
  * on hash codes. The hash is the polynomial `31 * h + b` over the signed bytes from 1, so strings
  * of one length whose polynomial from 0 is exactly 0 share it, and so do `prefix ++ s` for each of
  * them under any one prefix.
  */
object CollidingBytes:
  /** The first `n` strings of `length` bytes (at most 9, so that the sums stay inside a `Long`)
    * whose polynomial is 0. Seven bytes give over 300,000 of them, six over 9,000.
    */
  def of(n: Int, length: Int): Vector[Bytes] =
    val power = Vector.iterate(1L, length + 1)(_ * 31)
    // the most the remaining `k` bytes can add or take away, in units of the byte range
    val reach = power.map(p => (p - 1) / 30)
    def from(at: Int, sum: Long): LazyList[List[Byte]] =
      if at == length then if sum == 0 then LazyList(Nil) else LazyList.empty
      else
        val k = length - at - 1
        LazyList
          .range(-128, 128)
          .filter { b =>
            val scaled = (sum * 31 + b) * power(k)
            scaled >= -(reach(k) * 127) && scaled <= reach(k) * 128
          }
          .flatMap(b => from(at + 1, sum * 31 + b).map(b.toByte :: _))
    from(0, 0L).take(n).map(bs => Bytes(bs.toVector)).toVector

  /** `n` distinct strings of `length` bytes from a seeded generator, the control for [[of]]. */
  def random(n: Int, length: Int, seed: Long = 42): Vector[Bytes] =
    val r = new scala.util.Random(seed)
    LazyList
      .continually(Bytes(Vector.fill(length)(r.nextInt(256).toByte)))
      .distinct
      .take(n)
      .toVector
