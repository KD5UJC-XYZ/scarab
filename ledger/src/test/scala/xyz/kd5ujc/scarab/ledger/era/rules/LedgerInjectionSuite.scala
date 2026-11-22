// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.data.{NonEmptyList, NonEmptySet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.era.IsPhase2Valid
import xyz.kd5ujc.scarab.ledger.state.{Coin, Conway}
import xyz.kd5ujc.scarab.ledger.era.Mismatch
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** The injections of LEDGER (`InjectRuleFailure "LEDGER"`, `Conway/Rules/Ledger.hs:133-187`) and
  * LEDGERS (`InjectRuleFailure "LEDGERS"`, `Conway/Rules/Ledgers.hs:28-82`, cardano-ledger
  * f649f975) at Conway: each instance, summoned, gives the failure the embeddings wrap it in, the
  * sub-rule's failure in UTXOW's, CERTS's or GOV's place in LEDGER's (`EmbedInLedger`) and LEDGER's
  * in LEDGERS's (`EmbedLedgerInLedgers`); Shelley's LEDGER failure, which the reference does not
  * inject into LEDGERS, has no instance there; and Shelley's `AccountAlreadyRegistered` goes into
  * LEDGER under the builds whose ledger injects it (`Conway/Rules/Ledger.hs:137-138`, 29a7d656 and
  * 9df9f98c), into LEDGERS under none.
  */
