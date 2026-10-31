// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.syntax.all.*
import cats.Functor
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{BlockPoolProblem, Header, PeerError, ValidationError}
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.consensus.genesis.DiffusionPunishment
import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.network.selection.{BlockFetchPolicy, FetchMode}
import scala.concurrent.duration.*

/** The blocks that peer sessions fetched ahead of the rules, by header hash, shared by the sessions
  * of all peers. A session claims the blocks of a range before it requests them, so a block is
  * fetched from one peer only (the reference's `filterNotAlreadyInFlightWithOtherPeers` in bulk
  * sync, ouroboros-network `Ouroboros.Network.BlockFetch.Decision`), and the session that applies a
  * block takes it whichever peer it came from, waiting if that peer has not delivered it yet.
  *
  * The fetch mode decides whether another admitted session may also claim a block. Bulk sync keeps
  * claims exclusive; deadline mode retains the first delivery. [[FetchCoordinator]] owns the global
  * fetching-peer targets, preferred-peer handover and per-peer outstanding windows, rather than
  * imposing a second, incompatible per-block duplicate cap here.
  *
  * A claim that is not filled (the connection failed, the peer lacks the block) is released, and a
  * session waiting for it fetches the block itself. Received original bodies remain readable while
  * validation or LoE postpones selection, like upstream VolatileDB. The bounded cache drops the
  * oldest ordinary bodies past [[BlockPool.Retained]] entries. Queued admissions and the processed
  * VolatileDB forest are separate: all processed originals, including invalid ones, remain readable
  * until immutable-slot GC. With a received log the forest keeps each block's metadata only and
  * reads the body back from the log ([[BlockPool.body]]), as the native VolatileDB keeps an index in
  * memory and reads a block from its file; a node without a log keeps the bodies in memory. Pruning
  * at the native strict slot cutoff releases that forest and invalid pins. Retention follows durable
  * immutable progress, rather than receipt/adoption count: an LoE stall can grow the forest, by its
  * metadata. This preserves later fork selection.
  *
  * Claims are made through a [[BlockPool.Claims]] that belongs to one session ([[session]]). It is
  * a resource: when the session ends, however it ends (a failure, a cancellation), every claim it
  * still holds is released, so a claim cannot outlive the connection that was to fill it. Without
  * that a session waiting for such a claim waits for ever (issue 130).
  */
