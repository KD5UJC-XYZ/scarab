// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.io.IOException
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.ledger.{
  Block,
  ForgeError,
  Header,
  KesFileFailureCause,
  KesFileOperation,
  KesStageMismatch,
  OCertError
}
import xyz.kd5ujc.scarab.ledger.TestLedger
import xyz.kd5ujc.scarab.ledger.TestLedger.Key

/** KES key persistence, erasure and rotation (H2a, #116): the evolved key is written back and its
  * predecessor overwritten; a crash at any step leaves a usable key; SIGHUP's reload switches only
  * to credentials the chain would accept.
  */
class KesRotationSuite extends CatsEffectSuite with ForgeFixture:
  // ---- fixtures

  private def be8(n: Long) = Array.tabulate[Byte](8)(i => (n >> (8 * (7 - i))).toByte)

  /** Credentials for `cold`'s pool: the KES key of `seed` at period 0 and a certificate for it. */
  private def hotFor(cold: Key, seed: Int, start: Int, sequence: Int): HotCredentials =
    val kes = Sum6KesSigning.generate(Bytes.fromArray(Array.fill(32)(seed.toByte))).toOption.get
    val signable =
      Bytes.fromArray(kes.verificationKey.toArray ++ be8(sequence) ++ be8(start))
    HotCredentials(
      kes,
      Header.OpCert(kes.verificationKey, sequence, start, cold.sign(signable)),
      cold.vkey
    )

  private def head(major: Int, n: Int): String =
    if n < 24 then f"${(major << 5) + n}%02x"
    else if n < 256 then f"${(major << 5) + 24}%02x$n%02x"
    else f"${(major << 5) + 25}%02x$n%04x"

  private def bstr(b: Bytes): String = head(2, b.size) + b.hex

  private def opCertText(h: HotCredentials): String =
    val oc = h.opCert
    val cbor = "82" + "84" + bstr(oc.hotVkey) + head(0, oc.sequence.toInt) +
      head(0, oc.kesPeriod.toInt) + bstr(oc.sigma) + bstr(h.coldVkey)
    s"""{"type": "${KeyFiles.OpCertType}", "description": "", "cborHex": "$cbor"}"""

  private final case class Files3(dir: Path):
    val kes: Path = dir.resolve("kes.skey")
    val cert: Path = dir.resolve("node.cert")
    def next: Path = dir.resolve("kes.skey.next")

    /** What an operator (or the rotation driver) leaves in the key directory. */
    def install(h: HotCredentials): Unit =
      Files.writeString(kes, KeyFiles.kesEnvelope(kes.toString, h.kes).toOption.get)
      Files.writeString(cert, opCertText(h))

    /** The key as the file holds it, without any recovery. */
    def onDisk: Sum6KesSigning.SigningKey =
      KeyFiles.kesSigningKey(kes.toString, Files.readString(kes)).toOption.get

  private def withDir[A](body: Files3 => IO[A]): IO[A] =
    IO.blocking(Files3(Files.createTempDirectory("kes-rotation"))).flatMap { files =>
      body(files).guarantee(IO.blocking(removeTree(files.dir)))
    }

  private def removeTree(dir: Path): Unit =
    if Files.exists(dir) then
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))

  private def source(files: Files3): IO[HotKeySource[IO]] =
    HotKeySource.files[IO](files.kes, files.cert)

  private def evolvedTo(h: HotCredentials, period: Int): Sum6KesSigning.SigningKey =
    h.kes.evolveTo(period).toOption.get
    h.kes

  private def refusals(h: Harness): IO[Vector[ReloadRefusal]] =
    h.seen.map(_.collect { case ForgeEvent.KesReloadRefused(r) => r })

  private def sequenceOf(raw: Bytes): BigInt =
    Block.decode(raw, geometry.byron.epochSlots).toOption.get.header match
      case s: Header.Shelley => s.opCert.sequence
      case other             => fail(s"not a Praos header: $other")

  private def rightOrFail[A](value: Either[ForgeError, A]): A =
    value.fold(error => fail(error.message), identity)

  // ---- the key file: replacement and recovery

  test("an evolved key replaces the key in the file, which no longer holds the earlier one") {
    withDir { f =>
      IO {
        val h = hotFor(TestLedger.key(), 5, 0, 0)
        f.install(h)
        val first = Files.readString(f.kes)
        val key = f.onDisk
        assertEquals(key.period, 0)
        key.evolveTo(7)
        assertEquals(KesFiles.persist(f.kes, key), Persisted.Written)
        assertEquals(f.onDisk.period, 7)
        assertEquals(f.onDisk.verificationKey, h.kes.verificationKey)
        assertNotEquals(Files.readString(f.kes), first)
        assert(!Files.exists(f.next), "the staging file is gone")
        // the same key again, or an earlier one, changes nothing
        assertEquals(KesFiles.persist(f.kes, key), Persisted.Unchanged)
        assertEquals(KesFiles.persist(f.kes, f.onDisk.copy), Persisted.Unchanged)
        // another KES key in the file is somebody's rotation: left alone
        val other = hotFor(TestLedger.key(), 6, 0, 1)
        assertEquals(KesFiles.persist(f.kes, other.kes), Persisted.Superseded)
        assertEquals(f.onDisk.verificationKey, h.kes.verificationKey)
      }
    }
  }

  test("a crash between writing the new key and erasing the old one loses neither") {
    withDir { f =>
      IO {
        val h = hotFor(TestLedger.key(), 5, 0, 0)
        f.install(h)
        val evolved = f.onDisk
        evolved.evolveTo(9)
        // crashed after step 1: both files are complete; the later one is used and the swap finished
        assertEquals(KesFiles.stage(f.kes, evolved), Right(()))
        assertEquals(f.onDisk.period, 0)
        assert(Files.exists(f.next))
        assertEquals(KesFiles.recover(f.kes).map(_.period), Right(9))
        assert(!Files.exists(f.next))
        assertEquals(KeyFiles.kesSigningKey("k", Files.readString(f.kes)).map(_.period), Right(9))
      }
    }
  }

  test("a crash after the old key was erased, before the rename, leaves the new key") {
    withDir { f =>
      IO {
        f.install(hotFor(TestLedger.key(), 5, 0, 0))
        val evolved = f.onDisk
        evolved.evolveTo(12)
        assertEquals(KesFiles.stage(f.kes, evolved), Right(()))
        assertEquals(KesFiles.scrub(f.kes), Right(()))
        assert(Files.readAllBytes(f.kes).forall(_ == 0), "the old key is overwritten")
        assertEquals(KesFiles.recover(f.kes).map(_.period), Right(12))
        assert(!Files.exists(f.next))
        assertEquals(f.onDisk.period, 12)
      }
    }
  }

  test("staging and predecessor scrub finish deterministic short writes") {
    withDir { f =>
      IO {
        val h = hotFor(TestLedger.key(), 51, 0, 0)
        f.install(h)
        val evolved = f.onDisk
        evolved.evolveTo(6)
        val writes = ArrayBuffer.empty[Int]
        val short = new KesFiles.FileOps:
          override def write(channel: FileChannel, bytes: ByteBuffer, position: Long): Int =
            val originalLimit = bytes.limit()
            bytes.limit(math.min(originalLimit, bytes.position() + 7))
            val count = channel.write(bytes, position)
            bytes.limit(originalLimit)
            writes += count
            count
        assertEquals(KesFiles.persistWith(f.kes, evolved, short), Persisted.Written)
        assert(writes.size > 2, s"expected repeated partial writes, saw ${writes.size}")
        assertEquals(f.onDisk.period, 6)
        assert(Files.readAllBytes(f.kes).exists(_ != 0), "the replacement key is complete")
        assert(!Files.exists(f.next))
      }
    }
  }

  test("a zero-progress staged write is a typed failure and leaves the predecessor intact") {
    withDir { f =>
      IO {
        f.install(hotFor(TestLedger.key(), 52, 0, 0))
        val before = Files.readAllBytes(f.kes).toVector
        val evolved = f.onDisk
        evolved.evolveTo(4)
        val stalled = new KesFiles.FileOps:
          override def write(channel: FileChannel, bytes: ByteBuffer, position: Long): Int = 0
        KesFiles.persistWith(f.kes, evolved, stalled) match
          case Persisted.Failed(
                ForgeError.KesFileFailure(
                  _,
                  KesFileOperation.StageWrite,
                  KesFileFailureCause.NoWriteProgress
                )
              ) =>
            ()
          case other => fail(s"expected typed staged-write failure, found $other")
        assertEquals(Files.readAllBytes(f.kes).toVector, before)
      }
    }
  }

  test("staged readback rejects a different KES period") {
    withDir { f =>
      IO {
        f.install(hotFor(TestLedger.key(), 56, 0, 0))
        val expected = f.onDisk
        expected.evolveTo(7)
        val wrongPeriod = f.onDisk
        wrongPeriod.evolveTo(6)
        val wrongPeriodText = rightOrFail(KeyFiles.kesEnvelope("staged", wrongPeriod))
        val wrongPeriodOps = new KesFiles.FileOps:
          override def readStage(file: Path): String = wrongPeriodText
        KesFiles.stageWith(f.kes, expected, wrongPeriodOps) match
          case Left(
                ForgeError.KesFileFailure(
                  _,
                  KesFileOperation.StageReadback,
                  KesFileFailureCause.StagedKeyMismatch(KesStageMismatch.Period(7, 6))
                )
              ) =>
            ()
          case other => fail(s"expected staged period mismatch, found $other")
        expected.forget()
        wrongPeriod.forget()
      }
    }
  }

  test("staged readback rejects a different verification key") {
    withDir { f =>
      IO {
        f.install(hotFor(TestLedger.key(), 57, 0, 0))
        val expected = f.onDisk
        expected.evolveTo(7)
        val foreign = hotFor(TestLedger.key(), 58, 0, 0).kes
        foreign.evolveTo(7)
        val foreignText = rightOrFail(KeyFiles.kesEnvelope("staged", foreign))
        val foreignOps = new KesFiles.FileOps:
          override def readStage(file: Path): String = foreignText
        KesFiles.stageWith(f.kes, expected, foreignOps) match
          case Left(
                ForgeError.KesFileFailure(
                  _,
                  KesFileOperation.StageReadback,
                  KesFileFailureCause.StagedKeyMismatch(
                    KesStageMismatch.VerificationKey(_, _)
                  )
                )
              ) =>
            ()
          case other => fail(s"expected staged identity mismatch, found $other")
        expected.forget()
        foreign.forget()
      }
    }
  }

  test("staged readback preserves malformed-envelope causes as typed failures") {
    withDir { f =>
      IO {
        f.install(hotFor(TestLedger.key(), 59, 0, 0))
        val evolved = f.onDisk
        evolved.evolveTo(3)
        val malformed = new KesFiles.FileOps:
          override def readStage(file: Path): String = "not a KES envelope"
        KesFiles.stageWith(f.kes, evolved, malformed) match
          case Left(
                ForgeError.KesFileFailure(
                  _,
                  KesFileOperation.StageReadback,
                  KesFileFailureCause.StageReadback(_)
                )
              ) =>
            ()
          case other => fail(s"expected typed staged readback failure, found $other")
        evolved.forget()
      }
    }
  }

  test("a pre-erasure directory sync failure leaves the predecessor intact and recovery retries") {
    withDir { f =>
      IO {
        f.install(hotFor(TestLedger.key(), 53, 0, 0))
        val before = Files.readAllBytes(f.kes).toVector
        val evolved = f.onDisk
        evolved.evolveTo(5)
        val refuse = new KesFiles.FileOps:
          override def forceDirectoryBeforeErase(channel: FileChannel): Unit =
            throw new IOException("injected directory sync refusal")
        KesFiles.persistWith(f.kes, evolved, refuse) match
          case Persisted.Failed(
                ForgeError.KesFileFailure(_, KesFileOperation.DirectorySyncBeforeErase, _)
              ) =>
            ()
          case other => fail(s"expected typed pre-erase sync failure, found $other")
        assertEquals(Files.readAllBytes(f.kes).toVector, before)
        assert(Files.exists(f.next), "the validated replacement remains staged")
        assertEquals(KesFiles.recoverWith(f.kes, new KesFiles.FileOps {}).map(_.period), Right(5))
      }
    }
  }

  test("a failed rename after scrub recovers the sole valid staged key on retry") {
    withDir { f =>
      IO {
        f.install(hotFor(TestLedger.key(), 54, 0, 0))
        val evolved = f.onDisk
        evolved.evolveTo(8)
        var failed = false
        val failFirstRename = new KesFiles.FileOps:
          override def rename(next: Path, file: Path): Unit =
            if !failed then
              failed = true
              throw new IOException("injected atomic rename refusal")
            else super.rename(next, file)
        KesFiles.persistWith(f.kes, evolved, failFirstRename) match
          case Persisted.Failed(ForgeError.KesFileFailure(_, KesFileOperation.Rename, _)) => ()
          case other => fail(s"expected injected rename boundary failure, found $other")
        assert(Files.readAllBytes(f.kes).forall(_ == 0), "the predecessor was scrubbed")
        assert(Files.exists(f.next), "the staged key is the only valid key")
        assertEquals(KesFiles.persistWith(f.kes, evolved, failFirstRename), Persisted.Unchanged)
        assertEquals(f.onDisk.period, 8)
        assert(!Files.exists(f.next))
      }
    }
  }

  test("a post-rename directory sync failure is retried when the key is already in place") {
    withDir { f =>
      IO {
        f.install(hotFor(TestLedger.key(), 55, 0, 0))
        val evolved = f.onDisk
        evolved.evolveTo(9)
        var failSync = true
        var syncAttempts = 0
        val failOnce = new KesFiles.FileOps:
          override def forceDirectoryAfterRename(channel: FileChannel): Unit =
            syncAttempts += 1
            if failSync then
              failSync = false
              throw new IOException("injected post-rename sync failure")
        KesFiles.persistWith(f.kes, evolved, failOnce) match
          case Persisted.Failed(
                ForgeError.KesFileFailure(_, KesFileOperation.DirectorySyncAfterRename, _)
              ) =>
            ()
          case other => fail(s"expected typed post-rename sync failure, found $other")
        assertEquals(f.onDisk.period, 9)
        assertEquals(KesFiles.persistWith(f.kes, evolved, failOnce), Persisted.Unchanged)
        assertEquals(syncAttempts, 2)
      }
    }
  }

  test("every persist durability boundary leaves an intact or recoverable key") {
    val cases = Vector(
      (KesFileBoundary.StagedWriteCompleted, 0, true),
      (KesFileBoundary.StagedFileForced, 0, true),
      (KesFileBoundary.StagedReadbackValidated, 0, true),
      (KesFileBoundary.DirectoryForcedBeforeErase, 0, true),
      (KesFileBoundary.PredecessorScrubCompleted, -1, true),
      (KesFileBoundary.PredecessorFileForced, -1, true),
      (KesFileBoundary.AtomicRenameCompleted, 8, false),
      (KesFileBoundary.DirectoryForcedAfterRename, 8, false)
    )
    cases.traverse_ { case (boundary, mainPeriod, hasStage) =>
      withDir { f =>
        IO {
          f.install(hotFor(TestLedger.key(), 61, 0, 0))
          val evolved = f.onDisk
          evolved.evolveTo(8)
          val crash = new KesFiles.FileOps:
            override def checkpoint(at: KesFileBoundary): Unit =
              if at == boundary then throw new IOException(s"crash at $at")
          KesFiles.persistWith(f.kes, evolved, crash) match
            case Persisted.Failed(_) => ()
            case other => fail(s"expected injected failure at $boundary, found $other")
          if mainPeriod == -1 then
            assert(Files.readAllBytes(f.kes).forall(_ == 0), s"predecessor scrubbed at $boundary")
          else if mainPeriod == 0 then assertEquals(f.onDisk.period, 0)
          else assertEquals(f.onDisk.period, mainPeriod)
          assertEquals(Files.exists(f.next), hasStage, s"stage presence at $boundary")
          val recovered = rightOrFail(KesFiles.recover(f.kes))
          assertEquals(recovered.period, 8, s"recovery after $boundary")
          recovered.forget()
          assert(!Files.exists(f.next))
        }
      }
    }
  }

  test("every recovery durability boundary is retryable") {
    val boundaries = Vector(
      KesFileBoundary.StagedFileForced,
      KesFileBoundary.StagedReadbackValidated,
      KesFileBoundary.DirectoryForcedBeforeErase,
      KesFileBoundary.PredecessorScrubCompleted,
      KesFileBoundary.PredecessorFileForced,
      KesFileBoundary.AtomicRenameCompleted,
      KesFileBoundary.DirectoryForcedAfterRename
    )
    boundaries.traverse_ { boundary =>
      withDir { f =>
        IO {
          f.install(hotFor(TestLedger.key(), 62, 0, 0))
          val evolved = f.onDisk
          evolved.evolveTo(9)
          assertEquals(KesFiles.stage(f.kes, evolved), Right(()))
          val crash = new KesFiles.FileOps:
            override def checkpoint(at: KesFileBoundary): Unit =
              if at == boundary then throw new IOException(s"crash at $at")
          KesFiles.recoverWith(f.kes, crash) match
            case Left(ForgeError.KesFileFailure(_, _, _)) => ()
            case other => fail(s"expected injected recovery failure at $boundary, found $other")
          if boundary == KesFileBoundary.PredecessorScrubCompleted ||
            boundary == KesFileBoundary.PredecessorFileForced
          then
            assert(Files.readAllBytes(f.kes).forall(_ == 0), s"predecessor scrubbed at $boundary")
          else if boundary == KesFileBoundary.AtomicRenameCompleted ||
            boundary == KesFileBoundary.DirectoryForcedAfterRename
          then assertEquals(f.onDisk.period, 9)
          else assertEquals(f.onDisk.period, 0)
          val recovered = rightOrFail(KesFiles.recover(f.kes))
          assertEquals(recovered.period, 9, s"retry after $boundary")
          recovered.forget()
          assert(!Files.exists(f.next))
        }
      }
    }
  }

  test("retry recovers an older sole-valid stage before writing a newer in-memory key") {
    withDir { f =>
      IO {
        f.install(hotFor(TestLedger.key(), 63, 0, 0))
        val older = f.onDisk
        older.evolveTo(4)
        assertEquals(KesFiles.stage(f.kes, older), Right(()))
        assertEquals(KesFiles.scrub(f.kes), Right(()))
        val newer = older.copy
        newer.evolveTo(7)
        assertEquals(KesFiles.persist(f.kes, newer), Persisted.Written)
        assertEquals(f.onDisk.period, 7)
        assert(!Files.exists(f.next))
      }
    }
  }

  test("a torn or foreign staging file is destroyed and the key in the file stands") {
    withDir { f =>
      IO {
        val h = hotFor(TestLedger.key(), 5, 0, 0)
        f.install(h)
        val key = f.onDisk
        key.evolveTo(3)
        KesFiles.persist(f.kes, key)
        // torn: the crash came while the new key was being written
        Files.writeString(f.next, """{"type": "KesSigningKey_ed25519_kes_2^6", "cborHex": "5902""")
        assertEquals(KesFiles.recover(f.kes).map(_.period), Right(3))
        assert(!Files.exists(f.next))
        // foreign: a complete key, but another KES key
        val other = hotFor(TestLedger.key(), 6, 0, 0).kes
        other.evolveTo(8)
        KesFiles.stage(f.kes, other)
        assertEquals(KesFiles.recover(f.kes).map(_.verificationKey), Right(h.kes.verificationKey))
        assert(!Files.exists(f.next))
        // stale: the same key at an earlier period than the file's
        val stale = hotFor(TestLedger.key(), 5, 0, 0).kes
        KesFiles.stage(f.kes, stale)
        assertEquals(KesFiles.recover(f.kes).map(_.period), Right(3))
        assert(!Files.exists(f.next))
      }
    }
  }

  // ---- the forger writes the evolved key back

  test(
    "a refused KES evolution retains its crypto cause without changing the key or emitting events"
  ) {
    val p = pool(kesStart = 50, seed = 3)
    val before = p.creds.kes.toBytes
    for
      seen <- Ref.of[IO, Vector[ForgeEvent]](Vector.empty)
      keys <- HotKeys.create[IO](p.creds, None, config, e => seen.update(_ :+ e))
      result <- keys.locked(c => keys.evolved(c, 49))
      _ = assertEquals(
        result,
        Left(ForgeError.KesEvolution(Sum6KesSigning.Failure.PeriodOutOfRange(-1)))
      )
      _ = assertEquals(p.creds.kes.period, 0)
      _ = assertEquals(p.creds.kes.toBytes, before)
      events <- seen.get
      _ = assertEquals(events, Vector.empty)
    yield ()
  }

  test("restart across a KES boundary: the file holds the evolved key and forging goes on") {
    val p = pool(kesStart = 50, seed = 3)
    withDir { f =>
      f.install(p.creds.hot)
      val first = Files.readString(f.kes)
      withHandle {
        for
          src <- source(f)
          h <- harness(p, ledgerWith(p, 1), source = Some(src))
          // slots 5000..5299 are KES periods 50..52
          _ <- (5000 until 5300).toList.traverse_(s => h.forger.step(s))
          forged <- forgedIn(h)
          _ = assertEquals(f.onDisk.period, 2)
          _ = assertNotEquals(Files.readString(f.kes), first)
          // a restart: the credentials are read from the files, the earlier key is not needed
          restarted = KesFiles.readHot(f.kes, f.cert).toOption.get
          _ = assertEquals(restarted.kes.period, 2)
          p2 = p.copy(creds = p.creds.withHot(restarted))
          h2 <- harness(p2, ledgerWith(p2, 1), source = Some(src))
          _ <- (5300 until 5400).toList.traverse_(s => h2.forger.step(s))
          forged2 <- forgedIn(h2)
          events2 <- h2.seen
        yield
          assert(forged.nonEmpty && forged2.nonEmpty)
          assertEquals(forged2.map(_.relativeKesPeriod).distinct, Vector(3))
          assertEquals(events2.collect { case ForgeEvent.KesEvolved(_, r) => r }, Vector(3))
          assert(!events2.exists(_.isInstanceOf[ForgeEvent.Failed]), events2.toString)
          assertEquals(f.onDisk.period, 3)
      }
    }
  }

  test("the key is evolved and written at the period boundary, whether or not the pool leads") {
    val p = pool(kesStart = 50, seed = 8)
    withDir { f =>
      f.install(p.creds.hot)
      withHandle {
        for
          src <- source(f)
          // a ledger without this pool: it is never elected
          other = pool(kesStart = 50, seed = 9)
          h <- harness(p, ledgerWith(other, 1), source = Some(src))
          _ <- h.forger.step(5099)
          _ = assertEquals(f.onDisk.period, 0)
          _ <- h.forger.step(5100)
          events <- h.seen
        yield
          assertEquals(f.onDisk.period, 1)
          assertEquals(
            events.collect { case ForgeEvent.KesEvolved(k, r) => (k, r) },
            Vector((BigInt(51), 1))
          )
      }
    }
  }

  test(
    "a key that cannot be written is reported once per period, tried again at each slot, and forging goes on"
  ) {
    val p = pool(kesStart = 50, seed = 4)
    withDir { f =>
      f.install(p.creds.hot)
      withHandle {
        for
          inner <- source(f)
          failing <- Ref.of[IO, Boolean](true)
          flaky = new HotKeySource[IO]:
            def read = inner.read
            def persist(key: Sum6KesSigning.SigningKey) =
              failing.get.flatMap(down =>
                if down then IO.pure(Persisted.Failed(ForgeError.KeyFile("kes.skey", "disk full")))
                else inner.persist(key)
              )
          h <- harness(p, ledgerWith(p, 1), source = Some(flaky))
          _ <- h.forger.step(5100)
          _ <- h.forger.step(5101)
          _ <- failing.set(false)
          _ <- h.forger.step(5102)
          _ <- h.forger.step(5103)
          events <- h.seen
        yield
          assertEquals(
            events.collect { case ForgeEvent.KesPersistFailed(k, _) => k },
            Vector(BigInt(51))
          )
          assertEquals(f.onDisk.period, 1)
          assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]), events.toString)
      }
    }
  }

  // ---- the reload

  test("a reload with a good certificate and key switches, and blocks then carry the new counter") {
    val p = pool(kesStart = 50, seed = 11)
    TestControl.executeEmbed(
      withDir { f =>
        f.install(p.creds.hot)
        val next = hotFor(p.cold, seed = 21, start = 50, sequence = 1)
        withHandle {
          for
            src <- source(f)
            h <- harness(p, ledgerWith(p, 1), live = true, source = Some(src))
            _ <- (5000 until 5030).toList.traverse_(s => h.forger.step(s))
            old <- h.raws.get
            _ = f.install(next)
            _ <- h.forger.reload
            _ <- (5030 until 5080).toList.traverse_(s => h.forger.step(s))
            all <- h.raws.get
            events <- h.seen
            current <- h.forger.hot.current
          yield
            assert(p.creds.kes.isForgotten, "the earlier key is erased from memory")
            assertEquals(current.opCert.sequence, BigInt(1))
            assertEquals(
              events.collect { case e: ForgeEvent.KesReloaded => e },
              Vector(ForgeEvent.KesReloaded(1, 50, 62, 50))
            )
            assert(old.nonEmpty && all.size > old.size)
            assertEquals(old.values.map(sequenceOf).toSet, Set(BigInt(0)))
            assertEquals(
              all.removedAll(old.keys).values.map(sequenceOf).toSet,
              Set(BigInt(1))
            )
            assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]), events.toString)
        }
      }
    )
  }

  test("a reload that is refused keeps the credentials in force and says why") {
    val p = pool(kesStart = 50, seed = 12)
    val stranger = TestLedger.key()
    TestControl.executeEmbed(
      withDir { f =>
        f.install(p.creds.hot)
        val good = hotFor(p.cold, seed = 22, start = 50, sequence = 1)
        // the certificate is for another KES key than the one in the file
        def mismatch() =
          f.install(good)
          Files.writeString(f.cert, opCertText(hotFor(p.cold, seed = 23, start = 50, sequence = 1)))
        // the certificate's signature has been altered
        def forged() =
          val bad = hotFor(p.cold, seed = 24, start = 50, sequence = 1)
          val sigma = bad.opCert.sigma.toArray
          sigma(0) = (sigma(0) ^ 1).toByte
          f.install(bad.copy(opCert = bad.opCert.copy(sigma = Bytes.fromArray(sigma))))
        withHandle {
          for
            src <- source(f)
            h <- harness(p, ledgerWith(p, 1), live = true, source = Some(src))
            _ = mismatch()
            _ <- h.forger.reload
            _ = forged()
            _ <- h.forger.reload
            _ = f.install(hotFor(stranger, seed = 25, start = 50, sequence = 1))
            _ <- h.forger.reload
            _ = f.install(hotFor(p.cold, seed = 26, start = 50, sequence = 5))
            _ <- h.forger.reload
            _ = f.install(hotFor(p.cold, seed = 27, start = 60, sequence = 1))
            _ <- h.forger.reload
            _ = Files.delete(f.cert)
            _ <- h.forger.reload
            seen <- refusals(h)
            current <- h.forger.hot.current
            _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
            blocks <- h.raws.get
          yield
            assertEquals(
              seen.map(_.productPrefix),
              Vector(
                "KeyNotCertified",
                "BadCertificateSignature",
                "WrongPool",
                "Counter",
                "NotYetValid",
                "Unreadable"
              )
            )
            assertEquals(seen(3), ReloadRefusal.Counter(OCertError.CounterOutOfRange(0, 5)))
            assertEquals(seen(4), ReloadRefusal.NotYetValid(50, 60))
            assertEquals(current.opCert.sequence, BigInt(0))
            assert(!current.kes.isForgotten)
            assert(blocks.nonEmpty)
            assertEquals(blocks.values.map(sequenceOf).toSet, Set(BigInt(0)))
        }
      }
    )
  }

  test("a reload of the key already in use keeps its evolved state, never going back a period") {
    val p = pool(kesStart = 50, seed = 13)
    TestControl.executeEmbed(
      withDir { f =>
        f.install(p.creds.hot)
        withHandle {
          for
            src <- source(f)
            h <- harness(p, ledgerWith(p, 1), live = true, source = Some(src))
            _ <- (5000 until 5300).toList.traverse_(s => h.forger.step(s))
            _ <- IO.sleep(250.seconds) // the clock has reached slot 5250, period 52
            // someone puts the period-0 file back
            _ = f.install(hotFor(p.cold, seed = 13, start = 50, sequence = 0))
            _ <- h.forger.reload
            current <- h.forger.hot.current
            periodAfter = current.kes.period
            _ <- (5300 until 5330).toList.traverse_(s => h.forger.step(s))
            events <- h.seen
          yield
            assertEquals(events.collect { case e: ForgeEvent.KesReloaded => e }.size, 1)
            assertEquals(periodAfter, 2)
            assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]), events.toString)
        }
      }
    )
  }

  test(
    "the key is written where it was read: a refused reload changes nothing, a good one moves it"
  ) {
    val p = pool(kesStart = 50, seed = 17)
    TestControl.executeEmbed(
      withDir { f =>
        val link = f.dir.resolve("current")
        val (gen0, gen1) = (f.dir.resolve("gen-0"), f.dir.resolve("gen-1"))
        def install(gen: Path, h: HotCredentials): Unit =
          Files.createDirectories(gen)
          Files.writeString(gen.resolve("kes.skey"), KeyFiles.kesEnvelope("k", h.kes).toOption.get)
          Files.writeString(gen.resolve("node.cert"), opCertText(h))
        def point(gen: Path): Unit =
          val tmp = f.dir.resolve("current.tmp")
          Files.deleteIfExists(tmp)
          Files.createSymbolicLink(tmp, gen.getFileName)
          Files.move(tmp, link, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        def periodIn(gen: Path): Int =
          KeyFiles.kesSigningKey("k", Files.readString(gen.resolve("kes.skey"))).toOption.get.period
        install(gen0, p.creds.hot)
        point(gen0)
        withHandle {
          for
            src <- HotKeySource.files[IO](link.resolve("kes.skey"), link.resolve("node.cert"))
            h <- harness(p, ledgerWith(p, 1), live = true, source = Some(src))
            // a rotation the node will refuse (counter 5), as the driver leaves it before the signal
            _ = install(gen1, hotFor(p.cold, seed = 41, start = 50, sequence = 5))
            _ = point(gen1)
            _ <- h.forger.reload
            refused <- refusals(h)
            // the driver puts the old pair back and erases the refused one
            _ = point(gen0)
            _ = removeTree(gen1)
            _ <- h.forger.step(5100)
            afterRefusal = periodIn(gen0)
            // a good rotation: the new pair is where the key is written from now on
            _ = install(gen1, hotFor(p.cold, seed = 42, start = 50, sequence = 1))
            _ = point(gen1)
            _ <- h.forger.reload
            _ <- h.forger.step(5200)
            events <- h.seen
          yield
            assertEquals(refused.map(_.productPrefix), Vector("Counter"))
            assertEquals(afterRefusal, 1, "the evolved key went to the pair still in force")
            assertEquals(periodIn(gen1), 2, "after the reload the key goes to the new pair")
            assertEquals(
              periodIn(gen0),
              1,
              "and the old pair is left as it was, for the driver to erase"
            )
            assert(!events.exists(_.isInstanceOf[ForgeEvent.KesPersistFailed]), events.toString)
        }
      }
    )
  }

  // ---- the rules of a reload, on their own

  private val config0 = config

  private def decide(
      p: Pool,
      candidate: HotCredentials,
      period: Int,
      counter: Option[BigInt]
  ): Either[ReloadRefusal, Unit] =
    HotKeys.validate(p.creds, candidate, period, counter, config0)

  test("a certificate whose window has passed, or has not begun, is refused") {
    val p = pool(kesStart = 0, seed = 14)
    val cert = hotFor(p.cold, 31, start = 10, sequence = 0)
    assertEquals(decide(p, cert, 71, None), Right(()))
    assertEquals(decide(p, cert, 72, None), Left(ReloadRefusal.Expired(72, 10, 62)))
    assertEquals(decide(p, cert, 9, None), Left(ReloadRefusal.NotYetValid(9, 10)))
  }

  test("the counter must be the one on the chain or the next; lower and higher are refused") {
    val p = pool(kesStart = 0, seed = 15)
    def at(sequence: Int) = hotFor(p.cold, 32, start = 0, sequence = sequence)
    val onChain = Some(BigInt(5))
    assertEquals(decide(p, at(5), 3, onChain), Right(()))
    assertEquals(decide(p, at(6), 3, onChain), Right(()))
    assertEquals(
      decide(p, at(3), 3, onChain),
      Left(ReloadRefusal.Counter(OCertError.CounterOutOfRange(5, 3)))
    )
    assertEquals(
      decide(p, at(7), 3, onChain),
      Left(ReloadRefusal.Counter(OCertError.CounterOutOfRange(5, 7)))
    )
    // a pool with no counter on the chain yet starts at 0 or 1
    assertEquals(decide(p, at(1), 3, None), Right(()))
    assertEquals(
      decide(p, at(2), 3, None),
      Left(ReloadRefusal.Counter(OCertError.CounterOutOfRange(0, 2)))
    )
  }

  test("a key already evolved past the period it must sign in is refused") {
    val p = pool(kesStart = 0, seed = 16)
    val cert = hotFor(p.cold, 33, start = 0, sequence = 0)
    val fresh = cert.copy(kes = evolvedTo(cert, 10))
    assertEquals(decide(p, fresh, 10, None), Right(()))
    assertEquals(decide(p, fresh, 9, None), Left(ReloadRefusal.KeyPastPeriod(10, 9)))
  }

  test("a malformed certificate reload retains its cause and leaves the active keys unchanged") {
    val p = pool(kesStart = 50, seed = 31)
    val good = hotFor(p.cold, seed = 32, start = 50, sequence = 1)
    val candidate = good.copy(opCert = good.opCert.copy(sigma = Bytes.empty))
    val cause = xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate.Failure.Signature(
      xyz.kd5ujc.scarab.crypto.witness.WitnessInputError.MalformedSignatureLength(0)
    )
    val expected = ReloadRefusal.CertificateFailure(cause)
    assertEquals(HotKeys.validate(p.creds, candidate, 50, Some(0), config), Left(expected))
    for
      seen <- Ref.of[IO, Vector[ForgeEvent]](Vector.empty)
      adopted <- Ref.of[IO, Boolean](false)
      src = new HotKeySource[IO]:
        def read = IO.pure(Right(HotKeySource.Candidate(candidate, adopted.set(true))))
        def persist(key: Sum6KesSigning.SigningKey) = IO.pure(Persisted.Unchanged)
      keys <- HotKeys.create[IO](p.creds, Some(src), config, e => seen.update(_ :+ e))
      _ <- keys.reload(50, Some(0))
      current <- keys.current
      events <- seen.get
      switched <- adopted.get
    yield
      assertEquals(current, p.creds)
      assertEquals(events, Vector(ForgeEvent.KesReloadRefused(expected)))
      assert(!switched)
      assert(!current.kes.isForgotten)
      assert(candidate.kes.isForgotten)
  }
