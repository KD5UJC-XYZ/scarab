// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.Queue
import fs2.concurrent.{Signal, SignallingRef}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.network.Fibers

/** The signature checks of queued blocks, run ahead of the worker (#1499, item 1): while the
  * selection worker applies one block, the checks of the blocks waiting behind it in the add-block
  * queue are run into the verification cache ([[VerificationCache]]), so that the worker, when it
  * reaches a block, finds its verdicts there instead of waiting for them.
  *
  * Where the checks are asked for: each block admitted to the queue, once it has passed every check
  * made before the queue, asks for them ([[ahead]], from `BlockSelection` 's admission). The
  * request is queued (at most [[Requests]], further ones dropped) and never waits, as it runs under
  * the queue's publication. One verifier fiber takes the requests in the queue's order, which is
  * the order the worker takes the blocks in, and verifies one block at a time with a bounded number
  * of threads ([[parallelism]]).
  *
  * What the worker sees: the checks only fill the cache, whose verdicts are functions of their
  * keys, and the worker's own preparation still asks for the block's checks
  * (`BlockSelection.Before.prepare`), which finds those already made; for a block being verified
  * when the worker reaches it, once that verification ends ([[reached]]). A verdict is taken by the
  * rules when they reach the check, as without this, so a bad signature in a queued block is
  * refused when the worker applies that block: the same refusal, in the same order, blaming the
  * same peer. A block the worker never applies causes nothing but the work. A failure of a check
  * made ahead is no information and is discarded ([[VerificationCache.warm]] already discards a
  * provider defect): the rules ask the provider themselves.
  *
  * Which blocks: the worker takes each block from the queue and either prepares it ([[reached]]) or
  * passes it by ([[passed]]: older than the immutable tip, or already processed). Either way the
  * block is no longer the verifier's: a block not yet started is skipped, and the decoded block a
  * verification left in the cache for a block passed by is dropped. The verifier also asks the
  * worker's two checks itself just before it starts a block ([[Verifier.wanted]]), so that a block
  * the worker would pass by is not verified; both only ever turn from pass to fail (the immutable
  * tip only moves forward, and a processed block leaves the forest only below it), so a block they
  * refuse now the worker refuses too. Only a block that passes them now and fails them when the
  * worker reaches it is verified for nothing.
  *
  * The reference verifies each signature when its rule reaches it (ouroboros-consensus 82ecba32
  * `Protocol/Praos.hs` 587–592 and 637–640), one block after another on the ChainDB's add-block
  * runner (ouroboros-consensus 82ecba32 `Storage/ChainDB/Impl/Background.hs` 658–708). Only when a
  * verdict is computed changes, never which verdict, which block it decides or when: an
  * Optimisation (REF-6), approved for #1499 item 1.
  */
