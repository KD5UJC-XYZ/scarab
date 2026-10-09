// SPDX-License-Identifier: Apache-2.0
package lab.header

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.vrf.{PraosLeaderThreshold as Leader, PraosVrfCertificate as Vrf}
import scala.util.control.NonFatal

/** Checked eligibility under explicitly supplied epoch nonce/stake state. Requires an authenticated
  * certificate-step prefix from the named seed. This never derives consensus nonce/stake evolution.
  */
object PraosEligibility:
  final class Context private[PraosEligibility] (
      val id: Bytes,
      val certificates: PraosCertificateState.Context,
      val seed: PraosCertificateState.State,
      val epoch: BigInt,
      val nonce: Vrf.EpochNonce,
      val active: Leader.Fraction,
      val stakes: Map[Bytes, Leader.Fraction],
      private[PraosEligibility] val predecessorContextId: Bytes
  )
  object Context:
    def checked(
        certificates: PraosCertificateState.Context,
        seed: PraosCertificateState.State,
        epoch: BigInt,
        epochLength: BigInt,
        nonce: Vrf.EpochNonce,
        active: Leader.Fraction,
        stakes: Map[Bytes, Leader.Fraction],
        protocolDigest: Bytes
    ): Either[String, Context] =
      checkedWithPredecessor(
        certificates,
        seed,
        epoch,
        epochLength,
        nonce,
        active,
        stakes,
        protocolDigest,
        certificates,
        false
      )

    private[lab] def checkedSuccessor(
        previousCertificates: PraosCertificateState.Context,
        certificates: PraosCertificateState.Context,
        seed: PraosCertificateState.State,
        epoch: BigInt,
        epochLength: BigInt,
        nonce: Vrf.EpochNonce,
        active: Leader.Fraction,
        stakes: Map[Bytes, Leader.Fraction],
        protocolDigest: Bytes
    ): Either[String, Context] =
      checkedWithPredecessor(
        certificates,
        seed,
        epoch,
        epochLength,
        nonce,
        active,
        stakes,
        protocolDigest,
        previousCertificates,
        true
      )

    private def checkedWithPredecessor(
        certificates: PraosCertificateState.Context,
        seed: PraosCertificateState.State,
        epoch: BigInt,
        epochLength: BigInt,
        nonce: Vrf.EpochNonce,
        active: Leader.Fraction,
        stakes: Map[Bytes, Leader.Fraction],
        protocolDigest: Bytes,
        previous: PraosCertificateState.Context,
        successor: Boolean
    ): Either[String, Context] = protect {
      require(
        certificates != null && previous != null && seed != null && previous.id == seed.contextId,
        "eligibility certificate context mismatch"
      )
      require(
        epoch != null && epoch >= 0 && epochLength != null && epochLength > 0 &&
          epochLength <= ((BigInt(1) << 64) - 1),
        "invalid eligibility epoch"
      )
      require(
        certificates.firstSlot == epoch * epochLength &&
          certificates.lastSlot == (epoch + 1) * epochLength - 1 &&
          seed.tip.slot >= previous.firstSlot && seed.tip.slot <= previous.lastSlot,
        "eligibility epoch/window/anchor mismatch"
      )
      if successor then
        require(
          get(
            PraosCertificateState.Context.checkedSuccessor(
              previous,
              certificates.registrationDigest,
              certificates.registrations
            )
          ).id == certificates.id,
          "invalid successor eligibility certificate context"
        )
      require(nonce != null && active != null, "missing nonce or active coefficient")
      get(Leader.check(0, active, active)) // checked coefficient domain, not a leader assertion
      require(
        protocolDigest != null && protocolDigest.value != null && protocolDigest.size == 32,
        "protocol source digest required"
      )
      require(
        stakes != null && stakes.keySet == certificates.registrations.keySet &&
          stakes.values.forall(_ != null),
        "stake map must match required pool distribution"
      )
      val nonceValue = nonce match
        case Vrf.NeutralNonce => Value.Null
        case hash: Vrf.Hash32 => Value.ByteString(hash.bytes)
      def fraction(f: Leader.Fraction): Value = Value.Arr(
        Vector(
          Node(Value.UInt(f.numerator), Bytes.empty),
          Node(Value.UInt(f.denominator), Bytes.empty)
        )
      )
      val pools = Value.Map(
        stakes.toVector
          .sortBy(_._1.hex)
          .map((k, v) => Node(Value.ByteString(k), Bytes.empty) -> Node(fraction(v), Bytes.empty))
      )
      val values = Vector(
        Value.Text("supplied-praos-eligibility-v1"),
        Value.ByteString(certificates.id),
        Value.ByteString(seed.id),
        Value.ByteString(protocolDigest),
        Value.UInt(epoch),
        Value.UInt(epochLength),
        nonceValue,
        fraction(active),
        pools
      )
      val id =
        Blake2b.hash256.hash(get(Cbor.encode(Value.Arr(values.map(v => Node(v, Bytes.empty))))))
      new Context(id, certificates, seed, epoch, nonce, active, stakes, previous.id)
    }
  final case class HeaderResult(headerHash: Bytes, leaderValue: BigInt, stake: Leader.Fraction)
  final class Checked private[PraosEligibility] (
      val contextId: Bytes,
      val headers: Vector[HeaderResult],
      val historicallyTrusted: Boolean = false
  ):
    val suppliedContextEligibilityVerified = true
    val freshlyVerified = !historicallyTrusted
    val stateDerivedConsensus = false
    val referenceRuntimeParity = false

  private[lab] final case class LocalImage(
      contextId: Bytes,
      headerHash: Bytes,
      leaderValue: BigInt,
      numerator: BigInt,
      denominator: BigInt
  )
  private[lab] def localImage(value: Checked): Either[String, LocalImage] = protect {
    require(value.headers.size == 1, "single anchor eligibility result required")
    val h = value.headers.head
    LocalImage(value.contextId, h.headerHash, h.leaderValue, h.stake.numerator, h.stake.denominator)
  }

  /** Historical local attestation, never a fresh anchor VRF/certificate verification. */
  private[lab] def trustedRestoreLocal(
      image: LocalImage,
      anchorHash: Bytes,
      stakes: Map[Bytes, Leader.Fraction],
      active: Leader.Fraction
  ): Either[String, Checked] = protect {
    require(
      image.contextId.size == 32 && image.headerHash == anchorHash,
      "local eligibility binding"
    )
    val stake = get(Leader.Fraction.checked(image.numerator, image.denominator))
    require(
      stake.numerator == image.numerator && stake.denominator == image.denominator &&
        stakes.values.exists(f =>
          f.numerator == stake.numerator && f.denominator == stake.denominator
        ),
      "local eligibility stake"
    )
    require(
      get(Leader.check(image.leaderValue, stake, active)) == Leader.Decision.Eligible,
      "local eligibility threshold consistency"
    )
    new Checked(image.contextId, Vector(HeaderResult(anchorHash, image.leaderValue, stake)), true)
  }

  private def protect[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def arr(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case _             => throw new IllegalArgumentException("array required")
  private def bytes(n: Node): Bytes = n.value match
    case Value.ByteString(b) => b
    case _                   => throw new IllegalArgumentException("bytes required")

  /** Bound to this seed and exact applied prefix, not arbitrary case-class observations. */
  def check(
      context: Context,
      steps: Vector[PraosCertificateState.Applied]
  ): Either[String, Checked] = protect {
    require(
      context != null && steps != null && steps.nonEmpty && steps.size <= 8,
      "one to eight certificate steps required"
    )
    var previous = context.seed.id
    val results = steps.zipWithIndex.map { (step, index) =>
      require(
        step != null && step.before.id == previous &&
          step.before.contextId == (if index == 0 then context.predecessorContextId
                                    else context.certificates.id) &&
          step.after.contextId == context.certificates.id,
        "eligibility branch/context mismatch"
      )
      val o = step.observation
      require(
        o.slot >= context.certificates.firstSlot && o.slot <= context.certificates.lastSlot,
        "header outside supplied epoch"
      )
      val body = arr(get(Cbor.decode(o.originalBody, Cbor.Limits(65536, 8, 64, 65536))))
      val key = bytes(body(4))
      require(
        Blake2b.hash256.hash(key) == context.certificates.registrations(step.issuer),
        "eligibility VRF key binding mismatch"
      )
      val proof = arr(body(5))
      val input = Vrf.Input
        .create(Vrf.Slot.fromBigInt(o.slot).toOption.get, context.nonce)
        .fold(e => throw new IllegalArgumentException(e.toString), identity)
      val verified = Vrf.verify(input, key, bytes(proof(1)), bytes(proof(0))) match
        case Vrf.Result.VerifiedCertificate(output) => output
        case other =>
          throw new IllegalArgumentException("VRF certificate rejected: " + other.toString)
      val value = get(Leader.leaderValue(verified))
      val stake = context.stakes(step.issuer)
      get(Leader.check(value, stake, context.active)) match
        case Leader.Decision.Eligible => ()
        case other =>
          throw new IllegalArgumentException("VRF leader threshold rejected: " + other.toString)
      previous = step.after.id
      HeaderResult(o.originalHeaderHash, value, stake)
    }
    new Checked(context.id, results)
  }
