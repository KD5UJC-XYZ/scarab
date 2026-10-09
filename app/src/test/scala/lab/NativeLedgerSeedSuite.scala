// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.header.PraosCertificateState.Point
import lab.ledger.ConwayEmptyGovernance as G
import ReferenceJson.Json as J

/** Entirely generated bounded native-shaped inputs; no retained capture or native oracle. */
class NativeLedgerSeedSuite extends munit.FunSuite:
  private def get[A](v: Either[?, A]): A = v.fold(e => fail(e.toString), identity)
  private def n(v: V): Node = Node(v, Bytes.empty)
  private def a(vs: V*): V = V.Arr(vs.toVector.map(n))
  private def m(rows: (V, V)*): V = V.Map(rows.toVector.map((k, v) => n(k) -> n(v)))
  private def set(vs: V*): V = V.Tag(258, n(a(vs*)))
  private def bytes(size: Int, value: Int): Bytes = Bytes(Vector.fill(size)(value.toByte))
  private def b(size: Int, value: Int): V = V.ByteString(bytes(size, value))
  private def credential(value: Int): V = a(V.UInt(0), b(28, value))
  private def ratio(num: BigInt, den: BigInt): V = V.Tag(30, n(a(V.UInt(num), V.UInt(den))))
  private def raw(v: V): Bytes = get(Cbor.encode(v))
  private def sha(v: Bytes): Bytes = ClusterHeaderObservation.sha256(v)
  private def text(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private def json(j: J): Bytes = SyntheticRewardProjection.encode(j)
  private def obj(fields: (String, J)*): J.Obj = J.Obj(fields.toMap)
  private def at(node: Node, path: Vector[Int]): Node = path.foldLeft(node) { (at, index) =>
    at.value.asInstanceOf[V.Arr].value(index)
  }
  private def change(v: V, path: Vector[Int], value: V): V =
    if path.isEmpty then value
    else
      val fields = v.asInstanceOf[V.Arr].value
      V.Arr(fields.updated(path.head, n(change(fields(path.head).value, path.tail, value))))
  private val paths = Map(
    "accounts" -> Vector(3, 1, 0, 2, 0),
    "certificateState" -> Vector(3, 1, 0),
    "chainAccountState" -> Vector(3, 0),
    "committee" -> Vector(3, 1, 1, 3, 1),
    "committeeState" -> Vector(3, 1, 0, 0, 1),
    "constitution" -> Vector(3, 1, 1, 3, 2),
    "currentBlocks" -> Vector(2),
    "currentParameters" -> Vector(3, 1, 1, 3, 3),
    "delegationState" -> Vector(3, 1, 0, 2),
    "deposits" -> Vector(3, 1, 1, 1),
    "donations" -> Vector(3, 1, 1, 5),
    "dormantEpochs" -> Vector(3, 1, 0, 0, 2),
    "drepPulsingState" -> Vector(3, 1, 1, 3, 6),
    "dreps" -> Vector(3, 1, 0, 0, 0),
    "fees" -> Vector(3, 1, 1, 2),
    "futureDelegations" -> Vector(3, 1, 0, 2, 1),
    "futureParameters" -> Vector(3, 1, 1, 3, 5),
    "futurePools" -> Vector(3, 1, 0, 1, 2),
    "goPools" -> Vector(3, 2, 2, 1),
    "goSnapshot" -> Vector(3, 2, 2),
    "governance" -> Vector(3, 1, 1, 3),
    "instantaneousRewards" -> Vector(3, 1, 0, 2, 3),
    "instantaneousStake" -> Vector(3, 1, 1, 4),
    "leadership" -> Vector(5),
    "nonMyopic" -> Vector(3, 3),
    "nonMyopicLikelihoods" -> Vector(3, 3, 0),
    "nonMyopicRewardPot" -> Vector(3, 3, 1),
    "pools" -> Vector(3, 1, 0, 1, 1),
    "previousBlocks" -> Vector(1),
    "previousParameters" -> Vector(3, 1, 1, 3, 4),
    "proposals" -> Vector(3, 1, 1, 3, 0),
    "retiringPools" -> Vector(3, 1, 0, 1, 3),
    "rewardState" -> Vector(4),
    "snapshots" -> Vector(3, 2),
    "votingState" -> Vector(3, 1, 0, 0)
  )
  private val baseParameters = get(Cbor.decode(NativeSeedParameterFixtures.previous)).value
  private val newModels = m(V.UInt(1) -> a(Vector.fill(175)(V.UInt(0))*))
  private final case class Bundle(
      epoch: NativeEpochComponents.Checked,
      governance: NativeGovernanceComponents.Checked,
      globals: GovernanceGlobals.Checked
  ):
    def join = NativeLedgerSeed.bind(epoch, governance, globals, epoch.point, epoch.id)

  private def bundle(
      voteOffset: Int = 0,
      poolVrf: Int = 3,
      coin: Int = 100,
      count: Int = 1,
      epochLength: Int = 1000,
      pointHash: Int = 1,
      slot: Int = 36,
      nonCostDifference: Boolean = false,
      wrongHistoricalPrevious: Boolean = false,
      malformedHistoricalCosts: Boolean = false
  ): Bundle =
    val previous = baseParameters
    val withCosts = change(previous, Vector(15), newModels)
    val current =
      if nonCostDifference then change(withCosts, Vector(13), V.UInt(170000001)) else withCosts
    val historicalCurrent =
      if malformedHistoricalCosts then change(previous, Vector(15), m(V.UInt(1) -> a(V.UInt(0))))
      else previous
    val historicalPrevious = if wrongHistoricalPrevious then current else previous
    val roots = a(a(), a(), a(), a())
    val committee = a(a(m(), ratio(0, 1)))
    val constitution = a(a(V.Text(""), b(32, 0)), V.Null)
    val completed = a(
      a(a(), m(), m(), m()),
      a(
        a(committee, constitution, historicalCurrent, historicalPrevious, V.UInt(0), m(), roots),
        a(),
        set(),
        V.Bool(false)
      )
    )
    val governance =
      a(a(roots, a()), committee, constitution, current, previous, a(V.UInt(0)), completed)
    val dreps = m(
      (1 to 3).map(i =>
        credential(i + voteOffset) -> a(V.UInt(1000), a(), V.UInt(0), set(credential(i + 10)))
      )*
    )
    val accounts = m(
      (1 to 3).map(i =>
        credential(i + 10) -> a(
          V.UInt(0),
          V.UInt(0),
          if i == 1 then b(28, 4) else V.Null,
          credential(i + voteOffset)
        )
      )*
    )
    val pool = a(
      b(32, poolVrf),
      V.UInt(0),
      V.UInt(0),
      ratio(0, 1),
      credential(11),
      set(b(28, 11)),
      a(),
      a(),
      V.UInt(0),
      set(credential(11))
    )
    val pstate = a(m(), m(b(28, 4) -> pool), m(), m())
    val cert =
      a(a(dreps, m(), V.UInt(0)), pstate, a(accounts, m(), m(), a(m(), m(), V.UInt(0), V.UInt(0))))
    val poolSnapshot = a(
      V.UInt(coin),
      ratio(1, 1),
      set(b(28, 11)),
      V.UInt(coin),
      b(32, poolVrf),
      V.UInt(0),
      V.UInt(0),
      ratio(0, 1),
      V.UInt(1),
      credential(11)
    )
    val mark = a(m(credential(11) -> a(V.UInt(coin), b(28, 4))), m(b(28, 4) -> poolSnapshot))
    val empty = a(m(), m())
    val snapshots = a(mark, empty, empty, V.UInt(0))
    // Native little-endian MemPack tag2 base address, key credentials and testnet.
    require(coin > 0 && coin < 128)
    val packedOutput = Bytes(
      Vector(2.toByte, 1.toByte) ++ bytes(28, 11).value ++ Vector.fill(24)(0.toByte) ++ Vector(
        1.toByte
      ) ++ Vector.fill(7)(0.toByte) ++ Vector(0.toByte, coin.toByte)
    )
    val packedKey = Bytes(bytes(32, 5).value ++ Vector(0.toByte, 0.toByte))
    val packedUtxo = m(V.ByteString(packedKey) -> V.ByteString(packedOutput))
    val address = Bytes(Vector(0.toByte) ++ Vector.fill(28)(0.toByte) ++ bytes(28, 11).value)
    val whole = raw(m(a(b(32, 5), V.UInt(0)) -> a(V.ByteString(address), V.UInt(coin))))
    val supply = BigInt("45000000000000000")
    val us =
      a(packedUtxo, V.UInt(0), V.UInt(0), governance, m(credential(11) -> V.UInt(coin)), V.UInt(0))
    val es = a(a(V.UInt(0), V.UInt(supply - coin)), a(cert, us), snapshots, a(m(), V.UInt(0)))
    val state = a(
      V.UInt(0),
      m(),
      m(b(28, 4) -> V.UInt(count)),
      es,
      a(),
      a(m(b(28, 4) -> a(ratio(1, 1), V.UInt(coin), b(32, poolVrf))), V.UInt(coin)),
      V.Null
    )
    val seed = raw(state)
    val originalEpoch = raw(change(state, Vector(3, 1, 1, 0), m()))
    val parsed = get(Cbor.decode(seed))
    val components =
      paths.map((name, path) => name -> at(parsed, path).original).updated("utxo", whole)
    val descriptors = J.Obj(components.map { (name, raw) =>
      name -> obj(
        "encoding" -> J.Str("native-encCBOR-pv9"),
        "cborHex" -> J.Str(raw.hex),
        "sha256" -> J.Str(sha(raw).hex)
      )
    })
    val projection = json(
      obj(
        "schema" -> J.Num("1"),
        "kind" -> J.Str("native-epoch-diagnostic-projection"),
        "epoch" -> J.Num("0"),
        "seedSHA256" -> J.Str(sha(seed).hex),
        "components" -> descriptors,
        "rewardPhase" -> J.Str("absent"),
        "rewardFreeze" -> obj(
          "status" -> J.Str("absent-no-frozen-capsule"),
          "components" -> J.Obj(Map.empty),
          "frozenGoSnapshotAvailable" -> J.Lit("false"),
          "fullOriginalFreezeInputsAvailable" -> J.Lit("false")
        )
      )
    )
    val point = Point(bytes(32, pointHash), slot, 1)
    val pointJson = obj("slot" -> J.Num(slot.toString), "hash" -> J.Str(point.hash.hex))
    val request = json(
      obj(
        "schema" -> J.Num("1"),
        "point" -> pointJson,
        "networkMagic" -> J.Num("1082026"),
        "ntcVersion" -> J.Num("16")
      )
    )
    val capture = json(
      obj(
        "schema" -> J.Num("1"),
        "kind" -> J.Str("single-acquire-native-payloads"),
        "requestedPoint" -> pointJson,
        "acquiredPoint" -> pointJson,
        "finalPoint" -> pointJson,
        "blockNo" -> J.Num("1"),
        "finalBlockNo" -> J.Num("1"),
        "acquireCount" -> J.Num("1"),
        "reacquireCount" -> J.Num("0"),
        "ntcVersion" -> J.Num("16"),
        "epochHex" -> J.Str(originalEpoch.hex),
        "utxoHex" -> J.Str(whole.hex)
      )
    )
    val genesis = text(
      new String(NativeSeedParameterFixtures.genesis.toArray, "UTF-8")
        .replace("\"epochLength\":1000", s"\"epochLength\":$epochLength")
    )
    val originals = Map(
      "native-projection.json" -> projection,
      "original-debug-epoch.cbor" -> originalEpoch,
      "original-whole-utxo.cbor" -> whole,
      "derived-full-epoch-seed.cbor" -> seed,
      "capture.json" -> capture,
      "request.json" -> request,
      "effective-shelley-genesis.json" -> genesis
    )
    val epoch = get(
      NativeEpochComponents.decode(originals, originals.map((k, v) => k -> sha(v)), point)
    )
    val selected = components.filter((name, _) => NativeGovernanceComponents.ComponentNames(name))
    val gov = get(
      NativeGovernanceComponents.decode(
        seed,
        sha(seed),
        originalEpoch,
        sha(originalEpoch),
        selected,
        selected.map((k, v) => k -> sha(v))
      )
    )
    val params = epoch.parameters
    val globals = get(
      GovernanceGlobals.bind(
        params,
        params.bindingId,
        params.epoch,
        params.pointSlot,
        params.networkMagic
      )
    )
    Bundle(epoch, gov, globals)

  test("checked ledger-side join preserves one generated source and all four temporal originals") {
    val b = bundle()
    val joined = get(b.join)
    assertEquals(joined.point, b.epoch.point)
    assertEquals(joined.sourceId, b.epoch.id)
    assertEquals(joined.parameterRoles.previous.original, b.governance.previousParameters)
    assertEquals(joined.parameterRoles.current.original, b.governance.currentParameters)
    assertEquals(joined.historicalCurrent.original, b.governance.previousParameters)
    assertEquals(joined.historicalPrevious.original, b.governance.previousParameters)
    assertEquals(joined.governanceInput.accounts.size, 3)
    assertEquals(joined.governanceInput.dreps.size, 3)
    assertEquals(joined.governanceInput.deposits.total, BigInt(0))
    assertEquals(joined.pools.keySet, b.epoch.stake.context.pools.keySet)
    assertEquals(joined.epochComponents.reward.original.hex, "80")
    joined.governanceInput.oldDRep match
      case G.OldDRep.Complete(snapshot, _) =>
        assert(snapshot.dreps.isEmpty && snapshot.drepDistribution.isEmpty)
      case _ => fail("historical completion changed")
    assertEquals(joined.crossingBlockers, Set(NativeLedgerSeed.Blocker.MissingPointBoundProtocolV2))
    assert(joined.ledgerSideJoinChecked)
    assert(
      !joined.runtimeImport && !joined.rewardSeedAdmission && !joined.authenticatedSnapshot && !joined.actualAcquisitionVerified
    )
    assert(
      !joined.fullLedgerValidated && !joined.nativeConformance && !joined.fullParameterValidity && !joined.liveGovernanceCursorRecoverable
    )
  }
  test("500-slot geometry reports precisely two crossing blockers without inventing a freeze") {
    val joined = get(bundle(epochLength = 500).join)
    assertEquals(joined.globals.randomnessStabilisationWindow, BigInt(400))
    assertEquals(
      joined.crossingBlockers,
      Set(
        NativeLedgerSeed.Blocker.MissingPointBoundProtocolV2,
        NativeLedgerSeed.Blocker.IncompatibleCrossingGeometry
      )
    )
    assertEquals(joined.epochComponents.reward.original.hex, "80")
  }
  test("independently decoded coherent vote pool UTxO and count bundles cannot be spliced") {
    val base = bundle()
    val variants =
      Vector(bundle(voteOffset = 20), bundle(poolVrf = 8), bundle(coin = 101), bundle(count = 2))
    variants.foreach { other =>
      assert(other.join.isRight)
      assertNotEquals(other.epoch.id, base.epoch.id)
      assert(
        NativeLedgerSeed
          .bind(base.epoch, other.governance, base.globals, base.epoch.point, base.epoch.id)
          .isLeft
      )
      assert(
        NativeLedgerSeed
          .bind(other.epoch, base.governance, other.globals, other.epoch.point, other.epoch.id)
          .isLeft
      )
    }
  }
  test("valid foreign globals genesis and point identities are not interchangeable") {
    val base = bundle()
    for other <- Vector(bundle(epochLength = 500), bundle(slot = 37)) do
      assert(other.join.isRight)
      assert(
        NativeLedgerSeed
          .bind(base.epoch, base.governance, other.globals, base.epoch.point, base.epoch.id)
          .isLeft
      )
    val otherHash = bundle(pointHash = 9)
    assert(otherHash.join.isRight)
    assert(
      NativeLedgerSeed
        .bind(base.epoch, base.governance, base.globals, otherHash.epoch.point, base.epoch.id)
        .isLeft
    )
    assert(
      NativeLedgerSeed
        .bind(base.epoch, base.governance, base.globals, base.epoch.point, otherHash.epoch.id)
        .isLeft
    )
    assertNotEquals(get(base.join).id, get(otherHash.join).id)
  }
  test("fully decoded temporal mismatches reject even with equal consumed parameter projections") {
    val changed = bundle(nonCostDifference = true)
    assertEquals(
      changed.epoch.parameters.previous.rewards.original,
      changed.epoch.parameters.current.rewards.original
    )
    assertEquals(
      changed.epoch.parameters.previous.feePerByte,
      changed.epoch.parameters.current.feePerByte
    )
    assertEquals(
      changed.epoch.parameters.previous.maxTxSize,
      changed.epoch.parameters.current.maxTxSize
    )
    assert(changed.join.isLeft)
    assert(bundle(wrongHistoricalPrevious = true).join.isLeft)
  }
  test(
    "historical parameters receive typed cost-model checks in addition to their native record shape"
  ) {
    val b = bundle(malformedHistoricalCosts = true)
    assertEquals(b.governance.dreps.size, 3)
    assert(b.join.isLeft)
  }
  test("join requires independently expected full point and original identity") {
    val b = bundle()
    assert(
      NativeLedgerSeed
        .bind(b.epoch, b.governance, b.globals, b.epoch.point.copy(blockNo = 2), b.epoch.id)
        .isLeft
    )
    assert(
      NativeLedgerSeed.bind(b.epoch, b.governance, b.globals, b.epoch.point, bytes(32, 0)).isLeft
    )
    assert(NativeLedgerSeed.bind(null, b.governance, b.globals, b.epoch.point, b.epoch.id).isLeft)
  }
