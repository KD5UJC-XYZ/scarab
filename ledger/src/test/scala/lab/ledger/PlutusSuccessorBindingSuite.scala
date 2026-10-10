// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import lab.cbor.{Bytes, Cbor, Node, Value as V}

class PlutusSuccessorBindingSuite extends munit.FunSuite:
  private val model =
    Bytes.fromArray(Files.readAllBytes(Path.of("vm/src/main/resources/plutus-pv9/cost-model.json")))
  private def n(v: V): Node = Node(v, Bytes.empty)
  private def array(v: V*): V = V.Arr(v.toVector.map(n))
  private def ratio(a: BigInt, b: BigInt): V = V.Tag(30, n(array(V.UInt(a), V.UInt(b))))
  private val costs = new String(model.toArray, "UTF-8").trim
    .stripPrefix("[")
    .stripSuffix("]")
    .split(",")
    .toVector
    .map(x => BigInt(x.trim))
  private def cost(v: BigInt): V = if v < 0 then V.NInt(v) else V.UInt(v)
  private val base = Vector
    .fill[V](31)(V.UInt(0))
    .updated(0, V.UInt(44))
    .updated(1, V.UInt(155381))
    .updated(3, V.UInt(16384))
    .updated(12, array(V.UInt(9), V.UInt(0)))
    .updated(14, V.UInt(4310))
    .updated(15, V.Map(Vector(n(V.UInt(2)) -> n(V.Arr(costs.map(x => n(cost(x))))))))
    .updated(16, array(ratio(577, 10000), ratio(721, 10000000)))
    .updated(17, array(V.UInt(14000000), V.UInt(10000000000L)))
    .updated(18, array(V.UInt(62000000), V.UInt(20000000000L)))
    .updated(19, V.UInt(5000))
    .updated(20, V.UInt(150))
    .updated(21, V.UInt(3))
  private def encode(xs: Vector[V]): Bytes = Cbor.encode(V.Arr(xs.map(n))).toOption.get
  private def sha(b: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(b.toArray))
  private def decode(xs: Vector[V]) =
    val raw = encode(xs)
    PlutusParameters.decode(raw, sha(raw), model)

  private def get[A](x: Either[?, A]): A = x.fold(e => fail(e.toString), identity)
  private def bytes(i: Int) = Bytes(Vector.fill(32)(i.toByte))
  private class Fixture:
    val parameters = get(decode(base))
    val raw = get(
      ClusterTransition.environment(
        bytes(1),
        parameters.sourceSHA256,
        42,
        0,
        9,
        0,
        44,
        155381,
        16384,
        4310
      )
    )
    val time = PlutusContextInput.SlotTime(1700000000000L, 100, 1, bytes(1))
    val execution = get(PlutusEnvironment.bind(raw, parameters, time, 0))
    val environment = get(ClusterTransition.withPlutus(raw, execution))
    private def fixture(name: String) =
      Bytes.fromArray(Files.readAllBytes(Path.of("fixtures/plutus-pv9-reference/inputs", name)))
    val originalMap = Bytes(
      Vector(0xbf.toByte) ++ fixture("input-0.cbor").value ++ fixture(
        "output-0.cbor"
      ).value ++ Vector(0xff.toByte)
    )
    val ledger = get(
      ClusterTransition.checkpoint(
        environment,
        originalMap,
        10,
        110,
        bytes(2)
      )
    )
    val owner = ConwayStake.owner()
    val context = get(ConwayStake.context(bytes(3), 500, Map.empty, Map.empty))
    val empty = ConwayStake.emptySnapshot
    val stake = get(
      ConwayStake.seed(
        owner,
        context,
        ledger,
        bytes(4),
        Map.empty,
        ConwayStake.Snapshots(empty, empty, empty, 10)
      )
    )
    val boundaryOwner = ConwayEpochBoundary.owner()
    val boundary = get(
      ConwayEpochBoundary.context(
        boundaryOwner,
        owner,
        ledger.id,
        stake,
        ConwayEpochBoundary.Pots(0, 4999990, 10, 10000000),
        Map.empty,
        Map.empty
      )
    )
    def preview =
      val signal = get(ConwayEpochBoundary.signal(boundaryOwner, boundary, bytes(5), 500))
      get(
        ConwayEpochBoundary.preview(
          boundaryOwner,
          boundary,
          signal,
          ConwayEpochBoundary.RewardPhase.Absent(
            get(ConwayEpochBoundary.suppliedAbsent(boundaryOwner, boundary, bytes(6)))
          )
        )
      )

  test("successor binding keeps geometry and atomically commits and undoes one boundary/body") {
    val f = new Fixture
    val preview = f.preview
    val binding = get(PlutusSuccessorBinding.prepare(f.ledger, preview, f.parameters))
    val block = get(
      ClusterTransition.preparePlutusSuccessorBlock(
        f.ledger,
        binding,
        bytes(5),
        Vector.empty,
        500,
        None
      )
    )
    val stakeCandidate =
      get(ConwayStake.preparePlutusSuccessor(f.owner, f.stake, f.ledger, block, preview, binding))
    val applied = get(ClusterTransition.commitBlock(f.ledger, block))
    val selected = get(ConwayStake.select(f.owner, f.stake, stakeCandidate))
    assertEquals(applied.state.environment.epoch, BigInt(1))
    assertEquals(applied.state.environment.plutus.get.time, f.time)
    assertEquals(
      applied.state.environment.plutus.get.parameters.sourceSHA256,
      f.parameters.sourceSHA256
    )
    assertNotEquals(applied.state.environment.id, f.environment.id)
    assertEquals(applied.state.revision, f.ledger.revision + 1)
    assertEquals(selected.ledgerId, applied.state.id)
    assertEquals(selected.epoch, BigInt(1))
    assertEquals(selected.instantaneous, Map.empty)
    assertEquals(f.ledger.environment.epoch, BigInt(0))
    val restored = get(ClusterTransition.undo(applied.state, applied.state.revision, applied.undo))
    assertEquals(restored.environment.id, f.environment.id)
    assertEquals(restored.outputMap, f.ledger.outputMap)
    assertEquals(restored.fees, f.ledger.fees)
    assert(ClusterTransition.commitBlock(restored, block).isLeft)
    assert(PlutusSuccessorBinding.forSource(binding, restored, preview).isLeft)
  }

  test("foreign preview, source and incoming header cannot reuse a binding") {
    val f = new Fixture; val preview = f.preview
    val b = get(PlutusSuccessorBinding.prepare(f.ledger, preview, f.parameters))
    assert(PlutusSuccessorBinding.forSource(b, f.ledger, f.preview).isLeft)
    assert(PlutusSuccessorBinding.forSource(b, new Fixture().ledger, preview).isLeft)
    assert(
      ClusterTransition
        .preparePlutusSuccessorBlock(f.ledger, b, bytes(9), Vector.empty, 500, None)
        .isLeft
    )
    assert(
      ClusterTransition
        .preparePlutusSuccessorBlock(f.ledger, b, bytes(5), Vector.empty, 501, None)
        .isLeft
    )
    assert(PlutusSuccessorBinding.prepare(null, preview, f.parameters).isLeft)
  }

  test(
    "successor uses complete new checked execution parameters, not old cost-model identity alone"
  ) {
    val f = new Fixture
    val changed = get(decode(base.updated(0, V.UInt(45)).updated(20, V.UInt(200))))
    val b = get(PlutusSuccessorBinding.prepare(f.ledger, f.preview, changed))
    assertEquals(b.environment.feeParameters.feePerByte, BigInt(45))
    assertEquals(b.environment.plutus.get.parameters.execution.collateralPercentage, BigInt(200))
    assertEquals(b.environment.parameterDigest, changed.sourceSHA256)
    assertNotEquals(b.environment.parameterDigest, f.environment.parameterDigest)
  }

  test("legacy synthetic paths still reject Plutus and a bad body publishes no state") {
    val f = new Fixture; val p = f.preview
    val b = get(PlutusSuccessorBinding.prepare(f.ledger, p, f.parameters))
    assert(
      ClusterTransition
        .prepareSyntheticSuccessorBlock(f.ledger, 1, p.pots.fees, p.id, bytes(5), Vector.empty, 500)
        .isLeft
    )
    val block = get(
      ClusterTransition.preparePlutusSuccessorBlock(f.ledger, b, bytes(5), Vector.empty, 500, None)
    )
    assert(ConwayStake.prepareSyntheticSuccessor(f.owner, f.stake, f.ledger, block, p).isLeft)
    assert(
      ClusterTransition
        .preparePlutusSuccessorBlock(f.ledger, b, bytes(5), Vector(Bytes.empty), 500, None)
        .isLeft
    )
    assertEquals(f.ledger.environment.epoch, BigInt(0))
    assertEquals(f.ledger.fees, BigInt(10))
  }

  test("two separately bound pure successors preserve original inline datum and complete UTxO") {
    val f = new Fixture
    val firstPreview = f.preview
    val firstBinding = get(PlutusSuccessorBinding.prepare(f.ledger, firstPreview, f.parameters))
    val firstBlock = get(
      ClusterTransition.preparePlutusSuccessorBlock(
        f.ledger,
        firstBinding,
        bytes(5),
        Vector.empty,
        500,
        None
      )
    )
    val firstStake = get(
      ConwayStake.preparePlutusSuccessor(
        f.owner,
        f.stake,
        f.ledger,
        firstBlock,
        firstPreview,
        firstBinding
      )
    )
    val first = get(ClusterTransition.commitBlock(f.ledger, firstBlock)).state
    val selected = get(ConwayStake.select(f.owner, f.stake, firstStake))
    val context = get(
      ConwayEpochBoundary.context(
        f.boundaryOwner,
        f.owner,
        first.id,
        selected,
        firstPreview.pots,
        Map.empty,
        Map.empty
      )
    )
    val signal = get(ConwayEpochBoundary.signal(f.boundaryOwner, context, bytes(7), 1000))
    val secondPreview = get(
      ConwayEpochBoundary.preview(
        f.boundaryOwner,
        context,
        signal,
        ConwayEpochBoundary.RewardPhase.Absent(
          get(ConwayEpochBoundary.suppliedAbsent(f.boundaryOwner, context, bytes(8)))
        )
      )
    )
    val secondBinding = get(PlutusSuccessorBinding.prepare(first, secondPreview, f.parameters))
    val secondBlock = get(
      ClusterTransition.preparePlutusSuccessorBlock(
        first,
        secondBinding,
        bytes(7),
        Vector.empty,
        1000,
        None
      )
    )
    val secondStake = get(
      ConwayStake.preparePlutusSuccessor(
        f.owner,
        selected,
        first,
        secondBlock,
        secondPreview,
        secondBinding
      )
    )
    val second = get(ClusterTransition.commitBlock(first, secondBlock)).state
    assertEquals(second.environment.epoch, BigInt(2))
    assertEquals(second.revision, f.ledger.revision + 2)
    assertEquals(get(ConwayStake.select(f.owner, selected, secondStake)).ledgerId, second.id)
    val beforeOutputs = get(PlutusOutput.snapshot(f.ledger.outputMap, 0)).outputs
    val afterOutputs = get(PlutusOutput.snapshot(second.outputMap, 0)).outputs
    assertEquals(afterOutputs.keySet, beforeOutputs.keySet)
    beforeOutputs.foreach { (key, output) =>
      assertEquals(afterOutputs(key).original, output.original)
      assertEquals(afterOutputs(key).datum.get.original, output.datum.get.original)
    }
    assert(PlutusSuccessorBinding.forSource(firstBinding, first, secondPreview).isLeft)
  }
