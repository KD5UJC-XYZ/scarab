// SPDX-License-Identifier: Apache-2.0
package lab.opcert

import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, Executors, TimeUnit}
import lab.cbor.Bytes
import lab.witness.{PublicKey32, Signature64, VerificationError, VerificationResult}

class OperationalCertificateSuite extends munit.FunSuite:
  import OperationalCertificate.*
  private def bytes(s: String): Bytes = Bytes.fromHex(s).toOption.get
  private def lines(n: String) =
    Files.readString(Path.of("fixtures/opcert", n)).linesIterator.toVector
  private def rows = lines("certificates.tsv").map(_.split("\t", -1))
  private def cert(r: Array[String]): Certificate =
    Certificate.fromBytes(bytes(r(2)), BigInt(r(3)), BigInt(r(4)), bytes(r(5))).toOption.get
  private def key(r: Array[String]): PublicKey32 = PublicKey32.create(bytes(r(1))).toOption.get
  private def label(r: Either[Failure, Result]): String = r match
    case Right(Result.OperationalCertificateSignatureVerified) => "VERIFIED"
    case Right(Result.SignatureRejected)                       => "REJECTED"
    case other                                                 => fail(other.toString)

  test("four archived public positives and all 24 independently native-rejected mutations") {
    assertEquals(rows.size, 28)
    assertEquals(rows.count(_(7) == "VERIFIED"), 4)
    rows.foreach { r =>
      assertEquals(signableBytes(cert(r)).toOption.get.hex, r(6))
      assertEquals(label(verifySignature(key(r), cert(r))), r(7))
      assertEquals(
        label(verify(bytes(r(1)), bytes(r(2)), BigInt(r(3)), BigInt(r(4)), bytes(r(5)))),
        r(7)
      )
    }
  }
  test("25 independent unsigned64 cross-product raw48 serialization controls") {
    val controls = lines("serialization.tsv")
    assertEquals(controls.size, 25)
    controls.foreach { s =>
      val r = s.split("\t", -1)
      val c = Certificate
        .fromBytes(bytes(r(0)), BigInt(r(1)), BigInt(r(2)), bytes("00" * 64))
        .toOption
        .get
      val message = signableBytes(c).toOption.get
      assertEquals(message.size, 48)
      assertEquals(message.hex, r(3))
    }
  }
  test("uint64 rejects negative overflow null; admits full unsigned range") {
    Vector(BigInt(-1), UInt64.MaxValue + 1, null).foreach(n => assert(UInt64.fromBigInt(n).isLeft))
    Vector(BigInt(0), BigInt(1), (BigInt(1) << 63) - 1, BigInt(1) << 63, UInt64.MaxValue).foreach(
      n => assertEquals(UInt64.fromBigInt(n).toOption.get.value, n)
    )
    val r = rows.head
    for n <- Vector(BigInt(-1), UInt64.MaxValue + 1, null) do
      assert(Certificate.fromBytes(bytes(r(2)), n, 0, bytes(r(5))).isLeft)
      assert(Certificate.fromBytes(bytes(r(2)), 0, n, bytes(r(5))).isLeft)
  }
  test("wrong lengths and null raw fields are malformed") {
    val r = rows.head
    def run(k: Bytes = bytes(r(1)), h: Bytes = bytes(r(2)), s: Bytes = bytes(r(5))) =
      verify(k, h, 7, 505, s)
    Vector(0, 31, 33).foreach { n =>
      val b = Bytes(Vector.fill(n)(0.toByte))
      assert(run(k = b).swap.toOption.get.isInstanceOf[Failure.Malformed])
      assert(run(h = b).swap.toOption.get.isInstanceOf[Failure.Malformed])
    }
    Vector(0, 63, 65).foreach(n =>
      assert(
        run(s = Bytes(Vector.fill(n)(0.toByte))).swap.toOption.get.isInstanceOf[Failure.Malformed]
      )
    )
    Vector(run(k = null), run(h = null), run(s = null)).foreach(r =>
      assert(r.swap.toOption.get.isInstanceOf[Failure.Malformed])
    )
    assert(HotKesKey32.fromBytes(null).swap.toOption.get.isInstanceOf[Failure.Malformed])
  }
  test("checked certificate constructors reject each null; no public constructor or copy") {
    val c = cert(rows.head)
    Vector(
      Certificate.create(null, c.counter, c.startPeriod, c.signature),
      Certificate.create(c.hotKey, null, c.startPeriod, c.signature),
      Certificate.create(c.hotKey, c.counter, null, c.signature),
      Certificate.create(c.hotKey, c.counter, c.startPeriod, null)
    ).foreach(r => assert(r.isLeft))
    assert(signableBytes(null).swap.toOption.get.isInstanceOf[Failure.Malformed])
    assert(!classOf[Certificate].getMethods.exists(_.getName == "copy"))
    assert(!classOf[HotKesKey32].getMethods.exists(_.getName == "copy"))
    assert(!classOf[UInt64].getMethods.exists(_.getName == "copy"))
  }
  test(
    "null checked envelope fails before primitive; valid certificate invokes once with exact message"
  ) {
    val r = rows.head; var calls = 0
    val primitive = (_: PublicKey32, _: Signature64, m: Bytes) => {
      calls += 1; assertEquals(m.hex, r(6)); Right(VerificationResult.SignatureVerified)
    }
    assert(verifyWith(null, cert(r), primitive).isLeft)
    assert(verifyWith(key(r), null, primitive).isLeft)
    assertEquals(calls, 0)
    assertEquals(
      verifyWith(key(r), cert(r), primitive),
      Right(Result.OperationalCertificateSignatureVerified)
    )
    assertEquals(calls, 1)
  }
  test("rejection and primitive implementation failures remain distinct") {
    val r = rows.head
    assertEquals(
      verifyWith(key(r), cert(r), (_, _, _) => Right(VerificationResult.SignatureRejected)),
      Right(Result.SignatureRejected)
    )
    assertEquals(
      verifyWith(
        key(r),
        cert(r),
        (_, _, _) => Left(VerificationError.ImplementationFailure("provider"))
      ),
      Left(Failure.InternalFailure("provider"))
    )
    Vector(null, Right(null), Left(null)).foreach(out =>
      assert(
        verifyWith(key(r), cert(r), (_, _, _) => out).swap.toOption.get
          .isInstanceOf[Failure.InternalFailure]
      )
    )
  }
  test(
    "malformed Bytes programming misuse and nonfatal exceptions map internally; fatal propagates"
  ) {
    val r = rows.head
    assert(
      HotKesKey32.fromBytes(Bytes(null)).swap.toOption.get.isInstanceOf[Failure.InternalFailure]
    )
    Vector(
      verify(Bytes(null), bytes(r(2)), 7, 505, bytes(r(5))),
      verify(bytes(r(1)), Bytes(null), 7, 505, bytes(r(5))),
      verify(bytes(r(1)), bytes(r(2)), 7, 505, Bytes(null))
    ).foreach(out => assert(out.swap.toOption.get.isInstanceOf[Failure.InternalFailure]))
    assert(
      verifyWith(
        key(r),
        cert(r),
        (_, _, _) => throw new IllegalStateException("boom")
      ).swap.toOption.get.isInstanceOf[Failure.InternalFailure]
    )
    val fatal = new LinkageError("fatal"); var caught: Throwable = null
    try verifyWith(key(r), cert(r), (_, _, _) => throw fatal)
    catch case e: LinkageError => caught = e
    assertEquals(caught, fatal)
  }
  test("input and exported arrays cannot mutate checked certificate or serialization") {
    val r = rows.head; val arrays = Vector(1, 2, 5).map(i => bytes(r(i)).toArray)
    val owned = arrays.map(Bytes.fromArray)
    val k = PublicKey32.create(owned(0)).toOption.get
    val c = Certificate.fromBytes(owned(1), BigInt(r(3)), BigInt(r(4)), owned(2)).toOption.get
    arrays.foreach(a => a(0) = (a(0) ^ 1).toByte)
    Vector(
      k.toArray,
      c.hotKey.bytes.toArray,
      c.signature.toArray,
      signableBytes(c).toOption.get.toArray
    ).foreach(a => a(0) = (a(0) ^ 1).toByte)
    assertEquals(signableBytes(c).toOption.get.hex, r(6))
    assertEquals(label(verifySignature(k, c)), "VERIFIED")
  }
  test("concurrent calls preserve shared immutable input and private digest state") {
    val pool = Executors.newFixedThreadPool(4)
    try
      val r = rows.head; val k = key(r); val c = cert(r)
      val jobs = (0 until 16).map(_ =>
        pool.submit(new Callable[String] { def call(): String = label(verifySignature(k, c)) })
      )
      jobs.foreach(j => assertEquals(j.get(30, TimeUnit.SECONDS), "VERIFIED"))
    finally pool.shutdownNow()
  }
