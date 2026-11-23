// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.{Async, Ref}
import cats.effect.std.{Mutex, Semaphore}
import cats.mtl.{Ask, Raise}
import cats.mtl.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, StorageError}
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import io.circe.{Encoder, JsonObject}
import io.circe.syntax.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.{
  DiffTime,
  NetworkError,
  Protocols,
  TxSubmissionClient,
  TxSubmissionServer,
  Word32
}
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.read.ConwayView
import xyz.kd5ujc.scarab.node.{Fatal, StorageBoundary}
import xyz.kd5ujc.scarab.node.assertions.{Assertions, Property}
import xyz.kd5ujc.scarab.protocol.{Era, LeiosTxHash, TxSubmission2}
import xyz.kd5ujc.scarab.storage.UtxoStore
import xyz.kd5ujc.scarab.ledger.era.rules.DijkstraRuleDefect

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

  /** A transaction waits for the pool to change because the validations of the held transactions
    * took `used`, longer than the pool's `capacity` of time (once per submission, as [[Waiting]]).
    */
  case WaitingForTime(id: Option[Bytes], used: DiffTime, capacity: DiffTime)

  /** A held transaction left the pool with `reason`, for `cause`: the pool was validated again and
    * no longer admits it, or the forger withdrew it.
    */
  case Dropped(id: Bytes, reason: Rejection, cause: DropCause)

  /** A held transaction is now in the chain: it was in a block that became part of our chain. */
  case Adopted(id: Bytes)

  /** A held transaction was removed on request although it still applies (a block we forged with it
    * was refused by our own chain). Transactions that depended on it are reported as `Dropped`.
    */
  case Removed(id: Bytes)
  case Synced(count: Int, bytes: Long, dropped: Int)

/** A refused submission, with the era of the pool's ledger it met (none when the pool had none): a
  * refusal of the rules or of a limit is written in that era ([[LocalSubmission.reasonItem]]).
  */
final case class Refused(rejection: Rejection, era: Option[Era])

/** Why a held transaction left the pool with a reason ([[MempoolEvent.Dropped]], #1032). */
enum DropCause:
  /** The pool was validated again, at a new tip ([[Mempool.sync]]) or after transactions were
    * removed ([[Mempool.removeTxsEvenIfValid]], [[Mempool.withdraw]]), and no longer admits the
    * transaction: the rules refuse it, or have no verdict on it.
    */
  case Revalidation

  /** The forger had no verdict on it while it built a block, and withdrew it ([[Mempool.withdraw]],
    * issue 584).
    */
  case Withdrawal

/** The pool's Leios index as one read of its state gives it ([[Mempool.leiosTxIndex]]): the held
  * transactions by their Leios hash with their bytes, and the parity of the protocol version they
  * were validated under, none while the pool has no ledger (when it holds no transaction).
  */
final case class LeiosTxIndex(
    txs: Map[LeiosTxHash, Bytes],
    parity: Option[xyz.kd5ujc.scarab.consensus.leios.ProtVerParity]
)

trait MempoolEvents[F[_]]:
  def emit(event: MempoolEvent): F[Unit]

object MempoolEvents:
  def none[F[_]: cats.Applicative]: MempoolEvents[F] = _ => cats.Applicative[F].unit

