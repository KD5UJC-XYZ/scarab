// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import xyz.kd5ujc.scarab.consensus.{BootstrapState, ByronState, ConsensusExtras}
import xyz.kd5ujc.scarab.consensus.state.{HeaderState, ProtocolState}
import xyz.kd5ujc.scarab.ledger.EpochGeometry

/** The node state of snapshot format 4 and of the old rules (FROZEN, J2.9): the Haskell header state,
  * the flat ledger state and the extras. It is what the older snapshot files hold and what
  * [[StateMirror]] turns into the mirror.
  *
  * The header state holds the chain-dependent state of the protocol the chain runs and nothing else
  * of Haskell's: its tip is the origin and its telescope is not placed on the era history
  * ([[HeaderState.live]]), because the node knows the tip as the anchor of its chain and the bounds
  * as a function of the configuration. [[xyz.kd5ujc.scarab.consensus.state.StateMirror]] gives
  * both. While the chain is in Byron it holds the state Shelley will start from, and the PBFT
  * window is in [[ByronState.pbft]].
  */
final case class FlatNodeState(
    header: HeaderState,
    ledger: LedgerState,
    extras: ConsensusExtras
):
  def boot: BootstrapState = extras.boot
  def byron: ByronState = extras.byron

  /** The chain-dependent state of the protocol, with what the node keeps beside it. */
  def protocol: ProtocolState = ProtocolState(header.chainDep.state, extras.protocol)

  def withProtocol(p: ProtocolState): FlatNodeState =
    copy(header = HeaderState.live(p.dep), extras = extras.copy(protocol = p.extras))

  def withBoot(b: BootstrapState): FlatNodeState = copy(extras = extras.copy(boot = b))

  def withByron(b: ByronState): FlatNodeState = copy(extras = extras.copy(byron = b))

  /** The era history as this state knows it: a chain whose Byron span is derived learns the first
    * Shelley epoch when it enters Shelley.
    */
  def geometry(base: EpochGeometry): EpochGeometry =
    byron.firstShelleyEpoch.fold(base)(base.resolved)

object FlatNodeState:
  def apply(
      protocol: ProtocolState,
      ledger: LedgerState,
      boot: BootstrapState,
      byron: ByronState = ByronState.initial
  ): FlatNodeState =
    FlatNodeState(
      HeaderState.live(protocol.dep),
      ledger,
      ConsensusExtras(boot, byron, protocol.extras)
    )
