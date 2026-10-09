// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.header.{PraosCertificateState as Certificate, PraosNonceEvolution as Nonces}
import scala.util.control.NonFatal

/** Explicit source binding; absence of previousEpochNonce remains unknown, not neutral. */
object PraosNonceSnapshot:
  final case class Snapshot(lastSlot: BigInt, fields: Nonces.Fields)
  final case class Prepared(context: Nonces.Context, seed: Nonces.State)
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def checked[A](a: => A): Either[String, A] =
    try Right(a)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def source(raw: Bytes, pin: Bytes): ReferenceJson.Json =
    require(
      raw != null && raw.value != null && raw.size <= 4194304 && pin != null && pin.value != null && pin.size == 32,
      "bounded nonce source and digest required"
    )
    require(ClusterHeaderObservation.sha256(raw) == pin, "nonce source digest mismatch")
    ReferenceJson.parse(raw)
  private def nonce(j: ReferenceJson.Json): Nonces.Nonce = j match
    case ReferenceJson.Json.Lit("null") => Nonces.Nonce.Neutral
    case ReferenceJson.Json.Str(s) =>
      val b = get(Bytes.fromHex(s))
      require(b.size == 32 && b.hex == s, "canonical nonce hash required")
      Nonces.Nonce.Hash(b)
    case _ => throw new IllegalArgumentException("explicit nonce hash or null required")
  def parse(raw: Bytes, pin: Bytes): Either[String, Snapshot] = checked {
    val json = source(raw, pin)
    import ReferenceJson.{field, uint}
    val previous = json match
      case ReferenceJson.Json.Obj(values) => values.get("previousEpochNonce").map(nonce)
      case _ => throw new IllegalArgumentException("nonce snapshot object required")
    val slot = uint(field(json, "lastSlot"))
    require(slot <= ((BigInt(1) << 64) - 1), "nonce snapshot Word64 slot")
    Snapshot(
      slot,
      Nonces.Fields(
        nonce(field(json, "evolvingNonce")),
        nonce(field(json, "candidateNonce")),
        nonce(field(json, "epochNonce")),
        previous,
        nonce(field(json, "labNonce")),
        nonce(field(json, "lastEpochBlockNonce"))
      )
    )
  }
  private def coefficient(j: ReferenceJson.Json): (BigInt, BigInt) = j match
    case ReferenceJson.Json.Num(text) =>
      val d = new java.math.BigDecimal(text)
      require(d.scale >= -64 && d.scale <= 64, "nonce coefficient exponent bound")
      val n = BigInt(d.unscaledValue)
      val (a, b) =
        if d.scale >= 0 then (n, BigInt(10).pow(d.scale))
        else (n * BigInt(10).pow(-d.scale), BigInt(1))
      require(a > 0 && a <= b, "nonce active coefficient range")
      val gcd = a.gcd(b)
      (a / gcd, b / gcd)
    case _ => throw new IllegalArgumentException("numeric nonce coefficient required")

  /** Genesis digest must match the existing certificate context. Snapshot slot must match its seed.
    * Stable query brackets and era identity are acquisition requirements, not inferred here.
    */
  def bind(
      certificates: Certificate.Context,
      seed: Certificate.State,
      genesisBytes: Bytes,
      protocolBytes: Bytes,
      protocolSha256: Bytes
  ): Either[String, Prepared] = checked {
    require(certificates != null && seed != null, "nonce certificate context required")
    val snapshot = get(parse(protocolBytes, protocolSha256))
    val counters = ReferenceJson.field(source(protocolBytes, protocolSha256), "oCertCounters") match
      case ReferenceJson.Json.Obj(values) =>
        values.map { (key, value) =>
          val hash = get(Bytes.fromHex(key))
          require(hash.size == 28 && hash.hex == key, "canonical nonce counter issuer")
          hash -> ReferenceJson.uint(value)
        }
      case _ => throw new IllegalArgumentException("nonce counter map required")
    get(bindDecoded(certificates, seed, genesisBytes, snapshot, counters, protocolSha256))
  }

  /** Shared checked binding for already decoded protocol fields. The caller must bind the original
    * protocol digest to its acquisition evidence; matching fields alone do not prove that
    * provenance. JSON parsing retains its previous optional-previous-epoch semantics.
    */
  private[lab] def bindDecoded(
      certificates: Certificate.Context,
      seed: Certificate.State,
      genesisBytes: Bytes,
      snapshot: Snapshot,
      counters: Map[Bytes, BigInt],
      protocolSha256: Bytes
  ): Either[String, Prepared] = checked {
    require(certificates != null && seed != null, "nonce certificate context required")
    require(snapshot != null && counters != null, "decoded nonce snapshot/counters required")
    val genesis = source(genesisBytes, certificates.genesisDigest)
    require(snapshot.lastSlot == seed.tip.slot, "nonce snapshot anchor slot mismatch")
    require(counters == seed.counters, "nonce/certificate counter snapshot mismatch")
    import ReferenceJson.{field, uint}
    val (n, d) = coefficient(field(genesis, "activeSlotsCoeff"))
    val context = get(
      Nonces.Context.checked(
        certificates,
        uint(field(genesis, "epochLength")),
        uint(field(genesis, "securityParam")),
        n,
        d
      )
    )
    Prepared(context, get(Nonces.seed(context, seed, snapshot.fields, protocolSha256)))
  }
