// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.consensus.ConsensusExtras
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  EraLedgerState,
  MirrorExtras,
  MirrorState
}
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.{Gap, ShelleyBasedEra, SlotNo, WithOrigin}
import xyz.kd5ujc.scarab.legacy
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellLedgerFile
import xyz.kd5ujc.scarab.consensus.BlockRef

/** The content of the snapshot files of formats 1 to 4 for a state of this build, written by the
  * frozen writers of the `legacy` module: what the builds that wrote those formats would have
  * written for the same chain. The ledger state of the Haskell section (V3, V4) is the live one, a
  * format of the reference node; the sidecars and the flat node state (V1, V2) are the frozen
  * types'.
  *
  * What these builds held and the mirror does not (the old rules' bookkeeping, the flat Byron
  * ledger) is taken as empty, which is also what the migration of `SnapshotV5Suite` does: the
  * states of the era-indexed rules have none of it.
  */
object OldSnapshotFiles extends munit.Assertions:

  private def at(anchor: Option[BlockRef]): WithOrigin[SlotNo] =
    anchor.fold[WithOrigin[SlotNo]](WithOrigin.Origin)(a =>
      WithOrigin.At(SlotNo.fromUnsigned(a.slot.toLong))
    )

  /** The flat node state that a state of this build stands for: what the old rules held at it. */
  def flatState(
      env: ConsensusMirrorEnv,
      s: StateSnapshot[MirrorState]
  ): legacy.FlatNodeState =
    val m = s.state
    m.ledger(s.anchor) match
      case Right(Some(InEra.Of(era, read))) =>
        val ledger = read.nes
          .flatMap(nes =>
            legacy.LedgerMirrorBack.flatOf(env.ledger, at(s.anchor), nes, m.plutus)(using era)
          )
          .fold(e => fail(s"flat read-back: $e"), identity)
        legacy.FlatNodeState(m.protocol, ledger, m.consensus.boot, m.consensus.byron)
      case Right(None) =>
        legacy.FlatNodeState(
          m.protocol,
          legacy.FlatTestLedger.state(0, Vector.empty),
          m.consensus.boot,
          m.consensus.byron
        )
      case Left(e) => fail(s"no ledger state: $e")

  /** The content of a version 2 file (the number 1 and another fingerprint for version 1). */
  def v2(env: ConsensusMirrorEnv, s: StateSnapshot[MirrorState]): Array[Byte] =
    val flat = flatState(env, s)
    val upgraded = legacy.Upgrade
      .node(legacy.Downgrade.node(flat), env.forks, s.anchor)
      .fold(e => fail(e.toString), identity)
    legacy.SnapshotV2.encode(legacy.StateSnapshot(s.anchor, legacy.Downgrade.node(upgraded)))(using
      legacy.NodeCodecs.nodeSnapshot
    )

  /** As the build before the rename wrote it: number 1, another fingerprint, the same payload. */
  def v1(env: ConsensusMirrorEnv, s: StateSnapshot[MirrorState]): Array[Byte] =
    val content = v2(env, s)
    java.nio.ByteBuffer.wrap(content, 8, 4).putInt(1)
    java.util.Arrays.fill(content, 12, 20, 0x5a.toByte)
    content

  /** The Haskell section of the state (empty in Byron). */
  private def haskell(s: StateSnapshot[MirrorState]): Array[Byte] =
    s.state.ext.ledgerState.state match
      case _: EraLedgerState.ByronCurrent => Array.emptyByteArray
      case _ =>
        HaskellLedgerFile
          .of(s.state.ext)
          .flatMap(f => HaskellLedgerFile.encode(f).left.map(e => fail(e.toString)))
          .fold(e => fail(e.toString), _.toArray)

  /** The content of a version 3 file: the flat Byron state, or the node's UTxO bookkeeping, its
    * parameters and the V3 extras, beside the Haskell section.
    */
  def v3(env: ConsensusMirrorEnv, s: StateSnapshot[MirrorState]): Array[Byte] =
    val flat = flatState(env, s)
    val old = legacy.Downgrade.node(flat)
    val chain = (s.state.extras, s.state.ext.ledgerState.state) match
      case (_: MirrorExtras.InByron, EraLedgerState.ByronCurrent(_)) =>
        legacy.SnapshotSidecar.Chain.InByron(old)
      case (shelley: MirrorExtras.InShelley, i: EraLedgerState.InShelleyEra[e]) =>
        given ShelleyBasedEra[e] = i.era
        val gov = i.state.nes.es.ls.utxo.gov
        legacy.SnapshotSidecar.Chain.InShelley(
          i.state.nes.es.ls.utxo.utxo.utxo,
          EraLedger.curPParams(gov),
          Some(EraLedger.prevPParams(gov)),
          legacy.ConsensusExtras.InShelley(
            legacy.NodeExtras.empty.copy(plutus = s.state.plutus),
            old.byron,
            shelley.consensus.boot,
            legacy.ProtocolExtras.OfTPraos(
              shelley.consensus.protocol.epoch,
              shelley.consensus.protocol.previousEpochNonce
            )
          )
        )
      case _ => fail("extras of another kind than the ledger state")
    legacy.SnapshotV3.framed(
      legacy.SnapshotSidecar(s.anchor, env.observed, Set(Gap.NonMyopic), chain),
      haskell(s)
    )

  /** The content of a version 4 file: the Haskell section and the sidecar with the old rules'
    * bookkeeping (empty here), and in Byron the flat ledger state.
    */
  def v4(env: ConsensusMirrorEnv, s: StateSnapshot[MirrorState]): Array[Byte] =
    val sidecar = (s.state.extras, s.state.ext.ledgerState.state) match
      case (MirrorExtras.InByron(pending, boot, protocol), EraLedgerState.ByronCurrent(b)) =>
        legacy.v4.SnapshotSidecar(
          s.anchor,
          env.observed,
          Set.empty,
          legacy.v4.SnapshotSidecar.Chain
            .InByron(
              legacy.FlatTestLedger.state(0, Vector.empty),
              pending,
              ConsensusExtras(boot, b, protocol)
            )
        )
      case (shelley: MirrorExtras.InShelley, i: EraLedgerState.InShelleyEra[e]) =>
        given ShelleyBasedEra[e] = i.era
        val gov = i.state.nes.es.ls.utxo.gov
        legacy.v4.SnapshotSidecar(
          s.anchor,
          env.observed,
          Set(Gap.NonMyopic),
          legacy.v4.SnapshotSidecar.Chain.InShelley(
            i.state.nes.es.ls.utxo.utxo.utxo,
            EraLedger.curPParams(gov),
            Some(EraLedger.prevPParams(gov)),
            legacy.v4.MirrorExtras.InShelley(
              legacy.NodeExtras.empty.copy(plutus = s.state.plutus),
              shelley.consensus
            )
          )
        )
      case _ => fail("extras of another kind than the ledger state")
    legacy.v4.SnapshotV4.framed(sidecar, haskell(s))
