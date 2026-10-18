// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

/** Why a slot was not checked for leadership. Retain the tip until the event is rendered. */
enum ForgeSkip:
  case Busy
  case TipTooOld(tip: Option[BigInt])
  case SlotOccupied
  case NotConnected

  def kind: ForgeSkip.Kind = this match
    case Busy         => ForgeSkip.Kind.Busy
    case TipTooOld(_) => ForgeSkip.Kind.TipTooOld
    case SlotOccupied => ForgeSkip.Kind.SlotOccupied
    case NotConnected => ForgeSkip.Kind.NotConnected

  def message: String = this match
    case Busy => "the forger was busy with an earlier slot"
    case TipTooOld(tip) =>
      s"the tip (${tip.fold("genesis")(t => s"slot $t")}) is too old to forge from"
    case SlotOccupied => "a block already occupies this slot or a later one"
    case NotConnected => "the node is not connected"

object ForgeSkip:
  /** Stable keys in `/forging` and the epoch summary. */
  enum Kind(val key: String):
    case Busy extends Kind("busy")
    case TipTooOld extends Kind("tipTooOld")
    case SlotOccupied extends Kind("slotOccupied")
    case NotConnected extends Kind("notConnected")
