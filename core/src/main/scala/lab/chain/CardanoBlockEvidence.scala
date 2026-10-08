// SPDX-License-Identifier: Apache-2.0
package lab.chain

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.kes.Sum6Kes
import lab.opcert.OperationalCertificate
import lab.vrf.PraosVrfCertificate
import scala.util.control.NonFatal

/** Partial public-input evidence under an explicit source/candidate profile. No reference
  * decoder/serializer parity, authorized issuer, counter-state, derived nonce, leadership, ledger,
  * historical inclusion or selected-chain claim.
  */
object CardanoBlockEvidence:
  enum Stage:
    case Input, Index, BodyCommitment, HeaderProjection, MessageAdmission,
      ContextBinding, Timing, OperationalCertificateSignature, KesSignature, VrfCertificate

  enum Failure:
    case Malformed(stage: Stage, reason: String)
    case Unsupported(stage: Stage, reason: String)
    case Rejected(stage: Stage, reason: String)
    case ResourceLimit(stage: Stage, reason: String)
    case InternalFailure(stage: Stage, kind: String)

  enum VrfEvidence:
    case NotCheckedMissingNonce
    case CertificateCheckedWithSuppliedNonce

  enum MessageEvidence:
    case SourceProfileShortestDefiniteCandidate

  private val U64 = (BigInt(1) << 64) - 1
  private val U32 = (BigInt(1) << 32) - 1
  val MaxBlockBytes = 1048576
  val MaxHeaderBytes = 4096 // Local work limit, not a Cardano protocol maximum.
  val ProfileId = "praos-body-opcert-kes-source-candidate-v1"
  private val ProfileDescriptor =
    "cardano-scala-lab:block-evidence-profile:v1\n" +
      s"id=$ProfileId\n" +
      "ledger=f649f9751074d2ab3de033fc3912f29c9862c1f5\n" +
      "base=060819b59c184b951a54e3c563304983c53a3eac\n" +
      "consensus=82ecba329d7d054340bf707d44fe6e9ac27cec40\n" +
      "implementation-acceptance-revision=block-evidence-1\n" +
      "strict-ed25519-profile=lab-strict-v1;strict-draft03-profile=lab-draft03-v1;sum6-profile=lab-sum6-v1\n" +
      "evidence-revision=original-full-praos-partial-evidence-v1\n" +
      "revision-policy=change-profile-revision-on-any-acceptance-or-backend-change\n" +
      "message=shortest-definite-candidate-equals-original-headerbody\n" +
      "babbage=7.0,8.0;conway=9.0,9.1,10.0\n" +
      "kesStart=local-uint32;slot,counter,blockNo=uint64\n" +
      "vrfOutput=local-fixed64;nullParent=unsupported\n" +
      "cborg-serialise-resolved-plan=unavailable;reference-parity=false\n" +
      "required=body,opcert,supplied-timing,sum6;vrf=check-if-supplied\n"

  private def sha256(bytes: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(bytes.toArray))
  private def ascii(s: String): Bytes = Bytes.fromArray(s.getBytes(StandardCharsets.US_ASCII))
  val ProfileHash: Bytes = sha256(ascii(ProfileDescriptor))
  private val IndexLimits = CardanoBlockIndex.Limits()

  /** Context contents are supplied assumptions. A source digest records their attribution; it is
    * not proof of network applicability or that the caller read that source.
    */
  final class SuppliedNonce private[CardanoBlockEvidence] (
      val hash32: Bytes,
      val sourceDigest32: Bytes
  )
  object SuppliedNonce:
    def checked(hash32: Bytes, sourceDigest32: Bytes): Either[Failure, SuppliedNonce] =
      if !isBytes(hash32, 32) || !isBytes(sourceDigest32, 32) then
        Left(Failure.Malformed(Stage.ContextBinding, "nonce and source digest must be 32 bytes"))
      else Right(new SuppliedNonce(hash32, sourceDigest32))

  final class SuppliedContext private[CardanoBlockEvidence] (
      val expectedBlockSha256: Bytes,
      val expectedHeaderHash: Bytes,
      val expectedParentHash: Bytes,
      val expectedSlot: BigInt,
      val networkLabel: String,
      val timingSourceDigest: Bytes,
      val slotsPerKesPeriod: BigInt,
      val maxKesEvolutions: Int,
      val nonce: Option[SuppliedNonce],
      val encoding: Bytes,
      val identity: Bytes
  )
  object SuppliedContext:
    def checked(
        expectedBlockSha256: Bytes,
        expectedHeaderHash: Bytes,
        expectedParentHash: Bytes,
        expectedSlot: BigInt,
        networkLabel: String,
        timingSourceDigest: Bytes,
        slotsPerKesPeriod: BigInt,
        maxKesEvolutions: Int,
        nonce: Option[SuppliedNonce]
    ): Either[Failure, SuppliedContext] =
      if !isBytes(expectedBlockSha256, 32) || !isBytes(expectedHeaderHash, 32) ||
        !isBytes(expectedParentHash, 32) || !isBytes(timingSourceDigest, 32)
      then Left(Failure.Malformed(Stage.ContextBinding, "context digests must be 32 bytes"))
      else if expectedSlot == null || expectedSlot < 0 || expectedSlot > U64 ||
        slotsPerKesPeriod == null || slotsPerKesPeriod <= 0 || slotsPerKesPeriod > U64 ||
        maxKesEvolutions <= 0 || maxKesEvolutions > 64
      then
        Left(Failure.Malformed(Stage.Timing, "context slot/period/lifetime outside profile bounds"))
      else if networkLabel == null || !networkLabel.matches("[A-Za-z0-9._:-]{1,160}") then
        Left(Failure.Malformed(Stage.ContextBinding, "bounded ASCII attribution label required"))
      else if nonce == null || nonce.exists(_ == null) then
        Left(Failure.Malformed(Stage.ContextBinding, "null nonce option"))
      else
        val encoding = frame("cardano-scala-lab:block-supplied-context:v1") { out =>
          putBytes(out, ProfileHash)
          putBytes(out, expectedBlockSha256)
          putBytes(out, expectedHeaderHash)
          putBytes(out, expectedParentHash)
          putU64(out, expectedSlot)
          putText(out, networkLabel)
          putBytes(out, timingSourceDigest)
          putU64(out, slotsPerKesPeriod)
          putU64(out, BigInt(maxKesEvolutions))
          nonce match
            case None => out.write(0)
            case Some(n) =>
              out.write(1)
              putBytes(out, n.hash32)
              putBytes(out, n.sourceDigest32)
        }
        Right(
          new SuppliedContext(
            expectedBlockSha256,
            expectedHeaderHash,
            expectedParentHash,
            expectedSlot,
            networkLabel,
            timingSourceDigest,
            slotsPerKesPeriod,
            maxKesEvolutions,
            nonce,
            encoding,
            sha256(encoding)
          )
        )

  /** Only inspect can create this token. All required base checks completed; VRF coverage is
    * explicit. Its binary identity is diagnostic, not a Cardano state root or authenticity seal.
    */
  final class Receipt private[CardanoBlockEvidence] (
      val originalBlockSha256: Bytes,
      val originalHeaderSha256: Bytes,
      val originalHeaderHash: Bytes,
      val originalParentHash: Bytes,
      val diskEra: String,
      val slot: BigInt,
      val blockNumber: BigInt,
      val protocolMajor: BigInt,
      val protocolMinor: BigInt,
      val messageDigest: Bytes,
      val contextDigest: Bytes,
      val contextEncoding: Bytes,
      val currentKesPeriod: BigInt,
      val relativeKesPeriod: Int,
      val body: CardanoBodyCommitment.Observation,
      val vrf: VrfEvidence,
      val encodedIdentityFields: Bytes,
      val receiptId: Bytes
  ):
    val profileId: String = ProfileId
    val profileHash: Bytes = ProfileHash
    val messageEvidence: MessageEvidence = MessageEvidence.SourceProfileShortestDefiniteCandidate
    val bodyCommitmentChecked: Boolean = true
    val operationalCertificateSignatureChecked: Boolean = true
    val sum6SignatureChecked: Boolean = true
    val referenceSerializerParity: Boolean = false
    val authorizedIssuer: Boolean = false
    val registeredVrfKeyBinding: Boolean = false
    val opcertCounterAdmissibility: Boolean = false
    val nonceDerivedFromState: Boolean = false
    val leaderEligibility: Boolean = false
    val protocolVersionAdmissibility: Boolean = false
    val ledgerApplied: Boolean = false
    val selectedChain: Boolean = false

  private final case class Projection(
      cold: Bytes,
      vrfKey: Bytes,
      vrfOutput: Bytes,
      vrfProof: Bytes,
      hot: Bytes,
      counter: BigInt,
      start: BigInt,
      opcertSignature: Bytes,
      major: BigInt,
      minor: BigInt,
      message: Bytes,
      signature: Bytes
  )
  private final case class Stop(failure: Failure) extends RuntimeException
  private def stop(failure: Failure): Nothing = throw Stop(failure)
  private def isBytes(value: Bytes, size: Int): Boolean =
    value != null && value.value != null && value.size == size
  private def nodeArray(node: Node, size: Int, label: String): Vector[Node] = node.value match
    case Value.Arr(nodes) if nodes.size == size => nodes
    case _ => stop(Failure.Malformed(Stage.HeaderProjection, s"$label array arity must be $size"))
  private def nodeUInt(node: Node, max: BigInt, label: String): BigInt = node.value match
    case Value.UInt(n) if n >= 0 && n <= max => n
    case _ =>
      stop(Failure.Malformed(Stage.HeaderProjection, s"$label outside local unsigned range"))
  private def nodeBytes(node: Node, size: Int, label: String): Bytes = node.value match
    case Value.ByteString(value) if value.size == size => value
    case _ => stop(Failure.Malformed(Stage.HeaderProjection, s"$label must be $size bytes"))
  private def decodeFailure(stage: Stage, reason: String): Failure =
    if reason.endsWith("limit exceeded") then Failure.ResourceLimit(stage, reason)
    else if reason.startsWith("unsupported") then Failure.Unsupported(stage, reason)
    else Failure.Malformed(stage, reason)

  private def project(parsed: CardanoBlockIndex.ParsedBlock): Either[Failure, Projection] =
    val indexed = parsed.indexed
    if indexed.headerBytes.size > MaxHeaderBytes then
      Left(Failure.ResourceLimit(Stage.HeaderProjection, "local header byte cap exceeded"))
    else if indexed.era != "babbage" && indexed.era != "conway" then
      Left(
        Failure
          .Unsupported(Stage.HeaderProjection, "profile supports Praos disk Babbage/Conway only")
      )
    else
      val root = parsed.headerNode
      try
        val header = nodeArray(root, 2, "header")
        val body = nodeArray(header(0), 10, "Praos header body")
        val cert = nodeArray(body(8), 4, "operational certificate")
        val vrf = nodeArray(body(5), 2, "VRF certificate")
        val version = nodeArray(body(9), 2, "protocol version")
        val major = nodeUInt(version(0), U32, "protocol major")
        val minor = nodeUInt(version(1), U32, "protocol minor")
        val allowed =
          if indexed.era == "babbage" then Set((BigInt(7), BigInt(0)), (BigInt(8), BigInt(0)))
          else Set((BigInt(9), BigInt(0)), (BigInt(9), BigInt(1)), (BigInt(10), BigInt(0)))
        if !allowed.contains((major, minor)) then
          stop(
            Failure.Unsupported(
              Stage.MessageAdmission,
              "protocol pair outside source/candidate profile"
            )
          )
        // Redundant owner-field comparisons prevent later refactoring from joining projections.
        if nodeUInt(body(0), U64, "block number") != indexed.blockNo ||
          nodeUInt(body(1), U64, "slot") != indexed.slot ||
          nodeBytes(body(2), 32, "parent") != indexed.parentHash
        then
          stop(Failure.InternalFailure(Stage.HeaderProjection, "index-projection-owner-mismatch"))
        nodeUInt(body(6), U32, "body size")
        nodeBytes(body(7), 32, "body hash")
        // KESPeriod is source machine Word. Choose a narrower local uint32 profile;
        // this is not a platform-independent source decoder equivalence claim.
        val start = nodeUInt(cert(2), U64, "KES start period")
        if start > U32 then
          stop(
            Failure.Unsupported(Stage.MessageAdmission, "KES start outside local uint32 profile")
          )
        val candidate = Cbor
          .encode(header(0).value)
          .fold(reason => stop(Failure.InternalFailure(Stage.MessageAdmission, reason)), identity)
        val wholeCandidate = Cbor
          .encode(root.value)
          .fold(reason => stop(Failure.InternalFailure(Stage.MessageAdmission, reason)), identity)
        if candidate != header(0).original || wholeCandidate != indexed.headerBytes then
          stop(
            Failure.Unsupported(
              Stage.MessageAdmission,
              "header is outside shortest-definite candidate subset"
            )
          )
        Right(
          Projection(
            nodeBytes(body(3), 32, "cold key"),
            nodeBytes(body(4), 32, "VRF key"),
            nodeBytes(vrf(0), 64, "profile VRF output"),
            nodeBytes(vrf(1), 80, "VRF proof"),
            nodeBytes(cert(0), 32, "hot root"),
            nodeUInt(cert(1), U64, "OpCert counter"),
            start,
            nodeBytes(cert(3), 64, "OpCert signature"),
            major,
            minor,
            candidate,
            nodeBytes(header(1), 448, "KES signature")
          )
        )
      catch case Stop(failure) => Left(failure)

  private def verifyOpCert(p: Projection): Either[Failure, Unit] =
    OperationalCertificate
      .verify(p.cold, p.hot, p.counter, p.start, p.opcertSignature)
      .left
      .map {
        case OperationalCertificate.Failure.Malformed(reason) =>
          Failure.Malformed(Stage.OperationalCertificateSignature, reason)
        case OperationalCertificate.Failure.InternalFailure(kind) =>
          Failure.InternalFailure(Stage.OperationalCertificateSignature, kind)
      }
      .flatMap {
        case OperationalCertificate.Result.OperationalCertificateSignatureVerified => Right(())
        case OperationalCertificate.Result.SignatureRejected =>
          Left(Failure.Rejected(Stage.OperationalCertificateSignature, "signature rejected"))
      }
  private def verifyKes(p: Projection, relative: Int): Either[Failure, Unit] =
    Sum6Kes
      .verify(p.hot, relative, p.message, p.signature)
      .left
      .map {
        case Sum6Kes.Failure.Malformed(reason) => Failure.Malformed(Stage.KesSignature, reason)
        case Sum6Kes.Failure.InternalFailure(kind) =>
          Failure.InternalFailure(Stage.KesSignature, kind)
      }
      .flatMap {
        case Sum6Kes.Result.SuppliedMessageSignatureVerified => Right(())
        case Sum6Kes.Result.SignatureRejected =>
          Left(
            Failure.Rejected(
              Stage.KesSignature,
              "signature rejected under supplied timing and candidate message"
            )
          )
      }
  private def verifyVrf(
      p: Projection,
      slot: BigInt,
      supplied: Option[SuppliedNonce]
  ): Either[Failure, VrfEvidence] =
    supplied match
      case None => Right(VrfEvidence.NotCheckedMissingNonce)
      case Some(n) =>
        def mapFailure(f: PraosVrfCertificate.Failure): Failure = f match
          case PraosVrfCertificate.Failure.Malformed(reason) =>
            Failure.Malformed(Stage.VrfCertificate, reason)
          case PraosVrfCertificate.Failure.InternalFailure(kind, _) =>
            Failure.InternalFailure(Stage.VrfCertificate, kind)
        for
          checkedSlot <- PraosVrfCertificate.Slot.fromBigInt(slot).left.map(mapFailure)
          checkedNonce <- PraosVrfCertificate.Hash32.fromBytes(n.hash32).left.map(mapFailure)
          input <- PraosVrfCertificate.Input.create(checkedSlot, checkedNonce).left.map(mapFailure)
          result <- PraosVrfCertificate.verify(input, p.vrfKey, p.vrfProof, p.vrfOutput) match
            case PraosVrfCertificate.Result.VerifiedCertificate(_) =>
              Right(VrfEvidence.CertificateCheckedWithSuppliedNonce)
            case PraosVrfCertificate.Result.ProofRejected(reason) =>
              Left(Failure.Rejected(Stage.VrfCertificate, reason))
            case PraosVrfCertificate.Result.OutputMismatch =>
              Left(Failure.Rejected(Stage.VrfCertificate, "claimed output mismatch"))
            case PraosVrfCertificate.Result.Malformed(reason) =>
              Left(Failure.Malformed(Stage.VrfCertificate, reason))
            case PraosVrfCertificate.Result.InternalFailure(kind, _) =>
              Left(Failure.InternalFailure(Stage.VrfCertificate, kind))
        yield result

  // Test observer can count or interrupt work, but cannot replace predicate results or mint
  // successful receipts from synthetic verification outcomes.
  private[chain] trait StageObserver:
    def before(stage: Stage): Unit
  private object NoObserver extends StageObserver:
    def before(stage: Stage): Unit = ()

  private[chain] def derivePeriod(
      slot: BigInt,
      start: BigInt,
      slotsPerPeriod: BigInt,
      maximum: Int
  ): Either[Failure, (BigInt, Int)] =
    if slot == null || start == null || slotsPerPeriod == null || slot < 0 || slot > U64 ||
      start < 0 || start > U64 || slotsPerPeriod <= 0 || slotsPerPeriod > U64 || maximum <= 0 || maximum > 64
    then Left(Failure.Malformed(Stage.Timing, "period arithmetic input outside checked bounds"))
    else
      val current = slot / slotsPerPeriod
      val relative = current - start
      if relative < 0 || relative >= maximum || relative >= 64 then
        Left(Failure.Rejected(Stage.Timing, "outside supplied start/lifetime or scheme capacity"))
      else Right((current, relative.toInt))

  /** One bounded owned parse feeds indexing, body observation and header projection. Existing
    * public index/body APIs retain their original structural acceptance.
    */
  def inspect(raw: Bytes, context: SuppliedContext): Either[Failure, Receipt] =
    inspectObserved(raw, context, NoObserver)

  private[chain] def inspectObserved(
      raw: Bytes,
      context: SuppliedContext,
      observer: StageObserver
  ): Either[Failure, Receipt] =
    if raw == null || raw.value == null || context == null || observer == null then
      Left(Failure.Malformed(Stage.Input, "null block/context"))
    else if raw.size > MaxBlockBytes then
      Left(Failure.ResourceLimit(Stage.Input, "local block byte cap exceeded"))
    else
      def observed[A](stage: Stage)(run: => Either[Failure, A]): Either[Failure, A] =
        try
          observer.before(stage)
          run
        catch case NonFatal(e) => Left(Failure.InternalFailure(stage, e.getClass.getName))
      try
        for
          parsed <- CardanoBlockIndex.parseForEvidence(raw, IndexLimits).left.map { reason =>
            if reason.startsWith("unsupported") || reason.startsWith("null parent") ||
              reason.startsWith("disk-era block envelope") || reason.startsWith("disk era must")
            then Failure.Unsupported(Stage.Index, reason)
            else decodeFailure(Stage.Index, reason)
          }
          indexed = parsed.indexed
          _ <- Either.cond(
            indexed.rawSha256 == context.expectedBlockSha256 &&
              indexed.headerHash == context.expectedHeaderHash && indexed.parentHash == context.expectedParentHash &&
              indexed.slot == context.expectedSlot,
            (),
            Failure.Rejected(
              Stage.ContextBinding,
              "context targets different original block/header"
            )
          )
          body <- CardanoBodyCommitment.inspectParsed(parsed).left.map {
            case CardanoBodyCommitment.Failure.Malformed(reason) =>
              decodeFailure(Stage.BodyCommitment, reason)
            case CardanoBodyCommitment.Failure.InternalFailure(kind) =>
              Failure.InternalFailure(Stage.BodyCommitment, kind)
          }
          _ <- Either.cond(
            body.rawSha256 == indexed.rawSha256 && body.headerHash == indexed.headerHash,
            (),
            Failure.InternalFailure(Stage.BodyCommitment, "body-index-owner-mismatch")
          )
          _ <- Either.cond(
            body.bodyCommitmentMatched,
            (),
            Failure.Rejected(Stage.BodyCommitment, "declared body hash or size mismatch")
          )
          p <- project(parsed)
          period <- derivePeriod(
            indexed.slot,
            p.start,
            context.slotsPerKesPeriod,
            context.maxKesEvolutions
          )
          (current, relative) = period
          _ <- observed(Stage.OperationalCertificateSignature)(verifyOpCert(p))
          _ <- observed(Stage.KesSignature)(verifyKes(p, relative))
          vrf <-
            if context.nonce.isEmpty then Right(VrfEvidence.NotCheckedMissingNonce)
            else observed(Stage.VrfCertificate)(verifyVrf(p, indexed.slot, context.nonce))
          _ <- Either.cond(
            (context.nonce.isEmpty && vrf == VrfEvidence.NotCheckedMissingNonce) ||
              (context.nonce.nonEmpty && vrf == VrfEvidence.CertificateCheckedWithSuppliedNonce),
            (),
            Failure.InternalFailure(Stage.VrfCertificate, "crypto-coverage-contract")
          )
        yield
          val messageDigest = sha256(p.message)
          val headerSha256 = sha256(indexed.headerBytes)
          val encoded = frame("cardano-scala-lab:block-partial-evidence:v1") { out =>
            putBytes(out, ProfileHash)
            putBytes(out, indexed.rawSha256)
            putBytes(out, headerSha256)
            putBytes(out, indexed.headerHash)
            putBytes(out, indexed.parentHash)
            putText(out, indexed.era)
            putU64(out, indexed.slot)
            putU64(out, indexed.blockNo)
            putU64(out, p.major)
            putU64(out, p.minor)
            putBytes(out, messageDigest)
            putBytes(out, context.identity)
            putU64(out, current)
            putU64(out, BigInt(relative))
            putU64(out, BigInt(body.actualSize))
            putBytes(out, body.actualHash)
            out.write(if vrf == VrfEvidence.NotCheckedMissingNonce then 0 else 1)
          }
          new Receipt(
            indexed.rawSha256,
            headerSha256,
            indexed.headerHash,
            indexed.parentHash,
            indexed.era,
            indexed.slot,
            indexed.blockNo,
            p.major,
            p.minor,
            messageDigest,
            context.identity,
            context.encoding,
            current,
            relative,
            body,
            vrf,
            encoded,
            sha256(encoded)
          )
      catch case NonFatal(e) => Left(Failure.InternalFailure(Stage.Input, e.getClass.getName))

  // All call sites supply checked bounded values. No JSON hashing or ambient configuration.
  private def frame(domain: String)(write: ByteArrayOutputStream => Unit): Bytes =
    val out = new ByteArrayOutputStream()
    out.write(domain.getBytes(StandardCharsets.US_ASCII))
    out.write(0)
    write(out)
    Bytes.fromArray(out.toByteArray)
  private def putBytes(out: ByteArrayOutputStream, bytes: Bytes): Unit = out.write(bytes.toArray)
  private def putU64(out: ByteArrayOutputStream, n: BigInt): Unit =
    (7 to 0 by -1).foreach(i => out.write(((n >> (i * 8)) & 255).toInt))
  private def putText(out: ByteArrayOutputStream, text: String): Unit =
    val data = text.getBytes(StandardCharsets.US_ASCII)
    (3 to 0 by -1).foreach(i => out.write((data.length >>> (i * 8)) & 255))
    out.write(data)
