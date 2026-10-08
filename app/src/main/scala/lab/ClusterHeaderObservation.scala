// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, IOApp}
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.security.MessageDigest
import lab.cbor.{Bytes, Cbor, Value}
import lab.header.PraosHeaderConformance
import lab.kes.Sum6Kes
import lab.opcert.OperationalCertificate
import lab.ledger.ClusterTransfer
import lab.network.ChainSync
import scala.util.control.NonFatal

/** Offline header predicates in the existing hash-bound private cluster context. No consensus
  * acceptance, trusted ledger registration, VRF leadership or counter-state claim.
  */
object ClusterHeaderObservation extends IOApp:
  final case class Capture(headerEnvelope: Bytes, block: Bytes)
  final class BoundContext private[ClusterHeaderObservation] (
      val transfer: ClusterTransfer.Context,
      val genesisSha256: Bytes,
      val slotsPerKesPeriod: BigInt,
      val maxKesEvolutions: Int,
      private[ClusterHeaderObservation] val registrations: Map[Bytes, Bytes]
  )
  final class HeaderObservation private[ClusterHeaderObservation] (
      val cryptography: PraosHeaderConformance.Observation
  ):
    def cryptographicPredicatesSucceeded: Boolean =
      predicatesSucceeded(cryptography.opcert, cryptography.kes)
  final class Report private[ClusterHeaderObservation] (
      val genesisSha256: Bytes,
      val headers: Vector[HeaderObservation]
  ):
    def cryptographicPredicatesSucceeded: Boolean =
      headers.nonEmpty && headers.forall(_.cryptographicPredicatesSucceeded)
    val fullHeaderValidated: Boolean = false
    val consensusValidated: Boolean = false
    val trustedLedgerRegistration: Boolean = false

  private def get[A](value: Either[String, A]): A =
    value.fold(e => throw new IllegalArgumentException(e), identity)
  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def read(path: Path): Bytes =
    val stream = Files.newInputStream(path)
    val raw =
      try stream.readNBytes(4194305)
      finally stream.close()
    require(raw.length <= 4194304, "header evidence file exceeds bound")
    Bytes.fromArray(raw)
  private[lab] def sha256(raw: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(raw.toArray))
  private def key(value: String, size: Int): Bytes =
    val result = get(Bytes.fromHex(value))
    require(result.size == size && result.hex == value, "canonical lowercase key hash required")
    result

  /** Package-visible test seam; production callers use load, which reuses the full v2 loader. All
    * timing and registration values are extracted after checking the original source digest.
    */
  private[lab] def bindGenesis(raw: Bytes, context: ClusterTransfer.Context): BoundContext =
    require(sha256(raw) == context.genesisDigest, "header genesis source digest mismatch")
    val genesis = ReferenceJson.parse(raw)
    import ReferenceJson.{field, uint, string}
    require(string(field(genesis, "networkId")) == "Testnet", "testnet genesis required")
    require(
      uint(field(genesis, "networkMagic")) == context.networkMagic,
      "genesis network mismatch"
    )
    val slots = uint(field(genesis, "slotsPerKESPeriod"))
    val lifetime = uint(field(genesis, "maxKESEvolutions"))
    require(slots > 0 && slots <= (BigInt(1) << 64) - 1, "invalid genesis KES period length")
    require(lifetime > 0 && lifetime <= 64, "unsupported genesis Sum6 lifetime")
    val pools = field(genesis, "extraConfig", "stakePools", "data") match
      case ReferenceJson.Json.Obj(fields) => fields
      case _ => throw new IllegalArgumentException("genesis pool object required")
    require(pools.nonEmpty && pools.size <= 10000, "bounded genesis pool registration required")
    val registrations = pools.map { case (poolId, pool) =>
      val id = key(poolId, 28)
      require(key(string(field(pool, "poolId")), 28) == id, "genesis pool ID binding mismatch")
      id -> key(string(field(pool, "vrf")), 32)
    }
    new BoundContext(context, context.genesisDigest, slots, lifetime.toInt, registrations)

  def load(directory: Path): Either[String, BoundContext] = checked {
    val input = ClusterTransferCommand.load(directory)
    // Re-read and re-hash: a source changed after the loader cannot supply different parameters.
    bindGenesis(read(directory.resolve("transfer-genesis.md")), input.context)
  }

  def captures(path: Path): Either[String, Vector[Capture]] = checked {
    val text =
      StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(read(path).toArray)).toString
    text.linesIterator
      .filter(_.nonEmpty)
      .flatMap { line =>
        val record = ReferenceJson.parse(Bytes.fromArray(line.getBytes(StandardCharsets.UTF_8)))
        record match
          case ReferenceJson.Json.Obj(fields)
              if fields.get("record").contains(ReferenceJson.Json.Str("transfer-range-block")) =>
            import ReferenceJson.{field, string}
            Some(
              Capture(
                get(Bytes.fromHex(string(field(record, "headerEnvelopeHex")))),
                get(Bytes.fromHex(string(field(record, "rawBlockHex"))))
              )
            )
          case _ => None
      }
      .toVector match
      case found if found.nonEmpty && found.size <= 8 => found
      case _ => throw new IllegalArgumentException("one to eight captured blocks required")
  }

  private[lab] def predicatesSucceeded(
      opcert: OperationalCertificate.Result,
      kes: Sum6Kes.Result
  ): Boolean =
    opcert == OperationalCertificate.Result.OperationalCertificateSignatureVerified &&
      kes == Sum6Kes.Result.SuppliedMessageSignatureVerified

  private[lab] def observeHeader(
      context: BoundContext,
      envelope: Bytes
  ): Either[String, HeaderObservation] = checked {
    val header = get(ReferenceCaptureCommand.header(envelope))
    val root = get(Cbor.decode(header.raw, Cbor.Limits(4096, 16, 256, 4096)))
    val cold = root.value match
      case Value.Arr(h) =>
        h.head.value match
          case Value.Arr(body) =>
            body(3).value match
              case Value.ByteString(value) => value
              case _ => throw new IllegalArgumentException("issuer key required")
          case _ => throw new IllegalArgumentException("header body required")
      case _ => throw new IllegalArgumentException("header required")
    val issuer = Blake2b.hash224.hash(cold)
    val vrf = context.registrations.getOrElse(
      issuer,
      throw new IllegalArgumentException("issuer absent from hash-bound genesis registration")
    )
    val crypto = get(
      PraosHeaderConformance.inspect(
        header.raw,
        PraosHeaderConformance.Context(
          header.hash,
          context.genesisSha256,
          context.slotsPerKesPeriod,
          context.maxKesEvolutions,
          header.major,
          header.minor,
          Some(PraosHeaderConformance.RegisteredIssuer(issuer, vrf))
        )
      )
    )
    new HeaderObservation(crypto)
  }

  def inspect(context: BoundContext, captures: Vector[Capture]): Either[String, Report] = checked {
    require(captures.nonEmpty && captures.size <= 8, "one to eight captured blocks required")
    var previous: ChainSync.Point = ChainSync.Point.Block(
      get(ChainSync.UInt64.from(context.transfer.preSlot)),
      context.transfer.preHash
    )
    val observations = captures.map { capture =>
      // Reparse the envelope; never trust a caller-constructed Header case class.
      val header = get(ReferenceCaptureCommand.header(capture.headerEnvelope))
      get(ReferenceCaptureCommand.compare(header, capture.block, previous))
      val observation = get(observeHeader(context, capture.headerEnvelope))
      previous = ChainSync.Point.Block(get(ChainSync.UInt64.from(header.slot)), header.hash)
      observation
    }
    require(
      previous == ChainSync.Point
        .Block(get(ChainSync.UInt64.from(context.transfer.postSlot)), context.transfer.postHash),
      "captured range does not reach bound post point"
    )
    new Report(context.genesisSha256, observations)
  }

  def render(report: Report): String =
    val rows = report.headers
      .map { h =>
        val c = h.cryptography
        s"""{"headerHash":"${c.originalHeaderHash.hex}","opcert":"${c.opcert}","kes":"${c.kes}","relativeKesPeriod":${c.relativePeriod},"cryptographicPredicatesSucceeded":${h.cryptographicPredicatesSucceeded}}"""
      }
      .mkString(",")
    s"""{"scope":"hash-bound-genesis-header-observation","genesisSha256":"${report.genesisSha256.hex}","cryptographicPredicatesSucceeded":${report.cryptographicPredicatesSucceeded},"headers":[$rows],"genesisTimingSourceBound":true,"genesisRegistrationSourceBound":true,"trustedLedgerRegistration":false,"referenceSerializationAcceptanceParity":false,"vrfLeadershipChecked":false,"vrfProofChecked":false,"stakeChecked":false,"epochNonceChecked":false,"referenceSnapshotAtomic":false,"ledgerValidated":false,"opcertCounterStateChecked":false,"fullHeaderValidated":false,"consensusValidated":false}"""

  def run(args: List[String]): IO[ExitCode] = args match
    case List(directory) =>
      IO.blocking {
        val dir = Path.of(directory)
        for
          context <- load(dir)
          captured <- captures(dir.resolve("scala-transfer.md"))
          report <- inspect(context, captured)
        yield report
      }.flatMap {
        case Left(error) => IO.println("ERROR: " + error).as(ExitCode(2))
        case Right(report) =>
          IO.println(render(report))
            .as(if report.cryptographicPredicatesSucceeded then ExitCode.Success else ExitCode(2))
      }
    case _ => IO.println("usage: lab.ClusterHeaderObservation EVIDENCE_DIRECTORY").as(ExitCode(2))
