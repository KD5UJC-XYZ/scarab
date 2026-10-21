// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.Fragment
import xyz.kd5ujc.scarab.protocol.Era

/** ChainSel.hs:768–777,1137–1143 (consensus82ecba329): project every candidate through LoE and
  * eligibility before sorting. The sorting comparator is Ord, whose Praos implementation is
  * unrestricted (Praos/Common.hs:180–185); the configured restriction governs eligibility only.
  */
private[node] object CandidateSelection:
  def project(
      k: Int,
      local: ChainSelection.Local,
      candidates: Vector[ChainSelection.Candidate],
      limit: Option[Fragment],
      tiebreaker: Era => ChainSelection.Tiebreaker = ChainSelection.tiebreakerFor
  ): Vector[ChainSelection.Candidate] =
    val eligible = candidates.flatMap { candidate =>
      Follower.limitCandidate(k, local, candidate, limit).toVector.flatMap { projected =>
        val era = projected.headers.lastOption.map(_.era)
        era.toVector.flatMap { value =>
          ChainSelection.decide(tiebreaker(value), local, projected) match
            case ChainSelection.Decision.Switch(at, _, take) =>
              Vector(ChainSelection.Candidate(at, take))
            case ChainSelection.Decision.Keep | ChainSelection.Decision.Detached => Vector.empty
        }
      }
    }
    sort(eligible)((a, b) => compareTips(a.headers.last, b.headers.last))

  private[node] def compareTips(left: Header, right: Header): Int =
    compareViews(SelectView.of(left), SelectView.of(right))

  private[node] def compareViews(left: SelectView, right: SelectView): Int =
    val order = ChainSelection.Tiebreaker.Unrestricted
    if ChainSelection.prefers(order, right, left) then -1
    else if ChainSelection.prefers(order, left, right) then 1
    else 0

  /** GHC9.10.3/base4.20.2.0 Data.List.sortBy natural runs and pairwise stable merges. Preserve the
    * native comparison order even for synthetic views that violate an ordering law. External
    * breadth/depth is handled by tail-recursive machines, including run and merge construction.
    */
  private[node] def sort[A](values: Vector[A])(compare: (A, A) => Int): Vector[A] =
    @tailrec def run(
        previous: A,
        rest: List[A],
        reversed: List[A],
        descending: Boolean
    ): (List[A], List[A]) =
      rest match
        case next :: tail
            if (if descending then compare(previous, next) > 0 else compare(previous, next) <= 0) =>
          run(next, tail, next :: reversed, descending)
        case _ => (if descending then reversed else reversed.reverse) -> rest

    @tailrec def runs(rest: List[A], reversed: List[List[A]]): List[List[A]] = rest match
      case first :: second :: tail =>
        val (ordered, after) = run(second, tail, List(second, first), compare(first, second) > 0)
        runs(after, ordered :: reversed)
      case Nil => reversed.reverse
      case _   => (rest :: reversed).reverse

    @tailrec def merge(left: List[A], right: List[A], reversed: List[A]): List[A] =
      (left, right) match
        case (Nil, _) => reversed.reverse ::: right
        case (_, Nil) => reversed.reverse ::: left
        case (a :: as, b :: bs) =>
          if compare(a, b) > 0 then merge(left, bs, b :: reversed)
          else merge(as, right, a :: reversed)

    @tailrec def pairs(rest: List[List[A]], reversed: List[List[A]]): List[List[A]] = rest match
      case a :: b :: tail => pairs(tail, merge(a, b, Nil) :: reversed)
      case Nil            => reversed.reverse
      case _              => (rest ::: reversed).reverse

    @tailrec def all(pending: List[List[A]]): List[A] = pending match
      case Nil           => Nil
      case single :: Nil => single
      case _             => all(pairs(pending, Nil))

    all(runs(values.toList, Nil)).toVector
