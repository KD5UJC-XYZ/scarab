// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.{BlockRef, ByronState, EpochStats, FollowState}
import xyz.kd5ujc.scarab.consensus.ConsensusExtras as LiveConsensusExtras
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, ProtocolExtras}
import xyz.kd5ujc.scarab.consensus.state.MirrorExtras as LiveMirrorExtras
import xyz.kd5ujc.scarab.ledger.{GenDelegPair, Nonce, ParamUpdate, ProtocolParams, Utxo}
import xyz.kd5ujc.scarab.ledger.state.{Gap, NodeExtras}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.SnapshotSidecar as LiveSidecar

/** The sidecar of the snapshot versions before [[xyz.kd5ujc.scarab.ledger.FormatVersion.V11]],
  * frozen: the node's consensus bookkeeping held a bootstrap (ledger-lite) follow state, whose flag
  * no writer since the era-indexed rules (V5) sets and whose lite ledger nothing reads (#988). The
  * types below carry the names and the fields of the live types those versions were written with,
  * so the shapes derived from them, and with them the schema fingerprints of the frozen sidecar
  * schemas ([[SnapshotSidecarV7]], [[SnapshotSidecarV8]], [[SnapshotSidecarV10]]), are those the
  * files hold (the `CodecSuite` goldens guard them). The other types are shared with the current
  * schema, unchanged at V11.
  *
  * The migrations between the previous versions carry this sidecar whole, so a file they write
  * holds what its source held; only the conversion to the current types ([[live]]) drops the
  * bootstrap flag and the lite ledger and keeps the era and the epoch verdicts.
  */
private[storage] object FrozenSidecar:
  /** `SnapshotSidecar` as the previous versions hold it. */
  final case class SnapshotSidecar(
      anchor: Option[BlockRef],
      observed: Map[Era, BigInt],
      gaps: Set[Gap],
      chain: Chain
  )

  enum Chain:
    case InByron(pending: ChainDepState, extras: ConsensusExtras)
    case InShelley(
        utxo: Utxo,
        params: ProtocolParams,
        prevParams: Option[ProtocolParams],
        extras: MirrorExtras.InShelley
    )

  /** The Shelley-based case of the mirror's bookkeeping, under its name. */
  object MirrorExtras:
    final case class InShelley(ledger: NodeExtras, consensus: ConsensusExtras)

  final case class ConsensusExtras(
      boot: BootstrapState,
      byron: ByronState,
      protocol: ProtocolExtras
  )

  final case class BootstrapState(
      active: Boolean,
      era: Option[Era],
      lite: LiteLedger,
      stats: Option[EpochStats],
      completed: Option[EpochStats]
  )

  final case class LiteParams(
      protocolVersion: (BigInt, BigInt),
      d: Rational,
      extraEntropy: Nonce,
      maxBlockBodySize: BigInt,
      maxTxSize: BigInt,
      maxBlockHeaderSize: BigInt
  )

  final case class PoolEntry(vrfKeys: Set[Bytes], retireEpoch: Option[BigInt])

  final case class FutureGenDeleg(slot: BigInt, genesisKey: Bytes, pair: GenDelegPair)

  final case class LiteLedger(
      epoch: BigInt,
      params: LiteParams,
      genDelegs: Map[Bytes, GenDelegPair],
      futureGenDelegs: Vector[FutureGenDeleg],
      curProposals: Map[Bytes, ParamUpdate],
      futureProposals: Map[Bytes, ParamUpdate],
      pools: Map[Bytes, PoolEntry],
      quorum: Int
  )

  /** The sidecar in the current types: the bootstrap flag and the lite ledger dropped, everything
    * else kept, the era and the epoch verdicts of the bootstrap state as the follow state.
    */
  def live(s: SnapshotSidecar): LiveSidecar =
    LiveSidecar(
      s.anchor,
      s.observed,
      s.gaps,
      s.chain match
        case Chain.InByron(pending, extras) => LiveSidecar.Chain.InByron(pending, live(extras))
        case Chain.InShelley(utxo, params, prevParams, extras) =>
          LiveSidecar.Chain.InShelley(
            utxo,
            params,
            prevParams,
            LiveMirrorExtras.InShelley(extras.ledger, live(extras.consensus))
          )
    )

  private def live(c: ConsensusExtras): LiveConsensusExtras =
    LiveConsensusExtras(
      FollowState(c.boot.era, c.boot.stats, c.boot.completed),
      c.byron,
      c.protocol
    )
