// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{BlockRef, ChainSelection, Compute, SelectView, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{GenesisInternalProblem, Header, PeerError}
import cats.Applicative
import cats.effect.{Async, Ref}
import cats.mtl.{Ask, Handle, Raise, Stateful, Tell}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import cats.effect.syntax.all.*
import xyz.kd5ujc.scarab.network.genesis.LimitOnPatience
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.consensus.{HeaderChain, HeaderRules}
import xyz.kd5ujc.scarab.consensus.HeaderRules.Outcome
import scala.concurrent.duration.*

/** The headers one peer session has announced, validated in order before any block is fetched (G6).
  * A header that breaks a rule raises [[PeerError.InvalidHeader]]: the session ends, the peer is
  * disconnected, and none of the blocks of the batch is requested.
  */
trait HeaderGate[F[_]]:
  /** Validate the instructions of one ChainSync batch, in order. */
  def accept(batch: Vector[SyncEvent])(using Raise[F, PeerError]): F[Unit]

  def acceptOrRestart(
      batch: Vector[SyncEvent]
  )(using Raise[F, PeerError], cats.Functor[F]): F[HeaderGate.Acceptance] =
    accept(batch).as(HeaderGate.Acceptance.Accepted)

  def forecastTime(@scala.annotation.unused header: Header)(using
      F: Applicative[F]
  ): F[Option[HeaderGate.TimeForecast]] = F.pure(None)

  def canonicalTiming: Boolean = false

  def prepareHeader(@scala.annotation.unused header: Header)(using
      F: Applicative[F],
      @scala.annotation.unused errors: Raise[F, PeerError]
  ): F[HeaderGate.Acceptance] = F.pure(HeaderGate.Acceptance.Accepted)

  /** How the headers so far were established. */
  def counts: F[HeaderGate.Counts]

  def snapshot(using F: Applicative[F]): F[Option[HeaderChain[NodeHeaderState]]] = F.pure(None)

  def intersectionSelection(using F: Applicative[F]): F[Option[ChainSelection.Local]] = F.pure(None)

  def graft(
      @scala.annotation.unused snapshot: HeaderChain[NodeHeaderState],
      @scala.annotation.unused point: ChainSync.Point,
      @scala.annotation.unused selection: ChainSelection.Local
  )(using
      F: Applicative[F]
  ): F[Either[HeaderCheck.GraftError, Unit]] = F.pure(Left(HeaderCheck.GraftError.Unsupported))

object HeaderGate:
  enum TimeForecast:
    case Known(onsetPicos: BigInt)
    case Invalid(error: xyz.kd5ujc.scarab.ledger.HeaderError)
    case Refused(problem: PeerError.GenesisProblem)
    case Restart(point: ChainSync.Point)

  enum Acceptance:
    case Accepted
    case Restart(point: ChainSync.Point)
    case End(point: ChainSync.Point)

  /** `validated` headers passed every header rule; `unchecked` were left to their block's own
    * validation (Byron headers, the first Shelley header after a Byron prefix).
    */
  final case class Counts(validated: Long, unchecked: Long, waited: Long)

  def none[F[_]: Applicative]: HeaderGate[F] = new HeaderGate[F]:
    def accept(batch: Vector[SyncEvent])(using Raise[F, PeerError]) = Applicative[F].unit
    def counts = Applicative[F].pure(Counts(0, 0, 0))

/** Makes a [[HeaderGate]] for a session that has found its intersection with our chain. */
trait HeaderCheck[F[_]]:
  def start(intersection: ChainSync.Point): F[HeaderGate[F]]

  /** Capture the offered fragment and its header states before sending FindIntersect (`Client.hs`
    * 1065–1148), so a concurrent local fork cannot erase a valid offered point.
    */
  def capture(using F: Applicative[F]): F[Option[HeaderCheck.Captured[F]]] = F.pure(None)

  def withPatience(patience: LimitOnPatience[F]): HeaderCheck[F] = this

  def observed(observer: HeaderCheck.Event => F[Unit]): HeaderCheck[F] = this

