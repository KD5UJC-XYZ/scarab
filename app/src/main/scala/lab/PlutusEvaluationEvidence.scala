// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import java.nio.file.{Files, Path, StandardOpenOption as Open}
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import lab.cbor.Bytes
import lab.ledger.ScopedAdmission
import lab.plutus.PlutusExecution
import lab.submission.StatePin

/** Historical observations only: never an input to admission, relay, or ledger validation. */
private[lab] object PlutusEvaluationEvidence:
  val Schema = "plutus-evaluation-evidence-v1"
  val MaxRecords = 128
  val MaxBytes = 16384

  final class Observation private[PlutusEvaluationEvidence] (
      val transactionId: Bytes,
      val envelopeSHA256: Bytes,
      val bodySHA256: Bytes,
      val witnessesSHA256: Bytes,
      val pin: StatePin,
      val execution: PlutusExecution.Success,
      val event: EvaluationEvent
  ):
    val fullLedgerValidated = false
    val inclusionClaimed = false
    val newlyAdmitted = EvaluationEvent.newlyAdmitted(event)
    def phase: String = EvaluationEvent.wire(event)._1
    def outcome: String = EvaluationEvent.wire(event)._2

  trait Observer[F[_]]:
    def observe(value: Observation): F[Unit]

  /** The service supplies only its already checked candidate; this does not invoke any evaluator.
    * Already-present observations describe the duplicate request's evaluation, not the original
    * accepted event. Failed checks and owner-stale requests have no observation.
    */
  def checked(
      candidate: ScopedAdmission.Candidate[StatePin],
      event: EvaluationEvent
  ): Option[Observation] =
    require(candidate != null && candidate.pin != null)
    require(event != null)
    candidate.plutusAdmission.map { checked =>
      require(
        candidate.pin.ledgerStateId == checked.ledgerStateId &&
          candidate.pin.environmentId == checked.environmentId &&
          candidate.pin.validationSlot == checked.validationSlot && candidate.pin.profileId == checked.profileId
      )
      require(candidate.transaction eq checked.transaction)
      require(checked.requestDigest == checked.execution.requestDigest)
      new Observation(
        candidate.transaction.transactionId,
        candidate.transaction.envelopeSHA256,
        sha(candidate.transaction.originalBody),
        sha(candidate.transaction.originalWitnesses),
        candidate.pin,
        checked.execution,
        event
      )
    }

  final case class Stored(filename: String, sha256: Bytes, observation: Observation)
  final class Store[F[_]] private[PlutusEvaluationEvidence] (
      directory: Path,
      sourceJoinId: Bytes,
      manifest: Bytes,
      state: Ref[F, Vector[Stored]],
      gate: Semaphore[F],
      closed: Ref[F, Boolean]
  )(using F: Async[F])
      extends Observer[F]:
    def records: F[Vector[Stored]] = state.get
    def observe(value: Observation): F[Unit] = gate.permit.use { _ =>
      F.uncancelable { _ =>
        for
          done <- closed.get
          _ <- F.raiseWhen(done)(new IllegalStateException("evaluation store closed"))
          previous <- state.get
          _ <- F.raiseWhen(previous.size >= MaxRecords)(
            new IllegalStateException("evaluation record bound")
          )
          filename = f"plutus-evaluation-${previous.size}%04d.json"
          bytes <- F.delay(encode(value, sourceJoinId, manifest, previous.size))
          // Cancellation waits for this bounded write to finish: no detached writer survives the
          // owner fence or resource finalizer. Filesystem latency is not a hard real-time guarantee.
          _ <- F.blocking {
            val temporary = directory.resolve(filename + ".part")
            val target = directory.resolve(filename)
            val channel = FileChannel.open(temporary, Open.CREATE_NEW, Open.WRITE)
            try
              val buffer = ByteBuffer.wrap(bytes.toArray)
              while buffer.hasRemaining do channel.write(buffer)
              channel.force(true)
            finally channel.close()
            Files.createLink(target, temporary)
            Files.delete(temporary)
          }
          _ <- state.set(previous :+ Stored(filename, sha(bytes), value))
        yield ()
      }
    }
    private[PlutusEvaluationEvidence] def close: F[Unit] = gate.permit.use(_ => closed.set(true))

  def fileObserver[F[_]: Async](
      directory: Path,
      sourceJoinId: Bytes,
      initialManifestSHA256: Bytes
  ): Resource[F, Store[F]] =
    val F = Async[F]
    for
      _ <- Resource.eval(F.delay {
        require(
          directory != null && sourceJoinId != null && sourceJoinId.size == 32 &&
            initialManifestSHA256 != null && initialManifestSHA256.size == 32,
          "source join and manifest hashes required"
        )
      })
      _ <- Resource.eval(F.blocking(Files.createDirectory(directory)))
      state <- Resource.eval(Ref.of[F, Vector[Stored]](Vector.empty))
      gate <- Resource.eval(Semaphore[F](1))
      closed <- Resource.eval(Ref.of[F, Boolean](false))
      store <- Resource.make(
        F.pure(new Store(directory, sourceJoinId, initialManifestSHA256, state, gate, closed))
      )(_.close)
    yield store

  private def sha(raw: Bytes): Bytes = ClusterHeaderObservation.sha256(raw)
  private def quoted(value: String): String =
    require(value.matches("[a-zA-Z0-9._-]+"), "bounded evidence token")
    "\"" + value + "\""
  private def obj(fields: (String, String)*): String =
    fields.map((name, value) => quoted(name) + ":" + value).mkString("{", ",", "}")
  private def budget(value: PlutusExecution.Budget): String =
    obj("memory" -> value.memory.toString, "steps" -> value.steps.toString)

  private[lab] def encode(
      value: Observation,
      sourceJoinId: Bytes,
      manifest: Bytes,
      index: Int
  ): Bytes =
    require(value != null && index >= 0 && index < MaxRecords)
    val p = value.pin
    val e = value.execution
    val point = obj(
      "hash" -> quoted(p.point.hash.hex),
      "slot" -> p.point.slot.toString,
      "blockNo" -> p.point.blockNo.toString
    )
    val pin = obj(
      "ownerId" -> quoted(p.ownerId.hex),
      "generation" -> p.generation.toString,
      "point" -> point,
      "coherentStateId" -> quoted(p.coherentStateId.hex),
      "ledgerStateId" -> quoted(p.ledgerStateId.hex),
      "environmentId" -> quoted(p.environmentId.hex),
      "validationSlot" -> p.validationSlot.toString,
      "profileId" -> quoted(p.profileId)
    )
    val raw = Bytes.fromArray(
      (obj(
        "schema" -> quoted(Schema),
        "eventIndex" -> index.toString,
        "sourceJoinId" -> quoted(sourceJoinId.hex),
        "initialManifestSHA256" -> quoted(manifest.hex),
        "phase" -> quoted(value.phase),
        "outcome" -> quoted(value.outcome),
        "newlyAdmitted" -> value.newlyAdmitted.toString,
        "transactionId" -> quoted(value.transactionId.hex),
        "envelopeSHA256" -> quoted(value.envelopeSHA256.hex),
        "bodySHA256" -> quoted(value.bodySHA256.hex),
        "witnessesSHA256" -> quoted(value.witnessesSHA256.hex),
        "statePin" -> pin,
        "requestDigest" -> quoted(e.requestDigest.hex),
        "contextSHA256" -> quoted(e.contextSHA256.hex),
        "scriptSHA256" -> quoted(e.scriptSHA256.hex),
        "modelSHA256" -> quoted(e.modelSHA256.hex),
        "evaluator" -> quoted("Scalus"),
        "evaluatorVersion" -> quoted("1.3.0"),
        "language" -> quoted("PlutusV3"),
        "semantics" -> quoted("C"),
        "protocolMajor" -> "9",
        "declared" -> budget(e.declared),
        "consumed" -> budget(e.consumed),
        "fullLedgerValidated" -> "false",
        "inclusionClaimed" -> "false",
        "currentEligibilityClaimed" -> "false"
      ) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )
    require(raw.size <= MaxBytes)
    raw
