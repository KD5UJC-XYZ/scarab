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

/** Supplied-key, one-epoch rotation observation; registration authority is not established. No
  * ledger state or validated cursor is published.
  */
object NonceEpochCommand:
  val ProfileId = "praos-nonce-epoch-rotation-v1"
  val KeyMode = "pre-anchor-supplied-verification-keys-v1"
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
  final class Bound private[NonceEpochCommand] (
      val certificates: Certificate.Context,
      val seed: Certificate.State,
      val nonces: PraosNonceSnapshot.Prepared,
      val post: Certificate.Point,
      val epoch: BigInt,
      val originals: Map[String, Bytes],
      val pins: Map[String, Bytes],
      val manifestDigest: Bytes
  )
  final class Report private[NonceEpochCommand] (
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
        fields.keySet == (sources.keySet ++ Set("format", "keyMode")) && fields(
          "format"
        ) == ProfileId && fields("keyMode") == KeyMode,
        "exact nonce epoch manifest and explicit supplied-key assumption required"
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
        "fixed L500 k5 f1/20 epoch rotation profile required"
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
        epoch >= 1 && postEpoch == epoch + 1 && anchor.slot / 500 == epoch && post.slot / 500 == postEpoch && anchor.slot % 500 >= 400 && anchor.slot % 500 <= 460 && post.slot % 500 >= 40 && post.slot % 500 <= 180,
        "one epoch rotation with pre relative400..460 and post relative40..180 required"
      )
      require(
        post.slot > anchor.slot && post.blockNo - anchor.blockNo >= 2 && post.blockNo - anchor.blockNo <= MaxHeaders,
        "two to sixteen successor endpoint range required"
      )
      val ledger = parse(originals("pre-ledger-state.md"))
      require(uint(field(ledger, "lastEpoch")) == epoch, "pre ledger epoch mismatch")
      val lifetime = uint(field(genesis, "maxKESEvolutions"))
      require(lifetime > 0 && lifetime <= 64, "Sum6 lifetime")
      // Attribution binds the supplied-key assumption, not authority from ledger validation.
      val registrationAttribution = sha(
        Bytes.fromArray(
          s"$ProfileId\nkeyMode=$KeyMode\npreLedgerSha256=${pins("preLedgerSha256").hex}\n"
            .getBytes(StandardCharsets.UTF_8)
        )
      )
      val context = get(
        "certificate-context",
        Certificate.Context.checked(
          pins("genesisSha256"),
          registrationAttribution,
          anchor.slot,
          post.slot,
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
      read(directory.resolve("nonce-epoch-context.md"), 8192),
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
      supported(
        header.slot / 500 == bound.epoch || header.slot / 500 == bound.epoch + 1,
        "only the two adjacent epochs are supported"
      )
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
    require(
      headers.exists(_.slot / 500 == bound.epoch) && headers.exists(
        _.slot / 500 == bound.epoch + 1
      ),
      "successors in both epochs required"
    )
    // All byte/continuity checks above precede certificate/nonce replay under supplied keys.
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
        if nonce.before.lastSlot / 500 != nonce.after.lastSlot / 500 then checkRotation(nonce)
        else
          require(
            nonce.epochNonceUsed == nonce.before.fields.epoch && nonce.after.fields.epoch == nonce.before.fields.epoch &&
              nonce.after.fields.previousEpoch == nonce.before.fields.previousEpoch && nonce.after.fields.lastEpochBlock == nonce.before.fields.lastEpochBlock,
            "non-crossing header must not tick"
          )
        done :+ (certificate, nonce)
    }
    val certificates = paired.map(_._1); val nonces = paired.map(_._2)
    require(
      nonces.count(n => n.before.lastSlot / 500 != n.after.lastSlot / 500) == 1,
      "exactly one observed epoch tick required"
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
      uint(field(postLedger, "lastEpoch")) == bound.epoch + 1,
      "post-oracle",
      "post ledger epoch mismatch"
    )
    supported(
      registrations(postLedger) == bound.certificates.registrations,
      "observed post registration keys differ from the fixed supplied-key profile"
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

  /** Equation uses the old candidate and old lastEpochBlock; never the newly rotated LAB. */
  private[lab] def combine(a: Nonces.Nonce, b: Nonces.Nonce): Nonces.Nonce = (a, b) match
    case (Nonces.Nonce.Neutral, other) => other
    case (other, Nonces.Nonce.Neutral) => other
    case (Nonces.Nonce.Hash(x), Nonces.Nonce.Hash(y)) =>
      Nonces.Nonce.Hash(Blake2b.hash256.hash(Bytes(x.value ++ y.value)))
  private[lab] def rotationMatches(
      before: Nonces.Fields,
      after: Nonces.Fields,
      used: Nonces.Nonce
  ): Boolean =
    val expected = combine(before.candidate, before.lastEpochBlock)
    after.epoch == expected && used == expected && after.previousEpoch.contains(before.epoch) &&
    after.lastEpochBlock == before.lab
  private def checkRotation(step: Nonces.Applied): Unit =
    supported(
      step.epochNonceUsed != step.before.fields.epoch,
      "observed epoch nonce must distinguish tick from stale nonce"
    )
    supported(
      combine(step.before.fields.candidate, step.before.fields.lastEpochBlock) != combine(
        step.before.fields.candidate,
        step.before.fields.lab
      ),
      "observed old lastEpochBlock must distinguish wrong LAB rotation order"
    )
    check(
      rotationMatches(step.before.fields, step.after.fields, step.epochNonceUsed),
      "epoch-rotation",
      "old candidate/old lastEpochBlock combination, previous epoch rotation, LAB rotation or tick-before-VRF mismatch"
    )

  def render(report: Report): String =
    val b = report.bound
    val last = report.nonceSteps.last.after
    val tick = report.nonceSteps.find(s => s.before.lastSlot / 500 != s.after.lastSlot / 500).get
    s"""{"scope":"nonce-epoch-rotation-observation","profile":"$ProfileId","keyMode":"$KeyMode","passed":true,"scopedSuccess":true,"contextSha256":"${b.manifestDigest.hex}","registrationAttributionSha256":"${b.certificates.registrationDigest.hex}","anchorHash":"${b.seed.tip.hash.hex}","tipHash":"${b.post.hash.hex}","anchorSlot":${b.seed.tip.slot},"lastSlot":${last.lastSlot},"capturedBlocks":${report.nonceSteps.size},"preEpoch":${b.epoch},"postEpoch":${b.epoch + 1},"epochLength":500,"securityParam":5,"activeSlotsNumerator":1,"activeSlotsDenominator":20,"stabilizationWindow":400,"epochTransitions":1,"firstNewEpochSlot":${tick.after.lastSlot},"epochTickChecked":true,"oldCandidateAndLastEpochBlockCombined":true,"oldEpochNonceRotatedToPrevious":true,"oldLabRotatedToLastEpochBlock":true,"tickedNonceUsedForVrf":true,"observedTickDistinguishesStaleNonce":true,"observedTickDistinguishesWrongLabOrder":true,"suppliedRegistrationKeysOnly":true,"crossEpochRegistrationContinuityProven":false,"certificateRegistrationAuthorityValidated":false,"fiveNonceFieldsMatched":true,"finalCountersMatched":true,"sameRegistrationViewObserved":true,"rollbackReapplyChecked":true,"rollbackEveryPrefixChecked":true,"deterministicReapplyChecked":true,"staleAndForkReceiptsRejected":true,"forkGuardUsesSyntheticAttribution":true,"initialNonceStateId":"${b.nonces.seed.id.hex}","finalNonceStateId":"${last.id.hex}","previousEpochNonceInitiallyKnown":${b.nonces.seed.fields.previousEpoch.nonEmpty},"previousEpochNonceKnown":${last.fields.previousEpoch.nonEmpty},"previousEpochNonceDerivedKnown":${last.fields.previousEpoch.nonEmpty},"previousEpochNonceCompared":${report.previousEpochNonceCompared},"vrfProofChecked":true,"leaderEligibilityChecked":false,"stateDerivedConsensus":false,"coherentBranchPublished":false,"referenceSnapshotAtomic":false,"authenticatedSnapshot":false,"fullLedgerValidated":false,"consensusValidated":false}"""
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
    s"""{"scope":"nonce-epoch-rotation-observation","profile":"$ProfileId","passed":false,"outcome":"$outcome","stage":${quote(
        stage
      )},"detail":${quote(
        detail
      )},"keyMode":"$KeyMode","suppliedRegistrationKeysOnly":true,"crossEpochRegistrationContinuityProven":false,"certificateRegistrationAuthorityValidated":false,"leaderEligibilityChecked":false,"stateDerivedConsensus":false,"fullLedgerValidated":false,"consensusValidated":false}"""
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
          Stop(Failure.Rejected("arguments", "usage: nonce-epoch PORT EVIDENCE_DIRECTORY"))
        )
    work.timeout(60.seconds).handleErrorWith { e =>
      val failure = e match
        case Stop(f) => f
        case other =>
          Failure.Rejected("command", Option(other.getMessage).getOrElse(other.getClass.getName))
      IO.println(renderFailure(failure)).as(ExitCode(2))
    }
