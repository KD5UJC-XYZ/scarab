// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import monocle.Lens
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Certificate,
  DelegFailure,
  GenDelegPair,
  LedgerFailure,
  Mismatch,
  Pot,
  PredicateFailure,
  ProtocolParams,
  Ptr,
  TxDefect
}
import xyz.kd5ujc.scarab.ledger.TxEnv
import xyz.kd5ujc.scarab.ledger.state.*

// DELEGS and the rules under it for the eras before Conway: `Shelley/Rules/Delegs.hs`, `Delpl.hs`,
// `Deleg.hs` and (the rule of every era) `Pool.hs`, which Alonzo and Babbage use unchanged
// (`Alonzo/Rules/Delegs.hs`, `Babbage/Rules/Delegs.hs` only name the failure types). They run on
// the Shelley certificate state (`ShelleyCertState`): the accounts with the pointer of the
// certificate that registered each, the pointer map, the pools with their delegators, the genesis
// delegations and the instantaneous rewards. A rule that fails raises the predicate failure the
// reference has for it (`ShelleyDelegPredFailure`, `Deleg.hs:89-120`; the cases of the eras
// before Conway are in the closed failure types the Conway rules use as well).

/** The lenses into the Shelley certificate state that its rules use. */
object ShelleyCertLens:
  val accounts: Lens[ShelleyCertState, ShelleyAccounts] =
    ShelleyCertState.dState.andThen(DState.accounts[ShelleyAccounts])

  val states: Lens[ShelleyCertState, SortedMap[Credential[Staking], ShelleyAccountState]] =
    accounts.andThen(ShelleyAccounts.states)

  val ptrs: Lens[ShelleyCertState, SortedMap[Ptr, Credential[Staking]]] =
    accounts.andThen(ShelleyAccounts.ptrs)

  val pools: Lens[ShelleyCertState, SortedMap[KeyHash[StakePool], StakePoolState]] =
    ShelleyCertState.pState.andThen(PState.stakePools)

  val genDelegs: Lens[ShelleyCertState, SortedMap[KeyHash[Genesis], GenDelegPair]] =
    ShelleyCertState.dState.andThen(DState.genDelegs[ShelleyAccounts])

  val futureGenDelegs: Lens[ShelleyCertState, SortedMap[FutureGenDeleg, GenDelegPair]] =
    ShelleyCertState.dState.andThen(DState.futureGenDelegs[ShelleyAccounts])

  val iRewards: Lens[ShelleyCertState, InstantaneousRewards] =
    ShelleyCertState.dState.andThen(DState.iRewards[ShelleyAccounts])

/** `DelegsEnv` (`Delegs.hs`): the block-level context of the transaction (the slot, the epoch, the
  * position in the block), the parameters in force, the transaction (its identity, for the
  * failures) and the chain account (the reserves and the treasury a MIR certificate draws on).
  */
final case class DelegsEnv(
    ctx: TxEnv,
    pp: ProtocolParams,
    id: Bytes,
    account: ChainAccountState
)

/** `DelegEnv` (`Deleg.hs`, `DelplEnv`): DELEGS' environment, with the pointer of the certificate
  * that DELEGS builds (`Ptr slot txIx certIx`, `Delegs.hs:169`).
  */
final case class ShelleyDelegEnv(delegs: DelegsEnv, ptr: Ptr)

/** The pure changes the certificates make to the Shelley certificate state, each the Haskell
  * function of the same name (cited).
  */
object ShelleyCertOps:
  private val zeroCoin: CompactCoin = CompactCoin.fromUnsigned(0L)

  /** `registerShelleyAccount` (`Shelley/State/Account.hs:206`): a new account with no balance and
    * no delegation, its pointer recorded in the pointer map.
    */
  def register(
      s: ShelleyCertState,
      c: Credential[Staking],
      ptr: Ptr,
      deposit: CompactCoin
  ): ShelleyCertState =
    ShelleyCertLens.accounts.modify(a =>
      a.copy(
        states = a.states.updated(c, ShelleyAccountState(ptr, zeroCoin, deposit, None)),
        ptrs = a.ptrs.updated(ptr, c)
      )
    )(s)

  /** `unregisterShelleyAccount` (`Account.hs:221`) and `unDelegReDelegStakePool`
    * (`State/CertState.hs:281`): the account and its pointer are removed, and the pool it delegated
    * to forgets it.
    */
  def unregister(
      s: ShelleyCertState,
      c: Credential[Staking],
      account: ShelleyAccountState
  ): ShelleyCertState =
    val removed = ShelleyCertLens.accounts.modify(a =>
      a.copy(states = a.states.removed(c), ptrs = a.ptrs.removed(account.ptr))
    )(s)
    ShelleyCertState.pState.modify(CertOps.reDelegatePool(_, c, account.pool, None))(removed)

  /** The delegation of the account to `pool`, and the pools' delegators with it. */
  def delegate(
      s: ShelleyCertState,
      c: Credential[Staking],
      account: ShelleyAccountState,
      pool: KeyHash[StakePool]
  ): ShelleyCertState =
    val moved = ShelleyCertLens.states.modify(_.updated(c, account.copy(pool = Some(pool))))(s)
    ShelleyCertState.pState.modify(CertOps.reDelegatePool(_, c, account.pool, Some(pool)))(moved)

  /** The accounts of `withdrawals` have nothing left (`drainAccounts`). */
  def drain(s: ShelleyCertState, withdrawals: Iterable[Credential[Staking]]): ShelleyCertState =
    withdrawals.foldLeft(s)((cs, c) =>
      ShelleyCertLens.states.modify(
        _.updatedWith(c)(_.map(ShelleyAccountState.balance.replace(zeroCoin)))
      )(
        cs
      )
    )

