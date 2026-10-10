// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.Async
import cats.syntax.all.*
import cats.effect.syntax.all.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point
import lab.ledger.storage.{RestrictedLedgerImage as L, RestrictedStakeImage as S}
import lab.plutus.PlutusExecution
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Complete runtime reconstruction only for a linear, uncompacted <=8-block epoch-zero window.
  * Controller authority and separately checked native source acquisition are mandatory.
  */
object RestrictedRuntimeCheckpoint:
  val Format = "restricted-plutus-linear-replay-v1"
  val MaxBytes = 16 * 1024 * 1024
  private val MaxWord = (BigInt(1) << 64) - 1
  final case class Claim(
      storeId: Bytes,
      sessionId: Bytes,
      generation: Long,
      publicationSHA256: Bytes,
      contextId: Bytes,
      sourceJoinId: Bytes,
      manifestSHA256: Bytes,
      sourcePoint: Point,
      terminalPoint: Point,
      historicalStateId: Bytes,
      historicalRevision: BigInt,
      capacity: Int,
      originalCount: Int
  )

  /** Must compare an independently retained accepted publication and active
    * store/session/generation anti-rollback policy. There is deliberately no default or
    * checksum-only implementation.
    */
  trait ControllerAuthority[F[_]]:
    def authorize(claim: Claim): F[Either[String, Unit]]
  final class AcceptedAuthority private[RestrictedRuntimeCheckpoint] (
      private[RestrictedRuntimeCheckpoint] val claim: Claim
  )
  final class Publication private[RestrictedRuntimeCheckpoint] (val bytes: Bytes, val claim: Claim)
  final class Untrusted private[RestrictedRuntimeCheckpoint] (
      val claim: Claim,
      private[RestrictedRuntimeCheckpoint] val certificateId: Bytes,
      private[RestrictedRuntimeCheckpoint] val nonceId: Bytes,
      private[RestrictedRuntimeCheckpoint] val eligibilityId: Option[Bytes],
      private[RestrictedRuntimeCheckpoint] val ledgerImage: Bytes,
      private[RestrictedRuntimeCheckpoint] val stakeImage: Bytes,
      private[RestrictedRuntimeCheckpoint] val originals: Vector[BoundedChainFollower.Original]
  ):
    val restoreAuthorized = false
  final class Restored[F[_]] private[RestrictedRuntimeCheckpoint] (
      val runtime: CoherentSequence.Runtime[F],
      val claim: Claim,
      val snapshot: CoherentSequence.Snapshot
  ):
    val rollbackFloor: Point = claim.sourcePoint
    val historicalStateId: Bytes = claim.historicalStateId
    val restoredStateId: Bytes = snapshot.state.id
    val restoredRevision: BigInt = snapshot.state.revision
    val pendingAdmissionRestored = false
    val fullLedgerValidated = false
    val consensusValidated = false
  private def get[A](e: Either[?, A]): A =
    e.fold(x => throw new IllegalArgumentException(x.toString), identity)
  private def checked[A](a: => A): Either[String, A] =
    try Right(a)
    catch
      case NonFatal(e) =>
        Left(Option(e.getMessage).getOrElse("invalid restricted runtime checkpoint"))
  private def width(b: Bytes): Unit =
    require(b != null && b.value != null && b.size == 32, "32-byte identity required")
  private def point(p: Point): Unit =
    require(
      p != null && p.slot >= 0 && p.slot < 1000 && p.blockNo >= 0 && p.blockNo <= MaxWord,
      "epoch-zero point required"
    )
    width(p.hash)
  private class Writer:
    val buffer = new ByteArrayOutputStream(); val out = new DataOutputStream(buffer)
    def raw(b: Bytes): Unit =
      require(buffer.size.toLong + b.size <= MaxBytes, "checkpoint aggregate bound");
      out.write(b.toArray)
    def hash(b: Bytes): Unit = { width(b); raw(b) }
    def number(n: BigInt): Unit =
      require(n >= 0 && n <= MaxWord, "uint64 required")
      val a = n.toByteArray.dropWhile(_ == 0);
      raw(Bytes.fromArray(Array.fill[Byte](8 - a.length)(0) ++ a))
    def point(p: Point): Unit = {
      RestrictedRuntimeCheckpoint.point(p); hash(p.hash); number(p.slot); number(p.blockNo)
    }
    def blob(b: Bytes, max: Int): Unit =
      require(b != null && b.value != null && b.size > 0 && b.size <= max, "checkpoint field bound")
      out.writeInt(b.size); raw(b)
    def result: Bytes = Bytes.fromArray(buffer.toByteArray)
  private class Reader(b: Bytes):
    val in = new DataInputStream(new ByteArrayInputStream(b.toArray))
    def raw(n: Int): Bytes =
      require(n >= 0 && n <= in.available(), "checkpoint truncation")
      Bytes.fromArray(in.readNBytes(n))
    def hash(): Bytes = raw(32)
    def number(): BigInt = BigInt(1, raw(8).toArray)
    def point(): Point =
      val p = Point(hash(), number(), number()); RestrictedRuntimeCheckpoint.point(p); p
    def blob(max: Int): Bytes =
      val n = in.readInt(); require(n > 0 && n <= max, "checkpoint field bound"); raw(n)
    def end(): Unit = require(in.available() == 0, "trailing checkpoint bytes")

  def encode(
      snapshot: CoherentSequence.Snapshot,
      context: SequenceInput.Context,
      sources: CoherentStakeImages.Sources,
      source: CoherentStakeImages.SourceBinding,
      storeId: Bytes,
      sessionId: Bytes,
      generation: Long,
      capacity: Int = 8
  ): Either[String, Publication] = checked {
    require(snapshot != null && context != null, "one immutable snapshot and source required")
    width(storeId); width(sessionId); require(generation >= 0, "nonnegative generation")
    val s = snapshot.state; val originals = s.acquisition.originals
    require(
      s.supportsRestrictedImageExport && s.compactedBlocks == 0 && s.derivedAnchorId.isEmpty &&
        !s.trustedLocalPrefix && s.depth == originals.size && s.revision == originals.size &&
        capacity >= 1 && capacity <= 8 && originals.size <= capacity,
      "only linear uncompacted source-anchored history can be restored"
    )
    val anchor = context.certificateSeed.tip
    require(
      s.acquisition.anchor == lab.network.ChainSync.Point
        .Block(get(lab.network.ChainSync.UInt64.from(anchor.slot)), anchor.hash),
      "source rollback floor mismatch"
    )
    val pair = get(CoherentStakeImages.encodeSnapshot(snapshot, context, sources, source))
    val w = new Writer
    w.raw(Bytes.fromArray("RPLREST1".getBytes("US-ASCII")))
    w.hash(storeId); w.hash(sessionId); w.number(generation); w.hash(context.id);
    w.hash(source.sourceJoinId); w.hash(source.manifestSHA256)
    w.point(anchor); w.point(s.certificates.state.tip); w.hash(s.id); w.number(s.revision)
    w.out.writeInt(capacity); w.out.writeInt(originals.size)
    w.hash(s.certificates.state.id); w.hash(s.nonces.id)
    w.out.writeBoolean(s.eligibility.nonEmpty); s.eligibility.foreach(e => w.hash(e.contextId))
    w.blob(pair.ledger, L.MaxBytes); w.blob(pair.stake, S.MaxBytes)
    originals.foreach { o =>
      w.blob(o.envelope, 65536); w.blob(o.block, 1048576)
    }
    val bytes = w.result; val parsed = get(decode(bytes))
    new Publication(bytes, parsed.claim)
  }
  def decode(bytes: Bytes): Either[String, Untrusted] = checked {
    require(
      bytes != null && bytes.value != null && bytes.size > 0 && bytes.size <= MaxBytes,
      "checkpoint byte bound"
    )
    val r = new Reader(bytes)
    require(r.raw(8) == Bytes.fromArray("RPLREST1".getBytes("US-ASCII")), "checkpoint format")
    val store = r.hash(); val session = r.hash(); val generation = r.number()
    require(generation <= Long.MaxValue, "generation bound")
    val context = r.hash(); val join = r.hash(); val manifest = r.hash(); val source = r.point();
    val terminal = r.point()
    val state = r.hash(); val revision = r.number(); val capacity = r.in.readInt();
    val count = r.in.readInt()
    require(
      capacity >= 1 && capacity <= 8 && count >= 0 && count <= capacity && revision == count &&
        terminal.blockNo == source.blockNo + count && terminal.slot >= source.slot,
      "linear replay depth/revision/point bounds"
    )
    val certificate = r.hash(); val nonce = r.hash(); val tag = r.in.readUnsignedByte()
    require(tag <= 1 && (tag == 1 || count == 0), "terminal eligibility tag")
    val eligibility = if tag == 1 then Some(r.hash()) else None
    val ledger = r.blob(L.MaxBytes); val stake = r.blob(S.MaxBytes)
    val originals =
      Vector.fill(count)(BoundedChainFollower.Original(r.blob(65536), r.blob(1048576)))
    r.end()
    val claim = Claim(
      store,
      session,
      generation.toLong,
      S.sha256(bytes),
      context,
      join,
      manifest,
      source,
      terminal,
      state,
      revision,
      capacity,
      count
    )
    new Untrusted(claim, certificate, nonce, eligibility, ledger, stake, originals)
  }
  def accept[F[_]: Async](
      image: Untrusted,
      controller: ControllerAuthority[F]
  ): F[Either[String, AcceptedAuthority]] =
    if image == null || controller == null then
      Async[F].pure(Left("independent controller authority required"))
    else controller.authorize(image.claim).map(_.map(_ => new AcceptedAuthority(image.claim)))

  def recover[F[_]: Async](
      bytes: Bytes,
      accepted: Option[AcceptedAuthority],
      joined: NativeLedgerV2.Checked,
      deadline: FiniteDuration
  ): F[Either[String, Restored[F]]] =
    recoverObserved(bytes, accepted, joined, deadline, _ => Async[F].unit)
  private[lab] def recoverObserved[F[_]: Async](
      bytes: Bytes,
      accepted: Option[AcceptedAuthority],
      joined: NativeLedgerV2.Checked,
      deadline: FiniteDuration,
      between: String => F[Unit]
  ): F[Either[String, Restored[F]]] =
    val F = Async[F]
    def stage[A](name: String)(a: => Either[String, A]): F[Either[String, A]] =
      F.cede *> between(name) *> F.delay(a)
    val work = stage("decode")(decode(bytes)).flatMap {
      case Left(e) => F.pure(Left(e))
      case Right(image) =>
        if accepted == null || !accepted.exists(a => a != null && a.claim == image.claim) then
          F.pure(Left("missing or mismatched accepted controller authority"))
        else
          stage("source-and-originals")(checked {
            val c = image.claim
            require(
              joined != null && joined.id == c.sourceJoinId,
              "independently checked native source join mismatch"
            )
            val context = get(SequenceInput.fromNativeDiagnostic(joined, c.sourceJoinId))
            require(
              context.id == c.contextId && context.certificateSeed.tip == c.sourcePoint,
              "authenticated source anchor mismatch"
            )
            val env = context.ledger.environment
            val binding = L.Binding(
              PlutusExecution.ProfileId,
              BigInt(env.networkMagic),
              0,
              L.Point(c.terminalPoint.slot, c.terminalPoint.blockNo, c.terminalPoint.hash),
              env.id,
              Map(
                "genesis" -> env.genesisDigest,
                "parameters" -> env.parameterDigest,
                "manifest" -> c.manifestSHA256
              )
            )
            val ledger = get(L.decode(image.ledgerImage, binding))
            get(
              S.decode(
                image.stakeImage,
                S.Binding(
                  S.sha256(image.ledgerImage),
                  c.sourceJoinId,
                  context.stakeSourceId,
                  c.terminalPoint.slot,
                  c.terminalPoint.blockNo,
                  c.terminalPoint.hash
                ),
                S.sha256(image.stakeImage)
              )
            )
            val sources = CoherentStakeImages.Sources(
              ledger.sources("genesis"),
              ledger.sources("parameters"),
              ledger.sources("manifest")
            )
            require(
              sources.genesis == joined.ledger.epochComponents.parameters.genesisOriginal &&
                sources.parameters == joined.ledger.epochComponents.parameters.current.original,
              "complete checked source originals mismatch"
            )
            val blocks = image.originals.map(o => get(SequenceInput.block(o)))
            var tip = c.sourcePoint
            blocks.foreach { b =>
              require(
                b.header.parent == tip.hash && b.header.blockNo == tip.blockNo + 1 && b.header.slot > tip.slot && b.header.slot < 1000,
                "incomplete, repeated or noncontiguous replay originals"
              )
              tip = Point(b.header.hash, b.header.slot, b.header.blockNo)
            }
            require(tip == c.terminalPoint, "original suffix terminal point mismatch")
            (context, sources, blocks)
          }).flatMap {
            case Left(e) => F.pure(Left(e))
            case Right((context, sources, blocks)) =>
              (F.cede *> between("fresh-runtime") *> CoherentSequence
                .createPlutusRecoveryWithStake[F](
                  context,
                  joined.ledger.epochComponents.stake,
                  image.claim.publicationSHA256,
                  image.claim.capacity
                )).flatMap {
                case Left(e) => F.pure(Left(e.toString))
                case Right(runtime) =>
                  blocks
                    .foldLeft(F.pure[Either[String, Unit]](Right(()))) { (acc, block) =>
                      acc.flatMap {
                        case Left(e) => F.pure(Left(e))
                        case Right(_) =>
                          (F.cede *> between("prepare") *> runtime.prepare(block)).flatMap {
                            case Left(e) => F.pure(Left(e.toString))
                            case Right(candidate) =>
                              (F.cede *> between("publish") *> runtime.publish(candidate)).map(
                                _.left.map(_.toString).void
                              )
                          }
                      }
                    }
                    .flatMap {
                      case Left(e) => F.pure(Left(e))
                      case Right(_) =>
                        (F.cede *> between("verify") *> runtime.snapshot).map { snapshot =>
                          checked {
                            val c = image.claim; val s = snapshot.state
                            require(
                              s.revision == blocks.size && s.depth == blocks.size && s.compactedBlocks == 0 && s.supportsRestrictedImageExport &&
                                s.certificates.state.tip == c.terminalPoint && s.certificates.state.id == image.certificateId &&
                                s.nonces.id == image.nonceId && s.nonces.certificateStateId == s.certificates.state.id &&
                                s.nonces.lastSlot == s.ledger.slot && s.eligibility
                                  .map(_.contextId) == image.eligibilityId &&
                                s.ledger.checkpointId != context.ledger.checkpointId && s.id != c.historicalStateId,
                              "replayed terminal coherent tuple mismatch"
                            )
                            val source = CoherentStakeImages.SourceBinding(
                              context.id,
                              c.sourceJoinId,
                              context.stakeSourceId,
                              context.ledger.environment.genesisDigest,
                              context.ledger.environment.parameterDigest,
                              c.manifestSHA256
                            )
                            val pair = get(
                              CoherentStakeImages.encodeSnapshot(snapshot, context, sources, source)
                            )
                            require(
                              pair.ledger == image.ledgerImage && pair.stake == image.stakeImage,
                              "replayed terminal semantic images mismatch"
                            )
                            new Restored(runtime, c, snapshot)
                          }
                        }
                    }
              }
          }
    }
    if deadline == null || deadline <= Duration.Zero || deadline > 60.seconds then
      F.pure(Left("recovery deadline must be positive and <=60 seconds"))
    else work.timeoutTo(deadline, F.pure(Left("recovery deadline")))
