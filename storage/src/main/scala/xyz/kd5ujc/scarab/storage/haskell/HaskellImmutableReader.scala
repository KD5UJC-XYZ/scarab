// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell

import cats.effect.Async
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.{Chunk, Stream}
import fs2.io.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes

/** A block of the Haskell ImmutableDB. `slot` is the absolute slot (an EBB takes the first slot of
  * its epoch, which is its chunk's first slot while chunks are Byron epochs).
  */
final case class HaskellBlock(
    chunk: ChunkNo,
    position: BlockPosition,
    slot: Long,
    hash: Bytes,
    bytes: Bytes
):
  def isEbb: Boolean = position match
    case BlockPosition.Ebb(_)     => true
    case BlockPosition.Regular(_) => false

/** One chunk, read and validated. `dropped` is set only for the most recent chunk. */
final case class ChunkContents(
    chunk: ChunkNo,
    blocks: Vector[HaskellBlock],
    dropped: Option[Truncation],
    mostRecent: Boolean
)

/** A read-only view of a Haskell ImmutableDB directory (`<db>/immutable`): `NNNNN.chunk`,
  * `.primary` and `.secondary` files, validated as the node validates them. It never writes.
  */
trait HaskellImmutableReader[F[_]]:
  /** The chunk numbers present, which must be contiguous from 0. */
  def chunks: F[Vector[ChunkNo]]

  /** The validated chunks from `from` on, in chain order. Every chunk but the most recent must be
    * entirely valid; the most recent one is cut back to its valid prefix and what was cut is in
    * [[ChunkContents.dropped]] (the node may be writing it).
    */
  def contents(from: ChunkNo = ChunkNo.zero): Stream[F, ChunkContents]

  /** Every block, in chain order: `(slot, hash, isEbb, bytes)` as [[HaskellBlock]]. */
  def blocks(from: ChunkNo = ChunkNo.zero): Stream[F, HaskellBlock] =
    contents(from).flatMap(c => Stream.emits(c.blocks))

object HaskellImmutableReader:
  /** Chunk files sit in `immutableDir` (the `immutable` directory of the database). */
  def apply[F[_]: Async](immutableDir: Path, chunkSlots: ChunkSlots)(using
      Raise[F, HaskellImmutableError]
  ): HaskellImmutableReader[F] =
    new Impl[F](immutableDir, chunkSlots)

  private val FileName = """(\d{5,})\.(chunk|primary|secondary)""".r

  private final class Impl[F[_]](dir: Path, chunkSlots: ChunkSlots)(using
      F: Async[F],
      raise: Raise[F, HaskellImmutableError]
  ) extends HaskellImmutableReader[F]:
    private val files = Files.forAsync[F]

    private def fail[A](e: HaskellImmutableError): F[A] = e.raise[F, A]

    private def guard[A](what: Path)(fa: F[A]): F[A] =
      fa.attempt.flatMap(
        _.fold(e => fail(HaskellImmutableError.Io(what.toString, String.valueOf(e))), F.pure)
      )

    private def present(chunk: ChunkNo, kind: FileKind): Path =
      dir / s"${chunk.stem}.${kind.extension}"

    def chunks: F[Vector[ChunkNo]] =
      for
        isDir <- guard(dir)(files.isDirectory(dir))
        _ <- F.unlessA(isDir)(fail[Unit](HaskellImmutableError.NoImmutableDir(dir.toString)))
        names <- guard(dir)(files.list(dir).map(_.fileName.toString).compile.toVector)
        numbers = names
          .collect { case FileName(digits, _) => digits.toIntOption.flatMap(ChunkNo.of) }
          .flatten
          .distinct
          .sorted(using summon[Ordering[ChunkNo]])
        _ <- numbers.zipWithIndex.collectFirst {
          case (found, i) if found.value != i => (ChunkNo.of(i).getOrElse(ChunkNo.zero), found)
        } match
          case Some((expected, found)) =>
            fail[Unit](HaskellImmutableError.ChunkGap(expected, found))
          case None => F.unit
      yield numbers

    private def readFile(chunk: ChunkNo, kind: FileKind, mostRecent: Boolean): F[Array[Byte]] =
      val path = present(chunk, kind)
      guard(path)(files.exists(path)).flatMap {
        case true =>
          guard(path)(files.readAll(path).compile.to(Chunk).map(_.toArray))
        case false if mostRecent => F.pure(Array.empty[Byte])
        case false               => fail(HaskellImmutableError.MissingFile(chunk, kind))
      }

    private def readChunk(chunk: ChunkNo, mostRecent: Boolean): F[ChunkContents] =
      def invalid[A](f: ChunkFault): F[A] = fail[A](HaskellImmutableError.Invalid(chunk, f))
      for
        data <- readFile(chunk, FileKind.Chunk, mostRecent)
        primaryBytes <- readFile(chunk, FileKind.Primary, mostRecent)
        secondaryBytes <- readFile(chunk, FileKind.Secondary, mostRecent)
        // A most recent chunk that has no index yet holds no block the node had acknowledged.
        primary <-
          if mostRecent && primaryBytes.isEmpty then F.pure(PrimaryIndex(Vector(0L)))
          else
            PrimaryIndex
              .parse(primaryBytes, chunkSlots, tolerateTornTail = mostRecent)
              .fold(invalid, F.pure)
        secondary <- SecondaryIndex
          .parse(secondaryBytes, tolerateTornTail = mostRecent)
          .fold(invalid, F.pure)
        validated <- ChunkValidator
          .validate(chunk, chunkSlots, primary, secondary, data, mostRecent)
          .fold(invalid, F.pure)
      yield ChunkContents(
        chunk,
        validated.blocks.map { b =>
          val slot = b.position match
            case BlockPosition.Ebb(_)     => chunkSlots.firstSlot(chunk)
            case BlockPosition.Regular(s) => s
          HaskellBlock(
            chunk,
            b.position,
            slot,
            b.entry.headerHash,
            Bytes.fromArray(java.util.Arrays.copyOfRange(data, b.start, b.start + b.length))
          )
        },
        validated.dropped,
        mostRecent
      )

    def contents(from: ChunkNo): Stream[F, ChunkContents] =
      Stream.eval(chunks).flatMap { all =>
        val last = all.lastOption
        Stream
          .emits(all.filter(c => c.value >= from.value))
          .evalMap(c => readChunk(c, last.contains(c)))
      }
