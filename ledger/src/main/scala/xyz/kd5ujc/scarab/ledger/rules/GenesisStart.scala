// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Show
import cats.derived.*
import scala.annotation.tailrec
import cats.syntax.all.*
import io.circe.Json
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem, GenesisState, Ptr}
import xyz.kd5ujc.scarab.ledger.byron.ByronOut
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.Era

/** Why the ledger state of a chain whose first era is Shelley-based cannot be made from its
  * genesis. Each case is a refusal of the reference's own construction (cardano-ledger f649f975):
  * the injections throw `InjectionNotAllowedOnMainnet` (`Shelley/Transition.hs:252`), a staking
  * injection to a pool that is not registered is its `delegationInvariantMsg` error
  * (`Shelley/Transition.hs:298`, `Conway/Transition.hs:217`) and an initial fund already in the
  * UTxO set its `mergeUtxoNoOverlap` error (`Shelley/Transition.hs:700-710`).
  */
enum GenesisStartError extends DomainError derives Show:
  /** A value of the genesis does not fit its type in the state (a hash of another length, a coin
    * above 2^64).
    */
  case Vocab(cause: VocabError)

  /** The translation of the state into a later era refused (`TranslateEra`). */
  case Translation(cause: TickFailure)

  /** `InjectionNotAllowedOnMainnet`: a chain on mainnet does not start in a Shelley-based era. */
  case InjectionNotAllowedOnMainnet

  /** `delegationInvariantMsg`: the staking injection delegates to a pool it does not register. */
  case DelegationToUnregisteredPool(staking: KeyHash[Staking], pool: KeyHash[StakePool])

  /** `mergeUtxoNoOverlap`: an initial fund's pseudo input is already in the UTxO set. */
  case InitialFundInUtxo(input: node.TxIn)

  /** The era the chain starts in is not reached by the translations from Shelley. */
  case UnreachableEra(era: Era)

object GenesisStartError:
  private given Show[node.TxIn] = Show.fromToString
  private given Show[Era] = Show.fromToString

/** What the genesis files inject into the state of the era a chain starts in: the
  * `TransitionConfig` data of the reference that is only for test networks (`tcInitialFundsL`,
  * `tcInitialStakingL`, Conway's `tcInitialDRepsL` and `tcDelegsL`). Each source is resolved as
  * `resolveInjectionSource` does (`Shelley/Transition.hs:496-517`: `extraConfig.<name>.data`, or
  * the top-level field, never both), and each is in the order in which the reference folds it: an
  * embedded `ListMap` decodes the JSON object through aeson's key map, so in the order of the key
  * (`Data/ListMap.hs:109-125`, `Shelley/Genesis.hs:883-884`).
  *
  * @param mainnet
  *   the Shelley genesis `networkId` is `Mainnet` (`tcNetworkIDG`)
  * @param initialFunds
  *   `initialFunds`: each address with its pseudo input (`initialFundsPseudoTxIn`) and output
  * @param stakePools
  *   `staking.pools` / `stakePools`
  * @param stakeCredentials
  *   `staking.stake` / `stakeCredentials`: each staking key with the pool it delegates to
  * @param initialDReps
  *   the Conway genesis `initialDReps`, whose JSON never holds delegators (`DRep.hs:213-221`)
  * @param delegs
  *   the Conway genesis `delegs`
  * @param costModels
  *   the Alonzo genesis `extraConfig.costModels`, by language index
  *   ([[node.ProtocolParams.injectedCostModels]])
  */
final case class GenesisInjections(
    mainnet: Boolean,
    initialFunds: Vector[(node.TxIn, node.TxOut)],
    stakePools: Vector[(KeyHash[StakePool], node.PoolParams)],
    stakeCredentials: Vector[(KeyHash[Staking], KeyHash[StakePool])],
    initialDReps: Vector[(Credential[DRepRole], DRepState)],
    delegs: Vector[(Credential[Staking], Delegatee)],
    costModels: Map[Int, Vector[BigInt]]
)

