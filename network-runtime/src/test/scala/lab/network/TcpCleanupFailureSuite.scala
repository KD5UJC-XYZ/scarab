// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.CompletionHandler
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

class TcpCleanupFailureSuite extends munit.FunSuite:
  private val reported = new ConcurrentLinkedQueue[Throwable]()
  private val runtime = IORuntime.builder().setFailureReporter(e => { reported.add(e); () }).build()
  override def afterAll(): Unit = runtime.shutdown()
  private val peer = NumericPeer.checked("127.0.0.1", 3001).toOption.get
  private val limits = TcpLimits.checked().toOption.get
  private final class FailingClose(cleanup: Throwable) extends AsyncTcpTransport.Socket:
    @volatile var open = true
    @volatile var closes = 0
    def isOpen: Boolean = open
    def connect(address: InetSocketAddress, h: CompletionHandler[Void, Unit]): Unit =
      h.completed(null, ())
    def read(buffer: ByteBuffer, h: CompletionHandler[Integer, Unit]): Unit =
      throw new AssertionError("unexpected read")
    def write(buffer: ByteBuffer, h: CompletionHandler[Integer, Unit]): Unit =
      throw new AssertionError("unexpected write")
    def close(): Unit =
      closes += 1
      open = false
      throw cleanup

  test("primary use error survives TCP resource cleanup and secondary failure is reported") {
    val primary = new IllegalArgumentException("primary-protocol-failure")
    val secondary = new IllegalStateException("secondary-close-failure")
    val socket = new FailingClose(secondary)
    AsyncTcpTransport
      .connected[IO](socket, peer, limits)
      .use(_ => IO.raiseError[Unit](primary))
      .attempt
      .map { result =>
        assert(result.left.toOption.exists(_ eq primary), result.toString)
        assert(
          reported.contains(secondary),
          "secondary cleanup failure must reach runtime reporter"
        )
        assertEquals(socket.closes, 1)
        assert(!socket.isOpen)
      }
      .timeout(5.seconds)
      .unsafeToFuture()(runtime)
  }

  test("cleanup failure after successful use is not reported as success") {
    val secondary = new IllegalStateException("close-failure-after-success")
    val socket = new FailingClose(secondary)
    AsyncTcpTransport
      .connected[IO](socket, peer, limits)
      .use(_ => IO.unit)
      .attempt
      .map { result =>
        assert(result.left.toOption.exists(_ eq secondary), result.toString)
        assertEquals(socket.closes, 1)
        assert(!socket.isOpen)
      }
      .timeout(5.seconds)
      .unsafeToFuture()(runtime)
  }
