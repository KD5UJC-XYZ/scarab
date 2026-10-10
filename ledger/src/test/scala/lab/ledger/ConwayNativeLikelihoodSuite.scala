// SPDX-License-Identifier: Apache-2.0
package lab.ledger
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import ConwayStake as S
import ConwayEpochBoundary as B
import ConwayRewardStart as R
class ConwayNativeLikelihoodSuite extends munit.FunSuite:
  private val N = ConwayNativeLikelihood
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  private def key(n: Int) = Bytes(Vector.fill(28)(n.toByte))
  private def bytes(n: Int) = Bytes(Vector.fill(32)(n.toByte))
  private def encoded(format: String, values: Vector[BigInt]) = get(
    Cbor.encode(
      V.Arr(
        Vector(Node(V.Text(format), Bytes.empty)) ++ values.map(x => Node(V.UInt(x), Bytes.empty))
      )
    )
  )
  private def fixture(
      stake: BigInt = 1000,
      reserves: BigInt = 3000,
      blocks: BigInt = 4,
      slots: BigInt = 1000,
      supply: BigInt = 6000
  ): B.Frozen =
    val bo = B.owner(); val so = S.owner()
    val credential = S.Credential(false, key(3))
    val pool =
      S.Pool(bytes(5), 0, 0, S.Ratio(0, 1), credential, Set(credential.hash), Set(credential), 0)
    val context = get(
      S.context(
        bytes(7),
        slots,
        Map(credential -> S.Account(0, 0, Some(key(1)))),
        Map(key(1) -> pool)
      )
    )
    val active =
      if stake == 0 then Map.empty[S.Credential, S.Active]
      else Map(credential -> S.Active(stake, key(1)))
    val go = get(S.fromActive(context, active))
    val env = get(
      ClusterTransition.environment(bytes(7), bytes(7), 1082026, 0, 9, 0, 44, 155381, 16384, 4310)
    )
    val ledger = get(
      ClusterTransition.checkpoint(env, get(Cbor.encode(V.Map(Vector.empty))), 50, 110, bytes(7))
    )
    val state = get(S.seed(so, context, ledger, bytes(7), Map.empty, S.Snapshots(go, go, go, 37)))
    val start = get(
      B.context(
        bo,
        so,
        bytes(8),
        state,
        B.Pots(0, reserves, 0, supply),
        Map(key(1) -> blocks),
        Map.empty
      )
    )
    val parameters = get(
      R.decodeParameters(encoded(R.ParameterFormat, Vector[BigInt](9, 0, 0, 1, 0, 1)))
    )
    val globals = get(
      R.decodeGlobals(encoded(R.GlobalFormat, Vector[BigInt](slots, 1, 20, supply)))
    )
    get(B.freezeForAllocation(bo, start, 110, 100, parameters, globals))
  private def wire(
      r: N.Request,
      probability: String = "0000000000000000",
      word: String = "00000000"
  ) =
    Bytes.fromArray(
      (new String(r.original.value.toArray, "US-ASCII") + "--native--\n" + key(
        1
      ).hex + " " + probability + " " + word * 100 + "\n").getBytes("US-ASCII")
    )
  test("request uses circulation not normalized pool ratio and captured previous counts") {
    val f = fixture(); val r = get(N.request(f, f.id))
    val text = new String(r.original.value.toArray, "US-ASCII")
    assert(text.endsWith(s"${key(1).hex} 1000 3000 4\n"))
    assertEquals(f.go.pools(key(1)).ratio, S.Ratio(1, 1))
    val changed = fixture(reserves = 2999, blocks = 7)
    assert(
      new String(get(N.request(changed, changed.id)).original.value.toArray, "US-ASCII")
        .endsWith("1000 3001 7\n")
    )
  }
  test("captured zero-stake registered pool retained rather than erased") {
    val f = fixture(stake = 0, blocks = 0); val r = get(N.request(f, f.id))
    val generated = get(N.acceptTrustedNative(r, wire(r), N.Mode.AssistedNative))
    assertEquals(generated.likelihoods.keySet, Set(key(1)))
    assertEquals(generated.jvmMismatchWords, 0)
  }
  test("native diagnostic output stays authoritative with explicit JVM mismatch count") {
    val f = fixture(); val r = get(N.request(f, f.id))
    val generated = get(N.acceptTrustedNative(r, wire(r), N.Mode.AssistedNative))
    assert(N.acceptTrustedNative(r, wire(r)).isLeft)
    assert(generated.nativeValuesAuthoritative)
    assert(generated.jvmMismatchWords > 0)
    assertEquals(generated.likelihoods(key(1)).rawBits, Vector.fill(100)(0))
    assert(generated.diagnosticNativeDependency && !generated.generalJvmParityValidated)
  }
  test("completion refuses same-content reconstructed freeze and wrong identity") {
    val f = fixture(); val r = get(N.request(f, f.id));
    val g = get(N.acceptTrustedNative(r, wire(r), N.Mode.AssistedNative))
    assert(g.forFrozen(f, f.id).isRight)
    val reconstructed = fixture()
    assertEquals(reconstructed.id, f.id)
    assert(g.forFrozen(reconstructed, reconstructed.id).isLeft)
    assert(g.forFrozen(f, bytes(9)).isLeft)
  }
  test("stale echo, missing or excess pool rows and nonfinite words fail closed") {
    val f = fixture(); val r = get(N.request(f, f.id)); val other = fixture(blocks = 7)
    assert(N.acceptTrustedNative(r, wire(get(N.request(other, other.id)))).isLeft)
    assert(
      N.acceptTrustedNative(
        r,
        Bytes.fromArray(
          (new String(r.original.value.toArray, "US-ASCII") + "--native--\n").getBytes("US-ASCII")
        )
      ).isLeft
    )
    assert(N.acceptTrustedNative(r, wire(r, word = "7fc00000")).isLeft)
    assert(N.acceptTrustedNative(r, wire(r, probability = "7ff0000000000000")).isLeft)
    assert(N.acceptTrustedNative(r, Bytes(Vector.fill(N.MaxResponseBytes + 1)(0.toByte))).isLeft)
  }
  test("unsupported geometry counts and source fail without altering original generator guard") {
    val f = fixture(); assert(N.request(f, bytes(9)).isLeft)
    val count = fixture(blocks = 1001); assert(N.request(count, count.id).isLeft)
    val geometry = fixture(slots = 500); assert(N.request(geometry, geometry.id).isLeft)
    assert(ConwayNonMyopic.generateForFrozen(f, f.id).isLeft)
    assert(N.request(null, null).isLeft)
  }

  test("all ten actual dynamic native vectors retain exact raw words and measured JVM comparison") {
    val stream = getClass.getResourceAsStream("/non-myopic-dynamic/native-result.txt")
    val text =
      try new String(stream.readAllBytes(), "US-ASCII")
      finally stream.close()
    val parts = text.split("--native--\n")
    val inputs = parts(0).linesIterator.drop(3).toVector
    val outputs = parts(1).linesIterator.toVector
    assertEquals(inputs.size, 10)
    inputs.zip(outputs).foreach { (input, output) =>
      val fields = input.split(" ")
      val stake = BigInt(fields(1)); val circulation = BigInt(fields(2));
      val blocks = BigInt(fields(3))
      val f = fixture(stake = stake, reserves = 1000, blocks = blocks, supply = circulation + 1000)
      val request = get(N.request(f, f.id))
      val words = output.split(" ")
      val response = Bytes.fromArray(
        (new String(request.original.value.toArray, "US-ASCII") +
          "--native--\n" + key(1).hex + " " + words(1) + " " + words(2) + "\n").getBytes("US-ASCII")
      )
      val generated = get(N.acceptTrustedNative(request, response))
      assertEquals(generated.likelihoods(key(1)).hex.mkString, words(2))
      println(
        s"DYNAMIC_NATIVE stake=$stake circulation=$circulation blocks=$blocks mismatchWords=${generated.jvmMismatchWords}"
      )
    }
  }

  test("pure JVM capability has exact source binding and no native validation claim") {
    val f = fixture()
    val g = get(ConwayLikelihoodGeneration.generateJvm(f, f.id))
    assertEquals(g.mode, N.Mode.PureJvm)
    assertEquals(g.nativeResponse, None)
    assertEquals(g.computedRaw32Words, 100)
    assertEquals(g.computedRaw64Words, 1)
    assertEquals(g.raw32Comparisons, 0)
    assertEquals(g.raw64Comparisons, 0)
    assert(!g.nativeValidated && !g.nativeValuesAuthoritative && !g.diagnosticNativeDependency)
    assert(!g.generalJvmParityValidated)
    assert(g.forFrozen(f, f.id).isRight)
    assert(g.forFrozen(fixture(), f.id).isLeft)
    assert(ConwayLikelihoodGeneration.generateJvm(f, bytes(9)).isLeft)
    val invalid = fixture(blocks = 1001)
    assert(ConwayLikelihoodGeneration.generateJvm(invalid, invalid.id).isLeft)
    assertEquals(get(ConwayLikelihoodGeneration.generateJvm(f, f.id)).evidence, g.evidence)
    assert(N.acceptTrustedNative(g.request, wire(g.request), N.Mode.PureJvm).isLeft)
  }
  test("pure JVM computes all ten native golden cases independently before comparison") {
    val stream = getClass.getResourceAsStream("/non-myopic-dynamic/native-result.txt")
    val text =
      try new String(stream.readAllBytes(), "US-ASCII")
      finally stream.close()
    val parts = text.split("--native--\n")
    val inputs = parts(0).linesIterator.drop(3).toVector
    val outputs = parts(1).linesIterator.toVector
    assertEquals(inputs.size, 10)
    inputs.zip(outputs).foreach { (input, output) =>
      val fields = input.split(" ")
      val f = fixture(
        stake = BigInt(fields(1)),
        reserves = 1000,
        blocks = BigInt(fields(3)),
        supply = BigInt(fields(2)) + 1000
      )
      val generated = get(ConwayLikelihoodGeneration.generateJvm(f, f.id))
      val actual = new String(generated.evidence.value.toArray, "US-ASCII")
        .split("--jvm--\n")(1)
        .trim
        .split(" ")
      val expected = output.split(" ")
      assertEquals(actual(1), expected(1))
      assertEquals(actual(2), expected(2))
      assertEquals(generated.likelihoods(key(1)).hex.mkString, expected(2))
      assertEquals(generated.raw32Comparisons, 0)
    }
  }
