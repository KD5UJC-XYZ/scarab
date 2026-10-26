// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.{BinaryError, DomainError}
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.models.runtime.DecodeError

class ReconstructionSuite extends CatsEffectSuite:
  private enum Problem extends DomainError:
    case MissingRuntime

  private val failure = Problem.MissingRuntime
  private val cause = new IllegalStateException("original decoder failure")

  test("binary composition preserves a typed reconstruction failure") {
    given Codec[Problem] =
      Codec.make[Problem]("Problem")((_, _) => ())(_ => Left(BinaryError.Reconstruction(failure)))
    val result = Codec.decode[Vector[Problem]](Array(1.toByte, 0.toByte))
    assertEquals(result, Left(BinaryError.Reconstruction(failure)))
    assertEquals(
      DecodeFailure.binary("sidecar", BinaryError.Reconstruction(failure)),
      StorageError.Reconstruction("sidecar", failure)
    )
  }

  test("unexpected binary decoder exception escapes to its existing effect owner") {
    given Codec[Problem] = Codec.make[Problem]("Problem")((_, _) => ())(_ => throw cause)
    SnapshotFailure
      .capture[IO, Either[BinaryError, Vector[Problem]]] {
        IO(Codec.decode[Vector[Problem]](Array(1.toByte, 0.toByte)))
      }
      .map { result =>
        assertEquals(result, Left(SnapshotFailure.Defect(cause)))
      }
  }

  test("explicit foreign binary construction cause keeps its original identity") {
    given Codec[Problem] =
      Codec.make[Problem]("Problem")((_, _) => ())(_ => Left(BinaryError.Internal(cause)))
    Codec.decode[Vector[Problem]](Array(1.toByte, 0.toByte)) match
      case Left(error @ BinaryError.Internal(actual)) =>
        assert(actual eq cause)
        assertEquals(
          DecodeFailure.binary("sidecar", error),
          StorageError.DecoderDefect("sidecar", cause)
        )
      case other => fail(s"expected explicit foreign construction failure: $other")
  }

  test("nested native Internal remains distinct from malformed input") {
    val original = DecodeError(DecodeError.Kind.Internal(cause), List("model"), 7)
    val nested = DecodeError(DecodeError.Kind.NoAlternative(Vector(original)), List("state"), 0)
    assertEquals(DecodeFailure.native("state", nested), StorageError.DecoderDefect("state", cause))
    val malformed = DecodeError(DecodeError.Kind.MissingKey("field"), Nil, 0)
    assertEquals(DecodeFailure.native("state", malformed), StorageError.Corrupt("state", malformed))
  }

  private def snapshot(i: Int): StateSnapshot[Int] =
    StateSnapshot(Some(StorageTestKit.block(i)._1), i, CheckpointBinding.Unbacked)

  private val errors = Vector(
    StorageError.DecoderDefect("state", cause),
    StorageError.Reconstruction("state", failure)
  )

  errors.zipWithIndex.foreach { (error, index) =>
    test(s"restore stops before an older snapshot after reconstruction failure $index") {
      StorageTestKit.tempDir.use { dir =>
        Ref.of[IO, Vector[Int]](Vector.empty).flatMap { reads =>
          given SnapshotFormat[IO, Int] = new SnapshotFormat[IO, Int]:
            def encode(value: StateSnapshot[Int]) = IO.pure(Right(Array(value.state.toByte)))
            def decode(bytes: Array[Byte]) =
              val value = bytes(0).toInt
              reads
                .update(_ :+ value)
                .as(if value == 2 then Left(error) else Right(snapshot(value)))
          for
            result <- StorageTestKit.attempt {
              for
                store <- FileLedgerSnapshots.open[IO, Int](dir, 3)
                _ <- store.write(snapshot(1))
                _ <- store.write(snapshot(2))
                value <- store.restore(_ => IO.pure(Right(())))
              yield value
            }
            observed <- reads.get
          yield
            assertEquals(result, Left(error))
            assertEquals(observed, Vector(2))
        }
      }
    }

    test(s"terminal usability failure $index cannot select an older snapshot") {
      StorageTestKit.tempDir.use { dir =>
        given SnapshotFormat[IO, Int] = new SnapshotFormat[IO, Int]:
          def encode(value: StateSnapshot[Int]) = IO.pure(Right(Array(value.state.toByte)))
          def decode(bytes: Array[Byte]) = IO.pure(Right(snapshot(bytes(0).toInt)))
        for
          checked <- Ref.of[IO, Vector[Int]](Vector.empty)
          result <- StorageTestKit.attempt {
            for
              store <- FileLedgerSnapshots.open[IO, Int](dir, 3)
              _ <- store.write(snapshot(1))
              _ <- store.write(snapshot(2))
              value <- store.restore(s => checked.update(_ :+ s.state).as(Left(error)))
            yield value
          }
          observed <- checked.get
        yield
          assertEquals(result, Left(error))
          assertEquals(observed, Vector(2))
      }
    }

    test(s"write read-back failure $index is raised instead of an ignorable refusal") {
      StorageTestKit.tempDir.use { dir =>
        given SnapshotFormat[IO, Int] = new SnapshotFormat[IO, Int]:
          def encode(value: StateSnapshot[Int]) = IO.pure(Left(SnapshotRefusal.WouldNotRead(error)))
          def decode(bytes: Array[Byte]) = IO.pure(Right(snapshot(1)))
        for result <- StorageTestKit.attempt {
            FileLedgerSnapshots.open[IO, Int](dir, 3).flatMap(_.write(snapshot(1)))
          }
        yield assertEquals(result, Left(error))
      }
    }
  }
