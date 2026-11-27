// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.ledger.state.Network
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.rules.{
  Convert,
  RuleT,
  EraNes,
  InstantStakeOps,
  Invariants,
  NativeRules
}
import xyz.kd5ujc.scarab.ledger.state as native
import xyz.kd5ujc.scarab.ledger.state.{
  Build,
  ColdCommittee,
  CommitteeAuthorization,
  CompactCoin,
  Conway,
  ConwayAccountState,
  ConwayGovState,
  ConwayInstantStake,
  ConwayNewEpochState,
  DRepRole,
  EpochNo,
  HotCommittee,
  NewEpochState,
  ShelleyBasedEra,
  Sorted,
  Staking,
  VocabError
}
import xyz.kd5ujc.scarab.plutus.{ExUnits, PlutusLanguage}
import cats.data.NonEmptyList
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit.Fails
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayCertsPredFailure,
  ConwayGovCertPredFailure,
  ConwayGovPredFailure,
  ConwayLedgerPredFailure,
  ConwayUtxoPredFailure,
  ConwayUtxosPredFailure,
  ConwayUtxowPredFailure,
  LedgerPredFailure,
  TagMismatchDescription
}

import TestLedger.*

/** The Conway transaction rules that are not about governance: scripts, minting, reference-script
  * fees, collateral, treasury fields, on the native Conway state. Every transaction the rules
  * accept leaves a state whose pools list exactly the accounts that delegate to them, whose deposit
  * pot is the obligation, and whose instant stake is the UTxO's.
  *
  * The rules are Conway's LEDGER on the era types ([[ConwayTestKit.judge]]), on the transactions
  * the suite builds as values written in Conway's CDDL ([[ConwayTestKit.encode]]), each judged to
  * its first failure, which names a refusal.
  */
