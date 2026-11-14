// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

/** A sequence without repeats, in the order it was read (`data OSet a`, cardano-data at
  * cardano-ledger f649f975 `libs/cardano-data/src/Data/OSet/Strict.hs:71-74`): a body's
  * certificates and proposals. Only the era decoders make one, from a list their decoder has
  * already refused repeats in (`decodeOSet`, `Data/OSet/Strict.hs:109-116`, cardano-ledger
  * f649f975).
  */
opaque type OSet[A] = Vector[A]

object OSet:
  /** The elements a decoder read, which it refused a repeat in. */
  private[era] def decoded[A](elements: Vector[A]): OSet[A] = elements

  def empty[A]: OSet[A] = Vector.empty

  extension [A](s: OSet[A])
    /** The elements, in the order they were read. */
    def toVector: Vector[A] = s
    def size: Int = s.size

  given [A](using CanEqual[A, A]): CanEqual[OSet[A], OSet[A]] = CanEqual.derived
