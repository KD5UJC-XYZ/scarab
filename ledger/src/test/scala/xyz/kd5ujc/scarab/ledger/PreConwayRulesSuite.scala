// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.byron.ByronCbor
import xyz.kd5ujc.scarab.ledger.rules.{
  Convert,
  Counted,
  EraLedger,
  EraNes,
  Invariants,
  NativeRules,
  PreConway,
  ShelleyInstantStakeOps,
  StakeDistribution
}
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  Babbage,
  BlocksMade,
  Coin as NativeCoin,
  CompactCoin,
  Conway,
  DeltaCoin,
  EraTag,
  FuturePParams,
  Genesis,
  InstantaneousRewards,
  KeyHash,
  Mary,
  NewEpochState,
  ShelleyAccountState,
  ShelleyAccounts,
  ShelleyBasedEra,
  ShelleyInstantStake,
  SnapShots,
  Sorted,
  StakePool,
  Staking,
  UTxOState,
  VocabError,
  Word64
}
import xyz.kd5ujc.scarab.ledger.state as native
import xyz.kd5ujc.scarab.ledger.state.given
import xyz.kd5ujc.scarab.plutus.ExUnits

import TestLedger.*

/** The transaction and epoch rules of the eras before Conway, on the native state of each era: the
  * era is the one the protocol version names ([[NativeRules.eraOf]]). Every transaction the rules
  * accept leaves a state whose pools list exactly the accounts that delegate to them, whose deposit
  * pot is the obligation, and whose instant stake is the UTxO's.
  */
