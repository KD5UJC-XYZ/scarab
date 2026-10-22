// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import scala.annotation.tailrec
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** The processed VolatileDB forest, not the BlockFetch receipt queue. Mirrors consensus82ecba329
  * ChainSel.hs:358–408,710–777 and Paths.hs:63–130: only maximal contiguous extensions of retained
  * selected points are candidates. Preference, LoE trimming and global candidate ordering belong to
  * the caller. Headers, including their original signed bytes, are never reconstructed here.
  */
private[node] object AvailableBlocks:
  enum Error:
    case HashMismatch(key: Bytes, actual: Bytes)
    case Cycle(hash: Bytes)

  private val hashOrder: Ordering[Bytes] = new Ordering[Bytes]:
    def compare(a: Bytes, b: Bytes): Int =
      a.value.iterator
        .zip(b.value.iterator)
        .map((x, y) => Integer.compare(x & 255, y & 255))
        .find(_ != 0)
        .getOrElse(Integer.compare(a.size, b.size))

  /** `containing` restricts a first-admission pass to paths involving that actual adding block;
    * `None` is the unpunished LoE reprocessing pass. `available` must exclude later queued bodies.
    * Byron's configured genesis predecessor can be supplied as an exact alias for Origin.
    */
  def candidates(
      local: ChainSelection.Local,
      available: Map[Bytes, Header],
      invalid: Set[Bytes],
      containing: Option[Bytes] = None,
      originPredecessor: Option[Bytes] = None
  ): Either[Error, Vector[ChainSelection.Candidate]] =
    val ordered = available.toVector.sortBy(_._1)(hashOrder)
    val checked = ordered.traverse_ { (key, header) =>
      Either.cond(key == header.hash, (), Error.HashMismatch(key, header.hash))
    }
    // ChainSel.hs:574–582: an EBB may share the immutable predecessor's block number.
    val visible = ordered.filter { (hash, header) =>
      !invalid(
        hash
      ) && (local.anchor == Point.Origin || header.blockNo > local.anchorView.blockNo ||
        (header.isBoundary && header.blockNo == local.anchorView.blockNo))
    }.toMap
    for
      _ <- checked
      _ <- acyclic(visible)
    yield
      val selected = local.anchor +: local.blocks.map(_.point)
      val selectedHashes = selected.flatMap(hashOf).toSet
      val successors = visible.valuesIterator.toVector
        .groupBy(_.prevHash)
        .view
        .mapValues(_.sortBy(_.hash)(hashOrder))
        .toMap
      val initial = selected.toList.flatMap { point =>
        val parent = point match
          case Point.Origin => originPredecessor
          case _            => hashOf(point)
        val children =
          if point == Point.Origin && originPredecessor.nonEmpty then
            (successors.getOrElse(None, Vector.empty) ++ successors.getOrElse(parent, Vector.empty))
              .sortBy(_.hash)(hashOrder)
          else successors.getOrElse(parent, Vector.empty)
        children.filterNot(h => selectedHashes(h.hash)).map(h => Walk(point, h, Nil)).toList
      }
      walk(initial, successors, selectedHashes, containing, Vector.empty)

  private def hashOf(point: Point): Option[Bytes] = point match
    case Point.Origin         => None
    case Point.Block(_, hash) => Some(hash)

  private final case class Walk(base: Point, header: Header, reversed: List[Header])

  @tailrec private def walk(
      pending: List[Walk],
      successors: Map[Option[Bytes], Vector[Header]],
      selected: Set[Bytes],
      containing: Option[Bytes],
      result: Vector[ChainSelection.Candidate]
  ): Vector[ChainSelection.Candidate] = pending match
    case Nil => result
    case Walk(base, header, prefix) :: rest =>
      val reversed = header :: prefix
      val children = successors
        .getOrElse(Some(header.hash), Vector.empty)
        .filterNot(h => selected(h.hash))
      if children.nonEmpty then
        walk(
          children.toList.map(h => Walk(base, h, reversed)) ::: rest,
          successors,
          selected,
          containing,
          result
        )
      else
        val include = containing.forall(hash => reversed.exists(_.hash == hash))
        val grown =
          if include then result :+ ChainSelection.Candidate(base, reversed.reverse.toVector)
          else result
        walk(rest, successors, selected, containing, grown)

  private def acyclic(available: Map[Bytes, Header]): Either[Error, Unit] =
    @tailrec def predecessors(
        current: Option[Bytes],
        path: Set[Bytes],
        done: Set[Bytes]
    ): Either[Error, Set[Bytes]] =
      current match
        case Some(hash) if path(hash) => Left(Error.Cycle(hash))
        case Some(hash) if !done(hash) && available.contains(hash) =>
          predecessors(available.get(hash).flatMap(_.prevHash), path + hash, done)
        case _ => Right(done ++ path)
    available.keys.toVector
      .sorted(hashOrder)
      .foldLeft[Either[Error, Set[Bytes]]](Right(Set.empty)) { (state, hash) =>
        state.flatMap(done => predecessors(Some(hash), Set.empty, done))
      }
      .void
