// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import xyz.kd5ujc.scarab.codec.Bytes

/** `GenesisBlockFetch.decide` as it was before issue 810, kept as the model the production decision
  * must agree with: its `trim` looks for each header of the fragments to fetch by a linear scan of
  * the serving peer's candidate, a cost of the product of their lengths.
  */
object GenesisBlockFetchModel:
  import BlockFetchPolicy.*

  def decide[P](
      config: Config,
      before: GenesisBlockFetch.State[P],
      nowPicos: BigInt,
      starvation: GenesisBlockFetch.Starvation,
      candidates: Vector[Candidate[P]],
      gracePicos: BigInt = GenesisBlockFetch.DefaultGracePicos,
      fetched: Fetched = Fetched.none
  ): GenesisBlockFetch.Result[P] =
    val starvedAt = starvation match
      case GenesisBlockFetch.Starvation.Ongoing       => nowPicos
      case GenesisBlockFetch.Starvation.EndedAt(time) => time
    val demoted = before.current.filter(_ => starvedAt >= before.startPicos + gracePicos)
    val rotated = demoted.fold(before)(peer =>
      before.copy(order = before.order.drop(1) :+ peer, current = None)
    )
    val live = candidates.map(_.peer).toSet
    val order =
      rotated.order.filter(live) ++ candidates.map(_.peer).filterNot(rotated.order.contains)
    val aligned = rotated.copy(
      order = order,
      current = rotated.current.filter(p => order.headOption.contains(p))
    )
    val ordered = order.flatMap(p => candidates.find(_.peer == p))
    val (invalid, valid) = ordered.partition(_.refusal.isDefined)
    val rejected =
      invalid.map(c => Decision.Declined(c.peer, c.refusal.getOrElse(Refusal.InvalidCandidate)))
    val best = valid.foldLeft(Option.empty[Candidate[P]]) { (current, candidate) =>
      current match
        case Some(previous) if previous.chainPreference >= candidate.chainPreference => current
        case _ => Some(candidate)
    }
    best match
      case None => GenesisBlockFetch.Result(before, rejected, demoted)
      case Some(candidate) =>
        val missing =
          BlockFetchPolicy.filterFragments(candidate.fragments, h => !fetched.contains(h.hash))
        missing.flatMap(_.headers).headOption match
          case None =>
            GenesisBlockFetch.Result(
              before,
              rejected ++ valid.map(c => Decision.Declined(c.peer, Refusal.AlreadyFetched)),
              demoted
            )
          case Some(oldest) =>
            // withinFragmentBounds in the native implementation checks points, not merely slots.
            val (unavailable, available) = valid.span(c => !contains(c.fragments, oldest.hash))
            val prior =
              rejected ++ unavailable.map(c => Decision.Declined(c.peer, Refusal.AlreadyFetched))
            available match
              case peer +: rest =>
                val others =
                  rest.map(c => Decision.Declined(c.peer, Refusal.GenesisConcurrency(1)))
                val ownRemoved =
                  BlockFetchPolicy.filterFragments(missing, h => !peer.inFlight.blocks(h.hash))
                val trimmed = trim(ownRemoved, peer.fragments)
                val reason =
                  if ownRemoved.isEmpty then Some(Refusal.InFlightThisPeer)
                  else if trimmed.isEmpty then Some(Refusal.AlreadyFetched)
                  else None
                val decision = reason.fold(
                  BlockFetchPolicy.select(
                    config.copy(bulkConcurrency = Int.MaxValue),
                    FetchMode.BulkSync,
                    0,
                    peer,
                    trimmed
                  )
                )(r => Decision.Declined(peer.peer, r))
                decision match
                  case request: Decision.Request[P] =>
                    val updated = aligned.copy(
                      order = peer.peer +: order.filterNot(_ == peer.peer),
                      current = Some(peer.peer),
                      startPicos =
                        if peer.inFlight.blocks.isEmpty then nowPicos else aligned.startPicos
                    )
                    val state =
                      if peer.inFlight.blocks.isEmpty || !before.current.contains(peer.peer) then
                        updated
                      else before
                    GenesisBlockFetch.Result(state, Vector(request) ++ prior ++ others, demoted)
                  case declined =>
                    GenesisBlockFetch.Result(before, prior ++ others :+ declined, demoted)
              case _ => GenesisBlockFetch.Result(before, prior, demoted)

  def contains(fragments: Vector[Fragment], hash: Bytes): Boolean =
    fragments.exists(f => f.anchor.contains(hash) || f.headers.exists(_.hash == hash))

  /** The trimming before issue 810: each header of `fragments` looked for by a scan of `candidate`.
    */
  def trim(fragments: Vector[Fragment], candidate: Vector[Fragment]): Vector[Fragment] =
    fragments.flatMap { fragment =>
      val last = fragment.headers.lastIndexWhere(h => contains(candidate, h.hash))
      if last < 0 then Vector.empty
      else Vector(fragment.copy(headers = fragment.headers.take(last + 1)))
    }
