// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.{Async, Ref}
import cats.effect.std.Mutex
import cats.mtl.{Ask, Raise}
import xyz.kd5ujc.scarab.ledger.StorageError
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.{TxSubmissionClient, TxSubmissionServer}
import xyz.kd5ujc.scarab.node.read.ConwayView
import xyz.kd5ujc.scarab.node.{Fatal, StorageBoundary}
import xyz.kd5ujc.scarab.storage.UtxoStore

/** Observable mempool milestones, for the JSON log. */
enum MempoolEvent:
  case Added(id: Bytes, size: Int, count: Int, bytes: Long)
  case Rejected(id: Option[Bytes], reason: Rejection)

  /** Held transactions that the new tip no longer admits (adopted in a block, or invalidated). */
  case Dropped(id: Bytes, reason: Rejection)

  /** A held transaction is now in the chain: it was in a block that became part of our chain. */
  case Adopted(id: Bytes)

  /** A held transaction was removed on request although it still applies (a block we forged with it
    * was refused by our own chain). Transactions that depended on it are reported as `Dropped`.
    */
  case Removed(id: Bytes)
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
  /** The transaction id, or why the transaction was refused. `source` says whether a local client
    * or a peer submitted it ([[Source]]).
    */
  def add(raw: Bytes, source: Source)(using Raise[F, StorageError]): F[Either[Rejection, Bytes]]

  def snapshot: F[MempoolState]

  def contains(id: Bytes): F[Boolean]

  /** The first state for which `same` is false, waiting for the pool to change (a transaction added
    * or removed, a new tip). Cancelable.
    */
  def awaitChange(same: MempoolState => Boolean): F[MempoolState]

  /** The transactions a block of at most `maxBytes` of transaction bytes can take, in order. */
  def forBlock(maxBytes: Long): F[Vector[Entry]]

  /** Revalidate against `ledger`, ticked to the successor of the new tip (`tipSlot`, or -1 at
    * Origin). `adopted` are the ids of the transactions in the blocks that just joined our chain:
    * held ones among them leave the pool as adopted, the others that no longer apply are returned
    * with the reason.
    */
  def sync(
      ledger: Either[ConwayView.Unavailable, MempoolLedger],
      tipSlot: BigInt,
      adopted: Set[Bytes] = Set.empty
  )(using Raise[F, StorageError]): F[Vector[(Entry, Rejection)]]

  /** Remove the held transactions among `ids` even though they still apply, then revalidate the
    * rest in order and return the ones that depended on them (`implRemoveTxsEvenIfValid`). Ids that
    * are not held change nothing. Unlike `sync` it does not move the pool's tip.
    */
  def removeTxsEvenIfValid(ids: Set[Bytes])(using
      Raise[F, StorageError]
  ): F[Vector[(Entry, Rejection)]]

  /** Offer our transactions to a peer (we are the TxSubmission2 client). */
  def source: TxSubmissionClient.Source[F]

  /** Collect a peer's transactions (we are the TxSubmission2 server). */
  def sink(fatal: Fatal => F[Unit]): TxSubmissionServer.Sink[F]

