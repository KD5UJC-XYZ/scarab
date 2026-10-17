// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.Bytes

/** A small append-only CBOR writer for query results. Heads use the shortest width, containers are
  * definite unless the caller opens an indefinite one (the ledger encodes some lists that way), and
  * `raw` splices already encoded items. The result is a pure value; the mutable buffer never
  * escapes [[CborOut.build]].
  */
final class CborOut private ():
  private val out = new java.io.ByteArrayOutputStream()

  private def byte(b: Int): Unit = out.write(b)

  def head(major: Int, n: BigInt): CborOut =
    require(n >= 0 && n.bitLength <= 64, "CBOR argument outside uint64")
    val m = major << 5
    if n < 24 then byte(m | n.toInt)
    else if n < 256 then { byte(m | 24); byte(n.toInt) }
    else if n < 65536 then { byte(m | 25); byte((n >> 8).toInt); byte(n.toInt & 255) }
    else if n < BigInt(4294967296L) then
      byte(m | 26)
      (3 to 0 by -1).foreach(i => byte(((n >> (8 * i)) & 255).toInt))
    else
      byte(m | 27)
      (7 to 0 by -1).foreach(i => byte(((n >> (8 * i)) & 255).toInt))
    this

  def uint(n: BigInt): CborOut = head(0, n)

  /** An unsigned `Integer` as cborg's `encodeInteger` writes it: an integer up to 64 bits, beyond
    * that a tag-2 big number (the bytes big-endian, without leading zeros). The era history's
    * times, in picoseconds, pass 2^64 about 213 days after the system start.
    */
  def integer(n: BigInt): CborOut =
    if n.bitLength <= 64 then uint(n)
    else tag(2).bytes(Bytes.fromArray(n.toByteArray.dropWhile(_ == 0)))

  /** A signed integer. */
  def int(n: BigInt): CborOut = if n >= 0 then head(0, n) else head(1, -1 - n)

  def bytes(b: Bytes): CborOut = { head(2, b.size); b.toArray.foreach(x => byte(x & 255)); this }
  def text(s: String): CborOut =
    val a = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    head(3, a.length); a.foreach(x => byte(x & 255)); this

  def array(n: Int): CborOut = head(4, n)
  def map(n: Int): CborOut = head(5, n)
  def tag(n: Int): CborOut = head(6, n)
  def bool(b: Boolean): CborOut = { byte(if b then 0xf5 else 0xf4); this }
  def nul: CborOut = { byte(0xf6); this }
  def indefiniteArray: CborOut = { byte(0x9f); this }
  def indefiniteMap: CborOut = { byte(0xbf); this }
  def break: CborOut = { byte(0xff); this }
  def raw(b: Bytes): CborOut = { b.toArray.foreach(x => byte(x & 255)); this }
  def double(d: Double): CborOut =
    byte(0xfb)
    val bits = java.lang.Double.doubleToLongBits(d)
    (7 to 0 by -1).foreach(i => byte(((bits >> (8 * i)) & 255).toInt))
    this

  /** A rational as the ledger encodes it: tag 30 and `[numerator, denominator]`. */
  def rational(numerator: BigInt, denominator: BigInt): CborOut =
    tag(30).array(2).int(numerator).uint(denominator)

  /** A set as the ledger encodes it from protocol version 9: tag 258 and a definite array. */
  def set(n: Int): CborOut = tag(258).array(n)

  def result: Bytes = Bytes.fromArray(out.toByteArray)

object CborOut:
  /** Run `body` against a fresh writer and return what it wrote. */
  def build(body: CborOut => Unit): Bytes =
    val w = new CborOut
    body(w)
    w.result

  /** Encode a sequence of items with `array(n)` framing. */
  def array[A](items: Iterable[A])(each: (CborOut, A) => Unit): Bytes =
    build { w =>
      w.array(items.size)
      items.foreach(each(w, _))
    }
