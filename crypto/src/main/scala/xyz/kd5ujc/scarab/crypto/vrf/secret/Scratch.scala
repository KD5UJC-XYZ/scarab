// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf.secret

import java.util.Arrays
import scala.util.Using
import scala.util.Using.Releasable
import xyz.kd5ujc.scarab.codec.Bytes

/** Working storage for one computation on secret material. Every array it hands out is zeroed when
  * the computation ends, whether it returns or throws: [[Scratch.within]] is the only way to get a
  * scratch, and it erases on the way out (`scala.util.Using`, which releases on any `Throwable` and
  * then rethrows it).
  *
  * What the types guarantee: a buffer is a [[Buffer]] of its own scratch, a type member that names
  * that scratch, and [[Scratch.within]] returns only a value with a [[Public]] instance, which no
  * buffer, array or function has. A buffer, or a function that would read one later, cannot be the
  * result. Main code has no `var` and no mutable collection to keep one in (WartRemover; an array
  * of arrays would still do, which review refuses), and every method refuses once the scratch has
  * ended, so a use after the erasure is a defect that throws rather than a read of zeros.
  *
  * What they do not: the JVM may have copied an array before it is zeroed (a moving collector
  * evacuates live objects and does not clear the space they left), the JIT may have kept values in
  * registers or spilled them to the stack, and the curve library's own intermediates are immutable
  * objects that no scratch holds. The erasure is of the arrays here, best effort for the process's
  * memory.
  *
  * A scratch belongs to the thread that runs its computation and is not shared.
  */
private[vrf] final class Scratch private (observe: List[Array[Byte]] => Unit):
  /** A buffer of this scratch, which only this scratch reads and writes. */
  opaque type Buffer = Array[Byte]

  // FUN-2 (d): the arrays handed out, zeroed in place when the scratch ends, after which there are
  // none; the state of one computation on one thread
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var held: Option[List[Array[Byte]]] = Some(Nil)

  /** A new buffer of `size` zero bytes. */
  def buffer(size: Int): Buffer = kept(new Array[Byte](size))

  /** Keep `array`, made for this computation by a call that returned it (the encoding of a point
    * the curve library computed from a secret), to be zeroed with the scratch. No other reference
    * to it may outlive the computation.
    */
  def adopt(array: Array[Byte]): Buffer = kept(array)

  /** The storage of `b`, for a call that reads or writes it in place. */
  def array(b: Buffer): Array[Byte] =
    live()
    b

  /** The size of `b` in bytes. */
  def size(b: Buffer): Int =
    live()
    b.length

  /** A copy of `length` bytes of `b` from `from`, as a value that leaves the scratch: what the
    * computation publishes. The result's own array is the only copy made outside the scratch: the
    * range is cut into a buffer of this scratch, zeroed with the rest, and `Bytes.fromArray` copies
    * that once into the array the result owns.
    */
  def publish(b: Buffer, from: Int, length: Int): Bytes =
    val part = buffer(length)
    System.arraycopy(array(b), from, part, 0, length)
    Bytes.fromArray(part)

  /** A copy of all of `b` that leaves the scratch. */
  def publish(b: Buffer): Bytes = publish(b, 0, b.length)

  private def kept(array: Array[Byte]): Array[Byte] =
    live()
    held = held.map(array :: _)
    array

  // a use after the end is a defect of the caller: no buffer of an ended scratch is handed out
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def live(): Unit =
    if held.isEmpty then
      throw new IllegalStateException("the scratch has ended and its buffers are erased")

  // the observer sees the buffers before they are zeroed; the zeroing runs whether it returns or
  // throws, as the release of the buffers it is handed
  private def release(): Unit =
    val arrays = held.getOrElse(Nil)
    held = None
    Using.resource(arrays)(observe)(using Scratch.wiped)

private[vrf] object Scratch:
  private given Releasable[Scratch] = _.release()

  /** Zeroing the arrays a scratch held is their release. */
  private val wiped: Releasable[List[Array[Byte]]] = _.foreach(Arrays.fill(_, 0.toByte))

  /** Run `body` with a new scratch and zero every buffer it made or adopted once `body` returns or
    * throws; an exception is rethrown after the erasure.
    */
  def within[A: Public](body: Scratch => A): A = observed(_ => ())(body)

  /** [[within]] that hands `before` every buffer just before they are zeroed, on success and on
    * failure, so that a suite of this package can see what a computation held and that it is gone.
    * Main code calls it only through [[within]].
    */
  private[secret] def observed[A: Public](before: List[Array[Byte]] => Unit)(
      body: Scratch => A
  ): A = Using.resource(new Scratch(before))(body)
