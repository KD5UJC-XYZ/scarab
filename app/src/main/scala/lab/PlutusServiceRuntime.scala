// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref, Resource, ExitCode}
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
  import PlutusRunPolicy.{RelayStop, WindowEnd, TerminalCategory}
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

  private[lab] def deadline(now: Long, boundary: BigInt, requestedSeconds: Int): (Long, WindowEnd) =
    require(requestedSeconds >= 1 && requestedSeconds <= 60, "bounded requested duration")
    val requested = BigInt(now) + requestedSeconds * 1000
    val effective = requested.min(boundary - 2000)
    require(effective > now && effective.isValidLong, "initial epoch service window missed")
    (effective.toLong, if effective < requested then WindowEnd.Epoch else WindowEnd.Duration)

  private[lab] def repeatedDeadline(
      now: Long,
      requestedSeconds: Int,
      budget: EphemeralStreaming.RepeatedBudget = EphemeralStreaming.RepeatedBudget.Standard
  ): (Long, WindowEnd) =
    require(
      requestedSeconds >= 1 && requestedSeconds <= budget.maximumSeconds,
      "bounded repeated duration"
    )
    val end = BigInt(now) + BigInt(requestedSeconds) * 1000
    require(end.isValidLong && end > now, "repeated deadline range")
    (end.toLong, WindowEnd.Duration)

  private[lab] def withFollowWindow(
      value: J,
      window: Option[PlutusRepeatedEpochFollow.FollowWindow]
  ): J =
    window.fold(value) { w =>
      value match
        case J.Obj(fields) =>
          J.Obj(
            fields.updated(
              "followWindow",
              record(
                "schema" -> text("plutus-service-follow-window-v1"),
                "startedUnixMillis" -> num(w.startedUnixMillis),
                "endedUnixMillis" -> num(w.endedUnixMillis),
                "elapsedMonotonicNanos" -> num(w.elapsedMonotonicNanos)
              )
            )
          )
        case _ => throw new IllegalArgumentException("follow window requires an object")
    }

  private[lab] def operationTimeout(config: PlutusServiceCommand.Config): FiniteDuration =
    if config.repeated.isEmpty then 220.seconds
    else if config.repeatedBudget == EphemeralStreaming.RepeatedBudget.Soak then 880.seconds
    else 760.seconds

  private[lab] def publishAfterFinalization[A](output: Path, resource: Resource[IO, A])(
      body: A => IO[(J, Boolean)]
  ): IO[Boolean] =
    resource.use(body).flatMap { (value, success) =>
      boundedSave(output.resolve("result.json"), value, MaxTerminalBytes).as(success)
    }

  private def work(config: PlutusServiceCommand.Config): IO[Boolean] =
    val selected: Resource[IO, Option[NativeLikelihoodOracle.Oracle[IO]]] = config.repeated match
      case None => Resource.pure[IO, Option[NativeLikelihoodOracle.Oracle[IO]]](None)
      case Some(PlutusServiceCommand.Repeated.Jvm) =>
        JvmLikelihoodGenerator
          .resource[IO](config.base.exchange.resolve("jvm-likelihood"))
          .map(g => Some(g))
      case Some(PlutusServiceCommand.Repeated.Native(executable, executableSHA256)) =>
        NativeLikelihoodOracle
          .resource[IO](
            NativeLikelihoodOracle.Config(
              executable,
              executableSHA256,
              config.base.exchange.resolve("native-likelihood")
            )
          )
          .map(g => Some(g))
    publishAfterFinalization(config.base.exchange, selected)(workWithOracle(config, _))

  private def workWithOracle(
      config: PlutusServiceCommand.Config,
      oracle: Option[NativeLikelihoodOracle.Oracle[IO]]
  ): IO[(J, Boolean)] =
    val c = config.base
    val root = c.exchange
    for
      joined <- IO.blocking(initial(c.initial, c.manifest, AdmissionProfile.PlutusV3))
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
      earlyStartup <- PlutusServiceCheckpoint.start(
        config.checkpoint,
        joined,
        get(Bytes.fromHex(c.manifest))
      )
      startup <- oracle.fold(IO.pure(earlyStartup))(o =>
        RepeatedPlutusBootstrap.start(
          joined,
          earlyStartup,
          o,
          if config.repeated.contains(PlutusServiceCommand.Repeated.Jvm)
          then lab.ledger.ConwayNativeLikelihood.Mode.PureJvm
          else lab.ledger.ConwayNativeLikelihood.Mode.CheckedJvm
        )
      )
      startPoint = startup.snapshot.state.certificates.state.tip
      effectiveMaxBlocks = config.checkpoint.flatMap(_.checkpointAfter).getOrElse(config.maxBlocks)
      checkpointManifest <- config.checkpoint.filter(_.checkpointAfter.nonEmpty).traverse { _ =>
        IO.blocking(read(c.initial.resolve("adapter-inputs.json"), 65536)).flatTap { bytes =>
          IO(require(sha(bytes).hex == c.manifest, "checkpoint manifest changed"))
        }
      }
      checkpointSaved <- Ref.of[IO, Option[PlutusServiceCheckpoint.Saved]](None)
      boundary = BigInt(Instant.parse(string(field(genesis, "systemStart"))).toEpochMilli) + 100000
      _ <- IO.blocking(Files.createDirectory(root.resolve("originals")))
      opened <- Ref.of[IO, Int](0)
      closed <- Ref.of[IO, Int](0)
      followWindow <- Ref.of[IO, Option[PlutusRepeatedEpochFollow.FollowWindow]](None)
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
              startPoint.slot.toString,
              startPoint.hash.hex
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
      fresh = IO.pure(startup.runtime)
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
          def modeMetadata(
              value: J,
              saved: Option[PlutusServiceCheckpoint.Saved] = None,
              admission: Option[AdaSubmissionService.Snapshot] = None
          ): J =
            val profiled =
              if config.repeatedBudget == EphemeralStreaming.RepeatedBudget.Soak then
                value match
                  case J.Obj(fields) =>
                    J.Obj(fields.updated("soakProfile", text(PlutusServiceCommand.SoakProfileId)))
                  case _ =>
                    throw new IllegalArgumentException("profile metadata requires an object")
              else value
            val scoped = config.repeated.fold(profiled) { mode =>
              profiled match
                case J.Obj(fields) =>
                  J.Obj(
                    fields.updated(
                      "epochMode",
                      record(
                        "mode" -> text(mode.id),
                        "jvmComputed" -> bool(true),
                        "nativeChecked" -> bool(mode.executablePin.nonEmpty),
                        "nativeRuntimeDependency" -> bool(mode.executablePin.nonEmpty),
                        "researchOnly" -> bool(true),
                        "activeStartsAfterPeerReady" -> bool(true),
                        "startupTimeoutSeconds" -> num(30),
                        "nativeExecutableSHA256" -> mode.executablePin.fold[J](J.Lit("null"))(x =>
                          text(x.hex)
                        ),
                        "nativeEvidenceDirectory" -> mode.executablePin.fold[J](J.Lit("null"))(_ =>
                          text("native-likelihood")
                        ),
                        "generationEvidenceDirectory" -> text(
                          if mode.executablePin.nonEmpty then "native-likelihood"
                          else "jvm-likelihood"
                        ),
                        "maxEpochTransitions" -> num(8),
                        "lateCheckpointRestoreSupported" -> bool(false)
                      )
                    )
                  )
                case _ => throw new IllegalArgumentException("service metadata object required")
            }
            config.checkpoint.fold(scoped) { mode =>
              scoped match
                case J.Obj(fields) =>
                  J.Obj(
                    fields.updated(
                      "boundedRestart",
                      record(
                        "scope" -> text("linear-epoch-zero-max8"),
                        "startupRestored" -> bool(startup.restored),
                        "startupAdmission" -> admission.fold[J](J.Lit("null"))(a =>
                          record(
                            "pin" -> pin(a.pin),
                            "size" -> num(a.size),
                            "byteSize" -> num(a.byteSize),
                            "eligibleCount" -> num(a.eligible.size),
                            "rebuilding" -> bool(a.rebuilding),
                            "closed" -> bool(a.closed)
                          )
                        ),
                        "sourceAnchorPoint" -> point(joined.point),
                        "restoredDepth" -> num(startup.snapshot.state.depth),
                        "freshCheckpointId" -> text(startup.snapshot.state.ledger.checkpointId.hex),
                        "pendingAdmissionRestored" -> bool(false),
                        "checkpointAfter" -> mode.checkpointAfter.fold[J](J.Lit("null"))(num(_)),
                        "effectiveMaxBlocks" -> num(effectiveMaxBlocks),
                        "checkpointFile" -> saved.fold[J](J.Lit("null"))(_ =>
                          text("checkpoint.bin")
                        ),
                        "checkpointSHA256" -> saved.fold[J](J.Lit("null"))(x =>
                          text(x.publication.claim.publicationSHA256.hex)
                        ),
                        "checkpointRequestSHA256" -> saved.fold[J](J.Lit("null"))(x =>
                          text(x.requestSHA256.hex)
                        ),
                        "crashDurable" -> bool(false)
                      )
                    )
                  )
                case _ => throw new IllegalArgumentException("service metadata object required")
            }
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
          def relay: IO[RelayStop] = IO.defer {
            node.service.snapshot.flatMap { pool =>
              if pool.closed then IO.pure(RelayStop.ServiceUnavailable)
              else if pool.eligible.isEmpty then IO.sleep(100.millis) *> relay
              else
                relayCount
                  .modify(n => if n < MaxRelaySessions then (n + 1, Some(n + 1)) else (n, None))
                  .flatMap {
                    case None => IO.pure(RelayStop.SessionLimit)
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
              repeated <- config.repeated.traverse { _ =>
                node.owner.withCurrent(change.view.pin)(node.owner.snapshot).flatMap { checked =>
                  IO.fromEither(
                    checked
                      .leftMap(_ => new IllegalStateException("repeated publication state changed"))
                  ).map(snapshot => PlutusRepeatedPublication.fields(snapshot.state))
                }
              }
              baseValue = record(
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
              value = repeated.fold(baseValue) { fields =>
                baseValue match
                  case J.Obj(all) => J.Obj(all.updated("repeatedEpoch", fields))
                  case _          => throw new IllegalStateException("publication object required")
              }
              file = f"publication-${rows.size}%04d.json"
              _ <- boundedSave(root.resolve(file), value, MaxPublicationBytes)
              _ <- published.update(
                _ :+ record(
                  "file" -> text(file),
                  "sha256" -> text(sha(EvidenceJson.encode(value)).hex),
                  "pin" -> pin(change.view.pin)
                )
              )
              _ <- config.checkpoint.filter(_.checkpointAfter.contains(rows.size + 1)).traverse_ {
                mode =>
                  for
                    frozen <- node.owner.withCurrent(change.view.pin)(node.owner.snapshot)
                    snapshot <- IO.fromEither(
                      frozen.leftMap(_ =>
                        new IllegalStateException("checkpoint publication state changed")
                      )
                    )
                    saved <- PlutusServiceCheckpoint.save(
                      root,
                      snapshot,
                      joined,
                      checkpointManifest.get,
                      mode
                    )
                    _ <- checkpointSaved.set(Some(saved))
                  yield ()
              }
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
                window <- IO(
                  if config.repeated.nonEmpty then repeatedDeadline(now, 30)
                  else deadline(now, boundary, config.durationSeconds)
                )
                admission <- config.checkpoint.traverse(_ => node.service.snapshot)
                _ <- admission.traverse_(a =>
                  IO(
                    require(
                      a.size == 0 && a.byteSize == 0 &&
                        a.eligible.isEmpty && !a.rebuilding && !a.closed && a.pin.point == startPoint,
                      "bounded restart readiness requires actual empty startup pool"
                    )
                  )
                )
                _ <- boundedSave(
                  root.resolve("bootstrap-ready.json"),
                  modeMetadata(
                    record(
                      "schema" -> text("plutus-service-ready-v1"),
                      "apiPort" -> num(api.port),
                      "profileId" -> text(AdmissionProfile.PlutusV3.id),
                      "sourceJoinId" -> text(joined.id.hex),
                      "initialManifestSHA256" -> text(c.manifest),
                      "initialPoint" -> point(startPoint),
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
                    admission = admission
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
                activeWindow <- IO(
                  if config.repeated.nonEmpty then
                    repeatedDeadline(started, config.durationSeconds, config.repeatedBudget)
                  else window
                )
                activeView <- node.owner.current
                _ <- config.repeated.traverse_(_ =>
                  boundedSave(
                    root.resolve("service-active.json"),
                    record(
                      "schema" -> text("plutus-service-active-v1"),
                      "pin" -> pin(activeView.pin),
                      "repeatedEpoch" -> PlutusRepeatedPublication.fields(startup.snapshot.state),
                      "epochMode" -> text(config.repeated.get.id),
                      "startedUnixMillis" -> num(started),
                      "deadlineUnixMillis" -> num(activeWindow._1),
                      "durationSeconds" -> num(config.durationSeconds)
                    ),
                    16384
                  )
                )
                followStarted <- IO.realTime.map(_.toMillis)
                _ <- IO(
                  require(
                    followStarted < activeWindow._1,
                    "active deadline exhausted before follow"
                  )
                )
                race <- IO.race(
                  relay.attempt, {
                    val limits = EphemeralStreaming.Limits(
                      maxEvents = 4096,
                      maxBlocks = effectiveMaxBlocks,
                      duration = (activeWindow._1 - followStarted).millis
                    )
                    val complete = published.get.map(_.size >= effectiveMaxBlocks)
                    oracle match
                      case None =>
                        PlutusSameEpochFollow
                          .runUntil(node.owner, peers, startPoint, 1000, limits)(complete)(observed)
                          .map(_.stop)
                      case Some(o) =>
                        PlutusRepeatedEpochFollow
                          .runUntil(
                            PlutusRepeatedDriver(startup.runtime, node.owner, o),
                            peers,
                            startPoint,
                            1000,
                            limits,
                            config.repeatedBudget,
                            Option.when(
                              config.repeatedBudget == EphemeralStreaming.RepeatedBudget.Soak
                            )(followWindow)
                          )(complete)(observed)
                          .map(_.stop)
                  }
                )
                reason = PlutusRunPolicy.fromRace(race, activeWindow._2)
              yield (now, activeWindow, started, reason)
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
                savedCheckpoint <- checkpointSaved.get
              yield (
                modeMetadata(
                  record(
                    "schema" -> text("plutus-service-result-v1"),
                    "status" -> text(
                      PlutusRunPolicy.categoryWire(PlutusRunPolicy.category(reason))
                    ),
                    "stopReason" -> text(PlutusRunPolicy.serviceWire(reason)),
                    "transactionSuccessClaimed" -> bool(false),
                    "fullLedgerValidated" -> bool(false),
                    "volatile" -> bool(true),
                    "restartSupported" -> bool(false),
                    "profileId" -> text(AdmissionProfile.PlutusV3.id),
                    "sourceJoinId" -> text(joined.id.hex),
                    "initialManifestSHA256" -> text(c.manifest),
                    "initialPoint" -> point(startPoint),
                    "finalPin" -> pin(view.pin),
                    "initialEpoch" -> num(0),
                    "requestedDeadlineUnixMillis" -> num(
                      BigInt(
                        if config.repeated.nonEmpty then started else now
                      ) + config.durationSeconds * 1000
                    ),
                    "effectiveDeadlineUnixMillis" -> num(window._1),
                    "startedUnixMillis" -> num(started),
                    "endedUnixMillis" -> num(ended),
                    "publications" -> J.Arr(rows),
                    "terminalObservationFile" -> text("terminal-observation.json"),
                    "terminalObservationSHA256" -> text(
                      sha(EvidenceJson.encode(terminalValue)).hex
                    ),
                    "relaySessions" -> num(attempts),
                    "relayEvents" -> J.Arr(events)
                  ),
                  savedCheckpoint
                ),
                node.evidence.records,
                reason
              )
            }
        }
        .onCancel(for
          opens <- opened.get
          closes <- closed.get
          measured <- followWindow.get
          _ <- boundedSave(
            root.resolve("cancelled.json"),
            withFollowWindow(
              record(
                "schema" -> text("plutus-service-cancelled-v1"),
                "resourcesFinalized" -> bool(opens == closes && config.repeated.isEmpty),
                "transportOpens" -> num(opens),
                "transportCloses" -> num(closes)
              ),
              measured
            ),
            16384
          ).attempt.void
        yield ())
      // The service workers, owner and evidence sink have all finalized. records is a read-only Ref snapshot.
      receipts <- closedResult._2
      measured <- followWindow.get
      result = withFollowWindow(closedResult._1, measured)
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
              "resourcesFinalized" -> bool(closedResult._3 match
                case PlutusRunPolicy.ServiceStop.FollowerFailure(
                      PlutusRunPolicy.FollowStop
                        .Failed(_: PlutusRepeatedEpochFollow.ResourceFailure)
                    ) =>
                  false
                case _ => true),
              "transportOpens" -> num(opens),
              "transportCloses" -> num(closes)
            )
          )
        case _ => throw new IllegalStateException("result shape")
    yield (complete, PlutusRunPolicy.category(closedResult._3) == TerminalCategory.Stopped)

  def run(config: PlutusServiceCommand.Config): IO[ExitCode] = runOperation(config)(work(config))

  private[lab] def runOperation(
      config: PlutusServiceCommand.Config
  )(operation: => IO[Boolean]): IO[ExitCode] = IO
    .defer(operation)
    .timeout(operationTimeout(config))
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
