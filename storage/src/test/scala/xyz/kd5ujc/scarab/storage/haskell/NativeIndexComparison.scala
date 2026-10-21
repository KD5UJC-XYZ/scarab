// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell

import cats.syntax.all.*
import xyz.kd5ujc.scarab.storage.LegacyFormats

/** Acceptance of a complete captured ImmutableDB, distinct from malformed/torn-reader controls. */
object NativeIndexComparison:
  enum Format:
    case Primary, Secondary
  enum Parser:
    case Current, Legacy
  enum Error:
    case EmptyInventory
    case InvalidStem(stem: String)
    case Inventory(chunks: Set[String], primary: Set[String], secondary: Set[String])
    case Decode(stem: String, format: Format, parser: Parser, cause: ChunkFault)
    case Mismatch(stem: String, format: Format)
    case EmptyDecoded(summary: Summary)

  final case class Summary(
      chunks: Long,
      entries: Long,
      offsets: Long,
      primaryTailBytes: Long = 0L,
      secondaryTailBytes: Long = 0L
  ):
    def combine(other: Summary): Summary =
      Summary(
        chunks + other.chunks,
        entries + other.entries,
        offsets + other.offsets,
        primaryTailBytes + other.primaryTailBytes,
        secondaryTailBytes + other.secondaryTailBytes
      )
  val empty: Summary = Summary(0L, 0L, 0L)

  /** No index-only fallback: every native chunk needs both index files and vice versa. */
  def inventory(files: Vector[String]): Either[Error, Vector[String]] =
    def stems(suffix: String): Set[String] =
      files.filter(_.endsWith(suffix)).map(_.stripSuffix(suffix)).toSet
    val chunks = stems(".chunk")
    val primary = stems(".primary")
    val secondary = stems(".secondary")
    for
      _ <- Either.cond(
        chunks.nonEmpty || primary.nonEmpty || secondary.nonEmpty,
        (),
        Error.EmptyInventory
      )
      _ <- Either.cond(
        chunks == primary && primary == secondary,
        (),
        Error.Inventory(chunks, primary, secondary)
      )
      numbered <- chunks.toVector.traverse { stem =>
        stem.toIntOption
          .filter(n => n >= 0 && f"$n%05d" == stem)
          .toRight(Error.InvalidStem(stem))
          .map(_ -> stem)
      }
    yield numbered.sortBy(_._1).map(_._2)

  private[haskell] def agreement[A](
      stem: String,
      format: Format,
      current: Either[ChunkFault, A],
      legacy: Either[ChunkFault, A]
  ): Either[Error, A] =
    for
      currentValue <- current.leftMap(Error.Decode(stem, format, Parser.Current, _))
      legacyValue <- legacy.leftMap(Error.Decode(stem, format, Parser.Legacy, _))
      _ <- Either.cond(currentValue == legacyValue, (), Error.Mismatch(stem, format))
    yield currentValue

  def compare(
      stem: String,
      primary: Array[Byte],
      secondary: Array[Byte],
      slots: ChunkSlots,
      mostRecent: Boolean = false
  ): Either[Error, Summary] =
    for
      p <- agreement(
        stem,
        Format.Primary,
        PrimaryIndex.parse(primary, slots, tolerateTornTail = mostRecent),
        LegacyFormats.HaskellPrimary.parse(primary, slots, tolerateTornTail = mostRecent)
      )
      s <- agreement(
        stem,
        Format.Secondary,
        SecondaryIndex.parse(secondary, tolerateTornTail = mostRecent),
        LegacyFormats.HaskellSecondary.parse(secondary, tolerateTornTail = mostRecent)
      )
    yield Summary(
      1L,
      s.size.toLong,
      p.offsets.size.toLong,
      if mostRecent then math.max(0, primary.length - 1).toLong % 4 else 0L,
      if mostRecent then secondary.length.toLong % SecondaryIndex.EntrySize else 0L
    )

  def complete(summary: Summary): Either[Error, Summary] =
    Either.cond(
      summary.chunks > 0 && summary.entries > 0 && summary.offsets > 0,
      summary,
      Error.EmptyDecoded(summary)
    )
