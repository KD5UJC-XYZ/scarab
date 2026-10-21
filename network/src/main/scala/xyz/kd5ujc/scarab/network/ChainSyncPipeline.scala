// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.{Mutex, Queue}
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.{ChainSyncEngine, NetworkError}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Role, State}
import xyz.kd5ujc.scarab.protocol.ChainSyncEngine.Message

/** One owned receiver and ordered complete replies. Observed AwaitReply callbacks execute
  * immediately in the receiver's error scope, independent of sender validation.
  */
private[network] object ChainSyncPipeline:
  def resource[F[_]: Async, Q, T, P](
      channel: MuxChannel[F],
      point: ChainSyncEngine.PointCodec[Q],
      tip: ChainSyncEngine.TipCodec[T],
      payload: ChainSyncEngine.PayloadCodec[P],
      abort: NetworkError => F[Unit],
      settings: ChainSyncRuntime.ClientSettings,
      maxOutstanding: Int
  )(using Raise[F, NetworkError]): Resource[F, ChainSyncRuntime.Pipeline[F, Q, T, P]] =
    val F = Async[F]
    val id = Protocols.ChainSync
    type Update = ChainSyncRuntime.Update[Q, T, P]
    sealed trait Event
    final case class Await(ack: Deferred[F, Unit]) extends Event
    final case class Reply(update: Update) extends Event
    final case class Request(hook: Option[ChainSyncClient.ReceiverHook[F]])
    val cancelled = NetworkError.BearerClosed(NetworkError.Closure.Aborted)
    def illegal[A](cause: NetworkError.RuntimeViolation)(using Raise[F, NetworkError]): F[A] =
      NetworkError.IllegalTransition(id, cause).raise[F, A]
    for
      _ <- Resource.eval(
        illegal[Unit](NetworkError.RuntimeViolation.InvalidPipelineCapacity(maxOutstanding))
          .whenA(maxOutstanding <= 0 || maxOutstanding > 65535)
      )
      base <- Resource.eval(ChainSyncRuntime.client(channel, point, tip, payload, settings))
      submissions <- Resource.eval(Queue.bounded[F, Request](maxOutstanding))
      replies <- Resource.eval(Queue.bounded[F, Event](maxOutstanding + 1))
      head <- Resource.eval(Ref.of[F, Option[Event]](None))
      count <- Resource.eval(Ref.of[F, Int](0))
      awaiting <- Resource.eval(Ref.of[F, Boolean](false))
      observer <- Resource.eval(Ref.of[F, Option[ChainSyncClient.Observer[F]]](None))
      terminal <- Resource.eval(Ref.of[F, Boolean](false))
      failed <- Resource.eval(Deferred[F, NetworkError])
      sending <- Resource.eval(Mutex[F])
      pipeline <-
        def terminate(error: NetworkError): F[Unit] =
          F.uncancelable(_ => failed.complete(error).flatMap(first => abort(error).whenA(first)))
        def wire[A](body: Raise[F, NetworkError] ?=> F[A])(using Raise[F, NetworkError]): F[A] =
          Fibers.scoped[F, NetworkError, A](NetworkError.Defect(_))(body).flatMap {
            case Left(error)  => terminate(error) *> error.raise[F, A]
            case Right(value) => F.pure(value)
          }
        def healthy(using Raise[F, NetworkError]): F[Unit] =
          failed.tryGet.flatMap(_.traverse_(_.raise[F, Unit])) *>
            terminal.get.flatMap(ended =>
              illegal[Unit](NetworkError.RuntimeViolation.PipelinedRequest(State.Done)).whenA(ended)
            )
        def receive(current: State)(using Raise[F, NetworkError]): F[Message[Q, T, P]] =
          val timeout =
            if current == State.NextCanAwait then
              settings.timeouts.fold[ProtocolDeadline](
                ProtocolDeadline.After(settings.canAwaitTimeout)
              )(_.canAwait)
            else
              settings.timeouts.fold[ProtocolDeadline](
                ProtocolDeadline.After(settings.mustReplyTimeout)
              )(_.mustReply)
          Timed
            .within(timeout, id, current.toString)(
              channel.recv(settings.limits.maxMessageBytes)(
                ChainSyncEngine.decodePrefix(
                  current,
                  Role.Server,
                  _,
                  point,
                  tip,
                  payload,
                  settings.limits
                )
              )
            )
            .flatTap(message =>
              ChainSyncEngine
                .transition(current, Role.Server, message)
                .fold(
                  cause => NetworkError.IllegalTransition(id, cause).raise[F, Unit],
                  _ => F.unit
                )
            )
        def receiveReply(
            hook: Option[ChainSyncClient.ReceiverHook[F]]
        )(using Raise[F, NetworkError]): F[Unit] =
          F.tailRecM(State.NextCanAwait) { current =>
            receive(current).flatMap {
              case Message.AwaitReply =>
                // The native pipelined ReceiverEffect runs independently of sender validation.
                // An observed Await must not wait behind already queued RF/RB replies.
                hook
                  .fold(
                    Deferred[F, Unit].flatMap(ack => replies.offer(Await(ack)) *> ack.get)
                  )(_.awaitReply *> awaiting.set(true))
                  .as(Left(State.NextMustReply))
              case Message.RollForward(value, serverTip) =>
                replies
                  .offer(Reply(ChainSyncRuntime.Update.RollForward(value, serverTip)))
                  .as(Right(()))
              case Message.RollBackward(serverPoint, serverTip) =>
                replies
                  .offer(Reply(ChainSyncRuntime.Update.RollBackward(serverPoint, serverTip)))
                  .as(Right(()))
              case other => illegal(NetworkError.RuntimeViolation.ChainSyncEngineUnexpected(other))
            }
          }
        val worker = F.tailRecM(()) { _ =>
          submissions.take.flatMap { request =>
            Fibers
              .scoped[F, NetworkError, Unit](NetworkError.Defect(_))(receiveReply(request.hook))
              .flatMap {
                case Left(error) => terminate(error).as(Right(()))
                case Right(_)    => F.pure(Left(()))
              }
          }
        }
        val ownedWorker = Fibers
          .scoped[F, NetworkError, Unit](NetworkError.Defect(_))(worker)
          .flatMap(_.fold(terminate, _ => F.unit))
        def shutdown: F[Unit] = count.get.flatMap(n => terminate(cancelled).whenA(n > 0))
        Resource
          .make(ownedWorker.start)(fiber =>
            (shutdown *> terminal.set(true)).guarantee(fiber.cancel)
          )
          .as {
            new ChainSyncRuntime.Pipeline[F, Q, T, P]:
              private def guarded[A](operation: F[A]): F[A] =
                operation.onCancel(terminate(cancelled)).onError { case _ => terminate(cancelled) }

              def outstanding: F[Int] = count.get
              def idle: F[Boolean] =
                (count.get, base.idle, failed.tryGet, terminal.get)
                  .mapN((n, b, f, t) => n == 0 && b && f.isEmpty && !t)
              def idling: F[Boolean] = awaiting.get

              def submit(using Raise[F, NetworkError]): F[Unit] = sendRequest(None)

              def submitObserved(hook: ChainSyncClient.ReceiverHook[F])(using
                  Raise[F, NetworkError]
              ): F[Unit] =
                sendRequest(Some(hook))

              private def sendRequest(hook: Option[ChainSyncClient.ReceiverHook[F]])(using
                  Raise[F, NetworkError]
              ): F[Unit] = guarded {
                sending.lock.surround {
                  healthy *> count.get.flatMap { n =>
                    if n >= maxOutstanding then
                      illegal(NetworkError.RuntimeViolation.PipelineCapacity(maxOutstanding))
                    else
                      F.uncancelable { poll =>
                        count.update(_ + 1) *>
                          poll(wire {
                            ChainSyncEngine
                              .encode(
                                State.Idle,
                                Role.Client,
                                Message.RequestNext,
                                point,
                                tip,
                                payload,
                                settings.limits
                              )
                              .fold(
                                cause => NetworkError.IllegalTransition(id, cause).raise[F, Unit],
                                channel.send
                              )
                          }) *>
                          submissions.offer(Request(hook))
                      }
                  }
                }
              }

              private def peek: F[Option[Event]] = head.get.flatMap {
                case some @ Some(_) => F.pure(some)
                case None           => replies.tryTake.flatTap(head.set)
              }
              private def take(using Raise[F, NetworkError]): F[Event] =
                peek.flatMap {
                  case Some(event) => head.set(None).as(event)
                  case None =>
                    F.race(failed.get, replies.take).flatMap {
                      case Left(error)  => error.raise[F, Event]
                      case Right(event) => F.pure(event)
                    }
                }

              def serviceAwait(onAwait: F[Unit])(using Raise[F, NetworkError]): F[Unit] = guarded {
                F.tailRecM(()) { _ =>
                  peek.flatMap {
                    case Some(Await(ack)) =>
                      head.set(None) *> awaiting
                        .set(true) *> onAwait *> ack.complete(()).void.as(Left(()))
                    case _ => F.pure(Right(()))
                  }
                }
              }

              private def completed(update: Update): F[Update] =
                count.update(_ - 1) *> awaiting.set(false).as(update)

              def tryCollect(onAwait: F[Unit])(using Raise[F, NetworkError]): F[Option[Update]] =
                guarded {
                  serviceAwait(onAwait) *> peek.flatMap {
                    case Some(Reply(update)) => head.set(None) *> completed(update).map(Some(_))
                    case _ =>
                      failed.tryGet.flatMap {
                        case Some(error) => error.raise[F, Option[Update]]
                        case None        => F.pure(None)
                      }
                  }
                }

              def collect(onAwait: F[Unit])(using Raise[F, NetworkError]): F[Update] = guarded {
                count.get.flatMap { n =>
                  if n <= 0 then illegal(NetworkError.RuntimeViolation.ReplyNotExpected(State.Idle))
                  else
                    F.tailRecM(()) { _ =>
                      take.flatMap {
                        case Await(ack) =>
                          awaiting.set(true) *> onAwait *> ack.complete(()).void.as(Left(()))
                        case Reply(update) => completed(update).map(Right(_))
                      }
                    }
                }
              }

              def drain(onAwait: F[Unit])(onUpdate: Update => F[Unit])(using
                  Raise[F, NetworkError]
              ): F[Unit] =
                F.tailRecM(())(_ =>
                  count.get.flatMap {
                    case 0 => F.pure(Right(()))
                    case _ => collect(onAwait).flatMap(onUpdate).as(Left(()))
                  }
                )

              def findIntersect(points: Vector[Q])(using
                  Raise[F, NetworkError]
              ): F[Option[(Q, T)]] =
                guarded(sending.lock.surround(healthy *> count.get.flatMap { n =>
                  if n != 0 then
                    illegal(NetworkError.RuntimeViolation.PipelinedRequest(State.NextCanAwait))
                  else wire(base.findIntersect(points))
                }))
              def done(using Raise[F, NetworkError]): F[Unit] =
                guarded(sending.lock.surround(healthy *> count.get.flatMap { n =>
                  if n != 0 then
                    illegal(NetworkError.RuntimeViolation.PipelinedRequest(State.NextCanAwait))
                  else wire(base.done) *> terminal.set(true)
                }))
              def observe(next: ChainSyncClient.Observer[F]): Resource[F, Unit] =
                Resource.make(observer.getAndSet(Some(next)))(observer.set).void

              private def legacyAwait: F[Unit] = observer.get.flatMap(_.traverse_(_.awaitReply))
              private def legacyReply: F[Unit] = observer.get.flatMap(_.traverse_(_.reply))

              def requestNext(using Raise[F, NetworkError]): F[Update] =
                submit *> collect(legacyAwait).flatTap(_ => legacyReply)

              def requestNextBatch(n: Int)(using Raise[F, NetworkError]): F[Vector[Update]] =
                requestNextBatchObserved(n)(_ => F.unit)

              def requestNextBatchObserved(n: Int)(onUpdate: Update => F[Unit])(using
                  Raise[F, NetworkError]
              ): F[Vector[Update]] =
                F.tailRecM((n.max(1), Vector.empty[Update])) { (remaining, acc) =>
                  val batch = remaining.min(maxOutstanding)
                  List.fill(batch)(()).traverse_(_ => submit) *>
                    List
                      .fill(batch)(())
                      .traverse(_ =>
                        collect(legacyAwait).flatTap(_ => legacyReply).flatTap(onUpdate)
                      )
                      .map { results =>
                        val next = acc ++ results
                        if remaining == batch then Right(next) else Left((remaining - batch, next))
                      }
                }
          }
    yield pipeline
