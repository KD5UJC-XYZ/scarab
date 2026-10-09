// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.Async
import cats.syntax.all.*
import cats.effect.syntax.all.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import lab.cbor.Bytes
import lab.ledger.ClusterTransition
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Pure recovery image. A decoded envelope is untrusted; only full replay returns a runtime.
  * Expected pins/tokens must come from the caller's independent retention domain.
  */
object ValidatedCheckpoint:
  val MaxBytes = 40 * 1024 * 1024
  val Format = "restricted-validated-checkpoint-v1"
  final case class Token(storeId: Bytes, contextId: Bytes, generation: Long, digest: Bytes)
  final class Envelope private[ValidatedCheckpoint] (
      val token: Token,
      val capacity: Int,
      val revision: BigInt,
      val finalId: Bytes,
      private[ValidatedCheckpoint] val manifest: Bytes,
      private[ValidatedCheckpoint] val sources: Map[String, Bytes],
      private[ValidatedCheckpoint] val originals: Vector[BoundedChainFollower.Original]
  )
  private def bytes(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
  private def sha(b: Bytes) = ClusterHeaderObservation.sha256(b)
  private def guard(ok: Boolean, message: String): Unit = require(ok, message)
  private def protect[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def manifest(context: SequenceInput.Context): Bytes = bytes(
    "format\t" + SequenceInput.ProfileId + "\n" + SequenceInput.sources.keys.toVector.sorted
      .map(k => k + "\t" + context.sourcePins(k).hex)
      .mkString("\n") + "\n"
  )
  private def valid(capacity: Int, revision: BigInt, count: Int): Unit =
    guard(capacity >= 1 && capacity <= 8 && count >= 0 && count <= capacity, "window bounds")
    guard(
      revision >= count && revision <= ClusterTransition.MaxRevision &&
        (revision - count) % 2 == 0,
      "revision bounds/parity"
    )

  /** Snapshot construction remains owned by the coordinator; no arbitrary state hydration. */
  def encode(
      context: SequenceInput.Context,
      snapshot: CoherentSequence.Snapshot,
      storeId: Bytes,
      generation: Long,
      capacity: Int
  ): Either[String, (Bytes, Token)] = protect {
    val state = snapshot.state
    guard(context.id == state.contextId, "context mismatch")
    guard(storeId.size == 32 && generation >= 0, "store/generation bounds")
    valid(capacity, state.revision, state.acquisition.size)
    val buffer = new ByteArrayOutputStream()
    val out = new DataOutputStream(buffer)
    def field(b: Bytes, max: Int): Unit =
      guard(b.size > 0 && b.size <= max, "field bound")
      guard(buffer.size().toLong + 4 + b.size + 32 <= MaxBytes, "total bound")
      out.writeInt(b.size); out.write(b.toArray)
    field(bytes(Format), 128); field(bytes(CoherentSequence.ProfileId), 128)
    field(storeId, 32); field(context.id, 32); out.writeLong(generation)
    out.writeInt(capacity); field(bytes(state.revision.toString), 20); field(state.id, 32)
    field(manifest(context), 8192)
    SequenceInput.sources.toVector.sortBy(_._1).foreach { (_, name) =>
      field(context.originals(name), 4194304)
    }
    out.writeInt(state.acquisition.size)
    state.acquisition.originals.foreach { o =>
      field(o.envelope, 65535); field(o.block, 1048576)
    }
    out.flush()
    val payload = Bytes.fromArray(buffer.toByteArray)
    val digest = sha(payload)
    (Bytes(payload.value ++ digest.value), Token(storeId, context.id, generation, digest))
  }

  /** Even a matching checksum and token produce only an untrusted bounded envelope. */
  def decode(raw: Bytes, expectedContext: Bytes, expected: Token): Either[String, Envelope] =
    protect {
      guard(raw.size >= 32 && raw.size <= MaxBytes, "total bound")
      guard(
        expectedContext.size == 32 && expected.contextId == expectedContext &&
          expected.storeId.size == 32 && expected.digest.size == 32 && expected.generation >= 0,
        "expected pins/token"
      )
      val payload = Bytes(raw.value.dropRight(32))
      val digest = sha(payload)
      guard(
        digest == Bytes(raw.value.takeRight(32)) && digest == expected.digest,
        "checksum/expected token mismatch"
      )
      val in = new DataInputStream(new ByteArrayInputStream(payload.toArray))
      def field(max: Int): Bytes =
        val size = in.readInt()
        guard(size > 0 && size <= max && size <= in.available(), "field bound/truncation")
        Bytes.fromArray(in.readNBytes(size))
      def exact(value: String): Unit = guard(field(128) == bytes(value), "format/profile mismatch")
      exact(Format); exact(CoherentSequence.ProfileId)
      val store = field(32); val context = field(32); val generation = in.readLong()
      guard(
        store == expected.storeId && context == expectedContext && generation == expected.generation,
        "wrong store/context/generation"
      )
      val capacity = in.readInt()
      val revisionBytes = field(20)
      val revisionText = new String(revisionBytes.toArray, "US-ASCII")
      guard(revisionText.matches("0|[1-9][0-9]*"), "canonical revision required")
      val revision = BigInt(revisionText)
      val finalId = field(32); guard(finalId.size == 32, "final ID width")
      val m = field(8192)
      val sources = SequenceInput.sources.toVector
        .sortBy(_._1)
        .map { (_, name) => name -> field(4194304) }
        .toMap
      val count = in.readInt(); valid(capacity, revision, count)
      val originals =
        Vector.fill(count)(BoundedChainFollower.Original(field(65535), field(1048576)))
      guard(in.available() == 0, "trailing bytes")
      new Envelope(expected, capacity, revision, finalId, m, sources, originals)
    }

  def recover[F[_]: Async](
      raw: Bytes,
      expectedContext: Bytes,
      expected: Token,
      deadline: FiniteDuration
  ): F[Either[String, CoherentSequence.Runtime[F]]] =
    recoverObserved(raw, expectedContext, expected, deadline, _ => Async[F].unit)

  /** Stage observer receives labels only, never partially recovered capabilities. */
  private[lab] def recoverObserved[F[_]: Async](
      raw: Bytes,
      expectedContext: Bytes,
      expected: Token,
      deadline: FiniteDuration,
      between: String => F[Unit]
  ): F[Either[String, CoherentSequence.Runtime[F]]] =
    val F = Async[F]
    val work =
      (F.cede *> between("decode") *> F.delay(decode(raw, expectedContext, expected))).flatMap {
        case Left(error) => F.pure[Either[String, CoherentSequence.Runtime[F]]](Left(error))
        case Right(envelope) =>
          (F.cede *> between("context") *> F.delay(
            SequenceInput.bind(envelope.manifest, envelope.sources)
          )).flatMap {
            case Left(error) => F.pure(Left(error.toString))
            case Right(context) if context.id != expectedContext =>
              F.pure(Left("rebound context mismatch"))
            case Right(context) =>
              CoherentSequence
                .recover[F](
                  context,
                  envelope.capacity,
                  envelope.revision,
                  envelope.originals,
                  envelope.finalId,
                  between
                )
                .map(_.left.map(_.toString))
          }
      }
    if deadline <= Duration.Zero then F.pure(Left("positive recovery deadline required"))
    else work.timeoutTo(deadline, F.pure(Left("recovery deadline")))
