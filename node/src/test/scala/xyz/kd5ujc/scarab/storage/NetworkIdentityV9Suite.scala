// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.charset.StandardCharsets
import java.nio.file.Files as JFiles
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  FileKind,
  FormatVersion,
  GenesisFileHash,
  IdentityProvenance,
  NetworkIdentity,
  StorageError,
  StoredNetworkIdentity
}
import xyz.kd5ujc.scarab.node.NodeSetup

import StorageTestKit.*

/** The network identity V9 and its Dijkstra genesis binding (OD-2 of #54, #824): the identity the
  * node assembles binds the Dijkstra genesis file its configuration names, and a V6 file (the
  * committed `fixtures/network-identity-v6`) gets that binding at its first open, after its other
  * fields are found equal to the configured ones.
  */
class NetworkIdentityV9Suite extends CatsEffectSuite:
  private val devnet = java.nio.file.Path.of("fixtures/dijkstra-devnet/genesis")
  private val setup = NodeSetup
    .fromConfigFile(devnet.resolve("config.json"))
    .fold(e => fail(e.message), identity)
  // a configuration with no Dijkstra genesis
  private val eras = NodeSetup
    .fromDirectory(java.nio.file.Path.of("fixtures/eras/genesis"))
    .fold(e => fail(e.message), identity)
  private val v6 = JFiles.readString(
    java.nio.file.Path.of("fixtures/network-identity-v6/network-identity"),
    StandardCharsets.UTF_8
  )

  private def attempt[A](body: Handle[IO, StorageError] ?=> IO[A]): IO[Either[StorageError, A]] =
    Handle
      .allow[StorageError](body.map(_.asRight[StorageError]))
      .rescue(e => IO.pure(e.asLeft[A]))

  private def ok[A](body: Handle[IO, StorageError] ?=> IO[A]): IO[A] =
    attempt(body).flatMap(_.fold(e => IO.raiseError(new AssertionError(e.message)), IO.pure))

  private def seeded(dir: Path, text: String): IO[Unit] =
    IO.blocking(JFiles.writeString((dir / NetworkIdentityFile.FileName).toNioPath, text)).void

  private def text(dir: Path): IO[String] =
    IO.blocking(JFiles.readString((dir / NetworkIdentityFile.FileName).toNioPath))

  test("the node binds the Dijkstra genesis file its configuration names, and no other") {
    val file = JFiles.readAllBytes(devnet.resolve("dijkstra-genesis.json"))
    val hash = Blake2b.hash256.hash(Bytes.fromArray(file))
    assertEquals(setup.identity.dijkstraGenesis, Some(GenesisFileHash(hash)))
    // the file the setup checked against the configured `DijkstraGenesisHash`
    assertEquals(setup.config.hashes.dijkstra, Some(hash))
    assertEquals(eras.identity.dijkstraGenesis, None)
  }

  test("a new database records V9, with the binding or with none") {
    for (identity, line) <- List(
        setup.identity -> s"dijkstra-genesis file:${setup.identity.dijkstraGenesis.fold("")(_.hash.hex)}",
        eras.identity -> "dijkstra-genesis none"
      )
    do
      val rendered = identity.render
      assertEquals(rendered.linesIterator.next(), "scarab network identity v9")
      assert(rendered.linesIterator.contains(line), rendered)
      assertEquals(
        NetworkIdentity.parseVersioned("f", rendered),
        Right(
          (FormatVersion.V9, StoredNetworkIdentity.Complete(identity, IdentityProvenance.Fresh))
        )
      )
    assertEquals(FileKind.IdentityFile.current, FormatVersion.V9)
  }

  test("the committed V6 file is what V6 records for the devnet's identity, read as V6") {
    assertEquals(v6, setup.identity.renderAs(FormatVersion.V6))
    assertEquals(
      NetworkIdentity.parseVersioned("f", v6),
      Right(
        (
          FormatVersion.V6,
          StoredNetworkIdentity.BeforeDijkstra(
            setup.identity.beforeDijkstra,
            IdentityProvenance.Fresh
          )
        )
      )
    )
  }

  test("the V6 file gets the configured Dijkstra binding at its first open, and keeps it") {
    tempDir.use { dir =>
      for
        _ <- seeded(dir, v6)
        verified <- ok(NetworkIdentityFile.verify[IO](dir, setup.identity))
        // the read-only checks write nothing
        plan <- ok(
          NetworkIdentityFile.preflight[IO](
            dir,
            setup.identity,
            populated = true,
            confirmLegacy = false
          )
        )
        unchanged <- text(dir)
        migrated <- ok(NetworkIdentityFile.migrate[IO](dir, Some(setup.identity)))
        written <- text(dir)
        again <- ok(NetworkIdentityFile.migrate[IO](dir, Some(setup.identity)))
        read <- ok(NetworkIdentityFile.read[IO](dir))
      yield
        assert(verified)
        assertEquals(plan, NetworkIdentityFile.Admission.Unchanged)
        assertEquals(unchanged, v6)
        assertEquals(
          migrated,
          List(Migrated(FileKind.IdentityFile, FormatVersion.V6, FormatVersion.V9, 1))
        )
        assertEquals(written, setup.identity.render)
        assertEquals(again, Nil)
        assertEquals(
          read,
          Some(StoredNetworkIdentity.Complete(setup.identity, IdentityProvenance.Fresh))
        )
    }
  }

  test("the migration keeps the operator's adoption record") {
    val adopted = IdentityProvenance.OperatorConfirmedLegacy(
      FormatVersion.V2,
      Bytes.fromArray(Array.fill[Byte](32)(7))
    )
    tempDir.use { dir =>
      for
        _ <- seeded(dir, setup.identity.renderAs(FormatVersion.V6, adopted))
        _ <- ok(NetworkIdentityFile.migrate[IO](dir, Some(setup.identity)))
        written <- text(dir)
      yield assertEquals(written, setup.identity.renderAs(FormatVersion.V9, adopted))
    }
  }

  test("a V6 file opened without a Dijkstra genesis records none, which adopts one once") {
    val withoutDijkstra = setup.identity.copy(dijkstraGenesis = None)
    tempDir.use { dir =>
      for
        _ <- seeded(dir, v6)
        _ <- ok(NetworkIdentityFile.migrate[IO](dir, Some(withoutDijkstra)))
        written <- text(dir)
        // the read-only checks accept the configured genesis and write nothing
        verified <- ok(NetworkIdentityFile.verify[IO](dir, setup.identity))
        plan <- ok(
          NetworkIdentityFile.preflight[IO](
            dir,
            setup.identity,
            populated = true,
            confirmLegacy = false
          )
        )
        unchanged <- text(dir)
        adopted <- ok(NetworkIdentityFile.adoptDijkstra[IO](dir, setup.identity))
        rewritten <- text(dir)
        read <- ok(NetworkIdentityFile.read[IO](dir))
        again <- ok(NetworkIdentityFile.adoptDijkstra[IO](dir, setup.identity))
        last <- text(dir)
      yield
        assertEquals(written, withoutDijkstra.render)
        assert(written.linesIterator.contains("dijkstra-genesis none"), written)
        assert(verified)
        assertEquals(plan, NetworkIdentityFile.Admission.Unchanged)
        assertEquals(unchanged, written)
        assertEquals(adopted, Some(setup.identity))
        assertEquals(rewritten, setup.identity.render)
        assertEquals(
          read,
          Some(StoredNetworkIdentity.Complete(setup.identity, IdentityProvenance.Fresh))
        )
        assertEquals(again, None)
        assertEquals(last, rewritten)
    }
  }

  test("the adoption keeps the operator's adoption record") {
    val adopted = IdentityProvenance.OperatorConfirmedMissing
    tempDir.use { dir =>
      for
        _ <- seeded(
          dir,
          setup.identity.copy(dijkstraGenesis = None).renderAs(FormatVersion.V9, adopted)
        )
        _ <- ok(NetworkIdentityFile.adoptDijkstra[IO](dir, setup.identity))
        written <- text(dir)
      yield assertEquals(written, setup.identity.renderAs(FormatVersion.V9, adopted))
    }
  }

  test("a recorded Dijkstra genesis is never replaced: another one, or none, is a mismatch") {
    val other = setup.identity.copy(dijkstraGenesis =
      Some(GenesisFileHash(Bytes.fromArray(Array.fill[Byte](32)(5))))
    )
    val withoutDijkstra = setup.identity.copy(dijkstraGenesis = None)
    tempDir.use { dir =>
      for
        _ <- seeded(dir, setup.identity.render)
        results <- List(other, withoutDijkstra).traverse { expected =>
          for
            verified <- attempt(NetworkIdentityFile.verify[IO](dir, expected))
            plan <- attempt(
              NetworkIdentityFile.preflight[IO](
                dir,
                expected,
                populated = true,
                confirmLegacy = true
              )
            )
            adopted <- ok(NetworkIdentityFile.adoptDijkstra[IO](dir, expected))
          yield (expected, verified, plan, adopted)
        }
        after <- text(dir)
      yield
        results.foreach { (expected, verified, plan, adopted) =>
          val mismatch = Left(StorageError.NetworkMismatch(setup.identity, expected))
          assertEquals(verified, mismatch)
          assertEquals(plan, mismatch)
          assertEquals(adopted, None)
        }
        assertEquals(after, setup.identity.render)
    }
  }

  test("a recorded none with no Dijkstra genesis configured stays none") {
    tempDir.use { dir =>
      for
        _ <- seeded(dir, eras.identity.render)
        verified <- ok(NetworkIdentityFile.verify[IO](dir, eras.identity))
        adopted <- ok(NetworkIdentityFile.adoptDijkstra[IO](dir, eras.identity))
        after <- text(dir)
      yield
        assert(verified)
        assertEquals(adopted, None)
        assertEquals(after, eras.identity.render)
    }
  }

  test("a V6 file of another network is refused by every check and left as it is") {
    tempDir.use { dir =>
      val expected = eras.identity
      val found = setup.identity.beforeDijkstra.bind(expected.dijkstraGenesis)
      val mismatch = Left(StorageError.NetworkMismatch(found, expected))
      for
        _ <- seeded(dir, v6)
        verified <- attempt(NetworkIdentityFile.verify[IO](dir, expected))
        plan <- attempt(
          NetworkIdentityFile.preflight[IO](dir, expected, populated = true, confirmLegacy = true)
        )
        migrated <- attempt(NetworkIdentityFile.migrate[IO](dir, Some(expected)))
        after <- text(dir)
      yield
        assertEquals(verified, mismatch)
        assertEquals(plan, mismatch)
        assertEquals(migrated, mismatch)
        assertEquals(after, v6)
    }
  }

  test("a migration without the node's configuration does not bind the V6 file") {
    tempDir.use { dir =>
      for
        _ <- seeded(dir, v6)
        migrated <- attempt(NetworkIdentityFile.migrate[IO](dir, None))
        after <- text(dir)
      yield
        assertEquals(migrated, Left(StorageError.IdentityNeedsDijkstraBinding(FormatVersion.V6)))
        assertEquals(after, v6)
    }
  }
