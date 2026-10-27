// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.state.*

/** HARDFORK (`Conway/Rules/HardFork.hs:68`): the rule that runs at the epoch boundary where the
  * protocol version changed.
  *
  *   - Entering protocol version 10 (the end of the bootstrap phase) it rebuilds every DRep's index
  *     of delegators from the accounts and drops the delegations to DReps that are not registered
  *     (`updateDRepDelegations`).
  *   - Entering 11 it populates the VRF key hashes of the pools (`populateVRFKeyHashes`,
  *     `HardFork.hs:107`), which POOL keeps from then on ([[Pool]]). The node derived them from
  *     version 10 (`LedgerMirror`); a state the new rules carry across the fork needs them here
  *     (J2.4; the rest of J3.3, the VRF key hashes in the node's own checks, stays).
  */
object HardFork:
  /** `updateDRepDelegations` (`HardFork.hs:82`). */
  def updateDRepDelegations(cert: ConwayCertState): ConwayCertState =
    val reset: SortedMap[Credential[DRepRole], DRepState] =
      Sorted.map(
        cert.vState.dreps.iterator.map((c, d) => c -> d.copy(delegators = Sorted.emptySet))
      )
    // each account in the order of its credential; one that names an unknown DRep loses it
    val (dreps, accounts) =
      cert.dState.accounts.states.iterator.foldLeft((reset, cert.dState.accounts.states)) {
        case (acc @ (ds, as), (credential, account)) =>
          account.drep match
            case Some(DRep.DRepKeyHash(h)) =>
              register(ds, as, credential, account, Credential.KeyHashObj[DRepRole](h))
            case Some(DRep.DRepScriptHash(h)) =>
              register(ds, as, credential, account, Credential.ScriptHashObj[DRepRole](h))
            case _ => acc
      }
    cert.copy(
      vState = cert.vState.copy(dreps = dreps),
      dState = cert.dState.copy(accounts = cert.dState.accounts.copy(states = accounts))
    )

  private def register(
      dreps: SortedMap[Credential[DRepRole], DRepState],
      accounts: SortedMap[Credential[Staking], ConwayAccountState],
      credential: Credential[Staking],
      account: ConwayAccountState,
      drep: Credential[DRepRole]
  ): (
      SortedMap[Credential[DRepRole], DRepState],
      SortedMap[Credential[Staking], ConwayAccountState]
  ) =
    dreps.get(drep) match
      case Some(state) =>
        (dreps.updated(drep, state.copy(delegators = state.delegators + credential)), accounts)
      case None => (dreps, accounts.updated(credential, account.copy(drep = None)))

  /** `populateVRFKeyHashes` (`HardFork.hs:107`): the count of each VRF key hash is the number of
    * pools that use it, registered ones and re-registrations that wait for the boundary, each
    * counted. The map starts empty (the reference has none before version 11; a state converted
    * from the node's, which derived it from 10, is taken to the same).
    */
  def populateVRFKeyHashes(pools: PState): PState =
    def add(
        counts: SortedMap[VRFVerKeyHash, NonZero[Word64]],
        vrf: VRFVerKeyHash
    ): SortedMap[VRFVerKeyHash, NonZero[Word64]] =
      counts.get(vrf) match
        case None => counts.updated(vrf, NonZero.oneWord)
        // saturates at the largest word, where adding one would reach zero
        case Some(n) =>
          NonZero
            .from(Word64.fromUnsigned(n.value.bits + 1))
            .fold(_ => counts, counts.updated(vrf, _))
    val registered = pools.stakePools.valuesIterator.map(_.vrf)
    val pending =
      pools.future.valuesIterator.flatMap(f => VRFVerKeyHash.fromBytes(f.vrfKeyHash).toOption)
    pools.copy(vrfKeyHashes =
      (registered ++ pending).foldLeft(Sorted.empty[VRFVerKeyHash, NonZero[Word64]])(add)
    )

  /** `hardforkTransition` (`HardFork.hs:68`). */
  def apply(es: EpochState[Conway], newVersion: Int): EpochState[Conway] =
    if newVersion == 10 then
      es.copy(ls = es.ls.copy[Conway](cert = updateDRepDelegations(es.ls.cert)))
    else if newVersion == 11 then
      es.copy(ls =
        es.ls.copy[Conway](cert = es.ls.cert.copy(pState = populateVRFKeyHashes(es.ls.cert.pState)))
      )
    else es

/** EPOCH of Conway (`Conway/Rules/Epoch.hs:244,269`): the boundary of an epoch, after the rewards
  * are applied: the new snapshot (SNAP) and the retiring pools (POOLREAP), then the ratification
  * the DRep pulser reached is enacted: treasury withdrawals reach the accounts, the enacted and the
  * expired proposals are removed and their deposits returned, the committee and the constitution
  * change and the parameters of the next epoch take effect. The deposits are counted again, the
  * hard fork rule runs if the protocol version changed, and a fresh pulser starts.
  */
