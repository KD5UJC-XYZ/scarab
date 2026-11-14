// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.*

/** Each era's UTXOW runs its own checks whatever the protocol version (cardano-ledger f649f975):
  * Shelley's `transitionRulesUTXOW` (`Shelley/Rules/Utxow.hs:270-326`), Alonzo's
  * `alonzoStyleWitness` (`Alonzo/Rules/Utxow.hs:294-374`) and Babbage's `babbageUtxowTransition`
  * (`Babbage/Rules/Utxow.hs:308-391`, Conway's too), with each era's supplemental datums
  * (`Alonzo/UTxO.hs:216-225`, `Babbage/UTxO.hs:74-83`), the empty redeemers of its own
  * `eraProtVerLow` in the script integrity hash (`Alonzo/TxWits.hs:157-162,203-211,227-230`) and
  * its auxiliary data as its decoder reads them (`Alonzo/TxAuxData.hs:213-222`). A chain that
  * starts an era by epoch (`Test<Era>HardForkAtEpoch`) keeps the version of the era before it, so
  * each era is checked here at a version it does not usually run at, through the era's LEDGER. No
  * transaction here has a Plutus script, so nothing reaches phase 2.
  */
class EraNotVersionUtxowSuite extends FunSuite:
  private val alice = TestLedger.key()
  private val funds = BigInt(1000000000000L)
  private val fee = BigInt(200)
  private val input = TestLedger.txIn(0)
  private val referenced = TestLedger.txIn(1)

  /** A datum (the integer 1) and its witness-set list `[1]`. */
  private val datum = Bytes(Vector(0x01.toByte))
  private val datumHash = Blake2b.hash256.hash(datum)
  private val datums = Bytes(Vector(0x81.toByte, 0x01.toByte))

  /** `blake2b-256(redeemers ‖ datums ‖ language views)` of a witness set with the datum, no
    * redeemers field and no language: the redeemers are `emptyList` (`0x80`) or the empty map
    * (`0xa0`).
    */
  private def integrity(emptyRedeemers: Int): Bytes =
    Blake2b.hash256.hash(
      Bytes(Vector(emptyRedeemers.toByte)) ++ datums ++ Bytes(Vector(0xa0.toByte))
    )
  private val beforeConway = integrity(0x80)
  private val fromConway = integrity(0xa0)

  private def out(coin: BigInt): TxOut = TestLedger.out(alice.address(), coin)

  /** A transaction spending the one input to one output, with the datum in its witness set and the
    * given script integrity hash.
    */
  private def withDatum(
      outputs: Vector[TxOut],
      hash: Bytes,
      referenceInputs: Vector[TxIn] = Vector.empty,
      collateralReturn: Option[TxOut] = None
  ): Tx =
    val body = TestLedger.body(
      "era-not-version-utxow",
      Vector(input),
      outputs,
      fee,
      scriptDataHash = Some(hash),
      referenceInputs = referenceInputs,
      collateralReturn = collateralReturn
    )
    TestLedger.tx(
      body,
      Vector(alice),
      Witnesses.empty.copy(datums = Vector(datum), datumsRaw = Some(datums))
    )

  /** The verdict of the era's LEDGER at protocol version `major` on `tx`, with `held` the UTxO set
    * beside the input.
    */
  private def ledger[E <: EraTag](
      major: Int,
      tx: Tx,
      held: Vector[(TxIn, TxOut)] = Vector.empty
  )(using ShelleyBasedEra[E]): Either[LedgerFailure, Unit] =
    NativeRules
      .txIn[E](NativeRules.stateIn[E](major, (input -> out(funds)) +: held), tx, TestLedger.env)
      .map(_ => ())

  private def utxow(tx: Tx, f: UtxowFailure): Either[LedgerFailure, Unit] =
    Left(LedgerFailure.Rejected(tx.body.id, PredicateFailure.utxow(f)))

  /** An output that carries the datum's hash: the datum is a supplemental one in every era. */
  private val locked = out(funds - fee).copy(datum = Datum.Hash(datumHash))

  test("Alonzo at version 4 runs Alonzo's UTXOW on its datums (Alonzo/Rules/Utxow.hs:294-374)") {
    val tx = withDatum(Vector(locked), beforeConway)
    assertEquals(ledger[Alonzo](4, tx), Right(()))
    // the script integrity hash is checked, not refused as a field the era cannot hold
    val wrong = withDatum(Vector(locked), fromConway)
    assertEquals(
      ledger[Alonzo](4, wrong),
      utxow(
        wrong,
        UtxowFailure.PPViewHashesDontMatch(Mismatch(Some(fromConway), Some(beforeConway)))
      )
    )
  }

  test("Alonzo at version 4 reads its auxiliary data as Alonzo's decoder (TxAuxData.hs:219-222)") {
    // tag 259 with an empty metadata map: the keyed form, Alonzo's from its decoder version 5
    val aux = Bytes(Vector(0xd9, 0x01, 0x03, 0xa1, 0x00, 0xa0).map(_.toByte))
    val plain = TestLedger.body("era-not-version-aux", Vector(input), Vector(out(funds - fee)), fee)
    val tx = TestLedger
      .tx(plain.copy(auxDataHash = Some(Blake2b.hash256.hash(aux))), Vector(alice))
      .copy(auxiliary = Some(aux))
    assertEquals(ledger[Alonzo](4, tx), Right(()))
  }

  test("Babbage at version 6 allows the datums of reference inputs and the collateral return") {
    // `getBabbageSupplementalDataHashes` (`Babbage/UTxO.hs:74-83`), whatever the version
    val plain = Vector(out(funds - fee))
    val viaReference = withDatum(plain, beforeConway, referenceInputs = Vector(referenced))
    assertEquals(
      ledger[Babbage](
        6,
        viaReference,
        Vector(referenced -> out(1000000).copy(datum = Datum.Hash(datumHash)))
      ),
      Right(())
    )
    val viaReturn =
      withDatum(
        plain,
        beforeConway,
        collateralReturn = Some(out(1000000).copy(datum = locked.datum))
      )
    assertEquals(ledger[Babbage](6, viaReturn), Right(()))
  }

  test("Alonzo at version 7 runs every native script it is given (Alonzo/Rules/Utxow.hs:326)") {
    // Alonzo's `validateFailedNativeScripts` runs every native script provided, first; Babbage's
    // `validateFailedBabbageScripts` only the needed ones (`Babbage/Rules/Utxow.hs:210-228`)
    val bob = TestLedger.key()
    val unneeded = Script.nativeOf(
      Bytes(Vector(0x82, 0x00, 0x58, 0x1c).map(_.toByte)) ++ bob.hash,
      NativeScript.Sig(bob.hash)
    )
    val tx = TestLedger.tx(
      TestLedger.body("era-not-version-native", Vector(input), Vector(out(funds - fee)), fee),
      Vector(alice),
      Witnesses.empty.copy(scripts = Vector(unneeded))
    )
    assertEquals(
      ledger[Alonzo](7, tx),
      utxow(tx, UtxowFailure.ScriptWitnessNotValidating(Set(unneeded.hash)))
    )
  }

  test("Babbage at version 6 refuses a malformed reference script (Babbage/Rules/Utxow.hs:379)") {
    val script = Script.plutus(ScriptKind.PlutusV1, Bytes(Vector(0x01.toByte)))
    val tx = TestLedger.tx(
      TestLedger.body(
        "era-not-version-script",
        Vector(input),
        Vector(out(funds - fee).copy(scriptRef = Some(script))),
        fee
      ),
      Vector(alice)
    )
    assertEquals(
      ledger[Babbage](6, tx),
      utxow(tx, UtxowFailure.MalformedReferenceScripts(Set(script.hash)))
    )
  }

  test("Babbage at version 9 hashes absent redeemers as Babbage's empty list (TxWits.hs:157-162)") {
    val tx = withDatum(Vector(locked), beforeConway)
    assertEquals(ledger[Babbage](9, tx), Right(()))
  }

  test("Conway at version 8 hashes absent redeemers as Conway's empty map (TxWits.hs:157-162)") {
    val tx = withDatum(Vector(locked), fromConway)
    assertEquals(ledger[Conway](8, tx), Right(()))
    val list = withDatum(Vector(locked), beforeConway)
    assertEquals(
      ledger[Conway](8, list),
      utxow(
        list,
        UtxowFailure.PPViewHashesDontMatch(Mismatch(Some(beforeConway), Some(fromConway)))
      )
    )
  }
