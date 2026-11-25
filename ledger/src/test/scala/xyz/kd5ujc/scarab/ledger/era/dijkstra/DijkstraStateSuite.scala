// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{Block, DecodeField, LedgerDecodeError, Tx as FlatTx}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.era.{
  BothLevels,
  ConwayEraAccounts,
  Delegatee,
  EraAccounts,
  EraCertState,
  EraTxBody,
  IsPhase2Valid,
  Obligations,
  PParams,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.rules.Deposits
import xyz.kd5ujc.scarab.ledger.rules.NativeRewardFixture
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  CompactCoin,
  Credential,
  DRep,
  DRepRole,
  DRepState,
  Dijkstra,
  EpochNo,
  KeyHash,
  LedgerStateGen,
  Sorted,
  StakePool,
  Staking
}
import xyz.kd5ujc.scarab.ledger.ProtocolParams

import DijkstraCorpus.*

/** Dijkstra's instances of the state classes on the era types
  * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/State/{Account,CertState}.hs`, cardano-ledger
  * 9df9f98c): Conway's records and lenses, and the deposits and refunds of a batch.
  *
  * The transactions are those recorded on the public Leios testnet's chain, which its ledger
  * accepted, so each registration declares the deposit its parameters ask, and the reference's
  * UTXOW cases, with their parameters: 217. Of the 189 without sub-transactions, the 176 the Conway
  * decoder reads have their deposits and refunds compared with the flat rules' (`Deposits.of`,
  * `DijkstraUtxo.declaredRefunds`); the 13 it refuses, for a field Dijkstra adds or changes, are
  * counted by their typed refusal. Every one without certificates has the parameters' deposit for
  * each of its proposals at both levels. The flat path has no computation of a batch's, so crafted
  * batches give the refunds of each level and the deposits of a pool registered twice in the batch,
  * of a pool the state holds and of proposals at both levels, each total derived by hand from the
  * reference's rules. The accounts and obligations are those of Conway's instance, on a state of
  * distinct deposits and a delegation to a pool and a DRep.
  */
