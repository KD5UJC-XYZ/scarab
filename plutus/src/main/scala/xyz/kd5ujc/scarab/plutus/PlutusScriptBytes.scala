// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** The shape of a serialised Plutus script on chain: one definite-length CBOR byte string around
  * the flat encoding of the program. The reference `deserialiseScript` decodes that item and, for
  * V1 and V2 only, ignores whatever follows it; it refuses an indefinite-length (chunked) byte
  * string, since its decoder asks cborg for definite bytes (`ExpectedBytes`; probe
  * `outer-indefinite-bytes` in `fixtures/reference/plutus-flat-decode`). Scalus's `fromCbor`
  * rejects the trailing input, so the item is cut out first.
  */
object PlutusScriptBytes:
  /** The length of the leading definite-length CBOR byte string of `bytes`, or the whole length
    * when the input does not start with a well-framed one (an indefinite-length string among them),
    * so the decoder reports its own error.
    */
  def cborItemLength(bytes: Array[Byte]): Int =
    length(bytes, 0)
      .collect { case (n, from) if from.toLong + n <= bytes.length => from + n }
      .getOrElse(bytes.length)

  /** The content of the leading definite-length byte string: the flat encoding of the program.
    * `None` when `bytes` do not start with a well-framed one.
    */
  def flatOf(bytes: Array[Byte]): Option[Array[Byte]] =
    length(bytes, 0).collect {
      case (n, from) if from.toLong + n <= bytes.length => bytes.slice(from, from + n)
    }

  /** The length of a definite-length byte string whose head is at `at`, and the offset of its
    * content; `None` for another major type, an indefinite length or a head that is cut short.
    */
  private def length(bytes: Array[Byte], at: Int): Option[(Int, Int)] =
    if at >= bytes.length || (bytes(at) & 0xe0) != 0x40 then None
    else
      val info = bytes(at) & 0x1f
      if info < 24 then Some((info, at + 1))
      else
        val width = info match
          case 24 => 1
          case 25 => 2
          case 26 => 4
          case 27 => 8
          case _  => 0
        Option
          .when(width > 0 && at + 1 + width <= bytes.length) {
            (0 until width).foldLeft(0L)((acc, i) => (acc << 8) | (bytes(at + 1 + i) & 0xffL))
          }
          .filter(n => n >= 0 && n <= Int.MaxValue)
          .map(n => (n.toInt, at + 1 + width))
