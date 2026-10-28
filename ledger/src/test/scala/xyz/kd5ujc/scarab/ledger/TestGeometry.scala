// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.parser
import java.time.Instant
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.state.{NonZero, PositiveUnitInterval, Word64}

/** Test geometries from literals. The security parameter and the active slot coefficient are read
  * through the same checked constructors as a genesis file; an invalid literal is a broken test.
  */
object TestGeometry:
  def k(n: Long): NonZero[Word64] =
    NonZero.from(Word64.fromUnsigned(n)).fold(e => sys.error(e.show), identity)

  /** The coefficient written as the genesis file writes it: a decimal (`"0.05"`). */
  def f(decimal: String): PositiveUnitInterval =
    parser
      .parse(decimal)
      .leftMap(_.message)
      .flatMap(PositiveUnitInterval.fromJson(_).leftMap(_.message))
      .fold(sys.error, identity)

  def f(r: Rational): PositiveUnitInterval =
    PositiveUnitInterval.fromRational(r).fold(e => sys.error(e.message), identity)

  def apply(
      systemStart: Instant,
      slotLengthMillis: Long,
      epochLength: Long,
      securityParam: Long,
      activeSlotsCoeff: String,
      byron: ByronSpan = ByronSpan.Absent
  ): EpochGeometry =
    EpochGeometry(
      systemStart,
      slotLengthMillis,
      epochLength,
      k(securityParam),
      f(activeSlotsCoeff),
      byron
    )
