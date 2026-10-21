// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.TestLedger.out
import xyz.kd5ujc.scarab.plutus.PlutusLanguage
import xyz.kd5ujc.scarab.protocol.Era
import munit.FunSuite

/** The predicate-failure CBOR of the eras before Conway (`PreConwayFailureCbor`).
  *
  * These goldens are written out from the `EncCBOR` instances of the pinned cardano-ledger (the
  * tags and the nesting, cited on [[PreConwayFailureCbor]]); each expected value is derived by hand
  * from the instance, field by field, in the comment above it. The ones that the reference node
  * itself answered (Shelley to Alonzo, eight refusals of the `e2e` load) are in
  * [[PreConwayFailureReferenceSuite]], byte for byte; the rest, Babbage among them, are the
  * instances' reading only.
  */
class PreConwayFailureCborSuite extends FunSuite:
  private def hex(h: String): Bytes = Bytes.fromHex(h).fold(e => fail(e.message), identity)
  private val keyHash = hex("683acdaa374d6ef02de93bea119185477b17c8298d59967b06528a2d")
  private val h = keyHash.hex

  private def reason(era: Era, failures: PredicateFailure*): Option[String] =
    PredicateFailureCbor.applyTxError(era, failures.toVector).map(_.hex)

  // ---- Shelley

  test("Shelley: an expired transaction is UTXO tag 1 with [time to live, slot]") {
    // [[1, [0 UTXOW, [4 UtxoFailure, [1 ExpiredUTxO, [100, 120]]]]]]
    assertEquals(
      reason(Era.Shelley, PredicateFailure.utxo(UtxoFailure.ExpiredUTxO(Mismatch(100, 120)))),
      Some("81" + "8201" + "81" + "8200" + "8204" + "8201" + "82" + "1864" + "1878")
    )
  }

  test("Shelley: the genesis signatures of a MIR certificate are a plain array of key hashes") {
    // sets are tag 258 from protocol version 9 only: [5 MIRInsufficientGenesisSigsUTXOW, [hash]]
    assertEquals(
      reason(
        Era.Shelley,
        PredicateFailure.utxow(UtxowFailure.MIRInsufficientGenesisSigs(Set(keyHash)))
      ),
      Some("81" + "8201" + "81" + "8200" + "8205" + "81" + "581c" + h)
    )
  }

  test("Shelley: DELEG failures nest DELEGS (1), DELPL (1 for DELEG) and the rule's tag") {
    // [1 DelegsFailure, [1 DelplFailure, [1 DelegFailure, [0 StakeKeyAlreadyRegistered, cred]]]]
    assertEquals(
      reason(
        Era.Shelley,
        PredicateFailure.deleg(DelegFailure.StakeKeyAlreadyRegistered(Credential.Key(keyHash)))
      ),
      Some("81" + "8201" + "81" + "8201" + "8201" + "8201" + "8200" + "82" + "00" + "581c" + h)
    )
    // a MIR transfer before protocol version 5 is a bare constructor: [11]
    assertEquals(
      reason(Era.Shelley, PredicateFailure.deleg(DelegFailure.MIRTransferNotCurrentlyAllowed)),
      Some("81" + "8201" + "81" + "8201" + "8201" + "8201" + "81" + "0b")
    )
    // [7 InsufficientForInstantaneousRewards, pot (reserves 0), [supplied 10, expected 5]]
    assertEquals(
      reason(
        Era.Shelley,
        PredicateFailure.deleg(
          DelegFailure.InsufficientForInstantaneousRewards(Pot.Reserves, Mismatch(10, 5))
        )
      ),
      Some(
        "81" + "8201" + "81" + "8201" + "8201" + "8201" + "83" + "07" + "00" + "82" + "0a" + "05"
      )
    )
  }

  test("Shelley: POOL failures sit at DELPL tag 0, with the encoding Conway shares") {
    // [1 DelegsFailure, [1 DelplFailure, [0 PoolFailure, [0 StakePoolNotRegisteredOnKey, hash]]]]
    assertEquals(
      reason(Era.Shelley, PredicateFailure.pool(PoolFailure.StakePoolNotRegisteredOnKey(keyHash))),
      Some("81" + "8201" + "81" + "8201" + "8201" + "8200" + "8200" + "581c" + h)
    )
  }

  test("Shelley: the update proposals are UTXO tag 7; the wrong epoch carries its period") {
    // [7 UpdateFailure, [1 PPUpdateWrongEpoch, current 5, proposed 7, 1 VoteForNextEpoch]]
    assertEquals(
      reason(
        Era.Shelley,
        PredicateFailure.utxo(
          UtxoFailure.Update(PpupFailure.PPUpdateWrongEpoch(5, 7, VotingPeriod.VoteForNextEpoch))
        )
      ),
      Some("81" + "8201" + "81" + "8200" + "8204" + "8207" + "84" + "01" + "05" + "07" + "01")
    )
    // [2 PVCannotFollowPPUP, [major, minor]]
    assertEquals(
      reason(
        Era.Shelley,
        PredicateFailure.utxo(UtxoFailure.Update(PpupFailure.PVCannotFollow(3, 1)))
      ),
      Some("81" + "8201" + "81" + "8200" + "8204" + "8207" + "8202" + "82" + "03" + "01")
    )
    // [0 NonGenesisUpdatePPUP, [proposers, genesis keys]]
    assertEquals(
      reason(
        Era.Shelley,
        PredicateFailure.utxo(
          UtxoFailure.Update(PpupFailure.NonGenesisUpdate(Mismatch(Set(keyHash), Set.empty)))
        )
      ),
      Some(
        "81" + "8201" + "81" + "8200" + "8204" + "8207" + "8200" + "82" + "81" + "581c" + h + "80"
      )
    )
  }

  test("Shelley: the withdrawals LEDGER checks are tags 2 and 3 of the LEDGER rule") {
    val account = RewardAccount(0, Credential.Key(keyHash))
    // [2 ShelleyWithdrawalsMissingAccounts, {account: 5}]
    assertEquals(
      reason(Era.Shelley, PredicateFailure.WithdrawalsMissingAccounts(Map(account -> 5))),
      Some("81" + "8201" + "81" + "8202" + "a1" + "581de0" + h + "05")
    )
    // [3 ShelleyIncompleteWithdrawals, {account: [supplied 3, expected 7]}]
    assertEquals(
      reason(
        Era.Shelley,
        PredicateFailure.IncompleteWithdrawals(Map(account -> Mismatch(BigInt(3), BigInt(7))))
      ),
      Some("81" + "8201" + "81" + "8203" + "a1" + "581de0" + h + "82" + "03" + "07")
    )
  }

  // ---- Allegra and Mary

  test("Allegra and Mary: the validity interval is UTXO tag 1, the size of a value tag 12") {
    // [1 OutsideValidityInterval, [[], [1]], 18204]
    val interval = PredicateFailure.utxo(UtxoFailure.OutsideValidityInterval(None, Some(1), 18204))
    assertEquals(
      reason(Era.Allegra, interval),
      Some(
        "81" + "8202" + "81" + "8200" + "8204" + "83" + "01" + "82" + "80" + "81" + "01" + "19471c"
      )
    )
    assertEquals(
      reason(Era.Mary, interval),
      Some(
        "81" + "8203" + "81" + "8200" + "8204" + "83" + "01" + "82" + "80" + "81" + "01" + "19471c"
      )
    )
    // an output too big has no size in Allegra: [12 OutputTooBigUTxO, [output]]
    val address =
      Address
        .decode(Bytes(Vector(0x60.toByte) ++ keyHash.value))
        .fold(e => fail(e.message), identity)
    assertEquals(
      reason(
        Era.Mary,
        PredicateFailure.utxo(UtxoFailure.OutputTooBig(Vector((5000, 4000, out(address, 1)))))
      ),
      Some(
        "81" + "8203" + "81" + "8200" + "8204" + "820c" + "81" + "82" + "581d60" + h + "01"
      )
    )
  }

  test("Allegra: a time to live is not a failure of the era") {
    assertEquals(
      reason(Era.Allegra, PredicateFailure.utxo(UtxoFailure.ExpiredUTxO(Mismatch(1, 2)))),
      None
    )
  }

  // ---- Alonzo

  test("Alonzo: the Shelley constructors sit at UTXOW tag 0, the failures of UTXO under tag 4") {
    // [0 ShelleyInAlonzo, [4 UtxoFailure, [4 FeeTooSmallUTxO, [supplied 1, expected 196]]]]
    assertEquals(
      reason(Era.Alonzo, PredicateFailure.utxo(UtxoFailure.FeeTooSmall(Mismatch(1, 196)))),
      Some("81" + "8204" + "81" + "8200" + "8200" + "8204" + "8204" + "82" + "01" + "18c4")
    )
    // [0 ShelleyInAlonzo, [2 MissingScriptWitnessesUTXOW, [hash]]]
    assertEquals(
      reason(
        Era.Alonzo,
        PredicateFailure.utxow(UtxowFailure.MissingScriptWitnesses(Set(keyHash)))
      ),
      Some("81" + "8204" + "81" + "8200" + "8200" + "8202" + "81" + "581c" + h)
    )
  }

  test("Alonzo: the update proposals move under UTXOS (7), at tag 2") {
    // [7 UtxosFailure, [2 UpdateFailure, [1 PPUpdateWrongEpoch, 5, 7, 1]]]
    assertEquals(
      reason(
        Era.Alonzo,
        PredicateFailure.utxo(
          UtxoFailure.Update(PpupFailure.PPUpdateWrongEpoch(5, 7, VotingPeriod.VoteForNextEpoch))
        )
      ),
      Some(
        "81" + "8204" + "81" + "8200" + "8200" + "8204" + "8207" + "8202" + "84" + "01" + "05" +
          "07" + "01"
      )
    )
  }

  test("Alonzo: the failures of the collateral and the script data") {
    // [13 InsufficientCollateral, balance (a DeltaCoin, signed), required]
    assertEquals(
      reason(Era.Alonzo, PredicateFailure.utxo(UtxoFailure.InsufficientCollateral(-1, 20))),
      Some("81" + "8204" + "81" + "8200" + "8200" + "8204" + "83" + "0d" + "20" + "14")
    )
    // [4 PPViewHashesDontMatch, [supplied none, expected hash]]
    assertEquals(
      reason(
        Era.Alonzo,
        PredicateFailure.utxow(UtxowFailure.PPViewHashesDontMatch(Mismatch(None, Some(keyHash))))
      ),
      Some(
        "81" + "8204" + "81" + "8200" + "8204" + "82" + "80" + "81" + "581c" + h
      )
    )
    // [7 ExtraRedeemers, [[tag, index]]] at the UTXOW level
    assertEquals(
      reason(Era.Alonzo, PredicateFailure.utxow(UtxowFailure.ExtraRedeemers(Vector((1, 2))))),
      Some("81" + "8204" + "81" + "8200" + "8207" + "81" + "82" + "01" + "02")
    )
  }

  test("Alonzo: a setup fault with no reference constructor has no encoding here") {
    assertEquals(
      reason(
        Era.Alonzo,
        PredicateFailure.utxo(
          UtxoFailure.Utxos(
            UtxosFailure.CollectErrors(
              cats.data.NonEmptyVector
                .one(CollectError.BadTranslation(TranslationError.NoEraHistory))
            )
          )
        )
      ),
      None
    )
  }

  // ---- Babbage

  test("Babbage: UTXO is UTXOW tag 2, Alonzo's constructors are held at 1 and 0") {
    val address =
      Address
        .decode(Bytes(Vector(0x60.toByte) ++ keyHash.value))
        .fold(e => fail(e.message), identity)
    // [2 UtxoFailure, [3 BabbageOutputTooSmallUTxO, [[output, minimum 831830]]]]
    assertEquals(
      reason(
        Era.Babbage,
        PredicateFailure.utxo(UtxoFailure.BabbageOutputTooSmall(Vector(out(address, 1) -> 831830)))
      ),
      Some(
        "81" + "8205" + "81" + "8200" + "8202" + "8203" + "81" + "82" + "82" + "581d60" + h +
          "01" + "1a000cb156"
      )
    )
    // [2 UtxoFailure, [1 AlonzoInBabbage, [4 FeeTooSmallUTxO, [1, 196]]]]
    assertEquals(
      reason(Era.Babbage, PredicateFailure.utxo(UtxoFailure.FeeTooSmall(Mismatch(1, 196)))),
      Some("81" + "8205" + "81" + "8200" + "8202" + "8201" + "8204" + "82" + "01" + "18c4")
    )
    // [1 AlonzoInBabbage, [0 ShelleyInAlonzo, [2 MissingScriptWitnessesUTXOW, [hash]]]]
    assertEquals(
      reason(
        Era.Babbage,
        PredicateFailure.utxow(UtxowFailure.MissingScriptWitnesses(Set(keyHash)))
      ),
      Some("81" + "8205" + "81" + "8200" + "8201" + "8200" + "8202" + "81" + "581c" + h)
    )
    // [3 MalformedScriptWitnesses, [hash]]
    assertEquals(
      reason(
        Era.Babbage,
        PredicateFailure.utxow(UtxowFailure.MalformedScriptWitnesses(Set(keyHash)))
      ),
      Some("81" + "8205" + "81" + "8200" + "8203" + "81" + "581c" + h)
    )
  }

  // ---- the eras and Conway

  test("Byron has no ledger failures; Conway's are PredicateFailureCbor's") {
    val f = PredicateFailure.utxo(UtxoFailure.InputSetEmpty)
    assertEquals(reason(Era.Byron, f), None)
    assertEquals(
      reason(Era.Conway, f),
      Some(PredicateFailureCbor.applyTxError(Vector(f)).hex)
    )
  }

  test("a failure the era does not have is not encoded: Conway's in Shelley, Shelley's in Conway") {
    assertEquals(
      reason(Era.Shelley, PredicateFailure.gov(GovFailure.ZeroTreasuryWithdrawals(GovAction.Info))),
      None
    )
    assertEquals(
      reason(Era.Shelley, PredicateFailure.utxo(UtxoFailure.NoCollateralInputs)),
      None
    )
  }

  test("Conway sends the failures of the earlier rules as its free-text failure") {
    val earlier: Vector[PredicateFailure] = Vector(
      PredicateFailure.utxo(UtxoFailure.ExpiredUTxO(Mismatch(100, 120))),
      PredicateFailure.utxo(UtxoFailure.Update(PpupFailure.PVCannotFollow(3, 1))),
      PredicateFailure.utxow(UtxowFailure.MIRInsufficientGenesisSigs(Set(keyHash))),
      PredicateFailure.deleg(DelegFailure.MIRNegativeTransfer(Pot.Treasury, -1)),
      PredicateFailure.utxo(
        UtxoFailure.Utxos(
          UtxosFailure.CollectErrors(
            cats.data.NonEmptyVector.one(CollectError.BadTranslation(TranslationError.NoEraHistory))
          )
        )
      )
    )
    earlier.foreach { f =>
      assert(!PredicateFailureCbor.inConway(f), s"$f")
      val decoded =
        Cbor
          .decode(PredicateFailureCbor.applyTxError(Vector(f)))
          .fold(e => fail(e.message), identity)
      // [[6, [[7 ConwayMempoolFailure, text]]]]
      decoded.value match
        case CborValue.Arr(Vector(wrapper)) =>
          wrapper.value match
            case CborValue.Arr(Vector(_, failures)) =>
              failures.value match
                case CborValue.Arr(Vector(one)) =>
                  one.value match
                    case CborValue.Arr(Vector(tag, text)) =>
                      assertEquals(tag.value, CborValue.UInt(7), s"$f")
                      assert(text.value.isInstanceOf[CborValue.Text], s"$f")
                    case other => fail(s"$f: $other")
                case other => fail(s"$f: $other")
            case other => fail(s"$f: $other")
        case other => fail(s"$f: $other")
    }
  }

  test("Conway's LEDGER encoder, called on its own, also sends them as text") {
    val f = PredicateFailure.utxo(UtxoFailure.ExpiredUTxO(Mismatch(100, 120)))
    val item = CborOut.build(PredicateFailureCbor.ledger(_, f))
    Cbor.decode(item).fold(e => fail(e.message), identity).value match
      case CborValue.Arr(Vector(tag, text)) =>
        assertEquals(tag.value, CborValue.UInt(7))
        assert(text.value.isInstanceOf[CborValue.Text])
      case other => fail(s"$other")
  }

  test("the failures every pre-Conway era shares encode to well-formed CBOR in each") {
    val shared: Vector[PredicateFailure] = Vector(
      PredicateFailure.utxo(UtxoFailure.InputSetEmpty),
      PredicateFailure.utxo(UtxoFailure.MaxTxSize(Mismatch(17000, 16384))),
      PredicateFailure.utxo(UtxoFailure.WrongNetwork(0, Set(keyHash))),
      PredicateFailure.utxo(
        UtxoFailure.BadInputs(Set(TxIn(Bytes(keyHash.value ++ keyHash.value.take(4)), 0)))
      ),
      PredicateFailure.utxow(UtxowFailure.ExtraneousScriptWitnesses(Set(keyHash))),
      PredicateFailure.utxow(UtxowFailure.InvalidMetadata),
      PredicateFailure.deleg(DelegFailure.StakeKeyNotRegistered(Credential.Script(keyHash))),
      PredicateFailure.pool(PoolFailure.StakePoolCostTooLow(Mismatch(1, 2)))
    )
    for
      era <- Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage)
      f <- shared
    do
      val bytes = PredicateFailureCbor.applyTxError(era, Vector(f)).getOrElse(fail(s"$era $f"))
      val decoded = Cbor.decode(bytes).fold(e => fail(s"$era $f: $e"), identity)
      decoded.value match
        case CborValue.Arr(Vector(wrapper)) =>
          wrapper.value match
            case CborValue.Arr(Vector(index, _)) =>
              assertEquals(index.value, CborValue.UInt(BigInt(era.index)), s"$era $f")
            case other => fail(s"$era $f: $other")
        case other => fail(s"$era $f: $other")
  }

  // ---- Collect errors and missing redeemers with certifying purposes (Alonzo, Babbage)
  //
  // Hand-derived from the pinned instances (see the header of ContextFailureCbor): a purpose is
  // [tag, item] with the bare certificate (Alonzo/Scripts.hs:370-397, AsItem), the certificate is
  // ShelleyTxCert (Shelley/TxCert.hs:420-435). A script credential is [1, hash]. The reference
  // node's own bytes for the certifying missing redeemers, NoRedeemer and the Babbage translation
  // faults are in PlutusFailureReferenceSuite (recorded by the e2e load); the cases below that it
  // does not cover (NoWitness, NoCostModel, Alonzo's unresolved input) stay hand-derived.

  private val scriptHash = hex("cc" * 28)
  private val s = scriptHash.hex
  private val pool = hex("dd" * 28)
  private val unregistration = Certificate.Unregister(Credential.Script(scriptHash), None)
  private val script = "581c" + s
  // a script credential as the reference writes it: [1, hash]
  private val scriptCredential = "8201" + script
  // the certificate [1 UnRegCert, credential] and the purpose [2 Certifying, certificate]
  private val unregisteredCert = "8201" + scriptCredential
  private val certifyingPurpose = "8202" + unregisteredCert
  // a missing redeemer entry: [purpose, script hash]
  private val missingEntry = "82" + certifyingPurpose + script

  private def collectErrors(error: CollectError): UtxoFailure =
    UtxoFailure.Utxos(
      UtxosFailure.CollectErrors(cats.data.NonEmptyVector.one(error))
    )

  test("Alonzo: a missing redeemer of a script-required unregistration carries the certificate") {
    // [4 era, [[0 LEDGER UTXOW, [1 MissingRedeemers, [[purpose, script]]]]]] in the failure list
    assertEquals(
      reason(
        Era.Alonzo,
        PredicateFailure.utxow(
          UtxowFailure.MissingRedeemers(Vector(Purpose.Certifying(0, unregistration) -> scriptHash))
        )
      ),
      Some("81" + "8204" + "81" + "8200" + "8201" + "81" + missingEntry)
    )
  }

  test("Babbage: the missing redeemer is Alonzo's, held at UTXOW tag 1") {
    // [5 era, [[0 LEDGER UTXOW, [1 Babbage: Alonzo's, [1 MissingRedeemers, [[purpose, script]]]]]]]
    assertEquals(
      reason(
        Era.Babbage,
        PredicateFailure.utxow(
          UtxowFailure.MissingRedeemers(Vector(Purpose.Certifying(0, unregistration) -> scriptHash))
        )
      ),
      Some("81" + "8205" + "81" + "8200" + "8201" + "8201" + "81" + missingEntry)
    )
  }

  test("Alonzo: a delegation by a script credential is [2, credential, pool]") {
    val delegation = Certificate.Delegate(Credential.Script(scriptHash), Some(pool), None, None)
    // the purpose [2, [2 DelegCert (a 3-element list), credential, pool]]
    val purpose = "8202" + "83" + "02" + scriptCredential + "581c" + pool.hex
    assertEquals(
      reason(
        Era.Alonzo,
        PredicateFailure.utxow(
          UtxowFailure.MissingRedeemers(Vector(Purpose.Certifying(0, delegation) -> scriptHash))
        )
      ),
      Some("81" + "8204" + "81" + "8200" + "8201" + "81" + "82" + purpose + script)
    )
  }

  test("Alonzo: a certificate no script can name in a purpose has no encoding") {
    // the reference names only a script credential's unregistration and delegation
    // (getScriptWitnessShelleyTxCert, Shelley/TxCert.hs:577-586)
    val keyUnregistration = Certificate.Unregister(Credential.Key(keyHash), None)
    assertEquals(
      reason(
        Era.Alonzo,
        PredicateFailure.utxow(
          UtxowFailure.MissingRedeemers(
            Vector(Purpose.Certifying(0, keyUnregistration) -> scriptHash)
          )
        )
      ),
      None
    )
  }

  test("Alonzo: NoRedeemer names the certifying purpose: UTXOS 1 under UTXO 7, under UTXOW 4") {
    // [0 LEDGER UTXOW, [0 Shelley UTXOW, [4 UTXO, [7 UTXOS, [1, [[0 NoRedeemer, purpose]]]]]]]
    val utxo = "8207" + "8201" + "81" + "8200" + certifyingPurpose
    assertEquals(
      reason(
        Era.Alonzo,
        PredicateFailure.utxo(
          collectErrors(
            CollectError.NoRedeemer(
              Purpose.Certifying(0, unregistration)
            )
          )
        )
      ),
      Some("81" + "8204" + "81" + "8200" + "8200" + "8204" + utxo)
    )
  }

  test("Alonzo: NoWitness is tag 1 with the script hash; NoCostModel is tag 2 with the language") {
    assertEquals(
      reason(Era.Alonzo, PredicateFailure.utxo(collectErrors(CollectError.NoWitness(scriptHash)))),
      Some(
        "81" + "8204" + "81" + "8200" + "8200" + "8204" + "8207" + "8201" + "81" + "8201" + script
      )
    )
    // NoCostModel: [2, language], the language as its enum index (PlutusV1 = 0, encodeEnum)
    assertEquals(
      reason(
        Era.Alonzo,
        PredicateFailure.utxo(collectErrors(CollectError.NoCostModel(PlutusLanguage.V1)))
      ),
      Some("81" + "8204" + "81" + "8200" + "8200" + "8204" + "8207" + "8201" + "81" + "8202" + "00")
    )
  }

  test("Alonzo: a translation that misses an input is ContextError 1 under BadTranslation 3") {
    val input = TxIn(Bytes(keyHash.value ++ keyHash.value.take(4)), 0)
    // TxIn is the list [transaction id, index]: 82, 5820 id, 00
    val txIn = "82" + "5820" + input.id.hex + "00"
    assertEquals(
      reason(
        Era.Alonzo,
        PredicateFailure.utxo(
          collectErrors(CollectError.BadTranslation(TranslationError.UnresolvedInput(input)))
        )
      ),
      // [3 BadTranslation, [1 TranslationLogicMissingInput, txIn]]
      Some(
        "81" + "8204" + "81" + "8200" + "8200" + "8204" + "8207" + "8201" + "81" + "8203" + "8201" + txIn
      )
    )
  }

  test("Babbage: the collect errors are UTXO 1 (Alonzo's) under UTXO 7 (UTXOS)") {
    // [5 era, [[0 LEDGER UTXOW, [2 Babbage UTXO, [1 Alonzo's, [7 UTXOS, [1, [[0, purpose]]]]]]]]]
    val utxo = "8202" + "8201" + "8207" + "8201" + "81" + "8200" + certifyingPurpose
    assertEquals(
      reason(
        Era.Babbage,
        PredicateFailure.utxo(
          collectErrors(CollectError.NoRedeemer(Purpose.Certifying(0, unregistration)))
        )
      ),
      Some("81" + "8205" + "81" + "8200" + utxo)
    )
  }
