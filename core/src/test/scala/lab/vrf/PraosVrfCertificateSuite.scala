// SPDX-License-Identifier: Apache-2.0
package lab.vrf

import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, Executors, TimeUnit}
import lab.cbor.Bytes

class PraosVrfCertificateSuite extends munit.FunSuite:
  import PraosVrfCertificate.*
  private def bytes(s: String): Bytes = Bytes.fromHex(s).toOption.get
  private def lines(n: String) =
    Files.readString(Path.of("fixtures/praos", n)).linesIterator.toVector
  private def input(s: String, n: String): Input = Input
    .create(
      Slot.fromBigInt(BigInt(s)).toOption.get,
      if n == "NEUTRAL" then NeutralNonce else Hash32.fromBytes(bytes(n)).toOption.get
    )
    .toOption
    .get
  private def row = lines("certificates.tsv").head.split("\t", -1)
  private def verify(r: Array[String]): Result =
    PraosVrfCertificate.verify(input(r(1), r(2)), bytes(r(3)), bytes(r(4)), bytes(r(5)))
  private def label(r: Result): String = r match
    case Result.VerifiedCertificate(out) => "VERIFIED:" + out.hex
    case Result.OutputMismatch           => "OUTPUT_MISMATCH"
    case Result.ProofRejected(_)         => "PROOF_REJECTED"
    case other                           => fail(other.toString)

  test(
    "15 independently Python-derived alpha controls cover unsigned Word64 and nonce distinction"
  ) {
    val rows = lines("alpha.tsv")
    assertEquals(rows.size, 15)
    rows.foreach { r =>
      val p = r.split("\t", -1)
      assertEquals(alpha(input(p(0), p(1))).toOption.get.hex, p(2))
    }
    assertNotEquals(alpha(input("0", "NEUTRAL")), alpha(input("0", "00" * 32)))
  }
  test("four public archived certificate positives and 16 original native mutation rows") {
    val rows = lines("certificates.tsv").map(_.split("\t", -1))
    assertEquals(rows.size, 18)
    assertEquals(rows.count(_(7).startsWith("VERIFIED:")), 4)
    rows.foreach { r =>
      assertEquals(alpha(input(r(1), r(2))).toOption.get.hex, r(6))
      assertEquals(label(verify(r)), r(7))
    }
  }
  test("old epoch nonce rejects both independently verified epoch166 certificates") {
    lines("certificates.tsv").takeRight(2).foreach { s =>
      val r = s.split("\t", -1)
      r(2) = row(2)
      assert(verify(r).isInstanceOf[Result.ProofRejected])
    }
  }
  test("checked slot range and null constructors") {
    Vector(BigInt(-1), Slot.MaxValue + 1, null).foreach(s => assert(Slot.fromBigInt(s).isLeft))
    Vector(BigInt(0), BigInt(1) << 63, Slot.MaxValue).foreach(s =>
      assert(Slot.fromBigInt(s).isRight)
    )
    assert(Input.create(null, NeutralNonce).isLeft)
    assert(Input.create(Slot.fromBigInt(0).toOption.get, null).isLeft)
    assert(alpha(null).isLeft)
  }
  test("checked nonce lengths and malformed Bytes programming misuse") {
    Vector(0, 31, 33).foreach(n => assert(Hash32.fromBytes(Bytes(Vector.fill(n)(0.toByte))).isLeft))
    assert(Hash32.fromBytes(null).isLeft)
    assert(Hash32.fromBytes(Bytes(null)).swap.toOption.get.isInstanceOf[Failure.InternalFailure])
  }
  test("envelopes and nulls rejected before primitive invocation") {
    val r = row; val in = input(r(1), r(2)); val k = bytes(r(3)); val p = bytes(r(4));
    val c = bytes(r(5))
    var calls = 0
    val stub = (_: Bytes, _: Bytes, _: Bytes) => { calls += 1; StrictDraft03.Result.Verified(c) }
    for n <- Vector(0, 31, 33) do
      assert(
        verifyWith(in, Bytes(Vector.fill(n)(0.toByte)), p, c, stub).isInstanceOf[Result.Malformed]
      )
    for n <- Vector(0, 79, 81) do
      assert(
        verifyWith(in, k, Bytes(Vector.fill(n)(0.toByte)), c, stub).isInstanceOf[Result.Malformed]
      )
    for n <- Vector(0, 63, 65) do
      assert(
        verifyWith(in, k, p, Bytes(Vector.fill(n)(0.toByte)), stub).isInstanceOf[Result.Malformed]
      )
    Vector((null, p, c), (k, null, c), (k, p, null)).foreach { case (a, b, d) =>
      assert(verifyWith(in, a, b, d, stub).isInstanceOf[Result.Malformed])
    }
    assert(verifyWith(null, k, p, c, stub).isInstanceOf[Result.Malformed])
    assertEquals(calls, 0)
    assert(verifyWith(in, Bytes(null), p, c, stub).isInstanceOf[Result.InternalFailure])
  }
  test("strict primitive called once; valid proof with changed claim yields only mismatch") {
    val r = row; val in = input(r(1), r(2)); val c = bytes(r(5)); var calls = 0
    val stub = (_: Bytes, _: Bytes, a: Bytes) => {
      calls += 1; assertEquals(a.hex, r(6)); StrictDraft03.Result.Verified(c)
    }
    assertEquals(
      verifyWith(
        in,
        bytes(r(3)),
        bytes(r(4)),
        Bytes(c.value.updated(0, (c.value.head ^ 1).toByte)),
        stub
      ),
      Result.OutputMismatch
    )
    assertEquals(calls, 1)
  }
  test("internal, malformed and rejection classifications propagate; no unchecked output") {
    val r = row
    def run(result: StrictDraft03.Result) =
      verifyWith(input(r(1), r(2)), bytes(r(3)), bytes(r(4)), bytes(r(5)), (_, _, _) => result)
    assertEquals(run(StrictDraft03.Result.Rejected("x")), Result.ProofRejected("x"))
    assertEquals(run(StrictDraft03.Result.MalformedEnvelope("x")), Result.Malformed("x"))
    assertEquals(
      run(StrictDraft03.Result.InternalFailure("k", "d")),
      Result.InternalFailure("k", "d")
    )
    Vector(
      null,
      Bytes.empty,
      Bytes(Vector.fill(63)(0.toByte)),
      Bytes(Vector.fill(65)(0.toByte)),
      Bytes(null)
    ).foreach(o =>
      assert(run(StrictDraft03.Result.Verified(o)).isInstanceOf[Result.InternalFailure])
    )
    assert(run(null).isInstanceOf[Result.InternalFailure])
  }
  test("unexpected nonfatal failure maps internally and fatal JVM errors propagate") {
    val r = row
    def run(t: Throwable) =
      verifyWith(input(r(1), r(2)), bytes(r(3)), bytes(r(4)), bytes(r(5)), (_, _, _) => throw t)
    assert(run(new IllegalStateException("boom")).isInstanceOf[Result.InternalFailure])
    val fatal = new LinkageError("fatal")
    var caught: Throwable = null
    try run(fatal)
    catch case e: LinkageError => caught = e
    assertEquals(caught, fatal)
  }
  test("nonce, certificate inputs, alpha and verified output own immutable bytes") {
    val r = row; val raw = bytes(r(2)).toArray;
    val nonce = Hash32.fromBytes(Bytes.fromArray(raw)).toOption.get
    raw(0) = (raw(0) ^ 1).toByte
    assertEquals(nonce.bytes.hex, r(2))
    val in = Input.create(Slot.fromBigInt(BigInt(r(1))).toOption.get, nonce).toOption.get
    val a = alpha(in).toOption.get; val ar = a.toArray; ar(0) = (ar(0) ^ 1).toByte
    assertEquals(alpha(in).toOption.get.hex, r(6))
    val arrays = Vector(3, 4, 5).map(i => bytes(r(i)).toArray);
    val owned = arrays.map(Bytes.fromArray)
    arrays.foreach(a => a(0) = (a(0) ^ 1).toByte)
    val out = PraosVrfCertificate.verify(in, owned(0), owned(1), owned(2)) match
      case Result.VerifiedCertificate(o) => o
      case other                         => fail(other.toString)
    val exported = out.toArray; exported(0) = (exported(0) ^ 1).toByte
    assertEquals(out.hex, r(5))
  }
  test("parallel calls do not share digest state") {
    val pool = Executors.newFixedThreadPool(4)
    try
      val r = row
      val jobs = (0 until 16).map(_ =>
        pool.submit(new Callable[String] { def call(): String = label(verify(r)) })
      )
      jobs.foreach(j => assertEquals(j.get(30, TimeUnit.SECONDS), r(7)))
    finally pool.shutdownNow()
  }
