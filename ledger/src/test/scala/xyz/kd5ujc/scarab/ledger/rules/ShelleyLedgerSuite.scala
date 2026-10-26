// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import xyz.kd5ujc.scarab.ledger.testByteStrings
import cats.data.Kleisli
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import munit.FunSuite
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Certificate,
  Datum,
  GenDelegPair,
  LedgerFailure,
  NativeScript,
  ParamUpdate,
  PoolParams,
  Pot,
  PredicateFailure,
  Ptr,
  Redeemer,
  RewardAccount,
  Script,
  ScriptIntegrity,
  ScriptKind,
  TestLedger,
  Tx,
  TxEnv,
  TxOut,
  Update,
  Value,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.TestLedger.*

/** The LEDGER rules of Shelley, Allegra, Mary, Alonzo and Babbage on the era-indexed state
  * ([[ShelleyLedger]], one instance over the witness), on one transaction at a time and in every
  * era: the state is built natively ([[NativeRules.stateIn]], [[Build]]), and what the rules do
  * with the transaction is checked: the rule that refuses it, if it does, as the typed predicate
  * failure with its payload, and the state after it, if it does not, together with what the rules
  * maintain incrementally and the reference defines: the pools' delegators against the accounts,
  * the accounts' pointers against the pointer map, the instant stake against the UTxO and the
  * deposits against the obligation.
  */
