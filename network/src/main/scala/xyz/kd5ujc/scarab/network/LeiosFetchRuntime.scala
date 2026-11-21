// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Deferred, Ref}
import cats.effect.std.Queue
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{
  EbPoint,
  LeiosFetch,
  LeiosTxHash,
  LeiosWireError,
  ProtocolError,
  TxBitmaps
}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word32

/** A request of the LeiosFetch client with what to do with its answer (`SomeLeiosFetchJob`,
  * `LeiosDemoOnlyTestFetch.hs` 494-499 of ouroboros-consensus 2416493b): the message the client
  * sends from Idle and the continuation the answer is handed to. The client is told nothing else
  * about it; what the request was for is the continuation's.
  */
enum LeiosFetchJob[F[_]]:
  /** `MsgLeiosBlockRequest`, answered by `MsgLeiosBlock`. */
  case Block(point: EbPoint, onReply: Vector[(LeiosTxHash, Word32)] => F[Unit])

  /** `MsgLeiosBlockTxsRequest`, answered by `MsgLeiosBlockTxs`, its transactions as they came. */
  case BlockTxs(point: EbPoint, bitmaps: TxBitmaps, onReply: Vector[Bytes] => F[Unit])

/** The pipelined LeiosFetch client (`leiosFetchClientPeerPipelined`, `LeiosDemoOnlyTestFetch.hs`
  * 587-683 at 2416493b), run by the reference's pipelined driver (`runPipelinedPeerWithLimits`).
  * `next` is the next instruction: the client's result, which ends it once every answer outstanding
  * has arrived, or a job; or, when neither is ready, the action that waits for one.
  *
  *   - With no answer outstanding a job is sent at once; at the pipeline's depth the client waits
  *     for an answer first; below it, it collects an answer that has arrived, if one has, and
  *     sends.
  *   - With nothing ready, the client waits for an answer while any is outstanding, and for the
  *     next instruction otherwise.
  *   - With the result, the remaining answers are collected and not handed on (`AlreadyDraining`),
  *     and the client sends `MsgDone`.
  *
  * The answers are received by a receiver of their own, as the driver's receiver queue receives
  * them: each is handed to its job's continuation as it arrives, in the order sent, and counted as
  * arrived only after. Each is awaited under the Block or BlockTxs state's time limit and received
  * under its byte limit (`timeLimitsLeiosFetch`, `byteLimitsLeiosFetch`, `:231-253`).
  */
