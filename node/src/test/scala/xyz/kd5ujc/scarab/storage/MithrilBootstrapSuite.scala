// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.data.EitherT
import cats.effect.{IO, Outcome}
import cats.mtl.{Ask, Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import java.nio.file.{Files, Path as JPath}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorExtras}
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.storage.haskell.{ChunkSlots, HaskellImport}
import xyz.kd5ujc.scarab.storage.mithril.{MithrilBootstrap as Bootstrap, MithrilClient as Client}

/** Stub clients exercise orchestration only; cryptographic acceptance uses the pinned real client.
  */
class MithrilBootstrapSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private val setup =
    NodeSetup.fromDirectory(JPath.of("fixtures/eras/genesis")).fold(e => fail(e.message), identity)
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
  private val id = Client.Artifact.parse("a" * 64).fold(e => fail(e.message), identity)
  private val key = Client.VerificationKey
    .parse(Files.readString(JPath.of("fixtures/mempack/ancillary/preprod.vkey")))
    .fold(e => fail(e.message), identity)
  private val endpoint =
    Client.Endpoint.parse("http://127.0.0.1:1234").fold(e => fail(e.message), identity)
  private val boot = setup.mirrorStart.extras match
    case MirrorExtras.InByron(_, value, _) => value
    case MirrorExtras.InShelley(_, value)  => value.boot
  private def config(root: Path, source: Client.Source): Bootstrap.Config =
    val identity = setup.identity.copy(magic = 2L)
    Bootstrap.Config(
      root / "db",
      Client.Request(
        id,
        endpoint,
        Client.Roots(key, key),
        Client.Network.Preview,
        Client.Executable.pinned(root / "unused"),
        source
      ),
      HaskellImport.Config(
        Path("."),
        root / "db",
        identity,
        setup.geometry.byron.epochSlots,
        ChunkSlots.fromSecurityParam(10).getOrElse(fail("slots"))
      ),
      LedgerSnapshotImport.Config(
        Path("."),
        root / "db",
        identity,
        setup.geometry.byron.epochSlots,
        setup.handOver.fold(fail("Byron"))(_.protocolParams),
        boot,
        LedgerSnapshotImport.Trust.Unverified
      )
    )
  private def attempt[A](
      body: Handle[IO, Bootstrap.Error] ?=> IO[A]
  ): IO[Either[Bootstrap.Error, A]] =
    Handle
      .allow[Bootstrap.Error](body.map(_.asRight[Bootstrap.Error]))
      .rescue(e => IO.pure(Left(e)))
  private def emptyStage(root: Path): Unit =
    val paths = Files.list(root.toNioPath)
    try assert(!paths.anyMatch(_.getFileName.toString.startsWith(".db.mithril-")))
    finally paths.close()
  private def refusing(refused: IO[Unit]): Client[IO] = new Client[IO]:
    def acquire(request: Client.Request, work: Path)(using
        Raise[IO, Client.Error]
    ): IO[Client.Verified] =
      refused *> Client.Error.RefusedVerification.raise[IO, Client.Verified]

  test("existing destinations are unchanged and refused before invoking the verifier") {
    StorageTestKit.tempDir.use { root =>
      for
        invoked <- IO.ref(false)
        _ <- IO.blocking {
          Files.createDirectory((root / "db").toNioPath);
          Files.writeString((root / "db/old").toNioPath, "original")
        }
        result <- attempt(
          Bootstrap.run(config(root, Client.Source.Download), refusing(invoked.set(true)))
        )
        touched <- invoked.get
      yield
        assertEquals(result, Left(Bootstrap.Error.DestinationExists((root / "db").toString)))
        assert(!touched)
        assertEquals(Files.readString((root / "db/old").toNioPath), "original")
        emptyStage(root)
    }
  }

  test("cached input is isolated, verifier refusal removes staging and releases ownership") {
    StorageTestKit.tempDir.use { root =>
      val original = root / "original"
      for
        _ <- IO.blocking {
          Files.createDirectory(original.toNioPath);
          Files.writeString((original / "bytes").toNioPath, "original")
        }
        client = new Client[IO]:
          def acquire(request: Client.Request, work: Path)(using
              Raise[IO, Client.Error]
          ): IO[Client.Verified] = request.source match
            case Client.Source.Cached(copied) =>
              IO.blocking {
                assert(copied != original);
                Files.writeString((copied / "bytes").toNioPath, "changed"); ()
              } *>
                Client.Error.RefusedVerification.raise[IO, Client.Verified]
            case _ => IO.raiseError(new AssertionError("expected owned cache"))
        result <- attempt(Bootstrap.run(config(root, Client.Source.Cached(original)), client))
        _ <- StorageTestKit.ok(DatabaseOwnership.resource[IO](root / "db").use(_ => IO.unit))
      yield
        assertEquals(result, Left(Bootstrap.Error.Client(Client.Error.RefusedVerification)))
        assertEquals(Files.readString((original / "bytes").toNioPath), "original")
        assert(!Files.exists((root / "db").toNioPath))
        emptyStage(root)
    }
  }

  test("symlinks and recursive source destinations are refused before verification") {
    StorageTestKit.tempDir.use { root =>
      val source = root / "source"
      for
        invoked <- IO.ref(false)
        _ <- IO.blocking {
          Files.createDirectory(source.toNioPath);
          Files.createSymbolicLink((source / "link").toNioPath, root.toNioPath); ()
        }
        symlink <- attempt(
          Bootstrap.run(config(root, Client.Source.Cached(source)), refusing(invoked.set(true)))
        )
        overlap <- attempt(
          Bootstrap.run(config(root, Client.Source.Cached(root)), refusing(invoked.set(true)))
        )
        touched <- invoked.get
      yield
        assert(symlink.left.exists(_.isInstanceOf[Bootstrap.Error.UnsafeSource]))
        assert(overlap.left.exists(_.isInstanceOf[Bootstrap.Error.UnsafeSource]))
        assert(!touched)
        emptyStage(root)
    }
  }

  test("overlap refusal leaves a read-only cache unchanged, including aliases and absent parents") {
    StorageTestKit.tempDir.use { root =>
      val source = root / "source"
      val alias = root / "alias"
      def inventory: IO[Vector[(String, Boolean, String, Vector[Byte])]] = IO.blocking {
        val paths = Files.walk(source.toNioPath)
        try
          paths.iterator().asScala.toVector.sortBy(_.toString).map { path =>
            val directory = Files.isDirectory(path)
            (
              source.toNioPath.relativize(path).toString,
              directory,
              java.nio.file.attribute.PosixFilePermissions
                .toString(Files.getPosixFilePermissions(path)),
              if directory then Vector.empty else Files.readAllBytes(path).toVector
            )
          }
        finally paths.close()
      }
      val restore = IO.blocking {
        Files.setPosixFilePermissions(
          source.toNioPath,
          java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
        )
        ()
      }
      (for
        invoked <- IO.ref(false)
        _ <- IO.blocking {
          Files.createDirectory(source.toNioPath)
          Files.writeString((source / "bytes").toNioPath, "original")
          Files.createSymbolicLink(alias.toNioPath, source.toNioPath)
          Files.setPosixFilePermissions(
            source.toNioPath,
            java.nio.file.attribute.PosixFilePermissions.fromString("r-x------")
          )
          ()
        }
        before <- inventory
        _ <- Vector(source / "db", alias / "missing/db").traverse_ { destination =>
          val requested = config(root, Client.Source.Cached(source)).copy(destination = destination)
          for
            result <- attempt(Bootstrap.run(requested, refusing(invoked.set(true))))
            after <- inventory
          yield
            assertEquals(result, Left(Bootstrap.Error.UnsafeSource(source.toString)))
            assertEquals(after, before)
        }
        touched <- invoked.get
      yield assert(!touched)).guarantee(restore)
    }
  }

  test("supported request and configuration networks must agree before acquiring ownership") {
    StorageTestKit.tempDir.use { root =>
      val base = config(root, Client.Source.Download)
      val requested = base.copy(request = base.request.copy(network = Client.Network.Preprod))
      for
        invoked <- IO.ref(false)
        result <- attempt(Bootstrap.run(requested, refusing(invoked.set(true))))
        touched <- invoked.get
        contents <- IO.blocking {
          val paths = Files.list(root.toNioPath)
          try paths.count()
          finally paths.close()
        }
      yield
        assertEquals(result, Left(Bootstrap.Error.NetworkMismatch(1L, 2L)))
        assert(!touched)
        assertEquals(contents, 0L)
    }
  }

  test("cancellation removes the owned stage and releases the database lease") {
    StorageTestKit.tempDir.use { root =>
      for
        started <- IO.deferred[Unit]
        client = new Client[IO]:
          def acquire(request: Client.Request, work: Path)(using
              Raise[IO, Client.Error]
          ): IO[Client.Verified] =
            started.complete(()).void *> IO.never
        fiber <- attempt(Bootstrap.run(config(root, Client.Source.Download), client)).start
        _ <- started.get.timeout(5.seconds)
        _ <- fiber.cancel
        result <- fiber.join
        _ <- StorageTestKit.ok(DatabaseOwnership.resource[IO](root / "db").use(_ => IO.unit))
      yield
        assert(result.isInstanceOf[Outcome.Canceled[?, ?, ?]])
        assert(!Files.exists((root / "db").toNioPath))
        emptyStage(root)
    }
  }

  test("published diagnostic provenance declares version one without certifying ancillary files") {
    StorageTestKit.tempDir.use { root =>
      type F[A] = EitherT[IO, Bootstrap.Error, A]
      val stage = root / "stage"
      val destination = root / "published"
      val verified = Client.Verified(stage, id, Client.Network.Preview, id, Bytes.empty)
      val ledger = LedgerSnapshotImport.Report(
        BlockRef(1, Bytes.empty, 1),
        Era.Conway,
        1L,
        1,
        LedgerSnapshotImport.Evidence.Unverified
      )
      val evidence = Bootstrap.provenance(config(root, Client.Source.Download), verified, ledger)
      val sync = new Bootstrap.DirectorySync[F]:
        def apply(path: Path): F[Unit] = EitherT.rightT(())
      for
        _ <- IO.blocking {
          Files.createDirectory(stage.toNioPath)
          Files.writeString((stage / "mithril-bootstrap.json").toNioPath, evidence.noSpaces)
          ()
        }
        result <- Bootstrap.publish[F](stage, destination, sync).value
        published <- IO.blocking {
          io.circe.parser
            .parse(Files.readString((destination / "mithril-bootstrap.json").toNioPath))
            .fold(e => fail(e.message), identity)
        }
      yield
        assertEquals(result, Right(()))
        assertEquals(published.hcursor.get[Int]("version"), Right(1))
        assertEquals(published.hcursor.get[String]("artifact"), Right(id.text))
        assertEquals(published.hcursor.get[Boolean]("ancillaryFilesMithrilCertified"), Right(false))
        assert(!Files.exists(stage.toNioPath))
    }
  }

  test("a typed late directory-sync failure rolls publication back under EitherT") {
    StorageTestKit.tempDir.use { root =>
      type F[A] = EitherT[IO, Bootstrap.Error, A]
      val stage = root / "stage"
      val destination = root / "published"
      val primary = Bootstrap.Error.Storage(StorageError.Io("parent", "injected sync failure"))
      val sync = new Bootstrap.DirectorySync[F]:
        def apply(path: Path): F[Unit] =
          if path == stage then EitherT.rightT(()) else EitherT.leftT(primary)
      for
        _ <- IO.blocking {
          Files.createDirectory(stage.toNioPath);
          Files.writeString((stage / "state").toNioPath, "owned"); ()
        }
        result <- Bootstrap.publish[F](stage, destination, sync).value
      yield
        assertEquals(result, Left(primary))
        assert(!Files.exists(destination.toNioPath))
        assert(!Files.exists(stage.toNioPath))
    }
  }

  test("a real denied rollback preserves the primary typed persistence error") {
    StorageTestKit.tempDir.use { root =>
      type F[A] = EitherT[IO, Bootstrap.Error, A]
      val stage = root / "stage"
      val destination = root / "published"
      val primary = Bootstrap.Error.Storage(StorageError.Io("parent", "injected sync failure"))
      val sync = new Bootstrap.DirectorySync[F]:
        def apply(path: Path): F[Unit] =
          if path == stage then EitherT.rightT(())
          else
            EitherT.liftF[IO, Bootstrap.Error, Unit](IO.blocking {
              Files.setPosixFilePermissions(
                destination.toNioPath,
                java.nio.file.attribute.PosixFilePermissions.fromString("r-x------")
              )
              ()
            }) *> EitherT.leftT(primary)
      for
        _ <- IO.blocking {
          Files.createDirectory(stage.toNioPath);
          Files.writeString((stage / "state").toNioPath, "owned"); ()
        }
        result <- Bootstrap
          .publish[F](stage, destination, sync)
          .value
          .guarantee(IO.blocking {
            Files.setPosixFilePermissions(
              destination.toNioPath,
              java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
            )
            ()
          })
      yield
        assertEquals(result, Left(primary))
        assertEquals(Files.readString((destination / "state").toNioPath), "owned")
    }
  }

  test("a real unreadable cached input fails in the typed channel and removes staging") {
    StorageTestKit.tempDir.use { root =>
      val source = root / "source"
      for
        _ <- IO.blocking {
          Files.createDirectory(source.toNioPath)
          Files.writeString((source / "unreadable").toNioPath, "original")
          Files.setPosixFilePermissions(
            (source / "unreadable").toNioPath,
            java.nio.file.attribute.PosixFilePermissions.fromString("---------")
          )
          ()
        }
        result <- attempt(
          Bootstrap.run(config(root, Client.Source.Cached(source)), refusing(IO.unit))
        )
      yield
        assert(result.left.exists(_.isInstanceOf[Bootstrap.Error.Storage]))
        assert(!Files.exists((root / "db").toNioPath))
        emptyStage(root)
    }
  }
