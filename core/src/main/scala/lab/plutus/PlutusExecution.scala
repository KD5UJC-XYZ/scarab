// SPDX-License-Identifier: Apache-2.0
package lab.plutus

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import lab.cbor.Bytes

/** Dependency-neutral port. Requests and VM results are not ledger admission proofs. */
object PlutusExecution:
  val ProfileId = "isolated-conway-pv9-plutus-v3-spend-v1"
  final case class Budget(memory: BigInt, steps: BigInt)
  val ProfileBudget = Budget(100000, 30000000)

  enum Failure:
    case MalformedInput(detail: String)
    case Unsupported(detail: String)
    case ScriptFailure(detail: String)
    case NonUnitReturn
    case BudgetExhausted
    case InternalFailure(detail: String)

  /** bindingDigest binds the caller's complete checked state/environment/transaction context.
    * Script bytes are the ledger payload, excluding the surrounding witness byte-string encoding.
    */
  final class Request private[PlutusExecution] (
      val scriptPayload: Bytes,
      val contextCbor: Bytes,
      val modelText: Bytes,
      val declared: Budget,
      val bindingDigest: Bytes
  ):
    val profileId: String = ProfileId
    val scriptSHA256: Bytes = sha256(scriptPayload)
    val contextSHA256: Bytes = sha256(contextCbor)
    val modelSHA256: Bytes = sha256(modelText)
    val requestDigest: Bytes =
      val digest = MessageDigest.getInstance("SHA-256")
      Vector(
        Bytes.fromArray(ProfileId.getBytes(StandardCharsets.UTF_8)),
        bindingDigest,
        scriptPayload,
        contextCbor,
        modelText,
        Bytes.fromArray(declared.memory.toByteArray),
        Bytes.fromArray(declared.steps.toByteArray)
      ).foreach { field =>
        digest.update(ByteBuffer.allocate(4).putInt(field.size).array())
        digest.update(field.toArray)
      }
      Bytes.fromArray(digest.digest())

  def request(
      scriptPayload: Bytes,
      contextCbor: Bytes,
      modelText: Bytes,
      declared: Budget,
      bindingDigest: Bytes
  ): Either[Failure, Request] =
    def bounded(value: Bytes, maximum: Int): Boolean =
      value != null && value.value != null && value.size > 0 && value.size <= maximum
    if !bounded(scriptPayload, 369) || scriptPayload.size != 369 ||
      !bounded(contextCbor, 65536) || !bounded(modelText, 16384) ||
      !bounded(bindingDigest, 32) || bindingDigest.size != 32
    then Left(Failure.MalformedInput("execution request byte limits"))
    else if declared == null || declared != ProfileBudget then
      Left(Failure.Unsupported("execution request requires the fixed profile budget"))
    else Right(new Request(scriptPayload, contextCbor, modelText, declared, bindingDigest))

  /** A trusted evaluator reports this only after a Unit result. The admission composition gate must
    * verify every binding and budget before constructing its private checked receipt.
    */
  final case class Success(
      requestDigest: Bytes,
      scriptSHA256: Bytes,
      contextSHA256: Bytes,
      modelSHA256: Bytes,
      declared: Budget,
      consumed: Budget
  )

  trait Evaluator:
    def evaluate(request: Request): Either[Failure, Success]

  private def sha256(bytes: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(bytes.toArray))
