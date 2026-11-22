// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Monad, Order}
import cats.data.{Chain, NonEmptyMap, NonEmptySet}
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.crypto.bls.{BlsError, MinSig, ProofOfPossession, VerificationKey}
import xyz.kd5ujc.scarab.ledger.{
  Committee,
  GovActionId,
  GovActionState,
  LedgerDefect,
  LedgerFailure,
  RewardAccount,
  TxEnv
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.Relation.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{
  AccountBalanceInterval,
  DijkstraTxCert,
  PlutusLegacyMode
}
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Globals, Judge, Sts, Test, focus}
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  Accounts,
  BlsKey,
  CertState,
  Coin,
  CompactCoin,
  Credential,
  DState,
  Dijkstra,
  EpochInterval,
  EpochNo,
  EraTag,
  Network,
  NonZero,
  PState,
  PoolParamsBlsKey,
  Sorted,
  StakePoolParams,
  StakePoolState,
  Staking,
  VRFVerKeyHash,
  VocabError,
  Withdrawals,
  Word64,
  addInterval
}
import xyz.kd5ujc.scarab.protocol.StateLayout

// Dijkstra's certificate rules and ENTITIES as the reference writes them (cardano-ledger 9df9f98c,
// `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/{Pool,Cert,Entities}.hs`). Dijkstra's
// CERTS, DELEG and GOVCERT are Conway's transitions at Dijkstra (`Certs.scala`), with Dijkstra's
// failure types and injections (`CertsFailures.scala`, `DijkstraCertsFailures.scala`).

