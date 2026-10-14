// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.runtime

import java.nio.charset.StandardCharsets
import lab.cbor.Bytes as CborBytes

/** A canonical CBOR writer: shortest integer and length forms, definite-length containers. Total:
  * the model types guarantee ranges, so writing cannot fail.
  */
final class Writer(initial: Int = 256):
  private var buf = new Array[Byte](initial)
  private var len = 0

  private def ensure(extra: Int): Unit =
    if len + extra > buf.length then buf = java.util.Arrays.copyOf(buf, math.max(buf.length * 2, len + extra))

  private def put(b: Int): Unit =
    ensure(1)
    buf(len) = b.toByte
    len += 1

  def head(major: Int, n: Long): Unit =
    val m = major << 5
    if n < 0 then headBig(major, BigInt(n) + (BigInt(1) << 64)) // an unsigned 64-bit value held in a Long
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
