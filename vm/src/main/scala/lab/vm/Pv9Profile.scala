// SPDX-License-Identifier: Apache-2.0
package lab.vm

import scalus.cardano.ledger.{CostModels, Language, MajorProtocolVersion}
import scalus.cardano.ledger.{Builtins as LedgerBuiltins}
import scalus.uplc.*
import scalus.uplc.eval.*
import scala.util.control.NonFatal

/** Offline synthetic profile. This is not the PV11/E upstream conformance harness. */
final class Pv9Profile private (
    private[vm] val parameters: MachineParams,
    private val program: Program
):
  private val vm = new PlutusVM(
    Language.PlutusV3,
    parameters,
    BuiltinSemanticsVariant.C,
    Blake2bPlatform,
    MajorProtocolVersion.changPV
  )

  def evaluate(input: SyntheticSpend, limit: Budget = Pv9Profile.researchLimit): Outcome =
    if limit.cpu < 0 || limit.memory < 0 ||
      limit.cpu > Pv9Profile.researchLimit.cpu || limit.memory > Pv9Profile.researchLimit.memory
    then Outcome.InvalidInput("budget outside the fixed research ceiling")
    else
      SyntheticSpend.context(input) match
        case Left(reason) => Outcome.InvalidInput(reason)
        case Right(context) =>
          val spender = new RestrictingBudgetSpender(limit.units)
          def spent = Budget.from(spender.getSpentBudget)
          try
            val applied = Term.Apply(program.term, Term.Const(Constant.Data(context)))
            vm.evaluateDeBruijnedTerm(DeBruijn.deBruijnTerm(applied), spender) match
              case result @ Term.Const(Constant.Unit, _) => Outcome.Success(result, spent)
              case _ => Outcome.EvaluationFailure("invalid-v3-return", spent)
          catch
            case _: OutOfExBudgetError => Outcome.BudgetExhausted(spent)
            case _: scalus.uplc.eval.EvaluationFailure =>
              Outcome.EvaluationFailure("explicit-error", spent)
            case e: BuiltinError if e.cause.isInstanceOf[UnsupportedBackend] =>
              Outcome.Unsupported(e.cause.getMessage)
            case e: UnsupportedBackend => Outcome.Unsupported(e.getMessage)
            case e: BuiltinError => Outcome.InternalError(s"unexpected builtin failure: ${e.cause}")
            case NonFatal(e) =>
              Outcome.InternalError(s"${e.getClass.getSimpleName}: ${e.getMessage}")

