// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, BootstrapState, ByronEpochStats, ByronTip}
import xyz.kd5ujc.scarab.ledger.{LedgerState, Nonce}
import xyz.kd5ujc.scarab.ledger.byron.ByronLedgerState
import xyz.kd5ujc.scarab.ledger.state.NodeExtras

// FROZEN. The node state as the snapshot formats V1 to V3 persisted it, before the chain-dependent
// state moved onto the Haskell-shaped `consensus.state` types (step 2, jump 1, J1.5). The simple
// names, the fields and their order are the schema of those files (the schema fingerprint is made
// of them), so nothing here may be renamed or reordered; the types that did not change are the live
// ones of `ledger` and `consensus`. Do not edit; delete the module at its sunset (design D3).

/** The `StateSnapshot` of the V2 and V3 files: the state at an immutable point, `anchor` the newest
  * block applied (none: genesis).
  */
final case class StateSnapshot[S](anchor: Option[BlockRef], state: S)

/** Praos chain-dependent state of the node of V1 to V3 (the one record of every era from Shelley
  * on).
  */
final case class PraosState(
    epoch: BigInt,
    evolving: Nonce,
    candidate: Nonce,
    epochNonce: Nonce,
    lab: Nonce,
    lastEpochBlock: Nonce,
    counters: Map[Bytes, BigInt],
    previousEpochNonce: Nonce = Nonce.Neutral
)

/** The PBFT signing window of V1 to V3: the last `k` blocks' (slot, genesis key hash) pairs. */
final case class PBftState(signers: Vector[(BigInt, Bytes)])

/** Byron chain-follow state of V1 to V3. */
final case class ByronState(
    tip: Option[ByronTip],
    stats: Option[ByronEpochStats],
    completed: Option[ByronEpochStats],
    ledger: Option[ByronLedgerState] = None,
    pbft: PBftState = PBftState(Vector.empty),
    firstShelleyEpoch: Option[BigInt] = None
)

/** Consensus and ledger state of V1 to V3. */
final case class NodeState(
    praos: PraosState,
    ledger: LedgerState,
    boot: BootstrapState,
    byron: ByronState = ByronState(None, None, None)
)

/** The protocol-dependent part of what the node of V3 kept beside the Haskell chain-dependent
  * state.
  */
enum ProtocolExtras:
  case OfTPraos(epoch: BigInt, previousEpochNonce: Nonce)
  case OfPraos(epoch: BigInt)

/** What a V3 sidecar held of the node that the Haskell state has no place for (the consensus side;
  * the ledger side's is [[NodeExtras]]).
  */
enum ConsensusExtras:
  case InByron(ledger: LedgerState, praos: PraosState, boot: BootstrapState)
  case InShelley(
      ledger: NodeExtras,
      byron: ByronState,
      boot: BootstrapState,
      protocol: ProtocolExtras
  )
