// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Id
import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.{Queue, Semaphore}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.concurrent.{Signal, SignallingRef}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable.TreeMap
import xyz.kd5ujc.scarab.codec.{Bytes, Tree}
import xyz.kd5ujc.scarab.ledger.{Header, Phase2, ProtocolParams, Tx, TxIn, TxOut, Utxo}
import xyz.kd5ujc.scarab.ledger.rules.StAnnTx
import xyz.kd5ujc.scarab.ledger.state.{EraTag, ShelleyBasedEra}
import xyz.kd5ujc.scarab.ledger.TranslationError
import xyz.kd5ujc.scarab.network.Fibers
import xyz.kd5ujc.scarab.plutus.{
  EvalFailure,
  EvalRequest,
  EvalResult,
  ExUnits,
  PlutusData,
  ScriptEvaluator
}

/** The Plutus scripts of queued blocks, evaluated ahead of the worker (#1499, item 4): while the
  * selection worker applies one block, the scripts of the blocks waiting behind it in the add-block
  * queue are evaluated, and their results kept ([[ScriptsAhead.Results]]), so that the worker, when
  * its rules evaluate a script, finds the result there instead of running the machine.
  *
  * Where the scripts are asked for: each Shelley-based block admitted to the queue, once it has
  * passed every check made before the queue, asks for them ([[ahead]], from `BlockSelection` 's
  * admission, after its store reads and its signature checks). The request is queued (at most
  * [[ScriptsAhead.Requests]], further ones dropped) and never waits, as it runs under the queue's
  * publication. One speculator fiber takes the requests in the queue's order, which is the order
  * the worker takes the blocks in, one block at a time.
  *
  * What a block's scripts are evaluated with: the selected chain at the worker's tip as the
  * speculator finds it ([[ScriptsAhead.Tip]]): its parameters, its era history and its UTxO set,
  * with the outputs of the blocks queued between that tip and the block (the speculator walks the
  * block's predecessors back to the tip through the blocks it took before, and passes the block by
  * where it cannot), and of the block's own transactions. Each transaction's requests are built by
  * the rules' own collection (`Phase2.collect`, Conway's `collectPlutusScriptsWithContext`), and a
  * transaction an input of which none of these holds is skipped. A block whose redeemers declare
  * more execution units than the block may hold is skipped whole, so what is evaluated ahead stays
  * within what an admissible block declares, as the worker's own preparation does
  * (`Phase2.Program.preparable`).
  *
  * Why a result found is the worker's result. A request ([[EvalRequest]]) is everything an
  * evaluation's verdict depends on: the language, the protocol version, the script's bytes, its
  * arguments (the datum, the redeemer and the script context, in which the transaction, its
  * resolved inputs, the era's shape and the validity interval as POSIX time are), the cost model
  * and the budget; the evaluator is a deterministic function of it (`ScarabEvaluator`). A result is
  * kept under its whole request and given only for a request equal to it, field by field and the
  * data by structure, never by a digest ([[ScriptsAhead.Results.lookup]]). So the speculation may
  * be wrong in any way (a rollback, another fork, parameters changed at an epoch boundary, an
  * output read from another chain): its requests then differ from the worker's, are not found, and
  * the worker evaluates as without this. An answer that is no verdict (`EvalFailure.Unsupported`: a
  * stack exhausted, a defect) is never kept, as the evaluator's own caches keep none.
  *
  * What is kept: the results of each block until the worker has applied it (the worker reaches the
  * next block) or passes it by, and within bounds of blocks, entries and bytes
  * ([[ScriptsAhead.Limits]]); a result beyond them is not kept. The machines run on threads of
  * their own, at most [[ScriptsAhead.parallelism]] at once, never on the worker's Plutus permits.
  *
  * The reference evaluates each script when its rule reaches it (`evalPlutusScripts`,
  * cardano-ledger f649f975 `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/Evaluate.hs`
  * 188-207), one block after another on the ChainDB's add-block runner (ouroboros-consensus
  * 82ecba32 `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Storage/ChainDB/Impl/
  * Background.hs` 658-708). Only when a result is computed changes, never which result, which block
  * it decides or when: an Optimisation (REF-6), approved for #1499 item 4.
  */
