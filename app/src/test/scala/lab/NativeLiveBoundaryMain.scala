// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, IOApp, ExitCode, Ref}
import cats.syntax.all.*
import java.nio.file.{Files, Path, StandardOpenOption as Open, StandardCopyOption as Copy}
import java.time.Instant
import lab.cbor.Bytes
import lab.submission.AdmissionProfile
import lab.header.PraosCertificateState.Point
import lab.network.{AsyncTcpTransport, TcpLimits}
import ReferenceJson.{Json as J, field, string, uint}
import scala.concurrent.duration.*

/** Private Test/runMain only. Network-applied state stays in this process until endpoint
  * comparison.
  */
object NativeLiveBoundaryMain extends IOApp:
  export PlutusResearchIO.*
  private[lab] def work(args: List[String]): IO[Unit] =
    require(
      args.size == 5,
      "INITIAL_DIRECTORY INITIAL_MANIFEST_SHA256 PORT MAGIC EXCHANGE_DIRECTORY"
    )
    val root = Path.of(args(4))
    for
      joined <- IO.blocking(initial(Path.of(args(0)), args(1)))
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
      runtime <- CoherentSequence
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
      slotMillis = field(genesis, "slotLength") match
        case J.Num(s) =>
          (BigDecimal(s) * 1000).toBigIntExact
            .getOrElse(throw new IllegalArgumentException("integral slot milliseconds required"))
        case _ => throw new IllegalArgumentException("slot length required")
      _ <- IO(require(slotMillis == 100, "proven 100ms slot profile"))
      boundaryMillis = BigInt(
        genesisMillis
      ) + joined.ledger.globals.geometry.epochLength * slotMillis
      _ <- IO.blocking(Files.createDirectory(root.resolve("originals")))
      _ <- save(
        root.resolve("bootstrap-ready.json"),
        record(
          "schema" -> text("native-live-bootstrap-ready-v1"),
          "point" -> point(joined.point),
          "sourceJoinId" -> text(joined.id.hex),
          "boundaryUnixMillis" -> num(boundaryMillis),
          "networkMagic" -> num(joined.acquisition.networkMagic)
        )
      )
      peerRaw <- awaitFile(root.resolve("peer-ready.json"), 16384)
      peerReady = ReferenceJson.parse(peerRaw)
      _ <- IO(
        require(
          obj(peerReady).keySet == Set(
            "schema",
            "referenceContainerId",
            "generatedPort",
            "networkMagic",
            "producerResumedUnixMillis"
          ) &&
            string(field(peerReady, "schema")) == "native-live-peer-ready-v1" &&
            string(field(peerReady, "referenceContainerId")).matches("[0-9a-f]{64}") &&
            uint(field(peerReady, "generatedPort")) == BigInt(args(2)) && uint(
              field(peerReady, "networkMagic")
            ) == joined.acquisition.networkMagic,
          "exact owned peer readiness contract"
        )
      )
      opened <- Ref.of[IO, Int](0)
      closed <- Ref.of[IO, Int](0)
      tcp <- IO(get(TcpLimits.checked(read = 120.seconds)))
      connection = LiveValidatorCommand.counted(
        AsyncTcpTransport.resource[IO](options._1, tcp),
        opened,
        closed
      )
      peers = BoundedChainFollower.sessions[IO](connection, options._2)
      started <- IO.realTime.map(_.toMillis)
      _ <- IO(
        require(BigInt(started) < boundaryMillis, "network follow must start before epoch boundary")
      )
      publications <- Ref.of[IO, Vector[(Point, Long)]](Vector.empty)
      outcome <- NativeLiveBoundary.run(
        runtime,
        peers,
        joined.point,
        joined.ledger.globals.geometry.epochLength,
        EphemeralStreaming.Limits(duration = 120.seconds)
      )(o =>
        IO.realTime.map(_.toNanos).flatMap { observed =>
          observation(root, o, observed) *> (if o.applied.nonEmpty then
                                               publications.update(_ :+ (o.announced -> observed))
                                             else IO.unit)
        }
      )
      published <- publications.get
      ended <- IO.realTime.map(_.toMillis)
      opens <- opened.get
      closes <- closed.get
      state = outcome.snapshot.state
      terminal = state.certificates.state.tip
      _ <- save(
        root.resolve("stream-outcome.json"),
        record(
          "reason" -> text(outcome.reason),
          "boundaryReached" -> bool(outcome.boundaryReached),
          "initialPoint" -> point(joined.point),
          "finalPoint" -> point(terminal),
          "streamStartedUnixMillis" -> num(started),
          "streamEndedUnixMillis" -> num(ended),
          "initialIntersectionConfirmations" -> num(outcome.initialIntersectionConfirmations),
          "peerOpens" -> num(outcome.peerOpens),
          "peerCloses" -> num(outcome.peerCloses),
          "transportOpens" -> num(opens),
          "transportCloses" -> num(closes),
          "networkEvents" -> num(outcome.networkEvents),
          "networkBytes" -> num(outcome.networkBytes)
        )
      )
      _ <- IO(
        require(
          outcome.boundaryReached && outcome.reason == "boundaryApplied" && outcome.peerOpens == 1 && outcome.peerCloses == 1 &&
            opens > 0 && opens == closes,
          "live boundary/owned resource finalization failed: " + outcome.reason
        )
      )
      _ <- IO(
        require(
          outcome.observations.nonEmpty && outcome.observations.forall(_.applied.nonEmpty) &&
            published.exists { case (p, observed) =>
              p.slot < 1000 && BigInt(observed) < boundaryMillis * 1000000
            } &&
            published.exists { case (p, observed) =>
              p == terminal && BigInt(observed) >= boundaryMillis * 1000000
            } &&
            BigInt(ended) >= boundaryMillis,
          "actual online application before and across wall-clock boundary required"
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
      endpointReady <- awaitFile(root.resolve("endpoint-ready.json"), 16384)
      acquired <- IO.blocking(endpoint(root, ReferenceJson.parse(endpointReady), terminal, joined))
      protocol <- IO(get(NativeEndpointProtocol.compare(acquired, terminal, acquired.id, state)))
      ledger <- IO(
        get(NativeEndpointLedger.compare(acquired, terminal, acquired.id, state, joined))
      )
      governance <- IO(get(NativeEndpointGovernance.compare(ledger, state)))
      driver <- IO(
        outcome.driver.getOrElse(throw new IllegalArgumentException("driver result missing"))
      )
      domains =
        (ledger.domainsChecked ++ governance.domainsChecked + "praos-eight-payload-fields").toVector.sorted
      _ <- save(
        root.resolve("result.json"),
        record(
          "schema" -> text("native-live-boundary-result-v1"),
          "passed" -> bool(true),
          "initialPoint" -> point(joined.point),
          "finalPoint" -> point(terminal),
          "sourceJoinId" -> text(joined.id.hex),
          "endpointAcquisitionId" -> text(acquired.id.hex),
          "networkAppliedStateId" -> text(state.id.hex),
          "blocks" -> num(driver.counters.acceptedBlocks),
          "compactions" -> num(driver.counters.compactions),
          "retainedBlocks" -> num(state.acquisition.size),
          "initialIntersectionConfirmations" -> num(outcome.initialIntersectionConfirmations),
          "peerOpens" -> num(outcome.peerOpens),
          "peerCloses" -> num(outcome.peerCloses),
          "transportOpens" -> num(opens),
          "transportCloses" -> num(closes),
          "resourcesFinalized" -> bool(true),
          "liveBoundaryFollow" -> bool(true),
          "endpointCompared" -> bool(true),
          "domainsChecked" -> J.Arr(domains.map(text)),
          "unsupportedDomains" -> J.Arr(
            Vector(
              "general-ledger-admission",
              "full-consensus-validation",
              "native-authenticity",
              "live-pulser-cursor",
              "nonempty-governance",
              "productive-nonempty-go",
              "durable-persistence",
              "rollback",
              "reconnect"
            ).map(text)
          ),
          "normalizedGovernanceEqual" -> bool(governance.normalizedSerializationEqual),
          "representedProtocolEqual" -> bool(protocol.representedProtocolFieldsEqual),
          "finiteLedgerProfileEqual" -> bool(true),
          "fullLedgerValidated" -> bool(false),
          "nativeConformance" -> bool(false),
          "runtimeImport" -> bool(false),
          "livePulserCursorEqual" -> bool(false)
        )
      )
      _ <- IO.println("NATIVE_LIVE_BOUNDARY_PASSED")
    yield ()
  def run(args: List[String]): IO[ExitCode] =
    IO.defer(work(args)).timeout(220.seconds).as(ExitCode.Success).handleErrorWith { error =>
      val message = Option(error.getMessage).getOrElse(error.getClass.getName).take(4096)
      val saveFailure =
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
      saveFailure *> IO.println("NATIVE_LIVE_BOUNDARY_FAILED: " + message).as(ExitCode(2))
    }
