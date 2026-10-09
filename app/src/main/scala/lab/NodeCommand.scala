// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, ExitCode, IO, Ref, Resource}
import cats.syntax.all.*
import java.nio.file.Path
import lab.cbor.Bytes
import lab.network.{AsyncTcpTransport, ChainSync, TcpLimits}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Ordinary bounded, in-memory node entry point. Bootstrap is supplied authority, not a validated
  * chain tip. Download notifications and scoped publication are deliberately separate facts.
  */
object NodeCommand:
  val Profile = CoherentSequence.ProfileId
  val NetworkMagic = 1082026L
  final case class V2Settings(
      journal: Path,
      storeId: Bytes,
      seedCapture: Option[Path],
      seedSha256: Option[Bytes],
      compactThrough: Option[ChainSync.Point]
  )
  final class Config private[NodeCommand] (
      val profile: String,
      val bootstrap: Path,
      val port: Int,
      val blocks: Int,
      val seconds: Int,
      val events: Int,
      val bytes: Long,
      val reconnects: Int,
      val mode: String,
      val rollbackCapacity: Int,
      val audit: Boolean,
      val storeAction: Option[String],
      val store: Option[Path],
      val receipts: Option[Path],
      val expectedContext: Option[Bytes],
      val resumeReceipt: Option[Path],
      val resumeSha256: Option[Bytes],
      val v2: Option[V2Settings],
      val completionFence: Option[CompletionFence.Settings]
  ):
    def durable: Boolean = mode == "bounded-durable" || mode == "sustained-durable"
    def policy: BoundedValidatorRunner.Policy = BoundedValidatorRunner.Policy(
      target = blocks,
      maxEvents = events,
      reconnects = reconnects,
      maxBytes = bytes,
      duration = seconds.seconds,
      advanceWindow = mode == "sustained-volatile" || mode == "sustained-durable",
      rollbackCapacity = rollbackCapacity
    )
  object Config:
    private val names = Set(
      "--profile",
      "--bootstrap",
      "--port",
      "--blocks",
      "--seconds",
      "--events",
      "--bytes",
      "--reconnects",
      "--mode",
      "--rollback-capacity",
      "--audit",
      "--store-action",
      "--store",
      "--receipts",
      "--expected-context",
      "--resume-receipt",
      "--resume-sha256",
      "--journal",
      "--store-id",
      "--seed-capture",
      "--seed-sha256",
      "--compact-slot",
      "--compact-hash",
      "--completion-fence",
      "--fence-id",
      "--fence-phase",
      "--minimum-depth"
    )

    /** Entire grammar and all bounds are checked before any filesystem or socket operation. */
    def parse(args: List[String]): Either[String, Config] =
      try
        require(
          args != null && args.nonEmpty && args.size <= 54 && args.size % 2 == 0,
          "expected flag/value pairs"
        )
        require(
          args.forall(s => s != null && s.nonEmpty && s.length <= 4096),
          "bounded nonempty arguments required"
        )
        val pairs = args.grouped(2).map(p => p.head -> p(1)).toVector
        require(
          pairs.forall(p => names.contains(p._1) && !p._2.startsWith("--")),
          "unknown flag or missing value"
        )
        require(pairs.map(_._1).distinct.size == pairs.size, "duplicate flag")
        val fields = pairs.toMap
        require(
          Set("--profile", "--bootstrap", "--port").subsetOf(fields.keySet),
          "--profile, --bootstrap and --port are required"
        )
        require(fields("--profile") == Profile, "unsupported explicit profile")
        def number(name: String, default: String): Long =
          val text = fields.getOrElse(name, default)
          val value = text.toLongOption.getOrElse(
            throw new IllegalArgumentException("invalid integer: " + name)
          )
          require(text == value.toString, "canonical decimal integer required: " + name)
          value
        def integer(name: String, default: String): Int =
          val n = number(name, default)
          require(n >= 0 && n <= Int.MaxValue, "integer range: " + name)
          n.toInt
        val mode = fields.getOrElse("--mode", "bounded-volatile")
        mode match
          case "bounded-volatile" =>
            require(
              !fields.contains("--rollback-capacity"),
              "--rollback-capacity requires sustained-volatile"
            )
          case "sustained-volatile" | "sustained-durable" =>
            require(
              fields.contains("--rollback-capacity"),
              "sustained mode requires explicit --rollback-capacity 1..8"
            )
            require(
              fields.contains("--blocks") && integer("--blocks", "0") > 8,
              "sustained mode requires explicit --blocks 9..256"
            )
          case "bounded-durable" =>
            require(
              !fields.contains("--rollback-capacity"),
              "bounded-durable requires fixed capacity8"
            )
          case _ => throw new IllegalArgumentException("unsupported node mode")
        val audit = fields.getOrElse("--audit", "false") match
          case "true"  => true
          case "false" => false
          case _       => throw new IllegalArgumentException("--audit requires true or false")
        val durableFields = Set(
          "--store-action",
          "--store",
          "--receipts",
          "--expected-context",
          "--resume-receipt",
          "--resume-sha256"
        )
        def hash(name: String): Bytes =
          val value = fields(name)
          require(value.matches("[0-9a-f]{64}"), "canonical 32-byte hash required: " + name)
          Bytes.fromHex(value).toOption.get
        val storage = if mode != "bounded-durable" && mode != "sustained-durable" then
          require(!fields.keySet.exists(durableFields), "storage flags require bounded-durable")
          (None, None, None, None, None, None)
        else
          require(
            Set("--store-action", "--store", "--receipts", "--expected-context").subsetOf(
              fields.keySet
            ),
            "durable store action/path, external receipts and expected context required"
          )
          val action = fields("--store-action")
          require(Set("create", "resume")(action), "store action must be create or resume")
          val input = if mode == "sustained-durable" then
            require(
              !fields.contains("--resume-receipt") && !fields.contains("--resume-sha256"),
              "v2 journal is authority; v1 receipt flags forbidden"
            )
            (None, None)
          else if action == "resume" then
            require(
              fields.contains("--resume-receipt") && fields.contains("--resume-sha256"),
              "resume requires explicit receipt path and SHA256"
            )
            (Some(Path.of(fields("--resume-receipt"))), Some(hash("--resume-sha256")))
          else
            require(
              !fields.contains("--resume-receipt") && !fields.contains("--resume-sha256"),
              "create cannot accept resume authority"
            )
            (None, None)
          val root = Path.of(fields("--store")); val receipts = Path.of(fields("--receipts"))
          NodeDurableReceipts
            .validatePaths(root, receipts, input._1)
            .fold(s => throw new IllegalArgumentException(s), identity)
          (
            Some(action),
            Some(root),
            Some(receipts),
            Some(hash("--expected-context")),
            input._1,
            input._2
          )
        val v2Fields = Set(
          "--journal",
          "--store-id",
          "--seed-capture",
          "--seed-sha256",
          "--compact-slot",
          "--compact-hash"
        )
        val v2 = if mode != "sustained-durable" then
          require(!fields.keySet.exists(v2Fields), "v2 flags require sustained-durable")
          None
        else
          require(
            Set("--journal", "--store-id").subsetOf(fields.keySet),
            "v2 journal and independent store-id required"
          )
          val seedFlags = Set("--seed-capture", "--seed-sha256", "--compact-slot", "--compact-hash")
          val seed = if storage._1.contains("create") then
            require(
              seedFlags.subsetOf(fields.keySet),
              "v2 create requires pinned nonempty seed capture and explicit compact point"
            )
            val slotText = fields("--compact-slot")
            require(slotText.matches("0|[1-9][0-9]{0,19}"), "canonical compact slot required")
            val slot = ChainSync.UInt64
              .from(BigInt(slotText))
              .fold(e => throw new IllegalArgumentException(e), identity)
            (
              Some(Path.of(fields("--seed-capture"))),
              Some(hash("--seed-sha256")),
              Some(ChainSync.Point.Block(slot, hash("--compact-hash")))
            )
          else
            require(!fields.keySet.exists(seedFlags), "v2 resume forbids seed fallback")
            (None, None, None)
          val journal = Path.of(fields("--journal"))
          NodeV2Receipts
            .validatePaths(storage._2.get, journal, storage._3.get, seed._1)
            .fold(e => throw new IllegalArgumentException(e), identity)
          Some(V2Settings(journal, hash("--store-id"), seed._1, seed._2, seed._3))
        val fenceFields =
          Set("--completion-fence", "--fence-id", "--fence-phase", "--minimum-depth")
        val completionFence =
          if !fields.keySet.exists(fenceFields) then None
          else
            require(
              mode == "sustained-durable" && fenceFields.subsetOf(fields.keySet),
              "complete fence flags require sustained-durable"
            )
            require(
              fields("--fence-id").matches("[0-9a-f]{64}") && Set("A", "B")(
                fields("--fence-phase")
              ),
              "fence identity and phase"
            )
            val path = Path.of(fields("--completion-fence"))
            require(
              path.isAbsolute && path.normalize == path,
              "absolute normalized fence path required"
            )
            val minimum = integer("--minimum-depth", "0")
            val maximum = integer("--blocks", "0")
            require(
              minimum >= 9 && minimum <= maximum && maximum <= 16 &&
                integer("--events", "64") <= 128 && number(
                  "--bytes",
                  (32L * 1024 * 1024).toString
                ) <= 32L * 1024 * 1024,
              "fenced depth 9..16, events <=128, bytes <=32MiB"
            )
            require(
              (fields("--fence-phase") == "A") == storage._1.contains("create"),
              "fence phase/action mismatch"
            )
            require(
              (fields("--fence-phase") match
                case "A" => minimum == 9 && maximum <= 12
                case "B" => minimum >= 12
                case _   => false
              ),
              "fence phase bounds"
            )
            require(integer("--reconnects", "0") == 0, "fenced readiness is single-session")
            Some(
              CompletionFence.Settings(path, fields("--fence-id"), fields("--fence-phase"), minimum)
            )
        val port = integer("--port", "0")
        require(port >= 1 && port <= 65535, "port range 1..65535")
        val config = new Config(
          Profile,
          Path.of(fields("--bootstrap")),
          port,
          integer("--blocks", "4"),
          integer("--seconds", "60"),
          integer("--events", "64"),
          number("--bytes", (32L * 1024 * 1024).toString),
          integer("--reconnects", "0"),
          mode,
          integer("--rollback-capacity", "8"),
          audit,
          storage._1,
          storage._2,
          storage._3,
          storage._4,
          storage._5,
          storage._6,
          v2,
          completionFence
        )
        require(
          config.policy.valid,
          "policy bounds: bounded blocks 1..8 or sustained blocks 9..256, rollback capacity 1..8, events blocks..256, bytes 1..67108864, seconds 1..120, reconnects 0..4"
        )
        Right(config)
      catch
        case NonFatal(e) => Left(Option(e.getMessage).getOrElse("invalid configuration").take(1024))

  enum Confirmation:
    case Volatile, LoadedVerified, Acknowledged, V2LoadedVerified, V2Acknowledged
  final case class ReceiptReference(path: String, sha256: String)
  final case class EngineView(
      state: CoherentSequence.State,
      confirmation: Confirmation = Confirmation.Volatile,
      confirmedGeneration: Option[Long] = None,
      receipt: Option[ReceiptReference] = None,
      fullClaim: Option[LocalDerivedCheckpoint.Claim] = None,
      binding: Option[ControllerJournalCodec.Binding] = None
  )
  enum Ending:
    case Completed(reason: BoundedValidatorRunner.Stop)
    case StorageFailure(potentiallyOlderThanDisk: Boolean)
    case ReceiptFailure
  final case class EngineOutcome(
      snapshot: EngineView,
      ending: Ending,
      events: Int,
      returnedBytes: Long,
      reconnects: Int,
      cleanupFailure: Option[BoundedValidatorRunner.Stop] = None,
      completion: Option[(CompletionFence.Control[IO], CompletionFence.Accepted)] = None
  ):
    /** Compatibility accessor for volatile callers; rendering always inspects ending too. */
    def reason: BoundedValidatorRunner.Stop = ending match
      case Ending.Completed(reason) => reason
      case Ending.StorageFailure(_) =>
        BoundedValidatorRunner.Stop.Internal("storage failure: cached confirmation only")
      case Ending.ReceiptFailure =>
        BoundedValidatorRunner.Stop.Internal("external acknowledged receipt unavailable")
  object EngineOutcome:
    def volatile(out: BoundedValidatorRunner.Outcome): EngineOutcome =
      EngineOutcome(
        EngineView(out.snapshot.state),
        Ending.Completed(out.reason),
        out.events,
        out.returnedBytes,
        out.reconnects
      )
  trait Engine:
    def snapshot: IO[EngineView]
    def run: IO[EngineOutcome]
  trait EngineFactory:
    /** Hooks run synchronously after the named atomic transition and AFTER releasing any
      * nonreentrant publication gate. Snapshot must be callable without deadlock. Acquisition must
      * not invoke hooks before returning the Engine. The caller awaits run inside use.
      */
    def resource(
        context: SequenceInput.Context,
        peer: Resource[IO, BoundedChainFollower.Peer[IO]],
        policy: BoundedValidatorRunner.Policy,
        onTransition: String => IO[Unit]
    ): Resource[IO, Engine]
  val defaultFactory: EngineFactory = new EngineFactory:
    def resource(
        context: SequenceInput.Context,
        peer: Resource[IO, BoundedChainFollower.Peer[IO]],
        policy: BoundedValidatorRunner.Policy,
        onTransition: String => IO[Unit]
    ): Resource[IO, Engine] =
      BoundedValidatorRunner.resourceObserved[IO](context, peer, policy, onTransition).map {
        runner =>
          new Engine:
            def snapshot = runner.snapshot.map(s => EngineView(s.state))
            def run = runner.run.map(EngineOutcome.volatile)
      }

  final case class Failure(
      stage: String,
      kind: String,
      detail: String,
      lastConfirmed: Option[EngineView] = None,
      potentiallyOlderThanDisk: Boolean = false,
      externalReceiptStale: Boolean = false,
      durableRequested: Boolean = false,
      cleanupFailure: Option[String] = None
  )
  private final case class Abort(failure: Failure) extends RuntimeException
  private final case class ReceiptFailed(view: EngineView)
      extends RuntimeException("acknowledged state has no confirmed external receipt")
  final class Report private[NodeCommand] (
      val config: Config,
      val outcome: EngineOutcome,
      val peerOpens: Int,
      val peerCloses: Int
  )

  /** Explicit resume authority is checked before acquiring the checkpoint backend or any peer. */
  private[lab] def factoryFor(
      config: Config,
      context: SequenceInput.Context,
      acknowledge: Option[ValidatedCheckpoint.Token => IO[NodeDurableReceipts.Reference]] = None
  ): IO[EngineFactory] =
    if config.mode == "sustained-durable" then v2FactoryFor(config, context)
    else if !config.durable then IO.pure(defaultFactory)
    else
      val expected = config.expectedContext.get
      val root = config.store.get; val receipts = config.receipts.get
      for
        _ <- IO.raiseUnless(context.id == expected)(
          Abort(
            Failure(
              "bootstrap",
              "Rejected",
              "independent context mismatch",
              durableRequested = true
            )
          )
        )
        loaded <-
          if config.storeAction.contains("resume") then
            NodeDurableReceipts
              .load(config.resumeReceipt.get, config.resumeSha256.get, expected, root)
              .map(Some(_))
          else IO.pure(None)
      yield new EngineFactory:
        def resource(
            c: SequenceInput.Context,
            peer: Resource[IO, BoundedChainFollower.Peer[IO]],
            policy: BoundedValidatorRunner.Policy,
            onTransition: String => IO[Unit]
        ): Resource[IO, Engine] =
          val record = (tokens: CoherentSequence.PendingTokens) =>
            NodeDurableReceipts.recordPending(receipts, root, tokens)
          val backend = loaded match
            case None => ValidatorTransitions.durableCreate[IO](root, c, 8, 2.seconds, record)
            case Some(input) =>
              ValidatorTransitions
                .durableResume[IO](root, expected, input.token, 20.seconds, 2.seconds, record)
          for
            saved <- Resource.eval(
              Ref.of[IO, Option[(ValidatedCheckpoint.Token, NodeDurableReceipts.Reference)]](
                loaded.map(v => v.token -> v.reference)
              )
            )
            receiptFailure <- Resource.eval(Ref.of[IO, Option[EngineView]](None))
            runner <- DurableValidatorRunner.resource[IO](c, backend, peer, policy, onTransition)
          yield new Engine:
            def metadata(r: NodeDurableReceipts.Reference) =
              ReceiptReference(r.path.toString, r.sha256.hex)
            def mapped(
                value: ValidatorTransitions.ConfirmedState,
                mayRecord: Boolean
            ): IO[EngineView] =
              val (kind, token) = value.confirmation match
                case ValidatorTransitions.Confirmation.Volatile => (Confirmation.Volatile, None)
                case ValidatorTransitions.Confirmation.LoadedVerified(t) =>
                  (Confirmation.LoadedVerified, Some(t))
                case ValidatorTransitions.Confirmation.Acknowledged(t) =>
                  (Confirmation.Acknowledged, Some(t))
                case _ => throw new IllegalStateException("v1 factory received v2 confirmation")
              val base = EngineView(value.state, kind, token.map(_.generation))
              saved.get.flatMap { existing =>
                existing.filter(pair => token.contains(pair._1)) match
                  case Some((_, reference)) =>
                    IO.pure(base.copy(receipt = Some(metadata(reference))))
                  case None if kind == Confirmation.Acknowledged && mayRecord =>
                    acknowledge
                      .fold(NodeDurableReceipts.recordAcknowledged(receipts, root, token.get))(
                        _(token.get)
                      )
                      .timeout(2.seconds)
                      .flatMap(r =>
                        saved.set(Some(token.get -> r)).as(base.copy(receipt = Some(metadata(r))))
                      )
                      .handleErrorWith(_ =>
                        receiptFailure.set(Some(base)) *> IO.raiseError(ReceiptFailed(base))
                      )
                  case _ => IO.pure(base)
              }
            def snapshot: IO[EngineView] = receiptFailure.get.flatMap {
              case Some(view) => IO.raiseError(ReceiptFailed(view))
              case None       => runner.snapshot.flatMap(mapped(_, true))
            }
            def run: IO[EngineOutcome] = runner.run.flatMap { out =>
              receiptFailure.get.flatMap {
                case Some(view) =>
                  IO.pure(
                    EngineOutcome(
                      view,
                      Ending.ReceiptFailure,
                      out.events,
                      out.returnedBytes,
                      out.reconnects,
                      out.cleanupFailure
                    )
                  )
                case None =>
                  out.reason match
                    case DurableValidatorRunner.Stop.StorageFailure(older) =>
                      mapped(out.confirmed, false).map(v =>
                        EngineOutcome(
                          v,
                          Ending.StorageFailure(older),
                          out.events,
                          out.returnedBytes,
                          out.reconnects,
                          out.cleanupFailure
                        )
                      )
                    case DurableValidatorRunner.Stop.Completed(reason) =>
                      mapped(out.confirmed, true)
                        .map(v =>
                          EngineOutcome(
                            v,
                            Ending.Completed(reason),
                            out.events,
                            out.returnedBytes,
                            out.reconnects,
                            out.cleanupFailure
                          )
                        )
                        .handleErrorWith {
                          case ReceiptFailed(view) =>
                            IO.pure(
                              EngineOutcome(
                                view,
                                Ending.ReceiptFailure,
                                out.events,
                                out.returnedBytes,
                                out.reconnects,
                                out.cleanupFailure
                              )
                            )
                          case other => IO.raiseError(other)
                        }
              }
            }

  private[lab] def v2Binding(config: Config): ControllerJournalCodec.Binding =
    val settings = config.v2.get
    ControllerJournalCodec.Binding(
      settings.journal.toString,
      config.store.get.toString,
      ControllerReducer.Store(
        ControllerReducer.Id(settings.storeId.hex),
        ControllerReducer.Id(config.expectedContext.get.hex)
      )
    )

  private[lab] def v2FactoryFor(
      config: Config,
      context: SequenceInput.Context,
      acknowledge: Option[LocalDerivedCheckpoint.Claim => IO[NodeV2Receipts.Reference]] = None
  ): IO[EngineFactory] =
    val settings = config.v2.get
    val binding = v2Binding(config)
    val combined = CombinedLocalV2.Config(binding, config.rollbackCapacity, 20.seconds)
    for
      _ <- IO.raiseUnless(context.id == config.expectedContext.get)(
        Abort(
          Failure("bootstrap", "Rejected", "independent context mismatch", durableRequested = true)
        )
      )
      seed <-
        if config.storeAction.contains("create") then
          NodeV2Receipts
            .seed(
              settings.seedCapture.get,
              settings.seedSha256.get,
              context,
              settings.compactThrough.get
            )
            .handleErrorWith(_ =>
              IO.raiseError(
                Abort(
                  Failure(
                    "seed",
                    "Rejected",
                    "invalid bounded seed capture, pin or compact point",
                    durableRequested = true
                  )
                )
              )
            )
            .map(Some(_))
        else IO.pure(None)
      _ <- IO.raiseUnless(seed.forall(_.originals.size <= config.rollbackCapacity))(
        Abort(
          Failure("seed", "Rejected", "seed exceeds retained capacity", durableRequested = true)
        )
      )
    yield new EngineFactory:
      def resource(
          c: SequenceInput.Context,
          peer: Resource[IO, BoundedChainFollower.Peer[IO]],
          policy: BoundedValidatorRunner.Policy,
          onTransition: String => IO[Unit]
      ): Resource[IO, Engine] =
        val backend = seed match
          case Some(value) => ValidatorTransitions.combinedCreate[IO](combined, value)
          case None        => ValidatorTransitions.combinedResume[IO](combined)
        for
          saved <- Resource.eval(
            Ref.of[IO, Option[(LocalDerivedCheckpoint.Claim, NodeV2Receipts.Reference)]](None)
          )
          receiptFailure <- Resource.eval(Ref.of[IO, Option[EngineView]](None))
          fence <- Resource.eval(
            config.completionFence.traverse(CompletionFence.create[IO](_, c.id.hex, config.blocks))
          )
          runner <- DurableValidatorRunner
            .resource[IO](c, backend, peer, policy, onTransition, fence)
        yield new Engine:
          def mapped(
              value: ValidatorTransitions.ConfirmedState,
              mayRecord: Boolean
          ): IO[EngineView] =
            val (kind, claim) = value.confirmation match
              case ValidatorTransitions.Confirmation.V2LoadedVerified(claim) =>
                (Confirmation.V2LoadedVerified, claim)
              case ValidatorTransitions.Confirmation.V2Acknowledged(claim) =>
                (Confirmation.V2Acknowledged, claim)
              case _ => throw new IllegalStateException("v2 factory received non-v2 confirmation")
            val base = EngineView(
              value.state,
              kind,
              Some(claim.token.generation),
              fullClaim = Some(claim),
              binding = Some(binding)
            )
            saved.get.flatMap {
              case Some((previous, reference)) if previous == claim =>
                IO.pure(
                  base.copy(receipt =
                    Some(ReceiptReference(reference.path.toString, reference.sha256.hex))
                  )
                )
              case _ if kind == Confirmation.V2Acknowledged && mayRecord =>
                acknowledge
                  .fold(
                    NodeV2Receipts
                      .record(config.receipts.get, binding, config.rollbackCapacity, claim)
                  )(_(claim))
                  .timeout(2.seconds)
                  .flatMap { reference =>
                    saved
                      .set(Some(claim -> reference))
                      .as(
                        base.copy(receipt =
                          Some(ReceiptReference(reference.path.toString, reference.sha256.hex))
                        )
                      )
                  }
                  .handleErrorWith(_ =>
                    receiptFailure.set(Some(base)) *> IO.raiseError(ReceiptFailed(base))
                  )
              case _ =>
                IO.pure(base) // LoadedVerified never manufactures a new acknowledged export.
            }
          def snapshot: IO[EngineView] = receiptFailure.get.flatMap {
            case Some(view) => IO.raiseError(ReceiptFailed(view))
            case None       => runner.snapshot.flatMap(mapped(_, true))
          }
          def run: IO[EngineOutcome] = runner.run.flatMap { out =>
            def outcome(view: EngineView, ending: Ending) =
              EngineOutcome(
                view,
                ending,
                out.events,
                out.returnedBytes,
                out.reconnects,
                out.cleanupFailure,
                fence.flatMap(control => out.fence.map(control -> _))
              )
            out.reason match
              case DurableValidatorRunner.Stop.StorageFailure(older) =>
                mapped(out.confirmed, false).map(v => outcome(v, Ending.StorageFailure(older)))
              case DurableValidatorRunner.Stop.Completed(reason) =>
                receiptFailure.get.flatMap {
                  case Some(view) => IO.pure(outcome(view, Ending.ReceiptFailure))
                  case None =>
                    mapped(out.confirmed, true)
                      .map(v => outcome(v, Ending.Completed(reason)))
                      .handleErrorWith {
                        case ReceiptFailed(view) => IO.pure(outcome(view, Ending.ReceiptFailure))
                        case other               => IO.raiseError(other)
                      }
                }
          }

  private def quote(s: String): String = "\"" + s.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  } + "\""
  private def detail(e: Throwable) = Option(e.getMessage).getOrElse(e.getClass.getName).take(1024)
  private def point(p: Option[ChainSync.Point]): String = p match
    case Some(ChainSync.Point.Block(slot, hash)) =>
      s"""{"hash":"${hash.hex}","slot":${slot.value}}"""
    case _ => "null"
  private def intersectionPoint(p: ChainSync.Point): String = p match
    case ChainSync.Point.Origin => """{"origin":true}"""
    case _                      => point(Some(p))
  private def checkedProjection(s: CoherentSequence.State): String =
    val projection = ValidatedRestartCapture.canonical(ValidatedRestartCapture.projection(s))
    require(
      projection.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 16 * 1024 * 1024,
      "checked projection bound"
    )
    projection
  private def state(s: CoherentSequence.State): String =
    val derived = s.derivedAnchorId.fold("null")(id => quote(id.hex))
    s""""contextId":"${s.contextId.hex}","stateId":"${s.id.hex}","revision":${s.revision},"depth":${s.depth},"compactedBlocks":${s.compactedBlocks},"derivedAnchorId":$derived,"retainedBlocks":${s.acquisition.size},"scopedAppliedTip":${point(
        s.scopedAppliedTip
      )},"blockNo":${s.certificates.state.tip.blockNo}"""
  private def observed(v: EngineView): String =
    val status = v.confirmation match
      case Confirmation.Volatile         => "volatile"
      case Confirmation.LoadedVerified   => "loaded-verified"
      case Confirmation.Acknowledged     => "acknowledged"
      case Confirmation.V2LoadedVerified => "v2-loaded-verified"
      case Confirmation.V2Acknowledged   => "v2-acknowledged"
    val generation = v.confirmedGeneration.fold("null")(_.toString)
    val receiptPath = v.receipt.fold("null")(r => quote(r.path))
    val receiptHash = v.receipt.fold("null")(r => quote(r.sha256))
    val storage = v.fullClaim match
      case Some(claim) =>
        s""", "storageVersion":"v2","trustedLocalPrefix":${v.state.trustedLocalPrefix},"fullClaim":${ValidatedRestartCapture
            .canonical(NodeV2Receipts.claimJson(claim))},"storageBinding":${v.binding.fold("null")(
            b => ValidatedRestartCapture.canonical(NodeV2Receipts.bindingJson(b))
          )}"""
      case None =>
        s""", "storageVersion":${
            if v.confirmation == Confirmation.Volatile then "null" else "\"v1\""
          },"trustedLocalPrefix":false"""
    state(
      v.state
    ) + s""", "confirmation":"$status","confirmedGeneration":$generation,"receiptPath":$receiptPath,"receiptSha256":$receiptHash$storage"""
  private def claims(durable: Boolean = false): String =
    s""""bounded":true,"inMemory":${!durable},"adaOnly":true,"nativeScriptsSupported":true,"plutusSupported":false,"bootstrapValidated":false,"fullLedgerValidated":false,"consensusValidated":false,"stateDerivedConsensus":false,"durable":$durable"""
  private[lab] def classification(reason: BoundedValidatorRunner.Stop): (String, String, Boolean) =
    import BoundedValidatorRunner.Stop
    reason match
      case Stop.TargetReached         => ("TargetReached", "scoped-target-reached", true)
      case Stop.EventBudget           => ("EventBudget", "budget-stopped", true)
      case Stop.ByteBudget            => ("ByteBudget", "budget-stopped", true)
      case Stop.TimeBudget            => ("TimeBudget", "budget-stopped", true)
      case Stop.Unsupported(_, _)     => ("Unsupported", "unsupported", false)
      case Stop.Rejected(_, _)        => ("Rejected", "rejected", false)
      case Stop.PeerFailure(_)        => ("PeerFailure", "failed", false)
      case Stop.TransportExhausted(_) => ("TransportExhausted", "failed", false)
      case Stop.CleanupFailed(_)      => ("CleanupFailed", "failed", false)
      case Stop.Internal(_)           => ("Internal", "failed", false)
      case Stop.OutsideRetainedWindow => ("OutsideRetainedWindow", "failed", false)
      case Stop.AlreadyRun            => ("AlreadyRun", "failed", false)
  def exitCode(report: Report): ExitCode = report.outcome.ending match
    case Ending.Completed(reason)
        if classification(reason)._3 && report.outcome.cleanupFailure.isEmpty =>
      ExitCode.Success
    case _ => ExitCode(2)
  def render(report: Report): String =
    val out = report.outcome
    val (kind, status, _) = out.ending match
      case Ending.Completed(reason) => classification(reason)
      case Ending.StorageFailure(_) => ("StorageFailure", "failed", false)
      case Ending.ReceiptFailure    => ("ReceiptFailure", "failed", false)
    val older = out.ending match
      case Ending.StorageFailure(value) => value
      case _                            => false
    val cleanup = out.cleanupFailure.fold("null")(s => quote(classification(s)._1))
    s"""{"scope":"bounded-node-outcome","profile":${quote(report.config.profile)},"mode":${quote(
        report.config.mode
      )},"rollbackCapacity":${report.config.rollbackCapacity},"auditEnabled":${report.config.audit},"networkMagic":$NetworkMagic,"stopped":true,"typedStop":${quote(
        kind
      )},"status":${quote(
        status
      )},"budgetStop":${status == "budget-stopped"},"scopedTargetReached":${out.ending == Ending
        .Completed(BoundedValidatorRunner.Stop.TargetReached)},"caughtUp":false,${observed(
        out.snapshot
      )},"potentiallyOlderThanDisk":$older,"externalReceiptStale":${out.ending == Ending.ReceiptFailure},"cleanupFailure":$cleanup,"events":${out.events},"returnedBytes":${out.returnedBytes},"reconnects":${out.reconnects},"peerOpens":${report.peerOpens},"peerCloses":${report.peerCloses},"peerResourcesFinalized":${report.peerOpens == report.peerCloses && out.cleanupFailure.isEmpty},${claims(
        report.config.durable
      )}}"""
  def renderFailure(failure: Failure): String =
    val stateFields = failure.lastConfirmed.fold("\"stateAvailable\":false")(v =>
      "\"stateAvailable\":true," + observed(v)
    )
    val cleanup = failure.cleanupFailure.fold("null")(quote)
    s"""{"scope":"bounded-node-outcome","stopped":true,"typedStop":${quote(
        failure.kind
      )},"status":"failed","stage":${quote(failure.stage)},"detail":${quote(
        failure.detail.take(1024)
      )},$stateFields,"potentiallyOlderThanDisk":${failure.potentiallyOlderThanDisk},"externalReceiptStale":${failure.externalReceiptStale},"cleanupFailure":$cleanup,"caughtUp":false,"scopedTargetReached":false,${claims(
        failure.durableRequested
      )}}"""

  private def modeMatches(config: Config, view: EngineView, requireReceipt: Boolean): Boolean =
    if config.mode == "sustained-durable" then
      val c = view.fullClaim
      Set(Confirmation.V2LoadedVerified, Confirmation.V2Acknowledged)(view.confirmation) &&
      c.exists(claim =>
        claim.token.contextId == view.state.contextId && claim.token.storeId == config.v2.get.storeId &&
          claim.format == LocalDerivedCheckpoint.Format && claim.profile == Profile && claim.authority == LocalDerivedCheckpoint.Authority &&
          claim.token.sessionId.size == 32 && claim.token.digest.size == 32 && claim.token.generation >= 0 &&
          claim.finalId == view.state.id && claim.revision == view.state.revision &&
          claim.compactedBlocks == view.state.compactedBlocks && view.state.derivedAnchorId.nonEmpty && claim.anchorId.size == 32 &&
          view.confirmedGeneration.contains(claim.token.generation)
      ) &&
      view.binding.contains(v2Binding(config)) &&
      (!requireReceipt || view.confirmation == Confirmation.V2LoadedVerified || view.receipt.nonEmpty)
    else if config.mode == "bounded-durable" then
      Set(Confirmation.LoadedVerified, Confirmation.Acknowledged)(
        view.confirmation
      ) && view.confirmedGeneration.exists(_ >= 0) &&
      (!requireReceipt || view.receipt.nonEmpty) && view.state.compactedBlocks == 0 && view.state.derivedAnchorId.isEmpty && view.fullClaim.isEmpty && view.binding.isEmpty
    else
      view.confirmation == Confirmation.Volatile && view.confirmedGeneration.isEmpty && view.receipt.isEmpty && view.fullClaim.isEmpty && view.binding.isEmpty

  /** Small engine boundary: no concrete runner state, private receipts, or authority tokens escape.
    * Peer operations and progress output remain serial and backpressured by the engine owner.
    */
  def execute(
      config: Config,
      context: SequenceInput.Context,
      peer: Resource[IO, BoundedChainFollower.Peer[IO]],
      emit: String => IO[Unit],
      factory: EngineFactory = defaultFactory
  ): IO[Either[Failure, Report]] =
    def output(line: String) = emit(line).handleErrorWith(e =>
      IO.raiseError(Abort(Failure("output", "Rejected", detail(e))))
    )
    val work = for
      current <- Deferred[IO, Engine]
      opened <- Ref.of[IO, Int](0); closed <- Ref.of[IO, Int](0)
      initialIntersection <- Ref.of[IO, Boolean](true)
      pendingHeader <- Ref.of[IO, Option[(ChainSync.Point, Bytes)]](None)
      auditBytes <- Ref.of[IO, Long](0L)
      rollbackProjectionBytes <- Ref.of[IO, Long](0L)
      carriedOutcome <- Ref.of[IO, Option[EngineOutcome]](None)
      carriedFailure <- Ref.of[IO, Option[Failure]](None)

      download = (phase: String, cursor: Option[ChainSync.Point], size: Int) =>
        current.get.flatMap(_.snapshot).flatMap { s =>
          output(s"""{"record":"node-download","phase":${quote(phase)},"downloadCursor":${point(
              cursor
            )},"payloadBytes":$size,"downloadIsApplied":false,${observed(s)}}""")
        }
      owned = Resource
        .makeFull[IO, (BoundedChainFollower.Peer[IO], IO[Unit])](poll =>
          poll(peer.allocated).flatTap(_ => opened.update(_ + 1))
        ) { case (_, release) => release *> closed.update(_ + 1) }
        .map(_._1)
      traced = owned.map { underlying =>
        new BoundedChainFollower.Peer[IO]:
          def intersect(candidates: Vector[ChainSync.Point]) =
            pendingHeader.set(None) *>
              IO.raiseUnless(candidates.size <= 9)(
                Abort(Failure("intersection", "Internal", "offered point bound"))
              ) *>
              output(
                s"""{"record":"node-intersection-offered","offeredPoints":${candidates
                    .map(intersectionPoint)
                    .mkString("[", ",", "]")},"acquisitionOnly":true,"appliedClaim":false}"""
              ) *> underlying.intersect(candidates).flatTap { selected =>
                output(
                  s"""{"record":"node-intersection-selected","selectedPoint":${intersectionPoint(
                      selected
                    )},"offeredMatch":${candidates
                      .contains(selected)},"acquisitionOnly":true,"appliedClaim":false}"""
                )
              }
          def next = pendingHeader.get
            .flatMap {
              case Some(_) =>
                IO.raiseError[BoundedChainFollower.Event](
                  Abort(Failure("fetch", "Rejected", "unfetched original header before next event"))
                )
              case None => underlying.next
            }
            .flatTap {
              case BoundedChainFollower.Event.Forward(envelope) =>
                val announced = ReferenceCaptureCommand
                  .header(envelope)
                  .toOption
                  .map(h =>
                    ChainSync.Point.Block(ChainSync.UInt64.from(h.slot).toOption.get, h.hash)
                  )
                pendingHeader.set(announced.map(_ -> envelope)) *> download(
                  "announced",
                  announced,
                  envelope.size
                )
              case BoundedChainFollower.Event.Backward(p) =>
                download("rollback-announced", Some(p), 0)
              case BoundedChainFollower.Event.Await => IO.unit
            }
          def fetch(p: ChainSync.Point) = pendingHeader.getAndSet(None).flatMap {
            case Some((expected, envelope)) if expected == p =>
              underlying.fetch(p).flatTap { bytes =>
                download("fetched", Some(p), bytes.size) *>
                  (if !config.audit then IO.unit
                   else if envelope.size <= 65535 && bytes.size > 0 && bytes.size <= 1048576 then
                     auditBytes.updateAndGet(_ + envelope.size.toLong + bytes.size).flatMap {
                       total =>
                         // Includes one final returned object beyond the engine's byte budget. The
                         // runner then reports ByteBudget; this row still makes no applied claim.
                         IO.raiseUnless(total <= config.bytes + 65535L + 1048576L)(
                           Abort(Failure("audit", "Rejected", "cumulative audit byte bound"))
                         ) *>
                           output(
                             s"""{"record":"transfer-range-block","headerEnvelopeHex":"${envelope.hex}","rawBlockHex":"${bytes.hex}","acquisitionOnly":true,"appliedClaim":false}"""
                           )
                     }
                   else
                     output(
                       """{"record":"node-audit-omitted","reason":"object-size-bound","appliedClaim":false}"""
                     ))
              }
            case _ =>
              IO.raiseError(
                Abort(
                  Failure(
                    "fetch",
                    "Rejected",
                    "fetch point has no matching announced original header"
                  )
                )
              )
          }
      }
      transition = (label: String) =>
        if label == "fence-ready" then
          config.completionFence.traverse_ { f =>
            current.get.flatMap(_.snapshot).flatMap { initial =>
              output(
                s"""{"record":"node-fence-ready","fenceId":"${f.id}","phase":"${f.phase}","minimumDepth":${f.minimum},"maximumDepth":${config.blocks},${observed(
                    initial
                  )}}"""
              )
            }
          }
        else if label == "after-publish" then
          current.get
            .flatMap(_.snapshot)
            .flatMap { s =>
              val block = SequenceInput
                .block(s.state.acquisition.originals.last)
                .fold(f => throw Abort(Failure("applied", "Internal", f.toString)), identity)
              output(s"""{"record":"node-applied",${observed(
                  s
                )},"transactionCount":${block.transactionMemos.size},${claims(
                  config.durable
                )}}""")
            }
        else if label == "after-anchor-advance" then
          current.get
            .flatMap(_.snapshot)
            .flatMap(s =>
              output(s"""{"record":"node-anchor-advance",${observed(s)},${claims(
                  config.durable
                )}}""")
            )
        else if label == "after-rollback" then
          initialIntersection.getAndSet(false).flatMap { first =>
            current.get
              .flatMap(_.snapshot)
              .flatMap { s =>
                val projection =
                  if !config.durable || !config.audit then IO.pure("")
                  else
                    IO(checkedProjection(s.state)).flatMap { value =>
                      val size = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                      rollbackProjectionBytes.modify { used =>
                        if used + size <= 64L * 1024 * 1024 then
                          (used + size, s""","projection":$value""")
                        else (used, ""","projectionOmitted":"cumulative-size-bound"""")
                      }
                    }
                projection.flatMap { extra =>
                  output(s"""{"record":"node-rollback","initialIntersection":$first,${observed(
                      s
                    )}$extra,${claims(config.durable)}}""")
                }
              }
          }
        else IO.unit
      session <- factory
        .resource(context, traced, config.policy, transition)
        .use { engine =>
          val action = for
            _ <- current.complete(engine)
            initial <- engine.snapshot
            _ <- IO.raiseUnless(
              initial.state.contextId == context.id && modeMatches(config, initial, true)
            )(
              Abort(
                Failure(
                  "engine",
                  "Internal",
                  "bootstrap context or confirmation differs from requested mode"
                )
              )
            )
            _ <- output(s"""{"record":"node-bootstrap","profile":${quote(
                config.profile
              )},"mode":${quote(
                config.mode
              )},"rollbackCapacity":${config.rollbackCapacity},"auditEnabled":${config.audit},"networkMagic":$NetworkMagic,"sourceBound":true,"suppliedAnchor":${point(
                Some(
                  ChainSync.Point.Block(
                    ChainSync.UInt64.from(context.certificateSeed.tip.slot).toOption.get,
                    context.certificateSeed.tip.hash
                  )
                )
              )},"retainedAnchor":${point(Some(initial.state.acquisition.anchor))},${observed(
                initial
              )},"potentiallyOlderThanDisk":false,"externalReceiptStale":false,${claims(
                config.durable
              )}}""")
            _ <-
              if initial.confirmation == Confirmation.LoadedVerified || initial.confirmation == Confirmation.V2LoadedVerified
              then
                IO {
                  val projection = checkedProjection(initial.state)
                  s"""{"record":"node-loaded",${observed(
                      initial
                    )},"projection":$projection,"potentiallyOlderThanDisk":false,"externalReceiptStale":false,${claims(
                      true
                    )}}"""
                }.flatMap(output)
              else IO.unit
            out <- engine.run
            _ <- carriedOutcome.set(Some(out))
          yield out
          action.handleErrorWith {
            case ReceiptFailed(view) =>
              val failure = Failure(
                "receipt",
                "ReceiptFailure",
                "acknowledged state has no confirmed external receipt",
                Some(view),
                externalReceiptStale = true,
                durableRequested = true
              )
              carriedFailure.set(Some(failure)) *> IO.raiseError(Abort(failure))
            case error => IO.raiseError(error)
          }
        }
        .attempt
      outcome <- session match
        case Right(out) => IO.pure(out)
        case Left(error) =>
          (carriedOutcome.get, carriedFailure.get).tupled.flatMap {
            case (Some(out), _) =>
              val cleanup =
                BoundedValidatorRunner.Stop.CleanupFailed("engine resource finalization failed")
              val ending = out.ending match
                case Ending.Completed(_) => Ending.Completed(cleanup)
                case uncertain           => uncertain
              IO.pure(out.copy(ending = ending, cleanupFailure = Some(cleanup)))
            case (_, Some(failure)) =>
              IO.raiseError(
                Abort(
                  failure.copy(cleanupFailure =
                    if error.isInstanceOf[Abort] then None
                    else Some("engine resource finalization failed")
                  )
                )
              )
            case _ => IO.raiseError(error)
          }
      opens <- opened.get; closes <- closed.get
      _ <- IO.raiseUnless(
        outcome.snapshot.state.contextId == context.id && modeMatches(
          config,
          outcome.snapshot,
          outcome.ending.isInstanceOf[Ending.Completed]
        )
      )(
        Abort(
          Failure(
            "engine",
            "Internal",
            "result context or confirmation differs from requested mode"
          )
        )
      )
      _ <- IO.raiseUnless(
        outcome.ending != Ending.Completed(
          BoundedValidatorRunner.Stop.TargetReached
        ) || (if config.completionFence.nonEmpty then
                outcome.completion
                  .exists((_, a) => CompletionFence.matches(a.target, outcome.snapshot.state))
              else outcome.snapshot.state.depth >= config.blocks)
      )(Abort(Failure("engine", "Internal", "target result lacks requested scoped prefix")))
      effective =
        if opens == closes then outcome
        else
          val cleanup =
            BoundedValidatorRunner.Stop.CleanupFailed("peer resource finalization incomplete")
          val ending = outcome.ending match
            case Ending.Completed(_) => Ending.Completed(cleanup)
            case uncertain           => uncertain
          outcome.copy(
            ending = ending,
            cleanupFailure = outcome.cleanupFailure.orElse(Some(cleanup))
          )
      _ <-
        if config.audit && opens == closes && effective.cleanupFailure.isEmpty && effective.ending
            .isInstanceOf[Ending.Completed]
        then
          IO(NodeAuditCommand.stateRecord(effective.snapshot.state)).flatMap { record =>
            IO.raiseUnless(
              record.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 16 * 1024 * 1024
            )(
              Abort(Failure("audit", "Rejected", "final projection exceeds 16 MiB bound"))
            ) *> output(record)
          }
        else IO.unit
      _ <-
        if effective.ending == Ending.Completed(BoundedValidatorRunner.Stop.TargetReached) &&
          effective.cleanupFailure.isEmpty && opens == closes
        then
          effective.completion.traverse_ { (control, accepted) =>
            control.verify(accepted) *> IO {
              val t = accepted.target
              val projection = checkedProjection(effective.snapshot.state)
              val digest =
                CompletionFence.sha256(projection.getBytes(java.nio.charset.StandardCharsets.UTF_8))
              s"""{"record":"node-fence-ack","fenceId":"${t.id}","phase":"${t.phase}","fenceSha256":"${accepted.sha256}","slot":${t.slot},"hash":"${t.hash}","projectionSha256":"$digest","resourcesFinalized":true,${observed(
                  effective.snapshot
                )}}"""
            }.flatMap(output)
          }
        else IO.unit
    yield new Report(config, effective, opens, closes)
    work.attempt.map {
      case Right(report) => Right(report)
      case Left(Abort(failure)) =>
        Left(
          failure.copy(durableRequested = failure.durableRequested || config.durable)
        )
      case Left(error) =>
        Left(
          Failure(
            "engine-resource",
            "Internal",
            if config.durable then "durable engine terminated" else detail(error),
            durableRequested = config.durable
          )
        )
    }

  private[lab] def withCancellationStatus[A](
      work: IO[A],
      emit: String => IO[Unit],
      durableRequested: Boolean = false
  ): IO[A] =
    // This outer finalizer runs after lexical engine/peer cleanup. It never reads potentially
    // poisoned engine state and makes no successful-finalization claim when cleanup failed.
    work.onCancel(
      emit(
        s"""{"scope":"bounded-node-outcome","stopped":true,"typedStop":"Cancelled","status":"cancelled","stateAvailable":false,"caughtUp":false,"scopedTargetReached":false,${claims(
            durableRequested
          )}}"""
      ).timeout(1.second).attempt.void
    )

  def run(args: List[String]): IO[ExitCode] = Config.parse(args) match
    case Left(error) =>
      IO.println(renderFailure(Failure("configuration", "Rejected", error))).attempt.as(ExitCode(2))
    case Right(config) =>
      val work = for
        context <- IO
          .blocking(SequenceInput.load(config.bootstrap))
          .flatMap(r =>
            IO.fromEither(r.left.map {
              case SequenceInput.Failure.Unsupported(feature) =>
                Abort(Failure("bootstrap", "Unsupported", feature))
              case SequenceInput.Failure.Rejected(stage, reason) =>
                Abort(Failure("bootstrap/" + stage, "Rejected", reason))
            })
          )
        address <- IO.fromEither(
          ReferenceHandshakeCommand
            .options(List(config.port.toString, NetworkMagic.toString))
            .left
            .map(s => Abort(Failure("configuration", "Rejected", s)))
        )
        limits <- IO.fromEither(
          TcpLimits.checked().left.map(s => Abort(Failure("transport", "Rejected", s)))
        )
        peer = BoundedChainFollower.sessions[IO](
          AsyncTcpTransport.resource[IO](address._1, limits),
          NetworkMagic,
          30.seconds
        )
        factory <- factoryFor(config, context)
        result <- execute(config, context, peer, IO.println, factory)
      yield result
      withCancellationStatus(
        work
          .timeout((config.seconds + (if config.durable then 40 else 15)).seconds)
          .handleError {
            case Abort(failure) =>
              Left(
                failure.copy(durableRequested = failure.durableRequested || config.durable)
              )
            case error =>
              Left(
                Failure(
                  "command",
                  "Internal",
                  if config.durable then "durable command terminated"
                  else detail(error),
                  durableRequested = config.durable
                )
              )
          }
          .flatMap {
            case Right(report) => IO.println(render(report)).as(exitCode(report))
            case Left(failure) => IO.println(renderFailure(failure)).as(ExitCode(2))
          }
          .handleError(_ => ExitCode(2)),
        IO.println,
        config.durable
      )