object LeiosFetchPipelined:
  /** `leiosFetchPipelineDepth` (`Ouroboros/Consensus/Network/NodeToNode.hs` 2148-2168 at 2416493b):
    * the most requests left outstanding on one connection.
    */
  val PipelineDepth: Int = 150

  /** `longWait`, the time limit of Block and BlockTxs (Idle waits forever). */
  val BusyTimeout: FiniteDuration = 60.seconds

  final case class Settings(depth: Int = PipelineDepth, busyTimeout: FiniteDuration = BusyTimeout)

  type Instruction[F[_], A] = Either[A, LeiosFetchJob[F]]

  def run[F[_], A](
      channel: MuxChannel[F],
      next: F[Either[F[Instruction[F, A]], Instruction[F, A]]],
      settings: Settings = Settings()
  )(using F: Async[F], raise: Raise[F, NetworkError]): F[A] =
    val id = Protocols.LeiosFetch
    def illegal[B](cause: NetworkError.Violation)(using Raise[F, NetworkError]): F[B] =
      NetworkError.IllegalTransition(id, cause).raise[F, B]
    def send(message: LeiosFetch.Message)(using Raise[F, NetworkError]): F[Unit] =
      LeiosFetch.encode(message).fold(e => illegal(ProtocolError.Leios(e)), channel.send)
    for
      pending <- Queue.unbounded[F, Option[LeiosFetchJob[F]]]
      arrived <- Queue.unbounded[F, Unit]
      draining <- Ref.of[F, Boolean](false)
      result <- Deferred[F, A]
      sender = Fibers.scoped[F, NetworkError, Unit](NetworkError.Defect(_)) {
        def go1(n: Int): F[Unit] =
          next.flatMap {
            case Left(waiting) =>
              if n == 0 then waiting.flatMap(go2(0, _)) else arrived.take *> go1(n - 1)
            case Right(instruction) => go2(n, instruction)
          }
        def go2(n: Int, instruction: Instruction[F, A]): F[Unit] = instruction match
          case Left(a) =>
            draining.set(true) *> arrived.take.replicateA_(n) *> send(LeiosFetch.Message.Done) *>
              pending.offer(None) *> result.complete(a).void
          case Right(job) =>
            if n == 0 then dispatch(n, job)
            else if n >= settings.depth then arrived.take *> dispatch(n - 1, job)
            else arrived.tryTake.flatMap(_.fold(dispatch(n, job))(_ => dispatch(n - 1, job)))
        def dispatch(n: Int, job: LeiosFetchJob[F]): F[Unit] =
          val request = job match
            case LeiosFetchJob.Block(point, _)       => LeiosFetch.Message.BlockRequest(point)
            case LeiosFetchJob.BlockTxs(point, b, _) => LeiosFetch.Message.BlockTxsRequest(point, b)
          send(request) *> pending.offer(Some(job)) *> go1(n + 1)
        go1(0)
      }
      receiver = Fibers.scoped[F, NetworkError, Unit](NetworkError.Defect(_)) {
        def handed(action: F[Unit]): F[Unit] = draining.get.ifM(F.unit, action)
        def receive(state: LeiosFetch.State, limit: Int): F[LeiosFetch.Message] =
          Timed.within(settings.busyTimeout, id, state.toString)(
            channel.recv(limit)(LeiosFetch.incremental(state))
          )
        def loop: F[Unit] =
          pending.take.flatMap {
            case None => F.unit
            case Some(LeiosFetchJob.Block(_, onReply)) =>
              receive(LeiosFetch.State.Block, LeiosFetch.BlockByteLimit).flatMap {
                case LeiosFetch.Message.Block(txs) => handed(onReply(txs))
                case other                         => unexpected(other)
              } *> arrived.offer(()) *> loop
            case Some(LeiosFetchJob.BlockTxs(_, _, onReply)) =>
              receive(LeiosFetch.State.BlockTxs, LeiosFetch.BlockTxsByteLimit).flatMap {
                case LeiosFetch.Message.BlockTxs(txs) => handed(onReply(txs))
                case other                            => unexpected(other)
              } *> arrived.offer(()) *> loop
          }
        loop
      }
      outcome <- Fibers.failFast(List(sender, receiver))
      a <- outcome.fold(_.raise[F, A], _ => result.get)
    yield a

  /** A message the state's decoder admitted that is not the state's answer: the decoders admit
    * none, so this is the codec's defect, raised as the transition it would be.
    */
  private def unexpected[F[_], A](message: LeiosFetch.Message)(using Raise[F, NetworkError]): F[A] =
    NetworkError
      .IllegalTransition(
        Protocols.LeiosFetch,
        ProtocolError.Leios(LeiosWireError.NotAdmitted(tag(message)))
      )
      .raise[F, A]

  private[network] def tag(message: LeiosFetch.Message): Int = message match
    case LeiosFetch.Message.Done                  => 0
    case LeiosFetch.Message.BlockRequest(_)       => 1
    case LeiosFetch.Message.Block(_)              => 2
    case LeiosFetch.Message.BlockTxsRequest(_, _) => 3
    case LeiosFetch.Message.BlockTxs(_)           => 4

/** What the LeiosFetch server answers with (`LeiosFetchRequestHandler`, `LeiosDemoOnlyTestFetch.hs`
  * 538-543 at 2416493b): the body of a requested endorser block, and the requested transactions in
  * the order of the request's bitmaps. Either raises the refusal that ends the connection
  * ([[NetworkError.LeiosFetchRefused]]).
  */
trait LeiosFetchRequestHandler[F[_]]:
  def block(point: EbPoint)(using Raise[F, NetworkError]): F[Vector[(LeiosTxHash, Word32)]]
  def blockTxs(point: EbPoint, bitmaps: TxBitmaps)(using Raise[F, NetworkError]): F[Vector[Bytes]]

/** The LeiosFetch server (`leiosFetchServerPeer`, `LeiosDemoOnlyTestFetch.hs` 545-568 at 2416493b):
  * each request answered in turn by the handler, until `MsgDone`. Idle waits forever, under its
  * byte limit (`smallByteLimit`).
  */
object LeiosFetchServer:
  val IdleByteLimit: Int = 65535

  def run[F[_]](channel: MuxChannel[F], handler: LeiosFetchRequestHandler[F])(using
      F: Async[F],
      raise: Raise[F, NetworkError]
  ): F[Unit] =
    val id = Protocols.LeiosFetch
    def send(message: LeiosFetch.Message): F[Unit] =
      LeiosFetch
        .encode(message)
        .fold(
          e => NetworkError.IllegalTransition(id, ProtocolError.Leios(e)).raise[F, Unit],
          channel.send
        )
    def loop: F[Unit] =
      channel.recv(IdleByteLimit)(LeiosFetch.incremental(LeiosFetch.State.Idle)).flatMap {
        case LeiosFetch.Message.Done => F.unit
        case LeiosFetch.Message.BlockRequest(point) =>
          handler.block(point).flatMap(txs => send(LeiosFetch.Message.Block(txs))) *> loop
        case LeiosFetch.Message.BlockTxsRequest(point, bitmaps) =>
          handler
            .blockTxs(point, bitmaps)
            .flatMap(txs => send(LeiosFetch.Message.BlockTxs(txs))) *> loop
        case other =>
          NetworkError
            .IllegalTransition(
              id,
              ProtocolError.Leios(LeiosWireError.NotAdmitted(LeiosFetchPipelined.tag(other)))
            )
            .raise[F, Unit]
      }
    loop
