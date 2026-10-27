// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{
  BootstrapState,
  ByronState,
  ConsensusExtras,
  ForkTrigger,
  HardForkConfig
}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, Header, PlutusStats, Utxo}
import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, EraNes}
import xyz.kd5ujc.scarab.ledger.state.{
  BlockNo,
  Hash32,
  MirrorError,
  EpochState,
  LedgerState,
  NewEpochState,
  FuturePParams,
  NodeExtras,
  ShelleyBasedEra,
  SlotNo,
  UTxOState,
  UtxoTables,
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

object Placement:
  /** The placement of a chain whose Byron parameters are those of its geometry: the Byron security
    * parameter is the Shelley one on every network.
    */
  def of(geometry: EpochGeometry, forks: HardForkConfig): Placement =
    Placement(
      geometry,
      EraHistory.ByronParams(
        geometry.byron.epochSlots,
        BigInt(geometry.byron.slotLengthMillis),
        geometry.k
      ),
      forks
    )

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
    if era == t.era then Right(t.withState(next))
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
                vocab(Hash32.fromBytes(header.hash)),
                transition(m, placement.geometry, header.slot)
              ).tupled.map { (slot, blockNo, hash, counter) =>
                (
                  EraNes.eraOf(n.era),
                  EraLedgerState.inEra[e](
                    ShelleyLedgerState[e](
                      WithOrigin.At(ShelleyTip(slot, blockNo, hash)),
                      n.nes,
                      counter,
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
          ),
          provenanceAfter(m, placement.geometry, era, header.slot, start.epoch)
        )

  /** Consensus `Shelley/Ledger/Ledger.hs:568-575,713-741` (82ecba3): reset at a new epoch, then
    * increment at/after the voting deadline. `subSlots` saturates at zero. Native `succ` throws on
    * Word32 overflow; this pure boundary reports a typed refusal instead of an uncaught exception.
    * Across-Shelley translation preserves the counter (`Cardano/CanHardFork.hs:704`); Byron enters
    * with zero (:360).
    */
  private[state] def afterVoting(
      geometry: EpochGeometry,
      slot: BigInt,
      previousEpoch: Option[BigInt],
      previous: Word32
  ): Either[StateMirrorError, Word32] =
    val epoch = geometry.epochOf(slot)
    val before = if previousEpoch.contains(epoch) then previous.bits else 0
    val deadline = (geometry.firstSlot(epoch + 1) - 2 * geometry.stabilityWindow.toBigInt).max(0)
    if slot >= deadline && before == -1 then Left(StateMirrorError.TransitionCounterOverflow)
    else Right(Word32.fromUnsigned(if slot >= deadline then before + 1 else before))

  private def transition(
      m: MirrorState,
      geometry: EpochGeometry,
      slot: BigInt
  ): Either[StateMirrorError, ShelleyTransition] =
    val previous = m.ext.ledgerState.state match
      case EraLedgerState.ByronCurrent(_) => None
      case i: EraLedgerState.InShelleyEra[e] =>
        i.state.tip match
          case WithOrigin.Origin => None
          case WithOrigin.At(tip) =>
            Some(geometry.epochOf(tip.slot.toBigInt) -> i.state.transition.afterVoting)
    afterVoting(geometry, slot, previous.map(_._1), previous.fold(Word32.fromUnsigned(0))(_._2))
      .map(ShelleyTransition(_))

  private def provenanceAfter(
      m: MirrorState,
      geometry: EpochGeometry,
      era: Era,
      slot: BigInt,
      entered: BigInt
  ): MirrorProvenance =
    val previousEpoch = nesOf(m).map { case i: EraNes.InEra[e] =>
      i.nes.epoch.toBigInt
    }
    val epoch = geometry.epochOf(slot)
    val next = if previousEpoch.exists(_ < epoch) then m.provenance.epochBoundary else m.provenance
    if era == m.ext.ledgerState.era then next
    else
      Era.values.iterator
        .filter(e => e.index > m.ext.ledgerState.era.index && e.index <= era.index)
        .foldLeft(next)((p, e) => p.entered(e, entered))

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

  /** The mirror after a Byron block: the chain-follow state with the block applied (the PBFT window
    * is in it and in the chain-dependent state of the telescope), the bootstrap state, and the tip,
    * annotated with whether the block is a boundary block.
    */
  def byronBlock(
      m: MirrorState,
      header: Header.Byron,
      byron: ByronState,
      boot: BootstrapState
  ): Either[StateMirrorError, MirrorState] =
    m.extras match
      case _: MirrorExtras.InShelley => Left(StateMirrorError.ExtrasMismatch(m.ext.ledgerState.era))
      case MirrorExtras.InByron(pending, _, protocol) =>
        (
          vocab(SlotNo.fromBigInt(header.slot)),
          vocab(BlockNo.fromBigInt(header.blockNo)),
          vocab(Hash32.fromBytes(header.hash))
        ).tupled.map { (slot, blockNo, hash) =>
          val ebb = if header.isBoundary then IsEBB.EBB else IsEBB.NotEBB
          MirrorState(
            ExtLedgerState(
              m.ext.ledgerState.withState(EraLedgerState.ByronCurrent(byron)),
              HeaderState(
                WithOrigin.At(AnnTip(slot, blockNo, TipInfo.ByronTip(hash, ebb))),
                m.ext.headerState.chainDep.withState(ChainDepState.PBft(byron.pbft))
              )
            ),
            MirrorExtras.InByron(pending, boot, protocol),
            m.provenance
          )
        }

  /** The mirror with the handle on its UTxO set changed by `f`: the entries a block reads put in,
    * or the set settled to what the backing store holds. A Byron mirror has no handle (its set is
    * the Byron ledger's own) and is returned as it is.
    */
  def mapUtxo(m: MirrorState)(f: Utxo => Utxo): MirrorState =
    m.ext.ledgerState.state match
      case EraLedgerState.ByronCurrent(_) => m
      case i: EraLedgerState.InShelleyEra[e] =>
        val table = ShelleyLedgerState
          .nes[e]
          .andThen(NewEpochState.es[e])
          .andThen(EpochState.ls[e])
          .andThen(LedgerState.utxo[e])
          .andThen(UTxOState.utxo[e])
        val next =
          EraLedgerState.InShelleyEra(i.era, table.modify(t => UtxoTables(f(t.utxo)))(i.state))
        m.copy(ext = m.ext.copy(ledgerState = m.ext.ledgerState.withState(next)))

  /** The new-epoch state of a mirror, with the witness of its era; none for a Byron state. */
  def nesOf(m: MirrorState): Option[EraNes] = m.ext.ledgerState.state match
    case i: EraLedgerState.InShelleyEra[e] => Some(EraNes.InEra(i.era, i.state.nes))
    case EraLedgerState.ByronCurrent(_)    => None

  /** The handle on the UTxO set of a mirror; none while the chain is in Byron (the set is the Byron
    * ledger's own, and the Shelley state in the extras holds a placeholder).
    */
  def utxoOf(m: MirrorState): Option[Utxo] = m.ext.ledgerState.state match
    case EraLedgerState.ByronCurrent(_)    => None
    case i: EraLedgerState.InShelleyEra[e] => Some(i.state.nes.es.ls.utxo.utxo.utxo)

  /** The mirror of a snapshot with the node's whole parameter sets put back: the Haskell section
    * has only the fields of the era in its `PParams`, while the node's flat record has every field
    * in every era (the later eras' come from their genesis files at the start, and the translations
    * into those eras rely on them). The parameters in force and the previous ones are the
    * sidecar's; the future ones, which the section holds with the era's fields only, are the
    * sidecar's laid over with what the update changed
    * ([[xyz.kd5ujc.scarab.ledger.ProtocolParams.overlay]]).
    */
  def withParams(
      m: MirrorState,
      params: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      prev: Option[xyz.kd5ujc.scarab.ledger.ProtocolParams]
  ): MirrorState =
    m.ext.ledgerState.state match
      case EraLedgerState.ByronCurrent(_) => m
      case i: EraLedgerState.InShelleyEra[e] =>
        given ShelleyBasedEra[e] = i.era
        val ls = i.state.nes.es.ls
        val gov = ls.utxo.gov
        val read = EraLedger.curPParams(gov)
        val widened = EraLedger.withPParams(
          gov,
          params,
          prev.getOrElse(EraLedger.prevPParams(gov)),
          FuturePParams.map(EraLedger.futurePParams(gov))(
            xyz.kd5ujc.scarab.ledger.ProtocolParams.overlay(params, read, _)
          )
        )
        val nes = i.state.nes
        val state = i.state.copy(nes =
          nes.copy(es = nes.es.copy(ls = ls.copy[e](utxo = ls.utxo.copy(gov = widened))))
        )
        m.copy(ext =
          m.ext.copy(ledgerState =
            m.ext.ledgerState.withState(EraLedgerState.InShelleyEra(i.era, state))
          )
        )
