// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.header.{PraosCertificateState as Certificate, PraosNonceEvolution as Nonces}
import lab.network.ChainSync
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Supplied-state, same-epoch nonce observation. No ledger state or validated cursor is published.
  */
object NonceFreezeCommand:
  val ProfileId = "praos-nonce-candidate-freeze-v1"
  val MaxHeaders = 16
  enum Failure:
    case Unsupported(feature: String)
    case Rejected(stage: String, detail: String)
  private final case class Stop(failure: Failure) extends RuntimeException
  private[lab] val sources: Map[String, String] = Map(
    "genesisSha256" -> "transfer-genesis.md",
    "preTipsSha256" -> "pre-tips.md",
    "postTipsSha256" -> "post-tips.md",
    "preProtocolSha256" -> "pre-protocol-state.md",
    "postProtocolSha256" -> "post-protocol-state.md",
    "preLedgerSha256" -> "pre-ledger-state.md",
    "postLedgerSha256" -> "post-ledger-state.md",
    "preParametersSha256" -> "pre-parameters.md",
    "postParametersSha256" -> "post-parameters.md"
  )
  final class Bound private[NonceFreezeCommand] (
      val certificates: Certificate.Context,
      val seed: Certificate.State,
      val nonces: PraosNonceSnapshot.Prepared,
      val post: Certificate.Point,
      val epoch: BigInt,
      val originals: Map[String, Bytes],
      val pins: Map[String, Bytes],
      val manifestDigest: Bytes
  )
  final class Report private[NonceFreezeCommand] (
      val bound: Bound,
      val certificateSteps: Vector[Certificate.Applied],
      val nonceSteps: Vector[Nonces.Applied],
      val previousEpochNonceCompared: Boolean
  )
  private def sha(b: Bytes): Bytes = ClusterHeaderObservation.sha256(b)
  private def text(b: Bytes): String =
    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b.toArray)).toString
  private def read(path: Path, limit: Int): Bytes =
    val stream = Files.newInputStream(path)
    val bytes =
      try stream.readNBytes(limit + 1)
      finally stream.close()
    require(bytes.nonEmpty && bytes.length <= limit, "nonempty bounded source required")
    Bytes.fromArray(bytes)
  private def get[A](stage: String, value: Either[String, A]): A =
    value.fold(s => throw Stop(Failure.Rejected(stage, s)), identity)
  private def check(condition: Boolean, stage: String, detail: String): Unit =
    if !condition then throw Stop(Failure.Rejected(stage, detail))
  private def supported(condition: Boolean, feature: String): Unit =
    if !condition then throw Stop(Failure.Unsupported(feature))
  private def protect[A](stage: String)(body: => A): Either[Failure, A] =
    try Right(body)
    catch
      case Stop(failure) => Left(failure)
      case NonFatal(e) =>
        Left(Failure.Rejected(stage, Option(e.getMessage).getOrElse(e.getClass.getName)))
  private def hash(s: String, size: Int): Bytes =
    val b = get("source", Bytes.fromHex(s))
    require(b.size == size && b.hex == s, "canonical hash required")
    b
  private def obj(j: ReferenceJson.Json): Map[String, ReferenceJson.Json] = j match
    case ReferenceJson.Json.Obj(fields) => fields
    case _                              => throw new IllegalArgumentException("object required")
  private def point(p: Certificate.Point): ChainSync.Point =
    ChainSync.Point.Block(get("point", ChainSync.UInt64.from(p.slot)), p.hash)
  private def tips(raw: Bytes): (Certificate.Point, BigInt) =
    import ReferenceJson.{parse, array, field, string, uint}
    val values = ReferenceJson.array(parse(raw))
    require(values.size >= 2 && values.size <= 32, "bounded stable tip bracket required")
    val endpoints = values.map { t =>
      supported(string(field(t, "era")) == "Conway", "Conway tips required")
      val slot = uint(field(t, "slot")); val block = uint(field(t, "block"))
      require(slot <= ChainSync.UInt64.Max && block <= ChainSync.UInt64.Max, "tip Word64 bound")
      (Certificate.Point(hash(string(field(t, "hash")), 32), slot, block), uint(field(t, "epoch")))
    }
    require(endpoints.distinct.size == 1, "unstable tip bracket")
    endpoints.head
  private def registrations(ledger: ReferenceJson.Json): Map[Bytes, Bytes] =
    import ReferenceJson.{field, string}
    obj(field(ledger, "stakeDistrib", "unPoolDistr")).map { (id, p) =>
      hash(id, 28) -> hash(string(field(p, "individualPoolStakeVrf")), 32)
    }
  private def counters(protocol: ReferenceJson.Json): Map[Bytes, BigInt] =
    obj(ReferenceJson.field(protocol, "oCertCounters")).map { (id, n) =>
      val number = ReferenceJson.uint(n)
      require(number <= ChainSync.UInt64.Max, "counter Word64 bound")
      hash(id, 28) -> number
    }

  /** Post tips delimit acquisition. Other post sources remain owned bytes until replay completes.
    */
  private[lab] def bind(manifest: Bytes, originals: Map[String, Bytes]): Either[Failure, Bound] =
    protect("input") {
      require(manifest.size > 0 && manifest.size <= 8192, "manifest bound")
      val rows = text(manifest).linesIterator.map { line =>
        val columns = line.split("\t", -1)
        require(columns.length == 2 && columns.forall(_.nonEmpty), "strict manifest TSV required")
        columns(0) -> columns(1)
      }.toVector
      require(rows.map(_._1).distinct.size == rows.size, "duplicate manifest key")
      val fields = rows.toMap
      require(
        fields.keySet == sources.keySet + "format" && fields("format") == ProfileId,
        "exact nonce freeze manifest required"
      )
      require(originals.keySet == sources.values.toSet, "exact source files required")
      val pins = sources.map { (key, file) =>
        val raw = originals(file)
        require(raw.size > 0 && raw.size <= 4194304, "source bound")
        val pin = hash(fields(key), 32)
        require(sha(raw) == pin, "source digest mismatch: " + file)
        key -> pin
      }
      import ReferenceJson.{parse, field, uint, string}
      val genesis = parse(originals("transfer-genesis.md"))
      supported(
        string(field(genesis, "networkId")) == "Testnet" && uint(
          field(genesis, "networkMagic")
        ) == 1082026,
        "isolated testnet magic1082026 required"
      )
      val coefficient = field(genesis, "activeSlotsCoeff") match
        case ReferenceJson.Json.Num(n) => new java.math.BigDecimal(n)
        case _ => throw new IllegalArgumentException("numeric active coefficient required")
      supported(
        uint(field(genesis, "epochLength")) == 500 && uint(
          field(genesis, "securityParam")
        ) == 5 && coefficient.compareTo(new java.math.BigDecimal("0.05")) == 0,
        "fixed L500 k5 f1/20 freeze profile required"
      )
      val parameters = parse(originals("pre-parameters.md"))
      supported(
        uint(field(parameters, "protocolVersion", "major")) == 9 && uint(
          field(parameters, "protocolVersion", "minor")
        ) == 0,
        "ledger PV9.0 required"
      )
      val (anchor, epoch) = tips(originals("pre-tips.md"))
      val (post, postEpoch) = tips(originals("post-tips.md"))
      supported(
        epoch == postEpoch && anchor.slot / 500 == epoch && post.slot / 500 == epoch,
        "one fixed supplied epoch required"
      )
      require(
        post.slot > anchor.slot && post.blockNo - anchor.blockNo >= 2 && post.blockNo - anchor.blockNo <= MaxHeaders,
        "two to sixteen successor endpoint range required"
      )
      val ledger = parse(originals("pre-ledger-state.md"))
      require(uint(field(ledger, "lastEpoch")) == epoch, "pre ledger epoch mismatch")
      val lifetime = uint(field(genesis, "maxKESEvolutions"))
      require(lifetime > 0 && lifetime <= 64, "Sum6 lifetime")
      val context = get(
        "certificate-context",
        Certificate.Context.checked(
          pins("genesisSha256"),
          pins("preLedgerSha256"),
          epoch * 500,
          epoch * 500 + 499,
          uint(field(genesis, "slotsPerKESPeriod")),
          lifetime.toInt,
          registrations(ledger)
        )
      )
      val protocol = parse(originals("pre-protocol-state.md"))
      val seed = get(
        "certificate-seed",
        Certificate.seed(context, anchor, counters(protocol), pins("preProtocolSha256"))
      )
      val nonce = get(
        "nonce-seed",
        PraosNonceSnapshot.bind(
          context,
          seed,
          originals("transfer-genesis.md"),
          originals("pre-protocol-state.md"),
          pins("preProtocolSha256")
        )
      )
      require(nonce.context.window == 400, "strict cutoff must be relative100")
      new Bound(context, seed, nonce, post, epoch, originals, pins, sha(manifest))
    }
  def load(directory: Path): Either[Failure, Bound] = protect("input") {
    bind(
      read(directory.resolve("nonce-freeze-context.md"), 8192),
      sources.values.map(n => n -> read(directory.resolve(n), 4194304)).toMap
    ).fold(f => throw Stop(f), identity)
  }
  private def array(n: Node): Vector[Node] = n.value match
    case Value.Arr(v) => v
    case _            => throw new IllegalArgumentException("array required")
  private def emptyBlock(raw: Bytes): Unit =
    val outer = array(get("block", Cbor.decode(raw, Cbor.Limits(1048576, 48, 200000, 1048576))))
    require(outer.size == 2, "block envelope")
    val body = array(outer(1))
    require(body.size == 5, "Conway block arity")
    supported(
      array(body(1)).isEmpty && array(body(2)).isEmpty && body(3).value == Value.Map(
        Vector.empty
      ) && array(body(4)).isEmpty,
      "empty transaction/auxiliary/invalid-index block profile required"
    )

  def replay(
      bound: Bound,
      originals: Vector[BoundedChainFollower.Original]
  ): Either[Failure, Report] = protect("replay") {
    require(
      originals.size >= 2 && originals.size <= MaxHeaders,
      "two to sixteen complete originals required"
    )
    val headers = originals.zipWithIndex.map { (original, index) =>
      require(
        original.envelope.size <= 65535 && original.block.size <= 1048576,
        "original byte bounds"
      )
      val header = get("header", ReferenceCaptureCommand.header(original.envelope))
      supported(header.major == 11 && header.minor == 2, "observed header11.2 profile required")
      supported(header.slot / 500 == bound.epoch, "same epoch originals required")
      require(
        header.blockNo == bound.seed.tip.blockNo + index + 1,
        "complete block number sequence required"
      )
      emptyBlock(original.block)
      header
    }
    headers.zip(originals).zipWithIndex.foreach { case ((header, original), index) =>
      val prior =
        if index == 0 then bound.seed.tip
        else
          Certificate.Point(
            headers(index - 1).hash,
            headers(index - 1).slot,
            headers(index - 1).blockNo
          )
      get("acquisition", ReferenceCaptureCommand.compare(header, original.block, point(prior)))
    }
    val finalHeader = headers.last
    require(
      Certificate.Point(finalHeader.hash, finalHeader.slot, finalHeader.blockNo) == bound.post,
      "full original endpoint mismatch"
    )
    // All byte/continuity checks above precede authenticated certificate/nonce replay.
    val paired = headers.foldLeft(Vector.empty[(Certificate.Applied, Nonces.Applied)]) {
      (done, h) =>
        val previousCertificate = done.lastOption.fold(bound.seed)(_._1.after)
        val previousNonce = done.lastOption.fold(bound.nonces.seed)(_._2.after)
        val certificate = get(
          "certificate",
          Certificate.applyHeader(bound.certificates, previousCertificate, h.raw, h.hash)
        )
        val nonce =
          get("nonce", Nonces.applyHeader(bound.nonces.context, previousNonce, certificate))
        require(
          nonce.epochNonceUsed == bound.nonces.seed.fields.epoch && nonce.after.fields.epoch == bound.nonces.seed.fields.epoch && nonce.after.fields.previousEpoch == bound.nonces.seed.fields.previousEpoch && nonce.after.fields.lastEpochBlock == bound.nonces.seed.fields.lastEpochBlock,
          "same epoch replay must not tick"
        )
        done :+ (certificate, nonce)
    }
    val certificates = paired.map(_._1); val nonces = paired.map(_._2)
    val early = nonces.filter(_.after.lastSlot % 500 < 100)
    val frozen = nonces.filter(_.after.lastSlot % 500 >= 100)
    check(
      early.exists(s => s.after.fields.candidate != s.before.fields.candidate),
      "freeze-evidence",
      "actual candidate update below relative100 required"
    )
    check(
      early.forall(s => s.after.fields.candidate == s.after.fields.evolving),
      "freeze-evidence",
      "early candidate must follow evolving"
    )
    check(
      frozen.nonEmpty && frozen.forall(s =>
        s.after.fields.candidate == s.before.fields.candidate && s.after.fields.evolving != s.before.fields.evolving
      ),
      "freeze-evidence",
      "actual frozen candidate with evolving change at/after relative100 required"
    )
    // Only now parse reference post-state as an external oracle, never as a replay seed.
    val postNonce = get(
      "post-oracle",
      PraosNonceSnapshot.parse(
        bound.originals("post-protocol-state.md"),
        bound.pins("postProtocolSha256")
      )
    )
    val finalNonce = nonces.last.after
    check(
      postNonce.lastSlot == finalNonce.lastSlot && postNonce.fields
        .copy(previousEpoch = None) == finalNonce.fields.copy(previousEpoch = None),
      "post-oracle",
      "five nonce fields or lastSlot mismatch"
    )
    val previousCompared = (postNonce.fields.previousEpoch, finalNonce.fields.previousEpoch) match
      case (Some(a), Some(b)) =>
        check(a == b, "post-oracle", "known previous epoch nonce mismatch"); true
      case _ => false
    import ReferenceJson.{parse, field, uint}
    val postProtocol = parse(bound.originals("post-protocol-state.md"))
    check(
      counters(postProtocol) == certificates.last.after.counters,
      "post-oracle",
      "full post counter map mismatch"
    )
    val postLedger = parse(bound.originals("post-ledger-state.md"))
    check(
      uint(field(postLedger, "lastEpoch")) == bound.epoch,
      "post-oracle",
      "post ledger epoch mismatch"
    )
    check(
      registrations(postLedger) == bound.certificates.registrations,
      "post-oracle",
      "observed registration view changed"
    )
    // Parameter bytes are compared after derivation; no post parameter enters either state machine.
    parse(bound.originals("post-parameters.md"))
    check(
      bound.originals("post-parameters.md") == bound.originals("pre-parameters.md"),
      "post-oracle",
      "observed parameters changed"
    )
    (0 to paired.size).foreach { keep =>
      val expectedCertificate = if keep == 0 then bound.seed else certificates(keep - 1).after
      val expectedNonce = if keep == 0 then bound.nonces.seed else nonces(keep - 1).after
      val restored = paired.drop(keep).reverse.foldLeft((certificates.last.after, finalNonce)) {
        case ((c, n), (cs, ns)) =>
          (get("certificate-undo", Certificate.undo(c, cs)), get("nonce-undo", Nonces.undo(n, ns)))
      }
      require(
        restored._1.id == expectedCertificate.id && restored._1.tip == expectedCertificate.tip && restored._1.counters == expectedCertificate.counters && restored._2.id == expectedNonce.id && restored._2.fields == expectedNonce.fields && restored._2.lastSlot == expectedNonce.lastSlot && restored._2.certificateStateId == restored._1.id,
        "paired prefix rollback mismatch"
      )
      val reapplied = headers.drop(keep).foldLeft(restored) { case ((c, n), h) =>
        val cs =
          get("certificate-reapply", Certificate.applyHeader(bound.certificates, c, h.raw, h.hash))
        val ns = get("nonce-reapply", Nonces.applyHeader(bound.nonces.context, n, cs))
        (cs.after, ns.after)
      }
      require(
        reapplied._1.id == certificates.last.after.id && reapplied._1.counters == certificates.last.after.counters && reapplied._1.tip == bound.post && reapplied._2.id == finalNonce.id && reapplied._2.fields == finalNonce.fields && reapplied._2.certificateStateId == reapplied._1.id,
        "paired deterministic reapply mismatch"
      )
    }
    paired.foreach { (c, n) =>
      require(
        Certificate.undo(c.before, c).isLeft && Nonces.undo(n.before, n).isLeft && Nonces
          .applyHeader(bound.nonces.context, n.after, c)
          .isLeft,
        "stale receipt guard failed"
      )
    }
    // Synthetic alternate attribution checks receipt fencing, not an alternate reference branch.
    val alternativePin = sha(
      Bytes.fromArray(
        (bound.manifestDigest.hex + ":receipt-fence").getBytes(StandardCharsets.UTF_8)
      )
    )
    val forkCertificateSeed = get(
      "receipt-fence",
      Certificate.seed(bound.certificates, bound.seed.tip, bound.seed.counters, alternativePin)
    )
    val forkCertificate = get(
      "receipt-fence",
      Certificate.applyHeader(
        bound.certificates,
        forkCertificateSeed,
        headers.head.raw,
        headers.head.hash
      )
    )
    val forkNonceSeed = get(
      "receipt-fence",
      Nonces.seed(
        bound.nonces.context,
        forkCertificateSeed,
        bound.nonces.seed.fields,
        alternativePin
      )
    )
    val forkNonce =
      get("receipt-fence", Nonces.applyHeader(bound.nonces.context, forkNonceSeed, forkCertificate))
    require(
      Certificate
        .undo(certificates.head.after, forkCertificate)
        .isLeft && Nonces.undo(nonces.head.after, forkNonce).isLeft,
      "alternate attribution receipt guard failed"
    )
    new Report(bound, certificates, nonces, previousCompared)
  }

  def render(report: Report): String =
    val b = report.bound
    val last = report.nonceSteps.last.after
    val updates = report.nonceSteps.count(s =>
      s.after.lastSlot % 500 < 100 && s.before.fields.candidate != s.after.fields.candidate
    )
    val freezes = report.nonceSteps.count(_.after.lastSlot % 500 >= 100)
    s"""{"scope":"nonce-candidate-freeze-observation","profile":"$ProfileId","passed":true,"scopedSuccess":true,"contextSha256":"${b.manifestDigest.hex}","anchorHash":"${b.seed.tip.hash.hex}","tipHash":"${b.post.hash.hex}","anchorSlot":${b.seed.tip.slot},"lastSlot":${last.lastSlot},"capturedBlocks":${report.nonceSteps.size},"epoch":${b.epoch},"epochLength":500,"securityParam":5,"activeSlotsNumerator":1,"activeSlotsDenominator":20,"stabilizationWindow":400,"relativeFreezeCutoff":100,"candidateUpdatesBeforeCutoff":$updates,"frozenHeadersAtOrAfterCutoff":$freezes,"candidateUpdatedBeforeCutoff":true,"candidateFrozenAfterCutoff":true,"rollbackReapplyChecked":true,"stateDerivedConsensus":false,"fiveNonceFieldsMatched":true,"finalCountersMatched":true,"sameRegistrationViewObserved":true,"registrationContinuityProven":false,"rollbackEveryPrefixChecked":true,"deterministicReapplyChecked":true,"staleAndForkReceiptsRejected":true,"forkGuardUsesSyntheticAttribution":true,"initialNonceStateId":"${b.nonces.seed.id.hex}","finalNonceStateId":"${last.id.hex}","previousEpochNonceCompared":${report.previousEpochNonceCompared},"previousEpochNonceKnown":${last.fields.previousEpoch.nonEmpty},"epochTickChecked":false,"vrfProofChecked":true,"leaderEligibilityChecked":false,"coherentBranchPublished":false,"referenceSnapshotAtomic":false,"authenticatedSnapshot":false,"fullLedgerValidated":false,"consensusValidated":false}"""
  private def quote(s: String): String = "\"" + s.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  } + "\""
  private def renderFailure(f: Failure): String =
    val (outcome, stage, detail) = f match
      case Failure.Unsupported(feature) => ("Unsupported", "profile", feature)
      case Failure.Rejected(s, d)       => ("Rejected", s, d)
    s"""{"scope":"nonce-candidate-freeze-observation","profile":"$ProfileId","passed":false,"outcome":"$outcome","stage":${quote(
        stage
      )},"detail":${quote(detail)},"fullLedgerValidated":false,"consensusValidated":false}"""
  private def lift[A](result: Either[Failure, A]): IO[A] =
    IO.fromEither(result.left.map(Stop.apply))
  def run(args: List[String]): IO[ExitCode] =
    val work = args match
      case List(port, directory) =>
        for
          bound <- IO.blocking(load(Path.of(directory))).flatMap(lift)
          peer <- IO.fromEither(
            ReferenceHandshakeCommand
              .options(List(port, "1082026"))
              .left
              .map(e => Stop(Failure.Rejected("arguments", e)))
          )
          headers <- ReferenceCaptureCommand.headersThroughBounded(
            peer._1,
            peer._2,
            point(bound.seed.tip),
            Some(point(bound.post)),
            MaxHeaders
          )
          originals <- headers.traverse { h =>
            ReferenceCaptureCommand
              .exactBlock(peer._1, peer._2, h)
              .flatTap { block =>
                IO.println(
                  s"""{"record":"transfer-range-block","headerEnvelopeHex":"${h.envelope.hex}","rawBlockHex":"${block.hex}"}"""
                )
              }
              .map(block => BoundedChainFollower.Original(h.envelope, block))
          }
          report <- IO(replay(bound, originals)).flatMap(lift)
          _ <- IO.println(render(report))
        yield ExitCode.Success
      case _ =>
        IO.raiseError[ExitCode](
          Stop(Failure.Rejected("arguments", "usage: nonce-freeze PORT EVIDENCE_DIRECTORY"))
        )
    work.timeout(60.seconds).handleErrorWith { e =>
      val failure = e match
        case Stop(f) => f
        case other =>
          Failure.Rejected("command", Option(other.getMessage).getOrElse(other.getClass.getName))
      IO.println(renderFailure(failure)).as(ExitCode(2))
    }
