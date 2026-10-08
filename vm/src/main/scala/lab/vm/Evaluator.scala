// SPDX-License-Identifier: Apache-2.0
package lab.vm

import scalus.cardano.ledger.{ExUnits, Language, MajorProtocolVersion}
import scalus.uplc.*
import scalus.uplc.eval.*
import scala.util.control.NonFatal

final case class Budget(cpu: Long, memory: Long):
  private[vm] def units: ExUnits = ExUnits(memory = memory, steps = cpu)
object Budget:
  val researchLimit: Budget = Budget(10000000L, 100000L)
  private[vm] def from(value: ExUnits): Budget = Budget(value.steps, value.memory)

enum Outcome:
  case Success(term: Term, spent: Budget)
  case EvaluationFailure(kind: String, spent: Budget)
  case BudgetExhausted(attempted: Budget)
  case Unsupported(reason: String)
  case InvalidInput(reason: String)
  case InternalError(reason: String)

private[vm] def sha256(text: String): String =
  java.security.MessageDigest
    .getInstance("SHA-256")
    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    .map(b => f"${b & 0xff}%02x")
    .mkString

/** Explicit, byte-pinned reference parameters, never inferred from a live network. */
final class ReferenceParameters private[vm] (private[vm] val machine: MachineParams)
object ReferenceParameters:
  def parse(machineJson: String, builtinJson: String): Either[String, ReferenceParameters] =
    try
      require(
        sha256(machineJson) == "37b37d3c544b9fad73a112af2c8d8a9116f8639ea6b2e879f20bfafa0529a051",
        "CEK parameters differ from pinned reference E bytes"
      )
      require(
        sha256(builtinJson) == "56abe89beca140e9e9bbb7f573829397a3021d6340e22f868406dc4c10df83f9",
        "builtin parameters differ from pinned reference E bytes"
      )
      val entries = ujson
        .read(machineJson)
        .obj
        .iterator
        .flatMap { (key, value) =>
          Seq(
            s"$key-exBudgetCPU" -> value("exBudgetCPU").num.toLong,
            s"$key-exBudgetMemory" -> value("exBudgetMemory").num.toLong
          )
        }
        .toMap
      Right(
        new ReferenceParameters(
          MachineParams(
            CekMachineCosts.fromMap(entries),
            BuiltinCostModel.fromJsonString(builtinJson)
          )
        )
      )
    catch case NonFatal(e) => Left(s"invalid reference parameters: ${e.getMessage}")

/** Deterministic, effect-free arithmetic-only research adapter. No file/network/provider access.
  * Static gating is intentionally conservative, including unreachable code.
  */
final class Evaluator(parameters: ReferenceParameters):
  private val vm = new PlutusVM(
    Language.PlutusV3,
    parameters.machine,
    BuiltinSemanticsVariant.E,
    UnsupportedPlatform,
    MajorProtocolVersion.vanRossemPV
  )

  def evaluate(source: String, limit: Budget = Budget.researchLimit): Outcome =
    if limit.cpu < 0 || limit.memory < 0 || limit.cpu > Budget.researchLimit.cpu ||
      limit.memory > Budget.researchLimit.memory
    then Outcome.InvalidInput("budget must be nonnegative and within the research ceiling")
    else
      Evaluator.parse(source) match
        case Left(outcome) => outcome
        case Right(program) =>
          val spender = new RestrictingBudgetSpender(limit.units)
          def spent = Budget.from(spender.getSpentBudget)
          try
            val term = vm.evaluateDeBruijnedTerm(DeBruijn.deBruijnTerm(program.term), spender)
            Outcome.Success(term, spent)
          catch
            case _: OutOfExBudgetError => Outcome.BudgetExhausted(spent)
            case e: BuiltinError if e.cause.isInstanceOf[UnsupportedBackend] =>
              Outcome.Unsupported(e.cause.getMessage)
            case e: UnsupportedBackend => Outcome.Unsupported(e.getMessage)
            case e: BuiltinError
                if e.builtin == DefaultFun.DivideInteger && e.cause
                  .isInstanceOf[ArithmeticException] =>
              Outcome.EvaluationFailure("divideInteger-zero", spent)
            case e: BuiltinError => Outcome.InternalError(s"unexpected builtin failure: ${e.cause}")
            case e: MachineError => Outcome.InternalError(e.getClass.getSimpleName)
            case NonFatal(e) =>
              Outcome.InternalError(s"${e.getClass.getSimpleName}: ${e.getMessage}")

object Evaluator:
  val supportedBuiltins: Set[String] = Set("addInteger", "divideInteger")

  // Only these exact upstream source/result bytes may reach the Scalus parser.
  private val admittedHashes: Set[String] = Set(
    "b8300e6cb277ff498dd80ade14ae9b29c32f542ef937905c2a62fafad232131e",
    "7d35a9a740f4e5664f41b3933287e4feab8eb7a7a8f770d761e314a6e1214f94",
    "11b68ddf2da072aea052da5acca4c6724db8a52796f26c9e4b4d31ea41635028",
    "e4e07510de79300ec2cfacc7249ec7db488ac62989c884ac8fdb1a84a335de93",
    "81e69608d46a6c63c9a62172969d5a8d0cefba40fa311b19ef33e574cc9d742d",
    "5266485e73b95c6d69ecb4bf62d187c31a35b149d6ae83dac75343f2d9468063",
    "6a267026649eecca47aebcc5c00885362918addf02a5995789d954adbfeff934",
    "bf7b2f9fcd13bd77003c6bde3ae85fadd5f263ee794e481c7a4dbfca472bc2fc",
    "aef5150da8bf1291729c23734ac5663cdab82eaea75fe0470d3bfb14d68293bd"
  )
  def parse(source: String): Either[Outcome, Program] =
    if source.length > 65536 then Left(Outcome.InvalidInput("source exceeds research size limit"))
    else
      val digest = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .map(b => f"${b & 0xff}%02x")
        .mkString
      if !admittedHashes(digest) then
        Left(
          Outcome.Unsupported(
            "unregistered source bytes; only pinned fixture/result bytes are admitted"
          )
        )
      else
        try
          Program.parseUplc(source).left.map(e => Outcome.InvalidInput(e.toString)).flatMap { p =>
            if p.version != (1, 0, 0) then Left(Outcome.Unsupported("unsupported program version"))
            else if !supportedTerm(p.term) then
              Left(Outcome.Unsupported("unsupported AST capability"))
            else Right(p)
          }
        catch case NonFatal(e) => Left(Outcome.InvalidInput(e.getMessage))

  private def supportedTerm(term: Term): Boolean = term match
    case Term.Var(_, _)                     => false
    case Term.LamAbs(_, _, _)               => false
    case Term.Apply(function, argument, _)  => supportedTerm(function) && supportedTerm(argument)
    case Term.Force(_, _)                   => false
    case Term.Delay(_, _)                   => false
    case Term.Const(Constant.Integer(_), _) => true
    case Term.Const(_, _)                   => false
    case Term.Builtin(fun, _) => supportedBuiltins.exists(_.equalsIgnoreCase(fun.toString))
    case Term.Error(_)        => false
    case Term.Constr(_, _, _) => false
    case Term.Case(_, _, _)   => false
