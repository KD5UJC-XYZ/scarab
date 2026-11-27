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
  * A block is selected when its hash is the anchor's or a selected block's, and the paths of a
  * selected point start with the blocks whose predecessor is its hash (Origin's: none, or Byron's
  * genesis alias), each place of the chain on its own, as the walk from every selected point treats
  * them.
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

  /** A block a path starts with, after the selected point at `place` (0 the anchor, else one more
    * than the index in the selected blocks).
    */
  private final case class Root(place: Int, base: Point, header: Header)

  /** What the walks back from the targets met: each block on their paths, with those of its
    * successors on them (`paths`, which holds exactly the blocks met), and the blocks the paths
    * start with.
    */
  private final case class Back(paths: Map[Bytes, List[Header]], roots: Vector[Root])

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

    private def selected(hash: Bytes): Boolean =
      local.anchor match
        case Point.Block(_, anchor) if anchor == hash => true
        case Point.Block(_, _) | Point.Origin         => local.placesOf(Some(hash)).nonEmpty

    /** A block a path may go through: in the forest, visible and not selected. */
    private def open(hash: Bytes): Option[Header] =
      forest
        .header(hash)
        .filter(header => !hidden(hash) && aboveImmutable(header) && !selected(hash))

    private def children(header: Header): List[Header] =
      forest.successors(Some(header.hash)).toList.flatMap(open)

    /** Whether a block of predecessor `key` starts the paths of Origin. */
    private def afterOrigin(key: Option[Bytes]): Boolean =
      key.isEmpty || (originPredecessor.nonEmpty && key == originPredecessor)

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
          Map.empty,
          _ => true,
          Vector.empty
        )
      } { targets =>
        val found = targets.toVector
          .flatMap(open)
          .foldLeft(Back(Map.empty, Vector.empty))(back)
        val roots =
          if found.roots.sizeIs <= 1 then found.roots
          else
            found.roots.sortBy(root => (root.place, root.header.hash))(using
              Ordering.Tuple2(using Ordering.Int, hashOrder)
            )
        walk(
          roots.map(root => Walk(root.base, root.header, Nil, false)).toList,
          found.paths,
          targets,
          Vector.empty
        )
      }

    /** The paths through a target, in the order the walk from every selected point meets them:
      * depth first, children in hash order, emitting a tip once its path holds a target. Before a
      * target, only the successors on a path to one are followed (`paths`), and there is always
      * one, so no tip is emitted there.
      */
    @tailrec private def walk(
        pending: List[Walk],
        paths: Map[Bytes, List[Header]],
        targets: Bytes => Boolean,
        result: Vector[ChainSelection.Candidate]
    ): Vector[ChainSelection.Candidate] = pending match
      case Nil => result
      case Walk(base, header, prefix, through) :: rest =>
        val reversed = header :: prefix
        if through || targets(header.hash) then
          children(header) match
            case Nil =>
              walk(
                rest,
                paths,
                targets,
                result :+ ChainSelection.Candidate(base, reversed.reverse.toVector)
              )
            case next =>
              walk(next.map(Walk(base, _, reversed, true)) ::: rest, paths, targets, result)
        else
          val next = paths.getOrElse(header.hash, Nil) match
            case single @ (_ :: Nil) => single
            case several             => several.sortBy(_.hash)(using hashOrder)
          walk(next.map(Walk(base, _, reversed, false)) ::: rest, paths, targets, result)

    /** Back from a target towards the selected chain (`isReachable`, Paths.hs): each block met is
      * on a path, joined to its predecessor when that is a block a path goes through, and is a root
      * of each selected place whose successors it is among. A block met before was walked back from
      * then.
      */
    private def back(found: Back, target: Header): Back =
      @tailrec def go(header: Header, paths: Map[Bytes, List[Header]], roots: Vector[Root]): Back =
        val rooted = bases(header.predecessor) match
          case Vector() => roots
          case places   => roots ++ places.map((place, base) => Root(place, base, header))
        header.predecessor.flatMap(open) match
          case Some(parent) =>
            val joined =
              paths.updated(parent.hash, header :: paths.getOrElse(parent.hash, Nil))
            // a block met before was walked back from then
            if paths.contains(parent.hash) then Back(joined, rooted) else go(parent, joined, rooted)
          case None => Back(paths, rooted)
      if found.paths.contains(target.hash) then found
      else go(target, found.paths.updated(target.hash, Nil), found.roots)

    /** The selected places whose successors a block of predecessor `key` is among, with their
      * points: the anchor's (with Byron's alias when it is Origin) and each block's of that hash.
      */
    private def bases(key: Option[Bytes]): Vector[(Int, Point)] =
      val anchor = local.anchor match
        case Point.Origin                 => Option.when(afterOrigin(key))(0 -> Point.Origin)
        case point @ Point.Block(_, hash) => Option.when(key.contains(hash))(0 -> point)
      val named = key.fold(Vector.empty[Int])(hash => local.placesOf(Some(hash)))
      val blocks = if afterOrigin(key) then (local.placesOf(None) ++ named).sorted else named
      anchor.toVector ++ blocks.flatMap(i => local.blocks.lift(i).map(b => (i + 1) -> b.point))
