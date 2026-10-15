// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** The shape of a serialised Plutus script on chain: one CBOR byte string around the flat encoding
  * of the program. The reference `deserialiseScript` decodes that item and, for V1 and V2 only,
  * ignores whatever follows it; Scalus's `fromCbor` rejects the trailing input, so the item is cut
  * out first.
  */
object PlutusScriptBytes:
  /** The length of the leading CBOR byte string (definite or indefinite) of `bytes`, or the whole
    * length when the input does not start with a well-framed one, so the decoder reports its own
    * error.
    */
  def cborItemLength(bytes: Array[Byte]): Int =
    def head(at: Int): Option[(Int, Long, Int)] = // major type, argument, next offset
      if at >= bytes.length then None
      else
        val b = bytes(at) & 0xff
        val major = b >> 5
        val info = b & 0x1f
        val width = info match
          case 24 => 1
          case 25 => 2
          case 26 => 4
          case 27 => 8
          case _  => 0
        if info > 27 && info != 31 then None
        else if at + 1 + width > bytes.length then None
        else if info < 24 then Some((major, info.toLong, at + 1))
        else if info == 31 then Some((major, -1L, at + 1))
        else
          val v = (0 until width).foldLeft(0L)((acc, i) => (acc << 8) | (bytes(at + 1 + i) & 0xffL))
          if v < 0 then None else Some((major, v, at + 1 + width))
    def chunk(at: Int): Option[Int] = // end offset of a definite byte string chunk
      head(at).collect { case (2, n, next) if n >= 0 && next + n <= bytes.length => next + n.toInt }
    val end = head(0) match
      case Some((2, n, next)) if n >= 0 => Option.when(next + n <= bytes.length)(next + n.toInt)
      case Some((2, -1L, next)) =>
        Iterator
          .iterate(Option(next))(_.flatMap(at => chunk(at)))
          .takeWhile(_.isDefined)
          .flatten
          .find(at => at < bytes.length && (bytes(at) & 0xff) == 0xff)
          .map(_ + 1)
      case _ => None
    end.getOrElse(bytes.length)
