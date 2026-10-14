// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes
import scala.collection.immutable.SortedMap
import ChainSync.{DecodeResult, Role}
import ProtocolWire.{attempt, bad, get}

/** Node-to-client LocalTxMonitor (mini-protocol 9): the client acquires a snapshot of the mempool
  * and inspects it (next transaction, membership, sizes, measures) while the node keeps its own
  * mempool moving. `tx` and `txId` are single CBOR items whose contents (the hard-fork-combinator
  * wrapped transaction and transaction id) belong to the caller; the slot is a plain unsigned
  * integer.
  *
  * States and agency (ouroboros-network `Protocol.LocalTxMonitor.Type`):
  *   - `Idle` (client): `Acquire` or `Done`;
  *   - `Acquiring` (server): `Acquired(slot)`;
  *   - `Acquired` (client): `AwaitAcquire`, `NextTx`, `HasTx`, `GetSizes`, `GetMeasures` or
  *     `Release`;
  *   - `Busy(kind)` (server): the one reply of that kind.
  *
  * `AwaitAcquire` has the same wire form as `Acquire` (`[1]`): the codec tells them apart by the
  * state, as the reference does. The reference codec answers `GetMeasures` on every version it
  * offers (`LocalTxMonitor_V2`), so this one does too.
  */
object LocalTxMonitor:
  val MiniProtocolId = 9
  val MaxMessageBytes = 1048576
  private val wire = ProtocolWire.Limits(MaxMessageBytes, MaxMessageBytes, 32, 64, 65535)
  private val MaxWord32 = BigInt(0xffffffffL)

  enum Kind:
    case NextTx, HasTx, GetSizes, GetMeasures

  enum State:
    case Idle, Acquiring, Acquired, Done
    case Busy(kind: Kind)

  /** `MempoolSizeAndCapacity`: each a Word32. */
  final case class Sizes(capacityInBytes: Long, sizeInBytes: Long, numberOfTxs: Long)

  /** One measure: the mempool's size and the capacity, in the measure's unit. */
  final case class Measure(size: BigInt, capacity: BigInt)

  /** `MempoolMeasures`: the transaction count and the measures by name (in name order, as the
    * reference's `Map` encodes them).
    */
  final case class Measures(txCount: Long, byName: SortedMap[String, Measure])

  enum Message:
    case Acquire
    case Acquired(slot: BigInt)
    case AwaitAcquire
    case NextTx
    case ReplyNextTx(tx: Option[Bytes])
    case HasTx(txId: Bytes)
    case ReplyHasTx(has: Boolean)
    case GetSizes
    case ReplyGetSizes(sizes: Sizes)
    case GetMeasures
    case ReplyGetMeasures(measures: Measures)
    case Release
    case Done

  def transition(state: State, sender: Role, message: Message): Either[String, State] =
    import State.Busy
    (state, sender, message) match
      case (State.Idle, Role.Client, Message.Acquire)               => Right(State.Acquiring)
      case (State.Idle, Role.Client, Message.Done)                  => Right(State.Done)
      case (State.Acquiring, Role.Server, Message.Acquired(_))      => Right(State.Acquired)
      case (State.Acquired, Role.Client, Message.AwaitAcquire)      => Right(State.Acquiring)
      case (State.Acquired, Role.Client, Message.NextTx)            => Right(Busy(Kind.NextTx))
      case (State.Acquired, Role.Client, Message.HasTx(_))          => Right(Busy(Kind.HasTx))
      case (State.Acquired, Role.Client, Message.GetSizes)          => Right(Busy(Kind.GetSizes))
      case (State.Acquired, Role.Client, Message.GetMeasures)       => Right(Busy(Kind.GetMeasures))
      case (State.Acquired, Role.Client, Message.Release)           => Right(State.Idle)
      case (Busy(Kind.NextTx), Role.Server, Message.ReplyNextTx(_)) => Right(State.Acquired)
      case (Busy(Kind.HasTx), Role.Server, Message.ReplyHasTx(_))   => Right(State.Acquired)
      case (Busy(Kind.GetSizes), Role.Server, Message.ReplyGetSizes(_)) => Right(State.Acquired)
      case (Busy(Kind.GetMeasures), Role.Server, Message.ReplyGetMeasures(_)) =>
        Right(State.Acquired)
      case _ => Left(s"illegal LocalTxMonitor message/agency in $state from $sender")

  private def clientTurn(state: State): Boolean = state == State.Idle || state == State.Acquired

  def encode(state: State, sender: Role, message: Message): Either[String, Bytes] = attempt {
    get(transition(state, sender, message))
    val w = new ProtocolWire.Writer(wire)
    def outer(n: Int, tag: Int): Unit = { w.head(4, n); w.head(0, tag) }
    def word32(name: String, value: Long): Unit =
      if value < 0 || value > 0xffffffffL then bad(s"$name does not fit a Word32")
      w.head(0, value)
    message match
      case Message.Done         => outer(1, 0)
      case Message.Acquire      => outer(1, 1)
      case Message.AwaitAcquire => outer(1, 1)
      case Message.Release      => outer(1, 3)
      case Message.NextTx       => outer(1, 5)
      case Message.HasTx(id) =>
        get(ProtocolWire.validateItem(id, wire)); outer(2, 7); w.raw(id)
      case Message.GetSizes    => outer(1, 9)
      case Message.GetMeasures => outer(1, 11)
      case Message.Acquired(slot) =>
        outer(2, 2); w.head(0, slot)
      case Message.ReplyNextTx(None) => outer(1, 6)
      case Message.ReplyNextTx(Some(tx)) =>
        get(ProtocolWire.validateItem(tx, wire)); outer(2, 6); w.raw(tx)
      case Message.ReplyHasTx(has) => outer(2, 8); w.bool(has)
      case Message.ReplyGetSizes(sizes) =>
        outer(2, 10)
        w.head(4, 3)
        word32("capacityInBytes", sizes.capacityInBytes)
        word32("sizeInBytes", sizes.sizeInBytes)
        word32("numberOfTxs", sizes.numberOfTxs)
      case Message.ReplyGetMeasures(measures) =>
        outer(3, 12)
        word32("txCount", measures.txCount)
        w.head(5, measures.byName.size)
        measures.byName.foreach { (name, m) =>
          w.text(name); w.head(4, 2); w.head(0, m.size); w.head(0, m.capacity)
        }
    w.result
  }

  /** The client's messages in `Idle` and `Acquired`, the server's in the other states. */
  def decodePrefix(state: State, sender: Role, bytes: Bytes): DecodeResult[Message] =
    try
      if state == State.Done || clientTurn(state) != (sender == Role.Client) then
        bad("wrong LocalTxMonitor agency")
      val r = new ProtocolWire.Reader(bytes, wire)
      val arity = r.arrayLength()
      val tag = r.uint().value
      def word32(name: String): Long =
        val v = r.uint().value
        if v > MaxWord32 then bad(s"$name does not fit a Word32")
        v.toLong
      val message: Message = (state, arity, tag.toInt) match
        case (State.Idle, 1, 0) if tag == 0              => Message.Done
        case (State.Idle, 1, 1) if tag == 1              => Message.Acquire
        case (State.Acquired, 1, 1) if tag == 1          => Message.AwaitAcquire
        case (State.Acquired, 1, 3) if tag == 3          => Message.Release
        case (State.Acquired, 1, 5) if tag == 5          => Message.NextTx
        case (State.Acquired, 2, 7) if tag == 7          => Message.HasTx(r.item())
        case (State.Acquired, 1, 9) if tag == 9          => Message.GetSizes
        case (State.Acquired, 1, 11) if tag == 11        => Message.GetMeasures
        case (State.Acquiring, 2, 2) if tag == 2         => Message.Acquired(r.uint().value)
        case (State.Busy(Kind.NextTx), 1, 6) if tag == 6 => Message.ReplyNextTx(None)
        case (State.Busy(Kind.NextTx), 2, 6) if tag == 6 => Message.ReplyNextTx(Some(r.item()))
        case (State.Busy(Kind.HasTx), 2, 8) if tag == 8  => Message.ReplyHasTx(r.bool())
        case (State.Busy(Kind.GetSizes), 2, 10) if tag == 10 =>
          if r.arrayLength() != 3 then bad("sizes arity")
          Message.ReplyGetSizes(
            Sizes(word32("capacityInBytes"), word32("sizeInBytes"), word32("numberOfTxs"))
          )
        case (State.Busy(Kind.GetMeasures), 3, 12) if tag == 12 =>
          val count = word32("txCount")
          val n = r.mapLength()
          val entries = (0 until n).toVector.map { _ =>
            val name = r.text(MaxMessageBytes)
            if r.arrayLength() != 2 then bad("measure arity")
            name -> Measure(r.uint().value, r.uint().value)
          }
          if entries.map(_._1).distinct.size != entries.size then bad("duplicate measure name")
          Message.ReplyGetMeasures(Measures(count, SortedMap.from(entries)))
        case _ => bad("unknown LocalTxMonitor tag or arity")
      get(transition(state, sender, message))
      DecodeResult.Decoded(message, r.position)
    catch
      case ProtocolWire.Failure(_, true)      => DecodeResult.NeedMore
      case ProtocolWire.Failure(error, false) => DecodeResult.Failed(error)
