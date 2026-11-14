// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.syntax.all.*
import scalus.cardano.ledger.Word64
import scalus.uplc.builtin.{BuiltinValue, ByteString, Data}
import scalus.uplc.eval.{
  BuiltinError,
  BuiltinTermArgumentExpectedMachineError,
  CaseOnBuiltinError,
  CekValEnv,
  CekValue,
  DeserializationError,
  EvaluationFailure,
  InvalidReturnValue,
  KnownTypeUnliftingError,
  MachineError,
  MissingCaseBranch,
  NonConstrScrutinized,
  NonFunctionalApplicationMachineError,
  NonPolymorphicInstantiationMachineError,
  OpenTermEvaluatedMachineError,
  OutOfExBudgetError,
  UnexpectedBuiltinTermArgumentMachineError
}
import scalus.uplc.{Constant, DefaultFun, DefaultUni, NamedDeBruijn, ScarabBuiltinView, Term}
import xyz.kd5ujc.scarab.codec.{Bytes, TreeFold}
import xyz.kd5ujc.scarab.plutus.ReferenceDiagnostic as Native

/** The reference's `EvaluationError` of a refusal, built from what Scalus 1.4.0 holds at the error
  * site: the typed fields of its exceptions (made `val` by `patches/scalus/cek-hooks.patch`), the
  * values they carry, and the decoder's record of indices Scalus cannot hold. Nothing is read from
  * an exception's message or from machine state after the throw.
  *
  * The cause of a CEK error is the reference's (`CekInternal.hs`, Plutus 507cab8c): a value is
  * discharged as `dischargeCekValue` does (lines 655-704), here over Scalus's values and on the
  * heap (ADR 0011); a term Scalus has already discharged (a builtin application, lines 963-1006 and
  * 1059-1073, Scalus `Cek.scala:1571-1693`) is converted. Every name is the reference's `fakeName`
  * (`DeBruijn/Internal.hs:133-134`), every binder index `deBruijnInitIndex` (line 107).
  */
