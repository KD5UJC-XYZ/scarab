// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import xyz.kd5ujc.scarab.ledger.{
  Committee,
  Constitution,
  EnactState,
  ProtocolParams,
  PulsingSnapshot,
  RatifyState
}
import xyz.kd5ujc.scarab.ledger.ConwayGenesis
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.Era

/** A new-epoch state of some Shelley-based era: the witness of the era packed with the state, so
  * that the era parameter is existential and the hard-fork combinator can hold the state of
  * whichever era the chain is in (`HardForkState` of the consensus layer, one era of its
  * telescope).
  */
enum EraNes:
  case InEra[E <: EraTag](era: ShelleyBasedEra[E], nes: NewEpochState[E])

object EraNes:
  /** The runtime era a witness stands for. */
  def eraOf[E <: EraTag](era: ShelleyBasedEra[E]): Era = era match
    case ShelleyBasedEra.SShelley => Era.Shelley
    case ShelleyBasedEra.SAllegra => Era.Allegra
    case ShelleyBasedEra.SMary    => Era.Mary
    case ShelleyBasedEra.SAlonzo  => Era.Alonzo
    case ShelleyBasedEra.SBabbage => Era.Babbage
    case ShelleyBasedEra.SConway  => Era.Conway

  /** The state with the handle on its UTxO set changed by `f`: the entries a block reads put in,
    * the stamp of the block, the set settled to what the backing store holds.
    */
  def mapUtxo(e: EraNes)(
      f: xyz.kd5ujc.scarab.ledger.Utxo => xyz.kd5ujc.scarab.ledger.Utxo
  ): EraNes =
    e match
      case i: InEra[x] =>
        val ls = i.nes.es.ls
        EraNes.InEra(
          i.era,
          i.nes.copy(es =
            i.nes.es
              .copy(ls = ls.copy[x](utxo = ls.utxo.copy(utxo = UtxoTables(f(ls.utxo.utxo.utxo)))))
          )
        )

  extension (e: EraNes)
    def epoch: EpochNo = e match
      case i: InEra[e] => i.nes.epoch

    def toEra: Era = e match
      case i: InEra[e] => EraNes.eraOf(i.era)

/** The translation of the state of an era into the next (`TranslateEra era NewEpochState`:
  * `Allegra/Translation.hs:58`, `Mary/Translation.hs:50`, `Alonzo/Translation.hs:51`,
  * `Babbage/Translation.hs:48`, `Conway/Translation.hs:69`), as the hard-fork combinator applies it
  * when the chain enters the era: on the state that is about to tick into the new epoch, before the
  * new era's NEWEPOCH rule (`extendToSlot`, `HardFork/Combinator/State.hs:219`).
  *
  * The parameters of every era are the node's one flat [[ProtocolParams]], so what a translation
  * does to the parameters (`upgradePParams`) is what changes in the flat record: the fields the new
  * era introduces take their genesis values and those it drops are zeroed, as the era's view of the
  * parameters has them (`LedgerMirror`). The contexts the translations need are in [[Globals]]
  * (`TranslationContext`: the Alonzo and Conway genesis values).
  */
