// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.Async
import cats.syntax.all.*
import cats.effect.syntax.all.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import lab.cbor.Bytes
import lab.header.{
  PraosCertificateState as Certificate,
  PraosNonceEvolution as Nonces,
  PraosEligibility as Eligibility
}
import lab.ledger.ClusterTransition as Ledger
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Research crash-recovery format. Parsing/checksums confer no prefix authority. A separately
  * persisted controller claim must explicitly authorize its exact publication. Controller and
  * writer may be private roles within one trusted process; no process isolation or protection from
  * compromised code or rollback of both domains is supplied. Compromised writers, privileged
  * tampering and rollback of both domains are excluded.
  */
object LocalDerivedCheckpoint:
  val Format = "restricted-local-derived-checkpoint-v2"
  val Authority = "reviewed-local-writer-crash-recovery-v1"
  val MaxBytes = 40 * 1024 * 1024
  val MaxAnchorBytes = 2 * 1024 * 1024
  final case class Token(
      storeId: Bytes,
      contextId: Bytes,
      sessionId: Bytes,
      generation: Long,
      digest: Bytes
  )
  final case class Claim(
      token: Token,
      format: String,
      profile: String,
      authority: String,
      anchorId: Bytes,
      finalId: Bytes,
      compactedBlocks: BigInt,
      revision: BigInt
  )

  /** No implementation is supplied here. The controller must compare its independently retained
    * publication record and active store/session policy; trusting this claim's checksum is wrong.
    */
  trait ControllerAuthority[F[_]]:
    def authorize(claim: Claim): F[Either[String, Unit]]

  final class AcceptedAuthority private[LocalDerivedCheckpoint] (
      private[LocalDerivedCheckpoint] val claim: Claim
  )
  final class Publication private[LocalDerivedCheckpoint] (val bytes: Bytes, val claim: Claim)

  private[lab] final case class Anchor(
      id: Bytes,
      certificate: Certificate.LocalImage,
      nonce: Nonces.LocalImage,
      eligibility: Eligibility.LocalImage,
      ledger: Ledger.LocalImage
  )
  private[lab] final case class Image(
      storeId: Bytes,
      contextId: Bytes,
      sessionId: Bytes,
      generation: Long,
      capacity: Int,
      compactedBlocks: BigInt,
      provenance: Bytes,
      revision: BigInt,
      finalId: Bytes,
      finalPoint: Certificate.Point,
      manifest: Bytes,
      sources: Map[String, Bytes],
      anchor: Anchor,
      originals: Vector[BoundedChainFollower.Original]
  )
  final class UntrustedEnvelope private[LocalDerivedCheckpoint] (
      val claim: Claim,
      private[lab] val image: Image
  )

  private[lab] final class AuthorizedLocalImage private[LocalDerivedCheckpoint] (val image: Image)

  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private def sha(b: Bytes): Bytes = ClusterHeaderObservation.sha256(b)
  private def protect[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[?, A]): A =
    e.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def bounds(i: Image): Unit =
    require(
      i.storeId.size == 32 && i.contextId.size == 32 && i.sessionId.size == 32 &&
        i.generation >= 0 && i.provenance.size == 32 && i.finalId.size == 32,
      "local image identities"
    )
    val n = i.originals.size
    require(
      i.capacity >= 1 && i.capacity <= 8 && n <= i.capacity && i.compactedBlocks > 0 &&
        i.revision <= Ledger.MaxRevision && i.compactedBlocks + n <= i.revision &&
        (i.revision - i.compactedBlocks - n) % 2 == 0,
      "local depth/revision/capacity bounds"
    )

  private final class Writer(limit: Int):
    private val buffer = new ByteArrayOutputStream()
    val out = new DataOutputStream(buffer)
    def field(b: Bytes, max: Int): Unit =
      require(
        b.size > 0 && b.size <= max && buffer.size().toLong + 4 + b.size <= limit,
        "field/total bound"
      )
      out.writeInt(b.size); out.write(b.toArray)
    def hash(b: Bytes): Unit =
      require(b.size == 32, "hash width"); field(b, 32)
    def number(n: BigInt, max: BigInt): Unit =
      require(n >= 0 && n <= max, "number bound"); field(raw(n.toString), 78)
    def point(p: Certificate.Point): Unit =
      hash(p.hash); number(p.slot, Ledger.MaxRevision); number(p.blockNo, Ledger.MaxRevision)
    def nonce(n: Nonces.Nonce): Unit = n match
      case Nonces.Nonce.Neutral => out.writeByte(0)
      case Nonces.Nonce.Hash(b) => out.writeByte(1); hash(b)
    def result: Bytes =
      out.flush(); require(buffer.size() <= limit, "total bound");
      Bytes.fromArray(buffer.toByteArray)

  private final class Reader(bytes: Bytes):
    private val in = new DataInputStream(new ByteArrayInputStream(bytes.toArray))
    def int: Int = in.readInt()
    def long: Long = in.readLong()
    def tag: Int = in.readUnsignedByte()
    def field(max: Int): Bytes =
      val n = int
      require(n > 0 && n <= max && n <= in.available(), "field bound/truncation")
      Bytes.fromArray(in.readNBytes(n))
    def hash: Bytes =
      val b = field(32); require(b.size == 32, "hash width"); b
    def number(max: BigInt): BigInt =
      val s = new String(field(78).toArray, "US-ASCII")
      require(s.matches("0|[1-9][0-9]*"), "canonical integer")
      val n = BigInt(s); require(n <= max, "number bound"); n
    def point: Certificate.Point =
      Certificate.Point(hash, number(Ledger.MaxRevision), number(Ledger.MaxRevision))
    def nonce: Nonces.Nonce = tag match
      case 0 => Nonces.Nonce.Neutral
      case 1 => Nonces.Nonce.Hash(hash)
      case _ => throw new IllegalArgumentException("nonce tag")
    def exact(s: String): Unit = require(field(128) == raw(s), "format/profile/authority mismatch")
    def end(): Unit = require(in.available() == 0, "trailing bytes")

  private def anchorBytes(a: Anchor): Bytes =
    val w = new Writer(MaxAnchorBytes)
    w.hash(a.id)
    val c = a.certificate
    w.hash(c.contextId); w.hash(c.id); w.point(c.tip)
    require(c.counters.size <= 10000, "counter count")
    w.out.writeInt(c.counters.size)
    c.counters.toVector.sortBy(_._1.hex).foreach { (key, value) =>
      require(key.size == 28, "counter key"); w.field(key, 28); w.number(value, Ledger.MaxRevision)
    }
    val n = a.nonce
    w.hash(n.id); w.hash(n.contextId); w.hash(n.certificateStateId);
    w.number(n.lastSlot, Ledger.MaxRevision)
    w.nonce(n.fields.evolving); w.nonce(n.fields.candidate); w.nonce(n.fields.epoch)
    n.fields.previousEpoch match
      case None        => w.out.writeByte(0)
      case Some(value) => w.out.writeByte(1); w.nonce(value)
    w.nonce(n.fields.lab); w.nonce(n.fields.lastEpochBlock)
    val e = a.eligibility
    w.hash(e.contextId); w.hash(e.headerHash); w.number(e.leaderValue, (BigInt(1) << 256) - 1)
    w.number(e.numerator, Ledger.MaxRevision); w.number(e.denominator, Ledger.MaxRevision)
    val l = a.ledger
    w.hash(l.environmentId); w.hash(l.checkpointId); w.field(l.outputMap, Ledger.MaxStateBytes)
    w.number(l.fees, Ledger.MaxFees); w.number(l.slot, Ledger.MaxRevision); w.hash(l.id);
    w.hash(l.head)
    w.result

  private def readAnchor(bytes: Bytes): Anchor =
    val r = new Reader(bytes)
    val id = r.hash; val cid = r.hash; val certId = r.hash; val point = r.point
    val count = r.int; require(count >= 0 && count <= 10000, "counter count")
    val pairs = Vector.fill(count) {
      val key = r.field(28); require(key.size == 28, "counter key")
      key -> r.number(Ledger.MaxRevision)
    }
    require(
      pairs.map(_._1.hex).sliding(2).forall(p => p.size < 2 || p(0) < p(1)),
      "counter order/duplicates"
    )
    val certificate = Certificate.LocalImage(cid, certId, point, pairs.toMap)
    val nid = r.hash; val ncid = r.hash; val ncert = r.hash; val slot = r.number(Ledger.MaxRevision)
    val evolving = r.nonce; val candidate = r.nonce; val epoch = r.nonce
    val previous = r.tag match
      case 0 => None
      case 1 => Some(r.nonce)
      case _ => throw new IllegalArgumentException("previous nonce tag")
    val fields = Nonces.Fields(evolving, candidate, epoch, previous, r.nonce, r.nonce)
    val nonce = Nonces.LocalImage(nid, ncid, ncert, slot, fields)
    val eligibility = Eligibility.LocalImage(
      r.hash,
      r.hash,
      r.number((BigInt(1) << 256) - 1),
      r.number(Ledger.MaxRevision),
      r.number(Ledger.MaxRevision)
    )
    val ledger = Ledger.LocalImage(
      r.hash,
      r.hash,
      r.field(Ledger.MaxStateBytes),
      r.number(Ledger.MaxFees),
      r.number(Ledger.MaxRevision),
      r.hash,
      r.hash
    )
    r.end(); Anchor(id, certificate, nonce, eligibility, ledger)

  /** Pure serialization of UNTRUSTED data. This returns neither publication nor authority. */
  private[lab] def encodeUntrusted(i: Image): Either[String, Bytes] = protect {
    bounds(i)
    val w = new Writer(MaxBytes - 32)
    Vector(
      Format,
      Authority,
      CoherentSequence.ProfileId,
      SequenceInput.ProfileId,
      Certificate.Profile,
      Ledger.ProfileId
    ).foreach(s => w.field(raw(s), 128))
    w.hash(i.storeId); w.hash(i.contextId); w.hash(i.sessionId); w.out.writeLong(i.generation)
    w.out.writeInt(i.capacity); w.number(i.compactedBlocks, Ledger.MaxRevision);
    w.hash(i.provenance)
    w.number(i.revision, Ledger.MaxRevision); w.hash(i.finalId); w.point(i.finalPoint)
    w.field(i.manifest, 8192)
    require(i.sources.keySet == SequenceInput.sources.values.toSet, "source set")
    SequenceInput.sources.toVector
      .sortBy(_._1)
      .foreach((_, name) => w.field(i.sources(name), 4194304))
    w.field(anchorBytes(i.anchor), MaxAnchorBytes)
    w.out.writeInt(i.originals.size)
    i.originals.foreach { o =>
      w.field(o.envelope, 65535); w.field(o.block, 1048576)
    }
    val payload = w.result
    Bytes(payload.value ++ sha(payload).value)
  }

  def decode(raw: Bytes): Either[String, UntrustedEnvelope] = protect {
    require(raw.size >= 32 && raw.size <= MaxBytes, "total bound")
    val payload = Bytes(raw.value.dropRight(32)); val digest = sha(payload)
    require(digest == Bytes(raw.value.takeRight(32)), "checksum mismatch")
    val r = new Reader(payload)
    Vector(
      Format,
      Authority,
      CoherentSequence.ProfileId,
      SequenceInput.ProfileId,
      Certificate.Profile,
      Ledger.ProfileId
    ).foreach(r.exact)
    val store = r.hash; val context = r.hash; val session = r.hash; val generation = r.long
    val capacity = r.int; val d = r.number(Ledger.MaxRevision); val provenance = r.hash
    val revision = r.number(Ledger.MaxRevision); val finalId = r.hash; val point = r.point
    val manifest = r.field(8192)
    val sources =
      SequenceInput.sources.toVector.sortBy(_._1).map((_, name) => name -> r.field(4194304)).toMap
    val anchor = readAnchor(r.field(MaxAnchorBytes))
    val count = r.int; require(count >= 0 && count <= 8, "original count")
    val originals =
      Vector.fill(count)(BoundedChainFollower.Original(r.field(65535), r.field(1048576)))
    r.end()
    val i = Image(
      store,
      context,
      session,
      generation,
      capacity,
      d,
      provenance,
      revision,
      finalId,
      point,
      manifest,
      sources,
      anchor,
      originals
    )
    bounds(i)
    val token = Token(store, context, session, generation, digest)
    new UntrustedEnvelope(
      Claim(token, Format, CoherentSequence.ProfileId, Authority, anchor.id, finalId, d, revision),
      i
    )
  }

  private[lab] def encodeOwned(
      owned: CoherentSequence.OwnedLocalExport,
      storeId: Bytes,
      sessionId: Bytes,
      generation: Long
  ): Either[String, Publication] = protect {
    val a = owned.anchor; val s = owned.current; val c = owned.context
    require(
      s.compactedBlocks > 0 && a.acquisition.size == 0 &&
        a.acquisition.tip == s.acquisition.anchor && a.contextId == c.id &&
        a.compactedBlocks == s.compactedBlocks && a.derivedAnchorId == s.derivedAnchorId,
      "owned derived anchor required"
    )
    val anchor = Anchor(
      a.id,
      Certificate.localImage(a.certificates.state),
      Nonces.localImage(a.nonces),
      get(Eligibility.localImage(a.eligibility.get)),
      get(Ledger.localImage(a.ledger))
    )
    val manifest = raw(
      "format\t" + SequenceInput.ProfileId + "\n" + SequenceInput.sources.keys.toVector.sorted
        .map(k => k + "\t" + c.sourcePins(k).hex)
        .mkString("\n") + "\n"
    )
    val image = Image(
      storeId,
      c.id,
      sessionId,
      generation,
      owned.capacity,
      s.compactedBlocks,
      s.derivedAnchorId.get,
      s.revision,
      s.id,
      s.certificates.state.tip,
      manifest,
      SequenceInput.sources.values.map(n => n -> c.originals(n)).toMap,
      anchor,
      s.acquisition.originals
    )
    val bytes = get(encodeUntrusted(image)); val envelope = get(decode(bytes))
    new Publication(bytes, envelope.claim)
  }

  def accept[F[_]: Async](
      envelope: UntrustedEnvelope,
      controller: ControllerAuthority[F]
  ): F[Either[String, AcceptedAuthority]] =
    if envelope == null || controller == null then
      Async[F].pure(Left("controller authority required"))
    else controller.authorize(envelope.claim).map(_.map(_ => new AcceptedAuthority(envelope.claim)))

  def recover[F[_]: Async](
      raw: Bytes,
      expectedContext: Bytes,
      authority: Option[AcceptedAuthority],
      deadline: FiniteDuration
  ): F[Either[String, CoherentSequence.Runtime[F]]] =
    recoverObserved(raw, expectedContext, authority, deadline, _ => Async[F].unit)

  private[lab] def recoverObserved[F[_]: Async](
      raw: Bytes,
      expectedContext: Bytes,
      authority: Option[AcceptedAuthority],
      deadline: FiniteDuration,
      between: String => F[Unit]
  ): F[Either[String, CoherentSequence.Runtime[F]]] =
    val F = Async[F]
    val work = (F.cede *> between("decode") *> F.delay(decode(raw))).flatMap {
      case Left(error) => F.pure(Left(error))
      case Right(envelope) =>
        if authority == null || !authority.exists(a => a != null && a.claim == envelope.claim) then
          F.pure(Left("missing or mismatched accepted controller authority"))
        else if expectedContext != envelope.claim.token.contextId then
          F.pure(Left("expected context mismatch"))
        else
          (F.cede *> between("context") *> F.delay(
            SequenceInput.bind(envelope.image.manifest, envelope.image.sources)
          )).flatMap {
            case Left(error) => F.pure(Left(error.toString))
            case Right(context) if context.id != expectedContext =>
              F.pure(Left("rebound context mismatch"))
            case Right(context) =>
              CoherentSequence
                .trustedRestoreLocal(context, new AuthorizedLocalImage(envelope.image), between)
                .map(_.left.map(_.toString))
          }
    }
    if deadline == null || deadline <= Duration.Zero then
      F.pure(Left("positive recovery deadline required"))
    else work.timeoutTo(deadline, F.pure(Left("recovery deadline")))