/** The transaction pool of a node: transactions that apply, in order, to the ledger state at the
  * chain tip.
  *
  *   - `add` validates against the pool's current state, so what is held is always applicable;
  *   - `synchronise` is called by the pool's watcher on the tip ([[MempoolSync]]), from a fiber of
  *     its own, whenever the tip changed (a block, a rollback, a fork switch), and revalidates at
  *     the chain's tip as it is once the state lock is held, dropping what no longer applies;
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

  /** [[add]] of a transaction in the era `submission` names, which is brought into the era of the
    * pool's ledger as the selected build does or refused with the era mismatch
    * ([[MempoolState.add]]). A refusal comes with the era of the ledger it met ([[Refused]]).
    */
  def submit(submission: Submission, source: Source)(using
      Raise[F, StorageError]
  ): F[Either[Refused, Bytes]]

  /** [[submit]] of a submission its caller has already read in its era ([[MempoolState.read]]), so
    * that a transaction its era's decoder refuses is answered before it takes its place in line, as
    * the reference's codec refuses it before the mempool sees it.
    */
  def submitRead(read: MempoolState.Read, source: Source)(using
      Raise[F, StorageError]
  ): F[Either[Refused, Bytes]]

  /** The submissions in line behind the ones being served (see [[add]]). */
  def lines: F[Mempool.Lines]

  def snapshot: F[MempoolState]

  /** The held transactions by the hash an endorser block lists them by, with their bytes, and the
    * parity of the protocol version of the pool's ledger, which they were validated under, from one
    * read of the pool's state (`getLeiosTxIndex`, ouroboros-consensus 2416493b
    * `Mempool/API.hs:241-244`, `Mempool/Init.hs:135-140`; [[MempoolState.leiosTxIndex]],
    * [[MempoolState.leiosParity]]).
    */
  def leiosTxIndex: F[LeiosTxIndex]

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
    * with the reason. `tipHash` is the new tip's hash ([[MempoolState.tipHash]]; none at Origin,
    * and for the pools of tests that have no chain). A transaction the rules have no verdict on
    * leaves the pool with its reason, as a refused one does ([[MempoolState.sync]]).
    */
  def sync(
      ledger: Either[ConwayView.Unavailable, PoolLedger],
      tipSlot: BigInt,
      adopted: Set[Bytes] = Set.empty,
      tipHash: Option[Bytes] = None
  )(using Raise[F, StorageError]): F[Vector[(Entry, Rejection)]]

  /** [[sync]] at the target `next` chooses once the pool's lock is held, from the pool's state:
    * none when the pool is where it should be already. The reference reads the ledger it
    * revalidates against in the same step that takes the pool's state, so that a revalidation
    * waiting behind an admission never applies a tip the chain has left meanwhile
    * (`implSyncWithLedger`, ouroboros-consensus 82ecba32 `Mempool/Update.hs` 558-625, with the
    * interleaving it prevents at 561-586); `next` reads the chain then, never before.
    */
  def synchronise(next: MempoolState => F[Option[Mempool.Target]])(using
      Raise[F, StorageError]
  ): F[Vector[(Entry, Rejection)]]

  /** Remove the held transactions among `ids` even though they still apply, then revalidate the
    * rest in order and return the ones that depended on them (`implRemoveTxsEvenIfValid`). Ids that
    * are not held change nothing. Unlike `sync` it does not move the pool's tip. A transaction the
    * rules have no verdict on leaves the pool with its reason, as in `sync`.
    */
  def removeTxsEvenIfValid(ids: Set[Bytes])(using
      Raise[F, StorageError]
  ): F[Vector[(Entry, Rejection)]]

  /** As [[removeTxsEvenIfValid]], for held transactions the forger has no verdict on while it
    * builds a block: each leaves the pool with its reason (`Dropped`), as revalidation drops one
    * (issue 584).
    */
  def withdraw(reasons: Map[Bytes, Rejection])(using
      Raise[F, StorageError]
  ): F[Vector[(Entry, Rejection)]]

  /** Offer our transactions to a peer (we are the TxSubmission2 client). */
  def source: TxSubmissionClient.Source[F]

  /** Collect a peer's transactions (we are the TxSubmission2 server). */
  def sink(fatal: Fatal => F[Unit]): TxSubmissionServer.Sink[F]

