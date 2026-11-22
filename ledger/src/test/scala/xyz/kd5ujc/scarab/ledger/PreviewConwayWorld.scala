// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.{Chain, NonEmptyChain}
import munit.Assertions.{assertEquals, fail}
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.plutus.{ExUnits, ScarabEvaluator}
import xyz.kd5ujc.scarab.ledger.rules.{Convert, EraLedger, NativeRules}
import xyz.kd5ujc.scarab.ledger.state.{
  Build,
  ColdCommittee,
  Coin,
  CommitteeAuthorization,
  CommitteeState,
  CompactCoin,
  Conway,
  ConwayAccountState,
  ConwayAccounts,
  ConwayGovState,
  ConwayLedgerState,
  ConwayNewEpochState,
  HotCommittee,
  LedgerState,
  NewEpochState,
  Sorted,
  Staking,
  UTxOState,
  VState,
  VocabError
}
import xyz.kd5ujc.scarab.protocol.Era

import TestLedger.*

/** The ledger around recorded Preview Conway transactions (`PlutusCase`), built from the outputs
  * they spend and reference, their collateral, the accounts their proposals and withdrawals name
  * and the roots their proposals follow, at Preview's parameters: what `PreviewConwayRulesSuite`
  * judges each transaction in, and `PreviewConwayBlockLedgersSuite` the transactions of a block.
  */
