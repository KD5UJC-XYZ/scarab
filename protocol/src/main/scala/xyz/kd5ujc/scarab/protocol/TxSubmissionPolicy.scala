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
      case ExcessOffers(n, r)            => s"received $n offers after requesting $r"
      case DuplicateOffer                => "duplicate offered transaction id"

  /** Check the peer's requested credit before looking up any transactions. Even an empty mempool
    * does not permit a request larger than the finite outstanding window.
    */
  def acknowledge(
      unacked: Vector[Bytes],
      blocking: Boolean,
      ack: Int,
      request: Int
  ): Either[Violation, Vector[Bytes]] =
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

  /** Only this request must be distinct: a body may be requested again before acknowledgement. */
  def bodies(unacked: Vector[Bytes], ids: Vector[Bytes]): Either[Violation, Unit] =
    ids
      .foldLeft[Either[Violation, Set[Bytes]]](Right(Set.empty)) { (checked, id) =>
        checked.flatMap { seen =>
          if seen(id) then Left(Violation.DuplicateBody(id))
          else if !unacked.contains(id) then Left(Violation.UnadvertisedBody(id))
          else Right(seen + id)
        }
      }
      .map(_ => ())

  def offers(ids: Vector[Bytes], requested: Int): Either[Violation, Unit] =
    if ids.size > requested then Left(Violation.ExcessOffers(ids.size, requested))
    else if ids.distinct.size != ids.size then Left(Violation.DuplicateOffer)
    else Right(())

  /** Default reference state deadlines. Runtime Init/Idle retain explicit stricter local policy;
    * our server emits only blocking ID requests, so its ID wait remains indefinite.
    */
  def timeLimit(state: TxSubmission2.State): Option[FiniteDuration] = state match
    case TxSubmission2.State.Txs | TxSubmission2.State.Ids(false) => Some(ShortWait)
    case _                                                        => None
