// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.codec.HaskellSort
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.Fragment
import xyz.kd5ujc.scarab.protocol.Era

/** ChainSel.hs:768–777,1137–1143 (consensus82ecba329): project every candidate through LoE and
  * eligibility before sorting. The sorting comparator is Ord, whose Praos implementation is
  * unrestricted (Praos/Common.hs:180–185); the configured restriction governs eligibility only.
  */
private[node] object CandidateSelection:
  /** ChainSel.hs:159–211 initialChainSelection ranks from the immutable anchor and caps paths at k
    * when LoE is enabled. A live nonempty limit must not expand that initial bound.
    */
  def startup(
      k: Int,
      local: ChainSelection.Local,
      candidates: Vector[ChainSelection.Candidate],
      limit: Option[Fragment],
      tiebreaker: Era => ChainSelection.Tiebreaker
  ): Vector[ChainSelection.Candidate] =
    project(
      k,
      local.copy(blocks = Vector.empty),
      candidates,
      limit.map(_ => Fragment(local.anchor, Vector.empty)),
      tiebreaker
    )

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
            // each candidate with its tip, which the ranking compares
            case ChainSelection.Decision.Switch(at, _, take) =>
              take.lastOption.map(tip => (ChainSelection.Candidate(at, take), tip)).toVector
            case ChainSelection.Decision.Keep | ChainSelection.Decision.Detached => Vector.empty
        }
      }
    }
    HaskellSort.sortBy(eligible)((a, b) => compareTips(a._2, b._2)).map(_._1)

  private[node] def compareTips(left: Header, right: Header): Int =
    compareViews(SelectView.of(left), SelectView.of(right))

  private[node] def compareViews(left: SelectView, right: SelectView): Int =
    val order = ChainSelection.Tiebreaker.Unrestricted
    if ChainSelection.prefers(order, right, left) then -1
    else if ChainSelection.prefers(order, left, right) then 1
    else 0
