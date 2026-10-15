// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.Id
import cats.effect.kernel.Sync
import java.util.concurrent.ConcurrentHashMap
import xyz.kd5ujc.scarab.codec.{Bytes, TreeFold}
import xyz.kd5ujc.scarab.plutus.ExUnits
import scala.util.control.NonFatal
import scalus.cardano.ledger.{CostModels, ExUnits as ScalusExUnits, Language, MajorProtocolVersion}
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.builtin.{ByteString, Data, platform}
import scalus.uplc.eval.{
  BudgetSpender,
  CekMachine,
  CekValue,
  Logger,
  DefaultCostingFun,
  Log,
  MachineParams,
  OutOfExBudgetError,
  PlutusVM,
  RestrictingBudgetSpender,
  ThreeArguments
}
import scalus.uplc.{
  BuiltinRuntime,
  BuiltinSemanticsVariant,
  CardanoBuiltins,
  Constant,
  DeBruijn,
  DeBruijnedProgram,
  DefaultFun,
  Term
}

/** The [[ScriptEvaluator]] interpreter backed by the Scalus UPLC machine (Apache-2.0). Scalus
  * supplies only the machine and its builtins (including BLS12-381 through `blst` and secp256k1
  * through JNI); the script context, cost-model selection, budget and verdict are this node's.
  *
  * Evaluation is a deterministic function of the request, so [[pure]] is the interpreter the pure
  * ledger rules use. The two caches (decoded scripts, virtual machines per cost model) are
  * memoisation only: they never change a result.
  */