object PreviewConwayWorld:
  val ada = BigInt(1000000)

  /** Preview's length of an epoch, in slots. */
  val epochLength = BigInt(86400)

  private def ok[A](value: Either[VocabError, A]): A = value.fold(e => fail(e.toString), identity)

  private def compact(n: BigInt): CompactCoin = ok(CompactCoin.fromBigInt(n))

  /** Everything the ledger holds, in lovelace: the UTxO, the fees, the deposits, the two pots, the
    * account balances and the donations. It is the supply when the ledger is consistent.
    */
  def total(nes: NewEpochState[Conway]): BigInt =
    val es = nes.es
    es.ls.utxo.utxo.utxo.coin + es.ls.utxo.fees.value + es.ls.utxo.deposited.value +
      es.chainAccount.treasury.value + es.chainAccount.reserves.value +
      EraLedger.accounts(es.ls).iterator.map(_._2.balance.toBigInt).sum +
      es.ls.utxo.donation.value

  def gov(nes: NewEpochState[Conway]): ConwayGovState = ConwayNewEpochState.gov.get(nes)

  private val vState = ConwayNewEpochState.ls.andThen(ConwayLedgerState.vState)

  private val deposits =
    ConwayNewEpochState.ls.andThen(LedgerState.utxo[Conway]).andThen(UTxOState.deposited[Conway])

  /** The ledger around the transactions of `cases`, of one slot, one protocol version and one set
    * of cost models: their outputs, the accounts their proposals and withdrawals name (the
    * withdrawing ones holding what they withdraw and delegated to abstain), a treasury of a billion
    * ada, the constitution's guardrails script and the roots their proposals follow, the deposits
    * its accounts hold and the reserves making up the rest of the supply.
    */
  def worldOf(cases: Vector[PlutusCase]): NewEpochState[Conway] =
    val c = cases.headOption.getOrElse(fail("a world of no transaction"))
    cases.foreach { other =>
      assertEquals(other.slot, c.slot, s"${other.name}: the slot of ${c.name}")
      assertEquals(other.protocolMajor, c.protocolMajor, s"${other.name}: the version")
      assertEquals(other.costModels, c.costModels, s"${other.name}: the cost models")
    }
    assertEquals(NativeRules.eraOf(c.protocolMajor), Era.Conway, "a Conway transaction")
    val bodies = cases.map(_.tx.body)
    val utxo =
      cases.foldLeft(Map.empty[TxIn, TxOut])((u, k) => u ++ k.spent ++ k.references ++ k.collateral)
    val policy = bodies.flatMap(_.proposals).collectFirst {
      case Proposal(_, _, GovAction.ParameterChange(_, _, Some(p)), _)  => p
      case Proposal(_, _, GovAction.TreasuryWithdrawals(_, Some(p)), _) => p
    }
    val roots = bodies.flatMap(_.proposals).foldLeft(PrevIds()) { (r, p) =>
      p.action match
        case GovAction.ParameterChange(prev, _, _) => r.copy(paramUpdate = prev)
        case _                                     => r
    }
    val abstain = ok(Convert.drep(DRep.AlwaysAbstain))
    val withdrawing = bodies.flatMap(_.withdrawals).map { case (account, amount) =>
      account.credential -> ConwayAccountState(
        compact(amount.value),
        compact(2 * ada),
        None,
        Some(abstain)
      )
    }
    val credentials = bodies.flatMap(body =>
      body.proposals.map(_.returnAccount.credential) ++ body.proposals.flatMap(_.action match
        case GovAction.TreasuryWithdrawals(ws, _) => ws.map(_._1.credential)
        case _                                    => Vector.empty)
    )
    val accounts = credentials.distinct
      .map(_ -> ConwayAccountState(compact(0), compact(2 * ada), None, None))
      .toMap ++ withdrawing
    val params = TestLedger
      .params(c.protocolMajor)
      .copy(
        minFeeA = 44,
        minFeeB = 155381,
        maxTxSize = 16384,
        maxValueSize = 5000,
        coinsPerUTxOByte = 4310,
        keyDeposit = 2 * ada,
        priceMem = Rational(577, 10000),
        priceSteps = Rational(721, 10000000),
        maxTxExUnits = ExUnits(17500000, BigInt("10000000000")),
        maxBlockExUnits = ExUnits(77500000, BigInt("20000000000")),
        collateralPercentage = 150,
        maxCollateralInputs = 3,
        govActionDeposit = 1000 * ada,
        govActionLifetime = 30,
        minFeeRefScriptCostPerByte = Rational(15),
        costModels = c.costModels
      )
    val funded = Build.funded(Build.nes[Conway]((c.slot / epochLength).toLong, params), utxo)
    val base = ConwayNewEpochState.accounts
      .replace(ConwayAccounts(Sorted.map(accounts.toList.map { (credential, account) =>
        ok(Convert.credential[Staking](credential)) -> account
      })))
      .andThen(ConwayNewEpochState.treasury.replace(Coin(1000000000L * ada)))
      .andThen(
        ConwayNewEpochState.gov.modify(g =>
          g.copy(
            constitution = g.constitution.copy(script = policy),
            proposals = Proposals(Vector.empty, roots)
          )
        )
      )(funded)
    val registered = deposits.replace(Coin(NativeRules.obligation(base)))(base)
    val reserves = Supply - (total(registered) - registered.es.chainAccount.reserves.value)
    ConwayNewEpochState.reserves.replace(Coin(reserves))(registered)

  /** `world` with the committee the votes of `cases` need: each distinct hot credential that votes,
    * in order, authorized by a cold member of its own, the members a committee of threshold 2/3
    * whose terms run 50 epochs on, and every action voted on a parameter change proposed five
    * epochs before, for five more. Every voter is a committee member.
    */
  def withCommittee(
      world: NewEpochState[Conway],
      cases: Vector[PlutusCase]
  ): NewEpochState[Conway] =
    val c = cases.headOption.getOrElse(fail("a committee for no transaction"))
    val epoch = c.slot / epochLength
    val voters = cases.flatMap { k =>
      val body = k.tx.body
      val committee = body.votes.collect { case (Voter.CommitteeHot(h), vs) => h -> vs }
      assertEquals(committee.size, body.votes.size, s"${k.name}: only committee members vote")
      committee
    }
    val hots = voters.map(_._1).distinct
    val cold = hots.zipWithIndex.map { case (hot, i) =>
      Credential.Key(
        xyz.kd5ujc.scarab.crypto.Blake2b.hash224
          .hash(xyz.kd5ujc.scarab.codec.Bytes(Vector(i.toByte, 9)))
      ) -> hot
    }
    val actions = voters
      .flatMap(_._2.map(_._1))
      .distinct
      .map { id =>
        GovActionState(
          id,
          Map.empty,
          Map.empty,
          Map.empty,
          Proposal(
            1000 * ada,
            RewardAccount(0, cold.head._1),
            GovAction
              .ParameterChange(
                None,
                xyz.kd5ujc.scarab.ledger.TestLedger.conwayUpdate(16 -> 1),
                None
              )
          ),
          epoch - 5,
          epoch + 5
        )
      }
    val authorized = CommitteeState(Sorted.map(cold.map { (member, hot) =>
      ok(Convert.credential[ColdCommittee](member)) ->
        CommitteeAuthorization.CommitteeHotCredential(ok(Convert.credential[HotCommittee](hot)))
    }))
    ConwayNewEpochState.gov
      .modify(g =>
        g.copy(
          committee = Some(Committee(cold.map(_._1 -> (epoch + 50)).toMap, Rational(2, 3))),
          proposals = Proposals(actions, PrevIds())
        )
      )
      .andThen(vState.andThen(VState.committeeState).replace(authorized))(world)

  /** The context of a transaction at `slot`: Preview's epoch and stability window and the clock of
    * its system start.
    */
  def envOf(slot: BigInt): TxEnv =
    TxEnv(
      slot = slot,
      epoch = slot / epochLength,
      network = 0,
      firstSlotNextEpoch = (slot / epochLength + 1) * epochLength,
      stabilityWindow = 129600,
      clock = Some(xyz.kd5ujc.scarab.ledger.PosixClock.Total(slot => slot * 1000 + 1666656000000L)),
      evaluator = ScarabEvaluator.default,
      decoder = ScarabEvaluator.defaultDecoder
    )

  /** A transaction of a block read by Conway's decoder from its bytes. */
  def eraTx(what: String, transaction: Block.Transaction): era.conway.ConwayTx =
    era.conway.ConwayTx
      .decode(transaction, xyz.kd5ujc.scarab.protocol.StateLayout.Node11_1)
      .fold(e => fail(s"$what: $e"), identity)

  private def eraPParams(s: NewEpochState[Conway]): era.PParams[Conway] =
    era.PParams[Conway](
      era.conway.ConwayPParams.fromStored(gov(s).curPParams).fold(e => fail(s"$e"), identity)
    )

  private def epochNo(ctx: TxEnv): state.EpochNo =
    state.EpochNo.fromBigInt(ctx.epoch).fold(e => fail(s"$e"), identity)

  /** The rules' context at `ctx`, under the epoch information of the clock (one era without an
    * end).
    */
  private def utxoContext(ctx: TxEnv): era.rules.UtxoContext[Conway] =
    val epochInfo = Horizon(
      cats.data.NonEmptyVector.one(
        Horizon.Era(
          Horizon.Edge(0, 0, 0),
          None,
          Horizon.Params(epochLength, 1000, 129600, 129600)
        )
      )
    )
    era.rules.UtxoContext[Conway](
      era.Network.of(BigInt(ctx.network)).getOrElse(fail(s"network ${ctx.network}")),
      1666656000000L,
      epochInfo,
      ctx.validation,
      era.rules.ConwayUtxo.injectShelleyFailure(ctx.referenceBuild)
    )

  /** The era-typed LEDGERS (`ShelleyLedgers.ledgersTransition`) on `txs` from the ledger state of
    * `s`, at the slot and epoch, parameters and chain accounts the flat rules are given at `slot`:
    * the failures in the reference's order, or the state after with the events.
    */
  def eraLedgers(
      what: String,
      s: NewEpochState[Conway],
      slot: BigInt,
      txs: Vector[era.conway.ConwayTx]
  ): Either[
    NonEmptyChain[era.rules.LedgersPredFailure[Conway]],
    (LedgerState[Conway], Chain[era.rules.LedgersEvent[Conway]])
  ] =
    val ctx = envOf(slot)
    era.EraLedgerRun
      .ledgers(
        NativeRules.globalsOf(ctx),
        era.rules.LedgersEnv[Conway](ctx.slot, epochNo(ctx), eraPParams(s), s.es.chainAccount),
        txs,
        utxoContext(ctx),
        s.es.ls,
        ctx.evaluator,
        ctx.verdicts,
        ctx.decoder,
        ctx.clock.getOrElse(fail("no clock")).posix
      )
      .fold(defect => fail(s"$what: era defect $defect"), identity)

  /** The era-typed LEDGER (`ConwayLedger.conwayLedgerTransition`) on `tx` at index `ix` of a block,
    * `depth` levels below the root, from `ls`, under the parameters and chain accounts of `s`: the
    * transaction annotated against the stored outputs of `ls` it names, as LEDGERS annotates it.
    */
  def eraLedger(
      what: String,
      s: NewEpochState[Conway],
      ls: LedgerState[Conway],
      slot: BigInt,
      ix: Int,
      tx: era.conway.ConwayTx,
      depth: Int
  ): Either[
    NonEmptyChain[era.rules.LedgerPredFailure[Conway]],
    (LedgerState[Conway], Chain[era.rules.LedgerEvent[Conway]])
  ] =
    val ctx = envOf(slot)
    val pp = eraPParams(s)
    val utxo =
      era.conway.ConwayUTxO
        .fromStored(ls.utxo.utxo.utxo.toMap)
        .fold(e => fail(s"$what: $e"), identity)
    era.EraLedgerRun
      .ledger(
        NativeRules.globalsOf(ctx),
        era.rules.LedgerEnv[Conway](
          ctx.slot,
          Some(epochNo(ctx)),
          state.TxIx.txIxFromIntegral(ix).getOrElse(fail(s"$what: index $ix")),
          pp,
          s.es.chainAccount
        ),
        era.AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](
          ctx.clock.getOrElse(fail("no clock")).posix,
          pp,
          utxo,
          Map.empty,
          tx
        ),
        utxoContext(ctx),
        ls,
        ctx.evaluator,
        ctx.verdicts,
        ctx.decoder,
        depth
      )
      .fold(defect => fail(s"$what: era defect $defect"), identity)
