// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.Id
import cats.effect.kernel.Sync
import cats.syntax.all.*
import scala.util.control.NonFatal
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Program, ProtocolMajor}
import xyz.kd5ujc.scarab.uplc.cek.{CekError, Stop}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.ledger.{
  EvaluationError,
  FreeVariableError,
  LedgerContext,
  LedgerEval,
  LedgerOutcome,
  ScriptResult,
  Unavailability,
  VerboseMode
}

/** The [[ScriptEvaluator]] and [[ScriptDecoder]] interpreters backed by Scarab's own evaluator, the
  * `uplc` module (ADR 0027): `deserialiseScript` and `evaluateScriptRestricting` of plutus 1.71.0.0
  * as [[LedgerEval]] transcribes them, which below protocol 12 give 1.70.0.0's verdicts. It is the
  * node's only Plutus evaluator ([[PlutusBackend.default]]).
  *
  * The request is built by the ledger code: the script bytes, data arguments, cost model and
  * budget. Its results map onto the algebra as follows:
  *
  *   - `deserialiseScript` refused the script: [[EvalFailure.Decode]] with [[ScriptProblem.Uplc]],
  *     and [[ScriptDecodeFailure.Malformed]] from [[ScriptDecoder.validate]];
  *   - the program's version check of `mkTermToEvaluate` refused it: [[EvalFailure.Decode]] with
  *     [[ScriptProblem.UplcVersion]];
  *   - the scope check refused the applied term: [[EvalFailure.OpenTerm]];
  *   - the machine overspent the budget: [[EvalFailure.OutOfBudget]];
  *   - any other failure of the machine, or a V3 script that did not return `()`:
  *     [[EvalFailure.Script]] with [[ScriptFailure.Uplc]];
  *   - no verdict ([[ScriptResult.Unavailable]]: a defect, a cost model the evaluator could not
  *     take, an exception it threw), any `Throwable` that reaches the adapter ([[guarded]]), and a
  *     budget beyond 64 bits: [[EvalFailure.Unsupported]], and [[ScriptDecodeFailure.Unavailable]]
  *     from the decoder, never a failure of the script.
  *
  * Evaluation is a deterministic function of the request, so [[pure]] is the interpreter the pure
  * ledger rules use. The two caches (the evaluation context of each language and cost-model list,
  * the program of each language, protocol and script an evaluation decoded, within [[Programs]])
  * are memoisation only: they never change a result, and an answer that is no verdict is never
  * kept.
  */
