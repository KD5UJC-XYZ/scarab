// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.Monad
import cats.data.{Chain, NonEmptyMap, NonEmptySet}
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.{Committee, GovActionId, GovActionState, LedgerDefect, TxEnv}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.AccountBalanceInterval
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Sts, Test}
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  Accounts,
  CertState,
  Coin,
  DState,
  Dijkstra,
  EpochInterval,
  EpochNo,
  EraTag,
  Network,
  VocabError,
  Withdrawals
}

// SUBENTITIES of Dijkstra as the reference writes it (cardano-ledger 9df9f98c,
// `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/SubEntities.hs`): a sub-transaction's
// withdrawals, direct deposits, account balance intervals and certificates, with ENTITIES'
// predicates (`DijkstraCerts.scala`) and SUBCERTS as its sub-rule (`DijkstraSubCerts.scala`). Its
// injections of ENTITIES' failures, which the reference leaves partial
// (`entitiesToSubEntitiesPredFailure`, `SubEntities.hs:317-338`), are total on what it reaches, and
// Conway's LEDGER failure, impossible on every constructor (`:302-315`), has no injection.

/** `data SubEntitiesPredFailure era` (`Dijkstra/Rules/SubEntities.hs:107-127`, cardano-ledger
  * 9df9f98c), its constructors in the reference's order, which is their tag (`:148-158`).
  */
enum SubEntitiesPredFailure[E <: EraTag]:
  case SubCertsFailure(failure: SubCertsPredFailure[E])
  case SubWithdrawalAddressesWithWrongNetwork(
      expected: Network,
      accounts: NonEmptySet[AccountAddress]
  )
  case SubWithdrawalAccountsMissing(withdrawals: Map[AccountAddress, Coin])
  case SubWithdrawalAccountsMissingFromOriginal(withdrawals: Map[AccountAddress, Coin])
  case SubDirectDepositAddressesWithWrongNetwork(
      expected: Network,
      accounts: NonEmptySet[AccountAddress]
  )
  case SubDirectDepositAccountsMissing(deposits: Map[AccountAddress, Coin])
  case SubBalancesOutsideAccountBalanceIntervals(
      accounts: NonEmptyMap[AccountAddress, (Coin, AccountBalanceInterval)]
  )
  case SubMissingAccountsInAccountBalanceIntervals(
      accounts: NonEmptyMap[AccountAddress, AccountBalanceInterval]
  )
  case SubWrongNetworkInAccountBalanceIntervals(
      expected: Network,
      accounts: NonEmptySet[AccountAddress]
  )

object SubEntitiesPredFailure:
  given [E <: EraTag]: CanEqual[SubEntitiesPredFailure[E], SubEntitiesPredFailure[E]] =
    CanEqual.derived

/** `newtype SubEntitiesEvent era` (`Dijkstra/Rules/SubEntities.hs:178`, cardano-ledger 9df9f98c).
  */
final case class SubEntitiesEvent[E <: EraTag](event: SubCertsEvent[E])

object SubEntitiesEvent:
  given [E <: EraTag]: CanEqual[SubEntitiesEvent[E], SubEntitiesEvent[E]] = CanEqual.derived

/** `EraRuleFailure "SUBENTITIES" era` (`Dijkstra/Rules/SubEntities.hs:185`, cardano-ledger
  * 9df9f98c).
  */
type SubEntitiesRuleFailure[E <: EraTag] = E match
  case Dijkstra => SubEntitiesPredFailure[Dijkstra]

/** `EraRuleEvent "SUBENTITIES" era` (`Dijkstra/Rules/SubEntities.hs:187`, cardano-ledger 9df9f98c).
  */
type SubEntitiesRuleEvent[E <: EraTag] = E match
  case Dijkstra => SubEntitiesEvent[Dijkstra]

/** `SubEntitiesEnv` (`Dijkstra/Rules/SubEntities.hs:68-74`, cardano-ledger 9df9f98c): the epoch,
  * the parameters, the committee and the proposals to update it (the node's records, as in
  * [[EntitiesEnv]]), and the accounts before the batch.
  */
final case class SubEntitiesEnv[E <: EraTag](
    currentEpoch: EpochNo,
    pp: PParams[E],
    currentCommittee: Option[Committee],
    committeeProposals: Map[GovActionId, GovActionState],
    originalAccounts: Accounts[E]
)

