// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.{
  BlockPoolProblem,
  ChainSyncInternalProblem,
  Header,
  PeerError,
  ValidationError
}
import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.implicits.*
import cats.effect.std.{Queue, Semaphore}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, SocketAddress}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import xyz.kd5ujc.scarab.network.{
  BlockFetchRuntime,
  ChainSyncClient,
  ChainSyncPipelineDecision,
  NetworkError,
  NodeToNode,
  NodeToNodeConnection,
  OrderedSupervisor,
  TxSubmissionClient
}
import xyz.kd5ujc.scarab.network.selection.{ChainSyncJumping, FetchGsv, Time}
import xyz.kd5ujc.scarab.network.genesis.LimitOnPatience
import scala.concurrent.duration.*
import scala.collection.immutable.Queue as ImmutableQueue

enum SyncEvent:
  case RollForward(header: Header, tip: ChainSync.Tip)
  case RollBackward(point: ChainSync.Point, tip: ChainSync.Tip)

/** Upstream chain source: one ChainSync conversation plus block retrieval. The methods raise
  * through the `Raise` of the fiber that calls them: a session runs in one fiber, the pipeline
  * behind it in others, and a capability fixed when the peer is made would belong to the wrong one
  * (issue 132).
  */
trait Peer[F[_]]:
  /** The validated ChainSync fragment, independent of ordered body delivery. */
  def headerCandidate: Option[Ref[F, ChainSelection.Candidate]] = None
  def selectionWork: Option[SelectionWork.Session[F]] = None
  def invalidBlocks: Option[InvalidBlocks[F]] = None

  /** The newest point among `candidates` (ordered newest first) the peer also has, if any. Any
    * outstanding requests are drained first; buffered instructions from the old intersection are
    * discarded. The next instruction belongs to the new intersection on the same connection.
    */
  def intersect(candidates: Vector[ChainSync.Point])(using
      Raise[F, PeerError]
  ): F[Option[ChainSync.Point]]

  /** Next roll-forward or roll-backward instruction, waiting while the peer is at its tip. */
  def next(using Raise[F, PeerError]): F[SyncEvent]

