// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.ChainSync
import scala.concurrent.duration.*
import ControllerReducer.*
import ControllerJournalCodec.{Binding, Image, Message, project}
import LocalDerivedCheckpoint.{Claim as FullClaim, Publication}
import CoherentSequence as Sequence

/** A single trusted process, separate durable exact claims, and one structured writer lifetime. No
  * controller, evidence API, raw runtime or proposed cell escapes this package-private facade.
  */
private[lab] object CombinedLocalV2:
  final case class Config(binding: Binding, capacity: Int, recoveryDeadline: FiniteDuration)
  final case class Bootstrap(
      context: SequenceInput.Context,
      originals: Vector[BoundedChainFollower.Original],
      compactThrough: ChainSync.Point
  )
  enum Phase:
    case Reserved, WriterAcquired, Probed, Intent, Checkpoint, Verified, Committed,
      BeforeMemory, Memory, Activated, BeforeWriterClose, WriterClosed
  final class Failure(message: String) extends RuntimeException(message)
  final class View private[CombinedLocalV2] (
      private[CombinedLocalV2] val owner: AnyRef,
      private[CombinedLocalV2] val underlying: Sequence.Snapshot,
      val claim: FullClaim
  ):
    def state: Sequence.State = underlying.state
  final class Prepared private[CombinedLocalV2] (
      private[CombinedLocalV2] val before: View,
      private[CombinedLocalV2] val candidate: Sequence.Candidate
  )
  private type Controller[F[_]] = LocalControllerJournal.Controller[F]
  private type Writer[F[_]] = (NioLocalDerivedCheckpointStore.Disk, F[Unit])
  private def get[A](e: Either[String, A]): A = e.fold(s => throw new Failure(s), identity)
  private def raw(i: Id): Bytes = Bytes.fromHex(i.value).toOption.get
  private def fresh[F[_]: Async]: F[Id] = Async[F].delay {
    val b = new Array[Byte](32); new java.security.SecureRandom().nextBytes(b)
    Id(Bytes.fromArray(b).hex)
  }
  private def command[F[_]: Async](
      c: Controller[F],
      cmd: Command,
      claims: Vector[FullClaim] = Vector.empty
  ): F[LocalControllerJournal.Result] =
    c.snapshot
      .flatMap(i => c.submit(Message(Input.Request(i.journal.revision, cmd), claims)))
      .flatMap { r =>
        Async[F].delay(get(r)).flatMap(accepted[F])
      }
  private def evidence[F[_]: Async](
      c: Controller[F],
      e: Evidence
  ): F[LocalControllerJournal.Result] =
    c.submit(Message(Input.Completed(e))).flatMap(r => Async[F].delay(get(r))).flatMap(accepted[F])
  private def accepted[F[_]: Async](
      r: LocalControllerJournal.Result
  ): F[LocalControllerJournal.Result] = r.reply match
    case Reply.Rejected(reason) => Async[F].raiseError(new Failure(reason))
    case Reply.Stopped          => Async[F].raiseError(new Failure("controller stopped"))
    case _                      => Async[F].pure(r)
  private def currentLease[F[_]: Async](c: Controller[F]): F[Lease] =
    c.snapshot.flatMap(i => Async[F].fromOption(i.journal.lease, new Failure("lease missing")))
  private def retire[F[_]: Async](c: Controller[F]): F[Unit] = c.snapshot.flatMap { i =>
    if i.journal.lease.exists(l => !l.ended) then
      fresh[F].flatMap(id => command(c, Command.Retire(id))).void
    else Async[F].unit
  }
  private def settled[F[_]: Async](c: Controller[F]): F[Unit] = currentLease(c).flatMap { l =>
    if l.ended then Async[F].unit
    else
      for
        request <- fresh[F]
        released <- fresh[F]
        proof = l.child.fold[OwnershipEnd](OwnershipEnd.Absent(request))(child =>
          OwnershipEnd.Exited(child, request, released)
        )
        _ <- evidence(c, Evidence.OwnershipEnded(l.epoch, l.session, l.launch, proof))
      yield ()
  }
  private def cleanup[F[_]: Async](
      c: Controller[F],
      writer: Ref[F, Option[Writer[F]]],
      failedFreshAllocation: Ref[F, Boolean],
      observe: Phase => F[Unit]
  ): F[Unit] =
    val F = Async[F]
    // Failure to persist retirement does not justify leaving a live writer behind.
    retire(c).attempt *> writer.getAndSet(None).flatMap { owned =>
      val release = owned.fold(F.unit)(_._2)
      observe(Phase.BeforeWriterClose).attempt *> release.attempt.flatMap {
        case Left(e) => F.raiseError(e) // Do not attest uncertain physical release.
        case Right(_) =>
          failedFreshAllocation.get.flatMap { failed =>
            val attest = if owned.nonEmpty || failed then settled(c).attempt.void else F.unit
            attest *> observe(Phase.WriterClosed)
          }
      }
    }
  private def verified[F[_]: Async](
      config: Config,
      c: Controller[F],
      disk: NioLocalDerivedCheckpointStore.Disk,
      effect: Effect
  ): F[(Sequence.Runtime[F], FullClaim)] =
    val F = Async[F]
    effect match
      case Effect.Verify(l, expected) =>
        for
          frozen <- c.snapshot
          claim <- F.fromOption(
            frozen.claims.find(project(_) == expected),
            new Failure("complete verify claim missing")
          )
          _ <- F.raiseUnless(frozen.journal.lease.contains(l) && l.locked && !l.ended)(
            new Failure("verification lease fence")
          )
          bytes <- F.blocking(disk.read())
          envelope <- F.delay(get(LocalDerivedCheckpoint.decode(bytes)))
          _ <- F.raiseUnless(envelope.claim == claim)(new Failure("complete disk claim mismatch"))
          authority = new LocalDerivedCheckpoint.ControllerAuthority[F]:
            def authorize(candidate: FullClaim): F[Either[String, Unit]] = c.snapshot.map { now =>
              Either.cond(
                now == frozen && candidate == claim,
                (),
                "stale exact controller authority"
              )
            }
          accepted <- LocalDerivedCheckpoint
            .accept(envelope, authority)
            .flatMap(e => F.delay(get(e)))
          runtime <- LocalDerivedCheckpoint
            .recover(
              bytes,
              raw(config.binding.store.context),
              Some(accepted),
              config.recoveryDeadline
            )
            .flatMap(e => F.delay(get(e)))
          _ <- F.raiseUnless(runtime.maxBlocks == config.capacity)(
            new Failure("checkpoint capacity mismatch")
          )
        yield (runtime, claim)
      case _ => F.raiseError(new Failure("verification not requested"))
  private def commit[F[_]: Async](
      config: Config,
      c: Controller[F],
      disk: NioLocalDerivedCheckpointStore.Disk,
      previous: Option[FullClaim],
      publication: Publication,
      observe: Phase => F[Unit]
  ): F[Unit] =
    val F = Async[F]
    for
      l <- currentLease(c)
      id <- fresh[F]
      op = Operation(id, l.epoch, l.session, previous.map(project), project(publication.claim))
      prepared <- command(c, Command.Begin(op), Vector(publication.claim))
      _ <- F.raiseUnless(prepared.reply == Reply.Prepared(op))(
        new Failure("expected prepared intent")
      )
      _ <- observe(Phase.Intent)
      _ <- F.blocking(disk.install(previous, publication))
      _ <- observe(Phase.Checkpoint)
      installed <- command(c, Command.Installed(op))
      effect <- F.fromOption(installed.effects.headOption, new Failure("verify effect missing"))
      _ <- verified(config, c, disk, effect)
      _ <- observe(Phase.Verified)
      result <- evidence(c, Evidence.Verified(l.epoch, l.session, op.after))
      _ <- F.raiseUnless(result.reply == Reply.Committed(op))(
        new Failure("expected forced controller commit")
      )
      _ <- observe(Phase.Committed)
    yield ()

  final class Session[F[_]] private[CombinedLocalV2] (
      config: Config,
      runtime: Sequence.Runtime[F],
      controller: Controller[F],
      disk: NioLocalDerivedCheckpointStore.Disk,
      owner: AnyRef,
      gate: Semaphore[F],
      healthy: Ref[F, Boolean],
      closed: Ref[F, Boolean],
      view: Ref[F, View],
      finalizeWriter: F[Unit],
      observe: Phase => F[Unit]
  )(using F: Async[F]):
    val capacity: Int = config.capacity

    /** Reporting data only. After failure it can be older than disk and must not authorize retry.
      */
    def lastConfirmed: F[View] = view.get
    private def active: F[Unit] =
      healthy.get.flatMap(ok => F.raiseUnless(ok)(new Failure("session closed or poisoned")))
    private def locked[A](work: F[A]): F[A] = gate.permit.use { _ =>
      F.uncancelable { _ =>
        active *> work.handleErrorWith(e => healthy.set(false) *> F.raiseError(e))
      }
    }
    private def same(expected: View): F[Boolean] = view.get.map(current =>
      expected != null && (expected.owner eq owner) && (expected eq current)
    )
    def snapshot: F[View] = locked(view.get)
    def prepare(expected: View, block: SequenceInput.Block): F[Sequence.Result[Prepared]] = locked {
      same(expected).flatMap {
        case false => F.pure(Left(Sequence.Failure.StaleFence))
        case true  => runtime.prepare(block).map(_.map(new Prepared(expected, _)))
      }
    }
    private def mutate(
        expected: View
    )(plan: F[Sequence.Result[Sequence.LocalPlan]]): F[Sequence.Result[View]] = locked {
      same(expected).flatMap {
        case false => F.pure(Left(Sequence.Failure.StaleFence))
        case true =>
          plan.flatMap {
            case Left(e)                => F.pure(Left(e))
            case Right(p) if !p.changed => F.pure(Right(expected))
            case Right(p) if expected.claim.token.generation == Long.MaxValue =>
              F.pure(Left(Sequence.Failure.Rejected("publication", "generation exhausted")))
            case Right(p) =>
              for
                l <- currentLease(controller)
                _ <- F.raiseUnless(l.phase == ControllerReducer.Phase.Serving && l.locked)(
                  new Failure("session not serving")
                )
                publication <- runtime
                  .exportLocalPlan(
                    p,
                    raw(config.binding.store.id),
                    raw(l.session),
                    expected.claim.token.generation + 1
                  )
                  .flatMap(e => F.delay(get(e)))
                _ <- commit(config, controller, disk, Some(expected.claim), publication, observe)
                _ <- observe(Phase.BeforeMemory)
                _ <- runtime.installLocalPlan(p)
                next = new View(owner, p.snapshot, publication.claim)
                _ <- observe(Phase.Memory)
                _ <- view.set(next)
              yield Right(next)
          }
      }
    }
    def publish(prepared: Prepared): F[Sequence.Result[View]] =
      if prepared == null then F.pure(Left(Sequence.Failure.ForeignCandidate))
      else mutate(prepared.before)(runtime.planLocalPublish(prepared.candidate))
    def rollbackTo(expected: View, target: ChainSync.Point): F[Sequence.Result[View]] =
      mutate(expected)(F.defer(runtime.planLocalRollback(expected.underlying.fence, target)))
    def advanceAnchor(expected: View, through: ChainSync.Point): F[Sequence.Result[View]] =
      mutate(expected)(F.defer(runtime.planLocalAnchor(expected.underlying.fence, through)))
    private[CombinedLocalV2] def close: F[Unit] = F.uncancelable { _ =>
      gate.permit.use(_ =>
        closed.getAndSet(true).flatMap {
          case true  => F.unit
          case false => healthy.set(false) *> finalizeWriter
        }
      )
    }

  private def bootstrap[F[_]: Async](config: Config, seed: Bootstrap): F[Sequence.Runtime[F]] =
    val F = Async[F]
    def checked[A](e: Sequence.Result[A]): F[A] =
      F.fromEither(e.leftMap(e => new Failure(e.toString)))
    for
      _ <- F.delay(
        require(
          seed != null && seed.context != null && seed.context.id == raw(
            config.binding.store.context
          ) &&
            seed.originals != null && seed.originals.nonEmpty && seed.originals.size <= config.capacity &&
            seed.originals.forall(o => o.envelope.size <= 65535 && o.block.size <= 1048576),
          "bounded bootstrap required"
        )
      )
      runtime <- Sequence.create[F](seed.context, config.capacity).flatMap(checked)
      _ <- seed.originals.traverse_ { o =>
        F.delay(SequenceInput.block(o))
          .flatMap(e => F.fromEither(e.leftMap(e => new Failure(e.toString))))
          .flatMap(runtime.prepare)
          .flatMap(checked)
          .flatMap(runtime.publish)
          .flatMap(checked)
          .void
      }
      before <- runtime.snapshot
      compacted <- runtime.advanceAnchor(before.fence, seed.compactThrough).flatMap(checked)
      _ <- F.raiseUnless(compacted.state.compactedBlocks > 0)(
        new Failure("v2 requires explicit nonempty compaction")
      )
    yield runtime

  private def build[F[_]: Async](
      config: Config,
      seed: Option[Bootstrap],
      controller: Controller[F],
      faults: NioLocalDerivedCheckpointStore.Faults,
      observe: Phase => F[Unit]
  ): Resource[F, Session[F]] =
    val F = Async[F]
    Resource.make(F.uncancelable { _ =>
      for
        gate <- Semaphore[F](1)
        writer <- Ref.of[F, Option[Writer[F]]](None)
        failedAllocation <- Ref.of[F, Boolean](false)
        cleanupWriter = cleanup(controller, writer, failedAllocation, observe)
        result <- (for
          _ <- if seed.isEmpty then retire(controller) else F.unit
          // On Resume the old writer's lock is acquired in quarantine BEFORE reserving a new lease.
          _ <-
            if seed.isEmpty then
              NioLocalDerivedCheckpointStore
                .resource[F](config.binding, NioLocalDerivedCheckpointStore.Mode.Resume, faults)
                .allocated
                .flatMap(w => writer.set(Some(w))) *>
                controller.snapshot.flatMap(i =>
                  if i.journal.lease.exists(l => !l.ended) then settled(controller) else F.unit
                )
            else F.unit
          session <- fresh[F]; launch <- fresh[F]; child <- fresh[F]
          reserved <- command(controller, Command.ReserveLaunch(session, launch))
          _ <- F.raiseUnless(reserved.effects.exists(_.isInstanceOf[Effect.LaunchExact]))(
            new Failure("launch not reserved")
          )
          _ <- observe(Phase.Reserved)
          _ <-
            if seed.nonEmpty then
              NioLocalDerivedCheckpointStore
                .resource[F](config.binding, NioLocalDerivedCheckpointStore.Mode.Create, faults)
                .allocated
                .attempt
                .flatMap {
                  case Left(e)  => failedAllocation.set(true) *> F.raiseError(e)
                  case Right(w) => writer.set(Some(w))
                }
            else F.unit
          disk <- writer.get.flatMap(w => F.fromOption(w.map(_._1), new Failure("writer missing")))
          _ <- observe(Phase.WriterAcquired)
          l <- currentLease(controller)
          _ <- evidence(controller, Evidence.ChildBound(l.epoch, l.session, l.launch, child))
          probe <- evidence(controller, Evidence.LockHeld(l.epoch, l.session, child))
          _ <- observe(Phase.Probed)
          selected <- seed match
            case Some(s) =>
              for
                missing <- F.blocking(disk.readOption())
                _ <- F.raiseUnless(missing.isEmpty)(
                  new Failure("initial checkpoint already exists")
                )
                _ <- evidence(controller, Evidence.Inspected(l.epoch, l.session, Disk.Missing))
                runtime <- bootstrap(config, s)
                publication <- runtime
                  .exportLocalCheckpoint(raw(config.binding.store.id), raw(l.session), 0)
                  .flatMap(e => F.delay(get(e)))
                _ <- commit(config, controller, disk, None, publication, observe)
              yield (runtime, publication.claim)
            case None =>
              for
                rawImage <- F.blocking(disk.readOption())
                diskImage <- rawImage match
                  case None =>
                    evidence(controller, Evidence.Inspected(l.epoch, l.session, Disk.Missing)) *>
                      F.raiseError[Bytes](
                        new Failure("initialization-required: checkpoint primary missing")
                      )
                  case Some(bytes) => F.pure(bytes)
                envelope <- F.delay(get(LocalDerivedCheckpoint.decode(diskImage)))
                inspect <- F.fromOption(
                  probe.effects.collectFirst { case e: Effect.Inspect => e },
                  new Failure("inspect not requested")
                )
                frozen <- controller.snapshot
                _ <- F.raiseUnless(
                  inspect.allowed.contains(project(envelope.claim)) && frozen.claims.contains(
                    envelope.claim
                  )
                )(new Failure("unrecorded complete checkpoint claim"))
                requested <- evidence(
                  controller,
                  Evidence.Inspected(l.epoch, l.session, Disk.Present(project(envelope.claim)))
                )
                effect <- F
                  .fromOption(requested.effects.headOption, new Failure("verify not requested"))
                recovered <- verified(config, controller, disk, effect)
                _ <- evidence(
                  controller,
                  Evidence.Verified(l.epoch, l.session, project(recovered._2))
                )
                _ <- F.blocking(disk.discardStagingAfterRecovery())
              yield recovered
          activated <- command(controller, Command.Activate)
          _ <- F
            .raiseUnless(activated.reply.isInstanceOf[Reply.Serving])(new Failure("not activated"))
          _ <- observe(Phase.Activated)
          snapshot <- selected._1.snapshot
          owner <- F.delay(new Object())
          view <- Ref.of[F, View](new View(owner, snapshot, selected._2))
          healthy <- Ref.of[F, Boolean](true)
          closed <- Ref.of[F, Boolean](false)
        yield new Session(
          config,
          selected._1,
          controller,
          disk,
          owner,
          gate,
          healthy,
          closed,
          view,
          cleanupWriter,
          observe
        ))
          .onError(_ => cleanupWriter)
      yield result
    })(_.close)

  private[lab] def observed[F[_]: Async](
      config: Config,
      seed: Option[Bootstrap],
      journalFaults: LocalControllerJournal.Faults,
      storeFaults: NioLocalDerivedCheckpointStore.Faults,
      observe: Phase => F[Unit]
  ): Resource[F, Session[F]] =
    val F = Async[F]
    Resource.eval(
      F.delay(
        require(
          config != null && config.capacity >= 1 && config.capacity <= 8 &&
            config.recoveryDeadline > Duration.Zero && config.recoveryDeadline <= 30.seconds &&
            config.binding.launchPolicy == ControllerJournalCodec.LaunchPolicy,
          "combined v2 configuration"
        )
      )
    ) *>
      Resource.eval(fresh[F]).flatMap { incarnation =>
        LocalControllerJournal
          .resource[F](
            config.binding,
            if seed.isDefined then LocalControllerJournal.Mode.Create
            else LocalControllerJournal.Mode.Resume,
            incarnation,
            journalFaults
          )
          .flatMap(c => build(config, seed, c, storeFaults, observe))
      }
  def create[F[_]: Async](config: Config, seed: Bootstrap): Resource[F, Session[F]] =
    observed(
      config,
      Some(seed),
      LocalControllerJournal.NoFaults,
      NioLocalDerivedCheckpointStore.NoFaults,
      _ => Async[F].unit
    )
  def resume[F[_]: Async](config: Config): Resource[F, Session[F]] =
    observed(
      config,
      None,
      LocalControllerJournal.NoFaults,
      NioLocalDerivedCheckpointStore.NoFaults,
      _ => Async[F].unit
    )