/** Dijkstra's POOL (`Dijkstra/Rules/Pool.hs`, cardano-ledger 9df9f98c). */
object DijkstraPOOL:
  /** `poolTransition` (`Dijkstra/Rules/Pool.hs:187-318`, cardano-ledger 9df9f98c): a registration
    * checks the network of the reward account, the size of the metadata hash, the cost and, under a
    * policy that runs the static checks, the proof of possession of its voting key; a new pool's
    * VRF key must be unused, and a re-registration's must be held by the pool alone, through its
    * active or its future parameters. The rule keeps the count of each VRF key hash's holders as
    * the invariant of `psVRFKeyHashes` says (`:176-186`). It has no protocol version's gate: the
    * Dijkstra era begins at 12. A new pool's voting key takes effect at the current epoch
    * (`mkStakePoolState cEpoch SNothing`), and the rule tells that a pool registered or registered
    * again. Generic in its era; the reference is generic in its rule too, which SUBPOOL reuses.
    */
  def poolTransition[E <: EraTag, F[_]: Monad](
      env: PoolEnv[E],
      poolCert: PoolCert,
      validation: TxEnv.Validation
  )(using
      p: EraPParams[E],
      inject: InjectDijkstraPool[E],
      ask: Ask[F, Globals],
      st: Stateful[F, PState],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      judge: Judge[F, PoolPredFailure[E]],
      events: Tell[F, Chain[PoolRuleEvent[E]]]
  ): F[Unit] =
    val pp = env.pp
    val cEpoch = env.epoch
    def fail(f: DijkstraPoolPredFailure[E]): PoolPredFailure[E] = inject.injectFailure(f)
    def tell(e: PoolEvent[E]): F[Unit] = events.tell(Chain.one(inject.injectEvent(e)))
    poolCert match
      case PoolCert.RegPool(stakePoolParams) =>
        val sppId = stakePoolParams.id
        val sppVrf = stakePoolParams.vrf
        val suppliedNetID = stakePoolParams.accountAddress.networkId
        val sppCost = stakePoolParams.cost
        for
          globals <- ask.ask[Globals]
          actualNetID <- vocab[F, Network](
            Network
              .of(BigInt(globals.networkId))
              .toRight(VocabError.OutOfRange(VocabError.Kind.Network, BigInt(globals.networkId)))
          )
          _ <- Sts.predicate[F, PoolPredFailure[E]](
            actualNetID == suppliedNetID,
            fail(
              DijkstraPoolPredFailure.WrongNetworkPOOL(Mismatch(suppliedNetID, actualNetID), sppId)
            )
          )
          // `hashSize ([] @HASH)`, Blake2b-256's
          _ <- stakePoolParams.metadata.traverse_(pmd =>
            Sts.predicate[F, PoolPredFailure[E]](
              pmd.hash.size <= 32,
              fail(DijkstraPoolPredFailure.PoolMedataHashTooBig(sppId, pmd.hash.size))
            )
          )
          minPoolCost = field[E, CompactCoin](pp)(p.hkdMinPoolCostCompactL[Identity]).toCoin
          _ <- Sts.predicate[F, PoolPredFailure[E]](
            Order[Coin].gteqv(sppCost, minPoolCost),
            fail(DijkstraPoolPredFailure.StakePoolCostTooLowPOOL(Mismatch(sppCost, minPoolCost)))
          )
          // `checkFailOnJustStatic`: skipped unless the policy runs the static checks
          invalidBlsKey <- validation match
            case TxEnv.Validation.All =>
              Sts.lift[F, PoolDefect, Option[BlsKey]](invalid(stakePoolParams))
            case TxEnv.Validation.NonStatic => Monad[F].pure(Option.empty[BlsKey])
          _ <- Sts.failOnJust[F, BlsKey, PoolPredFailure[E]](invalidBlsKey)(blsKey =>
            fail(DijkstraPoolPredFailure.BlsKeyInvalidProofOfPossession(sppId, blsKey))
          )
          ps <- st.get
          next <- ps.stakePools.get(sppId) match
            case None =>
              for
                _ <- Sts.predicate[F, PoolPredFailure[E]](
                  !ps.vrfKeyHashes.contains(sppVrf),
                  fail(DijkstraPoolPredFailure.VRFKeyHashAlreadyRegistered(sppId, sppVrf))
                )
                _ <- tell(PoolEvent.RegisterPool(sppId))
                layout <- Sts.lift[F, LedgerDefect, StateLayout](
                  StateLayout
                    .of(globals.ledger.referenceBuild)
                    .leftMap(LedgerFailure.LayoutNotYet(_))
                )
                _ <- sameLayout[F](stakePoolParams, layout)
                pool <- vocab[F, StakePoolState](
                  Convert.poolState(
                    stakePoolParams,
                    stakePoolParams.blsKey.registered(cEpoch),
                    field[E, CompactCoin](pp)(p.hkdPoolDepositCompactL[Identity]),
                    Sorted.emptySet[Credential[Staking]]
                  )
                )
              yield ps.copy(
                stakePools = ps.stakePools.updated(sppId, pool),
                vrfKeyHashes = addVRFKeyHashOccurrence(sppVrf, ps.vrfKeyHashes)
              )
            case Some(stakePoolState) =>
              val activeVrf = stakePoolState.vrf
              val mbFutureVrf = ps.future.get(sppId).map(_.vrf)
              // the only reference to this VRF key hash, if any, must be the pool's own, held
              // through its active or its future parameters
              val expectedOccurrences =
                Option.when(sppVrf == activeVrf || mbFutureVrf.contains(sppVrf))(BigInt(1))
              // the reference held by the future parameters moves from `mbFutureVrf` to `sppVrf`;
              // one that coincides with the active VRF key hash is not counted separately
              def updateFutureVRFKeyHash(
                  counts: SortedMap[VRFVerKeyHash, NonZero[Word64]]
              ): SortedMap[VRFVerKeyHash, NonZero[Word64]] =
                if mbFutureVrf.contains(sppVrf) then counts
                else
                  val removed = mbFutureVrf
                    .filter(_ != activeVrf)
                    .fold(counts)(removeVRFKeyHashOccurrence(_, counts))
                  if sppVrf != activeVrf then addVRFKeyHashOccurrence(sppVrf, removed) else removed
              for
                _ <- Sts.predicate[F, PoolPredFailure[E]](
                  ps.vrfKeyHashes.get(sppVrf).map(n => Word64.toBigInt(n.value)) ==
                    expectedOccurrences,
                  fail(DijkstraPoolPredFailure.VRFKeyHashAlreadyRegistered(sppId, sppVrf))
                )
                _ <- tell(PoolEvent.ReregisterPool(sppId))
                // the parameters wait in the layout of the pool's state
                _ <- sameLayout[F](stakePoolParams, stakePoolState.blsKey.stateLayout)
              yield ps.copy(
                future = ps.future.updated(sppId, stakePoolParams),
                retiring = ps.retiring.removed(sppId),
                vrfKeyHashes = updateFutureVRFKeyHash(ps.vrfKeyHashes)
              )
          _ <- st.set(next)
        yield ()
      case PoolCert.RetirePool(sppId, e) =>
        // `addEpochInterval cEpoch (pp ^. ppEMaxL)`, a `Word64` sum that wraps
        val limitEpoch = cEpoch.addInterval(field[E, EpochInterval](pp)(p.hkdEMaxL[Identity]))
        for
          ps <- st.get
          _ <- Sts.predicate[F, PoolPredFailure[E]](
            ps.stakePools.contains(sppId),
            fail(DijkstraPoolPredFailure.StakePoolNotRegisteredOnKeyPOOL(sppId))
          )
          _ <- Sts.predicate[F, PoolPredFailure[E]](
            Order[EpochNo].lt(cEpoch, e) && Order[EpochNo].lteqv(e, limitEpoch),
            fail(
              DijkstraPoolPredFailure
                .StakePoolRetirementWrongEpochPOOL(Mismatch(e, cEpoch), Mismatch(e, limitEpoch))
            )
          )
          _ <- st.set(ps.copy(retiring = ps.retiring.updated(sppId, e)))
        yield ()

  /** `invalidBlsKey` (`Dijkstra/Rules/Pool.hs:239-247`, cardano-ledger 9df9f98c): the voting key of
    * `params` if its proof of possession does not verify (`verifyPossessionProofDSIGN`), none if it
    * does or there is no key. A key or proof that is not a point the decoder admits, and a failure
    * of the library, are POOL's defects ([[PoolDefect.BlsKeyUnverifiable]]), as the Leios
    * committee's selection treats them.
    */
  def invalid(params: StakePoolParams): Either[PoolDefect, Option[BlsKey]] =
    def unverifiable(e: BlsError): PoolDefect = PoolDefect.BlsKeyUnverifiable(params.id, e)
    params.blsKey match
      case PoolParamsBlsKey.Node11_1 | PoolParamsBlsKey.Leios(None) => Right(None)
      case PoolParamsBlsKey.Leios(Some(blsKey)) =>
        for
          vk <- VerificationKey.fromBytes(blsKey.key).leftMap(unverifiable)
          pop <- ProofOfPossession.fromBytes(blsKey.proofOfPossession).leftMap(unverifiable)
          held <- MinSig.verifyPossession(vk, pop) match
            case Right(())                               => Right(None)
            case Left(BlsError.ProofOfPossessionInvalid) => Right(Some(blsKey))
            case Left(other)                             => Left(unverifiable(other))
        yield held

  /** `addVRFKeyHashOccurrence` (`State/CertState.hs:504-511`, cardano-ledger 9df9f98c): one more
    * holder of `vrf`, saturating at the largest word, where adding one would reach zero.
    */
  def addVRFKeyHashOccurrence(
      vrf: VRFVerKeyHash,
      counts: SortedMap[VRFVerKeyHash, NonZero[Word64]]
  ): SortedMap[VRFVerKeyHash, NonZero[Word64]] =
    counts.get(vrf) match
      case None => counts.updated(vrf, NonZero.oneWord)
      case Some(n) =>
        NonZero
          .from(Word64.fromUnsigned(n.value.bits + 1))
          .fold(_ => counts, counts.updated(vrf, _))

  /** `removeVRFKeyHashOccurrence` (`State/CertState.hs:515-519`, cardano-ledger 9df9f98c): one
    * holder fewer, the hash removed when none remains, a hash not counted unchanged.
    */
  def removeVRFKeyHashOccurrence(
      vrf: VRFVerKeyHash,
      counts: SortedMap[VRFVerKeyHash, NonZero[Word64]]
  ): SortedMap[VRFVerKeyHash, NonZero[Word64]] =
    xyz.kd5ujc.scarab.ledger.rules.PoolReap.removeOccurrences(counts, Vector(vrf))

