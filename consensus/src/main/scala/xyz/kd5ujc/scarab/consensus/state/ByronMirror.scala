// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{BlockRef, ByronState, ConsensusExtras}
import xyz.kd5ujc.scarab.ledger.state.{BlockNo, Hash32, MirrorError, SlotNo, VocabError, WithOrigin}
import xyz.kd5ujc.scarab.protocol.Era

/** The mirror of a chain that is in Byron: the Byron ledger and its chain-follow state are carried
  * whole (the mirror does not model Byron's ledger state), the telescopes are the era history's
  * with Byron current, and the state that Shelley will start from is the one in the extras.
  *
  * It is cheap, so a snapshot of a chain in Byron is restored by building it from the sidecar's
  * parts, and a chain that starts in Byron starts from it.
  */
object ByronMirror:

  private def vocab[A](e: Either[VocabError, A]): Either[StateMirrorError, A] =
    e.leftMap(v => StateMirrorError.Ledger(MirrorError.Vocab(v)))

  /** The Byron mirror at the tip `anchor`: `pending` is the chain-dependent state Shelley starts
    * from, `consensus` the node's bookkeeping with the Byron state (which holds the Byron ledger
    * and the PBFT window).
    */
  def of(
      env: ConsensusMirrorEnv,
      anchor: Option[BlockRef],
      pending: ChainDepState,
      consensus: ConsensusExtras,
      provenance: MirrorProvenance
  ): Either[StateMirrorError, MirrorState] =
    val byron: ByronState = consensus.byron
    val geometry = byron.firstShelleyEpoch.fold(env.ledger.geometry)(env.ledger.geometry.resolved)
    val summaries = EraHistory.summary(
      geometry,
      env.byron,
      env.forks,
      env.observed,
      Era.Byron,
      anchor.fold(BigInt(0))(_.slot)
    )
    for
      current <- summaries.headOption.toRight(StateMirrorError.MissingEra(Era.Byron))
      block <- anchor.traverse(a =>
        (
          vocab(SlotNo.fromBigInt(a.slot)),
          vocab(BlockNo.fromBigInt(a.blockNo)),
          vocab(Hash32.fromBytes(a.hash))
        ).tupled
      )
      ledger <- Telescope
        .make(Vector.empty, Current(current.start, EraLedgerState.ByronCurrent(byron)))
        .leftMap(StateMirrorError.Telescope(_))
      dep <- Telescope
        .make(Vector.empty, Current(current.start, ChainDepState.PBft(byron.pbft)))
        .leftMap(StateMirrorError.Telescope(_))
    yield
      val tip = block.fold[WithOrigin[AnnTip]](WithOrigin.Origin) { (slot, blockNo, hash) =>
        val ebb = if anchor.exists(_.boundary) then IsEBB.EBB else IsEBB.NotEBB
        WithOrigin.At(AnnTip(slot, blockNo, TipInfo.ByronTip(hash, ebb)))
      }
      MirrorState(
        ExtLedgerState(ledger, HeaderState(tip, dep)),
        MirrorExtras.InByron(pending, consensus.boot, consensus.protocol),
        provenance
      )
