// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.codec.DomainError
import cats.{ApplicativeThrow, Defer, MonadThrow}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{
  BlockFailure,
  ChainError,
  EvaluationUnavailable,
  HeaderError,
  HeaderStructureProblem,
  LedgerFailure,
  NodeError,
  PeerError,
  StorageError,
  ValidationError,
  VerificationDefect
}
import xyz.kd5ujc.scarab.network.{Fibers, NetworkError}
import xyz.kd5ujc.scarab.protocol.ChainSync

/** What a fiber of the pipeline of a session ends with, as a value: the peer or the connection
  * failed, or something that is not ours threw (a library, a bug).
  */
enum Fault:
  case Failed(error: PeerError)
  case Defect(cause: Throwable)

object Fault:
  /** `body` as a fiber of its own: in an error scope of its own, finishing with the fault as a
    * value. The `Raise` the body gets is this fiber's, so what it raises is never an exception that
    * another fiber would have to catch (issue 132).
    */
  def fiber[F[_]: ApplicativeThrow: Defer, A](
      body: Raise[F, PeerError] ?=> F[A]
  ): F[Either[Fault, A]] =
    Fibers.scoped[F, Fault, A](Fault.Defect(_)) {
      val faults = summon[Handle[F, Fault]]
      body(using Peer.contramapped[F, Fault, PeerError](faults)(Fault.Failed(_)))
    }

  /** The fault of a fiber raised in the fiber that owns it: a peer error through its `Raise`, an
    * exception that is not ours as it came (to the session's boundary, where it is a defect).
    */
  def land[F[_], A](result: Either[Fault, A])(using
      F: ApplicativeThrow[F],
      raise: Raise[F, PeerError]
  ): F[A] = result match
    case Right(a)                  => F.pure(a)
    case Left(Fault.Failed(error)) => raise.raise[PeerError, A](error)
    case Left(Fault.Defect(cause)) => F.raiseError[A](cause)

/** How a hot session ended: the closed set of outcomes, and nothing else. A session ends by one of
  * these and by no exception: an exception of the session's own work is a
  * [[PeerError.ConnectionDefect]] that is not the peer's, one of the node is [[SessionEnd.Defect]]
  * (issues 154, 567).
  */
enum SessionEnd:
  /** The session returned (the follower never does). */
  case Returned

  /** ChainSync sent MsgDone after finding no intersection. This is a normal return, with the
    * reference's 120-second repromotion delay (consensus Node/ExitPolicy.hs:30).
    */
  case NoMoreIntersection

  /** The peer rolled back before our captured intersection (native return delay: 180 seconds). */
  case RolledBackPastIntersection(point: xyz.kd5ujc.scarab.protocol.ChainSync.Point)

  /** The peer or the connection failed: [[FailurePolicy.classify]] says whether the peer is
    * forgotten (it broke the protocol or sent an invalid header) or tried again after a delay.
    */
  case Failed(error: PeerError)

  /** A block this peer sent is rejected by our rules. The peer is forgotten; the session ends and
    * nothing of the block was applied. The node and its other sessions go on.
    */
  case Rejected(error: ValidationError)

  /** This implementation cannot evaluate the block: there is no ledger verdict, whether the
    * implementation is missing or its evaluator failed on the block's own scripts. The exception
    * ends the reference's linked add-block thread and stops the node (ouroboros-consensus 82ecba32
    * `Storage/ChainDB/Impl/Background.hs` 107-108 and 142), so the node stops, without peer blame
    * and with nothing recorded as invalid.
    */
  case Unavailable(error: EvaluationUnavailable)

  /** A crypto provider failed while the rules verified a block: an exception from a library, as the
    * reference's block application sees it, and the node stops (ADR 0017, amendment on provider
    * defects). Nothing is recorded as invalid and no peer is blamed.
    */
  case Unverifiable(error: VerificationDefect)

  /** The storage failed under the session. Nothing a reconnect can repair: the node stops and
    * recovers from disk on the next start.
    */
  case Storage(error: StorageError)

  /** A failure of the node itself that is not the peer's, not a rejected block and not storage (a
    * local chain model that does not hold, a forging fault): the node stops.
    */
  case Internal(error: NodeError)

  /** An exception of the node that reached the session: a defect the node published to it, an
    * exception while a chain was committed, or a failure to acquire the node's own state. The node
    * stops, as it does for storage.
    */
  case Defect(cause: Throwable)

  /** The session's fiber was cancelled (the node is shutting down, or the connection is released).
    */
  case Cancelled

  /** The governor stopped it; `clean` when the protocols ended on the wire and the connection can
    * be activated again.
    */
  case Stopped(clean: Boolean)

