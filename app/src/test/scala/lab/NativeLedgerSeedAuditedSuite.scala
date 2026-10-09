// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point

/** Opt-in immutable audited originals; no native execution or admitted seed. */
class NativeLedgerSeedAuditedSuite extends munit.FunSuite:
  private def get[A](e: Either[?, A]): A = e.fold(x => fail(x.toString), identity)
  private def sha(b: Bytes) = ClusterHeaderObservation.sha256(b)
  private val expectedPins = Map(
    "capture.json" -> "fd980008d558df25fe5ef2c25608ff7a6c8fa9bbc2c6635b5a8d07d45049264d",
    "derived-full-epoch-seed.cbor" -> "38d8d8fd63e5f92e59ebb8bbdc687029a91435473832a420a1ce79f3cac9e493",
    "effective-shelley-genesis.json" -> "c9c11cde61bdd2536c5a328395ba4f873554ba018d9675297e5329a09e9294a9",
    "native-projection.json" -> "19bff89f7dfd13f54312ba3cb6dfcad46ebb8b69d347221df0d2965948b49dc7",
    "original-debug-epoch.cbor" -> "cb5cc3ae0880788f11d61c2743a6a94a96a391a1c10d83d99938440d82cfbb66",
    "original-whole-utxo.cbor" -> "60491cfb55b475224eec61038d6e0e8e5b2cd4add6c2e6d119bc5d11b09a68da",
    "request.json" -> "848904b0a6c7bef738b72ac8a7f869f5528756f2e327685fb90eae10fb61a4fe"
  )
  sys.env.get("NATIVE_COMPONENTS_BUNDLE").foreach { root =>
    def originals = NativeEpochComponents.Names.map { name =>
      val stream = Files.newInputStream(Path.of(root).resolve(name))
      val bytes =
        try stream.readNBytes(524289)
        finally stream.close()
      assert(bytes.length <= 524288)
      val value = Bytes.fromArray(bytes)
      assertEquals(sha(value).hex, expectedPins(name), name)
      name -> value
    }.toMap
    val anchor = Point(
      get(Bytes.fromHex("20ff3cd11037c1d5393915aacf08fd1a0b3441b6e8e045d03b2595a808b289fe")),
      36,
      1
    )
    def components =
      val m = originals
      get(NativeEpochComponents.decode(m, m.map((k, v) => k -> sha(v)), anchor))
    def governance(e: NativeEpochComponents.Checked) =
      val c = e.components.filter((k, _) => NativeGovernanceComponents.ComponentNames(k))
      get(
        NativeGovernanceComponents.decode(
          e.originals("derived-full-epoch-seed.cbor"),
          e.pins("derived-full-epoch-seed.cbor"),
          e.originals("original-debug-epoch.cbor"),
          e.pins("original-debug-epoch.cbor"),
          c,
          c.map((k, v) => k -> sha(v))
        )
      )
    def globals(e: NativeEpochComponents.Checked) =
      val p = e.parameters
      get(GovernanceGlobals.bind(p, p.bindingId, p.epoch, p.pointSlot, p.networkMagic))

    test("audited bundle joins exact originals and reports only concrete crossing blockers") {
      val e = components
      assertEquals(
        e.pins("derived-full-epoch-seed.cbor").hex,
        "38d8d8fd63e5f92e59ebb8bbdc687029a91435473832a420a1ce79f3cac9e493"
      )
      val g = governance(e)
      val joined = get(NativeLedgerSeed.bind(e, g, globals(e), anchor, e.id))
      assertEquals(
        joined.crossingBlockers,
        Set(
          NativeLedgerSeed.Blocker.MissingPointBoundProtocolV2,
          NativeLedgerSeed.Blocker.IncompatibleCrossingGeometry
        )
      )
      assert(joined.ledgerSideJoinChecked)
      assert(
        !joined.runtimeImport && !joined.rewardSeedAdmission && !joined.authenticatedSnapshot &&
          !joined.actualAcquisitionVerified && !joined.fullLedgerValidated && !joined.nativeConformance &&
          !joined.fullParameterValidity && !joined.liveGovernanceCursorRecoverable
      )
      assertEquals(joined.sourceId, e.id)
      assertEquals(joined.point, anchor)
      assertEquals(e.parameters.globals.epochLength, BigInt(500))
      assertEquals(e.parameters.randomnessWindow, BigInt(400))
      assertEquals(g.accounts.size, 3)
      assertEquals(g.dreps.size, 3)
      assertEquals(e.stake.context.pools.size, 2)
      assertEquals(e.utxoCoin + e.pots.reserves, e.pots.maxSupply)
      assertEquals(e.stake.snapshots.mark.total, BigInt("45000009000000"))
      assertEquals(e.reward.original.hex, "80")
      assertEquals(e.nonMyopic.rewardPot, BigInt(0))
      assert(e.nonMyopic.likelihoods.isEmpty)
      assertEquals(joined.parameterRoles.current.original, g.currentParameters)
      assertEquals(joined.parameterRoles.previous.original, g.previousParameters)
      assertEquals(joined.historicalCurrent.original, g.previousParameters)
      assertEquals(joined.historicalPrevious.original, g.previousParameters)
      assertNotEquals(g.currentParameters, g.previousParameters)
    }

    test("audited join rejects independently valid governance from another whole-source identity") {
      val e = components
      val c = e.components.filter((k, _) => NativeGovernanceComponents.ComponentNames(k))
      // Change only the top-level epoch byte in both originals. Governance decoding succeeds
      // with freshly valid hashes and identical component spans, but the whole source differs.
      def nextEpoch(b: Bytes): Bytes =
        assertEquals(b.value(0), 0x87.toByte)
        assertEquals(b.value(1), 0.toByte)
        Bytes(b.value.updated(1, 1.toByte))
      val seed = nextEpoch(e.originals("derived-full-epoch-seed.cbor"))
      val epoch = nextEpoch(e.originals("original-debug-epoch.cbor"))
      val foreign = get(
        NativeGovernanceComponents
          .decode(seed, sha(seed), epoch, sha(epoch), c, c.map((k, v) => k -> sha(v)))
      )
      assertEquals(foreign.epoch, BigInt(1))
      assert(NativeLedgerSeed.bind(e, foreign, globals(e), anchor, e.id).isLeft)
      assert(
        NativeLedgerSeed.bind(e, governance(e), globals(e), anchor.copy(blockNo = 2), e.id).isLeft
      )
      assert(NativeLedgerSeed.bind(e, governance(e), globals(e), anchor, sha(Bytes.empty)).isLeft)
    }
  }
