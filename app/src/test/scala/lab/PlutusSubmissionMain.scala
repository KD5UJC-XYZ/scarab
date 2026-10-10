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
object PlutusSubmissionMain extends IOApp:
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
      args.size == 5,
      "INITIAL_DIRECTORY INITIAL_MANIFEST_SHA256 PORT MAGIC EXCHANGE_DIRECTORY"
    )
    val root = Path.of(args(4))
    val admissionProfile = AdmissionProfile.PlutusV3
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
                require(remainingMillis > 0, "same-epoch deadline requires positive remaining time")
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
                  outcome.inclusionReached && outcome.reason == "inclusionApplied" &&
                    opens == closes && opens > 0,
                  "same-epoch inclusion and transport cleanup"
                )
              )
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
              acquired <- IO.blocking(endpoint(root, ReferenceJson.parse(ready), terminal, joined))
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
              attempts = events.count(j => string(field(j, "kind")) == "SessionStarted")
              _ <- completed.set(
                Some(
                  record(
                    "schema" -> text("plutus-live-same-epoch-result-v1"),
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
          }
        }
      }
      result <- completed.get.map(
        _.getOrElse(throw new IllegalStateException("missing completed result"))
      )
      _ <- save(root.resolve("result.json"), result)
      _ <- IO.println("PLUTUS_SUBMISSION_PASSED")
    yield ()
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

