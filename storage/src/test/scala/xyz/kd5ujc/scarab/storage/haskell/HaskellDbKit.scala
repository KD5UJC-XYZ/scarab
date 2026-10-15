// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import java.nio.file.{Files as JFiles, Path as JPath}
import java.util.zip.CRC32
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes

/** Builds Haskell ImmutableDB files from blocks, by the rules of ouroboros-consensus 4.2.1.0
  * (`Primary.hs`, `Secondary.hs`, `Layout.hs`): used where no real database exists (Byron
  * epoch-boundary blocks) and to make corrupted variants.
  */
object HaskellDbKit:
  final case class Item(
      position: BlockPosition,
      bytes: Array[Byte],
      hash: Bytes,
      headerOffset: Int,
      headerSize: Int
  )

  final case class ChunkFiles(chunk: Array[Byte], primary: Array[Byte], secondary: Array[Byte])

  def crc32(a: Array[Byte]): Int =
    val c = new CRC32()
    c.update(a, 0, a.length)
    c.getValue.toInt

  def chunkOf(position: BlockPosition, chunkSlots: ChunkSlots): Int = position match
    case BlockPosition.Ebb(epoch)    => epoch.toInt
    case BlockPosition.Regular(slot) => (slot / chunkSlots.value).toInt

  def relativeSlot(position: BlockPosition, chunkSlots: ChunkSlots): Int = position match
    case BlockPosition.Ebb(_)        => 0
    case BlockPosition.Regular(slot) => (slot % chunkSlots.value).toInt + 1

  def slotField(position: BlockPosition): Long = position match
    case BlockPosition.Ebb(epoch)    => epoch
    case BlockPosition.Regular(slot) => slot

  /** The three files of one chunk holding `items` (in chain order). */
  def chunkFiles(chunkSlots: ChunkSlots, items: Seq[Item]): ChunkFiles =
    val offsets = items.scanLeft(0L)((o, i) => o + i.bytes.length)
    val entries = items.zip(offsets).map { (i, o) =>
      SecondaryEntry(
        ByteOffset.of(o).get,
        i.headerOffset,
        i.headerSize,
        crc32(i.bytes),
        i.hash,
        slotField(i.position)
      )
    }
    val primary = PrimaryIndex.build(
      items.map(i => relativeSlot(i.position, chunkSlots)),
      chunkSlots.value.toInt + 1
    )
    ChunkFiles(
      items.flatMap(_.bytes).toArray,
      PrimaryIndex.encode(primary),
      SecondaryIndex.encode(entries)
    )

  /** Write a database: every chunk from 0 to the last one (empty chunks included), plus
    * `protocolMagicId`.
    */
  def write(root: JPath, magic: Long, chunkSlots: ChunkSlots, items: Seq[Item]): Unit =
    val dir = root.resolve("immutable")
    JFiles.createDirectories(dir)
    JFiles.writeString(root.resolve("protocolMagicId"), magic.toString)
    val byChunk = items.groupBy(i => chunkOf(i.position, chunkSlots))
    (0 to byChunk.keys.maxOption.getOrElse(-1)).foreach { c =>
      val files = chunkFiles(chunkSlots, byChunk.getOrElse(c, Nil))
      val stem = f"$c%05d"
      JFiles.write(dir.resolve(s"$stem.chunk"), files.chunk)
      JFiles.write(dir.resolve(s"$stem.primary"), files.primary)
      JFiles.write(dir.resolve(s"$stem.secondary"), files.secondary)
    }

  /** A Shelley-shaped block `[tag, header-as-byte-string]`: the header hash is the Blake2b-256 of
    * the byte string item at offset 2.
    */
  def shelleyLike(position: BlockPosition, payload: Array[Byte], tag: Int = 5): Item =
    val header = cborBytes(payload)
    val block = Array(0x82.toByte, tag.toByte) ++ header
    Item(
      position,
      block,
      Blake2b.hash256.hash(Bytes.fromArray(header)),
      2,
      header.length
    )

  def cborBytes(payload: Array[Byte]): Array[Byte] =
    val n = payload.length
    val head =
      if n < 24 then Array((0x40 + n).toByte)
      else if n < 256 then Array(0x58.toByte, n.toByte)
      else Array(0x59.toByte, (n >> 8).toByte, n.toByte)
    head ++ payload

  /** A fingerprint of every file under `dir`, to show that reading never touched it. */
  def fingerprint(dir: JPath): Map[String, (Long, String)] =
    import scala.jdk.CollectionConverters.*
    val stream = JFiles.walk(dir)
    try
      stream
        .iterator()
        .asScala
        .filter(JFiles.isRegularFile(_))
        .map { p =>
          val bytes = JFiles.readAllBytes(p)
          dir.relativize(p).toString -> (
            JFiles.getLastModifiedTime(p).toMillis,
            java.security.MessageDigest
              .getInstance("SHA-256")
              .digest(bytes)
              .map("%02x".format(_))
              .mkString
          )
        }
        .toMap
    finally stream.close()

  def attempt[A](
      body: Handle[IO, HaskellImportError] ?=> IO[A]
  ): IO[Either[HaskellImportError, A]] =
    Handle
      .allow[HaskellImportError](body.map(_.asRight[HaskellImportError]))
      .rescue(e => IO.pure(e.asLeft[A]))

  def attemptRead[A](
      body: Handle[IO, HaskellImmutableError] ?=> IO[A]
  ): IO[Either[HaskellImmutableError, A]] =
    Handle
      .allow[HaskellImmutableError](body.map(_.asRight[HaskellImmutableError]))
      .rescue(e => IO.pure(e.asLeft[A]))
