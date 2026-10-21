// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.syntax.all.*
import cats.Functor
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.ValidationError
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
  * oldest bodies past [[BlockPool.Retained]] entries.
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
    recovered: Option[BlockPool.Recovered[F]]
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
  private[node] def session(report: Option[ValidationError => F[Unit]]): Resource[F, Claims[F]] =
    Resource
      .make(
        Deferred[F, ValidationError].flatMap(blame =>
          slots
            .modify(s =>
              (
                s.copy(
                  owners = s.owners + 1,
                  origins = s.origins.updated(s.owners, Origin(blame, report, false, None))
                ),
                s.owners
              )
            )
            .map(_ -> blame)
        )
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
  private def fill(owner: Long, blocks: Vector[(Bytes, Bytes)]): F[Unit] =
    reap *> slots
      .modify { s =>
        blocks.foldLeft((s, Vector.empty[(Deferred[F, Option[Filled]], Filled)])) {
          case ((acc, waiting), (hash, block)) =>
            acc.entries.get(hash) match
              case Some(Slot.Pending(done, seq, owners))
                  if owners(owner) && acc.origins.get(owner).exists(_.expires.isEmpty) =>
                (
                  acc.copy(
                    entries =
                      acc.entries.updated(hash, Slot.Done(block, seq, Source.Session(owner))),
                    origins = acc.origins.updatedWith(owner)(_.map(_.copy(delivered = true)))
                  ),
                  waiting :+ (done -> Filled(block, Source.Session(owner)))
                )
              case _ => (acc, waiting)
        }
      }
      .flatMap { completed =>
        completed.traverse_((done, filled) => done.complete(Some(filled))) *>
          fetchCoordinator.refreshGenesis.whenA(completed.nonEmpty)
      }

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
          (s, F.pure(Option(Filled(block, origin))))
        case Some(Slot.Pending(done, _, _)) =>
          (s, done.get)
        case None =>
          (s, recovered.traverse(_.get(hash)).map(_.flatten.map(Filled(_, Source.Recovered))))
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
        slots.update(s => s.copy(entries = s.entries -- refusedHash(error)))
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
            released.copy(
              entries = released.entries.filter {
                case (_, Slot.Done(_, _, Source.Session(from))) => from != origin
                case _                                          => true
              },
              origins = s.origins - origin,
              released =
                s.released - (if s.origins.get(origin).exists(_.expires.isDefined) then 1 else 0)
            ),
            (s.origins.get(origin), waits)
          )
        }
        .flatMap { (source, waits) =>
          waits.traverse_(_.complete(None)) *>
            source.traverse_(o => o.report.fold(o.blame.complete(error).void)(_(error)))
        }
    }

  private def retained(source: Source, hash: Bytes): F[Boolean] =
    reap *> (source match
      case Source.Session(origin) => slots.get.map(_.origins.contains(origin))
      case Source.Recovered =>
        (slots.get, recovered.traverse(_.hashes)).mapN { (current, durable) =>
          current.entries.contains(hash) || durable.exists(_(hash))
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
        s.copy(entries = Map.empty, origins = Map.empty, released = 0, reapAt = None)
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
    def fill(blocks: Vector[(Bytes, Bytes)]): F[Unit] = pool.fill(owner, blocks)

    /** Provenance of original bytes delivered by this connection, independently of which peer won a
      * duplicate pool claim. Genesis can validate that delivery before its range ends.
      */
    private[node] def delivered(block: Bytes): Taken =
      Taken(block, own = true, Source.Session(owner))
    def release(hashes: Vector[Bytes]): F[Unit] = pool.release(owner, hashes)

    /** The block, once some session has fetched it (see [[BlockPool.take]]), and whether this
      * session fetched it.
      */
    def take(hash: Bytes)(using Functor[F]): F[Option[Taken]] =
      pool
        .takeFilled(hash)
        .map(_.map(f => Taken(f.block, f.origin == Source.Session(owner), f.origin)))

    /** The rules refused a block that session `origin` delivered: drop the others it delivered and
      * tell it ([[blamed]]).
      */
    def distrust(origin: Source, error: ValidationError): F[Unit] = pool.distrust(origin, error)

    /** A staged body cannot outlive invalidation or the retained attribution of its source. */
    private[node] def retained(origin: Source, hash: Bytes): F[Boolean] =
      pool.retained(origin, hash)

    /** Completes when the rules have refused a block that this session delivered, with their
      * reason: the session's peer sent an invalid block.
      */
    def blamed: F[ValidationError] = blame.get

  /** A block and the session that delivered it. */
  enum Source:
    case Session(owner: Long)
    case Recovered

  /** Durable callbacks return readable original bytes and own their infrastructure error scope. */
  final case class Recovered[F[_]](
      hashes: F[Set[Bytes]],
      get: Bytes => F[Option[Bytes]],
      invalidate: Bytes => F[Unit]
  )

  final case class Filled(block: Bytes, origin: Source)

  /** A block taken from the pool: `own` when the session that takes it fetched it, else `origin`
    * delivered it.
    */
  final case class Taken(block: Bytes, own: Boolean, origin: Source)

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
                  durable.getOrElse(Set.empty)
              }
            ),
            policy.peerSalt
          )
          .map(new BlockPool(slots, policy, attribution, _, recovered))
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
      expires: Option[FiniteDuration]
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
      reapAt: Option[FiniteDuration]
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
      copy(
        origins = keep,
        released = retained.size,
        reapAt = keep.values.flatMap(_.expires).minOption,
        entries = entries.filter {
          case (_, Slot.Done(_, _, Source.Session(origin))) => !dropped(origin)
          case _                                            => true
        }
      )

    /** Without the finished entries older than the newest `Retained` claims, once the pool holds
      * twice as many (a walk of the map, so not on every claim).
      */
    def trimmed: Slots[F] =
      if entries.size <= 2 * Retained then this
      else
        val floor = next - Retained
        copy(entries = entries.filter {
          case (_, Slot.Done(_, seq, _)) => seq >= floor
          case _                         => true
        })