class ShelleyLedgerSuite extends FunSuite:
  private val alice = key()
  private val bob = key()
  private val carol = key()
  private val ada = BigInt(1000000)

  /** An era: the protocol version its parameters have, and its witness with the evidence. */
  private final class Case[E <: EraTag](
      val name: String,
      val pv: Int,
      witness: ShelleyBasedEra[E],
      evidence: PreConway[E]
  ):
    given era: ShelleyBasedEra[E] = witness
    given pre: PreConway[E] = evidence

  private val shelley = Case("Shelley", 2, ShelleyBasedEra.SShelley, summon[PreConway[Shelley]])
  private val allegra = Case("Allegra", 3, ShelleyBasedEra.SAllegra, summon[PreConway[Allegra]])
  private val mary = Case("Mary", 4, ShelleyBasedEra.SMary, summon[PreConway[Mary]])
  private val alonzo = Case("Alonzo", 5, ShelleyBasedEra.SAlonzo, summon[PreConway[Alonzo]])
  private val babbage = Case("Babbage", 7, ShelleyBasedEra.SBabbage, summon[PreConway[Babbage]])
  private val eras: Vector[Case[?]] = Vector(shelley, allegra, mary, alonzo, babbage)

  /** One test body for every era, at the era's type. */
  private def forEras(f: [E <: EraTag] => Case[E] => Unit): Unit =
    eras.foreach { case c: Case[e] => f[e](c) }

  // ---- the native vocabulary of the keys

  private def staking(k: Key): Credential[Staking] =
    Convert.credential[Staking](k.credential).fold(e => fail(s"$e"), identity)

  private def poolId(k: Key): KeyHash[StakePool] =
    Convert.keyHash[StakePool](k.hash).fold(e => fail(s"$e"), identity)

  private def genesisId(b: Bytes): KeyHash[Genesis] =
    Convert.keyHash[Genesis](b).fold(e => fail(s"$e"), identity)

  private def compact(n: BigInt): CompactCoin =
    CompactCoin.fromBigInt(n).fold(e => fail(s"$e"), identity)

  private def coin(n: BigInt): Coin = Coin(n)

  // ---- the parts of a state the assertions read

  private def accounts[E <: EraTag](c: Case[E], n: NewEpochState[E]): ShelleyAccounts =
    c.pre.dState.get(n.es.ls).accounts

  private def pools[E <: EraTag](c: Case[E], n: NewEpochState[E]): PState =
    c.pre.pState.get(n.es.ls)

  private def iRewards[E <: EraTag](c: Case[E], n: NewEpochState[E]): InstantaneousRewards =
    c.pre.dState.get(n.es.ls).iRewards

  private def gov[E <: EraTag](c: Case[E], n: NewEpochState[E]): ShelleyGovState =
    c.pre.gov.get(n.es.ls.utxo)

  private def stake[E <: EraTag](c: Case[E], n: NewEpochState[E]): ShelleyInstantStake =
    c.pre.instantStake.get(n.es.ls.utxo)

  private def utxo[E <: EraTag](n: NewEpochState[E]): node.Utxo = n.es.ls.utxo.utxo.utxo

  private def fees[E <: EraTag](n: NewEpochState[E]): BigInt = n.es.ls.utxo.fees.value

  private def deposited[E <: EraTag](n: NewEpochState[E]): BigInt = n.es.ls.utxo.deposited.value

  /** All the lovelace of a state: the UTxO, the pots, the deposits and the reward balances. */
  private def total[E <: EraTag](c: Case[E], n: NewEpochState[E]): BigInt =
    val u = n.es.ls.utxo
    utxo(n).valuesIterator.map(_.value.coin).sum + u.fees.value + u.deposited.value +
      n.es.chainAccount.treasury.value + n.es.chainAccount.reserves.value +
      accounts(c, n).states.valuesIterator.map(_.balance.toBigInt).sum + u.donation.value

  /** What the rules keep incrementally must be what the reference defines it as. */
  private def consistent[E <: EraTag](c: Case[E], n: NewEpochState[E]): Unit =
    import c.given
    val ls = n.es.ls
    val cert = c.pre.cert.get(ls)
    assertEquals(Invariants.poolDelegators(cert), Vector.empty[Inconsistency], c.name)
    val held = cert.dState.accounts
    held.ptrs.foreach((p, k) => assertEquals(held.states.get(k).map(_.ptr), Some(p), c.name))
    val fromUtxo = ShelleyInstantStakeOps
      .update(c.pre.stakeIs(Empty.instantStake[E]), utxo(n).valuesIterator.toVector, Nil)
      .fold(e => fail(s"${c.name}: $e"), identity)
    assertEquals(stake(c, n), fromUtxo, c.name)
    assertEquals(deposited(n), NativeRules.obligation(n), c.name)

  // ---- the rules on one transaction

  private def run[E <: EraTag](
      c: Case[E],
      s: NewEpochState[E],
      t: Tx,
      ctx: TxEnv
  ): Either[LedgerFailure, Counted[NewEpochState[E]]] =
    import c.given
    NativeRules.txIn[E](s, t, ctx)

  private def acceptedOf[E <: EraTag](
      c: Case[E],
      result: Either[LedgerFailure, Counted[NewEpochState[E]]]
  ): Counted[NewEpochState[E]] =
    result.fold(
      f => fail(s"${c.name}: expected the transaction to be accepted: ${f.message}"),
      after =>
        consistent(c, after.state)
        after
    )

  private def counted[E <: EraTag](
      c: Case[E],
      s: NewEpochState[E],
      t: Tx,
      ctx: TxEnv
  ): Counted[NewEpochState[E]] =
    acceptedOf(c, run(c, s, t, ctx))

  private def accepted[E <: EraTag](
      c: Case[E],
      s: NewEpochState[E],
      t: Tx,
      ctx: TxEnv = TestLedger.env
  ): NewEpochState[E] =
    counted(c, s, t, ctx).state

  /** The predicate failure of the rule that refuses the transaction. */
  private def refused[E <: EraTag](
      c: Case[E],
      s: NewEpochState[E],
      t: Tx,
      ctx: TxEnv
  ): PredicateFailure =
    run(c, s, t, ctx) match
      case Left(LedgerFailure.Rejected(_, p)) => p
      case Left(other) => fail(s"${c.name}: expected a rule's refusal: ${other.message}")
      case Right(_)    => fail(s"${c.name}: expected a refusal")

  /** The name of the rule that refuses the transaction, as the reference names it. */
  private def refusedWith[E <: EraTag](
      c: Case[E],
      s: NewEpochState[E],
      t: Tx,
      ctx: TxEnv = TestLedger.env
  ): String =
    refused(c, s, t, ctx).ruleName

  // ---- the states and the transactions

  private val coinIn = txIn(1)

  /** What an account holds when the test starts. */
  private final case class Held(balance: BigInt, deposit: BigInt)

  /** The state of an era with `funds` as its UTxO, the accounts registered by no certificate (the
    * zero pointer and no entry in the pointer map), the pools of the operators with the pool
    * deposit and the genesis delegations; the deposits are taken out of the reserves.
    */
  private def base[E <: EraTag](
      c: Case[E],
      funds: Vector[(node.TxIn, TxOut)],
      held: Map[Key, Held] = Map.empty,
      operators: Vector[Key] = Vector.empty,
      genDelegs: Map[Bytes, GenDelegPair] = Map.empty
  ): NewEpochState[E] =
    import c.given
    val start = NativeRules.stateIn[E](c.pv, funds)
    val pooled = operators.foldLeft(start)((n, op) => Build.pool(n, poolParams(op), poolDeposit))
    val ls = pooled.es.ls
    val ds = c.pre.dState.get(ls)
    val dState = ds.copy(
      accounts = ds.accounts.copy(states = Sorted.map(held.iterator.map { (k, h) =>
        staking(k) -> ShelleyAccountState(
          Ptr(0, 0, 0),
          compact(h.balance),
          compact(h.deposit),
          None
        )
      })),
      genDelegs = Sorted.map(genDelegs.iterator.map((k, p) => genesisId(k) -> p))
    )
    // the reference fills the VRF key-count map at protocol version 11, after these eras
    val pState = c.pre.pState
      .get(ls)
      .copy(vrfKeyHashes = c.pre.pState.get(start.es.ls).vrfKeyHashes)
    val deposits = held.values.map(_.deposit).sum + poolDeposit * operators.size
    val next = c.pre.pState.replace(pState)(c.pre.dState.replace(dState)(ls))
    pooled.copy(es =
      pooled.es.copy(
        ls = next.copy[E](utxo = next.utxo.copy[E](deposited = coin(deposits))),
        chainAccount = pooled.es.chainAccount
          .copy(reserves = coin(pooled.es.chainAccount.reserves.value - deposits))
      )
    )

  /** `n` with the UTxO (and the instant stake it makes) of `of`. */
  private def withUtxoOf[E <: EraTag](n: NewEpochState[E], of: NewEpochState[E]): NewEpochState[E] =
    val ls = n.es.ls
    val from = of.es.ls.utxo
    n.copy(es =
      n.es.copy(ls =
        ls.copy[E](utxo = ls.utxo.copy[E](utxo = from.utxo, instantStake = from.instantStake))
      )
    )

  private val rich = 10000 * ada
  private val poolDeposit = 500 * ada

  Vector(alonzo, babbage).foreach { case c: Case[e] =>
    test(s"${c.name} LEDGER refuses excessive collateral even without redeemers (#178)") {
      val inputs = Vector(coinIn, txIn(2), txIn(3), txIn(4))
      val s = base(c, inputs.map(_ -> out(alice.address(), rich)))
      val t = tx(
        body(
          "collateral-count",
          Vector(coinIn),
          Vector(out(alice.address(), rich - 200)),
          200,
          collateral = inputs
        ),
        Vector(alice)
      )
      assert(t.witnesses.redeemers.isEmpty)
      assertEquals(
        run(c, s, t, TestLedger.env).left.toOption,
        Some(
          LedgerFailure.Rejected(
            t.body.id,
            node.PredicateFailure.utxo(
              node.UtxoFailure.TooManyCollateralInputs(node.Mismatch(4, 3))
            )
          )
        )
      )
      accepted(c, s, tx(t.body.copy(collateral = inputs.take(3)), Vector(alice)))
    }
  }

  private def fundedBy(k: Key, coin: BigInt = rich, i: Int = 1) =
    Vector(txIn(i) -> out(k.address(), coin))

  /** A transaction of `k` spending `coinIn`, the change back to it. */
  private def pay(
      id: String,
      k: Key,
      funds: BigInt = rich,
      fee: BigInt = 200,
      extra: BigInt = 0,
      certificates: Vector[Certificate] = Vector.empty,
      withdrawals: Map[RewardAccount, BigInt] = Map.empty,
      update: Option[Update] = None,
      signers: Vector[Key] = Vector.empty,
      ttl: Option[BigInt] = None
  ): Tx =
    val b = body(
      id,
      Vector(coinIn),
      Vector(out(k.address(), funds - fee - extra + withdrawals.map(_._2).sum)),
      fee,
      certificates = certificates,
      withdrawals = withdrawals,
      update = update,
      ttl = ttl
    )
    tx(b, (k +: signers).distinct)

  private def poolParams(operator: Key, cost: BigInt = 0): PoolParams =
    PoolParams(
      operator.hash,
      Blake2b.hash256.hash(operator.hash),
      pledge = 0,
      cost = cost,
      margin = Rational(1, 100),
      rewardAccount = RewardAccount(0, operator.credential),
      owners = Set(operator.hash),
      relays = Bytes.empty,
      metadata = None
    )

  // ---- UTXOW and UTXO

  test("a payment is accepted in every era: the outputs move and the fee is collected") {
    forEras(
      [E <: EraTag] =>
        (c: Case[E]) =>
          val s = base(c, fundedBy(alice))
          val next = accepted(c, s, pay("pay", alice))
          assertEquals(fees(next), BigInt(200), c.name)
          assertEquals(total(c, next), Supply, c.name)
          assert(!utxo(next).contains(coinIn), c.name)
    )
  }

  test(
    "the stake of an output follows its address: the credential of a base address, the pointer of a pointer address"
  ) {
    val pointer = Address
      .decode(Bytes(0x40.toByte +: (alice.hash.value ++ Vector(7, 1, 0).map(_.toByte))))
      .fold(e => fail(e.toString), identity)
    forEras(
      [E <: EraTag] =>
        (c: Case[E]) =>
          val s = base(c, fundedBy(alice, 100 * ada))
          val t = tx(
            body(
              "stake",
              Vector(coinIn),
              Vector(
                out(bob.baseAddress(carol), 40 * ada),
                out(pointer, 30 * ada),
                out(alice.address(), 30 * ada - 200)
              ),
              200
            ),
            Vector(alice)
          )
          val next = stake(c, accepted(c, s, t))
          assertEquals(
            next.credentialStake.get(staking(carol)).map(_.toBigInt),
            Some(40 * ada),
            c.name
          )
          assertEquals(next.ptrStake.get(Ptr(7, 1, 0)).map(_.toBigInt), Some(30 * ada), c.name)
    )
  }

  test("the transactions that break a rule are refused as the rule is named, in every era") {
    forEras(
      [E <: EraTag] =>
        (c: Case[E]) =>
          val s = base(c, fundedBy(alice))
          def named(t: Tx) = refusedWith(c, s, t)
          val dearer = pay("unbalanced", alice)
          assertEquals(
            named(
              dearer.copy(body =
                dearer.body.copy(outputs =
                  dearer.body.outputs.map(o => o.copy(value = Value.coin(o.value.coin + 1)))
                )
              )
            ),
            "ValueNotConservedUTxO"
          )
          assertEquals(named(pay("cheap", alice, fee = 100)), "FeeTooSmallUTxO")
          assertEquals(
            named(tx(pay("unsigned", alice).body, Vector(bob))),
            "MissingVKeyWitnessesUTXOW"
          )
          assertEquals(
            named(
              tx(
                body("missing", Vector(txIn(9)), Vector(out(alice.address(), ada)), 200),
                Vector(alice)
              )
            ),
            "BadInputsUTxO"
          )
          assertEquals(
            named(
              tx(
                body(
                  "tiny",
                  Vector(coinIn),
                  Vector(out(alice.address(), 100), out(alice.address(), rich - 300)),
                  200
                ),
                Vector(alice)
              )
            ),
            "OutputTooSmallUTxO"
          )
          // the slot is 1000: Shelley's time to live is inclusive, Allegra's upper bound exclusive
          if c.pv == 2 then
            accepted(c, s, pay("ttl", alice, ttl = Some(1000)))
            assertEquals(named(pay("late", alice, ttl = Some(999))), "ExpiredUTxO")
          else
            assertEquals(named(pay("late", alice, ttl = Some(1000))), "OutsideValidityIntervalUTxO")
    )
  }

  // ---- DELEGS: registrations, delegations, pointers

  test("a stake key is registered with its pointer, delegated, then deregistered, in every era") {
    forEras(
      [E <: EraTag] =>
        (c: Case[E]) =>
          val pool = key()
          val s = base(c, fundedBy(alice), operators = Vector(pool))
          val ctx = TestLedger.env.copy(txIndex = 3)
          val register = pay(
            "reg",
            alice,
            extra = 2 * ada,
            certificates = Vector(
              Certificate.Register(bob.credential, None),
              Certificate.Delegate(bob.credential, Some(pool.hash), None, None)
            ),
            signers = Vector(bob)
          )
          val s1 = accepted(c, s, register, ctx)
          // the pointer is the slot, the position of the transaction in the block and of the certificate
          val registered = accounts(c, s1)
          assertEquals(registered.ptrs.get(Ptr(1000, 3, 0)), Some(staking(bob)), c.name)
          assertEquals(registered.states.get(staking(bob)).map(_.ptr), Some(Ptr(1000, 3, 0)))
          assertEquals(registered.states.get(staking(bob)).flatMap(_.pool), Some(poolId(pool)))
          assertEquals(deposited(s1), 2 * ada + 500 * ada, c.name)
          val dereg = pay(
            "dereg",
            alice,
            extra = -2 * ada,
            certificates = Vector(Certificate.Unregister(bob.credential, None)),
            signers = Vector(bob)
          )
          val s2 = accepted(c, withUtxoOf(s1, s), dereg)
          assertEquals(accounts(c, s2).states.get(staking(bob)), None, c.name)
          assertEquals(accounts(c, s2).ptrs.get(Ptr(1000, 3, 0)), None, c.name)
          assertEquals(deposited(s2), 500 * ada, c.name)
    )
  }

  test(
    "a registered key cannot be registered again, an unknown one cannot delegate or be deregistered"
  ) {
    forEras(
      [E <: EraTag] =>
        (c: Case[E]) =>
          val pool = key()
          val s = base(
            c,
            fundedBy(alice),
            held = Map(bob -> Held(0, 2 * ada)),
            operators = Vector(pool)
          )
          def certify(cert: Certificate, who: Key, deposit: BigInt = 0) =
            pay("cert", alice, extra = deposit, certificates = Vector(cert), signers = Vector(who))
          assertEquals(
            refusedWith(c, s, certify(Certificate.Register(bob.credential, None), bob, 2 * ada)),
            "StakeKeyAlreadyRegisteredDELEG"
          )
          assertEquals(
            refusedWith(
              c,
              s,
              certify(Certificate.Delegate(carol.credential, Some(pool.hash), None, None), carol)
            ),
            "StakeDelegationImpossibleDELEG"
          )
          assertEquals(
            refusedWith(
              c,
              s,
              certify(Certificate.Delegate(bob.credential, Some(key().hash), None, None), bob)
            ),
            "DelegateeNotRegisteredDELEG"
          )
          assertEquals(
            refusedWith(c, s, certify(Certificate.Unregister(carol.credential, None), carol)),
            "StakeKeyNotRegisteredDELEG",
            c.name
        )
    )
  }

  test("a key with a reward balance cannot be deregistered; the refund is the deposit held") {
    forEras(
      [E <: EraTag] =>
        (c: Case[E]) =>
          val s = base(
            c,
            fundedBy(alice),
            held = Map(bob -> Held(5, 2 * ada), carol -> Held(0, 3 * ada))
          )
          assertEquals(
            refusedWith(
              c,
              s,
              pay(
                "dereg",
                alice,
                extra = -2 * ada,
                certificates = Vector(Certificate.Unregister(bob.credential, None)),
                signers = Vector(bob)
              )
            ),
            "StakeKeyNonZeroAccountBalanceDELEG"
          )
          // the reference refunds the deposit the account holds (3 ada), not the parameter's (2 ada)
          val dereg = pay(
            "refund",
            alice,
            extra = -3 * ada,
            certificates = Vector(Certificate.Unregister(carol.credential, None)),
            signers = Vector(carol)
          )
          val next = accepted(c, s, dereg)
          assertEquals(accounts(c, next).states.get(staking(carol)), None, c.name)
    )
  }

  test("withdrawals drain the accounts; a partial one is refused as the reference names it") {
    forEras(
      [E <: EraTag] =>
        (c: Case[E]) =>
          val s = base(c, fundedBy(alice), held = Map(bob -> Held(7 * ada, 2 * ada)))
          val account = RewardAccount(0, bob.credential)
          val full =
            pay("full", alice, withdrawals = Map(account -> 7 * ada), signers = Vector(bob))
          val next = accepted(c, s, full)
          assertEquals(
            accounts(c, next).states.get(staking(bob)).map(_.balance.toBigInt),
            Some(BigInt(0)),
            c.name
          )
          val partial =
            pay("partial", alice, withdrawals = Map(account -> 3 * ada), signers = Vector(bob))
          assertEquals(refusedWith(c, s, partial), "ConwayIncompleteWithdrawals", c.name)
          val unknown = pay(
            "unknown",
            alice,
            withdrawals = Map(RewardAccount(0, carol.credential) -> 1),
            signers = Vector(carol)
          )
          assertEquals(refusedWith(c, s, unknown), "ConwayWithdrawalsMissingAccounts", c.name)
    )
  }

  // ---- POOL

  test("a pool is registered, re-registered and retired in every era") {
    forEras(
      [E <: EraTag] =>
        (c: Case[E]) =>
          val op = key()
          val s = base(c, fundedBy(alice), operators = Vector(op))
          val fresh = key()
          val registered = accepted(
            c,
            s,
            pay(
              "new",
              alice,
              extra = 500 * ada,
              certificates = Vector(Certificate.RegisterPool(poolParams(fresh))),
              signers = Vector(fresh)
            )
          )
          assert(pools(c, registered).stakePools.contains(poolId(fresh)), c.name)
          assertEquals(deposited(registered), 1000 * ada, c.name)
          // a re-registration is the future parameters, and pays no deposit
          val again = accepted(
            c,
            s,
            pay(
              "again",
              alice,
              certificates = Vector(Certificate.RegisterPool(poolParams(op, cost = 340))),
              signers = Vector(op)
            )
          )
          assertEquals(pools(c, again).future.get(poolId(op)).map(_.cost), Some(BigInt(340)))
          val retiring = accepted(
            c,
            s,
            pay(
              "retire",
              alice,
              certificates = Vector(Certificate.RetirePool(op.hash, 7)),
              signers = Vector(op)
            )
          )
          assertEquals(pools(c, retiring).retiring.get(poolId(op)).map(_.toBigInt), Some(BigInt(7)))
          assertEquals(
            refusedWith(
              c,
              s,
              pay(
                "far",
                alice,
                certificates = Vector(Certificate.RetirePool(op.hash, 100)),
                signers = Vector(op)
              )
            ),
            "StakePoolRetirementWrongEpochPOOL"
          )
          assertEquals(
            refusedWith(
              c,
              s,
              pay(
                "none",
                alice,
                certificates = Vector(Certificate.RetirePool(fresh.hash, 7)),
                signers = Vector(fresh)
              )
            ),
            "StakePoolNotRegisteredOnKeyPOOL"
        )
    )
  }

  // ---- MIR and genesis delegation

  private val genesisKeys = Vector(key(), key())
  private val delegates = Vector(key(), key())

  private def genDelegs: Map[Bytes, GenDelegPair] =
    genesisKeys
      .zip(delegates)
      .zipWithIndex
      .map { case ((g, d), i) => g.hash -> GenDelegPair(d.hash, Bytes(Vector.fill(32)(i.toByte))) }
      .toMap

  private def withGenesis[E <: EraTag](c: Case[E]): NewEpochState[E] =
    base(c, fundedBy(alice), held = Map(bob -> Held(0, 2 * ada)), genDelegs = genDelegs)

  private def reserves(r: InstantaneousRewards, k: Key): Option[BigInt] =
    r.reserves.get(staking(k)).map(_.value)

  test("MIR needs the genesis quorum's signatures and moves coin at the next boundary") {
    forEras(
      [E <: EraTag] =>
        (c: Case[E]) =>
          val s = withGenesis(c)
          val cert = Certificate.MoveRewards(Pot.Reserves, Right(Map(bob.credential -> 7 * ada)))
          val signed = pay("mir", alice, certificates = Vector(cert), signers = delegates)
          val next = accepted(c, s, signed)
          assertEquals(reserves(iRewards(c, next), bob), Some(7 * ada), c.name)
          // one signature of two: UTXOW refuses it
          val partial = pay("mir", alice, certificates = Vector(cert), signers = delegates.take(1))
          assertEquals(refusedWith(c, s, partial), "MIRInsufficientGenesisSigsUTXOW", c.name)
          // too late in the epoch: the slot is within 3k/f (100) of the end of the epoch (5000)
          assertEquals(
            refusedWith(c, s, signed, TestLedger.env.copy(slot = 4950)),
            "MIRCertificateTooLateinEpochDELEG"
        )
    )
  }

  test("MIR: transfers between pots and negative entries need protocol 5 (Alonzo)") {
    forEras(
      [E <: EraTag] =>
        (c: Case[E]) =>
          val s = withGenesis(c)
          def mir(cert: Certificate) =
            pay("mir", alice, certificates = Vector(cert), signers = delegates)
          val transfer = mir(Certificate.MoveRewards(Pot.Reserves, Left(3 * ada)))
          val negative =
            mir(Certificate.MoveRewards(Pot.Treasury, Right(Map(bob.credential -> -1))))
          if c.pv >= 5 then
            val next = iRewards(c, accepted(c, s, transfer))
            assertEquals(next.deltaTreasury.value, 3 * ada, c.name)
            assertEquals(next.deltaReserves.value, -3 * ada, c.name)
            assertEquals(refusedWith(c, s, negative), "MIRProducesNegativeUpdate")
          else
            assertEquals(refusedWith(c, s, transfer), "MIRTransferNotCurrentlyAllowed")
            assertEquals(refusedWith(c, s, negative), "MIRNegativesNotCurrentlyAllowed")
    )
  }

  test("MIR: a later certificate replaces an earlier one before Alonzo and adds to it after") {
    forEras(
      [E <: EraTag] =>
        (c: Case[E]) =>
          val s = withGenesis(c)
          val twice = pay(
            "twice",
            alice,
            certificates = Vector(
              Certificate.MoveRewards(Pot.Reserves, Right(Map(bob.credential -> 3 * ada))),
              Certificate.MoveRewards(Pot.Reserves, Right(Map(bob.credential -> 4 * ada)))
            ),
            signers = delegates
          )
          val next = accepted(c, s, twice)
          assertEquals(
            reserves(iRewards(c, next), bob),
            Some(if c.pv >= 5 then 7 * ada else 4 * ada),
            c.name
        )
    )
  }

  test(
    "a genesis delegation is scheduled; the reference refuses a delegate or VRF key already used"
  ) {
    forEras(
      [E <: EraTag] =>
        (c: Case[E]) =>
          val s = withGenesis(c)
          val delegate = key()
          val vrf = Bytes(Vector.fill(32)(0x55))
          def delegation(g: Key, d: Bytes, v: Bytes) =
            pay(
              "gen",
              alice,
              certificates = Vector(Certificate.GenesisDelegate(g.hash, d, v)),
              signers = Vector(g)
            )
          val next = accepted(c, s, delegation(genesisKeys(0), delegate.hash, vrf))
          val scheduled = c.pre.dState.get(next.es.ls).futureGenDelegs
          assertEquals(
            scheduled.keysIterator.map(d => (d.slot.toBigInt, d.genKeyHash.bytes)).toVector,
            Vector((BigInt(1100), genesisKeys(0).hash)),
            c.name
          )
          assertEquals(
            refusedWith(c, s, delegation(carol, delegate.hash, vrf)),
            "GenesisKeyNotInMappingDELEG"
          )
          // the other genesis key's delegate
          val dup = delegation(genesisKeys(0), delegates(1).hash, vrf)
          assertEquals(refusedWith(c, s, dup), "DuplicateGenesisDelegateDELEG", c.name)
          val dupVrf = delegation(genesisKeys(0), delegate.hash, Bytes(Vector.fill(32)(1.toByte)))
          assertEquals(refusedWith(c, s, dupVrf), "DuplicateGenesisVRFDELEG", c.name)
    )
  }

  // ---- PPUP

  private val newDeposit =
    ParamUpdate(Map(5 -> Bytes(Vector(0x1a, 0x00, 0x2d, 0xc6, 0xc0).map(_.toByte))))

  private def proposers(m: SortedMap[KeyHash[Genesis], ParamUpdate]): Set[Bytes] =
    m.keySet.map(_.bytes)

  test(
    "update proposals: this epoch's make the prediction, next epoch's wait; wrong ones are refused"
  ) {
    forEras(
      [E <: EraTag] =>
        (c: Case[E]) =>
          val s = withGenesis(c)
          def propose(from: Vector[Int], epoch: BigInt = 5, update: ParamUpdate = newDeposit) =
            pay(
              "up",
              alice,
              update = Some(Update(from.map(i => genesisKeys(i).hash -> update).toMap, epoch)),
              signers = from.map(delegates)
            )
          val both = gov(c, accepted(c, s, propose(Vector(0, 1))))
          assertEquals(proposers(both.curProposals), genesisKeys.map(_.hash).toSet, c.name)
          // two of two voted for the same update: the prediction is the parameters it makes
          both.futurePParams match
            case FuturePParams.PotentialPParamsUpdate(Some(p)) =>
              assertEquals(p.keyDeposit, BigInt(3000000), c.name)
            case other => fail(s"${c.name}: $other")
          gov(c, accepted(c, s, propose(Vector(0)))).futurePParams match
            case FuturePParams.PotentialPParamsUpdate(None) => ()
            case other                                      => fail(s"${c.name}: $other")
          // after the point of no return (4800) a proposal is for the next epoch
          val late = TestLedger.env.copy(slot = 4900)
          val upcoming = gov(c, accepted(c, s, propose(Vector(0), epoch = 6), late))
          assertEquals(proposers(upcoming.futureProposals), Set(genesisKeys(0).hash), c.name)
          assertEquals(proposers(upcoming.curProposals), Set.empty[Bytes], c.name)
          assertEquals(refusedWith(c, s, propose(Vector(0), epoch = 9)), "PPUpdateWrongEpoch")
          assertEquals(refusedWith(c, s, propose(Vector(0), epoch = 5), late), "PPUpdateWrongEpoch")
          // a protocol version that cannot follow: (9, 0) from a version below 8
          val version = ParamUpdate(Map(14 -> Bytes(Vector(0x82, 0x09, 0x00).map(_.toByte))))
          assertEquals(
            refusedWith(c, s, propose(Vector(0), update = version)),
            "PVCannotFollowPPUP"
          )
          // not a genesis delegate's proposal
          val stranger = pay(
            "up",
            alice,
            update = Some(Update(Map(carol.hash -> newDeposit), 5)),
            signers = Vector.empty
          )
          assertEquals(refusedWith(c, s, stranger), "NonGenesisUpdatePPUP")
          // the delegate must sign
          assertEquals(
            refusedWith(
              c,
              s,
              pay("up", alice, update = Some(Update(Map(genesisKeys(0).hash -> newDeposit), 5)))
            ),
            "MissingVKeyWitnessesUTXOW"
        )
    )
  }

  // ---- Mary: native scripts and multi-asset values

  private def scriptOf(s: NativeScript): Script =
    def head(major: Int, n: Int): Vector[Byte] = Vector(((major << 5) | n).toByte)
    def go(s: NativeScript): Vector[Byte] = s match
      case NativeScript.Sig(h) =>
        head(4, 2) ++ head(0, 0) ++ Vector(0x58, 28).map(_.toByte) ++ h.value
      case NativeScript.AllOf(xs) => head(4, 2) ++ head(0, 1) ++ head(4, xs.size) ++ xs.flatMap(go)
      case other                  => fail(s"not encoded in this suite: $other")
    xyz.kd5ujc.scarab.codec.Cbor
      .decode(Bytes(go(s)))
      .flatMap(Script.native)
      .fold(e => fail(e.toString), identity)

  test("from Mary a native script mints: it must be provided, hold and be needed") {
    val signer = key()
    val policyScript = scriptOf(NativeScript.AllOf(Vector(NativeScript.Sig(signer.hash))))
    val policy = policyScript.hash
    val asset = Bytes("coin".getBytes.toVector)
    val minted = TxOut(bob.address(), Value(2 * ada, Map(policy -> Map(asset -> BigInt(100)))))
    val b = body(
      "mint",
      Vector(coinIn),
      Vector(minted, out(alice.address(), rich - 2 * ada - 200)),
      200,
      mint = Map(policy -> Map(asset -> BigInt(100)))
    )
    val provided = Witnesses(Vector.empty, scripts = Vector(policyScript))
    val extra = Witnesses(
      Vector.empty,
      scripts = Vector(policyScript, scriptOf(NativeScript.AllOf(Vector.empty)))
    )
    Vector(mary, alonzo, babbage).foreach { case c: Case[e] =>
      val s = base(c, fundedBy(alice))
      val next = accepted(c, s, tx(b, Vector(alice, signer), provided))
      assertEquals(total(c, next), Supply, c.name)
      assertEquals(
        refusedWith(c, s, tx(b, Vector(alice, signer))),
        "MissingScriptWitnessesUTXOW",
        c.name
      )
      assertEquals(
        refusedWith(c, s, tx(b, Vector(alice), provided)),
        "ScriptWitnessNotValidatingUTXOW",
        c.name
      )
      assertEquals(
        refusedWith(c, s, tx(b, Vector(alice, signer), extra)),
        "ExtraneousScriptWitnessesUTXOW",
        c.name
      )
    }
  }

  // ---- Alonzo and Babbage: Plutus scripts, collateral, reference inputs

  /** A script-locked output and the transaction that spends it, in the era of `c`. */
  private final class PlutusCase[E <: EraTag](c: Case[E]):
    // a script that deserialises, as Babbage requires
    private val plutus = Script.plutus(
      ScriptKind.PlutusV1,
      Bytes.fromArray(
        scalus.uplc
          .Program((1, 0, 0), scalus.uplc.Term.Builtin(scalus.uplc.DefaultFun.AddInteger))
          .cborEncoded
      )
    )
    private val scriptAddress =
      Address.decode(Bytes(0x70.toByte +: plutus.hash.value)).fold(e => fail(e.toString), identity)
    private val datum = Bytes(Vector(0x01))
    private val locked = TxOut(scriptAddress, Value.coin(50 * ada))
      .copy(datum = Datum.Hash(Blake2b.hash256.hash(datum)))
    val collateralIn: node.TxIn = txIn(2)
    val lockedIn: node.TxIn = txIn(3)
    val s: NewEpochState[E] =
      base(c, Vector(collateralIn -> out(alice.address(), 100 * ada), lockedIn -> locked))
    private val params = c.pre.curPParams.get(s.es.ls.utxo)
    private val units = ExUnits(1000, 1000000)
    val wits: Witnesses = Witnesses(
      Vector.empty,
      scripts = Vector(plutus),
      datums = Vector(datum),
      redeemers = Vector(Redeemer(0, 0, Bytes(Vector(0x01)), units)),
      redeemersRaw = Some(
        Bytes(
          Vector(0x81, 0x84, 0x00, 0x00, 0x01, 0x82, 0x19, 0x03, 0xe8, 0x1a, 0x00, 0x0f, 0x42, 0x40)
            .map(_.toByte)
        )
      ),
      datumsRaw = Some(Bytes(Vector(0x81, 0x01).map(_.toByte)))
    )
    private val fee = BigInt(100 + 100) + (params.priceMem * Rational(1000) +
      params.priceSteps * Rational(1000000)).ceil
    def bodyWith(hash: Option[Bytes], returned: Option[TxOut]): node.TxBody =
      body(
        "plutus",
        Vector(lockedIn),
        Vector(out(bob.address(), 50 * ada - fee)),
        fee,
        collateral = Vector(collateralIn),
        scriptDataHash = hash,
        collateralReturn = returned
      )
    val hash: Option[Bytes] =
      ScriptIntegrity.hash(
        tx(bodyWith(None, None), Vector(alice), wits),
        params,
        Set(ScriptKind.PlutusV1)
      )

  test(
    "Plutus: the verdict of the scripts must be the block's flag; a failed transaction collects its collateral"
  ) {
    Vector(alonzo, babbage).foreach { case c: Case[e] =>
      val p = PlutusCase(c)
      val s = p.s
      val b = p.bodyWith(p.hash, None)
      def ctx(succeeds: Boolean) = TestLedger.env.copy(evaluator = scripted(succeeds))
      val valid = counted(c, s, tx(b, Vector(alice), p.wits), ctx(true))
      assertEquals((valid.plutus.succeeded, valid.plutus.failed), (1L, 0L), c.name)
      assert(
        utxo(valid.state).contains(p.collateralIn) && !utxo(valid.state).contains(p.lockedIn),
        c.name
      )
      val failed = counted(c, s, tx(b, Vector(alice), p.wits, isValid = false), ctx(false))
      assertEquals((failed.plutus.succeeded, failed.plutus.failed), (0L, 1L), c.name)
      assert(
        !utxo(failed.state).contains(p.collateralIn) && utxo(failed.state).contains(p.lockedIn),
        c.name
      )
      assertEquals(fees(failed.state), 100 * ada, c.name)
      assertEquals(
        refusedWith(c, s, tx(b, Vector(alice), p.wits, isValid = true), ctx(false)),
        "ValidationTagMismatch",
        c.name
      )
      assertEquals(
        refusedWith(c, s, tx(b, Vector(alice), p.wits, isValid = false), ctx(true)),
        "ValidationTagMismatch",
        c.name
      )
      assertEquals(
        refusedWith(
          c,
          s,
          tx(p.bodyWith(Some(Bytes(Vector.fill(32)(9))), None), Vector(alice), p.wits),
          ctx(true)
        ),
        "PPViewHashesDontMatch",
        c.name
      )
      assertEquals(
        refusedWith(c, s, tx(b, Vector(alice), p.wits.copy(redeemers = Vector.empty)), ctx(true)),
        "MissingRedeemers",
        c.name
      )
    }
  }

  test("Babbage: the collateral return comes back when a transaction fails phase 2") {
    val p = PlutusCase(babbage)
    val returned = out(alice.address(), 99 * ada)
    val b = p.bodyWith(p.hash, Some(returned))
    val failed = accepted(
      babbage,
      p.s,
      tx(b, Vector(alice), p.wits, isValid = false),
      TestLedger.env.copy(evaluator = scripted(false))
    )
    assertEquals(fees(failed), ada, babbage.name)
    assert(!utxo(failed).contains(p.collateralIn))
    assertEquals(utxo(failed).get(node.TxIn(b.id, 1)), Some(returned))
    assertEquals(stake(babbage, failed).credentialStake.get(staking(alice)), None)
  }

  test("Babbage: a reference input is read and not spent") {
    val referenced = txIn(7)
    val s = base(babbage, fundedBy(alice) ++ Vector(referenced -> out(bob.address(), 5 * ada)))
    val t = tx(
      body(
        "ref",
        Vector(coinIn),
        Vector(out(alice.address(), rich - 200)),
        200,
        referenceInputs = Vector(referenced)
      ),
      Vector(alice)
    )
    val next = accepted(babbage, s, t)
    assert(utxo(next).contains(referenced))
    val missing = tx(
      body(
        "ref",
        Vector(coinIn),
        Vector(out(alice.address(), rich - 200)),
        200,
        referenceInputs = Vector(txIn(8))
      ),
      Vector(alice)
    )
    assertEquals(refusedWith(babbage, s, missing), "BadInputsUTxO")
  }

  test("the era-indexed rules and the reference stack agree: RuleT and Kleisli over StateT") {
    import cats.Monad
    import cats.data.StateT
    type R[A] = Either[LedgerFailure, A]
    type S[A] = StateT[R, Counted[LedgerState[Babbage]], A]
    type K[A] = Kleisli[S, Globals, A]
    given Monad[K] = Kleisli.catsDataMonadForKleisli[S, Globals]
    val pool = key()
    val s = base(babbage, fundedBy(alice), operators = Vector(pool))
    val start = Build.counted(s)
    val globals = NativeRules.globalsOf(TestLedger.env)
    val ledgerEnv = ShelleyLedgerEnv(TestLedger.env, s.es.chainAccount)
    val delegate = pay(
      "delegate",
      alice,
      extra = 2 * ada,
      certificates = Vector(
        Certificate.Register(bob.credential, None),
        Certificate.Delegate(bob.credential, Some(pool.hash), None, None)
      ),
      signers = Vector(bob)
    )
    for t <- Vector(delegate, pay("cheap", alice, fee = 1)) do
      val rules = ShelleyLedger.sts[Babbage]
      val onRuleT = Sts(rules)(globals, ledgerEnv, start, t)
      val onStack = rules
        .transition[K](ledgerEnv, t)(using summon, summon, summon, summon)
        .run(globals)
        .run(start)
        .map(_._1)
      assertEquals(onRuleT, onStack)
  }

  // ---- width: ADR 0011

  private def onSmallStack[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    result.getOrElse(fail("the thread did not finish")).fold(throw _, identity)

  test("a transaction of thousands of certificates and withdrawals runs on a 256 KiB stack") {
    val pool = key()
    val width = 1500
    val keys = Vector.fill(width)(key())
    // Build the signed fixture once; each era validates it on its own small-stack thread.
    val others = Vector.fill(width)(key())
    val certificates =
      others.map(k => Certificate.Register(k.credential, None)) ++
        others.map(k => Certificate.Delegate(k.credential, Some(pool.hash), None, None))
    val t = pay(
      "wide",
      alice,
      extra = 2 * ada * width,
      certificates = certificates,
      withdrawals = keys.map(k => RewardAccount(0, k.credential) -> BigInt(1)).toMap,
      signers = keys ++ others
    )
    forEras(
      [E <: EraTag] =>
        (c: Case[E]) =>
          val s = base(
            c,
            fundedBy(alice),
            held = keys.map(_ -> Held(1, 2 * ada)).toMap,
            operators = Vector(pool)
          )
          val after = acceptedOf(c, onSmallStack(run(c, s, t, TestLedger.env))).state
          assertEquals(accounts(c, after).states.size, 2 * width, c.name)
          assertEquals(accounts(c, after).ptrs.size, width, c.name)
    )
  }
