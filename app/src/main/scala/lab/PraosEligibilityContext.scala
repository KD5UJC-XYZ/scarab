// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.header.PraosEligibility
import lab.header.PraosCertificateState as Certificate
import lab.vrf.{PraosLeaderThreshold as Leader, PraosVrfCertificate as Vrf}
import scala.util.control.NonFatal

/** Exact-byte source binding for the explicit private same-epoch profile. The nonce, pool
  * distribution and anchor state are supplied reference exports, never locally derived consensus.
  */
object PraosEligibilityContext:
  final case class Prepared(
      context: PraosEligibility.Context,
      certificates: CertificateBranch.Prepared
  )
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def read(path: Path): Bytes =
    val in = Files.newInputStream(path)
    val data =
      try in.readNBytes(4194305)
      finally in.close()
    require(data.length <= 4194304, "eligibility source exceeds bound")
    Bytes.fromArray(data)
  private def json(raw: Bytes, expected: Bytes): ReferenceJson.Json =
    require(
      raw != null && raw.value != null && raw.size <= 4194304,
      "bounded eligibility source required"
    )
    require(ClusterHeaderObservation.sha256(raw) == expected, "eligibility source digest mismatch")
    ReferenceJson.parse(raw)
  private def hash(text: String): Bytes =
    val b = get(Bytes.fromHex(text))
    require(b.size == 32 && b.hex == text, "canonical nonce hash required")
    b

  /** Exact JSON decimal/scientific lexeme -> reduced rational; no Double conversion. */
  private[lab] def coefficient(value: ReferenceJson.Json): Leader.Fraction = value match
    case ReferenceJson.Json.Num(text) =>
      val decimal = new java.math.BigDecimal(text)
      require(
        decimal.scale >= -64 && decimal.scale <= 64,
        "coefficient exponent outside local bound"
      )
      val unscaled = BigInt(decimal.unscaledValue)
      val (n, d) =
        if decimal.scale >= 0 then (unscaled, BigInt(10).pow(decimal.scale))
        else (unscaled * BigInt(10).pow(-decimal.scale), BigInt(1))
      require(n > 0, "positive active coefficient required")
      val gcd = n.gcd(d)
      val result = get(Leader.Fraction.checked(n / gcd, d / gcd))
      get(Leader.check(0, result, result))
      result
    case _ => throw new IllegalArgumentException("numeric active coefficient required")

  def loadTransfer(
      directory: Path,
      protocolState: Bytes,
      expectedProtocolSha256: Bytes
  ): Either[String, Prepared] = checked {
    val certificates =
      get(CertificateBranch.loadTransfer(directory, protocolState, expectedProtocolSha256))
    get(
      fromBoundSources(
        certificates,
        read(directory.resolve("transfer-genesis.md")),
        read(directory.resolve("pre-ledger-state.md")),
        protocolState,
        expectedProtocolSha256
      )
    )
  }

  /** Shared pre-only parser; every original source is rehashed against its supplied binding. */
  private[lab] def fromBoundSources(
      certificates: CertificateBranch.Prepared,
      genesisBytes: Bytes,
      ledgerBytes: Bytes,
      protocolBytes: Bytes,
      protocolDigest: Bytes
  ): Either[String, Prepared] = checked {
    val genesis = json(genesisBytes, certificates.context.genesisDigest)
    val ledger = json(ledgerBytes, certificates.context.registrationDigest)
    val protocol = json(protocolBytes, protocolDigest)
    import ReferenceJson.{field, uint}
    require(
      uint(field(protocol, "lastSlot")) == certificates.seed.tip.slot,
      "protocol seed slot mismatch"
    )
    val counters = field(protocol, "oCertCounters") match
      case ReferenceJson.Json.Obj(values) =>
        values.map { (key, value) =>
          val id = get(Bytes.fromHex(key))
          require(id.size == 28 && id.hex == key, "canonical pool hash required")
          id -> uint(value)
        }
      case _ => throw new IllegalArgumentException("protocol counters required")
    val rebuilt =
      get(Certificate.seed(certificates.context, certificates.seed.tip, counters, protocolDigest))
    require(rebuilt.id == certificates.seed.id, "protocol certificate seed mismatch")
    val nonce = field(protocol, "epochNonce") match
      case ReferenceJson.Json.Lit("null") => Vrf.NeutralNonce
      case ReferenceJson.Json.Str(text)   => Vrf.Hash32.fromBytes(hash(text)).toOption.get
      case _ => throw new IllegalArgumentException("explicit epochNonce hash or null required")
    val total = uint(field(ledger, "stakeDistrib", "pdTotalActiveStake"))
    require(
      total > 0 && total <= ((BigInt(1) << 64) - 1),
      "positive bounded total active stake required"
    )
    val pools = field(ledger, "stakeDistrib", "unPoolDistr") match
      case ReferenceJson.Json.Obj(pools) => pools
      case _ => throw new IllegalArgumentException("pool distribution required")
    val stakes = pools.map { (key, pool) =>
      val n = uint(field(pool, "individualPoolStake", "numerator"))
      val d = uint(field(pool, "individualPoolStake", "denominator"))
      val stake = get(Leader.Fraction.checked(n, d))
      val amount = uint(field(pool, "individualTotalPoolStake"))
      require(amount <= total && n * total == amount * d, "stake fraction/amount mismatch")
      get(Bytes.fromHex(key)) -> stake
    }
    val context = get(
      PraosEligibility.Context.checked(
        certificates.context,
        certificates.seed,
        uint(field(ledger, "lastEpoch")),
        uint(field(genesis, "epochLength")),
        nonce,
        coefficient(field(genesis, "activeSlotsCoeff")),
        stakes,
        protocolDigest
      )
    )
    Prepared(context, certificates)
  }

  /** Publish the returned pair only after certificate state and supplied-context VRF/leader checks.
    */
  def applyPrepared(
      prepared: Prepared
  ): Either[String, (CertificateBranch.Branch, PraosEligibility.Checked)] =
    for
      branch <- CertificateBranch.replay(
        prepared.certificates.context,
        prepared.certificates.seed,
        prepared.certificates.acquisition
      )
      eligible <- PraosEligibility.check(prepared.context, branch.steps)
    yield (branch, eligible)
