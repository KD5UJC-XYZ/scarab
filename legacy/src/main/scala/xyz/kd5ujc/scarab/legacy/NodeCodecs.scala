// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import xyz.kd5ujc.scarab.consensus.{BlockRef, BootstrapState}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.BinaryError
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.byron.ByronVersion

/** FROZEN. The codec of everything a V1 to V3 snapshot holds, one codec per type, composed. Case
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

  // Consensus and bootstrap.
  given praos: Codec[PraosState] = Codec.auto
  given lite: Codec[LiteLedger] = Codec.auto
  given bootstrap: Codec[BootstrapState] = Codec.auto
  // Keep the historical case-class layout and fingerprint as the domain becomes bounded.
  given byronVersion: Codec[ByronVersion] =
    val number = summon[Codec[BigInt]]
    Codec.make[ByronVersion]("ByronVersion(major:BigInt,minor:BigInt,alt:BigInt)")((out, version) =>
      number.write(out, BigInt(version.major))
      number.write(out, BigInt(version.minor))
      number.write(out, BigInt(version.alt))
    )(in =>
      for
        major <- number.read(in)
        minor <- number.read(in)
        alt <- number.read(in)
        version <- ByronVersion.from(major, minor, alt).leftMap {
          case ByronVersion.Error.OutOfRange(field, value) => BinaryError.InvalidByronVersion(field.toString, value)
        }
      yield version
    )
  given byron: Codec[ByronState] = Codec.auto

  // Roots.
  given blockRef: Codec[BlockRef] = Codec.auto
  given nodeState: Codec[NodeState] = Codec.auto
  given nodeSnapshot: Codec[StateSnapshot[NodeState]] = Codec.auto
