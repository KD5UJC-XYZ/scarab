// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.ledger.Mismatch as FlatMismatch

/** `type data Relation` (`libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:816-829`,
  * cardano-ledger f649f975; `:823-836` at 9df9f98c): the relation a mismatch's supplied value was
  * expected to stand in to its expected one, a kind of types and none of values. Each constructor
  * is a type here, so a [[Mismatch]] of one relation is not one of another.
  */
sealed trait Relation

object Relation:
  /** `RelEQ`: equal. */
  sealed trait RelEQ extends Relation

  /** `RelLT`: less than. */
  sealed trait RelLT extends Relation

  /** `RelGT`: greater than. */
  sealed trait RelGT extends Relation

  /** `RelLTEQ`: less than or equal. */
  sealed trait RelLTEQ extends Relation

  /** `RelGTEQ`: greater than or equal. */
  sealed trait RelGTEQ extends Relation

  /** `RelSubset`: a subset of. */
  sealed trait RelSubset extends Relation

/** `data Mismatch (r :: Relation) a` (`BaseTypes.hs:833-837`, cardano-ledger f649f975; `:840-844`
  * at 9df9f98c): what a predicate was given (supplied) and what it expected, with the relation `R`
  * the supplied value was to stand in to the expected one. The relation is a type only, as the
  * reference's is: the derived `Eq` reads the two values alone.
  */
final case class Mismatch[R <: Relation, A](supplied: A, expected: A)

object Mismatch:
  given [R <: Relation, A](using CanEqual[A, A]): CanEqual[Mismatch[R, A], Mismatch[R, A]] =
    CanEqual.derived

  extension [R <: Relation, A](m: Mismatch[R, A])
    /** The node's flat mismatch (`xyz.kd5ujc.scarab.ledger.Mismatch`), which has no relation, of
      * the same values: glue for the flat projections of the era failures, until those have their
      * own encoders (#85).
      */
    def stored: FlatMismatch[A] = FlatMismatch(m.supplied, m.expected)

    /** The flat mismatch of the values `f` gives, as [[stored]]: for a payload whose flat form is
      * another type (a `Coin` held as its integer, a word widened).
      */
    def storedBy[B](f: A => B): FlatMismatch[B] = FlatMismatch(f(m.supplied), f(m.expected))