object HeaderCheck:
  final case class Captured[F[_]](
      points: Vector[ChainSync.Point],
      start: ChainSync.Point => F[HeaderGate[F]]
  )

  private[node] final case class Canonical[F[_], S, H](
      timing: HeaderRules.Timing[S, H],
      selection: F[ChainSelection.Local],
      invariant: GenesisInternalProblem => F[Unit]
  )

  private enum Reintersection:
    case Lost(point: ChainSync.Point)
    case End(point: ChainSync.Point)

  enum GraftError:
    case Unsupported
    case MissingPoint(point: ChainSync.Point)
    case MissingSelection(point: ChainSync.Point)
    case MissingState(point: ChainSync.Point)

  enum Event:
    case Validated(header: Header)
    case RolledBack(point: ChainSync.Point)

  /** No header-first validation: every block is checked when it is applied (tests, tools). */
  def none[F[_]: Applicative]: HeaderCheck[F] = new HeaderCheck[F]:
    def start(intersection: ChainSync.Point) = Applicative[F].pure(HeaderGate.none[F])

  /** The production Genesis family shares canonical header states across accepted CSJ jumps. The
    * equality witness is supplied by the compiler; arbitrary test state types cannot be recovered
    * with an erased cast or installed as a different protocol's state.
    */
  def forGenesisChain[F[_]: Async, S](
      rules: HeaderRules[S, NodeHeaderState] & HeaderRules.Timing[S, NodeHeaderState],
      compute: Compute[F],
      retained: Int,
      stall: FiniteDuration = 120.seconds,
      poll: FiniteDuration = 20.millis,
      warm: Option[(Vector[Header], NodeHeaderState) => F[Unit]] = None,
      observe: Option[HeaderGate.Counts => F[Unit]] = None,
      added: Option[HeaderGate.Counts => F[Unit]] = None
  )(using
      Stateful[F, VolatileChain[S]],
      Ask[F, ChainSelection.Local],
      Tell[F, GenesisInternalProblem]
  ): HeaderCheck[F] =
    forChain(
      rules,
      compute,
      warm = warm,
      stall = stall,
      poll = poll,
      retained = retained,
      observe = observe,
      added = added,
      stateWitness = Some(summon[NodeHeaderState =:= NodeHeaderState]),
      canonical = Some(
        Canonical(
          rules,
          Ask[F, ChainSelection.Local].ask,
          problem => Tell[F, GenesisInternalProblem].tell(problem)
        )
      )
    )

  /** Header-first validation against the node's own chain.
    *
    * @param warm
    *   called with the headers of a batch and the header state before them, to verify their
    *   signatures in parallel ahead of the sequential rules (see [[VerificationCache.warmHeaders]])
    * @param observe
    *   called with the running counts of the session after every batch
    * @param added
    *   called after every batch with what that batch added to the counts: the sessions of several
    *   peers (and a reconnection) each count from zero, so a total over the node is the sum of
    *   these
    * @param stall
    *   how long the node's chain may stand still while a header waits for a ledger view
    */
  def forChain[F[_]: Async, S, H](
      rules: HeaderRules[S, H],
      compute: Compute[F],
      warm: Option[(Vector[Header], H) => F[Unit]] = None,
      stall: FiniteDuration = 120.seconds,
      poll: FiniteDuration = 20.millis,
      retained: Int = 4096,
      observe: Option[HeaderGate.Counts => F[Unit]] = None,
      added: Option[HeaderGate.Counts => F[Unit]] = None,
      patience: Option[LimitOnPatience[F]] = None,
      publication: Option[Event => F[Unit]] = None,
      stateWitness: Option[H =:= NodeHeaderState] = None,
      canonical: Option[Canonical[F, S, H]] = None
  )(using
      chain: Stateful[F, VolatileChain[S]]
  ): HeaderCheck[F] = new HeaderCheck[F]:
    private val F = Async[F]
    private val timing = canonical.map(_.timing)

    private def coherentCurrent: F[(VolatileChain[S], Option[ChainSelection.Local])] =
      canonical.fold(chain.get.map(_ -> Option.empty[ChainSelection.Local])) { context =>
        F.tailRecM(()) { _ =>
          (chain.get, context.selection).tupled.map { (current, selected) =>
            if selected.anchor != current.anchor.point || selected.blocks.map(
                _.point
              ) != current.blocks.map(_._1.point)
            then Left(())
            else Right(current -> Some(selected))
          }
        }
      }

    override def withPatience(value: LimitOnPatience[F]): HeaderCheck[F] =
      forChain(
        rules,
        compute,
        warm,
        stall,
        poll,
        retained,
        observe,
        added,
        Some(value),
        publication,
        stateWitness,
        canonical
      )

    override def observed(observer: Event => F[Unit]): HeaderCheck[F] =
      forChain(
        rules,
        compute,
        warm,
        stall,
        poll,
        retained,
        observe,
        added,
        patience,
        Some(event => publication.traverse_(_(event)) *> observer(event)),
        stateWitness,
        canonical
      )

    override def capture(using Applicative[F]): F[Option[Captured[F]]] =
      canonical.traverse(_ =>
        coherentCurrent.map { pair =>
          val current = pair._1
          Captured(
            current.blocks.reverse.map(_._1.point) :+ current.anchor.point,
            point => startAt(pair, point)
          )
        }
      )

    def start(intersection: ChainSync.Point): F[HeaderGate[F]] =
      coherentCurrent.flatMap(pair => startAt(pair, intersection))

    private def startAt(
        pair: (VolatileChain[S], Option[ChainSelection.Local]),
        intersection: ChainSync.Point
    ): F[HeaderGate[F]] =
      val (current, selected) = pair
      for
        based <- F.pure(current.rollback(intersection).toOption)
        defView = (point: ChainSync.Point, state: S) =>
          selected.flatMap { s =>
            (if point == s.anchor then Some(s.anchorView)
             else s.blocks.find(_.point == point).map(_.view))
              .flatMap(view => timing.map(t => HeaderChain.TipView(view, t.tiebreaker(state))))
          }
        localPrefix =
          if stateWitness.isEmpty then Vector.empty
          else
            based.toVector.flatMap { c =>
              val known = HeaderChain.Known(
                c.anchor.point,
                Some(rules.at(c.anchor.state)),
                defView(c.anchor.point, c.anchor.state)
              ) +:
                c.blocks.map((ref, state) =>
                  HeaderChain.Known(ref.point, Some(rules.at(state)), defView(ref.point, state))
                )
              known.dropRight(1)
            }
        initial = HeaderChain
          .at[H](intersection, based.map(c => rules.at(c.state)))
          .copy(prefix = localPrefix, baseView = based.flatMap(c => defView(intersection, c.state)))
        _ <- canonical.traverse_ { context =>
          val missing = (initial.prefix
            .collectFirst { case k if k.view.isEmpty => k.point })
            .orElse(Option.when(initial.baseView.isEmpty)(initial.base))
          missing.traverse_(point =>
            context.invariant(GenesisInternalProblem.HeaderSelectionUnavailable(point)) *> F
              .never[Unit]
          )
        }
        state <- Ref.of[F, HeaderChain[H]](initial)
        tally <- Ref.of[F, HeaderGate.Counts](HeaderGate.Counts(0, 0, 0))
        released <- Ref.of[F, Int](0)
        prepared <- Ref.of[F, Option[(Header, ChainSync.Point, () => Outcome[H])]](None)
        knownSelection <- Ref.of[F, Option[ChainSelection.Local]](selected)
      yield gate(state, tally, released, prepared, knownSelection)

    private def invalid[A](header: Header, error: xyz.kd5ujc.scarab.ledger.HeaderError)(using
        Raise[F, PeerError]
    ): F[A] =
      PeerError.InvalidHeader(header.point, error).raise[F, A]

    private def protocol[A](reason: PeerError.ProtocolFailure)(using Raise[F, PeerError]): F[A] =
      PeerError.Protocol(reason).raise[F, A]

    /** `released` counts the entries of the candidate chain that belong to earlier batches: their
      * blocks are being fetched and applied, so waiting for them is possible. A header whose parent
      * is in the batch being validated cannot wait for it: the batch is not released until all its
      * headers have passed, so it is left to the block's own validation.
      */
    private def gate(
        state: Ref[F, HeaderChain[H]],
        tally: Ref[F, HeaderGate.Counts],
        released: Ref[F, Int],
        prepared: Ref[F, Option[(Header, ChainSync.Point, () => Outcome[H])]],
        knownSelection: Ref[F, Option[ChainSelection.Local]]
    ): HeaderGate[F] =
      new HeaderGate[F]:
        def counts = tally.get

        private def sparse(
            hc: HeaderChain[H],
            selected: Option[ChainSelection.Local]
        ): F[Option[PeerError.GenesisProblem]] =
          canonical
            .traverse { context =>
              val view = hc.entries.lastOption
                .map(e =>
                  HeaderChain
                    .TipView(SelectView.of(e.header), ChainSelection.tiebreakerFor(e.header.era))
                )
                .orElse(hc.baseView)
                .orElse(
                  Option.when(hc.tipPoint == ChainSync.Point.Origin)(
                    HeaderChain.TipView(
                      SelectView(-1, 0, None, 0, None),
                      ChainSelection.Tiebreaker.Unrestricted
                    )
                  )
                )
              view.fold(
                context.invariant(GenesisInternalProblem.HeaderSelectionUnavailable(hc.tipPoint)) *>
                  F.never[Option[PeerError.GenesisProblem]]
              ) { candidate =>
                F.pure(selected.flatMap { ours =>
                  Option.unless(
                    ChainSelection.prefersCandidate(candidate.tiebreaker, ours.tipView, candidate.value)
                  )(
                    PeerError.GenesisProblem.CandidateTooSparse(
                      hc.points.reverseIterator.find(ours.contains).getOrElse(hc.base),
                      ours.blocks.lastOption.fold(ours.anchor)(_.point),
                      hc.tipPoint
                    )
                  )
                })
              }
            }
            .map(_.flatten)

        override val canonicalTiming: Boolean = canonical.nonEmpty

        override def prepareHeader(
            header: Header
        )(using Applicative[F], Raise[F, PeerError]): F[HeaderGate.Acceptance] =
          canonical.fold(F.pure(HeaderGate.Acceptance.Accepted)) { context =>
            def sample: F[Either[Unit, HeaderGate.Acceptance]] =
              (state.get, coherentCurrent).tupled.flatMap { (hc, snapshot) =>
                val (current, selected) = snapshot
                val parent = hc.tipState.orElse(appliedState(current, hc))
                if !hc.points.contains(current.anchor.point) then
                  F.pure(Right(HeaderGate.Acceptance.Restart(hc.tipPoint)))
                else
                  (parent, anchorOf(current, hc)) match
                    case (_, None) => F.pure(Right(HeaderGate.Acceptance.Restart(hc.tipPoint)))
                    case (None, _) => F.pure(Left(()))
                    case (Some(previous), Some((anchor, tip))) =>
                      F.delay(context.timing.prepareValidation(anchor, tip, previous, header))
                        .flatMap {
                          case HeaderRules.Prepared.Unknown(_) =>
                            sparse(hc, selected).flatMap {
                              case Some(problem) =>
                                PeerError
                                  .Genesis(problem)
                                  .raise[F, Either[Unit, HeaderGate.Acceptance]]
                              case None => F.pure(Left(()))
                            }
                          case ready =>
                            knownSelection.set(selected) *> prepared
                              .set(Some((header, hc.tipPoint, () => ready.result)))
                              .as(Right(HeaderGate.Acceptance.Accepted))
                        }
              }
            state.get.flatMap { hc =>
              prepared.get.flatMap {
                case Some((h, parent, _)) if h == header && parent == hc.tipPoint =>
                  F.pure(HeaderGate.Acceptance.Accepted)
                case _ =>
                  val checkTime = F.tailRecM(())(_ =>
                    sample.flatMap {
                      case Left(_) => F.sleep(poll).as(Left(()))
                      case done    => F.pure(done)
                    }
                  )
                  patience.fold(checkTime)(p =>
                    (p.setPaused(LimitOnPatience.PauseReason.Forecast, true) *> checkTime)
                      .guarantee(p.setPaused(LimitOnPatience.PauseReason.Forecast, false))
                  )
              }
            }
          }

        override def forecastTime(header: Header)(using
            Applicative[F]
        ): F[Option[HeaderGate.TimeForecast]] =
          timing.traverse { time =>
            def sample: F[Either[Unit, HeaderGate.TimeForecast]] =
              (state.get, coherentCurrent).tupled.flatMap { (hc, snapshot) =>
                val (current, selected) = snapshot
                val parent = hc.tipState.orElse(appliedState(current, hc))
                if !hc.points.contains(current.anchor.point) then
                  F.pure(Right(HeaderGate.TimeForecast.Restart(hc.tipPoint)))
                else
                  (parent, anchorOf(current, hc)) match
                    case (_, None) => F.pure(Right(HeaderGate.TimeForecast.Restart(hc.tipPoint)))
                    case (None, _) => F.pure(Left(()))
                    case (Some(previous), Some((anchor, tip))) =>
                      F.delay(time.forecastTime(anchor, tip, previous, header)).flatMap {
                        case HeaderRules.TimeForecast.Known(onset) =>
                          F.pure(Right(HeaderGate.TimeForecast.Known(onset)))
                        case HeaderRules.TimeForecast.Invalid(error) =>
                          F.pure(Right(HeaderGate.TimeForecast.Invalid(error)))
                        case HeaderRules.TimeForecast.Unknown(_) =>
                          sparse(hc, selected).map {
                            case Some(problem) => Right(HeaderGate.TimeForecast.Refused(problem))
                            case None          => Left(())
                          }
                      }
              }
            sample.flatMap {
              case Right(known) => F.pure(known)
              case Left(_) =>
                val waiting = F.tailRecM(())(_ => F.sleep(poll) *> sample)
                patience.fold(waiting)(p =>
                  (p.setPaused(LimitOnPatience.PauseReason.Forecast, true) *> waiting)
                    .guarantee(p.setPaused(LimitOnPatience.PauseReason.Forecast, false))
                )
            }
          }

        override def snapshot(using Applicative[F]): F[Option[HeaderChain[NodeHeaderState]]] =
          state.get.map(history => stateWitness.map(equality => history.map(equality)))

        override def intersectionSelection(using Applicative[F]): F[Option[ChainSelection.Local]] =
          knownSelection.get

        override def graft(
            snapshot: HeaderChain[NodeHeaderState],
            point: ChainSync.Point,
            selection: ChainSelection.Local
        )(using
            Applicative[F]
        ): F[Either[GraftError, Unit]] =
          stateWitness match
            case None => F.pure(Left(GraftError.Unsupported))
            case Some(equality) =>
              snapshot.rollback(point) match
                case None => F.pure(Left(GraftError.MissingPoint(point)))
                case Some(prefix) if prefix.baseState.isEmpty =>
                  F.pure(Left(GraftError.MissingState(prefix.base)))
                case Some(prefix) if prefix.prefix.exists(_.state.isEmpty) =>
                  F.pure(
                    Left(
                      GraftError.MissingState(
                        prefix.prefix.find(_.state.isEmpty).fold(prefix.base)(_.point)
                      )
                    )
                  )
                case Some(prefix) if prefix.entries.exists(_.state.isEmpty) =>
                  F.pure(
                    Left(
                      GraftError.MissingState(
                        prefix.entries.find(_.state.isEmpty).fold(prefix.base)(_.header.point)
                      )
                    )
                  )
                case Some(prefix)
                    if prefix.base != ChainSync.Point.Origin && prefix.baseView.isEmpty =>
                  F.pure(Left(GraftError.MissingSelection(prefix.base)))
                case Some(prefix)
                    if prefix.prefix
                      .exists(k => k.point != ChainSync.Point.Origin && k.view.isEmpty) =>
                  F.pure(
                    Left(
                      GraftError.MissingSelection(
                        prefix.prefix
                          .find(k => k.point != ChainSync.Point.Origin && k.view.isEmpty)
                          .fold(prefix.base)(_.point)
                      )
                    )
                  )
                case Some(prefix) =>
                  val restored = prefix.map(equality.flip)
                  F.uncancelable(_ =>
                    state.set(restored) *> knownSelection.set(Some(selection)) *> prepared.set(
                      None
                    ) *> released.set(restored.entries.size)
                  ).as(Right(()))

        def accept(batch: Vector[SyncEvent])(using Raise[F, PeerError]): F[Unit] =
          Handle.allow[Reintersection](acceptChecked(batch)).rescue {
            case Reintersection.Lost(point) =>
              PeerError.Diverged(PeerError.Divergence.DetachedCandidate(point)).raise[F, Unit]
            case Reintersection.End(point) =>
              PeerError.RolledBackPastIntersection(point).raise[F, Unit]
          }

        override def acceptOrRestart(batch: Vector[SyncEvent])(using
            Raise[F, PeerError],
            cats.Functor[F]
        ): F[HeaderGate.Acceptance] =
          Handle
            .allow[Reintersection](
              acceptChecked(batch).as[HeaderGate.Acceptance](HeaderGate.Acceptance.Accepted)
            )
            .rescue {
              case Reintersection.Lost(point) =>
                F.pure(HeaderGate.Acceptance.Restart(point))
              case Reintersection.End(point) => F.pure(HeaderGate.Acceptance.End(point))
            }

        private def acceptChecked(batch: Vector[SyncEvent])(using
            Raise[F, PeerError],
            Raise[F, Reintersection]
        ): F[Unit] =
          // The signatures of the leading headers are verified in parallel first; the rules below
          // then find the verdicts ready.
          val leading = batch.takeWhile(_.isInstanceOf[SyncEvent.RollForward]).collect {
            case SyncEvent.RollForward(h, _) => h
          }
          tally.get.flatMap(before =>
            compute.within(
              state.get.flatMap(hc => released.set(hc.entries.size)) *>
                state.get
                  .flatMap(hc => hc.tipState.traverse_(h => warm.traverse_(_(leading, h))))
                  .whenA(leading.size > 1) *>
                batch.traverse_ {
                  case SyncEvent.RollBackward(point, _) =>
                    rollback(point) *> publication.traverse_(_(Event.RolledBack(point)))
                  case SyncEvent.RollForward(header, _) => forward(header)
                }
            ) *> tally.get.flatMap(c =>
              observe.traverse_(_(c)) *>
                added.traverse_(
                  _(
                    HeaderGate.Counts(
                      c.validated - before.validated,
                      c.unchecked - before.unchecked,
                      c.waited - before.waited
                    )
                  )
                )
            )
          )

        /** The candidate chain returns to `point`: a header of ours or of the peer's. */
        private def rollback(
            point: ChainSync.Point
        )(using Raise[F, PeerError], Raise[F, Reintersection]): F[Unit] =
          state.get.flatMap { hc =>
            hc.rollback(point) match
              case Some(back) =>
                state.set(back) *> released.update(math.min(_, back.entries.size))
              case None if canonical.nonEmpty =>
                // The reference ends this session after draining; a new local fork must not
                // synthesize header states for a rollback outside the captured candidate.
                Raise[F, Reintersection].raise(Reintersection.End(point))
              case None =>
                // before the join point: the peer's chain below it is ours while it is retained
                chain.get.flatMap { current =>
                  current.rollback(point) match
                    case Right(c) =>
                      state.set(HeaderChain.at(point, Some(rules.at(c.state)))) *> released.set(0)
                    case Left(_) =>
                      // Follower.session reports a rollback outside our chain
                      state.set(HeaderChain.at[H](point, None)) *> released.set(0)
                }
          }

        private def forward(
            header: Header
        )(using Raise[F, PeerError], Raise[F, Reintersection]): F[Unit] =
          prepareHeader(header)
            .flatMap {
              case HeaderGate.Acceptance.Accepted => resolve(header, waited = false, since = None)
              case HeaderGate.Acceptance.Restart(point) =>
                Raise[F, Reintersection].raise(Reintersection.Lost(point))
              case HeaderGate.Acceptance.End(point) =>
                Raise[F, Reintersection].raise(Reintersection.End(point))
            }
            .flatMap { case (outcome, waited) =>
              outcome match
                case Outcome.Valid(next) =>
                  record(header, Some(next)) *> tally.update(c =>
                    c.copy(
                      validated = c.validated + 1,
                      waited = c.waited + (if waited then 1 else 0)
                    )
                  ) *> patience.traverse_(_.validated(header.blockNo)) *>
                    publication.traverse_(_(Event.Validated(header)))
                case Outcome.Unchecked(_, next) =>
                  record(header, next) *> tally.update(c => c.copy(unchecked = c.unchecked + 1))
                case Outcome.Invalid(error) => invalid(header, error)
                case Outcome.Later(_) =>
                  protocol(PeerError.ProtocolProblem.HeaderValidationDidNotSettle)
            }

        private def record(header: Header, next: Option[H])(using Raise[F, PeerError]): F[Unit] =
          state.get.flatMap(hc =>
            hc.extend(header, next)
              .fold(
                _ =>
                  protocol[Unit](
                    PeerError.ProtocolProblem.HeaderDoesNotExtend(header.point, hc.tipPoint)
                  ),
                g =>
                  val kept = g.pruned(retained)
                  val dropped = g.entries.size - kept.entries.size
                  state.set(kept) *> released.update(r => (r - dropped).max(0))
              )
          )

        /** Check `header`; when the ledger view cannot be forecast yet, wait for the node's chain
          * to advance (at most `stall` without progress).
          */
        private def resolve(
            header: Header,
            waited: Boolean,
            since: Option[(Option[BlockRef], FiniteDuration)]
        )(using Raise[F, PeerError], Raise[F, Reintersection]): F[(Outcome[H], Boolean)] =
          (state.get, prepared.getAndSet(None)).tupled.flatMap {
            case (hc, Some((h, parent, run))) if h == header && parent == hc.tipPoint =>
              if hc.canExtend(header) then F.delay(run()).map(_ -> waited)
              else
                protocol(PeerError.ProtocolProblem.HeaderDoesNotExtend(header.point, hc.tipPoint))
            case _ => resolveFresh(header, waited, since)
          }

        private def resolveFresh(
            header: Header,
            waited: Boolean,
            since: Option[(Option[BlockRef], FiniteDuration)]
        )(using Raise[F, PeerError], Raise[F, Reintersection]): F[(Outcome[H], Boolean)] =
          for
            hc <- state.get
            snapshot <- coherentCurrent
            (current, selected) = snapshot
            result <-
              if !hc.canExtend(header) then
                protocol[(Outcome[H], Boolean)](
                  PeerError.ProtocolProblem.HeaderDoesNotExtend(header.point, hc.tipPoint)
                )
              else
                val tipState = hc.tipState.orElse(appliedState(current, hc))
                val anchor = anchorOf(current, hc)
                (tipState, anchor) match
                  case (Some(prev), Some((anchorState, tip))) =>
                    F.delay(rules.validate(anchorState, tip, prev, header)).flatMap {
                      case Outcome.Later(reason) =>
                        wait(header, reason, since, current, hc, selected)
                      case other => F.pure(other -> waited)
                    }
                  case (None, Some(_)) =>
                    released.get.flatMap { earlier =>
                      if hc.entries.size > earlier && patience.isEmpty && canonical.isEmpty then
                        F.pure(
                          (
                            Outcome.Unchecked(
                              HeaderRules.UncheckedReason.ParentInBatch,
                              None
                            ): Outcome[H]
                          ) -> waited
                        )
                      else
                        wait(
                          header,
                          HeaderRules.ForecastReason.ParentStateUnknown,
                          since,
                          current,
                          hc,
                          selected
                        )
                    }
                  case (_, None) if patience.nonEmpty || canonical.nonEmpty =>
                    Raise[F, Reintersection].raise(Reintersection.Lost(hc.tipPoint))
                  case (_, None) =>
                    // no ledger state of ours lies on the peer's chain: the follower deals with it
                    F.pure(
                      (
                        Outcome
                          .Unchecked(HeaderRules.UncheckedReason.NoCommonLedger, None): Outcome[H]
                      ) -> waited
                    )
          yield result

        /** The header state at the parent when the node has applied the parent. */
        private def appliedState(current: VolatileChain[S], hc: HeaderChain[H]): Option[H] =
          hc.tipPoint match
            case p if current.tip == p => Some(rules.at(current.state))
            case p                     => current.rollback(p).toOption.map(c => rules.at(c.state))

        /** The newest ledger state of ours that lies on the candidate chain. */
        private def anchorOf(
            current: VolatileChain[S],
            hc: HeaderChain[H]
        ): Option[(S, Option[BlockRef])] =
          val selected = current.blocks.reverseIterator.collectFirst {
            case (ref, state) if hc.contains(ref.hash) || hc.base == ref.point => (state, Some(ref))
          }
          selected.orElse {
            Option.when(
              hc.points.contains(current.anchor.point)
            )(current.anchor.state -> current.anchor.block)
          }

        private def wait(
            header: Header,
            reason: HeaderRules.ForecastReason,
            since: Option[(Option[BlockRef], FiniteDuration)],
            current: VolatileChain[S],
            hc: HeaderChain[H],
            selected: Option[ChainSelection.Local]
        )(using Raise[F, PeerError], Raise[F, Reintersection]): F[(Outcome[H], Boolean)] =
          for
            refusal <- sparse(hc, selected)
            _ <- refusal.traverse_(p => PeerError.Genesis(p).raise[F, Unit])
            now <- F.monotonic
            earlier <- released.get
            progress = current.tipRef
            moved = since match
              case Some((seen, at)) if seen == progress => (seen, at)
              case _                                    => (progress, now)
            // our tip's place among the entries of earlier batches: everything of theirs is applied
            applied =
              if current.tip == hc.base then Some(0)
              else
                Some(hc.entries.indexWhere(e => progress.exists(_.hash == e.header.hash)) + 1)
                  .filter(_ > 0)
            nothingInFlight = applied.exists(_ >= earlier)
            result <-
              if patience.isEmpty && canonical.isEmpty && (nothingInFlight || now - moved._2 > stall)
              then
                // Nothing is left to wait for (every block of the earlier batches is applied, or the
                // chain stood still): the block validates the header, rather than dropping a peer
                // whose chain the forecast cannot reach (an honest gap, a limit of the forecast).
                F.pure(
                  (
                    Outcome.Unchecked(
                      HeaderRules.UncheckedReason.ForecastFallback(reason),
                      None
                    ): Outcome[H]
                  ) -> true
                )
              else
                val waiting = F.sleep(poll) *> resolve(header, waited = true, since = Some(moved))
                patience
                  .filter(_ => since.isEmpty)
                  .fold(waiting)(p =>
                    (p.setPaused(LimitOnPatience.PauseReason.Forecast, true) *> waiting)
                      .guarantee(p.setPaused(LimitOnPatience.PauseReason.Forecast, false))
                  )
          yield result
