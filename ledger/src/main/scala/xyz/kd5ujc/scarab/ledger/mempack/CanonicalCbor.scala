// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.mempack

import java.io.ByteArrayOutputStream
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Datum, ScriptKind, Value}

/** The CBOR the reference writes for an output it holds only in compact form (`EncCBOR
  * (BabbageTxOut era)`, `Babbage/TxOut.hs:312-318`; `EncCBOR MaryValue`, `Value.hs:342`): the
  * legacy array of two or three items for an output with neither inline datum nor script, else the
  * map with the keys 0 (address), 1 (value), 2 (datum option) and 3 (reference script, `#6.24`),
  * and a value as a bare coin or `[coin, {policy: {name: quantity}}]` with maps of more than 23
  * entries written indefinite (`variableMapLenEncoding`, `Encoder.hs:433`). A MemPack output has no
  * CBOR of its own; this is the one the node's [[xyz.kd5ujc.scarab.ledger.TxOut]] carries as its
  * `raw`, so that everything that reads `raw` (the sizes, the script, the address scan) works on
  * it.
  */
private[mempack] object CanonicalCbor:

  private final class Out:
    private val buf = new ByteArrayOutputStream(128)

    def byte(b: Int): Unit = buf.write(b)

    /** A head of `major` with the unsigned 64-bit argument `n` (a negative `Long` is above 2^63).
      */
    def head(major: Int, n: Long): Unit =
      val m = major << 5
      if n >= 0 && n < 24 then byte(m | n.toInt)
      else if n >= 0 && n < 0x100 then
        byte(m | 24)
        byte(n.toInt)
      else if n >= 0 && n < 0x10000 then
        byte(m | 25)
        be(n, 2)
      else if n >= 0 && n <= 0xffffffffL then
        byte(m | 26)
        be(n, 4)
      else
        byte(m | 27)
        be(n, 8)

    private def be(n: Long, bytes: Int): Unit =
      var k = bytes - 1
      while k >= 0 do
        byte(((n >>> (8 * k)) & 0xff).toInt)
        k -= 1

    def uint(v: BigInt): Unit = head(0, v.toLong)

    def bytes(b: Bytes): Unit =
      head(2, b.size.toLong)
      raw(b)

    def raw(b: Bytes): Unit = buf.write(b.unsafeArray, b.unsafeOffset, b.size)

    def mapStart(entries: Int): Unit = if entries <= 23 then head(5, entries.toLong) else byte(0xbf)

    def mapEnd(entries: Int): Unit = if entries > 23 then byte(0xff)

    def size: Int = buf.size
    def result: Bytes = Bytes.wrap(buf.toByteArray)

  /** The value: a bare coin when it has no assets. */
  def value(v: Value): Bytes =
    val out = new Out
    if v.assets.isEmpty then out.uint(v.coin)
    else
      out.byte(0x82)
      out.uint(v.coin)
      val policies = v.assets.toVector.sortBy(_._1)(using Ordering[Bytes])
      out.mapStart(policies.size)
      policies.foreach { (policy, names) =>
        out.bytes(policy)
        val sorted = names.toVector.sortBy(_._1)(using Ordering[Bytes])
        out.mapStart(sorted.size)
        sorted.foreach { (name, quantity) =>
          out.bytes(name)
          out.uint(quantity)
        }
        out.mapEnd(sorted.size)
      }
      out.mapEnd(policies.size)
    out.result

  /** The output with `valueCbor` as its value item. */
  def output(p: TxOutParts, valueCbor: Bytes): Bytes =
    val out = new Out
    (p.datum, p.script) match
      case (Datum.NoDatum, None) =>
        out.byte(0x82)
        out.bytes(p.address)
        out.raw(valueCbor)
      case (Datum.Hash(h), None) =>
        out.byte(0x83)
        out.bytes(p.address)
        out.raw(valueCbor)
        out.bytes(h)
      case (datum, script) =>
        val entries =
          2 + (if datum == Datum.NoDatum then 0 else 1) + (if script.isEmpty then 0 else 1)
        out.head(5, entries.toLong)
        out.head(0, 0)
        out.bytes(p.address)
        out.head(0, 1)
        out.raw(valueCbor)
        datum match
          case Datum.NoDatum => ()
          case Datum.Hash(h) =>
            out.head(0, 2)
            out.byte(0x82)
            out.head(0, 0)
            out.bytes(h)
          case Datum.Inline(d) =>
            out.head(0, 2)
            out.byte(0x82)
            out.head(0, 1)
            out.head(6, 24)
            out.bytes(d)
        script.foreach { s =>
          out.head(0, 3)
          out.head(6, 24)
          val inner = new Out
          inner.byte(0x82)
          inner.head(0, s.kind.tag.toLong)
          s.kind match
            case ScriptKind.Native => inner.raw(s.bytes)
            case _                 => inner.bytes(s.bytes)
          out.bytes(inner.result)
        }
    out.result