/** `InjectRuleFailure "SUBENTITIES" f era` (`Core/Era.hs:119-124`, cardano-ledger 9df9f98c):
  * contravariant in `F`, as [[InjectUtxoFailure]] is, so that the instance over the constructors of
  * ENTITIES' failure SUBENTITIES reaches serves a predicate declaring one of them. Shelley's
  * failure goes through ENTITIES' function, which reads the accounts
  * ([[DijkstraSubEntities.shelleyUtxoToDijkstraSubEntitiesPredFailure]]), and Conway's LEDGER
  * failure has no injection.
  */
trait InjectSubEntitiesFailure[-F, E <: EraTag]:
  def injectFailure(failure: F): SubEntitiesRuleFailure[E]

object InjectSubEntitiesFailure:
  /** `Dijkstra/Rules/SubEntities.hs:189`, cardano-ledger 9df9f98c: the identity. */
  given dijkstra: InjectSubEntitiesFailure[SubEntitiesPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: SubEntitiesPredFailure[Dijkstra]): SubEntitiesRuleFailure[Dijkstra] =
      failure

  /** `Dijkstra/Rules/SubEntities.hs:191-192`, cardano-ledger 9df9f98c: `SubCertsFailure`. */
  given dijkstraSubCerts: InjectSubEntitiesFailure[DijkstraSubCertsPredFailure[Dijkstra], Dijkstra]
  with
    def injectFailure(
        failure: DijkstraSubCertsPredFailure[Dijkstra]
    ): SubEntitiesRuleFailure[Dijkstra] = SubEntitiesPredFailure.SubCertsFailure(failure)

  /** `Dijkstra/Rules/SubEntities.hs:194-195`, cardano-ledger 9df9f98c: `SubCertsFailure` after
    * SUBCERTS's injection of CERTS's failure.
    */
  given dijkstraCerts: InjectSubEntitiesFailure[ConwayCertsPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: ConwayCertsPredFailure[Dijkstra]
    ): SubEntitiesRuleFailure[Dijkstra] = SubEntitiesPredFailure.SubCertsFailure(
      InjectSubCertsFailure.dijkstraConway.injectFailure(failure)
    )

  /** `Dijkstra/Rules/SubEntities.hs:200-201`, cardano-ledger 9df9f98c: on the constructors the
    * reference maps ([[DijkstraSubEntities.EntitiesFailure]]).
    */
  given dijkstraEntities: InjectSubEntitiesFailure[DijkstraSubEntities.EntitiesFailure, Dijkstra]
  with
    def injectFailure(
        failure: DijkstraSubEntities.EntitiesFailure
    ): SubEntitiesRuleFailure[Dijkstra] =
      DijkstraSubEntities.entitiesToSubEntitiesPredFailure(failure)

/** Dijkstra's functions of SUBENTITIES
  * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/SubEntities.hs`, cardano-ledger
  * 9df9f98c).
  */
