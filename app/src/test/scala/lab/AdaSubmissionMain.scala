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

/** Isolated Test-only application. Signed originals enter solely through loopback HTTP. */
object AdaSubmissionMain extends IOApp:
  import NativeLiveBoundaryMain.{
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

  private def work(args: List[String]): IO[Unit] =
    require(
      args.size == 5 || args.size == 6,
      "INITIAL_DIRECTORY INITIAL_MANIFEST_SHA256 PORT MAGIC EXCHANGE_DIRECTORY [PROFILE_ID]"
    )
    val root = Path.of(args(4))
    val admissionProfile = AdmissionProfile
      .fromId(args.lift(5).getOrElse(AdmissionProfile.AdaVkey.id))
      .getOrElse(throw new IllegalArgumentException("unsupported admission profile"))
    for
      joined <- IO.blocking(initial(Path.of(args(0)), args(1), admissionProfile))
      descriptor <- IO.blocking(
        ReferenceJson.parse(read(root.resolve("submission/descriptor.json"), 16384))
      )
      expectedId <- IO(hash(field(descriptor, "transactionId")))
      _ <- IO {
        descriptor match
          case J.Obj(values) =>
            if admissionProfile == AdmissionProfile.NativeScript then
              require(
                values.get("profileId").contains(text(admissionProfile.id)),
                "native descriptor profile required"
              )
            else
              values
                .get("profileId")
                .foreach(v =>
                  require(v == text(admissionProfile.id), "descriptor profile mismatch")
                )
          case _ => throw new IllegalArgumentException("descriptor object required")
      }
      context <- IO(get(SequenceInput.fromNativeDiagnostic(joined, joined.id)))
      epoch = joined.ledger.epochComponents
      profile <- IO(
        get(
          CoherentSequence.syntheticBoundaryProfile(
            joined.ledger.parameterRoles,
            joined.ledger.pools,
            joined.ledger.globals,
            joined.ledger.globals.randomnessStabilisationWindow
          )
        )
      )
      fresh = CoherentSequence
        .createWithSyntheticBoundary[IO](
          context,
          epoch.stake,
          profile,
          joined.ledger.governanceInput,
          epoch.nonMyopic,
          epoch.pots,
          epoch.previousBlocks,
          epoch.currentBlocks,
          epoch.reward.componentSHA256
        )
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
      _ <- SubmissionOwner.resource(fresh, admissionProfile).use { owner =>
        AdaSubmissionService.resource(owner).use { service =>
          val observer = new AdmissionStateObserver[IO]:
            def changed(change: AdmissionStateChange): IO[Unit] =
              service.changed(change) *> (if change.included.exists(_.transactionId == expectedId)
                                          then included.set(Some(change))
                                          else IO.unit)
            def closed: IO[Unit] = service.closed
          def relayEvidence(value: J): IO[Unit] =
            relayEvents.modify(xs => ((xs :+ value).takeRight(128), xs.size < 128)).flatMap {
              keep =>
                if !keep then IO.unit
                else
                  IO.blocking {
                    java.nio.file.Files.write(
                      root.resolve("relay-events.jsonl"),
                      SyntheticRewardProjection.encode(value).toArray,
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
                        IO.raiseError(new IllegalStateException("bounded relay attempts exhausted"))
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
          owner.attach(observer) *> AdaHttp.server(AdaHttpHandler(service)).use { api =>
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
                  "profileId" -> text(admissionProfile.id)
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
              outcome <- relay.background.use { _ =>
                NativeLiveBoundary
                  .runWhen(
                    owner,
                    peers,
                    joined.point,
                    BigInt(1000),
                    EphemeralStreaming.Limits(duration = 120.seconds)
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
              inclusion <- included.get.map(
                _.getOrElse(throw new IllegalStateException("follower inclusion missing"))
              )
              matched = inclusion.included.find(_.transactionId == expectedId).get
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
                  published.exists { case (p, t) =>
                    p.slot < 1000 && BigInt(t) < boundaryMillis * 1000000
                  } &&
                    published.exists { case (p, t) =>
                      p == terminal && BigInt(t) >= boundaryMillis * 1000000
                    },
                  "actual publication before and after live boundary required"
                )
              )
              opens <- opened.get
              closes <- closed.get
              _ <- IO(
                require(
                  outcome.boundaryReached && outcome.reason == "boundaryApplied" &&
                    opens == closes && opens > 0,
                  "network boundary and transport cleanup"
                )
              )
              _ <- save(
                root.resolve("endpoint-request.json"),
                record(
                  "schema" -> text("native-live-endpoint-request-v1"),
                  "point" -> point(terminal),
                  "epoch" -> num(1),
                  "sourceJoinId" -> text(joined.id.hex),
                  "networkAppliedStateId" -> text(state.id.hex),
                  "streamStartedUnixMillis" -> num(started),
                  "streamEndedUnixMillis" -> num(ended),
                  "followedAcrossBoundary" -> bool(true),
                  "peerClosed" -> bool(true),
                  "transportClosed" -> bool(true)
                )
              )
              ready <- awaitFile(root.resolve("endpoint-ready.json"), 16384)
              acquired <- IO.blocking(endpoint(root, ReferenceJson.parse(ready), terminal, joined))
              protocol <- IO(
                get(NativeEndpointProtocol.compare(acquired, terminal, acquired.id, state))
              )
              ledger <- IO(
                get(NativeEndpointLedger.compare(acquired, terminal, acquired.id, state, joined))
              )
              governance <- IO(get(NativeEndpointGovernance.compare(ledger, state)))
              finalPool <- service.snapshot
              _ <- IO(
                require(
                  finalPool.size == 0,
                  "included transaction removed from pool"
                )
              )
              attempts <- relayAttempts.get
              _ <- completed.set(
                Some(
                  record(
                    "schema" -> text("native-live-boundary-result-v1"),
                    "passed" -> bool(true),
                    "initialPoint" -> point(joined.point),
                    "finalPoint" -> point(terminal),
                    "sourceJoinId" -> text(joined.id.hex),
                    "networkAppliedStateId" -> text(state.id.hex),
                    "endpointAcquisitionId" -> text(acquired.id.hex),
                    "resourcesFinalized" -> bool(true),
                    "liveBoundaryFollow" -> bool(true),
                    "endpointCompared" -> bool(true),
                    "fullLedgerValidated" -> bool(false),
                    "nativeConformance" -> bool(false),
                    "runtimeImport" -> bool(false),
                    "livePulserCursorEqual" -> bool(false),
                    "representedProtocolEqual" -> bool(protocol.representedProtocolFieldsEqual),
                    "normalizedGovernanceEqual" -> bool(governance.normalizedSerializationEqual),
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
          }
        }
      }
      result <- completed.get.map(
        _.getOrElse(throw new IllegalStateException("missing completed result"))
      )
      _ <- save(root.resolve("result.json"), result)
      _ <- IO.println("ADA_SUBMISSION_PASSED")
    yield ()
  def run(args: List[String]): IO[ExitCode] = IO
    .defer(work(args))
    .timeout(220.seconds)
    .as(ExitCode.Success)
    .handleErrorWith { error =>
      val message = Option(error.getMessage).getOrElse(error.getClass.getName).take(4096)
      val failure =
        if args.size == 5 || args.size == 6 then
          save(
            Path.of(args(4)).resolve("failure.json"),
            record(
              "passed" -> bool(false),
              "errorType" -> text(error.getClass.getName),
              "message" -> text(message)
            )
          ).attempt.void
        else IO.unit
      failure *> IO.println("ADA_SUBMISSION_FAILED: " + message).as(ExitCode(2))
    }
