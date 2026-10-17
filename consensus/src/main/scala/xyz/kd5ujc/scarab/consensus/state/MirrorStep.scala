// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{ConsensusExtras, ForkTrigger, HardForkConfig}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, Header, PlutusStats}
import xyz.kd5ujc.scarab.ledger.rules.EraNes
import xyz.kd5ujc.scarab.ledger.state.{
  BlockNo,
  Hash32,
  MirrorError,
  NodeExtras,
  SlotNo,
  VocabError,
  WithOrigin
}
import xyz.kd5ujc.scarab.protocol.Era

/** Where the live mirror's telescopes sit on the era history: the epoch geometry (the resolved one
  * once a chain that begins in Byron has learned its first Shelley epoch), the Byron parameters and
  * the hard-fork configuration. What [[MirrorStep]] needs to extend a telescope into an era.
  */
final case class Placement(
    geometry: EpochGeometry,
    byron: EraHistory.ByronParams,
    forks: HardForkConfig
):
  /** The bound at the start of `epoch`. */
  def bound(epoch: BigInt): Bound = EraHistory.boundAt(geometry, byron, epoch)

/** What a block does to the mirror the node holds as its state: the tip, the new-epoch state, the
  * chain-dependent state and the node's bookkeeping, and, where the new-epoch state is of a later
  * era than the telescope's (the first block of an era, the hard-fork combinator having translated
  * the state in its tick), the telescopes extended into the era.
  *
  * The extension is `Telescope.extend` of the combinator, which `extendToSlot`
  * (`HardFork/Combinator/State.hs:219`) runs when the tick reaches a slot of a later era: the
  * current era ends where the next begins and the next holds the translated state. The ledger state
  * is translated by `translateLedgerState*Wrapper` (`Cardano/CanHardFork.hs`: Byron to Shelley 344,
  * Shelley to Allegra 485, Allegra to Mary 563, Mary to Alonzo 595, Alonzo to Babbage 633, Babbage
  * to Conway 677), each of which runs the era's `TranslateEra NewEpochState`
  * (`Allegra/Translation.hs:58`, `Mary/Translation.hs:50`, `Alonzo/Translation.hs:51`,
  * `Babbage/Translation.hs:48`, `Conway/Translation.hs:70`); here that is the ledger's
  * [[xyz.kd5ujc.scarab.ledger.rules.Translate]] in `Hfc.tick`, so the state given to [[block]] is
  * already of the new era. The chain-dependent state is translated by `translateChainDepState`
  * (`CanHardFork.hs:161-169`: Byron to Shelley 381, `AcrossShelley` for the rest), which is the
  * identity except from TPraos to Praos (`Protocol/Praos.hs:213-229`: the previous epoch nonce is
  * set to the epoch nonce); the header rules do it ([[ProtocolState.in]]) before the state reaches
  * [[block]].
  *
  * The bound the new era begins at is the configured epoch (`Test<Era>HardForkAtEpoch`), else the
  * epoch of the block that is the first of the era (what the node has always recorded as the epoch
  * the era was seen to begin in, `EraHistory.summary`), and never before the era it follows.
  */