/** Dijkstra's CERT (`Dijkstra/Rules/Cert.hs`, cardano-ledger 9df9f98c): one certificate, to the
  * rule of its kind. The era's certificate is Dijkstra's (`TxCert era ~ DijkstraTxCert era`).
  */
object DijkstraCERT:
  /** `certTransition` (`Dijkstra/Rules/Cert.hs:79-112`, cardano-ledger 9df9f98c): Conway's DELEG on
    * the certificate state, the delegation certificate made Conway's (`dijkstraToConwayDelegCert`);
    * Dijkstra's POOL on the pool state alone (`certPStateL`), under the policy `validation`;
    * Conway's GOVCERT with the era's injection. Each is a sub-rule whose failures and events CERT's
    * embed ([[EmbedInCert]]); POOL's defects are raised as they are (`poolDefect`).
    */
  def certTransition[E <: EraTag, F[_]: Monad](
      env: CertEnv[E],
      txCert: TxCert[E],
      validation: TxEnv.Validation
  )(using
      ev: TxCert[E] =:= DijkstraTxCert,
      p: ConwayEraPParams[E],
      c: ConwayEraCertState[E],
      embed: EmbedInCert[E],
      deleg: InjectDeleg[E],
      pool: InjectDijkstraPool[E],
      govCert: InjectGovCert[E],
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[E]],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      judge: Judge[F, CertPredFailure[E]],
      events: Tell[F, Chain[CertEvent[E]]]
  ): F[Unit] =
    st.get.flatMap { certState =>
      val pools = c.certPStateL.get(certState).stakePools
      ev(txCert) match
        case DijkstraTxCert.DijkstraTxCertDeleg(delegCert) =>
          judge.nest(
            DELEG.conwayDelegTransition[E, F](
              ConwayDelegEnv[E](env.pp, pools),
              DijkstraTxCert.dijkstraToConwayDelegCert(delegCert)
            )(using summon[Monad[F]], p, c, deleg, st, wrapped(judge)(embed.wrapDelegFailed))
          )
        case DijkstraTxCert.DijkstraTxCertPool(poolCert) =>
          judge.nest(
            DijkstraPOOL.poolTransition[E, F](
              PoolEnv[E](env.currentEpoch, env.pp),
              poolCert,
              validation
            )(using
              summon[Monad[F]],
              p,
              pool,
              ask,
              st.focus(c.certPStateL),
              defect,
              poolDefect,
              wrapped(judge)(embed.wrapPoolFailed),
              told(events)(embed.wrapPoolEvent)
            )
          )
        case DijkstraTxCert.DijkstraTxCertGov(govCertificate) =>
          judge.nest(
            GOVCERT.conwayGovCertTransition[E, F](
              ConwayGovCertEnv[E](
                env.pp,
                env.currentEpoch,
                env.currentCommittee,
                env.committeeProposals
              ),
              govCertificate
            )(using summon[Monad[F]], p, c, govCert, st, wrapped(judge)(embed.wrapGovCertFailed))
          )
    }

