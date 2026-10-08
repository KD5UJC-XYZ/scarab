// SPDX-License-Identifier: Apache-2.0
package lab

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import lab.cbor.Bytes
import lab.fetcher.Digests
import lab.network.ChainSync
import scala.util.control.NonFatal

/** Bounded original-byte acquisition evidence, not a consensus or ledger checkpoint. */
object AcquisitionCheckpoint:
  val MaxBytes: Int = 9 * 1024 * 1024
  val Profile: String = "conway-pv9-header11.2-acquisition-v1"
  private val Magic = "acquisition-checkpoint-v1"

  final case class Context private (
      sourceDigest: String,
      genesisDigest: String,
      networkMagic: Long,
      anchor: ChainSync.Point,
      profile: String
  )
  object Context:
    def checked(
        sourceDigest: String,
        genesisDigest: String,
        networkMagic: Long,
        anchor: ChainSync.Point,
        profile: String = Profile
    ): Either[String, Context] =
      val concrete = anchor match
        case ChainSync.Point.Block(_, hash) => hash.size == 32
        case _                              => false
      Either.cond(
        Digests.valid(sourceDigest) && Digests.valid(genesisDigest) &&
          networkMagic >= 0 && networkMagic <= 0xffffffffL && concrete && profile == Profile,
        Context(sourceDigest, genesisDigest, networkMagic, anchor, profile),
        "invalid acquisition context"
      )

  final case class Revision(generation: Long, digest: String)
  final case class Saved(
      context: Context,
      revision: Revision,
      checkpoint: BoundedChainFollower.Checkpoint
  )

  private def valid(
      context: Context,
      generation: Long,
      checkpoint: BoundedChainFollower.Checkpoint
  ): Either[String, Unit] =
    for
      _ <- Context.checked(
        context.sourceDigest,
        context.genesisDigest,
        context.networkMagic,
        context.anchor,
        context.profile
      )
      _ <- Either.cond(
        generation >= 0 && checkpoint.anchor == context.anchor,
        (),
        "generation/anchor mismatch"
      )
      checked <- BoundedChainFollower.checked(context.anchor, checkpoint.originals)
      _ <- checked.originals.foldLeft[Either[String, Unit]](Right(())) { (acc, original) =>
        acc.flatMap { _ =>
          ReferenceCaptureCommand
            .header(original.envelope)
            .flatMap(h =>
              Either.cond(h.major == 11 && h.minor == 2, (), "checkpoint header profile mismatch")
            )
        }
      }
    yield ()

  def encode(
      context: Context,
      generation: Long,
      checkpoint: BoundedChainFollower.Checkpoint
  ): Either[String, Array[Byte]] =
    valid(context, generation, checkpoint).map { _ =>
      val buffer = new ByteArrayOutputStream()
      val out = new DataOutputStream(buffer)
      def field(bytes: Array[Byte]): Unit =
        out.writeInt(bytes.length)
        out.write(bytes)
      def text(value: String): Unit = field(value.getBytes(UTF_8))
      text(Magic)
      text(context.profile)
      text(context.sourceDigest)
      text(context.genesisDigest)
      out.writeLong(context.networkMagic)
      context.anchor match
        case ChainSync.Point.Block(slot, hash) =>
          text(slot.value.toString)
          field(hash.value.toArray)
        case _ => throw new IllegalStateException("validated concrete anchor")
      out.writeLong(generation)
      out.writeInt(checkpoint.size)
      checkpoint.originals.foreach { original =>
        field(original.envelope.value.toArray)
        field(original.block.value.toArray)
      }
      out.flush()
      val payload = buffer.toByteArray
      payload ++ Digests.sha256(payload).getBytes(UTF_8)
    }

  def decode(bytes: Array[Byte], expected: Context): Either[String, Saved] =
    if bytes.length < 64 || bytes.length > MaxBytes then Left("checkpoint byte bound")
    else
      try
        val payload = bytes.dropRight(64)
        val digest = Digests.sha256(payload)
        if !java.util.Arrays.equals(bytes.takeRight(64), digest.getBytes(UTF_8)) then
          Left("checkpoint digest mismatch")
        else
          val in = new DataInputStream(new ByteArrayInputStream(payload))
          def field(max: Int): Array[Byte] =
            val size = in.readInt()
            if size < 0 || size > max || size > in.available() then
              throw new IllegalArgumentException("checkpoint field bound")
            val raw = new Array[Byte](size)
            in.readFully(raw)
            raw
          def text(max: Int): String =
            val raw = field(max)
            val value = new String(raw, UTF_8)
            if !java.util.Arrays.equals(raw, value.getBytes(UTF_8)) then
              throw new IllegalArgumentException("checkpoint UTF-8")
            value
          if text(64) != Magic then throw new IllegalArgumentException("checkpoint format")
          val profile = text(64)
          val source = text(64)
          val genesis = text(64)
          val magic = in.readLong()
          val slotText = text(20)
          if !slotText.matches("0|[1-9][0-9]*") then
            throw new IllegalArgumentException("anchor slot")
          val slot = ChainSync.UInt64
            .from(BigInt(slotText))
            .fold(e => throw new IllegalArgumentException(e), identity)
          val anchor = ChainSync.Point.Block(slot, Bytes.fromArray(field(32)))
          val generation = in.readLong()
          val count = in.readInt()
          if count < 0 || count > 8 then throw new IllegalArgumentException("checkpoint count")
          val originals = Vector.fill(count)(
            BoundedChainFollower.Original(
              Bytes.fromArray(field(65535)),
              Bytes.fromArray(field(1048576))
            )
          )
          if in.available() != 0 then
            throw new IllegalArgumentException("checkpoint trailing bytes")
          for
            context <- Context.checked(source, genesis, magic, anchor, profile)
            _ <- Either.cond(context == expected, (), "stale/cross-source checkpoint context")
            checkpoint <- BoundedChainFollower.checked(anchor, originals)
            _ <- valid(context, generation, checkpoint)
          yield Saved(context, Revision(generation, digest), checkpoint)
      catch case NonFatal(e) => Left("invalid checkpoint: " + e.getMessage)

/** Revision-checked publication of complete, revalidated acquisition evidence. */
trait AcquisitionCheckpointStore[F[_]]:
  def context: AcquisitionCheckpoint.Context
  def snapshot: F[AcquisitionCheckpoint.Saved]
  def save(
      expected: AcquisitionCheckpoint.Revision,
      checkpoint: BoundedChainFollower.Checkpoint
  ): F[AcquisitionCheckpoint.Saved]
