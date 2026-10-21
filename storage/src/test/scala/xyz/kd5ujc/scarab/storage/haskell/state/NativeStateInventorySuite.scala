// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.effect.IO
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.storage.StorageTestKit

class NativeStateInventorySuite extends CatsEffectSuite:
  private val source = Path.of("fixtures/haskell-state")
  private def copied(change: Path => Unit): IO[Either[NativeStateFixtures.Error, Vector[Path]]] =
    StorageTestKit.tempDir.use { temp =>
      IO.blocking {
        val root = temp.toNioPath
        NativeStateFixtures.hashes.keys.foreach { name =>
          val target = root.resolve(name)
          Files.createDirectories(target.getParent)
          Files.copy(source.resolve(name), target)
        }
        change(root)
        NativeStateFixtures.verify(root)
      }
    }

  test("all seven original native snapshots and every companion file are pinned") {
    IO.blocking {
      assertEquals(NativeStateFixtures.names.size, 7)
      assertEquals(NativeStateFixtures.hashes.size, 21)
      assertEquals(
        NativeStateFixtures.verify(source),
        Right(NativeStateFixtures.names.map(source.resolve))
      )
    }
  }

  test("missing state, metadata or table cannot silently remove fixture cases") {
    List("state", "meta", "tables").foldLeft(IO.unit) { (previous, file) =>
      previous *> copied(root => { Files.delete(root.resolve(s"conway-4593/$file")); () }).map {
        result =>
          assert(
            result.left.exists(_.isInstanceOf[NativeStateFixtures.Error.Inventory]),
            result.toString
          )
      }
    }
  }

  test("tampered committed input fails the independent byte pin") {
    copied(root => { Files.write(root.resolve("conway-4593/state"), Array[Byte](0)); () }).map {
      result =>
        assert(
          result.left.exists(_.isInstanceOf[NativeStateFixtures.Error.Digest]),
          result.toString
        )
    }
  }

  test("unexpected data does not silently expand the approved corpus") {
    copied(root => { Files.write(root.resolve("unreviewed"), Array[Byte](0)); () }).map { result =>
      assert(
        result.left.exists(_.isInstanceOf[NativeStateFixtures.Error.Inventory]),
        result.toString
      )
    }
  }

  test("an unreviewed empty era directory also fails the exact inventory") {
    copied(root => { Files.createDirectory(root.resolve("unknown-1")); () }).map { result =>
      assert(
        result.left.exists(_.isInstanceOf[NativeStateFixtures.Error.Inventory]),
        result.toString
      )
    }
  }