object Peer:
  /** How a session validates the headers its peer announces. A Genesis session's check grafts, and
    * the session hands the gates it starts to its Genesis controls, never the other way round
    * (issues 593, 603).
    */
  enum Chain[F[_]]:
    case Praos(headers: Option[HeaderCheck[F]])
    case Genesis(headers: HeaderCheck.Grafting[F], control: GenesisPeer[F])

  enum Rejection:
    case Forgiven, PunishedForeign, NotImplicated, NoAddingClient

  sealed trait Validation[F[_]]:
    def addedPoint: Option[ChainSync.Point]
    def admissionOrder: Option[BigInt]
    def rejected(refusal: ValidationError, lastValid: ChainSync.Point)(using
        cats.Applicative[F]
    ): F[Either[BlockPoolProblem, Rejection]]

  object Validation:
    private[node] def none[F[_]]: Validation[F] = new Validation[F]:
      def addedPoint = None
      def admissionOrder = None
      def rejected(refusal: ValidationError, lastValid: ChainSync.Point)(using
          F: cats.Applicative[F]
      ): F[Either[BlockPoolProblem, Rejection]] = F.pure(Right(Rejection.NoAddingClient))

    private[node] def admission[F[_]](
        pool: BlockPool[F],
        admission: BlockPool.Admission
    ): Validation[F] =
      new Validation[F]:
        def addedPoint = Some(admission.delivered.header.point)
        def admissionOrder = Some(admission.delivered.admission)
        def rejected(error: ValidationError, lastValid: ChainSync.Point)(using
            F: cats.Applicative[F]
        ) =
          pool
            .reject(admission.taken, admission.delivered.header.point, error, lastValid)
            .map(_.map {
              case BlockPool.Rejection.Forgiven(_)      => Rejection.Forgiven
              case BlockPool.Rejection.Punished(_)      => Rejection.PunishedForeign
              case BlockPool.Rejection.NotImplicated(_) => Rejection.NotImplicated
            })

  /** A session's candidate state and the signal of its next change. */
  private final case class Held[F[_]](state: CandidateState, changed: Deferred[F, Unit])

  private[node] def publicationRoom(
      published: ChainSelection.Candidate,
      candidate: ChainSelection.Candidate,
      retained: Int
  ): Boolean =
    if candidate.headers.sizeIs < retained.max(1) then true
    else
      val points = candidate.headers.map(_.point).toSet + candidate.base
      (published.base +: published.headers.map(_.point)).reverseIterator
        .find(points)
        .exists(
          _ != candidate.base
        )

  /** A candidate TVar coalesces unpublished wire changes in the reference. Keep that behavior:
    * headers coalesce independently of body downloads. At the retention boundary local capacity
    * waits preserve the common publication point without charging the peer's patience.
    */
  private[node] def publication(
      previous: ChainSelection.Candidate,
      latest: ChainSelection.Candidate,
      tip: ChainSync.Tip,
      limit: Int
  ): Either[PeerError.Divergence, (ChainSelection.Candidate, Vector[SyncEvent])] =
    // The newest point of `previous` that `latest` holds, and its last position in `latest` (the
    // base is 0). When `latest` only extends `previous`, that is `previous`'s tip at its own
    // position, unless it occurs again further on: found from the appended headers alone, without
    // indexing all of `latest` again for each header (issue 1394). The same holds when `latest`'s
    // base moved on to one of `previous`'s headers (its candidate was trimmed to our chain's
    // anchor): the tip's position is then fewer by the headers the trim dropped.
    val trimmed =
      if latest.base == previous.base then Some(0)
      else
        previous.headers.indexWhere(_.point == latest.base) match
          case -1 => None
          case at => Some(at + 1)
    val extending = trimmed.map(previous.headers.size - _).filter { kept =>
      latest.headers.sizeIs >= kept &&
      (kept == 0 || latest.headers.lift(kept - 1).exists(_.point == previous.tip)) &&
      latest.headers.drop(kept).forall(_.point != previous.tip)
    }
    val found: Option[(ChainSync.Point, Int)] =
      extending.map(previous.tip -> _).orElse {
        val positions = (latest.base +: latest.headers.map(_.point)).zipWithIndex.toMap
        (previous.base +: previous.headers.map(_.point)).reverseIterator
          .find(positions.contains)
          .map(point => point -> positions.getOrElse(point, 0))
      }
    found.toRight(PeerError.Divergence.DetachedCandidate(latest.base)).map { (point, start) =>
      val forward = latest.headers.drop(start).take(limit.max(1))
      val kept = latest.headers.take(start) ++ forward
      val after = ChainSelection.Candidate(latest.base, kept)
      val rollback = Option.when(previous.tip != point)(SyncEvent.RollBackward(point, tip)).toVector
      (after, rollback ++ forward.map(h => SyncEvent.RollForward(h, tip)))
    }

  private enum PipelineEvent:
    case Batch(events: Vector[SyncEvent])
    case Failed(fault: Fault)
    case Drained

  private enum DrainOutcome:
    case Complete
    case Failed(fault: Fault)
    case Cancelled

  /** Capture offered local block numbers before FindIntersect; failures belong to this node. */
  trait HeightSnapshot[F[_]]:
    def capture: F[HeightSnapshot.Captured]
    def unavailable[A](cause: ChainSyncInternalProblem): F[A]

  object HeightSnapshot:
    final case class Captured(
        points: Vector[ChainSync.Point],
        heights: Map[ChainSync.Point, Option[BigInt]]
    )

  /** A peer whose request pipeline can be brought to rest between two batches. */
  trait Controlled[F[_]] extends Peer[F]:
    /** Stop requesting headers and wait until the pipeline is idle: no request outstanding on
      * ChainSync, none fetching blocks. What the peer announced meanwhile is dropped. Afterwards
      * the connection's hot protocols can be ended on the wire ([[NodeToNodeConnection.finish]]).
      * It waits for the peer to answer the request that is in flight, which at the peer's tip is
      * the next block; the caller bounds the wait.
      */
    def quiesce: F[Unit]

    /** Raw node-to-node block bytes for a header announced by `next`, once the session's fetch
      * client has delivered it to the pool. For tools that read what a session fetched (the capture
      * tool): the node's blocks reach chain selection through the pool's admissions, never through
      * a session (issue 599).
      */
    def fetch(header: Header)(using Raise[F, PeerError]): F[Bytes]

  /** Cancellation cannot interrupt the finite worker-registration transaction after publishing the
    * started flag. Registration only supervises workers; their bodies remain cancelable.
    */
  private[node] def startOnce[F[_]: Async](started: Ref[F, Boolean])(register: F[Unit]): F[Unit] =
    Async[F].uncancelable(_ => started.getAndSet(true).flatMap(register.unlessA))

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

    /** One complete rolling ChainSync roll-forward or rollback collected, including replies
      * discarded while draining. AwaitReply is not a complete reply.
      */
    case HeaderReceived

    /** One block's download completed (the reference's `CompletedBlockFetch`), `millis` after its
      * range was sent.
      */
    case Fetched(millis: Long)

    /** The tip the peer announced with the last instruction of a batch of ChainSync replies. */
    case Announced(tip: ChainSync.Tip)

    /** Our ChainSync client found an intersection with the peer's chain at `point`: at the
      * session's start, and again each time it finds a new one (after NoLongerIntersects, or a
      * restart of chain-sync jumping), as the reference traces `TraceFoundIntersection` on every
      * intersection found (consensus 82ecba329 ChainSync/Client.hs 1092–1102, 1276–1285 and
      * 1502–1511; issue 602).
      */
    case Intersected(point: ChainSync.Point)

    /** The headers of one batch of ChainSync replies reached us together, at `at` on the monotonic
      * clock; `slots` are those of the roll-forwards, in order. The peer metrics
      * (`PeerMetrics.reportHeader`, the reference's `reportHeader` per header) count the peer that
      * is first with a slot. One observation per batch, not per header: the headers of a pipelined
      * batch share their arrival time, and the callback stays off the per-header path.
      */
    case HeadersArrived(slots: Vector[Long], at: Time)

    /** The blocks of one BlockFetch request reached us, each as `(slot, size in bytes)`, at `at`
      * (`reportFetch` per block in the reference). The size is the one the block's header announces
      * (`estimateBlockSize`, [[FetchCoordinator.estimatedBytes]]).
      */
    case BlocksArrived(blocks: Vector[(Long, Long)], at: Time)

  /** One pipelined session as a stall report reads it (issue 371): the state of the machinery
    * `streaming` waits on, taken in non-blocking reads. A session with no pipeline reports none.
    *
    * @param peer
    *   The session's peer address.
    * @param permitsFree
    *   Batch permits not taken, of `permits`: a permit is held from a batch's publication until the
    *   consumer takes the batch, so 0 free with an empty queue is a publisher waiting for itself.
    * @param queued
    *   Events in the batch queue, which the consumer takes.
    * @param candidateHeaders
    *   Headers of the validated candidate.
    * @param unpublishedHeaders
    *   Headers of the candidate beyond the newest point it shares with the published cursor
    *   ([[unpublished]]): what the publisher is still to publish, and what the ChainSync capacity
    *   wait is held by. None when the two share no point: a candidate that diverged from what was
    *   published, which the base and tip points tell from lag.
    * @param capacityRoom
    *   False while the candidate is at its retained size and either the published cursor has not
    *   caught up ([[publicationRoom]]) or its oldest header's block is not fetched yet, which is
    *   when a ChainSync reply waits.
    * @param candidateBase
    *   The candidate's base point, as `slot:hash` (`origin` at genesis).
    * @param candidateTip
    *   The candidate's last point, in the same form.
    * @param publishedBase
    *   The published cursor's base point.
    * @param publishedTip
    *   The published cursor's last point.
    * @param candidateTipSlot
    *   Slot of the candidate's last header.
    * @param publishedTipSlot
    *   Slot of the published cursor's last header.
    * @param lastReplyCollectedAgo
    *   Time since a ChainSync reply was last taken from the wire and handed to the capacity wait,
    *   if any was. A reply blocked in that wait shows here and not in `lastReplyAcceptedAgo`.
    * @param lastReplyAcceptedAgo
    *   Time since the last reply passed the capacity wait and was accepted for decoding.
    * @param lastReplyHandledAgo
    *   Time since the last such batch was fully handled (decoded, checked, recorded).
    */
  final case class PipelineState(
      peer: SocketAddress[Host],
      permitsFree: Long,
      permits: Int,
      queued: Int,
      candidateHeaders: Int,
      unpublishedHeaders: Option[Int],
      capacityRoom: Boolean,
      candidateBase: ChainSync.Point,
      candidateTip: ChainSync.Point,
      publishedBase: ChainSync.Point,
      publishedTip: ChainSync.Point,
      candidateTipSlot: Option[Long],
      publishedTipSlot: Option[Long],
      lastReplyCollectedAgo: Option[FiniteDuration],
      lastReplyAcceptedAgo: Option[FiniteDuration],
      lastReplyHandledAgo: Option[FiniteDuration]
  )

  /** When the session last collected a ChainSync reply (before the capacity wait), accepted one
    * (after it) and finished handling one, on the monotonic clock; the counters behind
    * [[PipelineState]].
    */
  private[node] final case class Replies(
      collected: Option[FiniteDuration] = None,
      accepted: Option[FiniteDuration] = None,
      handled: Option[FiniteDuration] = None
  )

  /** The headers of `candidate` after its newest point that `published` also has (`publication`
    * finds the same common point); none when they share no point, which is a divergence and not a
    * lag. Counting by size would be wrong when the two retained windows start at different bases.
    */
  private[node] def unpublished(
      published: ChainSelection.Candidate,
      candidate: ChainSelection.Candidate
  ): Option[Int] =
    val positions = (candidate.base +: candidate.headers.map(_.point)).zipWithIndex.toMap
    (published.base +: published.headers.map(_.point)).reverseIterator
      .find(positions.contains)
      .map(point => candidate.headers.size - positions.getOrElse(point, 0))

  /** @param readAhead
    *   Maximum headers per ordered body-publication chunk. The rolling request window is set
    *   independently by `pipelineWindow`.
    * @param byronEpochSlots
    *   Slots per Byron epoch (10k of the Byron genesis), to place Byron headers on the slot axis.
    */
  final case class Settings(
      address: SocketAddress[Host],
      magic: Long,
      network: NodeToNode.Settings = NodeToNode.Settings(),
      readAhead: Int = 128,
      byronEpochSlots: BigInt,
      pipelineWindow: ChainSyncPipelineDecision.Window = ChainSyncPipelineDecision.Window.Default
  )

  /** The points one FindIntersect offers from `candidates`, our chain's retained blocks newest
    * first and then its anchor, picked as the reference's ChainSync client picks them from its
    * current chain: `AF.selectPoints (mkOffsets k (AF.length ourFrag))` (ouroboros-consensus
    * 82ecba32 `MiniProtocol/ChainSync/Client.hs:1056-1061, 2039-2043`). The offsets are 0, each
    * `fib n` from `n = 2` that is below the length, and the length, whose point is the anchor. An
    * empty chain offers its anchor twice, since `selectOffsets` returns a point for each offset
    * (ouroboros-network c45735a5 `api/lib/Ouroboros/Network/AnchoredSeq.hs:670-688`). The
    * reference's length is `min k` of its fragment's, which is ours: the node's sessions pass a
    * [[xyz.kd5ujc.scarab.consensus.VolatileChain]]'s candidates, at most `k` blocks. (A tool that
    * names a single starting point, as `CaptureMain` does, has it offered twice.) `fib` rounds
    * `phi ** n / sqrt 5` in Double (`Util.hs:444-449`); with the 11.1.3 release binary's own `pow`
    * it is the exact Fibonacci number for every `n` up to 70, past any length a `Vector` can have,
    * so the exact numbers are used.
    */
  private[node] def intersectionPoints[A](candidates: Vector[A]): Vector[A] =
    val length = candidates.size - 1
    val fibonacci = Iterator.iterate((1L, 2L))((a, b) => (b, a + b)).map(_._1)
    val offsets = 0L +: fibonacci.takeWhile(_ < length).toVector :+ length.toLong
    offsets.flatMap(offset => candidates.lift(offset.toInt))

  /** The maximal runs of consecutive headers the caller owns (the others were claimed by another
    * session, which fetches them).
    */
  private[node] def ownedRuns(flagged: Vector[(Header, Boolean)]): Vector[NonEmptyVector[Header]] =
    // the runs newest first, and whether the newest may still grow (no header not owned since)
    val (runs, _) = flagged.foldLeft((List.empty[NonEmptyVector[Header]], false)) {
      case ((current :: older, true), (header, true))
          if header.prevHash.contains(current.last.hash) =>
        (current.append(header) :: older, true)
      case ((runs, _), (header, true)) => (NonEmptyVector.one(header) :: runs, true)
      case ((runs, _), (_, false))     => (runs, false)
    }
    runs.reverse.toVector

  private[node] def requestDepth(ahead: BigInt, configured: Int): Int =
    (ahead - 1).min(configured).min(BlockPool.Retained / (Depth + 1)).max(1).toInt

  /** Batches the peer keeps in flight: one being applied, the others being fetched. */
  private[node] val Depth = 3

  /** Called with each fetched raw block before it is handed to the rules. */
  type Prepare[F[_]] = Vector[Bytes] => F[Unit]

  /** `host:port` where host is a DNS name or an IP literal. */
  enum AddressError extends DomainError:
    case Invalid(text: String)
    def message: String = this match
      case Invalid(text) => s"invalid peer address '$text', expected <host>:<port>"
  def parseAddress(text: String): Either[AddressError, SocketAddress[Host]] =
    SocketAddress.fromString(text).toRight(AddressError.Invalid(text))

  /** A pure decoder over the peer's input, run where its result is used. One that throws instead of
    * answering is [[PeerError.InputDefect]] with the original cause: the connection's failure, not
    * the node's (the reference decodes in the mini-protocol's thread, where an exception is
    * `ShutdownPeer`, network c45735a5 RethrowPolicy.hs:99-103).
    */
  private[node] def decodingInput[F[_], A](site: PeerError.InputSite)(decode: => A)(using
      F: cats.Applicative[F],
      raise: Raise[F, PeerError]
  ): F[A] =
    Either
      .catchNonFatal(decode)
      .fold(cause => raise.raise[PeerError, A](PeerError.InputDefect(site, cause)), F.pure)

  /** The node-level classification of a network failure. */
  def networkFault(error: NetworkError): Fault = error match
    case NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(error: PeerError)) =>
      Fault.Failed(error)
    // An exception of the connection's own work (its fibers, servers and clients): the
    // reference's default for an exception no rethrow policy names, `ShutdownPeer`, ends that peer
    // and not the node (network c45735a5 RethrowPolicy.hs 61-70, 99-103; consensus 82ecba329
    // Node/RethrowPolicy.hs, `ourBug`). The peer is not charged (owner decision, issue 567).
    case NetworkError.Defect(cause) => Fault.Failed(PeerError.ConnectionDefect(cause))
    case NetworkError.DecoderDefect(protocol, cause) =>
      Fault.Failed(PeerError.InputDefect(PeerError.InputSite.Wire(protocol), cause))
    case NetworkError.BearerDefect(cause) =>
      Fault.Failed(PeerError.InputDefect(PeerError.InputSite.Bearer, cause))
    case NetworkError.ServingDefect(protocol, cause) =>
      Fault.Failed(PeerError.ServingDefect(protocol, cause))
    case NetworkError.Framing(cause)  => Fault.Failed(PeerError.Framing(cause))
    case NetworkError.MuxSetup(cause) => Fault.Failed(PeerError.MuxSetup(cause))
    case NetworkError.MuxWrite(cause) => Fault.Failed(PeerError.MuxWrite(cause))
    case cause: (NetworkError.Connect | NetworkError.Timeout | NetworkError.SduTimeout |
          NetworkError.ProtocolIdleTimeout | NetworkError.BearerClosed) =>
      Fault.Failed(PeerError.Unavailable(PeerError.Unavailability.Network(cause)))
    // our own ChainSync server could not serve the peer's next block on this connection (#924):
    // the connection ends, and the peer is not charged (`FailurePolicy`)
    case cause: NetworkError.NotKept => Fault.Failed(PeerError.NotKept(cause))
    // the node reached no verdict on the peer's message, for a limit or a defect of its own: the
    // connection ends, and the peer is not charged (`FailurePolicy`)
    case cause: NetworkError.Unjudged => Fault.Failed(PeerError.Unjudged(cause))
    // our own BlockFetch server ended the connection over the peer's range, as the reference's
    // server throws for it (`FailurePolicy`)
    case cause: NetworkError.RangeRefused => Fault.Failed(PeerError.RangeRefused(cause))
    // our own LeiosFetch server ended the connection over the peer's request, as the reference's
    // handler throws for it (`FailurePolicy`)
    case cause: NetworkError.LeiosFetchRefused => Fault.Failed(PeerError.LeiosFetchRefused(cause))
    // our own LeiosFetch client ended the connection over the peer's answer, as the reference's
    // handler of the answer throws (`FailurePolicy`)
    case cause: NetworkError.LeiosFetchInvalidReply =>
      Fault.Failed(PeerError.LeiosFetchInvalidReply(cause))
    // our own LeiosNotify server ended the connection over a request beyond the pipelining bound,
    // as the reference's server throws (`FailurePolicy`)
    case cause: NetworkError.LeiosNotifyExcessiveRequests =>
      Fault.Failed(PeerError.LeiosNotifyExcessiveRequests(cause))
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
      heightSnapshot: HeightSnapshot[F],
      transactions: Option[TxSubmissionClient.Source[F]] = None,
      headers: Option[HeaderCheck[F]] = None,
      observe: Option[Peer.Observation => F[Unit]] = None,
      pool: Option[BlockPool[F]] = None,
      invalidCachedBlock: Option[ValidationError => F[Unit]] = None
  )(using
      Raise[F, PeerError]
  ): Resource[F, Controlled[F]] =
    NodeToNode
      .connect[F, NtNHeader](
        settings.address,
        settings.magic,
        NtNHeader.codec,
        settings.network,
        transactions
      )
      .flatMap(
        over(
          _,
          settings,
          heightSnapshot,
          Chain.Praos(headers),
          observe,
          pool,
          invalidCachedBlock
        )
      )

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
    *
    * @param pool
    *   Where fetched blocks wait for the session that applies them; share one between the sessions
    *   of several peers so that each block is fetched from one of them only. Without one, the
    *   session has a pool of its own.
    * @param invalidCachedBlock
    *   Report against the source peer independently of this session's lifetime or error scope.
    */
  def over[F[_]: Async](
      connection: NodeToNodeConnection[F, NtNHeader],
      settings: Settings,
      heightSnapshot: HeightSnapshot[F],
      chain: Chain[F] = Chain.Praos[F](None),
      observe: Option[Peer.Observation => F[Unit]] = None,
      pool: Option[BlockPool[F]] = None,
      invalidCachedBlock: Option[ValidationError => F[Unit]] = None,
      fetchIdentity: Option[Long] = None,
      pipelineFailed: Option[Fault => F[Unit]] = None,
      patience: Option[LimitOnPatience[F]] = None,
      received: Option[SyncEvent => F[Unit]] = None,
      chainSyncObserver: Option[ChainSyncClient.Observer[F]] = None,
      retainedHeaders: Int = BlockPool.Retained,
      fetchSession: Option[FetchCoordinator.Session[F]] = None,
      selectionWork: Option[SelectionWork.Session[F]] = None,
      diffusion: BlockPool.Diffusion = BlockPool.Diffusion.On
  ): Resource[F, Controlled[F]] =
    val F = Async[F]
    val workSession = selectionWork
    val genesis: Option[GenesisPeer[F]] = chain match
      case Chain.Praos(_)            => None
      case Chain.Genesis(_, control) => Some(control)
    def protocol[A](reason: PeerError.ProtocolFailure)(using Raise[F, PeerError]): F[A] =
      PeerError.Protocol(reason).raise[F, A]

    /** The ChainSync observer, `captured` told after a Genesis session has published its gate as it
      * stands: the controls read the session's state only as the session hands it to them.
      */
    def observer(
        captured: Option[ChainSyncClient.Observer[F]],
        owned: Deferred[F, Ref[F, HeaderGate.Grafting[F]]]
    ): ChainSyncClient.Observer[F] =
      val current = genesis.traverse_(control =>
        owned.tryGet.flatMap(_.traverse_(_.get.flatMap(control.current)))
      )
      new ChainSyncClient.Observer[F]:
        override def capture(using cats.Applicative[F]): F[ChainSyncClient.Observer[F]] =
          current *> captured.traverse(_.capture).map(observer(_, owned))
        def awaitReply: F[Unit] =
          patience.traverse_(_.setPaused(LimitOnPatience.PauseReason.AwaitReply, true)) *>
            current *> captured.traverse_(_.awaitReply)
        def reply: F[Unit] =
          patience.traverse_(_.setPaused(LimitOnPatience.PauseReason.AwaitReply, false)) *>
            current *> captured.traverse_(_.reply)

    // The session's claims on the pool come first, so they are released last: after the supervisor
    // has cancelled and awaited the fibers that fetch, nothing the session claimed is left open.
    (for
      shared <- Resource.eval(pool.fold(BlockPool.create[F])(F.pure))
      _ <- (shared.fetchCoordinator.run
        .handleErrorWith(cause => connection.mux.abort(NetworkError.Defect(cause)) *> F.never)
        .background
        .void)
        .whenA(pool.isEmpty)
      claims <- shared.session(invalidCachedBlock, diffusion)
      fetcher <- fetchSession.fold(
        shared.fetchCoordinator.session(
          fetchIdentity.getOrElse(settings.address.hashCode.toLong),
          PeerOrder.of(connection.mux.connectionId)
        )
      )(Resource.pure[F, FetchCoordinator.Session[F]])
      supervisor <- OrderedSupervisor[F]
      held <- Resource.eval(
        Deferred[F, Unit].flatMap(signal =>
          Ref.of[F, Held[F]](Held(CandidateState.initial, signal))
        )
      )
      owned <- Resource.eval(Deferred[F, Ref[F, HeaderGate.Grafting[F]]])
      _ <- connection.chainSync.observe(observer(chainSyncObserver, owned))
    yield (claims, fetcher, (supervisor, held, owned))).evalMap { (claims, fetcher, lifecycle) =>
      val (supervisor, held, owned) = lifecycle
      // the session's candidate, as its state holds it, for those that read it; the session's own
      // changes are transitions of that state. A write through it replaces the candidate alone, as
      // a write of the candidate's own `Ref` did, and signals nothing.
      val candidateHeaders: Ref[F, ChainSelection.Candidate] =
        Ref.lens[F, Held[F], ChainSelection.Candidate](held)(
          _.state.candidate,
          h => next => h.copy(state = h.state.copy(candidate = next))
        )

      /** One transition of the session's candidate state, and the signal of the change after it: a
        * transition that changes the state completes the signal of the one before, so a fiber that
        * read the state and its signal together never misses a change.
        */
      def transition[A](f: CandidateState => (CandidateState, A)): F[(A, Deferred[F, Unit])] =
        F.uncancelable { _ =>
          Deferred[F, Unit].flatMap { fresh =>
            held
              .modify { h =>
                val (next, out) = f(h.state)
                if next eq h.state then (h, (None, out, h.changed))
                else (Held(next, fresh), (Some(h.changed), out, fresh))
              }
              .flatMap((before, out, signal) =>
                before.traverse_(_.complete(()).void).as(out -> signal)
              )
          }
        }

      /** `f` applied to the session's candidate state: the state it left, if it changed it. */
      def change(f: CandidateState => CandidateState): F[Option[CandidateState]] =
        transition { state =>
          val next = f(state)
          (next, Option.when(next ne state)(next))
        }.map(_._1)
      // Read-ahead: while the peer is far ahead, ChainSync requests are pipelined, and the
      // accepted headers are published to `queue` in order, up to `Depth` batches ahead of the
      // consumer. Their blocks are fetched independently: the session's fetch client takes the
      // requests the shared decisions add from its candidate, and delivers the blocks to the pool,
      // whose admissions the node's selection worker applies (issue 599).
      //
      // The queue holds at most `Depth` batches (a permit per batch, returned when the batch is
      // taken) and the faults of the fibers that did not finish theirs: one per permit still held,
      // and the producer's own.
      (
        Queue.bounded[F, PipelineEvent](Depth + 1),
        Ref.of[F, Vector[SyncEvent]](Vector.empty),
        Ref.of[F, Option[(ChainSync.Tip, BigInt)]](None),
        Ref.of[F, Boolean](false),
        Semaphore[F](Depth.toLong),
        Ref.of[F, HeaderGate[F]](HeaderGate.none[F]),
        Ref.of[F, Option[Deferred[F, DrainOutcome]]](None),
        Ref.of[F, Option[Fault]](None),
        Ref.of[F, Boolean](false),
        Deferred[F, Unit].flatMap(signal => Ref.of[F, Deferred[F, Unit]](signal)),
        Ref.of[F, Option[ChainSync.Point]](None),
        Ref.of[F, Option[PeerError.RolledBackPastIntersection]](None),
        Ref.of[F, Boolean](false),
        Ref.of[F, Option[ChainSyncJumping.Instruction[GenesisPeer.Checkpoint]]](None),
        Ref.of[F, Peer.Replies](Peer.Replies()),
        // the session's ChainSync as the writer of its candidate's announcements
        F.unique
      ).mapN {
        (
            queue,
            current,
            progress,
            started,
            permits,
            gate,
            drainCompletion,
            pipelineFailure,
            observersStarted,
            drainRequested,
            pendingRestart,
            pendingEnd,
            chainSyncFinished,
            pendingJump,
            replies,
            writer
        ) =>
          def finishChainSync(using Raise[F, PeerError]): F[Unit] =
            connection.chainSync.done *> chainSyncFinished.set(true)

          /** A Genesis session's own header state: the gate that grafts, from its first
            * intersection on, as the reference's client holds its `KnownIntersectionState` once it
            * has found one (consensus 82ecba329 ChainSync/Client.hs:1254–1272). Jump instructions
            * are taken only after that intersection.
            */
          def ownGate: F[HeaderGate.Grafting[F]] = owned.get.flatMap(_.get)

          def own(next: HeaderGate.Grafting[F]): F[Unit] =
            owned.tryGet.flatMap(
              _.fold(Ref.of[F, HeaderGate.Grafting[F]](next).flatMap(owned.complete).void)(
                _.set(next)
              )
            )

          /** The points and heights to offer in FindIntersect, captured before it is sent
            * (`Client.hs` 1065–1148), and how the session's gate starts at the point found from
            * them. A Genesis session's gate grafts; the session keeps it and tells the controls it
            * restarted there.
            */
          def offer: F[
            (
                Option[(Vector[ChainSync.Point], Map[ChainSync.Point, Option[BigInt]])],
                ChainSync.Point => F[Unit]
            )
          ] = chain match
            case Chain.Praos(check) =>
              check.traverse(_.capture).map(_.flatten).map { captured =>
                captured.map(c => c.points -> c.heights) -> { (point: ChainSync.Point) =>
                  captured
                    .fold(check.fold(F.pure(HeaderGate.none[F]))(_.start(point)))(_.start(point))
                    .flatMap(gate.set)
                }
              }
            case Chain.Genesis(check, control) =>
              check.captureGrafting.map { captured =>
                captured.map(c => c.points -> c.heights) -> { (point: ChainSync.Point) =>
                  captured
                    .fold(check.startGrafting(point))(_.start(point))
                    .flatMap(next => gate.set(next) *> own(next) *> control.restarted(next, point))
                }
              }

          /** Native checkKnownInvalid (Client.hs:1642–1694) excludes a tentative tip only when
            * diffusion pipelining is on. Ordered RequestNext batching does not select this mode.
            */
          def checkKnownInvalid(header: Header)(using Raise[F, PeerError]): F[Unit] =
            val scrutinized = diffusion match
              case BlockPool.Diffusion.On  => header.predecessor
              case BlockPool.Diffusion.Off => Some(header.hash)
            scrutinized.traverse_(hash =>
              claims.invalidBlocks
                .lookup(hash)
                .flatMap(
                  _.traverse_(refusal => PeerError.InvalidBlock(refusal).raise[F, Unit])
                )
            )

          /** The native watcher runs on invalidity fingerprints, not candidate changes, and scans
            * newest first while skipping exactly one tentative tip (Client.hs:2082–2156). Its
            * supervised child creates and lands its own error scope through Fault.fiber.
            */
          def invalidBlockRejector(using Raise[F, PeerError]): F[Unit] =
            F.tailRecM[BigInt, Unit](-1) { revision =>
              claims.invalidBlocks.awaitChange(revision).flatMap { invalid =>
                chainSyncFinished.get.flatMap { done =>
                  if done then F.never
                  else
                    candidateHeaders.get.flatMap { candidate =>
                      val checked = diffusion match
                        case BlockPool.Diffusion.On  => candidate.headers.dropRight(1)
                        case BlockPool.Diffusion.Off => candidate.headers
                      checked.reverseIterator
                        .flatMap(h => invalid.entries.get(h.hash))
                        .take(1)
                        .toVector
                        .traverse_(refusal => PeerError.InvalidBlock(refusal).raise[F, Unit])
                        .as(Left(invalid.revision))
                    }
                }
              }
            }

          def decode(update: ChainSyncClient.Update[NtNHeader])(using
              Raise[F, PeerError]
          ): F[SyncEvent] = update match
            case ChainSyncClient.Update.RollForward(h, tip) =>
              decodingInput(PeerError.InputSite.ChainSyncHeader)(
                Header.fromNtN(h, settings.byronEpochSlots)
              ).flatMap(
                _.fold(
                  e => protocol(PeerError.ProtocolProblem.HeaderDecode(e)),
                  h => F.pure(SyncEvent.RollForward(h, tip))
                )
              )
            case ChainSyncClient.Update.RollBackward(point, tip) =>
              F.pure(SyncEvent.RollBackward(point, tip))

          /** The session's BlockFetch client (network c45735a5 BlockFetch/Client.hs:81–190): it
            * takes each request the decisions added for this session (`acknowledgeFetchRequest`),
            * sends its ranges and collects their blocks, until the session is brought to rest. What
            * is requested is the decisions' alone; publishing headers never waits for it. Byte
            * credit returns after each checked original is visible in the shared body pool.
            */
          def fetchClient: F[Unit] =
            F.tailRecM(()) { _ =>
              fetcher.acquire.flatMap {
                case None             => F.pure(Right(()))
                case Some(allocation) => fetchRequest(allocation).as(Left(()))
              }
            }

          /** One request's ranges, sent in the request's order (`senderActive`, Client.hs:135–179)
            * and each collected in a fiber of the session's, so that the next request is taken
            * while they are in flight. The next range is sent once this one is on the wire. Each
            * block's pool claim is bookkeeping for whoever waits for it: what is requested is what
            * the decisions requested, and a block another session delivers first is its delivery.
            */
          def fetchRequest(allocation: FetchCoordinator.Allocation): F[Unit] =
            val runs = ownedRuns(allocation.headers.map(_ -> true))
            claims.claim(allocation.headers.map(_.hash), allowOverlap = true) *>
              Ref.of[F, Int](runs.size).flatMap { open =>
                val ended = open
                  .modify(n => (n - 1, n == 1))
                  .flatMap(last => fetcher.finished(allocation).whenA(last))
                fetcher.finished(allocation).whenA(runs.isEmpty) *> runs.traverse_ { run =>
                  Deferred[F, Unit].flatMap { sent =>
                    supervised(
                      requestRun(run, allocation, sent.complete(()).void)
                        .flatMap { (fetched, arrivedAt) =>
                          // each block counts the size its header announces, not the bytes
                          // received: `blockFetchSize header` (`BlockFetch/Client.hs` 274 at
                          // ouroboros-network c45735a56c567fa977969173d18943bac6bb3821), which is
                          // `estimateBlockSize` (`Ouroboros/Consensus/Node.hs` 943 at
                          // ouroboros-consensus 82ecba329d7d054340bf707d44fe6e9ac27cec40)
                          fetched.traverse_(raw =>
                            observe.traverse_(
                              _(
                                Peer.Observation.BlocksArrived(
                                  run.toVector
                                    .zip(raw)
                                    .map((h, _) =>
                                      h.slot.toLong -> FetchCoordinator.estimatedBytes(h)
                                    ),
                                  arrivedAt
                                )
                              )
                            )
                          )
                        }
                        .guarantee(
                          claims
                            .release(run.toVector.map(_.hash)) *> sent.complete(()).void *> ended
                        )
                    ) *> sent.get
                  }
                }
              }

          def requestRun(
              run: NonEmptyVector[Header],
              allocation: FetchCoordinator.Allocation,
              sent: F[Unit]
          )(using Raise[F, PeerError]): F[(Option[Vector[Bytes]], Time)] =
            (
              Ref.of[F, Vector[Header]](run.toVector),
              Ref.of[F, Option[FiniteDuration]](None)
            ).tupled
              .flatMap { (remaining, sentAt) =>
                val observeEvent: BlockFetchRuntime.Event => F[Unit] = {
                  case BlockFetchRuntime.Event.Sent(_) =>
                    F.monotonic.flatMap(at => sentAt.set(Some(at))) *> sent
                  case _ => F.unit
                }
                val receive: Bytes => F[Either[NetworkError, Unit]] = raw =>
                  Fault
                    .fiber {
                      remaining.modify(hs => (hs.drop(1), hs.headOption)).flatMap {
                        case None =>
                          protocol[Unit](
                            PeerError.ProtocolProblem.BlockCount(run.length, run.length + 1)
                          )
                        case Some(header) =>
                          for
                            delivered <- decodingInput(
                              PeerError.InputSite.FetchedBlock(header.point)
                            )(BlockPool.Delivered.checked(header, raw))
                              .flatMap(_.fold(protocol[BlockPool.Delivered], F.pure))
                            // CompletedBlockFetch (Client.hs:286, ClientState.hs:579–615): the
                            // block's download is complete, so long after its range was sent.
                            _ <- (sentAt.get, F.monotonic).tupled.flatMap { (from, now) =>
                              from.traverse_(at =>
                                observe.traverse_(_(Peer.Observation.Fetched((now - at).toMillis)))
                              )
                            }
                            _ <- claims.fillDelivered(Vector(delivered))
                            _ <- fetcher.received(allocation, header.hash)
                          yield ()
                      }
                    }
                    .map(_.leftMap {
                      case Fault.Failed(problem) =>
                        NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(problem))
                      case Fault.Defect(problem) => NetworkError.Defect(problem)
                    })
                val request =
                  connection.blockFetch.requestBlocks(run.head.point, run.last.point, run.length)(
                    receive,
                    observeEvent
                  )
                request.flatMap {
                  case Some(raw) if raw.sizeIs != run.length =>
                    protocol(PeerError.ProtocolProblem.BlockCount(run.length, raw.size))
                  case None =>
                    fetcher.rejected(allocation, run.toVector) *> F.monotonic.map(None -> _)
                  case result => fetcher.completed(allocation) *> F.monotonic.map(result -> _)
                }
              }

          /** A normal rollback exit is sent only after every response of the submitted batch was
            * collected; raising inside the streaming callback would abort its collector.
            */
          def finishPendingEnd(using Raise[F, PeerError]): F[Unit] =
            pendingEnd
              .getAndSet(None)
              .flatMap(_.traverse_(error => finishChainSync *> error.raise[F, Unit]))

          def capturedHeight(point: ChainSync.Point): F[Option[ChainSync.UInt64]] =
            gate.get
              .flatMap(_.snapshot)
              .flatMap { known =>
                val canonical = known.filter(_.tipPoint == point).flatMap { h =>
                  if point == ChainSync.Point.Origin then Some(None)
                  else
                    h.entries.lastOption
                      .map(e => Some(e.header.blockNo))
                      .orElse(
                        h.baseView.map(v => Some(v.value.blockNo))
                      )
                }
                canonical.fold(
                  held.get.flatMap(
                    _.state.track.heights
                      .get(point)
                      .fold(
                        heightSnapshot.unavailable[Option[BigInt]](
                          ChainSyncInternalProblem.MissingHeight(point)
                        )
                      )(F.pure)
                  )
                )(F.pure)
              }
              .flatMap(_.traverse { value =>
                ChainSync.UInt64
                  .from(value)
                  .fold(
                    _ =>
                      heightSnapshot.unavailable[ChainSync.UInt64](
                        ChainSyncInternalProblem.InvalidBlockNumber(value)
                      ),
                    F.pure
                  )
              })

          /** Native low/high policy remains live after each handled reply, without a batch barrier.
            */
          def requestHeadersRolling(
              pipeline: ChainSyncClient.Pipeline[F, NtNHeader],
              publish: Vector[SyncEvent] => F[Unit],
              capacity: ChainSyncClient.Update[NtNHeader] => F[Unit]
          )(using Raise[F, PeerError]): F[Unit] =
            for
              contexts <- Ref.of[F, ImmutableQueue[ChainSyncClient.Observer[F]]](
                ImmutableQueue.empty
              )
              _ <- {
                def head: F[ChainSyncClient.Observer[F]] = contexts.get.flatMap(
                  _.headOption.fold(
                    heightSnapshot.unavailable[ChainSyncClient.Observer[F]](
                      ChainSyncInternalProblem.MissingRequestContext
                    )
                  )(F.pure)
                )
                def pop: F[Unit] = contexts.update(_.drop(1))
                def onAwait: F[Unit] = head.flatMap(_.awaitReply)
                def stopping: F[Boolean] =
                  (drainCompletion.get, pendingRestart.get, pendingEnd.get, pendingJump.get)
                    .mapN { (drain, restart, end, jump) =>
                      drain.nonEmpty || restart.nonEmpty || end.nonEmpty || jump.nonEmpty
                    }
                def submit: F[Unit] = F.uncancelable { poll =>
                  observer(chainSyncObserver, owned).capture.flatMap { captured =>
                    poll(
                      pipeline.submitObserved(
                        new ChainSyncClient.ReceiverHook[F]:
                          def awaitReply(using Raise[F, NetworkError]): F[Unit] =
                            captured.awaitReply
                      )
                    ) *> contexts.update(_.enqueue(captured))
                  }
                }
                def headerReceived: F[Unit] = observe.traverse_(_(Observation.HeaderReceived))
                def handled(update: ChainSyncClient.Update[NtNHeader]): F[Unit] =
                  headerReceived *> F.monotonic.flatMap(at =>
                    replies.update(_.copy(collected = Some(at)))
                  ) *> stopping.flatMap {
                    case true => pop
                    case false =>
                      head.flatMap(_.reply) *> capacity(update) *>
                        stopping
                          .flatMap {
                            case true => F.unit
                            case false =>
                              F.race(
                                drainRequested.get.flatMap(_.get),
                                acceptUpdates(Vector(update))
                              ).flatMap {
                                case Left(_) => F.unit
                                case Right(batch) =>
                                  publish(batch) *>
                                    (pendingRestart.get, pendingEnd.get).tupled.flatMap {
                                      case (None, None) => afterReply
                                      case _            => F.unit
                                    }
                              }

                          }
                          .guarantee(pop)
                  }
                F.tailRecM(ChainSyncPipelineDecision.Policy(settings.pipelineWindow)) { policy =>
                  stopping.flatMap {
                    case true =>
                      pipeline.drain(onAwait)(_ => headerReceived *> pop) *> finishPendingEnd.as(
                        Right(())
                      )
                    case false =>
                      for
                        _ <- pipeline.serviceAwait(onAwait)
                        outstanding <- pipeline.outstanding
                        candidate <- candidateHeaders.get
                        client <- capturedHeight(candidate.tip)
                        server <- progress.get.map(_.flatMap { (tip, _) =>
                          Option.unless(tip.point == ChainSync.Point.Origin)(tip.blockNo)
                        })
                        decision <- policy
                          .decide(outstanding, client, server)
                          .fold(
                            {
                              case ChainSyncPipelineDecision.Error.InvalidMarks(low, high) =>
                                heightSnapshot.unavailable[
                                  (
                                      ChainSyncPipelineDecision.Action,
                                      ChainSyncPipelineDecision.Policy
                                  )
                                ](ChainSyncInternalProblem.InvalidPipelineMarks(low, high))
                              case ChainSyncPipelineDecision.Error.NegativeOutstanding(value) =>
                                heightSnapshot.unavailable[
                                  (
                                      ChainSyncPipelineDecision.Action,
                                      ChainSyncPipelineDecision.Policy
                                  )
                                ](ChainSyncInternalProblem.NegativeOutstanding(value))
                            },
                            F.pure
                          )
                        (action, next) = decision
                        _ <- action match
                          case ChainSyncPipelineDecision.Action.Request =>
                            submit *> pipeline.collect(onAwait).flatMap(handled)
                          case ChainSyncPipelineDecision.Action.Pipeline => submit
                          case ChainSyncPipelineDecision.Action.Collect =>
                            pipeline.collect(onAwait).flatMap(handled)
                          case ChainSyncPipelineDecision.Action.CollectOrPipeline =>
                            pipeline.tryCollect(onAwait).flatMap(_.fold(submit)(handled))
                      yield Left(next)
                  }
                }
              }
            yield ()

          /** The next batch of ChainSync instructions. */
          def requestHeaders(using Raise[F, PeerError]): F[Vector[SyncEvent]] =
            for
              seen <- progress.get
              ahead = seen.fold(BigInt(0)) { (tip, blockNo) =>
                (tip.blockNo.value - blockNo).max(0)
              }
              n = requestDepth(ahead, settings.readAhead)
              began <- F.monotonic
              updates <- connection.chainSync.requestNextBatch(n)
              arrived <- F.monotonic
              took = arrived - began
              _ <- observe.traverse_(_(Peer.Observation.Round(n, updates.size, took.toMillis)))
              batch <- acceptUpdates(updates)
              _ <- finishPendingEnd
            yield batch

          /** Client.hs:1255–1291 asks CSJ after every handled response. A jump consumes its
            * instruction once, then discards already-submitted replies before FindIntersect.
            */
          def afterReply: F[Unit] =
            genesis.traverse_ { control =>
              F.tailRecM(()) { _ =>
                F.race(drainRequested.get.flatMap(_.get), control.nextAfterReply).flatMap {
                  case Left(_)                                         => F.pure(Right(()))
                  case Right(ChainSyncJumping.Instruction.RunNormally) => F.pure(Right(()))
                  case Right(ChainSyncJumping.Instruction.Wait)        => F.cede.as(Left(()))
                  case Right(ChainSyncJumping.Instruction.Failed(problem)) =>
                    control.failed[Either[Unit, Unit]](problem)
                  case Right(instruction) => pendingJump.set(Some(instruction)).as(Right(()))
                }
              }
            }

          /** Genesis must make each validated prefix available while later replies wait for a
            * forecast. All requests remain pipelined; individual publication owns bounded credit.
            */
          def requestHeadersStreaming(
              publish: Vector[SyncEvent] => F[Unit],
              capacity: ChainSyncClient.Update[NtNHeader] => F[Unit]
          )(using
              Raise[F, PeerError]
          ): F[Unit] =
            connection.chainSyncPipeline.fold {
              for
                seen <- progress.get
                ahead = seen.fold(BigInt(0)) { (tip, blockNo) =>
                  (tip.blockNo.value - blockNo).max(0)
                }
                n = requestDepth(ahead, settings.readAhead)
                began <- F.monotonic
                updates <- connection.chainSync.requestNextBatchObserved(n) { update =>
                  (
                    drainCompletion.get,
                    pendingRestart.get,
                    pendingEnd.get,
                    pendingJump.get
                  ).tupled
                    .flatMap {
                      // Once draining starts every remaining wire reply is discarded. Racing a
                      // pre-completed drain signal would still start validation on a fragment whose
                      // preceding validation was cancelled (native drainThePipe discard handlers).
                      case (Some(_), _, _, _) | (_, Some(_), _, _) | (_, _, Some(_), _) |
                          (_, _, _, Some(_)) =>
                        F.unit
                      case (None, None, None, None) =>
                        F.race(drainRequested.get.flatMap(_.get), acceptUpdates(Vector(update)))
                          .flatMap {
                            case Left(_) => F.unit
                            case Right(batch) =>
                              publish(batch) *>
                                (pendingRestart.get, pendingEnd.get).tupled.flatMap {
                                  case (None, None) => afterReply
                                  case _            => F.unit
                                }
                          }
                    }
                }
                _ <- finishPendingEnd
                arrived <- F.monotonic
                _ <- observe.traverse_(
                  _(Peer.Observation.Round(n, updates.size, (arrived - began).toMillis))
                )
              yield ()
            }(requestHeadersRolling(_, publish, capacity))

          /** Headers come from the supervised streaming producer, not the unsupervised one. */
          def streamed: Boolean =
            connection.chainSyncPipeline.nonEmpty || patience.nonEmpty || genesis.nonEmpty ||
              workSession.nonEmpty

          /** Validate and announce a complete batch, or one streamed reply. */
          def acceptUpdates(updates: Vector[ChainSyncClient.Update[NtNHeader]])(using
              Raise[F, PeerError]
          ): F[Vector[SyncEvent]] =
            for
              arrived <- F.monotonic
              _ <- replies.update(_.copy(accepted = Some(arrived)))
              batch <- updates.traverse(decode)
              _ <- batch.traverse_ {
                case SyncEvent.RollForward(header, _) => checkKnownInvalid(header)
                case SyncEvent.RollBackward(_, _)     => F.unit
              }
              _ <- batch.traverse_(event => received.traverse_(_(event)))
              _ <- batch.lastOption.traverse_ {
                case SyncEvent.RollForward(_, tip) =>
                  observe.traverse_(_(Peer.Observation.Announced(tip)))
                case SyncEvent.RollBackward(_, tip) =>
                  observe.traverse_(_(Peer.Observation.Announced(tip)))
              }
              accepted <- gate.get.flatMap(g =>
                g.acceptOrRestart(batch).flatTap {
                  case HeaderGate.Acceptance.Accepted =>
                    genesis.traverse_(control =>
                      ownGate.flatMap(own => batch.traverse_(control.observed(own, _)))
                    )
                  case HeaderGate.Acceptance.Restart(_) | HeaderGate.Acceptance.End(_) => F.unit
                }
              )
              result <- accepted match
                case HeaderGate.Acceptance.Accepted =>
                  gate.get.flatMap(_.snapshot).flatMap {
                    case Some(history) => recordUpdates(batch, Some(history.base))
                    case None =>
                      candidateHeaders.get.flatMap { initial =>
                        val checked =
                          batch.foldLeft[Either[ChainSync.Point, ChainSelection.Candidate]](
                            Right(initial)
                          ) {
                            case (known, SyncEvent.RollForward(header, _)) =>
                              known.map(c => c.copy(headers = c.headers :+ header))
                            case (known, SyncEvent.RollBackward(point, _)) =>
                              known.flatMap(_.rollback(point).toRight(point))
                          }
                        checked.fold(
                          point =>
                            pendingEnd
                              .set(Some(PeerError.RolledBackPastIntersection(point)))
                              .as(Vector.empty[SyncEvent]),
                          _ => recordUpdates(batch, None)
                        )
                      }
                  }
                case HeaderGate.Acceptance.End(point) =>
                  pendingEnd
                    .set(Some(PeerError.RolledBackPastIntersection(point)))
                    .as(Vector.empty[SyncEvent])
                case HeaderGate.Acceptance.Restart(point) =>
                  // NoLongerIntersects: drain the pipeline and find a new intersection with our
                  // current chain; only no intersection ends the session (consensus 82ecba329,
                  // ChainSync/Client.hs:1497–1511). The unsupervised producer of tests and tools
                  // has no re-intersection and ends the session.
                  if streamed then pendingRestart.set(Some(point)).as(Vector.empty[SyncEvent])
                  else
                    PeerError
                      .Diverged(PeerError.Divergence.DetachedCandidate(point))
                      .raise[F, Vector[SyncEvent]]
              _ <- observe.traverse_ { report =>
                val slots = result.collect { case SyncEvent.RollForward(h, _) => h.slot.toLong }
                report(Peer.Observation.HeadersArrived(slots, arrived)).whenA(slots.nonEmpty)
              }
              handled <- F.monotonic
              _ <- replies.update(_.copy(handled = Some(handled)))
            yield result

          /** The session's candidate after `batch`, trimmed to `anchor`, our chain's anchor as the
            * header gate's history has it, as the reference's client trims its candidate as it
            * rolls forward (`intersectsWithCurrentChain`, ouroboros-consensus 82ecba329
            * MiniProtocol/ChainSync/Client.hs 942-992) ([[CandidateState.recorded]]).
            */
          def recordUpdates(
              batch: Vector[SyncEvent],
              anchor: Option[ChainSync.Point]
          ): F[Vector[SyncEvent]] =
            val received = batch.collect { case SyncEvent.RollForward(h, _) =>
              h.point -> Some(h.blockNo)
            }.toMap
            for
              // the candidate's index is carried through the batch's headers alone, and its
              // retained points filter the heights without a set of them built per batch
              recorded <- commit(_.recorded(batch, retainedHeaders, received, anchor)._1)
              candidate <- recorded.fold(candidateHeaders.get)(state => F.pure(state.candidate))
              _ <- batch.lastOption.traverse_ { event =>
                val tip = event match
                  case SyncEvent.RollForward(_, value)  => value
                  case SyncEvent.RollBackward(_, value) => value
                capturedHeight(candidate.tip).flatMap(height =>
                  progress.set(Some(tip -> height.fold(BigInt(0))(_.value)))
                )
              }
            yield batch

          /** `f` applied to the session's candidate state, and the state it left, if it changed it,
            * announced ([[announce]]): a state once made is announced, whatever cancels the fiber
            * that made it.
            */
          def commit(f: CandidateState => CandidateState): F[Option[CandidateState]] =
            F.uncancelable(_ => change(f).flatTap(_.traverse_(announce)))

          /** Tell the fetch decisions of the candidate `state` holds, with its index and revision:
            * an announcement of an older state never replaces a newer one. The reference's
            * BlockFetch reads the candidate its client writes as it rolls forward and back
            * (ouroboros-consensus 82ecba329 MiniProtocol/ChainSync/Client.hs 1518, 1615), so a trim
            * made while the client waits reaches it with the next of those; here each change is
            * announced as it is made. The headers a trim drops are on our chain, behind its anchor,
            * so no decision differs.
            */
          def announce(state: CandidateState): F[Unit] =
            state.track.announced.filter(_.candidate eq state.candidate).traverse_ { index =>
              connection.fetchPerformance
                .getOrElse(F.pure(FetchGsv.default))
                .flatMap(performance =>
                  fetcher.announce(
                    index.candidate.headers.lastOption,
                    performance,
                    index,
                    FetchCoordinator.Revision(writer, state.revision)
                  )
                )
            }

          def fail(fault: Fault): F[Unit] = F.uncancelable { _ =>
            pipelineFailure
              .modify {
                case None     => (Some(fault), true)
                case existing => (existing, false)
              }
              .flatMap { first =>
                if !first then F.unit
                else
                  val cause = fault match
                    case Fault.Failed(error) =>
                      NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(error))
                    case Fault.Defect(error) => NetworkError.Defect(error)
                  pipelineFailed.traverse_(_(fault)) *> connection.mux.abort(cause) *> queue
                    .offer(
                      PipelineEvent.Failed(fault)
                    )
              }
          }

          /** A fiber of the pipeline: its body in an error scope of its own, and the fault it ends
            * with handed to the session through the queue.
            */
          def supervised(body: Raise[F, PeerError] ?=> F[Unit]): F[Unit] =
            supervisor
              .supervise(
                Fault
                  .fiber(
                    F.race(connection.mux.closed, body).flatMap {
                      case Left(cause) => Fault.land[F, Unit](Left(networkFault(cause)))
                      case Right(_)    => F.unit
                    }
                  )
                  .flatMap(_.fold(fail, _ => F.unit))
              )
              .void

          /** Request batches for ever and publish, in order, what each changed of the candidate
            * from what was published before: as a candidate TVar coalesces them ([[publication]]),
            * a header rolled back within a batch is never published. Their blocks are the
            * decisions' to fetch ([[fetchClient]]).
            */
          def produce(published: Ref[F, ChainSelection.Candidate])(using
              Raise[F, PeerError]
          ): F[Unit] =
            for
              _ <- permits.acquire
              halt <- drainCompletion.get
              _ <- halt match
                case Some(_) =>
                  // brought to rest: the batches requested so far are published, then it is idle
                  permits.release *> queue.offer(PipelineEvent.Drained)
                case None =>
                  Fault.fiber(requestHeaders).flatMap {
                    case Left(
                          Fault.Failed(
                            error @ (PeerError.NoIntersection |
                            _: PeerError.RolledBackPastIntersection)
                          )
                        ) =>
                      val fault = Fault.Failed(error)
                      permits.release *> pipelineFailure.set(Some(fault)) *>
                        queue.offer(PipelineEvent.Failed(fault))
                    case Left(fault) => Fault.land[F, Unit](Left(fault))
                    case Right(batch) =>
                      val tip = batch.lastOption.map {
                        case SyncEvent.RollForward(_, tip)  => tip
                        case SyncEvent.RollBackward(_, tip) => tip
                      }
                      (published.get, candidateHeaders.get).tupled
                        .flatMap { (before, latest) =>
                          tip.fold(F.pure(Vector.empty[SyncEvent])) { at =>
                            publication(before, latest, at, latest.headers.size).fold(
                              PeerError.Diverged(_).raise[F, Vector[SyncEvent]],
                              (after, events) => published.set(after).as(events)
                            )
                          }
                        }
                        .flatMap { events =>
                          if events.isEmpty then permits.release
                          else queue.offer(PipelineEvent.Batch(events))
                        } *> produce(published)
                  }
            yield ()

          /** The session's candidate state stores one validated candidate, rather than every
            * transient wire fork, and the cursor published from it. HeaderGate's forecast bounds
            * its slot span; the body dispatcher alone owns permits.
            */
          def streaming(using Raise[F, PeerError]): F[Unit] =
            /** Room for one more header in the retained window: [[publicationRoom]], and the window
              * never drops a header whose block is not fetched yet, since that block is the
              * decisions' to request from the candidate alone.
              */
            def windowRoom(
                published: ChainSelection.Candidate,
                candidate: ChainSelection.Candidate
            ): F[Boolean] =
              candidate.headers.headOption
                .filter(_ => candidate.headers.sizeIs >= retainedHeaders.max(1))
                .traverse(oldest => fetcher.hasBody(oldest.hash))
                .map(got =>
                  publicationRoom(published, candidate, retainedHeaders) && got.forall(identity)
                )
            // the newest candidate published to the consumer, the candidate itself before the session
            // streams
            def cursorOf(state: CandidateState): ChainSelection.Candidate =
              state.publication.fold(state.candidate)(_.cursor)
            // the candidate's index is scanned once, kept for the batches after it and announced; the
            // publisher takes the candidate in a new epoch
            def remember(candidate: ChainSelection.Candidate, tip: ChainSync.Tip): F[Unit] =
              commit(_.replaced(FetchCandidates.Announced.of(candidate), tip)).void
            // our chain's anchor, as the header gate's history has it
            val anchor: F[Option[ChainSync.Point]] =
              gate.get.flatMap(_.snapshot).map(_.map(_.base))
            for
              _ <- change(_.streaming)
              _ <- fetcher.observed(
                (
                  F.monotonic,
                  replies.get,
                  permits.available,
                  queue.size,
                  held.get
                ).tupled.flatMap { (now, seen, free, queued, current) =>
                  val candidate = current.state.candidate
                  val published = cursorOf(current.state)
                  windowRoom(published, candidate).map(room =>
                    Peer.PipelineState(
                      peer = settings.address,
                      permitsFree = free,
                      permits = Depth,
                      queued = queued,
                      candidateHeaders = candidate.headers.size,
                      unpublishedHeaders = unpublished(published, candidate),
                      capacityRoom = room,
                      candidateBase = candidate.base,
                      candidateTip = candidate.tip,
                      publishedBase = published.base,
                      publishedTip = published.tip,
                      candidateTipSlot = candidate.headers.lastOption.map(_.slot.toLong),
                      publishedTipSlot = published.headers.lastOption.map(_.slot.toLong),
                      lastReplyCollectedAgo = seen.collected.map(now - _),
                      lastReplyAcceptedAgo = seen.accepted.map(now - _),
                      lastReplyHandledAgo = seen.handled.map(now - _)
                    )
                  )
                }
              )
              // The candidate follows the header gate's history as the gate trims it to our chain's
              // anchor, as the reference trims its candidate whenever our chain changes
              // (`intersectsWithCurrentChain`, ouroboros-consensus 82ecba329
              // MiniProtocol/ChainSync/Client.hs 942-992): as a batch is recorded, as the capacity
              // wait follows our chain, and after each step of the publisher, which takes a trim its
              // old cursor held back ([[CandidateState.trimmed]]). Each trim reads our anchor just
              // before it trims. The gate's base moves, forward or back, only in ChainSync's own flow,
              // so the batch and the capacity wait trim with what they read last.
              trim = anchor.flatMap(_.traverse_(at => commit(_.trimmed(at)).void))
              // The publisher's trim reads our anchor after its step moved the cursor, so it sees
              // every base a batch recorded before that saw. It trims only the state its step left: a
              // transition in between, a batch that may have moved the base back, a restart or the
              // capacity wait's trim, made its own trim against the moved cursor.
              trimAfter = (step: CandidateState) =>
                anchor.flatMap(
                  _.traverse_(at =>
                    commit(state =>
                      if state.revision == step.revision then state.trimmed(at) else state
                    ).void
                  )
                )
              capacity = (update: ChainSyncClient.Update[NtNHeader]) =>
                update match
                  case ChainSyncClient.Update.RollBackward(_, _) => F.unit
                  case ChainSyncClient.Update.RollForward(_, _)  =>
                    // the room as the state stands, and what wakes the wait: the state's next change or
                    // a block's arrival
                    def room: F[(Boolean, F[Unit])] =
                      (held.get, fetcher.changed).tupled.flatMap { (current, bodyArrived) =>
                        windowRoom(cursorOf(current.state), current.state.candidate)
                          .map(_ -> F.race(current.changed.get, bodyArrived).void)
                      }
                    room.flatMap {
                      case (true, _) => F.unit
                      case (false, _) =>
                        patience.traverse_(
                          _.setPaused(LimitOnPatience.PauseReason.LocalCapacity, true)
                        ) *>
                          // While the window is full no header comes to move the gate's history:
                          // our chain's changes are followed here, as the reference's client
                          // follows them in its waits (`intersectsWithCurrentChain` in
                          // `readLedgerStateHelper`, ouroboros-consensus 82ecba329
                          // MiniProtocol/ChainSync/Client.hs 942-992, 1786-1815). The change is
                          // awaited from before the gate follows our chain and the room is weighed,
                          // so none falls between them. A history that no longer passes through our
                          // anchor ends the wait with a restart, which drains the pipeline before
                          // any header is taken.
                          F.tailRecM(()) { _ =>
                            gate.get.flatMap { current =>
                              current.ourChainChanged.flatMap { ourChain =>
                                current.followOurChain.flatMap {
                                  case HeaderGate.Following.NoLongerIntersects(tip) =>
                                    // as the reference drains and finds a new intersection: the
                                    // header is not taken
                                    pendingRestart.set(Some(tip)).as(Right(()))
                                  case following =>
                                    (following match
                                      case HeaderGate.Following.Trimmed => trim
                                      case _                            => F.unit
                                    ) *> room.flatMap {
                                      case (true, _) => F.pure(Right(()))
                                      case (false, wake) =>
                                        F.race(
                                          drainRequested.get.flatMap(_.get),
                                          F.race(wake, ourChain.getOrElse(F.never[Unit]))
                                        ).map {
                                          case Left(_)  => Right(())
                                          case Right(_) => Left(())
                                        }
                                    }
                                }
                              }
                            }
                          }.guarantee(
                            patience.traverse_(
                              _.setPaused(LimitOnPatience.PauseReason.LocalCapacity, false)
                            )
                          )
                    }
              intersectionEnded <- Ref.of[F, Option[PeerError]](None)
              // a batch is in the candidate state once it is recorded; one that ended or restarted
              // the session pauses publication until a restart gives a tip again
              publish = (batch: Vector[SyncEvent]) => change(_.paused).void.whenA(batch.isEmpty)
              _ <- supervised {

                /** Find a new intersection with our current chain. `candidates` offers points when
                  * neither a captured fragment nor the pipeline's local points are available;
                  * `restarted` tells chain-sync jumping that the session restarted at a point.
                  */
                def restart(
                    candidates: F[Vector[ChainSync.Point]]
                )(using Raise[F, PeerError]): F[Unit] =
                  for
                    offered <- offer
                    (captured, startAt) = offered
                    metadata <- heightSnapshot.capture
                    points <- captured.fold(
                      if connection.chainSyncPipeline.nonEmpty then F.pure(metadata.points)
                      else candidates
                    )(c => F.pure(c._1))
                    offeredHeights = captured.fold(metadata.heights)(_._2)
                    _ <- change(_.offered(offeredHeights.updated(ChainSync.Point.Origin, None)))
                    found <- connection.chainSync.findIntersect(intersectionPoints(points))
                    _ <- found match
                      case None =>
                        finishChainSync *> PeerError.NoIntersection.raise[F, Unit]
                      case Some((point, tip)) =>
                        val candidate = ChainSelection.Candidate(point, Vector.empty)
                        startAt(point) *> remember(candidate, tip) *>
                          capturedHeight(point).flatMap(height =>
                            progress.set(Some(tip -> height.fold(BigInt(0))(_.value)))
                          ) *>
                          observe.traverse_(_(Observation.Intersected(point)))
                  yield ()
                def restartWith(control: GenesisPeer[F])(using Raise[F, PeerError]): F[Unit] =
                  restart(control.restartCandidates)
                def request(using Raise[F, PeerError]): F[Unit] =
                  genesis.fold(
                    pendingRestart.getAndSet(None).flatMap {
                      case Some(point) =>
                        // Without local points to offer, the detached candidate ends the session.
                        restart(
                          PeerError
                            .Diverged(PeerError.Divergence.DetachedCandidate(point))
                            .raise[F, Vector[ChainSync.Point]]
                        )
                      case None => requestHeadersStreaming(publish, capacity)
                    }
                  ) { control =>
                    import xyz.kd5ujc.scarab.network.selection.ChainSyncJumping.Instruction
                    pendingRestart.getAndSet(None).flatMap {
                      case Some(_) => restartWith(control)
                      case None    =>
                        // The ownGate branch: instructions are taken only once the session holds
                        // its gate, as the reference's client loop exists only with its
                        // KnownIntersectionState
                        F.race(
                          drainRequested.get.flatMap(_.get),
                          ownGate *> pendingJump
                            .getAndSet(None)
                            .flatMap(_.fold(control.next)(F.pure))
                        ).flatMap {
                          case Left(_) => F.unit
                          case Right(instruction) =>
                            instruction match {
                              case Instruction.RunNormally =>
                                requestHeadersStreaming(publish, capacity)
                              case Instruction.Restart         => restartWith(control)
                              case Instruction.Wait            => F.cede
                              case Instruction.Failed(problem) => control.failed[Unit](problem)
                              case Instruction.Jump(info, goodPoint) =>
                                connection.chainSync.findIntersectResult(Vector(info.tip)).flatMap {
                                  result =>
                                    candidateHeaders.get.flatMap(candidate =>
                                      capturedHeight(candidate.tip).flatMap(height =>
                                        progress
                                          .set(Some(result.tip -> height.fold(BigInt(0))(_.value)))
                                      )
                                    ) *>
                                      ownGate
                                        .flatMap(
                                          control.processJump(
                                            _,
                                            info,
                                            goodPoint,
                                            result.toOption.map(_._1)
                                          )
                                        )
                                        .flatMap {
                                          case GenesisPeer.Applied.Retained => F.unit
                                          case GenesisPeer.Applied.Restart  => restartWith(control)
                                          case GenesisPeer.Applied.Grafted(candidate) =>
                                            remember(candidate, result.tip) *>
                                              capturedHeight(candidate.tip).flatMap(height =>
                                                progress.set(
                                                  Some(
                                                    result.tip -> height.fold(BigInt(0))(_.value)
                                                  )
                                                )
                                              )
                                        }
                                }
                            }
                        }
                    }
                  }
                F.tailRecM(()) { _ =>
                  drainCompletion.get.flatMap {
                    case None =>
                      Fault.fiber(request).flatMap {
                        case Left(
                              Fault.Failed(
                                error @ (PeerError.NoIntersection |
                                _: PeerError.RolledBackPastIntersection)
                              )
                            ) =>
                          intersectionEnded.set(Some(error)) *> change(_.ended).as(Right(()))
                        case other => Fault.land(other).as(Left(()))
                      }
                    case Some(_) => change(_.ended).as(Right(()))
                  }
                }
              }
              // ChainSync is done and everything is published: the session ends, with the error of
              // the intersection that ended it, if one did
              finish = intersectionEnded.get.flatMap {
                case Some(error) =>
                  val fault = Fault.Failed(error)
                  pipelineFailure.set(Some(fault)) *> queue.offer(PipelineEvent.Failed(fault))
                case None => queue.offer(PipelineEvent.Drained)
              }
              // The publisher's step: the events since the cursor and the cursor moved to them, in one
              // transition ([[CandidateState.published]]); then the candidate trimmed to our anchor as
              // read after that ([[trimAfter]]), which takes a trim the old cursor held back; then the
              // events offered to the consumer in order. The candidate is published as ChainSync accepts it; nothing
              // here waits for a body (BlockFetch is independent of ChainSync, State.hs:317–335).
              _ <- F.tailRecM(()) { _ =>
                permits.acquire *> transition { state =>
                  state.published(settings.readAhead) match
                    case Left(divergence)      => (state, Left(divergence))
                    case Right((next, events)) => (next, Right((next, events)))
                }.flatMap {
                  case (Left(divergence), _) =>
                    PeerError.Diverged(divergence).raise[F, Either[Unit, Unit]]
                  case (Right((state, events)), changed) =>
                    trimAfter(state) *> (
                      if events.nonEmpty then
                        events.zipWithIndex
                          .traverse_ { (event, index) =>
                            permits.acquire.whenA(index > 0) *>
                              queue.offer(PipelineEvent.Batch(Vector(event)))
                          }
                          .as(Left(()))
                      // nothing to publish: the end, or a wait for the state's next change
                      else if state.publication.exists(_.done) then
                        permits.release *> finish.as(Right(()))
                      else permits.release *> changed.get.as(Left(()))
                    )
                }
              }
            yield ()

          val start: F[Unit] =
            startOnce(started) {
              observersStarted.getAndSet(true).flatMap { alreadyRunning =>
                // Every fetch decision reads the connection's GSV estimate itself (native
                // State.hs:418-421); nothing copies it between decisions.
                (supervised(invalidBlockRejector) *> supervised(fetchClient) *>
                  connection.fetchPerformance.traverse_(fetcher.measured))
                  .unlessA(alreadyRunning)
              } *> (if streamed then supervised(streaming)
                    else
                      supervised(
                        candidateHeaders.get
                          .flatMap(Ref.of[F, ChainSelection.Candidate])
                          .flatMap(produce)
                      ))
            }

          /** A governor may cancel `next` before asking us to drain. Return the permit in the
            * queue-taking fiber itself, before a surrounding race can cancel its handoff.
            */
          def take: F[PipelineEvent] = F.uncancelable { poll =>
            poll(queue.take).flatTap {
              case PipelineEvent.Batch(_) => permits.release
              case _                      => F.unit
            }
          }

          /** The next instruction, raising the fault of a fiber of the pipeline in the calling
            * fiber: its peer error through `Raise`, an exception that is not ours as it came.
            */
          def pull(using Raise[F, PeerError]): F[SyncEvent] =
            start *> current.modify(q => (q.drop(1), q.headOption)).flatMap {
              case Some(event) => F.pure(event)
              case None =>
                F.race(claims.blamed, take).flatMap {
                  case Left(refusal) => PeerError.InvalidBlock(refusal).raise[F, SyncEvent]
                  case Right(PipelineEvent.Failed(fault)) => Fault.land(Left(fault))
                  case Right(PipelineEvent.Batch(batch))  => current.set(batch) *> pull
                  case Right(PipelineEvent.Drained) =>
                    PeerError
                      .Unavailable(PeerError.Unavailability.SessionEnded)
                      .raise[F, SyncEvent]
                }
            }

          /** Stop new requests, collect the in-flight replies and await every publication. Draining
            * also returns queued batch permits. Faults retain their typed cause; an old producer
            * failure must not be hidden by a successful new FindIntersect.
            */
          def drain(using Raise[F, PeerError]): F[Unit] = pipelineFailure.get.flatMap {
            // The normal ChainSync exit is published only after the body dispatcher has finished
            // its ordered requests. Its terminal queue marker may already have been consumed.
            case Some(
                  Fault.Failed(PeerError.NoIntersection | _: PeerError.RolledBackPastIntersection)
                ) =>
              current.set(Vector.empty)
            case _ => drainPipeline
          }

          def drainPipeline(using Raise[F, PeerError]): F[Unit] = F.uncancelable { poll =>
            for
              fresh <- Deferred[F, DrainOutcome]
              waiting <- drainCompletion.modify {
                case None                  => Some(fresh) -> None
                case some @ Some(existing) => some -> Some(existing)
              }
              _ <- drainRequested.get.flatMap(_.complete(()).void)
              _ <- waiting match
                case Some(ended) =>
                  poll(ended.get).flatMap {
                    case DrainOutcome.Complete      => F.unit
                    case DrainOutcome.Failed(fault) => Fault.land(Left(fault))
                    case DrainOutcome.Cancelled     => poll(F.canceled *> F.never[Unit])
                  }
                case None =>
                  poll(
                    Fault.fiber {
                      started.getAndSet(true).flatMap { active =>
                        // The marker follows every published batch and returns the producer's final
                        // credit. No competing consumer is cancelled at the successful drain boundary.
                        F.tailRecM(()) { _ =>
                          F.race(claims.blamed, take).flatMap {
                            case Left(refusal) =>
                              PeerError.InvalidBlock(refusal).raise[F, Either[Unit, Unit]]
                            case Right(PipelineEvent.Drained)  => F.pure(Right(()))
                            case Right(PipelineEvent.Batch(_)) => F.pure(Left(()))
                            case Right(PipelineEvent.Failed(fault)) =>
                              Fault.land[F, Either[Unit, Unit]](Left(fault))
                          }
                        }.whenA(active) *> current.set(Vector.empty)
                      }
                    }
                  ).onCancel(fresh.complete(DrainOutcome.Cancelled).void).flatMap { result =>
                    fresh.complete(
                      result.fold(DrainOutcome.Failed(_), _ => DrainOutcome.Complete)
                    ) *>
                      Fault.land(result)
                  }
            yield ()
          }

          new Controlled[F]:
            override def headerCandidate = Some(candidateHeaders)
            override def selectionWork: Option[SelectionWork.Session[F]] = workSession
            def quiesce: F[Unit] =
              // Quiescing ends this hot session; a pending fault is of no further interest. Its
              // fetch client takes no further request and collects the ranges it sent
              // (`senderTerminate`, Client.hs:182–190).
              Fault.fiber(drain).void *> fetcher.terminate *> fetcher.settled

            def intersect(candidates: Vector[ChainSync.Point])(using Raise[F, PeerError]) =
              for
                _ <- drain
                _ <- pipelineFailure.get.flatMap {
                  case Some(
                        fault @ Fault.Failed(
                          PeerError.NoIntersection | _: PeerError.RolledBackPastIntersection
                        )
                      ) =>
                    Fault.land[F, Unit](Left(fault))
                  case _ => F.unit
                }
                _ <- pendingRestart.set(None) *> pendingEnd.set(None) *> pendingJump.set(
                  None
                ) *> progress.set(
                  None
                ) *>
                  change(_.reset(ChainSelection.Candidate(ChainSync.Point.Origin, Vector.empty)))
                offered <- offer
                (captured, startAt) = offered
                metadata <- heightSnapshot.capture
                offeredHeights = captured.fold(metadata.heights)(_._2)
                offeredPoints = captured.fold(
                  if connection.chainSyncPipeline.nonEmpty then metadata.points else candidates
                )(_._1)
                _ <- change(_.offered(offeredHeights.updated(ChainSync.Point.Origin, None)))
                found <- connection.chainSync.findIntersect(
                  intersectionPoints(offeredPoints)
                )
                _ <- finishChainSync.whenA(found.isEmpty)
                _ <- found.traverse_ { (point, tip) =>
                  change(_.reset(ChainSelection.Candidate(point, Vector.empty))) *>
                    startAt(point) *>
                    capturedHeight(point).flatMap(height =>
                      progress.set(Some(tip -> height.fold(BigInt(0))(_.value)))
                    ) *>
                    observe.traverse_(_(Observation.Intersected(point)))
                }
                _ <- Deferred[F, Unit].flatMap(drainRequested.set) *> drainCompletion.set(
                  None
                ) *> started.set(false)
              yield found.map(_._1)

            def next(using Raise[F, PeerError]): F[SyncEvent] = pull

            override def invalidBlocks = Some(claims.invalidBlocks)

            def fetch(header: Header)(using Raise[F, PeerError]): F[Bytes] =
              F.race(connection.mux.closed, fetcher.obtain(header.hash, claims.take(header.hash)))
                .flatMap {
                  case Left(cause)  => Fault.land[F, Bytes](Left(networkFault(cause)))
                  case Right(taken) => F.pure(taken.block)
                }
      }
    }
