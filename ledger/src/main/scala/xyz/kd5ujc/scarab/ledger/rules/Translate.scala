// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{
  Committee,
  Constitution,
  EnactState,
  ProtocolParams,
  PulsingSnapshot,
  RatifyState
}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.ConwayGenesis
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScript
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
    case ShelleyBasedEra.SShelley  => Era.Shelley
    case ShelleyBasedEra.SAllegra  => Era.Allegra
    case ShelleyBasedEra.SMary     => Era.Mary
    case ShelleyBasedEra.SAlonzo   => Era.Alonzo
    case ShelleyBasedEra.SBabbage  => Era.Babbage
    case ShelleyBasedEra.SConway   => Era.Conway
    case ShelleyBasedEra.SDijkstra => Era.Dijkstra

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
  * `Babbage/Translation.hs:48`, `Conway/Translation.hs:69`; Dijkstra's at cardano-ledger 9df9f98c,
  * `Dijkstra/Translation.hs:70-80`), as the hard-fork combinator applies it when the chain enters
  * the era: on the state that is about to tick into the new epoch, before the new era's NEWEPOCH
  * rule (`extendToSlot`, `HardFork/Combinator/State.hs:219`).
  *
  * The parameters of every era are the node's one flat [[ProtocolParams]], so what a translation
  * does to the parameters (`upgradePParams`) is what changes in the flat record: the fields the new
  * era introduces take their genesis values and those it drops are zeroed, as the era's view of the
  * parameters has them. The contexts the translations need are in [[Globals]]
  * (`TranslationContext`: the Alonzo, Conway and Dijkstra genesis values).
  */
