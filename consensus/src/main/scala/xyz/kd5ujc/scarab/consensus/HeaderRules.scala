// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.ledger.HeaderError
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.Header

/** Header-only validation of a chain, the work upstream's ChainSync client does before a block is
  * wanted (`Ouroboros.Consensus.MiniProtocol.ChainSync.Client`): each header is checked against a
  * forecast of the ledger view of its slot, taken from `anchor`, a ledger state of the same chain
  * that is not newer than the header's parent, and against `H`, the header state (the Praos
  * chain-dependent state: nonces and operational-certificate counters) after its parent.
  *
  * Pure: the gate that drives it ([[xyz.kd5ujc.scarab.node.HeaderGate]]) owns the effects.
  */
trait HeaderRules[S, H]:
  /** The header state at the tip of `state`. */
  def at(state: S): H

  /** Check `header`, whose parent has header state `prev`. `anchor` is the newest ledger state
    * known to be on the header's chain (`tip` its block, if any). The forecast is from `anchor`, so
    * a header further than the stability window beyond it is [[HeaderRules.Outcome.Later]].
    */
  def validate(anchor: S, tip: Option[BlockRef], prev: H, header: Header): HeaderRules.Outcome[H]

object HeaderRules:
  enum ForecastReason:
    case ShelleyLedgerNotReady
    case Horizon(slot: BigInt, tipSlot: BigInt)
    case ParentStateUnknown
    def message: String = this match
      case ShelleyLedgerNotReady => "the Shelley ledger exists once the first Shelley block is applied"
      case Horizon(slot, tip) => s"slot $slot is beyond the forecast horizon of the ledger at slot $tip"
      case ParentStateUnknown => "the parent's header state is not known yet"
  enum UncheckedReason:
    case Byron, FirstShelley, ParentInBatch, NoCommonLedger
    case ForecastFallback(cause: ForecastReason)
    def message: String = this match
      case Byron => "Byron header: validated with its block"
      case FirstShelley => "first Shelley block after the Byron era: validated with its block"
      case ParentInBatch => "the parent is in the same batch and was not validated header-first"
      case NoCommonLedger => "no common ledger state"
      case ForecastFallback(cause) => s"not validated header-first, nothing left to wait for: ${cause.message}"
  enum Outcome[+H]:
    /** Every header rule holds; the header state after the header. */
    case Valid(next: H)

    /** The header is of a kind that is not checked header-first (a Byron header, the first Shelley
      * header of a chain with a Byron prefix): its block's own validation is the only check. The
      * header state after it, when it is still known.
      */
    case Unchecked(reason: UncheckedReason, next: Option[H])

    /** A rule is violated. */
    case Invalid(error: HeaderError)

    /** The ledger view of the header's slot cannot be forecast from this anchor yet: ask again once
      * the node's chain has advanced.
      */
    case Later(reason: ForecastReason)

/** The candidate chain of one peer as header validation sees it: the point where it joined ours,
  * the header state there and every header announced since with the state after it (`None` where
  * the header was not validated header-first and no state could be derived).
  */
final case class HeaderChain[H](
    base: ChainSync.Point,
    baseState: Option[H],
    entries: Vector[HeaderChain.Entry[H]]
):
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
    else entries.findLast(_.header.hash == hash).map(_.state)

  def contains(hash: Bytes): Boolean = stateAfter(hash).isDefined

  /** The header can follow the tip. The first header of a chain that starts at Origin names the
    * chain's origin (the Byron genesis hash on a chain with a Byron prefix) as its parent, which
    * `VolatileChain` checks against the node's own origin; any parent is accepted there.
    */
  def canExtend(header: Header): Boolean =
    header.prevHash == tipHash || (base == ChainSync.Point.Origin && entries.isEmpty)

  /** Append a header that extends the tip. */
  def extend(header: Header, state: Option[H]): Either[HeaderChain.Error, HeaderChain[H]] =
    Either.cond(
      canExtend(header),
      copy(entries = entries :+ HeaderChain.Entry(header, state)),
      HeaderChain.Error.DoesNotExtend(header.hash, tipPoint)
    )

  /** The chain as it was at `point` (the base or one of the headers), if the chain passes there. */
  def rollback(point: ChainSync.Point): Option[HeaderChain[H]] =
    if point == base then Some(copy(entries = Vector.empty))
    else
      val index = entries.indexWhere(_.header.point == point)
      Option.when(index >= 0)(copy(entries = entries.take(index + 1)))

  /** At most `max` of the newest headers: the oldest are folded into the base. */
  def pruned(max: Int): HeaderChain[H] =
    if entries.size <= max then this
    else
      val (dropped, kept) = entries.splitAt(entries.size - max)
      copy(base = dropped.last.header.point, baseState = dropped.last.state, entries = kept)

object HeaderChain:
  enum Error:
    case DoesNotExtend(header: Bytes, tip: ChainSync.Point)
    def message: String = this match
      case DoesNotExtend(h, _) => s"header ${h.hex.take(16)} does not extend the candidate chain"

  final case class Entry[H](header: Header, state: Option[H])

  def at[H](base: ChainSync.Point, state: Option[H]): HeaderChain[H] =
    HeaderChain(base, state, Vector.empty)
