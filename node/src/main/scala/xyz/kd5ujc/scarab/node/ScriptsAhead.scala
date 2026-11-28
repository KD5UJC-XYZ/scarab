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
import xyz.kd5ujc.scarab.ledger.era.{
  ContextInputs,
  ContextsAhead,
  EraTx,
  EraTxBody,
  KeptContexts,
  PlutusWithContext,
  Tx as EraTxOf,
  TxLevel
}
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
  * publication. The speculator takes the requests in the queue's order, which is the order the
  * worker takes the blocks in, in two stages that run beside each other: one reads each block, the
  * outputs it reads and its transactions' collections, the other evaluates the requests of the
  * blocks read, two blocks at once on the same permits, which the requests of a block ask for all
  * at once when its evaluation starts, so mostly before the next block's. So a block is read and
  * collected while the scripts of the one before run.
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
  * stack exhausted, a defect) is never kept, as the evaluator's own caches keep none; nor is a
  * machine failure holding the term it is about, which can reach more than the bounds can see
  * ([[ScriptsAhead.Results.keeps]]).
  *
  * The collections, kept too (#1499): each transaction's collection is kept with everything it was
  * collected from and what it asked the clock, made by the rules' own code (`Phase2.Kept`,
  * `KeptContexts`), and the rules take it only where it is their own collection
  * (`Phase2.collected`, `ContextInputs.collected`): their inputs equal, field by field and the data
  * by structure, and their clock answering the slots it asked as it was answered. They then build
  * no script context and compare none, and their evaluator finds each result by the request itself,
  * the same object, before it compares any ([[ScriptsAhead.Results.lookup]]). Otherwise they
  * collect as without this.
  *
  * What is kept: the results and collections of each block until the worker has applied it (the
  * worker takes the next block) or passes it by, and within bounds of blocks, entries and bytes
  * ([[ScriptsAhead.Limits]]); one beyond them is not kept. The machines run on threads of their
  * own, at most [[ScriptsAhead.parallelism]] at once, never on the worker's Plutus permits.
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
    permits: Semaphore[F],
    ended: F[Fatal]
):
  import ScriptsAhead.{Plan, Recent, Request, Scripts, Slot, Tallies}
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
            .flatMap(offered => (slots.update(_ - hash) *> tallies.update(_.drop)).unlessA(offered))
            .whenA(fresh)
        )
    }

  /** The worker reached the block `hash` and prepares it: the block before it, which the worker has
    * applied or refused since, has its results dropped; this one's are kept for its rules, and no
    * evaluation of it not yet started is started. Where the speculation of the block was is tallied
    * ([[Tallies.reached]]).
    */
  def reached(hash: Bytes): F[Unit] =
    current.getAndSet(Some(hash)).flatMap(_.filterNot(_ == hash).traverse_(done)) *>
      slots
        .modify(held =>
          held.get(hash) match
            case Some(Slot.Preparing)  => (held.updated(hash, Slot.Reached), Some(Slot.Preparing))
            case Some(Slot.Evaluating) => (held.updated(hash, Slot.Reached), Some(Slot.Evaluating))
            case Some(other)           => (held - hash, Some(other))
            case None                  => (held, None)
        )
        .flatMap(was => tallies.update(_.reachedAt(was)))

  /** The worker took the block `hash` from the queue and passes it by, unprepared: its results and
    * those of the block before it, which the worker is done with, are dropped, and no evaluation of
    * it not yet started is started.
    */
  def passed(hash: Bytes): F[Unit] =
    current.getAndSet(None).flatMap(_.filterNot(_ == hash).traverse_(done)) *>
      slots.update(held =>
        held.get(hash) match
          case Some(Slot.Preparing) | Some(Slot.Evaluating) => held.updated(hash, Slot.Passed)
          case Some(_)                                      => held - hash
          case None                                         => held
      ) *> dropped(hash)

  private def dropped(hash: Bytes): F[Unit] = F.delay(results.drop(hash))

  /** The worker is done with the block `hash`: an evaluation of it still running keeps nothing, and
    * its results are dropped.
    */
  private def done(hash: Bytes): F[Unit] =
    slots.update(held =>
      held.get(hash) match
        case Some(Slot.Preparing) | Some(Slot.Evaluating) | Some(Slot.Reached) =>
          held.updated(hash, Slot.Passed)
        case _ => held
    ) *> dropped(hash)

  /** What the speculator did, since the start. */
  def tallied: F[Tallies] = tallies.get

  /** The blocks asked for and not yet done with, as they change, for the suites. */
  private[node] def held: Signal[F, Map[Bytes, Slot]] = slots

  /** The speculator's loop, in an error scope of its own: it ends only with a failure, as the value
    * it ends with. Its two stages run beside each other: one prepares the blocks in the queue's
    * order ([[planned]]: reads them, their outputs and their collections, which it keeps for the
    * rules at once), the other evaluates the prepared blocks' requests ([[evaluatedPlan]]), so the
    * next block is read and collected while the scripts of the one before run. Each block is
    * speculated in scopes of its own, and their failure, or a defect in them, is discarded: it is
    * no information, as the worker's rules collect and evaluate what is not found.
    */
  private def speculating(queue: Queue[F, Request]): F[Either[Fatal, Nothing]] =
    Fibers.scoped[F, Fatal, Nothing](Fatal.Defect(_))(
      fs2.Stream
        .fromQueueUnterminated(queue)
        .evalMapAccumulate(Recent.empty)(planned)
        .map(_._2)
        // the evaluations of two blocks at once, so that a block's last scripts do not keep the
        // threads from the next's
        .parEvalMapUnordered(ScriptsAhead.Evaluating)(evaluatedPlan)
        .compile
        .drain *> F.never[Nothing]
    )

  /** The block of `request` prepared on `recent`: its outputs recorded for the blocks after it
    * whatever happens to its scripts, and, if the speculator `started` it and its predecessors lead
    * back to the tip, its collections kept and its requests planned.
    */
  private def planned(recent: Recent, request: Request): F[(Recent, Plan)] =
    val hash = request.header.hash
    slots
      .modify(held =>
        held.get(hash) match
          case Some(Slot.Queued) => (held.updated(hash, Slot.Preparing), true)
          case _                 => (held, false)
      )
      .flatMap { started =>
        val nothing = Plan(hash, started, Vector.empty)
        Fibers
          .scoped[F, Fatal, (Recent, Plan)](Fatal.Defect(_))(prepared(request, recent, started))
          .flatMap(
            _.fold(_ => tallies.update(_.fail).as((recent, nothing)), F.pure)
          )
          .onCancel(finished(hash).whenA(started))
      }

  /** [[planned]] in its scope. */
  private def prepared(request: Request, recent: Recent, started: Boolean): F[(Recent, Plan)] =
    val header = request.header
    val hash = header.hash
    speculator.tip.flatMap { tip =>
      tip.scripts(header, request.raw) match
        case None => tallies.update(_.notRead).as((recent, Plan(hash, started, Vector.empty)))
        case Some(scripts) =>
          val next = recent.recorded(hash, header.prevHash, scripts.created)
          val skip = tallies.update(_.skip(scripts.transactions)).as(Vector.empty[EvalRequest])
          val run = recent.since(header.prevHash, tip.hash) match
            case None          => skip
            case Some(between) => collected(hash, tip, scripts, between)
          (if started then speculator.wanted(header).ifM(run, skip) else skip)
            .map(planned => (next, Plan(hash, started, planned)))
            <* slots.update(held =>
              held.get(hash) match
                case Some(Slot.Preparing) => held.updated(hash, Slot.Evaluating)
                case _                    => held
            )
    }

  /** The scripts of the block `hash` collected on `tip` with the outputs of the blocks `between`:
    * the collections kept for the rules while the block is the speculator's or the worker has
    * reached it, and the requests to evaluate, none where they budget more than the block may hold.
    */
  private def collected(
      hash: Bytes,
      tip: ScriptsAhead.Tip[F],
      scripts: Scripts,
      between: Map[TxIn, TxOut]
  ): F[Vector[EvalRequest]] =
    val local = between ++ scripts.created
    tip.outputs(scripts.reads -- local.keySet).flatMap { read =>
      val outputs = local ++ read
      val collected = scripts.collect(outputs.get)
      // what is evaluated is what the requests budget, which can exceed what the redeemers declare
      // (a redeemer of a repeated certificate is collected once for each): none is evaluated
      // beyond the block's limit
      val budgeted = collected.requests.foldLeft(ExUnits.zero)(_ + _.budget)
      keptCollections(hash, collected.collections) *>
        (if !budgeted.fits(scripts.limit) then
           tallies.update(_.skip(scripts.transactions)).as(Vector.empty)
         else tallies.update(_.skip(collected.skipped)).as(collected.requests))
    }

  /** The collections `collections` of the block `hash` kept for the rules, unless the worker passed
    * the block by.
    */
  private def keptCollections(hash: Bytes, collections: Vector[ScriptsAhead.Collection]): F[Unit] =
    whileHeld(hash)(
      F.delay(results.collected(hash, collections))
        .flatMap(kept => tallies.update(_.collect(kept, collections.size - kept)))
    )

  /** The requests of `plan` evaluated, each on a permit of the speculator's, while their block is
    * still wanted, and each result kept; the block's slot then finished.
    */
  private def evaluatedPlan(plan: Plan): F[Unit] =
    // every request waits for a permit at once, mostly before the next block's do
    val evaluations = plan.requests
      .parTraverseN(plan.requests.size.max(1)) { request =>
        permits.permit.use(_ =>
          wanted(plan.block).ifM(
            speculator.evaluate(request).flatMap(kept(plan.block, request, _)),
            tallies.update(_ |+| Tallies.abandoned)
          )
        )
      }
      .void
    Fibers
      .scoped[F, Fatal, Unit](Fatal.Defect(_))(
        evaluations
      )
      .flatMap(_.fold(_ => tallies.update(_.fail), F.pure))
      .guarantee(finished(plan.block).whenA(plan.started))

  /** Whether the block `hash` 's evaluations still go on: neither reached nor passed by. */
  private def wanted(hash: Bytes): F[Boolean] =
    slots.get.map(_.get(hash).exists {
      case Slot.Preparing | Slot.Evaluating => true
      case _                                => false
    })

  /** `keep` run while the block `hash` is the speculator's or the worker has reached it, and what
    * it kept dropped again where the worker passed the block by meanwhile.
    */
  private def whileHeld(hash: Bytes)(keep: F[Unit]): F[Unit] =
    def holding(held: Map[Bytes, Slot]): Boolean = held.get(hash).exists {
      case Slot.Preparing | Slot.Evaluating | Slot.Reached => true
      case _                                               => false
    }
    slots.get.map(holding).flatMap { now =>
      // a block passed by while its result was being kept: dropped again
      (keep *> slots.get.map(holding).flatMap(still => dropped(hash).unlessA(still))).whenA(now)
    }

  /** `result` kept for `request` unless it is not kept ([[ScriptsAhead.Results.keeps]]) or the
    * worker passed the block by.
    */
  private def kept(hash: Bytes, request: EvalRequest, result: EvalResult): F[Unit] =
    ScriptsAhead.Results.keeps(result) match
      case ScriptsAhead.Keeping.NoVerdict => tallies.update(_.noVerdict)
      case ScriptsAhead.Keeping.Retaining => tallies.update(_.retains)
      case ScriptsAhead.Keeping.Kept =>
        whileHeld(hash)(
          F.delay(results.put(hash, request, result))
            .flatMap(kept => tallies.update(t => if kept then t.kept else t.overfull))
        )

  /** The speculation of `hash` is over: the slot of a block the worker reached or passed by while
    * it ran is gone, the others' kept until the worker comes to the block.
    */
  private def finished(hash: Bytes): F[Unit] =
    slots.update(held =>
      held.get(hash) match
        case Some(Slot.Preparing) | Some(Slot.Evaluating) => held.updated(hash, Slot.Done)
        case Some(Slot.Reached) | Some(Slot.Passed)       => held - hash
        case _                                            => held
    )