class ConwayRulesSuite extends FunSuite:
  private type Nes = NewEpochState[Conway]

  private val alice = key()
  private val bob = key()
  private val ada = BigInt(1000000)

  // the fee for size is the constant 200 whatever the size, as it was for the transactions of 100
  // bytes the suite built as values (`TestLedger.tx`) under `minFeeA = 1, minFeeB = 100`: their
  // true bytes are larger, and the fees and their mismatches the tests name stay as they were
  private val conwayParams: ProtocolParams =
    params(10).copy(
      minFeeA = 0,
      minFeeB = 200,
      minFeeRefScriptCostPerByte = Rational(15),
      costModels = Map(0 -> Vector(1, 2, 3), 1 -> Vector(4, 5, 6, 7), 2 -> Vector(8, 9))
    )

  private def ok[A](v: Either[VocabError, A]): A = v.fold(e => fail(e.toString), identity)

  /** An empty Conway chain in epoch 5 with `pp` in force, `funds` as its UTxO set and the reserves
    * what makes the total [[Supply]] ([[NativeRules.stateIn]] with parameters of the test's own).
    */
  private def conway(funds: Vector[(TxIn, TxOut)], pp: ProtocolParams = conwayParams): Nes =
    val nes = Build.funded(
      Build.nes[Conway](5, pp),
      funds.map((in, o) => in -> (if o.raw.isEmpty then ConwayTestKit.stored(o) else o)).toMap
    )
    ConwayNewEpochState.reserves.replace(native.Coin(Supply - utxo(nes).coin))(nes)

  /** `nes` with the account of `c` registered, holding `balance` and `deposit`, delegating its vote
    * to `drep`; the deposit pot holds the deposit and the reserves pay for both.
    */
  private def withAccount(
      nes: Nes,
      c: Credential,
      balance: BigInt,
      deposit: BigInt,
      drep: Option[DRep] = None
  ): Nes =
    val account = ConwayAccountState(
      ok(CompactCoin.fromBigInt(balance)),
      ok(CompactCoin.fromBigInt(deposit)),
      None,
      drep.map(d => ok(Convert.drep(d)))
    )
    val accounts =
      ConwayNewEpochState.accounts.modify(a =>
        a.copy(states = a.states.updated(ok(Convert.credential[Staking](c)), account))
      )(nes)
    paid(accounts, deposit, balance)

  /** `nes` with `deposit` more in the deposit pot and `deposit + balance` less in the reserves. */
  private def paid(nes: Nes, deposit: BigInt, balance: BigInt): Nes =
    val ls = nes.es.ls
    val deposited = native.Coin(ls.utxo.deposited.value + deposit)
    val withPot = nes.copy(es =
      nes.es.copy(ls = ls.copy[Conway](utxo = ls.utxo.copy[Conway](deposited = deposited)))
    )
    ConwayNewEpochState.reserves.modify(r => native.Coin(r.value - deposit - balance))(withPot)

  private def utxo(nes: Nes): Utxo = nes.es.ls.utxo.utxo.utxo

  /** Every lovelace of the state: the UTxO, the pots, the deposits and the reward balances. */
  private def total(nes: Nes): BigInt =
    val u = nes.es.ls.utxo
    utxo(nes).coin + u.fees.value + u.deposited.value + u.donation.value +
      nes.es.chainAccount.treasury.value + nes.es.chainAccount.reserves.value +
      nes.es.ls.cert.dState.accounts.states.valuesIterator.map(_.balance.toBigInt).sum

  private def inConway(e: EraNes): Nes = e match
    case i: EraNes.InEra[x] =>
      NewEpochState
        .retype(i.era, ShelleyBasedEra.SConway)(i.nes)
        .getOrElse(fail(s"the state is in ${i.era}, not in Conway"))

  /** What a state the rules accepted must hold: the pools list the accounts that delegate to them,
    * the deposit pot is the obligation, and the instant stake is the one of the UTxO.
    */
  private def checked(nes: Nes): Nes =
    val ls = nes.es.ls
    assertEquals(Invariants.poolDelegators(ls.cert), Vector.empty)
    assertEquals(ls.utxo.deposited.value, NativeRules.obligation(nes))
    val stake = InstantStakeOps.update(
      ConwayInstantStake(Sorted.empty),
      utxo(nes).valuesIterator.toVector,
      Nil
    )
    assertEquals(ls.utxo.instantStake, ok(stake))
    nes

  private type Failure = LedgerPredFailure[Conway]
  private type Verdict = Either[Vector[Failure], ConwayTestKit.Admitted]

  private def run(s: Nes, t: ConwayTestKit.Built, ctx: TxEnv = env): Verdict =
    ConwayTestKit
      .judge(s, t, ctx, RuleT.Policy.FirstFailure)
      .map(a => a.copy(nes = checked(a.nes)))

  /** The transaction of `b`, signed by `signers`, with `witnesses` beside their key witnesses,
    * written with its true bytes ([[ConwayTestKit.encode]]).
    */
  private def tx(
      b: TxBody,
      signers: Vector[Key],
      witnesses: Witnesses = Witnesses.empty,
      isValid: Boolean = true
  ): ConwayTestKit.Built =
    ConwayTestKit.encode(TestLedger.tx(b, Vector.empty, witnesses, isValid), signers)

  private def admitted(r: Verdict): ConwayTestKit.Admitted =
    r.fold(f => fail(s"expected the transaction to be accepted, refused: $f"), identity)

  /** `r` is a refusal for the failure `rule` names, the first check that failed. */
  private def refusedBy(r: Verdict)(rule: PartialFunction[Failure, Unit])(using
      munit.Location
  ): Unit = r match
    case Left(Vector(f)) => assert(rule.isDefinedAt(f), s"refused for $f")
    case other           => fail(s"expected a refusal for one failure: $other")

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
    TxOut.fromBytes(Bytes(raw)).fold(e => fail(e.toString), identity)

  test("minting under a native script: the policy script must be provided, needed and hold") {
    val policy = ConwayTestKit.native(NativeScript.Sig(alice.hash))
    val in = txIn(1)
    val s = conway(Vector(in -> out(alice.address(), 100 * ada)))
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
      run(s, tx(b, signers, Witnesses(Vector.empty, scripts = scripts)))
    admitted(tryMint(Vector(alice), Vector(policy)))
    refusedBy(tryMint(Vector(alice), Vector.empty)) {
      case Fails.Utxow(ConwayUtxowPredFailure.MissingScriptWitnessesUTXOW(_)) =>
    }
    // the needed native scripts are checked first (`Babbage/Rules/Utxow.hs:344-349`,
    // cardano-ledger f649f975)
    refusedBy(tryMint(Vector(bob), Vector(policy))) {
      case Fails.Utxow(ConwayUtxowPredFailure.ScriptWitnessNotValidatingUTXOW(_)) =>
    }
    val superfluous = ConwayTestKit.native(NativeScript.Sig(bob.hash))
    refusedBy(tryMint(Vector(alice), Vector(policy, superfluous))) {
      case Fails.Utxow(ConwayUtxowPredFailure.ExtraneousScriptWitnessesUTXOW(_)) =>
    }
  }

  test("burning and minting conserve the assets") {
    val policy = ConwayTestKit.native(NativeScript.Sig(alice.hash))
    val name = Bytes(Vector(0x62))
    val have = Map(policy.hash -> Map(name -> BigInt(9)))
    val in = txIn(1)
    val s = conway(Vector(in -> TxOut(alice.address(), Value(100 * ada, have))))
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
    admitted(run(s, tx(b, Vector(alice), Witnesses(Vector.empty, scripts = Vector(policy)))))
    val unbalanced = body(
      "burn2",
      Vector(in),
      Vector(TxOut(alice.address(), Value(100 * ada - 200, have))),
      200,
      mint = burn
    )
    refusedBy(
      run(s, tx(unbalanced, Vector(alice), Witnesses(Vector.empty, scripts = Vector(policy))))
    ) { case Fails.Utxo(ConwayUtxoPredFailure.ValueNotConservedUTxO(_)) => }
  }

  test("a reference script costs 15 lovelace per byte, 1.2 times more for every further 25 KiB") {
    val spend = txIn(1)
    val ref = txIn(2)
    val script = outputWithScript(bob.address(), 5 * ada, 30000)
    assertEquals(script.referenceScriptSize, 30000L)
    val s = conway(Vector(spend -> out(alice.address(), 100 * ada), ref -> script))
    val extra = BigInt(25600) * 15 + BigInt(4400) * 18
    def attempt(fee: BigInt) =
      val b = body(
        "ref",
        Vector(spend),
        Vector(out(alice.address(), 100 * ada - fee)),
        fee,
        referenceInputs = Vector(ref)
      )
      run(s, tx(b, Vector(alice)))
    def tooSmall(fee: BigInt) =
      refusedBy(attempt(fee)) {
        case Fails.Utxo(
              ConwayUtxoPredFailure.FeeTooSmallUTxO(era.Mismatch(supplied, required))
            ) if supplied == native.Coin(fee) && required == native.Coin(200 + extra) =>
      }
    tooSmall(200)
    tooSmall(200 + extra - 1)
    admitted(attempt(200 + extra))
  }

  test("a transaction may use at most 200 KiB of reference scripts") {
    val spend = txIn(1)
    val refs = Vector(txIn(2), txIn(3))
    val s = conway(
      Vector(spend -> out(alice.address(), 100 * ada)) ++
        refs.map(_ -> outputWithScript(bob.address(), 5 * ada, 104 * 1024)),
      conwayParams.copy(minFeeRefScriptCostPerByte = Rational.zero)
    )
    def attempt(referenceInputs: Vector[TxIn]) =
      val b = body(
        "refs",
        Vector(spend),
        Vector(out(alice.address(), 100 * ada - 200)),
        200,
        referenceInputs = referenceInputs
      )
      run(s, tx(b, Vector(alice)))
    admitted(attempt(refs.take(1)))
    refusedBy(attempt(refs)) { case ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig(_) => }
  }

  test("the reference scripts of a transaction are added up in 64 bits, as the reference's Int") {
    // `txNonDistinctRefScriptsSize :: ... -> Int` (`Conway/UTxO.hs:166-170`,
    // cardano-ledger f649f975): 2048 reference inputs, each naming a script of 1 MiB, hold 2^31
    // bytes, one more than a 32-bit Int holds
    val script = outputWithScript(bob.address(), 5 * ada, 1 << 20)
    val refs = Vector.tabulate(2048)(i => TxIn(txIn(2).id, i))
    val spend = txIn(1)
    val s = conway(
      Vector(spend -> out(alice.address(), 100 * ada)) ++ refs.map(_ -> script),
      conwayParams.copy(minFeeRefScriptCostPerByte = Rational.zero)
    )
    val b = body(
      "wide",
      Vector(spend),
      Vector(out(alice.address(), 99 * ada)),
      ada,
      referenceInputs = refs
    )
    val total = BigInt(1) << 31
    val result = run(s, tx(b, Vector(alice)))
    assertEquals(
      result,
      Left(
        Vector(
          ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig(
            era.Mismatch(total.toLong, RefScripts.MaxRefScriptSizePerTx.toLong)
          )
        )
      )
    )
    // the distinct inputs and reference inputs' scripts, added up as the reference's `Int`
    val held = utxo(s)
    assertEquals(
      BigInt(
        (b.inputs.toSet ++ b.referenceInputs.toSet).iterator
          .flatMap(held.get)
          .map(_.referenceScriptSize)
          .sum
      ),
      total
    )
  }

  test("an input may not also be a reference input before protocol version 11") {
    val spend = txIn(1)
    val funds = Vector(spend -> out(alice.address(), 100 * ada))
    val b = body(
      "overlap",
      Vector(spend),
      Vector(out(alice.address(), 100 * ada - 200)),
      200,
      referenceInputs = Vector(spend)
    )
    refusedBy(run(conway(funds), tx(b, Vector(alice)))) {
      case Fails.Utxo(ConwayUtxoPredFailure.BabbageNonDisjointRefInputs(_)) =>
    }
    val later = conway(funds, conwayParams.copy(protocolMajor = 11))
    admitted(run(later, tx(b, Vector(alice))))
  }

  test(
    "Plutus V3: the evaluation verdict must equal is_valid and the redeemer map is hashed as is"
  ) {
    val plutus = Script.plutus(ScriptKind.PlutusV3, Bytes(Vector.fill(10)(7)))
    val scriptAddress =
      Address.decode(Bytes(0x70.toByte +: plutus.hash.value)).fold(e => fail(e.toString), identity)
    val datum = Bytes(Vector(0x01))
    val locked = TxOut(scriptAddress, Value.coin(50 * ada))
      .copy(datum = Datum.Hash(Blake2b.hash256.hash(datum)))
    val collateralIn = txIn(2)
    val lockedIn = txIn(3)
    val funds = Vector(collateralIn -> out(alice.address(), 100 * ada), lockedIn -> locked)
    val pp = conwayParams
    val s = conway(funds, pp)
    val units = ExUnits(1000, 1000000)
    // `{ [0, 0] : [1, [1000, 1000000]] }` and `[ 1 ]`
    val wits = Witnesses(
      Vector.empty,
      scripts = Vector(plutus),
      datums = Vector(datum),
      redeemers = Vector(Redeemer(0, 0, Bytes(Vector(0x01)), units)),
      redeemersRaw = Some(
        Bytes(
          Vector(0xa1, 0x82, 0x00, 0x00, 0x82, 0x01, 0x82, 0x19, 0x03, 0xe8, 0x1a, 0x00, 0x0f, 0x42,
            0x40).map(_.toByte)
        )
      ),
      datumsRaw = Some(Bytes(Vector(0x81, 0x01).map(_.toByte)))
    )
    val fee = BigInt(100 + 100) + (pp.priceMem * Rational(1000) +
      pp.priceSteps * Rational(1000000)).ceil
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
      tx(bodyWith(None), Vector(alice), wits).flat,
      pp,
      Set(ScriptKind.PlutusV3)
    )(using xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra.SConway)
    val b = bodyWith(hash)
    def attempt(
        succeeds: Boolean,
        isValid: Boolean,
        body: TxBody = b,
        state: Nes = s
    ) =
      run(
        state,
        tx(body, Vector(alice), wits, isValid = isValid),
        env.copy(
          evaluator = scripted(succeeds),
          decoder = xyz.kd5ujc.scarab.plutus.ScriptDecoder.lenient
        )
      )
    val valid = admitted(attempt(succeeds = true, isValid = true))
    assertEquals((valid.succeeded, valid.failed), (1L, 0L))
    assertEquals(total(valid.nes), Supply)
    val failed = admitted(attempt(succeeds = false, isValid = false))
    assert(!utxo(failed.nes).contains(collateralIn) && utxo(failed.nes).contains(lockedIn))
    assertEquals(total(failed.nes), Supply)
    refusedBy(attempt(succeeds = false, isValid = true)) {
      case Fails.Utxos(
            ConwayUtxosPredFailure.ValidationTagMismatch(
              era.IsPhase2Valid.Phase2Valid,
              TagMismatchDescription.FailedUnexpectedly(_)
            )
          ) =>
    }
    refusedBy(attempt(succeeds = true, isValid = false)) {
      case Fails.Utxos(
            ConwayUtxosPredFailure.ValidationTagMismatch(
              era.IsPhase2Valid.Phase2Invalid,
              TagMismatchDescription.PassedUnexpectedly
            )
          ) =>
    }
    // the hash is over the redeemers exactly as they were sent
    refusedBy(
      attempt(
        succeeds = true,
        isValid = true,
        bodyWith(Some(Blake2b.hash256.hash(Bytes(Vector(1)))))
      )
    ) { case Fails.Utxow(ConwayUtxowPredFailure.PPViewHashesDontMatch(_)) => }
    // from protocol version 11 the mismatch is ScriptIntegrityHashMismatch with the same pair and
    // the bytes the expected hash is taken over (Alonzo/Rules/Utxow.hs, checkScriptIntegrityHash)
    val pp11 = pp.copy(protocolMajor = 11)
    val at11 = conway(funds, pp11)
    val wrong = Blake2b.hash256.hash(Bytes(Vector(1)))
    val preimage = ScriptIntegrity.preimage(
      tx(bodyWith(None), Vector(alice), wits).flat,
      pp11,
      Set(ScriptKind.PlutusV3)
    )(using xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra.SConway)
    val expected = ScriptIntegrity.hash(
      tx(bodyWith(None), Vector(alice), wits).flat,
      pp11,
      Set(ScriptKind.PlutusV3)
    )(using xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra.SConway)
    assert(preimage.isDefined)
    assertEquals(
      attempt(succeeds = true, isValid = true, bodyWith(Some(wrong)), at11),
      Left(
        Vector(
          Fails.utxow(
            ConwayUtxowPredFailure.ScriptIntegrityHashMismatch(
              era.Mismatch(Some(wrong), expected),
              preimage
            )
          )
        )
      )
    )
    // without a V3 cost model the language view is still there, with `null` for the cost model
    // (`getLanguageView`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/PParams.hs:584`, cardano-ledger
    // f649f975): a body hashed without the language is refused, and one hashed with the null view
    // passes UTXOW and fails collection for want of a cost model (`NoCostModel`)
    val noV3 = pp.copy(costModels = pp.costModels - 2)
    val withoutModel = conway(funds, noV3)
    val unsigned = tx(bodyWith(None), Vector(alice), wits).flat
    val languageOmitted = ScriptIntegrity.hash(unsigned, noV3, Set.empty)(using
      xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra.SConway
    )
    val nullView = ScriptIntegrity.hash(unsigned, noV3, Set(ScriptKind.PlutusV3))(using
      xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra.SConway
    )
    assertNotEquals(languageOmitted, nullView)
    refusedBy(
      attempt(succeeds = true, isValid = true, bodyWith(languageOmitted), withoutModel)
    ) { case Fails.Utxow(ConwayUtxowPredFailure.PPViewHashesDontMatch(_)) => }
    assertEquals(
      attempt(succeeds = true, isValid = true, bodyWith(nullView), withoutModel),
      Left(
        Vector(
          Fails.utxos(
            ConwayUtxosPredFailure.CollectErrors(
              NonEmptyList.one(era.CollectError.NoCostModel(PlutusLanguage.V3))
            )
          )
        )
      )
    )
    // a script without collateral: its first failure is the balance below the fee's share, before
    // the empty collateral (`validateTotalCollateral`, `Babbage/Rules/Utxo.hs:209-240`,
    // cardano-ledger f649f975)
    refusedBy(attempt(succeeds = true, isValid = true, bodyWith(hash, Vector.empty))) {
      case Fails.Utxo(ConwayUtxoPredFailure.InsufficientCollateral(_, _)) =>
    }
  }

  test("Plutus V3: a reference input that is also an input is refused from protocol version 11") {
    val plutus = Script.plutus(ScriptKind.PlutusV3, Bytes(Vector.fill(10)(7)))
    val scriptAddress =
      Address.decode(Bytes(0x70.toByte +: plutus.hash.value)).fold(e => fail(e.toString), identity)
    val locked = TxOut(scriptAddress, Value.coin(50 * ada))
      .copy(datum = Datum.Inline(Bytes(Vector(0x01))))
    val collateralIn = txIn(2)
    val lockedIn = txIn(3)
    val other = txIn(4)
    val funds = Vector(
      collateralIn -> out(alice.address(), 100 * ada),
      lockedIn -> locked,
      other -> out(alice.address(), 5 * ada)
    )
    val units = ExUnits(1000, 1000000)
    val wits = Witnesses(
      Vector.empty,
      scripts = Vector(plutus),
      redeemers = Vector(Redeemer(0, 0, Bytes(Vector(0x01)), units)),
      redeemersRaw = Some(
        Bytes(
          Vector(0xa1, 0x82, 0x00, 0x00, 0x82, 0x01, 0x82, 0x19, 0x03, 0xe8, 0x1a, 0x00, 0x0f, 0x42,
            0x40).map(_.toByte)
        )
      )
    )
    val fee = BigInt(100 + 100) + (conwayParams.priceMem * Rational(1000) +
      conwayParams.priceSteps * Rational(1000000)).ceil
    def bodyWith(hash: Option[Bytes], refs: Vector[TxIn]) =
      body(
        "v3-refs",
        Vector(lockedIn),
        Vector(out(bob.address(), 50 * ada - fee)),
        fee,
        collateral = Vector(collateralIn),
        scriptDataHash = hash,
        referenceInputs = refs
      )
    val hash = ScriptIntegrity.hash(
      tx(bodyWith(None, Vector.empty), Vector(alice), wits).flat,
      conwayParams,
      Set(ScriptKind.PlutusV3)
    )(using xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra.SConway)
    def attempt(protocol: Int, refs: Vector[TxIn]) =
      // the placeholder script is not well-formed UPLC; this test is about reference inputs
      run(
        conway(funds, conwayParams.copy(protocolMajor = protocol)),
        tx(bodyWith(hash, refs), Vector(alice), wits),
        env.copy(
          evaluator = scripted(true),
          decoder = xyz.kd5ujc.scarab.plutus.ScriptDecoder.lenient
        )
      )
    // the input is also a reference input: refused by the UTXO rule up to 10, by the V3 script context at 11
    refusedBy(attempt(10, Vector(lockedIn))) {
      case Fails.Utxo(ConwayUtxoPredFailure.BabbageNonDisjointRefInputs(_)) =>
    }
    refusedBy(attempt(11, Vector(lockedIn))) {
      case Fails.Utxos(ConwayUtxosPredFailure.CollectErrors(_)) =>
    }
    // disjoint reference inputs are fine at both
    admitted(attempt(10, Vector(other)))
    admitted(attempt(11, Vector(other)))
  }

  test(
    "withdrawals from a script reward account need the script, and rewards are drained in full"
  ) {
    val script = ConwayTestKit.native(NativeScript.Sig(alice.hash))
    val in = txIn(1)
    val funds = Vector(in -> out(alice.address(), 100 * ada))
    def world(pp: ProtocolParams) =
      withAccount(conway(funds, pp), Credential.Script(script.hash), 5 * ada, 2 * ada)
    val s = world(conwayParams)
    val account = RewardAccount(0, Credential.Script(script.hash))
    def attempt(amount: BigInt, scripts: Vector[Script], state: Nes = s) =
      val b = body(
        "wd",
        Vector(in),
        Vector(out(alice.address(), 100 * ada + amount - 200)),
        200,
        withdrawals = Map(account -> amount)
      )
      run(state, tx(b, Vector(alice), Witnesses(Vector.empty, scripts = scripts)))
    admitted(attempt(5 * ada, Vector(script)))
    refusedBy(attempt(5 * ada, Vector.empty)) {
      case Fails.Utxow(ConwayUtxowPredFailure.MissingScriptWitnessesUTXOW(_)) =>
    }
    // up to protocol version 10 the CERTS rule reports a withdrawal that does not drain the
    // account; from 11 the LEDGER rule names it ConwayIncompleteWithdrawals
    refusedBy(attempt(4 * ada, Vector(script))) {
      case Fails.Certs(ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS(_)) =>
    }
    val v11 = world(conwayParams.copy(protocolMajor = 11))
    refusedBy(attempt(4 * ada, Vector(script), v11)) {
      case ConwayLedgerPredFailure.ConwayIncompleteWithdrawals(_) =>
    }
  }

  test("withdrawals from key accounts need a DRep delegation except at protocol version 9") {
    val staker = key()
    val in = txIn(1)
    val funds = Vector(in -> out(alice.address(), 100 * ada))
    def world(drep: Option[DRep], pv: Int) =
      withAccount(
        conway(funds, conwayParams.copy(protocolMajor = pv)),
        staker.credential,
        3 * ada,
        2 * ada,
        drep
      )
    val b = body(
      "wd",
      Vector(in),
      Vector(out(alice.address(), 103 * ada - 200)),
      200,
      withdrawals = Map(RewardAccount(0, staker.credential) -> (3 * ada))
    )
    def attempt(s: Nes) = run(s, tx(b, Vector(alice, staker)))
    def notDelegated(s: Nes) =
      refusedBy(attempt(s)) { case ConwayLedgerPredFailure.ConwayWdrlNotDelegatedToDRep(_) => }
    // only the bootstrap phase skips the check (`hardforkConwayBootstrapPhase`, `pvMajor pv == 9`,
    // `Conway/Era.hs:257-258` (cardano-ledger f649f975); `Conway/Rules/Ledger.hs:379-380`): Conway
    // at version 8, as a chain that starts Conway by epoch has it, runs the check
    notDelegated(world(None, 8))
    admitted(attempt(world(Some(DRep.AlwaysAbstain), 8)))
    admitted(attempt(world(None, 9)))
    notDelegated(world(None, 10))
    admitted(attempt(world(Some(DRep.AlwaysAbstain), 10)))
  }

  test("governance fields are checked: wrong deposit, unknown return account, unknown voter") {
    val in = txIn(1)
    val staker = key()
    val s = withAccount(
      conway(
        Vector(in -> out(alice.address(), 10000 * ada)),
        conwayParams.copy(govActionDeposit = 100 * ada)
      ),
      staker.credential,
      0,
      2 * ada
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
      run(s, tx(b, Vector(alice)))
    admitted(propose(100 * ada, staker.credential))
    refusedBy(propose(99 * ada, staker.credential)) {
      case Fails.Gov(ConwayGovPredFailure.ProposalDepositIncorrect(_)) =>
    }
    refusedBy(propose(100 * ada, bob.credential)) {
      case Fails.Gov(ConwayGovPredFailure.ProposalReturnAccountDoesNotExist(_)) =>
    }
    val ghost = GovActionId(Bytes(Vector.fill(32)(1)), 0)
    val vote = body(
      "v",
      Vector(in),
      Vector(out(alice.address(), 10000 * ada - 200)),
      200,
      votes = Vector(Voter.DRepVoter(bob.credential) -> Vector(ghost -> Vote.Yes))
    )
    refusedBy(run(s, tx(vote, Vector(alice, bob)))) {
      case Fails.Gov(ConwayGovPredFailure.VotersDoNotExist(_)) =>
    }
  }

  test("committee certificates: unknown members and resigned members are refused") {
    val cold = key()
    val hot = key()
    val in = txIn(1)
    val s0 = conway(Vector(in -> out(alice.address(), 100 * ada)))
    val member = ConwayNewEpochState.gov
      .andThen(ConwayGovState.committee)
      .replace(Some(Committee(Map(cold.credential -> BigInt(100)), Rational(1, 2))))(s0)
    val coldCredential = ok(Convert.credential[ColdCommittee](cold.credential))
    val hotCredential = ok(Convert.credential[HotCommittee](hot.credential))
    def authorization(s: Nes) = s.es.ls.cert.vState.committeeState.creds.get(coldCredential)
    def attempt(s: Nes, cert: Certificate, signer: Key) =
      val (spend, spent) = utxo(s).iterator.nextOption().getOrElse(fail("the UTxO is empty"))
      val b = body(
        "cc",
        Vector(spend),
        Vector(out(alice.address(), spent.value.coin - 200)),
        200,
        certificates = Vector(cert)
      )
      run(s, tx(b, Vector(alice, signer)))
    refusedBy(attempt(s0, Certificate.CommitteeAuthorize(cold.credential, hot.credential), cold)) {
      case Fails.GovCert(ConwayGovCertPredFailure.ConwayCommitteeIsUnknown(_)) =>
    }
    val authorised =
      admitted(
        attempt(member, Certificate.CommitteeAuthorize(cold.credential, hot.credential), cold)
      ).nes
    assertEquals(
      authorization(authorised),
      Some(CommitteeAuthorization.CommitteeHotCredential(hotCredential))
    )
    val resigned =
      admitted(attempt(authorised, Certificate.CommitteeResign(cold.credential, None), cold)).nes
    assertEquals(
      authorization(resigned),
      Some(CommitteeAuthorization.CommitteeMemberResigned(None))
    )
    refusedBy(
      attempt(resigned, Certificate.CommitteeAuthorize(cold.credential, hot.credential), cold)
    ) { case Fails.GovCert(ConwayGovCertPredFailure.ConwayCommitteeHasPreviouslyResigned(_)) => }
  }

  test("dormant epochs are counted when no proposal is open, and a proposal revives the DReps") {
    val drep = key()
    val in = txIn(1)
    val staker = key()
    val drepCredential = ok(Convert.credential[DRepRole](drep.credential))
    val registered = withAccount(
      conway(
        Vector(in -> out(alice.address(), 10000 * ada)),
        conwayParams.copy(govActionDeposit = 100 * ada, drepActivity = 20, govActionLifetime = 5)
      ),
      staker.credential,
      0,
      2 * ada
    )
    val s = paid(
      ConwayNewEpochState.dreps.modify(
        _.updated(
          drepCredential,
          native.DRepState(
            EpochNo.fromUnsigned(30L),
            None,
            ok(CompactCoin.fromBigInt(500 * ada)),
            Sorted.emptySet
          )
        )
      )(registered),
      500 * ada,
      0
    )
    def dormant(nes: Nes): BigInt = nes.es.ls.cert.vState.numDormantEpochs.toBigInt
    val globals = LedgerGlobals(
      Supply,
      100,
      Rational(1, 20),
      network = Network.Testnet,
      systemStart = xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
      kes = xyz.kd5ujc.scarab.ledger.KesParameters.Mainnet
    )
    val idle = inConway(
      Iterator
        .iterate[EraNes](EraNes.InEra(ShelleyBasedEra.SConway, s))(NativeRules.boundary(_, globals))
        .drop(3)
        .next()
    )
    assertEquals(dormant(idle), BigInt(3))
    val p = Proposal(100 * ada, RewardAccount(0, staker.credential), GovAction.Info)
    val b = body(
      "p",
      Vector(in),
      Vector(out(alice.address(), 10000 * ada - 200 - 100 * ada)),
      200,
      proposals = Vector(p)
    )
    val revived =
      admitted(run(idle, tx(b, Vector(alice)), env.copy(epoch = idle.epoch.toBigInt))).nes
    assertEquals(dormant(revived), BigInt(0))
    assertEquals(
      revived.es.ls.cert.vState.dreps.get(drepCredential).map(_.expiry.toBigInt),
      Some(BigInt(33))
    )
    // with a proposal open the epochs are not dormant
    val busy =
      inConway(NativeRules.boundary(EraNes.InEra(ShelleyBasedEra.SConway, revived), globals))
    assertEquals(dormant(busy), BigInt(0))
  }