/** `EntitiesEnv` (`Dijkstra/Rules/Entities.hs:68-75`, cardano-ledger 9df9f98c): the epoch, the
  * parameters, the committee and the proposals to update it (the node's records, as in
  * [[CertsEnv]]), and the accounts before the batch.
  */
final case class EntitiesEnv[E <: EraTag](
    currentEpoch: EpochNo,
    pp: PParams[E],
    currentCommittee: Option[Committee],
    committeeProposals: Map[GovActionId, GovActionState],
    originalAccounts: Accounts[E]
)

/** ENTITIES (`Dijkstra/Rules/Entities.hs`, cardano-ledger 9df9f98c): a top-level transaction's
  * withdrawals, direct deposits, account balance intervals and certificates, over the certificate
  * state after its sub-transactions. Written at Dijkstra, the one era with the rule, as Dijkstra's
  * UTXO is.
  */
object ENTITIES:
  private val t = EraTx.dijkstra
  private val b = EraTxBody.dijkstra
  private val u = EraUTxO.dijkstra
  private val c = EraCertState.dijkstra
  private given a: ConwayEraAccounts[Dijkstra] = c.accountsOf
  private given BothLevels[TxLevel.Top] = BothLevels.Top

  /** `shelleyUtxoToDijkstraEntitiesPredFailure` (`Dijkstra/Rules/Entities.hs:428-443`,
    * cardano-ledger 9df9f98c), on the one constructor of Shelley's failure ENTITIES reaches, the
    * one its predicate declares (`validateWrongNetworkWithdrawal`): the reference calls every other
    * impossible, and here they do not compile.
    */
  def shelleyUtxoToDijkstraEntitiesPredFailure(
      failure: ShelleyUtxoPredFailure.WrongNetworkWithdrawal[Dijkstra]
  ): EntitiesPredFailure.WithdrawalAddressesWithWrongNetwork[Dijkstra] =
    new EntitiesPredFailure.WithdrawalAddressesWithWrongNetwork[Dijkstra](
      failure.expected,
      failure.accounts
    )

  /** A body's map keyed by the node's reward account (its direct deposits and balance intervals),
    * read into the reference's `AccountAddress` keys, which every decoded reward account is.
    */
  def keyed[V](m: Map[RewardAccount, V]): Either[VocabError, SortedMap[AccountAddress, V]] =
    m.toVector.traverse((k, v) => Convert.accountAddress(k).map(_ -> v)).map(SortedMap.from(_))

  /** `lookupAccount` of `categorizeWithdrawals` (`State/Account.hs:262-264`, cardano-ledger
    * 9df9f98c): the account of `address` if it is on `network` and registered.
    */
  private def lookupAccount(
      address: AccountAddress,
      network: Network,
      accounts: Accounts[Dijkstra]
  ): Option[AccountState[Dijkstra]] =
    Option
      .when(address.networkId == network)(address.id.credential)
      .flatMap(EraAccounts.lookupAccountState[Dijkstra](_, accounts))

  /** `categorizeWithdrawals` (`State/Account.hs:230-264`, cardano-ledger 9df9f98c): none if every
    * withdrawal is of a registered account on `network` and its amount `acceptable`; otherwise the
    * withdrawals of an account missing or on another network, and those not acceptable, each with
    * its account's balance.
    */
  private def categorizeWithdrawals[R <: Relation](acceptable: (Coin, Coin) => Boolean)(
      withdrawals: Withdrawals,
      network: Network,
      accounts: Accounts[Dijkstra]
  ): Option[(Withdrawals, SortedMap[AccountAddress, Mismatch[R, Coin]])] =
    val found = withdrawals.unWithdrawals.map((address, amount) =>
      (address, amount, lookupAccount(address, network, accounts).map(balance))
    )
    val missing = SortedMap.from(found.collect { case (address, amount, None) =>
      address -> amount
    })
    val bad = SortedMap.from(found.collect {
      case (address, amount, Some(bal)) if !acceptable(amount, bal) =>
        address -> Mismatch[R, Coin](amount, bal)
    })
    Option.when(missing.nonEmpty || bad.nonEmpty)((Withdrawals(missing), bad))

  private def balance(account: AccountState[Dijkstra]): Coin =
    a.balanceAccountStateL.get(account).toCoin

  /** `withdrawalsMissingAccounts` (`State/Account.hs:359-366`, cardano-ledger 9df9f98c): none if
    * every withdrawal is of a registered account on `network`, otherwise those that are not, which
    * Dijkstra's SUBENTITIES reports.
    */
  def withdrawalsMissingAccounts(
      withdrawals: Withdrawals,
      network: Network,
      accounts: Accounts[Dijkstra]
  ): Option[Withdrawals] =
    categorizeWithdrawals[RelEQ]((_, _) => true)(withdrawals, network, accounts).map(_._1)

  /** `withdrawalsThatDoNotDrainAccounts` (`State/Account.hs:203-217`, cardano-ledger 9df9f98c). */
  def withdrawalsThatDoNotDrainAccounts(
      withdrawals: Withdrawals,
      network: Network,
      accounts: Accounts[Dijkstra]
  ): Option[(Withdrawals, SortedMap[AccountAddress, Mismatch[RelEQ, Coin]])] =
    categorizeWithdrawals[RelEQ](Order[Coin].eqv)(withdrawals, network, accounts)

  /** `withdrawalsThatExceedAccountBalance` (`State/Account.hs:219-228`, cardano-ledger 9df9f98c).
    */
  def withdrawalsThatExceedAccountBalance(
      withdrawals: Withdrawals,
      network: Network,
      accounts: Accounts[Dijkstra]
  ): Option[(Withdrawals, SortedMap[AccountAddress, Mismatch[RelLTEQ, Coin]])] =
    categorizeWithdrawals[RelLTEQ](Order[Coin].lteqv)(withdrawals, network, accounts)

  /** `updateAccountBalances` (`State/Account.hs:312-330`, cardano-ledger 9df9f98c): `update` of
    * each registered account's balance and the entry's amount, whatever the entry's network; an
    * entry of no registered account changes nothing. The amount is a `CompactForm Coin`
    * (`compactCoinOrError`), which every decoded amount is.
    */
  private def updateAccountBalances(update: (CompactCoin, CompactCoin) => CompactCoin)(
      balances: SortedMap[AccountAddress, Coin],
      accounts: Accounts[Dijkstra]
  ): Either[VocabError, Accounts[Dijkstra]] =
    balances.toVector.foldM(accounts) { case (acc, (address, amount)) =>
      CompactCoin
        .fromCoin(amount)
        .map(compact =>
          EraAccounts.adjustAccountState[Dijkstra](
            a.balanceAccountStateL.modify(update(compact, _)),
            address.id.credential,
            acc
          )
        )
    }

  /** `applyWithdrawals` (`State/Account.hs:284-293`, cardano-ledger 9df9f98c): each amount taken
    * from its account's balance in `Word64` arithmetic that wraps (`subtractCompactCoin`).
    */
  def applyWithdrawals(
      withdrawals: Withdrawals,
      accounts: Accounts[Dijkstra]
  ): Either[VocabError, Accounts[Dijkstra]] =
    updateAccountBalances((amount, bal) => bal.subtract(amount))(
      withdrawals.unWithdrawals,
      accounts
    )

  /** `applyDirectDeposits` (`State/Account.hs:297-305`, cardano-ledger 9df9f98c): each amount added
    * to its account's balance in `Word64` arithmetic that wraps (`addCompactCoin`).
    */
  def applyDirectDeposits(
      deposits: SortedMap[AccountAddress, Coin],
      accounts: Accounts[Dijkstra]
  ): Either[VocabError, Accounts[Dijkstra]] =
    updateAccountBalances((amount, bal) => bal.add(amount))(deposits, accounts)

  /** `directDepositsMissingAccounts` (`State/Account.hs:337-353`, cardano-ledger 9df9f98c): none if
    * every deposit is to a registered account on `network`, otherwise those that are not.
    */
  def directDepositsMissingAccounts(
      deposits: SortedMap[AccountAddress, Coin],
      network: Network,
      accounts: Accounts[Dijkstra]
  ): Option[SortedMap[AccountAddress, Coin]] =
    val missing = deposits.filter((address, _) =>
      address.networkId != network ||
        !EraAccounts.isAccountRegistered[Dijkstra](address.id.credential, accounts)
    )
    Option.when(missing.nonEmpty)(missing)

  /** `validateWrongNetworkInDirectDeposit` (`Dijkstra/Rules/Entities.hs:316-328`, cardano-ledger
    * 9df9f98c).
    */
  def validateWrongNetworkInDirectDeposit(
      netId: Network,
      deposits: SortedMap[AccountAddress, Coin]
  ): Test[EntitiesPredFailure.DirectDepositAddressesWithWrongNetwork[Dijkstra]] =
    Test.failureOnJust(
      NonEmptySet.fromSet(SortedSet.from(deposits.keysIterator.filter(_.networkId != netId)))
    )(new EntitiesPredFailure.DirectDepositAddressesWithWrongNetwork[Dijkstra](netId, _))

  /** `validateMissingAccountsInDirectDeposits` (`Dijkstra/Rules/Entities.hs:330-339`,
    * cardano-ledger 9df9f98c).
    */
  def validateMissingAccountsInDirectDeposits(
      deposits: SortedMap[AccountAddress, Coin],
      network: Network,
      accounts: Accounts[Dijkstra]
  ): Test[EntitiesPredFailure.DirectDepositAccountsMissing[Dijkstra]] =
    Test.failureOnJust(directDepositsMissingAccounts(deposits, network, accounts))(missing =>
      new EntitiesPredFailure.DirectDepositAccountsMissing[Dijkstra](missing)
    )

  private def failWithdrawals(
      m: Withdrawals
  )(mk: Map[AccountAddress, Coin] => EntitiesPredFailure[Dijkstra]): Test[
    EntitiesPredFailure[Dijkstra]
  ] = Test.failureUnless(m.unWithdrawals.isEmpty, mk(m.unWithdrawals))

  private def failMismatches[R <: Relation](m: SortedMap[AccountAddress, Mismatch[R, Coin]])(
      mk: NonEmptyMap[AccountAddress, Mismatch[R, Coin]] => EntitiesPredFailure[Dijkstra]
  ): Test[EntitiesPredFailure[Dijkstra]] = Test.failureOnJust(NonEmptyMap.fromMap(m))(mk)

  /** `validateWithdrawalsAgainstOriginalAccounts` (`Dijkstra/Rules/Entities.hs:341-386`,
    * cardano-ledger 9df9f98c): in the normal mode the top level's withdrawals of an account the
    * original accounts lack (a sub-transaction's are SUBENTITIES'), then the accounts whose
    * withdrawals over the batch exceed their original balance; in the legacy mode those of the
    * sub-transactions' alone.
    */
  def validateWithdrawalsAgainstOriginalAccounts(
      legacyMode: PlutusLegacyMode,
      topTxWithdrawals: Withdrawals,
      sumOfSubTxWithdrawals: Withdrawals,
      network: Network,
      originalAccounts: Accounts[Dijkstra]
  ): Test[EntitiesPredFailure[Dijkstra]] =
    legacyMode match
      case PlutusLegacyMode.NotLegacy =>
        withdrawalsThatExceedAccountBalance(
          topTxWithdrawals |+| sumOfSubTxWithdrawals,
          network,
          originalAccounts
        ).fold(Test.pass[EntitiesPredFailure[Dijkstra]]) { (missingAccounts, exceedingBalances) =>
          val topMissingAccounts = Withdrawals(
            topTxWithdrawals.unWithdrawals.filter((k, _) =>
              missingAccounts.unWithdrawals.contains(k)
            )
          )
          Test.all(
            failWithdrawals(topMissingAccounts)(
              EntitiesPredFailure.WithdrawalAccountsMissingFromOriginal[Dijkstra](_)
            ),
            failMismatches(exceedingBalances)(
              EntitiesPredFailure.WithdrawalAmountsExceedingOriginalBalance[Dijkstra](_)
            )
          )
        }
      case PlutusLegacyMode.Legacy =>
        // missing accounts are SUBENTITIES'
        withdrawalsThatExceedAccountBalance(sumOfSubTxWithdrawals, network, originalAccounts)
          .fold(Test.pass[EntitiesPredFailure[Dijkstra]])((_, exceedingBalances) =>
            failMismatches(exceedingBalances)(
              EntitiesPredFailure.WithdrawalAmountsExceedingOriginalBalance[Dijkstra](_)
            )
          )

  /** `validateWithdrawalsAgainstCurrentAccounts` (`Dijkstra/Rules/Entities.hs:388-411`,
    * cardano-ledger 9df9f98c): the top level's withdrawals of an account the current accounts lack
    * and, in the legacy mode, those that do not drain their account.
    */
  def validateWithdrawalsAgainstCurrentAccounts(
      legacyMode: PlutusLegacyMode,
      topTxWithdrawals: Withdrawals,
      network: Network,
      accounts: Accounts[Dijkstra]
  ): Test[EntitiesPredFailure[Dijkstra]] =
    withdrawalsThatDoNotDrainAccounts(topTxWithdrawals, network, accounts)
      .fold(Test.pass[EntitiesPredFailure[Dijkstra]]) { (missingAccounts, inexact) =>
        Test.all(
          failWithdrawals(missingAccounts)(
            EntitiesPredFailure.WithdrawalAccountsMissing[Dijkstra](_)
          ),
          legacyMode match
            case PlutusLegacyMode.Legacy =>
              failMismatches(inexact)(
                EntitiesPredFailure.WithdrawalAmountsInexactInLegacyMode[Dijkstra](_)
              )
            case PlutusLegacyMode.NotLegacy => Test.pass
        )
      }

  /** `accountBalanceIntervalContains` (`Dijkstra/Rules/Entities.hs:455-460`, cardano-ledger
    * 9df9f98c).
    */
  def accountBalanceIntervalContains(bal: Coin, interval: AccountBalanceInterval): Boolean =
    interval match
      case AccountBalanceInterval.AccountBalanceLowerBound(Inclusive(lo)) =>
        Order[Coin].lteqv(lo, bal)
      case AccountBalanceInterval.AccountBalanceUpperBound(Exclusive(hi)) => Order[Coin].lt(bal, hi)
      case AccountBalanceInterval.AccountBalanceBothBounds(Inclusive(lo), Exclusive(hi)) =>
        Order[Coin].lteqv(lo, bal) && Order[Coin].lt(bal, hi)
      case AccountBalanceInterval.AccountBalanceExact(n) => Order[Coin].eqv(bal, n)

  /** `categorizeAccountBalanceIntervals` (`Dijkstra/Rules/Entities.hs:462-483`, cardano-ledger
    * 9df9f98c): the intervals' accounts on another network, those not registered, and those whose
    * balance is outside its interval, with the balance.
    */
  def categorizeAccountBalanceIntervals(
      network: Network,
      accounts: Accounts[Dijkstra],
      intervals: SortedMap[AccountAddress, AccountBalanceInterval]
  ): (
      SortedSet[AccountAddress],
      SortedMap[AccountAddress, AccountBalanceInterval],
      SortedMap[AccountAddress, (Coin, AccountBalanceInterval)]
  ) =
    intervals.foldLeft(
      (
        SortedSet.empty[AccountAddress],
        SortedMap.empty[AccountAddress, AccountBalanceInterval],
        SortedMap.empty[AccountAddress, (Coin, AccountBalanceInterval)]
      )
    ) { case ((wrongNetwork, missing, outside), (acct, interval)) =>
      if acct.networkId != network then (wrongNetwork + acct, missing, outside)
      else
        EraAccounts.lookupAccountState[Dijkstra](acct.id.credential, accounts) match
          case None => (wrongNetwork, missing.updated(acct, interval), outside)
          case Some(accountState) =>
            val bal = balance(accountState)
            if accountBalanceIntervalContains(bal, interval) then (wrongNetwork, missing, outside)
            else (wrongNetwork, missing, outside.updated(acct, (bal, interval)))
    }

  /** `validateAccountBalanceIntervals` (`Dijkstra/Rules/Entities.hs:485-500`) and
    * `validateStartingAccountBalanceIntervals` (`:502-517`, cardano-ledger 9df9f98c): the same
    * three tests over `intervals`, against the accounts given, each reported by its own
    * constructors.
    */
  private def validateIntervals[R](
      network: Network,
      accounts: Accounts[Dijkstra],
      intervals: SortedMap[AccountAddress, AccountBalanceInterval]
  )(
      wrongNetworkFailure: NonEmptySet[AccountAddress] => R,
      missingFailure: NonEmptyMap[AccountAddress, AccountBalanceInterval] => R,
      outsideFailure: NonEmptyMap[AccountAddress, (Coin, AccountBalanceInterval)] => R
  ): Test[R] =
    val (wrongNetwork, missing, outside) =
      categorizeAccountBalanceIntervals(network, accounts, intervals)
    Test.all(
      Test.failureOnJust(NonEmptySet.fromSet(wrongNetwork))(wrongNetworkFailure),
      Test.failureOnJust(NonEmptyMap.fromMap(missing))(missingFailure),
      Test.failureOnJust(NonEmptyMap.fromMap(outside))(outsideFailure)
    )

  /** `validateAccountBalanceIntervals` (`Dijkstra/Rules/Entities.hs:485-500`, cardano-ledger
    * 9df9f98c): against the current accounts.
    */
  def validateAccountBalanceIntervals(
      network: Network,
      accounts: Accounts[Dijkstra],
      intervals: SortedMap[AccountAddress, AccountBalanceInterval]
  ): Test[IntervalsFailure] =
    validateIntervals[IntervalsFailure](network, accounts, intervals)(
      new EntitiesPredFailure.WrongNetworkInAccountBalanceIntervals[Dijkstra](network, _),
      new EntitiesPredFailure.MissingAccountsInAccountBalanceIntervals[Dijkstra](_),
      new EntitiesPredFailure.BalancesOutsideAccountBalanceIntervals[Dijkstra](_)
    )

  /** The failures [[validateAccountBalanceIntervals]] reports, the domain of an injection it asks
    * for (SUBENTITIES').
    */
  type IntervalsFailure = EntitiesPredFailure.WrongNetworkInAccountBalanceIntervals[Dijkstra] |
    EntitiesPredFailure.MissingAccountsInAccountBalanceIntervals[Dijkstra] |
    EntitiesPredFailure.BalancesOutsideAccountBalanceIntervals[Dijkstra]

  /** `validateStartingAccountBalanceIntervals` (`Dijkstra/Rules/Entities.hs:502-517`,
    * cardano-ledger 9df9f98c): against the original accounts.
    */
  def validateStartingAccountBalanceIntervals(
      network: Network,
      accounts: Accounts[Dijkstra],
      intervals: SortedMap[AccountAddress, AccountBalanceInterval]
  ): Test[EntitiesPredFailure[Dijkstra]] =
    validateIntervals[EntitiesPredFailure[Dijkstra]](network, accounts, intervals)(
      EntitiesPredFailure.WrongNetworkInStartingAccountBalanceIntervals[Dijkstra](network, _),
      EntitiesPredFailure.MissingAccountsInStartingAccountBalanceIntervals[Dijkstra](_),
      EntitiesPredFailure.BalancesOutsideStartingAccountBalanceIntervals[Dijkstra](_)
    )

  /** `dijkstraEntitiesTransition` (`Dijkstra/Rules/Entities.hs:261-314`, cardano-ledger 9df9f98c),
    * every failure recorded and injected into ENTITIES's ([[InjectEntitiesFailure]]): the
    * withdrawals' network, the direct deposits' network, the balance intervals against the current
    * accounts and the starting ones against the original; the withdrawals against the original
    * accounts, and, only when nothing has failed so far (`whenFailureFree`: a failed check may have
    * left the current accounts underflowed), against the current ones. Then the DReps' expiries are
    * updated and the top level's withdrawals applied, CERTS runs as a sub-rule on that state, its
    * failures and events embedded ([[EmbedCertsInEntities]]); the direct deposits are checked
    * against the accounts after CERTS and added to them. The withdrawals are the body's
    * `Withdrawals`; each account of its direct deposits and balance intervals is read into the
    * reference's `AccountAddress`, which every decoded one is, a value outside the vocabulary
    * raised ([[LedgerFailure.Vocab]]).
    *
    * `validation` is the policy the judgment runs under, which POOL's static check reads
    * ([[CertRule.dijkstra]]); a voting key POOL cannot check is raised (`poolDefect`).
    */
  def dijkstraEntitiesTransition[F[_]: Monad](
      env: EntitiesEnv[Dijkstra],
      stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
      validation: TxEnv.Validation
  )(using
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[Dijkstra]],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      judge: Judge[F, EntitiesRuleFailure[Dijkstra]],
      events: Tell[F, Chain[EntitiesRuleEvent[Dijkstra]]]
  ): F[Unit] =
    val embed = EmbedCertsInEntities.dijkstra
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)
    val txBody = t.body[TxLevel.Top](tx)
    val pp = env.pp
    val curEpoch = env.currentEpoch
    val legacyMode = u.plutusLegacyModeStAnnTx(stAnnTx)
    val accountsL = c.certDStateL.andThen(DState.accounts[Accounts[Dijkstra]])
    val certsEnv =
      CertsEnv[Dijkstra](tx, pp, curEpoch, env.currentCommittee, env.committeeProposals)
    for
      certState <- st.get
      accounts = accountsL.get(certState)
      globals <- ask.ask[Globals]
      network <- vocab[F, Network](
        Network
          .of(BigInt(globals.networkId))
          .toRight(VocabError.OutOfRange(VocabError.Kind.Network, BigInt(globals.networkId)))
      )
      topTxWithdrawals = b.withdrawals[TxLevel.Top](txBody)
      sumOfSubTxWithdrawals = b
        .subTransactions(txBody)
        .toVector
        .foldMap(sub => b.withdrawals[TxLevel.Sub](sub.body)(using BothLevels.Sub))
      directDeposits <- vocab[F, SortedMap[AccountAddress, Coin]](
        keyed(b.directDeposits[TxLevel.Top](txBody))
      )
      intervals <- vocab[F, SortedMap[AccountAddress, AccountBalanceInterval]](
        keyed(b.accountBalanceIntervals[TxLevel.Top](txBody))
      )
      startingIntervals <- vocab[F, SortedMap[AccountAddress, AccountBalanceInterval]](
        keyed(b.startingAccountBalanceIntervals(txBody))
      )
      _ <- Sts.runTest[F, EntitiesPredFailure[Dijkstra]](
        Test.all[EntitiesPredFailure[Dijkstra]](
          ShelleyUtxo
            .validateWrongNetworkWithdrawal[Dijkstra, TxLevel.Top](network, txBody)
            .leftMap(_.map(shelleyUtxoToDijkstraEntitiesPredFailure)),
          validateWrongNetworkInDirectDeposit(network, directDeposits),
          validateAccountBalanceIntervals(network, accounts, intervals),
          validateStartingAccountBalanceIntervals(network, env.originalAccounts, startingIntervals),
          validateWithdrawalsAgainstOriginalAccounts(
            legacyMode,
            topTxWithdrawals,
            sumOfSubTxWithdrawals,
            network,
            env.originalAccounts
          )
        )
      )
      _ <- Sts.whenFailureFree[F, EntitiesPredFailure[Dijkstra]](
        Sts.runTest[F, EntitiesPredFailure[Dijkstra]](
          validateWithdrawalsAgainstCurrentAccounts(legacyMode, topTxWithdrawals, network, accounts)
        )
      )
      voted <- vocab[F, CertState[Dijkstra]](
        CERTS.updateVotingDRepExpiries[Dijkstra, TxLevel.Top](
          tx,
          curEpoch,
          field[Dijkstra, EpochInterval](pp)(
            EraPParams.dijkstra.hkdDRepActivityL[Identity]
          ),
          CERTS.updateDormantDRepExpiries[Dijkstra, TxLevel.Top](tx, curEpoch, certState)
        )
      )
      withdrawn <- vocab[F, Accounts[Dijkstra]](
        applyWithdrawals(topTxWithdrawals, accountsL.get(voted))
      )
      _ <- st.set(accountsL.replace(withdrawn)(voted))
      _ <- judge.nest(
        CERTS.conwayCertsTransition[Dijkstra, PoolDefect, F](
          certsEnv,
          b.certs[TxLevel.Top](txBody)
        )(using
          summon[Monad[F]],
          t,
          b,
          EraPParams.dijkstra,
          c,
          EmbedCertInCerts.dijkstra,
          CertRule.dijkstra(validation),
          CertsBaseCase.dijkstra,
          InjectCertsFailure.dijkstra,
          ask,
          st,
          defect,
          poolDefect,
          wrapped(judge)(embed.wrapFailed),
          told(events)(embed.wrapEvent)
        )
      )
      certStateAfterCerts <- st.get
      accountsAfterCerts = accountsL.get(certStateAfterCerts)
      _ <- Sts.runTest[F, EntitiesPredFailure[Dijkstra]](
        validateMissingAccountsInDirectDeposits(directDeposits, network, accountsAfterCerts)
      )
      deposited <- vocab[F, Accounts[Dijkstra]](
        applyDirectDeposits(directDeposits, accountsAfterCerts)
      )
      _ <- st.set(accountsL.replace(deposited)(certStateAfterCerts))
    yield ()
