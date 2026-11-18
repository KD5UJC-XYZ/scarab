// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.codec.Bytes

/** The tokens at the start of `input`, a message's item not all of which has arrived, as cborg
  * reads them for a decoder of the reference that reads the item from the stream: a token is judged
  * only once its head, its initial byte and the bytes of its argument, has arrived whole. cborg's
  * source is not read here; cardano-node 11.1.3 waits on the `HasTx` prefixes `82 07 82 3b 00` and
  * `82 07 82 19 01` and ends the connection once the rest of that head has arrived (#924). Each
  * judge of a prefix reads its tokens here and says what it makes of each; `V` is its verdict and
  * `short` the one while a head has not arrived.
  */
private[node] final class PrefixTokens[V](input: Bytes, short: V):
  import PrefixTokens.Head

  /** The head at `at`, or `short` until it has arrived whole. */
  def head(at: Int): Either[V, Head] =
    input
      .lift(at)
      .flatMap { byte =>
        val initial = byte & 0xff
        val info = initial & 31
        val width = info match
          case 24 => 1
          case 25 => 2
          case 26 => 4
          case 27 => 8
          case _  => 0
        input
          .bigEndian(at + 1, width)
          .map(argument =>
            Head(initial >> 5, info, if width == 0 then info.toLong else argument, at + 1 + width)
          )
      }
      .toRight(short)

  /** cborg's `decodeListLen`: the head of a definite list, at any width; its length (below 2^63, as
    * cborg's `Int`) and where its first item starts, or `refused`.
    */
  def listLength(at: Int, refused: => V): Either[V, (Long, Int)] =
    head(at).flatMap(h =>
      Either.cond(h.major == 4 && h.info < 28 && h.argument >= 0, (h.argument, h.next), refused)
    )

  /** `enforceSize _ n`: `decodeListLen`, then the count; where the list's first item starts. */
  def listOf(n: Long, at: Int, refused: => V): Either[V, Int] =
    listLength(at, refused).flatMap((length, next) => Either.cond(length == n, next, refused))

  /** cborg's `decodeWord8`: an unsigned integer up to 255, at any width of its head; its value and
    * where the next token starts.
    */
  def word8(at: Int, refused: => V): Either[V, (Int, Int)] =
    head(at).flatMap(h =>
      Either.cond(
        h.major == 0 && h.info < 28 && h.argument >= 0 && h.argument <= 0xff,
        (h.argument.toInt, h.next),
        refused
      )
    )

private[node] object PrefixTokens:
  /** A head read whole: its major type, its additional information, its argument (that information
    * below 24; negative above 2^63 - 1) and where the next token starts.
    */
  final case class Head(major: Int, info: Int, argument: Long, next: Int)
