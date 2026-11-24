// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.ledger.Header

import HeaderChain.{Entry, Known, TipView}

// The HeaderChain of e201a84bc (issue 810, #1406), kept verbatim but for its name and the
// qualified names of its companion's types: the model HeaderChainModelSuite checks the indexed
// HeaderChain against.

/** The candidate chain of one peer as header validation sees it: the point where it joined ours,
  * the header state there and every header announced since with the state after it (`None` where no
  * state could be derived: a base outside our retained chain).
  */
final case class HeaderChainModel[H](
    base: ChainSync.Point,
    baseState: Option[H],
    entries: Vector[Entry[H]],
    prefix: Vector[Known[H]] = Vector.empty,
    baseView: Option[TipView] = None
):
  def map[J](f: H => J): HeaderChainModel[J] =
    HeaderChainModel(
      base,
      baseState.map(f),
      entries.map(e => Entry(e.header, e.state.map(f))),
      prefix.map(p => Known(p.point, p.state.map(f), p.view)),
      baseView
    )

  /** The local immutable-to-intersection prefix needs points and validated states, never
    * synthesized headers. Accepted CSJ searches may rewind into this captured local prefix.
    */
  def points: Vector[ChainSync.Point] = prefix.map(_.point) ++ (base +: entries.map(_.header.point))

  /** [[points]], which are never empty: the base is always there. */
  def nonEmptyPoints: NonEmptyVector[ChainSync.Point] =
    NonEmptyVector(base, entries.map(_.header.point)).prependVector(prefix.map(_.point))

  def tipHash: Option[Bytes] = entries.lastOption.map(_.header.hash).orElse(baseHash)

  private def baseHash: Option[Bytes] = base match
    case ChainSync.Point.Origin      => None
    case ChainSync.Point.Block(_, h) => Some(h)

  /** The header state after the newest header, if it is known. */
  def tipState: Option[H] = entries.lastOption.fold(baseState)(_.state)

  def tipPoint: ChainSync.Point = entries.lastOption.fold(base)(_.header.point)

  /** The state after the entry with this hash (`Some(None)`: known to be on the chain but its state
    * is not known).
    */
  def stateAfter(hash: Bytes): Option[Option[H]] =
    if baseHash.contains(hash) then Some(baseState)
    else
      entries
        .findLast(_.header.hash == hash)
        .map(_.state)
        .orElse(
          prefix
            .findLast(_.point match
              case ChainSync.Point.Block(_, h) => h == hash
              case ChainSync.Point.Origin      => false)
            .map(_.state)
        )

  // the hashes [[stateAfter]] finds (the base's, the headers' and the prefix's), built on the first
  // lookup: the search for the newest of our blocks on this chain asks for each of them in turn,
  // and a scan per question made header validation quadratic in the candidate's length (issue 1394)
  private lazy val hashes: Set[Bytes] =
    baseHash.toSet ++ entries.iterator.map(_.header.hash) ++ prefix.iterator.flatMap(
      _.point match
        case ChainSync.Point.Block(_, h) => Some(h)
        case ChainSync.Point.Origin      => None
    )

  def contains(hash: Bytes): Boolean = hashes(hash)

  /** The header can follow the tip. The first header of a chain that starts at Origin names the
    * chain's origin (the Byron genesis hash on a chain with a Byron prefix) as its parent, which
    * `VolatileChain` checks against the node's own origin; any parent is accepted there.
    */
  def canExtend(header: Header): Boolean =
    header.prevHash == tipHash || (base == ChainSync.Point.Origin && entries.isEmpty)

  /** Append a header that extends the tip. */
  def extend(header: Header, state: Option[H]): Either[HeaderChain.Error, HeaderChainModel[H]] =
    Either.cond(
      canExtend(header),
      copy(entries = entries :+ Entry(header, state)),
      HeaderChain.Error.DoesNotExtend(header.hash, tipPoint)
    )

  /** The chain as it was at `point` (the base or one of the headers), if the chain passes there. */
  def rollback(point: ChainSync.Point): Option[HeaderChainModel[H]] =
    if point == base then Some(copy(entries = Vector.empty))
    else
      val index = entries.indexWhere(_.header.point == point)
      if index >= 0 then Some(copy(entries = entries.take(index + 1)))
      else
        val known = prefix.indexWhere(_.point == point)
        Option.when(known >= 0)(
          HeaderChainModel(
            point,
            prefix(known).state,
            Vector.empty,
            prefix.take(known),
            prefix(known).view
          )
        )

  /** The chain from `anchor` on, when it passes there: the reference trims its candidate to the
    * anchor of our current chain whenever our chain changes, as the headers before it can never be
    * adopted or rolled back to (`intersectsWithCurrentChain`, `AF.splitAfterPoint theirFrag
    * (AF.anchorPoint ourFrag')`, ouroboros-consensus 82ecba329 `MiniProtocol/ChainSync/Client.hs`
    * 942-992). A header at `anchor` becomes the base, as in [[pruned]]; an anchor in the local
    * prefix drops the prefix before it; a chain that does not pass through `anchor` is kept, for
    * the caller's reintersection to decide (issue 810: without this, a candidate grew to the
    * retention cap and every header's checks grew with it).
    */
  def trimmedTo(anchor: ChainSync.Point): HeaderChainModel[H] =
    if anchor == base then if prefix.isEmpty then this else copy(prefix = Vector.empty)
    // already the first point kept: nothing to drop, and no search of the headers
    else if prefix.headOption.exists(_.point == anchor) then this
    else
      entries.indexWhere(_.header.point == anchor) match
        case -1 =>
          prefix.indexWhere(_.point == anchor) match
            case at if at > 0 => copy(prefix = prefix.drop(at))
            case _            => this
        case at =>
          val last = entries(at)
          copy(
            base = anchor,
            baseState = last.state,
            entries = entries.drop(at + 1),
            prefix = Vector.empty,
            baseView = Some(
              TipView(
                SelectView.of(last.header),
                ChainSelection.tiebreakerFor(last.header.era)
              )
            )
          )

  /** At most `max` of the newest headers: the oldest are folded into the base. */
  def pruned(max: Int): HeaderChainModel[H] =
    val (dropped, kept) = entries.splitAt(entries.size - max)
    // the newest dropped header, if any, becomes the base
    dropped.lastOption.fold(this) { last =>
      copy(
        base = last.header.point,
        baseState = last.state,
        entries = kept,
        prefix = Vector.empty,
        baseView = Some(
          TipView(
            SelectView.of(last.header),
            ChainSelection.tiebreakerFor(last.header.era)
          )
        )
      )
    }