object SessionEnd:
  /** The outcome for an error raised by the session or a live enclosing node scope. Every case of
    * [[NodeError]] is placed; none defaults to a failure of the peer.
    */
  def of(error: NodeError): SessionEnd = error match
    case PeerError.InvalidBlock(
          ValidationError(point, BlockFailure.Tx(failure: LedgerFailure.Unavailable))
        ) =>
      Unavailable(EvaluationUnavailable(point, failure))
    case ValidationError(point, BlockFailure.Tx(failure: LedgerFailure.Unavailable)) =>
      Unavailable(EvaluationUnavailable(point, failure))
    case e: EvaluationUnavailable       => Unavailable(e)
    case e: VerificationDefect          => Unverifiable(e)
    case StateFaultInHeader(fault)      => Internal(fault)
    case ChainError.CommitDefect(cause) => Defect(cause)
    case e @ PeerError.InvalidBlock(refused) =>
      refused.unverifiable.fold(Failed(e))(Unverifiable(_))
    case PeerError.NoIntersection                    => NoMoreIntersection
    case PeerError.RolledBackPastIntersection(point) => RolledBackPastIntersection(point)
    case e: PeerError                                => Failed(e)
    case e: ValidationError => e.unverifiable.fold(Rejected(e))(Unverifiable(_))
    case e: StorageError    => Storage(e)
    case other              => Internal(other)

  /** A header whose validation met a quantity of the node's own ledger state outside its vocabulary
    * type: an invariant of the node that does not hold, never the peer's fault, so the node stops.
    * The header pipeline has one error channel, the peer's, and this is where its value is placed.
    *
    * The only such values on the header path are those of the Byron to Shelley hand-over of the
    * header forecast ([[NodeHeaderRules]]): the first Shelley epoch and the keys of the genesis
    * delegations. The reference's forecast (`mkInitialShelleyForecast`, cardano-ledger f649f975
    * `TPraos/API.hs:656-668`) is total over its typed translation context, and neither value can
    * fail here:
    *   - the delegations' keys are 28-byte hashes once the genesis is read
    *     ([[xyz.kd5ujc.scarab.ledger.GenesisSchema]]);
    *   - a configured first epoch is a `Word64` once the configuration is read ([[NodeConfig]], as
    *     the reference's `EpochNo`);
    *   - the hand-over is entered only for a header whose epoch, of a `Word64` slot, is at least
    *     the first one ([[NodeRules.entry]]), whether the configuration or the Byron ledger's
    *     update state names it.
    */
  private object StateFaultInHeader:
    def unapply(error: PeerError): Option[ChainError] = error match
      case PeerError.InvalidHeader(
            ChainSync.Point.Block(slot, _),
            HeaderError.Structure(HeaderStructureProblem.Transition(failure))
          ) =>
        failure.stateOutOfRange.map(ChainError.StateOutOfRange(slot.value, _))
      case _ => None

  /** An exception of a session's own work (our ChainSync or BlockFetch client, the header pipeline,
    * a fiber the session landed) ends the session and its connection, and the peer is not charged:
    * the reference's `consensusRethrowPolicy` shuts down only the peer for an exception that is
    * neither of the storage nor of the clock (consensus 82ecba329 `Node/RethrowPolicy.hs`). A
    * session of the node commits no block: the selection worker does, in a scope of its own that
    * stops the node ([[BlockSelection]]); and a commit, wherever it runs, raises its own exceptions
    * as [[ChainError.CommitDefect]], which stops the node. A failure to acquire the node's state
    * ([[NetworkError.InternalFailure]]) is left a defect of the node (issue 567).
    */
  private def thrown(cause: Throwable): SessionEnd = cause match
    case failure: NetworkError.InternalFailure => Defect(failure)
    case other                                 => Failed(PeerError.ConnectionDefect(other))

  /** The session owns a fresh node-error scope for its rule/store operations. The enclosing `node`
    * scope remains live for compatibility with other enclosing services; its errors are caught in
    * the session that meets them. Neither scope turns an exception into a fault of the peer
    * ([[thrown]]).
    */
  def scoped[F[_]: MonadThrow: Defer](node: Handle[F, NodeError])(
      body: (Handle[F, SessionEnd], Handle[F, NodeError]) ?=> F[SessionEnd]
  ): F[SessionEnd] =
    Fibers
      .scoped[F, SessionEnd, SessionEnd](thrown) {
        val scope = summon[Handle[F, SessionEnd]]
        node
          .attempt {
            Defer[F].defer {
              Handle
                .allow[NodeError] {
                  val caller = summon[Handle[F, NodeError]]
                  Defer[F].defer(body(using scope, caller))
                }
                .rescue(e => scope.raise[SessionEnd, SessionEnd](of(e)))
            }
          }
          .flatMap(_.fold(e => scope.raise[SessionEnd, SessionEnd](of(e)), MonadThrow[F].pure))
      }
      .map(_.merge)

  /** The peer error that stands for an outcome in the log and in the governor's observations. */
  def peerError(end: SessionEnd): Option[PeerError] = end match
    case Failed(e)   => Some(e)
    case Rejected(e) => Some(PeerError.InvalidBlock(e))
    case _           => None

  /** Install the classified cause before resource cleanup cancels outstanding wire requests. */
  private[node] def networkFailure(end: SessionEnd): Option[NetworkError] = end match
    case Failed(PeerError.NoIntersection) => None
    case Failed(error) => Some(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(error)))
    case Rejected(error) =>
      Some(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(PeerError.InvalidBlock(error))))
    case Unavailable(error) =>
      Some(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(Fatal.Unavailable(error))))
    case Unverifiable(error) =>
      Some(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(Fatal.Unverifiable(error))))
    case Storage(error) =>
      Some(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(Fatal.Storage(error))))
    case Internal(error) =>
      Some(NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(Fatal.Internal(error))))
    case Defect(cause) => Some(NetworkError.Defect(cause))
    case Returned | NoMoreIntersection | RolledBackPastIntersection(_) | Cancelled | Stopped(_) =>
      None

  /** Consensus 82ecba329 Node/ExitPolicy.hs:29–35: successful ChainSync return delays. */
  def returnDelay(result: SessionEnd): Option[scala.concurrent.duration.FiniteDuration] =
    import scala.concurrent.duration.*
    result match
      case NoMoreIntersection            => Some(120.seconds)
      case RolledBackPastIntersection(_) => Some(180.seconds)
      case _                             => None

  /** NodeToNodeInitiatorResult returnPolicy, consensus 82ecba329 Node/ExitPolicy.hs:17–38. The
    * current producer preserves its available ChainSync return value; ordinary initiator completion
    * and AskedToTerminate both carry the reference's ten-second delay.
    */
  def initiatorReturnDelay(result: SessionEnd): scala.concurrent.duration.FiniteDuration =
    import scala.concurrent.duration.*
    returnDelay(result).getOrElse(10.seconds)

  /** The available producer result is preserved in the trace; missing native tip context and
    * ForkTooDeep are owned by the ChainSync client alignment (#477), not reconstructed here.
    */
  def returnReason(result: SessionEnd): Option[xyz.kd5ujc.scarab.network.selection.ReturnReason] =
    import cats.Show
    import xyz.kd5ujc.scarab.network.selection.ReturnReason
    def reason(render: SessionEnd => String): Option[ReturnReason] =
      Some(ReturnReason.Typed(result)(using Show.show(render)))
    result match
      case Returned           => reason(_ => "initiator returned")
      case NoMoreIntersection => reason(_ => "no more intersection")
      case RolledBackPastIntersection(point) =>
        reason(_ => s"rolled back past intersection: $point")
      case Stopped(true) => reason(_ => "asked to terminate")
      case _             => None

  /** The scope of a session's fiber has the outcome as its error. */
  def raiseOf[F[_]](scope: Raise[F, SessionEnd]): Raise[F, PeerError] =
    Peer.contramapped[F, SessionEnd, PeerError](scope)(of)

