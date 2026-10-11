// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import lab.Blake2b
import lab.cbor.Bytes
import lab.node.consensus.GenDelegPair
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import TestLedger.*

class PreConwayRulesSuite extends ScalaCheckSuite:
  private val alice = key()
  private val bob = key()
  private val ada = BigInt(1000000)
  private val eras = Vector(2, 3, 4, 5, 7)

  private def funded(pv: Int, coin: BigInt = 100 * ada): (LedgerState, TxIn) =
    val in = txIn(1)
    state(pv, Vector(in -> out(alice.address(), coin))) -> in

  private def rule(r: Either[LedgerFailure, LedgerState]): String = r match
    case Left(LedgerFailure.Invalid(_, rule, _)) => rule
    case Left(other)                             => other.message
    case Right(_)                                => "accepted"

  private def transfer(in: TxIn, pay: BigInt, change: BigInt, fee: BigInt = 200, id: String = "t") =
    body(id, Vector(in), Vector(out(bob.address(), pay), out(alice.address(), change)), fee)

  test("a balanced signed transfer is accepted in every era and conserves the supply") {
    eras.foreach { pv =>
      val (s, in) = funded(pv)
      val b = transfer(in, 40 * ada, 60 * ada - 200)
      val after = TxRules(s, tx(b, Vector(alice)), env).fold(f => fail(f.message), identity)
      assertEquals(after.total, Supply, s"era $pv")
      assertEquals(after.fees, BigInt(200))
      assert(!after.utxo.contains(in))
      assertEquals(after.utxo.size, 2)
    }
  }

  test("rules reject unbalanced, underpaid, unsigned and mis-timed transactions") {
    eras.foreach { pv =>
      val (s, in) = funded(pv)
      def run(b: TxBody, signers: Vector[Key] = Vector(alice)) =
        rule(TxRules(s, tx(b, signers), env))
      assertEquals(run(transfer(in, 40 * ada, 60 * ada - 199)), "ValueNotConservedUTxO")
      assertEquals(run(transfer(in, 40 * ada, 60 * ada - 100, fee = 100)), "FeeTooSmallUTxO")
      assertEquals(
        run(transfer(in, 40 * ada, 60 * ada - 200), Vector(bob)),
        "MissingVKeyWitnessesUTXOW"
      )
      assertEquals(
        run(transfer(in, 40 * ada, 60 * ada - 200).copy(ttl = Some(1000))),
        "OutsideValidityIntervalUTxO"
      )
      assertEquals(
        run(transfer(in, 40 * ada, 60 * ada - 200).copy(validityStart = Some(1001))),
        "OutsideValidityIntervalUTxO"
      )
      assertEquals(run(transfer(txIn(9), 40 * ada, 60 * ada - 200)), "BadInputsUTxO")
      assertEquals(run(transfer(in, 100, 100 * ada - 300)), "OutputTooSmallUTxO")
    }
  }

  test("Alonzo scales the minimum output by datum hash presence, Babbage by output size") {
    val dh = Datum.Hash(Bytes(Vector.fill(32)(1)))
    val withDatum = out(bob.address(), 0).copy(datum = dh)
    val alonzo = params(5)
    assertEquals(PreConwayRules.minUTxO(alonzo, out(bob.address(), 0)), BigInt(29 * 34482))
    assertEquals(PreConwayRules.minUTxO(alonzo, withDatum), BigInt(39 * 34482))
    val babbage = params(7)
    val sized = out(bob.address(), 0).copy(raw = Bytes(Vector.fill(65)(0)))
    assertEquals(PreConwayRules.minUTxO(babbage, sized), BigInt(225 * 4310))
  }

  property("stake key deposits and refunds keep the supply and the deposit pot exact") {
    forAll(Gen.choose(1, 5), Gen.choose(0, 5), Gen.oneOf(eras)) { (regs, deregs, pv) =>
      val cred = (0 until regs).map(_ => key()).toVector
      val (s0, in) = funded(pv)
      val deposit = s0.params.keyDeposit * regs
      val reg = body(
        "reg",
        Vector(in),
        Vector(out(alice.address(), 100 * ada - deposit - 200)),
        200,
        certificates = cred.map(k => Certificate.Register(k.credential, None))
      )
      val s1 = TxRules(s0, tx(reg, Vector(alice)), env).fold(f => fail(f.message), identity)
      assertEquals(s1.total, Supply)
      assertEquals(s1.deposited, deposit)
      assertEquals(s1.accounts.size, regs)
      val leaving = cred.take(deregs.min(regs))
      val in2 = TxIn(reg.id, 0)
      val dereg = body(
        "dereg",
        Vector(in2),
        Vector(
          out(
            alice.address(),
            100 * ada - deposit - 200 - 200 + s0.params.keyDeposit * leaving.size
          )
        ),
        200,
        certificates = leaving.map(k => Certificate.Unregister(k.credential, None))
      )
      val s2 = TxRules(s1, tx(dereg, Vector(alice) ++ leaving), env.copy(txIndex = 1))
        .fold(f => fail(f.message), identity)
      assertEquals(s2.total, Supply)
      assertEquals(s2.deposited, s0.params.keyDeposit * (regs - leaving.size))
      assertEquals(s2.accounts.size, regs - leaving.size)
    }
  }

  test("deregistering needs the stake key's signature and an empty reward balance") {
    val (s0, in) = funded(4)
    val stake = key()
    val reg = body(
      "reg",
      Vector(in),
      Vector(out(alice.address(), 100 * ada - 2 * ada - 200)),
      200,
      certificates = Vector(Certificate.Register(stake.credential, None))
    )
    val s1 = TxRules(s0, tx(reg, Vector(alice)), env).fold(f => fail(f.message), identity)
    val dereg = body(
      "dereg",
      Vector(TxIn(reg.id, 0)),
      Vector(out(alice.address(), 100 * ada - 2 * ada - 400 + 2 * ada)),
      200,
      certificates = Vector(Certificate.Unregister(stake.credential, None))
    )
    assertEquals(rule(TxRules(s1, tx(dereg, Vector(alice)), env)), "MissingVKeyWitnessesUTXOW")
    val funded1 = s1.copy(accounts =
      s1.accounts.updated(stake.credential, s1.accounts(stake.credential).copy(balance = 5))
    )
    assertEquals(
      rule(TxRules(funded1, tx(dereg, Vector(alice, stake)), env)),
      "StakeKeyNonZeroAccountBalanceDELEG"
    )
  }

  test(
    "pool registration needs operator and owner signatures and one deposit; re-registration none"
  ) {
    val (s0, in) = funded(5)
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
    val rich =
      s0.copy(
        utxo = Utxo(Map(in -> out(alice.address(), 1000 * ada))),
        reserves = Supply - 1000 * ada
      )
    val registration = body(
      "pool",
      Vector(in),
      Vector(out(alice.address(), 1000 * ada - 500 * ada - 200)),
      200,
      certificates = Vector(Certificate.RegisterPool(pool))
    )
    assertEquals(
      rule(TxRules(rich, tx(registration, Vector(alice, operator)), env)),
      "MissingVKeyWitnessesUTXOW"
    )
    val s1 = TxRules(rich, tx(registration, Vector(alice, operator, owner)), env)
      .fold(f => fail(f.message), identity)
    assertEquals(s1.deposited, s0.params.poolDeposit)
    assertEquals(s1.total, Supply)
    val again = body(
      "pool2",
      Vector(TxIn(registration.id, 0)),
      Vector(out(alice.address(), 1000 * ada - 500 * ada - 400)),
      200,
      certificates = Vector(Certificate.RegisterPool(pool.copy(pledge = 7)))
    )
    val s2 = TxRules(s1, tx(again, Vector(alice, operator, owner)), env)
      .fold(f => fail(f.message), identity)
    assertEquals(s2.deposited, s0.params.poolDeposit)
    assertEquals(s2.futurePools(operator.hash).pledge, BigInt(7))
    assertEquals(s2.pools(operator.hash).params.pledge, BigInt(0))
  }

  test("native scripts: minting needs the policy script, which must hold and be needed") {
    val signer = key()
    val script = NativeScript.AllOf(Vector(NativeScript.Sig(signer.hash)))
    val policyScript = scriptOf(script)
    val policy = policyScript.hash
    val asset = Bytes("coin".getBytes.toVector)
    val (s, in) = funded(4)
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
      rule(TxRules(s, tx(b, Vector(alice, signer), withScript), env)),
      "accepted"
    )
    assertEquals(
      rule(TxRules(s, tx(b, Vector(alice, signer)), env)),
      "MissingScriptWitnessesUTXOW"
    )
    assertEquals(
      rule(TxRules(s, tx(b, Vector(alice), withScript), env)),
      "ScriptWitnessNotValidatingUTXOW"
    )
    val extra = Witnesses(
      Vector.empty,
      scripts = Vector(policyScript, scriptOf(NativeScript.AnyOf(Vector.empty)))
    )
    assertEquals(
      rule(TxRules(s, tx(b, Vector(alice, signer), extra), env)),
      "ExtraneousScriptWitnessesUTXOW"
    )
    val burn = TxRules(s, tx(b, Vector(alice, signer), withScript), env).toOption.get
    val burnTx = body(
      "burn",
      Vector(TxIn(b.id, 0)),
      Vector(out(bob.address(), 2 * ada - 200)),
      200,
      mint = Map(policy -> Map(asset -> BigInt(-100)))
    )
    val afterBurn = TxRules(burn, tx(burnTx, Vector(bob, signer), withScript), env)
      .fold(f => fail(f.message), identity)
    assertEquals(afterBurn.total, Supply)
    assert(afterBurn.utxo.valuesIterator.forall(_.value.assets.isEmpty))
  }

  private def scriptOf(s: NativeScript): Script =
    // The hash is over the CBOR of the script; encode through the ledger's own decoder inputs.
    val encoded = encode(s)
    lab.cbor.Cbor.decode(encoded).flatMap(Script.native).fold(e => fail(e), identity)

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
      Address.decode(Bytes(0x70.toByte +: plutus.hash.value)).fold(e => fail(e), identity)
    val datum = Bytes(Vector(0x01))
    val datumHash = Blake2b.hash256.hash(datum)
    val locked = TxOut(scriptAddress, Value.coin(50 * ada)).copy(datum = Datum.Hash(datumHash))
    val collateralIn = txIn(2)
    val lockedIn = txIn(3)
    val s = state(
      5,
      Vector(collateralIn -> out(alice.address(), 100 * ada), lockedIn -> locked)
    )
    val units = ExUnits(1000, 1000000)
    val redeemer = Redeemer(0, 0, Bytes(Vector(0x01)), units)
    val wits = Witnesses(
      Vector.empty,
      scripts = Vector(plutus),
      datums = Vector(datum),
      redeemers = Vector(redeemer),
      // witness-set encodings: `[ [0, 0, 1, [1000, 1000000]] ]` and `[ 1 ]`
      redeemersRaw =
        Some(
          Bytes(
            Vector(0x81, 0x84, 0x00, 0x00, 0x01, 0x82, 0x19, 0x03, 0xe8, 0x1a, 0x00, 0x0f, 0x42,
              0x40).map(_.toByte)
          )
        ),
      datumsRaw = Some(Bytes(Vector(0x81, 0x01).map(_.toByte)))
    )
    val fee = BigInt(100 + 100) + (s.params.priceMem * Rational(1000) +
      s.params.priceSteps * Rational(1000000)).ceil
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
      s.params,
      Set(ScriptKind.PlutusV1)
    )
    val b = bodyWith(hash)
    def run(succeeds: Boolean, isValid: Boolean, body: TxBody = b) =
      TxRules(
        s,
        tx(body, Vector(alice), wits, isValid = isValid),
        env.copy(evaluator = scripted(succeeds))
      )
    val valid = run(succeeds = true, isValid = true).fold(f => fail(f.message), identity)
    assertEquals((valid.plutus.succeeded, valid.plutus.failed), (1L, 0L))
    assertEquals((valid.plutus.scripts, valid.plutus.integrityChecked), (1L, 1L))
    assert(valid.utxo.contains(collateralIn) && !valid.utxo.contains(lockedIn))
    assertEquals(valid.total, Supply)
    val failed = run(succeeds = false, isValid = false).fold(f => fail(f.message), identity)
    assertEquals((failed.plutus.succeeded, failed.plutus.failed), (0L, 1L))
    assert(!failed.utxo.contains(collateralIn) && failed.utxo.contains(lockedIn))
    assertEquals(failed.fees, 100 * ada)
    assertEquals(failed.total, Supply)
    // The flag and the evaluation must agree, in both directions.
    assertEquals(rule(run(succeeds = false, isValid = true)), "ValidationTagMismatch")
    assertEquals(rule(run(succeeds = true, isValid = false)), "ValidationTagMismatch")
    // A script data hash that is not the recomputed one is rejected.
    assertEquals(
      rule(run(succeeds = true, isValid = true, bodyWith(Some(Bytes(Vector.fill(32)(9)))))),
      "PPViewHashesDontMatch"
    )
    // a missing datum or redeemer is a phase-1 failure
    assertEquals(
      rule(TxRules(s, tx(b, Vector(alice), wits.copy(datums = Vector.empty)), env)),
      "MissingRequiredDatums"
    )
    assertEquals(
      rule(TxRules(s, tx(b, Vector(alice), wits.copy(redeemers = Vector.empty)), env)),
      "MissingRedeemers"
    )
    // is_valid = false is meaningless when there is no Plutus script that could fail
    val (plain, in) = funded(5)
    assertEquals(
      rule(
        TxRules(
          plain,
          tx(transfer(in, 40 * ada, 60 * ada - 200), Vector(alice), isValid = false),
          env
        )
      ),
      "ValidationTagMismatch"
    )
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
        Vector(0x1a, 0, 0, 0, 0).map(_.toByte)
    )
    val address = Address.decode(byron).fold(e => fail(e), identity)
    assertEquals(address.kind, Address.Kind.Byron(witness.addressRoot))
    val in = txIn(4)
    val s = state(5, Vector(in -> TxOut(address, Value.coin(100 * ada))))
    val b = transfer(in, 40 * ada, 60 * ada - 200)
    val signed = witness.copy(signature = spender.sign(b.id))
    val ok = Tx(b, Witnesses(Vector.empty, bootstrap = Vector(signed)), 100, true, None)
    assertEquals(rule(TxRules(s, ok, env)), "accepted")
    val unsigned = Tx(b, Witnesses.empty, 100, true, None)
    assertEquals(rule(TxRules(s, unsigned, env)), "MissingBootstrapWitnessesUTXOW")
    val forged = Tx(b, Witnesses(Vector.empty, bootstrap = Vector(witness)), 100, true, None)
    assertEquals(rule(TxRules(s, forged, env)), "InvalidWitnessesUTXOW")
  }

  test("MIR needs a genesis quorum and moves coin at the next epoch boundary") {
    val g1 = key()
    val g2 = key()
    val (s0, in) = funded(5)
    val genesisKeys = Vector(key(), key())
    val withGenesis = s0.copy(
      genDelegs = genesisKeys
        .zip(Vector(g1, g2))
        .map { case (g, d) => g.hash -> GenDelegPair(d.hash, Bytes(Vector.fill(32)(0))) }
        .toMap
    )
    val stake = key()
    val registered = withGenesis.copy(accounts =
      withGenesis.accounts.updated(stake.credential, Account(0, 2 * ada, None, None))
    )
    val cert = Certificate.MoveRewards(Pot.Reserves, Right(Map(stake.credential -> 7 * ada)))
    val b = body(
      "mir",
      Vector(in),
      Vector(out(alice.address(), 100 * ada - 200)),
      200,
      certificates = Vector(cert)
    )
    assertEquals(
      rule(TxRules(registered, tx(b, Vector(alice, g1)), env)),
      "MIRInsufficientGenesisSigsDELEG"
    )
    val s1 =
      TxRules(registered, tx(b, Vector(alice, g1, g2)), env).fold(f => fail(f.message), identity)
    assertEquals(s1.mir.reserves(stake.credential), 7 * ada)
    val reserves = s1.reserves
    val s2 = Mir(s1)
    assertEquals(s2.accounts(stake.credential).balance, 7 * ada)
    assertEquals(s2.reserves, reserves - 7 * ada)
    assertEquals(s2.mir, InstantRewards.empty)
    assertEquals(s2.total, s1.total)
    // too late in the epoch
    assertEquals(
      rule(TxRules(registered, tx(b, Vector(alice, g1, g2)), env.copy(slot = 4950))),
      "MIRCertificateTooLateinEpochDELEG"
    )
  }

  test("a quorum of identical update proposals changes the parameters at the boundary") {
    val genesis = Vector(key(), key(), key())
    val delegates = Vector(key(), key(), key())
    val (s0, in) = funded(5)
    val s = s0.copy(genDelegs =
      genesis.zip(delegates).map((g, d) => g.hash -> GenDelegPair(d.hash, Bytes.empty)).toMap
    )
    val newDeposit =
      ParamUpdate(Map(5 -> Bytes(Vector(0x1a, 0x00, 0x2d, 0xc6, 0xc0).map(_.toByte))))
    def propose(from: Vector[Int]) =
      val u = Update(from.map(i => genesis(i).hash -> newDeposit).toMap, 5)
      val b =
        body("up", Vector(in), Vector(out(alice.address(), 100 * ada - 200)), 200, update = Some(u))
      TxRules(s, tx(b, Vector(alice) ++ from.map(delegates)), env)
        .fold(f => fail(f.message), identity)
    val globals = LedgerGlobals(Supply, 10000, Rational(1, 20), 0, updateQuorum = 2)
    val adopted = PpupRules.newEpoch(propose(Vector(0, 1)), globals)
    assertEquals(adopted.params.keyDeposit, BigInt(3000000))
    assertEquals(adopted.ppup, Ppup.empty)
    val notAdopted = PpupRules.newEpoch(propose(Vector(0)), globals)
    assertEquals(notAdopted.params.keyDeposit, BigInt(2000000))
    // an unsigned proposal is refused
    val u = Update(Map(genesis(0).hash -> newDeposit), 5)
    val b =
      body("up", Vector(in), Vector(out(alice.address(), 100 * ada - 200)), 200, update = Some(u))
    assertEquals(rule(TxRules(s, tx(b, Vector(alice)), env)), "MissingVKeyWitnessesUTXOW")
    // a proposal for the wrong epoch is refused
    val late = b.copy(update = Some(u.copy(epoch = 9)))
    assertEquals(rule(TxRules(s, tx(late, Vector(alice, delegates(0))), env)), "PPUpdateWrongEpoch")
  }

  test(
    "the boundary that adopts protocol version 9 is a Conway transition and counts a dormant epoch"
  ) {
    val (s0, _) = funded(8)
    val pv9 = ParamUpdate(Map(14 -> Bytes(Vector(0x82, 0x09, 0x00).map(_.toByte))))
    val g = Vector(key(), key())
    val s = s0.copy(ppup = Ppup(g.map(_.hash -> pv9).toMap, Map.empty))
    val globals = LedgerGlobals(Supply, 10000, Rational(1, 20), 0, updateQuorum = 2)
    val entered =
      Epoch.boundary(s.copy(rewardUpdate = Some(Rewards.update(s, globals))), s.epoch + 1, globals)
    assertEquals(entered.params.protocolMajor, BigInt(9))
    assertEquals(entered.dormantEpochs, BigInt(1))
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
      val s0 = state(5, funds)
      val pools = Map(
        a.hash -> PoolState(pool(a, staker1), 0),
        b.hash -> PoolState(pool(b, staker2), 0)
      )
      val accounts = Map(
        staker1.credential -> Account(0, 0, Some(a.hash), None),
        staker2.credential -> Account(0, 0, Some(b.hash), None)
      )
      val base = s0.copy(pools = pools, accounts = accounts)
      val snap = Stake.snapshot(base)
      val start = base.copy(
        snapshots = Snapshots(snap, snap, snap, 1000 * ada),
        fees = 1000 * ada,
        reserves = base.reserves - 1000 * ada,
        blocksCurrent = Map(a.hash -> BigInt(blocksA), b.hash -> BigInt(blocksB)),
        params = base.params.copy(d = Rational.zero)
      )
      val globals = LedgerGlobals(Supply, 100, Rational(1, 2), 0)
      val end =
        (1 to epochs).foldLeft(start.copy(rewardUpdate = Some(Rewards.update(start, globals)))) {
          (st, e) =>
            val next = Epoch.boundary(st, st.epoch + 1, globals)
            // a block after the start of rewards creates the update
            next.copy(
              rewardUpdate = next.rewardDraft,
              rewardDraft = None,
              blocksCurrent = Map(a.hash -> BigInt(blocksA), b.hash -> BigInt(blocksB))
            )
        }
      assertEquals(end.total, Supply)
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
    PreConwayRules.outputChecks(pp, env, Bytes.empty, o) match
      case Left(LedgerFailure.Invalid(_, r, _)) => r
      case Left(other)                          => other.message
      case Right(_)                             => "accepted"

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
    assertEquals(PreConwayRules.minUTxO(params(4), valued(0, one)), expected)
    assertEquals(outputRule(params(4), valued(expected, one)), "accepted")
    // ... but the Allegra/Mary rule tests `pointwise (<) v (inject min)`, which no value with assets
    // satisfies, so only ada-only outputs can be too small (observed on the 11.1.3 reference too)
    assertEquals(outputRule(params(4), valued(expected - 1, one)), "accepted")
    assertEquals(outputRule(params(4), valued(1, one)), "accepted")
    assertEquals(outputRule(params(5), valued(1, one)), "OutputTooSmallUTxO")
    // a small value never goes below the parameter
    assertEquals(
      PreConwayRules.minUTxO(params(4).copy(minUTxOValue = 40), valued(0, one)),
      BigInt(40)
    )
    // Shelley and Allegra do not scale (they cannot hold assets, the rule is the flat parameter)
    assertEquals(PreConwayRules.minUTxO(params(3), valued(0, one)), mv)
    // the same asset name under two policies counts once: 2 triples, 2 policies, 5 name bytes
    val twice = one.updated(Bytes(Vector.fill(28)(0x22)), Map(assetName("abcde") -> BigInt(1)))
    assertEquals(
      PreConwayRules.valueWords(valued(0, twice).value),
      BigInt(6 + (24 + 5 + 56 + 7) / 8)
    )
    assertEquals(PreConwayRules.minUTxO(params(4), valued(0, twice)), (mv / 27) * (27 + 17))
    // Alonzo divides by the 29 words of an ada-only entry instead
    assertEquals(PreConwayRules.minUTxO(params(5), valued(0, Map.empty)), BigInt(29 * 34482))
  }

  private def cborValue(v: Value): Int =
    import lab.cbor.{Cbor, Node, Value as C}
    def n(x: C) = Node(x, Bytes.empty)
    val ada = C.UInt(v.coin)
    val value =
      if v.assets.isEmpty then ada
      else
        C.Arr(
          Vector(
            n(ada),
            n(
              C.Map(
                v.assets.toVector.map { case (p, names) =>
                  n(C.ByteString(p)) -> n(
                    C.Map(names.toVector.map { case (k, q) => n(C.ByteString(k)) -> n(C.UInt(q)) })
                  )
                }
              )
            )
          )
        )
    Cbor.encode(value).toOption.get.size

  test("the serialised value size matches the CBOR encoding") {
    val names = (0 until 30).map(i => Bytes(Vector.fill(i % 33)(i.toByte))).distinct
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
      )
    )
    shapes.foreach(v => assertEquals(v.serializedSize, cborValue(v), v.toString))
  }

  test("output value size: 4000 bytes in Mary, maxValueSize from Alonzo, none in Shelley") {
    def withNames(n: Int) =
      val m = (0 until n).map(i => assetName(f"$i%032d") -> BigInt(1)).toMap
      valued(1000000000, Map(policy -> m))
    // one policy: 1 + 5 (coin) + 1 + 30 (policy) + 2 (inner map head, 113 entries) + n * (2 + 32 + 1)
    assertEquals(withNames(113).value.serializedSize, 3994)
    assertEquals(withNames(114).value.serializedSize, 4029)
    List(3, 4).foreach { pv =>
      assertEquals(PreConwayRules.maxOutputValueSize(params(pv)), Some(BigInt(4000)))
    }
    assertEquals(outputRule(params(4), withNames(113)), "accepted")
    assertEquals(outputRule(params(4), withNames(114)), "OutputTooBigUTxO")
    // Shelley has no value size rule; a pre-Mary chain cannot carry such a value anyway
    assertEquals(PreConwayRules.maxOutputValueSize(params(2)), None)
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

  private def mirState(pv: Int) =
    val g = Vector(key(), key())
    val d = Vector(key(), key())
    val (s0, in) = funded(pv)
    val stake = key()
    val s = s0.copy(
      genDelegs =
        g.zip(d).map { case (gk, dk) => gk.hash -> GenDelegPair(dk.hash, Bytes.empty) }.toMap,
      accounts = s0.accounts.updated(stake.credential, Account(0, 2 * ada, None, None))
    )
    (s, in, stake, d)

  private def mirTx(
      s: LedgerState,
      in: TxIn,
      signers: Vector[Key],
      certs: Vector[Certificate],
      e: TxRules.Env = env
  ) =
    val b = body(
      "mir",
      Vector(in),
      Vector(out(alice.address(), 100 * ada - 200)),
      200,
      certificates = certs
    )
    TxRules(s, tx(b, alice +: signers), e)

  test("MIR: transfers between pots and negative amounts need protocol 5 (Alonzo)") {
    val (s4, in4, stake4, d4) = mirState(4)
    val (s5, in5, stake5, d5) = mirState(5)
    val transfer = Certificate.MoveRewards(Pot.Reserves, Left(5 * ada))
    assertEquals(rule(mirTx(s4, in4, d4, Vector(transfer))), "MIRTransferNotCurrentlyAllowed")
    val moved = mirTx(s5, in5, d5, Vector(transfer)).fold(f => fail(f.message), identity)
    assertEquals(moved.mir.deltaReserves, -5 * ada)
    assertEquals(moved.mir.deltaTreasury, 5 * ada)
    val negative = Certificate.MoveRewards(Pot.Reserves, Right(Map(stake4.credential -> -ada)))
    assertEquals(rule(mirTx(s4, in4, d4, Vector(negative))), "MIRNegativesNotCurrentlyAllowed")
    // from Alonzo a negative entry may cancel an earlier one but not go below zero
    val plus = Certificate.MoveRewards(Pot.Reserves, Right(Map(stake5.credential -> 3 * ada)))
    val minus = Certificate.MoveRewards(Pot.Reserves, Right(Map(stake5.credential -> -(4 * ada))))
    val cancel = Certificate.MoveRewards(Pot.Reserves, Right(Map(stake5.credential -> -(3 * ada))))
    assertEquals(rule(mirTx(s5, in5, d5, Vector(plus, minus))), "MIRProducesNegativeUpdate")
    val zero = mirTx(s5, in5, d5, Vector(plus, cancel)).fold(f => fail(f.message), identity)
    assertEquals(zero.mir.reserves(stake5.credential), BigInt(0))
  }

  test("MIR: a later certificate replaces an earlier one before Alonzo and adds to it after") {
    def entry(pv: Int) =
      val (s, in, stake, d) = mirState(pv)
      val first = Certificate.MoveRewards(Pot.Reserves, Right(Map(stake.credential -> 7 * ada)))
      val second = Certificate.MoveRewards(Pot.Reserves, Right(Map(stake.credential -> 3 * ada)))
      mirTx(s, in, d, Vector(first, second))
        .fold(f => fail(f.message), _.mir.reserves(stake.credential))
    assertEquals(entry(4), 3 * ada)
    assertEquals(entry(5), 10 * ada)
  }

  test("MIR is refused from the slot 3k/f before the next epoch") {
    val (s, in, stake, d) = mirState(4)
    val cert = Vector(Certificate.MoveRewards(Pot.Treasury, Right(Map(stake.credential -> ada))))
    // the treasury must hold the coin
    val holding = s.copy(treasury = 5 * ada, reserves = s.reserves - 5 * ada)
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
    val (s0, in, stake, _) = mirState(4)
    val s = s0.copy(
      mir = InstantRewards(Map(stake.credential -> 7 * ada), Map.empty, 0, 0),
      deposited = 2 * ada,
      reserves = s0.reserves - 2 * ada
    )
    val dereg = body(
      "dereg",
      Vector(in),
      Vector(out(alice.address(), 100 * ada - 200 + 2 * ada)),
      200,
      certificates = Vector(Certificate.Unregister(stake.credential, None))
    )
    val after =
      TxRules(s, tx(dereg, Vector(alice, stake)), env).fold(f => fail(f.message), identity)
    assert(!after.accounts.contains(stake.credential))
    // `Unregister` leaves `dsIRewards` alone: the entry stays until the MIR rule filters it
    assertEquals(after.mir.reserves.get(stake.credential), Some(7 * ada))
    val paid = Mir(after)
    assertEquals(paid.reserves, after.reserves)
    assertEquals(paid.mir, InstantRewards.empty)
  }
