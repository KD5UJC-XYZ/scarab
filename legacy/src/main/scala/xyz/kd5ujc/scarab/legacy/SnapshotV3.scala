// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import xyz.kd5ujc.scarab.codec.DomainError

import xyz.kd5ujc.scarab.codec.BinaryError
import cats.syntax.all.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.{ProtocolParams, StorageError, Utxo}
import xyz.kd5ujc.scarab.ledger.state.Gap
import xyz.kd5ujc.scarab.protocol.Era

/** FROZEN. What a V3 snapshot holds that the Haskell ledger state beside it has no place for.
  *
  * @param anchor
  *   the last block applied (none: genesis)
  * @param observed
  *   the epochs in which the node saw each era begin where the configuration does not say
  * @param gaps
  *   the gaps the state was written with
  * @param chain
  *   the state itself, by the family of era
  */
final case class SnapshotSidecar(
    anchor: Option[BlockRef],
    observed: Map[Era, BigInt],
    gaps: Set[Gap],
    chain: SnapshotSidecar.Chain
)

object SnapshotSidecar:
  /** The part by family of era. */
  enum Chain:
    /** The chain is still in Byron: the node state whole, and no Haskell section. */
    case InByron(state: NodeState)

    /** A Shelley-based era: the node's UTxO set as the snapshot left it (its bookkeeping, while the
      * entries are in the RocksDB store), the node's protocol parameters (every field in every era)
      * and the extras the Haskell section has no place for.
      */
    case InShelley(
        utxo: Utxo,
        params: ProtocolParams,
        prevParams: Option[ProtocolParams],
        extras: ConsensusExtras.InShelley
    )

  import NodeCodecs.given

  given codec: Codec[SnapshotSidecar] = Codec.auto

  /** The schema fingerprint stored in front of the sidecar: the V3 sidecar's golden
    * (`65d0043c049b7b7a`).
    */
  def fingerprint: Array[Byte] = SnapshotV2.fingerprint(codec.shape)

/** FROZEN. The snapshot of format version 3, the Haskell ledger state with a sidecar:
  *
  * {{{
  * "SCRBSNP3" | sidecar length u32 | sidecar | Haskell state bytes
  * sidecar = schema fingerprint[8] | Codec of SnapshotSidecar
  * }}}
  *
  * This is the reader of the framing, and the writer of it for the tests of the migration; the
  * Haskell section is decoded by the live `storage` code (the codec of the `state` file is a format
  * of the reference node, not ours).
  */
object SnapshotV3:
  val Magic: Array[Byte] = "SCRBSNP3".getBytes("US-ASCII")

  /** Magic and the length of the sidecar. */
  val HeaderSize: Int = Magic.length + 4

  /** The content of a V3 file (without its checksum) from a sidecar and the Haskell state bytes. */
  def framed(sidecar: SnapshotSidecar, haskell: Array[Byte]): Array[Byte] =
    val body = SnapshotSidecar.fingerprint ++ Codec.encode(sidecar)(using SnapshotSidecar.codec)
    val out = new ByteArrayOutputStream(HeaderSize + body.length + haskell.length)
    out.write(Magic)
    out.write(ByteBuffer.allocate(4).putInt(body.length).array())
    out.write(body)
    out.write(haskell)
    out.toByteArray

  /** The sidecar and the Haskell bytes of a V3 file's content, after the magic, the length and the
    * fingerprint have been checked.
    */
  def sections(content: Array[Byte]): Either[StorageError, (SnapshotSidecar, Array[Byte])] =
    def corrupt[E <: DomainError](cause: E): StorageError = StorageError.Corrupt("snapshot", cause)
    if content.length < HeaderSize || !content.startsWith(Magic) then
      Left(corrupt(BinaryError.BadMagic))
    else
      val length = ByteBuffer.wrap(content, Magic.length, 4).getInt
      val fingerprint = SnapshotSidecar.fingerprint
      val end = HeaderSize.toLong + length
      if length < fingerprint.length || end > content.length then
        Left(
          corrupt(SnapshotFramingError.SidecarLength(length, content.length, fingerprint.length))
        )
      else
        val found = content.slice(HeaderSize, HeaderSize + fingerprint.length)
        if !java.util.Arrays.equals(found, fingerprint) then
          Left(
            StorageError.VersionMismatch(
              "snapshot sidecar schema",
              found.map(b => f"${b & 0xff}%02x").mkString,
              fingerprint.map(b => f"${b & 0xff}%02x").mkString
            )
          )
        else
          Codec
            .decode[SnapshotSidecar](content.slice(HeaderSize + fingerprint.length, end.toInt))(
              using SnapshotSidecar.codec
            )
            .leftMap(corrupt)
            .map(_ -> content.drop(end.toInt))
