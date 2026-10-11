// SPDX-License-Identifier: Apache-2.0
package lab.node.storage

import lab.network.Era
import lab.node.*
import lab.node.consensus.*
import lab.node.ledger.*

/** The codec of everything a snapshot holds, one codec per type, composed. Case classes and enums
  * are derived from their `Mirror` ([[Codec.auto]]); the instances below exist so each shared type
  * is derived once (it keeps compilation fast) and so the composition reads as the state's shape. A
  * field added to any state type changes the schema fingerprint, so snapshots written before it are
  * rejected (`VersionMismatch`) rather than misread. Instances are lazy, so order is free.
  */
object NodeCodecs:
  // Leaf values shared by many types.
  given credential: Codec[Credential] = Codec.auto
  given address: Codec[Address] = Codec.auto
  given ptr: Codec[Ptr] = Codec.auto
  given rewardAccount: Codec[RewardAccount] = Codec.auto
  given value: Codec[Value] = Codec.auto
  given datum: Codec[Datum] = Codec.auto
  given script: Codec[Script] = Codec.auto
  given txIn: Codec[TxIn] = Codec.auto
  given txOut: Codec[TxOut] = Codec.auto
  given drep: Codec[DRep] = Codec.auto
  given paramUpdate: Codec[ParamUpdate] = Codec.auto
  given poolParams: Codec[PoolParams] = Codec.auto
  given protocolParams: Codec[ProtocolParams] = Codec.auto
  given nonce: Codec[Nonce] = Codec.auto
  given era: Codec[Era] = Codec.auto
  given genDelegPair: Codec[GenDelegPair] = Codec.auto

  // Ledger.
  given account: Codec[Account] = Codec.auto
  given stakeSnapshot: Codec[Snapshot] = Codec.auto
  given rewardUpdate: Codec[RewardUpdate] = Codec.auto
  given ledger: Codec[LedgerState] = Codec.auto

  // Consensus and bootstrap.
  given praos: Codec[PraosState] = Codec.auto
  given lite: Codec[LiteLedger] = Codec.auto
  given bootstrap: Codec[BootstrapState] = Codec.auto
  given byron: Codec[ByronState] = Codec.auto

  // Roots.
  given blockRef: Codec[BlockRef] = Codec.auto
  given nodeState: Codec[NodeState] = Codec.auto
  given nodeSnapshot: Codec[StateSnapshot[NodeState]] = Codec.auto
