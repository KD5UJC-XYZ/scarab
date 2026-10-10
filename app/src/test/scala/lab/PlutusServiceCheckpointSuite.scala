// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Resource, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, LinkOption, Path}
import lab.cbor.Bytes
import lab.header.{PraosCertificateState as Certificate}
import lab.submission.AdmissionProfile
import lab.network.ChainSync
import scala.concurrent.duration.*

class PlutusServiceCheckpointSuite extends munit.FunSuite:
  import CoherentStakeImages.*
  import NativeLiveBoundaryMain.{hash, obj, read, sha}
  import ReferenceJson.{field, string, uint}
  import PlutusResearchIO.{record, text}
  private val P = PlutusServiceCheckpoint
  private val R = RestrictedRuntimeCheckpoint
  private def get[A](e: Either[?, A]): A = e.fold(x => fail(x.toString), a => a)
  private val dummy = Bytes(Vector.fill(32)(1.toByte))
  private val identity = P.Identity(dummy, sha(dummy), 4)
  private val exportMode = P.Mode(Some(1), identity, None)
  private def temporary =
    Resource.make(IO.blocking(Files.createTempDirectory("service-restore-test")))(p =>
      IO.blocking {
        val stream = Files.walk(p)
        try
          stream
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(x => { Files.deleteIfExists(x); () })
        finally stream.close()
      }
    )
  private def authority(c: R.Claim) = EvidenceJson.encode(
    record(
      "schema" -> text("plutus-service-restore-authority-v1"),
      "decision" -> text("accept"),
      "claim" -> P.claim(c)
    )
  )
  test("checkpoint options are opt-in, closed groups with canonical independent identities") {
    val base =
      Map("--store-id" -> dummy.hex, "--session-id" -> sha(dummy).hex, "--generation" -> "4")
    val output = Path.of("/private/new")
    def parse(v: Map[String, String]) = P.options(v, 8, output)
    assertEquals(get(parse(Map.empty)), None)
    assertEquals(get(parse(base + ("--checkpoint-after" -> "1"))).get, exportMode)
    val restore = base ++ Map(
      "--restore-checkpoint" -> "/private/old/checkpoint.bin",
      "--restore-authority" -> "/private/controller/accept.json",
      "--restore-authority-sha256" -> dummy.hex
    )
    assert(get(parse(restore)).get.restore.nonEmpty)
    Vector(
      base,
      base + ("--checkpoint-after" -> "9"),
      base + ("--checkpoint-after" -> "01"),
      restore - "--restore-authority-sha256",
      restore.updated("--restore-checkpoint", "/private/new/checkpoint.bin"),
      restore.updated("--restore-authority", "/private/old/checkpoint.bin"),
      restore.updated("--generation", "04"),
      restore.updated("--session-id", "00"),
      restore.updated("--generation", Long.MaxValue.toString) + ("--checkpoint-after" -> "1")
    ).foreach(v => assert(parse(v).isLeft))
    assert(P.options(base + ("--checkpoint-after" -> "2"), 1, output).isLeft)
    val common = List(
      "--profile",
      AdmissionProfile.PlutusV3.id,
      "--initial",
      "/private/initial",
      "--manifest-sha256",
      dummy.hex,
      "--port",
      "12345",
      "--magic",
      "991122",
      "--output",
      output.toString,
      "--duration-seconds",
      "60",
      "--max-blocks",
      "8"
    )
    assertEquals(get(PlutusServiceCommand.options(common)).checkpoint, None)
    val both = (restore + ("--checkpoint-after" -> "1")).toList.flatMap((k, v) => List(k, v))
    val parsed = get(PlutusServiceCommand.options(common ++ both)).checkpoint.get
    assertEquals(parsed.exportGeneration, 5L)
    assert(PlutusServiceCommand.options(common ++ both ++ List("--unknown", "x")).isLeft)
  }
  test("controller decision is pinned exact claim and configured store/session/generation") {
    val point = Certificate.Point(dummy, BigInt(1), BigInt(1))
    val c = R.Claim(
      identity.storeId,
      identity.sessionId,
      identity.generation,
      dummy,
      dummy,
      dummy,
      dummy,
      point,
      point,
      dummy,
      BigInt(0),
      8,
      0
    )
    val raw = authority(c)
    assert(P.authority(raw, sha(raw), identity, c).isRight)
    assert(P.authority(raw, dummy, identity, c).isLeft)
    Vector(
      c.copy(storeId = sha(dummy)),
      c.copy(sessionId = dummy),
      c.copy(generation = 5),
      c.copy(publicationSHA256 = sha(dummy)),
      c.copy(sourceJoinId = sha(dummy))
    ).foreach { altered =>
      assert(P.authority(raw, sha(raw), identity, altered).isLeft)
    }
    assert(P.authority(raw, sha(raw), identity.copy(generation = 5), c).isLeft)
    val request = EvidenceJson.encode(
      record(
        "schema" -> text("plutus-service-checkpoint-publication-v1"),
        "decision" -> text("accept"),
        "claim" -> P.claim(c)
      )
    )
    assert(P.authority(request, sha(request), identity, c).isLeft)
  }
  test("individual byte publication refuses overwrite and cleans temporary files") {
    temporary
      .use { root =>
        for
          _ <- P.publishBytes(root.resolve("checkpoint.bin"), dummy, 32)
          duplicate <- P.publishBytes(root.resolve("checkpoint.bin"), sha(dummy), 32).attempt
          oversized <- P.publishBytes(root.resolve("oversized"), dummy, 31).attempt
          _ = assert(duplicate.isLeft && oversized.isLeft)
          _ = assertEquals(
            Bytes.fromArray(Files.readAllBytes(root.resolve("checkpoint.bin"))),
            dummy
          )
          _ <- IO.blocking {
            val files = Files.list(root)
            try assertEquals(files.count(), 1L)
            finally files.close()
          }
        yield ()
      }
      .unsafeToFuture()
  }
  sys.env.get("PLUTUS_LIVE_BUNDLE").foreach { directory =>
    val root = Path.of(directory).toAbsolutePath.normalize()
    lazy val pinned: Map[String, Bytes] =
      val manifest =
        read(Path.of(sys.env.getOrElse("PLUTUS_LIVE_MANIFEST", fail("manifest required"))), 65536)
      assertEquals(
        sha(manifest).hex,
        sys.env.getOrElse("PLUTUS_LIVE_MANIFEST_SHA256", fail("independent manifest pin required"))
      )
      val parsed = ReferenceJson.parse(manifest)
      assertEquals(string(field(parsed, "schema")), "plutus-retained-live-inputs-v1")
      val rows = obj(field(parsed, "inputs"))
      assert(rows.nonEmpty && rows.size <= 128)
      rows.map { (name, row) =>
        val relative = Path.of(name)
        assert(
          !relative.isAbsolute && !name.contains("\\") && relative.normalize().toString == name
        )
        val path = root.resolve(relative).normalize()
        assert(
          path.startsWith(root) && path != root && Files
            .isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        )
        assert(path.toRealPath().startsWith(root.toRealPath()))
        val bytes = read(path, 1048576)
        assertEquals(BigInt(bytes.size), uint(field(row, "bytes")), name)
        assertEquals(sha(bytes), hash(field(row, "sha256")), name)
        name -> bytes
      }
    def json(name: String) =
      ReferenceJson.parse(pinned.getOrElse(name, fail("unpinned input: " + name)))
    lazy val joined =
      val inputs = json("initial/adapter-inputs.json")
      obj(field(inputs, "inputs")).keys.foreach(n => assert(pinned.contains("initial/" + n)))
      NativeLiveBoundaryMain.initial(
        root.resolve("initial"),
        sha(pinned("initial/adapter-inputs.json")).hex,
        AdmissionProfile.PlutusV3
      )
    lazy val blocks =
      val names = pinned.keys.filter(_.matches("originals/block-[0-9]{4}\\.cbor")).toVector.sorted
      assert(names.nonEmpty && names.size <= 16)
      names.zipWithIndex.map { (name, index) =>
        assertEquals(name, f"originals/block-$index%04d.cbor")
        get(
          SequenceInput.block(
            BoundedChainFollower
              .Original(pinned(name.stripSuffix(".cbor") + "-header.cbor"), pinned(name))
          )
        )
      }

    lazy val context = get(SequenceInput.fromNativeDiagnostic(joined, joined.id))
    lazy val sources = Sources(
      joined.ledger.epochComponents.parameters.genesisOriginal,
      joined.ledger.epochComponents.parameters.current.original,
      pinned("initial/adapter-inputs.json")
    )
    lazy val binding = SourceBinding(
      context.id,
      joined.id,
      context.stakeSourceId,
      sha(sources.genesis),
      sha(sources.parameters),
      sha(sources.manifest)
    )
    def runtime = CoherentSequence
      .createPlutusDiagnosticWithStake[IO](context, joined.ledger.epochComponents.stake)
      .map(get(_))
    def publish(r: CoherentSequence.Runtime[IO], block: SequenceInput.Block) =
      r.prepare(block).map(get(_)).flatMap(c => r.publish(c).map(get(_))).void

    def checked = for
      r <- runtime
      _ <- publish(r, blocks.head)
      snapshot <- r.snapshot
    yield snapshot
    test("checkpoint publication is individually atomic and incomplete pair grants no authority") {
      temporary
        .use { target =>
          for
            snapshot <- checked
            failed <- P
              .saveObserved(
                target,
                snapshot,
                joined,
                sources.manifest,
                exportMode,
                IO.raiseError(new RuntimeException("between files"))
              )
              .attempt
            _ = assert(failed.isLeft)
            _ = assert(Files.exists(target.resolve("checkpoint.bin")))
            _ = assert(!Files.exists(target.resolve("checkpoint-request.json")))
            original <- IO.blocking(
              Bytes.fromArray(Files.readAllBytes(target.resolve("checkpoint.bin")))
            )
            _ = assert(!get(R.decode(original)).restoreAuthorized)
            retry <- P.save(target, snapshot, joined, sources.manifest, exportMode).attempt
            _ = assert(retry.isLeft)
            _ = assertEquals(
              Bytes.fromArray(Files.readAllBytes(target.resolve("checkpoint.bin"))),
              original
            )
          yield ()
        }
        .unsafeToFuture()
    }
    test(
      "explicit accepted publication restores new owner empty pool then permits checked continuation"
    ) {
      temporary
        .use { target =>
          for
            snapshot <- checked
            saved <- P.save(target, snapshot, joined, sources.manifest, exportMode)
            request <- IO(
              ReferenceJson.parse(
                Bytes.fromArray(Files.readAllBytes(target.resolve("checkpoint-request.json")))
              )
            )
            _ = assertEquals(field(request, "restoreAuthorized"), ReferenceJson.Json.Lit("false"))
            _ = assertEquals(field(request, "claim"), P.claim(saved.publication.claim))
            raw = authority(saved.publication.claim)
            auth = target.resolve("accepted.json")
            _ <- P.publishBytes(auth, raw, 65536)
            mode = P.Mode(
              None,
              identity,
              Some(P.Restore(target.resolve("checkpoint.bin"), auth, sha(raw)))
            )
            started <- P.start(Some(mode), joined, sha(sources.manifest))
            second <- P.start(Some(mode), joined, sha(sources.manifest))
            _ = assert(started.restored)
            _ = assertEquals(
              started.snapshot.state.ledger.outputMap,
              snapshot.state.ledger.outputMap
            )
            _ = assertNotEquals(
              started.snapshot.state.ledger.checkpointId,
              snapshot.state.ledger.checkpointId
            )
            _ = assertNotEquals(
              started.snapshot.state.ledger.checkpointId,
              second.snapshot.state.ledger.checkpointId
            )
            _ <- PlutusResearchNode
              .resource[IO](
                IO.pure(started.runtime),
                target.resolve("evaluation"),
                joined.id,
                sha(sources.manifest)
              )
              .use { node =>
                for
                  _ <- node.owner.attach(node.service)
                  pool <- node.service.snapshot
                  _ = assert(
                    pool.size == 0 && pool.byteSize == 0 && pool.eligible.isEmpty && !pool.closed
                  )
                  _ = assertEquals(pool.pin.point, started.snapshot.state.certificates.state.tip)
                  _ = assertEquals(blocks.size, 2)
                  initial = started.snapshot.state.certificates.state.tip
                  wire = ChainSync.Point.Block(
                    get(ChainSync.UInt64.from(initial.slot)),
                    initial.hash
                  )
                  released <- Ref.of[IO, Int](0)
                  pulls <- Ref.of[IO, Int](0)
                  peer = new BoundedChainFollower.Peer[IO]:
                    def intersect(points: Vector[ChainSync.Point]) = IO {
                      assertEquals(points, Vector(wire)); wire
                    }
                    def next = pulls.getAndUpdate(_ + 1).flatMap { n =>
                      if n == 0 then
                        IO.pure(
                          BoundedChainFollower.Event
                            .Forward(pinned("originals/block-0001-header.cbor"))
                        )
                      else IO.raiseError(new AssertionError("unexpected second pull"))
                    }
                    def fetch(point: ChainSync.Point) = IO.pure(pinned("originals/block-0001.cbor"))
                  result <- PlutusSameEpochFollow.runUntil(
                    node.owner,
                    Resource.make(IO.pure(peer: BoundedChainFollower.Peer[IO]))(_ =>
                      released.update(_ + 1)
                    ),
                    initial,
                    1000,
                    EphemeralStreaming.Limits(maxBlocks = 1, duration = 5.seconds)
                  )(node.owner.snapshot.map(_.state.depth == 2))(_ => IO.unit)
                  closes <- released.get
                  _ = assertEquals(closes, 1)
                  _ = assertEquals(result.snapshot.state.depth, BigInt(2))
                  _ = assertEquals(
                    result.snapshot.state.certificates.state.tip.hash,
                    blocks.last.header.hash
                  )
                yield ()
              }
            // Any later captured originals continue on the same restored lineage.
            _ <- blocks.drop(1).traverse_(publish(second.runtime, _))
            after <- second.runtime.snapshot
            _ = assertEquals(after.state.depth, BigInt(blocks.size))
            narrow = get(
              R.encode(
                snapshot,
                context,
                sources,
                binding,
                identity.storeId,
                identity.sessionId,
                identity.generation,
                1
              )
            )
            narrowAuthority = authority(narrow.claim)
            _ <- P.publishBytes(target.resolve("narrow.bin"), narrow.bytes, R.MaxBytes)
            _ <- P.publishBytes(target.resolve("narrow-accepted.json"), narrowAuthority, 65536)
            narrowMode = P.Mode(
              Some(1),
              identity,
              Some(
                P.Restore(
                  target.resolve("narrow.bin"),
                  target.resolve("narrow-accepted.json"),
                  sha(narrowAuthority)
                )
              )
            )
            capacityFailure <- P.start(Some(narrowMode), joined, sha(sources.manifest)).attempt
            _ = assert(capacityFailure.isLeft)
            wrong <- P
              .start(
                Some(mode.copy(identity = identity.copy(generation = 5))),
                joined,
                sha(sources.manifest)
              )
              .attempt
            _ = assert(wrong.isLeft)
            exceeded <- P
              .start(Some(mode.copy(checkpointAfter = Some(8))), joined, sha(sources.manifest))
              .attempt
            _ = assert(exceeded.isLeft)
          yield ()
        }
        .unsafeToFuture()
    }
  }
