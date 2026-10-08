// SPDX-License-Identifier: Apache-2.0
package lab.header

import java.nio.file.{Files, Path}
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.vrf.{PraosLeaderThreshold as Leader, PraosVrfCertificate as Vrf}

class PraosEligibilitySuite extends munit.FunSuite:
  private def get[A](e: Either[String, A]): A = e.fold(fail(_), identity)
  private def arr(n: Node) = n.value.asInstanceOf[Value.Arr].value
  private def bytes(n: Node) = n.value.asInstanceOf[Value.ByteString].value
  private def uint(n: Node) = n.value.asInstanceOf[Value.UInt].value
  private val raw =
    Bytes.fromArray(Files.readAllBytes(Path.of("fixtures/opcert/evidence/preprod_70070426.cbor")))
  assertEquals(
    Bytes.fromArray(java.security.MessageDigest.getInstance("SHA-256").digest(raw.toArray)).hex,
    "620a8f9ae54e5ec1dc1d369a8f6d045b4bf5bd3d04b81ba5be597a5088a45e0d"
  )
  private val body = arr(arr(get(Cbor.decode(raw))).head)
  private val issuer = Blake2b.hash224.hash(bytes(body(3)))
  private val source = Bytes(Vector.fill(32)(1.toByte))
  private val cert = get(
    PraosCertificateState.Context.checked(
      source,
      source,
      0,
      99999999,
      129600,
      62,
      Map(issuer -> Blake2b.hash256.hash(bytes(body(4))))
    )
  )
  private val anchor =
    PraosCertificateState.Point(bytes(body(2)), uint(body(1)) - 1, uint(body(0)) - 1)
  private val seed = get(
    PraosCertificateState.seed(cert, anchor, Map(issuer -> uint(arr(body(8))(1))), source)
  )
  private val applied = get(
    PraosCertificateState.applyHeader(cert, seed, raw, Blake2b.hash256.hash(raw))
  )
  private val nonce = Vrf.Hash32
    .fromBytes(
      get(Bytes.fromHex("b2853ec951e7ed91b674a47c8276189f414e22b19d61d9da0ac7490801e4bf0d"))
    )
    .toOption
    .get
  private val one = get(Leader.Fraction.checked(1, 1))
  private def context(
      n: Vrf.EpochNonce = nonce,
      stake: Leader.Fraction = one,
      f: Leader.Fraction = one
  ): PraosEligibility.Context = get(
    PraosEligibility.Context.checked(cert, seed, 0, 100000000, n, f, Map(issuer -> stake), source)
  )
  test("pinned public VRF proof succeeds under explicitly synthetic stake/epoch context") {
    val result = get(PraosEligibility.check(context(), Vector(applied)))
    assert(result.suppliedContextEligibilityVerified)
    assert(!result.stateDerivedConsensus && !result.referenceRuntimeParity)
    assertEquals(result.headers.head.headerHash, applied.after.tip.hash)
  }
  test("wrong epoch nonce rejects the proof, never becoming a supplied success") {
    val wrong = Vrf.Hash32.fromBytes(source).toOption.get
    assert(
      PraosEligibility
        .check(context(wrong), Vector(applied))
        .left
        .toOption
        .get
        .contains("VRF certificate rejected")
    )
    assert(PraosEligibility.check(context(Vrf.NeutralNonce), Vector(applied)).isLeft)
  }
  test("stake zero rejects normal f while required branch and epoch bindings fail closed") {
    assert(
      PraosEligibility
        .check(
          context(
            stake = get(Leader.Fraction.checked(0, 1)),
            f = get(Leader.Fraction.checked(1, 20))
          ),
          Vector(applied)
        )
        .left
        .toOption
        .get
        .contains("threshold rejected")
    )
    assert(
      PraosEligibility.Context
        .checked(cert, seed, 1, 100000000, nonce, one, Map(issuer -> one), source)
        .isLeft
    )
    assert(
      PraosEligibility.Context
        .checked(cert, seed, 0, 99999999, nonce, one, Map(issuer -> one), source)
        .isLeft
    )
    assert(PraosEligibility.check(context(), Vector(applied, applied)).isLeft)
    assert(PraosEligibility.check(context(), Vector.empty).isLeft)
  }
  test("wrong VRF registration and malformed/missing context cannot be paired with steps") {
    val wrong = get(
      PraosCertificateState.Context
        .checked(source, source, 0, 99999999, 129600, 62, Map(issuer -> source))
    )
    val wrongSeed = get(PraosCertificateState.seed(wrong, anchor, seed.counters, source))
    val wrongContext = get(
      PraosEligibility.Context
        .checked(wrong, wrongSeed, 0, 100000000, nonce, one, Map(issuer -> one), source)
    )
    assert(PraosEligibility.check(wrongContext, Vector(applied)).isLeft)
    assert(
      PraosEligibility.Context
        .checked(cert, seed, 0, 100000000, null, one, Map(issuer -> one), source)
        .isLeft
    )
    assert(
      PraosEligibility.Context
        .checked(cert, seed, 0, 100000000, nonce, one, Map.empty, source)
        .isLeft
    )
    assert(
      PraosEligibility.Context
        .checked(cert, seed, 0, 100000000, nonce, one, Map(issuer -> one), Bytes.empty)
        .isLeft
    )
  }
  test("same proof at a different slot is rejected by the reused VRF primitive") {
    val proof = arr(body(5))
    val input =
      Vrf.Input.create(Vrf.Slot.fromBigInt(uint(body(1)) + 1).toOption.get, nonce).toOption.get
    assert(
      !Vrf
        .verify(input, bytes(body(4)), bytes(proof(1)), bytes(proof(0)))
        .isInstanceOf[Vrf.Result.VerifiedCertificate]
    )
  }
