// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Order
import monocle.Lens
import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.collection.mutable
import xyz.kd5ujc.scarab.ledger.{GenDelegPair, Ptr, ProtocolParams}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.given

/** What the epoch rules do to the ledger state of any Shelley-based era, by the era witness.
  *
  * Haskell's rules are written once over `era` with the type classes `EraCertState`, `EraAccounts`,
  * `EraGov` and `EraStake` (`Cardano.Ledger.State`, `Cardano.Ledger.Core`), which choose the record
  * of the era. Here the records are the match types of `Eras.scala`, and a rule that is generic
  * over `E` reaches them through these functions: each is one match on the witness, in whose
  * branches the families reduce, so there is no cast. Nothing here is a rule; each is the Haskell
  * class method of the same meaning (cited on it).
  */
object EraLedger:

  /** One stake account as every era's accounts see it (`balanceAccountStateL`,
    * `depositAccountStateL`, `stakePoolDelegationAccountStateL`, `State/Account.hs`).
    */
  final case class AccountView(
      balance: CompactCoin,
      deposit: CompactCoin,
      pool: Option[KeyHash[StakePool]]
  )

  // ---- the two families of certificate state and of governance state

  private def foldCert[E <: EraTag, A](c: CertState[E])(
      shelley: ShelleyCertState => A,
      conway: ConwayCertState => A
  )(using era: ShelleyBasedEra[E]): A = era.cert.on(c)(shelley, conway)

  private def mapCert[E <: EraTag](c: CertState[E])(
      shelley: ShelleyCertState => ShelleyCertState,
      conway: ConwayCertState => ConwayCertState
  )(using era: ShelleyBasedEra[E]): CertState[E] = era.cert.update(c)(shelley, conway)

  private def foldGov[E <: EraTag, A](g: GovState[E])(
      shelley: ShelleyGovState => A,
      conway: ConwayGovState => A
  )(using era: ShelleyBasedEra[E]): A = era.gov.on(g)(shelley, conway)

  private def mapGov[E <: EraTag](g: GovState[E])(
      shelley: ShelleyGovState => ShelleyGovState,
      conway: ConwayGovState => ConwayGovState
  )(using era: ShelleyBasedEra[E]): GovState[E] = era.gov.update(g)(shelley, conway)

  private def onCert[E <: EraTag](ls: LedgerState[E])(
      shelley: ShelleyCertState => ShelleyCertState,
      conway: ConwayCertState => ConwayCertState
  )(using ShelleyBasedEra[E]): LedgerState[E] =
    ls.copy[E](cert = mapCert[E](ls.cert)(shelley, conway))

  // ---- the pools and the delegation state (`certPStateL`, `certDStateL`)

  /** `certPStateL`. */
  def pState[E <: EraTag](ls: LedgerState[E])(using ShelleyBasedEra[E]): PState =
    foldCert[E, PState](ls.cert)(_.pState, _.pState)

  def withPState[E <: EraTag](ls: LedgerState[E], p: PState)(using
      ShelleyBasedEra[E]
  ): LedgerState[E] = onCert(ls)(_.copy(pState = p), _.copy(pState = p))

  /** `dsIRewards`. */
  def iRewards[E <: EraTag](ls: LedgerState[E])(using ShelleyBasedEra[E]): InstantaneousRewards =
    foldCert[E, InstantaneousRewards](ls.cert)(_.dState.iRewards, _.dState.iRewards)

  def withIRewards[E <: EraTag](ls: LedgerState[E], ir: InstantaneousRewards)(using
      ShelleyBasedEra[E]
  ): LedgerState[E] =
    onCert(ls)(
      c => c.copy(dState = c.dState.copy(iRewards = ir)),
      c => c.copy(dState = c.dState.copy(iRewards = ir))
    )

  /** `dsGenDelegs`. */
  def genDelegs[E <: EraTag](ls: LedgerState[E])(using
      ShelleyBasedEra[E]
  ): SortedMap[KeyHash[Genesis], GenDelegPair] =
    foldCert[E, SortedMap[KeyHash[Genesis], GenDelegPair]](ls.cert)(
      _.dState.genDelegs,
      _.dState.genDelegs
    )

  /** `dsFutureGenDelegs`. */
  def futureGenDelegs[E <: EraTag](ls: LedgerState[E])(using
      ShelleyBasedEra[E]
  ): SortedMap[FutureGenDeleg, GenDelegPair] =
    foldCert[E, SortedMap[FutureGenDeleg, GenDelegPair]](ls.cert)(
      _.dState.futureGenDelegs,
      _.dState.futureGenDelegs
    )

  def withGenDelegs[E <: EraTag](
      ls: LedgerState[E],
      current: SortedMap[KeyHash[Genesis], GenDelegPair],
      future: SortedMap[FutureGenDeleg, GenDelegPair]
  )(using ShelleyBasedEra[E]): LedgerState[E] =
    onCert(ls)(
      c => c.copy(dState = c.dState.copy(genDelegs = current, futureGenDelegs = future)),
      c => c.copy(dState = c.dState.copy(genDelegs = current, futureGenDelegs = future))
    )

  // ---- the accounts (`EraAccounts`, `State/Account.hs`)

  /** `isAccountRegistered` (`State/Account.hs`). */
  def isRegistered[E <: EraTag](ls: LedgerState[E], c: Credential[Staking])(using
      ShelleyBasedEra[E]
  ): Boolean =
    foldCert[E, Boolean](ls.cert)(
      _.dState.accounts.states.contains(c),
      _.dState.accounts.states.contains(c)
    )

  /** Whether a credential is registered, for credentials asked in ascending order (see
    * [[Sorted.Cursor]]): the accounts are read once beside the credentials rather than looked up
    * for each.
    */
  def registeredAscending[E <: EraTag](ls: LedgerState[E])(using
      ShelleyBasedEra[E]
  ): Credential[Staking] => Boolean =
    foldCert[E, Credential[Staking] => Boolean](ls.cert)(
      c => Sorted.Cursor(c.dState.accounts.states).at(_).isDefined,
      c => Sorted.Cursor(c.dState.accounts.states).at(_).isDefined
    )

  /** The frozen registration set shares the persistent accounts tree's ordered key view. */
  def registered[E <: EraTag](ls: LedgerState[E])(using
      ShelleyBasedEra[E]
  ): scala.collection.immutable.SortedSet[Credential[Staking]] =
    foldCert[E, scala.collection.immutable.SortedSet[Credential[Staking]]](ls.cert)(
      _.dState.accounts.states.keySet,
      _.dState.accounts.states.keySet
    )

  /** The accounts, in the order of their credential. */
  def accounts[E <: EraTag](ls: LedgerState[E])(using
      ShelleyBasedEra[E]
  ): Iterator[(Credential[Staking], AccountView)] =
    foldCert[E, Iterator[(Credential[Staking], AccountView)]](ls.cert)(
      _.dState.accounts.states.iterator
        .map((c, a) => c -> AccountView(a.balance, a.deposit, a.pool)),
      _.dState.accounts.states.iterator
        .map((c, a) => c -> AccountView(a.balance, a.deposit, a.pool))
    )

  /** The sum of the deposits the accounts hold (`obligationCertState`'s key deposits). */
  def keyDeposits[E <: EraTag](ls: LedgerState[E])(using ShelleyBasedEra[E]): BigInt =
    foldCert[E, BigInt](ls.cert)(
      c => CompactCoin.sum(c.dState.accounts.states.valuesIterator.map(_.deposit)).toBigInt,
      c => CompactCoin.sum(c.dState.accounts.states.valuesIterator.map(_.deposit)).toBigInt
    )

  /** `addToBalanceAccounts` (`State/Account.hs:131–147`): compact balances add modulo 2^64. Callers
    * perform their own reference conversion before this operation and supply registered account
    * keys. A missing account is left absent, as in the reference's non-asserting build.
    */
  def credit[E <: EraTag](
      ls: LedgerState[E],
      amounts: Map[Credential[Staking], CompactCoin]
  )(using ShelleyBasedEra[E]): LedgerState[E] =
    def shelley(c: ShelleyCertState) =
      val states = creditStates(c.dState.accounts.states, ShelleyAccountState.balance, amounts)
      c.copy(dState = c.dState.copy(accounts = c.dState.accounts.copy(states = states)))
    def conway(c: ConwayCertState) =
      val states = creditStates(c.dState.accounts.states, ConwayAccountState.balance, amounts)
      c.copy(dState = c.dState.copy(accounts = c.dState.accounts.copy(states = states)))
    onCert(ls)(shelley, conway)

  private[rules] def creditStates[A](
      states: SortedMap[Credential[Staking], A],
      balance: Lens[A, CompactCoin],
      amounts: Iterable[(Credential[Staking], CompactCoin)]
  ): SortedMap[Credential[Staking], A] =
    amounts.foldLeft(states) { case (acc, (credential, by)) =>
      acc.updatedWith(credential)(_.map(a => balance.replace(balance.get(a).add(by))(a)))
    }

  /** [[credit]] for an ordered map (the rewards of the boundary): the accounts and amounts are
    * walked together and the map is built once. Both this path and the sparse path perform the same
    * native compact addition.
    */
  def creditAscending[E <: EraTag](
      ls: LedgerState[E],
      amounts: SortedMap[Credential[Staking], CompactCoin]
  )(using ShelleyBasedEra[E]): LedgerState[E] =
    if amounts.isEmpty then ls
    else if amounts.size * SparseFactor < accountCount(ls) then credit(ls, amounts)
    else
      val ascending = amounts.toVector
      def shelley(c: ShelleyCertState) =
        val states = mergeStates(c.dState.accounts.states, ShelleyAccountState.balance, ascending)
        c.copy(dState = c.dState.copy(accounts = c.dState.accounts.copy(states = states)))
      def conway(c: ConwayCertState) =
        val states = mergeStates(c.dState.accounts.states, ConwayAccountState.balance, ascending)
        c.copy(dState = c.dState.copy(accounts = c.dState.accounts.copy(states = states)))
      onCert(ls)(shelley, conway)

  /** Fewer amounts than one in this many accounts are credited one by one. */
  private val SparseFactor = 24

  private def accountCount[E <: EraTag](ls: LedgerState[E])(using ShelleyBasedEra[E]): Int =
    foldCert[E, Int](ls.cert)(_.dState.accounts.states.size, _.dState.accounts.states.size)

  private[rules] def mergeStates[A](
      states: SortedMap[Credential[Staking], A],
      balance: Lens[A, CompactCoin],
      amounts: scala.collection.IndexedSeq[(Credential[Staking], CompactCoin)]
  ): SortedMap[Credential[Staking], A] =
    val order = summon[Order[Credential[Staking]]]
    val out = mutable.ArrayBuffer.empty[(Credential[Staking], A)]
    out.sizeHint(states.size)
    @tailrec def go(accounts: Iterator[(Credential[Staking], A)], i: Int): Unit =
      if accounts.hasNext then
        val (credential, account) = accounts.next()
        var j = i
        while j < amounts.size && order.lt(amounts(j)._1, credential) do j += 1
        if j < amounts.size && order.eqv(amounts(j)._1, credential) then
          out += credential -> balance.replace(balance.get(account).add(amounts(j)._2))(account)
          go(accounts, j + 1)
        else
          out += credential -> account
          go(accounts, j)
    go(states.iterator, 0)
    Sorted.fromAscending(out)

  /** `removeStakePoolDelegations` (`State/Account.hs`): the accounts of `delegators` delegate to no
    * pool.
    */
  def clearPoolDelegations[E <: EraTag](
      ls: LedgerState[E],
      delegators: Iterable[Credential[Staking]]
  )(using ShelleyBasedEra[E]): LedgerState[E] =
    def clear[A](
        states: SortedMap[Credential[Staking], A],
        pool: Lens[A, Option[KeyHash[StakePool]]]
    ) =
      delegators.foldLeft(states)((s, d) => s.updatedWith(d)(_.map(pool.replace(None))))
    onCert(ls)(
      c =>
        c.copy(dState =
          c.dState.copy(accounts =
            c.dState.accounts
              .copy(states = clear(c.dState.accounts.states, ShelleyAccountState.pool))
          )
        ),
      c =>
        c.copy(dState =
          c.dState.copy(accounts =
            c.dState.accounts
              .copy(states = clear(c.dState.accounts.states, ConwayAccountState.pool))
          )
        )
    )

  /** `resolveInstantStake` (`State/Stake.hs:220`, `Shelley/State/Stake.hs:148`, `Conway/State/
    * Stake.hs:123`): the stake of the registered accounts that delegate to a pool, from the instant
    * stake of the UTxO and the balance of the account, with (before Conway) the stake of the
    * pointer addresses added to the account that the pointer names.
    */
  def activeStake[E <: EraTag](ls: LedgerState[E])(using
      era: ShelleyBasedEra[E]
  ): ActiveStake = era.shapes.fold(
    shelley =>
      val stake = shelley.instantStake(ls.utxo.instantStake)
      StakeDistribution.resolve(
        stake.credentialStake,
        accounts(ls),
        shelley.cert(ls.cert).dState.accounts.ptrs,
        stake.ptrStake
      ),
    conway =>
      StakeDistribution.resolve(
        conway.instantStake(ls.utxo.instantStake).credentialStake,
        accounts(ls),
        Sorted.empty[Ptr, Credential[Staking]],
        Sorted.empty[Ptr, CompactCoin]
      )
  )

  // ---- the governance state (`EraGov`, `State/Governance.hs`)

  /** `curPParamsGovStateL`. */
  def curPParams[E <: EraTag](g: GovState[E])(using ShelleyBasedEra[E]): ProtocolParams =
    foldGov[E, ProtocolParams](g)(_.curPParams, _.curPParams)

  /** `prevPParamsGovStateL`. */
  def prevPParams[E <: EraTag](g: GovState[E])(using ShelleyBasedEra[E]): ProtocolParams =
    foldGov[E, ProtocolParams](g)(_.prevPParams, _.prevPParams)

  /** `futurePParamsGovStateL`. */
  def futurePParams[E <: EraTag](g: GovState[E])(using ShelleyBasedEra[E]): FuturePParams =
    foldGov[E, FuturePParams](g)(_.futurePParams, _.futurePParams)

  /** `predictFuturePParams` (`Conway/Governance.hs:299`) on the governance state of any era:
    * Conway's, and the identity before it (`Shelley/Rules/NewEpoch.hs`: a tick within an epoch
    * changes nothing).
    */
  def predictFuture[E <: EraTag](g: GovState[E])(using ShelleyBasedEra[E]): GovState[E] =
    mapGov(g)(identity, ConwayEpoch.predictFuturePParams)

  /** The part of the governance state that UPEC works on: the proposed updates and the parameters.
    * Before Conway it is the governance state itself; Conway has no proposed updates, so its view
    * has none and writing the view back changes only the parameters.
    */
  def shelleyGov[E <: EraTag](using era: ShelleyBasedEra[E]): Lens[GovState[E], ShelleyGovState] =
    Lens[GovState[E], ShelleyGovState](g =>
      foldGov[E, ShelleyGovState](g)(
        identity,
        c =>
          ShelleyGovState(
            Sorted.empty,
            Sorted.empty,
            c.curPParams,
            c.prevPParams,
            c.futurePParams
          )
      )
    )(v =>
      g =>
        mapGov[E](g)(
          _ => v,
          _.copy(
            curPParams = v.curPParams,
            prevPParams = v.prevPParams,
            futurePParams = v.futurePParams
          )
        )
    )

  /** The parameters in force, the previous ones and the future ones, changed by `f` (Haskell's
    * `translateEra'` of each).
    */
  def mapParams[E <: EraTag](g: GovState[E])(f: ProtocolParams => ProtocolParams)(using
      ShelleyBasedEra[E]
  ): GovState[E] =
    withPParams(g, f(curPParams(g)), f(prevPParams(g)), FuturePParams.map(futurePParams(g))(f))

  def withPParams[E <: EraTag](
      g: GovState[E],
      cur: ProtocolParams,
      prev: ProtocolParams,
      future: FuturePParams
  )(using ShelleyBasedEra[E]): GovState[E] =
    mapGov(g)(
      _.copy(curPParams = cur, prevPParams = prev, futurePParams = future),
      _.copy(curPParams = cur, prevPParams = prev, futurePParams = future)
    )

  def withFuture[E <: EraTag](g: GovState[E], future: FuturePParams)(using
      ShelleyBasedEra[E]
  ): GovState[E] = mapGov(g)(_.copy(futurePParams = future), _.copy(futurePParams = future))

  /** `solidifyFuturePParams` (`State/Governance.hs:135`). */
  def solidify(f: FuturePParams): FuturePParams = f match
    case FuturePParams.PotentialPParamsUpdate(None)    => FuturePParams.NoPParamsUpdate
    case FuturePParams.PotentialPParamsUpdate(Some(p)) => FuturePParams.DefinitePParamsUpdate(p)
    case other                                         => other
