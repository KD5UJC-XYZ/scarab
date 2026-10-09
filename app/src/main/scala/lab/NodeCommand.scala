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
      val audit: Boolean
  ):
    def policy: BoundedValidatorRunner.Policy = BoundedValidatorRunner.Policy(
      target = blocks,
      maxEvents = events,
      reconnects = reconnects,
      maxBytes = bytes,
      duration = seconds.seconds,
      advanceWindow = mode == "sustained-volatile",
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
      "--audit"
    )

    /** Entire grammar and all bounds are checked before any filesystem or socket operation. */
    def parse(args: List[String]): Either[String, Config] =
      try
        require(
          args != null && args.nonEmpty && args.size <= 22 && args.size % 2 == 0,
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
          case "sustained-volatile" =>
            require(
              fields.contains("--rollback-capacity"),
              "sustained-volatile requires explicit --rollback-capacity 1..8"
            )
            require(
              fields.contains("--blocks") && integer("--blocks", "0") > 8,
              "sustained-volatile requires explicit --blocks 9..256"
            )
          case "sustained-durable" =>
            throw new IllegalArgumentException(
              "unsupported sustained-durable combination: checkpoint v1 cannot encode derived anchors"
            )
          case "bounded-durable" =>
            throw new IllegalArgumentException("bounded-durable is not integrated")
          case _ => throw new IllegalArgumentException("unsupported node mode")
        val audit = fields.getOrElse("--audit", "false") match
          case "true"  => true
          case "false" => false
          case _       => throw new IllegalArgumentException("--audit requires true or false")
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
          audit
        )
        require(
          config.policy.valid,
          "policy bounds: bounded blocks 1..8 or sustained blocks 9..256, rollback capacity 1..8, events blocks..256, bytes 1..67108864, seconds 1..120, reconnects 0..4"
        )
        Right(config)
      catch
        case NonFatal(e) => Left(Option(e.getMessage).getOrElse("invalid configuration").take(1024))

  trait Engine:
    def snapshot: IO[CoherentSequence.Snapshot]
    def run: IO[BoundedValidatorRunner.Outcome]
  trait EngineFactory:
    /** Hooks run synchronously after the named atomic transition and AFTER releasing any
      * nonreentrant publication gate: Engine.snapshot must be callable from a hook without
      * deadlock. They receive no mutation authority. Resource acquisition must not invoke hooks
      * before returning the Engine to its caller. The caller awaits run inside this Resource's use
      * scope.
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
            def snapshot = runner.snapshot
            def run = runner.run
      }

  final case class Failure(stage: String, kind: String, detail: String)
  private final case class Abort(failure: Failure) extends RuntimeException
  final class Report private[NodeCommand] (
      val config: Config,
      val outcome: BoundedValidatorRunner.Outcome,
      val peerOpens: Int,
      val peerCloses: Int
  )
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
  private def state(s: CoherentSequence.State): String =
    val derived = s.derivedAnchorId.fold("null")(id => quote(id.hex))
    s""""contextId":"${s.contextId.hex}","stateId":"${s.id.hex}","revision":${s.revision},"depth":${s.depth},"compactedBlocks":${s.compactedBlocks},"derivedAnchorId":$derived,"retainedBlocks":${s.acquisition.size},"scopedAppliedTip":${point(
        s.scopedAppliedTip
      )},"blockNo":${s.certificates.state.tip.blockNo}"""
  private val claims =
    "\"bounded\":true,\"inMemory\":true,\"adaOnly\":true,\"nativeScriptsSupported\":true,\"plutusSupported\":false,\"bootstrapValidated\":false,\"fullLedgerValidated\":false,\"consensusValidated\":false,\"stateDerivedConsensus\":false,\"durable\":false"
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
  def exitCode(report: Report): ExitCode =
    if classification(report.outcome.reason)._3 then ExitCode.Success else ExitCode(2)
  def render(report: Report): String =
    val out = report.outcome
    val (kind, status, _) = classification(out.reason)
    s"""{"scope":"bounded-node-outcome","profile":${quote(
        report.config.profile
      )},"mode":${quote(
        report.config.mode
      )},"rollbackCapacity":${report.config.rollbackCapacity},"auditEnabled":${report.config.audit},"networkMagic":$NetworkMagic,"stopped":true,"typedStop":${quote(
        kind
      )},"status":${quote(
        status
      )},"budgetStop":${status == "budget-stopped"},"scopedTargetReached":${out.reason == BoundedValidatorRunner.Stop.TargetReached},"caughtUp":false,"reason":${quote(
        out.reason.toString.take(1024)
      )},${state(
        out.snapshot.state
      )},"events":${out.events},"returnedBytes":${out.returnedBytes},"reconnects":${out.reconnects},"peerOpens":${report.peerOpens},"peerCloses":${report.peerCloses},"peerResourcesFinalized":${report.peerOpens == report.peerCloses},$claims}"""
  def renderFailure(failure: Failure): String =
    s"""{"scope":"bounded-node-outcome","stopped":true,"typedStop":${quote(
        failure.kind
      )},"status":"failed","stage":${quote(failure.stage)},"detail":${quote(
        failure.detail.take(1024)
      )},"caughtUp":false,"scopedTargetReached":false,$claims}"""

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

      download = (phase: String, cursor: Option[ChainSync.Point], size: Int) =>
        current.get.flatMap(_.snapshot).flatMap { s =>
          output(s"""{"record":"node-download","phase":${quote(phase)},"downloadCursor":${point(
              cursor
            )},"payloadBytes":$size,"downloadIsApplied":false,${state(s.state)}}""")
        }
      owned = Resource
        .makeFull[IO, (BoundedChainFollower.Peer[IO], IO[Unit])](poll =>
          poll(peer.allocated).flatTap(_ => opened.update(_ + 1))
        ) { case (_, release) => release *> closed.update(_ + 1) }
        .map(_._1)
      traced = owned.map { underlying =>
        new BoundedChainFollower.Peer[IO]:
          def intersect(candidates: Vector[ChainSync.Point]) =
            pendingHeader.set(None) *> underlying.intersect(candidates)
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
        if label == "after-publish" then
          current.get
            .flatMap(_.snapshot)
            .flatMap { s =>
              val block = SequenceInput
                .block(s.state.acquisition.originals.last)
                .fold(f => throw Abort(Failure("applied", "Internal", f.toString)), identity)
              output(s"""{"record":"node-applied",${state(
                  s.state
                )},"transactionCount":${block.transactionMemos.size},$claims}""")
            }
        else if label == "after-anchor-advance" then
          current.get
            .flatMap(_.snapshot)
            .flatMap(s => output(s"""{"record":"node-anchor-advance",${state(s.state)},$claims}"""))
        else if label == "after-rollback" then
          initialIntersection.getAndSet(false).flatMap { first =>
            current.get
              .flatMap(_.snapshot)
              .flatMap(s =>
                output(s"""{"record":"node-rollback","initialIntersection":$first,${state(
                    s.state
                  )},$claims}""")
              )
          }
        else IO.unit
      outcome <- factory.resource(context, traced, config.policy, transition).use { engine =>
        for
          _ <- current.complete(engine)
          initial <- engine.snapshot
          _ <- IO.raiseUnless(initial.state.contextId == context.id)(
            Abort(Failure("engine", "Internal", "foreign bootstrap context"))
          )
          _ <- output(s"""{"record":"node-bootstrap","profile":${quote(
              config.profile
            )},"mode":${quote(
              config.mode
            )},"rollbackCapacity":${config.rollbackCapacity},"auditEnabled":${config.audit},"networkMagic":$NetworkMagic,"sourceBound":true,"suppliedAnchor":${point(
              Some(initial.state.acquisition.anchor)
            )},${state(initial.state)},$claims}""")
          out <- engine.run
        yield out
      }
      opens <- opened.get; closes <- closed.get
      _ <- IO.raiseUnless(outcome.snapshot.state.contextId == context.id)(
        Abort(Failure("engine", "Internal", "foreign result context"))
      )
      _ <- IO.raiseUnless(
        outcome.reason != BoundedValidatorRunner.Stop.TargetReached || outcome.snapshot.state.depth >= config.blocks
      )(Abort(Failure("engine", "Internal", "target result lacks requested scoped prefix")))
      effective =
        if opens == closes then outcome
        else
          outcome.copy(reason =
            BoundedValidatorRunner.Stop.CleanupFailed("peer resource finalization incomplete")
          )
      _ <-
        if config.audit && opens == closes then
          IO(NodeAuditCommand.stateRecord(effective.snapshot.state)).flatMap { record =>
            IO.raiseUnless(
              record.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 16 * 1024 * 1024
            )(
              Abort(Failure("audit", "Rejected", "final projection exceeds 16 MiB bound"))
            ) *> output(record)
          }
        else IO.unit
    yield new Report(config, effective, opens, closes)
    work.attempt.map {
      case Right(report)        => Right(report)
      case Left(Abort(failure)) => Left(failure)
      case Left(error)          => Left(Failure("engine-resource", "Internal", detail(error)))
    }

  private[lab] def withCancellationStatus[A](work: IO[A], emit: String => IO[Unit]): IO[A] =
    // This outer finalizer runs after lexical engine/peer cleanup. It never reads potentially
    // poisoned engine state and makes no successful-finalization claim when cleanup failed.
    work.onCancel(
      emit(
        s"""{"scope":"bounded-node-outcome","stopped":true,"typedStop":"Cancelled","status":"cancelled","stateAvailable":false,"caughtUp":false,"scopedTargetReached":false,$claims}"""
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
        result <- execute(config, context, peer, IO.println)
      yield result
      withCancellationStatus(
        work
          .timeout((config.seconds + 15).seconds)
          .handleError {
            case Abort(failure) => Left(failure)
            case error          => Left(Failure("command", "Internal", detail(error)))
          }
          .flatMap {
            case Right(report) => IO.println(render(report)).as(exitCode(report))
            case Left(failure) => IO.println(renderFailure(failure)).as(ExitCode(2))
          }
          .handleError(_ => ExitCode(2)),
        IO.println
      )