final class ScriptsAhead[F[_]: Async] private (
    requests: Option[Queue[F, ScriptsAhead.Request]],
    slots: SignallingRef[F, Map[Bytes, ScriptsAhead.Slot]],
    current: Ref[F, Option[Bytes]],
    tallies: Ref[F, ScriptsAhead.Tallies],
    val results: ScriptsAhead.Results,
    speculator: ScriptsAhead.Speculator[F],
    ended: F[Fatal]
):
  import ScriptsAhead.{Recent, Request, Scripts, Slot, Tallies}
  private val F = Async[F]

  /** Completes only if the speculator ends, which only a defect outside the speculation of one
    * block can make it do: the value it ended with, for the owner to raise in its own fiber
    * (FIB-2). One made by [[ScriptsAhead.idle]] has no speculator, and this never completes.
    */
  def stopped: F[Fatal] = ended

  /** Ask for the scripts of the queued block `raw` ahead of the worker. Never waits: a request the
    * queue has no room for is dropped, and a block already asked for is not asked again.
    */
  def ahead(header: Header, raw: Bytes): F[Unit] =
    requests.traverse_ { queue =>
      val hash = header.hash
      slots
        .modify(held =>
          if held.contains(hash) then (held, false) else (held.updated(hash, Slot.Queued), true)
        )
        .flatMap(fresh =>
          queue
            .tryOffer(Request(header, raw))
            .flatMap(offered => slots.update(_ - hash).unlessA(offered))
            .whenA(fresh)
        )
    }

  /** The worker reached the block `hash` and prepares it: the block before it, which the worker has
    * applied or refused since, has its results dropped; this one's are kept for its rules, and no
    * evaluation of it not yet started is started.
    */
  def reached(hash: Bytes): F[Unit] =
    current.getAndSet(Some(hash)).flatMap(_.filterNot(_ == hash).traverse_(done)) *>
      slots.update(held =>
        held.get(hash) match
          case Some(Slot.Running) => held.updated(hash, Slot.Reached)
          case Some(_)            => held - hash
          case None               => held
      )

  /** The worker took the block `hash` from the queue and passes it by, unprepared: its results are
    * dropped, and no evaluation of it not yet started is started.
    */
  def passed(hash: Bytes): F[Unit] =
    slots.update(held =>
      held.get(hash) match
        case Some(Slot.Running) => held.updated(hash, Slot.Passed)
        case Some(_)            => held - hash
        case None               => held
    ) *> dropped(hash)

  private def dropped(hash: Bytes): F[Unit] = F.delay(results.drop(hash))

  /** The worker is done with the block `hash`: an evaluation of it still running keeps nothing, and
    * its results are dropped.
    */
  private def done(hash: Bytes): F[Unit] =
    slots.update(held =>
      held.get(hash) match
        case Some(Slot.Running) | Some(Slot.Reached) => held.updated(hash, Slot.Passed)
        case _                                       => held
    ) *> dropped(hash)

  /** What the speculator did, since the start. */
  def tallied: F[Tallies] = tallies.get

  /** The blocks asked for and not yet done with, as they change, for the suites. */
  private[node] def held: Signal[F, Map[Bytes, Slot]] = slots

  /** The speculator's loop, in an error scope of its own: it ends only with a failure, as the value
    * it ends with. Each block is speculated in a scope of its own too, and its failure, or a defect
    * in it, is discarded: it is no information, as the worker's rules evaluate what is not found.
    */
  private def speculating(queue: Queue[F, Request]): F[Either[Fatal, Nothing]] =
    Fibers.scoped[F, Fatal, Nothing](Fatal.Defect(_))(
      F.tailRecM(Recent.empty) { recent =>
        queue.take.flatMap { request =>
          val hash = request.header.hash
          slots
            .modify(held =>
              held.get(hash) match
                case Some(Slot.Queued) => (held.updated(hash, Slot.Running), true)
                case _                 => (held, false)
            )
            .flatMap { started =>
              Fibers
                .scoped[F, Fatal, Recent](Fatal.Defect(_))(speculated(request, recent, started))
                .map(_.getOrElse(recent))
                .guarantee(finished(hash).whenA(started))
            }
            .map(Left(_))
        }
      }
    )

  /** The block of `request` speculated: its outputs recorded for the blocks after it whatever
    * happens to its scripts, and its scripts evaluated if the speculator `started` it and its
    * predecessors lead back to the tip.
    */
  private def speculated(request: Request, recent: Recent, started: Boolean): F[Recent] =
    val header = request.header
    speculator.tip.flatMap { tip =>
      tip.scripts(header, request.raw) match
        case None => F.pure(recent)
        case Some(scripts) =>
          val next = recent.recorded(header.hash, header.prevHash, scripts.created)
          val skip = tallies.update(_.skip(scripts.transactions))
          val run = recent.since(header.prevHash, tip.hash) match
            case None          => skip
            case Some(between) => evaluated(header.hash, tip, scripts, between)
          (if started then speculator.wanted(header).ifM(run, skip) else skip).as(next)
    }

  /** The scripts of the block `hash` evaluated on `tip` with the outputs of the blocks `between`,
    * each kept while the block is the speculator's or the worker has reached it.
    */
  private def evaluated(
      hash: Bytes,
      tip: ScriptsAhead.Tip[F],
      scripts: Scripts,
      between: Map[TxIn, TxOut]
  ): F[Unit] =
    val local = between ++ scripts.created
    tip.outputs(scripts.reads -- local.keySet).flatMap { read =>
      val outputs = local ++ read
      val collected = scripts.collect(outputs.get)
      tallies.update(_.skip(collected.skipped)) *>
        collected.requests
          .parTraverseN(speculator.parallelism) { request =>
            wanted(hash).ifM(
              speculator.evaluate(request).flatMap(kept(hash, request, _)),
              tallies.update(_ |+| Tallies.abandoned)
            )
          }
          .void
    }

  /** Whether the block `hash` 's evaluations still go on: neither reached nor passed by. */
  private def wanted(hash: Bytes): F[Boolean] =
    slots.get.map(_.get(hash).contains(Slot.Running))

  /** `result` kept for `request` unless it is no verdict or the worker passed the block by. */
  private def kept(hash: Bytes, request: EvalRequest, result: EvalResult): F[Unit] =
    result match
      case EvalResult.Failure(_: EvalFailure.Unsupported, _, _) => tallies.update(_.noVerdict)
      case verdict =>
        slots.get.map(_.get(hash)).flatMap {
          case Some(Slot.Running) | Some(Slot.Reached) =>
            F.delay(results.put(hash, request, verdict))
              .flatMap(kept => tallies.update(t => if kept then t.kept else t.overfull)) *>
              // a block passed by while its result was being kept: dropped again
              slots.get.map(_.get(hash)).flatMap {
                case Some(Slot.Running) | Some(Slot.Reached) => F.unit
                case _                                       => dropped(hash)
              }
          case _ => F.unit
        }

  /** The speculation of `hash` is over: the slot of a block the worker reached or passed by while
    * it ran is gone, the others' kept until the worker comes to the block.
    */
  private def finished(hash: Bytes): F[Unit] =
    slots.update(held =>
      held.get(hash) match
        case Some(Slot.Running)                     => held.updated(hash, Slot.Done)
        case Some(Slot.Reached) | Some(Slot.Passed) => held - hash
        case _                                      => held
    )

