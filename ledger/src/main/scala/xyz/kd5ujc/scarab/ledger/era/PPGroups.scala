// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import monocle.Lens
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.ledger.{DRepGroup, StakePoolGroup}

/** `PPGroups` (`data PPGroups = PPGroups DRepGroup StakePoolGroup`, cardano-ledger f649f975
  * `eras/conway/impl/src/Cardano/Ledger/Conway/PParams.hs:534-536`): the DRep group of a Conway
  * parameter and whether the stake pools vote on it. Ordered as the reference derives it.
  */
final case class PPGroups(drep: DRepGroup, pool: StakePoolGroup)

object PPGroups:
  given Ordering[PPGroups] =
    Ordering.by[PPGroups, (Int, Int)](g => (g.drep.ordinal, g.pool.ordinal))

/** The groups of a field, as types (`'PPGroups t s`, the kind `PPGroups` promoted): `D` and `S` are
  * the singleton types of a [[DRepGroup]] and a [[StakePoolGroup]] case.
  */
sealed trait Groups[D <: DRepGroup, S <: StakePoolGroup]

/** `THKD t f a` (`Conway/PParams.hs:576`, cardano-ledger f649f975): a field of a Conway parameter
  * record, tagged with its groups. Equal and ordered as its value.
  */
opaque type THKD[G, F[_], A] = F[A]

object THKD:
  def apply[G, F[_], A](value: F[A]): THKD[G, F, A] = value

  extension [G, F[_], A](t: THKD[G, F, A]) def unTHKD: F[A] = t

  /** The field's value: `lens (unTHKD . get) (\pp x -> pp {field = THKD x})` is this after the
    * record's field.
    */
  def value[G, F[_], A]: Lens[THKD[G, F, A], F[A]] = Lens[THKD[G, F, A], F[A]](t => t)(a => _ => a)

  /** `ppGroup` (`Conway/PParams.hs:625-632`, cardano-ledger f649f975): the groups of an update's
    * field when it is set.
    */
  def ppGroup[D <: DRepGroup, S <: StakePoolGroup, A](
      t: THKD[Groups[D, S], StrictMaybe, A]
  )(using d: ValueOf[D], s: ValueOf[S]): SortedSet[PPGroups] = t match
    case Some(_) => SortedSet(PPGroups(d.value, s.value))
    case None    => SortedSet.empty
