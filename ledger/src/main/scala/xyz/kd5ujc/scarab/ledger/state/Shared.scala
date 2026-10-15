// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Eq, Order, Show}
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  Committee,
  Constitution,
  GenDelegPair,
  ParamUpdate,
  PoolParams,
  ProtocolParams,
  Proposals,
  PulsingSnapshot,
  Ptr,
  RatifyState
}

// What the mirror needs of the types it shares with the node's own ledger: instances for the
// records reused as they are (the plan's "reused as-is" list). They are data of immutable
// collections, so equality is the structural one of a case class. They are orphans by necessity (the
// types are not ours to extend here) and top-level, so any file of this package finds them.

given Eq[Bytes] = Eq.fromUniversalEquals
given Show[Bytes] = Show.show(b => s"Bytes(${b.hex})")
given Show[Rational] = Show.fromToString
given Eq[Anchor] = Eq.fromUniversalEquals
given Show[Anchor] = Show.fromToString
given Eq[GenDelegPair] = Eq.fromUniversalEquals
given Show[GenDelegPair] = Show.fromToString
given Eq[PoolParams] = Eq.fromUniversalEquals
given Show[PoolParams] = Show.fromToString
given Eq[ParamUpdate] = Eq.fromUniversalEquals
given Show[ParamUpdate] = Show.fromToString
given Eq[ProtocolParams] = Eq.fromUniversalEquals
given Show[ProtocolParams] = Show.fromToString
given Eq[Proposals] = Eq.fromUniversalEquals
given Show[Proposals] = Show.fromToString
given Eq[Committee] = Eq.fromUniversalEquals
given Show[Committee] = Show.fromToString
given Eq[Constitution] = Eq.fromUniversalEquals
given Show[Constitution] = Show.fromToString
given Eq[PulsingSnapshot] = Eq.fromUniversalEquals
given Show[PulsingSnapshot] = Show.fromToString
given Eq[RatifyState] = Eq.fromUniversalEquals
given Show[RatifyState] = Show.fromToString

/** `Ptr` (`Credential.hs:258-259`, `data Ptr = Ptr !SlotNo32 !TxIx !CertIx deriving (Eq, Ord)`):
  * slot, then transaction index, then certificate index, which is the order the node's `Ptr`
  * already has.
  */
given Order[Ptr] = Order.fromOrdering[Ptr]
given Show[Ptr] = Show.fromToString

/** Maps and sets whose iteration order is the Haskell order of the key, so that traversing one is
  * traversing its CBOR encoding. The order is the key's `cats.Order`, never an `Ordering` found by
  * accident.
  */
object Sorted:
  def map[K: Order, V](entries: IterableOnce[(K, V)]): SortedMap[K, V] =
    SortedMap.from(entries)(using Order[K].toOrdering)

  def empty[K: Order, V]: SortedMap[K, V] = SortedMap.empty(using Order[K].toOrdering)

  def set[K: Order](keys: IterableOnce[K]): SortedSet[K] =
    SortedSet.from(keys)(using Order[K].toOrdering)

  def emptySet[K: Order]: SortedSet[K] = SortedSet.empty(using Order[K].toOrdering)
