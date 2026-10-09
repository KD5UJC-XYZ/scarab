// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.ConwayEmptyGovernance as G

/** Self-generated shape/provenance fixtures; no native capture or semantic equivalence claim. */
class NativeGovernanceComponentsSuite extends munit.FunSuite:
  private def get[A](e: Either[String, A]): A = e.fold(e => fail(e), identity)
  private def n(v: V): Node = Node(v, Bytes.empty)
  private def a(vs: V*): V = V.Arr(vs.toVector.map(n))
  private def m(entries: (V, V)*): V = V.Map(entries.toVector.map((k, v) => n(k) -> n(v)))
  private def set(vs: V*): V = V.Tag(258, n(a(vs*)))
  private def raw(v: V): Bytes = get(Cbor.encode(v))
  private def sha(b: Bytes): Bytes = ClusterHeaderObservation.sha256(b)
  private def hash(size: Int, byte: Int): V = V.ByteString(Bytes(Vector.fill(size)(byte.toByte)))
  private def cred(i: Int): V = a(V.UInt(0), hash(28, i))
  private def params(i: Int): V = a(Vector.fill(31)(V.UInt(BigInt(i)))*)
  private val roots = a(a(), a(), a(), a())
  private val committee = a(a(m(), V.Tag(30, n(a(V.UInt(0), V.UInt(1))))))
  private val constitution = a(a(V.Text(""), hash(32, 0)), V.Null)
  private val registrations = m(
    (1 to 3).map(i => cred(i) -> a(V.UInt(1000), a(), V.UInt(0), set(cred(i + 10))))*
  )
  private val accounts = m(
    (1 to 3).map(i => cred(i + 10) -> a(V.UInt(0), V.UInt(0), V.Null, cred(i)))*
  )
  private val snapshot = a(a(), m(), m(), m())
  private val enact = a(committee, constitution, params(7), params(8), V.UInt(0), m(), roots)
  private val ratify = a(enact, a(), set(), V.Bool(false))
  private val governance = a(
    a(roots, a()),
    committee,
    constitution,
    params(1),
    params(2),
    a(V.UInt(0)),
    a(snapshot, ratify)
  )
  private val voting = a(registrations, m(), V.UInt(3))
  private val delegation = a(accounts, V.Null, V.Null, V.Null)
  private val certificate = a(voting, V.Null, delegation)
  private val utxo = a(m(), V.UInt(0), V.Null, governance, V.UInt(0), V.Null)
  private val epochState = a(V.Null, a(certificate, utxo), V.Null, V.Null)
  private val root = a(V.UInt(2), V.Null, V.Null, epochState, V.Null, V.Null, V.Null)
  private val govPath = Vector(3, 1, 1, 3)
  private val votingPath = Vector(3, 1, 0, 0)
  private val accountPath = Vector(3, 1, 0, 2, 0)
  private val paths = Map(
    "certificateState" -> Vector(3, 1, 0),
    "votingState" -> votingPath,
    "delegationState" -> Vector(3, 1, 0, 2),
    "accounts" -> accountPath,
    "dreps" -> (votingPath :+ 0),
    "committeeState" -> (votingPath :+ 1),
    "dormantEpochs" -> (votingPath :+ 2),
    "governance" -> govPath,
    "proposals" -> (govPath :+ 0),
    "committee" -> (govPath :+ 1),
    "constitution" -> (govPath :+ 2),
    "currentParameters" -> (govPath :+ 3),
    "previousParameters" -> (govPath :+ 4),
    "futureParameters" -> (govPath :+ 5),
    "drepPulsingState" -> (govPath :+ 6)
  )
  private def replace(v: V, path: Vector[Int], replacement: V): V =
    if path.isEmpty then replacement
    else
      v match
        case V.Arr(xs) =>
          V.Arr(xs.updated(path.head, n(replace(xs(path.head).value, path.tail, replacement))))
        case _ => fail("synthetic array path")
  private def at(node: Node, path: Vector[Int]): Node = path.foldLeft(node) { (node, index) =>
    node.value match
      case V.Arr(xs) => xs(index)
      case _         => fail("synthetic decoded path")
  }
  private final case class Fixture(seed: Bytes, epoch: Bytes, components: Map[String, Bytes]):
    def decode = NativeGovernanceComponents.decode(
      seed,
      sha(seed),
      epoch,
      sha(epoch),
      components,
      components.map((k, v) => k -> sha(v))
    )
  private def fixture(value: V = root, original: Option[V] = None): Fixture =
    val seed = raw(value)
    val decoded = get(Cbor.decode(seed))
    Fixture(
      seed,
      raw(original.getOrElse(value)),
      paths.map((name, path) => name -> at(decoded, path).original)
    )
  private def reject(path: Vector[Int], value: V): Unit =
    assert(fixture(replace(root, path, value)).decode.isLeft, path.toString)

  test("synthetic current registrations stay distinct from empty historical completed snapshot") {
    val f = fixture()
    val checked = get(f.decode)
    assertEquals(checked.epoch, BigInt(2))
    assertEquals(checked.dormant, BigInt(3))
    assertEquals(checked.dreps.size, 3)
    assertEquals(checked.accounts.size, 3)
    assertEquals(
      checked.historical.snapshot,
      G.CompletedSnapshot(Vector.empty, Map.empty, Map.empty, Map.empty)
    )
    assertEquals(checked.currentParameters, raw(params(1)))
    assertEquals(checked.previousParameters, raw(params(2)))
    assertEquals(checked.historical.enact.currentParameters, raw(params(7)))
    assertEquals(checked.historical.enact.previousParameters, raw(params(8)))
    assertNotEquals(checked.currentParameters, checked.historical.enact.currentParameters)
    assertEquals(checked.componentOriginals, f.components)
    assert(checked.componentDerivationChecked && checked.voteReverseDelegationChecked)
    assert(!checked.runtimeImport && !checked.rewardSeedAdmission && !checked.nativeConformance)
    assert(!checked.authenticatedSnapshot && !checked.liveGovernanceCursorRecoverable)
    assert(!checked.parameterSemanticsChecked && !checked.wholeSeedDerivationChecked)
  }
  test("component and both original digests are independently mandatory") {
    val f = fixture()
    val pins = f.components.map((k, v) => k -> sha(v))
    val wrong = sha(Bytes(Vector(42.toByte)))
    assert(
      NativeGovernanceComponents
        .decode(f.seed, wrong, f.epoch, sha(f.epoch), f.components, pins)
        .isLeft
    )
    assert(
      NativeGovernanceComponents
        .decode(f.seed, sha(f.seed), f.epoch, wrong, f.components, pins)
        .isLeft
    )
    paths.keys.foreach { name =>
      assert(
        NativeGovernanceComponents
          .decode(
            f.seed,
            sha(f.seed),
            f.epoch,
            sha(f.epoch),
            f.components,
            pins.updated(name, wrong)
          )
          .isLeft,
        name
      )
      assert(f.copy(components = f.components - name).decode.isLeft, name)
      assert(
        NativeGovernanceComponents
          .decode(f.seed, sha(f.seed), f.epoch, sha(f.epoch), f.components, pins - name)
          .isLeft,
        name
      )
    }
    assert(f.copy(components = f.components.updated("extra", raw(V.UInt(0)))).decode.isLeft)
  }
  test("repinned component substitutions fail original-span derivation") {
    val f = fixture()
    paths.keys.foreach { name =>
      assert(f.copy(components = f.components.updated(name, raw(V.UInt(42)))).decode.isLeft, name)
    }
    assert(fixture(root, Some(replace(root, govPath :+ 3, params(9)))).decode.isLeft)
    assert(fixture(root, Some(replace(root, Vector(0), V.UInt(3)))).decode.isLeft)
  }
  test("opaque UTxO differences are preserved without whole-seed derivation claims") {
    val different = replace(root, Vector(3, 1, 1, 0), m(V.UInt(1) -> V.UInt(2)))
    val checked = get(fixture(root, Some(different)).decode)
    assertNotEquals(checked.originalSeed, checked.originalEpoch)
    assert(!checked.wholeSeedDerivationChecked)
    assertNotEquals(checked.sourceId, get(fixture().decode).sourceId)
  }
  test("duplicate credential maps and reverse delegator sets reject") {
    val registered = a(V.UInt(1000), a(), V.UInt(0), set(cred(11)))
    reject(votingPath :+ 0, m(cred(1) -> registered, cred(1) -> registered))
    reject(votingPath :+ 0, m(cred(1) -> a(V.UInt(1000), a(), V.UInt(0), set(cred(11), cred(11)))))
    val account = a(V.UInt(0), V.UInt(0), V.Null, cred(1))
    reject(accountPath, m(cred(11) -> account, cred(11) -> account))
    reject(votingPath :+ 0, m(cred(1) -> a(V.UInt(1000), a(), V.UInt(0), set())))
  }
  test("unknown vote and absent registration reject independent of valid digests") {
    reject(accountPath, m(cred(11) -> a(V.UInt(0), V.UInt(0), V.Null, a(V.UInt(4)))))
    reject(accountPath, m(cred(11) -> a(V.UInt(0), V.UInt(0), V.Null, cred(99))))
    reject(accountPath, m(cred(11) -> a(V.UInt(0), V.UInt(0), V.Null, V.UInt(0))))
  }
  test("source record shapes reject missing and extra fields") {
    val valid = fixture()
    def rejectOriginalShape(path: Vector[Int], value: V): Unit =
      val malformed = raw(replace(root, path, value))
      // Keep valid component spans: extracting paths from a malformed parent
      // would fail in the fixture before the production arity checks run.
      val result = valid.copy(seed = malformed, epoch = malformed).decode
      assert(result.left.exists(_.contains("governance array shape")), path.toString)
    for path <- Vector(
        Vector.empty[Int],
        Vector(3),
        Vector(3, 1),
        Vector(3, 1, 0),
        votingPath,
        Vector(3, 1, 0, 2),
        Vector(3, 1, 1),
        govPath
      )
    do
      val nodes = at(get(Cbor.decode(raw(root))), path).value.asInstanceOf[V.Arr].value
      rejectOriginalShape(path, V.Arr(nodes.dropRight(1)))
      rejectOriginalShape(path, V.Arr(nodes :+ n(V.Null)))
    reject(govPath :+ 3, a(Vector.fill(30)(V.UInt(0))*))
    reject(govPath :+ 4, a(Vector.fill(32)(V.UInt(0))*))
    reject(govPath :+ 5, a(V.UInt(1)))
  }
  test(
    "nonempty proposals roots committees and authorizations remain outside the supported subset"
  ) {
    reject(govPath ++ Vector(0, 1), a(V.UInt(0)))
    reject(govPath ++ Vector(0, 0, 0), a(V.UInt(0)))
    reject(govPath :+ 1, a())
    reject(govPath ++ Vector(1, 0, 0), m(cred(1) -> V.UInt(100)))
    reject(votingPath :+ 1, m(cred(1) -> a(V.UInt(0))))
    reject(govPath ++ Vector(1, 0, 1), V.Tag(30, n(a(V.UInt(0), V.UInt(2)))))
    reject(
      votingPath :+ 0,
      m(cred(1) -> a(V.UInt(1000), a(V.Text("anchor")), V.UInt(0), set(cred(11))))
    )
  }
  test("historical snapshot fields are never silently substituted by current registrations") {
    val path = govPath ++ Vector(6, 0)
    reject(path :+ 0, a(V.UInt(0)))
    for index <- 1 to 3 do reject(path :+ index, m(cred(1) -> V.UInt(1)))
  }
  test("ratification delayed enacted expired withdrawals and scripts fail closed") {
    val ratification = govPath ++ Vector(6, 1)
    reject(ratification :+ 1, a(V.UInt(0)))
    reject(ratification :+ 2, set(V.UInt(0)))
    reject(ratification :+ 2, a())
    reject(ratification :+ 3, V.Bool(true))
    reject(ratification ++ Vector(0, 5), m(cred(1) -> V.UInt(1)))
    reject(ratification ++ Vector(0, 6, 0), a(V.UInt(0)))
    reject(govPath ++ Vector(2, 1), hash(28, 0))
    reject(govPath ++ Vector(2, 0, 0), V.Text("unsupported"))
    reject(govPath ++ Vector(2, 0, 1), hash(32, 1))
    reject(ratification ++ Vector(0, 1, 1), hash(28, 0))
  }
  test("original component and map entry bounds fail closed") {
    val f = fixture()
    assert(f.copy(seed = Bytes(Vector.fill(1048577)(0.toByte))).decode.isLeft)
    assert(
      f.copy(components = f.components.updated("governance", Bytes(Vector.fill(65537)(0.toByte))))
        .decode
        .isLeft
    )
    assert(f.copy(seed = Bytes(f.seed.value :+ 0.toByte)).decode.isLeft)
    reject(votingPath :+ 0, V.Map(Vector.fill(4097)(n(cred(1)) -> n(V.UInt(0)))))
  }
