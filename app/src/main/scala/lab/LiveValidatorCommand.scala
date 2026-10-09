// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, ExitCode, IO, Ref, Resource}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.network.{AsyncTcpTransport, ChainSync, TcpLimits}
import scala.concurrent.duration.*

/** Live in-memory observation followed by independent offline replay and an external post oracle.
  * Cursor notifications describe downloaded data, never ledger or consensus acceptance.
  */
object LiveValidatorCommand:
  private[lab] val LivePolicy =
    BoundedValidatorRunner.Policy(target = 4, reconnects = 0, duration = 120.seconds)
  enum Failure:
    case Online(reason: BoundedValidatorRunner.Stop)
    case Rejected(stage: String, detail: String)
    case Unsupported(stage: String, feature: String)
  private final case class Abort(failure: Failure) extends RuntimeException
  final class Summary private[LiveValidatorCommand] (
      val outcome: BoundedValidatorRunner.Outcome,
      val report: CoherentSequenceCommand.Report,
      val peerOpens: Int,
      val peerCloses: Int,
      val transportOpens: Int,
      val transportCloses: Int,
      val transportCounted: Boolean,
      val rollbackEvents: Int
  )
  private def quote(s: String): String = "\"" + s.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  } + "\""
  private def errorText(t: Throwable): String =
    Option(t.getMessage).getOrElse(t.getClass.getName).take(1024)
  private def stopName(stop: BoundedValidatorRunner.Stop): String = stop match
    case BoundedValidatorRunner.Stop.Unsupported(_, _)     => "Unsupported"
    case BoundedValidatorRunner.Stop.Rejected(_, _)        => "Rejected"
    case BoundedValidatorRunner.Stop.PeerFailure(_)        => "PeerFailure"
    case BoundedValidatorRunner.Stop.TransportExhausted(_) => "TransportExhausted"
    case BoundedValidatorRunner.Stop.CleanupFailed(_)      => "CleanupFailed"
    case BoundedValidatorRunner.Stop.Internal(_)           => "Internal"
    case other                                             => other.toString
  private def stateFields(s: CoherentSequence.State): String =
    val tip = s.certificates.state.tip
    val scoped = scopedPoint(s.scopedAppliedTip)
    s""""pointHash":"${tip.hash.hex}","slot":${tip.slot},"blockNo":${tip.blockNo},"revision":${s.revision},"retainedBlocks":${s.acquisition.size},"stateId":"${s.id.hex}","scopedAppliedTip":$scoped"""
  private def pointFields(point: Option[ChainSync.Point]): String = point match
    case Some(ChainSync.Point.Block(slot, hash)) =>
      s""""pointHash":"${hash.hex}","slot":${slot.value}"""
    case _ => "\"pointHash\":null,\"slot\":null"
  private def scopedPoint(p: Option[ChainSync.Point]): String =
    p.fold("null")(_ => "{" + pointFields(p) + "}")
  private def point(o: Bytes): Option[ChainSync.Point] = ReferenceCaptureCommand
    .header(o)
    .toOption
    .map(h => ChainSync.Point.Block(ChainSync.UInt64.from(h.slot).toOption.get, h.hash))

  /** Count only successful allocations/releases. A failed release cannot look finalized. */
  private[lab] def counted[A](
      resource: Resource[IO, A],
      opens: Ref[IO, Int],
      closes: Ref[IO, Int]
  ): Resource[IO, A] =
    Resource
      .makeFull[IO, (A, IO[Unit])](poll =>
        poll(resource.allocated).flatTap(_ => opens.update(_ + 1))
      ) { case (_, release) =>
        release *> closes.update(_ + 1)
      }
      .map(_._1)
  private[lab] def sameTuple(a: CoherentSequence.State, b: CoherentSequence.State): Boolean =
    def eligibility(s: CoherentSequence.State) = s.eligibility.map(e =>
      (
        e.contextId,
        e.headers.map(h => (h.headerHash, h.leaderValue, h.stake.numerator, h.stake.denominator))
      )
    )
    a.id == b.id && a.contextId == b.contextId && a.scopedAppliedTip == b.scopedAppliedTip &&
    a.acquisition.anchor == b.acquisition.anchor && a.acquisition.originals == b.acquisition.originals &&
    a.certificates.state.id == b.certificates.state.id && a.certificates.state.tip == b.certificates.state.tip &&
    a.certificates.state.counters == b.certificates.state.counters &&
    a.nonces.id == b.nonces.id && a.nonces.fields == b.nonces.fields &&
    a.nonces.lastSlot == b.nonces.lastSlot && a.nonces.certificateStateId == b.nonces.certificateStateId &&
    eligibility(a) == eligibility(b) && a.ledger.id == b.ledger.id &&
    a.ledger.checkpointId == b.ledger.checkpointId && a.ledger.environment.id == b.ledger.environment.id &&
    a.ledger.outputMap == b.ledger.outputMap && a.ledger.fees == b.ledger.fees && a.ledger.slot == b.ledger.slot

  /** The supplier is not evaluated until the runner has stopped and all owned resources released.
    * Tests inject a bounded scripted Peer and a controlled supplier; production uses fixed policy.
    */
  private[lab] def observe(
      context: SequenceInput.Context,
      peer: Resource[IO, BoundedChainFollower.Peer[IO]],
      oracle: IO[Either[CoherentSequenceCommand.Failure, CoherentSequenceCommand.Report]],
      emit: String => IO[Unit],
      policy: BoundedValidatorRunner.Policy = LivePolicy,
      transportCounts: Option[IO[(Int, Int)]] = None
  ): IO[Either[Failure, Summary]] =
    def output(line: String) = emit(line).handleErrorWith(e =>
      IO.raiseError(Abort(Failure.Rejected("callback", errorText(e))))
    )
    val work = for
      runnerReady <- Deferred[IO, BoundedValidatorRunner.Runner[IO]]
      readySent <- Ref.of[IO, Boolean](false)
      opens <- Ref.of[IO, Int](0); closes <- Ref.of[IO, Int](0)
      announced <- Ref.of[IO, Option[ChainSync.Point]](None)
      fetched <- Ref.of[IO, Option[ChainSync.Point]](None)
      rollbacks <- Ref.of[IO, Int](0)
      download = (phase: String, p: Option[ChainSync.Point], payload: Int) =>
        runnerReady.get.flatMap(_.snapshot).flatMap { current =>
          output(s"""{"record":"live-validator-download","phase":${quote(phase)},${pointFields(
              p
            )},"payloadBytes":$payload,"appliedClaim":false,"scopedAppliedTip":${scopedPoint(
              current.state.scopedAppliedTip
            )},"appliedRevision":${current.state.revision},"appliedStateId":"${current.state.id.hex}"}""")
        }
      traced = counted(peer, opens, closes).map { underlying =>
        new BoundedChainFollower.Peer[IO]:
          def intersect(candidates: Vector[ChainSync.Point]) = underlying.intersect(candidates)
          def next = underlying.next.flatTap {
            case BoundedChainFollower.Event.Forward(envelope) =>
              val p = point(envelope)
              announced.set(p) *> download("announced", p, envelope.size)
            case BoundedChainFollower.Event.Backward(p) =>
              rollbacks.update(_ + 1) *> announced.set(Some(p)) *>
                download("rollback-announced", Some(p), 0)
            case BoundedChainFollower.Event.Await => IO.unit
          }
          def fetch(p: ChainSync.Point) = underlying.fetch(p).flatTap { bytes =>
            fetched.set(Some(p)) *> download("fetched", Some(p), bytes.size)
          }
      }
      hook = (label: String) =>
        if label == "after-rollback" then
          readySent.getAndSet(true).flatMap { sent =>
            if sent then IO.unit
            else
              runnerReady.get
                .flatMap(_.snapshot)
                .flatMap(s =>
                  output(s"""{"record":"live-validator-ready",${stateFields(
                      s.state
                    )},"intersectionAccepted":true,"coordinatorChecked":true}""")
                )
          }
        else if label == "after-publish" then
          runnerReady.get.flatMap(_.snapshot).flatMap { s =>
            val last = SequenceInput
              .block(s.state.acquisition.originals.last)
              .fold(f => throw Abort(Failure.Rejected("applied-record", f.toString)), identity)
            output(s"""{"record":"live-validator-applied",${stateFields(
                s.state
              )},"transactionCount":${last.transactionMemos.size}}""")
          }
        else IO.unit
      out <- BoundedValidatorRunner.resourceObserved[IO](context, traced, policy, hook).use {
        runner =>
          runnerReady.complete(runner) *> runner.run
      }
      po <- opens.get; pc <- closes.get
      counts <- transportCounts.getOrElse(IO.pure((0, 0)))
      (to, tc) = counts
      finalized = po == pc && (transportCounts.isEmpty || (to > 0 && to == tc))
      announcedPoint <- announced.get; fetchedPoint <- fetched.get
      rollbackCount <- rollbacks.get
      _ <- out.snapshot.state.acquisition.originals.traverse_(o =>
        output(
          s"""{"record":"transfer-range-block","headerEnvelopeHex":"${o.envelope.hex}","rawBlockHex":"${o.block.hex}"}"""
        )
      )
      _ <- output(s"""{"record":"live-validator-stop","typedStop":${quote(
          stopName(out.reason)
        )},${stateFields(
          out.snapshot.state
        )},"events":${out.events},"returnedBytes":${out.returnedBytes},"reconnects":${out.reconnects},"rollbackEvents":$rollbackCount,"peerOpens":$po,"peerCloses":$pc,"transportOpens":$to,"transportCloses":$tc,"transportCounted":${transportCounts.nonEmpty},"resourcesFinalized":$finalized,"announcedCursor":{${pointFields(
          announcedPoint
        )}},"fetchedCursor":{${pointFields(fetchedPoint)}}}""")
      _ <- IO.raiseUnless(out.reason == BoundedValidatorRunner.Stop.TargetReached)(
        Abort(Failure.Online(out.reason))
      )
      _ <- IO.raiseUnless(finalized && po > 0)(
        Abort(Failure.Rejected("cleanup", "owned resources not finalized"))
      )
      // First evaluation of post-state supplier, after authoritative online snapshot and finalization.
      report <- oracle.flatMap(r =>
        IO.fromEither(r.left.map {
          case CoherentSequenceCommand.Failure.Unsupported(stage, feature) =>
            Abort(Failure.Unsupported(stage, feature))
          case CoherentSequenceCommand.Failure.Rejected(stage, detail) =>
            Abort(Failure.Rejected(stage, detail))
        })
      )
      _ <- IO.raiseUnless(
        report.contextId == context.id && sameTuple(out.snapshot.state, report.finalState)
      )(
        Abort(
          Failure.Rejected(
            "online-offline-tuple",
            "online tuple differs from independent oracle replay"
          )
        )
      )
      _ <- output(CoherentSequenceCommand.render(report))
    yield new Summary(out, report, po, pc, to, tc, transportCounts.nonEmpty, rollbackCount)
    work.attempt.map {
      case Right(summary)       => Right(summary)
      case Left(Abort(failure)) => Left(failure)
      case Left(e)              => Left(Failure.Rejected("adapter", errorText(e)))
    }

  private[lab] def awaitOracle(
      ready: IO[Boolean],
      read: IO[Either[CoherentSequenceCommand.Failure, CoherentSequenceCommand.Report]],
      duration: FiniteDuration = 30.seconds
  ): IO[Either[CoherentSequenceCommand.Failure, CoherentSequenceCommand.Report]] =
    def loop: IO[Unit] =
      ready.flatMap(isReady => if isReady then IO.unit else IO.sleep(250.millis) *> IO.defer(loop))
    loop.timeoutTo(
      duration,
      IO.raiseError(Abort(Failure.Rejected("post-oracle-wait", "bounded manifest wait expired")))
    ) *> read

  def render(s: Summary): String =
    s"""{"scope":"live-validator-observation","profile":"${CoherentSequence.ProfileId}","passed":true,"typedStop":"TargetReached","onlineBeforePostOracle":true,"downloadCursorSeparate":true,"finalTupleReferenceMatched":true,"resourcesFinalized":true,"peerOpens":${s.peerOpens},"peerCloses":${s.peerCloses},"transportOpens":${s.transportOpens},"transportCloses":${s.transportCloses},"transportCounted":${s.transportCounted},"events":${s.outcome.events},"returnedBytes":${s.outcome.returnedBytes},"reconnects":${s.outcome.reconnects},"rollbackEvents":${s.rollbackEvents},"onlineRevision":${s.outcome.snapshot.state.revision},"offlineInitialPassRevision":${s.report.finalState.revision},"finalStateId":"${s.outcome.snapshot.state.id.hex}","liveForkClaim":false,"durableClaim":false,"fullLedgerValidated":false,"consensusValidated":false,"referenceSnapshotAtomic":false,"authenticatedSnapshot":false}"""
  private def failure(f: Failure): String =
    val (kind, stage, detail) = f match
      case Failure.Online(reason)              => (stopName(reason), "online", reason.toString)
      case Failure.Rejected(stage, detail)     => ("Rejected", stage, detail)
      case Failure.Unsupported(stage, feature) => ("Unsupported", stage, feature)
    s"""{"scope":"live-validator-observation","passed":false,"typedStop":${quote(
        kind
      )},"stage":${quote(stage)},"detail":${quote(
        detail
      )},"liveForkClaim":false,"durableClaim":false,"fullLedgerValidated":false,"consensusValidated":false}"""
  def run(args: List[String]): IO[ExitCode] =
    val work = args match
      case List(port, directory) =>
        val dir = Path.of(directory)
        for
          context <- IO
            .blocking(SequenceInput.load(dir))
            .flatMap(r =>
              IO.fromEither(r.left.map {
                case SequenceInput.Failure.Unsupported(feature) =>
                  Abort(Failure.Unsupported("input", feature))
                case SequenceInput.Failure.Rejected(stage, detail) =>
                  Abort(Failure.Rejected(stage, detail))
              })
            )
          address <- IO.fromEither(
            ReferenceHandshakeCommand
              .options(List(port, "1082026"))
              .left
              .map(s => Abort(Failure.Rejected("arguments", s)))
          )
          limits <- IO.fromEither(
            TcpLimits.checked().left.map(s => Abort(Failure.Rejected("transport", s)))
          )
          opens <- Ref.of[IO, Int](0); closes <- Ref.of[IO, Int](0)
          connection = counted(AsyncTcpTransport.resource[IO](address._1, limits), opens, closes)
          peer = BoundedChainFollower.sessions[IO](connection, address._2, 30.seconds)
          post = awaitOracle(
            IO.blocking(Files.exists(dir.resolve("coherent-sequence-oracle.md"))),
            CoherentSequenceCommand.observe(dir, dir)
          )
          result <- observe(
            context,
            peer,
            post,
            IO.println,
            LivePolicy,
            Some((opens.get, closes.get).tupled)
          )
        yield result
      case _ =>
        IO.pure(Left(Failure.Rejected("arguments", "live-validator PORT EVIDENCE_DIRECTORY")))
    work
      .timeout(160.seconds)
      .handleError {
        case Abort(f) => Left(f)
        case e        => Left(Failure.Rejected("adapter", errorText(e)))
      }
      .flatMap {
        case Right(summary) => IO.println(render(summary)).as(ExitCode.Success)
        case Left(error)    => IO.println(failure(error)).as(ExitCode(2))
      }