object GenesisInjections:
  private def vocab[A](e: Either[VocabError, A]): Either[ConfigError, A] =
    e.leftMap(v => ConfigError(ConfigProblem.GenesisStart(GenesisStartError.Vocab(v))))

  private def field(context: String, name: String)(e: io.circe.DecodingFailure): ConfigError =
    ConfigError(ConfigProblem.JsonField(context, name, e))

  /** `Delegatee` from its JSON (`Conway/TxCert.hs:398-406`): a `poolId`, a `dRep`, or both. */
  private def delegatee(j: Json): Either[ConfigError, Delegatee] =
    val c = j.hcursor
    for
      poolId <- c
        .downField("poolId")
        .as[Option[String]]
        .leftMap(field("genesis delegation", "poolId"))
      dRep <- c.downField("dRep").as[Option[String]].leftMap(field("genesis delegation", "dRep"))
      pool <- poolId.traverse(h =>
        GenesisState.hex28(h).flatMap(b => vocab(KeyHash.fromBytes[StakePool](b)))
      )
      drep <- dRep.traverse(d => GenesisState.drep(d).flatMap(n => vocab(Convert.drep(n))))
      result <- (pool, drep) match
        case (Some(p), None)    => Right(Delegatee.Stake(p))
        case (None, Some(d))    => Right(Delegatee.Vote(d))
        case (Some(p), Some(d)) => Right(Delegatee.StakeVote(p, d))
        case (None, None) =>
          Left(ConfigError(ConfigProblem.MissingField("genesis delegation", "dRep")))
    yield result

  /** `DRepState` from its JSON (`DRep.hs:213-221`): the expiry, the anchor and the deposit, and no
    * delegators.
    */
  private def drepState(j: Json): Either[ConfigError, DRepState] =
    val c = j.hcursor
    for
      expiry <- GenesisState.get[BigInt](c, "expiry").flatMap(e => vocab(EpochNo.fromBigInt(e)))
      deposit <- GenesisState
        .get[BigInt](c, "deposit")
        .flatMap(d => vocab(CompactCoin.fromBigInt(d)))
      anchor <- c.downField("anchor").focus.filterNot(_.isNull).traverse { a =>
        (
          GenesisState.get[String](a.hcursor, "url"),
          GenesisState
            .get[String](a.hcursor, "dataHash")
            .flatMap(h => Bytes.fromHex(h).leftMap(e => ConfigError(ConfigProblem.Hex(e))))
        ).mapN(node.Anchor(_, _))
      }
    yield DRepState(expiry, anchor, deposit, Sorted.emptySet[Credential[Staking]])

  /** The injections of the genesis files: the Shelley genesis `initialFunds` and staking
    * (`Shelley/Genesis.hs:215-219`, `sgInitialFunds`, `sgStaking`) and, when `conway` (the chain
    * starts in Conway, whose `TransitionConfig` holds them), the Conway genesis `initialDReps` and
    * `delegs` (`Conway/Transition.hs:157-170`).
    */
  def read(files: GenesisState.Files, conway: Boolean): Either[ConfigError, GenesisInjections] =
    val s = files.shelley.hcursor
    val extra = s.downField("extraConfig")
    val c = files.conway.hcursor
    val conwayExtra = c.downField("extraConfig")
    for
      network <- s.downField("networkId").as[Option[String]].leftMap(field("genesis", "networkId"))
      funds <- GenesisState
        .source[BigInt](s.downField("initialFunds"), extra, "initialFunds")
        .flatMap(_.traverse((hex, coin) => GenesisState.output(hex, coin)))
      pools <- GenesisState
        .source[Json](s.downField("staking").downField("pools"), extra, "stakePools")
        .flatMap(
          _.traverse((id, j) =>
            GenesisState
              .pool(id, j)
              .flatMap((op, p) => vocab(KeyHash.fromBytes[StakePool](op)).map(_ -> p))
          )
        )
      stake <- GenesisState
        .source[String](s.downField("staking").downField("stake"), extra, "stakeCredentials")
        .flatMap(
          _.traverse((k, p) =>
            (GenesisState.hex28(k), GenesisState.hex28(p)).tupled.flatMap((kb, pb) =>
              vocab((KeyHash.fromBytes[Staking](kb), KeyHash.fromBytes[StakePool](pb)).tupled)
            )
          )
        )
      dreps <-
        if !conway then Right(Vector.empty)
        else
          GenesisState
            .source[Json](c.downField("initialDReps"), conwayExtra, "initialDReps")
            .flatMap(
              _.traverse((k, j) =>
                (
                  GenesisState.credential(k).flatMap(n => vocab(Convert.credential[DRepRole](n))),
                  drepState(j)
                ).tupled
              )
            )
      delegs <-
        if !conway then Right(Vector.empty)
        else
          GenesisState
            .source[Json](c.downField("delegs"), conwayExtra, "delegs")
            .flatMap(
              _.traverse((k, j) =>
                (
                  GenesisState.credential(k).flatMap(n => vocab(Convert.credential[Staking](n))),
                  delegatee(j)
                ).tupled
              )
            )
      costModels <- node.ProtocolParams.injectedCostModels(files.alonzo)
    yield GenesisInjections(
      network.contains("Mainnet"),
      funds,
      pools,
      stake,
      dreps,
      delegs,
      costModels
    )

