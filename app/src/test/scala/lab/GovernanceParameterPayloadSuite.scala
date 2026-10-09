// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayEmptyGovernance as G, FeeSize, MinimumOutput}

/** Hand-built exact source-shape vectors, not captured native encodings or an admission oracle. */
class GovernanceParameterPayloadSuite extends munit.FunSuite:
  private val P = GovernanceParameterPayload
  private def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private def sha(b: Bytes) = ClusterHeaderObservation.sha256(b)
  private def n(v: V) = Node(v, Bytes.empty)
  private def a(vs: V*) = V.Arr(vs.toVector.map(n))
  private def ratio(a: BigInt, b: BigInt): V = V.Tag(30, n(this.a(V.UInt(a), V.UInt(b))))
  private def full(rho: V = ratio(3, 1000), tau: V = ratio(1, 5)): Vector[V] =
    Vector(
      V.UInt(44),
      V.UInt(155381),
      V.UInt(90112),
      V.UInt(16384),
      V.UInt(1100),
      V.UInt(0),
      V.UInt(0),
      V.UInt(18),
      V.UInt(500),
      ratio(3, 10),
      rho,
      tau,
      a(V.UInt(9), V.UInt(0)),
      V.UInt(170000000),
      V.UInt(4310),
      V.Map(Vector.empty),
      a(ratio(1, 10), ratio(1, 100)),
      a(V.UInt(1000), V.UInt(2000)),
      a(V.UInt(3000), V.UInt(4000)),
      V.UInt(5000),
      V.UInt(150),
      V.UInt(3),
      a(Vector.fill(5)(ratio(1, 2))*),
      a(Vector.fill(10)(ratio(1, 2))*),
      V.UInt(0),
      V.UInt(10),
      V.UInt(6),
      V.UInt(0),
      V.UInt(0),
      V.UInt(20),
      ratio(15, 1)
    )
  private def pp(xs: Vector[V] = full()): Bytes = get(Cbor.encode(V.Arr(xs.map(n))))

  private def decode(xs: Vector[V] = full()) =
    val raw = pp(xs)
    P.decode(raw, sha(raw))
  private val fees = get(FeeSize.Parameters.create("Conway", 9, 44, 155381, 16384))
  private val minimum = get(MinimumOutput.Parameters.checked("Conway", 9, 0, 4310))

  test("native positional extraction preserves full canonical original and all 31 spans") {
    val value = get(decode())
    assertEquals(value.original, pp())
    assertEquals(value.payload.original, pp())
    assertEquals(value.sha256, sha(pp()))
    assertEquals(value.fieldOriginals.size, 31)
    value.fieldOriginals
      .zip(full())
      .foreach((raw, expected) => assertEquals(raw, get(Cbor.encode(expected))))
    assertEquals(value.feePerByte, BigInt(44))
    assertEquals(value.feeFixed, BigInt(155381))
    assertEquals(value.maxTxSize, BigInt(16384))
    assertEquals(value.coinsPerUTxOByte, BigInt(4310))
    assert(P.checkProjections(value, fees, minimum, value.rewards).isRight)
    assert(!value.nativeSeedAdmitted && !value.fullParameterValidity)
  }

  test("canonical complete bytes and matching hash reject each inconsistent ledger projection") {
    val expected = get(decode()).rewards
    Vector((0, 45), (1, 155382), (3, 16385), (14, 4311)).foreach { (index, replacement) =>
      val changed = get(decode(full().updated(index, V.UInt(replacement))))
      assertEquals(changed.sha256, sha(changed.original))
      assert(P.checkProjections(changed, fees, minimum, expected).isLeft)
    }
  }

  test("canonical complete bytes and matching hash reject each inconsistent reward projection") {
    val expected = get(decode()).rewards
    Vector((9, ratio(1, 2)), (10, ratio(1, 100)), (11, ratio(1, 4)), (8, V.UInt(501))).foreach {
      (index, replacement) =>
        val changed = get(decode(full().updated(index, replacement)))
        assertEquals(changed.sha256, sha(changed.original))
        assert(P.checkProjections(changed, fees, minimum, expected).isLeft)
    }
  }

  test("explicit previous and current roles retain differences outside the consumed projection") {
    val previous = get(decode())
    val current = get(decode(full().updated(5, V.UInt(123))))
    val selected = get(
      P.bindRoles(
        previous,
        current,
        previous.sha256,
        current.sha256,
        previous.rewards,
        current.rewards,
        fees,
        minimum
      )
    )
    assert(selected.previous eq previous)
    assert(selected.current eq current)
    assertNotEquals(previous.sha256, current.sha256)
    assert(
      P.bindRoles(
        current,
        previous,
        previous.sha256,
        current.sha256,
        previous.rewards,
        current.rewards,
        fees,
        minimum
      ).isLeft
    )
    val same = get(
      P.bindRoles(
        previous,
        previous,
        previous.sha256,
        previous.sha256,
        previous.rewards,
        previous.rewards,
        fees,
        minimum
      )
    )
    assertNotEquals(selected.id, same.id)
  }

  test("wrong version count source pin duplicate map keys and noncanonical encoding reject") {
    assert(decode(full().updated(12, a(V.UInt(10), V.UInt(0)))).isLeft)
    assert(decode(full().updated(12, a(V.UInt(9), V.UInt(1)))).isLeft)
    assert(decode(full().dropRight(1)).isLeft)
    assert(P.decode(pp(), Bytes(Vector.fill(32)(0.toByte))).isLeft)
    val duplicate = V.Map(Vector(n(V.UInt(0)) -> n(a()), n(V.UInt(0)) -> n(a())))
    assert(decode(full().updated(15, duplicate)).isLeft)
    // First scalar is 44 as 0x18 0x2c; widen it after the two-byte array length.
    val noncanonical =
      Bytes(pp().value.take(2) ++ Vector(0x19.toByte, 0.toByte, 44.toByte) ++ pp().value.drop(4))
    assert(P.decode(noncanonical, sha(noncanonical)).isLeft)
  }

  test("unsigned and rational bounds and unsupported cost-model variants reject") {
    Vector((3, BigInt(1) << 32), (8, BigInt(65536))).foreach { (i, bad) =>
      assert(decode(full().updated(i, V.UInt(bad))).isLeft)
    }
    val aboveWord64 = V.Tag(2, n(V.ByteString(Bytes(Vector(1.toByte) ++ Vector.fill(8)(0.toByte)))))
    Vector(0, 1, 14).foreach(i => assert(decode(full().updated(i, aboveWord64)).isLeft))
    Vector(ratio(1, 0), ratio(2, 4), ratio(2, 1)).foreach { bad =>
      assert(decode(full().updated(10, bad)).isLeft)
    }
    assert(decode(full().updated(15, V.Map(Vector(n(V.UInt(0)) -> n(a(V.UInt(0))))))).isLeft)
    assert(decode(full().updated(14, V.NInt(-1))).isLeft)
    assert(P.decode(Bytes.empty, sha(Bytes.empty)).isLeft)
    val tooLarge = Bytes(Vector.fill(65537)(0.toByte))
    assert(P.decode(tooLarge, sha(tooLarge)).isLeft)
  }

  private val modelLengths = Vector(0 -> 166, 1 -> 175, 2 -> 251)
  private def model(length: Int, first: V = V.UInt(0)): V =
    V.Arr(Vector.tabulate(length)(i => n(if i == 0 then first else V.UInt(i))))
  private def models(languages: Vector[(Int, Int)]): V =
    V.Map(languages.map((language, length) => n(V.UInt(language)) -> n(model(length))))

  test("recognized initial cost models preserve exact signed parameters and role originals") {
    val signedMax = (BigInt(1) << 63) - 1
    val signedMin = -(BigInt(1) << 63)
    val modelEntries = modelLengths.map { (language, length) =>
      val first =
        if language == 0 then V.NInt(signedMin)
        else if language == 1 then V.UInt(signedMax)
        else V.NInt(-1)
      n(V.UInt(language)) -> n(model(length, first))
    }
    val currentModels = V.Map(modelEntries)
    val previousModels = V.Map(modelEntries.filterNot(_._1.value == V.UInt(1)))
    val previous = get(decode(full().updated(15, previousModels)))
    val current = get(decode(full().updated(15, currentModels)))
    assertEquals(current.costModels.view.mapValues(_.size).toMap, modelLengths.toMap)
    assertEquals(previous.costModels.keySet, Set(0, 2))
    assertEquals(current.costModels(0).head, signedMin)
    assertEquals(current.costModels(1).head, signedMax)
    assertEquals(current.costModels(2).head, BigInt(-1))
    assertEquals(current.fieldOriginals(15), get(Cbor.encode(currentModels)))
    assertEquals(current.original, pp(full().updated(15, currentModels)))
    assertEquals(current.payload.original, current.original)
    assertEquals(current.sha256, sha(current.original))
    assertEquals(previous.rewards.original, current.rewards.original)
    assertNotEquals(previous.sha256, current.sha256)
    assertEquals(
      previous.fieldOriginals.indices
        .filter(i => previous.fieldOriginals(i) != current.fieldOriginals(i))
        .toVector,
      Vector(15)
    )
    val roles = get(
      P.bindRoles(
        previous,
        current,
        previous.sha256,
        current.sha256,
        previous.rewards,
        current.rewards,
        fees,
        minimum
      )
    )
    assertEquals(roles.previous.original, previous.original)
    assertEquals(roles.current.original, current.original)
    assert(!current.nativeSeedAdmitted && !current.fullParameterValidity)
    modelLengths.foreach { entry =>
      assert(decode(full().updated(15, models(Vector(entry)))).isRight)
    }
  }

  test("cost model languages lengths integer types duplicates and signed overflow fail closed") {
    modelLengths.foreach { (language, length) =>
      Vector(length - 1, length + 1).foreach { badLength =>
        assert(decode(full().updated(15, models(Vector(language -> badLength)))).isLeft)
      }
      val invalid = Vector[V](
        V.UInt(BigInt(1) << 63),
        V.NInt(-(BigInt(1) << 63) - 1),
        V.Text("0"),
        V.Null,
        ratio(0, 1),
        V.ByteString(Bytes.empty),
        a()
      )
      invalid.foreach { value =>
        val bad = V.Map(Vector(n(V.UInt(language)) -> n(model(length, value))))
        assert(decode(full().updated(15, bad)).isLeft)
      }
    }
    Vector(3, 255).foreach { language =>
      assert(decode(full().updated(15, models(Vector(language -> 251)))).isLeft)
    }
    val duplicate = V.Map(Vector.fill(2)(n(V.UInt(0)) -> n(model(166))))
    assert(decode(full().updated(15, duplicate)).isLeft)
    val wrongKey = V.Map(Vector(n(V.NInt(-1)) -> n(model(166))))
    assert(decode(full().updated(15, wrongKey)).isLeft)
    assert(decode(full().updated(15, a())).isLeft)
  }

  // External audited public-byte evidence is optional in generic unit runs; when requested it
  // must exist and pass. No captured seed or key material is committed with these tests.
  sys.env.get("GOVERNANCE_AUDITED_PROJECTION").foreach { source =>
    test("audited native projection preserves actual previous and current parameter roles") {
      val path = java.nio.file.Path.of(source)
      assert(java.nio.file.Files.isRegularFile(path))
      val size = java.nio.file.Files.size(path)
      assert(size > 0 && size <= 1048576)
      val in = java.nio.file.Files.newInputStream(path)
      val raw =
        try in.readNBytes(1048577)
        finally in.close()
      assert(raw.length <= 1048576)
      val root = ReferenceJson.parse(Bytes.fromArray(raw))
      val components = ReferenceJson.field(root, "components")
      def component(name: String): GovernanceParameterPayload.Checked =
        val record = ReferenceJson.field(components, name)
        val original =
          get(Bytes.fromHex(ReferenceJson.string(ReferenceJson.field(record, "cborHex"))))
        val pin = get(Bytes.fromHex(ReferenceJson.string(ReferenceJson.field(record, "sha256"))))
        val value = get(P.decode(original, pin))
        assertEquals(value.original, original)
        assertEquals(value.payload.original, original)
        assertEquals(value.sha256, pin)
        value
      val previous = component("previousParameters")
      val current = component("currentParameters")
      assertEquals(previous.costModels.view.mapValues(_.size).toMap, Map(0 -> 166, 2 -> 251))
      assertEquals(current.costModels.view.mapValues(_.size).toMap, modelLengths.toMap)
      assertEquals(
        previous.fieldOriginals.indices
          .filter(i => previous.fieldOriginals(i) != current.fieldOriginals(i))
          .toVector,
        Vector(15)
      )
      assertEquals(previous.rewards.original, current.rewards.original)
      assertNotEquals(previous.sha256, current.sha256)
    }
  }

  // Native binary-1.9.1.0 encodeList emits 0x9f ... 0xff for >23 elements.
  private def nativeModelFields(nonminimalFirst: Boolean = false): Vector[Bytes] =
    val costs = Vector(0xa3.toByte) ++ modelLengths.flatMap { (language, length) =>
      val scalars = Vector
        .tabulate(length) { i =>
          if nonminimalFirst && language == 0 && i == 0 then Vector(0x18.toByte, 0.toByte)
          else get(Cbor.encode(V.UInt(i))).value
        }
        .flatten
      get(Cbor.encode(V.UInt(language))).value ++ Vector(0x9f.toByte) ++ scalars ++ Vector(
        0xff.toByte
      )
    }
    full().map(v => get(Cbor.encode(v))).updated(15, Bytes(costs))
  private def originalRecord(fields: Vector[Bytes]): Bytes =
    Bytes(Vector(0x98.toByte, 31.toByte) ++ fields.flatMap(_.value))

  test(
    "native indefinite cost arrays retain every original byte without generic payload relaxation"
  ) {
    val fields = nativeModelFields()
    val raw = originalRecord(fields)
    assert(G.payload(raw).isLeft)
    val value = get(P.decode(raw, sha(raw)))
    assertEquals(value.original, raw)
    assertEquals(value.payload.original, raw)
    assertEquals(value.fieldOriginals, fields)
    assertEquals(value.costModels.view.mapValues(_.size).toMap, modelLengths.toMap)
    val definite = get(decode(full().updated(15, models(modelLengths))))
    assertEquals(value.costModels, definite.costModels)
    assertNotEquals(value.sha256, definite.sha256)
    assertEquals(value.rewards.original, definite.rewards.original)
  }

  test(
    "native cost-array exception rejects nonminimal integers and indefinite structure elsewhere"
  ) {
    val nonminimalCost = originalRecord(nativeModelFields(nonminimalFirst = true))
    assert(P.decode(nonminimalCost, sha(nonminimalCost)).isLeft)
    val fields = nativeModelFields()
    val raw = originalRecord(fields)
    val indefiniteRoot = Bytes(Vector(0x9f.toByte) ++ raw.value.drop(2) ++ Vector(0xff.toByte))
    assert(P.decode(indefiniteRoot, sha(indefiniteRoot)).isLeft)
    val indefiniteThresholds =
      Bytes(Vector(0x9f.toByte) ++ fields(22).value.drop(1) ++ Vector(0xff.toByte))
    val changed = originalRecord(fields.updated(22, indefiniteThresholds))
    assert(P.decode(changed, sha(changed)).isLeft)
    val nonminimalOther =
      originalRecord(fields.updated(0, Bytes(Vector(0x19.toByte, 0.toByte, 44.toByte))))
    assert(P.decode(nonminimalOther, sha(nonminimalOther)).isLeft)
    val indefiniteCostMap =
      Bytes(Vector(0xbf.toByte) ++ fields(15).value.tail ++ Vector(0xff.toByte))
    val changedMap = originalRecord(fields.updated(15, indefiniteCostMap))
    assert(P.decode(changedMap, sha(changedMap)).isLeft)
  }
