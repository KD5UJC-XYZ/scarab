// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

/** A bound that includes its value (`newtype Inclusive a`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:817`, cardano-ledger 9df9f98c).
  */
final case class Inclusive[A](unInclusive: A)

object Inclusive:
  given [A](using CanEqual[A, A]): CanEqual[Inclusive[A], Inclusive[A]] = CanEqual.derived

/** A bound that excludes its value (`newtype Exclusive a`, `BaseTypes.hs:820`, cardano-ledger
  * 9df9f98c).
  */
final case class Exclusive[A](unExclusive: A)

object Exclusive:
  given [A](using CanEqual[A, A]): CanEqual[Exclusive[A], Exclusive[A]] = CanEqual.derived
