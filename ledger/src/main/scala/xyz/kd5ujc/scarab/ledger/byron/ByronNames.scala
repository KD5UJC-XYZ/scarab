// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

/** Why a system tag of a proposal's installer metadata is refused (`SystemTagError`,
  * `Update/SystemTag.hs:70-73`, cardano-ledger f649f975).
  */
enum SystemTagError:
  case NotAscii(tag: String)
  case TooLong(tag: String)

object SystemTagError:
  /** `systemTagMaxLength` (`Update/SystemTag.hs:67-68`). */
  val MaxLength: Int = 10

  /** `checkSystemTag` (`Update/SystemTag.hs:113-117`): too long first, then not ASCII. */
  def check(tag: String): Either[SystemTagError, Unit] =
    if ByronNames.length(tag) > MaxLength then Left(TooLong(tag))
    else if !ByronNames.ascii(tag) then Left(NotAscii(tag))
    else Right(())

/** Why an application name is refused (`ApplicationNameError`, `Update/ApplicationName.hs:64-67`).
  */
enum ApplicationNameError:
  case TooLong(name: String)
  case NotAscii(name: String)

object ApplicationNameError:
  /** `applicationNameMaxLength` (`Update/ApplicationName.hs:122-123`). */
  val MaxLength: Int = 12

  /** `checkApplicationName` (`Update/ApplicationName.hs:113-120`): too long first, then not ASCII.
    */
  def check(name: String): Either[ApplicationNameError, Unit] =
    if ByronNames.length(name) > MaxLength then Left(TooLong(name))
    else if !ByronNames.ascii(name) then Left(NotAscii(name))
    else Right(())

/** Why a software version is refused (`SoftwareVersionError`, `Update/SoftwareVersion.hs:78-80`):
  * `checkSoftwareVersion` checks its application name only (`Update/SoftwareVersion.hs:112-116`).
  */
enum SoftwareVersionError:
  case ApplicationName(cause: ApplicationNameError)

object SoftwareVersionError:
  def check(name: String): Either[SoftwareVersionError, Unit] =
    ApplicationNameError.check(name).left.map(ApplicationName(_))

/** The text measures of the name checks, as `Data.Text` takes them. */
private[byron] object ByronNames:
  /** `T.length`: the number of characters, code points, not UTF-16 units. */
  def length(s: String): Int = s.codePointCount(0, s.length)

  /** `T.all isAscii`: every character below U+0080 (a surrogate is not). */
  def ascii(s: String): Boolean = s.forall(_ < '\u0080')