/** What the node must do about an outcome that no peer-selection policy can handle. */
enum Fatal extends DomainError:
  case Unavailable(error: EvaluationUnavailable)

  case Storage(error: StorageError)
  case Internal(error: NodeError)
  case Defect(cause: Throwable)

  /** A crypto provider failed while the node verified a block (a block from a peer, a stored block
    * on replay or recovery, our own block): an exception from a library, which stops the node as it
    * does in the reference (exit 7).
    */
  case Unverifiable(error: VerificationDefect)

  def message: String = this match
    case Unverifiable(e) => e.message
    case Unavailable(e)  => e.message
    case Storage(e)      => s"storage: ${e.message}"
    case Internal(e)     => s"internal: ${e.message}"
    case Defect(t)       => s"defect: ${Option(t.getMessage).getOrElse(t.getClass.getName)}"

/** What the node does with a peer after its session ended: the one place the outcomes are given a
  * policy (owner decision 8 for peers; the reference's `consensusRethrowPolicy` for the rest).
  */
enum Disposition:
  /** The peer broke a rule: the governor forgets it. */
  case Forget

  /** The connection failed or the chains parted through nobody's fault: tried again after a delay.
    */
  case Backoff

  /** Nothing to do: we ended it, or it returned. */
  case Settled

  /** The node stops (storage, an internal failure, a defect). */
  case StopNode(fatal: Fatal)
