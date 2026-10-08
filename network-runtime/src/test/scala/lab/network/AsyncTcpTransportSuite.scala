// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.syntax.all.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousCloseException, CompletionHandler}
import lab.cbor.Bytes
import scala.collection.mutable
import scala.concurrent.duration.*

/** No real socket or global runtime in these callback tests. */
class AsyncTcpTransportSuite extends munit.FunSuite:
  private val runtime = IORuntime.builder().build()
  override def afterAll(): Unit = runtime.shutdown()
  private def run[A](io: IO[A]) = io.timeout(5.seconds).unsafeToFuture()(runtime)
  private val limits = TcpLimits.checked().toOption.get
  private val peer = NumericPeer.checked("127.0.0.1", 3001).toOption.get
  private val bytes = Bytes.fromHex("0001020304050607").toOption.get
  private def until(condition: => Boolean): IO[Unit] =
    IO.defer(if condition then IO.unit else IO.cede *> until(condition))

  private def untilEffect(condition: IO[Boolean]): IO[Unit] =
    condition.flatMap(ok => if ok then IO.unit else IO.cede *> untilEffect(condition))

  private class Fake extends AsyncTcpTransport.Socket:
    @volatile var open = true
    @volatile var closes = 0
    @volatile var reads = 0
    @volatile var writes = 0
    @volatile var connects = 0
    var output = Vector.empty[Byte]
    val writeCounts = mutable.Queue.empty[Int]
    val readSteps = mutable.Queue.empty[Option[Vector[Byte]]]
    var stallWrites = false
    var zeroWrites = false
    var failRegistration = false
    var immediateConnect = true
    var pendingRead: CompletionHandler[Integer, Unit] = null
    var pendingWrite: CompletionHandler[Integer, Unit] = null
    var pendingConnect: CompletionHandler[Void, Unit] = null
    def isOpen: Boolean = open
    def close(): Unit = synchronized {
      closes += 1
      open = false
      if pendingRead != null then pendingRead.failed(new AsynchronousCloseException(), ())
      if pendingWrite != null then pendingWrite.failed(new AsynchronousCloseException(), ())
      if pendingConnect != null then pendingConnect.failed(new AsynchronousCloseException(), ())
    }
    def connect(address: InetSocketAddress, h: CompletionHandler[Void, Unit]): Unit = synchronized {
      connects += 1
      pendingConnect = h
      if failRegistration then throw new IllegalStateException("register failed")
      if immediateConnect then h.completed(null, ())
    }
    def read(buffer: ByteBuffer, h: CompletionHandler[Integer, Unit]): Unit = synchronized {
      reads += 1
      if failRegistration then throw new IllegalStateException("register failed")
      if readSteps.isEmpty then pendingRead = h
      else
        readSteps.dequeue() match
          case None => h.completed(Integer.valueOf(-1), ())
          case Some(chunk) =>
            buffer.put(chunk.toArray)
            h.completed(Integer.valueOf(chunk.size), ())
    }
    def write(buffer: ByteBuffer, h: CompletionHandler[Integer, Unit]): Unit = synchronized {
      writes += 1
      if failRegistration then throw new IllegalStateException("register failed")
      if stallWrites then pendingWrite = h
      else
        val count =
          if zeroWrites then 0
          else if writeCounts.nonEmpty then writeCounts.dequeue()
          else buffer.remaining()
        if count > 0 then
          val chunk = new Array[Byte](count)
          buffer.get(chunk)
          output = output ++ chunk.toVector
        h.completed(Integer.valueOf(count), ())
    }

  test("partial and zero writes preserve each byte exactly once") {
    val socket = new Fake
    socket.writeCounts.enqueue(0, 2, 0, 1, 5)
    run(AsyncTcpTransport.endpoint[IO](socket, limits).flatMap { t =>
      t.write(bytes) *> IO {
        assertEquals(socket.output, bytes.value)
        assertEquals(socket.writes, 5)
        assertEquals(socket.closes, 0)
      } *> t.close *> t.close *> IO(assertEquals(socket.closes, 1))
    })
  }
  test("zero reads yield, positive reads are nonempty, EOF closes") {
    val socket = new Fake
    socket.readSteps.enqueue(Some(Vector.empty), Some(bytes.value), None)
    run(AsyncTcpTransport.endpoint[IO](socket, limits).flatMap { t =>
      for
        first <- t.read
        eof <- t.read
        again <- t.read
        closed <- t.isClosed
      yield
        assertEquals(first, Some(bytes))
        assertEquals(eof, None)
        assertEquals(again, None)
        assert(closed)
        assertEquals(socket.closes, 1)
        assertEquals(socket.reads, 3)
    })
  }
  test("synchronous registration failure and negative write close admitted owner") {
    List(true, false).traverse_ { throwing =>
      val socket = new Fake
      socket.failRegistration = throwing
      socket.writeCounts.enqueue(-1)
      AsyncTcpTransport.endpoint[IO](socket, limits).flatMap { t =>
        t.write(bytes)
          .attempt
          .flatMap(result =>
            IO {
              assert(result.isLeft)
              assertEquals(socket.closes, 1)
            }
          )
      }
    } match
      case io => run(io)
  }
  test("canceling a read permit waiter does not poison current read owner") {
    val socket = new Fake
    run(AsyncTcpTransport.endpoint[IO](socket, limits).flatMap { t =>
      for
        owner <- t.read.attempt.start
        _ <- until(socket.reads == 1)
        waiter <- t.read.attempt.start
        _ <- untilEffect(t.asInstanceOf[AsyncTcpTransport.Probe[IO]].readPermits.map(_ < 0))
        _ <- waiter.cancel
        _ <- IO { assertEquals(socket.closes, 0); assertEquals(socket.reads, 1) }
        _ <- owner.cancel
        _ <- IO(assertEquals(socket.closes, 1))
      yield ()
    })
  }
  test("canceling a write permit waiter does not poison current write owner") {
    val socket = new Fake
    socket.stallWrites = true
    run(AsyncTcpTransport.endpoint[IO](socket, limits).flatMap { t =>
      for
        owner <- t.write(bytes).attempt.start
        _ <- until(socket.writes == 1)
        waiter <- t.write(bytes).attempt.start
        _ <- untilEffect(t.asInstanceOf[AsyncTcpTransport.Probe[IO]].writePermits.map(_ < 0))
        _ <- waiter.cancel
        _ <- IO { assertEquals(socket.closes, 0); assertEquals(socket.writes, 1) }
        _ <- owner.cancel
        _ <- IO(assertEquals(socket.closes, 1))
      yield ()
    })
  }
  test("close is independent of busy read and write permits") {
    val socket = new Fake
    socket.stallWrites = true
    run(AsyncTcpTransport.endpoint[IO](socket, limits).flatMap { t =>
      for
        read <- t.read.attempt.start
        write <- t.write(bytes).attempt.start
        _ <- until(socket.reads == 1 && socket.writes == 1)
        _ <- t.close
        _ <- read.join
        _ <- write.join
        _ <- t.close
        _ <- IO(assertEquals(socket.closes, 1))
      yield ()
    })
  }
  test("connect timeout closes acquired socket exactly once") {
    val socket = new Fake
    socket.immediateConnect = false
    val short = TcpLimits.checked(connect = 50.millis).toOption.get
    run(AsyncTcpTransport.connected[IO](socket, peer, short).use(_ => IO.unit).attempt.map { r =>
      assert(r.isLeft)
      assertEquals(socket.connects, 1)
      assertEquals(socket.closes, 1)
    })
  }
  test("late successful connect callback after cancellation cannot resurrect ownership") {
    val socket = new Fake
    socket.immediateConnect = false
    run(for
      fiber <- AsyncTcpTransport.connected[IO](socket, peer, limits).use(_ => IO.never).start
      _ <- until(socket.connects == 1)
      _ <- fiber.cancel
      _ <- IO(socket.pendingConnect.completed(null, ()))
      _ <- IO { assert(!socket.open); assertEquals(socket.closes, 1) }
    yield ())
  }
  test("read timeout poisons admitted transport; invalid write before admission does not") {
    val socket = new Fake
    val short = TcpLimits.checked(read = 50.millis).toOption.get
    run(AsyncTcpTransport.endpoint[IO](socket, short).flatMap { t =>
      for
        invalid <- t.write(Bytes.empty).attempt
        _ <- IO { assert(invalid.isLeft); assertEquals(socket.closes, 0) }
        result <- t.read.attempt
        _ <- IO { assert(result.isLeft); assertEquals(socket.closes, 1) }
      yield ()
    })
  }

  test("zero-write progress does not restart the whole-operation deadline") {
    val socket = new Fake
    socket.zeroWrites = true
    val short = TcpLimits.checked(write = 50.millis).toOption.get
    run(AsyncTcpTransport.endpoint[IO](socket, short).flatMap { t =>
      t.write(bytes).attempt.map { result =>
        assert(result.isLeft)
        assert(socket.writes > 1)
        assertEquals(socket.output, Vector.empty)
        assertEquals(socket.closes, 1)
      }
    })
  }
  test("synchronous connect registration failure closes resource") {
    val socket = new Fake
    socket.failRegistration = true
    run(AsyncTcpTransport.connected[IO](socket, peer, limits).use(_ => IO.unit).attempt.map { r =>
      assert(r.isLeft)
      assertEquals(socket.closes, 1)
    })
  }
  test("owned channel group and executor are terminated after release") {
    run(AsyncTcpTransport.group[IO](limits).allocated.flatMap { case (owned, release) =>
      release *> IO {
        assert(owned.group.isTerminated)
        assert(owned.executor.isTerminated)
      }
    })
  }
