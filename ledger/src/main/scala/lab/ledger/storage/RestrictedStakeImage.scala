// SPDX-License-Identifier: Apache-2.0
package lab.ledger.storage

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import java.security.MessageDigest
import lab.cbor.Bytes
import lab.ledger.{ClusterTransition, ConwayStake as S}
import scala.util.control.NonFatal

/** A restricted projection image, never a persisted owner or validator capability. */
object RestrictedStakeImage:
  val Version = 1
  val MaxBytes = 3 * 1024 * 1024
  private val Max = (BigInt(1) << 64) - 1
  final case class Binding(
      ledgerImageSHA256: Bytes,
      sourceJoinId: Bytes,
      stakeSourceId: Bytes,
      slot: BigInt,
      blockNo: BigInt,
      headerHash: Bytes
  )
  final class Untrusted private[RestrictedStakeImage] (
      val original: Bytes,
      val binding: Binding,
      private[RestrictedStakeImage] val contextId: Bytes,
      private[RestrictedStakeImage] val environmentId: Bytes,
      private[RestrictedStakeImage] val profile: String,
      private[RestrictedStakeImage] val epoch: BigInt,
      private[RestrictedStakeImage] val fees: BigInt,
      private[RestrictedStakeImage] val utxo: Bytes,
      private[RestrictedStakeImage] val instantaneous: Map[S.Credential, BigInt],
      private[RestrictedStakeImage] val snapshots: Vector[Bytes],
      private[RestrictedStakeImage] val snapshotFees: BigInt
  ):
    val restoreAuthorized = false
    val completeValidatorState = false
    val fullLedgerValidated = false
    def imageSHA256: Bytes = sha256(original)

  def sha256(b: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(b.toArray))
  private def checked[A](f: => A): Either[String, A] =
    try Right(f)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse("invalid stake image"))
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def width(b: Bytes, n: Int): Unit = require(b != null && b.size == n, "image hash width")
  private def binding(b: Binding): Unit =
    require(b != null, "image binding")
    Vector(b.ledgerImageSHA256, b.sourceJoinId, b.stakeSourceId, b.headerHash).foreach(width(_, 32))
    require(b.slot >= 0 && b.slot <= Max && b.blockNo >= 0 && b.blockNo <= Max, "point bounds")
  private class Writer:
    val bytes = new ByteArrayOutputStream()
    val out = new DataOutputStream(bytes)
    def raw(b: Bytes): Unit =
      require(bytes.size.toLong + b.size <= MaxBytes, "image size bound")
      out.write(b.toArray)
    def blob(b: Bytes): Unit =
      require(b.size <= MaxBytes, "blob bound")
      out.writeInt(b.size); raw(b)
    def number(n: BigInt): Unit =
      require(n >= 0 && n < (BigInt(1) << 128), "integer bounds")
      blob(Bytes.fromArray(n.toByteArray))
    def cred(c: S.Credential): Unit =
      width(c.hash, 28); out.writeBoolean(c.script); raw(c.hash)
    def result: Bytes =
      require(bytes.size <= MaxBytes, "image size bound")
      Bytes.fromArray(bytes.toByteArray)
  private class Reader(b: Bytes):
    val in = new DataInputStream(new ByteArrayInputStream(b.toArray))
    def raw(n: Int): Bytes =
      require(n >= 0 && n <= in.available(), "truncated image")
      val a = new Array[Byte](n); in.readFully(a); Bytes.fromArray(a)
    def blob(limit: Int = MaxBytes): Bytes =
      val n = in.readInt(); require(n >= 0 && n <= limit, "image field bound"); raw(n)
    def number(): BigInt =
      val b = blob(17); require(b.size > 0, "empty integer")
      val n = BigInt(b.toArray)
      require(
        n >= 0 && n < (BigInt(1) << 128) && Bytes.fromArray(n.toByteArray) == b,
        "noncanonical integer"
      )
      n
    def bool(): Boolean =
      val n = in.readUnsignedByte(); require(n <= 1, "boolean encoding"); n == 1
    def cred(): S.Credential = S.Credential(bool(), raw(28))
    def count(): Int =
      val n = in.readInt(); require(n >= 0 && n <= 4096, "entry count bound"); n
    def end(): Unit = require(in.available() == 0, "trailing image bytes")
  private def snapshotBytes(s: S.Snapshot): Bytes =
    val w = new Writer
    w.out.writeBoolean(s.active.isEmpty && s.total == 1 && s.pools.isEmpty)
    w.out.writeInt(s.active.size)
    s.active.toVector.sortBy(_._1.key).foreach { (c, a) =>
      w.cred(c); w.number(a.coin); w.raw(a.pool)
    }
    w.number(s.total); w.out.writeInt(s.pools.size)
    s.pools.toVector.sortBy(_._1.hex).foreach { (id, p) =>
      w.raw(id); w.number(p.coin); w.number(p.ratio.numerator); w.number(p.ratio.denominator)
      w.out.writeInt(p.owners.size); p.owners.toVector.sortBy(_.hex).foreach(w.raw)
      w.number(p.ownerCoin); w.raw(p.vrf); w.number(p.pledge); w.number(p.cost)
      w.number(p.margin.numerator); w.number(p.margin.denominator); w.out.writeInt(p.delegators);
      w.cred(p.rewardAccount)
    }
    w.result
  private def snapshot(raw: Bytes, context: S.Context): S.Snapshot =
    val r = new Reader(raw)
    val empty = r.bool()
    val pairs = Vector.fill(r.count()) { val c = r.cred(); c -> S.Active(r.number(), r.raw(28)) }
    require(pairs.map(_._1).distinct.size == pairs.size, "duplicate active credential")
    val s = if empty then { require(pairs.isEmpty, "empty snapshot active"); S.emptySnapshot }
    else get(S.fromActive(context, pairs.toMap))
    // The complete original snapshot encoding must equal the checked reconstruction,
    // including the registered zero-stake pool domain, ratios and owner stake.
    require(snapshotBytes(s) == raw, "snapshot does not match source context")
    s

  def encode(state: S.State, ledger: ClusterTransition.State, b: Binding): Either[String, Bytes] =
    checked {
      binding(b)
      require(
        state != null && ledger != null && state.ledgerId == ledger.id &&
          state.revision == ledger.revision && state.slot == ledger.slot && state.epoch == ledger.environment.epoch &&
          b.slot == ledger.slot,
        "stake/ledger binding mismatch"
      )
      val w = new Writer
      w.out.writeInt(0x53544b31); w.out.writeInt(Version)
      Vector(b.ledgerImageSHA256, b.sourceJoinId, b.stakeSourceId).foreach(w.raw)
      w.number(b.slot); w.number(b.blockNo); w.raw(b.headerHash)
      w.raw(state.context.id); w.raw(ledger.environment.id)
      w.blob(Bytes.fromArray(ledger.profileId.getBytes("UTF-8")))
      w.number(state.epoch); w.number(ledger.fees); w.blob(ledger.outputMap)
      w.out.writeInt(state.instantaneous.size)
      state.instantaneous.toVector.sortBy(_._1.key).foreach { (c, n) =>
        w.cred(c); w.number(n)
      }
      Vector(state.snapshots.mark, state.snapshots.set, state.snapshots.go).foreach(s =>
        w.blob(snapshotBytes(s))
      )
      w.number(state.snapshots.fees)
      w.result
    }
  def decode(raw: Bytes, expected: Binding, expectedImageSHA256: Bytes): Either[String, Untrusted] =
    checked {
      binding(expected); width(expectedImageSHA256, 32)
      require(
        raw != null && raw.size <= MaxBytes && sha256(raw) == expectedImageSHA256,
        "independent image pin mismatch"
      )
      val r = new Reader(raw)
      require(r.in.readInt() == 0x53544b31 && r.in.readInt() == Version, "stake image version")
      val b = Binding(r.raw(32), r.raw(32), r.raw(32), r.number(), r.number(), r.raw(32))
      require(b == expected, "external source/point binding mismatch")
      val context = r.raw(32); val environment = r.raw(32)
      val profile = new String(r.blob(128).toArray, "UTF-8")
      val epoch = r.number(); val fees = r.number();
      val utxo = r.blob(ClusterTransition.MaxStateBytes)
      val pairs = Vector.fill(r.count()) { r.cred() -> r.number() }
      require(pairs.map(_._1).distinct.size == pairs.size, "duplicate instantaneous credential")
      val snapshots = Vector.fill(3)(r.blob())
      val snapshotFees = r.number(); r.end()
      new Untrusted(
        raw,
        b,
        context,
        environment,
        profile,
        epoch,
        fees,
        utxo,
        pairs.toMap,
        snapshots,
        snapshotFees
      )
    }

  /** Caller supplies authority independently. The image cannot authenticate its own bindings. A
    * fresh owner is caller-created; no historical owner, revision or state id is restored.
    */
  def reconstruct(
      image: Untrusted,
      sourceContext: S.Context,
      freshLedger: ClusterTransition.State,
      freshOwner: S.Owner
  ): Either[String, S.State] = checked {
    require(
      image != null && sourceContext != null && freshLedger != null && freshOwner != null,
      "reconstruction inputs"
    )
    require(
      freshLedger.revision == 0 && image.contextId == sourceContext.id && image.environmentId == freshLedger.environment.id &&
        image.profile == freshLedger.profileId && image.epoch == freshLedger.environment.epoch &&
        image.binding.slot == freshLedger.slot && image.fees == freshLedger.fees && image.utxo == freshLedger.outputMap,
      "checked source/ledger mismatch"
    )
    val ss = image.snapshots.map(snapshot(_, sourceContext))
    get(
      S.seed(
        freshOwner,
        sourceContext,
        freshLedger,
        image.binding.stakeSourceId,
        image.instantaneous,
        S.Snapshots(ss(0), ss(1), ss(2), image.snapshotFees)
      )
    )
  }
