// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy.v4

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.BinaryError
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.BootstrapState
import xyz.kd5ujc.scarab.consensus.ByronState
import xyz.kd5ujc.scarab.consensus.ConsensusExtras
import xyz.kd5ujc.scarab.consensus.*
import xyz.kd5ujc.scarab.legacy.*
import xyz.kd5ujc.scarab.consensus.state.{
  ChainDepState,
  PBftSigner,
  PBftState,
  PraosState,
  PrtclState,
  ProtocolExtras,
  TPraosLedgerState,
  TPraosState,
  TicknState
}
import xyz.kd5ujc.scarab.ledger.state.{KeyHash, KeyRole, SlotNo, Sorted, WithOrigin, Word64}
import cats.Order
import xyz.kd5ujc.scarab.codec.Bytes
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.GenDelegPair
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.*

/** FROZEN. The codec of everything a snapshot of format 4 holds, one codec per type, composed. Case
  * classes and enums are derived from their `Mirror` ([[Codec.auto]]); the instances below exist so
  * each shared type is derived once (it keeps compilation fast) and so the composition reads as the
  * state's shape. A field added to any state type changes the schema fingerprint, so snapshots
  * written before it are rejected (`VersionMismatch`) rather than misread. Instances are lazy, so
  * order is free.
  */
object NodeCodecs:
  // Leaf values shared by many types.
  given credential: Codec[Credential] = Codec.auto
  given address: Codec[Address] = Codec.auto
  given ptr: Codec[Ptr] = Codec.auto
  // The pointers keep their persisted form (a map); the reverse index is rebuilt on read.
  given ptrMap: Codec[PtrMap] =
    val map = summon[Codec[Map[Ptr, Credential]]]
    Codec.make[PtrMap](map.shape)((o, p) => map.write(o, p.toMap))(i => map.read(i).map(PtrMap(_)))
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
  given govAnchor: Codec[xyz.kd5ujc.scarab.ledger.Anchor] = Codec.auto
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
  given stakeSnapshot: Codec[Snapshot] = Codec.auto
  given rewardUpdate: Codec[RewardUpdate] = Codec.auto
  given ledger: Codec[LedgerState] = Codec.auto

  // The chain-dependent state of the Haskell shape: unsigned words, key hashes, sorted maps.
  given word64: Codec[Word64] =
    Codec.make[Word64]("Word64")((o, w) => o.fixed64(w.bits))(i =>
      i.fixed64().map(Word64.fromUnsigned)
    )

  given slotNo: Codec[SlotNo] =
    Codec.make[SlotNo]("SlotNo")((o, s) => o.fixed64(s.word.bits))(i =>
      i.fixed64().map(SlotNo.fromUnsigned)
    )

  given keyHash[R <: KeyRole]: Codec[KeyHash[R]] =
    Codec.make[KeyHash[R]]("KeyHash")((o, h) => summon[Codec[Bytes]].write(o, h.bytes))(i =>
      summon[Codec[Bytes]]
        .read(i)
        .flatMap(bytes =>
          KeyHash.fromBytes[R](bytes).leftMap(_ => BinaryError.InvalidKeyHashLength(bytes.size, 28))
        )
    )

  /** In ascending key order (that of `Order[K]`), as the map holds it. */
  given sortedMap[K, V](using k: Codec[K], v: Codec[V], order: Order[K]): Codec[SortedMap[K, V]]
  with
    def write(out: Out, a: SortedMap[K, V]): Unit =
      out.varint(a.size.toLong)
      a.foreach { (key, value) =>
        k.write(out, key)
        v.write(out, value)
      }
    def read(in: In): Either[BinaryError, SortedMap[K, V]] =
      Codec.readCounted(in, Sorted.map[K, V](Vector.empty)) {
        for
          key <- k.read(in)
          value <- v.read(in)
        yield key -> value
      }(_ + _)
    def shape: String = s"SortedMap[${k.shape},${v.shape}]"

  given withOrigin[A](using Codec[A]): Codec[WithOrigin[A]] = Codec.auto
  given pbftSigner: Codec[PBftSigner] = Codec.auto
  given pbftState: Codec[PBftState] = Codec.auto
  given prtclState: Codec[PrtclState] = Codec.auto
  given ticknState: Codec[TicknState] = Codec.auto
  given tpraosLedgerState: Codec[TPraosLedgerState] = Codec.auto
  given tpraosState: Codec[TPraosState] = Codec.auto
  given praosState: Codec[PraosState] = Codec.auto
  given chainDepState: Codec[ChainDepState] = Codec.auto
  given protocolExtras: Codec[ProtocolExtras] = Codec.auto

  // Consensus and bootstrap.
  given lite: Codec[LiteLedger] = Codec.auto
  given bootstrap: Codec[BootstrapState] = Codec.auto
  // V4 uses the same three-BigInt Byron version layout as V1-V3.
  given byronVersion: Codec[xyz.kd5ujc.scarab.ledger.byron.ByronVersion] =
    xyz.kd5ujc.scarab.legacy.NodeCodecs.byronVersion
  given byron: Codec[ByronState] = Codec.auto
  given consensusExtras: Codec[ConsensusExtras] = Codec.auto
  given mirrorInShelley: Codec[MirrorExtras.InShelley] = Codec.auto

  // Roots.
  given blockRef: Codec[BlockRef] = Codec.auto