object ScriptsAhead:
  private type ConwayEra = xyz.kd5ujc.scarab.ledger.state.Conway

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

  /** Where a block asked for is: waiting for the speculator, being read and collected, its scripts
    * being evaluated (or waiting for the evaluating stage), reached by the worker or passed by
    * while being speculated, or speculated and not yet reached.
    */
  enum Slot:
    case Queued, Preparing, Evaluating, Reached, Passed, Done

  /** The blocks whose scripts are evaluated at once: the one whose last scripts run and the next.
    */
  val Evaluating = 2

  /** A block prepared for the evaluating stage: its hash, whether the speculator started it (its
    * slot is the speculator's to finish), and the requests to evaluate.
    */
  final case class Plan(block: Bytes, started: Boolean, requests: Vector[EvalRequest])

  /** What the speculator did: scripts evaluated and kept; transactions whose scripts were not
    * evaluated ahead (`skipped`: an input not resolved, a collection refused, the block's era or
    * chain not the tip's, the block reached or passed by first, its units beyond the block's
    * limit); scripts whose evaluation was not started because the worker reached or passed their
    * block (`abandoned`); scripts evaluated and not kept, the bounds being full (`full`), the
    * answer no verdict (`unanswered`) or a failure holding a term of the machine (`retaining`,
    * [[Results.keeps]]); blocks not speculated at all: asked for with the queue full (`dropped`),
    * not read as a block of the tip's era with scripts to speculate (`unread`: another era, Byron
    * or Dijkstra, a block that does not decode, units beyond the block's limit), or whose
    * speculation failed and was discarded (`failed`); transactions' collections kept for the rules
    * (`collected`) and not kept, the bounds being full (`collectedFull`); and the blocks the worker
    * reached, by where their speculation was (`reached`).
    */
  final case class Tallies(
      evaluated: Long,
      skipped: Long,
      abandoned: Long,
      full: Long,
      unanswered: Long,
      retaining: Long,
      dropped: Long,
      unread: Long,
      failed: Long,
      collected: Long,
      collectedFull: Long,
      reached: Reached
  ):
    def skip(transactions: Int): Tallies = copy(skipped = skipped + transactions)
    def kept: Tallies = copy(evaluated = evaluated + 1)
    def overfull: Tallies = copy(full = full + 1)
    def noVerdict: Tallies = copy(unanswered = unanswered + 1)
    def retains: Tallies = copy(retaining = retaining + 1)
    def drop: Tallies = copy(dropped = dropped + 1)
    def notRead: Tallies = copy(unread = unread + 1)
    def fail: Tallies = copy(failed = failed + 1)
    def collect(kept: Int, full: Int): Tallies =
      copy(collected = collected + kept, collectedFull = collectedFull + full)
    def reachedAt(slot: Option[Slot]): Tallies = copy(reached = reached.at(slot))

  object Tallies:
    val zero: Tallies = Tallies(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Reached.zero)
    val abandoned: Tallies = zero.copy(abandoned = 1)
    given cats.Monoid[Tallies] with
      def empty: Tallies = zero
      def combine(a: Tallies, b: Tallies): Tallies =
        Tallies(
          a.evaluated + b.evaluated,
          a.skipped + b.skipped,
          a.abandoned + b.abandoned,
          a.full + b.full,
          a.unanswered + b.unanswered,
          a.retaining + b.retaining,
          a.dropped + b.dropped,
          a.unread + b.unread,
          a.failed + b.failed,
          a.collected + b.collected,
          a.collectedFull + b.collectedFull,
          Reached(
            a.reached.queued + b.reached.queued,
            a.reached.preparing + b.reached.preparing,
            a.reached.evaluating + b.reached.evaluating,
            a.reached.done + b.reached.done,
            a.reached.unasked + b.reached.unasked
          )
        )

  /** The blocks the worker reached, by where their speculation was then: still in the queue
    * (`queued`), being read and collected (`preparing`), its scripts being evaluated or waiting for
    * that stage (`evaluating`), speculated whole (`done`), or not asked for or gone (`unasked`: a
    * Byron block, one the queue had no room for, one asked for again after it was done with).
    */
  final case class Reached(
      queued: Long,
      preparing: Long,
      evaluating: Long,
      done: Long,
      unasked: Long
  ):
    def at(slot: Option[Slot]): Reached = slot match
      case Some(Slot.Queued)                             => copy(queued = queued + 1)
      case Some(Slot.Preparing)                          => copy(preparing = preparing + 1)
      case Some(Slot.Evaluating)                         => copy(evaluating = evaluating + 1)
      case Some(Slot.Done)                               => copy(done = done + 1)
      case Some(Slot.Reached) | Some(Slot.Passed) | None => copy(unasked = unasked + 1)

  object Reached:
    val zero: Reached = Reached(0, 0, 0, 0, 0)

  /** Whether a result is kept ([[Results.keeps]]). */
  enum Keeping:
    case Kept, NoVerdict, Retaining

  /** The requests of a block's transactions, the collections they came from, kept for the rules
    * ([[Collection]]), and the transactions with redeemers whose requests were not collected
    * (`skipped`).
    */
  final case class Collected(
      requests: Vector[EvalRequest],
      collections: Vector[Collection],
      skipped: Int
  )

  object Collected:
    val empty: Collected = Collected(Vector.empty, Vector.empty, 0)

  /** A transaction's scripts collected ahead of the rules, which the rules take only where it is
    * their own collection: the flat rules' (`Phase2.Kept`, an era before Conway) or Conway's
    * contexts (`KeptContexts`), with the transaction's id, which the rules' lookup finds it by.
    */
  enum Collection:
    case Flat(kept: Phase2.Kept)
    case Conway(id: Bytes, kept: KeptContexts[ConwayEra])

    def txId: Bytes = this match
      case Flat(kept)    => kept.inputs.tx.body.id
      case Conway(id, _) => id

    /** The requests the collection's scripts are evaluated with: the flat rules' as collected, and
      * Conway's as its scripts give them (`PlutusWithContext.evalRequest`, one request a script).
      */
    def requests: Vector[EvalRequest] = this match
      case Flat(kept)      => kept.requests.map(_._2)
      case Conway(_, kept) => kept.scripts.toVector.map(PlutusWithContext.evalRequest)

  /** A block's scripts as the speculator sees them: its `transactions` with redeemers, the outputs
    * it creates (`created`: a valid transaction's outputs, an invalid one's collateral return),
    * what its transactions with redeemers read from the UTxO set before it (`reads`: their inputs
    * and reference inputs), and their requests, collected from the outputs of those (`collect`).
    */
  final case class Scripts(
      transactions: Int,
      created: Map[TxIn, TxOut],
      reads: Set[TxIn],
      limit: ExUnits,
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
    * worker's tip (`tip`), and the evaluation of a request on a thread of its own (`evaluate`, the
    * rules' evaluator), at most `parallelism` at once.
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

  /** How the rules looked for their transactions' collections: one kept for the transaction
    * (`offered`, which they take where it is their own collection), none (`missed`); and the
    * scripts whose result they found by the request itself (`byRequest`), which a collection they
    * took handed them.
    */
  final case class Collections(offered: Long, missed: Long, byRequest: Long)

  object Collections:
    val zero: Collections = Collections(0, 0, 0)

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
      weight: Long,
      shared: Vector[(Same.Kept, Long)]
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
      counts: Counts,
      proven: Map[Same.Kept, PlutusData],
      provenOf: Map[Bytes, Vector[Same.Kept]],
      charged: Map[Bytes, Set[Same.Kept]],
      collections: Map[Bytes, Vector[(Bytes, Collection)]],
      collectionsOf: Map[Bytes, Vector[Bytes]],
      collectionWeights: Map[Bytes, (Int, Long)],
      collectionCounts: Collections
  ):
    /** The entry kept for a request equal to `request` ([[Same.requests]]): the same request first,
      * which a collection kept ahead hands the rules; if any; whether any is kept for its locator
      * at all.
      */
    def found(request: EvalRequest): Option[Either[Unit, Entry]] =
      entries
        .get(Locator.of(request))
        .map(kept =>
          kept
            .find(_.request eq request)
            .orElse(kept.find(e => Same.requests(request, e.request, proven)))
            .toRight(())
        )

    /** The collection kept for the transaction `txId` that `pick` takes, if any. */
    def collection[A](txId: Bytes)(pick: PartialFunction[Collection, A]): Option[A] =
      collections.get(txId).flatMap(_.iterator.map(_._2).collectFirst(pick))

    /** `found` counted as a collection the rules looked for. */
    /** A lookup of the rules for a transaction's collection counted: one kept for it or none. */
    def looked(kept: Boolean): Held =
      copy(collectionCounts =
        if kept then collectionCounts.copy(offered = collectionCounts.offered + 1)
        else collectionCounts.copy(missed = collectionCounts.missed + 1)
      )

    /** The collections `offered` of the block `block` kept within `limits`, as many as there is
      * room for in order, each charged its estimated weight with the nodes it shares with what the
      * block holds charged once ([[Results.Weighed]]); how many were kept.
      */
    def collected(limits: Limits, block: Bytes, offered: Vector[Results.Weighed]): (Held, Int) =
      offered.foldLeft((this, 0)) { case ((held, n), weighed) =>
        held.collectedOne(limits, block, weighed).fold((held, n))(next => (next, n + 1))
      }

    private def collectedOne(limits: Limits, block: Bytes, weighed: Results.Weighed): Option[Held] =
      val collection = weighed.collection
      val txId = collection.txId
      val present =
        collections.get(txId).exists(_.exists((b, c) => b == block && (c eq collection)))
      if present then Some(this)
      else
        val room =
          if blocks.contains(block) || blocks.sizeIs < limits.blocks then this
          else order.headOption.fold(this)(head => dropped(head._2))
        val before = room.charged.getOrElse(block, Set.empty)
        val shared = weighed.shared
        val weight =
          weighed.own + shared.iterator.filterNot((k, _) => before.contains(k)).map(_._2).sum
        Option.when(room.size < limits.entries && room.bytes + weight <= limits.bytes)(
          room.copy(
            blocks =
              if room.blocks.contains(block) then room.blocks
              else room.blocks.updated(block, room.next),
            order =
              if room.blocks.contains(block) then room.order
              else room.order.updated(room.next, block),
            next = room.next + 1,
            size = room.size + 1,
            bytes = room.bytes + weight,
            charged = room.charged.updated(block, before ++ shared.map(_._1)),
            collections = room.collections.updated(
              txId,
              room.collections.getOrElse(txId, Vector.empty) :+ (block -> collection)
            ),
            collectionsOf = room.collectionsOf.updated(
              block,
              room.collectionsOf.getOrElse(block, Vector.empty) :+ txId
            ),
            collectionWeights = room.collectionWeights.updated(
              block,
              room.collectionWeights.getOrElse(block, (0, 0L)) match
                case (n, w) => (n + 1, w + weight)
            )
          )
        )

    /** The pairs of `request` 's nodes and the kept `entry` 's that its hit proved equal, kept for
      * the lookups of the same transaction's other scripts while the entry's block is.
      */
    def proved(request: EvalRequest, entry: Entry): Held =
      // a pair of a block no longer kept would never be dropped
      if !blocks.contains(entry.block) then this
      else
        val pairs = Same
          .pairs(request.args, entry.request.args)
          .distinctBy(_._1)
          .filterNot((k, _) => proven.contains(k))
          .take((Same.Proven - proven.size).max(0))
        if pairs.isEmpty then this
        else
          copy(
            proven = proven ++ pairs,
            provenOf = provenOf.updated(
              entry.block,
              provenOf.getOrElse(entry.block, Vector.empty) ++ pairs.map(_._1)
            )
          )

    def counted(found: Option[Either[Unit, Entry]], request: EvalRequest): Held =
      copy(
        counts = found match
          case None           => counts.copy(miss = counts.miss + 1)
          case Some(Left(_))  => counts.copy(stale = counts.stale + 1)
          case Some(Right(_)) => counts.copy(hit = counts.hit + 1)
        ,
        collectionCounts = found match
          case Some(Right(e)) if e.request eq request =>
            collectionCounts.copy(byRequest = collectionCounts.byRequest + 1)
          case _ => collectionCounts
      )

    /** `entry` kept within `limits`, a node it shares with an entry of its block kept before (a
      * transaction's information, which its scripts' contexts share) charged once, with the entry's
      * weight as charged.
      */
    def put(limits: Limits, offered: Entry): Option[Held] =
      val locator = Locator.of(offered.request)
      val present = entries.get(locator).exists(_.exists(_.request == offered.request))
      if present then Some(this)
      else
        val room =
          if blocks.contains(offered.block) || blocks.sizeIs < limits.blocks then this
          else order.headOption.fold(this)(head => dropped(head._2))
        val before = room.charged.getOrElse(offered.block, Set.empty)
        val entry = offered.copy(weight =
          offered.weight - offered.shared.iterator
            .filter((k, _) => before.contains(k))
            .map(_._2)
            .sum
        )
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
            bytes = room.bytes + entry.weight,
            charged = room.charged.updated(offered.block, before ++ offered.shared.map(_._1))
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
        val (collected, collectedBytes) = collectionWeights.getOrElse(block, (0, 0L))
        val txIds = collectionsOf.getOrElse(block, Vector.empty)
        copy(
          entries = kept,
          blocks = blocks - block,
          order = order - stamp,
          size = size - gone.size - collected,
          bytes = bytes - gone.iterator.map(_.weight).sum - collectedBytes,
          proven = proven -- provenOf.getOrElse(block, Vector.empty),
          provenOf = provenOf - block,
          charged = charged - block,
          collections = txIds.distinct.foldLeft(collections)((cs, txId) =>
            cs.get(txId).map(_.filterNot(_._1 == block)) match
              case Some(left) if left.nonEmpty => cs.updated(txId, left)
              case _                           => cs - txId
          ),
          collectionsOf = collectionsOf - block,
          collectionWeights = collectionWeights - block
        )
      }

  private object Held:
    val empty: Held =
      Held(
        Map.empty,
        Map.empty,
        TreeMap.empty,
        0L,
        0,
        0L,
        Counts.zero,
        Map.empty,
        Map.empty,
        Map.empty,
        Map.empty,
        Map.empty,
        Map.empty,
        Collections.zero
      )

  /** The comparison of a request with a kept one, as `==` compares them, made fast for the rules'
    * hits (#1499): every field but the arguments by the request's own equality, so a field a later
    * request gains is compared too; then the arguments by structure, with an explicit stack and no
    * allocation per node beyond it, where `PlutusData` 's own equality walks a generic tree. A
    * transaction's scripts share its information (`txInfo`, the context's first field, which the
    * collection makes once a transaction and language): the pairs of nodes near the top of the
    * arguments that one hit proved equal (`proven`, a kept node by identity and the rules' node it
    * equals) answer for the same nodes in the next script's comparison by identity, so the shared
    * information is walked once a transaction. A pair is only ever recorded once the whole of both
    * nodes compared equal, and values are immutable, so a proven pair is equal by construction.
    */
  private[node] object Same:
    /** The depth of the arguments down to which proven pairs are recorded: the arguments, the
      * context's fields (the information among them) and theirs.
      */
    val Depth = 2

    /** The proven pairs kept at most. */
    val Proven = 4096

    /** A kept node, by identity. */
    final class Kept(val node: PlutusData):
      override def equals(that: Any): Boolean = that match
        case k: Kept => k.node eq node
        case _       => false
      override def hashCode(): Int = System.identityHashCode(node)

    /** Whether `request` equals the kept `kept`, as `==` says. */
    def requests(
        request: EvalRequest,
        kept: EvalRequest,
        proven: Map[Kept, PlutusData]
    ): Boolean =
      request.copy(args = Vector.empty) == kept.copy(args = Vector.empty) &&
        request.args.sizeIs == kept.args.size &&
        request.args.indices.forall(i => near(request.args(i), kept.args(i), 0, proven))

    /** A node of the rules' request and the kept one's at `depth`: the same node, a proven pair, or
      * equal by structure, its children near the top compared the same way and last first, so that
      * a context differing from a sibling's in its purpose (after the shared information) differs
      * at once.
      */
    private def near(
        a: PlutusData,
        b: PlutusData,
        depth: Int,
        proven: Map[Kept, PlutusData]
    ): Boolean =
      (a eq b) || proven.get(Kept(b)).exists(_ eq a) || (
        if depth >= Depth then data(a, b)
        else
          a match
            case PlutusData.Constr(t, fs) =>
              b match
                case PlutusData.Constr(u, gs) =>
                  t == u && fs.sizeIs == gs.size &&
                  fs.indices.reverse.forall(i => near(fs(i), gs(i), depth + 1, proven))
                case _ => false
            case PlutusData.List(xs) =>
              b match
                case PlutusData.List(ys) =>
                  xs.sizeIs == ys.size &&
                  xs.indices.reverse.forall(i => near(xs(i), ys(i), depth + 1, proven))
                case _ => false
            case PlutusData.Map(kvs) =>
              b match
                case PlutusData.Map(lvs) =>
                  kvs.sizeIs == lvs.size && kvs.indices.reverse.forall(i =>
                    near(kvs(i)._1, lvs(i)._1, depth + 1, proven) &&
                      near(kvs(i)._2, lvs(i)._2, depth + 1, proven)
                  )
                case _ => false
            case _ => data(a, b)
      )

    /** `x == y` for data, by structure: walked with a stack of the pairs still to compare, stack
      * safe (STK-1), stopping at the first difference.
      */
    def data(x: PlutusData, y: PlutusData): Boolean =
      @tailrec def go(stack: List[PlutusData]): Boolean = stack match
        case a :: b :: rest =>
          if a eq b then go(rest)
          else
            a match
              case PlutusData.Constr(t, fs) =>
                b match
                  case PlutusData.Constr(u, gs) =>
                    t == u && fs.sizeIs == gs.size && go(pushed(fs, gs, rest))
                  case _ => false
              case PlutusData.List(xs) =>
                b match
                  case PlutusData.List(ys) => xs.sizeIs == ys.size && go(pushed(xs, ys, rest))
                  case _                   => false
              case PlutusData.Map(kvs) =>
                b match
                  case PlutusData.Map(lvs) =>
                    kvs.sizeIs == lvs.size && go(
                      kvs.indices.foldRight(rest)((i, s) =>
                        kvs(i)._1 :: lvs(i)._1 :: kvs(i)._2 :: lvs(i)._2 :: s
                      )
                    )
                  case _ => false
              case PlutusData.I(i) =>
                b match
                  case PlutusData.I(j) => i == j && go(rest)
                  case _               => false
              case PlutusData.B(c) =>
                b match
                  case PlutusData.B(d) => c == d && go(rest)
                  case _               => false
        case _ => true
      go(List(x, y))

    /** The children `as` and `bs` paired, in order, in front of `rest`. */
    private def pushed(
        as: Vector[PlutusData],
        bs: Vector[PlutusData],
        rest: List[PlutusData]
    ): List[PlutusData] =
      as.indices.foldRight(rest)((i, s) => as(i) :: bs(i) :: s)

    /** The pairs of non-leaf nodes of the kept arguments `cs` and the equal rules' arguments `ws`
      * down to [[Depth]], the kept node by identity.
      */
    def pairs(ws: Vector[PlutusData], cs: Vector[PlutusData]): Vector[(Kept, PlutusData)] =
      def children(d: PlutusData): Vector[PlutusData] = d match
        case PlutusData.Constr(_, fs) => fs
        case PlutusData.List(xs)      => xs
        case PlutusData.Map(kvs)      => kvs.flatMap((k, v) => Vector(k, v))
        case _                        => Vector.empty
      def at(w: PlutusData, c: PlutusData, depth: Int): Vector[(Kept, PlutusData)] =
        val (wk, ck) = (children(w), children(c))
        if ck.isEmpty || wk.sizeIs != ck.size then Vector.empty
        else
          (Kept(c) -> w) +: (
            if depth >= Depth then Vector.empty
            else wk.indices.toVector.flatMap(i => at(wk(i), ck(i), depth + 1))
          )
      ws.indices.toVector.flatMap(i => at(ws(i), cs(i), 0))

  /** The results evaluated ahead, kept for the worker's rules: a value behind an atomic reference,
    * as the evaluator's own caches are (`BoundedCache`), so the rules' evaluator, which runs on the
    * Plutus workers' threads as a plain function ([[evaluator]]), reads it without an effect. It is
    * memoisation only: a result is given only for a request equal to the one it was evaluated for.
    */
  final class Results private (limits: Limits, state: AtomicReference[Held]):
    /** The result kept for `request`, if one was kept for an equal request; the lookup counted. A
      * hit of another request than the kept one records the pairs it proved ([[Held.proved]]); a
      * hit of the kept request itself, which a collection kept ahead hands the rules, proves none.
      */
    def lookup(request: EvalRequest): Option[EvalResult] =
      val found = state.get.found(request)
      val _ = state.updateAndGet(held =>
        found
          .flatMap(_.toOption)
          .filterNot(_.request eq request)
          .fold(held)(held.proved(request, _))
          .counted(found, request)
      )
      found.flatMap(_.toOption).map(_.result)

    /** `underlying` answering first from what was kept: a request equal to a kept one gets the kept
      * result, any other `underlying` 's.
      */
    def evaluator(underlying: ScriptEvaluator[Id]): ScriptEvaluator[Id] =
      request => lookup(request).getOrElse(underlying.evaluate(request))

    /** The collections kept for the flat rules' transactions (`Phase2.Ahead`): the one kept for the
      * transaction of `inputs`, by its id, which the rules take only where it is their own
      * collection (`Phase2.collected`); each lookup counted ([[Collections]]).
      */
    val flat: Phase2.Ahead = new Phase2.Ahead:
      def flat(inputs: Phase2.Inputs[?]): Option[Phase2.Kept] =
        looked(state.get.collection(inputs.tx.body.id) { case Collection.Flat(kept) => kept })

    /** The contexts kept for Conway's transactions (`ContextsAhead`), as [[flat]] for the flat
      * rules: by the transaction's id, taken only where they are the rules' own collection
      * (`ContextInputs.collected`).
      */
    val conway: ContextsAhead[ConwayEra] = new ContextsAhead[ConwayEra]:
      def contexts(inputs: ContextInputs[ConwayEra]): Option[KeptContexts[ConwayEra]] =
        looked(state.get.collection(ScriptsAhead.conwayTxId(inputs.tx)) {
          case Collection.Conway(_, kept) => kept
        })

    /** What the rules take from this ([[MirrorRules.Ahead]]). */
    def ahead: MirrorRules.Ahead = MirrorRules.Ahead(flat, conway)

    /** A lookup of the rules for a transaction's collection, counted where the results are (as a
      * result's lookup is, [[lookup]]): whether one was kept for it.
      */
    private def looked[A](found: Option[A]): Option[A] =
      val _ = state.updateAndGet(_.looked(found.isDefined))
      found

    /** The collections `collections` of the block `block` kept, as many as the bounds leave room
      * for, in order; how many were kept.
      */
    private[node] def collected(block: Bytes, collections: Vector[Collection]): Int =
      // weighed once: what the retries of a contended update share
      val weighed = collections.map(Results.Weighed.of)
      @tailrec def go(): Int =
        val now = state.get
        val (next, kept) = now.collected(limits, block, weighed)
        if state.compareAndSet(now, next) then kept else go()
      go()

    /** How the rules looked for their transactions' collections, since the start. */
    def collectionCounts: Collections = state.get.collectionCounts

    /** `result` kept for `request`, of the block `block`; false where the bounds leave no room. */
    private[node] def put(block: Bytes, request: EvalRequest, result: EvalResult): Boolean =
      val entry =
        Entry(block, request, result, Results.weight(request, result), Results.shared(request))
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

    /** The proven pairs kept, for the suites. */
    private[node] def provenPairs: Int = state.get.proven.size

    /** The results kept and their estimated bytes, for the suites. */
    private[node] def held: (Int, Long) =
      val now = state.get
      (now.size, now.bytes)

  object Results:
    def create(limits: Limits): Results = new Results(limits, new AtomicReference(Held.empty))

    /** The estimated heap of a kept collection beyond its requests' arguments, which are charged as
      * a result's are ([[shared]]): its transaction and the outputs it reads, the parameters and
      * the scripts being the state's and the block's, with a fixed charge per request.
      */
    def collectionWeight(collection: Collection): Long =
      val per = 256L * collection.requests.size
      collection match
        case Collection.Flat(kept) =>
          val in = kept.inputs
          per + 4L * in.tx.size +
            (in.spent.valuesIterator ++ in.references.valuesIterator).map(4L * _.raw.size + 256).sum
        case Collection.Conway(_, kept) =>
          per + 4L * kept.inputs.tx.sizeTxForFeeCalculation +
            kept.inputs.outputBytes.iterator.map(4L * _ + 256).sum

    /** A collection weighed for the bounds, apart from what the block holds: its own estimated heap
      * (its transaction and outputs, [[collectionWeight]], and its requests' arguments whole but
      * for the nodes one level down, which its scripts share), and those nodes with theirs, each
      * once, which are charged once a block.
      */
    final case class Weighed(collection: Collection, own: Long, shared: Vector[(Same.Kept, Long)])

    object Weighed:
      def of(collection: Collection): Weighed =
        val requests = collection.requests
        val nodes = requests.flatMap(shared)
        val args = requests.iterator.flatMap(_.args).map(data).sum
        Weighed(
          collection,
          collectionWeight(collection) + args - nodes.iterator.map(_._2).sum,
          nodes.distinctBy(_._1)
        )

    /** The estimated heap of a kept result: its request's script and arguments, its cost model, the
      * result's logs, with a fixed charge per object.
      */
    def weight(request: EvalRequest, result: EvalResult): Long =
      val logs = (result match
        case EvalResult.Success(_, logs)    => logs
        case EvalResult.Failure(_, _, logs) => logs
      ).iterator.map(_.length.toLong * 2 + 48).sum
      256L + request.script.size + request.costModel.size * 24L +
        request.args.iterator.map(data).sum + logs

    /** The nodes of `request` 's arguments one level down (a context's information among them),
      * with their estimated heap: what the scripts of one transaction share, charged once a block.
      */
    def shared(request: EvalRequest): Vector[(Same.Kept, Long)] =
      request.args.flatMap {
        case PlutusData.Constr(_, fs) =>
          fs.collect {
            case d @ (PlutusData.Constr(_, _) | PlutusData.List(_) | PlutusData.Map(_)) =>
              Same.Kept(d) -> data(d)
          }
        case _ => Vector.empty
      }

    /** Whether `result` is kept: an answer that is no verdict never is, as the evaluator's own
      * caches keep none; nor is a machine failure with the term it is about (`Cause`), which can
      * hold any value the machine made and the decoded program's closures, more than [[weight]] can
      * see. The rules evaluate such a script themselves, as without this; a success and a failure
      * with no such term (an overspent budget, a V3 script's return value, a refused decoding or
      * scope) are kept.
      */
    def keeps(result: EvalResult): Keeping = result match
      case EvalResult.Failure(_: EvalFailure.Unsupported, _, _) => Keeping.NoVerdict
      case EvalResult.Failure(
            EvalFailure.Script(
              xyz.kd5ujc.scarab.plutus.ScriptFailure.Uplc(
                xyz.kd5ujc.scarab.uplc.ledger.EvaluationError
                  .Cek(xyz.kd5ujc.scarab.uplc.cek.CekFailure(_, Some(_))),
                _
              )
            ),
            _,
            _
          ) =>
        Keeping.Retaining
      case _ => Keeping.Kept

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
  )(using era: ShelleyBasedEra[E]): Collected =
    txs.foldLeft(Collected.empty) { (acc, tx) =>
      (tx.body.inputs ++ tx.body.referenceInputs).traverse(in => outputs(in).map(in -> _)) match
        case None => acc.copy(skipped = acc.skipped + 1)
        case Some(read) =>
          val ann = StAnnTx(tx, Utxo.fromMap(read.toMap))
          val inputs =
            Phase2.Inputs(era, tx, pp, ann.scripts, ann.spentByInput, ann.referencedByInput)
          Phase2.Kept
            .of(inputs, posix)
            .fold(acc.copy(skipped = acc.skipped + 1)) { kept =>
              val collection = Collection.Flat(kept)
              acc.copy(
                requests = acc.requests ++ collection.requests,
                collections = acc.collections :+ collection
              )
            }
    }

  /** The id of a Conway transaction (`txIdTxBody`), which a kept collection is found by. */
  def conwayTxId(tx: EraTxOf[TxLevel.Top, ConwayEra]): Bytes =
    val t = EraTx.conway
    given t.bodyOf.Level[TxLevel.Top] = t.bodyOf.top
    EraTxBody.conway.id[TxLevel.Top](t.body[TxLevel.Top](tx))

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
      permits <- Resource.eval(Semaphore[F](speculator.parallelism.toLong))
      ended <- Resource.eval(Deferred[F, Fatal])
      ahead = new ScriptsAhead[F](
        Some(queue),
        slots,
        current,
        tallies,
        results,
        speculator,
        permits,
        ended.get
      )
      _ <- ahead
        .speculating(queue)
        .flatMap(_.fold(ended.complete(_).void, never => never))
        .background
    yield ahead

  /** An evaluation of a request ahead. */
  type Evaluate[F[_]] = EvalRequest => F[EvalResult]

  /** The node's evaluation of a request ahead: on a blocking thread, none of them the worker's
    * Plutus permits; the speculator runs at most its `parallelism` at once. Blocking rather than
    * interruptible, as the worker's own: a release waits for the machines it started, which a
    * block's limit of units bounds.
    */
  def evaluating[F[_]: Async](underlying: ScriptEvaluator[Id]): Evaluate[F] =
    request => Async[F].blocking(underlying.evaluate(request))

  /** Evaluates nothing ahead ([[ahead]] does nothing): every script the rules evaluate is a miss.
    */
  def idle[F[_]: Async](results: Results): F[ScriptsAhead[F]] =
    (
      SignallingRef.of[F, Map[Bytes, Slot]](Map.empty),
      Ref.of[F, Option[Bytes]](None),
      Ref.of[F, Tallies](Tallies.zero),
      Semaphore[F](1)
    ).mapN((slots, current, tallies, permits) =>
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
        permits,
        Async[F].never[Fatal]
      )
    )