import cats.effect.{Async, Ref, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point
import lab.network.ChainSync
import scala.concurrent.duration.*

/** Test-only network pull adapter. No preloaded block list, reconnect, or public admission. */
private[lab] object PlutusSameEpochFollow:
  type Observation = NativeLiveBoundary.Observation
  private val Observation = NativeLiveBoundary.Observation
  final case class Outcome(
      snapshot: CoherentSequence.Snapshot,
      driver: Option[EphemeralStreaming.Report],
      reason: String,
      inclusionReached: Boolean,
      observations: Vector[Observation],
      networkEvents: Long,
      networkBytes: Long,
      peerOpens: Int,
      peerCloses: Int,
      initialIntersectionConfirmations: Int
  )
  private def networkPoint(p: Point): ChainSync.Point =
    ChainSync.Point.Block(ChainSync.UInt64.from(p.slot).toOption.get, p.hash)
  private def sha(b: Bytes): Bytes = Bytes.fromArray(
    java.security.MessageDigest.getInstance("SHA-256").digest(b.toArray)
  )

  /** record fires after fetch, and again only after verified publication. Times are monotonic
    * offsets from this invocation; applied is the first post-publication observation, not an
    * internal publication instant. The second observation has applied=Some; first has None.
    * Resource allocation/intersection share the same deadline as network follow. External
    * cancellation propagates; peer release is guaranteed by Resource.
    */
  /** Additional completion condition is observed only after a verified network publication. */
  def runWhen[F[_]: Async](
      runtime: CoherentDriver[F],
      peer: Resource[F, BoundedChainFollower.Peer[F]],
      initial: Point,
      epochLength: BigInt,
      limits: EphemeralStreaming.Limits
  )(ready: F[Boolean])(record: Observation => F[Unit]): F[Outcome] =
    val F = Async[F]
    def invalid(message: String): F[Unit] = F.raiseError(new IllegalArgumentException(message))
    def checked[A](e: Either[?, A]): F[A] =
      F.fromEither(e.leftMap(x => new IllegalArgumentException(x.toString)))
    for
      _ <- F.raiseUnless(
        runtime != null && initial != null && initial.hash.size == 32 && initial.slot >= 0 &&
          initial.slot <= BigInt("18446744073709551615") && initial.blockNo >= 0 &&
          epochLength > 0 && initial.slot < epochLength && limits != null && limits.valid && limits.retained == 8 &&
          limits.maxEvents <= 4096 && limits.maxBlocks <= 128
      )(new IllegalArgumentException("bounded epoch-zero initial state and limits required"))
      before <- runtime.snapshot
      _ <- F.raiseUnless(
        before.state.certificates.state.tip == initial &&
          before.state.acquisition.tip == networkPoint(initial) &&
          before.state.ledger.environment.epoch == 0 &&
          before.state.syntheticBoundary.isEmpty && before.state.syntheticRewards.isEmpty &&
          before.state.stake.nonEmpty && before.state.ledger.environment.plutus.nonEmpty
      )(new IllegalArgumentException("runtime does not match initial same-epoch Plutus state"))
      started <- F.monotonic
      observations <- Ref.of[F, Vector[Observation]](Vector.empty)
      events <- Ref.of[F, Long](0L)
      bytes <- Ref.of[F, Long](0L)
      opens <- Ref.of[F, Int](0)
      closes <- Ref.of[F, Int](0)
      reached <- Ref.of[F, Boolean](false)
      initialRollbackAllowed <- Ref.of[F, Boolean](true)
      initialConfirmations <- Ref.of[F, Int](0)
      result <- {
        def elapsed = F.monotonic.map(_ - started)
        def charge(n: Long): F[Unit] = bytes
          .modify { old =>
            if n > limits.maxBytes - old then (old, false) else (old + n, true)
          }
          .flatMap(ok => F.raiseUnless(ok)(new IllegalArgumentException("network byte limit")))
        def observeApplication: F[Unit] = (observations.get, runtime.snapshot).tupled.flatMap {
          (all, snapshot) =>
            all.lastOption.filter(_.applied.isEmpty) match
              case None => F.unit
              case Some(last)
                  if snapshot.state.certificates.state.tip == last.announced &&
                    snapshot.state.acquisition.tip == networkPoint(last.announced) =>
                elapsed.flatMap { now =>
                  val updated = last.copy(applied = Some(now))
                  observations.update(xs => xs.updated(xs.size - 1, updated)) *> record(updated)
                }
              case Some(_) => F.unit
        }
        val owned = peer.evalTap(_ => opens.update(_ + 1)).onFinalize(closes.update(_ + 1))
        val session = owned.use { p =>
          def next: F[Option[EphemeralStreaming.Event]] = F.defer {
            observeApplication *> (runtime.snapshot, ready).tupled.flatMap {
              (snapshot, completed) =>
                val state = snapshot.state
                if completed && state.ledger.environment.epoch == 0 &&
                  state.syntheticBoundary.isEmpty &&
                  state.certificates.state.tip.slot / epochLength == 0
                then
                  observations.get.flatMap { xs =>
                    if xs.lastOption.exists(o =>
                        o.applied.nonEmpty && o.announced == state.certificates.state.tip
                      )
                    then reached.set(true).as(None)
                    else invalid("inclusion has no published network observation").as(None)
                  }
                else
                  events
                    .modify(n => if n < limits.maxEvents then (n + 1, true) else (n, false))
                    .flatMap(ok =>
                      F.raiseUnless(ok)(new IllegalArgumentException("network event limit"))
                    ) *>
                    p.next.flatMap {
                      case BoundedChainFollower.Event.Await           => F.cede *> next
                      case BoundedChainFollower.Event.Backward(point) =>
                        // A reference peer may confirm its selected intersection with the first
                        // RollBackward. This exact, once-only no-op changes no runtime state.
                        initialRollbackAllowed.getAndSet(false).flatMap { allowed =>
                          if allowed && point == networkPoint(initial) &&
                            state.certificates.state.tip == initial &&
                            state.acquisition.tip == networkPoint(initial)
                          then initialConfirmations.update(_ + 1) *> F.cede *> next
                          else
                            val received = point match
                              case ChainSync.Point.Origin => "origin"
                              case ChainSync.Point.Block(slot, hash) =>
                                s"slot=${slot.value},hash=${hash.hex.take(64)}"
                            val applied = state.certificates.state.tip
                            invalid(
                              (s"rollback unsupported in monotonic live profile; received=($received); " +
                                s"applied=(slot=${applied.slot},blockNo=${applied.blockNo},hash=${applied.hash.hex.take(64)})")
                                .take(512)
                            ).as(None)
                        }
                      case BoundedChainFollower.Event.Forward(envelope) =>
                        for
                          _ <- initialRollbackAllowed.set(false)
                          arrival <- elapsed
                          _ <- F.raiseUnless(envelope.size > 0 && envelope.size <= 65535)(
                            new IllegalArgumentException("header envelope bound")
                          )
                          _ <- charge(envelope.size.toLong)
                          header <- checked(ReferenceCaptureCommand.header(envelope))
                          previous = state.certificates.state.tip
                          _ <- F.raiseUnless(
                            header.parent == previous.hash && header.slot > previous.slot &&
                              header.blockNo == previous.blockNo + 1 && header.slot < epochLength
                          )(
                            new IllegalArgumentException(
                              "announcement does not extend applied fullpoint within initial epoch"
                            )
                          )
                          announced = Point(header.hash, header.slot, header.blockNo)
                          raw <- p.fetch(networkPoint(announced))
                          fetched <- elapsed
                          _ <- F.raiseUnless(raw.size > 0 && raw.size <= 1048576)(
                            new IllegalArgumentException("block size bound")
                          )
                          _ <- charge(raw.size.toLong)
                          _ <- checked(
                            ReferenceCaptureCommand.compare(header, raw, state.acquisition.tip)
                          )
                          original = BoundedChainFollower.Original(envelope, raw)
                          block <- checked(SequenceInput.block(original))
                          all <- observations.get
                          _ <- F.raiseUnless(all.size < limits.maxBlocks)(
                            new IllegalArgumentException("network block limit")
                          )
                          observation = Observation(
                            all.size,
                            announced,
                            original,
                            sha(envelope),
                            sha(raw),
                            arrival,
                            fetched,
                            None
                          )
                          _ <- observations.update(_ :+ observation)
                          _ <- record(observation)
                        yield Some(EphemeralStreaming.Event.Block(block))
                    }
            }
          }
          p.intersect(Vector(networkPoint(initial))).flatMap { found =>
            F.raiseUnless(found == networkPoint(initial))(
              new IllegalArgumentException("foreign intersection")
            ) *> elapsed.flatMap { spent =>
              val remaining = limits.duration - spent
              if remaining <= Duration.Zero then
                F.raiseError(new java.util.concurrent.TimeoutException("deadline"))
              else
                EphemeralStreaming
                  .run(runtime, limits.copy(duration = remaining))(next)
                  .flatTap(_ => observeApplication)
            }
          }
        }
        session.timeout(limits.duration).attempt
      }
      snapshot <- runtime.snapshot
      all <- observations.get
      n <- events.get
      b <- bytes.get
      o <- opens.get
      c <- closes.get
      hit <- reached.get
      confirmations <- initialConfirmations.get
      report = result.toOption
      reason = result.fold(
        e =>
          if e.isInstanceOf[java.util.concurrent.TimeoutException] then "deadline"
          else s"failure:${e.getMessage}",
        r =>
          if hit && r.stop == EphemeralStreaming.Stop.End then "inclusionApplied"
          else r.stop.toString
      )
    yield Outcome(
      snapshot,
      report,
      reason,
      hit && report.exists(_.stop == EphemeralStreaming.Stop.End),
      all,
      n,
      b,
      o,
      c,
      confirmations
    )