final class VerifyAhead[F[_]: Async] private (
    requests: Option[Queue[F, VerifyAhead.Request]],
    slots: SignallingRef[F, Map[Bytes, VerifyAhead.Slot[F]]],
    tallies: Ref[F, VerifyAhead.Counts],
    verifier: VerifyAhead.Verifier[F],
    ended: F[Fatal]
):
  import VerifyAhead.{Counts, Reach, Request, Slot}
  private val F = Async[F]

  /** Completes only if the verifier ends, which only a defect outside the verification of one block
    * can make it do: the value it ended with, for the owner to raise in its own fiber (FIB-2). One
    * made by [[VerifyAhead.idle]] has no verifier, and this never completes.
    */
  def stopped: F[Fatal] = ended

  /** Ask for the checks of the queued block `raw` ahead of the worker. Never waits: a request the
    * queue has no room for is dropped, and a block already asked for is not asked again.
    */
  def ahead(header: Header, raw: Bytes): F[Unit] =
    requests.traverse_ { queue =>
      val hash = header.hash
      slots
        .modify(held =>
          if held.contains(hash) then (held, false)
          else (held.updated(hash, Slot.Queued[F]()), true)
        )
        .flatMap(fresh =>
          queue
            .tryOffer(Request(header, raw))
            .flatMap(offered => slots.update(_ - hash).unlessA(offered))
            .whenA(fresh)
        )
    }

  /** The worker reached the block `hash` and prepares it: the block is no longer the verifier's.
    * Whether all its checks were made ahead (`Hit`), were being made and the worker waited for them
    * to end (`Joined`), or not (`Miss`). The worker waits rather than make the same checks beside
    * the verifier, so that the decoded block the verification leaves in the cache is there before
    * the worker's own preparation puts it again and the rules take it, never after.
    */
  def reached(hash: Bytes): F[Reach] =
    slots
      .modify(held => (held - hash, held.get(hash)))
      .flatMap {
        case Some(Slot.Verified())    => F.pure(Reach.Hit)
        case Some(Slot.Running(done)) => done.get.as(Reach.Joined)
        case Some(Slot.Queued() | Slot.Unverified() | Slot.Abandoned()) | None => F.pure(Reach.Miss)
      }
      .flatTap(reach => tallies.update(_.counted(reach)))

  /** The worker took the block `hash` from the queue and passes it by, unprepared: a verification
    * not yet started is skipped, and the decoded block one left in the cache is dropped.
    */
  def passed(hash: Bytes): F[Unit] =
    slots.modify { held =>
      held.get(hash) match
        case Some(Slot.Running(_)) => (held.updated(hash, Slot.Abandoned[F]()), F.unit)
        case Some(Slot.Verified()) => (held - hash, verifier.discard(hash))
        case Some(Slot.Queued() | Slot.Unverified() | Slot.Abandoned()) => (held - hash, F.unit)
        case None                                                       => (held, F.unit)
    }.flatten

  /** How the worker found the blocks it prepared, since the start. */
  def counts: F[Counts] = tallies.get

  /** The blocks asked for and not yet reached or passed by the worker, as they change, for the
    * suites.
    */
  private[node] def held: Signal[F, Map[Bytes, Slot[F]]] = slots

  /** The verifier's loop, in an error scope of its own: it ends only with a failure, as the value
    * it ends with. Each block is verified in a scope of its own too, and its failure, or a defect
    * in it, is discarded: it is no information, as the rules ask for each verdict themselves.
    */
  private def verifying(queue: Queue[F, Request]): F[Either[Fatal, Nothing]] =
    Fibers.scoped[F, Fatal, Nothing](Fatal.Defect(_))(
      F.foreverM(queue.take.flatMap { request =>
        val hash = request.header.hash
        Deferred[F, Unit].flatMap { done =>
          slots
            .modify(held =>
              held.get(hash) match
                case Some(Slot.Queued()) => (held.updated(hash, Slot.Running(done)), true)
                case _                   => (held, false)
            )
            .flatMap(
              F.whenA(_)(
                F.uncancelable(poll =>
                  poll(verified(request))
                    .onCancel(finished(hash, done, false))
                    .flatMap(finished(hash, done, _))
                )
              )
            )
        }
      })
    )

  /** Whether all the checks of `request` 's block were made: none for a block the worker would no
    * longer prepare, and not all for a verification that failed, whose failure is discarded.
    */
  private def verified(request: Request): F[Boolean] =
    Fibers
      .scoped[F, Fatal, Boolean](Fatal.Defect(_))(
        verifier
          .wanted(request.header)
          .ifM(verifier.verify(request.header, request.raw), F.pure(false))
      )
      .map(_.getOrElse(false))

  /** The verification of `hash` is over, `verified` or not: kept for the worker, or, for a block
    * the worker passed by meanwhile, its decoded block dropped; a worker waiting for it (`done`)
    * goes on.
    */
  private def finished(hash: Bytes, done: Deferred[F, Unit], verified: Boolean): F[Unit] =
    slots
      .modify { held =>
        held.get(hash) match
          case Some(Slot.Running(_)) =>
            (
              held.updated(hash, if verified then Slot.Verified[F]() else Slot.Unverified[F]()),
              F.unit
            )
          case Some(Slot.Abandoned()) => (held - hash, verifier.discard(hash))
          case _                      => (held, F.unit)
      }
      .flatten
      .guarantee(done.complete(()).void)

