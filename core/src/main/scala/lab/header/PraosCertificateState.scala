// SPDX-License-Identifier: Apache-2.0
package lab.header

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.kes.Sum6Kes
import lab.opcert.OperationalCertificate
import scala.util.control.NonFatal

/** Experimental fixed-registration-window certificate state. This advances selected authenticated
  * header predicates, not consensus: VRF eligibility and nonce/stake evolution remain unchecked.
  */
object PraosCertificateState:
  val Profile = "experimental-praos-certificate-state-v1"
  private val Max = (BigInt(1) << 64) - 1
  final case class Point(hash: Bytes, slot: BigInt, blockNo: BigInt)
  final class Context private[PraosCertificateState] (
      val id: Bytes,
      val genesisDigest: Bytes,
      val registrationDigest: Bytes,
      val firstSlot: BigInt,
      val lastSlot: BigInt,
      val slotsPerKesPeriod: BigInt,
      val maxKesEvolutions: Int,
      val registrations: Map[Bytes, Bytes]
  )
  object Context:
    /** Digests identify externally bound sources; shape checks are not proof of ledger authority.
      */
    def checked(
        genesisDigest: Bytes,
        registrationDigest: Bytes,
        firstSlot: BigInt,
        lastSlot: BigInt,
        slotsPerKesPeriod: BigInt,
        maxKesEvolutions: Int,
        registrations: Map[Bytes, Bytes]
    ): Either[String, Context] = protect {
      require(size(genesisDigest, 32) && size(registrationDigest, 32), "context digest width")
      require(word(firstSlot) && word(lastSlot) && firstSlot <= lastSlot, "context slot window")
      require(word(slotsPerKesPeriod) && slotsPerKesPeriod > 0, "KES period length")
      require(maxKesEvolutions > 0 && maxKesEvolutions <= 64, "Sum6 lifetime")
      require(
        registrations != null && registrations.nonEmpty && registrations.size <= 10000,
        "bounded nonempty pool distribution required"
      )
      require(registrations.forall((k, v) => size(k, 28) && size(v, 32)), "pool/VRF hash widths")
      val id = digest(
        Vector(
          Value.Text(Profile),
          Value.ByteString(genesisDigest),
          Value.ByteString(registrationDigest),
          Value.UInt(firstSlot),
          Value.UInt(lastSlot),
          Value.UInt(slotsPerKesPeriod),
          Value.UInt(maxKesEvolutions),
          entries(registrations.map((k, v) => k -> Value.ByteString(v)))
        )
      )
      new Context(
        id,
        genesisDigest,
        registrationDigest,
        firstSlot,
        lastSlot,
        slotsPerKesPeriod,
        maxKesEvolutions,
        registrations
      )
    }

  final class State private[PraosCertificateState] (
      val contextId: Bytes,
      val id: Bytes,
      val tip: Point,
      val counters: Map[Bytes, BigInt]
  )
  final class Applied private[PraosCertificateState] (
      val before: State,
      val after: State,
      val issuer: Bytes,
      val observation: PraosHeaderConformance.Observation
  ):
    val consensusValidated = false
    val vrfEligibilityChecked = false

  private def word(n: BigInt): Boolean = n != null && n >= 0 && n <= Max
  private def size(b: Bytes, n: Int): Boolean = b != null && b.value != null && b.size == n
  private def protect[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def array(n: Node): Vector[Node] = n.value match
    case Value.Arr(v) => v
    case _            => throw new IllegalArgumentException("array required")
  private def uint(n: Node): BigInt = n.value match
    case Value.UInt(v) => v
    case _             => throw new IllegalArgumentException("uint required")
  private def bytes(n: Node): Bytes = n.value match
    case Value.ByteString(v) => v
    case _                   => throw new IllegalArgumentException("byte string required")
  private def entries(m: Map[Bytes, Value]): Value = Value.Map(
    m.toVector
      .sortBy(_._1.hex)
      .map((k, v) => Node(Value.ByteString(k), Bytes.empty) -> Node(v, Bytes.empty))
  )
  private def digest(v: Vector[Value]): Bytes =
    Blake2b.hash256.hash(get(Cbor.encode(Value.Arr(v.map(x => Node(x, Bytes.empty))))))

  /** A non-origin anchor needs an actual supplied counter snapshot. Empty is not inferred. */
  def seed(
      context: Context,
      anchor: Point,
      counters: Map[Bytes, BigInt],
      counterSourceDigest: Bytes
  ): Either[String, State] = protect {
    require(
      context != null && anchor != null && size(anchor.hash, 32) && word(anchor.slot) &&
        word(anchor.blockNo),
      "invalid seed point"
    )
    require(size(counterSourceDigest, 32), "counter source digest width")
    require(
      counters != null && counters.size <= 10000 &&
        counters.forall((k, v) => size(k, 28) && word(v)),
      "invalid counter snapshot"
    )
    val id = digest(
      Vector(
        Value.Text("certificate-seed"),
        Value.ByteString(context.id),
        Value.ByteString(counterSourceDigest),
        Value.ByteString(anchor.hash),
        Value.UInt(anchor.slot),
        Value.UInt(anchor.blockNo),
        entries(counters.map((k, v) => k -> Value.UInt(v)))
      )
    )
    new State(context.id, id, anchor, counters)
  }

  /** Exact pinned Word64 rule, including wrapping m+1 at maxBound. No state mutation here. */
  private[header] def counterRule(
      stored: Option[BigInt],
      inPoolDistribution: Boolean,
      proposed: BigInt
  ): Either[String, BigInt] = protect {
    require(stored != null && stored.forall(word) && word(proposed), "invalid Word64 counter")
    val current = stored
      .orElse(if inPoolDistribution then Some(BigInt(0)) else None)
      .getOrElse(throw new IllegalArgumentException("NoCounterForKeyHashOCERT"))
    if current > proposed then throw new IllegalArgumentException("CounterTooSmallOCERT")
    if proposed > ((current + 1) & Max) then
      throw new IllegalArgumentException("CounterOverIncrementedOCERT")
    proposed
  }

  def applyHeader(
      context: Context,
      state: State,
      raw: Bytes,
      expectedHash: Bytes
  ): Either[String, Applied] = protect {
    require(
      context != null && state != null && context.id == state.contextId,
      "counter context mismatch"
    )
    require(size(expectedHash, 32), "header hash width")
    val h = array(get(Cbor.decode(raw, Cbor.Limits(65536, 8, 64, 65536))))
    require(h.size == 2, "header arity")
    val body = array(h(0)); require(body.size == 10, "Praos header body arity")
    val issuer = Blake2b.hash224.hash(bytes(body(3)))
    val registeredVrf = context.registrations.getOrElse(
      issuer,
      throw new IllegalArgumentException("issuer absent from required pool distribution")
    )
    val pv = array(body(9)); require(pv.size == 2, "protocol version arity")
    val o = get(
      PraosHeaderConformance.inspect(
        raw,
        PraosHeaderConformance.Context(
          expectedHash,
          context.genesisDigest,
          context.slotsPerKesPeriod,
          context.maxKesEvolutions,
          uint(pv(0)),
          uint(pv(1)),
          Some(PraosHeaderConformance.RegisteredIssuer(issuer, registeredVrf))
        )
      )
    )
    require(
      o.slot >= context.firstSlot && o.slot <= context.lastSlot,
      "outside registration window"
    )
    require(
      bytes(body(2)) == state.tip.hash && o.slot > state.tip.slot &&
        uint(body(0)) == state.tip.blockNo + 1,
      "header does not extend certificate branch"
    )
    // On the pinned 64-bit target KESPeriod is Word. Overflowing c0+maxEvo cannot pass
    // both c0<=kp and kp<c0+maxEvo. Do not inherit the wider BigInt acceptance at that edge.
    require(o.startPeriod + context.maxKesEvolutions <= Max, "KES lifetime Word overflow")
    require(
      o.opcert == OperationalCertificate.Result.OperationalCertificateSignatureVerified,
      "OpCert signature rejected"
    )
    require(o.kes == Sum6Kes.Result.SuppliedMessageSignatureVerified, "KES signature rejected")
    val n = get(counterRule(state.counters.get(issuer), true, o.certificateCounter))
    val point = Point(o.originalHeaderHash, o.slot, uint(body(0)))
    val next = new State(
      context.id,
      digest(
        Vector(
          Value.Text("certificate-apply"),
          Value.ByteString(state.id),
          Value.ByteString(point.hash)
        )
      ),
      point,
      state.counters.updated(issuer, n)
    )
    new Applied(state, next, issuer, o)
  }

  /** Undo an exact applied step, never decrement: equal counters and absent-vs-zero matter. */
  def undo(current: State, applied: Applied): Either[String, State] =
    if current == null || applied == null || current.id != applied.after.id ||
      current.contextId != applied.after.contextId
    then Left("undo branch/state mismatch")
    else Right(applied.before)
