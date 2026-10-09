// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.Bytes
import lab.submission.SignedTransaction

/** Immutable pool transitions. The app installs every returned State under the SAME gate as chain
  * publication. Validation/revalidation runs outside that gate. P must be the full immutable
  * owner/generation/point/view/profile pin, never a height. Times are monotonic nanoseconds
  * supplied by the runtime, not chain slots.
  */
object AdaPool:
  final case class Limits(
      maxTransactions: Int = 64,
      maxBytes: Int = 4194304,
      retentionNanos: Long = 60000000000L,
      maxHistory: Int = 256
  ):
    require(maxTransactions > 0 && maxTransactions <= 64)
    require(maxBytes > 0 && maxBytes <= 4194304)
    require(retentionNanos > 0 && retentionNanos <= 60000000000L)
    require(maxHistory >= 0 && maxHistory <= 256)

  enum Rejection:
    case EnvelopeConflict
    case InputsReserved(inputs: Set[TxIn])
    case Capacity
  enum Drop:
    case Expired, Removed, Shutdown, Conflict
    case Validation(error: AdaAdmission.Failure)
  enum Outcome[+P]:
    case Accepted(receipt: Receipt[P])
    case AlreadyPresent(receipt: Receipt[P])
    case Rejected(reason: Rejection)
    case Retry(currentPin: P)
    case Unavailable
  final case class Receipt[+P](transactionId: Bytes, envelopeSHA256: Bytes, pin: P):
    val profileId = AdaAdmission.ProfileId
    val volatile = true
    val fullLedgerValidated = false
  enum Status[+P]:
    case Pending(receipt: Receipt[P], eligible: Boolean)
    case Included(pin: P)
    case Dropped(reason: Drop)
  private final case class Entry[P](candidate: AdaAdmission.Candidate[P], admittedAt: Long):
    def receipt = Receipt(
      candidate.transaction.transactionId,
      candidate.transaction.envelopeSHA256,
      candidate.pin
    )
  private final case class History[P](id: Bytes, status: Status[P], at: Long)

  final class State[P] private[AdaPool] (
      val pin: P,
      val limits: Limits,
      private[AdaPool] val entries: Vector[Entry[P]],
      private[AdaPool] val history: Vector[History[P]],
      val rebuilding: Boolean,
      val closed: Boolean,
      private[AdaPool] val rebuildToken: Option[Object]
  ):
    def size: Int = entries.size
    def byteSize: Int = entries.map(_.candidate.transaction.byteSize).sum
    def reserved: Set[TxIn] =
      if rebuilding || closed then Set.empty else entries.flatMap(_.candidate.spent).toSet
    def status(id: Bytes, now: Long): Option[Status[P]] =
      entries
        .find(_.candidate.transaction.transactionId == id)
        .filter(e => now - e.admittedAt < limits.retentionNanos)
        .map(e => Status.Pending(e.receipt, !rebuilding && !closed))
        .orElse(
          history.reverseIterator
            .find(h => h.id == id && now - h.at < limits.retentionNanos)
            .map(_.status)
        )

    /** Snapshot only. Relay owner applies its separate lease count/size/time bounds. */
    def eligible(now: Long): Vector[SignedTransaction] =
      if rebuilding || closed then Vector.empty
      else
        entries.filter(e => now - e.admittedAt < limits.retentionNanos).map(_.candidate.transaction)

  def empty[P](pin: P, limits: Limits = Limits()): State[P] =
    new State(pin, limits, Vector.empty, Vector.empty, false, false, None)
  private def updated[P](
      s: State[P],
      entries: Vector[Entry[P]],
      history: Vector[History[P]],
      now: Long
  ): State[P] =
    new State(
      s.pin,
      s.limits,
      entries,
      history.filter(h => now - h.at < s.limits.retentionNanos).takeRight(s.limits.maxHistory),
      s.rebuilding,
      s.closed,
      s.rebuildToken
    )
  def expire[P](s: State[P], now: Long): State[P] =
    val (expired, kept) = s.entries.partition(e => now - e.admittedAt >= s.limits.retentionNanos)
    updated(
      s,
      kept,
      s.history ++ expired.map(e =>
        History(e.candidate.transaction.transactionId, Status.Dropped(Drop.Expired), now)
      ),
      now
    )

  /** Compare-and-reserve linearization point, called under the owner's mutation gate. */
  def admit[P](
      previous: State[P],
      candidate: AdaAdmission.Candidate[P],
      now: Long
  ): (State[P], Outcome[P]) =
    val s = expire(previous, now)
    val tx = candidate.transaction
    if candidate.pin != s.pin then (s, Outcome.Retry(s.pin))
    else if s.closed || s.rebuilding then (s, Outcome.Unavailable)
    else
      s.entries.find(_.candidate.transaction.transactionId == tx.transactionId) match
        case Some(existing) =>
          if existing.candidate.transaction.original == tx.original then
            (s, Outcome.AlreadyPresent(existing.receipt))
          else (s, Outcome.Rejected(Rejection.EnvelopeConflict))
        case None =>
          val conflicts = candidate.spent intersect s.reserved
          if conflicts.nonEmpty then (s, Outcome.Rejected(Rejection.InputsReserved(conflicts)))
          else if s.size >= s.limits.maxTransactions || tx.byteSize > s.limits.maxBytes - s.byteSize
          then (s, Outcome.Rejected(Rejection.Capacity))
          else
            val entry = Entry(candidate, now)
            (
              updated(s, s.entries :+ entry, s.history.filterNot(_.id == tx.transactionId), now),
              Outcome.Accepted(entry.receipt)
            )

  def remove[P](s: State[P], id: Bytes, reason: Drop, now: Long): State[P] =
    val found = s.entries.exists(_.candidate.transaction.transactionId == id)
    updated(
      s,
      s.entries.filterNot(_.candidate.transaction.transactionId == id),
      s.history ++ (if found then Vector(History(id, Status.Dropped(reason), now))
                    else Vector.empty),
      now
    )

  final class Rebuild[P] private[AdaPool] (
      val pin: P,
      private[AdaPool] val token: Object,
      private[AdaPool] val originals: Vector[Entry[P]]
  )
  final class Rebuilt[P] private[AdaPool] (
      private[AdaPool] val work: Rebuild[P],
      private[AdaPool] val results: Vector[Either[AdaAdmission.Failure, AdaAdmission.Candidate[P]]]
  )

  /** included IDs must come from an applied follower block at newPin. Never relay events. */
  def move[P](
      previous: State[P],
      newPin: P,
      included: Set[Bytes],
      now: Long
  ): (State[P], Rebuild[P]) =
    require(newPin != previous.pin, "every movement must advance the complete generation pin")
    val s = expire(previous, now)
    val kept = s.entries.filterNot(e => included(e.candidate.transaction.transactionId))
    val history = s.history ++ s.entries
      .filter(e => included(e.candidate.transaction.transactionId))
      .map(e => History(e.candidate.transaction.transactionId, Status.Included(newPin), now))
    val token = new Object
    val next = new State(
      newPin,
      s.limits,
      kept,
      history.takeRight(s.limits.maxHistory),
      true,
      s.closed,
      Some(token)
    )
    (next, new Rebuild(newPin, token, kept))

  /** Bounded by at most 64 entries, preserving original admission order. No state publication. */
  def revalidate[P](work: Rebuild[P], view: ClusterTransition.State): Rebuilt[P] =
    new Rebuilt(
      work,
      work.originals.map(e =>
        AdaAdmission.prepare(work.pin, view, e.candidate.transaction.original)
      )
    )

  def finish[P](previous: State[P], result: Rebuilt[P], now: Long): (State[P], Boolean) =
    val s = expire(previous, now)
    if s.closed || !s.rebuilding || s.pin != result.work.pin || !s.rebuildToken.contains(
        result.work.token
      )
    then (s, false)
    else
      var kept = Vector.empty[Entry[P]]
      var reservations = Set.empty[TxIn]
      var history = s.history
      result.work.originals.zip(result.results).foreach { (old, checked) =>
        val id = old.candidate.transaction.transactionId
        if s.entries.exists(_.candidate.transaction.transactionId == id) then
          checked match
            case Right(candidate) if (candidate.spent intersect reservations).isEmpty =>
              kept :+= Entry(candidate, old.admittedAt)
              reservations ++= candidate.spent
            case other =>
              val drop = other match
                case Left(error) => Drop.Validation(error)
                case Right(_)    => Drop.Conflict
              history :+= History(id, Status.Dropped(drop), now)
      }
      val next =
        new State(s.pin, s.limits, kept, history.takeRight(s.limits.maxHistory), false, false, None)
      (next, true)

  /** Invalidate orphaned inclusion summaries after rollback; never resurrect originals. */
  def invalidateIncluded[P](s: State[P], orphaned: P => Boolean): State[P] =
    val history = s.history.filterNot(h =>
      h.status match
        case Status.Included(pin) => orphaned(pin)
        case _                    => false
    )
    new State(s.pin, s.limits, s.entries, history, s.rebuilding, s.closed, s.rebuildToken)

  def shutdown[P](s: State[P], now: Long): State[P] =
    val history = s.history ++ s.entries.map(e =>
      History(e.candidate.transaction.transactionId, Status.Dropped(Drop.Shutdown), now)
    )
    new State(
      s.pin,
      s.limits,
      Vector.empty,
      history.takeRight(s.limits.maxHistory),
      false,
      true,
      None
    )
