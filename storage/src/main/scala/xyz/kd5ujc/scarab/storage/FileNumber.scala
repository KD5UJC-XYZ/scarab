// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.util.Locale
import xyz.kd5ujc.scarab.codec.DomainError

/** The decimal numbers in the names of storage files: chunk and log file numbers, and the slots of
  * snapshots and their checkpoints.
  *
  * `%d` writes the digits of the default format locale, and under some locales (Persian, most
  * Arabic ones, Bengali, Marathi, Nepali, Burmese, ...) they are not ASCII, so a store written
  * under one of them would not open under another. A number here is written as the root locale
  * writes it, in ASCII digits under every locale, as the reference writes the numbers of its file
  * names with `show` (ouroboros-consensus 82ecba32: `renderFile`,
  * `Storage/ImmutableDB/Impl/Util.hs:66-69`; `filePath`, `Storage/VolatileDB/Impl/Util.hs:73-74`).
  * They are the bytes `%d` writes under `en_US`.
  *
  * A number is read back from ASCII digits only, as the reference's `readMaybe` reads them
  * (`parseDBFile`, `Storage/ImmutableDB/Impl/Util.hs:78-80`; `parseFd`,
  * `Storage/VolatileDB/Impl/Util.hs:45-51`; `valDecDig`, GHC 9.6.7 `Text/Read/Lex.hs:563-566`).
  * Where the reference passes over a name it cannot read, a store here refuses a name of its own
  * that holds a locale's digits, so that a store an earlier build named under such a locale is
  * never read as numbers it no longer writes, nor opened beside new files of the same numbers.
  */
object FileNumber:

  /** `n` in decimal, padded with zeros to a field of at least `width` characters (a sign included):
    * `%0<width>d` in the root locale.
    */
  def padded(n: Long, width: Int): String = s"%0${width}d".formatLocal(Locale.ROOT, n)

  /** As [[padded]], for a number that may not fit a `Long` (a slot is a `Word64`). */
  def padded(n: BigInt, width: Int): String = s"%0${width}d".formatLocal(Locale.ROOT, n)

  /** The number of `name` when it is `<number><suffix>`, read as the stores read it
    * (`toIntOption`); `None` when it does not end with `suffix` or spells no number, and
    * [[FileNameProblem.LocaleDigits]] when it ends with `suffix` and holds a decimal digit other
    * than ASCII.
    */
  def numberOf(suffix: String)(name: String): Either[FileNameProblem, Option[Int]] =
    if !name.endsWith(suffix) then Right(None)
    else if name.codePoints.anyMatch(c => Character.isDigit(c) && (c < '0' || c > '9')) then
      Left(FileNameProblem.LocaleDigits(name))
    else Right(name.stripSuffix(suffix).toIntOption)

/** A file of a store whose name the store does not read. */
enum FileNameProblem extends DomainError:
  /** `name` holds a decimal digit other than ASCII: a locale's digit, as builds that wrote the
    * numbers of names with the default locale's `%d` wrote them under a locale with other digits.
    * No number is read from it: the store is refused before any of its files changes.
    */
  case LocaleDigits(name: String)

  /** `name` reads as a file number but is not the name the store writes for that number,
    * `canonical` (`1.log` for `00000001.log`): a store finds a file by the name it writes for its
    * number, so it would read and write another file than the one it found. The store is refused
    * before any of its files changes.
    */
  case NotCanonical(name: String, canonical: String)
