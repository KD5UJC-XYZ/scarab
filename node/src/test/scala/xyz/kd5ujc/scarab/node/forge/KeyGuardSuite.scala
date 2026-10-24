// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{IO, Ref}
import cats.mtl.Handle
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.ledger.{ForgeError, ForgeKeyProblem}

/** The permission check mirrors `checkVRFFilePermissions` of cardano-node 11.1.3: any "other"
  * access refuses, any "group" access warns, owner-only passes. The lock is ours.
  */
class KeyGuardSuite extends CatsEffectSuite:
  private def file(dir: Path, name: String, mode: String): Path =
    val p = Files.createFile(dir.resolve(name))
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode))
    p

  private val dir = FunFixture[Path](
    _ => Files.createTempDirectory("key-guard"),
    d =>
      Files.list(d).forEach(Files.delete(_))
      Files.delete(d)
  )

  dir.test("0600 and 0400 pass without a warning") { d =>
    assertEquals(
      KeyFilePermissions.check(file(d, "a", "rw-------")),
      Right(KeyFileAccess.OwnerOnly)
    )
    assertEquals(
      KeyFilePermissions.check(file(d, "b", "r--------")),
      Right(KeyFileAccess.OwnerOnly)
    )
  }

  dir.test("0640 and 0660 warn, 0610 too (any group bit)") { d =>
    assertEquals(
      KeyFilePermissions.check(file(d, "a", "rw-r-----")),
      Right(KeyFileAccess.GroupAccess(0x1a0))
    )
    assertEquals(
      KeyFilePermissions.check(file(d, "b", "rw-rw----")),
      Right(KeyFileAccess.GroupAccess(0x1b0))
    )
    assertEquals(
      KeyFilePermissions.check(file(d, "c", "rw---x---")),
      Right(KeyFileAccess.GroupAccess(0x188))
    )
  }

  dir.test("every other bit refuses, group bits or not") { d =>
    List("rw-r--r--" -> 0x1a4, "rw----r--" -> 0x184, "rw------x" -> 0x181).zipWithIndex
      .foreach { case ((m, mode), i) =>
        val p = file(d, s"f$i", m.take(9))
        assertEquals(
          KeyFilePermissions.check(p),
          Left(ForgeError.KeyFile(p.toString, ForgeKeyProblem.OtherPermissions(mode)))
        )
      }
  }

  dir.test("a missing file is a typed Io error") { d =>
    assert(
      KeyFilePermissions.check(d.resolve("none")).left.exists {
        case ForgeError.KeyFile(_, ForgeKeyProblem.Io(_)) => true
        case _                                            => false
      }
    )
  }

  private def guarded[A](
      vrf: Path,
      kes: Path,
      lock: Boolean,
      seen: Ref[IO, Vector[ForgeEvent]]
  )(body: IO[A]): IO[Either[ForgeError, A]] =
    Handle
      .allow[ForgeError] {
        KeyGuard
          .resource[IO](
            vrf,
            kes,
            lock,
            new ForgeEvents[IO]:
              def emit(event: ForgeEvent): IO[Unit] = seen.update(_ :+ event)
          )
          .use(_ => body)
          .map(Right(_))
      }
      .rescue(e => IO.pure(Left(e)))

  dir.test("group access is reported as an event and does not stop the start") { d =>
    val vrf = file(d, "vrf", "rw-r-----")
    val kes = file(d, "kes", "rw-------")
    Ref.of[IO, Vector[ForgeEvent]](Vector.empty).flatMap { seen =>
      guarded(vrf, kes, lock = true, seen)(IO.unit).flatMap { r =>
        assertEquals(r, Right(()))
        seen.get
          .map(e => assertEquals(e, Vector(ForgeEvent.KeyFileGroupAccess(vrf.toString, 0x1a0))))
      }
    }
  }

  dir.test("other access refuses before anything is locked") { d =>
    val vrf = file(d, "vrf", "rw-r--r--")
    val kes = file(d, "kes", "rw-------")
    Ref.of[IO, Vector[ForgeEvent]](Vector.empty).flatMap { seen =>
      guarded(vrf, kes, lock = true, seen)(IO.unit).map { r =>
        assertEquals(
          r,
          Left(ForgeError.KeyFile(vrf.toString, ForgeKeyProblem.OtherPermissions(0x1a4)))
        )
        assert(!Files.exists(KeyGuard.lockFile(kes)))
      }
    }
  }

  dir.test("a second acquire is refused while the first is held, and works after release") { d =>
    val vrf = file(d, "vrf", "rw-------")
    val kes = file(d, "kes", "rw-------")
    Ref.of[IO, Vector[ForgeEvent]](Vector.empty).flatMap { seen =>
      val refused = Left(
        ForgeError.KeyFile(
          kes.toString,
          ForgeKeyProblem.InstanceLocked(KeyGuard.lockFile(kes).toString)
        )
      )
      for
        outer <- guarded(vrf, kes, lock = true, seen)(
          guarded(vrf, kes, lock = true, seen)(IO.unit)
        )
        _ = assertEquals(outer, Right(refused))
        again <- guarded(vrf, kes, lock = true, seen)(IO.unit)
        _ = assertEquals(again, Right(()))
      yield ()
    }
  }

  dir.test("with the lock off a second instance starts") { d =>
    val vrf = file(d, "vrf", "rw-------")
    val kes = file(d, "kes", "rw-------")
    Ref.of[IO, Vector[ForgeEvent]](Vector.empty).flatMap { seen =>
      guarded(vrf, kes, lock = true, seen)(guarded(vrf, kes, lock = false, seen)(IO.unit))
        .map(r => assertEquals(r, Right(Right(()))))
    }
  }
