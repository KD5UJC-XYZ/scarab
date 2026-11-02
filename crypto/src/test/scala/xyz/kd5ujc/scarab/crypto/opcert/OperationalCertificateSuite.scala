// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.opcert

import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, Executors, TimeUnit}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.witness.{
  PublicKey32,
  Signature64,
  VerificationError,
  WitnessInputError,
  VerificationResult
}

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
  test("uint64 errors retain the rejected value and the certificate field") {
    assertEquals(UInt64.fromBigInt(null), Left(UInt64.Error.MissingValue))
    val r = rows.head
    for n <- Vector(BigInt(-1), UInt64.MaxValue + 1) do
      assertEquals(UInt64.fromBigInt(n), Left(UInt64.Error.OutOfRange(n)))
      assertEquals(
        Certificate.fromBytes(bytes(r(2)), n, 0, bytes(r(5))),
        Left(Failure.InvalidCounter(UInt64.Error.OutOfRange(n)))
      )
      assertEquals(
        Certificate.fromBytes(bytes(r(2)), 0, n, bytes(r(5))),
        Left(Failure.InvalidStartPeriod(UInt64.Error.OutOfRange(n)))
      )
    assertEquals(
      Certificate.fromBytes(bytes(r(2)), null, 0, bytes(r(5))),
      Left(Failure.InvalidCounter(UInt64.Error.MissingValue))
    )
    assertEquals(
      Certificate.fromBytes(bytes(r(2)), 0, null, bytes(r(5))),
      Left(Failure.InvalidStartPeriod(UInt64.Error.MissingValue))
    )
    Vector(BigInt(0), BigInt(1), (BigInt(1) << 63) - 1, BigInt(1) << 63, UInt64.MaxValue).foreach(
      n => assertEquals(UInt64.fromBigInt(n).fold(e => fail(e.message), identity).value, n)
    )
  }
  test("wrong lengths and missing raw fields have distinct typed causes") {
    val r = rows.head
    def run(k: Bytes = bytes(r(1)), h: Bytes = bytes(r(2)), s: Bytes = bytes(r(5))) =
      verify(k, h, 7, 505, s)
    Vector(0, 31, 33).foreach { n =>
      val b = Bytes(Vector.fill(n)(0.toByte))
      assertEquals(run(k = b), Left(Failure.ColdKey(WitnessInputError.MalformedPublicKeyLength(n))))
      assertEquals(run(h = b), Left(Failure.InvalidHotKeyLength(n)))
    }
    Vector(0, 63, 65).foreach(n =>
      assertEquals(
        run(s = Bytes(Vector.fill(n)(0.toByte))),
        Left(Failure.Signature(WitnessInputError.MalformedSignatureLength(n)))
      )
    )
    assertEquals(run(k = null), Left(Failure.MissingColdKey))
    assertEquals(run(h = null), Left(Failure.MissingHotKey))
    assertEquals(run(s = null), Left(Failure.MissingSignature))
    assertEquals(HotKesKey32.fromBytes(null), Left(Failure.MissingHotKey))
  }
  test("checked constructors identify each missing component; no public constructor or copy") {
    val c = cert(rows.head)
    assertEquals(
      Certificate.create(null, c.counter, c.startPeriod, c.signature),
      Left(Failure.MissingHotKey)
    )
    assertEquals(
      Certificate.create(c.hotKey, null, c.startPeriod, c.signature),
      Left(Failure.InvalidCounter(UInt64.Error.MissingValue))
    )
    assertEquals(
      Certificate.create(c.hotKey, c.counter, null, c.signature),
      Left(Failure.InvalidStartPeriod(UInt64.Error.MissingValue))
    )
    assertEquals(
      Certificate.create(c.hotKey, c.counter, c.startPeriod, null),
      Left(Failure.MissingSignature)
    )
    assertEquals(signableBytes(null), Left(Failure.MissingCertificate))
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
    assertEquals(verifyWith(null, cert(r), primitive), Left(Failure.MissingColdKey))
    assertEquals(verifyWith(key(r), null, primitive), Left(Failure.MissingCertificate))
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
    val provider = new IllegalStateException("provider")
    assertEquals(
      verifyWith(
        key(r),
        cert(r),
        (_, _, _) => Left(VerificationError.ImplementationFailure(provider))
      ),
      Left(Failure.PrimitiveFailure(VerificationError.ImplementationFailure(provider)))
    )
    Vector(
      (null, PrimitiveReply.MissingResult),
      Right(null) -> PrimitiveReply.MissingVerdict,
      Left(null) -> PrimitiveReply.MissingError
    ).foreach { (out, why) =>
      assertEquals(
        verifyWith(key(r), cert(r), (_, _, _) => out),
        Left(Failure.PrimitiveContractViolation(why))
      )
    }
  }
  test("a nonfatal verifier failure is kept as a defect and a fatal one propagates") {
    val r = rows.head
    val defect = new IllegalStateException("boom")
    assertEquals(
      verifyWith(key(r), cert(r), (_, _, _) => throw defect),
      Left(Failure.Defect(defect))
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
