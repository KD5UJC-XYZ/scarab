// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.{Async, Ref}
import cats.effect.std.{Mutex, Semaphore}
import cats.mtl.{Ask, Raise}
import cats.mtl.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, StorageError, VerificationDefect}
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.{NetworkError, TxSubmissionClient, TxSubmissionServer}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.read.ConwayView
import xyz.kd5ujc.scarab.node.{Fatal, StorageBoundary}
import xyz.kd5ujc.scarab.storage.UtxoStore

import Capacity.fits

/** Observable mempool milestones, for the JSON log. */
enum MempoolEvent:
  case Added(id: Bytes, size: Int, count: Int, bytes: Long)
  case Rejected(id: Option[Bytes], reason: Rejection)

  /** A transaction that fits an empty pool found no room and waits for the pool to change (once per
    * submission, however many times it retries): `needed` is what it counts for, `free` what the
    * pool had left.
    */
  case Waiting(id: Option[Bytes], needed: TxMeasure, free: TxMeasure)

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
    *
    * A transaction that fits the capacity of an empty pool but not the room left waits instead of
    * being refused, as `implAddTx` does in the reference
    * (`ouroboros-consensus/.../Mempool/Update.hs` at `82ecba329d7d054340bf707d44fe6e9ac27cec40`):
    * it retries from the first measure whenever the pool changes (a transaction added or removed, a
    * new tip) until it is held or refused for another reason. The wait is cancelable and holds no
    * state lock, so `sync`, `removeTxsEvenIfValid`, `awaitChange` and the readers run meanwhile.
    * Waiters are served in order through two queues: a peer's transaction takes the remote queue
    * and then the common one, a local client's only the common one, so a local client overtakes the
    * peers that wait; only the head of the queue waits for room, so a small transaction does not
    * pass a larger one. A transaction that cannot fit even an empty pool of the current capacity is
    * refused at once with [[Rejection.Full]] (the reference would wait for ever).
    */
  def add(raw: Bytes, source: Source)(using Raise[F, StorageError]): F[Either[Rejection, Bytes]]

  /** The submissions in line behind the ones being served (see [[add]]). */
  def lines: F[Mempool.Lines]

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
    * with the reason. A transaction the rules have no verdict on is raised before anything changes:
    * the state, its version, the waiters and the events stay as they were.
    */
  def sync(
      ledger: Either[ConwayView.Unavailable, MempoolLedger],
      tipSlot: BigInt,
      adopted: Set[Bytes] = Set.empty
  )(using Raise[F, StorageError], Raise[F, MempoolState.NoVerdict]): F[Vector[(Entry, Rejection)]]

  /** Remove the held transactions among `ids` even though they still apply, then revalidate the
    * rest in order and return the ones that depended on them (`implRemoveTxsEvenIfValid`). Ids that
    * are not held change nothing. Unlike `sync` it does not move the pool's tip. A transaction the
    * rules have no verdict on is raised before anything changes, as in `sync`.
    */
  def removeTxsEvenIfValid(ids: Set[Bytes])(using
      Raise[F, StorageError],
      Raise[F, MempoolState.NoVerdict]
  ): F[Vector[(Entry, Rejection)]]

  /** Offer our transactions to a peer (we are the TxSubmission2 client). */
  def source: TxSubmissionClient.Source[F]

  /** Collect a peer's transactions (we are the TxSubmission2 server). */
  def sink(fatal: Fatal => F[Unit]): TxSubmissionServer.Sink[F]

