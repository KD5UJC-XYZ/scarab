// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, ChainSyncFixtures, NetworkError}
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Message, State}

import Peers.*

class ChainSyncPipelineSuite extends FailsOnUncaught:
  private final case class Script(
      input: Queue[IO, Bytes],
      reads: Ref[IO, Int],
      started: Queue[IO, Int],
      aborts: Ref[IO, Vector[NetworkError]],
      readCancelled: Deferred[IO, Unit],
      channel: MuxChannel[IO]
  )

  private def script: IO[Script] =
    for
      input <- Queue.unbounded[IO, Bytes]
      reads <- Ref.of[IO, Int](0)
      started <- Queue.unbounded[IO, Int]
      aborts <- Ref.of[IO, Vector[NetworkError]](Vector.empty)
      readCancelled <- Deferred[IO, Unit]
    yield Script(
      input,
      reads,
      started,
      aborts,
      readCancelled,
      new MuxChannel[IO]:
        val protocol = Protocols.ChainSync
        def send(bytes: Bytes)(using Raise[IO, NetworkError]): IO[Unit] = IO.unit
        def flush: IO[Unit] = IO.unit
        def recv[A](maxBytes: Int)(decode: Bytes => DecodeResult[A])(using
            Raise[IO, NetworkError]
        ): IO[A] =
          (reads.updateAndGet(_ + 1).flatMap(started.offer) *> input.take.flatMap { bytes =>
            if bytes.size > maxBytes then
              NetworkError.IngressLimit(protocol, bytes.size).raise[IO, A]
            else
              decode(bytes) match
                case DecodeResult.Decoded(value, _) => IO.pure(value)
                case DecodeResult.Failed(cause) => NetworkError.Decode(protocol, cause).raise[IO, A]
                case DecodeResult.NeedMore =>
                  IO.raiseError(new AssertionError("test supplied incomplete message"))
          }).onCancel(readCancelled.complete(()).void)
    )

  private def encoded(state: State, message: Message[Bytes]): Bytes =
    ChainSync
      .encode(state, ChainSync.Role.Server, message, ChainSyncFixtures.rawItem)
      .fold(e => fail(e.message), identity)

  private def settings = ChainSyncClient.Settings(
    canAwaitTimeout = 5.seconds,
    mustReply = ChainSyncTimeouts.Policy.Fixed(ProtocolDeadline.After(1.second))
  )

  for rolling <- List(false, true) do
    test(s"intersection rejection retains the remote tip with rolling=$rolling") {
      typed {
        script.flatMap { s =>
          val advertised = tip(100)
          val offered = ChainSync.Point.Origin
          val client: Resource[IO, ChainSyncClient[IO, Bytes]] =
            if rolling then
              ChainSyncClient
                .resource(
                  s.channel,
                  ChainSyncFixtures.rawItem,
                  e => s.aborts.update(_ :+ e),
                  settings
                )
                .map(c => c: ChainSyncClient[IO, Bytes])
            else Resource.eval(ChainSyncClient(s.channel, ChainSyncFixtures.rawItem, settings))
          List(
            encoded(State.Intersect, Message.IntersectNotFound(advertised)),
            encoded(State.Intersect, Message.IntersectNotFound(advertised)),
            encoded(State.Intersect, Message.IntersectFound(offered, advertised)),
            encoded(State.NextCanAwait, Message.RollForward(header(1), advertised))
          ).traverse_(s.input.offer) *> client.use { c =>
            for
              rejected <- c.findIntersectResult(Vector(offered))
              legacy <- c.findIntersect(Vector(offered))
              found <- c.findIntersectResult(Vector(offered))
              update <- c.requestNext
              _ <- c.done
              aborts <- s.aborts.get
            yield
              assertEquals(rejected, ChainSyncRuntime.Intersection.NotFound(advertised))
              assertEquals(rejected.tip, advertised)
              assertEquals(rejected.toOption, None)
              assertEquals(legacy, None)
              assertEquals(found, ChainSyncRuntime.Intersection.Found(offered, advertised))
              assertEquals(found.toOption, Some(offered -> advertised))
              assertEquals(update, ChainSyncClient.Update.RollForward(header(1), advertised))
              assertEquals(aborts, Vector.empty)
          }
        }
      }.timeout(5.seconds)
    }

  test("rolling replenishment preserves reply order across rollback without draining the window") {
    typed {
      script.flatMap { s =>
        val updates = List(
          Message.RollForward(header(1), tip(10)),
          Message.RollBackward(ChainSync.Point.Origin, tip(10)),
          Message.RollForward(header(2), tip(10)),
          Message.RollForward(header(3), tip(10))
        )
        updates.traverse_(m => s.input.offer(encoded(State.NextCanAwait, m))) *>
          ChainSyncClient
            .resource(
              s.channel,
              ChainSyncFixtures.rawItem,
              e => s.aborts.update(_ :+ e),
              settings,
              maxOutstanding = 3
            )
            .use { client =>
              for
                _ <- List.fill(3)(()).traverse_(_ => client.submit)
                n0 <- client.outstanding
                first <- client.collect(IO.unit)
                _ <- client.submit
                n1 <- client.outstanding
                rest <- List.fill(3)(()).traverse(_ => client.collect(IO.unit))
                n2 <- client.outstanding
                idle <- client.idle
                _ <- client.done
                aborted <- s.aborts.get
              yield
                assertEquals(n0, 3)
                assertEquals(n1, 3)
                assertEquals(n2, 0)
                assert(idle)
                assertEquals(first, ChainSyncClient.Update.RollForward(header(1), tip(10)))
                assertEquals(
                  rest,
                  List(
                    ChainSyncClient.Update.RollBackward(ChainSync.Point.Origin, tip(10)),
                    ChainSyncClient.Update.RollForward(header(2), tip(10)),
                    ChainSyncClient.Update.RollForward(header(3), tip(10))
                  )
                )
                assertEquals(aborted, Vector.empty)
            }
      }
    }.map(result => assertEquals(result, Right(())))
  }

  test("AwaitReply acknowledges caller hooks before starting MustReply read or timeout") {
    TestControl
      .executeEmbed(typed {
        script.flatMap { s =>
          for
            entered <- Deferred[IO, Unit]
            release <- Deferred[IO, Unit]
            _ <- s.input.offer(encoded(State.NextCanAwait, Message.AwaitReply))
            _ <- s.input.offer(encoded(State.NextMustReply, Message.RollForward(header(1), tip(1))))
            _ <- ChainSyncClient
              .resource(
                s.channel,
                ChainSyncFixtures.rawItem,
                e => s.aborts.update(_ :+ e),
                settings
              )
              .use { client =>
                for
                  _ <- client.submit
                  firstRead <- s.started.take
                  collecting <- typed(
                    client.collect(entered.complete(()).void *> release.get *> IO.sleep(2.seconds))
                  ).start
                  _ <- (for
                    _ <- entered.get
                    beforeAck <- s.reads.get
                    _ <- IO(assertEquals(beforeAck, 1))
                    _ <- release.complete(())
                    result <- collecting.joinWithNever
                  yield
                    assertEquals(firstRead, 1)
                    assertEquals(
                      result,
                      Right(ChainSyncClient.Update.RollForward(header(1), tip(1)))
                    )
                  ).guarantee(release.complete(()).void *> collecting.cancel)
                yield ()
              }
          yield ()
        }
      })
      .map(result => assertEquals(result, Right(())))
  }

  test("resource release with uncollected requests aborts instead of reusing ordered replies") {
    typed {
      script.flatMap { s =>
        ChainSyncClient
          .resource(s.channel, ChainSyncFixtures.rawItem, e => s.aborts.update(_ :+ e), settings)
          .use(_.submit) *> s.aborts.get.map { aborted =>
          assertEquals(aborted, Vector(NetworkError.BearerClosed(NetworkError.Closure.Aborted)))
        }
      }
    }.map(result => assertEquals(result, Right(())))
  }

  test("cancelling a collector aborts the outstanding request and retains typed closure") {
    typed {
      script.flatMap { s =>
        for
          entered <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          _ <- s.input.offer(encoded(State.NextCanAwait, Message.AwaitReply))
          _ <- ChainSyncClient
            .resource(s.channel, ChainSyncFixtures.rawItem, e => s.aborts.update(_ :+ e), settings)
            .use { client =>
              for
                _ <- client.submit
                collecting <- typed(client.collect(entered.complete(()).void *> release.get)).start
                _ <- (for
                  _ <- entered.get
                  _ <- collecting.cancel
                  next <- typed(client.submit)
                  aborted <- s.aborts.get
                yield
                  assertEquals(next, Left(NetworkError.BearerClosed(NetworkError.Closure.Aborted)))
                  assertEquals(
                    aborted,
                    Vector(NetworkError.BearerClosed(NetworkError.Closure.Aborted))
                  )
                ).guarantee(release.complete(()).void *> collecting.cancel)
              yield ()
            }
        yield ()
      }
    }.map(result => assertEquals(result, Right(())))
  }

  test("a collector's callback that throws is published as ChainSync's own failure (issue 600)") {
    val boom = new RuntimeException("onAwait")
    typed {
      script.flatMap { s =>
        s.input.offer(encoded(State.NextCanAwait, Message.AwaitReply)) *>
          ChainSyncClient
            .resource(s.channel, ChainSyncFixtures.rawItem, e => s.aborts.update(_ :+ e), settings)
            .use { client =>
              for
                _ <- client.submit
                collected <- typed(client.collect(IO.raiseError(boom))).attempt
                next <- typed(client.submit)
                aborted <- s.aborts.get
              yield
                assertEquals(collected, Left(boom))
                assertEquals(next, Left(NetworkError.Defect(boom)))
                assertEquals(aborted, Vector(NetworkError.Defect(boom)))
            }
      }
    }.map(result => assertEquals(result, Right(())))
  }

  test("an operation's typed failure is published as itself, not as a cancellation (issue 600)") {
    val unexpected = NetworkError.IllegalTransition(
      Protocols.ChainSync,
      NetworkError.RuntimeViolation.ReplyNotExpected(State.Idle)
    )
    typed {
      script.flatMap { s =>
        ChainSyncClient
          .resource(s.channel, ChainSyncFixtures.rawItem, e => s.aborts.update(_ :+ e), settings)
          .use { client =>
            for
              collected <- typed(client.collect(IO.unit))
              aborted <- s.aborts.get
            yield
              assertEquals(collected, Left(unexpected))
              assertEquals(aborted, Vector(unexpected))
          }
      }
    }.map(result => assertEquals(result, Right(())))
  }

  test("AwaitReply alone never counts as a complete reply ready to collect") {
    typed {
      script.flatMap { s =>
        Ref.of[IO, Int](0).flatMap { observed =>
          s.input.offer(encoded(State.NextCanAwait, Message.AwaitReply)) *>
            ChainSyncClient
              .resource(
                s.channel,
                ChainSyncFixtures.rawItem,
                e => s.aborts.update(_ :+ e),
                settings
              )
              .use { client =>
                val onAwait = observed.update(_ + 1)
                for
                  _ <- client.submit
                  _ <- (client
                    .serviceAwait(onAwait) *> observed.get).iterateUntil(_ == 1).timeout(1.second)
                  ready <- client.tryCollect(onAwait)
                  n <- client.outstanding
                  waiting <- client.idling
                  _ <- s.input.offer(
                    encoded(State.NextMustReply, Message.RollForward(header(1), tip(1)))
                  )
                  update <- client.collect(onAwait)
                  calls <- observed.get
                yield
                  assertEquals(ready, None)
                  assertEquals(n, 1)
                  assert(waiting)
                  assertEquals(update, ChainSyncClient.Update.RollForward(header(1), tip(1)))
                  assertEquals(calls, 1)
              }
        }
      }
    }.map(result => assertEquals(result, Right(())))
  }

  test("rolling explicit hooks and draining do not invoke retained legacy observers") {
    typed {
      script.flatMap { s =>
        for
          legacy <- Ref.of[IO, Vector[String]](Vector.empty)
          explicit <- Ref.of[IO, Int](0)
          _ <- ChainSyncClient
            .resource(s.channel, ChainSyncFixtures.rawItem, e => s.aborts.update(_ :+ e), settings)
            .use { client =>
              client
                .observe(new ChainSyncClient.Observer[IO]:
                  def awaitReply = legacy.update(_ :+ "await")
                  def reply = legacy.update(_ :+ "reply"))
                .use { _ =>
                  def offer = s.input.offer(encoded(State.NextCanAwait, Message.AwaitReply)) *>
                    s.input.offer(
                      encoded(State.NextMustReply, Message.RollForward(header(1), tip(1)))
                    )
                  for
                    _ <- offer *> client.submit *> client.collect(explicit.update(_ + 1))
                    before <- legacy.get
                    _ <- offer *> client.requestNext
                    _ <- offer *> client.submit *> client
                      .drain(explicit.update(_ + 1))(_ => IO.unit)
                    after <- legacy.get
                    calls <- explicit.get
                  yield
                    assertEquals(before, Vector.empty)
                    assertEquals(after, Vector("await", "reply"))
                    assertEquals(calls, 2)
                }
            }
        yield ()
      }
    }.map(result => assertEquals(result, Right(())))
  }

  test(
    "receiver decode failure remains the same typed cause for collector, abort, and later calls"
  ) {
    typed {
      script.flatMap { s =>
        ChainSyncClient
          .resource(s.channel, ChainSyncFixtures.rawItem, e => s.aborts.update(_ :+ e), settings)
          .use { client =>
            for
              _ <- s.input.offer(Bytes(Vector(0xff.toByte)))
              _ <- client.submit
              first <- typed(client.collect(IO.unit))
              next <- typed(client.submit)
              aborted <- s.aborts.get
            yield first match
              case Left(error @ NetworkError.Decode(Protocols.ChainSync, _)) =>
                assertEquals(next, Left(error))
                assertEquals(aborted, Vector(error))
              case other => fail(s"expected typed decoder failure, got $other")
          }
      }
    }.map(result => assertEquals(result, Right(())))
  }

  test(
    "configured high-water capacity reaches the transport resource, including values above 300"
  ) {
    List(2, 300, 512).traverse_ { maximum =>
      typed {
        script.flatMap { s =>
          val configured = NodeToNode.Settings().pipelining(maximum)
          ChainSyncClient
            .resource(
              s.channel,
              ChainSyncFixtures.rawItem,
              e => s.aborts.update(_ :+ e),
              configured.chainSync,
              maxOutstanding = configured.chainSync.pipelineCapacity
            )
            .use { client =>
              for
                _ <- List.fill(maximum)(()).traverse_(_ => client.submit)
                n <- client.outstanding
                excess <- typed(client.submit)
              yield
                assertEquals(n, maximum)
                assertEquals(
                  excess,
                  Left(
                    NetworkError.IllegalTransition(
                      Protocols.ChainSync,
                      NetworkError.RuntimeViolation.PipelineCapacity(maximum)
                    )
                  )
                )
            }
        }
      }.map(result => assertEquals(result, Right(())))
    }
  }

  test("a defective abort callback still cancels the owned receiver before release completes") {
    val defect = new IllegalStateException("test abort defect")
    typed {
      script.flatMap { s =>
        for
          result <- ChainSyncClient
            .resource(
              s.channel,
              ChainSyncFixtures.rawItem,
              _ => IO.raiseError[Unit](defect),
              settings
            )
            .use { client =>
              client.submit *> s.started.take.void
            }
            .attempt
          stopped <- s.readCancelled.tryGet
        yield
          assertEquals(result, Left(defect))
          assertEquals(stopped, Some(()))
      }
    }.map(result => assertEquals(result, Right(())))
  }

  test("a receiver failure is not visible to callers until its abort callback has completed") {
    typed {
      script.flatMap { s =>
        for
          entered <- Deferred[IO, Unit]
          gate <- Deferred[IO, Unit]
          abort = (e: NetworkError) => entered.complete(()) *> gate.get *> s.aborts.update(_ :+ e)
          _ <- ChainSyncClient
            .resource(s.channel, ChainSyncFixtures.rawItem, abort, settings)
            .use { client =>
              for
                _ <- s.input.offer(Bytes(Vector(0xff.toByte)))
                _ <- client.submit
                collecting <- typed(client.collect(IO.unit)).start
                _ <- (for
                  _ <- entered.get
                  probe <- typed(client.tryCollect(IO.unit))
                  idleDuring <- client.idle
                  _ <- gate.complete(())
                  first <- collecting.joinWithNever
                  aborted <- s.aborts.get
                yield first match
                  case Left(error @ NetworkError.Decode(Protocols.ChainSync, _)) =>
                    assertEquals(probe, Right(None))
                    assert(!idleDuring)
                    assertEquals(aborted, Vector(error))
                  case other => fail(s"expected typed decoder failure, got $other")
                ).guarantee(gate.complete(()).void *> collecting.cancel)
              yield ()
            }
        yield ()
      }
    }.map(result => assertEquals(result, Right(())))
  }

  test("a sender that loses the failure claim raises its own error and the first abort stands") {
    typed {
      script.flatMap { s =>
        for
          entered <- Deferred[IO, Unit]
          gate <- Deferred[IO, Unit]
          failSend <- Ref.of[IO, Boolean](false)
          senderError = NetworkError.BearerClosed(NetworkError.Closure.Aborted)
          abort = (e: NetworkError) => entered.complete(()) *> gate.get *> s.aborts.update(_ :+ e)
          channel = new MuxChannel[IO]:
            val protocol = s.channel.protocol
            def send(bytes: Bytes)(using Raise[IO, NetworkError]): IO[Unit] =
              failSend.get.flatMap(if _ then senderError.raise[IO, Unit] else s.channel.send(bytes))
            def flush: IO[Unit] = s.channel.flush
            def recv[A](maxBytes: Int)(decode: Bytes => DecodeResult[A])(using
                Raise[IO, NetworkError]
            ): IO[A] = s.channel.recv(maxBytes)(decode)
          _ <- ChainSyncClient
            .resource(channel, ChainSyncFixtures.rawItem, abort, settings)
            .use { client =>
              for
                _ <- s.input.offer(Bytes(Vector(0xff.toByte)))
                _ <- client.submit
                collecting <- typed(client.collect(IO.unit)).start
                _ <- (for
                  _ <- entered.get
                  _ <- failSend.set(true)
                  lost <- typed(client.submit)
                  _ <- gate.complete(())
                  first <- collecting.joinWithNever
                  later <- typed(client.submit)
                  aborted <- s.aborts.get
                yield first match
                  case Left(error @ NetworkError.Decode(Protocols.ChainSync, _)) =>
                    assertEquals(lost, Left(senderError))
                    assertEquals(later, Left(error))
                    assertEquals(aborted, Vector(error))
                  case other => fail(s"expected typed decoder failure, got $other")
                ).guarantee(gate.complete(()).void *> collecting.cancel)
              yield ()
            }
        yield ()
      }
    }.map(result => assertEquals(result, Right(())))
  }

  test("a later request's AwaitReply runs while validation waits with an older rollback queued") {
    typed {
      script.flatMap { s =>
        for
          validating <- Deferred[IO, Unit]
          paused <- Deferred[IO, Unit]
          seen <- Ref.of[IO, Vector[Int]](Vector.empty)
          callerAwaits <- Ref.of[IO, Int](0)
          _ <- s.input.offer(encoded(State.NextCanAwait, Message.RollForward(header(1), tip(100))))
          _ <- ChainSyncClient
            .resource(s.channel, ChainSyncFixtures.rawItem, e => s.aborts.update(_ :+ e), settings)
            .use { client =>
              def hook(request: Int) = new ChainSyncClient.ReceiverHook[IO]:
                def awaitReply(using Raise[IO, NetworkError]): IO[Unit] =
                  seen.update(_ :+ request) *> paused.complete(()).void
              cats.effect.Resource
                .make(
                  (validating.get *>
                    s.input.offer(
                      encoded(
                        State.NextCanAwait,
                        Message.RollBackward(ChainSync.Point.Origin, ChainSync.Tip.Origin)
                      )
                    ) *>
                    s.input.offer(encoded(State.NextCanAwait, Message.AwaitReply))).start
                )(_.cancel)
                .use { _ =>
                  for
                    _ <- (1 to 3).toList.traverse_(n => client.submitObserved(hook(n)))
                    first <- client.collect(callerAwaits.update(_ + 1))
                    _ <- validating.complete(())
                    // The sender is suspended here, as it can be in header verification or CSJ.
                    // It performs no collection or event servicing until the receiver signals Await.
                    _ <- paused.get.timeout(1.second)
                    beforeCollect <- seen.get
                    n <- client.outstanding
                    rollback <- client.collect(callerAwaits.update(_ + 1))
                    _ <- s.input.offer(
                      encoded(State.NextMustReply, Message.RollForward(header(2), tip(2)))
                    )
                    last <- client.collect(callerAwaits.update(_ + 1))
                    legacy <- callerAwaits.get
                  yield
                    assertEquals(first, ChainSyncClient.Update.RollForward(header(1), tip(100)))
                    assertEquals(beforeCollect, Vector(3))
                    assertEquals(n, 2)
                    assertEquals(
                      rollback,
                      ChainSyncClient.Update
                        .RollBackward(ChainSync.Point.Origin, ChainSync.Tip.Origin)
                    )
                    assertEquals(last, ChainSyncClient.Update.RollForward(header(2), tip(2)))
                    assertEquals(legacy, 0)
                }
            }
        yield ()
      }
    }.map(result => assertEquals(result, Right(())))
  }

  test("receiver hooks use their worker's error capability and preserve the exact typed failure") {
    val cause = NetworkError.Timeout(Protocols.ChainSync, "receiver-hook")
    val made = typed(
      IO.pure(
        new ChainSyncClient.ReceiverHook[IO]:
          def awaitReply(using Raise[IO, NetworkError]): IO[Unit] = cause.raise[IO, Unit]
      )
    )
    made.flatMap {
      case Left(error) => fail(s"hook construction failed: $error")
      case Right(hook) =>
        typed {
          script.flatMap { s =>
            ChainSyncClient
              .resource(
                s.channel,
                ChainSyncFixtures.rawItem,
                e => s.aborts.update(_ :+ e),
                settings
              )
              .use { client =>
                for
                  _ <- s.input.offer(encoded(State.NextCanAwait, Message.AwaitReply))
                  _ <- client.submitObserved(hook)
                  result <- typed(client.collect(IO.unit))
                  after <- typed(client.submitObserved(hook))
                  aborted <- s.aborts.get
                yield
                  assertEquals(result, Left(cause))
                  assertEquals(after, Left(cause))
                  assertEquals(aborted, Vector(cause))
              }
          }
        }.map(result => assertEquals(result, Right(())))
    }
  }

  test("an observed receiver hook completes before the MustReply deadline starts") {
    TestControl
      .executeEmbed(typed {
        script.flatMap { s =>
          s.input.offer(encoded(State.NextCanAwait, Message.AwaitReply)) *>
            s.input.offer(encoded(State.NextMustReply, Message.RollForward(header(1), tip(1)))) *>
            ChainSyncClient
              .resource(
                s.channel,
                ChainSyncFixtures.rawItem,
                e => s.aborts.update(_ :+ e),
                settings
              )
              .use { client =>
                val hook = new ChainSyncClient.ReceiverHook[IO]:
                  def awaitReply(using Raise[IO, NetworkError]): IO[Unit] = IO.sleep(2.seconds)
                client.submitObserved(hook) *> client.collect(IO.unit).map { update =>
                  assertEquals(update, ChainSyncClient.Update.RollForward(header(1), tip(1)))
                }
              }
        }
      })
      .map(result => assertEquals(result, Right(())))
  }