final class BlockPool[F[_]: Async] private (
    slots: Ref[F, BlockPool.Slots[F]],
    policy: BlockPool.FetchPolicy,
    attribution: BlockPool.AttributionPolicy,
    private[node] val fetchCoordinator: FetchCoordinator[F],
    recovered: Option[BlockPool.Recovered[F]],
    private[node] val invalidBlocks: InvalidBlocks[F],
    private[node] val noVerdictBlocks: NoVerdictBlocks[F],
    private[node] val admissions: BlockAdmissions[F]
):
  import BlockPool.*
  private val F = Async[F]

  /** The claims of one session, released when the resource is. */
  def session: Resource[F, Claims[F]] = session(None)

  /** A reporter belongs to the peer identity, not to the session's error scope. Its typed refusal
    * can therefore reach that peer's governor even after this session has disconnected. The
    * reference associates punishment with the block's sender (`ChainSel.hs` 1051-1067,
    * ouroboros-consensus 82ecba329); its `mkPunishThisThread` targets the adding client
    * (`InvalidBlockPunishment.hs` 48-56). Our shared cache instead retains a bounded peer reporter,
    * because the session validating a cached block can be a different client's session.
    */
  private[node] def session(
      report: Option[ValidationError => F[Unit]],
      diffusion: Diffusion = Diffusion.On
  ): Resource[F, Claims[F]] =
    Resource
      .make(
        (
          Deferred[F, ValidationError],
          Ref.of[F, DiffusionPunishment.State](DiffusionPunishment.initial)
        ).tupled.flatMap { (blame, punishment) =>
          slots
            .modify(s =>
              (
                s.copy(
                  owners = s.owners + 1,
                  origins = s.origins
                    .updated(s.owners, Origin(blame, report, false, None, punishment, diffusion))
                ),
                s.owners
              )
            )
            .map(_ -> blame)
        }
      ) { (owner, _) =>
        releaseAll(owner) *> F.monotonic.flatMap { now =>
          slots.update { s =>
            val origins = s.origins.get(owner) match
              case Some(origin) if origin.delivered && origin.expires.isEmpty =>
                s.origins.updated(owner, origin.copy(expires = Some(now + attribution.lifetime)))
              case Some(origin) if origin.expires.isDefined => s.origins
              case _                                        => s.origins - owner
            val retained = s.origins.get(owner).exists(o => o.delivered && o.expires.isEmpty)
            s.copy(
              origins = origins,
              released = s.released + (if retained then 1 else 0),
              reapAt =
                if retained then
                  Some(s.reapAt.fold(now + attribution.lifetime)(_.min(now + attribution.lifetime)))
                else s.reapAt
            ).pruned(now, attribution)
          }
        }
      }
      .map((owner, blame) => new Claims(this, owner, blame))

  /** Claim the hashes no session holds (in deadline mode also those held by fewer sessions than the
    * policy allows); the result says, per hash and in order, whether `owner` now holds it (and must
    * [[fill]] or [[release]] it).
    */
  private def claim(
      owner: Long,
      hashes: Vector[Bytes],
      mode: FetchMode,
      allowOverlap: Boolean
  ): F[Vector[Boolean]] =
    reap *> hashes.traverse(_ => Deferred[F, Option[Filled]]).flatMap { fresh =>
      slots.modify { s =>
        val (grown, owned) = hashes.zip(fresh).foldLeft((s, Vector.empty[Boolean])) {
          case ((acc, flags), (hash, done)) =>
            if !acc.origins.get(owner).exists(_.expires.isEmpty) then (acc, flags :+ false)
            else
              acc.entries.get(hash) match
                case None if acc.processed.contains(hash) => (acc, flags :+ false)
                case None =>
                  (
                    acc.copy(
                      entries = acc.entries.updated(hash, Slot.Pending(done, acc.next, Set(owner))),
                      next = acc.next + 1
                    ),
                    flags :+ true
                  )
                case Some(Slot.Pending(waiting, seq, owners))
                    if !owners(owner) && (allowOverlap ||
                      (mode == FetchMode.Deadline && policy.deadlineConcurrency > 0)) =>
                  (
                    acc.copy(entries =
                      acc.entries.updated(hash, Slot.Pending(waiting, seq, owners + owner))
                    ),
                    flags :+ true
                  )
                case Some(_) => (acc, flags :+ false)
        }
        (grown.trimmed, owned)
      }
    }

  /** The blocks `owner` claimed arrived: the first delivery of a block wins. */
  private def fill(
      owner: Long,
      blocks: Vector[(Bytes, Bytes)],
      metadata: Map[Bytes, DeliveredHeader] = Map.empty
  ): F[Vector[Filled]] =
    reap *> slots
      .modify { s =>
        blocks.foldLeft((s, Vector.empty[(Deferred[F, Option[Filled]], Filled)])) {
          case ((acc, waiting), (hash, block)) =>
            acc.entries.get(hash) match
              case Some(Slot.Pending(done, seq, owners))
                  if owners(owner) && acc.origins.get(owner).exists(_.expires.isEmpty) =>
                val admitted = metadata.get(hash).map(_.copy(admission = acc.admissions))
                (
                  acc.copy(
                    entries =
                      acc.entries.updated(hash, Slot.Done(block, seq, Source.Session(owner))),
                    origins = acc.origins.updatedWith(owner)(_.map(_.copy(delivered = true))),
                    headers = admitted.fold(acc.headers)(value => acc.headers.updated(hash, value)),
                    fresh = if admitted.nonEmpty then acc.fresh + hash else acc.fresh,
                    admissions = if admitted.nonEmpty then acc.admissions + 1 else acc.admissions
                  ),
                  waiting :+ (done -> Filled(block, Source.Session(owner), admitted))
                )
              case _ => (acc, waiting)
        }
      }
      .flatMap { completed =>
        completed.traverse_((done, filled) => done.complete(Some(filled))) *>
          fetchCoordinator.refreshGenesis.whenA(completed.nonEmpty).as(completed.map(_._2))
      }

  /** First successful checked receipt: required metadata is constructed in the same transaction as
    * its global cache winner, never recovered from a later announcing session.
    */
  private def fillChecked(owner: Long, value: Delivered): F[Option[Admission]] =
    reap *> slots
      .modify { current =>
        val hash = value.header.hash
        current.entries.get(hash) match
          case Some(Slot.Pending(done, sequence, owners))
              if owners(owner) && current.origins.get(owner).exists(_.expires.isEmpty) =>
            val delivered = value.metadata.copy(admission = current.admissions)
            val taken = Taken(value.block, false, Source.Session(owner), Some(delivered))
            current.copy(
              entries =
                current.entries.updated(hash, Slot.Done(value.block, sequence, taken.origin)),
              origins = current.origins.updatedWith(owner)(_.map(_.copy(delivered = true))),
              headers = current.headers.updated(hash, delivered),
              fresh = current.fresh + hash,
              admissions = current.admissions + 1
            ) -> Some(done -> Admission(taken, delivered))
          case _ => current -> None
      }
      .flatMap(_.traverse { (done, admission) =>
        done.complete(
          Some(Filled(admission.taken.block, admission.taken.origin, Some(admission.delivered)))
        ) *>
          fetchCoordinator.refreshGenesis.as(admission)
      })

  /** Give up the claims of `owner` that are still open (those already filled stay). A block that
    * another session also claimed stays pending for it; one nobody holds any more is released to
    * whoever waits for it.
    */
  private def release(owner: Long, hashes: Vector[Bytes]): F[Unit] =
    slots.modify(release1(_, owner, hashes)).flatMap(_.traverse_(_.complete(None)))

  /** Give up every claim `owner` still holds. */
  private def releaseAll(owner: Long): F[Unit] =
    slots
      .modify { s =>
        val mine = s.entries.collect {
          case (hash, Slot.Pending(_, _, owners)) if owners(owner) => hash
        }
        release1(s, owner, mine.toVector)
      }
      .flatMap(_.traverse_(_.complete(None)))

  /** The state without `owner` on the claims `hashes`, and the waits nobody can fill any more. */
  private def release1(
      s: Slots[F],
      owner: Long,
      hashes: Vector[Bytes]
  ): (Slots[F], Vector[Deferred[F, Option[Filled]]]) =
    hashes.foldLeft((s, Vector.empty[Deferred[F, Option[Filled]]])) { case ((acc, open), hash) =>
      acc.entries.get(hash) match
        case Some(Slot.Pending(done, seq, owners)) if owners(owner) =>
          if owners.size > 1 then
            (
              acc.copy(entries =
                acc.entries.updated(hash, Slot.Pending(done, seq, owners - owner))
              ),
              open
            )
          else (acc.copy(entries = acc.entries - hash), open :+ done)
        case _ => (acc, open)
    }

  /** The original block remains readable after receipt, including while another session applies it.
    * None means that no live claim or retained body exists and the caller must fetch it.
    */
  def take(hash: Bytes): F[Option[Bytes]] = takeFilled(hash).map(_.map(_.block))

  /** As [[take]], with the session that delivered the block. */
  def takeFilled(hash: Bytes): F[Option[Filled]] =
    reap *> slots.modify { s =>
      s.entries.get(hash) match
        case Some(Slot.Done(block, _, origin)) =>
          (s, F.pure(Option(Filled(block, origin, s.headers.get(hash)))))
        case Some(Slot.Pending(done, _, _)) =>
          (s, done.get)
        case None =>
          (
            s,
            s.processed
              .get(hash)
              .fold(
                recovered.traverse(_.get(hash)).map(_.flatten.map(Filled(_, Source.Recovered)))
              )(p => body(hash, p).map(_.map(Filled(_, p.origin, Some(p.delivered)))))
          )
    }.flatten

  /** Drop the retained blocks that session `origin` delivered, and tell it why: its blocks are not
    * trusted, since one of them was refused (`error`). A session that waits for one of them fetches
    * it itself.
    */
  private def refusedHash(error: ValidationError): Option[Bytes] = error.point match
    case xyz.kd5ujc.scarab.protocol.ChainSync.Point.Block(_, hash) => Some(hash)
    case xyz.kd5ujc.scarab.protocol.ChainSync.Point.Origin         => None

  private def distrust(source: Source, error: ValidationError): F[Unit] =
    (source match
      case Source.Session(origin) => distrustSession(origin, error)
      case Source.Recovered =>
        slots.update(s => s.copy(entries = s.entries -- refusedHash(error)).coherent)
    ) *> recovered.traverse_(store => refusedHash(error).traverse_(store.invalidate))

  private def distrustSession(origin: Long, error: ValidationError): F[Unit] =
    F.uncancelable { _ =>
      reap *> slots
        .modify { s =>
          val pending = s.entries.collect {
            case (hash, Slot.Pending(_, _, owners)) if owners(origin) => hash
          }.toVector
          val (released, waits) = release1(s, origin, pending)
          (
            released
              .copy(
                entries = released.entries.filter {
                  case (_, Slot.Done(_, _, Source.Session(from))) => from != origin
                  case _                                          => true
                },
                origins = s.origins - origin,
                released =
                  s.released - (if s.origins.get(origin).exists(_.expires.isDefined) then 1 else 0)
              )
              .coherent,
            (s.origins.get(origin), waits)
          )
        }
        .flatMap { (source, waits) =>
          waits.traverse_(_.complete(None)) *>
            source.traverse_(o => o.report.fold(o.blame.complete(error).void)(_(error)))
        }
    }

  /** Pin the actual checked original before publishing its invalidity. Native VolatileDB keeps
    * invalid bodies readable until slot GC; merely retaining their hashes would falsely satisfy the
    * fetched predicate. Pinning never creates another first-admission context.
    */
  private[node] def invalidated(
      taken: Taken,
      header: Header,
      error: ValidationError
  ): F[Either[BlockPoolProblem, Unit]] = taken.metadata match
    case None => F.pure(Left(BlockPoolProblem.MissingDeliveredHeader(header.point)))
    case Some(metadata) if metadata.header.point != header.point =>
      F.pure(Left(BlockPoolProblem.MissingDeliveredHeader(header.point)))
    case Some(metadata) =>
      F.uncancelable { _ =>
        slots
          .modify { before =>
            val hash = header.hash
            val (sequence, waiting) = before.entries.get(hash) match
              case Some(Slot.Pending(done, seq, _)) => seq -> Some(done)
              case Some(Slot.Done(_, seq, _))       => seq -> None
              case None                             => before.next -> None
            before.copy(
              entries =
                before.entries.updated(hash, Slot.Done(taken.block, sequence, taken.origin)),
              headers = before.headers.updated(hash, metadata),
              pinned = before.pinned + hash,
              fresh = before.fresh - hash,
              next = before.next.max(sequence + 1)
            ) -> waiting
          }
          .flatMap(
            _.traverse_(_.complete(Some(Filled(taken.block, taken.origin, Some(metadata)))))
          ) *>
          invalidBlocks.record(header, error) *> fetchCoordinator.refreshGenesis.as(Right(()))
      }

  /** ChainSel.hs:1326–1344 associates invalidity with the body which caused chain selection, not
    * necessarily the invalid body in that candidate's prefix. Semantic rejection retains every
    * received original, just as the native VolatileDB does; the invalid map excludes it.
    */
  private[node] def reject(
      taken: Taken,
      added: ChainSync.Point,
      error: ValidationError,
      lastValid: ChainSync.Point
  ): F[Either[BlockPoolProblem, Rejection]] =
    taken.origin match
      case Source.Recovered => F.pure(Right(Rejection.NotImplicated(taken.origin)))
      case Source.Session(id) =>
        taken.metadata match
          case None => F.pure(Left(BlockPoolProblem.MissingDeliveredHeader(added)))
          case Some(delivered) =>
            val classification = for
              addedPoint <- blockPoint(delivered.header.point)
              invalidPoint <- blockPoint(error.point)
            yield DiffusionPunishment.classify(addedPoint, invalidPoint, lastValid)
            classification match
              case Left(problem) => F.pure(Left(problem))
              case Right(None)   => F.pure(Right(Rejection.NotImplicated(taken.origin)))
              case Right(Some(invalidity)) =>
                slots.get.flatMap(_.origins.get(id) match
                  // A forgotten retained attribution has no live client thread to punish.
                  case None => F.pure(Right(Rejection.NotImplicated(taken.origin)))
                  case Some(origin) =>
                    origin.punishment
                      .modify { before =>
                        val decision = origin.diffusion match
                          case Diffusion.On =>
                            DiffusionPunishment.judge(before, delivered.view, invalidity)
                          case Diffusion.Off =>
                            DiffusionPunishment.Decision(before, DiffusionPunishment.Outcome.Punish)
                        decision.state -> decision.outcome
                      }
                      .flatMap {
                        case DiffusionPunishment.Outcome.Forgive =>
                          F.pure(Right(Rejection.Forgiven(taken.origin)))
                        case DiffusionPunishment.Outcome.Punish =>
                          origin.report
                            .fold(origin.blame.complete(error).void)(_(error))
                            .as(Right(Rejection.Punished(taken.origin)))
                      })

  private def blockPoint(point: ChainSync.Point): Either[BlockPoolProblem, ChainSync.Point.Block] =
    point match
      case block: ChainSync.Point.Block => Right(block)
      case ChainSync.Point.Origin       => Left(BlockPoolProblem.MissingDeliveredHeader(point))

  private def retained(source: Source, hash: Bytes): F[Boolean] =
    reap *> (source match
      case Source.Session(origin) => slots.get.map(_.origins.contains(origin))
      case Source.Recovered =>
        (slots.get, recovered.traverse(_.hashes)).mapN { (current, durable) =>
          current.entries.contains(hash) || current.processed.contains(hash) || durable.exists(
            _(hash)
          )
        })

  /** Seed actual original bytes with restart provenance, never a fabricated live-session owner. */
  private[node] def seedRecovered(blocks: Vector[(Bytes, Bytes)]): F[Unit] =
    slots.update { before =>
      blocks
        .foldLeft(before) { case (current, (hash, raw)) =>
          if current.entries.contains(hash) then current
          else
            current.copy(
              entries =
                current.entries.updated(hash, Slot.Done(raw, current.next, Source.Recovered)),
              next = current.next + 1
            )
        }
        .trimmed
    } *> fetchCoordinator.refreshGenesis

  /** Expiry also drops untaken blocks: a cache entry never survives its retained source. */
  private[node] def reap: F[Unit] =
    F.monotonic.flatMap(now => slots.update(_.pruned(now, attribution)))

  private def clear: F[Unit] =
    slots
      .getAndUpdate(s =>
        s.copy(
          entries = Map.empty,
          origins = Map.empty,
          released = 0,
          reapAt = None,
          headers = Map.empty,
          fresh = Set.empty,
          pinned = Set.empty,
          processed = Map.empty
        )
      )
      .flatMap(_.entries.values.toVector.traverse_ {
        case Slot.Pending(done, _, _) => done.complete(None).void
        case _                        => F.unit
      })

  /** Keep released provenance bounded while idle, then clear it after all sessions finish. */
  def maintenance: Resource[F, Unit] =
    Resource.make(F.unit)(_ => clear).flatMap { _ =>
      (F.sleep(attribution.lifetime.min(1.minute)) *> reap).foreverM.background.void
    }

  /** Retained released sources (tests); active sessions keep their own live notification. */
  private[node] def retainedOrigins: F[Int] =
    reap *> slots.get.map(_.origins.values.count(_.expires.isDefined))

  /** Entries held (tests). */
  def size: F[Int] = slots.get.map(_.entries.size)

  /** The blocks selection may choose from: the processed forest and the admitted blocks whose
    * bodies are still retained.
    */
  private[node] def selectionSnapshot: F[Map[Bytes, DeliveredHeader]] = slots.get.map { current =>
    current.processed.view.mapValues(_.delivered).toMap ++ current.headers.filter { (hash, _) =>
      current.entries.get(hash).exists {
        case Slot.Done(_, _, _) => true
        case _                  => false
      }
    }
  }

  private[node] def processedSnapshot: F[Map[Bytes, Processed]] = slots.get.map(_.processed)

  /** The original body of a processed block: kept in memory without a received log, else read back
    * from it as the reference reads a VolatileDB block from its file. None once the log has
    * collected it, which happens only below the immutable tip, where selection no longer reads.
    */
  private[node] def body(hash: Bytes, processed: Processed): F[Option[Bytes]] =
    processed.held.fold(recovered.flatTraverse(_.original(hash)))(raw => F.pure(Some(raw)))

  private[node] def processAdmission(admission: Admission): F[Boolean] = slots.modify { current =>
    val hash = admission.delivered.header.hash
    if current.processed.contains(hash) then current -> false
    else
      val processed =
        Processed(admission.delivered, admission.taken.origin, held(admission.taken.block))
      current.copy(processed = current.processed.updated(hash, processed)) -> true
  }

  /** The body a processed block keeps in memory: none when the received log holds it. */
  private def held(raw: Bytes): Option[Bytes] = Option.when(recovered.isEmpty)(raw)

  /** Read every checked recovered original as one startup forest. The ordinary cache limit must not
    * discard branches before native initialChainSelection has compared them.
    */
  private[node] def seedRecoveredHeaders(blocks: Vector[Delivered]): F[Unit] =
    seed(blocks.map(b => Processed(b.metadata, Source.Recovered, held(b.block))))

  /** As [[seedRecoveredHeaders]], from the metadata of blocks the received log holds: their bodies
    * are not kept and are read back from the log.
    */
  private[node] def seedRecoveredMetadata(blocks: Vector[DeliveredHeader]): F[Unit] =
    seed(blocks.map(Processed(_, Source.Recovered, None)))

  private def seed(blocks: Vector[Processed]): F[Unit] =
    slots.update { current =>
      val recovered = blocks.map(p => p.delivered.header.hash -> p).toMap
      current.copy(processed = recovered ++ current.processed)
    } *> fetchCoordinator.refreshGenesis

  private[node] def completeAdmission(hash: Bytes): F[Unit] =
    slots.update { current =>
      val completed = current.copy(fresh = current.fresh - hash)
      if current.processed.contains(hash) then completed
      else completed.copy(entries = completed.entries - hash).coherent
    } *> fetchCoordinator.refreshGenesis

  private def deliver(
      owner: Long,
      blocks: Vector[Delivered]
  ): F[Unit] =
    blocks.traverse_ { value =>
      val publish = fillChecked(owner, value)
      admissions.active.flatMap {
        case false => publish.void
        case true =>
          admissions.submit(
            publish
          )
      }
    }
  private def fillOpaque(owner: Long, blocks: Vector[(Bytes, Bytes)]): F[Unit] =
    fill(owner, blocks).void