object ConwayEpoch:
  /** `predictFuturePParams` (`Conway/Governance.hs:299`): while the future parameters are only
    * `Potential`, they are the parameters the pulser's ratification enacts, if it enacts a change
    * of parameters or a hard fork. The pulser is complete here, so it is not forced.
    */
  def predictFuturePParams(g: ConwayGovState): ConwayGovState = g.futurePParams match
    case FuturePParams.PotentialPParamsUpdate(_) =>
      val ratify = g.drepPulsing.ratify
      val changes = ratify.enacted.exists(gas =>
        gas.action match
          case _: node.GovAction.ParameterChange | _: node.GovAction.HardFork => true
          case _                                                              => false
      )
      g.copy(futurePParams =
        FuturePParams.PotentialPParamsUpdate(Option.when(changes)(ratify.enact.params))
      )
    case _ => g

  def sts: Sts[Unit, EpochState[Conway], EpochNo, TickFailure] =
    new Sts[Unit, EpochState[Conway], EpochNo, TickFailure]:
      def transition[F[_]: Monad](env: Unit, epoch: EpochNo)(using
          ask: Ask[F, Globals],
          es: Stateful[F, EpochState[Conway]],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        for
          es0 <- es.get
          globals <- Sts.globals[F]
          _ <- Epoch.within(Snap.sts[Conway], es0.ls, (), EpochState.snapshots[Conway])
          _ <- Epoch.within(PoolReap.sts[Conway], (), epoch, PoolreapState.lens[Conway])
          es1 <- es.get
          next <- Sts.lift[F, TickFailure, EpochState[Conway]](
            enact(globals, epoch, es0, es1).leftMap(TickFailure.Vocab(_))
          )
          _ <- es.set(next)
        yield ()

  /** The deposit pot implied by the complete certificate and proposal state. Snapshot interchange
    * uses the same obligation as the epoch rule when checking reconstructed state.
    */
  def totalObligation(ls: LedgerState[Conway]): BigInt =
    EraLedger.keyDeposits(ls) +
      CompactCoin.sum(ls.cert.pState.stakePools.valuesIterator.map(_.deposit)).toBigInt +
      CompactCoin.sum(ls.cert.vState.dreps.valuesIterator.map(_.deposit)).toBigInt +
      ls.utxo.gov.proposals.totalDeposits

  /** The rest of the rule once SNAP and POOLREAP have run: `es0` is the state the rule began with
    * (its pulser, governance state and donations), `es1` the one after the two sub-rules.
    */
  private def enact(
      globals: Globals,
      epoch: EpochNo,
      es0: EpochState[Conway],
      es1: EpochState[Conway]
  ): Either[VocabError, EpochState[Conway]] =
    val gov0 = es0.ls.utxo.gov
    val ratify = gov0.drepPulsing.ratify
    val enacted = ratify.enact
    val ls1 = es1.ls
    // treasury withdrawals reach the accounts that are still registered (`applyEnactedWithdrawals`)
    val registered =
      enacted.withdrawals.toVector.flatMap((c, n) =>
        Convert.credential[Staking](c).toOption.map(_ -> n)
      )
    val successful = registered.filter((c, _) => EraLedger.isRegistered(ls1, c))
    val (proposals, competing, expired) =
      gov0.proposals.applyEnactment(ratify.enacted, ratify.expired)
    val removed = ratify.enacted ++ competing ++ expired
    val adopted = gov0.futurePParams.definite.getOrElse(gov0.curPParams)
    val gov1 = gov0.copy(
      proposals = proposals,
      committee = enacted.committee,
      constitution = enacted.constitution,
      curPParams = adopted,
      prevPParams = gov0.curPParams,
      futurePParams = FuturePParams.PotentialPParamsUpdate(None)
    )
    for
      withdrawn <- successful.traverse((c, n) => CompactCoin.fromCoin(Coin(n)).map(c -> _))
      withdrawnTotal = CompactCoin.sum(withdrawn.iterator.map(_._2)).toBigInt
      afterWithdrawals = EraLedger.credit(ls1, withdrawn.toMap)
      // proposal deposits go back to their accounts, or to the treasury where there is none
      refunds <- removed.traverse { gas =>
        Convert
          .credential[Staking](gas.proposal.returnAccount.credential)
          .map(c => c -> Coin(gas.proposal.deposit))
      }
      (refundable, unclaimedRefunds) = refunds.partition((c, _) =>
        EraLedger.isRegistered(afterWithdrawals, c)
      )
      compactRefunds <- refundable.traverse((c, n) => CompactCoin.fromCoin(n).map(c -> _))
      refunded = EraLedger.credit(
        afterWithdrawals,
        compactRefunds.groupMapReduce(_._1)(_._2)((a, b) => a.add(b))
      )
      unclaimed = unclaimedRefunds.foldLeft(BigInt(0))(_ + _._2.value)
      dormant <- {
        val active = proposals.actions.exists(epoch.toBigInt <= _.expiresAfter)
        if active then Right(refunded.cert.vState.numDormantEpochs)
        else EpochNo.fromBigInt(refunded.cert.vState.numDormantEpochs.toBigInt + 1)
      }
      committeeState = refunded.cert.vState.committeeState.creds.filter((cold, _) =>
        enacted.committee.exists(_.members.contains(Convert.back(cold)))
      )
      treasury = Coin(
        es1.chainAccount.treasury.value - withdrawnTotal + es0.ls.utxo.donation.value + unclaimed
      )
      cert2 = refunded.cert.copy(vState =
        refunded.cert.vState.copy(
          committeeState = CommitteeState(committeeState),
          numDormantEpochs = dormant
        )
      )
      ls2 = LedgerState[Conway](cert2, ls1.utxo.copy(gov = gov1))
      deposited = Coin(totalObligation(ls2))
      ls3 = ls2.copy[Conway](utxo = ls2.utxo.copy(deposited = deposited, donation = Coin.zero))
      es2 = es1.copy(chainAccount = es1.chainAccount.copy(treasury = treasury), ls = ls3)
    yield
      // the hard fork rule, if the protocol version changed
      val forked =
        if gov1.curPParams.version != gov1.prevPParams.version then
          HardFork(es2, gov1.curPParams.protocolMajor.toInt)
        else es2
      val pulser = ConwayPulser.fresh(globals, forked, epoch)
      forked.copy(ls =
        forked.ls.copy[Conway](utxo =
          forked.ls.utxo
            .copy(gov = predictFuturePParams(forked.ls.utxo.gov.copy(drepPulsing = pulser)))
        )
      )
