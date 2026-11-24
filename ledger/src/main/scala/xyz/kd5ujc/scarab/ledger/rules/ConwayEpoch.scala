// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** HARDFORK (`Conway/Rules/HardFork.hs:68-80`, cardano-ledger f649f975; 65-77 at 29a7d656 and
  * 9df9f98c): the rule that runs at the epoch boundary where the protocol version changed, a change
  * of the minor version included (`Conway/Rules/Epoch.hs:367-371` at f649f975, 371-375 at
  * 29a7d656). What it does depends on the major version of the new one:
  *
  *   - 10 (entering it ends the bootstrap phase): it rebuilds every DRep's index of delegators from
  *     the accounts and drops the delegations to DReps that are not registered
  *     (`updateDRepDelegations`).
  *   - 11: it populates the VRF key hashes of the pools ([[populateVRFKeyHashes]], where the builds
  *     differ), which POOL keeps from then on ([[Pool]]). The retired flat conversion derived them
  *     from version 10; a state the new rules carry across the fork needs them here (J2.4; the rest
  *     of J3.3, the VRF key hashes in the node's own checks, stays).
  */
object HardFork:
  /** `updateDRepDelegations` (`HardFork.hs:82`, cardano-ledger f649f975). */
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

  /** `populateVRFKeyHashes`, which HARDFORK applies to the pools at every change of protocol
    * version whose new major version is 11 (`HardFork.hs:77-78` at f649f975, 74-75 at 29a7d656 and
    * 9df9f98c): the first version of 11 and every later one, a new minor version among them. Each
    * count saturates at the largest word. The builds count differently:
    *
    *   - 11.1 (cardano-ledger f649f975, `Conway/Rules/HardFork.hs:107-125`): one more for each
    *     registered pool and one more for each re-registration waiting for the boundary, added to
    *     the counts the map already has. The map is empty until the first version of 11 (POOL keeps
    *     it only from 11, `hardforkConwayDisallowDuplicatedVRFKeys`, `Shelley/Era.hs:259-262`; the
    *     genesis registers none), so that first change counts each pool once; each later change at
    *     11 counts every registered pool once more, and POOLREAP takes only one count for a pool
    *     that retires (`Shelley/Rules/PoolReap.hs:224-237`), leaving its key hash in the map.
    *   - 11.2 (cardano-ledger 29a7d656) and the Leios build (cardano-ledger 9df9f98c): the counts
    *     recomputed from nothing, a waiting re-registration counted only where its key hash differs
    *     from its pool's (`State/CertState.hs:525-535` at 29a7d656, 528-538 at 9df9f98c;
    *     [[Translate.populateVRFKeyHashes]]).
    */
  def populateVRFKeyHashes(build: ReferenceBuild, pools: PState): PState =
    build match
      case ReferenceBuild.Release11_1 =>
        val registered = pools.stakePools.valuesIterator.map(_.vrf)
        val pending = pools.future.valuesIterator.map(_.vrf)
        pools.copy(vrfKeyHashes =
          (registered ++ pending).foldLeft(pools.vrfKeyHashes)(addVRFKeyHashOccurrence)
        )
      case ReferenceBuild.Release11_2 | ReferenceBuild.Leios =>
        Translate.populateVRFKeyHashes(pools)

  /** `addVRFKeyHashOccurrence` (`HardFork.hs:121-125`, cardano-ledger f649f975): one more count of
    * `vrf`, saturating at the largest word, where adding one would reach zero.
    */
  private def addVRFKeyHashOccurrence(
      counts: SortedMap[VRFVerKeyHash, NonZero[Word64]],
      vrf: VRFVerKeyHash
  ): SortedMap[VRFVerKeyHash, NonZero[Word64]] =
    counts.get(vrf) match
      case None => counts.updated(vrf, NonZero.oneWord)
      case Some(n) =>
        NonZero
          .from(Word64.fromUnsigned(n.value.bits + 1))
          .fold(_ => counts, counts.updated(vrf, _))

  /** `hardforkTransition` (`HardFork.hs:68-80`, cardano-ledger f649f975; 65-77 at 29a7d656 and
    * 9df9f98c), with the rule of `build` where the builds differ, for every era with Conway's
    * governance and certificate state ([[ConwayShapes]]): Dijkstra's HARDFORK is Conway's (`type
    * instance EraRule "HARDFORK" DijkstraEra = Conway.HARDFORK DijkstraEra`, cardano-ledger
    * 9df9f98c `Dijkstra/Era.hs:288`).
    */
  def apply[E <: EraTag](build: ReferenceBuild, es: EpochState[E], newVersion: Int)(using
      shapes: ConwayShapes[E]
  ): Either[VocabError, EpochState[E]] =
    def onCert(
        f: ConwayCertState => Either[VocabError, ConwayCertState]
    ): Either[VocabError, EpochState[E]] =
      f(shapes.cert(es.ls.cert)).map(c => es.copy(ls = es.ls.copy[E](cert = shapes.cert.flip(c))))
    if newVersion == 10 then onCert(c => Right(updateDRepDelegations(c)))
    else if newVersion == 11 then
      onCert(c => Right(c.copy(pState = populateVRFKeyHashes(build, c.pState))))
    else Right(es)

/** EPOCH of Conway (`Conway/Rules/Epoch.hs:244,269`, cardano-ledger f649f975): the boundary of an
  * epoch, after the rewards are applied: the new snapshot (SNAP) and the retiring pools (POOLREAP),
  * then the ratification the DRep pulser reached is enacted: treasury withdrawals reach the
  * accounts, the enacted and the expired proposals are removed and their deposits returned, the
  * committee and the constitution change and the parameters of the next epoch take effect. The
  * deposits are counted again, the hard fork rule runs if the protocol version changed, and a fresh
  * pulser starts.
  */
object ConwayEpoch:
  /** `predictFuturePParams` (`Conway/Governance.hs:299`, cardano-ledger f649f975): while the future
    * parameters are only `Potential`, they are the parameters the pulser's ratification enacts, if
    * it enacts a change of parameters or a hard fork. The pulser is complete here, so it is not
    * forced.
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
          _ <- Epoch.within(Snap.sts[Conway], es0.ls, epoch, EpochState.snapshots[Conway])
          _ <- Epoch.within(PoolReap.sts[Conway], (), epoch, PoolreapState.lens[Conway])
          es1 <- es.get
          enacted <- Sts.lift[F, TickFailure, EpochState[Conway]](
            enact(globals.ledger.referenceBuild, epoch, es0, es1).leftMap(TickFailure.Vocab(_))
          )
          _ <- es.set(withFreshPulser(globals, enacted, epoch))
        yield ()

  /** The deposit pot implied by the complete certificate and proposal state. Snapshot interchange
    * uses the same obligation as the epoch rule when checking reconstructed state.
    */
  def totalObligation[E <: EraTag](ls: LedgerState[E])(using
      era: ShelleyBasedEra[E],
      shapes: ConwayShapes[E],
      gs: GovState[E] =:= ConwayGovState
  ): BigInt =
    val cert = shapes.cert(ls.cert)
    EraLedger.keyDeposits(ls) +
      CompactCoin.sum(cert.pState.stakePools.valuesIterator.map(_.deposit)).toBigInt +
      CompactCoin.sum(cert.vState.dreps.valuesIterator.map(_.deposit)).toBigInt +
      gs(ls.utxo.gov).proposals.totalDeposits

  /** The enactment of the ratification the DRep pulser reached, once POOLREAP has run, and the hard
    * fork rule if the protocol version changed: `es0` is the state the rule began with (its pulser,
    * governance state and donations), `es1` the one after the sub-rules. The same in every era with
    * Conway's certificate state ([[ConwayShapes]]) and the node's flat governance record, Conway;
    * Dijkstra's EPOCH, which applies it as Conway's does (cardano-ledger 9df9f98c
    * `Dijkstra/Rules/Epoch.hs:153-224`), is on the era's record (`era.rules.DijkstraEpoch`).
    */
  private[rules] def enact[E <: EraTag](
      build: ReferenceBuild,
      epoch: EpochNo,
      es0: EpochState[E],
      es1: EpochState[E]
  )(using
      era: ShelleyBasedEra[E],
      shapes: ConwayShapes[E],
      gs: GovState[E] =:= ConwayGovState
  ): Either[VocabError, EpochState[E]] =
    val gov0 = gs(es0.ls.utxo.gov)
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
      refundedCert = shapes.cert(refunded.cert)
      unclaimed = unclaimedRefunds.foldLeft(BigInt(0))(_ + _._2.value)
      dormant <- {
        val active = proposals.actions.exists(epoch.toBigInt <= _.expiresAfter)
        if active then Right(refundedCert.vState.numDormantEpochs)
        else EpochNo.fromBigInt(refundedCert.vState.numDormantEpochs.toBigInt + 1)
      }
      committeeState = refundedCert.vState.committeeState.creds.filter((cold, _) =>
        enacted.committee.exists(_.members.contains(Convert.back(cold)))
      )
      treasury = Coin(
        es1.chainAccount.treasury.value - withdrawnTotal + es0.ls.utxo.donation.value + unclaimed
      )
      cert2 = refundedCert.copy(vState =
        refundedCert.vState.copy(
          committeeState = CommitteeState(committeeState),
          numDormantEpochs = dormant
        )
      )
      ls2 = LedgerState[E](shapes.cert.flip(cert2), ls1.utxo.copy(gov = gs.flip(gov1)))
      deposited = Coin(totalObligation(ls2))
      ls3 = ls2.copy[E](utxo = ls2.utxo.copy(deposited = deposited, donation = Coin.zero))
      es2 = es1.copy(chainAccount = es1.chainAccount.copy(treasury = treasury), ls = ls3)
      // the hard fork rule, if the protocol version changed
      forked <-
        if gov1.curPParams.version != gov1.prevPParams.version then
          HardFork(build, es2, gov1.curPParams.protocolMajor.toInt)
        else Right(es2)
    yield forked

  /** The fresh DRep pulser of the epoch the chain enters, over the state's mark snapshot, and the
    * future parameters it predicts (`setFreshDRepPulsingState`, [[ConwayPulser.fresh]],
    * [[predictFuturePParams]]): the last step of EPOCH, after SNAP in Dijkstra (cardano-ledger
    * 9df9f98c `Dijkstra/Rules/Epoch.hs:225-233`).
    */
  private[rules] def withFreshPulser[E <: EraTag](
      globals: Globals,
      es: EpochState[E],
      epoch: EpochNo
  )(using
      shapes: ConwayShapes[E],
      gs: GovState[E] =:= ConwayGovState
  ): EpochState[E] =
    val pulser = ConwayPulser.fresh(globals, es, epoch)
    val gov = gs(es.ls.utxo.gov)
    es.copy(ls =
      es.ls.copy[E](utxo =
        es.ls.utxo.copy(gov = gs.flip(predictFuturePParams(gov.copy(drepPulsing = pulser))))
      )
    )