object DijkstraSubEntities:
  /** The constructors of ENTITIES' failure that `entitiesToSubEntitiesPredFailure` maps
    * (`Dijkstra/Rules/SubEntities.hs:317-338`, cardano-ledger 9df9f98c): its domain here, the 8 it
    * calls impossible left out.
    */
  type EntitiesFailure = EntitiesPredFailure.WithdrawalAddressesWithWrongNetwork[Dijkstra] |
    EntitiesPredFailure.DirectDepositAddressesWithWrongNetwork[Dijkstra] |
    EntitiesPredFailure.DirectDepositAccountsMissing[Dijkstra] | ENTITIES.IntervalsFailure

  /** `entitiesToSubEntitiesPredFailure` (`Dijkstra/Rules/SubEntities.hs:317-338`, cardano-ledger
    * 9df9f98c), on the constructors it maps ([[EntitiesFailure]]).
    */
  def entitiesToSubEntitiesPredFailure(failure: EntitiesFailure): SubEntitiesPredFailure[Dijkstra] =
    failure match
      case EntitiesPredFailure.WithdrawalAddressesWithWrongNetwork(net, addrs) =>
        SubEntitiesPredFailure.SubWithdrawalAddressesWithWrongNetwork(net, addrs)
      case EntitiesPredFailure.DirectDepositAddressesWithWrongNetwork(net, addrs) =>
        SubEntitiesPredFailure.SubDirectDepositAddressesWithWrongNetwork(net, addrs)
      case EntitiesPredFailure.DirectDepositAccountsMissing(dds) =>
        SubEntitiesPredFailure.SubDirectDepositAccountsMissing(dds)
      case EntitiesPredFailure.WrongNetworkInAccountBalanceIntervals(net, addrs) =>
        SubEntitiesPredFailure.SubWrongNetworkInAccountBalanceIntervals(net, addrs)
      case EntitiesPredFailure.MissingAccountsInAccountBalanceIntervals(x) =>
        SubEntitiesPredFailure.SubMissingAccountsInAccountBalanceIntervals(x)
      case EntitiesPredFailure.BalancesOutsideAccountBalanceIntervals(x) =>
        SubEntitiesPredFailure.SubBalancesOutsideAccountBalanceIntervals(x)

  /** `injectFailure` of `InjectRuleFailure "SUBENTITIES" Shelley.ShelleyUtxoPredFailure
    * DijkstraEra` (`Dijkstra/Rules/SubEntities.hs:203-204`, cardano-ledger 9df9f98c):
    * [[entitiesToSubEntitiesPredFailure]] after ENTITIES' injection
    * ([[ENTITIES.shelleyUtxoToDijkstraEntitiesPredFailure]]), on the one constructor its predicate
    * declares.
    */
  def shelleyUtxoToDijkstraSubEntitiesPredFailure(
      failure: ShelleyUtxoPredFailure.WrongNetworkWithdrawal[Dijkstra]
  ): SubEntitiesPredFailure[Dijkstra] =
    entitiesToSubEntitiesPredFailure(ENTITIES.shelleyUtxoToDijkstraEntitiesPredFailure(failure))

  /** `validateMissingOriginalAccountsInWithdrawals` (`Dijkstra/Rules/SubEntities.hs:280-289`,
    * cardano-ledger 9df9f98c).
    */
  def validateMissingOriginalAccountsInWithdrawals(
      withdrawals: Withdrawals,
      network: Network,
      originalAccounts: Accounts[Dijkstra]
  ): Test[SubEntitiesPredFailure.SubWithdrawalAccountsMissingFromOriginal[Dijkstra]] =
    Test.failureOnJust(ENTITIES.withdrawalsMissingAccounts(withdrawals, network, originalAccounts))(
      missing =>
        new SubEntitiesPredFailure.SubWithdrawalAccountsMissingFromOriginal[Dijkstra](
          missing.unWithdrawals
        )
    )

  /** `validateMissingAccountsInWithdrawals` (`Dijkstra/Rules/SubEntities.hs:291-300`,
    * cardano-ledger 9df9f98c).
    */
  def validateMissingAccountsInWithdrawals(
      withdrawals: Withdrawals,
      network: Network,
      accounts: Accounts[Dijkstra]
  ): Test[SubEntitiesPredFailure.SubWithdrawalAccountsMissing[Dijkstra]] =
    Test.failureOnJust(ENTITIES.withdrawalsMissingAccounts(withdrawals, network, accounts))(
      missing =>
        new SubEntitiesPredFailure.SubWithdrawalAccountsMissing[Dijkstra](missing.unWithdrawals)
    )

  private val t = EraTx.dijkstra
  private val b = EraTxBody.dijkstra
  private val c = EraCertState.dijkstra
  private given BothLevels[TxLevel.Sub] = BothLevels.Sub

  private def injected[A](test: Test[A])(using
      inject: InjectSubEntitiesFailure[A, Dijkstra]
  ): Test[SubEntitiesRuleFailure[Dijkstra]] =
    test.leftMap(_.map(inject.injectFailure))

  /** `dijkstraSubEntitiesTransition` (`Dijkstra/Rules/SubEntities.hs:248-278`, cardano-ledger
    * 9df9f98c), every failure recorded and injected into SUBENTITIES's: the withdrawals' network,
    * the direct deposits' network, the balance intervals against the current accounts, the
    * withdrawals of an account the original accounts lack and of one the current accounts lack.
    * Then the DReps' expiries are updated for the sub-transaction and its withdrawals applied,
    * SUBCERTS runs as a sub-rule on that state, its failures and events embedded
    * (`SubEntities.hs:340-348`); the direct deposits are checked against the accounts after
    * SUBCERTS and added to them. Unlike ENTITIES, no withdrawal's amount is checked here, and the
    * withdrawals are applied whatever failed. Each account of the direct deposits and the balance
    * intervals is read into the reference's `AccountAddress`, which every decoded one is; a value
    * outside the vocabulary is raised (`LedgerFailure.Vocab`).
    *
    * `validation` is the policy the judgment runs under, which SUBPOOL's static check reads; a
    * voting key SUBPOOL cannot check is raised (`poolDefect`).
    */
  def dijkstraSubEntitiesTransition[F[_]: Monad](
      env: SubEntitiesEnv[Dijkstra],
      tx: Tx[TxLevel.Sub, Dijkstra],
      validation: TxEnv.Validation
  )(using
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[Dijkstra]],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      judge: Judge[F, SubEntitiesRuleFailure[Dijkstra]],
      events: Tell[F, Chain[SubEntitiesRuleEvent[Dijkstra]]]
  ): F[Unit] =
    val txBody = t.body[TxLevel.Sub](tx)
    val pp = env.pp
    val curEpoch = env.currentEpoch
    val accountsL = c.certDStateL.andThen(DState.accounts[Accounts[Dijkstra]])
    val subCertsEnv =
      SubCertsEnv(tx, pp, curEpoch, env.currentCommittee, env.committeeProposals)
    for
      certState <- st.get
      accounts = accountsL.get(certState)
      globals <- ask.ask[Globals]
      network = globals.networkId
      withdrawals = b.withdrawals[TxLevel.Sub](txBody)
      directDeposits <- vocab[F, SortedMap[AccountAddress, Coin]](
        ENTITIES.keyed(b.directDeposits[TxLevel.Sub](txBody))
      )
      intervals <- vocab[F, SortedMap[AccountAddress, AccountBalanceInterval]](
        ENTITIES.keyed(b.accountBalanceIntervals[TxLevel.Sub](txBody))
      )
      _ <- Sts.runTest[F, SubEntitiesRuleFailure[Dijkstra]](
        Test.all(
          ShelleyUtxo
            .validateWrongNetworkWithdrawal[Dijkstra, TxLevel.Sub](network, txBody)
            .leftMap(_.map(shelleyUtxoToDijkstraSubEntitiesPredFailure)),
          injected(ENTITIES.validateWrongNetworkInDirectDeposit(network, directDeposits)),
          injected(ENTITIES.validateAccountBalanceIntervals(network, accounts, intervals)),
          injected(
            validateMissingOriginalAccountsInWithdrawals(
              withdrawals,
              network,
              env.originalAccounts
            )
          ),
          injected(validateMissingAccountsInWithdrawals(withdrawals, network, accounts))
        )
      )
      voted <- vocab[F, CertState[Dijkstra]](
        CERTS.updateVotingDRepExpiries[Dijkstra, TxLevel.Sub](
          tx,
          curEpoch,
          field[Dijkstra, EpochInterval](pp)(EraPParams.dijkstra.hkdDRepActivityL[Identity]),
          CERTS.updateDormantDRepExpiries[Dijkstra, TxLevel.Sub](tx, curEpoch, certState)
        )
      )
      withdrawn <- vocab[F, Accounts[Dijkstra]](
        ENTITIES.applyWithdrawals(withdrawals, accounts)
      )
      _ <- st.set(accountsL.replace(withdrawn)(voted))
      _ <- judge.nest(
        DijkstraSUBCERTS.subCertsTransition[F](
          subCertsEnv,
          b.certs[TxLevel.Sub](txBody),
          validation
        )(using
          summon[Monad[F]],
          ask,
          st,
          defect,
          poolDefect,
          wrapped(judge)(SubEntitiesPredFailure.SubCertsFailure[Dijkstra](_)),
          told(events)(SubEntitiesEvent[Dijkstra](_))
        )
      )
      certStateAfterSubCerts <- st.get
      accountsAfterSubCerts = accountsL.get(certStateAfterSubCerts)
      _ <- Sts.runTest[F, SubEntitiesRuleFailure[Dijkstra]](
        injected(
          ENTITIES.validateMissingAccountsInDirectDeposits(
            directDeposits,
            network,
            accountsAfterSubCerts
          )
        )
      )
      deposited <- vocab[F, Accounts[Dijkstra]](
        ENTITIES.applyDirectDeposits(directDeposits, accountsAfterSubCerts)
      )
      _ <- st.set(accountsL.replace(deposited)(certStateAfterSubCerts))
    yield ()
