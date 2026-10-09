// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path, StandardCopyOption}
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class CompletionFenceSuite extends munit.FunSuite:
  private val id = "a" * 64
  private val context = "b" * 64
  private def settings(p: Path) = CompletionFence.Settings(p, id, "A", 9)
  private def raw(depth: String = "9", extra: String = "") =
    s"version=live-completion-fence-v1\nfenceId=$id\nphase=A\ncontextId=$context\ndepth=$depth\nblockNo=30\nslot=90\nhash=${"c" * 64}\n$extra"
      .getBytes(UTF_8)
  private def temp =
    Resource.make(IO.blocking(Files.createTempDirectory("completion-fence-").toRealPath())) { p =>
      IO.blocking {
        require(p.getFileName.toString.startsWith("completion-fence-"))
        val paths = Files.walk(p)
        try
          paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
        finally paths.close()
      }
    }
  private def publish(path: Path, bytes: Array[Byte]): IO[Unit] = IO.blocking {
    val staged = path.resolveSibling("staged")
    Files.write(staged, bytes)
    Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE)
    ()
  }
  test("canonical fence rejects range, duplicate, unknown, stale process and malformed fields") {
    val s = settings(Path.of("/tmp/completion-fence"))
    assertEquals(CompletionFence.parse(raw(), s, context, 12).depth, 9)
    Vector(
      raw("8"),
      raw("13"),
      raw("09"),
      raw(extra = "hash=x\n"),
      raw().dropRight(1),
      new String(raw(), UTF_8).replace("phase=A", "phase=B").getBytes(UTF_8),
      Array.fill[Byte](2049)(0)
    ).foreach { bytes =>
      intercept[IllegalArgumentException](CompletionFence.parse(bytes, s, context, 12))
    }
    intercept[IllegalArgumentException](
      CompletionFence.parse(raw(), s.copy(id = "d" * 64), context, 12)
    )
  }
  test("real atomic IPC accepts one arrival; identical replacement is rejected") {
    temp
      .use { dir =>
        val path = dir.resolve("fence")
        for
          c <- CompletionFence.create[IO](settings(path), context, 12)
          waiting <- c.await.start
          _ <- publish(path, raw())
          first <- waiting.joinWithNever.timeout(2.seconds)
          again <- c.read
          _ = assertEquals(again, Some(first))
          _ <- publish(path, raw())
          changed <- c.verify(first).attempt
        yield assert(changed.isLeft)
      }
      .unsafeToFuture()
  }
  test("watch cancellation terminates while absent and does not create artifact") {
    temp
      .use { dir =>
        val path = dir.resolve("fence")
        for
          c <- CompletionFence.create[IO](settings(path), context, 12)
          waiting <- c.await.start
          _ <- IO.cede *> waiting.cancel.timeout(1.second)
          result <- waiting.join
        yield
          assert(result.isCanceled)
          assert(!Files.exists(path))
      }
      .unsafeToFuture()
  }
  test("preexisting file, symlink, removal and rewritten bytes reject") {
    temp
      .use { dir =>
        val path = dir.resolve("fence")
        for
          c <- CompletionFence.create[IO](settings(path), context, 12)
          _ <- publish(path, raw())
          first <- c.await
          preexisting <- CompletionFence.create[IO](settings(path), context, 12).attempt
          _ = assert(preexisting.isLeft)
          _ <- IO.blocking(Files.write(path, raw("10")))
          rewritten <- c.verify(first).attempt
          _ = assert(rewritten.isLeft)
          _ <- IO.blocking(Files.delete(path))
          removed <- c.verify(first).attempt
          _ = assert(removed.isLeft)
          symlink = dir.resolve("link")
          other <- CompletionFence.create[IO](settings(symlink), context, 12)
          _ <- IO.blocking(Files.write(path, raw()))
          _ <- IO.blocking(Files.createSymbolicLink(symlink, path))
          linked <- other.read.attempt
        yield assert(linked.isLeft)
      }
      .unsafeToFuture()
  }
