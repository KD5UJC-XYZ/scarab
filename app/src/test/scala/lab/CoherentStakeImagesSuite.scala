// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, LinkOption, Path}
import lab.cbor.Bytes
import lab.submission.AdmissionProfile

class CoherentStakeImagesSuite extends munit.FunSuite:
  import CoherentStakeImages.*
  import NativeLiveBoundaryMain.{hash, obj, read, sha}
  import ReferenceJson.{field, string, uint}
  private def get[A](e: Either[?, A]): A = e.fold(x => fail(x.toString), identity)
  private val dummy = Bytes(Vector.fill(32)(1.toByte))
  private val dummySources = Sources(dummy, dummy, dummy)
  private val dummyBinding = SourceBinding(dummy, dummy, dummy, dummy, dummy, dummy)
  test("synthetic rewards and full boundary compositions refuse partial export before encoding") {
    Vector(
      SyntheticBoundaryCompositionFixture.runtime,
      SyntheticBoundaryCompositionFixture.boundaryRuntime
    )
      .traverse_ { create =>
        for
          runtime <- create
          snapshot <- runtime.snapshot
          _ = assert(!snapshot.state.supportsRestrictedImageExport)
          result = encodeSnapshot(
            snapshot,
            SyntheticBoundaryCompositionFixture.context,
            dummySources,
            dummyBinding
          )
          _ = assert(result.swap.toOption.get.contains("reward/epoch/boundary"))
        yield ()
      }
      .unsafeToFuture()
  }
  test("ordinary non-Plutus source context cannot acquire restricted image export capability") {
    (for
      runtime <- CoherentSequence
        .createWithStake[IO](
          SyntheticBoundaryCompositionFixture.context,
          SyntheticBoundaryCompositionFixture.stakeSeed
        )
        .map(get(_))
      snapshot <- runtime.snapshot
      _ = assert(snapshot.state.supportsRestrictedImageExport)
      _ = assert(
        encodeSnapshot(
          snapshot,
          SyntheticBoundaryCompositionFixture.context,
          dummySources,
          dummyBinding
        ).isLeft
      )
    yield ()).unsafeToFuture()
  }
  test("untrusted expected shape rejects before descriptor formatting or source access") {
    import lab.ledger.storage.{RestrictedLedgerImage as L, RestrictedStakeImage as S}
    val point = L.Point(1, 0, dummy)
    val ledger = L.Binding(
      lab.plutus.PlutusExecution.ProfileId,
      42,
      0,
      point,
      dummy,
      Map("genesis" -> dummy, "parameters" -> dummy, "manifest" -> dummy)
    )
    val stake = S.Binding(dummy, dummy, dummy, 1, 0, dummy)
    val e = Expected(dummyBinding, ledger, stake, dummy, 0, dummy, dummy, None, dummy, dummy)
    val variants = Vector(
      e.copy(stateId = Bytes(Vector.fill(4097)(1.toByte))),
      e.copy(ledger = ledger.copy(profile = "x" * 4097)),
      e.copy(revision = BigInt(1) << 65536),
      e.copy(ledger = ledger.copy(point = point.copy(slot = 1000))),
      e.copy(stake = stake.copy(blockNo = BigInt(1) << 65)),
      e.copy(ledger = ledger.copy(sourcePins = Map.empty)),
      e.copy(eligibilityId = null)
    )
    variants.foreach { v =>
      val error =
        CoherentStakeImages.verify(Bytes.empty, Bytes.empty, v, dummy, null).swap.toOption.get
      assert(error.contains("expected shape") || error.contains("32-byte binding"), error)
    }
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
    def verify(p: Publication) =
      CoherentStakeImages.verify(p.ledger, p.stake, p.expected, p.publicationSHA256, context)
    test(
      "one immutable snapshot exports cross-bound pairs before and after actual checked publication"
    ) {
      (for
        r <- runtime
        before <- r.snapshot
        first = get(encodeSnapshot(before, context, sources, binding))
        _ <- blocks.traverse_(publish(r, _))
        after <- r.snapshot
        second = get(encodeSnapshot(after, context, sources, binding))
        historical = get(encodeSnapshot(before, context, sources, binding))
        _ = assertEquals(first.publicationSHA256, historical.publicationSHA256)
        _ = assertEquals(first.ledger, historical.ledger)
        _ = assertNotEquals(first.publicationSHA256, second.publicationSHA256)
        _ = assertNotEquals(before.state.ledger.outputMap, after.state.ledger.outputMap)
        _ = assertEquals(
          second.expected.ledger.point.blockNo,
          after.state.certificates.state.tip.blockNo
        )
        _ = assertEquals(second.expected.stake.ledgerImageSHA256, sha(second.ledger))
        checked = get(verify(second))
        _ = assertEquals(checked.ledger.originalUtxo, after.state.ledger.outputMap)
        _ = assertEquals(checked.ledger.fees, after.state.ledger.fees)
        _ = assert(
          !checked.restoreAuthorized && !checked.completeValidatorState && !checked.currentOwnerAuthorized
        )
        _ = assert(
          CoherentStakeImages
            .verify(first.ledger, second.stake, second.expected, second.publicationSHA256, context)
            .isLeft
        )
        _ = assert(
          CoherentStakeImages
            .verify(second.ledger, first.stake, second.expected, second.publicationSHA256, context)
            .isLeft
        )
      yield ()).unsafeToFuture()
    }
    test(
      "independent descriptor binds complete point and source metadata, not only image checksums"
    ) {
      (for
        r <- runtime
        before <- r.snapshot
        p = get(encodeSnapshot(before, context, sources, binding))
        e = p.expected
        variants = Vector(
          e.copy(ledger =
            e.ledger.copy(point = e.ledger.point.copy(blockNo = e.ledger.point.blockNo + 1))
          ),
          e.copy(stake = e.stake.copy(headerHash = dummy)),
          e.copy(source = e.source.copy(sourceJoinId = dummy)),
          e.copy(source = e.source.copy(manifestSHA256 = dummy)),
          e.copy(stateId = dummy),
          e.copy(revision = e.revision + 1)
        )
        _ = variants.foreach(v =>
          assert(
            CoherentStakeImages.verify(p.ledger, p.stake, v, p.publicationSHA256, context).isLeft
          )
        )
        _ = assert(encodeSnapshot(before, context, sources, binding.copy(contextId = dummy)).isLeft)
        _ = assert(encodeSnapshot(before, context, sources.copy(manifest = dummy), binding).isLeft)
        _ = assert(CoherentStakeImages.verify(p.ledger, p.stake, e, dummy, context).isLeft)
      yield ()).unsafeToFuture()
    }
    test(
      "component verification grants no owner authority; fresh ordinary construction rejects old fence"
    ) {
      (for
        old <- runtime
        snapshot <- old.snapshot
        p = get(encodeSnapshot(snapshot, context, sources, binding))
        _ = assert(verify(p).isRight)
        fresh <- runtime
        before <- fresh.snapshot
        result <- fresh.rollbackTo(snapshot.fence, before.state.acquisition.tip)
        after <- fresh.snapshot
        _ = assertEquals(result, Left(CoherentSequence.Failure.ForeignFence))
        _ = assertEquals(after.state.id, before.state.id)
        _ = assertEquals(after.state.revision, before.state.revision)
        // This is independent checked construction, not reconstruction from the image pair.
        refused <- old.exportLocalCheckpoint(dummy, dummy, 0)
        _ = assert(refused.isLeft)
      yield ()).unsafeToFuture()
    }
  }
