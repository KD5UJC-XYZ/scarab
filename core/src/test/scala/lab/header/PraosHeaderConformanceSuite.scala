// SPDX-License-Identifier: Apache-2.0
package lab.header

import java.nio.file.{Files, Path}
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.kes.Sum6Kes
import lab.opcert.OperationalCertificate

class PraosHeaderConformanceSuite extends munit.FunSuite:
  import PraosHeaderConformance.*
  private def arr(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case _             => fail("array expected")
  private def bs(n: Node): Bytes = n.value match
    case Value.ByteString(b) => b
    case _                   => fail("bytes expected")
  private def u(n: Node): BigInt = n.value match
    case Value.UInt(x) => x
    case _             => fail("uint expected")
  private def node(v: Value): Node = Node(v, Bytes(Vector.empty))
  private def encode(v: Value): Bytes = Cbor.encode(v).toOption.get
  private val fixtures = Vector(
    "preprod_70070331.cbor",
    "preprod_70070379.cbor",
    "preprod_70070426.cbor",
    "preprod_70070464.cbor"
  ).zip(
    Vector(
      "a0bd5600bb96ff965fbaa72d9c3d2692e7aecc3c3948a4b816263069782ed902",
      "91c7021b612c2ee0ca6e0c074870cf5b2cf44d841c1dde51ace597e011273261",
      "620a8f9ae54e5ec1dc1d369a8f6d045b4bf5bd3d04b81ba5be597a5088a45e0d",
      "ab5f2b6479634a0a014b7c39adb9583fc4228fd67932dace32bdf0172fbc417a"
    )
  ).map { case (name, pin) =>
    val raw = Files.readAllBytes(Path.of("fixtures/opcert/evidence", name))
    assertEquals(
      Bytes.fromArray(java.security.MessageDigest.getInstance("SHA-256").digest(raw)).hex,
      pin
    )
    Bytes.fromArray(raw)
  }
  private def context(raw: Bytes): Context =
    val b = arr(arr(Cbor.decode(raw).toOption.get)(0))
    val v = arr(b(9))
    Context(
      Blake2b.hash256.hash(raw),
      Bytes(Vector.fill(32)(0.toByte)),
      129600,
      62,
      u(v(0)),
      u(v(1)),
      Some(RegisteredIssuer(Blake2b.hash224.hash(bs(b(3))), Blake2b.hash256.hash(bs(b(4)))))
    )
  private val raw = fixtures.head
  private val ctx = context(raw)
  private def mutateBody(f: Vector[Node] => Vector[Node]): Bytes =
    val h = arr(Cbor.decode(raw).toOption.get)
    encode(Value.Arr(h.updated(0, node(Value.Arr(f(arr(h(0))))))))
  private def rebound(changed: Bytes): Context =
    ctx.copy(expectedHeaderHash = Blake2b.hash256.hash(changed))

  test("four admitted public headers compose both predicates without claiming consensus") {
    fixtures.foreach { input =>
      val observation = inspect(input, context(input)).toOption.get
      assertEquals(
        observation.opcert,
        OperationalCertificate.Result.OperationalCertificateSignatureVerified
      )
      assertEquals(observation.kes, Sum6Kes.Result.SuppliedMessageSignatureVerified)
      assertEquals(observation.issuerBinding, IssuerBinding.MatchedSuppliedRegistration)
      assert(!observation.consensusValidated)
      assert(observation.unchecked.contains("opcert-counter-state"))
      assert(observation.unchecked.contains("vrf-proof"))
      assertEquals(observation.originalBody, arr(Cbor.decode(input).toOption.get)(0).original)
    }
  }
  test("original header hash and supplied registration are independently bound") {
    assert(inspect(raw, ctx.copy(expectedHeaderHash = ctx.genesisHash)).isLeft)
    assert(
      inspect(
        raw,
        ctx.copy(registeredIssuer =
          Some(RegisteredIssuer(Bytes(Vector.fill(28)(0.toByte)), ctx.genesisHash))
        )
      ).isLeft
    )
    val registration = ctx.registeredIssuer.get
    assert(
      inspect(
        raw,
        ctx.copy(registeredIssuer = Some(registration.copy(vrfKeyHash = ctx.genesisHash)))
      ).isLeft
    )
    assertEquals(
      inspect(raw, ctx.copy(registeredIssuer = None)).toOption.get.issuerBinding,
      IssuerBinding.NotSupplied
    )
  }
  test("signed field mutation fails KES even with a freshly bound header hash") {
    val changed = mutateBody(b => b.updated(0, node(Value.UInt(u(b(0)) + 1))))
    assert(inspect(changed, ctx).isLeft)
    val observed = inspect(changed, rebound(changed)).toOption.get
    assertEquals(
      observed.opcert,
      OperationalCertificate.Result.OperationalCertificateSignatureVerified
    )
    assertEquals(observed.kes, Sum6Kes.Result.SignatureRejected)
  }
  test("certificate signature mutation reports both signature failures") {
    val changed = mutateBody { b =>
      val cert = arr(b(8)); val sig = bs(cert(3))
      val flipped = Bytes(sig.value.updated(0, (sig.value.head ^ 1).toByte))
      b.updated(8, node(Value.Arr(cert.updated(3, node(Value.ByteString(flipped))))))
    }
    val observed = inspect(changed, rebound(changed)).toOption.get
    assertEquals(observed.opcert, OperationalCertificate.Result.SignatureRejected)
    assertEquals(observed.kes, Sum6Kes.Result.SignatureRejected)
  }
  test("non-shortest signed body is unsupported, never silently normalized") {
    val changed = Bytes(raw.value.take(1) ++ Vector(0x98.toByte, 10.toByte) ++ raw.value.drop(2))
    assert(
      inspect(changed, rebound(changed)).left.toOption.get.contains("unsupported serialization")
    )
  }
  test("outer encoding is original-hashed but does not alter KES message") {
    val changed = Bytes(Vector(0x98.toByte, 2.toByte) ++ raw.value.drop(1))
    assert(inspect(changed, ctx).isLeft)
    assertEquals(
      inspect(changed, rebound(changed)).toOption.get.kes,
      Sum6Kes.Result.SuppliedMessageSignatureVerified
    )
  }
  test("period start, exclusive end, and local Sum6 bounds fail closed") {
    assert(inspect(raw, ctx.copy(slotsPerKesPeriod = 0)).isLeft)
    assert(inspect(raw, ctx.copy(slotsPerKesPeriod = BigInt(1) << 64)).isLeft)
    assert(inspect(raw, ctx.copy(slotsPerKesPeriod = 1000000000)).isLeft)
    val relative = inspect(raw, ctx).toOption.get.relativePeriod
    assert(inspect(raw, ctx.copy(maxKesEvolutions = relative)).isLeft)
    assert(inspect(raw, ctx.copy(maxKesEvolutions = relative + 1)).isRight)
    assert(inspect(raw, ctx.copy(maxKesEvolutions = 65)).isLeft)
    assert(inspect(raw, ctx.copy(expectedMajor = 11, expectedMinor = 2)).isLeft)
  }
  test("word32 body size, malformed input and hard allocation limit are enforced") {
    val tooLarge = mutateBody(b => b.updated(6, node(Value.UInt(BigInt(1) << 32))))
    assert(inspect(tooLarge, rebound(tooLarge)).isLeft)
    assert(inspect(null, ctx).isLeft)
    assert(inspect(raw, null).isLeft)
    assert(inspect(Bytes(Vector.fill(65537)(0.toByte)), ctx).isLeft)
    assert(inspect(Bytes(raw.value.dropRight(1)), ctx).isLeft)
  }
