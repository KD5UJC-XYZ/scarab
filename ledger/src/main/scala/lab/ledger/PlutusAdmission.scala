// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.Bytes
import lab.plutus.PlutusExecution as E
import lab.submission.SignedTransaction
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.util.control.NonFatal

/** Sole phase-one/context/integrity/VM composition gate. VM reports never grant admission alone. */
object PlutusAdmission:
  enum Failure:
    case Identity(error: SignedTransaction.Error)
    case PhaseOne(error: PlutusSpending.Failure)
    case Execution(error: E.Failure)
    case BindingMismatch
    case InternalFailure(kind: String)

  final class Checked private[PlutusAdmission] (
      val prepared: PlutusSpending.Prepared,
      val execution: E.Success,
      val requestDigest: Bytes
  ):
    val transaction = prepared.transaction
    val spent = prepared.spent
    val collateral = prepared.collateral
    val dependencies = spent ++ collateral
    val fee = prepared.fee
    val minimumOutput = prepared.minimumOutput
    val ledgerStateId = prepared.ledgerStateId
    val environmentId = prepared.environmentId
    val validationSlot = prepared.validationSlot
    val profileId = E.ProfileId
    val fullLedgerValidated = false

  def check(
      view: ClusterTransition.State,
      original: Bytes,
      environment: PlutusEnvironment,
      evaluator: E.Evaluator,
      validationSlot: BigInt
  ): Either[Failure, Checked] =
    SignedTransaction.checked(original).left.map(Failure.Identity.apply).flatMap { identity =>
      checkIdentity(view, identity, environment, evaluator, validationSlot)
    }

  private def checkIdentity(
      view: ClusterTransition.State,
      identity: SignedTransaction,
      environment: PlutusEnvironment,
      evaluator: E.Evaluator,
      validationSlot: BigInt
  ): Either[Failure, Checked] =
    try
      for
        prepared <- PlutusSpending
          .prepare(view, identity, environment, validationSlot)
          .left
          .map(Failure.PhaseOne.apply)
        context <- PlutusContext
          .derive(prepared.contextInput, environment.networkId)
          .left
          .map(Failure.Execution.apply)
        _ <- PlutusIntegrity
          .check(
            context.originalRedeemers,
            environment.parameters.modelText,
            context.suppliedIntegrity
          )
          .left
          .map(Failure.Execution.apply)
        binding = Bytes.fromArray(
          MessageDigest
            .getInstance("SHA-256")
            .digest(
              Vector(
                E.ProfileId,
                "admission-request-v1",
                view.id.hex,
                environment.id.hex,
                identity.envelopeSHA256.hex,
                validationSlot.toString
              ).mkString("\n").getBytes(UTF_8)
            )
        )
        request <- E
          .request(
            context.scriptPayload,
            context.contextCbor,
            environment.parameters.modelText,
            E.ProfileBudget,
            binding
          )
          .left
          .map(Failure.Execution.apply)
        result <- Option(evaluator)
          .toRight(Failure.InternalFailure("trusted evaluator required"))
          .flatMap(_.evaluate(request).left.map(Failure.Execution.apply))
        _ <- Either.cond(
          result != null && result.requestDigest == request.requestDigest &&
            result.scriptSHA256 == request.scriptSHA256 && result.contextSHA256 == request.contextSHA256 &&
            result.modelSHA256 == request.modelSHA256 && result.declared == request.declared &&
            result.consumed != null && result.consumed.memory != null && result.consumed.steps != null &&
            result.consumed.memory >= 0 && result.consumed.steps >= 0 &&
            result.consumed.memory <= request.declared.memory && result.consumed.steps <= request.declared.steps,
          (),
          Failure.BindingMismatch
        )
      yield new Checked(prepared, result, request.requestDigest)
    catch case NonFatal(e) => Left(Failure.InternalFailure(e.getClass.getName))

  def prepare[P](
      pin: P,
      view: ClusterTransition.State,
      original: Bytes,
      evaluator: Option[E.Evaluator]
  ): Either[ScopedAdmission.Failure, ScopedAdmission.Candidate[P]] =
    for
      identity <- SignedTransaction
        .checked(original)
        .left
        .map(ScopedAdmission.Failure.Identity.apply)
      _ <- Either.cond(
        view != null,
        (),
        ScopedAdmission.Failure.Unsupported("confirmed ledger view required")
      )
      environment <- view.environment.plutus.toRight(
        ScopedAdmission.Failure.Unsupported("Plutus environment required")
      )
      vm <- evaluator.toRight(
        ScopedAdmission.Failure.Unsupported("trusted Plutus evaluator required")
      )
      checked <- checkIdentity(view, identity, environment, vm, view.slot).left
        .map(ScopedAdmission.Failure.Plutus.apply)
      candidate <- ScopedAdmission.bindPlutus(pin, view, checked)
    yield candidate
