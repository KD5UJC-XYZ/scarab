// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, IOApp, ExitCode, Ref}
import cats.syntax.all.*
import cats.effect.syntax.all.*
import java.nio.file.Path
import java.time.Instant
import lab.cbor.Bytes
import lab.network.{AsyncTcpTransport, TcpLimits, TxSubmission2Session}
import lab.submission.*
import ReferenceJson.{Json as J, field, string, uint}
import scala.concurrent.duration.*

/** Bounded research diagnostic composition. Signed originals enter solely through loopback HTTP. */
private[lab] object PlutusResearchRuntime:
  import PlutusResearchIO.{
    get,
    initial,
    endpoint,
    save,
    awaitFile,
    observation,
    record,
    num,
    text,
    bool,
    point,
    sha,
    read,
    hash
  }

  private final case class Bootstrap(
      joined: NativeLedgerV2.Checked,
      descriptor: J,
      expectedId: Bytes,
      submitted: SignedTransaction,
      spentInput: lab.ledger.TxIn,
      collateralInput: lab.ledger.TxIn,
      submittedFee: BigInt,
      fresh: IO[CoherentSequence.Runtime[IO]],
      options: (lab.network.NumericPeer, Long, lab.network.ChainSync.Point),
      boundaryMillis: BigInt
  )
  private final case class Followed(
      outcome: PlutusSameEpochFollow.Outcome,
      started: Long,
      ended: Long
  )
  private final case class VerifiedInclusion(
      followed: Followed,
      inclusion: AdmissionStateChange,
      matched: IncludedTransaction,
      clientRaw: Bytes,
      events: Vector[J],
      state: CoherentSequence.State,
      terminal: lab.header.PraosCertificateState.Point,
      opens: Int,
      closes: Int
  )

  private def prepare(
      args: List[String],
      root: Path,
      admissionProfile: AdmissionProfile
  ): IO[Bootstrap] =
    for
      joined <- IO.blocking(initial(Path.of(args(0)), args(1), admissionProfile))
      descriptor <- IO.blocking(
        ReferenceJson.parse(read(root.resolve("submission/descriptor.json"), 16384))
      )
      expectedId <- IO(hash(field(descriptor, "transactionId")))
      submittedRaw <- IO.blocking(read(root.resolve("submission/transaction.cbor"), 65536))
      submitted <- IO(get(SignedTransaction.checked(submittedRaw)))
      spentInput <- IO(get(PlutusEndpointLedger.input(string(field(descriptor, "spentInput")))))
      collateralInput <- IO(
        get(PlutusEndpointLedger.input(string(field(descriptor, "collateralInput"))))
      )
      submittedFee <- IO(uint(field(descriptor, "fee")))
      _ <- IO(
        require(
          string(field(descriptor, "profileId")) == admissionProfile.id &&
            submitted.transactionId == expectedId &&
            hash(field(descriptor, "envelopeSHA256")) == submitted.envelopeSHA256 &&
            hash(field(descriptor, "bodySHA256")) == sha(submitted.originalBody) &&
            hash(field(descriptor, "witnessesSHA256")) == sha(submitted.originalWitnesses),
          "descriptor must bind the exact external submitted original"
        )
      )
      initialOutputs <- IO(
        get(lab.ledger.PlutusOutput.snapshot(joined.ledger.epochComponents.stake.utxo, 0)).outputs
      )
      _ <- IO(
        require(
          initialOutputs.collect { case (ref, out) if out.datum.nonEmpty => ref }.toSet == Set(
            spentInput
          ) &&
            initialOutputs.get(collateralInput).exists(out => out.kind == 6 && out.datum.isEmpty) &&
            spentInput != collateralInput,
          "actual funded script input and distinct key collateral required"
        )
      )
      context <- IO(get(SequenceInput.fromNativeDiagnostic(joined, joined.id)))
      epoch = joined.ledger.epochComponents
      fresh = CoherentSequence
        .createPlutusDiagnosticWithStake[IO](context, epoch.stake)
        .map(get(_))
      options <- IO(
        get(
          ReferenceCaptureCommand.options(
            List(args(2), args(3), joined.point.slot.toString, joined.point.hash.hex)
          )
        )
      )
      _ <- IO(require(BigInt(options._2) == joined.acquisition.networkMagic, "peer network magic"))
      genesis = ReferenceJson.parse(joined.ledger.globals.genesisOriginal)
      genesisMillis = Instant.parse(string(field(genesis, "systemStart"))).toEpochMilli
      boundaryMillis = BigInt(genesisMillis) + 100000
      _ <- IO(
        require(
          joined.ledger.globals.geometry.epochLength == 1000 &&
            field(genesis, "slotLength") == J.Num("0.1"),
          "proven local timing profile"
        )
      )
    yield Bootstrap(
      joined,
      descriptor,
      expectedId,
      submitted,
      spentInput,
      collateralInput,
      submittedFee,
      fresh,
      options,
      boundaryMillis
    )

  private[lab] def work(args: List[String]): IO[Unit] =
    require(
      args.size == 5,
      "INITIAL_DIRECTORY INITIAL_MANIFEST_SHA256 PORT MAGIC EXCHANGE_DIRECTORY"
    )
    val root = Path.of(args(4))
    val admissionProfile = AdmissionProfile.PlutusV3
    prepare(args, root, admissionProfile).flatMap { bootstrap =>
      import bootstrap.*
      for
        included <- Ref.of[IO, Option[AdmissionStateChange]](None)
        completed <- Ref.of[IO, Option[J]](None)
        publications <- Ref
          .of[IO, Vector[(lab.header.PraosCertificateState.Point, Long)]](Vector.empty)
        relayEvents <- Ref.of[IO, Vector[J]](Vector.empty)
        relayAttempts <- Ref.of[IO, Int](0)
        opened <- Ref.of[IO, Int](0)
        closed <- Ref.of[IO, Int](0)
        tcp <- IO(get(TcpLimits.checked(read = 120.seconds)))
        connection = LiveValidatorCommand.counted(
          AsyncTcpTransport.resource[IO](options._1, tcp),
          opened,
          closed
        )
        peers = BoundedChainFollower.sessions[IO](connection, options._2)
        _ <- IO.blocking(java.nio.file.Files.createDirectory(root.resolve("originals")))
        _ <- PlutusResearchNode
          .resource(
            fresh,
            root.resolve("evaluation-receipts"),
            joined.id,
            get(Bytes.fromHex(args(1)))
          )
          .use { session =>
            val owner = session.owner
            val service = session.service
            val observer = new AdmissionStateObserver[IO]:
              def changed(change: AdmissionStateChange): IO[Unit] =
                if change.included.exists(_.transactionId == expectedId) then
                  included.set(Some(change))
                else IO.unit
              def closed: IO[Unit] = IO.unit
            def relayEvidence(value: J): IO[Unit] =
              relayEvents.modify(xs => ((xs :+ value).takeRight(128), xs.size < 128)).flatMap {
                keep =>
                  if !keep then IO.unit
                  else
                    IO.blocking {
                      java.nio.file.Files.write(
                        root.resolve("relay-events.jsonl"),
                        EvidenceJson.encode(value).toArray,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND
                      )
                      ()
                    }
              }
            def emit(event: TxSubmission2Session.Event): IO[Unit] =
              val (kind, ids) = event match
                case TxSubmission2Session.Event.Announced(xs)     => ("Announced", xs)
                case TxSubmission2Session.Event.BodiesWritten(xs) => ("BodiesWritten", xs)
                case TxSubmission2Session.Event.Acknowledged(xs)  => ("Acknowledged", xs)
                case TxSubmission2Session.Event.Finished          => ("Finished", Vector.empty)
              IO.monotonic.flatMap(now =>
                relayEvidence(
                  record(
                    "kind" -> text(kind),
                    "ids" -> J.Arr(ids.map(x => text(x.hex))),
                    "monotonicNanos" -> num(now.toNanos)
                  )
                )
              )
            def relay: IO[Unit] = IO.defer {
              included.get.flatMap {
                case Some(_) => IO.unit
                case None =>
                  service.snapshot.flatMap { pool =>
                    if pool.eligible.isEmpty then IO.sleep(100.millis) *> relay
                    else
                      relayAttempts.getAndUpdate(_ + 1).flatMap { n =>
                        if n >= 4 then
                          IO.raiseError(
                            new IllegalStateException("bounded relay attempts exhausted")
                          )
                        else
                          (relayEvidence(
                            record("kind" -> text("SessionStarted"), "attempt" -> num(n + 1))
                          ) *> connection
                            .use { transport =>
                              TxSubmission2Session
                                .resource[IO](transport)
                                .use(
                                  _.run(
                                    lab.network.Handshake.Data(options._2),
                                    service.relaySource,
                                    emit
                                  )
                                )
                            }).attempt
                            .flatMap { result =>
                              result.left.toOption.traverse_(e =>
                                relayEvidence(
                                  record(
                                    "kind" -> text("SessionFailed"),
                                    "errorType" -> text(e.getClass.getName),
                                    "message" -> text(
                                      Option(e.getMessage).getOrElse(e.getClass.getName).take(512)
                                    )
                                  )
                                )
                              ) *>
                                IO.sleep(250.millis) *> relay
                            }
                      }
                  }
              }
            }
            // Phase boundaries shorten the compiler's nested flatMap tree without changing
            // the enclosing HTTP/node/relay resource lifetimes or any effect ordering.
            def follow(api: AdaHttp.Bound): IO[Followed] =
              for
                _ <- save(
                  root.resolve("bootstrap-ready.json"),
                  record(
                    "schema" -> text("native-live-bootstrap-ready-v1"),
                    "point" -> point(joined.point),
                    "sourceJoinId" -> text(joined.id.hex),
                    "boundaryUnixMillis" -> num(boundaryMillis),
                    "networkMagic" -> num(joined.acquisition.networkMagic),
                    "apiPort" -> num(api.port),
                    "profileId" -> text(admissionProfile.id),
                    "plutusDatumMemPackMatched" -> bool(true),
                    "fundedInput" -> text(string(field(descriptor, "spentInput"))),
                    "collateralInput" -> text(string(field(descriptor, "collateralInput")))
                  )
                )
                peerRaw <- awaitFile(root.resolve("peer-ready.json"), 16384)
                peer = ReferenceJson.parse(peerRaw)
                _ <- IO(
                  require(
                    string(field(peer, "schema")) == "native-live-peer-ready-v1" &&
                      uint(field(peer, "generatedPort")) == BigInt(args(2)) &&
                      uint(field(peer, "networkMagic")) == joined.acquisition.networkMagic &&
                      string(field(peer, "referenceContainerId")).matches("[0-9a-f]{64}"),
                    "owned peer readiness"
                  )
                )
                started <- IO.realTime.map(_.toMillis)
                _ <- IO(require(BigInt(started) < boundaryMillis, "preboundary start"))
                remainingMillis = boundaryMillis - BigInt(started) - 2000
                _ <- IO(
                  require(
                    remainingMillis > 0,
                    "same-epoch deadline requires positive remaining time"
                  )
                )
                outcome <- relay.background.use { _ =>
                  PlutusSameEpochFollow
                    .runWhen(
                      owner,
                      peers,
                      joined.point,
                      BigInt(1000),
                      EphemeralStreaming
                        .Limits(duration = remainingMillis.min(BigInt(60000)).toLong.millis)
                    )(included.get.map(_.nonEmpty))(o =>
                      IO.realTime
                        .map(_.toNanos)
                        .flatMap(t =>
                          observation(root, o, t) *>
                            (if o.applied.nonEmpty then publications.update(_ :+ (o.announced -> t))
                             else IO.unit)
                        )
                    )
                }
                ended <- IO.realTime.map(_.toMillis)
              yield Followed(outcome, started, ended)

            def verifyInclusion(followed: Followed): IO[VerifiedInclusion] =
              import followed.*
              for
                inclusion <- included.get.map(
                  _.getOrElse(throw new IllegalStateException("follower inclusion missing"))
                )
                matched = inclusion.included.find(_.transactionId == expectedId).get
                _ <- IO(
                  require(
                    matched.originalBody.contains(submitted.originalBody) &&
                      matched.originalWitnesses.contains(submitted.originalWitnesses),
                    "follower-included body and witness original bytes must equal HTTP original"
                  )
                )
                clientRaw <- awaitFile(root.resolve("submission/client-result.json"), 65536)
                client = ReferenceJson.parse(clientRaw)
                _ <- IO(
                  require(
                    field(client, "passed") == J.Lit("true") &&
                      string(field(client, "profileId")) == admissionProfile.id &&
                      hash(field(client, "transactionId")) == expectedId,
                    "external API client result"
                  )
                )
                events <- relayEvents.get
                _ <- IO(
                  require(
                    events.exists(j =>
                      string(field(j, "kind")) == "BodiesWritten" &&
                        (field(j, "ids") match {
                          case J.Arr(xs) => xs.contains(text(expectedId.hex)); case _ => false
                        })
                    ),
                    "Scala relay body write required"
                  )
                )
                state = outcome.snapshot.state
                terminal = state.certificates.state.tip
                published <- publications.get
                _ <- IO(
                  require(
                    terminal == inclusion.view.pin.point && terminal.slot < 1000 &&
                      state.ledger.environment.epoch == 0 &&
                      published.lastOption.exists { case (p, _) => p == terminal },
                    "stop at verified same-epoch inclusion publication"
                  )
                )
                opens <- opened.get
                closes <- closed.get
                _ <- IO(
                  require(
                    outcome.inclusionReached && outcome.stop == PlutusRunPolicy.FollowStop
                      .Completed(PlutusRunPolicy.CompletionGoal.Inclusion) &&
                      opens == closes && opens > 0,
                    "same-epoch inclusion and transport cleanup"
                  )
                )
              yield VerifiedInclusion(
                followed,
                inclusion,
                matched,
                clientRaw,
                events,
                state,
                terminal,
                opens,
                closes
              )

            def complete(verified: VerifiedInclusion): IO[Unit] =
              import verified.*
              import followed.*
              for
                _ <- save(
                  root.resolve("endpoint-request.json"),
                  record(
                    "schema" -> text("native-live-endpoint-request-v1"),
                    "point" -> point(terminal),
                    "epoch" -> num(0),
                    "sourceJoinId" -> text(joined.id.hex),
                    "networkAppliedStateId" -> text(state.id.hex),
                    "streamStartedUnixMillis" -> num(started),
                    "streamEndedUnixMillis" -> num(ended),
                    "followedAcrossBoundary" -> bool(false),
                    "sameEpoch" -> bool(true),
                    "peerClosed" -> bool(true),
                    "transportClosed" -> bool(true)
                  )
                )
                ready <- awaitFile(root.resolve("endpoint-ready.json"), 16384)
                acquired <- IO.blocking(
                  endpoint(root, ReferenceJson.parse(ready), terminal, joined)
                )
                protocol <- IO(
                  get(NativeEndpointProtocol.compare(acquired, terminal, acquired.id, state))
                )
                ledger <- IO(
                  get(
                    PlutusEndpointLedger.compare(
                      acquired,
                      terminal,
                      acquired.id,
                      state,
                      joined,
                      submitted,
                      spentInput,
                      collateralInput,
                      submittedFee
                    )
                  )
                )
                finalPool <- service.snapshot
                _ <- IO(
                  require(
                    finalPool.size == 0,
                    "included transaction removed from pool"
                  )
                )
                evidenceRecords <- session.evidence.records
                acceptedEvidence <- IO.fromOption(
                  evidenceRecords.find(row =>
                    row.observation.event == EvaluationEvent.Admission(
                      EvaluationEvent.AdmissionOutcome.Accepted
                    ) && row.observation.newlyAdmitted && row.observation.transactionId == expectedId && row.observation.envelopeSHA256 == submitted.envelopeSHA256
                  )
                )(new IllegalStateException("missing accepted evaluation receipt"))
                attempts = events.count(j => string(field(j, "kind")) == "SessionStarted")
                _ <- completed.set(
                  Some(
                    record(
                      "schema" -> text("plutus-live-same-epoch-result-v1"),
                      "evaluationReceiptFile" -> text(
                        "evaluation-receipts/" + acceptedEvidence.filename
                      ),
                      "evaluationReceiptSHA256" -> text(acceptedEvidence.sha256.hex),
                      "evaluationReceiptCount" -> num(evidenceRecords.size),
                      "passed" -> bool(true),
                      "initialPoint" -> point(joined.point),
                      "finalPoint" -> point(terminal),
                      "sourceJoinId" -> text(joined.id.hex),
                      "networkAppliedStateId" -> text(state.id.hex),
                      "endpointAcquisitionId" -> text(acquired.id.hex),
                      "resourcesFinalized" -> bool(true),
                      "liveBoundaryFollow" -> bool(false),
                      "sameEpoch" -> bool(true),
                      "plutusSubmission" -> bool(true),
                      "endpointCompared" -> bool(true),
                      "fullLedgerValidated" -> bool(false),
                      "nativeConformance" -> bool(false),
                      "runtimeImport" -> bool(false),
                      "livePulserCursorEqual" -> bool(false),
                      "representedProtocolEqual" -> bool(protocol.representedProtocolFieldsEqual),
                      "governanceCompared" -> bool(false),
                      "wholeUtxoEqual" -> bool(ledger.wholeUtxoEqual),
                      "wholeUtxoSHA256" -> text(ledger.wholeUtxoSHA256.hex),
                      "instantaneousStakeEqual" -> bool(ledger.instantaneousStakeEqual),
                      "collateralPreserved" -> bool(ledger.collateralPreserved),
                      "feesBefore" -> num(ledger.feesBefore),
                      "feesAfter" -> num(ledger.feesAfter),
                      "feeDelta" -> num(ledger.feesAfter - ledger.feesBefore),
                      "spentInput" -> text(string(field(descriptor, "spentInput"))),
                      "collateralInput" -> text(string(field(descriptor, "collateralInput"))),
                      "adaSubmission" -> bool(true),
                      "profileId" -> text(admissionProfile.id),
                      "nativeScriptSubmission" -> bool(
                        admissionProfile == AdmissionProfile.NativeScript
                      ),
                      "adaSubmittedViaHttp" -> bool(true),
                      "adaIncluded" -> bool(true),
                      "ingress" -> text("scala-http"),
                      "transactionId" -> text(expectedId.hex),
                      "inclusionPoint" -> point(inclusion.view.pin.point),
                      "inclusionGeneration" -> num(inclusion.view.pin.generation),
                      "includedBodySHA256" -> matched.originalBody
                        .fold[J](J.Lit("null"))(x => text(sha(x).hex)),
                      "includedWitnessesSHA256" -> matched.originalWitnesses
                        .fold[J](J.Lit("null"))(x => text(sha(x).hex)),
                      "fullEnvelopeEqualityClaimed" -> bool(false),
                      "poolEntryRemoved" -> bool(true),
                      "relayAttempts" -> num(attempts),
                      "relayEvents" -> J.Arr(events),
                      "clientResultSHA256" -> text(sha(clientRaw).hex),
                      "transportOpens" -> num(opens),
                      "transportCloses" -> num(closes)
                    )
                  )
                )
              yield ()

            session.http(Some(observer)).use { api =>
              for
                followed <- follow(api)
                verified <- verifyInclusion(followed)
                _ <- complete(verified)
              yield ()
            }
          }
        result <- completed.get.map(
          _.getOrElse(throw new IllegalStateException("missing completed result"))
        )
        _ <- save(root.resolve("result.json"), result)
        _ <- IO.println("PLUTUS_SUBMISSION_PASSED")
      yield ()
    }
  def run(args: List[String]): IO[ExitCode] = IO
    .defer(work(args))
    .timeout(220.seconds)
    .as(ExitCode.Success)
    .handleErrorWith { error =>
      val message = Option(error.getMessage).getOrElse(error.getClass.getName).take(4096)
      val failure =
        if args.size == 5 then
          save(
            Path.of(args(4)).resolve("failure.json"),
            record(
              "passed" -> bool(false),
              "errorType" -> text(error.getClass.getName),
              "message" -> text(message)
            )
          ).attempt.void
        else IO.unit
      failure *> IO.println("PLUTUS_SUBMISSION_FAILED: " + message).as(ExitCode(2))
    }
