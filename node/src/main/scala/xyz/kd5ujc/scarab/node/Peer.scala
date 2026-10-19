// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.{Block, Header, PeerError, ValidationError}
import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.syntax.all.*
import cats.effect.std.{Mutex, Queue, Semaphore, Supervisor}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, SocketAddress}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{CardanoBlockFetch, ChainSync, NtNHeader}
import xyz.kd5ujc.scarab.network.{
  ChainSyncClient,
  NetworkError,
  NodeToNode,
  NodeToNodeConnection,
  TxSubmissionClient
}
import xyz.kd5ujc.scarab.network.selection.{FetchMode, Time}

enum SyncEvent:
  case RollForward(header: Header, tip: ChainSync.Tip)
  case RollBackward(point: ChainSync.Point, tip: ChainSync.Tip)

/** Upstream chain source: one ChainSync conversation plus block retrieval. The methods raise
  * through the `Raise` of the fiber that calls them: a session runs in one fiber, the pipeline
  * behind it in others, and a capability fixed when the peer is made would belong to the wrong one
  * (issue 132).
  */
trait Peer[F[_]]:
  /** The newest point among `candidates` (ordered newest first) the peer also has, if any. */
  def intersect(candidates: Vector[ChainSync.Point])(using
      Raise[F, PeerError]
  ): F[Option[ChainSync.Point]]

  /** Next roll-forward or roll-backward instruction, waiting while the peer is at its tip. */
  def next(using Raise[F, PeerError]): F[SyncEvent]

  /** Raw node-to-node block bytes for a header announced by `next`. */
  def fetch(header: Header)(using Raise[F, PeerError]): F[Bytes]