object Mempool:
  /** @param capacityOverride
    *   The `MempoolCapacityBytesOverride` in bytes, rounded up to whole blocks; `None` is two
    *   blocks of the parameters in force ([[Capacity.of]]).
    * @param utxoStore
    *   Where the UTxO entries outside the ledger state's diff live; the pool fetches the read set
    *   of each transaction from it, as the block path does.
    * @param environment
    *   A caller may refresh backend capabilities between validations.
    */
  def create[F[_]: Async](
      ledger: Either[ConwayView.Unavailable, MempoolLedger],
      tipSlot: BigInt,
      env: MempoolEnv,
      capacityOverride: Option[Long],
      events: MempoolEvents[F],
      utxoStore: Option[UtxoStore[F]] = None,
      environment: Option[Ask[F, MempoolEnv]] = None
  ): F[Mempool[F]] =
    for
      state <- Ref.of[F, MempoolState](MempoolState.empty(ledger, tipSlot, capacityOverride))
      version <- SignallingRef.of[F, Long](0L)
      lock <- Mutex[F]
    yield new Mempool[F]:
      private val F = Async[F]
      private val readEnv = environment.fold(F.pure(env))(_.ask[MempoolEnv])

      def add(raw: Bytes, source: Source)(using Raise[F, StorageError]) = lock.lock.surround {
        for
          held <- state.get
          current <- MempoolState.parse(raw) match
            case Right((_, tx)) =>
              held.current.fold(
                _ => F.pure(held),
                current =>
                  UtxoStore
                    .complete(utxoStore, current.utxo, UtxoStore.readSet(Some(tx)))
                    .map(u => held.copy(current = Right(current.withUtxo(u))))
              )
            case Left(_) => F.pure(held)
          activeEnv <- readEnv
          result <- F.blocking(current.add(raw, activeEnv, source))
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
              events
                .emit(
                  MempoolEvent
                    .Rejected(id.orElse(MempoolState.parse(raw).toOption.map(_._1)), reason)
                )
                .as(reason.asLeft[Bytes])
        yield out
      }

      def snapshot = state.get

      def contains(id: Bytes) = state.get.map(_.contains(id))

      def awaitChange(same: MempoolState => Boolean): F[MempoolState] =
        version.get.flatMap { seen =>
          state.get.flatMap { s =>
            if !same(s) then F.pure(s)
            else version.waitUntil(_ > seen) *> awaitChange(same)
          }
        }

      def forBlock(maxBytes: Long) = state.get.map(_.forBlock(maxBytes))

      def sync(
          ledger: Either[ConwayView.Unavailable, MempoolLedger],
          tipSlot: BigInt,
          adopted: Set[Bytes]
      )(using Raise[F, StorageError]) = lock.lock.surround {
        for
          current <- state.get
          completed <- ledger.traverse(l =>
            UtxoStore
              .complete(utxoStore, l.utxo, UtxoStore.readSet(current.entries.map(_.tx)))
              .map(l.withUtxo)
          )
          activeEnv <- readEnv
          result <- F.blocking(current.sync(completed, tipSlot, activeEnv))
          (next, dropped) = result
          _ <- state.set(next)
          _ <- version.update(_ + 1)
          (inBlock, invalid) = dropped.partition((entry, _) => adopted(entry.id))
          _ <- inBlock.traverse_ { (entry, _) =>
            events.emit(MempoolEvent.Adopted(entry.id))
          }
          _ <- invalid.traverse_ { (entry, reason) =>
            events.emit(MempoolEvent.Dropped(entry.id, reason))
          }
          _ <- events.emit(MempoolEvent.Synced(next.entries.size, next.bytes, invalid.size))
        yield invalid
      }

      def removeTxsEvenIfValid(ids: Set[Bytes])(using Raise[F, StorageError]) =
        lock.lock.surround {
          for
            current <- state.get
            (removed, kept) = current.entries.partition(e => ids(e.id))
            out <-
              if removed.isEmpty then F.pure(Vector.empty[(Entry, Rejection)])
              else
                for
                  completed <- current.base.traverse(l =>
                    UtxoStore
                      .complete(utxoStore, l.utxo, UtxoStore.readSet(kept.map(_.tx)))
                      .map(l.withUtxo)
                  )
                  activeEnv <- readEnv
                  result <- F.blocking(current.copy(base = completed).remove(ids, activeEnv))
                  (next, dependants) = result
                  _ <- state.set(next)
                  _ <- version.update(_ + 1)
                  _ <- removed.traverse_(entry => events.emit(MempoolEvent.Removed(entry.id)))
                  _ <- dependants.traverse_ { (entry, reason) =>
                    events.emit(MempoolEvent.Dropped(entry.id, reason))
                  }
                  _ <- events.emit(
                    MempoolEvent.Synced(next.entries.size, next.bytes, dependants.size)
                  )
                yield dependants
          yield out
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

      def sink(fatal: Fatal => F[Unit]): TxSubmissionServer.Sink[F] =
        new TxSubmissionServer.Sink[F]:
          def known(id: Bytes) =
            // NodeKernel's mempoolHasTx checks the current snapshot only. TxSubmission2 owns
            // its in-flight IDs; a previous rejection or adoption must not suppress a re-offer.
            state.get.map(_.contains(id))
          def submit(originals: Vector[Bytes]) =
            StorageBoundary.run(fatal)(originals.traverse_(add(_, Source.Remote).void))
