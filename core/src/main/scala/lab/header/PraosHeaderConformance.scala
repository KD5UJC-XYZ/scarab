// SPDX-License-Identifier: Apache-2.0
package lab.header

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.kes.Sum6Kes
import lab.opcert.OperationalCertificate

/** Experimental fixture adapter, never a consensus validator. Context is supplied, not derived from
  * a ledger. The reference reserializes HeaderBody for KES; this adapter only admits bodies whose
  * original bytes equal the definite, shortest-width encoding of the supported shape.
  */
object PraosHeaderConformance:
  final case class RegisteredIssuer(poolKeyHash: Bytes, vrfKeyHash: Bytes)
  final case class Context(
      expectedHeaderHash: Bytes,
      genesisHash: Bytes,
      slotsPerKesPeriod: BigInt,
      maxKesEvolutions: Int,
      expectedMajor: BigInt,
      expectedMinor: BigInt,
      registeredIssuer: Option[RegisteredIssuer]
  )
  enum IssuerBinding:
    case NotSupplied, MatchedSuppliedRegistration
  final case class Observation(
      originalHeaderHash: Bytes,
      originalBody: Bytes,
      suppliedGenesisHash: Bytes,
      slot: BigInt,
      certificateCounter: BigInt,
      startPeriod: BigInt,
      relativePeriod: Int,
      issuerBinding: IssuerBinding,
      opcert: OperationalCertificate.Result,
      kes: Sum6Kes.Result
  ):
    val consensusValidated: Boolean = false
    val unchecked: Set[String] = Set(
      "genesis-parameter-provenance",
      "registration-state-provenance",
      "reference-serialization-and-acceptance-parity",
      "vrf-proof",
      "stake",
      "epoch-nonce",
      "opcert-counter-state",
      "leadership",
      "chain-continuity",
      "body-commitment",
      "ledger-transition"
    )

  private val Word64Max = (BigInt(1) << 64) - 1
  private final case class Invalid(message: String) extends RuntimeException(message)
  private def requireThat(ok: Boolean, message: String): Unit =
    if !ok then throw Invalid(message)
  private def sized(b: Bytes, size: Int): Boolean =
    b != null && b.value != null && b.size == size
  private def word(n: BigInt): Boolean = n != null && n >= 0 && n <= Word64Max
  private def array(n: Node, size: Int): Vector[Node] = n.value match
    case Value.Arr(xs) if xs.size == size => xs
    case _                                => throw Invalid(s"expected $size-element array")
  private def uint(n: Node): BigInt = n.value match
    case Value.UInt(x) if word(x) => x
    case _                        => throw Invalid("expected uint64")
  private def bytes(n: Node, size: Int): Bytes = n.value match
    case Value.ByteString(b) if sized(b, size) => b
    case _                                     => throw Invalid(s"expected $size-byte string")

  /** Raw standalone Praos header only. Hash binding covers the entire original header. Version
    * equality is a fixture assertion, not protocol-version admission. No caller-supplied tree or
    * independently supplied signing key/message can bypass extraction from the bound bytes.
    */
  def inspect(raw: Bytes, context: Context): Either[String, Observation] =
    try
      requireThat(raw != null && raw.value != null, "null header")
      requireThat(context != null, "null context")
      requireThat(sized(context.expectedHeaderHash, 32), "expected header hash must be 32 bytes")
      requireThat(sized(context.genesisHash, 32), "supplied genesis hash must be 32 bytes")
      requireThat(
        word(context.slotsPerKesPeriod) && context.slotsPerKesPeriod > 0,
        "slots per KES period must be positive uint64"
      )
      requireThat(
        context.maxKesEvolutions > 0 && context.maxKesEvolutions <= 64,
        "Sum6 lifetime must be 1..64 periods"
      )
      requireThat(
        word(context.expectedMajor) && word(context.expectedMinor),
        "invalid expected version"
      )
      requireThat(context.registeredIssuer != null, "null registration option")
      context.registeredIssuer.foreach { issuer =>
        requireThat(
          issuer != null && sized(issuer.poolKeyHash, 28) && sized(issuer.vrfKeyHash, 32),
          "registration requires pool hash28 and VRF hash32"
        )
      }
      Cbor.decode(raw, Cbor.Limits(65536, 8, 64, 65536)).flatMap { root =>
        val h = array(root, 2)
        val body = array(h(0), 10)
        val hash = Blake2b.hash256.hash(raw)
        requireThat(hash == context.expectedHeaderHash, "original header hash mismatch")
        uint(body(0))
        val slot = uint(body(1))
        if body(2).value != Value.Null then bytes(body(2), 32)
        val cold = bytes(body(3), 32)
        val vrfKey = bytes(body(4), 32)
        val vrf = array(body(5), 2)
        bytes(vrf(0), 64)
        bytes(vrf(1), 80)
        requireThat(uint(body(6)) <= BigInt("ffffffff", 16), "body size exceeds Word32")
        bytes(body(7), 32)
        val cert = array(body(8), 4)
        val hot = bytes(cert(0), 32)
        val counter = uint(cert(1))
        val start = uint(cert(2))
        val sigma = bytes(cert(3), 64)
        val version = array(body(9), 2)
        requireThat(
          uint(version(0)) == context.expectedMajor && uint(version(1)) == context.expectedMinor,
          "fixture header version mismatch"
        )
        val signature = bytes(h(1), 448)
        val canonical = Cbor.encode(h(0).value).fold(e => throw Invalid(e), identity)
        requireThat(
          canonical == h(0).original,
          "unsupported serialization: original body differs from shortest definite encoding"
        )
        val current = slot / context.slotsPerKesPeriod
        requireThat(current >= start, "KES period precedes certificate start")
        val relative = current - start
        requireThat(relative < context.maxKesEvolutions, "KES certificate lifetime expired")
        val binding = context.registeredIssuer match
          case None => IssuerBinding.NotSupplied
          case Some(issuer) =>
            requireThat(
              Blake2b.hash224.hash(cold) == issuer.poolKeyHash,
              "registered issuer mismatch"
            )
            requireThat(
              Blake2b.hash256.hash(vrfKey) == issuer.vrfKeyHash,
              "registered VRF key mismatch"
            )
            IssuerBinding.MatchedSuppliedRegistration
        for
          opcert <- OperationalCertificate
            .verify(cold, hot, counter, start, sigma)
            .left
            .map(_.toString)
          kes <- Sum6Kes.verify(hot, relative.toInt, canonical, signature).left.map(_.toString)
        yield Observation(
          hash,
          h(0).original,
          context.genesisHash,
          slot,
          counter,
          start,
          relative.toInt,
          binding,
          opcert,
          kes
        )
      }
    catch case Invalid(message) => Left(message)