class PreConwayRulesSuite extends ScalaCheckSuite:
  private val alice = key()
  private val bob = key()
  private val ada = BigInt(1000000)
  private val eras = Vector(2, 3, 4, 5, 7)
  private val boundaryGlobals = LedgerGlobals(Supply, 10000, Rational(1, 20), 0, updateQuorum = 2)

  private def ok[A](v: Either[VocabError, A]): A = v.fold(e => fail(e.toString), identity)
  private def coin(n: BigInt): NativeCoin = NativeCoin(n)
  private def compact(n: BigInt): CompactCoin = ok(CompactCoin.fromBigInt(n))
  private def staking(c: Credential): native.Credential[Staking] =
    ok(Convert.credential[Staking](c))
  private def poolId(k: Key): KeyHash[StakePool] = ok(KeyHash.fromBytes[StakePool](k.hash))
  private def genesisId(k: Key): KeyHash[Genesis] = ok(KeyHash.fromBytes[Genesis](k.hash))

  private def funded(pv: Int, coin: BigInt = 100 * ada): (EraNes, TxIn) =
    val in = txIn(1)
    NativeRules.state(pv, Vector(in -> out(alice.address(), coin))) -> in

  private def fundedIn[E <: EraTag](pv: Int, coin: BigInt = 100 * ada)(using
      ShelleyBasedEra[E]
  ): (NewEpochState[E], TxIn) =
    val in = txIn(1)
    NativeRules.stateIn[E](pv, Vector(in -> out(alice.address(), coin))) -> in

  // ---- reading the native state

  private def utxoState(e: EraNes): UTxOState[?] = e match
    case i: EraNes.InEra[x] => i.nes.es.ls.utxo

  private def utxo[E <: EraTag](nes: NewEpochState[E]): Utxo = nes.es.ls.utxo.utxo.utxo

  private def accounts[E <: EraTag](nes: NewEpochState[E])(using
      ShelleyBasedEra[E]
  ): Map[native.Credential[Staking], EraLedger.AccountView] = EraLedger.accounts(nes.es.ls).toMap

  private def mir[E <: EraTag](nes: NewEpochState[E])(using
      ShelleyBasedEra[E]
  ): InstantaneousRewards = EraLedger.iRewards(nes.es.ls)

  private def paramsOf[E <: EraTag](nes: NewEpochState[E])(using
      ShelleyBasedEra[E]
  ): ProtocolParams = EraLedger.curPParams(nes.es.ls.utxo.gov)

  private val noInstantRewards: InstantaneousRewards =
    InstantaneousRewards(Sorted.empty, Sorted.empty, DeltaCoin(BigInt(0)), DeltaCoin(BigInt(0)))

  /** Every lovelace of the state: the UTxO, the pots, the deposits and the reward balances. */
  private def total[E <: EraTag](nes: NewEpochState[E])(using ShelleyBasedEra[E]): BigInt =
    val u = nes.es.ls.utxo
    u.utxo.utxo.coin + u.fees.value + u.deposited.value + u.donation.value +
      nes.es.chainAccount.treasury.value + nes.es.chainAccount.reserves.value +
      EraLedger.accounts(nes.es.ls).map(_._2.balance.toBigInt).sum

  private def totalOf(e: EraNes): BigInt = e match
    case i: EraNes.InEra[x] => total(i.nes)(using i.era)

  private def accountCount(e: EraNes): Int = e match
    case i: EraNes.InEra[x] => accounts(i.nes)(using i.era).size

  /** The state as one of the era `E`, which it must be in. */
  private def as[E <: EraTag](e: EraNes)(using era: ShelleyBasedEra[E]): NewEpochState[E] =
    e match
      case i: EraNes.InEra[x] =>
        NewEpochState.retype(i.era, era)(i.nes).getOrElse(fail(s"the state is in ${i.era}"))

  // ---- building it

  /** `nes` with `deposit` more in the deposit pot and `deposit + balance` less in the reserves. */
  private def paid[E <: EraTag](
      nes: NewEpochState[E],
      deposit: BigInt,
      balance: BigInt
  ): NewEpochState[E] =
    val ls = nes.es.ls
    nes.copy(es =
      nes.es.copy(
        chainAccount = nes.es.chainAccount
          .copy(reserves = coin(nes.es.chainAccount.reserves.value - deposit - balance)),
        ls = ls.copy[E](utxo = ls.utxo.copy[E](deposited = coin(ls.utxo.deposited.value + deposit)))
      )
    )

  /** `nes` with the account of `c` registered (at a pointer of its own), holding `balance` and
    * `deposit` and delegating to `pool`; the deposit pot holds the deposit and the reserves pay for
    * both. The pool's delegators are the caller's.
    */
  private def withAccount[E <: EraTag](
      nes: NewEpochState[E],
      c: Credential,
      balance: BigInt,
      deposit: BigInt,
      pool: Option[KeyHash[StakePool]] = None
  )(using pre: PreConway[E]): NewEpochState[E] =
    val credential = staking(c)
    val ls = pre.dState.modify { d =>
      val ptr = Ptr(0, 0, d.accounts.states.size)
      val account = ShelleyAccountState(ptr, compact(balance), compact(deposit), pool)
      d.copy(accounts =
        ShelleyAccounts(
          d.accounts.states.updated(credential, account),
          d.accounts.ptrs.updated(ptr, credential)
        )
      )
    }(nes.es.ls)
    paid(nes.copy(es = nes.es.copy(ls = ls)), deposit, balance)

  /** `nes` with each genesis key delegating to its delegate (no VRF key). */
  private def withGenesis[E <: EraTag](nes: NewEpochState[E], delegations: Vector[(Key, Key)])(using
      ShelleyBasedEra[E]
  ): NewEpochState[E] =
    val current =
      Sorted.map(delegations.map((g, d) => genesisId(g) -> GenDelegPair(d.hash, Bytes.empty)))
    val ls = nes.es.ls
    nes.copy(es =
      nes.es.copy(ls = EraLedger.withGenDelegs(ls, current, EraLedger.futureGenDelegs(ls)))
    )

  // ---- running the rules

  /** What a state the rules accepted must hold: the pools list the accounts that delegate to them,
    * the deposit pot is the obligation, and the instant stake is the one of the UTxO.
    */
  private def checked[E <: EraTag](nes: NewEpochState[E])(using
      ShelleyBasedEra[E],
      PreConway[E]
  ): NewEpochState[E] =
    val ls = nes.es.ls
    assertEquals(Invariants.poolDelegators(PreConway[E].cert.get(ls)), Vector.empty)
    assertEquals(ls.utxo.deposited.value, NativeRules.obligation(nes))
    val stake = ShelleyInstantStakeOps.update(
      ShelleyInstantStake(Sorted.empty, Sorted.empty),
      utxo(nes).valuesIterator.toVector,
      Nil
    )
    assertEquals(PreConway[E].instantStake.get(ls.utxo), ok(stake))
    nes

  private def run[E <: EraTag](nes: NewEpochState[E], t: Tx, ctx: TxEnv = env)(using
      ShelleyBasedEra[E],
      PreConway[E]
  ): Either[LedgerFailure, Counted[NewEpochState[E]]] =
    NativeRules.txIn[E](nes, t, ctx).map(c => Counted(checked(c.state), c.plutus))

  private def runAny(e: EraNes, t: Tx, ctx: TxEnv = env): Either[LedgerFailure, EraNes] =
    e match
      case i: EraNes.InEra[x] =>
        given ShelleyBasedEra[x] = i.era
        given PreConway[x] = PreConway.of(i.era).getOrElse(fail(s"${i.era} is not before Conway"))
        run[x](i.nes, t, ctx).map(c => EraNes.InEra(i.era, c.state))

  private def boundary[E <: EraTag](nes: NewEpochState[E], g: LedgerGlobals)(using
      era: ShelleyBasedEra[E]
  ): NewEpochState[E] = as[E](NativeRules.boundary(EraNes.InEra(era, nes), g))

  /** The state one epoch later, after a block in the last slot of its epoch. The block's TICK is
    * past the point of no return, where the parameters of the next epoch become certain
    * (`solidifyNextEpochPParams`, `Shelley/Rules/Tick.hs`), and past the reward window, where RUPD
    * begins the epoch's reward update, which the boundary applies. A tick from earlier in the epoch
    * straight into the next does neither, so an update proposed in the epoch is adopted only this
    * way.
    */
  private def afterLastBlock[E <: EraTag](nes: NewEpochState[E], g: LedgerGlobals)(using
      era: ShelleyBasedEra[E]
  ): EraNes =
    val last = NativeRules.globals(g).firstSlot(nes.epoch.toBigInt + 1) - 1
    NativeRules.boundary(NativeRules.tick(EraNes.InEra(era, nes), last, g), g)

  private def rule(r: Either[LedgerFailure, ?]): String = r match
    case Left(LedgerFailure.Rejected(_, f)) => f.ruleName
    case Left(other)                        => other.message
    case Right(_)                           => "accepted"

  private def transfer(in: TxIn, pay: BigInt, change: BigInt, fee: BigInt = 200, id: String = "t") =
    body(id, Vector(in), Vector(out(bob.address(), pay), out(alice.address(), change)), fee)

  test("a balanced signed transfer is accepted in every era and conserves the supply") {
    eras.foreach { pv =>
      val (s, in) = funded(pv)
      val b = transfer(in, 40 * ada, 60 * ada - 200)
      val after = runAny(s, tx(b, Vector(alice))).fold(f => fail(f.message), identity)
      assertEquals(totalOf(after), Supply, s"era $pv")
      assertEquals(utxoState(after).fees.value, BigInt(200))
      assert(!utxoState(after).utxo.utxo.contains(in))
      assertEquals(utxoState(after).utxo.utxo.size, 2)
    }
  }

  test("rules reject unbalanced, underpaid, unsigned and mis-timed transactions") {
    eras.foreach { pv =>
      val (s, in) = funded(pv)
      def attempt(b: TxBody, signers: Vector[Key] = Vector(alice)) =
        rule(runAny(s, tx(b, signers)))
      assertEquals(attempt(transfer(in, 40 * ada, 60 * ada - 199)), "ValueNotConservedUTxO")
      assertEquals(attempt(transfer(in, 40 * ada, 60 * ada - 100, fee = 100)), "FeeTooSmallUTxO")
      assertEquals(
        attempt(transfer(in, 40 * ada, 60 * ada - 200), Vector(bob)),
        "MissingVKeyWitnessesUTXOW"
      )
      // the slot is 1000: Shelley's time to live is inclusive, Allegra's upper bound exclusive
      if pv == 2 then
        assertEquals(
          attempt(transfer(in, 40 * ada, 60 * ada - 200).copy(ttl = Some(1000))),
          "accepted"
        )
        assertEquals(
          attempt(transfer(in, 40 * ada, 60 * ada - 200).copy(ttl = Some(999))),
          "ExpiredUTxO"
        )
      else
        assertEquals(
          attempt(transfer(in, 40 * ada, 60 * ada - 200).copy(ttl = Some(1000))),
          "OutsideValidityIntervalUTxO"
        )
        assertEquals(
          attempt(transfer(in, 40 * ada, 60 * ada - 200).copy(validityStart = Some(1001))),
          "OutsideValidityIntervalUTxO"
        )
      assertEquals(attempt(transfer(txIn(9), 40 * ada, 60 * ada - 200)), "BadInputsUTxO")
      assertEquals(attempt(transfer(in, 100, 100 * ada - 300)), "OutputTooSmallUTxO")
    }
  }

  test("Alonzo scales the minimum output by datum hash presence, Babbage by output size") {
    val dh = Datum.Hash(Bytes(Vector.fill(32)(1)))
    val withDatum = out(bob.address(), 0).copy(datum = dh)
    val alonzo = TestLedger.params(5)
    assertEquals(TxChecks.minUTxO(alonzo, out(bob.address(), 0)), BigInt(29 * 34482))
    assertEquals(TxChecks.minUTxO(alonzo, withDatum), BigInt(39 * 34482))
    val babbage = TestLedger.params(7)
    val sized = out(bob.address(), 0).copy(raw = Bytes(Vector.fill(65)(0)))
    assertEquals(TxChecks.minUTxO(babbage, sized), BigInt(225 * 4310))
  }

  property("stake key deposits and refunds keep the supply and the deposit pot exact") {
    forAll(Gen.choose(1, 5), Gen.choose(0, 5), Gen.oneOf(eras)) { (regs, deregs, pv) =>
      val cred = (0 until regs).map(_ => key()).toVector
      val (s0, in) = funded(pv)
      val keyDeposit = TestLedger.params(pv).keyDeposit
      val deposit = keyDeposit * regs
      val reg = body(
        "reg",
        Vector(in),
        Vector(out(alice.address(), 100 * ada - deposit - 200)),
        200,
        certificates = cred.map(k => Certificate.Register(k.credential, None))
      )
      val s1 = runAny(s0, tx(reg, Vector(alice))).fold(f => fail(f.message), identity)
      assertEquals(totalOf(s1), Supply)
      assertEquals(utxoState(s1).deposited.value, deposit)
      assertEquals(accountCount(s1), regs)
      val leaving = cred.take(deregs.min(regs))
      val in2 = TxIn(reg.id, 0)
      val dereg = body(
        "dereg",
        Vector(in2),
        Vector(
          out(
            alice.address(),
            100 * ada - deposit - 200 - 200 + keyDeposit * leaving.size
          )
        ),
        200,
        certificates = leaving.map(k => Certificate.Unregister(k.credential, None))
      )
      val s2 = runAny(s1, tx(dereg, Vector(alice) ++ leaving), env.copy(txIndex = 1))
        .fold(f => fail(f.message), identity)
      assertEquals(totalOf(s2), Supply)
      assertEquals(utxoState(s2).deposited.value, keyDeposit * (regs - leaving.size))
      assertEquals(accountCount(s2), regs - leaving.size)
    }
  }

  test("deregistering needs the stake key's signature and an empty reward balance") {
    val (s0, in) = fundedIn[Mary](4)
    val stake = key()
    val reg = body(
      "reg",
      Vector(in),
      Vector(out(alice.address(), 100 * ada - 2 * ada - 200)),
      200,
      certificates = Vector(Certificate.Register(stake.credential, None))
    )
    val s1 = run(s0, tx(reg, Vector(alice))).fold(f => fail(f.message), _.state)
    val dereg = body(
      "dereg",
      Vector(TxIn(reg.id, 0)),
      Vector(out(alice.address(), 100 * ada - 2 * ada - 400 + 2 * ada)),
      200,
      certificates = Vector(Certificate.Unregister(stake.credential, None))
    )
    assertEquals(rule(run(s1, tx(dereg, Vector(alice)))), "MissingVKeyWitnessesUTXOW")
    // five lovelace of rewards in the account, paid from the reserves
    val credited = EraLedger.credit(s1.es.ls, Map(staking(stake.credential) -> compact(5)))
    val funded1 = paid(s1.copy(es = s1.es.copy(ls = credited)), 0, 5)
    assertEquals(
      rule(run(funded1, tx(dereg, Vector(alice, stake)))),
      "StakeKeyNonZeroAccountBalanceDELEG"
    )
  }

  test(
    "pool registration needs operator and owner signatures and one deposit; re-registration none"
  ) {
    val (rich, in) = fundedIn[Alonzo](5, 1000 * ada)
    val operator = key()
    val owner = key()
    val pool = PoolParams(
      operator.hash,
      Bytes(Vector.fill(32)(2)),
      pledge = 0,
      cost = 0,
      margin = Rational.zero,
      rewardAccount = RewardAccount(0, owner.credential),
      owners = Set(owner.hash),
      relays = Bytes.empty,
      metadata = None
    )
    val poolDeposit = TestLedger.params(5).poolDeposit
    val registration = body(
      "pool",
      Vector(in),
      Vector(out(alice.address(), 1000 * ada - 500 * ada - 200)),
      200,
      certificates = Vector(Certificate.RegisterPool(pool))
    )
    assertEquals(
      rule(run(rich, tx(registration, Vector(alice, operator)))),
      "MissingVKeyWitnessesUTXOW"
    )
    val s1 = run(rich, tx(registration, Vector(alice, operator, owner)))
      .fold(f => fail(f.message), _.state)
    assertEquals(s1.es.ls.utxo.deposited.value, poolDeposit)
    assertEquals(total(s1), Supply)
    val again = body(
      "pool2",
      Vector(TxIn(registration.id, 0)),
      Vector(out(alice.address(), 1000 * ada - 500 * ada - 400)),
      200,
      certificates = Vector(Certificate.RegisterPool(pool.copy(pledge = 7)))
    )
    val s2 = run(s1, tx(again, Vector(alice, operator, owner)))
      .fold(f => fail(f.message), _.state)
    assertEquals(s2.es.ls.utxo.deposited.value, poolDeposit)
    val pools = s2.es.ls.cert.pState
    assertEquals(pools.future.get(poolId(operator)).map(_.pledge), Some(BigInt(7)))
    assertEquals(pools.stakePools.get(poolId(operator)).map(_.pledge.value), Some(BigInt(0)))
  }

  test("native scripts: minting needs the policy script, which must hold and be needed") {
    val signer = key()
    val script = NativeScript.AllOf(Vector(NativeScript.Sig(signer.hash)))
    val policyScript = scriptOf(script)
    val policy = policyScript.hash
    val asset = Bytes("coin".getBytes.toVector)
    val (s, in) = fundedIn[Mary](4)
    val mint = Map(policy -> Map(asset -> BigInt(100)))
    val minted = TxOut(
      bob.address(),
      Value(2 * ada, Map(policy -> Map(asset -> BigInt(100))))
    )
    val b = body(
      "mint",
      Vector(in),
      Vector(minted, out(alice.address(), 100 * ada - 2 * ada - 200)),
      200,
      mint = mint
    )
    val withScript = Witnesses(Vector.empty, scripts = Vector(policyScript))
    assertEquals(
      rule(run(s, tx(b, Vector(alice, signer), withScript))),
      "accepted"
    )
    assertEquals(
      rule(run(s, tx(b, Vector(alice, signer)))),
      "MissingScriptWitnessesUTXOW"
    )
    assertEquals(
      rule(run(s, tx(b, Vector(alice), withScript))),
      "ScriptWitnessNotValidatingUTXOW"
    )
    val extra = Witnesses(
      Vector.empty,
      scripts = Vector(policyScript, scriptOf(NativeScript.AnyOf(Vector.empty)))
    )
    assertEquals(
      rule(run(s, tx(b, Vector(alice, signer), extra))),
      "ExtraneousScriptWitnessesUTXOW"
    )
    val burn = run(s, tx(b, Vector(alice, signer), withScript)).fold(f => fail(f.message), _.state)
    val burnTx = body(
      "burn",
      Vector(TxIn(b.id, 0)),
      Vector(out(bob.address(), 2 * ada - 200)),
      200,
      mint = Map(policy -> Map(asset -> BigInt(-100)))
    )
    val afterBurn = run(burn, tx(burnTx, Vector(bob, signer), withScript))
      .fold(f => fail(f.message), _.state)
    assertEquals(total(afterBurn), Supply)
    assert(utxo(afterBurn).valuesIterator.forall(_.value.assets.isEmpty))
  }

  private def scriptOf(s: NativeScript): Script =
    // The hash is over the CBOR of the script; encode through the ledger's own decoder inputs.
    val encoded = encode(s)
    xyz.kd5ujc.scarab.codec.Cbor
      .decode(encoded)
      .flatMap(Script.native)
      .fold(e => fail(e.toString), identity)

  private def encode(s: NativeScript): Bytes =
    def head(major: Int, n: Int): Vector[Byte] = Vector(((major << 5) | n).toByte)
    def go(s: NativeScript): Vector[Byte] = s match
      case NativeScript.Sig(h) =>
        head(4, 2) ++ head(0, 0) ++ Vector(0x58, 28).map(_.toByte) ++ h.value
      case NativeScript.AllOf(xs) => head(4, 2) ++ head(0, 1) ++ head(4, xs.size) ++ xs.flatMap(go)
      case NativeScript.AnyOf(xs) => head(4, 2) ++ head(0, 2) ++ head(4, xs.size) ++ xs.flatMap(go)
      case NativeScript.NOfK(n, xs) =>
        head(4, 3) ++ head(0, 3) ++ head(0, n.toInt) ++ head(4, xs.size) ++ xs.flatMap(go)
      case NativeScript.InvalidBefore(n) =>
        head(4, 2) ++ head(0, 4) ++ Vector(0x19, (n >> 8).toByte, n.toByte)
      case NativeScript.InvalidHereafter(n) =>
        head(4, 2) ++ head(0, 5) ++ Vector(0x19, (n >> 8).toByte, n.toByte)
    Bytes(go(s))

  test("timelock scripts follow the transaction's validity interval") {
    val k = Blake2b.hash224.hash(Bytes(Vector(1)))
    val before = NativeScript.InvalidBefore(500)
    val after = NativeScript.InvalidHereafter(2000)
    assert(before.eval(Set.empty, Some(600), None))
    assert(!before.eval(Set.empty, Some(400), None))
    assert(!before.eval(Set.empty, None, None))
    assert(after.eval(Set.empty, None, Some(1500)))
    assert(!after.eval(Set.empty, None, Some(2500)))
    assert(!after.eval(Set.empty, None, None))
    val two = NativeScript.NOfK(2, Vector(NativeScript.Sig(k), before, after))
    assert(two.eval(Set(k), Some(500), None))
    assert(!two.eval(Set.empty, Some(500), None))
    assert(two.eval(Set(k), Some(500), Some(1000)))
    assert(NativeScript.AnyOf(Vector.empty).eval(Set.empty, None, None) == false)
    assert(NativeScript.AllOf(Vector.empty).eval(Set.empty, None, None))
  }

  test("Plutus transactions: the evaluation verdict must equal the is_valid flag") {
    val plutus = Script.plutus(ScriptKind.PlutusV1, Bytes(Vector.fill(10)(7)))
    val scriptAddress =
      Address.decode(Bytes(0x70.toByte +: plutus.hash.value)).fold(e => fail(e.toString), identity)
    val datum = Bytes(Vector(0x01))
    val datumHash = Blake2b.hash256.hash(datum)
    val locked = TxOut(scriptAddress, Value.coin(50 * ada)).copy(datum = Datum.Hash(datumHash))
    val collateralIn = txIn(2)
    val lockedIn = txIn(3)
    val s = NativeRules.stateIn[Alonzo](
      5,
      Vector(collateralIn -> out(alice.address(), 100 * ada), lockedIn -> locked)
    )
    val pp = paramsOf(s)
    val units = ExUnits(1000, 1000000)
    val redeemer = Redeemer(0, 0, Bytes(Vector(0x01)), units)
    // witness-set encodings: `[ [0, 0, 1, [1000, 1000000]] ]` and `[ 1 ]`
    val wits = Witnesses(
      Vector.empty,
      scripts = Vector(plutus),
      datums = Vector(datum),
      redeemers = Vector(redeemer),
      redeemersRaw =
        Some(
          Bytes(
            Vector(0x81, 0x84, 0x00, 0x00, 0x01, 0x82, 0x19, 0x03, 0xe8, 0x1a, 0x00, 0x0f, 0x42,
              0x40).map(_.toByte)
          )
        ),
      datumsRaw = Some(Bytes(Vector(0x81, 0x01).map(_.toByte)))
    )
    val fee = BigInt(100 + 100) + (pp.priceMem * Rational(1000) +
      pp.priceSteps * Rational(1000000)).ceil
    def bodyWith(hash: Option[Bytes]) =
      body(
        "plutus",
        Vector(lockedIn),
        Vector(out(bob.address(), 50 * ada - fee)),
        fee,
        collateral = Vector(collateralIn),
        scriptDataHash = hash
      )
    val hash = ScriptIntegrity.hash(
      tx(bodyWith(None), Vector(alice), wits),
      pp,
      Set(ScriptKind.PlutusV1)
    )
    val b = bodyWith(hash)
    def attempt(succeeds: Boolean, isValid: Boolean, body: TxBody = b) =
      run(
        s,
        tx(body, Vector(alice), wits, isValid = isValid),
        env.copy(evaluator = scripted(succeeds))
      )
    val valid = attempt(succeeds = true, isValid = true).fold(f => fail(f.message), identity)
    assertEquals((valid.plutus.succeeded, valid.plutus.failed), (1L, 0L))
    assertEquals((valid.plutus.scripts, valid.plutus.integrityChecked), (1L, 1L))
    assert(utxo(valid.state).contains(collateralIn) && !utxo(valid.state).contains(lockedIn))
    assertEquals(total(valid.state), Supply)
    val failed = attempt(succeeds = false, isValid = false).fold(f => fail(f.message), identity)
    assertEquals((failed.plutus.succeeded, failed.plutus.failed), (0L, 1L))
    assert(!utxo(failed.state).contains(collateralIn) && utxo(failed.state).contains(lockedIn))
    assertEquals(failed.state.es.ls.utxo.fees.value, 100 * ada)
    assertEquals(total(failed.state), Supply)
    // The flag and the evaluation must agree, in both directions.
    assertEquals(rule(attempt(succeeds = false, isValid = true)), "ValidationTagMismatch")
    assertEquals(rule(attempt(succeeds = true, isValid = false)), "ValidationTagMismatch")
    // A script data hash that is not the recomputed one is rejected.
    assertEquals(
      rule(attempt(succeeds = true, isValid = true, bodyWith(Some(Bytes(Vector.fill(32)(9)))))),
      "PPViewHashesDontMatch"
    )
    // a missing datum or redeemer is a phase-1 failure
    assertEquals(
      rule(run(s, tx(b, Vector(alice), wits.copy(datums = Vector.empty)))),
      "MissingRequiredDatums"
    )
    assertEquals(
      rule(run(s, tx(b, Vector(alice), wits.copy(redeemers = Vector.empty)))),
      "MissingRedeemers"
    )
    // is_valid = false is meaningless when there is no Plutus script that could fail
    val (plain, in) = fundedIn[Alonzo](5)
    assertEquals(
      rule(run(plain, tx(transfer(in, 40 * ada, 60 * ada - 200), Vector(alice), isValid = false))),
      "ValidationTagMismatch"
    )
  }

  /** What the well-formedness check of the scripts (UTXOW from Babbage) says of a transaction that
    * spends the suite's first input and creates outputs holding the `referenced` scripts, with the
    * parameters of protocol version `protocol` in force.
    */
  private def wellFormed(protocol: Int, scripts: Vector[Script], referenced: Vector[Script]) =
    val outputs = referenced.map(r => out(bob.address(), 2 * ada).copy(scriptRef = Some(r)))
    val b = body("wf", Vector(txIn(1)), outputs, 200)
    rule(
      TxChecks.scriptsWellFormed(
        TestLedger.params(protocol),
        tx(b, Vector(alice), Witnesses(Vector.empty, scripts = scripts)),
        env
      )
    )

  test("from Babbage a Plutus script must deserialise: an unavailable builtin is phase-1 invalid") {
    import scalus.uplc.{DefaultFun, Program, Term}
    def plutus(kind: ScriptKind, f: DefaultFun) =
      Script.plutus(kind, Bytes.fromArray(Program((1, 0, 0), Term.Builtin(f)).cborEncoded))
    val keccakV2 = plutus(ScriptKind.PlutusV2, DefaultFun.Keccak_256) // V2: protocol 11
    val ripemdV3 = plutus(ScriptKind.PlutusV3, DefaultFun.Ripemd_160) // V3: protocol 10
    val addV2 = plutus(ScriptKind.PlutusV2, DefaultFun.AddInteger)
    def check(protocol: Int, scripts: Vector[Script], referenced: Vector[Script]) =
      wellFormed(protocol, scripts, referenced)
    assertEquals(check(8, Vector(keccakV2), Vector.empty), "MalformedScriptWitnesses")
    assertEquals(check(8, Vector.empty, Vector(keccakV2)), "MalformedReferenceScripts")
    assertEquals(check(8, Vector(addV2), Vector(addV2)), "accepted")
    assertEquals(check(7, Vector.empty, Vector(keccakV2, addV2)), "MalformedReferenceScripts")
    assertEquals(check(9, Vector(ripemdV3), Vector.empty), "MalformedScriptWitnesses")
    assertEquals(check(10, Vector(ripemdV3), Vector(ripemdV3)), "accepted")
    assertEquals(check(10, Vector(keccakV2), Vector.empty), "MalformedScriptWitnesses")
    assertEquals(check(11, Vector(keccakV2), Vector(keccakV2)), "accepted")
    // Alonzo has no such rule: an undecodable script fails phase 2 only when it is needed.
    assertEquals(check(5, Vector(keccakV2), Vector.empty), "accepted")
  }

  test(
    "a malformed value constant and the protocol-11 admission limits are phase-1 invalid (AUD-03, #166)"
  ) {
    import xyz.kd5ujc.scarab.plutus.FlatProgram as F
    val v110 = (1, 1, 0)
    def plutus(kind: ScriptKind, flat: Bytes) = Script.plutus(kind, flat)
    val a = "a".getBytes("US-ASCII")
    // a value constant with a zero quantity: the reference refuses to decode it at every protocol version
    val zeroAsset = plutus(
      ScriptKind.PlutusV2,
      F.script((1, 0, 0), F.value(Vector(a -> Vector(a -> BigInt(0)))))
    )
    val constr1024 = plutus(
      ScriptKind.PlutusV3,
      F.script(v110, F.Ap(F.Lam(F.unit), F.Constr(0, Vector.fill(1024)(F.unit))))
    )
    val constr1025 = plutus(
      ScriptKind.PlutusV3,
      F.script(v110, F.Ap(F.Lam(F.unit), F.Constr(0, Vector.fill(1025)(F.unit))))
    )
    val type31 =
      plutus(ScriptKind.PlutusV3, F.script(v110, F.Ap(F.Lam(F.unit), F.nestedEmptyList(15))))
    val type33 =
      plutus(ScriptKind.PlutusV3, F.script(v110, F.Ap(F.Lam(F.unit), F.nestedEmptyList(16))))
    def check(protocol: Int, scripts: Vector[Script], referenced: Vector[Script]) =
      wellFormed(protocol, scripts, referenced)
    for pv <- Seq(8, 10, 11) do
      assertEquals(
        check(pv, Vector(zeroAsset), Vector.empty),
        "MalformedScriptWitnesses",
        s"value $pv"
      )
      assertEquals(
        check(pv, Vector.empty, Vector(zeroAsset)),
        "MalformedReferenceScripts",
        s"value $pv"
      )
    // the limits apply from protocol 11 only; the boundary values are admitted at 11
    for pv <- Seq(9, 10, 11) do
      assertEquals(
        check(pv, Vector(constr1024, type31), Vector(constr1024, type31)),
        "accepted",
        s"$pv"
      )
    for pv <- Seq(9, 10) do
      assertEquals(
        check(pv, Vector(constr1025, type33), Vector(constr1025, type33)),
        "accepted",
        s"$pv"
      )
    assertEquals(check(11, Vector(constr1025), Vector.empty), "MalformedScriptWitnesses")
    assertEquals(check(11, Vector.empty, Vector(constr1025)), "MalformedReferenceScripts")
    assertEquals(check(11, Vector(type33), Vector.empty), "MalformedScriptWitnesses")
    assertEquals(check(11, Vector.empty, Vector(type33)), "MalformedReferenceScripts")
  }

  test("Byron outputs are spent with bootstrap witnesses whose address root matches") {
    val spender = key()
    val chainCode = Bytes(Vector.fill(32)(5))
    val attributes = Bytes(Vector(0xa0.toByte))
    val witness = BootstrapWitness(spender.vkey, Bytes(Vector.fill(64)(0)), chainCode, attributes)
    val payload = Vector(0x83, 0x58, 28).map(_.toByte) ++ witness.addressRoot.value ++
      Vector(0xa0, 0x00).map(_.toByte)
    val byron = Bytes(
      Vector(0x82, 0xd8, 0x18, 0x58, payload.size).map(_.toByte) ++ payload ++
        ByronCbor.uint(ByronCbor.crc32(Bytes(payload))).value
    )
    val address = Address.decode(byron).fold(e => fail(e.toString), identity)
    assertEquals(address.kind, Address.Kind.Byron(witness.addressRoot))
    val in = txIn(4)
    val s = NativeRules.stateIn[Alonzo](5, Vector(in -> TxOut(address, Value.coin(100 * ada))))
    val b = transfer(in, 40 * ada, 60 * ada - 200)
    val signed = witness.copy(signature = spender.sign(b.id))
    val accepted = Tx(b, Witnesses(Vector.empty, bootstrap = Vector(signed)), 100, true, None)
    assertEquals(rule(run(s, accepted)), "accepted")
    val unsigned = Tx(b, Witnesses.empty, 100, true, None)
    assertEquals(rule(run(s, unsigned)), "MissingVKeyWitnessesUTXOW")
    val forged = Tx(b, Witnesses(Vector.empty, bootstrap = Vector(witness)), 100, true, None)
    assertEquals(rule(run(s, forged)), "InvalidWitnessesUTXOW")
  }

  test("MIR needs a genesis quorum and moves coin at the next epoch boundary") {
    val g1 = key()
    val g2 = key()
    val (s0, in) = fundedIn[Alonzo](5)
    val genesisKeys = Vector(key(), key())
    val withGenesisKeys = withGenesis(s0, genesisKeys.zip(Vector(g1, g2)))
    val stake = key()
    val registered = withAccount(withGenesisKeys, stake.credential, 0, 2 * ada)
    val cert = Certificate.MoveRewards(Pot.Reserves, Right(Map(stake.credential -> 7 * ada)))
    val b = body(
      "mir",
      Vector(in),
      Vector(out(alice.address(), 100 * ada - 200)),
      200,
      certificates = Vector(cert)
    )
    assertEquals(
      rule(run(registered, tx(b, Vector(alice, g1)))),
      "MIRInsufficientGenesisSigsUTXOW"
    )
    val s1 = run(registered, tx(b, Vector(alice, g1, g2))).fold(f => fail(f.message), _.state)
    assertEquals(mir(s1).reserves.get(staking(stake.credential)).map(_.value), Some(7 * ada))
    val reserves = s1.es.chainAccount.reserves.value
    val s2 = boundary(s1, boundaryGlobals)
    assertEquals(
      accounts(s2).get(staking(stake.credential)).map(_.balance.toBigInt),
      Some(7 * ada)
    )
    assertEquals(s2.es.chainAccount.reserves.value, reserves - 7 * ada)
    assertEquals(mir(s2), noInstantRewards)
    // the account's deposit is in the pot from the start, so the boundary keeps the pot and the supply
    assertEquals(s2.es.ls.utxo.deposited.value, s1.es.ls.utxo.deposited.value)
    assertEquals(total(s2), total(s1))
    assertEquals(total(s2), Supply)
    // too late in the epoch
    assertEquals(
      rule(run(registered, tx(b, Vector(alice, g1, g2)), env.copy(slot = 4950))),
      "MIRCertificateTooLateinEpochDELEG"
    )
  }

  test("a quorum of identical update proposals changes the parameters at the boundary") {
    val genesis = Vector(key(), key(), key())
    val delegates = Vector(key(), key(), key())
    val (s0, in) = fundedIn[Alonzo](5)
    val s = withGenesis(s0, genesis.zip(delegates))
    val newDeposit =
      ParamUpdate(Map(5 -> Bytes(Vector(0x1a, 0x00, 0x2d, 0xc6, 0xc0).map(_.toByte))))
    def propose(from: Vector[Int]) =
      val u = Update(from.map(i => genesis(i).hash -> newDeposit).toMap, 5)
      val b =
        body("up", Vector(in), Vector(out(alice.address(), 100 * ada - 200)), 200, update = Some(u))
      run(s, tx(b, Vector(alice) ++ from.map(delegates))).fold(f => fail(f.message), _.state)
    val globals = LedgerGlobals(Supply, 10000, Rational(1, 20), 0, updateQuorum = 2)
    val adopted = as[Alonzo](afterLastBlock(propose(Vector(0, 1)), globals))
    assertEquals(paramsOf(adopted).keyDeposit, BigInt(3000000))
    val gov = adopted.es.ls.utxo.gov
    assert(gov.curProposals.isEmpty && gov.futureProposals.isEmpty, gov)
    val notAdopted = as[Alonzo](afterLastBlock(propose(Vector(0)), globals))
    assertEquals(paramsOf(notAdopted).keyDeposit, BigInt(2000000))
    // an unsigned proposal is refused
    val u = Update(Map(genesis(0).hash -> newDeposit), 5)
    val b =
      body("up", Vector(in), Vector(out(alice.address(), 100 * ada - 200)), 200, update = Some(u))
    assertEquals(rule(run(s, tx(b, Vector(alice)))), "MissingVKeyWitnessesUTXOW")
    // a proposal for the wrong epoch is refused
    val late = b.copy(update = Some(u.copy(epoch = 9)))
    assertEquals(rule(run(s, tx(late, Vector(alice, delegates(0))))), "PPUpdateWrongEpoch")
  }

  test(
    "the boundary that adopts protocol version 9 is a Conway transition and counts a dormant epoch"
  ) {
    val (s0, _) = fundedIn[Babbage](8)
    val pv9 = ParamUpdate(Map(14 -> Bytes(Vector(0x82, 0x09, 0x00).map(_.toByte))))
    val g = Vector(key(), key())
    val ls = s0.es.ls
    // the two proposals and the parameters they vote for, as PPUP leaves them (`Rules/Ppup.hs`)
    val gov = ls.utxo.gov
    val voted = UpdateChecks.voted(gov.curPParams, g.map(_.hash -> pv9).toMap, 2)
    val proposed = gov.copy(
      curProposals = Sorted.map(g.map(k => genesisId(k) -> pv9)),
      futurePParams = FuturePParams.PotentialPParamsUpdate(voted)
    )
    val s =
      s0.copy(es = s0.es.copy(ls = ls.copy[Babbage](utxo = ls.utxo.copy[Babbage](gov = proposed))))
    val globals = LedgerGlobals(Supply, 10000, Rational(1, 20), 0, updateQuorum = 2)
    val entered = as[Conway](afterLastBlock(s, globals))
    assertEquals(paramsOf(entered).protocolMajor, BigInt(9))
    assertEquals(entered.es.ls.cert.vState.numDormantEpochs.toBigInt, BigInt(1))
  }

  property("epoch boundaries conserve the supply however many blocks pools make") {
    forAll(Gen.choose(0, 40), Gen.choose(0, 40), Gen.choose(1, 4)) { (blocksA, blocksB, epochs) =>
      val a = key()
      val b = key()
      val staker1 = key()
      val staker2 = key()
      def pool(op: Key, owner: Key) = PoolParams(
        op.hash,
        Bytes(Vector.fill(32)(1)),
        pledge = 0,
        cost = 340,
        margin = Rational(1, 10),
        rewardAccount = RewardAccount(0, owner.credential),
        owners = Set(owner.hash),
        relays = Bytes.empty,
        metadata = None
      )
      val funds = Vector(
        txIn(1) -> out(staker1.baseAddress(staker1), 4000 * ada),
        txIn(2) -> out(staker2.baseAddress(staker2), 6000 * ada)
      )
      val s0 = NativeRules.stateIn[Alonzo](5, funds)
      // each pool registered without a deposit, its owner's account delegating to it
      def registered(op: Key, owner: Key) =
        poolId(op) -> ok(
          Convert.poolState(pool(op, owner), compact(0), Sorted.set(Seq(staking(owner.credential))))
        )
      val withPools = s0.copy(es =
        s0.es.copy(ls =
          EraLedger.withPState(
            s0.es.ls,
            s0.es.ls.cert.pState
              .copy(stakePools = Sorted.map(Vector(registered(a, staker1), registered(b, staker2))))
          )
        )
      )
      val base = withAccount(
        withAccount(withPools, staker1.credential, 0, 0, Some(poolId(a))),
        staker2.credential,
        0,
        0,
        Some(poolId(b))
      )
      val snap = StakeDistribution.snapshot(
        EraLedger.activeStake(base.es.ls),
        base.es.ls.cert.pState.stakePools
      )
      // a pool that made no block is not in the blocks made, as in the reference
      val blocks = BlocksMade(
        Sorted.map(
          Vector(poolId(a) -> blocksA, poolId(b) -> blocksB).collect {
            case (p, n) if n > 0 => p -> Word64.fromUnsigned(n.toLong)
          }
        )
      )
      val ls = base.es.ls
      val pp = paramsOf(base).copy(d = Rational.zero)
      val start = base.copy(
        bcur = blocks,
        es = base.es.copy(
          chainAccount = base.es.chainAccount
            .copy(reserves = coin(base.es.chainAccount.reserves.value - 1000 * ada)),
          ls = ls.copy[Alonzo](utxo =
            ls.utxo.copy[Alonzo](
              fees = coin(1000 * ada),
              gov = ls.utxo.gov.copy(curPParams = pp, prevPParams = pp)
            )
          ),
          snapshots = SnapShots(snap, snap, snap, coin(1000 * ada))
        )
      )
      val globals = LedgerGlobals(Supply, 100, Rational(1, 2), 0)
      // each epoch makes its reward update (a block after the start of rewards creates it) and
      // the boundary applies it; the pools make the same blocks in the epoch that follows
      val end =
        (1 to epochs).foldLeft(start) { (st, _) =>
          as[Alonzo](NativeRules.epoch(EraNes.InEra(ShelleyBasedEra.SAlonzo, st), globals))
            .copy(bcur = blocks)
        }
      assertEquals(total(end), Supply)
    }
  }

  // ---------------------------------------------------------------------------------------------
  // B7 / B12: min-UTxO, output value size, MIR rules (cardano-ledger Mary.TxOut, Allegra.Rules.Utxo,
  // Shelley.Rules.Deleg)

  private val policy = Bytes(Vector.fill(28)(0x11))
  private def assetName(s: String) = Bytes(s.getBytes.toVector)
  private def valued(coin: BigInt, assets: Map[Bytes, Map[Bytes, BigInt]]) =
    val v = Value(coin, assets)
    out(bob.address(), 0).copy(value = v)

  private def outputRule(pp: ProtocolParams, o: TxOut): String =
    TxChecks.outputChecks(pp, env, Bytes.empty, Vector(o)) match
      case Left(LedgerFailure.Rejected(_, f)) => f.ruleName
      case Left(other)                        => other.message
      case Right(_)                           => "accepted"

  test("Shelley and Allegra require exactly minUTxOValue, Mary scales it for native assets") {
    // ada-only outputs: the flat parameter in every Shelley-based era up to Mary
    List(2, 3, 4).foreach { pv =>
      assertEquals(outputRule(params(pv), valued(ada, Map.empty)), "accepted", s"era $pv")
      assertEquals(
        outputRule(params(pv), valued(ada - 1, Map.empty)),
        "OutputTooSmallUTxO",
        s"era $pv"
      )
    }
    // Mary scaledMinDeposit: max(mv, (mv / 27) * (27 + size v)); one 5-byte name: 6 + ceil(45 / 8) = 12 words
    val one = Map(policy -> Map(assetName("abcde") -> BigInt(1)))
    val mv = BigInt(1000000)
    val expected = (mv / 27) * (27 + 12)
    assertEquals(expected, BigInt(1444443))
    assertEquals(TxChecks.minUTxO(params(4), valued(0, one)), expected)
    assertEquals(outputRule(params(4), valued(expected, one)), "accepted")
    // ... but the Allegra/Mary rule tests `pointwise (<) v (inject min)`, which no value with assets
    // satisfies, so only ada-only outputs can be too small (observed on the 11.1.3 reference too)
    assertEquals(outputRule(params(4), valued(expected - 1, one)), "accepted")
    assertEquals(outputRule(params(4), valued(1, one)), "accepted")
    assertEquals(outputRule(params(5), valued(1, one)), "OutputTooSmallUTxO")
    // a small value never goes below the parameter
    assertEquals(
      TxChecks.minUTxO(params(4).copy(minUTxOValue = 40), valued(0, one)),
      BigInt(40)
    )
    // Shelley and Allegra do not scale (they cannot hold assets, the rule is the flat parameter)
    assertEquals(TxChecks.minUTxO(params(3), valued(0, one)), mv)
    // the same asset name under two policies counts once: 2 triples, 2 policies, 5 name bytes
    val twice = one.updated(Bytes(Vector.fill(28)(0x22)), Map(assetName("abcde") -> BigInt(1)))
    assertEquals(
      TxChecks.valueWords(valued(0, twice).value),
      BigInt(6 + (24 + 5 + 56 + 7) / 8)
    )
    assertEquals(TxChecks.minUTxO(params(4), valued(0, twice)), (mv / 27) * (27 + 17))
    // Alonzo divides by the 29 words of an ada-only entry instead
    assertEquals(TxChecks.minUTxO(params(5), valued(0, Map.empty)), BigInt(29 * 34482))
  }

  /** The bytes of `serialize v` as cardano-ledger-binary writes them: minimal heads, and a map of
    * more than 23 entries indefinite-length (`variableMapLenEncoding`), independent of `Value`.
    */
  private def cborValue(v: Value): Int =
    def head(major: Int, n: BigInt): Vector[Byte] =
      def be(len: Int) = (len - 1 to 0 by -1).map(i => (n >> (8 * i)).toByte).toVector
      if n < 24 then Vector(((major << 5) | n.toInt).toByte)
      else if n < 256 then ((major << 5) | 24).toByte +: be(1)
      else if n < 65536 then ((major << 5) | 25).toByte +: be(2)
      else if n < (BigInt(1) << 32) then ((major << 5) | 26).toByte +: be(4)
      else ((major << 5) | 27).toByte +: be(8)
    def bytes(b: Bytes) = head(2, b.size) ++ b.value
    def map(entries: Iterable[(Vector[Byte], Vector[Byte])]) =
      val body = entries.flatMap { case (k, x) => k ++ x }.toVector
      if entries.size <= 23 then head(5, entries.size) ++ body
      else Vector(0xbf.toByte) ++ body :+ 0xff.toByte
    val ada = head(0, v.coin)
    val encoded =
      if v.assets.isEmpty then ada
      else
        head(4, 2) ++ ada ++ map(v.assets.map { case (p, names) =>
          bytes(p) -> map(names.map { case (k, q) => bytes(k) -> head(0, q) })
        })
    encoded.size

  private def bigNames(n: Int): Map[Bytes, BigInt] =
    (0 until n).map(i => assetName(f"$i%08d") -> BigInt(1)).toMap

  test("the serialised value size matches the CBOR encoding") {
    val names = (0 until 30).map(i => Bytes(Vector.fill(i % 33)(i.toByte))).distinct
    // maps of 23, 24, 255 and 256 entries: exact head, then indefinite (2 bytes) whatever the count
    val shapes = List(
      Value.coin(0),
      Value.coin(23),
      Value.coin(24),
      Value.coin(70000),
      Value.coin(BigInt("5000000000")),
      Value(ada, Map(policy -> Map(assetName("a") -> BigInt(1)))),
      Value(
        ada,
        Map(policy -> Map(assetName("") -> BigInt(300), assetName("b" * 32) -> BigInt(70000)))
      ),
      Value(
        1,
        Map(
          policy -> names.map(x => x -> BigInt(5)).toMap,
          Bytes(Vector.fill(28)(0x33)) -> Map(assetName("z") -> BigInt("5000000000"))
        )
      ),
      Value(1, Map(policy -> bigNames(23))),
      Value(1, Map(policy -> bigNames(24))),
      Value(1, Map(policy -> bigNames(255))),
      Value(1, Map(policy -> bigNames(256))),
      Value(1, Map(policy -> bigNames(500)))
    )
    shapes.foreach(v => assertEquals(v.serializedSize, cborValue(v), v.toString))
  }

  test(
    "preprod tx 06869a76 (block 1558703): a 5001-byte definite value is 5000 bytes as the ledger writes it"
  ) {
    import xyz.kd5ujc.scarab.codec.{Cbor, Value as C}
    val hex =
      java.nio.file.Files.readString(java.nio.file.Path.of("fixtures/preprod/tx-06869a76.hex")).trim
    val bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
    val tx = Cbor.decode(bytes).fold(e => fail(e.toString), identity)
    val body = tx.value match
      case C.Arr(items) =>
        TxBody
          .decode(items.head.original, xyz.kd5ujc.scarab.protocol.Era.Babbage)
          .fold(e => fail(e.toString), identity)
      case other => fail(s"not a transaction: $other")
    assertEquals(
      Blake2b.hash256.hash(body.raw).hex,
      "06869a76132665a0667fd26edbc7adc6cd241a794416777e752829653f4a9c3c"
    )
    val big = body.outputs.head
    assertEquals(big.value.assets.values.map(_.size).toList.sorted, List(1, 500))
    // 500 names in one map: definite head 3 bytes, the ledger's indefinite form 2 bytes (+ break counted)
    assertEquals(big.value.serializedSize, 5000)
    assertEquals(cborValue(big.value), 5000)
    // Babbage on preprod, maxValueSize 5000: accepted; one byte less allowed and it would be refused
    assertEquals(outputRule(params(8), big), "accepted")
    assertEquals(
      outputRule(params(8).copy(maxValueSize = 4999), big),
      "OutputTooBigUTxO"
    )
    body.outputs.foreach(o => assert(o.value.serializedSize <= 5000, o.toString))
  }

  test("output value size: 4000 bytes in Mary, maxValueSize from Alonzo, none in Shelley") {
    def withNames(n: Int) =
      val m = (0 until n).map(i => assetName(f"$i%032d") -> BigInt(1)).toMap
      valued(1000000000, Map(policy -> m))
    // one policy: 1 + 5 (coin) + 1 + 30 (policy) + 2 (inner map head, 113 entries) + n * (2 + 32 + 1)
    assertEquals(withNames(113).value.serializedSize, 3994)
    assertEquals(withNames(114).value.serializedSize, 4029)
    List(3, 4).foreach { pv =>
      assertEquals(TxChecks.maxOutputValueSize(params(pv)), Some(BigInt(4000)))
    }
    assertEquals(outputRule(params(4), withNames(113)), "accepted")
    assertEquals(outputRule(params(4), withNames(114)), "OutputTooBigUTxO")
    // Shelley has no value size rule; a pre-Mary chain cannot carry such a value anyway
    assertEquals(TxChecks.maxOutputValueSize(params(2)), None)
    // Alonzo and later use the maxValueSize parameter (5000 here)
    List(5, 7).foreach { pv =>
      assertEquals(outputRule(params(pv), withNames(114)), "accepted", s"era $pv")
      assertEquals(outputRule(params(pv), withNames(141)), "accepted", s"era $pv")
      assertEquals(outputRule(params(pv), withNames(142)), "OutputTooBigUTxO", s"era $pv")
    }
    val tight = params(5).copy(maxValueSize = 3994)
    assertEquals(outputRule(tight, withNames(113)), "accepted")
    assertEquals(outputRule(tight.copy(maxValueSize = 3993), withNames(113)), "OutputTooBigUTxO")
  }

  /** A state of the era `E` at protocol version `pv` with two genesis keys and their delegates, and
    * a registered stake account; its funding input, the account's key and the delegates.
    */
  private def mirState[E <: EraTag](pv: Int)(using
      ShelleyBasedEra[E],
      PreConway[E]
  ): (NewEpochState[E], TxIn, Key, Vector[Key]) =
    val g = Vector(key(), key())
    val d = Vector(key(), key())
    val (s0, in) = fundedIn[E](pv)
    val stake = key()
    val s = withAccount(withGenesis(s0, g.zip(d)), stake.credential, 0, 2 * ada)
    (s, in, stake, d)

  private def mirTx[E <: EraTag](
      s: NewEpochState[E],
      in: TxIn,
      signers: Vector[Key],
      certs: Vector[Certificate],
      e: TxEnv = env
  )(using ShelleyBasedEra[E], PreConway[E]) =
    val b = body(
      "mir",
      Vector(in),
      Vector(out(alice.address(), 100 * ada - 200)),
      200,
      certificates = certs
    )
    run(s, tx(b, alice +: signers), e).map(_.state)

  test("MIR: transfers between pots and negative amounts need protocol 5 (Alonzo)") {
    val (s4, in4, stake4, d4) = mirState[Mary](4)
    val (s5, in5, stake5, d5) = mirState[Alonzo](5)
    val transfer = Certificate.MoveRewards(Pot.Reserves, Left(5 * ada))
    assertEquals(rule(mirTx(s4, in4, d4, Vector(transfer))), "MIRTransferNotCurrentlyAllowed")
    val moved = mirTx(s5, in5, d5, Vector(transfer)).fold(f => fail(f.message), identity)
    assertEquals(mir(moved).deltaReserves.value, -5 * ada)
    assertEquals(mir(moved).deltaTreasury.value, 5 * ada)
    val negative = Certificate.MoveRewards(Pot.Reserves, Right(Map(stake4.credential -> -ada)))
    assertEquals(rule(mirTx(s4, in4, d4, Vector(negative))), "MIRNegativesNotCurrentlyAllowed")
    // from Alonzo a negative entry may cancel an earlier one but not go below zero
    val plus = Certificate.MoveRewards(Pot.Reserves, Right(Map(stake5.credential -> 3 * ada)))
    val minus = Certificate.MoveRewards(Pot.Reserves, Right(Map(stake5.credential -> -(4 * ada))))
    val cancel = Certificate.MoveRewards(Pot.Reserves, Right(Map(stake5.credential -> -(3 * ada))))
    assertEquals(rule(mirTx(s5, in5, d5, Vector(plus, minus))), "MIRProducesNegativeUpdate")
    val zero = mirTx(s5, in5, d5, Vector(plus, cancel)).fold(f => fail(f.message), identity)
    assertEquals(mir(zero).reserves.get(staking(stake5.credential)).map(_.value), Some(BigInt(0)))
  }

  test("MIR: a later certificate replaces an earlier one before Alonzo and adds to it after") {
    def entry[E <: EraTag](pv: Int)(using ShelleyBasedEra[E], PreConway[E]) =
      val (s, in, stake, d) = mirState[E](pv)
      val first = Certificate.MoveRewards(Pot.Reserves, Right(Map(stake.credential -> 7 * ada)))
      val second = Certificate.MoveRewards(Pot.Reserves, Right(Map(stake.credential -> 3 * ada)))
      mirTx(s, in, d, Vector(first, second))
        .fold(f => fail(f.message), mir(_).reserves.get(staking(stake.credential)).map(_.value))
    assertEquals(entry[Mary](4), Some(3 * ada))
    assertEquals(entry[Alonzo](5), Some(10 * ada))
  }

  test("MIR is refused from the slot 3k/f before the next epoch") {
    val (s, in, stake, d) = mirState[Mary](4)
    val cert = Vector(Certificate.MoveRewards(Pot.Treasury, Right(Map(stake.credential -> ada))))
    // the treasury must hold the coin
    val pots = s.es.chainAccount
    val holding = s.copy(es =
      s.es.copy(chainAccount =
        pots.copy(treasury = coin(5 * ada), reserves = coin(pots.reserves.value - 5 * ada))
      )
    )
    val last = env.firstSlotNextEpoch - env.stabilityWindow - 1
    assertEquals(rule(mirTx(holding, in, d, cert, env.copy(slot = last))), "accepted")
    assertEquals(
      rule(mirTx(holding, in, d, cert, env.copy(slot = last + 1))),
      "MIRCertificateTooLateinEpochDELEG"
    )
    // more than the pot holds is refused whatever the slot
    val greedy =
      Vector(Certificate.MoveRewards(Pot.Treasury, Right(Map(stake.credential -> 6 * ada))))
    assertEquals(rule(mirTx(holding, in, d, greedy)), "InsufficientForInstantaneousRewardsDELEG")
  }

  test("MIR entries survive stake key deregistration and are dropped at the boundary") {
    val (s0, in, stake, _) = mirState[Mary](4)
    val ir =
      noInstantRewards.copy(reserves = Sorted.map(Seq(staking(stake.credential) -> coin(7 * ada))))
    val s = s0.copy(es = s0.es.copy(ls = EraLedger.withIRewards(s0.es.ls, ir)))
    val dereg = body(
      "dereg",
      Vector(in),
      Vector(out(alice.address(), 100 * ada - 200 + 2 * ada)),
      200,
      certificates = Vector(Certificate.Unregister(stake.credential, None))
    )
    val after = run(s, tx(dereg, Vector(alice, stake))).fold(f => fail(f.message), _.state)
    assert(!accounts(after).contains(staking(stake.credential)))
    // `Unregister` leaves `dsIRewards` alone: the entry stays until the MIR rule filters it
    assertEquals(mir(after).reserves.get(staking(stake.credential)).map(_.value), Some(7 * ada))
    val paidOut = boundary(after, boundaryGlobals)
    assertEquals(paidOut.es.chainAccount.reserves, after.es.chainAccount.reserves)
    assertEquals(mir(paidOut), noInstantRewards)
  }
