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
    def loop(at: Int, total: Int): Boolean = raw.lift(at) match
      case None => false
      case Some(b) =>
        val head = b & 0xff
        val info = head & 31
        if head == 0xff then at + 1 == raw.size && total == expected
        else if head >> 5 != 2 || info > 27 then false
        else
          val width = if info < 24 then 0 else 1 << (info - 24)
          // none when the argument runs past the end; eight bytes above 2^63 read as negative
          raw.bigEndian(at + 1, width) match
            case None => false
            case Some(argument) =>
              val size = if width == 0 then info.toLong else argument
              size >= 0 && size <= limit && size <= expected - total &&
              size <= raw.size - at - 1 - width &&
              loop(at + 1 + width + size.toInt, total + size.toInt)
    raw.headOption.contains(0x5f.toByte) && loop(1, 0)
