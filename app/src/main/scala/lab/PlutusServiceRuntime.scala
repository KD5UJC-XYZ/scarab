// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref, ExitCode}
import cats.syntax.all.*
import java.nio.file.{Files, Path, StandardOpenOption as Open}
import java.time.Instant
import lab.cbor.Bytes
import lab.network.{AsyncTcpTransport, TcpLimits, TxSubmission2Session}
import lab.submission.*
import lab.header.PraosNonceEvolution.Nonce
import ReferenceJson.{Json as J, field, string, uint}
import scala.concurrent.duration.*

/** Bounded volatile service. No expected transaction, endpoint oracle or state import. */
private[lab] object PlutusServiceRuntime:
  import PlutusResearchIO.{
    get,
    initial,
    awaitFile,
    read,
    sha,
    save,
    observation,
    record,
    num,
    text,
    bool,
    point
  }
  val MaxRelaySessions = 16
  private[lab] def relayBackoff(attempt: Int): FiniteDuration =
    require(attempt >= 1 && attempt <= MaxRelaySessions, "bounded relay attempt")
    (if attempt == 1 then 1 else if attempt == 2 then 2 else 4) .seconds
  val MaxPublicationBytes = 131072
  val MaxTerminalBytes = 1048576
  def pin(p: StatePin): J = record(
    "ownerId" -> text(p.ownerId.hex),
    "generation" -> num(p.generation),
    "point" -> point(p.point),
    "coherentStateId" -> text(p.coherentStateId.hex),
    "ledgerStateId" -> text(p.ledgerStateId.hex),
    "environmentId" -> text(p.environmentId.hex),
    "validationSlot" -> num(p.validationSlot),
    "profileId" -> text(p.profileId)
  )
  private def boundedSave(path: Path, value: J, maximum: Int): IO[Unit] =
    IO(require(EvidenceJson.encode(value).size <= maximum, "diagnostic JSON byte bound")) *> save(
      path,
      value
    )
  private def saveOriginal(path: Path, raw: Bytes): IO[Unit] = IO.blocking {
    require(raw.size > 0 && raw.size <= MaxTerminalBytes, "terminal original byte bound")
    val tmp = Files.createTempFile(path.getParent, ".plutus-service-", ".part")
    try
      Files.write(tmp, raw.toArray, Open.WRITE, Open.TRUNCATE_EXISTING)
      Files.createLink(path, tmp)
      ()
    finally Files.deleteIfExists(tmp)
  }
  private def nonce(n: Nonce): J = n match
    case Nonce.Neutral => J.Lit("null")
    case Nonce.Hash(b) => text(b.hex)
  private[lab] def terminal(
      state: CoherentSequence.State,
      p: StatePin,
      source: Bytes,
      manifest: String
  ): J =
    require(
      p.coherentStateId == state.id && p.point == state.certificates.state.tip && p.ledgerStateId == state.ledger.id,
      "terminal coherent pin"
    )
    val stake = state.stake.getOrElse(throw new IllegalArgumentException("terminal stake required"))
    val f = state.nonces.fields
    record(
      "schema" -> text("plutus-service-terminal-observation-v1"),
      "diagnosticOnly" -> bool(true),
      "restartSupported" -> bool(false),
      "fullLedgerValidated" -> bool(false),
      "pin" -> pin(p),
      "sourceJoinId" -> text(source.hex),
      "initialManifestSHA256" -> text(manifest),
      "outputMapFile" -> text("terminal-output-map.cbor"),
      "outputMapSHA256" -> text(sha(state.ledger.outputMap).hex),
      "fees" -> num(state.ledger.fees),
      "epoch" -> num(state.ledger.environment.epoch),
      "validationSlot" -> num(state.ledger.slot),
      "instantaneousStake" -> J.Arr(
        stake.instantaneous.toVector
          .sortBy(_._1.key)
          .map((c, n) =>
            record("script" -> bool(c.script), "hash" -> text(c.hash.hex), "coin" -> num(n))
          )
      ),
      "representedProtocol" -> record(
        "lastSlot" -> num(state.nonces.lastSlot),
        "counters" -> J.Arr(
          state.certificates.state.counters.toVector
            .sortBy(_._1.hex)
            .map((h, n) => record("issuer" -> text(h.hex), "counter" -> num(n)))
        ),
        "evolving" -> nonce(f.evolving),
        "candidate" -> nonce(f.candidate),
        "epoch" -> nonce(f.epoch),
        "previousEpoch" -> record(
          "present" -> bool(f.previousEpoch.nonEmpty),
          "value" -> f.previousEpoch.fold[J](J.Lit("null"))(nonce)
        ),
        "lab" -> nonce(f.lab),
        "lastEpochBlock" -> nonce(f.lastEpochBlock)
      )
    )

  private[lab] def deadline(now: Long, boundary: BigInt, requestedSeconds: Int): (Long, String) =
    require(requestedSeconds >= 1 && requestedSeconds <= 60, "bounded requested duration")
    val requested = BigInt(now) + requestedSeconds * 1000
    val effective = requested.min(boundary - 2000)
    require(effective > now && effective.isValidLong, "initial epoch service window missed")
    (effective.toLong, if effective < requested then "epochLimit" else "durationLimit")

  private def work(config: PlutusServiceCommand.Config): IO[Boolean] =
    val c = config.base
    val root = c.exchange
    for
      joined <- IO.blocking(initial(c.initial, c.manifest, AdmissionProfile.PlutusV3))
      context <- IO(get(SequenceInput.fromNativeDiagnostic(joined, joined.id)))
      genesis <- IO(ReferenceJson.parse(joined.ledger.globals.genesisOriginal))
      _ <- IO(
        require(
          joined.acquisition.networkMagic == BigInt(
            c.magic
          ) && joined.ledger.globals.geometry.epochLength == 1000 && field(
            genesis,
            "slotLength"
          ) == J.Num("0.1"),
          "checked local network and epoch geometry"
        )
      )
      boundary = BigInt(Instant.parse(string(field(genesis, "systemStart"))).toEpochMilli) + 100000
      _ <- IO.blocking(Files.createDirectory(root.resolve("originals")))
      opened <- Ref.of[IO, Int](0)
      closed <- Ref.of[IO, Int](0)
      relayCount <- Ref.of[IO, Int](0)
      relayEvents <- Ref.of[IO, Vector[J]](Vector.empty)
      pending <- Ref.of[IO, Vector[AdmissionStateChange]](Vector.empty)
      published <- Ref.of[IO, Vector[J]](Vector.empty)
      options <- IO(
        get(
          ReferenceCaptureCommand.options(
            List(
              c.port.toString,
              c.magic.toString,
              joined.point.slot.toString,
              joined.point.hash.hex
            )
          )
        )
      )
      tcp <- IO(get(TcpLimits.checked(read = 120.seconds)))
      connection = LiveValidatorCommand.counted(
        AsyncTcpTransport.resource[IO](options._1, tcp),
        opened,
        closed
      )
      peers = BoundedChainFollower.sessions[IO](connection, c.magic)
      fresh = CoherentSequence
        .createPlutusDiagnosticWithStake[IO](context, joined.ledger.epochComponents.stake)
        .map(get(_))
      closedResult <- PlutusResearchNode
        .resource(
          fresh,
          root.resolve("evaluation-receipts"),
          joined.id,
          get(Bytes.fromHex(c.manifest))
        )
        .use { node =>
          val listener = new AdmissionStateObserver[IO]:
            def changed(change: AdmissionStateChange): IO[Unit] =
              if change.kind != StateChangeKind.Published then IO.unit
              else
                pending
                  .modify(xs => if xs.size < 128 then (xs :+ change, true) else (xs, false))
                  .flatMap(ok =>
                    IO.raiseUnless(ok)(new IllegalStateException("publication queue bound"))
                  )
            def closed: IO[Unit] = IO.unit
          def event(value: J): IO[Unit] = relayEvents.update(xs => (xs :+ value).takeRight(256))
          def emit(e: TxSubmission2Session.Event): IO[Unit] =
            val (kind, ids) = e match
              case TxSubmission2Session.Event.Announced(xs)     => ("Announced", xs)
              case TxSubmission2Session.Event.BodiesWritten(xs) => ("BodiesWritten", xs)
              case TxSubmission2Session.Event.Acknowledged(xs)  => ("Acknowledged", xs)
              case TxSubmission2Session.Event.Finished          => ("Finished", Vector.empty)
            event(
              record("kind" -> text(kind), "ids" -> J.Arr(ids.take(16).map(id => text(id.hex))))
            )
          def relay: IO[Unit] = IO.defer {
            node.service.snapshot.flatMap { pool =>
              if pool.closed then IO.raiseError(new IllegalStateException("serviceUnavailable"))
              else if pool.eligible.isEmpty then IO.sleep(100.millis) *> relay
              else
                relayCount
                  .modify(n => if n < MaxRelaySessions then (n + 1, Some(n + 1)) else (n, None))
                  .flatMap {
                    case None => IO.raiseError(new IllegalStateException("relaySessionLimit"))
                    case Some(n) =>
                      (event(
                        record("kind" -> text("SessionStarted"), "attempt" -> num(n))
                      ) *> connection
                        .use(t =>
                          TxSubmission2Session
                            .resource[IO](t)
                            .use(
                              _.run(
                                lab.network.Handshake.Data(c.magic),
                                node.service.relaySource,
                                emit
                              )
                            )
                        )
                        .timeout(10.seconds)).attempt.flatMap {
                        case Left(e) =>
                          event(
                            record(
                              "kind" -> text("SessionFailed"),
                              "errorType" -> text(e.getClass.getSimpleName)
                            )
                          ) *> IO.sleep(relayBackoff(n)) *> relay
                        case Right(_) => IO.sleep(relayBackoff(n)) *> relay
                      }
                  }
            }
          }
          def writePublication(change: AdmissionStateChange): IO[Unit] = IO.uncancelable { _ =>
            for
              rows <- published.get
              _ <- IO(require(rows.size < config.maxBlocks, "publication file bound"))
              pool <- node.service.snapshot.attempt
              value = record(
                "schema" -> text("plutus-service-publication-v1"),
                "index" -> num(rows.size),
                "pin" -> pin(change.view.pin),
                "sourceJoinId" -> text(joined.id.hex),
                "initialManifestSHA256" -> text(c.manifest),
                "profileId" -> text(AdmissionProfile.PlutusV3.id),
                "included" -> J.Arr(
                  change.included.map(x =>
                    record(
                      "transactionId" -> text(x.transactionId.hex),
                      "bodySHA256" -> x.originalBody.fold[J](J.Lit("null"))(b => text(sha(b).hex)),
                      "witnessesSHA256" -> x.originalWitnesses.fold[J](J.Lit("null"))(b =>
                        text(sha(b).hex)
                      )
                    )
                  )
                ),
                "poolCount" -> pool.toOption.fold[J](J.Lit("null"))(s => num(s.size)),
                "diagnosticOnly" -> bool(true),
                "fullLedgerValidated" -> bool(false)
              )
              file = f"publication-${rows.size}%04d.json"
              _ <- boundedSave(root.resolve(file), value, MaxPublicationBytes)
              _ <- published.update(
                _ :+ record(
                  "file" -> text(file),
                  "sha256" -> text(sha(EvidenceJson.encode(value)).hex),
                  "pin" -> pin(change.view.pin)
                )
              )
            yield ()
          }
          def observed(o: NetworkPublicationObservation): IO[Unit] =
            IO.realTime
              .map(_.toNanos)
              .flatMap(t => observation(root, o, t)) *> (if o.applied.isEmpty then IO.unit
                                                         else
                                                           IO.uncancelable { _ =>
                                                             pending
                                                               .modify { xs =>
                                                                 val found = xs.find(
                                                                   _.view.pin.point == o.announced
                                                                 )
                                                                 (
                                                                   xs.filterNot(
                                                                     _.view.pin.point == o.announced
                                                                   ),
                                                                   found
                                                                 )
                                                               }
                                                               .flatMap(v =>
                                                                 IO.fromOption(v)(
                                                                   new IllegalStateException(
                                                                     "publication observation has no owner change"
                                                                   )
                                                                 )
                                                               )
                                                               .flatMap(writePublication)
                                                           })
          node
            .http(Some(listener))
            .use { api =>
              for
                now <- IO.realTime.map(_.toMillis)
                window <- IO(deadline(now, boundary, config.durationSeconds))
                _ <- boundedSave(
                  root.resolve("bootstrap-ready.json"),
                  record(
                    "schema" -> text("plutus-service-ready-v1"),
                    "apiPort" -> num(api.port),
                    "profileId" -> text(AdmissionProfile.PlutusV3.id),
                    "sourceJoinId" -> text(joined.id.hex),
                    "initialManifestSHA256" -> text(c.manifest),
                    "initialPoint" -> point(joined.point),
                    "networkMagic" -> num(c.magic),
                    "initialEpoch" -> num(0),
                    "requestedDeadlineUnixMillis" -> num(
                      BigInt(now) + config.durationSeconds * 1000
                    ),
                    "deadlineUnixMillis" -> num(window._1),
                    "limits" -> record(
                      "durationSeconds" -> num(config.durationSeconds),
                      "maxBlocks" -> num(config.maxBlocks),
                      "maxEvents" -> num(4096),
                      "maxEvaluationReceipts" -> num(128),
                      "maxRelaySessions" -> num(MaxRelaySessions)
                    )
                  ),
                  16384
                )
                waitStarted <- IO.realTime.map(_.toMillis)
                _ <- IO(
                  require(waitStarted < window._1, "service startup exhausted effective deadline")
                )
                readyRaw <- awaitFile(root.resolve("peer-ready.json"), 16384).timeoutTo(
                  (window._1 - waitStarted).millis,
                  IO.raiseError(new IllegalStateException("startupDeadline"))
                )
                ready = ReferenceJson.parse(readyRaw)
                _ <- IO(
                  require(
                    string(field(ready, "schema")) == "native-live-peer-ready-v1" && uint(
                      field(ready, "generatedPort")
                    ) == c.port && uint(field(ready, "networkMagic")) == c.magic && string(
                      field(ready, "referenceContainerId")
                    ).matches("[0-9a-f]{64}"),
                    "owned loopback peer readiness"
                  )
                )
                started <- IO.realTime.map(_.toMillis)
                _ <- IO(
                  require(started < window._1, "service startup exhausted effective deadline")
                )
                race <- IO.race(
                  relay.attempt,
                  PlutusSameEpochFollow.runUntil(
                    node.owner,
                    peers,
                    joined.point,
                    1000,
                    EphemeralStreaming.Limits(
                      maxEvents = 4096,
                      maxBlocks = config.maxBlocks,
                      duration = (window._1 - started).millis
                    )
                  )(published.get.map(_.size >= config.maxBlocks))(observed)
                )
                reason = race match
                  case Left(Left(e)) =>
                    if Option(e.getMessage).contains("relaySessionLimit") then "relaySessionLimit"
                    else if Option(e.getMessage).contains("serviceUnavailable") then
                      "serviceUnavailable"
                    else "relayFailure"
                  case Left(Right(_)) => "relayUnexpectedCompletion"
                  case Right(outcome) =>
                    outcome.reason match
                      case "deadline" | "Deadline"     => window._2
                      case "blockLimit" | "BlockLimit" => "blockLimit"
                      case "epochBoundaryRefused"      => "epochBoundaryRefused"
                      case _                           => "followerFailure"
              yield (now, window, started, reason)
            }
            .flatMap { case (now, window, started, reason) =>
              // HTTP and all owned request fibers are finalized before capturing receipts/state.
              // The owner remains alive for the coherent diagnostic export, then closes below.
              for
                _ <- IO.uncancelable(_ =>
                  pending.getAndSet(Vector.empty).flatMap(_.traverse_(writePublication))
                )
                view <- node.owner.current
                frozen <- node.owner.withCurrent(view.pin)(node.owner.snapshot)
                snapshot <- IO.fromEither(
                  frozen.leftMap(_ => new IllegalStateException("terminal state changed"))
                )
                terminalValue <- IO(terminal(snapshot.state, view.pin, joined.id, c.manifest))
                _ <- saveOriginal(
                  root.resolve("terminal-output-map.cbor"),
                  snapshot.state.ledger.outputMap
                )
                _ <- boundedSave(
                  root.resolve("terminal-observation.json"),
                  terminalValue,
                  MaxTerminalBytes
                )
                rows <- published.get
                events <- relayEvents.get
                attempts <- relayCount.get
                ended <- IO.realTime.map(_.toMillis)
              yield (
                record(
                  "schema" -> text("plutus-service-result-v1"),
                  "status" -> text(
                    if Set("durationLimit", "epochLimit", "blockLimit").contains(reason) then
                      "stopped"
                    else "failed"
                  ),
                  "stopReason" -> text(reason),
                  "transactionSuccessClaimed" -> bool(false),
                  "fullLedgerValidated" -> bool(false),
                  "volatile" -> bool(true),
                  "restartSupported" -> bool(false),
                  "profileId" -> text(AdmissionProfile.PlutusV3.id),
                  "sourceJoinId" -> text(joined.id.hex),
                  "initialManifestSHA256" -> text(c.manifest),
                  "initialPoint" -> point(joined.point),
                  "finalPin" -> pin(view.pin),
                  "initialEpoch" -> num(0),
                  "requestedDeadlineUnixMillis" -> num(BigInt(now) + config.durationSeconds * 1000),
                  "effectiveDeadlineUnixMillis" -> num(window._1),
                  "startedUnixMillis" -> num(started),
                  "endedUnixMillis" -> num(ended),
                  "publications" -> J.Arr(rows),
                  "terminalObservationFile" -> text("terminal-observation.json"),
                  "terminalObservationSHA256" -> text(sha(EvidenceJson.encode(terminalValue)).hex),
                  "relaySessions" -> num(attempts),
                  "relayEvents" -> J.Arr(events)
                ),
                node.evidence.records
              )
            }
        }
        .onCancel(for
          opens <- opened.get
          closes <- closed.get
          _ <- boundedSave(
            root.resolve("cancelled.json"),
            record(
              "schema" -> text("plutus-service-cancelled-v1"),
              "resourcesFinalized" -> bool(opens == closes),
              "transportOpens" -> num(opens),
              "transportCloses" -> num(closes)
            ),
            16384
          ).attempt.void
        yield ())
      // The service workers, owner and evidence sink have all finalized. records is a read-only Ref snapshot.
      receipts <- closedResult._2
      result = closedResult._1
      opens <- opened.get
      closes <- closed.get
      _ <- IO(require(opens == closes, "owned transport finalization mismatch"))
      complete = result match
        case J.Obj(fields) =>
          J.Obj(
            fields ++ Map(
              "evaluationReceipts" -> J.Arr(
                receipts.map(r =>
                  record(
                    "file" -> text("evaluation-receipts/" + r.filename),
                    "sha256" -> text(r.sha256.hex)
                  )
                )
              ),
              "resourcesFinalized" -> bool(true),
              "transportOpens" -> num(opens),
              "transportCloses" -> num(closes)
            )
          )
        case _ => throw new IllegalStateException("result shape")
      _ <- boundedSave(root.resolve("result.json"), complete, MaxTerminalBytes)
    yield string(field(result, "status")) == "stopped"

  def run(config: PlutusServiceCommand.Config): IO[ExitCode] = IO
    .defer(work(config))
    .timeout(220.seconds)
    .map(ok => if ok then ExitCode.Success else ExitCode(2))
    .handleErrorWith { e =>
      val value = record(
        "schema" -> text("plutus-service-failure-v1"),
        "status" -> text("failed"),
        "errorType" -> text(e.getClass.getSimpleName),
        "message" -> text(Option(e.getMessage).getOrElse("failure").take(512)),
        "fullLedgerValidated" -> bool(false)
      )
      boundedSave(config.base.exchange.resolve("failure.json"), value, 16384).attempt.void *> IO
        .println("PLUTUS_SERVICE_FAILED: " + e.getClass.getSimpleName)
        .as(ExitCode(2))
    }
