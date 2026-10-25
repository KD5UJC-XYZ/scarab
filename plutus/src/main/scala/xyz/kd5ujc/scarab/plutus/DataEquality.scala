// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.builtin.Data
import xyz.kd5ujc.scarab.codec.Tree
import xyz.kd5ujc.scarab.codec.Tree.treeEquals

/** Equality of the machine's `Data` as the reference's `equalsData` computes it: the derived `Eq`
  * of `PlutusCore.Data` (`PlutusCore/Data.hs`, plutus 1.70.0.0 at 507cab8c), structural over the
  * ordered list of a `Map`, in which order and repeated entries both count.
  *
  * Scalus 1.3.0 and 1.4.0 define `Data.Map.equals` and `hashCode` over `values.toScalaList.toSet`
  * (`Data.scala:162-167`) and implement `equalsData` as `d1 == d2`, so `{1:1, 2:2}` equals
  * `{2:2, 1:1}` and `{1:1, 2:2}` equals `{1:1, 2:2, 1:1}` there. This is the walk of
  * [[xyz.kd5ujc.scarab.codec.Tree.equal]] on the heap (ADR 0011): it never calls the `equals` of a
  * `Data.Map`, and deep data does not recurse. A constructor is compared through the `constr`
  * accessor, which every `Data.Constr` reader of the pinned Scalus uses ([[ConstrIndex]]).
  *
  * The `equalsData` costing is on the sizes of the two arguments and is applied before the builtin
  * runs, so it does not depend on this function.
  */
private[plutus] object DataEquality:
  given Tree[Data] with
    def children(d: Data): IndexedSeq[Data] = d match
      case Data.Constr(_, fields) => PList.asScala(fields).toVector
      case Data.Map(entries) =>
        PList.asScala(entries).iterator.flatMap((key, value) => Iterator(key, value)).toVector
      case Data.List(items) => PList.asScala(items).toVector
      case _                => Vector.empty
    def sameLabel(x: Data, y: Data): Boolean = (x, y) match
      case (Data.Constr(a, _), Data.Constr(b, _)) => a == b
      case (Data.Map(_), Data.Map(_))             => true
      case (Data.List(_), Data.List(_))           => true
      case (Data.I(a), Data.I(b))                 => a == b
      case (Data.B(a), Data.B(b))                 => a == b
      case _                                      => false
    def labelHash(d: Data): Int = d match
      case Data.Constr(a, _) => a.hashCode
      case Data.Map(_)       => 1
      case Data.List(_)      => 2
      case Data.I(a)         => 41 + a.hashCode
      case Data.B(a)         => a.hashCode
    def name(d: Data): String = d match
      case Data.Constr(a, _) => s"Constr($a)"
      case Data.Map(_)       => "Map"
      case Data.List(_)      => "List"
      case Data.I(a)         => s"I($a)"
      case Data.B(a)         => s"B(${a.toHex})"

  /** The reference's `Data` equality. */
  def equal(x: Data, y: Data): Boolean = x.treeEquals(y)
