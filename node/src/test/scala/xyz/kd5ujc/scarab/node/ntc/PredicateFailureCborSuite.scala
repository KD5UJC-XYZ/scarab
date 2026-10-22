// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.plutus.{EvalFailure, ExUnits, ScriptFailure}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.TestLedger.out
import munit.FunSuite

/** The predicate-failure CBOR against the reasons the Haskell `cardano-node` 11.1.3 sent for the
  * same refusals (recorded on a devnet with a LocalTxSubmission client: the bytes of the
  * `MsgRejectTx` reason). The transactions behind them are not needed: every failure here is built
  * from its payload.
  */
class PredicateFailureCborSuite extends FunSuite:
  private def hex(h: String): Bytes = Bytes.fromHex(h).fold(e => fail(e.message), identity)
  private def reason(failures: PredicateFailure*): String =
    PredicateFailureCbor.applyTxError(failures.toVector).hex

  private val keyHash = hex("683acdaa374d6ef02de93bea119185477b17c8298d59967b06528a2d")

  test("typed script failures render only at the wire boundary without changing rejection bytes") {
    val cause = new IllegalArgumentException("failure")
    val evaluation = EvalFailure.Script(ScriptFailure(cause, Vector("trace")))
    val failure = PredicateFailure.utxo(
      UtxoFailure.Utxos(
        UtxosFailure.ValidationTagMismatch(
          true,
          Some(Vector(PlutusFailure(evaluation, hex("0102"))))
        )
      )
    )
    // Alonzo/Rules/Utxos.hs:259-309 (f649f975): PlutusFailure keeps constructor tag 1,
    // its rendered text and opaque reconstruction bytes. The outer UTXOS tag stays 0.
    val utxos = "8300f58201818301756661696c7572653b2074726163653a207472616365420102"
    assertEquals(reason(failure), "81820681820182008200" + utxos)
    for era <- Vector(Era.Alonzo, Era.Babbage, Era.Conway) do
      val encoded = PredicateFailureCbor
        .applyTxError(era, Vector(failure))
        .getOrElse(fail(s"missing $era validity-tag encoding"))
      assert(encoded.hex.endsWith(utxos), s"$era: ${encoded.hex}")
    evaluation match
      case EvalFailure.Script(ScriptFailure(original: Throwable, logs)) =>
        assert(original eq cause)
        assertEquals(logs, Vector("trace"))
      case other => fail(s"changed evaluator cause: $other")
  }

  test("an unexpectedly passing script retains the existing validity-tag rejection bytes") {
    assertEquals(
      reason(
        PredicateFailure.utxo(
          UtxoFailure.Utxos(
            UtxosFailure.ValidationTagMismatch(false, None)
          )
        )
      ),
      "818206818201820082008300f48100"
    )
  }

  test("the fee is below the minimum: expected first, then supplied") {
    assertEquals(
      reason(PredicateFailure.utxo(UtxoFailure.FeeTooSmall(Mismatch(1, 196)))),
      "8182068182018200830518c401"
    )
  }

  test("a transaction none of whose inputs is unspent is refused by the MEMPOOL rule alone") {
    assertEquals(
      reason(
        PredicateFailure.Mempool(
          xyz.kd5ujc.scarab.ledger.MempoolProblem.AllInputsSpent
        )
      ),
      "8182068182077844416c6c20696e7075747320617265207370656e742e205472616e73616374696f6e206861732070726f6261626c7920616c7265616479206265656e20696e636c75646564"
    )
  }

  test("value not conserved: consumed, then produced") {
    assertEquals(
      reason(
        PredicateFailure.utxo(
          UtxoFailure.ValueNotConserved(
            Mismatch(Value.coin(BigInt("15000003000000")), Value.coin(BigInt("15000003100000")))
          )
        )
      ),
      "818206818201820083061b00000da475d9b6c01b00000da475db3d60"
    )
  }

  test("outside the validity interval: the interval as two maybes, then the slot") {
    assertEquals(
      reason(
        PredicateFailure.utxo(UtxoFailure.OutsideValidityInterval(None, Some(1), 18204))
      ),
      "818206818201820083028280810119471c"
    )
  }

  test("missing key witnesses are a set of key hashes") {
    assertEquals(
      reason(PredicateFailure.utxow(UtxowFailure.MissingVKeyWitnesses(Set(keyHash)))),
      "8182068182018202d9010281581c683acdaa374d6ef02de93bea119185477b17c8298d59967b06528a2d"
    )
  }

  test("a withdrawal on another network: the network of the ledger, then the accounts") {
    val account = RewardAccount(
      1,
      Credential.Key(hex("471db3352ca9d734922746e1b842782ba42ce45014b38408e77f6df6"))
    )
    val wrong = PredicateFailure.utxo(UtxoFailure.WrongNetworkWithdrawal(0, Set(account)))
    // the reason of the reference node on the devnet (`ntc`, the case with the validity flag false)
    assertEquals(
      reason(wrong),
      "8182068182018200830800d9010281581de1471db3352ca9d734922746e1b842782ba42ce45014b38408e77f6df6"
    )
    // and the two failures it lists when LEDGER's own check of the withdrawals refuses the
    // transaction first: the accounts that are missing, then this one
    assertEquals(
      reason(PredicateFailure.WithdrawalsMissingAccounts(Map(account -> BigInt(0))), wrong),
      "818206828208a1581de1471db3352ca9d734922746e1b842782ba42ce45014b38408e77f6df60082018200830800d9010281581de1471db3352ca9d734922746e1b842782ba42ce45014b38408e77f6df6"
    )
  }

  test("an output below the minimum travels with the minimum it missed") {
    val address =
      Address
        .decode(Bytes(Vector(0x60.toByte) ++ keyHash.value))
        .fold(e => fail(e.message), identity)
    assertEquals(
      reason(
        PredicateFailure.utxo(UtxoFailure.BabbageOutputTooSmall(Vector(out(address, 1) -> 831830)))
      ),
      "81820681820182008215818282581d60683acdaa374d6ef02de93bea119185477b17c8298d59967b06528a2d011a000cb156"
    )
  }

  test("certificate failures nest CERTS, CERT and the rule's own tag") {
    val registered = Credential.Key(hex("118c4307096319eebab0c1943c5302dafc645a18f50e2f5090134b0a"))
    assertEquals(
      reason(PredicateFailure.deleg(DelegFailure.StakeKeyRegistered(registered))),
      "8182068182028201820182028200581c118c4307096319eebab0c1943c5302dafc645a18f50e2f5090134b0a"
    )
    val missing = Credential.Key(hex("d94f5c17a399d3ce713379d5a84054414a5b4bc912b7400e79dc19b2"))
    assertEquals(
      reason(PredicateFailure.deleg(DelegFailure.StakeKeyNotRegistered(missing))),
      "8182068182028201820182038200581cd94f5c17a399d3ce713379d5a84054414a5b4bc912b7400e79dc19b2"
    )
    val drep = Credential.Key(hex("327fcad3acfe2dd1161d8351059f549db4728c01638d614c551a28d7"))
    assertEquals(
      reason(PredicateFailure.govCert(GovCertFailure.DRepNotRegistered(drep))),
      "8182068182028201820382018200581c327fcad3acfe2dd1161d8351059f549db4728c01638d614c551a28d7"
    )
    val pool = hex("9dbb1d6810d45b8eb878d8d7d62290bd240b13442cdcf689fcafda75")
    assertEquals(
      reason(PredicateFailure.deleg(DelegFailure.DelegateeStakePoolNotRegistered(pool))),
      "818206818202820182018206581c9dbb1d6810d45b8eb878d8d7d62290bd240b13442cdcf689fcafda75"
    )
  }

  test("pool retirement: the current epoch, the epoch asked for, the latest allowed") {
    assertEquals(
      reason(
        PredicateFailure.pool(
          PoolFailure.StakePoolRetirementWrongEpoch(Mismatch(1, 39), Mismatch(1, 57))
        )
      ),
      "8182068182028201820284011827011839"
    )
  }

  test("a treasury value that is not the treasury: expected first, then supplied") {
    assertEquals(
      reason(PredicateFailure.TreasuryValueMismatch(Mismatch(1, BigInt("2810157520157")))),
      "8182068183051b0000028e4a70d51d01"
    )
  }

  test("a transaction of another era gets the hard-fork combinator's era mismatch") {
    // Babbage transaction against a Conway ledger: [[5, "Babbage"], [6, "Conway"]]
    assertEquals(
      PredicateFailureCbor.eraMismatch(5, "Babbage").hex,
      "8282056742616262616765820666436f6e776179"
    )
  }

  test("every constructor encodes to well-formed CBOR that opens with the era wrapper") {
    val samples: Vector[PredicateFailure] = Vector(
      PredicateFailure.utxo(UtxoFailure.InputSetEmpty),
      PredicateFailure.utxo(
        UtxoFailure.BadInputs(Set(TxIn(Bytes(keyHash.value ++ keyHash.value.take(4)), 0)))
      ),
      PredicateFailure.utxo(UtxoFailure.MaxTxSize(Mismatch(17000, 16384))),
      PredicateFailure.utxo(UtxoFailure.WrongNetwork(0, Set(keyHash))),
      PredicateFailure.utxo(UtxoFailure.ExUnitsTooBig(Mismatch(ExUnits(2, 3), ExUnits(1, 1)))),
      PredicateFailure.utxo(UtxoFailure.TooManyCollateralInputs(Mismatch(4, 3))),
      PredicateFailure.utxo(UtxoFailure.NoCollateralInputs),
      PredicateFailure.utxo(UtxoFailure.InsufficientCollateral(10, 20)),
      PredicateFailure.utxow(UtxowFailure.ExtraneousScriptWitnesses(Set(keyHash))),
      PredicateFailure.utxow(UtxowFailure.PPViewHashesDontMatch(Mismatch(None, Some(keyHash)))),
      PredicateFailure.utxow(
        UtxowFailure.ScriptIntegrityHashMismatch(Mismatch(None, Some(keyHash)), Some(keyHash))
      ),
      PredicateFailure.WdrlNotDelegatedToDRep(Vector(keyHash)),
      PredicateFailure.TxRefScriptsSizeTooBig(Mismatch(300000, 204800)),
      PredicateFailure.deleg(DelegFailure.DepositIncorrect(Mismatch(1, 2))),
      PredicateFailure.govCert(GovCertFailure.DRepIncorrectDeposit(Mismatch(1, 2))),
      PredicateFailure.gov(GovFailure.ProposalDepositIncorrect(Mismatch(1, 2))),
      PredicateFailure.gov(GovFailure.VotersDoNotExist(Vector(Voter.Pool(keyHash))))
    )
    samples.foreach { f =>
      val bytes = PredicateFailureCbor.applyTxError(Vector(f))
      val decoded = Cbor.decode(bytes).fold(e => fail(s"$f: $e"), identity)
      decoded.value match
        case CborValue.Arr(Vector(wrapper)) =>
          wrapper.value match
            case CborValue.Arr(Vector(era, _)) => assertEquals(era.value, CborValue.UInt(6))
            case other                         => fail(s"$f: $other")
        case other => fail(s"$f: $other")
    }
  }

  test("Conway: the integrity hash mismatch from protocol 11 is tag 18 with the preimage") {
    // [18 ScriptIntegrityHashMismatch, [supplied, expected], expected preimage] with StrictMaybe
    // as an empty or one-element list (Conway/Rules/Utxow.hs, the Sum encoder).
    val h = keyHash.hex
    assertEquals(
      PredicateFailureCbor
        .applyTxError(
          Era.Conway,
          Vector(
            PredicateFailure.utxow(
              UtxowFailure.ScriptIntegrityHashMismatch(
                Mismatch(None, Some(keyHash)),
                Some(Bytes(Vector(0xa0.toByte)))
              )
            )
          )
        )
        .map(_.hex),
      Some(
        "81" + "8206" + "81" + "8201" + "83" + "12" + "82" + "80" + "81" + "581c" + h + "81" + "41a0"
      )
    )
  }

  test(
    "Conway: the integrity hash mismatch is byte-equal to the reference node's refusal at protocol 11"
  ) {
    // Recorded from the reference node 11.1.3 by `devnet.py ntc` (case script-integrity-hash-mismatch: a
    // Plutus V3 mint built against a cost model one unit off, UTXOW tag 18): the mismatch pair and the
    // preimage of the expected hash (the redeemers and the language views, no datums).
    val recorded =
      "818206818201831282815820ae09c91630e2308bc5d6df0400ef63094264fe4b505249744553472be3eaa7e281582069" +
        "f198b0685b443f01c4822569dc5c0a2c65d24f78bc1a5e59d3c55d8e820596815902c3a18201008200821a001e84801a" +
        "3b9aca00a10298fb1a000189b41901a401011903e818ad00011903e819ea350401192baf18201a000312591920a40419" +
        "3e801864193e801864193e801864193e801864193e801864193e80186418641864193e8018641a000170a718201a0002" +
        "0782182019f016041a0001194a18b2000119568718201a0001643519030104021a00014f581a0001e143191c89390383" +
        "1906b41903c018391a00014f580001011903e819a7a9040219779f197053184b011a000db464196a8f0119ca3f19022e" +
        "011999101903e819ecb2011a00022a4718201a000144ce1820193bc318201a0001291101193371041956540a19714718" +
        "4a01197147184a0119a9151902280119aecd19021d0119843c18201a00010a9618201a00011aaa1820191c4b1820191c" +
        "df1820192d1a18201a00014f581a0001e143191c893903831906b41903c018391a00014f5800011a0001614219020700" +
        "011a000122c118201a00014f581a0001e143191c893903831906b41903c018391a00014f580001011a00014f581a0001" +
        "e143191c893903831906b41903c018391a00014f5800011a000e94721a0003414000021a0004213c19583c041a00163c" +
        "ad19fc3604194ff30104001a00022aa818201a000189b41901a401011a00013eff182019e86a1820194eae182019600c" +
        "1820195108182019654d182019602f18201a0290f1e70a1a032e93af1937fd0a1a0298e40b1966c40a193e801864193e" +
        "8018641a000eaf1f121a002a6e06061a0006be98011a0321aac7190eac121a00041699121a048e466e1922a4121a0327" +
        "ec9a121a001e743c18241a0031410f0c1a000dbf9e011a09f2f6d31910d318241a0004578218241a096e44021967b518" +
        "241a0473cee818241a13e62472011a0f23d40118481a00212c5618481a0022814619fc3b041a00032b00192076041a00" +
        "13be0419702c183f00011a000f59d919aa6718fb0001"
    val supplied = hex(recorded.slice(24, 24 + 64))
    val expected = hex(recorded.slice(24 + 64 + 6, 24 + 64 + 6 + 64))
    val preimage = hex(recorded.drop(24 + 64 + 6 + 64 + 8))
    assertEquals(
      PredicateFailureCbor
        .applyTxError(
          Era.Conway,
          Vector(
            PredicateFailure.utxow(
              UtxowFailure.ScriptIntegrityHashMismatch(
                Mismatch(Some(supplied), Some(expected)),
                Some(preimage)
              )
            )
          )
        )
        .map(_.hex),
      Some(recorded)
    )
  }

  test("Conway: a missing redeemer of a certifying purpose carries its certificate, not null") {
    // Hand-derived here and byte-equal to the reference node's answer in PlutusFailureReferenceSuite
    // (recorded by the e2e load at protocol 9): [6 era, [[1 LEDGER UTXOW (Conway/Rules/Ledger.hs:231), [10 MissingRedeemers,
    // [[[2 Certifying, [1 UnRegCert, [1 script, hash]]], script]]]]]. The certificate is the Conway
    // one: an unregistration without a refund is [1, credential] (Conway/TxCert.hs).
    val scriptHash = Bytes(Vector.fill(28)(0xcc.toByte))
    val s = scriptHash.hex
    val failure = PredicateFailure.utxow(
      UtxowFailure.MissingRedeemers(
        Vector(
          Purpose.Certifying(
            0,
            Certificate.Unregister(Credential.Script(scriptHash), None)
          ) -> scriptHash
        )
      )
    )
    assertEquals(
      PredicateFailureCbor.applyTxError(Era.Conway, Vector(failure)).map(_.hex),
      Some(
        "81" + "8206" + "81" + "8201" + "820a" + "81" + "82" + "8202" + "8201" + "8201" + "581c" + s + "581c" + s
      )
    )
  }
