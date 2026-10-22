// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import xyz.kd5ujc.scarab.codec.Bytes

/** Devoted BlockFetch, ouroboros-network 2fb69829 Decision/Genesis.hs. The caller supplies
  * plausible, intersecting candidate suffixes before removing fetched blocks. Flight accounting
  * always includes retired peers: rotation changes preference, never erases outstanding work.
  */
object GenesisBlockFetch:
  import BlockFetchPolicy.*

  final case class State[P](
      order: Vector[P] = Vector.empty,
      current: Option[P] = None,
      startPicos: BigInt = 0
  )
  enum Starvation:
    case Ongoing
    case EndedAt(timePicos: BigInt)
  final case class Result[P](
      state: State[P],
      decisions: Vector[Decision[P]],
      demoteDynamo: Option[P]
  )
  val DefaultGracePicos: BigInt = BigInt(10) * 1000000000000L

  def decide[P](
      config: Config,
      before: State[P],
      nowPicos: BigInt,
      starvation: Starvation,
      candidates: Vector[Candidate[P]],
      gracePicos: BigInt = DefaultGracePicos,
      fetched: Set[Bytes] = Set.empty
  ): Result[P] =
    val starvedAt = starvation match
      case Starvation.Ongoing       => nowPicos
      case Starvation.EndedAt(time) => time
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
      case None => Result(before, rejected, demoted)
      case Some(candidate) =>
        val missing = BlockFetchPolicy.filterFragments(candidate.fragments, h => !fetched(h.hash))
        missing.flatMap(_.headers).headOption match
          case None =>
            Result(
              before,
              rejected ++ valid.map(c => Decision.Declined(c.peer, Refusal.AlreadyFetched)),
              demoted
            )
          case Some(oldest) =>
            // withinFragmentBounds in the native implementation checks points, not merely slots.
            val (unavailable, available) = valid.span(c => !contains(c.fragments, oldest.hash))
            val prior =
              rejected ++ unavailable.map(c => Decision.Declined(c.peer, Refusal.AlreadyFetched))
            available.headOption match
              case None => Result(before, prior, demoted)
              case Some(peer) =>
                val others =
                  available.tail.map(c => Decision.Declined(c.peer, Refusal.GenesisConcurrency(1)))
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
                    Result(state, Vector(request) ++ prior ++ others, demoted)
                  case declined => Result(before, prior ++ others :+ declined, demoted)

  private def contains(fragments: Vector[Fragment], hash: Bytes): Boolean =
    fragments.exists(f => f.anchor.contains(hash) || f.headers.exists(_.hash == hash))

  /** Retain only prefixes up to the latest intersection with the serving peer's suffix. */
  private def trim(fragments: Vector[Fragment], candidate: Vector[Fragment]): Vector[Fragment] =
    fragments.flatMap { fragment =>
      val last = fragment.headers.lastIndexWhere(h => contains(candidate, h.hash))
      if last < 0 then Vector.empty
      else Vector(fragment.copy(headers = fragment.headers.take(last + 1)))
    }
