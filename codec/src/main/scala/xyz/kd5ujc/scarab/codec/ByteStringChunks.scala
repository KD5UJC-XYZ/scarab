// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

/** Inspect original definite chunk lengths after CBOR syntax validation, without imposing a bound
  * on the joined byte string. Short synthetic byte-string nodes need no original encoding;
  * malformed or absent evidence cannot establish that a longer value used bounded chunks.
  */
object ByteStringChunks:
  def within(node: Node, limit: Int): Boolean =
    limit >= 0 && (node.value match
      case Value.ByteString(value) =>
        value.size <= limit || chunksFit(node.original, limit, value.size)
      case _ => false)

  private def chunksFit(raw: Bytes, limit: Int, expected: Int): Boolean =
    @scala.annotation.tailrec
    def loop(at: Int, total: Int): Boolean =
      if at >= raw.size then false
      else
        val head = raw(at) & 0xff
        val info = head & 31
        if head == 0xff then at + 1 == raw.size && total == expected
        else if head >> 5 != 2 || info > 27 then false
        else
          val width = if info < 24 then 0 else 1 << (info - 24)
          if width > raw.size - at - 1 then false
          else
            val size = (0 until width).foldLeft(BigInt(if width == 0 then info else 0))((n, i) =>
              (n << 8) | (raw(at + 1 + i) & 0xff)
            )
            size <= limit && size <= expected - total && size <= raw.size - at - 1 - width &&
            loop(at + 1 + width + size.toInt, total + size.toInt)
    raw.nonEmpty && (raw(0) & 0xff) == 0x5f && loop(1, 0)