class DijkstraStateSuite extends FunSuite:
  private val bodies = EraTxBody.dijkstra
  private val certStates = EraCertState.dijkstra
  private val accounts = EraAccounts.dijkstra
  private given BothLevels[TxLevel.Top] = BothLevels.Top

  private final case class Case(
      name: String,
      raw: xyz.kd5ujc.scarab.codec.Bytes,
      tx: DijkstraTx[TxLevel.Top],
      stored: ProtocolParams,
      pp: PParams[Dijkstra]
  )

  private lazy val cases: Vector[Case] =
    val testnet = testnetStored
    def read(name: String, raw: xyz.kd5ujc.scarab.codec.Bytes, stored: ProtocolParams) =
      Case(
        name,
        raw,
        DijkstraTx.inBlock(node(raw)).fold(e => fail(s"$name: $e"), identity),
        stored,
        era(stored)
      )
    chainContextCases.map(c => read(str(c, "name"), hex(str(c, "bytes")), testnet)) ++
      (utxowCases ++ entitiesCases).map(c =>
        read(str(c, "name"), hex(str(c, "tx")), utxowStored(c))
      )

  /** The reference's ENTITIES cases (`fixtures/reference/dijkstra-rules`, of 2026-11-24): their
    * transactions withdraw, deposit directly, bound balances or deregister.
    */
  private lazy val entitiesCases =
    jsonLines("fixtures/reference/dijkstra-rules/cases.jsonl").filter(c =>
      str(c, "kind") == "entities"
    )

  /** The transaction in its Conway form, the flat rules' reading of it, where it has no
    * sub-transaction: the Conway decoder's refusal where it reads a field Dijkstra adds, or none
    * for a transaction with sub-transactions.
    */
  private def conwayForm(c: Case): Option[Either[LedgerDecodeError, FlatTx]] =
    Option.when(bodies.subTransactions(c.tx.body).toVector.isEmpty)(c.tx).map { tx =>
      FlatTx.fromBlock(
        Block.Transaction(
          tx.body.raw,
          tx.wits.raw,
          tx.auxData.map(_.raw),
          DijkstraTx.isValid(tx) == IsPhase2Valid.Phase2Valid
        ),
        Era.Conway
      )
    }

  /** `dijkstraTotalRefundsTxCerts` (`Dijkstra/TxCert.hs:262-271`, cardano-ledger 9df9f98c) over the
    * Conway form of the body: the refunds its certificates declare, as the flat Dijkstra UTXO read
    * them.
    */
  private def declaredRefunds(body: xyz.kd5ujc.scarab.ledger.TxBody): BigInt =
    body.certificates.iterator.map {
      case xyz.kd5ujc.scarab.ledger.Certificate.Unregister(_, refund) => refund.getOrElse(BigInt(0))
      case xyz.kd5ujc.scarab.ledger.Certificate.UnregisterDRep(_, refund) => refund
      case _                                                              => BigInt(0)
    }.sum

  test("the deposits and refunds of a transaction without sub-transactions equal the flat rules'") {
    val forms = cases.flatMap(c => conwayForm(c).map(c -> _))
    val compared = forms.collect { case (c, Right(flat)) =>
      val deposits =
        certStates.certsTotalDepositsTxBody(c.pp, LedgerStateGen.conwayCert, c.tx.body)(using
          bodies
        )
      val refunds = certStates.certsTotalRefundsTxBody[TxLevel.Top](
        c.pp,
        LedgerStateGen.conwayAccounts,
        c.tx.body
      )(using bodies, BothLevels.Top)
      assertEquals(
        (deposits.value, refunds.value),
        (
          Deposits.of(c.stored, LedgerStateGen.conwayCert, flat.body)._1,
          declaredRefunds(flat.body)
        ),
        c.name
      )
      (deposits.value, refunds.value)
    }
    // the transactions the Conway decoder refuses, each for a field Dijkstra adds or changes, are
    // not compared: a witness set's key 8 (seven), a parameter change of keys 45 to 48 (the two
    // proposals), the direct deposits' key 25 with the balance intervals' key 26 (one), a guard
    // where Conway has a signer (three), and of the ENTITIES cases of 2026-11-24 the direct
    // deposits' key 25 alone (two), the balance intervals' key 26 (four) and the original balance
    // intervals' key 27 (two); 28 more have sub-transactions. The cases of 2026-11-24 add 6
    // compared: the collateral return to a pointer address (UTXOW), and of the ENTITIES cases the
    // four withdrawals and the deregistration whose declared refund is not the deposit held
    val excluded = forms.collect { case (c, Left(e)) => (c.name, e) }
    import LedgerDecodeError.*
    def count(p: LedgerDecodeError => Boolean) = excluded.count((_, e) => p(e))
    assertEquals(
      (
        count {
          case UnknownFields(DecodeField.WitnessSet, fields) => fields == Set(8)
          case _                                             => false
        },
        count {
          case UnknownKey(DecodeField.ParameterUpdate, key) => key >= 45 && key <= 48
          case _                                            => false
        },
        count {
          case UnknownFields(DecodeField.TransactionBody, fields) => fields == Set(25, 26)
          case _                                                  => false
        },
        count {
          case Expected(DecodeField.Signer, Shape.Bytes, _) => true
          case _                                            => false
        },
        count {
          case UnknownFields(DecodeField.TransactionBody, fields) =>
            fields == Set(25) || fields == Set(26) || fields == Set(27)
          case _ => false
        }
      ),
      (7, 2, 1, 3, 8),
      excluded.mkString("\n")
    )
    assertEquals((compared.size, excluded.size, cases.size - forms.size), (176 + 6, 13 + 8, 28))
    assert(compared.exists(_._1 > 0) && compared.exists(_._2 > 0), compared.toString)
  }

  test("the proposals' deposits are the parameters' deposit for each, at both levels") {
    // `conwayProposalsDeposits` of each level (`State/CertState.hs:53-54`, cardano-ledger
    // 9df9f98c): on every case without certificates, the deposit times the proposals its body and
    // each sub-transaction's hold
    val compared = cases.flatMap { c =>
      val subs = bodies.subTransactions(c.tx.body).toVector.map(_.body)
      val certs = bodies.certs[TxLevel.Top](c.tx.body)(using BothLevels.Top).size +
        subs.map(bodies.certs[TxLevel.Sub](_)(using BothLevels.Sub).size).sum
      Option.when(certs == 0) {
        val proposals =
          bodies.proposalProcedures[TxLevel.Top](c.tx.body)(using BothLevels.Top).toVector.size +
            subs
              .map(bodies.proposalProcedures[TxLevel.Sub](_)(using BothLevels.Sub).toVector.size)
              .sum
        assertEquals(
          certStates
            .certsTotalDepositsTxBody(c.pp, LedgerStateGen.conwayCert, c.tx.body)(using bodies)
            .value,
          c.stored.govActionDeposit * proposals,
          c.name
        )
        proposals
      }
    }
    // the testnet's two parameter changes among them
    assertEquals(compared.count(_ > 0), 2)
  }

  test("a batch's refunds add up each level's certificates' declared refunds") {
    val batches = cases.filter(c => bodies.subTransactions(c.tx.body).toVector.nonEmpty)
    batches.foreach { c =>
      val subs = bodies.subTransactions(c.tx.body).toVector
      val own = bodies.certsOf.getTotalRefundsTxCerts(c.pp, _ => None, bodies.certs(c.tx.body))
      val perLevel = subs.map(s =>
        certStates.certsTotalRefundsTxBody[TxLevel.Sub](
          c.pp,
          LedgerStateGen.conwayAccounts,
          s.body
        )(using bodies, BothLevels.Sub)
      )
      assertEquals(
        certStates.certsTotalRefundsTxBody[TxLevel.Top](
          c.pp,
          LedgerStateGen.conwayAccounts,
          c.tx.body
        )(using bodies, BothLevels.Top),
        Coin(own.value + perLevel.map(_.value).sum),
        c.name
      )
    }
    assertEquals(batches.size, 28)
    val subCerts = batches.count(c =>
      bodies
        .subTransactions(c.tx.body)
        .toVector
        .exists(s => bodies.certs(s.body)(using BothLevels.Sub).nonEmpty)
    )
    // two of the 28 batches hold certificates in a sub-transaction
    assertEquals(subCerts, 2)
  }

  // ---- crafted batches: a top-level transaction with one sub-transaction, its bytes written here

  private def head(major: Int, n: BigInt): String =
    val m = major << 5
    if n < 24 then f"${m + n.toInt}%02x"
    else if n < 256 then f"${m + 24}%02x${n.toInt}%02x"
    else if n < 65536 then f"${m + 25}%02x${n.toInt}%04x"
    else if n < BigInt(1L << 32) then f"${m + 26}%02x${n.toLong}%08x"
    else f"${m + 27}%02x${n.toLong}%016x"
  private def uint(n: BigInt) = head(0, n)
  private def bstr(hexBytes: String) = head(2, hexBytes.length / 2) + hexBytes
  private def arr(items: String*) = head(4, items.size) + items.mkString
  private def cmap(entries: (Int, String)*) =
    head(5, entries.size) + entries.map((k, v) => uint(k) + v).mkString
  private def keyHash(b: String) = b * 28
  private def cred(b: String) = arr(uint(0), bstr(keyHash(b)))
  private def account(b: String) = bstr("e0" + keyHash(b))
  private def input(b: String) = arr(arr(bstr(b * 32), uint(0)))

  // `RegPoolTxCert` (tag 3) of `operator`, its owner and reward account those of `b`
  private def regPool(operator: String) =
    arr(
      uint(3),
      bstr(keyHash(operator)),
      bstr("22" * 32),
      uint(0),
      uint(170000000),
      "d81e82010a",
      account(operator),
      arr(bstr(keyHash(operator))),
      arr(),
      "f6"
    )
  private def regDeposit(b: String, deposit: BigInt) = arr(uint(7), cred(b), uint(deposit))
  private def unRegDeposit(b: String, refund: BigInt) = arr(uint(8), cred(b), uint(refund))
  private def unRegDRep(b: String, refund: BigInt) = arr(uint(17), cred(b), uint(refund))
  // an information action
  private def proposal(b: String) =
    arr(uint(100000000000L), account(b), arr(uint(6)), arr(head(3, 3) + "616263", bstr("33" * 32)))

  /** A valid top-level transaction with no witnesses, its certificates and proposals at each level
    * as given (an empty list is left out, as the decoder refuses one).
    */
  private def batch(
      topCerts: Vector[String],
      topProposals: Vector[String],
      subCerts: Vector[String],
      subProposals: Vector[String]
  ): DijkstraTx[TxLevel.Top] =
    def fields(certs: Vector[String], proposals: Vector[String]) =
      Vector(4 -> certs, 20 -> proposals).filter(_._2.nonEmpty).map((k, xs) => k -> arr(xs*))
    val sub = arr(
      cmap((Vector(0 -> input("44"), 1 -> arr()) ++ fields(subCerts, subProposals))*),
      "a0",
      "f6"
    )
    val body = cmap(
      (Vector(0 -> input("55"), 1 -> arr(), 2 -> uint(0)) ++ fields(topCerts, topProposals) :+
        (23 -> arr(sub)))*
    )
    DijkstraTx
      .inBlock(node(hex(arr(body, "a0", "f6", "f5"))))
      .fold(e => fail(s"the crafted batch: $e"), identity)

  test(
    "a crafted batch's refunds are each level's declared refunds, the top level's adding up both"
  ) {
    // `dijkstraCertsTotalRefundsTxBody` (`State/CertState.hs:62-81`, cardano-ledger 9df9f98c):
    // the refunds the certificates declare, the stake and the DRep deregistrations', at the top
    // level of its own and its sub-transactions' certificates
    val tx = batch(
      Vector(unRegDeposit("61", 13)),
      Vector.empty,
      Vector(unRegDeposit("62", 5), unRegDRep("63", 11)),
      Vector.empty
    )
    val pp = era(testnetStored)
    val sub = bodies.subTransactions(tx.body).toVector.head
    def refunds[L <: TxLevel](body: DijkstraTxBody[L])(using l: BothLevels[L]) =
      certStates.certsTotalRefundsTxBody[L](pp, LedgerStateGen.conwayAccounts, body)(using
        bodies,
        l
      )
    assertEquals(refunds[TxLevel.Sub](sub.body)(using BothLevels.Sub), Coin(5 + 11))
    assertEquals(refunds[TxLevel.Top](tx.body), Coin(13 + 5 + 11))
  }

  test("a crafted batch's deposits count a pool once over the batch, none for a registered one") {
    // `dijkstraCertsTotalDepositsTxBody` (`State/CertState.hs:41-60`, cardano-ledger 9df9f98c):
    // the batch's certificates, the sub-transaction's first, `getTotalDepositsTxCerts` counting a
    // pool's deposit once and none for a pool the state holds (`Core/TxCert.hs`); and each
    // level's proposals
    val stored = testnetStored
    val pp = era(stored)
    val registered = poolHash("66")
    val certState = LedgerStateGen.conwayCert.copy(pState =
      LedgerStateGen.emptyPState.copy(stakePools =
        Sorted.map(Vector(registered -> NativeRewardFixture.stakePool(stakingCred("66"))))
      )
    )
    val tx = batch(
      Vector(regPool("65"), regDeposit("67", 2000000)),
      Vector(proposal("68"), proposal("69")),
      Vector(regPool("65"), regPool("66")),
      Vector(proposal("6a"))
    )
    // the pool of 65 once, none for 66, the parameters' key deposit for the stake registration
    // (`getTotalDepositsTxCerts` charges `ppKeyDeposit`; the certificate declares the same
    // amount), and three proposals
    assertEquals(
      certStates.certsTotalDepositsTxBody(pp, certState, tx.body)(using bodies),
      Coin(stored.poolDeposit + stored.keyDeposit + 3 * stored.govActionDeposit)
    )
    // without the state's pool, 66's deposit is counted too
    assertEquals(
      certStates.certsTotalDepositsTxBody(pp, LedgerStateGen.conwayCert, tx.body)(using bodies),
      Coin(2 * stored.poolDeposit + stored.keyDeposit + 3 * stored.govActionDeposit)
    )
  }

  private def stakingCred(b: String): Credential[Staking] =
    Credential.KeyHashObj[Staking](
      KeyHash.fromBytes[Staking](hex(keyHash(b))).fold(e => fail(e.toString), identity)
    )
  private def poolHash(b: String): KeyHash[StakePool] =
    KeyHash.fromBytes[StakePool](hex(keyHash(b))).fold(e => fail(e.toString), identity)

  test("Dijkstra's accounts and obligations are Conway's records, through the same lenses") {
    val conway = EraAccounts.conway
    val pool = poolHash("71")
    val drep = DRep.DRepKeyHash(
      KeyHash.fromBytes[DRepRole](hex(keyHash("72"))).fold(e => fail(e.toString), identity)
    )
    // two accounts of distinct deposits, one delegated to a pool and a DRep
    val first = ConwayEraAccounts.registerConwayAccount[Dijkstra](
      stakingCred("73"),
      CompactCoin.fromUnsigned(2000000L),
      Some(Delegatee.DelegStakeVote(pool, drep)),
      LedgerStateGen.conwayAccounts
    )(using accounts)
    val both = ConwayEraAccounts.registerConwayAccount[Dijkstra](
      stakingCred("74"),
      CompactCoin.fromUnsigned(3000000L),
      None,
      first
    )(using accounts)
    val conwayBoth = ConwayEraAccounts.registerConwayAccount[xyz.kd5ujc.scarab.ledger.state.Conway](
      stakingCred("74"),
      CompactCoin.fromUnsigned(3000000L),
      None,
      ConwayEraAccounts.registerConwayAccount[xyz.kd5ujc.scarab.ledger.state.Conway](
        stakingCred("73"),
        CompactCoin.fromUnsigned(2000000L),
        Some(Delegatee.DelegStakeVote(pool, drep)),
        LedgerStateGen.conwayAccounts
      )(using conway)
    )(using conway)
    assertEquals(both, conwayBoth)
    val delegated = accounts.accountsMapL.get(both)(stakingCred("73"))
    assertEquals(accounts.stakePoolDelegationAccountStateL.get(delegated), Some(pool))
    assertEquals(accounts.dRepDelegationAccountStateL.get(delegated), Some(drep))
    assertEquals(
      accounts.unregisterAccount(stakingCred("73"), both),
      conway.unregisterAccount(stakingCred("73"), both)
    )
    // a state holding the accounts, two pools and a DRep, each deposit distinct: the obligations
    // are each kind's sum (`conwayObligationCertState`)
    val pools = Sorted.map(
      Vector(
        pool -> NativeRewardFixture.stakePool(stakingCred("73")),
        poolHash("75") -> NativeRewardFixture
          .stakePool(stakingCred("74"))
          .copy(deposit = CompactCoin.fromUnsigned(7L))
      )
    )
    val dreps = Sorted.map(
      Vector(
        (Credential.KeyHashObj[DRepRole](
          KeyHash.fromBytes[DRepRole](hex(keyHash("72"))).fold(e => fail(e.toString), identity)
        ): Credential[DRepRole]) ->
          DRepState(EpochNo.fromUnsigned(10), None, CompactCoin.fromUnsigned(11L), Sorted.emptySet)
      )
    )
    val state = LedgerStateGen.conwayCert.copy(
      vState = LedgerStateGen.emptyVState.copy(dreps = dreps),
      pState = LedgerStateGen.emptyPState.copy(stakePools = pools),
      dState = LedgerStateGen.conwayCert.dState.copy(accounts = both)
    )
    assertEquals(
      certStates.obligationCertState(state),
      Obligations(Coin(2000000 + 3000000), Coin(500000000 + 7), Coin(11), Coin(0))
    )
    assertEquals(
      certStates.obligationCertState(state),
      EraCertState.conway.obligationCertState(state)
    )
  }
