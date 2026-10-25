// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import scala.annotation.tailrec

/** The list sort of the reference nodes' GHC 9.10.3 (`base-4.20.2.0`, which re-exports
  * `ghc-internal-9.1003.0`): `sortBy` in `libraries/ghc-internal/src/GHC/Internal/Data/OldList.hs`
  * at tag `ghc-9.10.3-release`, lines 1646–1673.
  *
  * The reference applies comparators that are not total preorders, such as ouroboros-network's
  * `comparePeerGSV`, whose 5% tolerance is not transitive. `java.util` sorts (and so Scala's
  * `sorted`, `sortWith` and `sortBy`) may throw on such a comparator, and a different algorithm
  * would order its inputs differently. This is the same algorithm: split the input into maximal
  * runs, strictly descending (`GT` between neighbours, reversed) or ascending (not `GT`), then
  * merge neighbouring runs pairwise until one remains, taking the left element unless it compares
  * `GT`. It therefore returns exactly the reference's order for any comparator, never throws, and
  * is stable for a lawful one. Each step is a tail-recursive machine, so neither the number of
  * elements nor of runs consumes the thread's stack.
  */
object HaskellSort:
  /** `Data.List.sortBy`; `compare(a, b) > 0` stands for `GT`, the only result the algorithm reads.
    */
  def sortBy[A](values: Vector[A])(compare: (A, A) => Int): Vector[A] =
    // `ascending`/`descending`: extend the run that starts with `previous`.
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

    // `sequences`. The reference ends the list of runs with an empty one where the input runs out
    // exactly; merging with an empty run is the identity, so leaving it out changes no result.
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

    // `mergeAll`.
    @tailrec def all(pending: List[List[A]]): List[A] = pending match
      case Nil           => Nil
      case single :: Nil => single
      case _             => all(pairs(pending, Nil))

    all(runs(values.toList, Nil)).toVector
