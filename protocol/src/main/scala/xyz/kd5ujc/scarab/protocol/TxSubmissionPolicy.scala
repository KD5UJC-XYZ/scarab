// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes
import scala.concurrent.duration.*

/** Reference request bounds, independent of the mempool and the wire's local eight-body profile.
  * ouroboros-network 2fb69829, TxSubmission/Outbound.hs:107-173,185-208 and
  * TxSubmission/Inbound/V2/Policy.hs:94; protocol TxSubmission2/Codec.hs:50-93.
  */
object TxSubmissionPolicy:
  val MaxUnacknowledged = 10
  val ShortWait: FiniteDuration = 10.seconds

  enum Violation:
    case Counts(acknowledged: Int, requested: Int)
    case Acknowledgement(acknowledged: Int, outstanding: Int)
    case Outstanding(retained: Int, requested: Int)
    case EmptyRequest
    case BlockingWithOutstanding(retained: Int)
    case NonBlockingWithoutOutstanding
    case DuplicateBody(id: Bytes)
    case UnadvertisedBody(id: Bytes)
    case AlreadyRequested(id: Bytes)
    case ExcessOffers(received: Int, requested: Int)
    case DuplicateOffer

    def message: String = this match
      case Counts(a, r)               => s"counts outside uint16: acknowledged $a, requested $r"
      case Acknowledgement(a, n)      => s"acknowledged $a with $n outstanding"
      case Outstanding(n, r)          => s"$n retained plus $r requested exceeds $MaxUnacknowledged"
      case EmptyRequest               => "requested and acknowledged nothing"
      case BlockingWithOutstanding(n) => s"blocking request with $n outstanding"
      case NonBlockingWithoutOutstanding => "non-blocking request without outstanding ids"
      case DuplicateBody(id)             => s"duplicate body request ${id.hex}"
      case UnadvertisedBody(id)          => s"unadvertised body request ${id.hex}"
      case AlreadyRequested(id) => s"body request ${id.hex} beyond its outstanding advertisements"
      case ExcessOffers(n, r)   => s"received $n offers after requesting $r"
      case DuplicateOffer       => "duplicate offered transaction id"

  /** One advertisement of the unacknowledged window, oldest first: the id offered and whether a
    * `MsgRequestTxs` has asked for it.
    */
  final case class Advertised[I](id: I, requested: Boolean = false)

  /** Check the peer's requested credit before looking up any transactions. Even an empty mempool
    * does not permit a request larger than the finite outstanding window. Acknowledgement drops the
    * oldest `ack` advertisements, and with them whether they were asked for.
    */
  def acknowledge[A](
      unacked: Vector[A],
      blocking: Boolean,
      ack: Int,
      request: Int
  ): Either[Violation, Vector[A]] =
    val retained = unacked.drop(ack)
    if ack < 0 || ack > 65535 || request < 0 || request > 65535 then
      Left(Violation.Counts(ack, request))
    else if ack > unacked.size then Left(Violation.Acknowledgement(ack, unacked.size))
    else if retained.size + request > MaxUnacknowledged then
      Left(Violation.Outstanding(retained.size, request))
    else if request == 0 && (blocking || ack == 0) then Left(Violation.EmptyRequest)
    else if blocking && retained.nonEmpty then
      Left(Violation.BlockingWithOutstanding(retained.size))
    else if !blocking && retained.isEmpty then Left(Violation.NonBlockingWithoutOutstanding)
    else Right(retained)

  /** A `MsgRequestTxs` may name only ids that are outstanding and were not already asked for
    * (network specification, `network-spec/miniprotocols.tex` 1392-1393 at ouroboros-network
    * c45735a5), each once (1396-1398 at 2fb69829). An id offered more than once in the window is
    * outstanding once per advertisement: a request takes the oldest advertisement of the id not yet
    * asked for, and asking for an id whose advertisements have all been asked for is
    * [[Violation.AlreadyRequested]]. So the bodies served between acknowledgements are at most the
    * advertisements of the window.
    *
    * A duplicate id is refused ([[Violation.DuplicateBody]]) and the connection ends, as
    * `ProtocolErrorDuplicateTxIds` of ouroboros-network `3e6802b18` (PR #5435, in 2fb69829,
    * `TxSubmission/Outbound.hs:185-209`) and the network specification's `MsgRequestTxs` ("MUST NOT
    * contain duplicates") require. ouroboros-network c45735a5, which cardano-node 11.1.3 links,
    * sends the transaction, while it is still in the mempool, once for each time the request names
    * it (`TxSubmission/Outbound.hs:171-193`). An approved deviation (`docs/decisions.md`, #956,
    * 2026-11-14): no 11.1.3 peer names an id twice, as its requests are maps from id to size.
    *
    * The reference's receivers ask for an id again only after it was advertised again: V1 when it
    * is no longer in its own unacknowledged sequence (`Inbound/V1.hs` 365-375), V2 when an
    * advertisement puts it back among those available (`Inbound/V2/State.hs` 1040-1043, 1446-1453;
    * the duplicate is expected, 370-375), at ouroboros-network c45735a5.
    *
    * @return
    *   The window with the advertisements asked for marked.
    */
  def bodies(
      unacked: Vector[Advertised[Bytes]],
      ids: Vector[Bytes]
  ): Either[Violation, Vector[Advertised[Bytes]]] =
    ids
      .foldLeft[Either[Violation, (Set[Bytes], Vector[Advertised[Bytes]])]](
        Right((Set.empty, unacked))
      ) { (checked, id) =>
        checked.flatMap { (seen, window) =>
          if seen(id) then Left(Violation.DuplicateBody(id))
          else if !window.exists(_.id == id) then Left(Violation.UnadvertisedBody(id))
          else
            window.indexWhere(a => a.id == id && !a.requested) match
              case -1 => Left(Violation.AlreadyRequested(id))
              case at => Right((seen + id, window.updated(at, Advertised(id, requested = true))))
        }
      }
      .map(_._2)

  /** The receiver's byte limit for a message in state `StTxs`: `largeByteLimit`
    * (`byteLimitsTxSubmission2`, ouroboros-network c45735a5
    * `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/TxSubmission2/Codec.hs:47-58`;
    * `ouroboros-network/api/lib/Ouroboros/Network/Protocol/Limits.hs:105-106`).
    */
  val ReplyByteLimit: Long = 2500000L

  /** `MsgReplyTxs` around its transactions: the array of two, the tag, and the indefinite list's
    * start and break.
    */
  private val ReplyFraming = 4L

  /** How many of the bodies of one reply, of the encoded `sizes` in request order, it carries: the
    * longest prefix whose reply stays within [[ReplyByteLimit]], so a reply is never one an honest
    * receiver refuses. A body left out is one the reply omits, as one no longer held is omitted.
    *
    * The bound cannot be reached today and is kept as defence in depth. The bodies of one reply are
    * those of distinct ids of the unacknowledged window, so at most [[MaxUnacknowledged]] (10),
    * each at most `TxSubmission2.MaxOriginalBytes` (65,536) bytes, 65,545 encoded (a held
    * transaction is also no larger than the protocol's maximum transaction size): 655,454 bytes at
    * most. The codec reads at most `TxSubmission2.MaxBatch` (8) ids in a request, which makes
    * 524,364, within the local profile's own 524,400 for `StTxs` (`TxSubmission2.maxMessageBytes`).
    */
  def replyWithin(sizes: Vector[Long]): Int =
    sizes.scanLeft(ReplyFraming)(_ + _).drop(1).takeWhile(_ <= ReplyByteLimit).size

  def offers(ids: Vector[Bytes], requested: Int): Either[Violation, Unit] =
    if ids.size > requested then Left(Violation.ExcessOffers(ids.size, requested))
    else if ids.distinct.size != ids.size then Left(Violation.DuplicateOffer)
    else Right(())

  /** The reference's state deadlines, `timeLimitsTxSubmission2` (ouroboros-network c45735a5,
    * `Ouroboros/Network/Protocol/TxSubmission2/Codec.hs:78-88`): `StInit`, `StIdle` and blocking
    * `StTxIds` wait for ever, non-blocking `StTxIds` and `StTxs` have `shortWait`. Our server emits
    * only blocking ID requests, so its ID wait is indefinite.
    */
  def timeLimit(state: TxSubmission2.State): Option[FiniteDuration] = state match
    case TxSubmission2.State.Txs | TxSubmission2.State.Ids(false) => Some(ShortWait)
    case _                                                        => None
