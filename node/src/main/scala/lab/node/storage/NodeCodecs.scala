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
  given utxoChange: Codec[Utxo.Change] = Codec.auto
  given utxo: Codec[Utxo] = Codec.auto
  given drep: Codec[DRep] = Codec.auto
  given paramUpdate: Codec[ParamUpdate] = Codec.auto
  given poolParams: Codec[PoolParams] = Codec.auto
  given protocolParams: Codec[ProtocolParams] = Codec.auto
  given nonce: Codec[Nonce] = Codec.auto
  given era: Codec[Era] = Codec.auto
  given genDelegPair: Codec[GenDelegPair] = Codec.auto

  // Governance (Conway).
  given govActionId: Codec[GovActionId] = Codec.auto
  given voter: Codec[Voter] = Codec.auto
  given vote: Codec[Vote] = Codec.auto
  given govAction: Codec[GovAction] = Codec.auto
  given proposal: Codec[Proposal] = Codec.auto
  given govAnchor: Codec[lab.node.ledger.Anchor] = Codec.auto
  given constitution: Codec[Constitution] = Codec.auto
  given committee: Codec[Committee] = Codec.auto
  given committeeAuth: Codec[CommitteeAuth] = Codec.auto
  given prevIds: Codec[PrevIds] = Codec.auto
  given govActionState: Codec[GovActionState] = Codec.auto
  given proposals: Codec[Proposals] = Codec.auto
  given enactState: Codec[EnactState] = Codec.auto
  given ratifyState: Codec[RatifyState] = Codec.auto
  given pulsingSnapshot: Codec[PulsingSnapshot] = Codec.auto
  given govState: Codec[GovState] = Codec.auto
  given drepState: Codec[DRepState] = Codec.auto

  // Ledger.
  given account: Codec[Account] = Codec.auto
  given tableSpace: Codec[Table.Space] = Codec.auto

  /** A table is persisted as its diff and counters: a snapshot is written settled, so the read set
    * (a cache fetched for one block) is empty and is not part of the format.
    */
  given table[K, V](using k: Codec[K], v: Codec[V]): Codec[Table[K, V]] = new Codec[Table[K, V]]:
    private val space = summon[Codec[Table.Space]]
    private val big = summon[Codec[BigInt]]
    private val diff = summon[Codec[Map[K, Table.Change[V]]]]
    def write(out: Out, t: Table[K, V]): Unit =
      space.write(out, t.space)
      diff.write(out, t.diff)
      out.varint(t.count)
      big.write(out, t.sumA)
      big.write(out, t.sumB)
      out.varint(t.stamp)
      out.varint(t.flushed)
    def read(in: In): Table[K, V] =
      val sp = space.read(in)
      val d = diff.read(in)
      val count = in.varint()
      val a = big.read(in)
      val b = big.read(in)
      val stamp = in.varint()
      val flushed = in.varint()
      Table(sp, d, Map.empty, loaded = false, count, a, b, stamp, flushed)
    def shape: String = s"Table[${k.shape},${v.shape}]{space,diff,count,sumA,sumB,stamp,flushed}"

  given tableChange[V](using v: Codec[V]): Codec[Table.Change[V]] = Codec.auto
  given stakeDistribution: Codec[StakeDistribution] = Codec.auto
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