object ScalusEvaluator:
  /** Large enough for the recursive flat decoder on deeply nested scripts. */
  private val StackSize = 256L * 1024 * 1024

  /** A fresh interpreter with its own caches. */
  def pure: ScriptEvaluator[Id] = Interpreter()

  /** A fresh decoder with its own cache. */
  def pureDecoder: ScriptDecoder[Id] = Interpreter()

  private lazy val shared = Interpreter()

  /** The shared interpreter the ledger rules use by default, so the caches persist. */
  lazy val default: ScriptEvaluator[Id] = shared

  /** The script decoder of the shared interpreter: it shares the decoded-program cache. */
  lazy val defaultDecoder: ScriptDecoder[Id] = shared

  /** The same interpreter suspended in `F`, blocking-safe. */
  def suspended[F[_]: Sync]: ScriptEvaluator[F] =
    val underlying = Interpreter()
    request => Sync[F].interruptible(underlying.evaluate(request))

  private final case class VmKey(
      language: PlutusLanguage,
      protocolMajor: Int,
      costModel: Vector[Long]
  )

  /** A decoded script with what the availability checks need, collected once per script. */
  private final case class Decoded(
      program: DeBruijnedProgram,
      builtins: Set[DefaultFun],
      remainder: Int
  )

  private final class Interpreter extends ScriptEvaluator[Id] with ScriptDecoder[Id]:
    // Deliberate instance-scoped caches (one Interpreter per evaluator value, not hidden in an object;
    // `default` shares one): `evaluate` is a pure function of its request, and the caches only memoise
    // PlutusVM construction and program decoding. ConcurrentHashMap.computeIfAbsent is atomic and
    // BoundedCache is synchronized, so concurrent evaluations are safe and results are unaffected.
    private val vms = ConcurrentHashMap[VmKey, Either[String, PlutusVM]]()
    private val programs = BoundedCache[Bytes, Either[String, Decoded]](512)

    def evaluate(request: EvalRequest): EvalResult =
      onBigStack(EvalResult.Failure(EvalFailure.Script("not run"), ExUnits.zero, Vector.empty))(
        run(request)
      )

    def validate(
        language: PlutusLanguage,
        protocolMajor: Int,
        script: Bytes
    ): Either[String, Unit] =
      cached(script) match
        case Some(d) => d.flatMap(available(_, language, protocolMajor))
        case None =>
          onBigStack[Either[String, Unit]](Left("not run"))(
            program(script).flatMap(available(_, language, protocolMajor))
          )

    /** The machine and the decoder recurse on the term; run on a thread with a large stack. */
    private def onBigStack[A](initial: A)(body: => A): A =
      var result: A = initial
      val worker = Thread(null, () => result = body, "plutus-evaluator", StackSize)
      worker.start()
      worker.join()
      result

    /** The checks of the ledger's deserialisation and of `mkTermToEvaluate`, in that order. */
    private def admitted(decoded: Decoded, request: EvalRequest): Either[String, Decoded] =
      for
        _ <- available(decoded, request.language, request.protocolMajor)
        _ <- PlutusAvailability
          .versionFailure(request.language, request.protocolMajor, decoded.program.version)
          .toLeft(())
      yield decoded

    private def available(
        decoded: Decoded,
        language: PlutusLanguage,
        protocolMajor: Int
    ): Either[String, Unit] =
      PlutusAvailability
        .decodeFailure(language, protocolMajor, decoded.builtins)
        .orElse(
          // `deserialiseScript` tolerates bytes after the script's CBOR item in V1 and V2 only
          Option.when(decoded.remainder > 0 && language == PlutusLanguage.V3)(
            s"${decoded.remainder} bytes remain after the script (RemainderError)"
          )
        )
        .toLeft(())

    private def vm(request: EvalRequest): Either[String, PlutusVM] =
      val key = VmKey(request.language, request.protocolMajor, request.costModel)
      vms.computeIfAbsent(key, _ => buildVm(key))

    private def buildVm(key: VmKey): Either[String, PlutusVM] =
      try
        val language = Language.fromId(key.language.id)
        val protocol = MajorProtocolVersion(key.protocolMajor)
        val variant = BuiltinSemanticsVariant.fromProtocolAndPlutusVersion(protocol, language)
        val params = MachineParams.fromCostModels(
          CostModels(Map(key.language.id -> key.costModel.toIndexedSeq)),
          language,
          protocol
        )
        Right(
          SerialisingVm(
            language,
            corrected(params, variant),
            variant,
            protocol
          )
        )
      catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))

    /** Costing the on-chain parameters cannot express and Scalus gets wrong. Semantics variant A
      * (Plutus V1/V2 before protocol 9) prices `verifyEd25519Signature` by the size of its third
      * argument, the signature (`linear_in_z` in the reference costing model); Scalus's
      * parameter-to-model conversion prices it by the second, the message, in every variant. The
      * reference ledger disagrees on a 11-word message against a 8-word signature, which showed up
      * on Preview as a script spending 3063 more steps than the 539483304 the block declared.
      *
      * Reproducer: `ScalusReproducersSuite`; costing differential: `BuiltinCostingSuite`. Still
      * present in Scalus 1.3.0 (reported as https://github.com/scalus3/scalus/issues/387; remove
      * this when a fixed release is adopted and the reproducer fails).
      */
    private def corrected(params: MachineParams, variant: BuiltinSemanticsVariant): MachineParams =
      if variant != BuiltinSemanticsVariant.A then params
      else
        val model = params.builtinCostModel
        val ed = model.verifyEd25519Signature
        (ed.cpu match
          case ThreeArguments.LinearInY(f) =>
            params.copy(builtinCostModel =
              model.copy(verifyEd25519Signature =
                DefaultCostingFun(ThreeArguments.LinearInZ(f), ed.memory)
              )
            )
          case _ => params
        )

    private def cached(script: Bytes): Option[Either[String, Decoded]] = programs.get(script)

    /** Must run on a big stack ([[onBigStack]]): the flat decoder recurses on the term. */
    private def program(script: Bytes): Either[String, Decoded] =
      programs.getOrElseUpdate(
        script,
        try
          val bytes = script.toArray
          // Scalus rejects bytes after the CBOR item, the reference keeps them as a remainder
          val used = PlutusScriptBytes.cborItemLength(bytes)
          val p = DeBruijnedProgram.fromCbor(bytes.take(used))
          Right(Decoded(p, builtinsOf(p.term), bytes.length - used))
        catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
      )

    /** The builtins a term mentions (iterative: terms can be deeply nested). */
    private def builtinsOf(term: Term): Set[DefaultFun] =
      val found = collection.mutable.Set.empty[DefaultFun]
      val todo = collection.mutable.Stack(term)
      while todo.nonEmpty do
        todo.pop() match
          case Term.Builtin(f, _)                          => found += f
          case Term.LamAbs(_, t, _)                        => todo.push(t)
          case Term.Apply(f, a, _)                         => todo.push(f); todo.push(a)
          case Term.Force(t, _)                            => todo.push(t)
          case Term.Delay(t, _)                            => todo.push(t)
          case Term.Constr(_, as, _)                       => as.foreach(todo.push)
          case Term.Case(a, cs, _)                         => todo.push(a); cs.foreach(todo.push)
          case _: Term.Var | _: Term.Const | _: Term.Error => ()
      found.toSet

    private def run(request: EvalRequest): EvalResult =
      def failure(
          reason: EvalFailure,
          used: ExUnits = ExUnits.zero,
          logs: Vector[String] = Vector.empty
      ) =
        EvalResult.Failure(reason, used, logs)
      if !request.budget.mem.isValidLong || !request.budget.steps.isValidLong then
        failure(EvalFailure.Unsupported("execution budget exceeds 64 bits"))
      else
        (vm(request), program(request.script).flatMap(admitted(_, request))) match
          case (Left(reason), _) => failure(EvalFailure.Unsupported(reason))
          case (_, Left(reason)) => failure(EvalFailure.Decode(reason))
          case (Right(machine), Right(Decoded(script, _, _))) =>
            val spender = RestrictingBudgetSpender(
              ScalusExUnits(request.budget.mem.toLong, request.budget.steps.toLong)
            )
            val log = Log()
            def spent = ExUnits(spender.getSpentBudget.memory, spender.getSpentBudget.steps)
            try
              val applied = request.args.foldLeft(script)((p, a) => p.applyArg(toData(a)))
              machine.evaluateScript(applied, spender, log)
              EvalResult.Success(spent, log.getLogs.toVector)
            catch
              case _: OutOfExBudgetError =>
                failure(EvalFailure.OutOfBudget, spent, log.getLogs.toVector)
              case NonFatal(e) =>
                val logs = log.getLogs.toVector
                val cause = Option(e.getMessage).getOrElse(e.getClass.getName)
                // the trace tells an operator which of the script's checks failed
                val traced =
                  if logs.isEmpty then cause else s"$cause; trace: ${logs.mkString(" | ")}"
                failure(EvalFailure.Script(traced), spent, logs)
              case _: StackOverflowError =>
                failure(EvalFailure.Script("stack overflow"), spent, log.getLogs.toVector)

  /** A [[PlutusVM]] whose `serialiseData` is [[DataSerialiser]].
    *
    * Scalus 1.2.0 encodes the index of a `Data.Constr` through a `Long`, so a constructor outside
    * `[0, 2^63)` (for example `2^64 - 1`, which the ledger lets a datum carry) serialises to other
    * bytes than the reference's, and a script that hashes it (Hydra's fanout compares the hash of
    * the serialised outputs with the closed head's) fails where the reference accepts. This is
    * `PlutusVM.evaluateDeBruijnedTerm` with one builtin's meaning replaced: the typing scheme and
    * costing function of Scalus's `SerialiseData` are kept, so the budget is unchanged.
    *
    * The same machine also replaces `verifyEcdsaSecp256k1Signature` with [[Secp256k1.verifyEcdsa]]
    * (Scalus 1.2.0 raises an error on a signature with `r = 0` or `s = 0` where the reference
    * returns `False`; preprod block 3,498,869).
    *
    * Reproducer: `ScalusConformanceReproducersSuite`; regression: `PreprodConwayRulesSuite`.
    */
  private final class SerialisingVm(
      language: Language,
      params: MachineParams,
      variant: BuiltinSemanticsVariant,
      protocol: MajorProtocolVersion
  ) extends PlutusVM(language, params, variant, platform, protocol):
    private val cardano = CardanoBuiltins(params.builtinCostModel, platform, variant)
    private val serialiseData: BuiltinRuntime =
      cardano.SerialiseData.copy(f =
        (_, args) =>
          CekValue.VCon(
            Constant.ByteString(ByteString.fromArray(DataSerialiser.serialise(args.head.asData)))
          )
      )
    private val verifyEcdsa: BuiltinRuntime =
      cardano.VerifyEcdsaSecp256k1Signature.copy(f =
        (_, args) =>
          CekValue.VCon(
            Constant.Bool(
              Secp256k1.verifyEcdsa(
                args(0).asByteString.bytes,
                args(1).asByteString.bytes,
                args(2).asByteString.bytes
              )
            )
          )
      )
    private val caseOnBuiltins =
      protocol >= MajorProtocolVersion.vanRossemPV || language == Language.PlutusV4

    private def runtime(fun: DefaultFun): BuiltinRuntime =
      fun match
        case DefaultFun.SerialiseData                 => serialiseData
        case DefaultFun.VerifyEcdsaSecp256k1Signature => verifyEcdsa
        case _                                        => cardano.getBuiltinRuntime(fun)

    override def evaluateDeBruijnedTerm(
        term: Term,
        spender: BudgetSpender,
        logger: Logger,
        debug: Boolean
    ): Term =
      val machine =
        CekMachine(machineParams, spender, logger, runtime, caseOnBuiltins, tracing = debug)
      DeBruijn.fromDeBruijnTerm(machine.evaluateTerm(term))

  /** Plutus data as the machine's `Data`; the nesting is walked on the heap. */
  private[scarab] def toData(root: PlutusData): Data =
    import TreeFold.Step
    TreeFold.fold[PlutusData, Data](root) {
      case PlutusData.Constr(tag, fields) =>
        Step.Branch(fields, ds => Right(Data.Constr(tag, PList.from(ds))))
      case PlutusData.Map(entries) =>
        Step.Branch(
          TreeFold.flatten(entries),
          ds => Right(Data.Map(PList.from(TreeFold.pairs(ds))))
        )
      case PlutusData.List(items) => Step.Branch(items, ds => Right(Data.List(PList.from(ds))))
      case PlutusData.I(v)        => Step.Leaf(Data.I(v))
      case PlutusData.B(b)        => Step.Leaf(Data.B(ByteString.fromArray(b.toArray)))
    }

  /** A small synchronised LRU map. */
  private final class BoundedCache[K, V](limit: Int):
    private val map = new java.util.LinkedHashMap[K, V](16, 0.75f, true):
      override def removeEldestEntry(e: java.util.Map.Entry[K, V]): Boolean = size() > limit
    def get(key: K): Option[V] = map.synchronized(Option(map.get(key)))
    def getOrElseUpdate(key: K, compute: => V): V =
      val hit = map.synchronized(Option(map.get(key)))
      hit.getOrElse {
        val value = compute
        map.synchronized(map.put(key, value))
        value
      }
