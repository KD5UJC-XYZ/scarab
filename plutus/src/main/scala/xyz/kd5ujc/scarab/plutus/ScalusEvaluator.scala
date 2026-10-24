// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.Id
import cats.effect.kernel.Sync
import java.util.concurrent.{
  ExecutionException,
  SynchronousQueue,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit
}
import xyz.kd5ujc.scarab.codec.{Bytes, TreeFold}
import xyz.kd5ujc.scarab.plutus.ExUnits
import scala.util.control.NonFatal
import scalus.cardano.ledger.{CostModels, ExUnits as ScalusExUnits, Language, MajorProtocolVersion}
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.builtin.{ByteString, Data, platform}
import scalus.uplc.builtin.bls12_381.{G1Element, G2Element}
import xyz.kd5ujc.scarab.blst.{Platform, RawDst}
import scalus.uplc.eval.{
  BudgetSpender,
  BuiltinError,
  BuiltinException,
  DeserializationError,
  InvalidReturnValue,
  MachineError,
  UnknownBuiltin,
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
  /** Large enough for the machine, which recurses on deeply nested terms and values. */
  private val StackSize = 256L * 1024 * 1024

  /** The threads that evaluate, each with a stack of [[StackSize]]. Starting one costs a mapping of
    * that size and its release, and the mainnet follower in late Alonzo started one per script, 220
    * a second (issue 133). An idle thread now waits for the next script for [[KeepAlive]] seconds,
    * which is what a block of scripts after another needs; the pool grows to the number of
    * evaluations in flight at once, as one thread each did before. Daemon threads, so that an idle
    * one never holds the process open.
    */
  private val KeepAlive = 30L
  private lazy val bigStacks: ThreadPoolExecutor =
    val factory: ThreadFactory = task =>
      val thread = Thread(null, task, "plutus-evaluator", StackSize)
      thread.setDaemon(true)
      thread
    ThreadPoolExecutor(0, Int.MaxValue, KeepAlive, TimeUnit.SECONDS, SynchronousQueue(), factory)

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
      remainder: Int,
      scan: FlatScan
  )

  /** Runs a decoded program on the machine; the result term is not needed. */
  private type Machine = (PlutusVM, DeBruijnedProgram, BudgetSpender, Logger) => Unit

  private val machineRun: Machine = (vm, program, spender, logger) =>
    val _ = vm.evaluateScript(program, spender, logger)

  /** An interpreter whose flat decoder and machine run are replaced: the seam through which a test
    * places a fault (an exception of Scalus's or of the runtime) at either site.
    */
  private[plutus] def injected(
      decode: Array[Byte] => Either[FlatScan.Failure, FlatScan.Decoded],
      machine: (PlutusVM, DeBruijnedProgram, BudgetSpender, Logger) => Unit
  ): ScriptEvaluator[Id] & ScriptDecoder[Id] = Interpreter(decode, machine)

  private final class Interpreter(
      decode: Array[Byte] => Either[FlatScan.Failure, FlatScan.Decoded] = FlatScan.decode,
      runMachine: Machine = machineRun
  ) extends ScriptEvaluator[Id]
      with ScriptDecoder[Id]:
    // Deliberate instance-scoped caches (one Interpreter per evaluator value, not hidden in an object;
    // `default` shares one): `evaluate` is a pure function of its request, and the caches only memoise
    // PlutusVM construction and program decoding. RetryingMemo shares construction atomically and
    // BoundedCache is synchronized. Unavailable attempts are never retained as input verdicts.
    private val vms = RetryingMemo[VmKey, EvalFailure.Unsupported, PlutusVM]()
    private val programs = BoundedCache[Bytes, Either[ScriptDecodeFailure, Decoded]](512)

    def evaluate(request: EvalRequest): EvalResult =
      onBigStack(error =>
        EvalResult.Failure(
          EvalFailure.Unsupported(BackendFailure.Defect(error)),
          ExUnits.zero,
          Vector.empty
        )
      )(
        run(request)
      )

    def validate(
        language: PlutusLanguage,
        protocolMajor: Int,
        script: Bytes
    ): Either[ScriptDecodeFailure, Unit] =
      cached(script) match
        case Some(d) => d.flatMap(available(_, language, protocolMajor))
        case None =>
          onBigStack[Either[ScriptDecodeFailure, Unit]](error =>
            Left(ScriptDecodeFailure.Unavailable(BackendFailure.Defect(error)))
          )(
            program(script).flatMap(available(_, language, protocolMajor))
          )

    /** The machine recurses on the term; run on a thread with a large stack. */
    private def onBigStack[A](failed: Throwable => A)(body: => A): A =
      try bigStacks.submit(() => body).get()
      catch case error: ExecutionException => failed(Option(error.getCause).getOrElse(error))

    /** The checks of the ledger's deserialisation and of `mkTermToEvaluate`, in that order. */
    private def admitted(
        decoded: Decoded,
        request: EvalRequest
    ): Either[ScriptDecodeFailure, Decoded] =
      for
        _ <- available(decoded, request.language, request.protocolMajor)
        _ <- PlutusAvailability
          .versionFailure(request.language, request.protocolMajor, decoded.program.version)
          .map(f => ScriptDecodeFailure.Malformed(ScriptProblem.Availability(f)))
          .toLeft(())
      yield decoded

    private def available(
        decoded: Decoded,
        language: PlutusLanguage,
        protocolMajor: Int
    ): Either[ScriptDecodeFailure, Unit] =
      PlutusAvailability
        .decodeFailure(language, protocolMajor, decoded.builtins)
        .orElse(PlutusAvailability.scanFailure(protocolMajor, decoded.scan))
        .map(ScriptProblem.Availability.apply)
        // `deserialiseScript` tolerates bytes after the script's CBOR item in V1 and V2 only
        .orElse(
          Option.when(decoded.remainder > 0 && language == PlutusLanguage.V3)(
            ScriptProblem.TrailingBytes(decoded.remainder)
          )
        )
        .map(ScriptDecodeFailure.Malformed.apply)
        .toLeft(())

    private def vm(request: EvalRequest): Either[EvalFailure.Unsupported, PlutusVM] =
      val key = VmKey(request.language, request.protocolMajor, request.costModel)
      vms.getOrElseUpdate(key)(buildVm(key))

    private def buildVm(key: VmKey): Either[EvalFailure.Unsupported, PlutusVM] =
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
          CorrectedVm(
            language,
            corrected(params, variant),
            variant,
            protocol
          )
        )
      catch
        case NonFatal(e) =>
          Left(EvalFailure.Unsupported(BackendFailure.Defect(e)))

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

    private def cached(script: Bytes): Option[Either[ScriptDecodeFailure, Decoded]] =
      programs.get(script)

    /** Decodes with Scarab's own walker ([[FlatScan.decode]]), which keeps its stack on the heap;
      * it runs on a big stack only because [[validate]] shares the thread pool of the machine.
      */
    private def program(script: Bytes): Either[ScriptDecodeFailure, Decoded] =
      programs.getOrElseUpdate(
        script,
        try
          val bytes = script.toArray
          // Scalus rejects bytes after the CBOR item, the reference keeps them as a remainder
          val used = PlutusScriptBytes.cborItemLength(bytes)
          PlutusScriptBytes
            .flatOf(bytes)
            .toRight(ScriptDecodeFailure.Malformed(ScriptProblem.CborByteStringExpected))
            .flatMap { flat =>
              decode(flat).left
                .map(failure => ScriptDecodeFailure.Malformed(ScriptProblem.Flat(failure)))
                .map { decoded =>
                  Decoded(
                    decoded.program,
                    builtinsOf(decoded.program.term),
                    bytes.length - used,
                    decoded.scan
                  )
                }
            }
        catch
          case _: StackOverflowError =>
            Left(ScriptDecodeFailure.Unavailable(BackendFailure.StackExhausted))
          case NonFatal(error) =>
            Left(ScriptDecodeFailure.Unavailable(BackendFailure.Defect(error)))
      )(ScriptDecodeFailure.cacheable)

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
        failure(EvalFailure.Unsupported(BackendFailure.BudgetOutOfRange(request.budget)))
      else
        (vm(request), program(request.script).flatMap(admitted(_, request))) match
          case (Left(reason), _) => failure(reason)
          case (_, Left(ScriptDecodeFailure.Malformed(reason))) =>
            failure(EvalFailure.Decode(reason))
          case (_, Left(ScriptDecodeFailure.Unavailable(reason))) =>
            failure(EvalFailure.Unsupported(reason))
          case (Right(_), Right(Decoded(_, _, _, FlatScan(_, _, _, Some(index), _)))) =>
            // `mkTermToEvaluate` checks the scope of the applied term before the machine starts
            failure(EvalFailure.OpenTerm(index))
          case (Right(machine), Right(Decoded(script, _, _, _))) =>
            val spender = RestrictingBudgetSpender(
              ScalusExUnits(request.budget.mem.toLong, request.budget.steps.toLong)
            )
            val log = EvaluationLog()
            def spent = ExUnits(spender.getSpentBudget.memory, spender.getSpentBudget.steps)
            try
              val applied = request.args.foldLeft(script)((p, a) => p.applyArg(toData(a)))
              runMachine(machine, applied, spender, log)
              EvalResult.Success(spent, log.getLogs.toVector)
            catch
              case _: OutOfExBudgetError =>
                failure(EvalFailure.OutOfBudget, spent, log.getLogs.toVector)
              case NonFatal(error) =>
                val logs = log.getLogs.toVector
                val reason = log.refused.getOrElse(
                  if referenceFailure(error) then EvalFailure.Script(ScriptFailure(error, logs))
                  else EvalFailure.Unsupported(BackendFailure.Defect(error))
                )
                failure(reason, spent, logs)
              case _: StackOverflowError =>
                failure(
                  EvalFailure.Unsupported(BackendFailure.StackExhausted),
                  spent,
                  log.getLogs.toVector
                )

  /** The vendor's typed CEK failures are reference script failures. BuiltinError also wraps backend
    * defects, so its cause is accepted only for the documented builtin/input combinations.
    */
  private[plutus] def referenceFailure(error: Throwable): Boolean = error match
    case _: UnknownBuiltin => false
    case builtin: BuiltinError =>
      builtin.cause match
        case _: BuiltinException => true
        case _: ArithmeticException =>
          Set(
            DefaultFun.DivideInteger,
            DefaultFun.QuotientInteger,
            DefaultFun.ModInteger,
            DefaultFun.RemainderInteger,
            DefaultFun.ExpModInteger
          )(builtin.builtin)
        case _: IndexOutOfBoundsException => builtin.builtin == DefaultFun.IndexByteString
        case _: NoSuchElementException =>
          Set(DefaultFun.HeadList, DefaultFun.TailList)(builtin.builtin)
        case _: UnsupportedOperationException => builtin.builtin == DefaultFun.TailList
        case _: IllegalArgumentException =>
          Set(
            DefaultFun.DecodeUtf8,
            DefaultFun.VerifyEd25519Signature,
            DefaultFun.VerifyEcdsaSecp256k1Signature,
            DefaultFun.VerifySchnorrSecp256k1Signature,
            DefaultFun.Bls12_381_G1_uncompress,
            DefaultFun.Bls12_381_G2_uncompress,
            DefaultFun.Bls12_381_G1_hashToGroup,
            DefaultFun.Bls12_381_G2_hashToGroup
          )(builtin.builtin)
        // blst0.3.15 bindings/blst.swg maps its BLST_ERROR to exactly java.lang.RuntimeException
        // for these two uncompression operations; subclasses remain backend defects.
        case cause if cause.getClass == classOf[RuntimeException] =>
          Set(DefaultFun.Bls12_381_G1_uncompress, DefaultFun.Bls12_381_G2_uncompress)(
            builtin.builtin
          )
        case _ => false
    case _: MachineError | _: InvalidReturnValue => true
    case _                                       => false

  /** Scalus BuiltinRuntime.f returns CekValue, not Either, and Cek.scala1647-1660 catches callback
    * exceptions into BuiltinError. There is no failure-value path. Keep the typed refusal in the
    * evaluation-local log and signal only with its documented BuiltinException. The interpreter
    * translates it to EvalResult before leaving its worker; no custom domain exception is used.
    */
  private final class EvaluationLog extends Log:
    var refused: Option[EvalFailure] = None

  private def unavailable(logger: Logger, reason: EvalFailure): Nothing =
    logger match
      case log: EvaluationLog => log.refused = Some(reason)
      case _                  => ()
    throw new BuiltinException("builtin evaluation unavailable")

  /** A [[PlutusVM]] with builtins whose meaning Scalus gets wrong replaced. This is
    * `PlutusVM.evaluateDeBruijnedTerm` with those meanings swapped in: the typing scheme and
    * costing function of Scalus's builtins are kept, so the budget is unchanged.
    *
    *   - `serialiseData` is [[DataSerialiser]]. Scalus 1.2.0 encoded the index of a `Data.Constr`
    *     through a `Long` (fixed in 1.3.0), but its encoder (borer) still stops at 1000 levels of
    *     array nesting, where the reference has no limit and a datum can be as deep as the block
    *     that carries it. A script that hashes such a datum (Hydra's fanout compares the hash of
    *     the serialised outputs with the closed head's) must get the reference's bytes.
    *   - `verifyEcdsaSecp256k1Signature` is [[Secp256k1.verifyEcdsa]] (Scalus 1.3.0 raises an error
    *     on a signature with `r = 0` or `s = 0` where the reference returns `False`; preprod block
    *     3,498,869).
    *   - `constrData` builds through [[ConstrIndex]]: Scalus 1.3.0 asserts a non-negative index,
    *     which the reference takes in variants A to C, and accepts any index in variants D and E,
    *     where the reference takes an unsigned 64-bit integer only.
    *   - `equalsData` is [[DataEquality]]: Scalus 1.3.0 compares a `Data.Map` as a set of its
    *     entries, where the reference compares the ordered list of entries.
    *
    * Reproducers: `DataSerialiserSuite`, `Secp256k1Suite`, `ConstrIndexReferenceSuite`,
    * `DataEqualityReferenceSuite`; regressions: `PreprodConwayRulesSuite`,
    * `PreprodSecp256k1RulesSuite`.
    */
  private[plutus] final class CorrectedVm(
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
    private val equalsData: BuiltinRuntime =
      cardano.EqualsData.copy(f =
        (_, args) =>
          CekValue.VCon(Constant.Bool(DataEquality.equal(args(0).asData, args(1).asData)))
      )
    private val verifyEcdsa: BuiltinRuntime =
      cardano.VerifyEcdsaSecp256k1Signature.copy(f = (logger, args) =>
        if !scalus.crypto.jni.CryptoJni.isEnabled then
          unavailable(
            logger,
            EvalFailure.Unsupported(
              BackendFailure.BuiltinUnavailable(DefaultFun.VerifyEcdsaSecp256k1Signature)
            )
          )
        CekValue.VCon(
          Constant.Bool(
            Secp256k1
              .verifyEcdsa(
                args(0).asByteString.bytes,
                args(1).asByteString.bytes,
                args(2).asByteString.bytes
              )
              .fold(
                {
                  case Secp256k1.Error.Defect(cause) =>
                    unavailable(logger, EvalFailure.Unsupported(BackendFailure.Defect(cause)))
                  case Secp256k1.Error.NativeUnavailable =>
                    unavailable(
                      logger,
                      EvalFailure.Unsupported(
                        BackendFailure.BuiltinUnavailable(DefaultFun.VerifyEcdsaSecp256k1Signature)
                      )
                    )
                  case error =>
                    unavailable(logger, EvalFailure.Script(ScriptFailure(error, Vector.empty)))
                },
                identity
              )
          )
        ))

    /** Scalus hands the domain separation tag of `hashToGroup` to blst as a Latin-1 `String`, which
      * the native layer reads as (modified) UTF-8: a byte of 0x80 or above, or 0, makes another tag
      * and another point than the reference computes (conformance cases `hash-dst-len-255` and
      * `large-dst`). The blst-java API has no byte-level entry, so these tags are refused with a
      * typed failure on platforms without the raw-byte JNI adapter. ASCII tags are served as before
      * there; Linux/amd64 uses the byte-array adapter for every tag.
      */
    private def asciiDstOnly(base: BuiltinRuntime): BuiltinRuntime =
      base.copy(f =
        (logger, args) =>
          if args(1).asByteString.bytes.exists(_ <= 0) then
            unavailable(
              logger,
              EvalFailure.Unsupported(
                BackendFailure.BinaryDstUnavailable
              )
            )
          else base.f(logger, args)
      )

    /** Upstream's binary-DST fix, pinned in blst-native/native/README.md. Preserve the original
      * runtime's typing, costing and >255-byte rejection; only the native byte-array entry differs.
      * Other platforms retain the typed refusal until an equivalent portable artifact is supplied.
      */
    private def rawDst(base: BuiltinRuntime)(
        hash: (Array[Byte], Array[Byte]) => Constant
    ): BuiltinRuntime =
      base.copy(f = (logger, args) =>
        val message = args(0).asByteString.bytes
        val dst = args(1).asByteString.bytes
        if dst.length > 255 then base.f(logger, args)
        else if Platform.current != Platform.LinuxAmd64 then asciiDstOnly(base).f(logger, args)
        else
          try CekValue.VCon(hash(message, dst))
          catch
            case _: LinkageError =>
              unavailable(
                logger,
                EvalFailure.Unsupported(BackendFailure.NativeHashToGroupUnavailable)
              ))
    private val hashToGroupG1 = rawDst(cardano.Bls12_381_G1_hashToGroup) { (message, dst) =>
      Constant.BLS12_381_G1_Element(G1Element(ByteString.fromArray(RawDst.hashG1(message, dst))))
    }
    private val hashToGroupG2 = rawDst(cardano.Bls12_381_G2_hashToGroup) { (message, dst) =>
      Constant.BLS12_381_G2_Element(G2Element(ByteString.fromArray(RawDst.hashG2(message, dst))))
    }
    private val verifySchnorr = cardano.VerifySchnorrSecp256k1Signature.copy(f =
      (logger, args) =>
        if !scalus.crypto.jni.CryptoJni.isEnabled then
          unavailable(
            logger,
            EvalFailure.Unsupported(
              BackendFailure.BuiltinUnavailable(DefaultFun.VerifySchnorrSecp256k1Signature)
            )
          )
        else cardano.VerifySchnorrSecp256k1Signature.f(logger, args)
    )
    private val ensurable =
      variant == BuiltinSemanticsVariant.D || variant == BuiltinSemanticsVariant.E
    private val constrData: BuiltinRuntime =
      cardano.ConstrData.copy(f = (logger, args) =>
        val index = args(0).asInteger
        // The `ensurable` variants unlift the index as a Word64; out of range that is a failure
        // of the script (`Builtins.hs` ConstrData), which a BuiltinException is here.
        if ensurable && !ConstrIndex.word64(index) then
          throw new BuiltinException("constrData index out of the Word64 range")
        else if index >= 0 then cardano.ConstrData.f(logger, args)
        else
          val fields = args(1).asList.map {
            case Constant.Data(d) => d
            case _                => throw new DeserializationError(DefaultFun.ConstrData, args(1))
          }
          CekValue.VCon(Constant.Data(ConstrIndex.constr(index, PList.from(fields)))))
    private val caseOnBuiltins =
      protocol >= MajorProtocolVersion.vanRossemPV || language == Language.PlutusV4

    private[plutus] def runtime(fun: DefaultFun): BuiltinRuntime =
      fun match
        case DefaultFun.SerialiseData                   => serialiseData
        case DefaultFun.ConstrData                      => constrData
        case DefaultFun.EqualsData                      => equalsData
        case DefaultFun.VerifyEcdsaSecp256k1Signature   => verifyEcdsa
        case DefaultFun.VerifySchnorrSecp256k1Signature => verifySchnorr
        case DefaultFun.Bls12_381_G1_hashToGroup        => hashToGroupG1
        case DefaultFun.Bls12_381_G2_hashToGroup        => hashToGroupG2
        case _                                          => cardano.getBuiltinRuntime(fun)

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
  private[plutus] final class BoundedCache[K, V](limit: Int):
    private val map = new java.util.LinkedHashMap[K, V](16, 0.75f, true):
      override def removeEldestEntry(e: java.util.Map.Entry[K, V]): Boolean = size() > limit
    def get(key: K): Option[V] = map.synchronized(Option(map.get(key)))
    def getOrElseUpdate(key: K, compute: => V)(cacheable: V => Boolean): V =
      val hit = map.synchronized(Option(map.get(key)))
      hit.getOrElse {
        val value = compute
        if cacheable(value) then
          val _ = map.synchronized(map.put(key, value))
        value
      }