object Pv9Profile:
  val researchLimit = Budget(30000000L, 100000L)
  val modelSha256 = "6ab455d588e186649a6aae2761fec85ae5a2647cb002a2acb737604f698b21a2"
  val scriptSha256 = "1129132bf56b79e2492e88a096d816d695e28131b29ff7218d2362078146062e"
  val profileId = "synthetic-conway-pv9-v3-c-spend-v1"
  private[vm] val allowedBuiltins: Set[DefaultFun] = Set(
    DefaultFun.EqualsData,
    DefaultFun.EqualsInteger,
    DefaultFun.FstPair,
    DefaultFun.HeadList,
    DefaultFun.IfThenElse,
    DefaultFun.LessThanEqualsInteger,
    DefaultFun.NullList,
    DefaultFun.SndPair,
    DefaultFun.TailList,
    DefaultFun.UnConstrData,
    DefaultFun.UnIData,
    DefaultFun.UnListData,
    DefaultFun.UnMapData
  )
  // Positional indices from pinned PlutusV3Params, cross-checked against release ParamName.
  // Includes every CEK charge and every cost coefficient of every admitted builtin.
  private[vm] val requiredCostIndices: Map[String, Int] = Map(
    "cekApplyCost-exBudgetCPU" -> 17,
    "cekApplyCost-exBudgetMemory" -> 18,
    "cekBuiltinCost-exBudgetCPU" -> 19,
    "cekBuiltinCost-exBudgetMemory" -> 20,
    "cekConstCost-exBudgetCPU" -> 21,
    "cekConstCost-exBudgetMemory" -> 22,
    "cekDelayCost-exBudgetCPU" -> 23,
    "cekDelayCost-exBudgetMemory" -> 24,
    "cekForceCost-exBudgetCPU" -> 25,
    "cekForceCost-exBudgetMemory" -> 26,
    "cekLamCost-exBudgetCPU" -> 27,
    "cekLamCost-exBudgetMemory" -> 28,
    "cekStartupCost-exBudgetCPU" -> 29,
    "cekStartupCost-exBudgetMemory" -> 30,
    "cekVarCost-exBudgetCPU" -> 31,
    "cekVarCost-exBudgetMemory" -> 32,
    "equalsData-cpu-arguments-intercept" -> 68,
    "equalsData-cpu-arguments-slope" -> 69,
    "equalsData-memory-arguments" -> 70,
    "equalsInteger-cpu-arguments-intercept" -> 71,
    "equalsInteger-cpu-arguments-slope" -> 72,
    "equalsInteger-memory-arguments" -> 73,
    "fstPair-cpu-arguments" -> 78,
    "fstPair-memory-arguments" -> 79,
    "headList-cpu-arguments" -> 80,
    "headList-memory-arguments" -> 81,
    "ifThenElse-cpu-arguments" -> 84,
    "ifThenElse-memory-arguments" -> 85,
    "lessThanEqualsInteger-cpu-arguments-intercept" -> 96,
    "lessThanEqualsInteger-cpu-arguments-slope" -> 97,
    "lessThanEqualsInteger-memory-arguments" -> 98,
    "nullList-cpu-arguments" -> 128,
    "nullList-memory-arguments" -> 129,
    "sndPair-cpu-arguments" -> 165,
    "sndPair-memory-arguments" -> 166,
    "tailList-cpu-arguments" -> 171,
    "tailList-memory-arguments" -> 172,
    "unConstrData-cpu-arguments" -> 177,
    "unConstrData-memory-arguments" -> 178,
    "unIData-cpu-arguments" -> 179,
    "unIData-memory-arguments" -> 180,
    "unListData-cpu-arguments" -> 181,
    "unListData-memory-arguments" -> 182,
    "unMapData-cpu-arguments" -> 183,
    "unMapData-memory-arguments" -> 184,
    "cekConstrCost-exBudgetCPU" -> 193,
    "cekConstrCost-exBudgetMemory" -> 194,
    "cekCaseCost-exBudgetCPU" -> 195,
    "cekCaseCost-exBudgetMemory" -> 196
  )
  private[vm] def validateUsedCosts(costs: Vector[Long]): Either[String, Unit] =
    requiredCostIndices
      .collectFirst {
        case (name, index) if index >= costs.size           => s"missing used cost: $name at $index"
        case (name, index) if costs(index) == Long.MaxValue => s"padded used cost: $name"
      }
      .toLeft(())

  // Exact signed decimal parsing: no JSON Double conversion, rounding or exponent notation.
  private[vm] def parseValues(text: String): Either[String, Vector[Long]] =
    if text.length > 16384 then Left("model exceeds size bound")
    else
      val trimmed = text.trim
      if !trimmed.startsWith("[") || !trimmed.endsWith("]") then Left("expected cost array")
      else
        val entries = trimmed.drop(1).dropRight(1).split(",", -1).toVector.map(_.trim)
        if entries.size != 251 then Left("expected exactly 251 PV9 costs")
        else
          entries.foldLeft[Either[String, Vector[Long]]](Right(Vector.empty)) { (acc, value) =>
            for
              values <- acc
              _ <- Either.cond(value.matches("-?(0|[1-9][0-9]*)"), (), "noninteger cost")
              number <- value.toLongOption.toRight("cost outside signed Int64")
            yield values :+ number
          }

  private[vm] def pv9Available(fun: DefaultFun): Boolean =
    LedgerBuiltins
      .findBuiltinsIntroducedIn(
        Language.PlutusV3,
        MajorProtocolVersion.changPV
      )
      .contains(fun)

  // Closed AST and closed variables; this runs only after the exact script hash is admitted.
  private def supported(term: Term, bound: Set[String]): Boolean = term match
    case Term.Var(name, _)                  => bound(name.name)
    case Term.LamAbs(name, body, _)         => supported(body, bound + name)
    case Term.Apply(f, a, _)                => supported(f, bound) && supported(a, bound)
    case Term.Force(body, _)                => supported(body, bound)
    case Term.Delay(body, _)                => supported(body, bound)
    case Term.Const(Constant.Integer(_), _) => true
    case Term.Const(Constant.Unit, _)       => true
    case Term.Const(_, _)                   => false
    case Term.Builtin(fun, _)               => allowedBuiltins(fun) && pv9Available(fun)
    case Term.Error(_)                      => true
    case Term.Constr(_, _, _)               => false
    case Term.Case(_, _, _)                 => false

  def create(modelText: String, scriptText: String): Either[String, Pv9Profile] =
    if modelText.length > 16384 || scriptText.length > 16384 then Left("profile input too large")
    else if sha256(modelText) != modelSha256 then Left("unregistered PV9 model bytes")
    else if sha256(scriptText) != scriptSha256 then Left("unregistered PV9 script bytes")
    else
      try
        for
          costs <- parseValues(modelText)
          _ <- validateUsedCosts(costs)
          parsed <- Program.parseUplc(scriptText).left.map(_.toString)
          _ <- Either.cond(parsed.version == (1, 0, 0), (), "unsupported UPLC version")
          _ <- Either.cond(supported(parsed.term, Set.empty), (), "unsupported PV9 AST")
          parameters = MachineParams.fromCostModels(
            CostModels(Map(Language.PlutusV3.languageId -> costs)),
            Language.PlutusV3,
            MajorProtocolVersion.changPV
          )
        yield new Pv9Profile(parameters, parsed)
      catch case NonFatal(e) => Left(s"invalid PV9 profile: ${e.getMessage}")
