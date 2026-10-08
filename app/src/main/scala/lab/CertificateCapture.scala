// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import lab.cbor.Bytes
import lab.header.PraosCertificateState as Certificate
import lab.network.ChainSync
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Source-bound experimental counter replay. Stable brackets are separate, non-atomic queries. */
object CertificateCapture extends IOApp:
  private[lab] val sources = Map(
    "genesisSha256" -> "transfer-genesis.md",
    "preTipsSha256" -> "pre-tips.md",
    "postTipsSha256" -> "post-tips.md",
    "preLedgerSha256" -> "pre-ledger-state.md",
    "postLedgerSha256" -> "post-ledger-state.md",
    "preProtocolSha256" -> "pre-protocol-state.md",
    "postProtocolSha256" -> "post-protocol-state.md",
    "preParametersSha256" -> "pre-parameters.md",
    "postParametersSha256" -> "post-parameters.md"
  )
  final case class Bound(
      context: Certificate.Context,
      seed: Certificate.State,
      post: Certificate.Point,
      postCounters: Map[Bytes, BigInt],
      manifestDigest: Bytes
  )
  final case class Report(bound: Bound, branch: CertificateBranch.Branch, counterChanges: Int)
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def read(path: Path): Bytes =
    val in = Files.newInputStream(path)
    val bytes =
      try in.readNBytes(4194305)
      finally in.close()
    require(bytes.length <= 4194304, "certificate source exceeds bound")
    Bytes.fromArray(bytes)
  private def text(raw: Bytes): String =
    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw.toArray)).toString
  private def hash(s: String, width: Int): Bytes =
    val b = get(Bytes.fromHex(s))
    require(b.size == width && b.hex == s, "canonical source hash required")
    b
  private def obj(j: ReferenceJson.Json): Map[String, ReferenceJson.Json] = j match
    case ReferenceJson.Json.Obj(v) => v
    case _                         => throw new IllegalArgumentException("source object required")
  private def point(p: Certificate.Point): ChainSync.Point =
    ChainSync.Point.Block(get(ChainSync.UInt64.from(p.slot)), p.hash)

  private[lab] def bind(manifest: Bytes, originals: Map[String, Bytes]): Either[String, Bound] =
    checked {
      require(manifest.size <= 8192, "manifest bound")
      val entries = text(manifest).linesIterator.map { line =>
        val columns = line.split("\t", -1)
        require(columns.length == 2 && columns.forall(_.nonEmpty), "strict manifest TSV required")
        columns(0) -> columns(1)
      }.toVector
      require(entries.map(_._1).distinct.size == entries.size, "duplicate manifest field")
      val fields = entries.toMap
      require(fields.keySet == sources.keySet + "format", "exact manifest fields required")
      require(fields("format") == "certificate-context-v1", "manifest format")
      require(originals.keySet == sources.values.toSet, "exact source files required")
      val parsed = sources.map { (name, file) =>
        val raw = originals(file)
        require(
          ClusterHeaderObservation.sha256(raw) == hash(fields(name), 32),
          "source digest mismatch: " + file
        )
        name -> ReferenceJson.parse(raw)
      }
      import ReferenceJson.{field, uint, string, array}
      val genesis = parsed("genesisSha256")
      require(
        string(field(genesis, "networkId")) == "Testnet" &&
          uint(field(genesis, "networkMagic")) == 1082026,
        "isolated reference network required"
      )
      val epochLength = uint(field(genesis, "epochLength"))
      require(epochLength > 0, "positive epoch length required")
      def endpoint(prefix: String): (Certificate.Point, BigInt) =
        val tips = array(parsed(prefix + "TipsSha256"))
        require(tips.size >= 2 && tips.size <= 32, "bounded tip brackets required")
        val points = tips.map { tip =>
          require(string(field(tip, "era")) == "Conway", "Conway tip required")
          (
            Certificate.Point(
              hash(string(field(tip, "hash")), 32),
              uint(field(tip, "slot")),
              uint(field(tip, "block"))
            ),
            uint(field(tip, "epoch"))
          )
        }
        require(points.distinct.size == 1, "unstable tip bracket")
        val (p, epoch) = points.head
        require(p.slot / epochLength == epoch, "unsupported epoch translation")
        require(
          uint(field(parsed(prefix + "LedgerSha256"), "lastEpoch")) == epoch,
          "ledger epoch mismatch"
        )
        require(
          uint(field(parsed(prefix + "ProtocolSha256"), "lastSlot")) == p.slot,
          "protocol slot mismatch"
        )
        (p, epoch)
      val (pre, epoch) = endpoint("pre")
      val (post, postEpoch) = endpoint("post")
      require(
        epoch == postEpoch && post.slot > pre.slot &&
          post.blockNo - pre.blockNo >= 2 && post.blockNo - pre.blockNo <= 8,
        "two to eight same-epoch successors required"
      )
      require(
        originals("pre-parameters.md") == originals("post-parameters.md"),
        "parameters changed"
      )
      require(
        uint(field(parsed("preParametersSha256"), "protocolVersion", "major")) == 9,
        "ledger PV9 required"
      )
      def pools(prefix: String) = obj(
        field(parsed(prefix + "LedgerSha256"), "stakeDistrib", "unPoolDistr")
      )
        .map((id, pool) => hash(id, 28) -> hash(string(field(pool, "individualPoolStakeVrf")), 32))
      val registrations = pools("pre")
      require(registrations == pools("post"), "pool distribution registration changed")
      val lifetime = uint(field(genesis, "maxKESEvolutions"))
      require(lifetime > 0 && lifetime <= 64, "Sum6 lifetime")
      val context = get(
        Certificate.Context.checked(
          hash(fields("genesisSha256"), 32),
          hash(fields("preLedgerSha256"), 32),
          epoch * epochLength,
          (epoch + 1) * epochLength - 1,
          uint(field(genesis, "slotsPerKESPeriod")),
          lifetime.toInt,
          registrations
        )
      )
      def counters(prefix: String) = obj(field(parsed(prefix + "ProtocolSha256"), "oCertCounters"))
        .map((id, n) => hash(id, 28) -> uint(n))
      val seed =
        get(Certificate.seed(context, pre, counters("pre"), hash(fields("preProtocolSha256"), 32)))
      // Validate the final full map and point with the same Word64 shape checks as the seed.
      val finalSeed = get(
        Certificate.seed(context, post, counters("post"), hash(fields("postProtocolSha256"), 32))
      )
      Bound(context, seed, post, finalSeed.counters, ClusterHeaderObservation.sha256(manifest))
    }
  def load(directory: Path): Either[String, Bound] = checked {
    get(
      bind(
        read(directory.resolve("certificate-context.md")),
        sources.values.map(file => file -> read(directory.resolve(file))).toMap
      )
    )
  }
  def captures(path: Path): Either[String, Vector[BoundedChainFollower.Original]] =
    ClusterHeaderObservation
      .captures(path)
      .map(_.map(c => BoundedChainFollower.Original(c.headerEnvelope, c.block)))

  private def assessBound(
      bound: Bound,
      originals: Vector[BoundedChainFollower.Original]
  ): Either[String, Report] = checked {
    require(originals.size >= 2 && originals.size <= 8, "two to eight originals required")
    val acquisition = get(BoundedChainFollower.checked(point(bound.seed.tip), originals))
    require(acquisition.tip == point(bound.post), "capture endpoint mismatch")
    val branch = get(CertificateBranch.replay(bound.context, bound.seed, acquisition))
    require(branch.state.tip == bound.post, "final point or block number mismatch")
    require(branch.state.counters == bound.postCounters, "full reference counter map mismatch")
    (0 to originals.size).foreach { keep =>
      val expected = if keep == 0 then bound.seed else branch.steps(keep - 1).after
      val restored = get(CertificateBranch.rollback(branch, point(expected.tip)))
      require(restored.state eq expected, "rollback did not restore exact state")
      val reapplied = originals
        .drop(keep)
        .foldLeft(restored)((b, o) => get(CertificateBranch.append(bound.context, b, o)))
      require(
        reapplied.state.id == branch.state.id && reapplied.state.counters == branch.state.counters &&
          reapplied.state.tip == branch.state.tip,
        "nondeterministic reapply"
      )
    }
    val keys = bound.seed.counters.keySet ++ branch.state.counters.keySet
    Report(
      bound,
      branch,
      keys.count(k => bound.seed.counters.get(k) != branch.state.counters.get(k))
    )
  }
  def assess(
      directory: Path,
      originals: Vector[BoundedChainFollower.Original]
  ): Either[String, Report] =
    load(directory).flatMap(assessBound(_, originals))

  def render(r: Report): String =
    val b = r.bound
    val first = r.branch.steps.head.after.tip
    s"""{"scope":"${Certificate.Profile}","manifestSha256":"${b.manifestDigest.hex}","genesisSha256":"${b.context.genesisDigest.hex}","blockCount":${r.branch.steps.size},"firstSlot":${first.slot},"lastSlot":${r.branch.state.tip.slot},"anchorHash":"${b.seed.tip.hash.hex}","tipHash":"${r.branch.state.tip.hash.hex}","initialStateId":"${b.seed.id.hex}","finalStateId":"${r.branch.state.id.hex}","counterChangeCount":${r.counterChanges},"opCertSignaturesChecked":true,"kesSignaturesChecked":true,"registrationChecked":true,"finalCountersMatched":true,"passed":true,"rollbackReapplyMatched":true,"rollbackEveryPrefixChecked":true,"deterministicReapplyChecked":true,"referenceSnapshotAtomic":false,"nonAtomic":true,"trustedLedgerRegistration":false,"fullLedgerValidated":false,"consensusValidated":false,"vrfEligibilityChecked":false}"""

  def run(args: List[String]): IO[ExitCode] =
    val work = args match
      case List(port, directory) =>
        val dir = Path.of(directory)
        for
          bound <- IO.blocking(get(load(dir)))
          peer <- IO.fromEither(
            ReferenceHandshakeCommand
              .options(List(port, "1082026"))
              .leftMap(new IllegalArgumentException(_))
          )
          headers <- ReferenceCaptureCommand.headersThrough(
            peer._1,
            peer._2,
            point(bound.seed.tip),
            Some(point(bound.post)),
            8
          )
          originals <- headers.traverse { h =>
            ReferenceCaptureCommand
              .exactBlock(peer._1, peer._2, h)
              .flatTap { raw =>
                IO.println(
                  s"""{"record":"transfer-range-block","headerEnvelopeHex":"${h.envelope.hex}","rawBlockHex":"${raw.hex}"}"""
                )
              }
              .map(raw => BoundedChainFollower.Original(h.envelope, raw))
          }
          fresh <- IO.blocking(get(load(dir)))
          _ <- IO.raiseUnless(fresh.manifestDigest == bound.manifestDigest)(
            new IllegalArgumentException("manifest changed during capture")
          )
          report <- IO.blocking(get(assessBound(fresh, originals)))
          _ <- IO.println(render(report))
        yield ExitCode.Success
      case _ =>
        IO.raiseError[ExitCode](
          new IllegalArgumentException("usage: lab.CertificateCapture PORT EVIDENCE_DIRECTORY")
        )
    work
      .timeout(120.seconds)
      .handleErrorWith(e => IO.println("ERROR: " + e.getMessage).as(ExitCode(2)))
