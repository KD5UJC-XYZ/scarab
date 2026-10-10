// SPDX-License-Identifier: Apache-2.0
package lab.vm

import lab.cbor.{Cbor, Bytes}
import lab.plutus.PlutusExecution
import lab.plutus.PlutusExecution.{Failure, Request, Success}
import java.nio.charset.StandardCharsets.UTF_8
import scalus.cardano.ledger.{Language, MajorProtocolVersion}
import scalus.uplc.*
import scalus.uplc.builtin.Data
import scalus.uplc.builtin.Data.toCbor
import scalus.uplc.eval.*
import scala.util.control.NonFatal

/** Synchronous bounded VM port, never phase-one validation or ledger admission. The application
  * owns scheduling/cancellation and must recheck request/state bindings.
  */
object Pv9SubmissionEvaluator extends PlutusExecution.Evaluator:
  private lazy val source: Either[Failure, String] =
    try
      val stream = getClass.getResourceAsStream("/plutus-pv9/spend.uplc")
      if stream == null then Left(Failure.InternalFailure("registered script resource unavailable"))
      else
        try
          val bytes = stream.readNBytes(16385)
          if bytes.length > 16384 then
            Left(Failure.InternalFailure("script resource exceeds bound"))
          else Right(new String(bytes, UTF_8))
        finally stream.close()
    catch case NonFatal(error) => Left(Failure.InternalFailure(error.getClass.getSimpleName))

  def evaluate(request: Request): Either[Failure, Success] =
    try
      if request == null then Left(Failure.MalformedInput("execution request required"))
      else if request.scriptSHA256.hex != Pv9ReferenceFixture.scriptCborSha256 ||
        request.modelSHA256.hex != Pv9Profile.modelSha256 ||
        request.declared != PlutusExecution.ProfileBudget
      then Left(Failure.Unsupported("registered PV9 script/model/budget required"))
      else
        for
          text <- source
          // create authenticates source/model before parsing and checks every admitted builtin.
          profile <- Pv9Profile
            .create(new String(request.modelText.toArray, UTF_8), text)
            .left
            .map(Failure.Unsupported.apply)
          program <- Program.parseUplc(text).left.map(e => Failure.InternalFailure(e.toString))
          _ <- Either.cond(
            program.deBruijnedProgram.cborEncoded.sameElements(request.scriptPayload.toArray),
            (),
            Failure.Unsupported("registered script serialization mismatch")
          )
          _ <- Cbor
            .decode(request.contextCbor, Cbor.Limits(65536, 32, 4096, 4096))
            .left
            .map(Failure.MalformedInput.apply)
          data <- decodeData(request.contextCbor)
          result <- run(request, program, profile, data)
        yield result
    catch case NonFatal(error) => Left(Failure.InternalFailure(error.getClass.getSimpleName))

  private def decodeData(original: Bytes): Either[Failure, Data] =
    try
      val data = Data.fromCbor(original.toArray)
      if data.toCbor.sameElements(original.toArray) then Right(data)
      else Left(Failure.MalformedInput("canonical context Data encoding required"))
    catch case NonFatal(_) => Left(Failure.MalformedInput("invalid context Data"))

  private def run(
      request: Request,
      program: Program,
      profile: Pv9Profile,
      data: Data
  ): Either[Failure, Success] =
    val vm = new PlutusVM(
      Language.PlutusV3,
      profile.parameters,
      BuiltinSemanticsVariant.C,
      Blake2bPlatform,
      MajorProtocolVersion.changPV
    )
    val limit = scalus.cardano.ledger
      .ExUnits(memory = request.declared.memory.toLong, steps = request.declared.steps.toLong)
    val spender = new RestrictingBudgetSpender(limit)
    try
      val applied = Term.Apply(program.term, Term.Const(Constant.Data(data)))
      vm.evaluateDeBruijnedTerm(DeBruijn.deBruijnTerm(applied), spender) match
        case Term.Const(Constant.Unit, _) =>
          val spent = spender.getSpentBudget
          val consumed = PlutusExecution.Budget(BigInt(spent.memory), BigInt(spent.steps))
          if consumed.memory < 0 || consumed.steps < 0 || consumed.memory > request.declared.memory ||
            consumed.steps > request.declared.steps
          then Left(Failure.InternalFailure("consumed budget outside declared bounds"))
          else
            Right(
              Success(
                request.requestDigest,
                request.scriptSHA256,
                request.contextSHA256,
                request.modelSHA256,
                request.declared,
                consumed
              )
            )
        case _ => Left(Failure.NonUnitReturn)
    catch
      case _: OutOfExBudgetError                 => Left(Failure.BudgetExhausted)
      case _: scalus.uplc.eval.EvaluationFailure => Left(Failure.ScriptFailure("explicit-error"))
      case error: BuiltinError if error.cause.isInstanceOf[UnsupportedBackend] =>
        Left(Failure.Unsupported("unsupported JVM builtin backend"))
      case _: UnsupportedBackend => Left(Failure.Unsupported("unsupported JVM builtin backend"))
      case _: BuiltinError       => Left(Failure.ScriptFailure("builtin-error"))
      case _: MachineError       => Left(Failure.ScriptFailure("machine-error"))
      case NonFatal(error)       => Left(Failure.InternalFailure(error.getClass.getSimpleName))
