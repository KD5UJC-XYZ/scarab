// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** How many members of a local root group the governor keeps hot. Positive and at most the warm
  * valency of its group; the only way to get one is [[LocalRoots.fromGroups]].
  */
opaque type HotValency = Int
object HotValency:
  extension (valency: HotValency) def value: Int = valency
  private[selection] def of(n: Int): HotValency = n

/** How many members of a local root group the governor keeps established (warm or hot). At least
  * the hot valency of its group and at most its size.
  */
opaque type WarmValency = Int
object WarmValency:
  extension (valency: WarmValency) def value: Int = valency
  private[selection] def of(n: Int): WarmValency = n

/** Whether we only open the connection or also accept the peer's requests on it. */
enum DiffusionMode:
  case InitiatorOnly, InitiatorAndResponder

/** What a topology says of one local root (`LocalRootConfig`, `State/LocalRootPeers.hs` 47-66):
  * whether we may tell others about it (`advertise`), the diffusion mode and provenance of the
  * connection, and whether the peer is trustable (the Cardano extra flag).
  */
final case class RootConfig(
    advertise: Boolean,
    diffusionMode: DiffusionMode,
    provenance: Provenance,
    trustable: Boolean
)

/** One local root group of a topology file as written: the valencies are whatever the file says. */
final case class RootGroupSpec[A](hot: Int, warm: Int, members: List[(A, RootConfig)])

/** A local root group that satisfies the invariants: `0 < hot <= warm <= members.size`. */
final case class RootGroup[A](hot: HotValency, warm: WarmValency, members: SortedSet[A])

/** The configured local roots: the peers the operator names, in groups with their own valencies. A
  * value satisfies the invariants of the reference (`State/LocalRootPeers.hs` 102-119): the groups
  * are disjoint, and `0 < hot <= warm <= size` in each. The constructor is private, so a value is
  * built by [[LocalRoots.fromGroups]], which repairs any input as the reference does ("bash it
  * until it is valid"), and kept valid by [[LocalRoots.clampToLimit]] and
  * [[LocalRoots.clampToTrustable]].
  */
final case class LocalRoots[A] private (
    config: SortedMap[A, RootConfig],
    groups: Vector[RootGroup[A]]
):
  def isEmpty: Boolean = config.isEmpty
  def size: Int = config.size
  def contains(peer: A): Boolean = config.contains(peer)
  def keys: SortedSet[A] = SortedSet.from(config.keys)(using config.ordering)

  /** The members that are trustable. */
  def trustable(using Ordering[A]): SortedSet[A] =
    SortedSet.from(config.collect { case (peer, c) if c.trustable => peer })

object LocalRoots:
  def empty[A: Ordering]: LocalRoots[A] =
    new LocalRoots(SortedMap.empty[A, RootConfig], Vector.empty)

  /** The groups of a topology file made valid: a member named by an earlier group is dropped from
    * the later ones, a warm valency above the group size is lowered to it, a hot valency above the
    * warm is lowered to it, and a group left with no valency (an empty one, or a zero valency) is
    * dropped (`fromGroups`, `State/LocalRootPeers.hs` 159-180).
    */
  def fromGroups[A: Ordering](specs: Seq[RootGroupSpec[A]]): LocalRoots[A] =
    val (_, groups, config) =
      specs.foldLeft((Set.empty[A], Vector.empty[RootGroup[A]], SortedMap.empty[A, RootConfig])) {
        case ((seen, acc, cfg), spec) =>
          val fresh = spec.members.filterNot((peer, _) => seen(peer))
          val members = SortedSet.from(fresh.map(_._1))
          val warm = spec.warm.min(members.size)
          val hot = spec.hot.min(warm)
          val seen2 = seen ++ spec.members.map(_._1)
          if warm > 0 && hot > 0 then
            (
              seen2,
              acc :+ RootGroup(HotValency.of(hot), WarmValency.of(warm), members),
              cfg ++ fresh
            )
          else (seen2, acc, cfg)
      }
    new LocalRoots(config, groups)

  extension [A](roots: LocalRoots[A])
    /** The first `limit` roots, group by group (`clampToLimit`, 213-220): the root peers may not
      * outnumber the known-peers target. `size == min limit size`.
      */
    def clampToLimit(limit: Int)(using Ordering[A]): LocalRoots[A] =
      val (_, kept) = roots.groups.foldLeft((0, Vector.empty[RootGroup[A]])) {
        case ((n, acc), group) =>
          if n >= limit then (n, acc)
          else if n + group.members.size <= limit then (n + group.members.size, acc :+ group)
          else
            val members = group.members.take(limit - n)
            val warm = group.warm.value.min(members.size)
            val hot = group.hot.value.min(warm)
            (
              limit,
              acc :+ RootGroup(HotValency.of(hot), WarmValency.of(warm), members)
            )
      }
      rebuilt(roots, kept)

    /** Only the trustable members, as the sensitive state requires (`clampToTrustable`,
      * `Cardano/Network/PeerSelection/State/LocalRootPeers.hs`): a group keeps its trustable
      * members with valencies lowered to fit, and a group with none is dropped.
      */
    def clampToTrustable(using Ordering[A]): LocalRoots[A] =
      val kept = roots.groups.flatMap { group =>
        val members = group.members.filter(peer => roots.config.get(peer).exists(_.trustable))
        if members.isEmpty then None
        else
          val warm = group.warm.value.min(members.size)
          val hot = group.hot.value.min(warm)
          Some(RootGroup(HotValency.of(hot), WarmValency.of(warm), members))
      }
      rebuilt(roots, kept)

  private def rebuilt[A: Ordering](
      roots: LocalRoots[A],
      groups: Vector[RootGroup[A]]
  ): LocalRoots[A] =
    val members = groups.flatMap(_.members).toSet
    new LocalRoots(roots.config.filter((peer, _) => members(peer)), groups)