private[plutus] object NativeDiagnostics:
  import TreeFold.Step

  type Converted[A] = Either[DiagnosticGap, A]

  /** `fakeName`: the name of every variable of a deserialised script. */
  val FakeName = "i"

  /** `deBruijnInitIndex`: the index a discharged lambda's binder carries. */
  private val BinderIndex = BigInt(0)

  private val Word64Modulus = BigInt(1) << 64

  /** The original index of a variable: its Scalus index, or for one Scalus holds lowered (-1) the
    * index the decoder recorded for that exact variable ([[FlatScan.wideOrigins]]).
    */
  final case class Origins(wide: Map[NamedDeBruijn, BigInt]):
    def index(name: NamedDeBruijn): Converted[BigInt] =
      if name.index >= 0 then Right(BigInt(name.index))
      else wide.get(name).toRight(DiagnosticGap.UnknownVariableOrigin(name))

  object Origins:
    val none: Origins = Origins(Map.empty)
    def of(scan: FlatScan): Origins = Origins(scan.wideOrigins)

  /** A `Word64` Scalus holds in a signed `Long`. */
  def word64(word: Word64): BigInt =
    val signed = BigInt(word.value)
    if signed < 0 then signed + Word64Modulus else signed

  private def bytes(b: ByteString): Bytes = Bytes.fromArray(b.bytes)

  // ---- refusals ------------------------------------------------------------------------------

  private def cek(error: Native.CekError, cause: Option[Native.Term]): Native.EvaluationError =
    Native.EvaluationError.Cek(Native.ErrorWithCause(error, cause))

  private def structural(cause: Native.MachineError, term: Native.Term): Native.EvaluationError =
    cek(Native.CekError.Structural(cause), Some(term))

  private def refused(provider: Throwable)(error: Converted[Native.EvaluationError]): EvalFailure =
    error.fold(
      EvalFailure.Unresolved(_, Some(provider)),
      EvalFailure.Refused(_, Some(provider))
    )

  /** `mkTermToEvaluate` 's scope check (`Common/Eval.hs:129-130`): `DeBruijnError (FreeIndex i)`.
    */
  def scope(index: BigInt): EvalFailure =
    EvalFailure.Refused(
      Native.EvaluationError.DeBruijn(Native.FreeVariableError.FreeIndex(index)),
      None
    )

  /** `mkTermToEvaluate` 's program version check (`Common/Eval.hs:123-127`). */
  def programVersion(
      version: Native.Version,
      language: PlutusLanguage,
      protocolMajor: Int
  ): EvalFailure =
    EvalFailure.Refused(
      Native.EvaluationError.Codec(
        Native.ScriptDecodeError
          .PlutusCoreLanguageNotAvailable(version, language, BigInt(protocolMajor))
      ),
      None
    )

  /** A script refused when the ledger decodes it for evaluation (`CodecError`,
    * `ledger-core/Plutus/Evaluate.hs:163-172`). `deserialiseScript` checks the ledger language
    * before it decodes and the remainder after (`SerialisedScript.hs:254-266`), so an unavailable
    * language is the refusal whatever else the bytes hold. `remainder` is what follows the script's
    * CBOR item.
    */
  def admission(
      problem: ScriptProblem,
      language: PlutusLanguage,
      protocolMajor: Int,
      remainder: => Bytes
  ): EvalFailure =
    def codec(error: Native.ScriptDecodeError) =
      EvalFailure.Refused(Native.EvaluationError.Codec(error), None)
    val introduced = PlutusAvailability.languageIntroducedIn(language)
    if protocolMajor < introduced then
      codec(
        Native.ScriptDecodeError
          .LedgerLanguageNotAvailable(language, BigInt(introduced), BigInt(protocolMajor))
      )
    else
      problem match
        case ScriptProblem.TrailingBytes(_) => codec(Native.ScriptDecodeError.Remainder(remainder))
        case other => EvalFailure.Unresolved(DiagnosticGap.DecoderReason(other), None)

  /** `restricting` 's refusal (`ExBudgetMode.hs:155-165`): `CekOutOfExError` with the remaining
    * budget, and no cause.
    */
  def budget(error: OutOfExBudgetError): EvalFailure = error match
    case exhausted: ReferenceBudget.Exhausted =>
      EvalFailure.Refused(
        cek(
          Native.CekError.Operational(Native.CekUserError.OutOfBudget(exhausted.remaining)),
          None
        ),
        Some(error)
      )
    case _ => EvalFailure.Unresolved(DiagnosticGap.ForeignBudgetRefusal, Some(error))

  /** The builtins whose one argument is `data`: given a `data` constant their unlifting succeeds,
    * so a refusal is their denotation's `evaluationFailure`, which Scalus signals by a
    * `DeserializationError` (`Builtin.scala:664-720`).
    */
  private val dataDestructors: Set[DefaultFun] = Set(
    DefaultFun.UnConstrData,
    DefaultFun.UnMapData,
    DefaultFun.UnListData,
    DefaultFun.UnIData,
    DefaultFun.UnBData
  )

  /** A refused builtin application (`evalBuiltinApp`, `CekInternal.hs:1071-1073`), whose cause is
    * the application term. Scalus signals the denotation's `evaluationFailure`, and the operational
    * unlifting refusals that become one (`CekInternal.hs:519-523`; [[UnliftingBounds]],
    * `ConstrIndex`), with a `BuiltinException` or one of the JDK exceptions
    * `ScalusEvaluator.referenceFailure` accepts; its type mismatches, structural unlifting errors
    * natively, with `KnownTypeUnliftingError` and `DeserializationError`.
    */
  private def builtinFailure(error: BuiltinError, origins: Origins): EvalFailure =
    refused(error)(term(error.term, origins).flatMap { cause =>
      val failure = cek(
        Native.CekError.Operational(Native.CekUserError.EvaluationFailure),
        Some(cause)
      )
      error.cause match
        case _: KnownTypeUnliftingError => Left(DiagnosticGap.UnliftingText(error.builtin, cause))
        case _: DeserializationError =>
          cause match
            case Native.Term.Apply(
                  Native.Term.Builtin(builtin),
                  Native.Term.Constant(Native.Constant.Data(_))
                ) if dataDestructors(builtin) =>
              Right(failure)
            case _ => Left(DiagnosticGap.UnliftingText(error.builtin, cause))
        case _ => Right(failure)
    })

  /** The reference's error for a machine refusal Scalus signalled with `error`, which
    * `ScalusEvaluator.referenceFailure` accepts as a reference failure.
    */
  def machine(error: Throwable, origins: Origins): EvalFailure = error match
    case e: OutOfExBudgetError => budget(e)
    // `computeCek` on `error` (`CekInternal.hs:874-875`)
    case e: EvaluationFailure =>
      EvalFailure.Refused(
        cek(
          Native.CekError.Operational(Native.CekUserError.EvaluationFailure),
          Some(Native.Term.Error)
        ),
        Some(e)
      )
    case e: BuiltinError => builtinFailure(e, origins)
    // `forceEvaluate` (`CekInternal.hs:976-977`): the forced value, discharged
    case e: NonPolymorphicInstantiationMachineError =>
      refused(e)(
        discharge(e.value, origins)
          .map(structural(Native.MachineError.NonPolymorphicInstantiation, _))
      )
    // `applyEvaluate` (`CekInternal.hs:1007-1008`): the applied value, discharged
    case e: NonFunctionalApplicationMachineError =>
      refused(e)(
        discharge(e.arg, origins).map(structural(Native.MachineError.NonFunctionalApplication, _))
      )
    // `lookupVarName` (`CekInternal.hs:1082-1088`): the variable
    case e: OpenTermEvaluatedMachineError =>
      refused(e)(
        origins
          .index(e.name)
          .map(i => structural(Native.MachineError.OpenTermEvaluated, Native.Term.Var(FakeName, i)))
      )
    // `forceEvaluate` and `applyEvaluate` of a builtin (`CekInternal.hs:974-975, 1005-1006`): the
    // application term, which Scalus discharges as the reference does (`Cek.scala:1580-1584,
    // 1650-1654`)
    case e: BuiltinTermArgumentExpectedMachineError =>
      refused(e)(
        term(e.term, origins).map(structural(Native.MachineError.BuiltinTermArgumentExpected, _))
      )
    case e: UnexpectedBuiltinTermArgumentMachineError =>
      refused(e)(
        term(e.term, origins)
          .map(structural(Native.MachineError.UnexpectedBuiltinTermArgument, _))
      )
    // `returnCek (FrameCases ...)` (`CekInternal.hs:936-944`): the scrutinee, discharged
    case e: MissingCaseBranch =>
      val tag = word64(e.tag)
      e.scrutinee.fold(EvalFailure.Unresolved(DiagnosticGap.UncapturedScrutinee(tag), Some(e))) {
        scrutinee =>
          refused(e)(
            discharge(scrutinee, origins).map(
              structural(Native.MachineError.MissingCaseBranch(tag), _)
            )
          )
      }
    // A constant scrutinee goes to the caser (`CekInternal.hs:946-949`), whose refusal is
    // `CekCaseBuiltinError`; any other value is `NonConstrScrutinizedMachineError` (line 950)
    case e: NonConstrScrutinized =>
      e.value match
        case CekValue.VCon(_) =>
          refused(e)(
            discharge(e.value, origins).flatMap(c => Left(DiagnosticGap.CaseBuiltinText(Some(c))))
          )
        case value =>
          refused(e)(
            discharge(value, origins).map(structural(Native.MachineError.NonConstrScrutinized, _))
          )
    case e: CaseOnBuiltinError =>
      EvalFailure.Unresolved(DiagnosticGap.CaseBuiltinText(None), Some(e))
    // `processLogsAndErrors` (`Common/Eval.hs:314-320`); Scalus's `isResultValid` is the same test
    // (`PlutusVM.scala:208-211`)
    case e: InvalidReturnValue =>
      EvalFailure.Refused(Native.EvaluationError.InvalidReturnValue, Some(e))
    // Scalus's one plain `MachineError` is the variable of index 0 (`Cek.scala:1493-1496`), which
    // `lookupVarName` refuses as an open term with that variable as the cause
    case e: MachineError if e.getClass == classOf[MachineError] =>
      EvalFailure.Refused(
        structural(Native.MachineError.OpenTermEvaluated, Native.Term.Var(FakeName, BigInt(0))),
        Some(e)
      )
    case e => EvalFailure.Unresolved(DiagnosticGap.UnattributedMachineError, Some(e))

  /** For logs: the family and case of a native error. */
  def summary(error: Native.EvaluationError): String = error match
    case Native.EvaluationError.Cek(Native.ErrorWithCause(Native.CekError.Structural(cause), _)) =>
      s"CekError ${cause.productPrefix}"
    case Native.EvaluationError.Cek(Native.ErrorWithCause(Native.CekError.Operational(cause), _)) =>
      s"CekError ${cause.productPrefix}"
    case Native.EvaluationError.DeBruijn(cause) => s"DeBruijnError ${cause.productPrefix}"
    case Native.EvaluationError.Codec(cause)    => s"CodecError ${cause.productPrefix}"
    case Native.EvaluationError.CostModelParameterMismatch => "CostModelParameterMismatch"
    case Native.EvaluationError.InvalidReturnValue         => "InvalidReturnValue"

  // ---- terms and values ----------------------------------------------------------------------

  /** What the discharge visits: a value, or a term under an environment and a number of lambdas
    * descended (`goValue` and `goValEnv`).
    */
  private enum Node:
    case Value(value: CekValue)
    case Body(env: CekValEnv, shift: BigInt, term: Term)

  private type Visit = Converted[Step[Node, Native.Term, DiagnosticGap]]

  private def leaf(term: Native.Term): Visit = Right(Step.Leaf(term))

  // Each fixed arity below is the arity of the children handed to TreeFold.
  private def unary(child: Node)(build: Native.Term => Native.Term): Visit =
    Right(Step.Branch(Vector(child), ts => Right(build(ts(0)))))

  /** `dischargeCekValue` of a value (`CekInternal.hs:657-704`). */
  def discharge(value: CekValue, origins: Origins): Converted[Native.Term] =
    fold(Node.Value(value), origins)

  /** A term Scalus holds, already discharged: under no environment every variable stays. */
  def term(term: Term, origins: Origins): Converted[Native.Term] =
    fold(Node.Body(Vector.empty, BigInt(0), term), origins)

  private def fold(root: Node, origins: Origins): Converted[Native.Term] =
    TreeFold.foldE[Node, Native.Term, DiagnosticGap](root) {
      case Node.Value(CekValue.VCon(c)) => constant(c).map(k => Step.Leaf(Native.Term.Constant(k)))
      case Node.Value(CekValue.VDelay(body, env)) =>
        unary(Node.Body(env, BigInt(0), body))(Native.Term.Delay(_))
      case Node.Value(CekValue.VLamAbs(_, body, env)) =>
        unary(Node.Body(env, BigInt(1), body))(Native.Term.Lam(FakeName, BinderIndex, _))
      // `VBuiltin _ term _`: the builtin with each `force` and argument so far, in order
      case Node.Value(CekValue.VBuiltin(fun, _, runtime, args, applied)) =>
        val kinds = ScarabBuiltinView.argKinds(runtime).take(applied)
        Right(
          Step.Branch(args.reverse.map(Node.Value(_)), values => builtinTerm(fun, kinds, values))
        )
      case Node.Value(CekValue.VConstr(tag, args)) =>
        Right(
          Step.Branch(
            args.map(Node.Value(_)),
            fields => Right(Native.Term.Constr(word64(tag), fields))
          )
        )
      case Node.Value(CekValue.VList(_)) => Left(DiagnosticGap.NonNativeValue)
      case Node.Body(env, shift, body)   => visit(env, shift, body, origins)
    }

  private def builtinTerm(
      fun: DefaultFun,
      kinds: Vector[Boolean],
      values: Vector[Native.Term]
  ): Converted[Native.Term] =
    kinds
      .foldLeftM[Converted, (Native.Term, Vector[Native.Term])](
        (Native.Term.Builtin(fun), values)
      ) {
        case ((built, rest), false) => Right((Native.Term.Force(built), rest))
        case ((built, rest), true) =>
          rest match
            case next +: others => Right((Native.Term.Apply(built, next), others))
            case _              => Left(DiagnosticGap.InconsistentBuiltin(fun))
      }
      .map(_._1)

  /** `goValEnv` (`CekInternal.hs:676-704`): a variable bound by a lambda descended stays, one the
    * environment binds is its value discharged, any other stays free.
    */
  private def visit(env: CekValEnv, shift: BigInt, term: Term, origins: Origins): Visit =
    term match
      case Term.Var(name, _) =>
        origins.index(name).flatMap { index =>
          val position = BigInt(env.size) - (index - shift)
          if shift >= index || position < 0 then leaf(Native.Term.Var(FakeName, index))
          else Right(Step.Branch(Vector(Node.Value(env(position.toInt)._2)), ts => Right(ts(0))))
        }
      case Term.LamAbs(_, body, _) =>
        unary(Node.Body(env, shift + 1, body))(Native.Term.Lam(FakeName, BinderIndex, _))
      case Term.Apply(function, argument, _) =>
        Right(
          Step.Branch(
            Vector(Node.Body(env, shift, function), Node.Body(env, shift, argument)),
            ts => Right(Native.Term.Apply(ts(0), ts(1)))
          )
        )
      case Term.Force(body, _) => unary(Node.Body(env, shift, body))(Native.Term.Force(_))
      case Term.Delay(body, _) => unary(Node.Body(env, shift, body))(Native.Term.Delay(_))
      case Term.Const(c, _)    => constant(c).map(k => Step.Leaf(Native.Term.Constant(k)))
      case Term.Builtin(f, _)  => leaf(Native.Term.Builtin(f))
      case Term.Error(_)       => leaf(Native.Term.Error)
      case Term.Constr(tag, args, _) =>
        Right(
          Step.Branch(
            args.map(Node.Body(env, shift, _)),
            fields => Right(Native.Term.Constr(word64(tag), fields))
          )
        )
      case Term.Case(scrutinee, branches, _) =>
        Right(
          Step.Branch(
            Node.Body(env, shift, scrutinee) +: branches.map(Node.Body(env, shift, _)),
            ts => Right(Native.Term.Case(ts(0), ts.drop(1)))
          )
        )

  // ---- constants -----------------------------------------------------------------------------

  /** A constant's type (`DefaultUni`, `Default/Universe.hs:136-155`). */
  def universe(root: DefaultUni): Native.Universe =
    TreeFold.fold[DefaultUni, Native.Universe](root) {
      case DefaultUni.Apply(function, argument) =>
        Step.Branch(Vector(function, argument), us => Right(Native.Universe.Apply(us(0), us(1))))
      case DefaultUni.Integer              => Step.Leaf(Native.Universe.Integer)
      case DefaultUni.ByteString           => Step.Leaf(Native.Universe.ByteString)
      case DefaultUni.String               => Step.Leaf(Native.Universe.Text)
      case DefaultUni.Unit                 => Step.Leaf(Native.Universe.Unit)
      case DefaultUni.Bool                 => Step.Leaf(Native.Universe.Bool)
      case DefaultUni.ProtoList            => Step.Leaf(Native.Universe.ProtoList)
      case DefaultUni.ProtoPair            => Step.Leaf(Native.Universe.ProtoPair)
      case DefaultUni.ProtoArray           => Step.Leaf(Native.Universe.ProtoArray)
      case DefaultUni.Data                 => Step.Leaf(Native.Universe.Data)
      case DefaultUni.BLS12_381_G1_Element => Step.Leaf(Native.Universe.BlsG1)
      case DefaultUni.BLS12_381_G2_Element => Step.Leaf(Native.Universe.BlsG2)
      case DefaultUni.BLS12_381_MlResult   => Step.Leaf(Native.Universe.BlsMlResult)
      case DefaultUni.BuiltinValue         => Step.Leaf(Native.Universe.Value)
    }

  /** `data` as the node's own type, on the heap. */
  def data(root: Data): PlutusData =
    TreeFold.fold[Data, PlutusData](root) {
      case Data.Constr(tag, fields) =>
        Step.Branch(fields.toScalaList, ds => Right(PlutusData.Constr(tag, ds)))
      case Data.Map(entries) =>
        Step.Branch(
          entries.toScalaList.flatMap((key, value) => List(key, value)),
          ds => Right(PlutusData.Map(TreeFold.pairs(ds)))
        )
      case Data.List(items) => Step.Branch(items.toScalaList, ds => Right(PlutusData.List(ds)))
      case Data.I(value)    => Step.Leaf(PlutusData.I(value))
      case Data.B(value)    => Step.Leaf(PlutusData.B(bytes(value)))
    }

  private def point(compressed: => ByteString): Converted[Bytes] =
    Either.catchNonFatal(bytes(compressed)).leftMap(_ => DiagnosticGap.BlsCompression)

  /** A constant (`Default/Universe.hs`), its lists, arrays and pairs on the heap. A `value` is its
    * entries in key order, as Scalus holds it normalised and the reference decodes it.
    */
  def constant(root: Constant): Converted[Native.Constant] =
    TreeFold.foldE[Constant, Native.Constant, DiagnosticGap](root) {
      case Constant.Integer(value)    => Right(Step.Leaf(Native.Constant.Integer(value)))
      case Constant.ByteString(value) => Right(Step.Leaf(Native.Constant.ByteString(bytes(value))))
      case Constant.String(value)     => Right(Step.Leaf(Native.Constant.Text(value)))
      case Constant.Unit              => Right(Step.Leaf(Native.Constant.UnitValue))
      case Constant.Bool(value)       => Right(Step.Leaf(Native.Constant.Bool(value)))
      case Constant.Data(value)       => Right(Step.Leaf(Native.Constant.Data(data(value))))
      case Constant.List(element, values) =>
        Right(Step.Branch(values, ks => Right(Native.Constant.List(universe(element), ks))))
      case Constant.Array(element, values) =>
        Right(Step.Branch(values, ks => Right(Native.Constant.Array(universe(element), ks))))
      case Constant.Pair(left, right) =>
        Right(Step.Branch(Vector(left, right), ks => Right(Native.Constant.Pair(ks(0), ks(1)))))
      case Constant.BLS12_381_G1_Element(value) =>
        point(value.toCompressedByteString).map(b => Step.Leaf(Native.Constant.BlsG1(b)))
      case Constant.BLS12_381_G2_Element(value) =>
        point(value.toCompressedByteString).map(b => Step.Leaf(Native.Constant.BlsG2(b)))
      case Constant.BLS12_381_MlResult(_) => Right(Step.Leaf(Native.Constant.BlsMlResult))
      case Constant.BuiltinValue(value) =>
        Right(
          Step.Leaf(
            Native.Constant.Value(
              BuiltinValue
                .toEntryList(value)
                .map((currency, tokens) =>
                  bytes(currency) -> tokens
                    .map((token, quantity) => bytes(token) -> quantity)
                    .toVector
                )
                .toVector
            )
          )
        )
    }
