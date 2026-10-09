// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, IOApp, ExitCode, Ref}
import cats.syntax.all.*
import java.nio.file.{Files, Path, StandardOpenOption as Open, StandardCopyOption as Copy}
import java.time.Instant
import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point
import lab.network.{AsyncTcpTransport, TcpLimits}
import ReferenceJson.{Json as J, field, string, uint}
import scala.concurrent.duration.*

/** Private Test/runMain only. Network-applied state stays in this process until endpoint
  * comparison.
  */
object NativeLiveBoundaryMain extends IOApp:
  private[lab] def get[A](e: Either[?, A]): A =
    e.fold(x => throw new IllegalArgumentException(x.toString), identity)
  private[lab] def obj(j: J): Map[String, J] = j match
    case J.Obj(values) => values
    case _             => throw new IllegalArgumentException("JSON object required")
  private[lab] def sha(b: Bytes): Bytes = ClusterHeaderObservation.sha256(b)
  private[lab] def hash(j: J): Bytes =
    val s = string(j)
    require(s.matches("[0-9a-f]{64}"), "SHA256/hash spelling")
    get(Bytes.fromHex(s))
  private[lab] def read(path: Path, limit: Int): Bytes =
    val stream = Files.newInputStream(path)
    val data =
      try stream.readNBytes(limit + 1)
      finally stream.close()
    require(data.length <= limit, "input byte bound")
    Bytes.fromArray(data)
  private[lab] def encode(j: J): Bytes = SyntheticRewardProjection.encode(j)
  private[lab] def num(n: BigInt): J = J.Num(n.toString)
  private[lab] def text(s: String): J = J.Str(s)
  private[lab] def bool(v: Boolean): J = J.Lit(v.toString)
  private[lab] def record(fields: (String, J)*): J = J.Obj(fields.toMap)
  private[lab] def point(p: Point): J =
    record("slot" -> num(p.slot), "blockNo" -> num(p.blockNo), "hash" -> text(p.hash.hex))
  private[lab] def point(j: J): Point =
    require(obj(j).keySet == Set("slot", "blockNo", "hash"), "exact fullpoint fields")
    Point(hash(field(j, "hash")), uint(field(j, "slot")), uint(field(j, "blockNo")))
  private[lab] def save(path: Path, value: J): IO[Unit] = IO.blocking {
    require(!Files.exists(path), "evidence already exists")
    val temporary = path.resolveSibling(path.getFileName.toString + ".part")
    Files.write(temporary, encode(value).toArray, Open.CREATE_NEW, Open.WRITE)
    Files.move(temporary, path, Copy.ATOMIC_MOVE)
    ()
  }
  private[lab] def awaitFile(path: Path, limit: Int): IO[Bytes] =
    def loop: IO[Bytes] = IO.blocking(Files.exists(path)).flatMap {
      case true  => IO.blocking(read(path, limit))
      case false => IO.sleep(100.millis) *> IO.defer(loop)
    }
    loop.timeout(60.seconds)
  private[lab] def inputs(
      root: Path,
      rows: Map[String, J],
      names: Set[String]
  ): (Map[String, Bytes], Map[String, Bytes]) =
    require(rows.keySet == names, "exact original input names")
    val originals = rows.map { (name, row) =>
      require(obj(row).keySet == Set("sha256", "bytes"), "input descriptor fields")
      val b = read(root.resolve(name), 524288)
      require(
        BigInt(b.size) == uint(field(row, "bytes")) && sha(b) == hash(field(row, "sha256")),
        "original input pin: " + name
      )
      name -> b
    }
    (originals, rows.map((name, row) => name -> hash(field(row, "sha256"))))
  private[lab] def initial(root: Path, pin: String): NativeLedgerV2.Checked =
    val raw = read(root.resolve("adapter-inputs.json"), 16384)
    require(pin.matches("[0-9a-f]{64}") && sha(raw).hex == pin, "independent initial manifest pin")
    val j = ReferenceJson.parse(raw)
    require(
      obj(j).keySet == Set("schema", "point", "inputs") && string(
        field(j, "schema")
      ) == "native-ledger-v2-reviewed-inputs-v1",
      "initial manifest contract"
    )
    val anchor = point(field(j, "point"))
    val (raws, pins) = inputs(root, obj(field(j, "inputs")), NativeLedgerV2.InputNames)
    val joined = get(NativeLedgerV2.decode(raws, pins, anchor))
    require(
      anchor.slot < 300 && joined.ledger.globals.geometry.epochLength == 1000,
      "proven early epoch-zero profile"
    )
    joined
  private[lab] def endpoint(
      root: Path,
      ready: J,
      terminal: Point,
      joined: NativeLedgerV2.Checked
  ): NativeProtocolBootstrap.Acquisition =
    require(
      obj(ready).keySet == Set("schema", "manifestSHA256", "acquisitionResultSHA256") &&
        string(field(ready, "schema")) == "native-endpoint-ready-v1",
      "endpoint readiness contract"
    )
    val manifest = read(root.resolve("endpoint/endpoint-inputs.json"), 16384)
    require(
      sha(manifest) == hash(field(ready, "manifestSHA256")),
      "independent endpoint manifest pin"
    )
    val j = ReferenceJson.parse(manifest)
    require(
      obj(j).keySet == Set(
        "schema",
        "point",
        "genesisSHA256",
        "acquisitionResultSHA256",
        "inputs"
      ) &&
        string(field(j, "schema")) == "native-endpoint-reviewed-inputs-v1",
      "endpoint manifest contract"
    )
    require(
      point(field(j, "point")) == terminal && hash(
        field(j, "genesisSHA256")
      ) == joined.ledger.globals.genesisSHA256,
      "endpoint fullpoint/genesis binding"
    )
    val resultPin = hash(field(ready, "acquisitionResultSHA256"))
    require(
      hash(field(j, "acquisitionResultSHA256")) == resultPin &&
        sha(read(root.resolve("acquisition-result.json"), 65536)) == resultPin,
      "endpoint acquisition evidence pin"
    )
    val (raws, pins) =
      inputs(root.resolve("endpoint"), obj(field(j, "inputs")), NativeProtocolBootstrap.InputNames)
    get(NativeProtocolBootstrap.checkAcquisition(raws, pins, terminal))
  private[lab] def observation(
      root: Path,
      o: NativeLiveBoundary.Observation,
      observedUnixNanos: Long
  ): IO[Unit] = IO.blocking {
    val prefix = f"block-${o.index}%04d"
    val envelope = root.resolve("originals").resolve(prefix + "-header.cbor")
    val block = root.resolve("originals").resolve(prefix + ".cbor")
    if o.applied.isEmpty then
      Files.write(envelope, o.original.envelope.toArray, Open.CREATE_NEW, Open.WRITE)
      Files.write(block, o.original.block.toArray, Open.CREATE_NEW, Open.WRITE)
    else
      require(
        sha(read(envelope, 65535)) == o.envelopeSHA256 && sha(
          read(block, 1048576)
        ) == o.blockSHA256,
        "stream originals changed"
      )
    val row = record(
      "index" -> num(o.index),
      "point" -> point(o.announced),
      "phase" -> text(if o.applied.isEmpty then "fetched" else "published-observed"),
      "headerSHA256" -> text(o.envelopeSHA256.hex),
      "blockSHA256" -> text(o.blockSHA256.hex),
      "observedUnixNanos" -> num(observedUnixNanos),
      "arrivedNanos" -> num(o.arrived.toNanos),
      "fetchedNanos" -> num(o.fetched.toNanos),
      "appliedObservedNanos" -> o.applied.fold[J](J.Lit("null"))(x => num(x.toNanos))
    )
    Files.write(
      root.resolve("observations.jsonl"),
      encode(row).toArray,
      Open.CREATE,
      Open.APPEND
    )
    ()
  }
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
