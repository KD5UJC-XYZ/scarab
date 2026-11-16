// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import java.util.concurrent.atomic.AtomicInteger
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.vrf.{
  Draft03Prover,
  PraosLeaderThreshold as Leader,
  PraosVrfCertificate as Vrf
}
import xyz.kd5ujc.scarab.ledger.{ForgeError, Nonce, VrfCert}

/** The leader check evaluates the VRF output for every slot and proves only a won slot: its
  * decision and its certificate are those of the full proof, which the reference evaluates for
  * every slot.
  */
class LeadershipSuite extends ScalaCheckSuite:
  private def key(seed: Seq[Int]): Draft03Prover.SigningKey =
    Draft03Prover.SigningKey
      .fromSeed(Bytes.fromArray(seed.map(_.toByte).toArray))
      .fold(e => fail(e.toString), identity)

  private def fraction(n: BigInt, d: BigInt): Leader.Fraction =
    Leader.Fraction.checked(n, d).fold(e => fail(e.message), identity)

  private val fixedKey = key(Seq.fill(32)(9))
  private val epochNonce: Nonce = Nonce.Hash(Blake2b.hash256.hash(Bytes(Vector(7))))
  private val twentieth = fraction(1, 20)

  /** What the check gave before it proved only won slots: the full proof of the slot, its output's
    * leader value against the threshold, and the proved certificate if the slot is won.
    */
  private def fullProof(
      slot: BigInt,
      nonce: Nonce,
      k: Draft03Prover.SigningKey,
      stake: BigInt,
      total: BigInt,
      active: Leader.Fraction
  ): Either[ForgeError, VrfCert] =
    val epoch = nonce match
      case Nonce.Neutral => Vrf.NeutralNonce
      case Nonce.Hash(b) => Vrf.Hash32.fromBytes(b).fold(e => fail(e.toString), identity)
    val alpha = Vrf.Slot
      .fromBigInt(slot)
      .flatMap(s => Vrf.Input.create(s, epoch))
      .flatMap(Vrf.alpha)
      .fold(e => fail(e.toString), identity)
    val proved = k.prove(alpha).fold(e => fail(e.toString), identity)
    val value = Leader.leaderValue(proved.output).fold(e => fail(e.message), identity)
    val share = if stake <= 0 then fraction(0, 1) else fraction(stake, total)
    Leader.check(value, share, active).fold(e => fail(e.message), identity) match
      case Leader.Decision.Eligible => Right(VrfCert(proved.output, proved.proof))
      case Leader.Decision.Ineligible | Leader.Decision.IterationLimit =>
        Left(ForgeError.NotLeader(slot))

  /** The key's evaluations, each counted. */
  private final class Counting(k: Draft03Prover.SigningKey) extends Leadership.Evaluator:
    private val outputCount = new AtomicInteger(0)
    private val proofCount = new AtomicInteger(0)
    def outputs: Int = outputCount.get
    def proofs: Int = proofCount.get
    def output(alpha: Bytes): Either[Draft03Prover.Failure, Bytes] =
      outputCount.incrementAndGet()
      k.output(alpha)
    def prove(alpha: Bytes): Either[Draft03Prover.Failure, Draft03Prover.Proved] =
      proofCount.incrementAndGet()
      k.prove(alpha)

  test("over many slots, the decision and the certificate are the full proof's") {
    val slots = (0 until 1000).map(BigInt(_))
    val results = slots.map { slot =>
      val obtained = Leadership.check(slot, epochNonce, fixedKey, 1, 1, twentieth)
      assertEquals(
        obtained,
        fullProof(slot, epochNonce, fixedKey, 1, 1, twentieth),
        s"slot $slot"
      )
      obtained
    }
    val won = results.count(_.isRight)
    assert(won > 0, "no slot won: the comparison saw no certificate")
    assert(won < slots.size, "every slot won: the comparison saw no lost slot")
  }

  property("for any key, nonce, slot and stake, the check is the full proof's") {
    val nonce = Gen.oneOf(
      Gen.const(Nonce.Neutral),
      Gen
        .listOfN(32, Gen.choose(0, 255))
        .map(b => Nonce.Hash(Bytes.fromArray(b.map(_.toByte).toArray)))
    )
    forAll(
      Gen.listOfN(32, Gen.choose(0, 255)),
      nonce,
      Gen.choose(0L, Long.MaxValue),
      Gen.choose(0, 10)
    ) { (seed, n, slot, stake) =>
      val k = key(seed)
      val half = fraction(1, 2)
      assertEquals(
        Leadership.check(slot, n, k, stake, 10, half),
        fullProof(slot, n, k, stake, 10, half)
      )
    }
  }

  test("a lost slot builds no proof, and a won slot exactly one") {
    val counted = (0 until 1000).map { s =>
      val slot = BigInt(s)
      val counting = Counting(fixedKey)
      val result = Leadership.decide(slot, epochNonce, counting, 1, 1, twentieth)
      result match
        case Right(_) => assertEquals(counting.proofs, 1, s"proofs built in won slot $slot")
        case Left(ForgeError.NotLeader(_)) =>
          assertEquals(counting.proofs, 0, s"proofs built in lost slot $slot")
        case Left(e) => fail(s"slot $slot: $e")
      assertEquals(counting.outputs, 1, s"outputs evaluated in slot $slot")
      result
    }
    assert(counted.exists(_.isRight), "no slot won")
    assert(counted.exists(_.isLeft), "no slot lost")
  }

  test("a failed output, or a won slot's failed proof, is a VRF proof error") {
    val boom = Draft03Prover.Failure.InternalFailure(new IllegalStateException("evaluation"))
    val noOutput = new Leadership.Evaluator:
      def output(alpha: Bytes): Either[Draft03Prover.Failure, Bytes] = Left(boom)
      def prove(alpha: Bytes): Either[Draft03Prover.Failure, Draft03Prover.Proved] =
        fixedKey.prove(alpha)
    val noProof = new Leadership.Evaluator:
      def output(alpha: Bytes): Either[Draft03Prover.Failure, Bytes] = fixedKey.output(alpha)
      def prove(alpha: Bytes): Either[Draft03Prover.Failure, Draft03Prover.Proved] = Left(boom)
    val every = fraction(1, 1)
    assertEquals(
      Leadership.decide(1, epochNonce, noOutput, 1, 1, every),
      Left(ForgeError.VrfProof(boom))
    )
    assertEquals(
      Leadership.decide(1, epochNonce, noProof, 1, 1, every),
      Left(ForgeError.VrfProof(boom))
    )
  }