object ScarabEvaluator:
  /** Contexts kept: one per language and cost-model list, which change with the protocol parameters
    * only.
    */
  private val Contexts = 16

  /** Programs kept by an interpreter not sized for a heap ([[programs]]): at most 4,096, and at
    * most 64 MiB of them by [[ProgramWeight]]. A script's decoded program can hold hundreds of
    * times its bytes (a list of pairs of empty lists holds about 580 bytes of heap for each byte of
    * its script), so a bound on the number of entries alone is no bound on the heap; the number of
    * entries is bounded so that the weight is the bound that holds, every entry weighing at least
    * its script and its place in the map.
    */
  private[plutus] val Programs = Limits(entries = 4096, weight = Weight.mebibytes(64))

  /** Programs kept by the node's interpreter, for a maximum heap of `maxHeap` bytes: a sixteenth of
    * it by [[ProgramWeight]], as the warm tier of the UTxO set is given (`WarmUtxoStore.Budget`),
    * and never less than [[Programs]]. The working set of the scripts a range of blocks evaluates
    * can exceed 64 MiB many times: from mainnet block 10,490,500 a block's transactions cycle
    * through 586 distinct scripts of about 15.5 KiB, each weighing about 1 MiB, and a cache that
    * keeps fewer than about 200 of them, its least recently used evicted first, finds none of them
    * when it is asked again (#1529, `docs/performance/rewards-and-evaluation.md` section 71).
    */
  private[plutus] def programs(maxHeap: Long): Limits =
    Limits(Programs.entries, Weight.bytes(math.max(Programs.weight.toLong, maxHeap / 16)))

  /** A fresh interpreter with its own caches. */
  def pure: ScriptEvaluator[Id] = Interpreter()

  /** A fresh decoder with its own caches. */
  def pureDecoder: ScriptDecoder[Id] = Interpreter()

  /** A fresh evaluator and decoder that share their caches. */
  def interpreter: ScriptEvaluator[Id] & ScriptDecoder[Id] = Interpreter()

  private lazy val shared = Interpreter()

  /** The shared interpreter, so that its caches persist. */
  lazy val default: ScriptEvaluator[Id] = shared

  /** The script decoder of the shared interpreter: it reuses the programs its evaluations keep. */
  lazy val defaultDecoder: ScriptDecoder[Id] = shared

  /** The shared interpreter, whose machine stops, with no verdict ([[Unavailability.Interrupted]]),
    * once the thread it runs on is interrupted: the mempool's admission runs it under
    * `Sync.interruptible`, whose cancellation by the hard time limit interrupts the thread. Until
    * then its every step, charge and verdict are [[default]]'s.
    */
  lazy val interruptible: ScriptEvaluator[Id] = interruptible(shared)

  /** `interpreter`, its machine stopping as [[interruptible]]'s does. */
  private[plutus] def interruptible(interpreter: Interpreter): ScriptEvaluator[Id] =
    interpreter.evaluate(_, UntilInterrupted)

  /** A stop once the thread is interrupted: the interrupt status is the interface through which
    * `Sync.interruptible` cancels (FUN-2 (b)), read and left as it is.
    */
  private val UntilInterrupted: Stop[Unavailability] =
    () => Option.when(Thread.currentThread().isInterrupted)(Unavailability.Interrupted)

  /** The same interpreter suspended in `F`. */
  def suspended[F[_]: Sync]: ScriptEvaluator[F] =
    val underlying = Interpreter()
    request => Sync[F].interruptible(underlying.evaluate(request))

  /** An evaluation context is made for the language a script is evaluated as. */
  private final case class ContextKey(language: PlutusLanguage, costModel: Vector[Long])

  /** `deserialiseScript` depends on the protocol (the builtins and the language it admits). */
  private final case class ProgramKey(language: PlutusLanguage, protocol: Int, script: Bytes)

  private type Admitted = Either[LedgerEval.NotAdmitted, Program]

  private[plutus] final class Interpreter(limits: Limits = Programs)
      extends ScriptEvaluator[Id]
      with ScriptDecoder[Id]:
    // Instance-scoped caches: `evaluate` is a pure function of its request; a BoundedCache is safe
    // to share between threads, and an attempt with no verdict is never retained.
    private val contexts =
      BoundedCache.counted[ContextKey, Either[Unavailability, LedgerContext]](Contexts)
    private val programs =
      BoundedCache[ProgramKey, Admitted](
        limits,
        (key, admitted) =>
          admitted.fold(_ => Weight.unbounded, ProgramWeight.entry(key.script, _, limits.weight))
      )

    /** The number of programs kept, and their weight. */
    private[plutus] def held: (Int, Weight) =
      val now = programs.snapshot
      (now.size, now.weight)

    /** Whether the program of `script` is kept, the order of use left as it is. */
    private[plutus] def holds(language: PlutusLanguage, protocol: Int, script: Bytes): Boolean =
      programs.snapshot.peek(ProgramKey(language, protocol, script)).isDefined

    /** The scripts of the programs kept, the least recently used first. */
    private[plutus] def scripts: Vector[Bytes] = programs.snapshot.keys.map(_.script)

    def evaluate(request: EvalRequest): EvalResult = evaluate(request, Stop.never)

    def evaluate(request: EvalRequest, stop: Stop[Unavailability]): EvalResult =
      bounded(request.budget).fold(
        failure(_),
        budget =>
          guarded(reason => failure(EvalFailure.Unsupported(reason)))(
            result(outcome(request, budget, stop))
          )
      )

    /** `deserialiseScript`, then `evaluateScriptRestricting` in the context of the request's cost
      * model: [[LedgerEval.evaluateSerialised]] with the program and the context memoised.
      */
    private def outcome(
        request: EvalRequest,
        budget: ExBudget,
        stop: Stop[Unavailability]
    ): LedgerOutcome =
      admitted(request.language, request.protocolMajor, request.script) match
        case Left(refused: LedgerOutcome.Refused)        => refused
        case Left(unavailable: ScriptResult.Unavailable) => unavailable
        case Right(program) =>
          context(request).fold(
            ScriptResult.Unavailable(_),
            LedgerEval.evaluateScriptRestricting(
              request.language,
              ProtocolMajor(request.protocolMajor),
              VerboseMode.Verbose,
              _,
              budget,
              program,
              request.args,
              stop
            )
          )

    /** `deserialiseScript` for phase-1 well-formedness: a program kept by an evaluation is reused,
      * and one decoded here is not kept. Phase 1 decodes every script of a transaction whatever its
      * other checks say, so a transaction refused there leaves nothing in the cache; a script that
      * runs is kept by its evaluation.
      */
    def validate(
        language: PlutusLanguage,
        protocolMajor: Int,
        script: Bytes
    ): Either[ScriptDecodeFailure, Unit] =
      guarded[Either[ScriptDecodeFailure, Unit]](reason =>
        Left(ScriptDecodeFailure.Unavailable(reason))
      )(
        programs
          .get(ProgramKey(language, protocolMajor, script))
          .getOrElse(LedgerEval.deserialiseScript(language, ProtocolMajor(protocolMajor), script))
          .void
          .leftMap {
            case LedgerOutcome.Refused(error) =>
              ScriptDecodeFailure.Malformed(ScriptProblem.Uplc(error))
            case ScriptResult.Unavailable(reason) =>
              ScriptDecodeFailure.Unavailable(BackendFailure.Uplc(reason))
          }
      )

    /** The program of `script` for an evaluation, memoised. The key, and the program decoded from
      * it, own exactly the script's bytes ([[Bytes.compact]]), so an entry never holds a larger
      * buffer the script was read from. A program is kept with its scope check made
      * ([[Program.withScope]]), so that an evaluation on another thread reads the check and never
      * waits for it.
      */
    private def admitted(language: PlutusLanguage, protocol: Int, script: Bytes): Admitted =
      val owned = script.compact
      programs.getOrElseUpdate(
        ProgramKey(language, protocol, owned),
        LedgerEval.deserialiseScript(language, ProtocolMajor(protocol), owned).map(_.withScope)
      )(retained)

    /** The evaluation context of the request's language and cost model, made once
      * (`mkEvaluationContext`); its warnings are the reference's, which the ledger does not act on.
      */
    private def context(request: EvalRequest): Either[Unavailability, LedgerContext] =
      val language = request.language.evaluatedAs
      contexts.getOrElseUpdate(
        ContextKey(language, request.costModel),
        Either
          .catchNonFatal(LedgerContext.make(language, request.costModel)._2)
          .leftMap(Unavailability.Thrown(_))
          .flatten
      )(_.isRight)

  /** Whether a decoding result may be kept: an admitted program. Never the absence of a verdict;
    * and not a refusal, whose value (the tags of a type it could not read, a `data` item) has no
    * bound its weight could follow. A refused script is decoded again at each evaluation.
    */
  private def retained(admitted: Admitted): Boolean = admitted.isRight

  /** The budget as the machine's: the ledger's `ExUnits` are memory and steps, `ExBudget` is CPU
    * and memory. A component beyond 64 bits gets no verdict.
    */
  private def bounded(budget: ExUnits): Either[EvalFailure, ExBudget] =
    Either.cond(
      budget.mem.isValidLong && budget.steps.isValidLong,
      ExBudget.of(budget.steps.toLong, budget.mem.toLong),
      EvalFailure.Unsupported(BackendFailure.BudgetOutOfRange(budget))
    )

  private def units(budget: ExBudget): ExUnits =
    ExUnits(budget.memory.toBigInt, budget.cpu.toBigInt)

  private def failure(reason: EvalFailure): EvalResult =
    EvalResult.Failure(reason, ExUnits.zero, Vector.empty)

  /** What the adapter's body throws, as no verdict: the own evaluator turns its non-fatal
    * exceptions into [[Unavailability.Thrown]] itself, so what reaches here is a defect of this
    * adapter or an error of the JVM. A stack overflow is [[BackendFailure.StackExhausted]] (the
    * machine and the decoder keep their stacks on the heap, so it is a defect too); a non-fatal
    * exception is [[Unavailability.Thrown]]; any other `Throwable` (a `LinkageError` of a native
    * library, an `OutOfMemoryError`) is [[BackendFailure.Defect]]. None is ever an error that ends
    * the thread the ledger rules run on, nor a failure of the script.
    */
  private[plutus] def guarded[A](unavailable: BackendFailure => A)(body: => A): A =
    Either
      .catchOnly[Throwable](body)
      .fold(
        {
          case _: StackOverflowError => unavailable(BackendFailure.StackExhausted)
          case NonFatal(error) => unavailable(BackendFailure.Uplc(Unavailability.Thrown(error)))
          case error           => unavailable(BackendFailure.Defect(error))
        },
        identity
      )

  /** What the ledger's handling of the script gives, in the shared algebra (see the mapping above).
    */
  private[plutus] def result(outcome: LedgerOutcome): EvalResult = outcome match
    case LedgerOutcome.Refused(error) =>
      failure(EvalFailure.Decode(ScriptProblem.Uplc(error)))
    case ScriptResult.Success(spent, logs) =>
      EvalResult.Success(units(spent), logs)
    case ScriptResult.Unavailable(reason) =>
      failure(EvalFailure.Unsupported(BackendFailure.Uplc(reason)))
    case ScriptResult.Failure(error, spent, logs) =>
      val reason = error match
        case EvaluationError.Codec(refusal) =>
          EvalFailure.Decode(ScriptProblem.UplcVersion(refusal))
        case EvaluationError.DeBruijn(FreeVariableError.FreeIndex(index)) =>
          EvalFailure.OpenTerm(index.toBigInt)
        case EvaluationError.Cek(cek) =>
          cek.error match
            case CekError.OutOfBudget(_) => EvalFailure.OutOfBudget
            case _                       => EvalFailure.Script(ScriptFailure.Uplc(error, logs))
        case EvaluationError.InvalidReturnValue =>
          EvalFailure.Script(ScriptFailure.Uplc(error, logs))
      EvalResult.Failure(reason, units(spent), logs)