class LedgerInjectionSuite extends munit.FunSuite:
  private val ledger = EmbedInLedger.conway
  private val ledgers = EmbedLedgerInLedgers.conway

  private val shelleyW =
    ShelleyUtxowPredFailure.InvalidWitnessesUTXOW[Conway](NonEmptyList.one(Bytes.empty))
  private val alonzoW = AlonzoUtxowPredFailure.ShelleyInAlonzoUtxowPredFailure[Conway](shelleyW)
  private val babbageW =
    BabbageUtxowPredFailure.MalformedScriptWitnesses[Conway](NonEmptySet.one(Bytes.empty))
  private val conwayW =
    ConwayUtxowPredFailure.InvalidWitnessesUTXOW[Conway](NonEmptyList.one(Bytes.empty))
  private val conwayU = ConwayUtxoPredFailure.InputSetEmptyUTxO[Conway]()
  private val babbageU =
    BabbageUtxoPredFailure.IncorrectTotalCollateralField[Conway](BigInt(1), Coin.zero)
  private val alonzoU = AlonzoUtxoPredFailure.InputSetEmptyUTxO[Conway]()
  private val allegraU = AllegraUtxoPredFailure.InputSetEmptyUTxO[Conway]()
  private val alonzoS = AlonzoUtxosPredFailure.ValidationTagMismatch[Conway](
    IsPhase2Valid.Phase2Invalid,
    TagMismatchDescription.PassedUnexpectedly
  )
  private val conwayS = ConwayUtxosPredFailure.ValidationTagMismatch[Conway](
    IsPhase2Valid.Phase2Invalid,
    TagMismatchDescription.PassedUnexpectedly
  )
  private val shelleyU = ShelleyUtxoPredFailure.InputSetEmptyUTxO[Conway]()

  private val deleg = ConwayDelegPredFailure.IncorrectDepositDELEG[Conway](Coin.zero)
  private val pool =
    ShelleyPoolPredFailure.StakePoolCostTooLowPOOL[Conway](Mismatch(Coin.zero, Coin.zero))
  private val govCert =
    ConwayGovCertPredFailure.ConwayDRepIncorrectDeposit[Conway](Mismatch(Coin.zero, Coin.zero))
  private val cert = ConwayCertPredFailure.DelegFailure[Conway](deleg)
  private val certs = ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS[Conway](Map.empty)
  private val gov =
    ConwayGovPredFailure.ProposalDepositIncorrect[Conway](Mismatch(Coin.zero, Coin(BigInt(1))))
  private val conwayL =
    ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[Conway](
      Mismatch(Coin.zero, Coin(BigInt(1)))
    )
  private val shelleyL =
    ShelleyLedgerPredFailure.ShelleyWithdrawalsMissingAccounts[Conway](Map.empty)

  /** Each failure below LEDGER with the failure LEDGER's embeddings make of it. */
  private val belowLedger
      : Vector[(String, ConwayLedgerPredFailure[Conway], ConwayLedgerPredFailure[Conway])] =
    def utxow(f: UtxowPredFailure[Conway]) = ledger.wrapUtxowFailed(f)
    def utxo(f: UtxoPredFailure[Conway]) = utxow(EmbedUtxoInUtxow.conway.wrapFailed(f))
    def utxos(f: UtxosPredFailure[Conway]) = utxo(EmbedUtxosInUtxo.conway.wrapFailed(f))
    def inCerts(f: CertPredFailure[Conway]) =
      ledger.wrapCertsFailed(EmbedCertInCerts.conway.wrapFailed(f))
    Vector(
      (
        "ConwayUtxowPredFailure",
        summon[InjectLedgerFailure[ConwayUtxowPredFailure[Conway], Conway]].injectFailure(conwayW),
        utxow(conwayW)
      ),
      (
        "BabbageUtxowPredFailure",
        summon[InjectLedgerFailure[BabbageUtxowPredFailure[Conway], Conway]]
          .injectFailure(babbageW),
        utxow(InjectUtxowFailure.conwayBabbage.injectFailure(babbageW))
      ),
      (
        "AlonzoUtxowPredFailure",
        summon[InjectLedgerFailure[AlonzoUtxowPredFailure[Conway], Conway]].injectFailure(alonzoW),
        utxow(InjectUtxowFailure.conwayAlonzo.injectFailure(alonzoW))
      ),
      (
        "ShelleyUtxowPredFailure",
        summon[InjectLedgerFailure[ShelleyUtxowPredFailure[Conway], Conway]]
          .injectFailure(shelleyW),
        utxow(conwayW)
      ),
      (
        "ConwayUtxoPredFailure",
        summon[InjectLedgerFailure[ConwayUtxoPredFailure[Conway], Conway]].injectFailure(conwayU),
        utxo(conwayU)
      ),
      (
        "BabbageUtxoPredFailure",
        summon[InjectLedgerFailure[BabbageUtxoPredFailure[Conway], Conway]].injectFailure(babbageU),
        utxo(InjectUtxoFailure.conwayBabbage.injectFailure(babbageU))
      ),
      (
        "AlonzoUtxoPredFailure",
        summon[InjectLedgerFailure[AlonzoUtxoPredFailure[Conway], Conway]].injectFailure(alonzoU),
        utxo(conwayU)
      ),
      (
        "AllegraUtxoPredFailure",
        summon[InjectLedgerFailure[AllegraUtxoPredFailure[Conway], Conway]].injectFailure(allegraU),
        utxo(conwayU)
      ),
      (
        "AlonzoUtxosPredFailure",
        summon[InjectLedgerFailure[AlonzoUtxosPredFailure[Conway], Conway]].injectFailure(alonzoS),
        utxos(conwayS)
      ),
      (
        "ConwayUtxosPredFailure",
        summon[InjectLedgerFailure[ConwayUtxosPredFailure[Conway], Conway]].injectFailure(conwayS),
        utxos(conwayS)
      ),
      (
        "ConwayCertsPredFailure",
        summon[InjectLedgerFailure[ConwayCertsPredFailure[Conway], Conway]].injectFailure(certs),
        ledger.wrapCertsFailed(certs)
      ),
      (
        "ConwayCertPredFailure",
        summon[InjectLedgerFailure[ConwayCertPredFailure[Conway], Conway]].injectFailure(cert),
        inCerts(cert)
      ),
      (
        "ConwayDelegPredFailure",
        summon[InjectLedgerFailure[ConwayDelegPredFailure[Conway], Conway]].injectFailure(deleg),
        inCerts(EmbedInCert.conway.wrapDelegFailed(deleg))
      ),
      (
        "ShelleyPoolPredFailure",
        summon[InjectLedgerFailure[ShelleyPoolPredFailure[Conway], Conway]].injectFailure(pool),
        inCerts(EmbedInCert.conway.wrapPoolFailed(pool))
      ),
      (
        "ConwayGovCertPredFailure",
        summon[InjectLedgerFailure[ConwayGovCertPredFailure[Conway], Conway]]
          .injectFailure(govCert),
        inCerts(EmbedInCert.conway.wrapGovCertFailed(govCert))
      ),
      (
        "ConwayGovPredFailure",
        summon[InjectLedgerFailure[ConwayGovPredFailure[Conway], Conway]].injectFailure(gov),
        ledger.wrapGovFailed(gov)
      )
    )

  test("each injection into LEDGER is the embeddings' wrapping") {
    belowLedger.foreach((name, injected, wrapped) => assertEquals(injected, wrapped, name))
    assertEquals(
      summon[InjectLedgerFailure[ConwayLedgerPredFailure[Conway], Conway]].injectFailure(conwayL),
      conwayL
    )
    assertEquals(
      summon[InjectLedgerFailure[ShelleyLedgerPredFailure[Conway], Conway]].injectFailure(shelleyL),
      ConwayLedgerPredFailure.ConwayWithdrawalsMissingAccounts[Conway](Map.empty)
    )
    ReferenceBuild.values.foreach { build =>
      assertEquals(
        InjectLedgerFailure
          .conwayShelleyUtxo(ConwayUtxo.injectShelleyFailure(build))
          .injectFailure(shelleyU),
        ledger.wrapUtxowFailed(
          InjectUtxowFailure
            .conwayShelleyUtxo(ConwayUtxo.injectShelleyFailure(build))
            .injectFailure(shelleyU)
        ),
        build.name
      )
    }
  }

  test("each injection into LEDGERS is LEDGER's, wrapped as LEDGERS embeds LEDGER") {
    val belowLedgers: Vector[(String, ShelleyLedgersPredFailure[Conway])] = Vector(
      "ConwayUtxowPredFailure" ->
        summon[InjectLedgersFailure[ConwayUtxowPredFailure[Conway], Conway]].injectFailure(conwayW),
      "BabbageUtxowPredFailure" ->
        summon[InjectLedgersFailure[BabbageUtxowPredFailure[Conway], Conway]]
          .injectFailure(babbageW),
      "AlonzoUtxowPredFailure" ->
        summon[InjectLedgersFailure[AlonzoUtxowPredFailure[Conway], Conway]].injectFailure(alonzoW),
      "ShelleyUtxowPredFailure" ->
        summon[InjectLedgersFailure[ShelleyUtxowPredFailure[Conway], Conway]]
          .injectFailure(shelleyW),
      "ConwayUtxoPredFailure" ->
        summon[InjectLedgersFailure[ConwayUtxoPredFailure[Conway], Conway]].injectFailure(conwayU),
      "BabbageUtxoPredFailure" ->
        summon[InjectLedgersFailure[BabbageUtxoPredFailure[Conway], Conway]]
          .injectFailure(babbageU),
      "AlonzoUtxoPredFailure" ->
        summon[InjectLedgersFailure[AlonzoUtxoPredFailure[Conway], Conway]].injectFailure(alonzoU),
      "AllegraUtxoPredFailure" ->
        summon[InjectLedgersFailure[AllegraUtxoPredFailure[Conway], Conway]]
          .injectFailure(allegraU),
      "AlonzoUtxosPredFailure" ->
        summon[InjectLedgersFailure[AlonzoUtxosPredFailure[Conway], Conway]].injectFailure(alonzoS),
      "ConwayUtxosPredFailure" ->
        summon[InjectLedgersFailure[ConwayUtxosPredFailure[Conway], Conway]].injectFailure(conwayS),
      "ConwayCertsPredFailure" ->
        summon[InjectLedgersFailure[ConwayCertsPredFailure[Conway], Conway]].injectFailure(certs),
      "ConwayCertPredFailure" ->
        summon[InjectLedgersFailure[ConwayCertPredFailure[Conway], Conway]].injectFailure(cert),
      "ConwayDelegPredFailure" ->
        summon[InjectLedgersFailure[ConwayDelegPredFailure[Conway], Conway]].injectFailure(deleg),
      "ShelleyPoolPredFailure" ->
        summon[InjectLedgersFailure[ShelleyPoolPredFailure[Conway], Conway]].injectFailure(pool),
      "ConwayGovCertPredFailure" ->
        summon[InjectLedgersFailure[ConwayGovCertPredFailure[Conway], Conway]]
          .injectFailure(govCert),
      "ConwayGovPredFailure" ->
        summon[InjectLedgersFailure[ConwayGovPredFailure[Conway], Conway]].injectFailure(gov)
    )
    assertEquals(belowLedgers.map(_._1), belowLedger.map(_._1))
    belowLedgers.zip(belowLedger).foreach { case ((name, injected), (_, _, inLedger)) =>
      assertEquals(injected, ledgers.wrapFailed(inLedger), name)
    }
    assertEquals(
      summon[InjectLedgersFailure[ConwayLedgerPredFailure[Conway], Conway]].injectFailure(conwayL),
      ledgers.wrapFailed(conwayL)
    )
    val wrapped = ledgers.wrapFailed(conwayL)
    assertEquals(
      summon[InjectLedgersFailure[ShelleyLedgersPredFailure[Conway], Conway]]
        .injectFailure(wrapped),
      wrapped
    )
    ReferenceBuild.values.foreach { build =>
      assertEquals(
        InjectLedgersFailure
          .conwayShelleyUtxo(ConwayUtxo.injectShelleyFailure(build))
          .injectFailure(shelleyU),
        ledgers.wrapFailed(
          InjectLedgerFailure
            .conwayShelleyUtxo(ConwayUtxo.injectShelleyFailure(build))
            .injectFailure(shelleyU)
        ),
        build.name
      )
    }
  }

  test("Shelley's LEDGER failure has no injection into LEDGERS, as in the reference") {
    // `Conway/Rules/Ledgers.hs:28-82` (cardano-ledger f649f975) has no instance for it
    assert(
      !scala.compiletime.testing.typeChecks(
        "summon[InjectLedgersFailure[ShelleyLedgerPredFailure[Conway], Conway]]"
      )
    )
    assert(
      scala.compiletime.testing.typeChecks(
        "summon[InjectLedgersFailure[ConwayLedgerPredFailure[Conway], Conway]]"
      )
    )
  }

  test("Shelley's AccountAlreadyRegistered goes into LEDGER under the builds whose ledger has it") {
    // `Conway/Rules/Ledger.hs:137-138` (cardano-ledger 29a7d656 and 9df9f98c):
    // `ConwayCertsFailure . CertFailure . DelegFailure . injectFailure`, DELEG's
    // `DelegAccountAlreadyRegistered`; the 11.1 line's f649f975 has no such instance
    val credential = xyz.kd5ujc.scarab.ledger.state.Credential.KeyHashObj(
      xyz.kd5ujc.scarab.ledger.state.KeyHash
        .fromBytes[xyz.kd5ujc.scarab.ledger.state.Staking](Bytes(Vector.fill(28)(7.toByte)))
        .fold(e => fail(s"$e"), identity)
    )
    val registered = ConwayDelegPredFailure.StakeKeyRegisteredDELEG[Conway](credential)
    val expected = ConwayLedgerPredFailure.ConwayCertsFailure[Conway](
      ConwayCertsPredFailure.CertFailure(ConwayCertPredFailure.DelegFailure(registered))
    )
    assertEquals(
      ledger.wrapCertsFailed(
        EmbedCertInCerts.conway.wrapFailed(
          EmbedInCert.conway.wrapDelegFailed(InjectDeleg.conway.injectFailure(registered))
        )
      ),
      expected
    )
    ReferenceBuild.values.foreach { build =>
      val injected = InjectLedgerFailure
        .conwayAccountAlreadyRegistered(build)
        .map(_.injectFailure(AccountAlreadyRegistered[Conway](credential)))
      build match
        case ReferenceBuild.Release11_1 => assertEquals(injected, None, build.name)
        case ReferenceBuild.Release11_2 | ReferenceBuild.LeiosB004fe0c =>
          assertEquals(injected, Some(expected), build.name)
    }
    // and LEDGERS has no instance for it at any build
    assert(
      !scala.compiletime.testing.typeChecks(
        "summon[InjectLedgersFailure[AccountAlreadyRegistered[Conway], Conway]]"
      )
    )
  }
