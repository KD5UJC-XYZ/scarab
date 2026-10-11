// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import lab.Blake2b
import lab.cbor.Bytes
import munit.FunSuite
import TestLedger.*

/** The Conway transaction rules that are not about governance: scripts, minting, reference-script
  * fees, collateral, treasury fields.
  */
class ConwayRulesSuite extends FunSuite:
  private val alice = key()
  private val bob = key()
  private val ada = BigInt(1000000)

  private def conway: LedgerState =
    state(10, Vector.empty).copy(params =
      params(10).copy(
        minFeeRefScriptCostPerByte = Rational(15),
        costModels = Map(0 -> Vector(1, 2, 3), 1 -> Vector(4, 5, 6, 7), 2 -> Vector(8, 9))
      )
    )

  private def withUtxo(s: LedgerState, outs: Vector[(TxIn, TxOut)]): LedgerState =
    val utxo = s.utxo ++ outs
    s.copy(utxo = utxo, reserves = Supply - utxo.valuesIterator.map(_.value.coin).sum)

  private def rule(r: Either[LedgerFailure, LedgerState]): String = r match
    case Left(LedgerFailure.Invalid(_, rule, _)) => rule
    case Left(other)                             => other.message
    case Right(_)                                => "accepted"

  // minimal CBOR writers for hand-made outputs
  private def head(major: Int, n: Int): Vector[Byte] =
    if n < 24 then Vector(((major << 5) | n).toByte)
    else if n < 256 then Vector(((major << 5) | 24).toByte, n.toByte)
    else if n < 65536 then Vector(((major << 5) | 25).toByte, (n >> 8).toByte, n.toByte)
    else ((major << 5) | 26).toByte +: Vector(24, 16, 8, 0).map(s => (n >> s).toByte)
  private def cborBytes(b: Vector[Byte]): Vector[Byte] = head(2, b.size) ++ b

  /** An output holding a reference script of `size` bytes (Plutus V3). */
  private def outputWithScript(address: Address, coin: BigInt, size: Int): TxOut =
    val script = Vector.fill(size)(0x42.toByte)
    val wrapped = head(4, 2) ++ Vector(0x03.toByte) ++ cborBytes(script)
    val raw = head(5, 3) ++ Vector(0x00.toByte) ++ cborBytes(address.raw.value) ++
      Vector(0x01.toByte) ++ head(0, coin.toInt) ++ Vector(0x03.toByte, 0xd8.toByte, 0x18.toByte) ++
      cborBytes(wrapped)
    TxOut.fromBytes(Bytes(raw)).fold(e => fail(e), identity)

  test("minting under a native script: the policy script must be provided, needed and hold") {
    val policy = Script(
      ScriptKind.Native,
      Blake2b.hash224.hash(Bytes(Vector(1, 2, 3))),
      Some(NativeScript.Sig(alice.hash))
    )
    val in = txIn(1)
    val s = withUtxo(conway, Vector(in -> out(alice.address(), 100 * ada)))
    val token = Map(policy.hash -> Map(Bytes(Vector(0x61)) -> BigInt(5)))
    val minted = TxOut(bob.address(), Value(10 * ada, token))
    def tryMint(signers: Vector[Key], scripts: Vector[Script]) =
      val b = body(
        "mint",
        Vector(in),
        Vector(minted, out(alice.address(), 90 * ada - 200)),
        200,
        mint = token
      )
      rule(TxRules(s, tx(b, signers, Witnesses(Vector.empty, scripts = scripts)), env))
    assertEquals(tryMint(Vector(alice), Vector(policy)), "accepted")
    assertEquals(tryMint(Vector(alice), Vector.empty), "MissingScriptWitnessesUTXOW")
    assertEquals(tryMint(Vector(bob), Vector(policy)), "MissingVKeyWitnessesUTXOW")
    val superfluous = policy.copy(hash = Blake2b.hash224.hash(Bytes(Vector(9))))
    assertEquals(
      tryMint(Vector(alice), Vector(policy, superfluous)),
      "ExtraneousScriptWitnessesUTXOW"
    )
  }

  test("burning and minting conserve the assets") {
    val policy = Script(
      ScriptKind.Native,
      Blake2b.hash224.hash(Bytes(Vector(1))),
      Some(NativeScript.Sig(alice.hash))
    )
    val name = Bytes(Vector(0x62))
    val have = Map(policy.hash -> Map(name -> BigInt(9)))
    val in = txIn(1)
    val s = withUtxo(conway, Vector(in -> TxOut(alice.address(), Value(100 * ada, have))))
    val burn = Map(policy.hash -> Map(name -> BigInt(-4)))
    val b = body(
      "burn",
      Vector(in),
      Vector(
        TxOut(alice.address(), Value(100 * ada - 200, Map(policy.hash -> Map(name -> BigInt(5)))))
      ),
      200,
      mint = burn
    )
    val result =
      TxRules(s, tx(b, Vector(alice), Witnesses(Vector.empty, scripts = Vector(policy))), env)
    assertEquals(rule(result), "accepted")
    val unbalanced = body(
      "burn2",
      Vector(in),
      Vector(TxOut(alice.address(), Value(100 * ada - 200, have))),
      200,
      mint = burn
    )
    assertEquals(
      rule(
        TxRules(
          s,
          tx(unbalanced, Vector(alice), Witnesses(Vector.empty, scripts = Vector(policy))),
          env
        )
      ),
      "ValueNotConservedUTxO"
    )
  }

  test("a reference script costs 15 lovelace per byte, 1.2 times more for every further 25 KiB") {
    val spend = txIn(1)
    val ref = txIn(2)
    val script = outputWithScript(bob.address(), 5 * ada, 30000)
    assertEquals(script.referenceScriptSize, 30000)
    val s = withUtxo(
      conway,
      Vector(spend -> out(alice.address(), 100 * ada), ref -> script)
    )
    val extra = BigInt(25600) * 15 + BigInt(4400) * 18
    def run(fee: BigInt) =
      val b = body(
        "ref",
        Vector(spend),
        Vector(out(alice.address(), 100 * ada - fee)),
        fee,
        referenceInputs = Vector(ref)
      )
      rule(TxRules(s, tx(b, Vector(alice)), env))
    assertEquals(run(200), "FeeTooSmallUTxO")
    assertEquals(run(200 + extra - 1), "FeeTooSmallUTxO")
    assertEquals(run(200 + extra), "accepted")
  }

  test("a transaction may use at most 200 KiB of reference scripts") {
    val spend = txIn(1)
    val refs = Vector(txIn(2), txIn(3))
    val s = withUtxo(
      conway.copy(params = conway.params.copy(minFeeRefScriptCostPerByte = Rational.zero)),
      Vector(spend -> out(alice.address(), 100 * ada)) ++
        refs.map(_ -> outputWithScript(bob.address(), 5 * ada, 104 * 1024))
    )
    def run(referenceInputs: Vector[TxIn]) =
      val b = body(
        "refs",
        Vector(spend),
        Vector(out(alice.address(), 100 * ada - 200)),
        200,
        referenceInputs = referenceInputs
      )
      rule(TxRules(s, tx(b, Vector(alice)), env))
    assertEquals(run(refs.take(1)), "accepted")
    assertEquals(run(refs), "ConwayTxRefScriptsSizeTooBig")
  }

  test("an input may not also be a reference input before protocol version 11") {
    val spend = txIn(1)
    val s = withUtxo(conway, Vector(spend -> out(alice.address(), 100 * ada)))
    val b = body(
      "overlap",
      Vector(spend),
      Vector(out(alice.address(), 100 * ada - 200)),
      200,
      referenceInputs = Vector(spend)
    )
    assertEquals(rule(TxRules(s, tx(b, Vector(alice)), env)), "BabbageNonDisjointRefInputs")
    val later = s.copy(params = s.params.copy(protocolMajor = 11))
    assertEquals(rule(TxRules(later, tx(b, Vector(alice)), env)), "accepted")
  }

  test(
    "Plutus V3: the evaluation verdict must equal is_valid and the redeemer map is hashed as is"
  ) {
    val plutus = Script.plutus(ScriptKind.PlutusV3, Bytes(Vector.fill(10)(7)))
    val scriptAddress =
      Address.decode(Bytes(0x70.toByte +: plutus.hash.value)).fold(e => fail(e), identity)
    val datum = Bytes(Vector(0x01))
    val locked = TxOut(scriptAddress, Value.coin(50 * ada))
      .copy(datum = Datum.Hash(Blake2b.hash256.hash(datum)))
    val collateralIn = txIn(2)
    val lockedIn = txIn(3)
    val s = withUtxo(
      conway,
      Vector(collateralIn -> out(alice.address(), 100 * ada), lockedIn -> locked)
    )
    val units = ExUnits(1000, 1000000)
    val wits = Witnesses(
      Vector.empty,
      scripts = Vector(plutus),
      datums = Vector(datum),
      redeemers = Vector(Redeemer(0, 0, Bytes(Vector(0x01)), units)),
      // `{ [0, 0] : [1, [1000, 1000000]] }` and `[ 1 ]`
      redeemersRaw = Some(
        Bytes(
          Vector(0xa1, 0x82, 0x00, 0x00, 0x82, 0x01, 0x82, 0x19, 0x03, 0xe8, 0x1a, 0x00, 0x0f, 0x42,
            0x40).map(_.toByte)
        )
      ),
      datumsRaw = Some(Bytes(Vector(0x81, 0x01).map(_.toByte)))
    )
    val fee = BigInt(100 + 100) + (s.params.priceMem * Rational(1000) +
      s.params.priceSteps * Rational(1000000)).ceil
    def bodyWith(hash: Option[Bytes], collateral: Vector[TxIn] = Vector(collateralIn)) =
      body(
        "v3",
        Vector(lockedIn),
        Vector(out(bob.address(), 50 * ada - fee)),
        fee,
        collateral = collateral,
        scriptDataHash = hash
      )
    val hash = ScriptIntegrity.hash(
      tx(bodyWith(None), Vector(alice), wits),
      s.params,
      Set(ScriptKind.PlutusV3)
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
    assertEquals(valid.total, Supply)
    val failed = run(succeeds = false, isValid = false).fold(f => fail(f.message), identity)
    assert(!failed.utxo.contains(collateralIn) && failed.utxo.contains(lockedIn))
    assertEquals(failed.total, Supply)
    assertEquals(rule(run(succeeds = false, isValid = true)), "ValidationTagMismatch")
    assertEquals(rule(run(succeeds = true, isValid = false)), "ValidationTagMismatch")
    // the hash is over the redeemers exactly as they were sent
    assertEquals(
      rule(
        run(succeeds = true, isValid = true, bodyWith(Some(Blake2b.hash256.hash(Bytes(Vector(1))))))
      ),
      "PPViewHashesDontMatch"
    )
    // a script without collateral
    assertEquals(
      rule(run(succeeds = true, isValid = true, bodyWith(hash, Vector.empty))),
      "NoCollateralInputs"
    )
  }

  test(
    "withdrawals from a script reward account need the script, and rewards are drained in full"
  ) {
    val script = Script(
      ScriptKind.Native,
      Blake2b.hash224.hash(Bytes(Vector(7))),
      Some(NativeScript.Sig(alice.hash))
    )
    val in = txIn(1)
    val base = withUtxo(conway, Vector(in -> out(alice.address(), 100 * ada)))
    val s = base.copy(
      accounts = Map(Credential.Script(script.hash) -> Account(5 * ada, 2 * ada, None, None)),
      deposited = 2 * ada,
      reserves = base.reserves - 7 * ada
    )
    val account = RewardAccount(0, Credential.Script(script.hash))
    def run(amount: BigInt, scripts: Vector[Script]) =
      val b = body(
        "wd",
        Vector(in),
        Vector(out(alice.address(), 100 * ada + amount - 200)),
        200,
        withdrawals = Vector(account -> amount)
      )
      rule(TxRules(s, tx(b, Vector(alice), Witnesses(Vector.empty, scripts = scripts)), env))
    assertEquals(run(5 * ada, Vector(script)), "accepted")
    assertEquals(run(5 * ada, Vector.empty), "MissingScriptWitnessesUTXOW")
    assertEquals(run(4 * ada, Vector(script)), "ConwayIncompleteWithdrawals")
  }

  test("withdrawals from key accounts need a DRep delegation from protocol version 10") {
    val staker = key()
    val in = txIn(1)
    val base = withUtxo(conway, Vector(in -> out(alice.address(), 100 * ada)))
    def world(drep: Option[DRep], pv: Int) =
      base.copy(
        params = base.params.copy(protocolMajor = pv),
        accounts = Map(staker.credential -> Account(3 * ada, 2 * ada, None, drep)),
        deposited = 2 * ada,
        reserves = base.reserves - 5 * ada
      )
    val b = body(
      "wd",
      Vector(in),
      Vector(out(alice.address(), 103 * ada - 200)),
      200,
      withdrawals = Vector(RewardAccount(0, staker.credential) -> (3 * ada))
    )
    def run(s: LedgerState) = rule(TxRules(s, tx(b, Vector(alice, staker)), env))
    assertEquals(run(world(None, 9)), "accepted")
    assertEquals(run(world(None, 10)), "ConwayWdrlNotDelegatedToDRep")
    assertEquals(run(world(Some(DRep.AlwaysAbstain), 10)), "accepted")
  }

  test("governance fields are checked: wrong deposit, unknown return account, unknown voter") {
    val in = txIn(1)
    val s0 = withUtxo(conway, Vector(in -> out(alice.address(), 10000 * ada)))
    val staker = key()
    val s = s0.copy(
      params = s0.params.copy(govActionDeposit = 100 * ada),
      accounts = Map(staker.credential -> Account(0, 2 * ada, None, None)),
      deposited = 2 * ada,
      reserves = s0.reserves - 2 * ada
    )
    def propose(deposit: BigInt, account: Credential) =
      val p = Proposal(deposit, RewardAccount(0, account), GovAction.Info)
      val b = body(
        "p",
        Vector(in),
        Vector(out(alice.address(), 10000 * ada - 200 - 100 * ada)),
        200,
        proposals = Vector(p)
      )
      rule(TxRules(s, tx(b, Vector(alice)), env))
    assertEquals(propose(100 * ada, staker.credential), "accepted")
    assertEquals(propose(99 * ada, staker.credential), "ProposalDepositIncorrect")
    assertEquals(propose(100 * ada, bob.credential), "ProposalReturnAccountDoesNotExist")
    val ghost = GovActionId(Bytes(Vector.fill(32)(1)), 0)
    val vote = body(
      "v",
      Vector(in),
      Vector(out(alice.address(), 10000 * ada - 200)),
      200,
      votes = Vector(Voter.DRepVoter(bob.credential) -> Vector(ghost -> Vote.Yes))
    )
    assertEquals(rule(TxRules(s, tx(vote, Vector(alice, bob)), env)), "VotersDoNotExist")
  }

  test("committee certificates: unknown members and resigned members are refused") {
    val cold = key()
    val hot = key()
    val in = txIn(1)
    val s0 = withUtxo(conway, Vector(in -> out(alice.address(), 100 * ada)))
    val member = s0.copy(gov =
      s0.gov.copy(committee = Some(Committee(Map(cold.credential -> BigInt(100)), Rational(1, 2))))
    )
    def run(s: LedgerState, cert: Certificate, signer: Key) =
      val spend = s.utxo.keys.head
      val coin = s.utxo(spend).value.coin
      val b = body(
        "cc",
        Vector(spend),
        Vector(out(alice.address(), coin - 200)),
        200,
        certificates = Vector(cert)
      )
      TxRules(s, tx(b, Vector(alice, signer)), env)
    assertEquals(
      rule(run(s0, Certificate.CommitteeAuthorize(cold.credential, hot.credential), cold)),
      "ConwayCommitteeIsUnknown"
    )
    val authorised =
      run(member, Certificate.CommitteeAuthorize(cold.credential, hot.credential), cold)
        .fold(f => fail(f.message), identity)
    assertEquals(authorised.committeeState(cold.credential), CommitteeAuth.Hot(hot.credential))
    val resigned = run(authorised, Certificate.CommitteeResign(cold.credential, None), cold)
      .fold(f => fail(f.message), identity)
    assertEquals(resigned.committeeState(cold.credential), CommitteeAuth.Resigned(None))
    assertEquals(
      rule(run(resigned, Certificate.CommitteeAuthorize(cold.credential, hot.credential), cold)),
      "ConwayCommitteeHasPreviouslyResigned"
    )
  }

  test("dormant epochs are counted when no proposal is open, and a proposal revives the DReps") {
    val drep = key()
    val in = txIn(1)
    val s0 = withUtxo(conway, Vector(in -> out(alice.address(), 10000 * ada)))
    val staker = key()
    val s = s0.copy(
      params =
        s0.params.copy(govActionDeposit = 100 * ada, drepActivity = 20, govActionLifetime = 5),
      accounts = Map(staker.credential -> Account(0, 2 * ada, None, None)),
      dreps = Map(drep.credential -> DRepState(500 * ada, 30)),
      deposited = 502 * ada,
      reserves = s0.reserves - 502 * ada
    )
    val globals = LedgerGlobals(Supply, 100, Rational(1, 20), network = 0)
    val idle = Iterator.iterate(s)(x => Epoch.boundary(x, x.epoch + 1, globals)).drop(3).next()
    assertEquals(idle.dormantEpochs, BigInt(3))
    val p = Proposal(100 * ada, RewardAccount(0, staker.credential), GovAction.Info)
    val b = body(
      "p",
      Vector(in),
      Vector(out(alice.address(), 10000 * ada - 200 - 100 * ada)),
      200,
      proposals = Vector(p)
    )
    val revived = TxRules(idle, tx(b, Vector(alice)), env.copy(epoch = idle.epoch))
      .fold(f => fail(f.message), identity)
    assertEquals(revived.dormantEpochs, BigInt(0))
    assertEquals(revived.dreps(drep.credential).expiry, BigInt(33))
    // with a proposal open the epochs are not dormant
    val busy = Epoch.boundary(revived, revived.epoch + 1, globals)
    assertEquals(busy.dormantEpochs, BigInt(0))
  }
