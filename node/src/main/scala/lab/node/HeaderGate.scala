// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.Applicative
import cats.effect.{Async, Ref}
import cats.effect.syntax.all.*
import cats.mtl.{Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import lab.network.ChainSync
import lab.node.consensus.{HeaderChain, HeaderRules}
import lab.node.consensus.HeaderRules.Outcome
import scala.concurrent.duration.*

/** The headers one peer session has announced, validated in order before any block is fetched (G6).
  * A header that breaks a rule raises [[PeerError.InvalidHeader]]: the session ends, the peer is
  * disconnected, and none of the blocks of the batch is requested.
  */
trait HeaderGate[F[_]]:
  /** Validate the instructions of one ChainSync batch, in order. */
  def accept(batch: Vector[SyncEvent]): F[Unit]

  /** How the headers so far were established. */
  def counts: F[HeaderGate.Counts]

object HeaderGate:
  /** `validated` headers passed every header rule; `unchecked` were left to their block's own
    * validation (Byron headers, the first Shelley header after a Byron prefix).
    */
  final case class Counts(validated: Long, unchecked: Long, waited: Long)

  def none[F[_]: Applicative]: HeaderGate[F] = new HeaderGate[F]:
    def accept(batch: Vector[SyncEvent]) = Applicative[F].unit
    def counts = Applicative[F].pure(Counts(0, 0, 0))

/** Makes a [[HeaderGate]] for a session that has found its intersection with our chain. */
trait HeaderCheck[F[_]]:
  def start(intersection: ChainSync.Point): F[HeaderGate[F]]

object HeaderCheck:
  /** No header-first validation: every block is checked when it is applied (tests, tools). */
  def none[F[_]: Applicative]: HeaderCheck[F] = new HeaderCheck[F]:
    def start(intersection: ChainSync.Point) = Applicative[F].pure(HeaderGate.none[F])

  /** Header-first validation against the node's own chain.
    *
    * @param warm
    *   called with the headers of a batch and the header state before them, to verify their
    *   signatures in parallel ahead of the sequential rules (see [[VerificationCache.warmHeaders]])
    * @param observe
    *   called with the running counts after every batch
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
      observe: Option[HeaderGate.Counts => F[Unit]] = None
  )(using
      chain: Stateful[F, VolatileChain[S]],
      raise: Raise[F, PeerError]
  ): HeaderCheck[F] = new HeaderCheck[F]:
    private val F = Async[F]

    def start(intersection: ChainSync.Point): F[HeaderGate[F]] =
      for
        current <- chain.get
        based = current.rollback(intersection).toOption
        initial = HeaderChain.at[H](intersection, based.map(c => rules.at(c.state)))
        state <- Ref.of[F, HeaderChain[H]](initial)
        tally <- Ref.of[F, HeaderGate.Counts](HeaderGate.Counts(0, 0, 0))
      yield gate(state, tally)

    private def invalid[A](header: Header, error: lab.node.consensus.HeaderError): F[A] =
      PeerError.InvalidHeader(header.point, error).raise[F, A]

    private def protocol[A](reason: String): F[A] = PeerError.Protocol(reason).raise[F, A]

    private def gate(
        state: Ref[F, HeaderChain[H]],
        tally: Ref[F, HeaderGate.Counts]
    ): HeaderGate[F] =
      new HeaderGate[F]:
        def counts = tally.get

        def accept(batch: Vector[SyncEvent]): F[Unit] =
          // The signatures of the leading headers are verified in parallel first; the rules below
          // then find the verdicts ready.
          val leading = batch.takeWhile(_.isInstanceOf[SyncEvent.RollForward]).collect {
            case SyncEvent.RollForward(h, _) => h
          }
          (compute.within(
            state.get
              .flatMap(hc => hc.tipState.traverse_(h => warm.traverse_(_(leading, h))))
              .whenA(leading.size > 1) *>
              batch.traverse_ {
                case SyncEvent.RollBackward(point, _) => rollback(point)
                case SyncEvent.RollForward(header, _) => forward(header)
              }
          ) *> tally.get.flatMap(c => observe.traverse_(_(c))))

        /** The candidate chain returns to `point`: a header of ours or of the peer's. */
        private def rollback(point: ChainSync.Point): F[Unit] =
          state.get.flatMap { hc =>
            hc.rollback(point) match
              case Some(back) => state.set(back)
              case None       =>
                // before the join point: the peer's chain below it is ours while it is retained
                chain.get.flatMap { current =>
                  current.rollback(point) match
                    case Right(c) => state.set(HeaderChain.at(point, Some(rules.at(c.state))))
                    case Left(_)  =>
                      // Follower.session reports a rollback outside our chain
                      state.set(HeaderChain.at[H](point, None))
                }
          }

        private def forward(header: Header): F[Unit] =
          resolve(header, waited = false, since = None).flatMap { case (outcome, waited) =>
            outcome match
              case Outcome.Valid(next) =>
                record(header, Some(next)) *> tally.update(c =>
                  c.copy(validated = c.validated + 1, waited = c.waited + (if waited then 1 else 0))
                )
              case Outcome.Unchecked(_, next) =>
                record(header, next) *> tally.update(c => c.copy(unchecked = c.unchecked + 1))
              case Outcome.Invalid(error) => invalid(header, error)
              case Outcome.Later(_)       => protocol("header validation did not settle")
          }

        private def record(header: Header, next: Option[H]): F[Unit] =
          state.get.flatMap(hc =>
            hc.extend(header, next).fold(protocol[Unit], g => state.set(g.pruned(retained)))
          )

        /** Check `header`; when the ledger view cannot be forecast yet, wait for the node's chain
          * to advance (at most `stall` without progress).
          */
        private def resolve(
            header: Header,
            waited: Boolean,
            since: Option[(Option[BlockRef], FiniteDuration)]
        ): F[(Outcome[H], Boolean)] =
          for
            hc <- state.get
            current <- chain.get
            result <-
              if header.prevHash != hc.tipHash then
                protocol[(Outcome[H], Boolean)](
                  s"header ${Points.show(header.point)} does not extend the peer's chain"
                )
              else
                val tipState = hc.tipState.orElse(appliedState(current, hc))
                val anchor = anchorOf(current, hc)
                (tipState, anchor) match
                  case (Some(prev), Some((anchorState, tip))) =>
                    F.delay(rules.validate(anchorState, tip, prev, header))
                      .flatMap {
                        case Outcome.Later(reason) => wait(header, reason, since, current)
                        case other                 => F.pure(other -> waited)
                      }
                  case (None, Some(_)) =>
                    wait(header, "the parent's header state is not known yet", since, current)
                  case (_, None) =>
                    // no ledger state of ours lies on the peer's chain: the follower deals with it
                    F.pure(
                      (
                        Outcome.Unchecked("no common ledger state", None): Outcome[H]
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
            reason: String,
            since: Option[(Option[BlockRef], FiniteDuration)],
            current: VolatileChain[S]
        ): F[(Outcome[H], Boolean)] =
          for
            now <- F.monotonic
            progress = current.tipRef
            moved = since match
              case Some((seen, at)) if seen == progress => (seen, at)
              case _                                    => (progress, now)
            _ <-
              if now - moved._2 > stall then
                protocol[Unit](
                  s"header ${Points.show(header.point)} cannot be validated, the chain stands still: $reason"
                )
              else F.sleep(poll)
            result <- resolve(header, waited = true, since = Some(moved))
          yield result
