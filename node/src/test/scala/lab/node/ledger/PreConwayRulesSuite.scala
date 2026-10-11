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

  test("from Babbage a Plutus script must deserialise: an unavailable builtin is phase-1 invalid") {
    import scalus.uplc.{DefaultFun, Program, Term}
    def plutus(kind: ScriptKind, f: DefaultFun) =
      Script.plutus(kind, Bytes.fromArray(Program((1, 0, 0), Term.Builtin(f)).cborEncoded))
    val keccakV2 = plutus(ScriptKind.PlutusV2, DefaultFun.Keccak_256) // V2: protocol 11
    val ripemdV3 = plutus(ScriptKind.PlutusV3, DefaultFun.Ripemd_160) // V3: protocol 10
    val addV2 = plutus(ScriptKind.PlutusV2, DefaultFun.AddInteger)
    def check(protocol: Int, scripts: Vector[Script], referenced: Vector[Script]) =
      val (s, in) = funded(protocol)
      val outputs = referenced.map(r => out(bob.address(), 2 * ada).copy(scriptRef = Some(r)))
      val b = body("wf", Vector(in), outputs, 200)
      rule(
        PreConwayRules
          .scriptsWellFormed(
            s,
            tx(b, Vector(alice), Witnesses(Vector.empty, scripts = scripts)),
            env
          )
          .map(_ => s)
      )
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
