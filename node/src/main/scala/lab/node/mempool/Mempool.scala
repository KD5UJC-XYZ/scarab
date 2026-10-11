// SPDX-License-Identifier: Apache-2.0
package lab.node.mempool

import cats.effect.{Async, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import lab.cbor.Bytes
import lab.network.runtime.{TxSubmissionClient, TxSubmissionServer}
import lab.node.ledger.LedgerState

/** Observable mempool milestones, for the JSON log. */
enum MempoolEvent:
  case Added(id: Bytes, size: Int, count: Int, bytes: Long)
  case Rejected(id: Option[Bytes], reason: Rejection)

  /** Held transactions that the new tip no longer admits (adopted in a block, or invalidated). */
  case Dropped(id: Bytes, reason: Rejection)
  case Synced(count: Int, bytes: Long, dropped: Int)

trait MempoolEvents[F[_]]:
  def emit(event: MempoolEvent): F[Unit]

object MempoolEvents:
  def none[F[_]: cats.Applicative]: MempoolEvents[F] = _ => cats.Applicative[F].unit

/** The transaction pool of a node: transactions that apply, in order, to the ledger state at the
  * chain tip.
  *
  *   - `add` validates against the pool's current state, so what is held is always applicable;
  *   - `sync` is called with the ledger state of every new tip (a block, a rollback, a fork switch)
  *     and drops what no longer applies;
  *   - `forBlock` is the block builder's view;
  *   - `source` and `sink` plug it into TxSubmission2 in both directions.
  *
  * A rejection is a value (it is the normal answer to a bad transaction), not a failure of `F`.
  */
trait Mempool[F[_]]:
  /** The transaction id, or why the transaction was refused. */
  def add(raw: Bytes): F[Either[Rejection, Bytes]]

  def snapshot: F[MempoolState]

  def contains(id: Bytes): F[Boolean]

  /** The transactions a block of at most `maxBytes` of transaction bytes can take, in order. */
  def forBlock(maxBytes: Long): F[Vector[Entry]]

  /** Revalidate against the ledger state `ledger` at the new tip (in `tipSlot`). */
  def sync(ledger: LedgerState, tipSlot: BigInt): F[Vector[(Entry, Rejection)]]

  /** Offer our transactions to a peer (we are the TxSubmission2 client). */
  def source: TxSubmissionClient.Source[F]

  /** Collect a peer's transactions (we are the TxSubmission2 server). */
  def sink: TxSubmissionServer.Sink[F]

object Mempool:
  /** Ids of recently refused or removed transactions, remembered so peers that offer them again are
    * not asked for the bodies.
    */
  private final case class Seen(ids: Set[Bytes], order: Vector[Bytes]):
    def add(id: Bytes, limit: Int): Seen =
      if ids(id) then this
      else
        val grown = order :+ id
        if grown.size > limit then Seen(ids + id - grown.head, grown.tail)
        else Seen(ids + id, grown)

  def create[F[_]: Async](
      ledger: LedgerState,
      tipSlot: BigInt,
      env: MempoolEnv,
      capacityBytes: Long,
      events: MempoolEvents[F],
      seenLimit: Int = 8192
  ): F[Mempool[F]] =
    for
      state <- Ref.of[F, MempoolState](MempoolState.empty(ledger, tipSlot, capacityBytes))
      seen <- Ref.of[F, Seen](Seen(Set.empty, Vector.empty))
      version <- SignallingRef.of[F, Long](0L)
      lock <- Mutex[F]
    yield new Mempool[F]:
      private val F = Async[F]

      def add(raw: Bytes) = lock.lock.surround {
        for
          current <- state.get
          result <- F.blocking(current.add(raw, env))
          out <- result match
            case Right((next, entry)) =>
              state.set(next) *> version.update(_ + 1) *>
                events
                  .emit(MempoolEvent.Added(entry.id, entry.size, next.entries.size, next.bytes))
                  .as(entry.id.asRight[Rejection])
            case Left(reason) =>
              val id = reason match
                case Rejection.AlreadyHeld(i) => Some(i)
                case _                        => None
              val remembered = reason match
                case Rejection.Ledger(_) | Rejection.Full(_, _) =>
                  MempoolState.parse(raw).toOption.map(_._1)
                case _ => None
              remembered.traverse_(i => seen.update(_.add(i, seenLimit))) *>
                events
                  .emit(MempoolEvent.Rejected(id.orElse(remembered), reason))
                  .as(reason.asLeft[Bytes])
        yield out
      }

      def snapshot = state.get

      def contains(id: Bytes) = state.get.map(_.contains(id))

      def forBlock(maxBytes: Long) = state.get.map(_.forBlock(maxBytes))

      def sync(ledger: LedgerState, tipSlot: BigInt) = lock.lock.surround {
        for
          current <- state.get
          result <- F.blocking(current.sync(ledger, tipSlot, env))
          (next, dropped) = result
          _ <- state.set(next)
          _ <- dropped.traverse_ { (entry, reason) =>
            seen.update(_.add(entry.id, seenLimit)) *>
              events.emit(MempoolEvent.Dropped(entry.id, reason))
          }
          _ <- events.emit(MempoolEvent.Synced(next.entries.size, next.bytes, dropped.size))
        yield dropped
      }

      val source: TxSubmissionClient.Source[F] = new TxSubmissionClient.Source[F]:
        def after(ticket: Long, max: Int) =
          state.get.map(
            _.after(ticket).take(max).map(e => TxSubmissionClient.Held(e.id, e.size, e.ticket))
          )
        def awaitAfter(ticket: Long): F[Unit] =
          version.get.flatMap { seenVersion =>
            state.get.flatMap { s =>
              if s.after(ticket).nonEmpty then F.unit
              else version.waitUntil(_ > seenVersion) *> awaitAfter(ticket)
            }
          }
        def body(id: Bytes) = state.get.map(_.entries.find(_.id == id).map(_.raw))

      val sink: TxSubmissionServer.Sink[F] = new TxSubmissionServer.Sink[F]:
        def known(id: Bytes) =
          (state.get.map(_.contains(id)), seen.get.map(_.ids(id))).mapN(_ || _)
        def submit(originals: Vector[Bytes]) = originals.traverse_(add(_).void)