object VerifyAhead:
  /** Requests waiting for the verifier: more than the add-block queue holds (ten, and the one being
    * added), so a request is dropped only when the verifier is behind by more than the queue.
    */
  val Requests = 16

  /** The threads the checks of one queued block use, of the `cores` the verification cache warms
    * with (`VerificationCache.parallelism`, one per processor): half of them, and none with fewer
    * than two, where there is no processor to spare beside the worker. They run beside the worker,
    * which applies blocks on one thread of the CPU pool and runs a block's scripts on the Plutus
    * workers, beside the worker's own preparation of a block the verifier did not reach and the
    * closure checks of endorser blocks, which take their threads from the same `cores` permits
    * (`VerificationCache.running`), and beside the header checks of ChainSync batches, which run on
    * threads of their own. Half leaves the worker's preparation at least the other half of the
    * permits; and it is ample: a mainnet Babbage block's checks take some 6 ms on all cores, so
    * about 12 ms on half of them, well within the some 30 ms the worker takes to apply the block in
    * front of it.
    */
  def parallelism(cores: Int): Option[Int] = Option.when(cores >= 2)(cores / 2)

  /** What the verifier does with a queued block: whether the worker would still prepare it now
    * (`wanted`: younger than the immutable tip and not processed yet, the worker's own two checks),
    * its checks (`verify`, into the cache, with whether all of them were made), and how the decoded
    * block those leave in the cache is dropped (`discard`), for a block the worker passes by.
    */
  final case class Verifier[F[_]](
      wanted: Header => F[Boolean],
      verify: (Header, Bytes) => F[Boolean],
      discard: Bytes => F[Unit]
  )

  /** A block asked for: its header and its bytes. */
  final case class Request(header: Header, raw: Bytes)

  /** Where a block asked for is: waiting for the verifier, being verified (`done` when that ends),
    * all its checks made, not all of them made (the verifier found the worker would not prepare it,
    * the cache cannot check it, or the verification failed), or passed by the worker while being
    * verified.
    */
  enum Slot[F[_]]:
    case Queued()
    case Running(done: Deferred[F, Unit])
    case Verified()
    case Unverified()
    case Abandoned()

  /** How the worker found a block it prepares. */
  enum Reach:
    case Hit, Joined, Miss

  /** The blocks the worker prepared, by how it found them. */
  final case class Counts(hit: Long, joined: Long, miss: Long):
    def counted(reach: Reach): Counts = reach match
      case Reach.Hit    => copy(hit = hit + 1)
      case Reach.Joined => copy(joined = joined + 1)
      case Reach.Miss   => copy(miss = miss + 1)

  object Counts:
    val zero: Counts = Counts(0, 0, 0)

  /** The checks of queued blocks run by `verifier` on one fiber, which stops when the resource is
    * released. A verifier that ends gives its value to [[VerifyAhead.stopped]].
    */
  def resource[F[_]: Async](verifier: Verifier[F]): Resource[F, VerifyAhead[F]] =
    for
      queue <- Resource.eval(Queue.dropping[F, Request](Requests))
      slots <- Resource.eval(SignallingRef.of[F, Map[Bytes, Slot[F]]](Map.empty))
      tallies <- Resource.eval(Ref.of[F, Counts](Counts.zero))
      ended <- Resource.eval(Deferred[F, Fatal])
      ahead = new VerifyAhead[F](Some(queue), slots, tallies, verifier, ended.get)
      _ <- ahead
        .verifying(queue)
        .flatMap(_.fold(ended.complete(_).void, never => never))
        .background
    yield ahead

  /** Checks nothing ahead ([[ahead]] does nothing): every block the worker prepares is a miss. */
  def idle[F[_]: Async]: F[VerifyAhead[F]] =
    (SignallingRef.of[F, Map[Bytes, Slot[F]]](Map.empty), Ref.of[F, Counts](Counts.zero)).mapN(
      (slots, tallies) =>
        new VerifyAhead[F](
          None,
          slots,
          tallies,
          Verifier(_ => Async[F].pure(false), (_, _) => Async[F].pure(false), _ => Async[F].unit),
          Async[F].never[Fatal]
        )
    )
