// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

/** A map that keeps the order its values were read in (`data OMap k v`, cardano-data at
  * cardano-ledger 9df9f98c `libs/cardano-data/src/Data/OMap/Strict.hs:92-95`): a top-level Dijkstra
  * body's sub-transactions, by id. Only the era decoders make one, from values their decoder has
  * already refused a repeated key in (`decodeOMap`, `Data/OMap/Strict.hs:429-435`, cardano-ledger
  * 9df9f98c), each key its value's (`HasOKey`).
  */
opaque type OMap[K, V] = Vector[(K, V)]

object OMap:
  /** The values a decoder read in order, with their keys; none if a key is given twice, which the
    * decoder refuses (`decodeOMap`), so no ordered map holds a key twice.
    */
  private[era] def decoded[K, V](entries: Vector[(K, V)]): Option[OMap[K, V]] =
    Option.when(entries.map(_._1).distinct.sizeIs == entries.size)(entries)

  def empty[K, V]: OMap[K, V] = Vector.empty

  extension [K, V](m: OMap[K, V])
    /** The values in the order they were read (`toStrictSeq`, `Data/OMap/Strict.hs:255-256`,
      * cardano-ledger 9df9f98c).
      */
    def toVector: Vector[V] = m.map(_._2)

    /** The keys in the order their values were read (`toStrictSeqOKeys`,
      * `Data/OMap/Strict.hs:259-260`, cardano-ledger 9df9f98c).
      */
    def keys: Vector[K] = m.map(_._1)

    /** The keys with their values, in the order they were read. */
    def entries: Vector[(K, V)] = m

    /** The value of `key` (`lookup`, `Data/OMap/Strict.hs:138-139`, cardano-ledger 9df9f98c). */
    def get(key: K)(using CanEqual[K, K]): Option[V] = m.collectFirst {
      case (k, v) if k == key => v
    }

    /** `size` (`Data/OMap/Strict.hs:127-128`, cardano-ledger 9df9f98c). */
    def size: Int = m.size

  given [K, V](using CanEqual[K, K], CanEqual[V, V]): CanEqual[OMap[K, V], OMap[K, V]] =
    CanEqual.derived
