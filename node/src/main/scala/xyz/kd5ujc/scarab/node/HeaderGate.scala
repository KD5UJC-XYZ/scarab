// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{BlockRef, Compute, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{Header, PeerError}
import cats.Applicative
import cats.effect.{Async, Ref}
import cats.mtl.{Raise, Stateful}
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

  /** How the headers so far were established. */
  def counts: F[HeaderGate.Counts]

object HeaderGate:
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

  def withPatience(patience: LimitOnPatience[F]): HeaderCheck[F] = this

  def observed(observer: HeaderCheck.Event => F[Unit]): HeaderCheck[F] = this

object HeaderCheck:
  enum Event:
    case Validated(header: Header)
    case RolledBack(point: ChainSync.Point)

  /** No header-first validation: every block is checked when it is applied (tests, tools). */
  def none[F[_]: Applicative]: HeaderCheck[F] = new HeaderCheck[F]:
    def start(intersection: ChainSync.Point) = Applicative[F].pure(HeaderGate.none[F])

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
      publication: Option[Event => F[Unit]] = None
  )(using
      chain: Stateful[F, VolatileChain[S]]
  ): HeaderCheck[F] = new HeaderCheck[F]:
    private val F = Async[F]

    override def withPatience(value: LimitOnPatience[F]): HeaderCheck[F] =
      forChain(rules, compute, warm, stall, poll, retained, observe, added, Some(value), publication)

    override def observed(observer: Event => F[Unit]): HeaderCheck[F] =
      forChain(rules, compute, warm, stall, poll, retained, observe, added, patience,
        Some(event => publication.traverse_(_(event)) *> observer(event)))

    def start(intersection: ChainSync.Point): F[HeaderGate[F]] =
      for
        current <- chain.get
        based = current.rollback(intersection).toOption
        initial = HeaderChain.at[H](intersection, based.map(c => rules.at(c.state)))
        state <- Ref.of[F, HeaderChain[H]](initial)
        tally <- Ref.of[F, HeaderGate.Counts](HeaderGate.Counts(0, 0, 0))
        released <- Ref.of[F, Int](0)
      yield gate(state, tally, released)

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
        released: Ref[F, Int]
    ): HeaderGate[F] =
      new HeaderGate[F]:
        def counts = tally.get

        def accept(batch: Vector[SyncEvent])(using Raise[F, PeerError]): F[Unit] =
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
        private def rollback(point: ChainSync.Point)(using Raise[F, PeerError]): F[Unit] =
          state.get.flatMap { hc =>
            hc.rollback(point) match
              case Some(back) =>
                state.set(back) *> released.update(math.min(_, back.entries.size))
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

        private def forward(header: Header)(using Raise[F, PeerError]): F[Unit] =
          resolve(header, waited = false, since = None).flatMap { case (outcome, waited) =>
            outcome match
              case Outcome.Valid(next) =>
                record(header, Some(next)) *> tally.update(c =>
                  c.copy(validated = c.validated + 1, waited = c.waited + (if waited then 1 else 0))
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
        )(using Raise[F, PeerError]): F[(Outcome[H], Boolean)] =
          for
            hc <- state.get
            current <- chain.get
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
                    F.delay(rules.validate(anchorState, tip, prev, header))
                      .flatMap {
                        case Outcome.Later(reason) => wait(header, reason, since, current, hc)
                        case other                 => F.pure(other -> waited)
                      }
                  case (None, Some(_)) =>
                    released.get.flatMap { earlier =>
                      if hc.entries.size > earlier && patience.isEmpty then
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
                          hc
                        )
                    }
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
          val onChain = current.tipRef.map(_.hash).exists(hc.contains) ||
            (current.tip == hc.base)
          if onChain then Some((current.state, current.tipRef))
          else current.rollback(hc.base).toOption.map(c => (c.state, c.tipRef))

        private def wait(
            header: Header,
            reason: HeaderRules.ForecastReason,
            since: Option[(Option[BlockRef], FiniteDuration)],
            current: VolatileChain[S],
            hc: HeaderChain[H]
        )(using Raise[F, PeerError]): F[(Outcome[H], Boolean)] =
          for
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
              if patience.isEmpty && (nothingInFlight || now - moved._2 > stall) then
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
                patience.filter(_ => since.isEmpty).fold(waiting)(p =>
                  (p.setPaused(LimitOnPatience.PauseReason.Forecast, true) *> waiting)
                    .guarantee(p.setPaused(LimitOnPatience.PauseReason.Forecast, false))
                )
          yield result
