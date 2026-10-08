// SPDX-License-Identifier: Apache-2.0
package lab.ledger.runtime

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes

/** Forked only by the process-interruption suite. halt intentionally bypasses all JVM finalizers.
  */
object ReplayInterruptionProbe:
  def main(args: Array[String]): Unit =
    val runtime = Runtime.getRuntime
    require(runtime.availableProcessors() == 2, "child processor profile differs from APC2")
    require(runtime.maxMemory() <= 256L * 1048576, "child heap exceeds 256 MiB")
    println(
      s"probe-processors=${runtime.availableProcessors()} probe-max-memory=${runtime.maxMemory()}"
    )
    System.out.flush()
    val root = Path.of(args(0))
    val phase = NioReplayStore.Phase.valueOf(args(1))
    val undo = args(2) == "undo"
    val tx = Bytes.fromArray(Files.readAllBytes(Path.of(args(3))))
    if args(2) == "flood" then
      val chunk = "x" * 4096
      (0 until 256).foreach(_ => System.out.print(chunk))
      System.out.flush()
      Runtime.getRuntime.halt(93)
    else if args(2) == "lock" then
      NioReplayStore.open[IO](root).use(_.snapshot).attempt.unsafeRunSync() match
        case Left(error: ReplayStore.StorageException)
            if error.failure == ReplayStore.StorageFailure.Locked =>
          Runtime.getRuntime.halt(92)
        case other =>
          throw new IllegalStateException(s"independent process lock was not enforced: $other")
    else
      NioReplayStore
        .open[IO](root, fault = p => if p == phase then Runtime.getRuntime.halt(91))
        .use { store =>
          store.snapshot.flatMap { snapshot =>
            if undo then store.rollback(snapshot.version, snapshot.undo.get.transitionId).void
            else store.commit(snapshot.version, Vector(tx)).void
          }
        }
        .unsafeRunSync()
      throw new IllegalStateException("interruption hook was not reached")
