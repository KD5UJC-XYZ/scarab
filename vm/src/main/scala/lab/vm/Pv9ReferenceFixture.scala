// SPDX-License-Identifier: Apache-2.0
package lab.vm

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scalus.cardano.ledger.{Language, MajorProtocolVersion}
import scalus.uplc.*
import scalus.uplc.builtin.Data
import scalus.uplc.builtin.Data.toCbor
import scalus.uplc.eval.*
import scala.util.control.NonFatal

/** One hash-admitted reference context; no arbitrary Data or Flat evaluator. */
final class Pv9ReferenceFixture private (
    parameters: MachineParams,
    program: Program,
    private val context: Data
):
  private val vm = new PlutusVM(
    Language.PlutusV3,
    parameters,
    BuiltinSemanticsVariant.C,
    Blake2bPlatform,
    MajorProtocolVersion.changPV
  )

  // Both accessors produce fresh representations, never expose mutable admitted bytes.
  def contextCbor: Array[Byte] = context.toCbor
  def orderedContextTree: ujson.Value = Pv9ReferenceFixture.orderedTree(context)

  def evaluate(limit: Budget = Pv9Profile.researchLimit): Outcome =
    if limit.cpu < 0 || limit.memory < 0 ||
      limit.cpu > Pv9Profile.researchLimit.cpu || limit.memory > Pv9Profile.researchLimit.memory
    then Outcome.InvalidInput("budget outside the fixed research ceiling")
    else
      val spender = new RestrictingBudgetSpender(limit.units)
      def spent = Budget.from(spender.getSpentBudget)
      try
        val applied = Term.Apply(program.term, Term.Const(Constant.Data(context)))
        vm.evaluateDeBruijnedTerm(DeBruijn.deBruijnTerm(applied), spender) match
          case unit @ Term.Const(Constant.Unit, _) => Outcome.Success(unit, spent)
          case _ => Outcome.EvaluationFailure("invalid-v3-return", spent)
      catch
        case _: OutOfExBudgetError => Outcome.BudgetExhausted(spent)
        case _: scalus.uplc.eval.EvaluationFailure =>
          Outcome.EvaluationFailure("explicit-error", spent)
        case e: BuiltinError if e.cause.isInstanceOf[UnsupportedBackend] =>
          Outcome.Unsupported(e.cause.getMessage)
        case e: UnsupportedBackend => Outcome.Unsupported(e.getMessage)
        case e: BuiltinError => Outcome.InternalError(s"unexpected builtin failure: ${e.cause}")
        case NonFatal(e) => Outcome.InternalError(s"${e.getClass.getSimpleName}: ${e.getMessage}")

object Pv9ReferenceFixture:
  val fixtureId = "reference-conway-pv9-v3-spend-1"
  val contextSha256 = "6b17abecf3fff8cdd91091d48c4245fb946f56fdc8270a9115339f7659689776"
  val scriptCborSha256 = "57fb50f08ffc1222cbe2b652db3dcfed0f714da98f8170cb104aee2bde4070f6"
  val combinationSha256 = "9658c512e9bae5bc7f6ad14eca1c64067d5bb5465d7505abb2a798b70a7417f4"
  val referenceBudget = Budget(19269788L, 47600L)

  private def digest(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  /** Hash every member and the length-framed tuple BEFORE UTF-8, UPLC or Data parsing. The
    * serialized script is checked against the parsed, already-pinned source; this API does not
    * decode caller-supplied Flat programs.
    */
  def admit(
      modelBytes: Array[Byte],
      scriptSourceBytes: Array[Byte],
      scriptCborBytes: Array[Byte],
      contextBytes: Array[Byte]
  ): Either[String, Pv9ReferenceFixture] =
    if modelBytes.length > 16384 || scriptSourceBytes.length > 16384 ||
      scriptCborBytes.length != 369 || contextBytes.length != 424
    then Left("fixture byte sizes outside pinned bounds")
    else
      // Bound before cloning; caller mutations cannot change bytes after admission.
      val members =
        Vector(modelBytes, scriptSourceBytes, scriptCborBytes, contextBytes).map(_.clone())
      val pins =
        Vector(Pv9Profile.modelSha256, Pv9Profile.scriptSha256, scriptCborSha256, contextSha256)
      if !members.zip(pins).forall((bytes, pin) => digest(bytes) == pin) then
        Left("unregistered fixture member bytes")
      else
        val framed = members
          .flatMap(bytes => ByteBuffer.allocate(4).putInt(bytes.length).array() ++ bytes)
          .toArray
        if digest(framed) != combinationSha256 then Left("unregistered fixture combination")
        else
          try
            val model = new String(members(0), UTF_8)
            val source = new String(members(1), UTF_8)
            for
              profile <- Pv9Profile.create(model, source)
              program <- Program.parseUplc(source).left.map(_.toString)
              _ <- Either.cond(
                program.deBruijnedProgram.cborEncoded.sameElements(members(2)),
                (),
                "pinned script source/serialization mismatch"
              )
              data = Data.fromCbor(members(3))
              _ <- Either.cond(
                data.toCbor.sameElements(members(3)),
                (),
                "reference Data re-encoding mismatch"
              )
            yield new Pv9ReferenceFixture(profile.parameters, program, data)
          catch case NonFatal(e) => Left(s"invalid pinned fixture: ${e.getMessage}")

  // Arrays preserve map-pair order; integers never pass through JSON Double.
  private def orderedTree(data: Data): ujson.Value = data match
    case Data.Constr(tag, fields) =>
      ujson.Obj(
        "constructor" -> tag.toString,
        "fields" -> ujson.Arr.from(fields.toScalaList.map(orderedTree))
      )
    case Data.Map(pairs) =>
      ujson.Obj("map" -> ujson.Arr.from(pairs.toScalaList.map { (key, value) =>
        ujson.Arr(orderedTree(key), orderedTree(value))
      }))
    case Data.List(items) => ujson.Obj("list" -> ujson.Arr.from(items.toScalaList.map(orderedTree)))
    case Data.I(value)    => ujson.Obj("integer" -> value.toString)
    case Data.B(bytes)    => ujson.Obj("bytes" -> bytes.toHex)
