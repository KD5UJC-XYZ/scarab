// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.codec.Bytes

/** The bytes a channel has received and not yet decoded, in one growing array: appending an SDU is
  * amortised constant time (the array doubles), and [[view]] shows the live bytes without a copy.
  * The previous representation concatenated the whole buffer with every SDU, which copies a message
  * of n bytes n / SDU times: a tens-of-megabytes node-to-client answer took seconds (issue 356,
  * `docs/performance.md` section 22).
  *
  * This is the performance-critical imperative exception of AGENTS.md: the mutation is local to one
  * channel, whose `recv` holds the channel lock around every use except [[size]], and the interface
  * the multiplexer sees is `append`, `view`, `consume` and `clear`. A [[view]] handed to a decoder,
  * and anything the decoder keeps of it, is never overwritten: bytes below `end` are never written
  * again, and the array is dropped (not reused) when the buffer empties or grows, so a decoded
  * value may keep a slice of it.
  */
@SuppressWarnings(Array("org.wartremover.warts.Var"))
private[network] final class ReceiveBuffer(limit: Long = ReceiveBuffer.MaxArray):
  private var array: Array[Byte] = ReceiveBuffer.NoBytes
  private var start = 0
  private var end = 0
  @volatile private var live = 0

  /** The bytes held; readable from any fiber. */
  def size: Int = live

  /** The live bytes, without a copy; valid for as long as the caller likes (see the class note). */
  def view: Bytes = if live == 0 then Bytes.empty else Bytes.wrap(array, start, live)

  /** Add `more`; false, with the buffer unchanged, when the total would not fit in one array. */
  def append(more: Bytes): Boolean =
    ReceiveBuffer.room(live, end, array.length, more.size, limit) match
      case ReceiveBuffer.Room.TooLarge => false
      case room =>
        room match
          case ReceiveBuffer.Room.Grow(capacity) =>
            val grown = new Array[Byte](capacity)
            System.arraycopy(array, start, grown, 0, live)
            array = grown
            start = 0
            end = live
          case _ => ()
        more.copyTo(array, end)
        end += more.size
        live = end - start
        true

  /** Drop the first `n` bytes (at most [[size]]). */
  def consume(n: Int): Unit =
    start += math.max(0, math.min(n, live))
    live = end - start
    if live == 0 then clear()

  def clear(): Unit =
    array = ReceiveBuffer.NoBytes
    start = 0
    end = 0
    live = 0

private object ReceiveBuffer:
  val NoBytes: Array[Byte] = new Array[Byte](0)
  val MinCapacity = 1024
  val MaxArray: Long = Int.MaxValue.toLong - 8

  /** What an append needs of the array. */
  enum Room:
    case Fits
    case Grow(capacity: Int)
    case TooLarge

  /** The capacity decision of `append`, pure and in `Long` arithmetic: `end + incoming` can exceed
    * `Int.MaxValue` after partial consumption (`end` far above `live`), where an `Int` sum wraps
    * negative and would skip the growth.
    *
    * @param live
    *   the bytes held
    * @param end
    *   the index after the last written byte
    * @param length
    *   the current array length
    * @param incoming
    *   the size of the bytes to add
    */
  def room(live: Int, end: Int, length: Int, incoming: Int, limit: Long = MaxArray): Room =
    val needed = live.toLong + incoming.toLong
    if needed > limit then Room.TooLarge
    else if end.toLong + incoming.toLong <= length.toLong then Room.Fits
    else
      val wanted = math.max(math.max(needed, 2L * live), MinCapacity.toLong)
      Room.Grow(math.min(limit, wanted).toInt)