object Peer:
  /** A peer whose request pipeline can be brought to rest between two batches. */
  trait Controlled[F[_]] extends Peer[F]:
    /** Stop requesting headers and wait until the pipeline is idle: no request outstanding on
      * ChainSync, none fetching blocks. What the peer announced meanwhile is dropped. Afterwards
      * the connection's hot protocols can be ended on the wire ([[NodeToNodeConnection.finish]]).
      * It waits for the peer to answer the request that is in flight, which at the peer's tip is
      * the next block; the caller bounds the wait.
      */
    def quiesce: F[Unit]

    /** The rules refused a block, the last one this session fetched. When another session delivered
      * it (the pool is shared), the refusal is not this peer's: that session is told (its next
      * instruction raises [[PeerError.InvalidBlock]]), the blocks it delivered and nobody has taken
      * are dropped, and the answer is `true`. When this session fetched it, `false`.
      */
    def refused(refusal: ValidationError): F[Boolean]

  /** A `Raise` for `E2` over one for `E1`: what is raised is mapped by `f`. */
  private[node] def contramapped[F[_], E1, E2](scope: Raise[F, E1])(f: E2 => E1): Raise[F, E2] =
    new Raise[F, E2]:
      def functor = scope.functor
      def raise[E3 <: E2, A](e: E3): F[A] = scope.raise[E1, A](f(e))

  /** Network failures as peer errors: where a fiber raises a [[PeerError]] it can call the network
    * layer, and what the network layer raises is classified by [[networkFault]]. Import it where
    * the clients are called.
    */
  given networkAsPeer[F[_]: cats.ApplicativeThrow](using
      peer: Raise[F, PeerError]
  ): Raise[F, NetworkError] =
    new Raise[F, NetworkError]:
      def functor = peer.functor
      def raise[E <: NetworkError, A](error: E): F[A] = Fault.land(Left(networkFault(error)))

  /** What a session measured of its peer, for the log. */
  enum Observation:
    /** One pipelined ChainSync round: `requested` RequestNext sent at once, `received` instructions
      * back after `millis` (from the first request to the last reply). D3 uses it to see how long a
      * relay takes to answer a deep pipeline.
      */
    case Round(requested: Int, received: Int, millis: Long)

    /** One block fetched on its own (at the tip), from the request to the block. */
    case Fetched(millis: Long)

    /** The tip the peer announced with the last instruction of a batch of ChainSync replies. */
    case Announced(tip: ChainSync.Tip)

    /** The headers of one batch of ChainSync replies reached us together, at `at` on the monotonic
      * clock; `slots` are those of the roll-forwards, in order. The peer metrics
      * (`PeerMetrics.reportHeader`, the reference's `reportHeader` per header) count the peer that
      * is first with a slot. One observation per batch, not per header: the headers of a pipelined
      * batch share their arrival time, and the callback stays off the per-header path.
      */
    case HeadersArrived(slots: Vector[Long], at: Time)

    /** The blocks of one BlockFetch request reached us, each as `(slot, size in bytes)`, at `at`
      * (`reportFetch` per block in the reference).
      */
    case BlocksArrived(blocks: Vector[(Long, Long)], at: Time)

  final case class Settings(
      address: SocketAddress[Host],
      magic: Long,
      network: NodeToNode.Settings = NodeToNode.Settings(),
      /** Most ChainSync requests pipelined at once while the peer is far ahead (D3: 128, see
        * docs/performance.md; 64 to 256 were measured against the public Preview relay).
        */
      readAhead: Int = 128,
      /** Slots per Byron epoch (10k of the Byron genesis), to place Byron headers on the slot axis.
        */
      byronEpochSlots: BigInt
  )

  /** The most intersection candidates one FindIntersect carries. */
  private val MaxCandidates = 64

  /** At most [[MaxCandidates]] of `candidates` (newest first): the newest 16, then every second,
    * fourth, eighth... one, and always the last (the oldest, which the anchor is).
    */
  private[node] def thin[A](candidates: Vector[A]): Vector[A] =
    if candidates.size <= MaxCandidates then candidates
    else
      val picked = Iterator
        .unfold((0, 1)) { case (i, step) =>
          Option.when(i < candidates.size)(
            i -> ((i + step, if i >= 15 then step * 2 else step))
          )
        }
        .take(MaxCandidates - 1)
        .toVector
      (picked.map(candidates(_)) :+ candidates.last).distinct

  /** Split announced headers into runs one BlockFetch request accepts: at most 100 blocks and about
    * 8 MB of announced body (the client refuses more than 10 MB per request).
    */
  private[node] def ranges(headers: Vector[Header]): Vector[Vector[Header]] =
    val budget = 8000000L
    val (completed, current, _) =
      headers.foldLeft((Vector.empty[Vector[Header]], Vector.empty[Header], 0L)) {
        case ((completed, current, size), header) =>
          val bytes = header match
            case shelley: Header.Shelley => shelley.bodySize.toLong + shelley.raw.size + 512
            case _                       => 0L
          if current.nonEmpty && (current.size >= 100 || size + bytes > budget) then
            (completed :+ current, Vector(header), bytes)
          else (completed, current :+ header, size + bytes)
      }
    if current.nonEmpty then completed :+ current else completed

  /** The maximal runs of consecutive headers the caller owns (the others were claimed by another
    * session, which fetches them).
    */
  private[node] def ownedRuns(flagged: Vector[(Header, Boolean)]): Vector[Vector[Header]] =
    val (completed, current) =
      flagged.foldLeft((Vector.empty[Vector[Header]], Vector.empty[Header])) {
        case ((completed, current), (header, true)) => (completed, current :+ header)
        case ((completed, current), (_, false)) =>
          if current.nonEmpty then (completed :+ current, Vector.empty) else (completed, current)
      }
    if current.nonEmpty then completed :+ current else completed

  /** Batches the peer keeps in flight: one being applied, the others being fetched. */
  private[node] val Depth = 3

  /** Called with each prefetched batch of raw blocks before they are handed to the rules. */
  type Prepare[F[_]] = Vector[Bytes] => F[Unit]

  /** `host:port` where host is a DNS name or an IP literal. */
  enum AddressError extends DomainError:
    case Invalid(text: String)
    def message: String = this match
      case Invalid(text) => s"invalid peer address '$text', expected <host>:<port>"
  def parseAddress(text: String): Either[AddressError, SocketAddress[Host]] =
    SocketAddress.fromString(text).toRight(AddressError.Invalid(text))

  /** The node-level classification of a network failure. */
  def networkFault(error: NetworkError): Fault = error match
    case NetworkError.Defect(cause)   => Fault.Defect(cause)
    case NetworkError.Framing(cause)  => Fault.Failed(PeerError.Framing(cause))
    case NetworkError.MuxSetup(cause) => Fault.Failed(PeerError.MuxSetup(cause))
    case NetworkError.MuxWrite(cause) => Fault.Failed(PeerError.MuxWrite(cause))
    case cause: (NetworkError.Connect | NetworkError.Timeout | NetworkError.SduTimeout |
          NetworkError.BearerClosed) =>
      Fault.Failed(PeerError.Unavailable(PeerError.Unavailability.Network(cause)))
    case cause: (NetworkError.HandshakeRefused | NetworkError.VersionMismatch) =>
      Fault.Failed(PeerError.Handshake(cause))
    case cause: (NetworkError.IngressLimit | NetworkError.Decode | NetworkError.IllegalTransition |
          NetworkError.QueryRefused | NetworkError.TxSubmission) =>
      Fault.Failed(PeerError.Protocol(cause))
    case failure: NetworkError.StateAcquireFailure =>
      Fault.Defect(new NetworkError.InternalFailure(failure))

  /** Node-to-node interpreter over `network`: ChainSync and BlockFetch share one multiplexed
    * connection (KeepAlive runs in the background). Expected network failures surface as typed
    * [[PeerError]] values in the calling fiber; an unexpected defect preserves its original cause
    * for the session's fatal boundary.
    */
  def nodeToNode[F[_]: Async](
      settings: Settings,
      prepare: Prepare[F],
      transactions: Option[TxSubmissionClient.Source[F]] = None,
      headers: Option[HeaderCheck[F]] = None,
      observe: Option[Peer.Observation => F[Unit]] = None,
      pool: Option[BlockPool[F]] = None,
      fetchMode: Option[F[FetchMode]] = None
  )(using
      Raise[F, PeerError]
  ): Resource[F, Peer[F]] =
    NodeToNode
      .connect[F, NtNHeader](
        settings.address,
        settings.magic,
        NtNHeader.codec,
        settings.network,
        transactions
      )
      .flatMap(over(_, settings, prepare, headers, observe, pool, fetchMode))

  /** The peer interface over a connection that is already negotiated: one we opened
    * ([[nodeToNode]]) or an inbound duplex connection on which we run our own clients (G2). The
    * pipelined producer lives as long as the resource; the connection is not closed by it.
    *
    * With `headers`, every batch of ChainSync headers is validated before the blocks of the batch
    * are fetched (G6): a header that breaks a rule raises [[PeerError.InvalidHeader]] and no block
    * of its batch is requested.
    *
    * The fibers of the pipeline each run in an error scope of their own ([[Fault.fiber]]) and hand
    * their fault to the session as a value in the queue of batches; the session raises it again in
    * its own fiber, through the `Raise` it passes to [[Peer.next]].
    */
  def over[F[_]: Async](
      connection: NodeToNodeConnection[F, NtNHeader],
      settings: Settings,
      prepare: Prepare[F],
      headers: Option[HeaderCheck[F]] = None,
      observe: Option[Peer.Observation => F[Unit]] = None,
      /** Where fetched blocks wait for the session that applies them; share one between the
        * sessions of several peers so that each block is fetched from one of them only. Without
        * one, the session has a pool of its own.
        */
      pool: Option[BlockPool[F]] = None,
      /** The fetch mode the node is in now (bulk sync or deadline): it decides whether a block
        * another session has claimed may be requested too ([[BlockPool]]). Bulk sync without it.
        */
      fetchMode: Option[F[FetchMode]] = None
  ): Resource[F, Controlled[F]] =
    val F = Async[F]
    val currentMode: F[FetchMode] = fetchMode.getOrElse(F.pure(FetchMode.BulkSync))
    def protocol[A](reason: PeerError.ProtocolFailure)(using Raise[F, PeerError]): F[A] =
      PeerError.Protocol(reason).raise[F, A]

    // The session's claims on the pool come first, so they are released last: after the supervisor
    // has cancelled and awaited the fibers that fetch, nothing the session claimed is left open.
    (for
      shared <- Resource.eval(pool.fold(BlockPool.create[F])(F.pure))
      claims <- shared.session
      supervisor <- Supervisor[F](await = false)
    yield (claims, supervisor)).evalMap { (claims, supervisor) =>
      // Read-ahead: while the peer is far ahead, ChainSync requests are pipelined and the
      // blocks of the whole batch are fetched with one BlockFetch range. A producer fiber
      // keeps up to `Depth` batches in flight: it requests the headers of the next batch while
      // the blocks of the previous ones are still being fetched and verified, and publishes
      // the batches to `queue` in order. The consumer applies one batch while the network and
      // the (parallel) signature checks work on the following ones. `blocks` holds the
      // prefetched blocks by header hash.
      //
      // The queue holds at most `Depth` batches (a permit per batch, returned when the batch is
      // taken) and the faults of the fibers that did not finish theirs: one per permit still held,
      // and the producer's own.
      (
        Queue.bounded[F, Either[Fault, Vector[SyncEvent]]](Depth + 1),
        Ref.of[F, Vector[SyncEvent]](Vector.empty),
        Ref.of[F, Option[(ChainSync.Tip, BigInt)]](None),
        Ref.of[F, Boolean](false),
        Semaphore[F](Depth.toLong),
        Mutex[F],
        Ref.of[F, HeaderGate[F]](HeaderGate.none[F]),
        Ref.of[F, Boolean](false),
        Deferred[F, Unit],
        Ref.of[F, Option[(Bytes, BlockPool.Taken)]](None)
      ).mapN {
        (queue, current, progress, started, permits, fetchLock, gate, stopping, ended, last) =>
          def decode(update: ChainSyncClient.Update[NtNHeader])(using
              Raise[F, PeerError]
          ): F[SyncEvent] = update match
            case ChainSyncClient.Update.RollForward(h, tip) =>
              Header
                .fromNtN(h, settings.byronEpochSlots)
                .fold(
                  e => protocol(PeerError.ProtocolProblem.HeaderDecode(e)),
                  h => F.pure(SyncEvent.RollForward(h, tip))
                )
            case ChainSyncClient.Update.RollBackward(point, tip) =>
              F.pure(SyncEvent.RollBackward(point, tip))

          /** Prefetch the blocks announced by the leading roll-forwards of a batch, in ranges the
            * client's per-request limits accept (a batch of full blocks is more than 10 MB).
            */
          def prefetch(batch: Vector[SyncEvent])(using Raise[F, PeerError]): F[Unit] =
            val headers = batch.takeWhile(_.isInstanceOf[SyncEvent.RollForward]).collect {
              case SyncEvent.RollForward(h, _) => h
            }
            ranges(headers).traverse_(prefetchRange)

          /** Fetch the blocks of one range that no other session has claimed. The claim is made
            * when this connection is free to send the request, so with several peers the ranges of
            * a batch go to whichever connection is idle (the reference's bulk-sync decision keeps a
            * block in flight with one peer only).
            */
          def prefetchRange(headers: Vector[Header])(using Raise[F, PeerError]): F[Unit] =
            fetchLock.lock
              .surround(
                currentMode.flatMap(claims.claim(headers.map(_.hash), _)).flatMap { owned =>
                  val runs = ownedRuns(headers.zip(owned))
                  val mine = runs.flatMap(_.map(_.hash))
                  // a failure or a cancellation anywhere in the range gives up every claim of it, not
                  // only the run in flight (issue 130); the session's own claims are the backstop
                  runs
                    .traverse(run => requestRun(run).map(run -> _))
                    .onError(_ => claims.release(mine))
                    .onCancel(claims.release(mine))
                }
              )
              .flatMap(_.traverse_ { case (run, (fetched, arrivedAt)) =>
                fetched.fold(claims.release(run.map(_.hash))) { raw =>
                  // A range answers in chain order, one block per announced header; the rules
                  // check each block against the header they were handed.
                  val paired: Vector[(Bytes, Long, Bytes)] =
                    if raw.size == run.size then
                      run.zip(raw).map((h, b) => (h.hash, h.slot.toLong, b))
                    else
                      raw.flatMap(b =>
                        Block
                          .decode(b, settings.byronEpochSlots)
                          .toOption
                          .map(block => (block.header.hash, block.header.slot.toLong, b))
                      )
                  val byHash = paired.map((hash, _, b) => hash -> b)
                  observe.traverse_(
                    _(
                      Peer.Observation
                        .BlocksArrived(paired.map((_, slot, b) => slot -> b.size.toLong), arrivedAt)
                    )
                  ) *>
                    (prepare(raw) *> claims.fill(byHash)).guarantee(claims.release(run.map(_.hash)))
                }
              })

          /** One BlockFetch request for a run, with the time its blocks arrived. */
          def requestRun(run: Vector[Header])(using
              Raise[F, PeerError]
          ): F[(Option[Vector[Bytes]], Time)] =
            connection.blockFetch.requestRange(run.head.point, run.last.point).flatMap { fetched =>
              F.monotonic.map(fetched -> _)
            }

          /** The next batch of ChainSync instructions. */
          def requestHeaders(using Raise[F, PeerError]): F[Vector[SyncEvent]] =
            for
              seen <- progress.get
              ahead = seen.fold(BigInt(0)) { (tip, blockNo) =>
                (tip.blockNo.value - blockNo).max(0)
              }
              n = (ahead - 1).min(settings.readAhead).max(1).toInt
              began <- F.monotonic
              updates <- connection.chainSync.requestNextBatch(n)
              arrived <- F.monotonic
              took = arrived - began
              _ <- observe.traverse_(_(Peer.Observation.Round(n, updates.size, took.toMillis)))
              batch <- updates.traverse(decode)
              _ <- observe.traverse_ { report =>
                val slots = batch.collect { case SyncEvent.RollForward(h, _) => h.slot.toLong }
                report(Peer.Observation.HeadersArrived(slots, arrived)).whenA(slots.nonEmpty)
              }
              _ <- batch.lastOption.traverse_ {
                case SyncEvent.RollForward(_, tip) =>
                  observe.traverse_(_(Peer.Observation.Announced(tip)))
                case SyncEvent.RollBackward(_, tip) =>
                  observe.traverse_(_(Peer.Observation.Announced(tip)))
              }
              // header-first: nothing of this batch is fetched before its headers are valid
              _ <- gate.get.flatMap(_.accept(batch))
              _ <- batch.lastOption.traverse_ {
                case SyncEvent.RollForward(h, tip) => progress.set(Some(tip -> h.blockNo))
                case SyncEvent.RollBackward(_, tip) =>
                  progress.set(Some(tip -> tip.blockNo.value))
              }
            yield batch

          def fail(fault: Fault): F[Unit] = queue.offer(Left(fault))

          /** A fiber of the pipeline: its body in an error scope of its own, and the fault it ends
            * with handed to the session through the queue.
            */
          def supervised(body: Raise[F, PeerError] ?=> F[Unit]): F[Unit] =
            supervisor.supervise(Fault.fiber(body).flatMap(_.fold(fail, _ => F.unit))).void

          /** Request batches for ever: the blocks of each are fetched in a fiber of its own and the
            * batch is published once the previous one has been, so order is kept.
            */
          def produce(previous: Deferred[F, Unit])(using Raise[F, PeerError]): F[Unit] =
            for
              _ <- permits.acquire
              halt <- stopping.get
              _ <-
                if halt then
                  // brought to rest: the batches requested so far are published, then it is idle
                  previous.get *> ended.complete(()).void
                else
                  for
                    batch <- requestHeaders
                    published <- Deferred[F, Unit]
                    _ <- supervised(
                      prefetch(batch) *> previous.get *> queue.offer(Right(batch)) *>
                        published.complete(()).void
                    )
                    _ <- produce(published)
                  yield ()
            yield ()

          val start: F[Unit] =
            started.getAndSet(true).flatMap {
              case true => F.unit
              case false =>
                supervised(Deferred[F, Unit].flatTap(_.complete(())).flatMap(produce))
            }

          /** The next instruction, raising the fault of a fiber of the pipeline in the calling
            * fiber: its peer error through `Raise`, an exception that is not ours as it came.
            */
          def pull(using Raise[F, PeerError]): F[SyncEvent] =
            start *> current.modify(q => (q.drop(1), q.headOption)).flatMap {
              case Some(event) => F.pure(event)
              case None        =>
                // a block this session delivered was refused by the rules, wherever it was applied
                F.race(claims.blamed, queue.take).flatMap {
                  case Left(refusal) => PeerError.InvalidBlock(refusal).raise[F, SyncEvent]
                  case Right(Left(Fault.Failed(error))) => error.raise[F, SyncEvent]
                  case Right(Left(Fault.Defect(cause))) => F.raiseError[SyncEvent](cause)
                  case Right(Right(batch)) => permits.release *> current.set(batch) *> pull
                }
            }

          def fetchBlock(header: Header)(using Raise[F, PeerError]): F[Bytes] =
            claims.take(header.hash).flatMap {
              case Some(taken) => last.set(Some(header.hash -> taken)).as(taken.block)
              case None =>
                CardanoBlockFetch.SpecificPoint.from(header.point) match
                  case Left(reason) => protocol(reason)
                  case Right(specific) =>
                    fetchLock.lock
                      .surround(
                        for
                          began <- F.monotonic
                          fetched <- connection.blockFetch
                            .requestRange(specific.point, specific.point)
                          arrived <- F.monotonic
                          took = arrived - began
                          _ <- observe.traverse_(_(Peer.Observation.Fetched(took.toMillis)))
                          _ <- observe.traverse_(report =>
                            fetched.traverse_(
                              _.traverse_(b =>
                                report(
                                  Peer.Observation.BlocksArrived(
                                    Vector(header.slot.toLong -> b.size.toLong),
                                    arrived
                                  )
                                )
                              )
                            )
                          )
                        yield fetched
                      )
                      .flatMap {
                        case Some(Vector(block)) => F.pure(block)
                        case Some(other) =>
                          protocol(PeerError.ProtocolProblem.BlockCount(1, other.size))
                        case None =>
                          PeerError
                            .Unavailable(
                              PeerError.Unavailability.MissingAnnouncedBlock(header.point)
                            )
                            .raise
                      }
            }

          new Controlled[F]:
            def quiesce: F[Unit] =
              stopping.set(true) *> started.getAndSet(true).flatMap {
                // the producer never ran: it never will
                case false => ended.complete(()).void
                // it may wait for a permit that only a consumer returns: consume and drop; a fault
                // met on the way is of no interest, the session is ending
                case true =>
                  F.race(ended.get, Fault.fiber(pull.foreverM[Unit])).void
              }

            def intersect(candidates: Vector[ChainSync.Point])(using Raise[F, PeerError]) =
              current.set(Vector.empty) *> progress.set(None) *>
                connection.chainSync.findIntersect(thin(candidates)).flatMap { found =>
                  found.traverse_ { case (point, _) =>
                    headers.traverse_(_.start(point).flatMap(gate.set))
                  } *> F.pure(found.map(_._1))
                }

            def next(using Raise[F, PeerError]): F[SyncEvent] = pull

            def refused(refusal: ValidationError): F[Boolean] =
              last.get.flatMap {
                case Some((fetched, taken)) if !taken.own && (refusal.point match
                      case ChainSync.Point.Block(_, hash) => hash == fetched
                      case ChainSync.Point.Origin => false) =>
                  claims.distrust(taken.origin, refusal).as(true)
                case _ => F.pure(false)
              }

            def fetch(header: Header)(using Raise[F, PeerError]): F[Bytes] = fetchBlock(header)
      }
    }