/** The ledger state of a chain whose first era is Shelley-based, made from the genesis as the
  * reference makes it (ouroboros-consensus 82ecba32, `mkInitExtLedgerStateCardano`,
  * `Cardano/Node.hs:933-986`):
  *
  *   1. the Byron ledger of the genesis (`initExtLedgerStateByron`: the Byron genesis UTxO, the
  *      AVVM balances at their redeem addresses and the non-AVVM ones);
  *   1. the hard forks scheduled at the first slot, executed at once (`injectInitialExtLedgerState`
  *      and its `extendToSlot (SlotNo 0)`, `HardFork/Combinator/Embed/Nary.hs:272-306`): Byron to
  *      Shelley (`translateLedgerStateByronToShelleyWrapper`, `CanHardFork.hs:336-366`, here
  *      [[Handover.toShelley]]) and each later era's translation ([[Translate.next]]) up to the era
  *      the chain starts in;
  *   1. the injection of that era (`perEraInjections`, `L.injectIntoTestState` of the era,
  *      [[inject]]).
  *
  * A chain that starts in Byron has no injection (the injection of the Byron era is the identity),
  * and its Shelley state is made when it crosses into Shelley ([[Handover]]).
  */
object GenesisStart:
  private val certIxRange = BigInt(65536)

  /** The new-epoch state of `era` at the start of the chain.
    *
    * @param context
    *   what the Shelley genesis gives the Byron to Shelley translation
    *   (`toFromByronTranslationContext`)
    * @param byronUtxo
    *   the UTxO of the Byron genesis
    */
  def ledger(
      globals: Globals,
      context: FromByron,
      byronUtxo: Map[node.TxIn, ByronOut],
      era: Era,
      injections: GenesisInjections
  ): Either[GenesisStartError, EraNes] =
    for
      epoch <- EpochNo.fromBigInt(globals.ledger.firstEpoch).leftMap(GenesisStartError.Vocab(_))
      shelley <- Handover
        .toShelley(context, epoch, byronUtxo)
        .leftMap(GenesisStartError.Translation(_))
      translated <- translateTo(globals, era, EraNes.InEra(ShelleyBasedEra.SShelley, shelley))
      injected <- translated match
        case i: EraNes.InEra[e] =>
          inject[e](injections, i.nes)(using i.era).map(EraNes.InEra(i.era, _))
    yield injected

  /** The translations from the state `from` up to `era` (each `translateLedgerStateWith`). */
  def translateTo(globals: Globals, era: Era, from: EraNes): Either[GenesisStartError, EraNes] =
    @tailrec def go(e: EraNes): Either[GenesisStartError, EraNes] =
      if e.toEra == era then Right(e)
      else
        Translate.next(globals, e) match
          case Left(failure)     => Left(GenesisStartError.Translation(failure))
          case Right(None)       => Left(GenesisStartError.UnreachableEra(era))
          case Right(Some(next)) => go(next)
    go(from)

  /** `injectIntoTestState` of the era (`Shelley/Transition.hs:325`, `Allegra/Transition.hs:25`,
    * `Mary/Transition.hs:26`, `Alonzo/Transition.hs:36-37`, `Babbage/Transition.hs:26-30`,
    * `Conway/Transition.hs:130-132`): `shelleyRegisterInitialFundsThenStaking`
    * (`Shelley/Transition.hs:306-314`) before Conway and `conwayRegisterInitialFundsThenStaking`
    * (`Conway/Transition.hs:157-170`) in it, which also injects the DReps and the vote delegations.
    * From Alonzo the current parameters' cost models are first overridden by the Alonzo genesis
    * ones ([[alonzoInjectCostModels]]).
    */
  def inject[E <: EraTag](injections: GenesisInjections, nes: NewEpochState[E])(using
      era: ShelleyBasedEra[E]
  ): Either[GenesisStartError, NewEpochState[E]] =
    for
      _ <- Either.cond(!injections.mainnet, (), GenesisStartError.InjectionNotAllowedOnMainnet)
      funded <- registerInitialFunds(
        injections.initialFunds,
        alonzoInjectCostModels(injections, nes)
      )
      pooled <- injectStakePools(injections.stakePools, funded)
      staked <- (era match
        case ShelleyBasedEra.SConway =>
          conwayStakeCredentials(injections.stakeCredentials, pooled)
            .map(injectDRepsThenDelegs(injections.initialDReps, injections.delegs, _))
        case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
            ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
          shelleyStakeCredentials[E](injections.stakeCredentials, pooled)
      ): Either[GenesisStartError, NewEpochState[E]]
      reset <- resetStakeDistribution(staked).leftMap(GenesisStartError.Vocab(_))
    yield reset

  /** `alonzoInjectCostModels` (`Alonzo/Transition.hs:47-66`, applied by the Alonzo, Babbage and
    * Conway injections): the injected cost models override the current parameters' models of the
    * same language (`updateCostModels`); the previous parameters keep the translated ones. Before
    * Alonzo there is nothing to override.
    */
  def alonzoInjectCostModels[E <: EraTag](injections: GenesisInjections, nes: NewEpochState[E])(
      using era: ShelleyBasedEra[E]
  ): NewEpochState[E] =
    val alonzoOnwards = era match
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary  => false
      case ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway => true
    if !alonzoOnwards || injections.costModels.isEmpty then nes
    else
      val ls = nes.es.ls
      val gov = ls.utxo.gov
      val cur = EraLedger.curPParams(gov)
      val next = EraLedger.withPParams(
        gov,
        cur.copy(costModels = cur.costModels ++ injections.costModels),
        EraLedger.prevPParams(gov),
        EraLedger.futurePParams(gov)
      )
      withLedger(nes, ls.copy[E](utxo = ls.utxo.copy[E](gov = next)))

  private def withLedger[E <: EraTag](nes: NewEpochState[E], ls: LedgerState[E]): NewEpochState[E] =
    nes.copy(es = nes.es.copy(ls = ls))

  /** `registerInitialFunds` (`Shelley/Transition.hs:634-710`): the funds are added to the UTxO set,
    * the reserves give up their total, and the instant stake is made again over the whole set.
    */
  def registerInitialFunds[E <: EraTag](
      funds: Vector[(node.TxIn, node.TxOut)],
      nes: NewEpochState[E]
  )(using era: ShelleyBasedEra[E]): Either[GenesisStartError, NewEpochState[E]] =
    val ls = nes.es.ls
    val utxo = ls.utxo.utxo.utxo
    val total = funds.foldLeft(BigInt(0))(_ + _._2.value.coin)
    for
      _ <- funds
        .collectFirst { case (in, _) if utxo.contains(in) => in }
        .toLeft(())
        .leftMap(GenesisStartError.InitialFundInUtxo(_))
      next = utxo ++ funds
      reserves <- Coin
        .fromBigInt(nes.es.chainAccount.reserves.value - total)
        .leftMap(GenesisStartError.Vocab(_))
      outputs = next.valuesIterator.toVector
      stake <- ((era match
        case ShelleyBasedEra.SConway =>
          InstantStakeOps.update(Empty.instantStake[Conway], outputs, Nil)
        case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
            ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
          ShelleyInstantStakeOps.update(Empty.instantStake[E], outputs, Nil)
      ): Either[VocabError, InstantStake[E]]).leftMap(GenesisStartError.Vocab(_))
    yield
      val utxoState = ls.utxo.copy[E](utxo = UtxoTables(next), instantStake = stake)
      val updated = withLedger(nes, ls.copy[E](utxo = utxoState))
      updated.copy(es =
        updated.es.copy(chainAccount = updated.es.chainAccount.copy(reserves = reserves))
      )

  /** `injectStakePools` (`Shelley/Transition.hs:526-543`): the pools of the genesis, each with the
    * pool deposit of the parameters and no delegator (`mkStakePoolState deposit mempty`), in place
    * of any the state had.
    */
  def injectStakePools[E <: EraTag](
      pools: Vector[(KeyHash[StakePool], node.PoolParams)],
      nes: NewEpochState[E]
  )(using ShelleyBasedEra[E]): Either[GenesisStartError, NewEpochState[E]] =
    val ls = nes.es.ls
    val params = EraLedger.curPParams(ls.utxo.gov)
    (for
      deposit <- CompactCoin.fromBigInt(params.poolDeposit)
      states <- pools.traverse((id, p) =>
        Convert.poolState(p, deposit, Sorted.emptySet[Credential[Staking]]).map(id -> _)
      )
    yield
      val ps = LedgerState.pState[E].get(ls)
      withLedger(
        nes,
        LedgerState.pState[E].replace(ps.copy(stakePools = Sorted.map(states)))(ls)
      )
    ).leftMap(GenesisStartError.Vocab(_))

  private def keyDeposit[E <: EraTag](nes: NewEpochState[E])(using
      ShelleyBasedEra[E]
  ): Either[GenesisStartError, CompactCoin] =
    CompactCoin
      .fromBigInt(EraLedger.curPParams(nes.es.ls.utxo.gov).keyDeposit)
      .leftMap(GenesisStartError.Vocab(_))

  /** `injectStakeCredentials` of the eras before Conway (`Shelley/Transition.hs:269-303`): each
    * staking key, in turn, is registered with the key deposit and a pointer of its own (slot 0, the
    * transaction and certificate indices of its position) and delegates to its pool, which must be
    * registered.
    */
  def shelleyStakeCredentials[E <: EraTag](
      stake: Vector[(KeyHash[Staking], KeyHash[StakePool])],
      nes: NewEpochState[E]
  )(using era: ShelleyBasedEra[E]): Either[GenesisStartError, NewEpochState[E]] =
    val ls = nes.es.ls
    val pools = LedgerState.pState[E].get(ls).stakePools
    def register(
        cert: ShelleyCertState,
        deposit: CompactCoin
    ): Either[GenesisStartError, ShelleyCertState] =
      stake.zipWithIndex.foldLeft[Either[GenesisStartError, ShelleyCertState]](Right(cert)) {
        case (acc, ((key, pool), index)) =>
          acc.flatMap { cs =>
            val credential = Credential.KeyHashObj[Staking](key)
            val ptr = Ptr(BigInt(0), BigInt(index) / certIxRange, BigInt(index) % certIxRange)
            Either.cond(
              pools.contains(pool),
              ShelleyCertState.pState.modify(
                CertOps.reDelegatePool(_, credential, None, Some(pool))
              )(
                ShelleyCertLens.states.modify(
                  _.updatedWith(credential)(_.map(_.copy(pool = Some(pool))))
                )(ShelleyCertOps.register(cs, credential, ptr, deposit))
              ),
              GenesisStartError.DelegationToUnregisteredPool(key, pool)
            )
          }
      }
    keyDeposit(nes).flatMap(deposit =>
      era match
        case ShelleyBasedEra.SConway => Right(nes)
        case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
            ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
          register(ls.cert, deposit).map(c => withLedger(nes, ls.copy[E](cert = c)))
    )

  /** `injectStakeCredentials` of Conway (`Conway/Transition.hs:200-221`): each staking key is
    * registered with the key deposit and delegates its stake to its pool (`registerConwayAccount
    * ... (Just (DelegStake pool))`), which must be registered.
    */
  def conwayStakeCredentials(
      stake: Vector[(KeyHash[Staking], KeyHash[StakePool])],
      nes: NewEpochState[Conway]
  ): Either[GenesisStartError, NewEpochState[Conway]] =
    val pools = CertLens.pools.get(nes.es.ls.cert)
    keyDeposit(nes).flatMap { deposit =>
      stake
        .foldLeft[Either[GenesisStartError, ConwayCertState]](Right(nes.es.ls.cert)) {
          case (acc, (key, pool)) =>
            acc.flatMap { cs =>
              val credential = Credential.KeyHashObj[Staking](key)
              Either.cond(
                pools.contains(pool),
                CertLens.pools.modify(
                  _.updatedWith(pool)(_.map(StakePoolState.delegators.modify(_ + credential)))
                )(CertOps.register(cs, credential, deposit, Some(Delegatee.Stake(pool)))),
                GenesisStartError.DelegationToUnregisteredPool(key, pool)
              )
            }
        }
        .map(c => withLedger(nes, nes.es.ls.copy[Conway](cert = c)))
    }

  /** `injectDRepsThenDelegs` (`Conway/Transition.hs:95-119`): the DReps of the genesis in place of
    * any the state had, then each delegation as `processDelegation` makes it
    * (`Conway/Rules/Deleg.hs:305-316`): the account's delegatee set where there is an account, and
    * the reverse delegation added to the pool or the DRep either way.
    */
  def injectDRepsThenDelegs(
      dreps: Vector[(Credential[DRepRole], DRepState)],
      delegs: Vector[(Credential[Staking], Delegatee)],
      nes: NewEpochState[Conway]
  ): NewEpochState[Conway] =
    val withDReps = CertLens.dreps.replace(Sorted.map(dreps))(nes.es.ls.cert)
    val delegated = delegs.foldLeft(withDReps) { case (cs, (credential, delegatee)) =>
      CertOps.delegate(
        cs,
        credential,
        CertLens.accounts.get(cs).get(credential),
        delegatee,
        preserveIncorrect = false
      )
    }
    withLedger(nes, nes.es.ls.copy[Conway](cert = delegated))

  /** `resetStakeDistribution` (`Shelley/Transition.hs:594-613`): the mark snapshot is taken from
    * the instant stake and the registrations (`snapShotFromInstantStake`), and the pool
    * distribution of the epoch is that snapshot's (`calculatePoolDistr`), so that the injected
    * pools lead from the first epoch.
    */
  def resetStakeDistribution[E <: EraTag](nes: NewEpochState[E])(using
      ShelleyBasedEra[E]
  ): Either[VocabError, NewEpochState[E]] =
    val ls = nes.es.ls
    EraLedger
      .activeStake(ls)
      .flatMap(StakeDistribution.snapshot(_, LedgerState.pState[E].get(ls).stakePools))
      .map(mark =>
        nes.copy(
          es = nes.es.copy(snapshots = nes.es.snapshots.copy(mark = mark)),
          pd = PoolDistr.of(mark)
        )
      )
