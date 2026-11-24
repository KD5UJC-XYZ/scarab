// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** The candidates of the processed VolatileDB forest, not the BlockFetch receipt queue. Mirrors
  * ouroboros-consensus 82ecba32 ChainSel.hs:358–408,710–777 and Paths.hs:63–130: only maximal
  * contiguous extensions of retained selected points are candidates. Preference, LoE trimming and
  * global candidate ordering belong to the caller. Headers, including their original signed bytes,
  * are never reconstructed here.
  *
  * The walks read the forest through its successors index ([[ProcessedForest.successors]], the
  * reference's `filterByPredecessor`), so only the blocks they reach are read. The candidates are
  * every maximal path from a selected point through visible blocks that are not selected, children
  * in hash order ([[ProcessedForest.hashOrder]]), the selected points in the order of the chain
  * (anchor first); `containing` keeps the paths through one of its blocks.
  *
  * A selected point is told by [[ChainSelection.Local.contains]] on a block's point: the selected
  * chain names each of its blocks by the point of the block's header, the hash of which determines
  * it, and holds no point twice.
  */
private[node] object AvailableBlocks:
  import ProcessedForest.hashOrder

  /** `containing` restricts a first-admission pass to paths involving that actual adding block (or
    * one of the ranking blocks a reprocessing of an endorser block reconsiders): as the reference's
    * `chainSelectionForBlock` finds them (ouroboros-consensus 82ecba32 `ChainSel.hs:710–777`), from
    * those blocks alone, walking back to the selected chain (`isReachable`, `Paths.hs:393–482`) and
    * on through the successors (`extendWithSuccessors`, `Paths.hs:63–130`). `None` is the
    * unpunished LoE reprocessing pass, which starts from the successors of every selected point
    * (`ChainSelReprocessLoEBlocks`, `ChainSel.hs:372–408`). `hidden` blocks (invalid, withheld or
    * parked) are neither tips nor successors, as `ignoreInvalidSuc` hides them
    * (`ChainSel.hs:1406–1417`). The forest must not hold later queued bodies. Byron's configured
    * genesis predecessor can be supplied as an exact alias for Origin.
    *
    * The order and the blocks are those of the reference's walks over this node's selected chain;
    * which block a walk back stops at is decided by the hash of a selected point, not by the block
    * numbers `isReachable` compares, as the whole-forest walk of every selected point decided it.
    */
  def candidates[A](
      local: ChainSelection.Local,
      forest: ProcessedForest[A],
      hidden: Bytes => Boolean,
      containing: Option[Set[Bytes]] = None,
      originPredecessor: Option[Bytes] = None
  ): Vector[ChainSelection.Candidate] =
    new Walks(local, forest, hidden, originPredecessor).candidates(containing)

  private final case class Walk(
      base: Point,
      header: Header,
      reversed: List[Header],
      through: Boolean
  )

  private final class Walks[A](
      local: ChainSelection.Local,
      forest: ProcessedForest[A],
      hidden: Bytes => Boolean,
      originPredecessor: Option[Bytes]
  ):
    // ChainSel.hs:574–582 (ouroboros-consensus 82ecba32): an EBB may share the immutable
    // predecessor's block number.
    private def aboveImmutable(header: Header): Boolean =
      local.anchor == Point.Origin || header.blockNo > local.anchorView.blockNo ||
        (header.isBoundary && header.blockNo == local.anchorView.blockNo)

    /** A block a path may go through: in the forest, visible and not selected. */
    private def open(hash: Bytes): Option[Header] =
      forest
        .header(hash)
        .filter(header => !hidden(hash) && aboveImmutable(header) && !local.contains(header.point))

    private def children(header: Header): List[Header] =
      forest.successors(Some(header.hash)).toList.flatMap(open)

    /** The blocks after `point` that a path starts with, in hash order. */
    private def expansion(point: Point): List[Header] =
      val successors = point match
        case Point.Origin =>
          originPredecessor.fold(forest.successors(None))(alias =>
            forest.successors(None) ++ forest.successors(Some(alias))
          )
        case Point.Block(_, hash) => forest.successors(Some(hash))
      successors.toList.flatMap(open)

    def candidates(containing: Option[Set[Bytes]]): Vector[ChainSelection.Candidate] =
      containing.fold {
        val selected = local.anchor +: local.blocks.map(_.point)
        walk(
          selected.toList.flatMap(point => expansion(point).map(Walk(point, _, Nil, true))),
          Set.empty,
          _ => true,
          Vector.empty
        )
      } { targets =>
        val (relevant, roots) = targets.toVector
          .flatMap(open)
          .foldLeft((Set.empty[Bytes], Vector.empty[(Point, Header)])) {
            case ((seen, found), target) => back(target, seen, found)
          }
        walk(
          ordered(roots).map((base, root) => Walk(base, root, Nil, false)).toList,
          relevant,
          targets,
          Vector.empty
        )
      }

    /** The paths through a target, in the order the walk from every selected point meets them:
      * depth first, children in hash order, emitting a tip once its path holds a target. Below a
      * block whose path holds none, only the children that lead to one (`relevant`) are walked.
      */
    @tailrec private def walk(
        pending: List[Walk],
        relevant: Set[Bytes],
        targets: Bytes => Boolean,
        result: Vector[ChainSelection.Candidate]
    ): Vector[ChainSelection.Candidate] = pending match
      case Nil => result
      case Walk(base, header, prefix, through) :: rest =>
        val reversed = header :: prefix
        val reached = through || targets(header.hash)
        children(header) match
          case Nil =>
            val grown =
              if reached then result :+ ChainSelection.Candidate(base, reversed.reverse.toVector)
              else result
            walk(rest, relevant, targets, grown)
          case next =>
            val followed = if reached then next else next.filter(h => relevant(h.hash))
            walk(followed.map(Walk(base, _, reversed, reached)) ::: rest, relevant, targets, result)

    /** Back from `header` towards the selected chain (`isReachable`, Paths.hs): each block met is
      * `relevant`, and a block is a root of each selected point whose successors it is among. A
      * block already met was walked back from before.
      */
    @tailrec private def back(
        header: Header,
        seen: Set[Bytes],
        found: Vector[(Point, Header)]
    ): (Set[Bytes], Vector[(Point, Header)]) =
      if seen(header.hash) then (seen, found)
      else
        val rooted = found ++ bases(header.prevHash).map(_ -> header)
        header.prevHash.flatMap(open) match
          case Some(parent) => back(parent, seen + header.hash, rooted)
          case None         => (seen + header.hash, rooted)

    /** The selected points whose successors a block of predecessor `key` is among: the anchor (with
      * Byron's alias when it is Origin), and the selected block of that hash.
      */
    private def bases(key: Option[Bytes]): Vector[Point] =
      val anchor = local.anchor match
        case Point.Origin =>
          Option.when(key.isEmpty || (originPredecessor.nonEmpty && key == originPredecessor))(
            Point.Origin
          )
        case point @ Point.Block(_, hash) => Option.when(key.contains(hash))(point)
      anchor.toVector ++ key.flatMap(selectedBlock).toVector

    /** The selected block (not the anchor) of `hash`: by its header's point when the forest holds
      * it, else looked for in the selected chain.
      */
    private def selectedBlock(hash: Bytes): Option[Point] =
      forest.header(hash) match
        case Some(header) =>
          Option.when(header.point != local.anchor && local.contains(header.point))(header.point)
        case None =>
          local.blocks.reverseIterator.map(_.point).find {
            case Point.Block(_, selected) => selected == hash
            case Point.Origin             => false
          }

    /** Roots in the order the selected points are walked: by the point's place in the chain, then
      * by hash, as the point's successors are.
      */
    private def ordered(roots: Vector[(Point, Header)]): Vector[(Point, Header)] =
      if roots.sizeIs <= 1 then roots
      else
        roots.sortBy((base, root) => (place(base), root.hash))(using
          Ordering.Tuple2(using Ordering.Int, hashOrder)
        )

    private def place(point: Point): Int =
      if point == local.anchor then 0 else local.blocks.lastIndexWhere(_.point == point) + 1
