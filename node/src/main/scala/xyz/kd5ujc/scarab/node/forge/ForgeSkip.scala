// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

/** Why a slot was not checked for leadership. Retain the tip until the event is rendered. */
enum ForgeSkip:
  case Busy
  case TipTooOld(tip: Option[BigInt])

  /** The chain's tip is in a later slot than this one: `TraceBlockFromFuture` of
    * `mkCurrentBlockContext` (consensus 82ecba329 NodeKernel.hs:880).
    */
  case BlockFromFuture

  /** The chain holds no volatile block and its anchor (the immutable tip) is in this slot or a
    * later one: `TraceSlotIsImmutable` (NodeKernel.hs:846-850).
    */
  case SlotIsImmutable
  case NotConnected

  def kind: ForgeSkip.Kind = this match
    case Busy            => ForgeSkip.Kind.Busy
    case TipTooOld(_)    => ForgeSkip.Kind.TipTooOld
    case BlockFromFuture => ForgeSkip.Kind.BlockFromFuture
    case SlotIsImmutable => ForgeSkip.Kind.SlotIsImmutable
    case NotConnected    => ForgeSkip.Kind.NotConnected

  def message: String = this match
    case Busy => "the forger was busy with an earlier slot"
    case TipTooOld(tip) =>
      s"the tip (${tip.fold("genesis")(t => s"slot $t")}) is too old to forge from"
    case BlockFromFuture => "the chain's tip is in a later slot"
    case SlotIsImmutable => "the immutable tip is in this slot or a later one"
    case NotConnected    => "the node is not connected"

object ForgeSkip:
  /** Stable keys in `/forging` and the epoch summary. */
  enum Kind(val key: String):
    case Busy extends Kind("busy")
    case TipTooOld extends Kind("tipTooOld")
    case BlockFromFuture extends Kind("blockFromFuture")
    case SlotIsImmutable extends Kind("slotIsImmutable")
    case NotConnected extends Kind("notConnected")