object ScriptsAhead:
  /** Requests waiting for the speculator: more than the add-block queue holds (ten, and the one
    * being added), as the verifier's ([[VerifyAhead.Requests]]).
    */
  val Requests = 16

  /** The machines run ahead at once, of the machine's `cores`: a quarter of them, at most two, and
    * none with fewer than four. They run beside the worker (one thread of the CPU pool, and up to
    * four Plutus workers for a block's own scripts, `ParallelPlutus.Workers.default`), the
    * signature checks made ahead (`VerifyAhead.parallelism`, half the cores) and the read-ahead's
    * readers, so a quarter leaves the rest their share. Two are enough: a mainnet Babbage block's
    * scripts take some 23 ms of CPU (#1499's profile, about seven scripts of 3.3 ms), so about 12
    * ms on two threads, within the some 22 ms the worker takes to apply the block in front of it.
    * They take none of the worker's Plutus permits, so a script the worker must evaluate itself
    * never waits behind a later block's.
    */
  def parallelism(cores: Int): Option[Int] = Option.when(cores >= 4)((cores / 4).min(2))

  /** The bounds of what is kept: the results of at most `blocks` blocks (the oldest block's dropped
    * first), at most `entries` results and `bytes` of their estimated heap ([[Results.weight]]); a
    * result beyond the last two is not kept, so the results of the blocks the worker reaches first
    * stay.
    */
  final case class Limits(blocks: Int, entries: Int, bytes: Long)

  /** Twice the blocks that can be queued (ten and the one being added) and in flight, 1,024 results
    * (a mainnet block holds some seven scripts) and 64 MiB.
    */
  val Bounds: Limits = Limits(blocks = 32, entries = 1024, bytes = 64L * 1024 * 1024)

  /** A block asked for: its header and its bytes. */
  final case class Request(header: Header, raw: Bytes)

  /** Where a block asked for is: waiting for the speculator, being speculated, reached by the
    * worker or passed by while being speculated, or speculated and not yet reached.
    */
  enum Slot:
    case Queued, Running, Reached, Passed, Done

  /** What the speculator did: scripts evaluated and kept; transactions whose scripts were not
    * evaluated ahead (`skipped`: an input not resolved, a collection refused, the block's era or
    * chain not the tip's, the block reached or passed by first, its units beyond the block's
    * limit); scripts whose evaluation was not started because the worker reached or passed their
    * block (`abandoned`); scripts evaluated and not kept, the bounds being full (`full`) or the
    * answer no verdict (`unanswered`).
    */
  final case class Tallies(
      evaluated: Long,
      skipped: Long,
      abandoned: Long,
      full: Long,
      unanswered: Long
  ):
    def skip(transactions: Int): Tallies = copy(skipped = skipped + transactions)
    def kept: Tallies = copy(evaluated = evaluated + 1)
    def overfull: Tallies = copy(full = full + 1)
    def noVerdict: Tallies = copy(unanswered = unanswered + 1)

  object Tallies:
    val zero: Tallies = Tallies(0, 0, 0, 0, 0)
    val abandoned: Tallies = zero.copy(abandoned = 1)
    given cats.Monoid[Tallies] with
      def empty: Tallies = zero
      def combine(a: Tallies, b: Tallies): Tallies =
        Tallies(
          a.evaluated + b.evaluated,
          a.skipped + b.skipped,
          a.abandoned + b.abandoned,
          a.full + b.full,
          a.unanswered + b.unanswered
        )

  /** The requests of a block's transactions, and the transactions with redeemers whose requests
    * were not collected (`skipped`).
    */
  final case class Collected(requests: Vector[EvalRequest], skipped: Int)

  /** A block's scripts as the speculator sees them: its `transactions` with redeemers, the outputs
    * it creates (`created`: a valid transaction's outputs, an invalid one's collateral return),
    * what its transactions with redeemers read from the UTxO set before it (`reads`: their inputs
    * and reference inputs), and their requests, collected from the outputs of those (`collect`).
    */
  final case class Scripts(
      transactions: Int,
      created: Map[TxIn, TxOut],
      reads: Set[TxIn],
      collect: (TxIn => Option[TxOut]) => Collected
  )

  /** The selected chain at the worker's tip, as the speculator reads it: its tip's hash; the
    * scripts of a block on its basis (its parameters, era and era history), or none where they are
    * not speculated (another era than the tip's, Byron's or Dijkstra's, a block that does not
    * decode, units beyond the block's limit); and the outputs its UTxO set holds for `keys`, read
    * without taking what the worker's read-ahead keeps.
    */
  trait Tip[F[_]]:
    def hash: Option[Bytes]
    def scripts(header: Header, raw: Bytes): Option[Scripts]
    def outputs(keys: Set[TxIn]): F[Map[TxIn, TxOut]]

  /** What the speculator does with a queued block: whether the worker would still prepare it now
    * (`wanted`, the verifier's two checks, [[VerifyAhead.Verifier]]), the selected chain at the
    * worker's tip (`tip`), and the evaluation of a request on the speculator's own threads
    * (`evaluate`, the rules' evaluator, at most `parallelism` at once).
    */
  final case class Speculator[F[_]](
      wanted: Header => F[Boolean],
      tip: F[Tip[F]],
      evaluate: EvalRequest => F[EvalResult],
      parallelism: Int
  )

  /** The outputs created by the blocks the speculator took most recently (at most
    * [[Bounds.blocks]]), each with its predecessor, newest first.
    */
  final case class Recent(blocks: Vector[(Bytes, Option[Bytes], Map[TxIn, TxOut])]):
    def recorded(hash: Bytes, prev: Option[Bytes], created: Map[TxIn, TxOut]): Recent =
      Recent(((hash, prev, created) +: blocks.filterNot(_._1 == hash)).take(Bounds.blocks))

    /** The outputs created by the blocks from `tip` (exclusive) to the block whose predecessor is
      * `prev`, when its predecessors lead back to `tip` through the blocks recorded; none where
      * they do not.
      */
    def since(prev: Option[Bytes], tip: Option[Bytes]): Option[Map[TxIn, TxOut]] =
      @tailrec def go(
          at: Option[Bytes],
          acc: Map[TxIn, TxOut],
          steps: Int
      ): Option[Map[TxIn, TxOut]] =
        if at == tip then Some(acc)
        else if steps > blocks.size then None
        else
          at.flatMap(h => blocks.find(_._1 == h)) match
            case None                    => None
            case Some((_, before, made)) => go(before, acc ++ made, steps + 1)
      go(prev, Map.empty, 0)

  object Recent:
    val empty: Recent = Recent(Vector.empty)

  /** How the worker's rules found their scripts: answered from what was kept (`hit`), nothing kept
    * for the script and budget (`miss`), or results kept for them under other requests only
    * (`stale`: another context, datum, redeemer, cost model or protocol version).
    */
  final case class Counts(hit: Long, miss: Long, stale: Long)

  object Counts:
    val zero: Counts = Counts(0, 0, 0)

  /** Where a kept result is found: the script's language, protocol version, bytes and budget, the
    * fields whose hash is cheap (a script's hash is cached by its bytes); the request is then
    * compared whole.
    */
  private final case class Locator(
      language: xyz.kd5ujc.scarab.plutus.PlutusLanguage,
      protocol: Int,
      script: Bytes,
      budget: ExUnits
  )

  private object Locator:
    def of(request: EvalRequest): Locator =
      Locator(request.language, request.protocolMajor, request.script, request.budget)

  /** A kept result: its block, its whole request, the result and its estimated weight. */
  private final case class Entry(
      block: Bytes,
      request: EvalRequest,
      result: EvalResult,
      weight: Long
  )

  /** The results kept, a value: by locator, with each block's entries in the order of their blocks'
    * first result (`order`) so that the oldest block is dropped first.
    */
  private final case class Held(
      entries: Map[Locator, Vector[Entry]],
      blocks: Map[Bytes, Long],
      order: TreeMap[Long, Bytes],
      next: Long,
      size: Int,
      bytes: Long,
      counts: Counts
  ):
    def found(request: EvalRequest): Option[Either[Unit, EvalResult]] =
      entries
        .get(Locator.of(request))
        .map(kept => kept.find(_.request == request).map(_.result).toRight(()))

    def counted(found: Option[Either[Unit, EvalResult]]): Held =
      copy(counts = found match
        case None           => counts.copy(miss = counts.miss + 1)
        case Some(Left(_))  => counts.copy(stale = counts.stale + 1)
        case Some(Right(_)) => counts.copy(hit = counts.hit + 1))

    def put(limits: Limits, entry: Entry): Option[Held] =
      val locator = Locator.of(entry.request)
      val present = entries.get(locator).exists(_.exists(_.request == entry.request))
      if present then Some(this)
      else
        val room =
          if blocks.contains(entry.block) || blocks.sizeIs < limits.blocks then this
          else order.headOption.fold(this)(head => dropped(head._2))
        Option.when(room.size < limits.entries && room.bytes + entry.weight <= limits.bytes)(
          room.copy(
            entries =
              room.entries.updated(locator, room.entries.getOrElse(locator, Vector.empty) :+ entry),
            blocks =
              if room.blocks.contains(entry.block) then room.blocks
              else room.blocks.updated(entry.block, room.next),
            order =
              if room.blocks.contains(entry.block) then room.order
              else room.order.updated(room.next, entry.block),
            next = room.next + 1,
            size = room.size + 1,
            bytes = room.bytes + entry.weight
          )
        )

    def dropped(block: Bytes): Held =
      blocks.get(block).fold(this) { stamp =>
        val (gone, kept) =
          entries.foldLeft((Vector.empty[Entry], Map.empty[Locator, Vector[Entry]])) {
            case ((gone, kept), (locator, held)) =>
              val (out, in) = held.partition(_.block == block)
              (gone ++ out, if in.isEmpty then kept else kept.updated(locator, in))
          }
        copy(
          entries = kept,
          blocks = blocks - block,
          order = order - stamp,
          size = size - gone.size,
          bytes = bytes - gone.iterator.map(_.weight).sum
        )
      }

  private object Held:
    val empty: Held = Held(Map.empty, Map.empty, TreeMap.empty, 0L, 0, 0L, Counts.zero)

  /** The results evaluated ahead, kept for the worker's rules: a value behind an atomic reference,
    * as the evaluator's own caches are (`BoundedCache`), so the rules' evaluator, which runs on the
    * Plutus workers' threads as a plain function ([[evaluator]]), reads it without an effect. It is
    * memoisation only: a result is given only for a request equal to the one it was evaluated for.
    */
  final class Results private (limits: Limits, state: AtomicReference[Held]):
    /** The result kept for `request`, if one was kept for an equal request; the lookup counted. */
    def lookup(request: EvalRequest): Option[EvalResult] =
      val found = state.get.found(request)
      val _ = state.updateAndGet(_.counted(found))
      found.flatMap(_.toOption)

    /** `underlying` answering first from what was kept: a request equal to a kept one gets the kept
      * result, any other `underlying` 's.
      */
    def evaluator(underlying: ScriptEvaluator[Id]): ScriptEvaluator[Id] =
      request => lookup(request).getOrElse(underlying.evaluate(request))

    /** `result` kept for `request`, of the block `block`; false where the bounds leave no room. */
    private[node] def put(block: Bytes, request: EvalRequest, result: EvalResult): Boolean =
      val entry = Entry(block, request, result, Results.weight(request, result))
      @tailrec def go(): Boolean =
        val now = state.get
        now.put(limits, entry) match
          case None       => false
          case Some(next) => if state.compareAndSet(now, next) then true else go()
      go()

    /** The results of `block` dropped. */
    private[node] def drop(block: Bytes): Unit =
      val _ = state.updateAndGet(_.dropped(block))

    /** How the rules found their scripts, since the start. */
    def counts: Counts = state.get.counts

    /** The results kept and their estimated bytes, for the suites. */
    private[node] def held: (Int, Long) =
      val now = state.get
      (now.size, now.bytes)

  object Results:
    def create(limits: Limits): Results = new Results(limits, new AtomicReference(Held.empty))

    /** The estimated heap of a kept result: its request's script and arguments, its cost model, and
      * the result's logs, with a fixed charge per object.
      */
    def weight(request: EvalRequest, result: EvalResult): Long =
      val logs = (result match
        case EvalResult.Success(_, logs)    => logs
        case EvalResult.Failure(_, _, logs) => logs
      ).iterator.map(_.length.toLong * 2 + 48).sum
      256L + request.script.size + request.costModel.size * 24L +
        request.args.iterator.map(data).sum + logs

    /** The estimated heap of `root`, walked with a stack of its own (STK-1). */
    private def data(root: PlutusData): Long =
      val tree = summon[Tree[PlutusData]]
      @tailrec def go(stack: List[PlutusData], total: Long): Long = stack match
        case Nil => total
        case d :: rest =>
          val own = d match
            case PlutusData.B(b) => 48L + b.size
            case _               => 48L
          go(tree.children(d).foldLeft(rest)((s, c) => c :: s), total + own)
      go(List(root), 0L)

  /** The requests of the transactions with redeemers `txs` of a block of an era `E` before Conway,
    * each collected as the rules collect it (`Phase2.collect`, through the transaction annotated
    * with the outputs it reads, `StAnnTx`) with the parameters `pp` and the slot to time
    * translation `posix`, from the outputs `outputs` gives; a transaction an input or reference
    * input of which it does not give, or whose collection fails, is skipped.
    */
  def flat[E <: EraTag](
      txs: Vector[Tx],
      pp: ProtocolParams,
      posix: BigInt => Either[TranslationError, BigInt],
      outputs: TxIn => Option[TxOut]
  )(using ShelleyBasedEra[E]): Collected =
    txs.foldLeft(Collected(Vector.empty, 0)) { (acc, tx) =>
      (tx.body.inputs ++ tx.body.referenceInputs).traverse(in => outputs(in).map(in -> _)) match
        case None => acc.copy(skipped = acc.skipped + 1)
        case Some(read) =>
          val ann = StAnnTx(tx, Utxo.fromMap(read.toMap))
          Phase2
            .collect[E](tx, pp, ann.scripts, ann.spentByInput, ann.referencedByInput, posix)
            .fold(
              _ => acc.copy(skipped = acc.skipped + 1),
              requests => acc.copy(requests = acc.requests ++ requests.map(_._2))
            )
    }

  /** The outputs a decoded transaction creates as the UTxO set holds them: a valid one's outputs,
    * an invalid one's collateral return, after its outputs' indices.
    */
  def created(tx: Tx): Map[TxIn, TxOut] =
    val id = tx.body.id
    if tx.isValid then
      tx.body.outputs.iterator.zipWithIndex.map((out, i) => TxIn(id, BigInt(i)) -> out).toMap
    else
      tx.body.collateralReturn.fold(Map.empty[TxIn, TxOut])(out =>
        Map(TxIn(id, BigInt(tx.body.outputs.size)) -> out)
      )

  /** The scripts of queued blocks speculated by `speculator` on one fiber, which stops when the
    * resource is released, into `results`. A speculator that ends gives its value to
    * [[ScriptsAhead.stopped]].
    */
  def resource[F[_]: Async](
      speculator: Speculator[F],
      results: Results
  ): Resource[F, ScriptsAhead[F]] =
    for
      queue <- Resource.eval(Queue.dropping[F, Request](Requests))
      slots <- Resource.eval(SignallingRef.of[F, Map[Bytes, Slot]](Map.empty))
      current <- Resource.eval(Ref.of[F, Option[Bytes]](None))
      tallies <- Resource.eval(Ref.of[F, Tallies](Tallies.zero))
      ended <- Resource.eval(Deferred[F, Fatal])
      ahead = new ScriptsAhead[F](
        Some(queue),
        slots,
        current,
        tallies,
        results,
        speculator,
        ended.get
      )
      _ <- ahead
        .speculating(queue)
        .flatMap(_.fold(ended.complete(_).void, never => never))
        .background
    yield ahead

  /** The node's evaluation of a request ahead: on a blocking thread, at most `threads` at once,
    * none of them the worker's Plutus permits. Blocking rather than interruptible, as the worker's
    * own: a release waits for the machines it started, which a block's limit of units bounds.
    */
  def evaluating[F[_]: Async](
      underlying: ScriptEvaluator[Id],
      threads: Int
  ): F[EvalRequest => F[EvalResult]] =
    Semaphore[F](threads.toLong).map(permits =>
      request => permits.permit.use(_ => Async[F].blocking(underlying.evaluate(request)))
    )

  /** Evaluates nothing ahead ([[ahead]] does nothing): every script the rules evaluate is a miss.
    */
  def idle[F[_]: Async](results: Results): F[ScriptsAhead[F]] =
    (
      SignallingRef.of[F, Map[Bytes, Slot]](Map.empty),
      Ref.of[F, Option[Bytes]](None),
      Ref.of[F, Tallies](Tallies.zero)
    ).mapN((slots, current, tallies) =>
      new ScriptsAhead[F](
        None,
        slots,
        current,
        tallies,
        results,
        Speculator[F](
          _ => Async[F].pure(false),
          Async[F].never[Tip[F]],
          _ => Async[F].never[EvalResult],
          1
        ),
        Async[F].never[Fatal]
      )
    )
