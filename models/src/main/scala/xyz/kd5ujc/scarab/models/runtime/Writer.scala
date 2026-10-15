// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.runtime

import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.codec.{Float16, Bytes as CborBytes}

/** A canonical CBOR writer: shortest integer and length forms, definite-length containers. Total:
  * the model types guarantee ranges, so writing cannot fail.
  *
  * The methods of the first group are the canonical ones every generated codec uses. The second
  * group is for the formats that are not canonical: indefinite-length containers, floats, and the
  * length policy of the Haskell ledger's binary layer. Nothing in the second group is reached by
  * the first, so a codec that does not call it is unaffected.
  */
final class Writer(initial: Int = 256):
  private var buf = new Array[Byte](initial)
  private var len = 0

  private def ensure(extra: Int): Unit =
    if len + extra > buf.length then
      buf = java.util.Arrays.copyOf(buf, math.max(buf.length * 2, len + extra))

  private def put(b: Int): Unit =
    ensure(1)
    buf(len) = b.toByte
    len += 1

  def head(major: Int, n: Long): Unit =
    val m = major << 5
    if n < 0 then
      headBig(major, BigInt(n) + (BigInt(1) << 64)) // an unsigned 64-bit value held in a Long
    else if n < 24 then put(m | n.toInt)
    else if n <= 0xffL then { put(m | 24); put(n.toInt) }
    else if n <= 0xffffL then { put(m | 25); put((n >> 8).toInt); put(n.toInt) }
    else if n <= 0xffffffffL then
      put(m | 26)
      var s = 24
      while s >= 0 do { put(((n >> s) & 0xff).toInt); s -= 8 }
    else
      put(m | 27)
      var s = 56
      while s >= 0 do { put(((n >> s) & 0xff).toInt); s -= 8 }

  def headBig(major: Int, n: BigInt): Unit =
    if n.isValidLong && n >= 0 then head(major, n.toLong)
    else
      put((major << 5) | 27)
      val arr = n.toByteArray.takeRight(8)
      var pad = 8 - arr.length
      while pad > 0 do { put(0); pad -= 1 }
      arr.foreach(b => put(b & 0xff))

  def arrayHead(n: Int): Unit = head(4, n.toLong)
  def mapHead(n: Int): Unit = head(5, n.toLong)
  def uint(n: Long): Unit = head(0, n)
  def uint(n: BigInt): Unit = headBig(0, n)

  /** A CBOR integer of either sign. */
  def int(n: BigInt): Unit = if n.signum >= 0 then headBig(0, n) else headBig(1, -1 - n)
  def tag(n: BigInt): Unit = headBig(6, n)
  def bool(b: Boolean): Unit = put(if b then 0xf5 else 0xf4)
  def nul(): Unit = put(0xf6)

  def bytes(b: CborBytes): Unit =
    head(2, b.size.toLong)
    raw(b)

  def text(s: String): Unit =
    val b = s.getBytes(StandardCharsets.UTF_8)
    head(3, b.length.toLong)
    ensure(b.length)
    System.arraycopy(b, 0, buf, len, b.length)
    len += b.length

  /** Append already-encoded CBOR (or any bytes) verbatim. */
  def raw(b: CborBytes): Unit =
    ensure(b.size)
    b.copyTo(buf, len)
    len += b.size

  def result: CborBytes = CborBytes.fromArray(java.util.Arrays.copyOf(buf, len))

  // ---- not canonical: indefinite containers, floats, the ledger's length policy ----------------

  /** The head of an indefinite-length array; the items follow, then [[break]]. */
  def indefiniteArray(): Unit = put(0x9f)

  /** The head of an indefinite-length map; the key-value pairs follow, then [[break]]. */
  def indefiniteMap(): Unit = put(0xbf)

  /** The break that closes an indefinite-length item. */
  def break(): Unit = put(0xff)

  /** A half-precision float (`0xf9`), as cborg's `encodeFloat16`: the nearest half to `f`. */
  def float16(f: Float): Unit =
    val bits = Float16.fromFloat(f)
    put(0xf9)
    put(bits >> 8)
    put(bits & 0xff)

  /** A single-precision float (`0xfa`), as cborg's `encodeFloat` and the ledger's `EncCBOR Float`:
    * always four bytes, never shrunk to a half.
    */
  def float32(f: Float): Unit =
    val bits = java.lang.Float.floatToRawIntBits(f)
    put(0xfa)
    var shift = 24
    while shift >= 0 do
      put((bits >>> shift) & 0xff)
      shift -= 8

  /** A double-precision float (`0xfb`), as cborg's `encodeDouble`: always eight bytes. */
  def float64(d: Double): Unit =
    val bits = java.lang.Double.doubleToRawLongBits(d)
    put(0xfb)
    var shift = 56
    while shift >= 0 do
      put(((bits >>> shift) & 0xff).toInt)
      shift -= 8

  /** An `Integer` of any size, as cborg's `encodeInteger`: a plain integer within 64 bits either
    * way, and beyond them a bignum (tag 2 for a positive and tag 3 for a negative `-1 - n`).
    */
  def integer(n: BigInt): Unit =
    val magnitude = if n.signum >= 0 then n else -1 - n
    if magnitude.bitLength <= 64 then int(n)
    else
      head(6, if n.signum >= 0 then 2L else 3L)
      val raw = magnitude.toByteArray
      val minimal = if raw.head == 0 then raw.tail else raw // the sign byte of a magnitude
      head(2, minimal.length.toLong)
      minimal.foreach(b => put(b & 0xff))

  /** An array of `n` items that `body` writes, framed as the ledger frames its lists, sets and
    * sequences (`variableListLenEncoding`, cardano-ledger-binary `Encoder.hs:458-468`): a definite
    * head up to [[Writer.LengthThreshold]] items, an indefinite one with its break above.
    */
  def variableArray(n: Int)(body: => Unit): Unit =
    if n <= Writer.LengthThreshold then
      arrayHead(n)
      body
    else
      indefiniteArray()
      body
      break()

  /** A map of `n` entries that `body` writes, framed as the ledger frames its maps
    * (`variableMapLenEncoding`, `Encoder.hs:430-440`).
    */
  def variableMap(n: Int)(body: => Unit): Unit =
    if n <= Writer.LengthThreshold then
      mapHead(n)
      body
    else
      indefiniteMap()
      body
      break()

object Writer:
  /** The ledger's `lengthThreshold` (`Encoder.hs:442-443`): above it a container is indefinite. */
  val LengthThreshold: Int = 23
