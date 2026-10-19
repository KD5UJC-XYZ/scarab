// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.codec.DomainError
import cats.{ApplicativeThrow, Defer, MonadThrow}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{
  BlockFailure,
  EvaluationUnavailable,
  LedgerFailure,
  NodeError,
  PeerError,
  StorageError,
  ValidationError
}
import xyz.kd5ujc.scarab.network.Fibers

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
  * these and by no exception: an unexpected exception is [[SessionEnd.Defect]], never a failure of
  * the connection (issue 154).
  */
enum SessionEnd:
  /** The session returned (the follower never does). */
  case Returned

  /** The peer or the connection failed: [[FailurePolicy.classify]] says whether the peer is
    * forgotten (it broke the protocol or sent an invalid header) or tried again after a delay.
    */
  case Failed(error: PeerError)

  /** A block this peer sent is rejected by our rules. The peer is forgotten; the session ends and
    * nothing of the block was applied. The node and its other sessions go on.
    */
  case Rejected(error: ValidationError)

  /** This implementation cannot evaluate the block: there is no ledger verdict. The node stops
    * without peer blame; reconnecting cannot supply a missing implementation.
    */
  case Unavailable(error: EvaluationUnavailable)

  /** The storage failed under the session. Nothing a reconnect can repair: the node stops and
    * recovers from disk on the next start.
    */
  case Storage(error: StorageError)

  /** A failure of the node itself that is not the peer's, not a rejected block and not storage (a
    * local chain model that does not hold, a forging fault): the node stops.
    */
  case Internal(error: NodeError)

  /** An exception nobody expected: a bug, or a library that threw. The node stops, as it does for
    * storage.
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
    case e: EvaluationUnavailable => Unavailable(e)
    case e: PeerError             => Failed(e)
    case e: ValidationError       => Rejected(e)
    case e: StorageError          => Storage(e)
    case other                    => Internal(other)

  /** The session owns a fresh node-error scope for its rule/store operations. The enclosing `node`
    * scope remains live for compatibility with other enclosing services; its errors are caught in
    * the session that meets them. Neither scope turns an unexpected exception into a peer failure.
    */
  def scoped[F[_]: MonadThrow: Defer](node: Handle[F, NodeError])(
      body: (Handle[F, SessionEnd], Handle[F, NodeError]) ?=> F[SessionEnd]
  ): F[SessionEnd] =
    Fibers
      .scoped[F, SessionEnd, SessionEnd](Defect(_)) {
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

  /** The scope of a session's fiber has the outcome as its error. */
  def raiseOf[F[_]](scope: Raise[F, SessionEnd]): Raise[F, PeerError] =
    Peer.contramapped[F, SessionEnd, PeerError](scope)(Failed(_))

/** What the node must do about an outcome that no peer-selection policy can handle. */
enum Fatal extends DomainError:
  case Unavailable(error: EvaluationUnavailable)

  case Storage(error: StorageError)
  case Internal(error: NodeError)
  case Defect(cause: Throwable)

  def message: String = this match
    case Unavailable(e) => e.message
    case Storage(e)     => s"storage: ${e.message}"
    case Internal(e)    => s"internal: ${e.message}"
    case Defect(t)      => s"defect: ${Option(t.getMessage).getOrElse(t.getClass.getName)}"

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
