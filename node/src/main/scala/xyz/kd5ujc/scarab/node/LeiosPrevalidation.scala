// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Clock}
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.mtl.Handle
import cats.syntax.all.*
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.consensus.leios.ProtVerParity
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.ledger.{StorageError, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.{Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.rules.DijkstraClosure
import xyz.kd5ujc.scarab.ledger.rules.TickFailure
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, SlotNo}
import xyz.kd5ujc.scarab.node.mempool.{DijkstraPoolEnv, DijkstraState, MempoolEnv}
import xyz.kd5ujc.scarab.node.read.TxClock
import xyz.kd5ujc.scarab.protocol.{EbPoint, Era, LeiosTxHash}
import xyz.kd5ujc.scarab.storage.{UtxoReadView, UtxoStore}

/** Pre-validation of the transactions a peer sends for an endorser block's closure, so that the
  * voter's closure check reapplies them instead of validating them in full inside the vote window
  * (an Improvement over the reference, approved by the owner on 2026-11-23 for #79, its design and
  * record at https://github.com/KD5UJC-XYZ/scarab/issues/79#issuecomment-7088710251).
  *
  * The reference records a fetched transaction in its LeiosTxCache as unapplied
  * (`withLockedInsertUnappliedTx`, `LeiosDemoLogic.hs:1561-1564` at ouroboros-consensus 2416493b),
  * so its closure check validates it in full (`validateEbClosure`, `LeiosVoting.hs:495-542`).
  * Reapplying (`reapplyTx`) skips only the static checks (witnesses, scripts, metadata and
  * integrity hashes) and runs every state-dependent one again against the closure's own state. The
  * static checks are a function of the transaction's bytes, the outputs its inputs resolve to and
  * the epoch's protocol parameters: an input names one output forever, and the parameters change
  * only at an epoch boundary. So a transaction is tagged validated here, under the state's
  * protocol-version parity, only when:
  *   - it is independent: every input its rules read, of it and of its sub-transactions
  *     ([[MirrorDijkstra.inputs]]), resolves in the selection tip's UTxO set, none an output of a
  *     transaction not yet on the chain;
  *   - the endorser block's slot is in the tip's epoch, so the parameters it is judged under are
  *     those of the epoch the block belongs to;
  *   - it passes the closure check's own full path ([[DijkstraState.applyIntervening]]) on the
  *     tip's state at the block's slot (or at the tip's, if that is later).
  *
  * A tag, like the reference's own, is the transaction's hash and the parity alone: the reference's
  * mempool and its votes tag a transaction validated under one epoch's parameters, and a closure of
  * a later epoch of the same parity reapplies it. A tag of this one has the same reach, no more. A
  * transaction that does not pass is not tagged, and its full check at vote time gives the verdict;
  * no negative result is kept. The closure check, the cache's one reader of tags, still applies
  * every transaction in order on the announcing block's state, so a closure's verdict does not
  * change, only which of its transactions it reapplies.
  *
  * Independent transactions share no state, so they are validated in parallel, each on the same
  * immutable state. Their witness signatures are verified by the rules themselves
  * ([[Verdicts.Direct]]), never through the node's signature cache: its permits and its take-once
  * verdicts are the voter's and the block validation's. The work runs on one background fiber fed
  * by a bounded queue that drops what does not fit, never in the vote's path: the closure check
  * reads the cache when it decides its steps, and a tag that lands later goes unused.
  */
object LeiosPrevalidation:
  /** A transactions reply's transactions, for the endorser block at `point`. */
  final case class Arrived(point: EbPoint, txs: Vector[(LeiosTxHash, Bytes)])

  /** Why nothing of a reply was pre-validated. */
  enum Skip:
    /** The selection's tip is not in Dijkstra. */
    case NoDijkstraTip

    /** The endorser block is not in the tip's epoch. */
    case OtherEpoch(tipEpoch: BigInt, blockEpoch: BigInt)

    /** The tip's state could not be ticked to the slot. */
    case Tick(cause: TickFailure)

    /** The spent outputs could not be read from the store. */
    case Store(cause: StorageError)

  /** The state a reply's transactions are judged on: the tip's, ticked to `env`'s slot. */
  final case class Base(state: DijkstraState, env: DijkstraPoolEnv, parity: ProtVerParity)

  /** What judging a reply on a base uses: the store of the outputs the tip's state does not hold,
    * the transaction cache, how many transactions are validated at once, and the trace.
    */
  final case class Work[F[_]](
      utxoStore: Option[UtxoReadView[F]],
      cache: LeiosTxCache[F],
      parallelism: Int,
      trace: LeiosKernelEvent[Nothing, Nothing] => F[Unit]
  )

  /** What pre-validation reads: the selection's chain, the mempool's environment, the clock of a
    * transaction, and the [[Work]].
    */
  final case class Env[F[_]](
      chain: F[VolatileChain[MirrorState]],
      poolEnv: F[MempoolEnv],
      txClock: TxClock[MirrorState],
      work: Work[F]
  )

  /** The replies the queue holds at most; one that does not fit is dropped. */
  val QueueDepth: Int = 256

  /** The base for the endorser block at `point`: the tip's state ticked to the block's slot, or to
    * the tip's where that is later, with the mempool's environment at that slot and the rules' own
    * signature verifier; a [[Skip]] when the tip is not in Dijkstra, the block is in another epoch,
    * or the tick fails.
    */
  def base[F[_]: Async](env: Env[F], point: EbPoint): F[Either[Skip, Base]] =
    (env.chain, env.poolEnv).mapN { (chain, pool) =>
      for
        nes <- MirrorStep
          .nesOf(chain.state)
          .flatMap(MirrorDijkstra.inDijkstra)
          .toRight(Skip.NoDijkstraTip)
        slot <- slotFor(pool.geometry, chain.tipRef.fold(BigInt(0))(_.slot), point.slot.toBigInt)
        at <- SlotNo
          .fromBigInt(slot)
          .leftMap(v => TickFailure.Vocab(v))
          .flatMap(LeiosClosureCheck.ticked(pool.rules, nes, _))
          .leftMap(Skip.Tick(_))
      yield Base(
        at,
        DijkstraPoolEnv
          .of(pool, slot)
          .copy(
            clock = env.txClock.at(chain.state, Era.Dijkstra, slot),
            verdicts = Verdicts.Direct
          ),
        LeiosClosureCheck.parityOf(at)
      )
    }

  /** The slot a block's transactions are judged at, on a tip at `tip`: the block's own, or the
    * tip's where that is later, the tip's state then ticked to its own slot as the closure check
    * ticks the announcing block's; none ([[Skip.OtherEpoch]]) unless the tip and the block are in
    * one epoch, whose parameters are then the ones the block is judged under.
    */
  private[node] def slotFor(
      epochs: xyz.kd5ujc.scarab.ledger.EpochGeometry,
      tip: BigInt,
      block: BigInt
  ): Either[Skip, BigInt] =
    val blockEpoch = epochs.epochOf(block)
    Either.cond(
      epochs.epochOf(tip) == blockEpoch,
      block.max(tip),
      Skip.OtherEpoch(epochs.epochOf(tip), blockEpoch)
    )

  /** The transactions of `decoded` whose every input resolves in `utxo` ([[MirrorDijkstra.inputs]],
    * at every level): those no transaction off the chain feeds.
    */
  def independent(
      decoded: Vector[(LeiosTxHash, Tx[TxLevel.Top, Dijkstra])],
      utxo: xyz.kd5ujc.scarab.ledger.Utxo
  ): Vector[(LeiosTxHash, Tx[TxLevel.Top, Dijkstra])] =
    decoded.filter((_, tx) => MirrorDijkstra.inputs(Vector(tx)).forall(utxo.get(_).isDefined))

  /** Pre-validate one reply ([[Arrived]]) and trace what it came to. */
  def prevalidate[F[_]: Async](env: Env[F])(arrived: Arrived): F[Unit] =
    Clock[F].monotonic.flatMap { start =>
      base(env, arrived.point).flatMap {
        case Left(skip) =>
          env.work.trace(LeiosKernelEvent.PrevalidationSkipped(arrived.point, skip))
        case Right(b) => prevalidateOn(env.work, arrived, b, start)
      }
    }

  /** One reply pre-validated on the base `b`: the transactions not already tagged under its parity,
    * decoded, their outputs read in one batch, the independent ones validated in parallel, those
    * that pass tagged; traced with its counts.
    */
  private[node] def prevalidateOn[F[_]: Async](
      env: Work[F],
      arrived: Arrived,
      b: Base,
      start: FiniteDuration
  ): F[Unit] =
    for
      cached <- env.cache.lookup
      // a transaction the cache already holds validated under this parity needs nothing; one that
      // does not decode is the closure check's to report
      decoded = arrived.txs
        .filterNot((h, _) => cached(h).contains(Right(b.parity)))
        .flatMap((h, raw) => DijkstraClosure.deserializeTxForLeios(raw).toOption.map(h -> _))
      read <- Handle
        .allow[StorageError](
          UtxoStore
            .complete(env.utxoStore, b.state.utxo, MirrorDijkstra.inputs(decoded.map(_._2)))
            .map(_.asRight[StorageError])
        )
        .rescue(error => Async[F].pure(error.asLeft))
      _ <- read.fold(
        error => env.trace(LeiosKernelEvent.PrevalidationSkipped(arrived.point, Skip.Store(error))),
        utxo => validated(env, arrived, b, utxo, independent(decoded, utxo), start)
      )
    yield ()

  private def validated[F[_]: Async](
      env: Work[F],
      arrived: Arrived,
      b: Base,
      utxo: xyz.kd5ujc.scarab.ledger.Utxo,
      ready: Vector[(LeiosTxHash, Tx[TxLevel.Top, Dijkstra])],
      start: FiniteDuration
  ): F[Unit] =
    val on = b.state.withUtxo(utxo)
    for
      // each on the same immutable state; a throw of the rules is no verdict here, and the closure
      // check that meets the transaction meets it too
      passed <- ready.parTraverseN(env.parallelism.max(1))((h, tx) =>
        Async[F].blocking(
          Either.catchNonFatal(on.applyIntervening(tx, b.env)).toOption.flatMap(_.toOption).as(h)
        )
      )
      tagged = passed.flatten
      _ <- env.cache.insertApplied(tagged, b.parity).whenA(tagged.nonEmpty)
      end <- Clock[F].monotonic
      _ <- env.trace(
        LeiosKernelEvent.TxsPrevalidated(
          arrived.point,
          arrived.txs.size,
          ready.size,
          tagged.size,
          end - start
        )
      )
    yield ()

  /** The background fiber: each reply the queue holds, pre-validated in turn. */
  def run[F[_]: Async](env: Env[F], queue: Queue[F, Arrived]): F[Unit] =
    queue.take.flatMap(prevalidate(env)).foreverM

  /** A reply offered to the queue, dropped when it is full (pre-validation is an optimisation). */
  def offer[F[_]: Async](queue: Queue[F, Arrived]): (EbPoint, Vector[(LeiosTxHash, Bytes)]) => F[
    Unit
  ] = (point, txs) => queue.tryOffer(Arrived(point, txs)).void.whenA(txs.nonEmpty)