object Translate:

  /** The next era's state, or none in the last era (Dijkstra). */
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
        case ShelleyBasedEra.SConway =>
          conwayToDijkstra(globals, i.nes).map(n =>
            Some(EraNes.InEra(ShelleyBasedEra.SDijkstra, n))
          )
        case ShelleyBasedEra.SDijkstra => Right(None)

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
      nes.layout,
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
    val reserves = Coin(nes.es.chainAccount.reserves.value + returned)
    Right {
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

  /** `upgradeConwayPParamsHKD` (`Conway/PParams.hs:1143-1144`, cardano-ledger f649f975) compacts
    * the Conway genesis deposits with `compactCoinOrError` (`asCompactCoinHKD`, `:1258-1259`): the
    * genesis holds them as an unbounded `Coin` (`:752-753`), so one outside `Word64` aborts the
    * reference when the chain enters Conway. That is this state leaving its vocabulary
    * ([[TickFailure.Vocab]]), the governance deposit first, as the record has them. It is the only
    * reachable case of a deposit out of range: the Shelley genesis and every parameter update read
    * the others as `Word64` (#573).
    */
  private def conwayDeposits(globals: Globals): Either[TickFailure, Unit] =
    globals.ledger.conway.fold(Right(()))(c =>
      (CompactCoin.fromBigInt(c.govActionDeposit) *> CompactCoin.fromBigInt(
        c.drepDeposit
      )).void.left
        .map(TickFailure.Vocab(_))
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
    conwayDeposits(globals).as(
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
        nes.layout,
        ()
      )
    )

  // ---- Conway to Dijkstra (cardano-ledger 9df9f98c `eras/dijkstra/impl/src/Cardano/Ledger/
  // Dijkstra/Translation.hs`)

  /** `TranslateEra DijkstraEra NewEpochState` (`Translation.hs:70-80`): the state with its records
    * as they are (Dijkstra's families are Conway's records, so the `coerce` s of the accounts
    * (109-111), the voting state (118-119), the committee and the constitution (176-177, 204, 206)
    * and the instant stake (221) change nothing), the stake snapshots unchanged (96-97), and these
    * translated:
    *   - the parameters, current, previous and future, and those of the enact state the pulser
    *     reached, by the Dijkstra genesis
    *     ([[xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraGenesis.upgrade]], 86-94, 172-183, 200-211);
    *   - the pools' VRF key hashes, counted again ([[populateVRFKeyHashes]], 113-116);
    *   - the UTxO, each output by `upgradeTxOut` ([[upgradeUtxo]], 213-227).
    *
    * The proposals, in the governance state (`translateProposals`, 159-160), the pulser's snapshot
    * (162-170) and the enacted list (185-193), are upgraded by `upgradeGovAction`
    * (`Dijkstra/TxBody.hs:1230-1242`), whose only change is to the parameter update of a
    * `ParameterChange`, upgraded with the empty Dijkstra upgrade
    * (`emptyDijkstraUpgradePParamsUpdate`, `PParams.hs:565-591`): its Conway fields kept and the
    * Dijkstra fields absent, which is the node's [[xyz.kd5ujc.scarab.ledger.ConwayParamUpdate]]
    * unchanged. The pulser the node holds is already complete, so `finishDRepPulser` (195-198) is
    * the identity.
    *
    * The context is the Dijkstra genesis (`TranslationContext DijkstraEra`, 51), which a strict
    * configuration that enables `ExperimentalHardForksEnabled` must name; without one the
    * translation is refused ([[TickFailure.NoDijkstraGenesis]]).
    */
  def conwayToDijkstra(
      globals: Globals,
      nes: NewEpochState[Conway]
  ): Either[TickFailure, NewEpochState[Dijkstra]] =
    for
      genesis <- globals.ledger.dijkstra.toRight(TickFailure.NoDijkstraGenesis)
      pools <- populateVRFKeyHashes(nes.es.ls.cert.pState).left.map(TickFailure.Vocab(_))
    yield
      val ls = nes.es.ls
      val gov = ls.utxo.gov
      val upgrade = genesis.upgrade
      val pulser = gov.drepPulsing
      val enact = pulser.ratify.enact
      val dijkstraGov = gov.copy(
        curPParams = upgrade(gov.curPParams),
        prevPParams = upgrade(gov.prevPParams),
        futurePParams = FuturePParams.map(gov.futurePParams)(upgrade),
        drepPulsing = pulser.copy(ratify =
          pulser.ratify.copy(enact =
            enact.copy(params = upgrade(enact.params), prevParams = upgrade(enact.prevParams))
          )
        )
      )
      NewEpochState[Dijkstra](
        nes.epoch,
        nes.bprev,
        nes.bcur,
        EpochState[Dijkstra](
          nes.es.chainAccount,
          LedgerState[Dijkstra](
            ls.cert.copy(pState = pools),
            UTxOState[Dijkstra](
              UtxoTables(upgradeUtxo(ls.utxo.utxo.utxo)),
              ls.utxo.deposited,
              ls.utxo.fees,
              dijkstraGov,
              ls.utxo.instantStake,
              ls.utxo.donation
            )
          ),
          nes.es.snapshots,
          nes.es.nonMyopic
        ),
        nes.ru,
        nes.layout,
        ()
      )

  /** `populateVRFKeyHashes` of cardano-ledger 9df9f98c
    * (`libs/cardano-ledger-core/src/Cardano/Ledger/State/CertState.hs:528-539`), which the
    * translation applies to the pools (`Dijkstra/Translation.hs:113-116`): the count of each VRF
    * key hash recomputed from nothing, one for each registered pool that uses it and one more for
    * each re-registration waiting for the boundary whose key hash differs from its pool's (or whose
    * pool is not registered), saturating at the largest word (`addVRFKeyHashOccurrence`, 501-510).
    * Not Conway's of f649f975 ([[HardFork.populateVRFKeyHashes]]), which adds to the map it has and
    * counts every waiting re-registration.
    */
  def populateVRFKeyHashes(pools: PState): Either[VocabError, PState] =
    def add(
        counts: SortedMap[VRFVerKeyHash, NonZero[Word64]],
        vrf: VRFVerKeyHash
    ): SortedMap[VRFVerKeyHash, NonZero[Word64]] =
      counts.get(vrf) match
        case None => counts.updated(vrf, NonZero.oneWord)
        case Some(n) =>
          NonZero
            .from(Word64.fromUnsigned(n.value.bits + 1))
            .fold(_ => counts, counts.updated(vrf, _))
    val active =
      pools.stakePools.valuesIterator.foldLeft(Sorted.empty[VRFVerKeyHash, NonZero[Word64]])(
        (counts, pool) => add(counts, pool.vrf)
      )
    pools.future.toVector
      .traverse((id, params) => VRFVerKeyHash.fromBytes(params.vrfKeyHash).map(id -> _))
      .map { future =>
        val counts = future.foldLeft(active) { case (counts, (id, vrf)) =>
          if pools.stakePools.get(id).map(_.vrf).contains(vrf) then counts else add(counts, vrf)
        }
        pools.copy(vrfKeyHashes = counts)
      }

  /** `TranslateEra DijkstraEra UTxO` (`Dijkstra/Translation.hs:225-227`), `upgradeTxOut` of every
    * output (`upgradeBabbageTxOut`, `Conway/TxOut.hs:68-81`): the address, the value and the datum
    * kept (`translateDatum` keeps the bytes of an inline datum), and a reference script upgraded
    * (`upgradeScript`, `Dijkstra/Scripts.hs:423-428`): a Plutus script's bytes kept, a native
    * script's encoding made anew ([[DijkstraScript.upgradeTimelock]]), so that its bytes, its size
    * and its hash are those of the new encoding. The consensus layer applies the same
    * `upgradeTxOut` to the outputs of the ledger tables (ouroboros-consensus 2416493b
    * `Cardano/CanHardFork.hs:757-765`).
    *
    * Only the outputs this value decides are upgraded (the changes since the backing store's
    * flushed point and the read set), recorded as changes so that the store's copies are replaced
    * when they are flushed. The reference's default backend upgrades every stored output once, on
    * the first block of the new era (`upgradeTables`, `HardFork/Combinator/Ledger.hs:1250-1287`,
    * from `implDuplicateWithDiffs`, `Storage/LedgerDB/V2/InMemory.hs:131-152`), and any read in the
    * new era before that upgrades the output it reads (`ejectHardForkTxOut`,
    * `Cardano/Ledger.hs:137-162`). That is the backend the Leios testnet build configures
    * (cardano-node b004fe0c `configuration/cardano/testnet-template-config.json:14-15`); its LSM
    * backend upgrades no stored output once the tip is in the new era, and reads the stored bytes
    * as they are (`implDuplicateWithDiffs` and `implRead`,
    * `ouroboros-consensus/src/ouroboros-consensus-lsm/Ouroboros/Consensus/Storage/LedgerDB/V2/LSM.hs`
    * 262-336). The node's store keeps no era and is the immutable tip, so it is not rewritten at
    * the crossing: once the crossing block is validated, the node upgrades the stored outputs that
    * this does not see (`xyz.kd5ujc.scarab.storage.UtxoStore.enteredDijkstra`). An output that
    * [[reencodedInDijkstra]] does not select is its own upgrade.
    */
  def upgradeUtxo(utxo: xyz.kd5ujc.scarab.ledger.Utxo): xyz.kd5ujc.scarab.ledger.Utxo =
    utxo
      .matching(reencodedInDijkstra)
      .toVector
      .foldLeft(utxo) { case (u, (in, out)) => u.updated(in, upgradeTxOut(out)) }

  /** Whether `upgradeTxOut` may change the output: it holds a native reference script. */
  def reencodedInDijkstra(out: xyz.kd5ujc.scarab.ledger.TxOut): Boolean =
    out.scriptRef.exists(_.native.isDefined)

  /** `upgradeTxOut` of one output (see [[upgradeUtxo]]); an output without a native reference
    * script is the same output.
    */
  def upgradeTxOut(out: xyz.kd5ujc.scarab.ledger.TxOut): xyz.kd5ujc.scarab.ledger.TxOut =
    out.scriptRef.flatMap(_.native).fold(out) { script =>
      xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack
        .withNativeReference(out, DijkstraScript.upgradeTimelock(script), script)
    }