object MirrorStep:

  /** The start of `era` when the block at `slot` is the first of it (or of an earlier one the state
    * was translated through at once): the configured epoch of the era, else the epoch of the block,
    * not before `previous`.
    */
  def startOf(placement: Placement, era: Era, slot: BigInt, previous: Bound): Bound =
    val epoch = placement.forks.triggers.get(era) match
      case Some(ForkTrigger.AtEpoch(e)) => e
      // Shelley begins where the Byron span ends (`EraHistory.summary`)
      case _ if era == Era.Shelley => placement.geometry.firstShelleyEpoch
      case _                       => placement.geometry.epochOf(slot)
    placement.bound(epoch.max(previous.epoch))

  /** The telescope with `next` as the state of `era`. In the era the telescope is in, the state is
    * replaced; in a later one, the current era ends at `start` and the eras between (a state
    * translated through several at once) are empty there.
    */
  def into[A: EraIndexed](
      t: Telescope[A],
      era: Era,
      start: Bound,
      next: A
  ): Either[TelescopeError, Telescope[A]] =
    if era == t.era then Telescope.make(t.past, t.current.copy(state = next))
    else if era.index < t.era.index then Left(TelescopeError.WrongEra(era))
    else
      val skipped = Vector.fill(era.index - t.era.index - 1)(Past(start, start))
      Telescope.make(
        (t.past :+ Past(t.current.start, start)) ++ skipped,
        Current(start, next)
      )

  private def vocab[A](e: Either[VocabError, A]) =
    e.leftMap(v => StateMirrorError.Ledger(MirrorError.Vocab(v)))

  private def telescoped[A](e: Either[TelescopeError, A]) = e.leftMap(StateMirrorError.Telescope(_))

  /** The mirror after a block of a Shelley-based era `header`: the new-epoch state `nes` (the state
    * after the block, in the era of the block), the chain-dependent state `protocol`, the node's
    * `consensus` bookkeeping and the phase-2 counters. The mirror before the block may be in an
    * earlier Shelley-based era; a Byron mirror is the hand-over's ([[MirrorStep.handover]]).
    */
  def block(
      m: MirrorState,
      placement: Placement,
      header: Header.Shelley,
      nes: EraNes,
      protocol: ProtocolState,
      consensus: ConsensusExtras,
      plutus: PlutusStats
  ): Either[StateMirrorError, MirrorState] =
    m.extras match
      case _: MirrorExtras.InByron => Left(StateMirrorError.ExtrasMismatch(m.ext.ledgerState.era))
      case MirrorExtras.InShelley(ledgerExtras, _) =>
        for
          shelley <- (nes match
            case n: EraNes.InEra[e] =>
              (
                vocab(SlotNo.fromBigInt(header.slot)),
                vocab(BlockNo.fromBigInt(header.blockNo)),
                vocab(Hash32.fromBytes(header.hash))
              ).tupled.map { (slot, blockNo, hash) =>
                (
                  EraNes.eraOf(n.era),
                  EraLedgerState.inEra[e](
                    ShelleyLedgerState[e](
                      WithOrigin.At(ShelleyTip(slot, blockNo, hash)),
                      n.nes,
                      ShelleyTransition(Word32.fromUnsigned(0)),
                      None
                    )
                  )(using n.era),
                  AnnTip(slot, blockNo, TipInfo.ShelleyBasedTip(n.era, hash))
                )
              }
          )
          (era, ledgerState, tip) = shelley
          past = m.ext.ledgerState.current.start
          start = startOf(placement, era, header.slot, past)
          ledger <- telescoped(into(m.ext.ledgerState, era, start, ledgerState))
          dep <- telescoped(into(m.ext.headerState.chainDep, era, start, protocol.dep))
        yield MirrorState(
          ExtLedgerState(ledger, HeaderState(WithOrigin.At(tip), dep)),
          MirrorExtras.InShelley(
            ledgerExtras.copy(plutus = plutus),
            consensus.copy(protocol = protocol.extras)
          )
        )

  /** The mirror of a chain that has just left Byron, with the first Shelley block applied: the
    * hand-over's state `nes` (of Shelley, at the epoch the chain entered it) and the
    * chain-dependent state Shelley starts from, on the Byron mirror's telescopes (Byron's current
    * era ends where Shelley's begins), the node's bookkeeping being `consensus` (its chain-follow
    * state finished and without a ledger).
    */
  def handover(
      byron: MirrorState,
      placement: Placement,
      header: Header.Shelley,
      nes: EraNes,
      protocol: ProtocolState,
      consensus: ConsensusExtras
  ): Either[StateMirrorError, MirrorState] =
    block(
      byron.copy(extras = MirrorExtras.InShelley(NodeExtras.empty, consensus)),
      placement,
      header,
      nes,
      protocol,
      consensus,
      PlutusStats.zero
    )