object Mempool:
  /** The transaction a revalidation (`sync`, `removeTxsEvenIfValid`) has no verdict on, as the
    * defect of the block it was revalidated for at `at`. The reference's revalidation watcher is
    * linked to the node (consensus 82ecba32 `Mempool/Init.hs:64-77`), so the node stops
    * ([[Fatal.Unverifiable]]).
    */
  def unverifiable(
      noVerdict: MempoolState.NoVerdict,
      at: xyz.kd5ujc.scarab.protocol.ChainSync.Point
  ): VerificationDefect =
    VerificationDefect(at, VerificationDefect.Site.Witness(noVerdict.txId), noVerdict.defect)

  /** A revalidation's no-verdict raised as the node's defect at `at` ([[unverifiable]]). */
  def raisedAt[F[_]](at: xyz.kd5ujc.scarab.protocol.ChainSync.Point)(using
      node: Raise[F, VerificationDefect]
  ): Raise[F, MempoolState.NoVerdict] = new Raise[F, MempoolState.NoVerdict]:
    def functor = node.functor
    def raise[E <: MempoolState.NoVerdict, A](noVerdict: E): F[A] =
      node.raise(unverifiable(noVerdict, at))

  /** How many submissions wait for their turn: `remote` behind the peer's transaction that holds
    * the remote line, `common` behind the one that holds the common line.
    */
  final case class Lines(remote: Int, common: Int)

  /** One validation step of an add: its answer, or the room to wait for. `seen` is the version of
    * the pool that the step looked at.
    */
  private enum Attempt:
    case Done(result: Either[Rejection, Bytes])
    case Wait(seen: Long, id: Option[Bytes], full: Rejection.Full)

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
      remoteFifo <- Semaphore[F](1)
      allFifo <- Semaphore[F](1)
    yield new Mempool[F]:
      private val F = Async[F]
      private val readEnv = environment.fold(F.pure(env))(_.ask[MempoolEnv])

      /** A submission's turn: `remoteFifo` then `allFifo` for a peer, `allFifo` alone for a local
        * client (`implAddTx`'s two FIFOs). A one-permit `Semaphore` is a `Mutex` that also counts
        * its waiters ([[lines]]); it queues in order and its `permit` releases on cancellation, so
        * a disconnect or a closed client frees the place in line.
        */
      private def inLine[A](source: Source)(fa: F[A]): F[A] = source match
        case Source.Remote => remoteFifo.permit.surround(allFifo.permit.surround(fa))
        case Source.Local  => allFifo.permit.surround(fa)

      def lines = (remoteFifo.count, allFifo.count).mapN((remote, common) =>
        Mempool.Lines((-remote).max(0).toInt, (-common).max(0).toInt)
      )

      def add(raw: Bytes, source: Source)(using Raise[F, StorageError]) =
        inLine(source) {
          F.tailRecM(false) { announced =>
            attempt(raw, source).flatMap {
              case Attempt.Done(result) => F.pure(result.asRight[Boolean])
              case Attempt.Wait(seen, id, full) =>
                events
                  .emit(MempoolEvent.Waiting(id, full.needed, full.free))
                  .unlessA(announced) *>
                  version.waitUntil(_ > seen).as(true.asLeft[Either[Rejection, Bytes]])
            }
          }
        }

      /** The state lock is held for this step only, never while waiting. Every change of the pool
        * happens under it and bumps `version`, so the version read here is the one of the state
        * that was measured and the wait cannot miss a change. The step starts from the first
        * measure again each time: a new tip may have changed the parameters and the capacity.
        */
      private def attempt(raw: Bytes, source: Source)(using
          Raise[F, StorageError]
      ): F[Attempt] = lock.lock.surround {
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
                  .as(Attempt.Done(entry.id.asRight[Rejection]))
            case Left(full: Rejection.Full) if full.needed.fits(current.capacity) =>
              version.get.map(
                Attempt.Wait(_, MempoolState.parse(raw).toOption.map(_._1), full)
              )
            case Left(reason) =>
              val id = reason match
                case Rejection.AlreadyHeld(i) => Some(i)
                case _                        => None
              events
                .emit(
                  MempoolEvent
                    .Rejected(id.orElse(MempoolState.parse(raw).toOption.map(_._1)), reason)
                )
                .as(Attempt.Done(reason.asLeft[Bytes]))
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
      )(using Raise[F, StorageError], Raise[F, MempoolState.NoVerdict]) = lock.lock.surround {
        for
          current <- state.get
          completed <- ledger.traverse(l =>
            UtxoStore
              .complete(utxoStore, l.utxo, UtxoStore.readSet(current.entries.map(_.tx)))
              .map(l.withUtxo)
          )
          activeEnv <- readEnv
          result <- F.blocking(current.sync(completed, tipSlot, activeEnv))
          synced <- Raise[F, MempoolState.NoVerdict].fromEither(result)
          (next, dropped) = synced
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

      def removeTxsEvenIfValid(ids: Set[Bytes])(using
          Raise[F, StorageError],
          Raise[F, MempoolState.NoVerdict]
      ) =
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
                  revalidated <- Raise[F, MempoolState.NoVerdict].fromEither(result)
                  (next, dependants) = revalidated
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
          // Runs in the peer's inbound TxSubmission2 fiber: a transaction that waits for room holds
          // that peer's replies back, as `txSubmissionInbound` does in the reference. The wait is
          // outside every protocol deadline (`TxSubmission2Runtime.server` times the read of a
          // message, not the sink), the peer sits in StIdle where it may wait for ever, and its
          // other mini-protocols and our outbound direction run in fibers of their own.
          //
          // A crypto provider that fails on a witness gives no verdict on the transaction. It is not
          // a rejection: it closes this peer's connection, with the peer tried again later and
          // nothing recorded, as the exception does in the reference's inbound TxSubmission2 thread
          // (consensus 82ecba32 Node/NodeKernel.hs `getMempoolWriter`, `Mempool/Update.hs`
          // `doAddTx`, `Node/RethrowPolicy.hs:40-45`). The transactions after it are not offered.
          def submit(originals: Vector[Bytes])(using Raise[F, NetworkError]) =
            originals.traverse_ { original =>
              StorageBoundary.run(fatal)(add(original, Source.Remote)).flatMap {
                case Left(Rejection.Ledger(defect: LedgerFailure.Unverifiable)) =>
                  NetworkError
                    .BearerClosed(NetworkError.Closure.AbortedBy(defect))
                    .raise[F, Unit]
                case _ => F.unit
              }
            }