object Translate:

  /** The next era's state, or none in the last era. */
  def next(globals: Globals, e: EraNes): Either[TickFailure, Option[EraNes]] = e match
    case i: EraNes.InEra[e] =>
      i.era match
        case ShelleyBasedEra.SShelley =>
          shelleyToAllegra(i.nes).map(n => Some(EraNes.InEra(ShelleyBasedEra.SAllegra, n)))
        case ShelleyBasedEra.SAllegra =>
          Right(Some(EraNes.InEra(ShelleyBasedEra.SMary, allegraToMary(i.nes))))
        case ShelleyBasedEra.SMary =>
          Right(Some(EraNes.InEra(ShelleyBasedEra.SAlonzo, maryToAlonzo(globals, i.nes))))
        case ShelleyBasedEra.SAlonzo =>
          Right(Some(EraNes.InEra(ShelleyBasedEra.SBabbage, alonzoToBabbage(i.nes))))
        case ShelleyBasedEra.SBabbage =>
          babbageToConway(globals, i.nes).map(n => Some(EraNes.InEra(ShelleyBasedEra.SConway, n)))
        case ShelleyBasedEra.SConway => Right(None)

  // ---- the parameters

  private def params[E <: EraTag](
      g: GovState[E]
  )(f: ProtocolParams => ProtocolParams)(using ShelleyBasedEra[E]): GovState[E] =
    EraLedger.mapParams(g)(f)

  /** The state of one pre-Conway era as the next one's: the records are the same (the families
    * select Shelley's for each), and the governance state's parameters are upgraded.
    */
  private def reindex[A <: EraTag, B <: EraTag](nes: NewEpochState[A], avvm: StashedAVVM[B])(
      f: ProtocolParams => ProtocolParams
  )(using
      CertState[A] =:= CertState[B],
      GovState[A] =:= GovState[B],
      InstantStake[A] =:= InstantStake[B],
      ShelleyBasedEra[B]
  ): NewEpochState[B] =
    val certs = summon[CertState[A] =:= CertState[B]]
    val govs = summon[GovState[A] =:= GovState[B]]
    val stakes = summon[InstantStake[A] =:= InstantStake[B]]
    val ls = nes.es.ls
    val utxo = ls.utxo
    NewEpochState[B](
      nes.epoch,
      nes.bprev,
      nes.bcur,
      EpochState[B](
        nes.es.chainAccount,
        LedgerState[B](
          certs(ls.cert),
          UTxOState[B](
            utxo.utxo,
            utxo.deposited,
            utxo.fees,
            params[B](govs(utxo.gov))(f),
            stakes(utxo.instantStake),
            utxo.donation
          )
        ),
        nes.es.snapshots,
        nes.es.nonMyopic
      ),
      nes.ru,
      nes.pd,
      avvm
    )

  // ---- Shelley to Allegra: the AVVM balances go back to the reserves

  /** `TranslateEra AllegraEra NewEpochState` with `returnRedeemAddrsToReserves` (`Allegra/
    * Translation.hs:58`, `Shelley/LedgerState/NewEpochState.hs:126`): the Byron redeem balances
    * stashed when the chain entered Shelley are removed from the UTxO and returned to the reserves.
    */
  def shelleyToAllegra(nes: NewEpochState[Shelley]): Either[TickFailure, NewEpochState[Allegra]] =
    val stash = nes.avvm.utxo
    val returned = stash.coin
    Coin
      .fromBigInt(nes.es.chainAccount.reserves.value + returned)
      .left
      .map(TickFailure.Vocab(_))
      .map { reserves =>
        val translated = reindex[Shelley, Allegra](nes, ())(identity)
        translated.copy(es =
          translated.es.copy(
            chainAccount = translated.es.chainAccount.copy(reserves = reserves),
            ls = translated.es.ls.copy[Allegra](utxo =
              translated.es.ls.utxo
                .copy(utxo = UtxoTables(translated.es.ls.utxo.utxo.utxo.removeKnown(stash.toMap)))
            )
          )
        )
      }

  // ---- Allegra to Mary: the same

  def allegraToMary(nes: NewEpochState[Allegra]): NewEpochState[Mary] =
    reindex[Allegra, Mary](nes, ())(identity)

  // ---- Mary to Alonzo: the parameters Alonzo introduces take their genesis values

  /** `TranslateEra AlonzoEra PParams` (`Alonzo/Translation.hs:64`, `upgradePParams`): the Alonzo
    * genesis supplies the cost per UTxO word; the minimum lovelace per output is gone.
    */
  def maryToAlonzo(globals: Globals, nes: NewEpochState[Mary]): NewEpochState[Alonzo] =
    reindex[Mary, Alonzo](nes, ())(
      _.copy(coinsPerUTxOWord = globals.ledger.alonzoCoinsPerUTxOWord, minUTxOValue = 0)
    )

  // ---- Alonzo to Babbage: the cost per byte

  /** `upgradePParams ()` (`Babbage/Translation.hs:84`, `Babbage/PParams.hs:321-345`): the cost per
    * UTxO byte is the per word cost over the eight bytes of a word, rounded down; the per word cost
    * is gone.
    */
  def alonzoToBabbage(nes: NewEpochState[Alonzo]): NewEpochState[Babbage] =
    reindex[Alonzo, Babbage](nes, ())(p =>
      p.copy(coinsPerUTxOByte = p.coinsPerUTxOWord / 8, coinsPerUTxOWord = 0, minUTxOValue = 0)
    )

  // ---- Babbage to Conway

  /** `upgradePParams` of the Conway genesis (`Conway/Translation.hs:109`): the parameters of the
    * governance the era introduces.
    */
  private def conwayParams(globals: Globals)(p: ProtocolParams): ProtocolParams =
    globals.ledger.conway.fold(p)(c =>
      p.copy(
        drepDeposit = c.drepDeposit,
        drepActivity = c.drepActivity,
        govActionDeposit = c.govActionDeposit,
        govActionLifetime = c.govActionLifetime,
        minFeeRefScriptCostPerByte = c.minFeeRefScriptCostPerByte,
        poolVotingThresholds = c.poolVotingThresholds,
        drepVotingThresholds = c.drepVotingThresholds,
        committeeMinSize = c.committeeMinSize,
        committeeMaxTermLength = c.committeeMaxTermLength,
        costModels =
          if c.plutusV3CostModel.isEmpty then p.costModels
          else p.costModels.updated(2, c.plutusV3CostModel)
      )
    )

  /** `TranslateEra ConwayEra NewEpochState` (`Conway/Translation.hs:69,131,156,165`): the accounts
    * lose their pointers and have no DRep delegation, the pointer stake goes, the governance state
    * is new (the committee and the constitution of the genesis, no proposals) with the pulser
    * completed over the enact state of that state, and the voting state is empty.
    */
  def babbageToConway(
      globals: Globals,
      nes: NewEpochState[Babbage]
  ): Either[TickFailure, NewEpochState[Conway]] =
    val ls = nes.es.ls
    val gov = ls.utxo.gov
    val cur = conwayParams(globals)(gov.curPParams)
    val prev = conwayParams(globals)(gov.prevPParams)
    val future = FuturePParams.map(gov.futurePParams)(conwayParams(globals))
    val (committee, constitution) = globals.ledger.conway.fold(
      (Option.empty[Committee], Constitution(ConwayGenesis.noAnchor, None))
    )(c => (c.committee, c.constitution))
    val enact = EnactState(
      committee,
      constitution,
      cur,
      prev,
      BigInt(0),
      Map.empty,
      xyz.kd5ujc.scarab.ledger.PrevIds()
    )
    val conwayGov = ConwayGovState(
      xyz.kd5ujc.scarab.ledger.Proposals.empty,
      committee,
      constitution,
      cur,
      prev,
      future,
      DRComplete(
        PulsingSnapshot.empty,
        RatifyState(enact, Vector.empty, Set.empty, delayed = false)
      )
    )
    val accounts = ConwayAccounts(
      Sorted.map(
        ls.cert.dState.accounts.states.iterator.map((c, a) =>
          c -> ConwayAccountState(a.balance, a.deposit, a.pool, None)
        )
      )
    )
    val cert = ConwayCertState(
      VState(Sorted.empty, CommitteeState(Sorted.empty), EpochNo.fromUnsigned(0L)),
      ls.cert.pState,
      DState(
        accounts,
        ls.cert.dState.futureGenDelegs,
        ls.cert.dState.genDelegs,
        ls.cert.dState.iRewards
      )
    )
    Right(
      NewEpochState[Conway](
        nes.epoch,
        nes.bprev,
        nes.bcur,
        EpochState[Conway](
          nes.es.chainAccount,
          LedgerState[Conway](
            cert,
            UTxOState[Conway](
              ls.utxo.utxo,
              ls.utxo.deposited,
              ls.utxo.fees,
              conwayGov,
              ConwayInstantStake(ls.utxo.instantStake.credentialStake),
              ls.utxo.donation
            )
          ),
          nes.es.snapshots,
          nes.es.nonMyopic
        ),
        nes.ru,
        nes.pd,
        ()
      )
    )