/** DELEG (`Shelley/Rules/Deleg.hs:252`, `delegationTransition`): registration, deregistration and
  * delegation of stake credentials, the delegation of a genesis key, and the instantaneous rewards.
  *
  * The signatures a MIR certificate needs of the genesis delegates are not this rule's: they are
  * UTXOW's (`validateMIRInsufficientGenesisSigs`, `Utxow.hs:449`), where the old node's rule
  * checked them (`ShelleyUtxow`). The deregistration of a key with a balance, the delegation to a
  * pool that is not registered and the duplicate genesis delegate and VRF key are the reference's;
  * the order of the failures within a certificate is its too.
  */
object ShelleyDeleg
    extends JudgedSts[
      ShelleyDelegEnv,
      ShelleyCertState,
      Certificate,
      PredicateFailure,
      LedgerFailure
    ]:
  def transition[F[_]: Monad](env: ShelleyDelegEnv, cert: Certificate)(using
      ask: Ask[F, Globals],
      st: Stateful[F, ShelleyCertState],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    val id = env.delegs.id
    val pp = env.delegs.pp
    val ctx = env.delegs.ctx
    def refused[A](f: DelegFailure): F[A] =
      (LedgerFailure.Rejected(id, PredicateFailure.deleg(f)): LedgerFailure).raise[F, A]
    def ensure(cond: Boolean, f: => DelegFailure): F[Unit] =
      Sts.check[F, LedgerFailure](cond, LedgerFailure.Rejected(id, PredicateFailure.deleg(f)))
    cert match
      case Certificate.Register(c, _) =>
        for
          cred <- Refuse.vocab[F, Credential[Staking]](Convert.credential[Staking](c))
          s <- st.get
          _ <- ensure(
            !ShelleyCertLens.states.get(s).contains(cred),
            DelegFailure.StakeKeyAlreadyRegistered(c)
          )
          deposit <- Refuse.vocab[F, CompactCoin](CompactCoin.fromBigInt(pp.keyDeposit))
          _ <- st.set(ShelleyCertOps.register(s, cred, env.ptr, deposit))
        yield ()
      case Certificate.Unregister(c, _) =>
        for
          cred <- Refuse.vocab[F, Credential[Staking]](Convert.credential[Staking](c))
          s <- st.get
          account <- ShelleyCertLens.states.get(s).get(cred) match
            case Some(a) => a.pure[F]
            case None    => refused[ShelleyAccountState](DelegFailure.StakeKeyNotRegistered(c))
          _ <- ensure(
            account.balance.word.isZero,
            DelegFailure.StakeKeyHasNonZeroAccountBalance(account.balance.toBigInt)
          )
          _ <- st.set(ShelleyCertOps.unregister(s, cred, account))
        yield ()
      case Certificate.Delegate(c, Some(pool), _, _) =>
        for
          cred <- Refuse.vocab[F, Credential[Staking]](Convert.credential[Staking](c))
          target <- Refuse.vocab[F, KeyHash[StakePool]](Convert.keyHash[StakePool](pool))
          s <- st.get
          _ <- ensure(
            ShelleyCertLens.pools.get(s).contains(target),
            DelegFailure.DelegateeNotRegistered(pool)
          )
          account <- ShelleyCertLens.states.get(s).get(cred) match
            case Some(a) => a.pure[F]
            case None    => refused[ShelleyAccountState](DelegFailure.StakeDelegationImpossible(c))
          _ <- st.set(ShelleyCertOps.delegate(s, cred, account, target))
        yield ()
      case Certificate.GenesisDelegate(g, delegate, vrf) =>
        for
          genesis <- Refuse.vocab[F, KeyHash[Genesis]](Convert.keyHash[Genesis](g))
          s <- st.get
          current = ShelleyCertLens.genDelegs.get(s)
          future = ShelleyCertLens.futureGenDelegs.get(s)
          _ <- ensure(current.contains(genesis), DelegFailure.GenesisKeyNotInMapping(g))
          others = current.removed(genesis).values ++
            future.collect { case (k, p) if k.genKeyHash != genesis => p }
          _ <- ensure(
            !others.exists(_.delegate == delegate),
            DelegFailure.DuplicateGenesisDelegate(delegate)
          )
          _ <- ensure(!others.exists(_.vrf == vrf), DelegFailure.DuplicateGenesisVRF(vrf))
          due <- Refuse.vocab[F, SlotNo](SlotNo.fromBigInt(ctx.slot + ctx.stabilityWindow))
          _ <- st.set(
            ShelleyCertLens.futureGenDelegs.modify(
              _.updated(FutureGenDeleg(due, genesis), GenDelegPair(delegate, vrf))
            )(s)
          )
        yield ()
      case Certificate.MoveRewards(pot, transfer) => moveRewards(env, pot, transfer)
      case other =>
        (LedgerFailure.Undecodable(id, TxDefect.ConwayCertificate(other)): LedgerFailure)
          .raise[F, Unit]

  /** The `MIRCert` of a transaction (`Deleg.hs:252-420`): to the reserves' or the treasury's
    * entries, or a transfer between the pots, from the pots as the chain account and the
    * instantaneous rewards so far have them.
    */
  private def moveRewards[F[_]: Monad](
      env: ShelleyDelegEnv,
      pot: Pot,
      transfer: Either[BigInt, Map[xyz.kd5ujc.scarab.ledger.Credential, BigInt]]
  )(using
      st: Stateful[F, ShelleyCertState],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    val id = env.delegs.id
    val ctx = env.delegs.ctx
    def refused[A](f: DelegFailure): F[A] =
      (LedgerFailure.Rejected(id, PredicateFailure.deleg(f)): LedgerFailure).raise[F, A]
    def ensure(cond: Boolean, f: => DelegFailure): F[Unit] =
      Sts.check[F, LedgerFailure](cond, LedgerFailure.Rejected(id, PredicateFailure.deleg(f)))
    // `hardforkAlonzoAllowMIRTransfer`: transfers between pots and negative entries from Alonzo
    val transfersAllowed = env.delegs.pp.protocolMajor > 4
    def total(m: SortedMap[Credential[Staking], Coin]): BigInt = m.valuesIterator.map(_.value).sum
    def coins(m: Map[Credential[Staking], BigInt]): F[SortedMap[Credential[Staking], Coin]] =
      Sorted.map(m.iterator.map((c, n) => c -> Coin(n)).toList).pure[F]
    def delta(d: DeltaCoin, by: BigInt): DeltaCoin = DeltaCoin(d.value + by)
    for
      s <- st.get
      ir = ShelleyCertLens.iRewards.get(s)
      account = env.delegs.account
      availableR = account.reserves.value + ir.deltaReserves.value
      availableT = account.treasury.value + ir.deltaTreasury.value
      // `checkSlotNotTooLate`
      tooLate = ctx.firstSlotNextEpoch - ctx.stabilityWindow
      _ <- ensure(
        ctx.slot < tooLate,
        DelegFailure.MIRCertificateTooLateinEpoch(Mismatch(ctx.slot, tooLate))
      )
      next <- transfer match
        case Left(_) if !transfersAllowed =>
          refused[InstantaneousRewards](DelegFailure.MIRTransferNotCurrentlyAllowed)
        case Left(coin) =>
          for
            _ <- ensure(coin >= 0, DelegFailure.MIRNegativeTransfer(pot, coin))
            result <- pot match
              case Pot.Reserves =>
                val available = availableR - total(ir.reserves)
                ensure(
                  coin <= available,
                  DelegFailure.InsufficientForTransfer(pot, Mismatch(coin, available))
                ).as(
                  ir.copy(
                    deltaReserves = delta(ir.deltaReserves, -coin),
                    deltaTreasury = delta(ir.deltaTreasury, coin)
                  )
                )
              case Pot.Treasury =>
                val available = availableT - total(ir.treasury)
                ensure(
                  coin <= available,
                  DelegFailure.InsufficientForTransfer(pot, Mismatch(coin, available))
                ).as(
                  ir.copy(
                    deltaReserves = delta(ir.deltaReserves, coin),
                    deltaTreasury = delta(ir.deltaTreasury, -coin)
                  )
                )
          yield result
        case Right(amounts) =>
          for
            credited <- Refuse.vocab[F, List[(Credential[Staking], BigInt)]](
              amounts.toList.traverse((c, n) => Convert.credential[Staking](c).map(_ -> n))
            )
            current = if pot == Pot.Reserves then ir.reserves else ir.treasury
            // before Alonzo a certificate replaces earlier entries of the same credential and may
            // not carry negative amounts; from Alonzo it adds to them and the sum may not go
            // negative
            combined =
              if transfersAllowed then
                credited.foldLeft(current.view.mapValues(_.value).toMap)((m, kv) =>
                  m.updated(kv._1, m.getOrElse(kv._1, BigInt(0)) + kv._2)
                )
              else current.view.mapValues(_.value).toMap ++ credited
            _ <-
              if transfersAllowed then
                ensure(combined.values.forall(_ >= 0), DelegFailure.MIRProducesNegativeUpdate)
              else ensure(credited.forall(_._2 >= 0), DelegFailure.MIRNegativesNotCurrentlyAllowed)
            available = if pot == Pot.Reserves then availableR else availableT
            _ <- ensure(
              combined.values.sum <= available,
              DelegFailure.InsufficientForInstantaneousRewards(
                pot,
                Mismatch(combined.values.sum, available)
              )
            )
            entries <- coins(combined)
          yield
            if pot == Pot.Reserves then ir.copy(reserves = entries)
            else ir.copy(treasury = entries)
      _ <- st.set(ShelleyCertLens.iRewards.replace(next)(s))
    yield ()

/** DELPL (`Shelley/Rules/Delpl.hs:174`, `delplTransition`, cardano-ledger `f649f975`): a
  * certificate goes to the rule of its family (`trans`, `Delpl.hs:193-202`): POOL for a pool's
  * registration and retirement (POOL sees the pool state only), DELEG for the rest.
  */
object ShelleyDelpl
    extends JudgedSts[
      ShelleyDelegEnv,
      ShelleyCertState,
      Certificate,
      PredicateFailure,
      LedgerFailure
    ]:
  given poolEmbed: Embed[PState, ShelleyCertState, LedgerFailure, LedgerFailure] =
    Embed(ShelleyCertState.pState, identity)

  def transition[F[_]: Monad](env: ShelleyDelegEnv, cert: Certificate)(using
      ask: Ask[F, Globals],
      st: Stateful[F, ShelleyCertState],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    val delegs = env.delegs
    // the pool rule's environment: no committee and no proposals before Conway
    val poolEnv = CertEnv(delegs.id, delegs.pp, delegs.ctx.epoch, None, Map.empty)
    def pool(c: PoolCert): F[Unit] =
      JudgedSts.trans[
        F,
        CertEnv,
        PState,
        ShelleyCertState,
        PoolCert,
        PredicateFailure,
        LedgerFailure,
        LedgerFailure
      ](Pool, poolEnv, c)
    cert match
      case Certificate.RegisterPool(p) => pool(PoolCert.RegPool(p))
      case Certificate.RetirePool(p, e) =>
        Refuse
          .vocab[F, KeyHash[StakePool]](Convert.keyHash[StakePool](p))
          .flatMap(h => pool(PoolCert.RetirePool(h, e)))
      case _ =>
        JudgedSts.trans[
          F,
          ShelleyDelegEnv,
          ShelleyCertState,
          ShelleyCertState,
          Certificate,
          PredicateFailure,
          LedgerFailure,
          LedgerFailure
        ](ShelleyDeleg, env, cert)

/** DELEGS (`Shelley/Rules/Delegs.hs:169`, `delegsTransition`, cardano-ledger `f649f975`): the
  * certificates of a transaction, by recursion on their prefix (`gamma :|> c`,
  * `Delegs.hs:189-198`): DELEGS of all but the last certificate, then DELPL of the last with the
  * pointer of its position (`Ptr slot txIx certIx`, `certIx` the length of the prefix), each a
  * sub-rule (`trans`), so the first certificate is the deepest. The recursion is a fold over the
  * certificates, each step a program over the previous (ADR 0011).
  */
object ShelleyDelegs
    extends JudgedSts[DelegsEnv, ShelleyCertState, Vector[
      Certificate
    ], PredicateFailure, LedgerFailure]:
  def transition[F[_]: Monad](env: DelegsEnv, certs: Vector[Certificate])(using
      ask: Ask[F, Globals],
      st: Stateful[F, ShelleyCertState],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    certs.zipWithIndex.foldLeft(Monad[F].unit) { case (prefix, (cert, ix)) =>
      judge.trans(prefix) *>
        JudgedSts.trans[
          F,
          ShelleyDelegEnv,
          ShelleyCertState,
          ShelleyCertState,
          Certificate,
          PredicateFailure,
          LedgerFailure,
          LedgerFailure
        ](
          ShelleyDelpl,
          ShelleyDelegEnv(env, Ptr(env.ctx.slot, env.ctx.txIndex, ix)),
          cert
        )
    }