object Mempool:
  /** What the assertion of a drop reports: the transaction, why it left and for what cause. */
  private final case class Drop(id: Bytes, reason: Rejection, cause: DropCause)

  private object Drop:
    given Encoder.AsObject[Drop] = Encoder.AsObject.instance(d =>
      JsonObject(
        "tx" -> d.id.hex.asJson,
        "reason" -> d.reason.message.asJson,
        "cause" -> d.cause.productPrefix.asJson
      )
    )

  /** How a peer's transaction ends its TxSubmission2 session, when it does: no verdict on it, so
    * the connection closes and the node goes on. A crypto provider or the evaluator that fails on
    * it closes the connection with the failure; a decoder that threw is the connection's input
    * defect (issue 465); rules that threw are the node's own failure while serving the peer, which
    * is not charged (issue 564). A refusal, or any other answer, ends nothing.
    */
  private[mempool] def peerConnectionEnd(rejection: Rejection): Option[NetworkError] =
    rejection match
      case Rejection.Ledger(
            defect @ (_: LedgerFailure.Unverifiable | _: LedgerFailure.Unavailable)
          ) =>
        Some(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(defect)))
      case Rejection.InputDefect(cause) =>
        Some(NetworkError.DecoderDefect(Protocols.TxSubmission2, cause))
      case Rejection.RulesDefect(cause) =>
        Some(NetworkError.ServingDefect(Protocols.TxSubmission2, cause))
      case Rejection.TimedOut(timeout) =>
        Some(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(timeout)))
      // no verdict on a Dijkstra transaction where a provider failed on it (a script the evaluator
      // cannot judge, a BLS key the library cannot verify) or the node's two readings of it
      // disagree: the connection closes as for the flat eras', not charged; the node's other
      // limits and defects (the state's own defects) leave
      // the peer's connection as it is, the transaction not held
      case Rejection.DijkstraUnjudged(_, cause) =>
        cause match
          case DijkstraUnjudged.Rules(
                DijkstraRuleDefect.Unavailable(_) | DijkstraRuleDefect.Pool(_)
              ) | DijkstraUnjudged.Projection(_) =>
            Some(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(cause)))
          case DijkstraUnjudged.Rules(
                DijkstraRuleDefect.UtxoState(_) | DijkstraRuleDefect.Ledger(_) |
                DijkstraRuleDefect.Gov(_)
              ) | DijkstraUnjudged.UtxoState(_) =>
            None
      // no verdict on a Conway transaction, as on a Dijkstra one: a script the evaluator cannot
      // judge, as the flat rules' unavailable script, or the node's two readings of it that
      // disagree close the connection, not charged; the state's own defects leave it as it is
      case Rejection.ConwayUnjudged(_, cause) =>
        cause match
          case ConwayUnjudged.Unavailable(_) | ConwayUnjudged.Projection(_) =>
            Some(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(cause)))
          case ConwayUnjudged.UtxoState(_) | ConwayUnjudged.Gov(_) |
              ConwayUnjudged.ProposalsRead(_) | ConwayUnjudged.ProposalsWrite(_) =>
            None
      case _ => None

  /** The refusal of a validation that took `took` in the era of the pool's ledger, `ledger`, if the
    * era has an error for it, as `mkMempoolApplyTxError` makes one: only Conway's
    * `ConwayMempoolFailure` (ouroboros-consensus 82ecba32 `shelley` `Shelley/Eras.hs` 169-220,
    * 808e4005 179-230: none from Shelley to Babbage, nor in Dijkstra; Byron's is
    * `nothingMkMempoolApplyTxError`, `byron` `Byron/Ledger/Mempool.hs` 143). Its text names the
    * submitted transaction, so there is none either for a transaction of an era whose transactions
    * the pool does not read (Byron, and Dijkstra under the release builds), whose id it cannot
    * show.
    */
  private[mempool] def tooSlow(
      ledger: Era,
      measured: MempoolState.Measured,
      took: FiniteDuration
  ): Option[Rejection.TooSlow] =
    measured.named.flatMap((submitted, id) =>
      ledger match
        case Era.Conway => Some(Rejection.TooSlow(id, submitted, took))
        case Era.Byron | Era.Shelley | Era.Allegra | Era.Mary | Era.Alonzo | Era.Babbage |
            Era.Dijkstra =>
          None
    )

  /** How many submissions wait for their turn: `remote` behind the peer's transaction that holds
    * the remote line, `common` behind the one that holds the common line.
    */
  final case class Lines(remote: Int, common: Int)

  /** Where a revalidation brings the pool, as [[Mempool.sync]] takes it. */
  final case class Target(
      ledger: Either[ConwayView.Unavailable, PoolLedger],
      tipSlot: BigInt,
      adopted: Set[Bytes],
      tipHash: Option[Bytes]
  )

  /** One validation step of an add: its answer, or the room to wait for. `seen` is the version of
    * the pool that the step looked at.
    */
  private enum Attempt:
    case Done(result: Either[Refused, Bytes])
    case Wait(seen: Long, waiting: MempoolEvent)

  /** @param capacityOverride
    *   The `MempoolCapacityBytesOverride` in bytes, rounded up to whole blocks; `None` is two
    *   blocks of the parameters in force ([[Capacity.of]]).
    * @param timeouts
    *   The time limits of a validation ([[MempoolTimeouts]]); none, as the reference's `Nothing`,
    *   enforces none, and the validations are timed all the same.
    * @param timing
    *   How a validation is run and timed ([[Timing.interruptible]]).
    * @param utxoStore
    *   Where the UTxO entries outside the ledger state's diff live; the pool fetches the read set
    *   of each transaction from it, as the block path does.
    * @param environment
    *   A caller may refresh backend capabilities between validations.
    * @param tipHash
    *   The hash of the chain tip at `tipSlot` ([[MempoolState.tipHash]]).
    */
  def create[F[_]: Async](
      ledger: Either[ConwayView.Unavailable, PoolLedger],
      tipSlot: BigInt,
      env: MempoolEnv,
      capacityOverride: Option[Word32],
      events: MempoolEvents[F],
      utxoStore: Option[UtxoStore[F]] = None,
      environment: Option[Ask[F, MempoolEnv]] = None,
      tipHash: Option[Bytes] = None,
      timeouts: Option[MempoolTimeouts] = None,
      timing: Option[Timing[F]] = None
  )(using assertions: Assertions[F]): F[Mempool[F]] =
    for
      state <- Ref.of[F, MempoolState](
        MempoolState.empty(ledger, tipSlot, capacityOverride, tipHash)
      )
      version <- SignallingRef.of[F, Long](0L)
      lock <- Mutex[F]
      remoteFifo <- Semaphore[F](1)
      allFifo <- Semaphore[F](1)
    yield new Mempool[F]:
      private val F = Async[F]
      private val readEnv = environment.fold(F.pure(env))(_.ask[MempoolEnv])
      private val timed = timing.getOrElse(Timing.interruptible[F])

      /** A held transaction left the pool: said, and asserted when the pool's revalidation dropped
        * it.
        */
      private def leaves(entry: Entry, reason: Rejection, cause: DropCause): F[Unit] =
        events.emit(MempoolEvent.Dropped(entry.id, reason, cause)) *>
          assertions.sometimes(
            Property.Sometimes.MempoolDropsOnRevalidation,
            cause == DropCause.Revalidation,
            Drop(entry.id, reason, cause)
          )

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

      private val build = env.globals.referenceBuild

      def add(raw: Bytes, source: Source)(using Raise[F, StorageError]) =
        // a transaction of the pool's own era, whichever it is when its turn comes
        added(
          s => MempoolState.read(Submission(s.era.fold(Era.Conway.index)(_.index), raw), build),
          source
        ).map(_.leftMap(_.rejection))

      def submit(submission: Submission, source: Source)(using Raise[F, StorageError]) =
        added(_ => MempoolState.read(submission, build), source)

      def submitRead(read: MempoolState.Read, source: Source)(using Raise[F, StorageError]) =
        added(_ => Right(read), source)

      /** `submission` read in its era, at each attempt, from the pool's state then. */
      private def added(
          submission: MempoolState => Either[Rejection, MempoolState.Read],
          source: Source
      )(using
          Raise[F, StorageError]
      ): F[Either[Refused, Bytes]] =
        inLine(source) {
          F.tailRecM(false) { announced =>
            attempt(submission, source).flatMap {
              case Attempt.Done(result) => F.pure(result.asRight[Boolean])
              case Attempt.Wait(seen, waiting) =>
                events.emit(waiting).unlessA(announced) *>
                  version.waitUntil(_ > seen).as(true.asLeft[Either[Refused, Bytes]])
            }
          }
        }

      /** The state lock is held for this step only, never while waiting. Every change of the pool
        * happens under it and bumps `version`, so the version read here is the one of the state
        * that was measured and the wait cannot miss a change. The step starts from the first
        * measure again each time: a new tip may have changed the parameters and the capacity. Its
        * validation is timed as the reference's `doAddTx` times it ([[validated]]); a refusal, a
        * cut-off and a cancellation during the validation leave the pool as it was. Once a
        * transaction is held, its state, the version and the `Added` event are three effects, so a
        * cancellation between them leaves the state without the version or the event, as before.
        */
      private def attempt(
          of: MempoolState => Either[Rejection, MempoolState.Read],
          source: Source
      )(using
          Raise[F, StorageError]
      ): F[Attempt] = lock.lock.surround {
        for
          held <- state.get
          // the submission as read in the era it names: its id and inputs, whatever its era
          submission = of(held)
          read = submission.toOption.collect { case MempoolState.Read.Decoded(transaction) =>
            transaction
          }
          current <- read match
            case Some(transaction) =>
              held.current.fold(
                _ => F.pure(held),
                current =>
                  UtxoStore
                    .complete(utxoStore, current.utxo, MempoolState.readSet(Some(transaction)))
                    .map(u => held.withCurrent(current.withUtxo(u)))
              )
            case None => F.pure(held)
          activeEnv <- readEnv
          measured <- F.blocking(submission.flatMap(current.measure(_, activeEnv)))
          out <- measured.fold(
            answer(current, read.map(_.id), _),
            validated(current, _, activeEnv, source)
          )
        yield out
      }

      /** The timed step of an admission and what follows, in the order of `doAddTx`
        * (ouroboros-consensus 82ecba32 `Mempool/Update.hs` 239-319; 808e4005 240-320): the
        * validation ([[MempoolState.tryAdd]]) is timed from after the UTxO entries are read, under
        * the hard limit. Cut off, it is refused with the era's error for a local client
        * ([[Mempool.tooSlow]]), or ends the submitter's connection ([[Rejection.TimedOut]]).
        * Finished after longer than the soft limit, it is refused with the era's error whatever its
        * result, where the era has one. Otherwise its result is the answer, and a transaction held
        * records how long its validation took.
        */
      private def validated(
          current: MempoolState,
          measured: MempoolState.Measured,
          env: MempoolEnv,
          source: Source
      ): F[Attempt] =
        val era = measured.ledger.era
        timed
          .within(timeouts.fold(MempoolTimeouts.HardLimit.Unbounded)(_.hardLimit))(
            current.tryAdd(
              measured,
              env,
              source,
              timeouts.flatMap(_.admissionCapacity(env.globals.referenceBuild))
            )
          )
          .flatMap {
            case Timed.Cut(took) =>
              val refusal = source match
                case Source.Local  => Mempool.tooSlow(era, measured, took)
                case Source.Remote => None
              answer(
                current,
                measured.id,
                refusal.getOrElse(Rejection.TimedOut(MempoolTimeout(measured.id, took)))
              )
            // no verdict: in the reference the exception of the evaluation escapes its `timeout`
            // and never meets the soft limit (`Mempool/Update.hs` 243-245), so the failure keeps
            // its cause and its handling
            case Timed.Done(
                  MempoolState.Tried.Done(
                    Left(
                      failure @ (Rejection.RulesDefect(_) | Rejection.InputDefect(_) | Rejection
                        .Ledger(_: LedgerFailure.Unavailable | _: LedgerFailure.Unverifiable) |
                      Rejection.DijkstraUnjudged(_, _) | Rejection.ConwayUnjudged(_, _))
                    )
                  ),
                  _
                ) =>
              answer(current, measured.id, failure)
            case Timed.Done(tried, took) =>
              val late =
                timeouts.exists(t => DiffTime.of(took).toPicoseconds > t.soft.toPicoseconds)
              Option
                .when(late)(Mempool.tooSlow(era, measured, took))
                .flatten
                .fold(finished(current, measured, tried, took))(answer(current, measured.id, _))
          }

      /** The answer of a validation that took `took`, not refused as too slow: a wait while the
        * held transactions took longer than the capacity, a refusal, or the transaction held with
        * the time it took.
        */
      private def finished(
          current: MempoolState,
          measured: MempoolState.Measured,
          tried: MempoolState.Tried,
          took: FiniteDuration
      ): F[Attempt] =
        tried match
          case MempoolState.Tried.NoTime(used, capacity) =>
            version.get.map(
              Attempt.Wait(_, MempoolEvent.WaitingForTime(measured.id, used, capacity))
            )
          case MempoolState.Tried.Done(Left(reason)) => answer(current, measured.id, reason)
          case MempoolState.Tried.Done(Right(admitted)) =>
            val (next, entry) = current.hold(admitted, took)
            state.set(next) *> version.update(_ + 1) *>
              events
                .emit(MempoolEvent.Added(entry.id, entry.size, next.entries.size, next.bytes))
                .as(Attempt.Done(entry.id.asRight[Refused]))

      /** A refusal of `id`, or a wait for room when it is a lack of room that an empty pool would
        * not have.
        */
      private def answer(current: MempoolState, id: Option[Bytes], reason: Rejection): F[Attempt] =
        reason match
          case full: Rejection.Full if full.needed.fits(current.capacity) =>
            version.get.map(Attempt.Wait(_, MempoolEvent.Waiting(id, full.needed, full.free)))
          case _ =>
            val named = reason match
              case Rejection.AlreadyHeld(i) => Some(i)
              case _                        => None
            events
              .emit(MempoolEvent.Rejected(named.orElse(id), reason))
              .as(Attempt.Done(Refused(reason, current.era).asLeft[Bytes]))

      def snapshot = state.get

      def leiosTxIndex = state.get.map(s => LeiosTxIndex(s.leiosTxIndex, s.leiosParity))

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
          ledger: Either[ConwayView.Unavailable, PoolLedger],
          tipSlot: BigInt,
          adopted: Set[Bytes],
          tipHash: Option[Bytes]
      )(using Raise[F, StorageError]) =
        lock.lock.surround(revalidated(Mempool.Target(ledger, tipSlot, adopted, tipHash)))

      def synchronise(next: MempoolState => F[Option[Mempool.Target]])(using
          Raise[F, StorageError]
      ) = lock.lock.surround(
        state.get
          .flatMap(next)
          .flatMap(_.fold(F.pure(Vector.empty[(Entry, Rejection)]))(revalidated))
      )

      /** The pool revalidated at `target`, under the state lock its caller holds. */
      private def revalidated(target: Mempool.Target)(using
          Raise[F, StorageError]
      ): F[Vector[(Entry, Rejection)]] =
        for
          current <- state.get
          completed <- target.ledger.traverse(l =>
            UtxoStore
              .complete(utxoStore, l.utxo, MempoolState.readSet(current.entries.map(_.held.in)))
              .map(l.withUtxo)
          )
          activeEnv <- readEnv
          synced <- F.blocking(current.sync(completed, target.tipSlot, activeEnv))
          next = synced._1.at(target.tipHash)
          dropped = synced._2
          _ <- state.set(next)
          _ <- version.update(_ + 1)
          (inBlock, invalid) = dropped.partition((entry, _) => target.adopted(entry.id))
          _ <- inBlock.traverse_ { (entry, _) =>
            events.emit(MempoolEvent.Adopted(entry.id))
          }
          _ <- invalid.traverse_((entry, reason) => leaves(entry, reason, DropCause.Revalidation))
          _ <- events.emit(MempoolEvent.Synced(next.entries.size, next.bytes, invalid.size))
        yield invalid

      def removeTxsEvenIfValid(ids: Set[Bytes])(using Raise[F, StorageError]) =
        removing(ids, entry => events.emit(MempoolEvent.Removed(entry.id)), droppedNamed = false)

      def withdraw(reasons: Map[Bytes, Rejection])(using Raise[F, StorageError]) =
        removing(
          reasons.keySet,
          entry => reasons.get(entry.id).traverse_(leaves(entry, _, DropCause.Withdrawal)),
          droppedNamed = true
        )

      /** Remove `ids` and revalidate the rest; `droppedNamed` counts the named ones among the
        * dropped of the summary (`Synced`), as a withdrawal drops them.
        */
      private def removing(ids: Set[Bytes], report: Entry => F[Unit], droppedNamed: Boolean)(using
          Raise[F, StorageError]
      ): F[Vector[(Entry, Rejection)]] =
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
                      .complete(utxoStore, l.utxo, MempoolState.readSet(kept.map(_.held.in)))
                      .map(l.withUtxo)
                  )
                  activeEnv <- readEnv
                  revalidated <- F.blocking(current.withBase(completed).remove(ids, activeEnv))
                  (next, dependants) = revalidated
                  _ <- state.set(next)
                  _ <- version.update(_ + 1)
                  _ <- removed.traverse_(report)
                  _ <- dependants
                    .traverse_((entry, reason) => leaves(entry, reason, DropCause.Revalidation))
                  _ <- events.emit(
                    MempoolEvent.Synced(
                      next.entries.size,
                      next.bytes,
                      dependants.size + (if droppedNamed then removed.size else 0)
                    )
                  )
                yield dependants
          yield out
        }

      val source: TxSubmissionClient.Source[F] = new TxSubmissionClient.Source[F]:
        def after(ticket: Long, max: Int) =
          state.get.map(
            _.after(ticket)
              .take(max)
              .map(e => TxSubmissionClient.Held(e.id, e.size, e.ticket, e.era))
          )
        def awaitAfter(ticket: Long): F[Unit] =
          version.get.flatMap { seenVersion =>
            state.get.flatMap { s =>
              if s.after(ticket).nonEmpty then F.unit
              else version.waitUntil(_ > seenVersion) *> awaitAfter(ticket)
            }
          }
        // the transaction as it is held now, in the era of the pool's ledger
        def body(id: Bytes) =
          state.get.map(_.entries.find(_.id == id).map(e => TxSubmission2.EraTx(e.era, e.raw)))
        def reader = RelayedTransactions.of(build)

      def sink(fatal: Fatal => F[Unit]): TxSubmissionServer.Sink[F] =
        new TxSubmissionServer.Sink[F]:
          def reader = RelayedTransactions.of(build)
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
          // A crypto provider or the evaluator that fails on the transaction gives no verdict on it.
          // It is not a rejection: it closes this peer's connection, with the peer tried again
          // later and nothing recorded, as the exception does in the reference's inbound
          // TxSubmission2 thread
          // (consensus 82ecba32 Node/NodeKernel.hs `getMempoolWriter`, `Mempool/Update.hs`
          // `doAddTx`, `Node/RethrowPolicy.hs:40-45`). The transactions after it are not offered.
          def submit(originals: Vector[TxSubmission2.EraTx])(using Raise[F, NetworkError]) =
            originals.traverse_ { original =>
              StorageBoundary
                .run(fatal)(
                  added(
                    _ =>
                      MempoolState.read(Submission(original.era.index, original.original), build),
                    Source.Remote
                  )
                )
                .flatMap(
                  _.left.toOption
                    .map(_.rejection)
                    .flatMap(Mempool.peerConnectionEnd)
                    .traverse_(_.raise[F, Unit])
                )
            }
