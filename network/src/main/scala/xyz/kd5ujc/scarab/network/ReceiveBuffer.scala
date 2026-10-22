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
private[network] final class ReceiveBuffer:
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
    val needed = live.toLong + more.size.toLong
    if needed > ReceiveBuffer.MaxArray then false
    else
      if end + more.size > array.length then
        val wanted = math.max(math.max(needed, 2L * live), ReceiveBuffer.MinCapacity.toLong)
        val grown = new Array[Byte](math.min(ReceiveBuffer.MaxArray, wanted).toInt)
        System.arraycopy(array, start, grown, 0, live)
        array = grown
        start = 0
        end = live
      more.copyTo(array, end)
      end += more.size
      live = end - start
      true

  /** Drop the first `n` bytes (at most [[size]]). */
  def consume(n: Int): Unit =
    start += math.min(n, live)
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