object BlockPool:
  /** Global fetching-peer targets and per-peer request credit, shared by both connection
    * directions. Preferred-peer handover follows the pinned policy's target exception. Production
    * defaults are from `Cardano/Network/Diffusion/Configuration.hs:89-100` and
    * `Cardano/Network/NodeToNode.hs:209-212` at the pinned ouroboros-network revision.
    */
  final case class FetchPolicy(
      deadlineConcurrency: Int = 1,
      bulkConcurrency: Int = 1,
      maxRequestsPerPeer: Int = 100,
      peerSalt: Long = 0L
  )

  /** A released source remains accountable for at most this window, including a block already taken
    * but not yet validated. The cap is independent of block count and live session count. The
    * resource reaps expiry while idle; accesses also reap before serving cached data.
    */
  private[node] final case class AttributionPolicy(
      retained: Int = Retained,
      lifetime: FiniteDuration = 10.minutes
  )

  /** What one session may do with the pool: claim blocks to fetch, fill what it fetched, release
    * what it could not. Obtained from [[BlockPool.session]].
    */
  final class Claims[F[_]] private[BlockPool] (
      pool: BlockPool[F],
      owner: Long,
      blame: Deferred[F, ValidationError]
  ):
    /** Claim `hashes` for this session under the fetch `mode` (bulk sync, the exclusive claim,
      * unless a caller says otherwise).
      */
    def claim(
        hashes: Vector[Bytes],
        mode: FetchMode = FetchMode.BulkSync,
        allowOverlap: Boolean = false
    ): F[Vector[Boolean]] =
      pool.claim(owner, hashes, mode, allowOverlap)
    def fill(blocks: Vector[(Bytes, Bytes)]): F[Unit] = pool.fillOpaque(owner, blocks)
    private[node] def invalidBlocks: InvalidBlocks[F] = pool.invalidBlocks
    private[node] def noVerdictBlocks: NoVerdictBlocks[F] = pool.noVerdictBlocks
    def fillDelivered(blocks: Vector[Delivered]): F[Unit] = pool.deliver(owner, blocks)

    /** Provenance of original bytes delivered by this connection, independently of which peer won a
      * duplicate pool claim. Genesis can validate that delivery before its range ends.
      */
    private[node] def delivered(block: Bytes): Taken =
      Taken(block, own = true, Source.Session(owner))
    private[node] def delivered(block: Delivered): Taken =
      Taken(block.block, own = true, Source.Session(owner), Some(block.metadata))
    def release(hashes: Vector[Bytes]): F[Unit] = pool.release(owner, hashes)

    /** The block, once some session has fetched it (see [[BlockPool.take]]), and whether this
      * session fetched it.
      */
    def take(hash: Bytes)(using Functor[F]): F[Option[Taken]] =
      pool
        .takeFilled(hash)
        .map(_.map(f => Taken(f.block, f.origin == Source.Session(owner), f.origin, f.metadata)))

    /** The rules refused a block that session `origin` delivered: drop the others it delivered and
      * tell it ([[blamed]]).
      */
    def distrust(origin: Source, error: ValidationError): F[Unit] = pool.distrust(origin, error)

    def reject(
        taken: Taken,
        added: ChainSync.Point,
        error: ValidationError,
        lastValid: ChainSync.Point
    ): F[Either[BlockPoolProblem, Rejection]] = pool.reject(taken, added, error, lastValid)

    /** Whether the pool still retains the body `hash` with its attribution to `origin`. */
    private[node] def retained(origin: Source, hash: Bytes): F[Boolean] =
      pool.retained(origin, hash)

    /** Completes when the rules have refused a block that this session delivered, with their
      * reason: the session's peer sent an invalid block.
      */
    def blamed: F[ValidationError] = blame.get

    /** As [[blamed]], without waiting: the refusal this session was blamed for, if it has been. */
    private[node] def blamedYet: F[Option[ValidationError]] = blame.tryGet

  /** A block and the session that delivered it. */
  enum Source:
    case Session(owner: Long)
    case Recovered

  /** The received log, through callbacks that return readable original bytes and own their
    * infrastructure error scope.
    *
    * @param get
    *   a block the log may still offer: none once a validation refusal suppressed it
    * @param original
    *   a block the log holds, refused or not: what a processed block's body is read back from
    */
  final case class Recovered[F[_]](
      hashes: F[Set[Bytes]],
      get: Bytes => F[Option[Bytes]],
      original: Bytes => F[Option[Bytes]],
      invalidate: Bytes => F[Unit]
  )

  enum Diffusion:
    case On, Off

  final case class DeliveredHeader(
      header: Header,
      view: DiffusionPunishment.View,
      admission: BigInt = 0
  )
  private[node] final case class Admission private[BlockPool] (
      taken: Taken,
      delivered: DeliveredHeader
  )

  /** A block of the processed forest: what the reference's VolatileDB keeps in memory of a block it
    * holds, its index entry (`OpenState`, `Storage/VolatileDB/Impl/State.hs:92-108`,
    * ouroboros-consensus 82ecba32), and not its body. The body is read back from the received log
    * ([[BlockPool.body]]); `held` keeps it only for a node without one.
    */
  private[node] final case class Processed private[BlockPool] (
      delivered: DeliveredHeader,
      origin: Source,
      held: Option[Bytes]
  )
  final class Delivered private (
      val header: Header,
      val block: Bytes,
      val metadata: DeliveredHeader
  )
  object Delivered:
    def checked(header: Header, block: Bytes): Either[PeerError.ProtocolProblem, Delivered] =
      for
        _ <- FetchedBlockIntegrity.check(header, block)
        view <- DiffusionPunishment
          .view(header)
          .leftMap(PeerError.ProtocolProblem.InvalidPunishmentHeader(_))
      yield new Delivered(header, block, DeliveredHeader(header, view))

  enum Rejection:
    case Forgiven(source: Source)
    case Punished(source: Source)
    case NotImplicated(source: Source)
    def origin: Source = this match
      case Forgiven(value)      => value
      case Punished(value)      => value
      case NotImplicated(value) => value

  final case class Filled(block: Bytes, origin: Source, metadata: Option[DeliveredHeader] = None)

  /** A block taken from the pool: `own` when the session that takes it fetched it, else `origin`
    * delivered it.
    */
  final case class Taken(
      block: Bytes,
      own: Boolean,
      origin: Source,
      metadata: Option[DeliveredHeader] = None
  )

  /** Entries kept before the oldest finished ones are dropped. */
  val Retained = 8192

  def create[F[_]: Async]: F[BlockPool[F]] = create(FetchPolicy())

  def create[F[_]: Async](policy: FetchPolicy): F[BlockPool[F]] =
    create(policy, AttributionPolicy())

  def create[F[_]: Async](
      policy: FetchPolicy,
      selection: F[ChainSelection.Local]
  ): F[BlockPool[F]] =
    create(policy, AttributionPolicy(), Some(selection))

  def create[F[_]: Async](
      policy: FetchPolicy,
      selection: F[ChainSelection.Local],
      recovered: Recovered[F]
  ): F[BlockPool[F]] = create(policy, AttributionPolicy(), Some(selection), Some(recovered))

  private[node] def create[F[_]: Async](
      policy: FetchPolicy,
      attribution: AttributionPolicy,
      selection: Option[F[ChainSelection.Local]] = None,
      recovered: Option[Recovered[F]] = None
  ): F[BlockPool[F]] =
    Ref
      .of[F, Slots[F]](Slots(Map.empty, 0L, 0L, Map.empty, 0, None))
      .flatMap(slots =>
        FetchCoordinator
          .create[F](
            BlockFetchPolicy.Config(
              policy.bulkConcurrency,
              policy.deadlineConcurrency,
              policy.maxRequestsPerPeer
            ),
            selection,
            Some(
              (slots.get, recovered.traverse(_.hashes)).mapN { (current, durable) =>
                current.entries.collect { case (hash, Slot.Done(_, _, _)) => hash }.toSet ++
                  current.processed.keySet ++ durable.getOrElse(Set.empty)
              }
            ),
            policy.peerSalt
          )
          .flatMap(coordinator => NoVerdictBlocks.create[F].map(coordinator -> _))
          .flatMap((coordinator, noVerdict) =>
            InvalidBlocks
              .create[F](cutoff =>
                // the blocks without a verdict are pruned at the same cutoff as the invalid ones
                noVerdict.pruneBefore(cutoff) *> slots.update { current =>
                  val expired = current.headers.collect {
                    case (hash, metadata)
                        if metadata.header.slot < cutoff && !current.fresh(hash) =>
                      hash
                  }.toSet
                  current.copy(
                    entries = current.entries -- expired,
                    headers = current.headers -- expired,
                    fresh = current.fresh -- expired,
                    pinned = current.pinned -- expired,
                    processed =
                      current.processed.filter((_, a) => a.delivered.header.slot >= cutoff)
                  )
                }
              )
              .flatMap(invalid =>
                BlockAdmissions
                  .create[F]
                  .map(admissions =>
                    new BlockPool(
                      slots,
                      policy,
                      attribution,
                      coordinator,
                      recovered,
                      invalid,
                      noVerdict,
                      admissions
                    )
                  )
              )
          )
      )

  /** The node owns the expiry worker; release removes callbacks and wakes any remaining waiters. */
  def resource[F[_]: Async](policy: FetchPolicy): Resource[F, BlockPool[F]] =
    resource(policy, AttributionPolicy())

  private[node] def resource[F[_]: Async](
      policy: FetchPolicy,
      attribution: AttributionPolicy
  ): Resource[F, BlockPool[F]] =
    Resource.eval(create[F](policy, attribution)).flatTap(_.maintenance)

  private enum Slot[F[_]]:
    case Pending(done: Deferred[F, Option[Filled]], seq: Long, owners: Set[Long])
    case Done(block: Bytes, seq: Long, origin: Source)

  private final case class Origin[F[_]](
      blame: Deferred[F, ValidationError],
      report: Option[ValidationError => F[Unit]],
      delivered: Boolean,
      expires: Option[FiniteDuration],
      punishment: Ref[F, DiffusionPunishment.State],
      diffusion: Diffusion
  )

  /** @param origins
    *   Where a session is told that a block it delivered was refused.
    */
  private final case class Slots[F[_]](
      entries: Map[Bytes, Slot[F]],
      next: Long,
      owners: Long,
      origins: Map[Long, Origin[F]],
      released: Int,
      reapAt: Option[FiniteDuration],
      headers: Map[Bytes, DeliveredHeader] = Map.empty,
      fresh: Set[Bytes] = Set.empty,
      pinned: Set[Bytes] = Set.empty,
      admissions: BigInt = 0,
      processed: Map[Bytes, Processed] = Map.empty
  ):
    def pruned(now: FiniteDuration, policy: AttributionPolicy): Slots[F] =
      if released <= policy.retained && reapAt.forall(_ > now) then this
      else prune(now, policy)

    private def prune(now: FiniteDuration, policy: AttributionPolicy): Slots[F] =
      val released = origins.toVector.collect {
        case (id, o) if o.expires.isDefined =>
          (id, o.expires.getOrElse(Duration.Zero))
      }
      val retained = released
        .filter(_._2 > now)
        .sortBy((id, expiry) => (expiry, id))
        .takeRight(policy.retained.max(0))
        .map(_._1)
        .toSet
      val keep = origins.filter((id, o) => o.expires.isEmpty || retained(id))
      val dropped = origins.keySet -- keep.keySet
      val retainedEntries = entries.filter {
        case (hash, Slot.Done(_, _, Source.Session(origin))) => pinned(hash) || !dropped(origin)
        case _                                               => true
      }
      copy(
        origins = keep,
        released = retained.size,
        reapAt = keep.values.flatMap(_.expires).minOption,
        entries = retainedEntries,
        headers = headers.filter((hash, _) => retainedEntries.contains(hash)),
        fresh = fresh.filter(retainedEntries.contains)
      )

    /** Without the finished entries older than the newest `Retained` claims, once the pool holds
      * twice as many (a walk of the map, so not on every claim).
      */
    private[BlockPool] def coherent: Slots[F] = copy(
      headers = headers.filter((hash, _) => entries.contains(hash)),
      fresh = fresh.filter(entries.contains),
      pinned = pinned.filter(entries.contains)
    )

    def trimmed: Slots[F] =
      if entries.size <= 2 * Retained then this
      else
        val floor = next - Retained
        val retainedEntries = entries.filter {
          case (hash, Slot.Done(_, seq, _)) => seq >= floor || fresh(hash) || pinned(hash)
          case _                            => true
        }
        copy(
          entries = retainedEntries,
          headers = headers.filter((hash, _) => retainedEntries.contains(hash))
        )
