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
  *
  * It is in the layout of the build the node mirrors (`ConsensusMirrorEnv.layout`): the start of
  * Byron is `initBound` in that layout (`EraHistory.originLayout`), and the Leios build's Peras
  * state is the one the reference starts a Byron chain from, no epoch context and no certificate
  * (`initPerasState`, ouroboros-consensus 2416493b `Ledger/Peras.hs:56-71`, kept by
  * `injectInitialExtLedgerState`, `HardFork/Combinator/Embed/Nary.hs:347-357`). Ticking through
  * Byron keeps it ([[GenesisMirror.originExt]]): an epoch of an era with no Peras rounds has no
  * round information (`epochToPerasRoundInfo` needs `ensurePerasEnabled`,
  * `HardFork/History/Qry.hs:276-277,726-755`), so the tick embeds no epoch context
  * (`embedBoundedEpochContext`, `Peras/Context.hs:793-829`), and no Byron block carries a
  * certificate.
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
      anchor.fold(BigInt(0))(_.slot),
      env.layout
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
        ExtLedgerState(ledger, HeaderState(tip, dep), GenesisMirror.originExt(env.layout)),
        MirrorExtras.InByron(pending, consensus.follow, consensus.protocol),
        provenance,
        None
      )
