// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.preview

import cats.effect.IO
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.node.{NativeSnapshots, NodeSetup}
import xyz.kd5ujc.scarab.storage.{FileLedgerSnapshots, SnapshotReadError}

class NativeSnapshotBindingSuite extends CatsEffectSuite:
  private val source = Path.of("fixtures/snapshot-v5/preview-4320.bin")
  private val env = NodeSetup
    .fromDirectory(Path.of("fixtures/preview/genesis"))
    .fold(error => fail(error.message), _.mirrorEnv())

  test("the directly authenticated V5 bytes decode identically to the existing file helper") {
    for
      bytes <- IO.blocking(Files.readAllBytes(source))
      fromBytes <- NativeSnapshots.decode[IO](bytes, env)
      fromFile <- NativeSnapshots.read[IO](source, env)
      _ <- IO {
        assert(fromBytes.isRight)
        assertEquals(fromBytes, fromFile)
      }
    yield ()
  }

  test("direct byte decoding retains both checksum and native format validation") {
    for
      bytes <- IO.blocking(Files.readAllBytes(source))
      damaged = bytes.updated(bytes.length - 1, (bytes.last ^ 1).toByte)
      checksum <- NativeSnapshots.decode[IO](damaged, env)
      wrongFormat <- NativeSnapshots
        .decode[IO](FileLedgerSnapshots.closed(Array.fill[Byte](16)(0)), env)
      _ <- IO {
        checksum match
          case Left(StorageError.Corrupt(_, SnapshotReadError.ChecksumMismatch)) => ()
          case other => fail(s"checksum refusal: $other")
        assert(wrongFormat.isLeft)
      }
    yield ()
  }
