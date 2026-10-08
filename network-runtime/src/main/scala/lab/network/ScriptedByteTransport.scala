// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import lab.cbor.Bytes

/** Finite independently supplied byte script. No wire encoders, sockets, endpoints or discovery.
  * Every read/write is matched in order. Script admission is bounded before creating transport
  * state.
  */
object ScriptedByteTransport:
  enum Step:
    case Receive(bytes: Bytes)
    case Expect(bytes: Bytes)
  final case class Limits(
      maxSteps: Int = 16384,
      maxBytes: Long = 6L * 1024 * 1024,
      maxChunkBytes: Int = 65543
  )
  private final case class State(index: Int, closed: Boolean)
  def resource[F[_]: Async](
      script: Vector[Step],
      limits: Limits = Limits()
  ): Resource[F, ByteTransport[F]] =
    val F = Async[F]
    val admission = F.delay {
      require(
        limits.maxSteps > 0 && limits.maxSteps <= 65536 && limits.maxBytes > 0 &&
          limits.maxBytes <= 6L * 1024 * 1024 && limits.maxChunkBytes > 0 &&
          limits.maxChunkBytes <= 65543,
        "invalid script limits"
      )
      require(script.size <= limits.maxSteps, "script step limit exceeded")
      script.foldLeft(0L) { (used, step) =>
        val bytes = step match
          case Step.Receive(b) => b
          case Step.Expect(b)  => b
        require(bytes.size > 0 && bytes.size <= limits.maxChunkBytes, "invalid script chunk")
        require(bytes.size.toLong <= limits.maxBytes - used, "script byte limit exceeded")
        used + bytes.size
      }
    }
    Resource.eval(admission *> Ref.of[F, State](State(0, false))).flatMap { state =>
      val transport = new ByteTransport[F]:
        def close: F[Unit] = state.update(_.copy(closed = true))
        def isClosed: F[Boolean] = state.get.map(_.closed)
        def read: F[Option[Bytes]] = state
          .modify { s =>
            if s.closed || s.index == script.size then (s, Right(None))
            else
              script(s.index) match
                case Step.Receive(bytes) => (s.copy(index = s.index + 1), Right(Some(bytes)))
                case _ => (s, Left(new IllegalStateException("script expected write before read")))
          }
          .flatMap(F.fromEither)
        def write(bytes: Bytes): F[Unit] = state
          .modify { s =>
            if s.closed then (s, Left(new IllegalStateException("script transport closed")))
            else if s.index >= script.size then
              (s, Left(new IllegalStateException("unexpected write after script")))
            else
              script(s.index) match
                case Step.Expect(expected) if expected == bytes =>
                  (s.copy(index = s.index + 1), Right(()))
                case _ => (s, Left(new IllegalStateException("script write mismatch")))
          }
          .flatMap(F.fromEither)
      Resource.make(F.pure(transport))(_.close)
    }
