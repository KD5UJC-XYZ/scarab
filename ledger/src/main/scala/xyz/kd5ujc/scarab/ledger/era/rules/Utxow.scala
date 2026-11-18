// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  LedgerDefect,
  LedgerFailure,
  Mismatch,
  PredicateFailure,
  Purpose,
  UtxowFailure,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPlutusPurpose
import xyz.kd5ujc.scarab.ledger.rules.Test
import xyz.kd5ujc.scarab.ledger.state.EraTag

// The predicates of UTXOW as the reference writes them, each generic in its era and asking for the
// reference's classes (cardano-ledger f649f975, the `Rules/Utxow.hs` of each era), as
// `era/rules/Utxo.scala` writes UTXO's. A predicate reports the failures of the node's
// `UtxowFailure`, so its verdict is the one the flat rules give.

private def utxow(f: UtxowFailure): PredicateFailure = PredicateFailure.utxow(f)

/** Shelley's predicates of UTXOW (`eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Utxow.hs`,
  * cardano-ledger f649f975).
  */
object ShelleyUtxow:
  /** `validateVerifiedWits` (`Shelley/Rules/Utxow.hs:390-407`, cardano-ledger f649f975): the keys
    * of the key witnesses, then of the bootstrap witnesses, whose signature of the body's hash does
    * not verify. The key witnesses are in their set's order (`Ord WitVKey`), as the reference lists
    * them; the bootstrap witnesses in the order `bootAddrTxWits` gives, a sequence the decoder
    * holds in the reference's set order (`Ord BootstrapWitness`, by address root, one per root:
    * [[xyz.kd5ujc.scarab.ledger.BootstrapWitness.set]]). The node's signature checks are
    * `verdicts`; one that gives no verdict is a defect of the provider, not a failure of the
    * transaction (`LedgerFailure.Unverifiable`), as in the flat check.
    */
  def validateVerifiedWits[E <: EraTag, L <: TxLevel](tx: Tx[L, E], verdicts: Verdicts)(using
      t: EraTx[E],
      l: t.bodyOf.Level[L]
  ): Either[LedgerDefect, Test[PredicateFailure]] =
    val txBodyHash = t.bodyOf.id[L](t.body[L](tx))
    val wits = t.wits[L](tx)
    def failing(witnesses: Vector[(Bytes, Bytes)]) =
      witnesses.traverseFilter((vkey, signature) =>
        verdicts.witness(vkey, signature, txBodyHash).map(valid => Option.unless(valid)(vkey))
      )
    (
      failing(t.witsOf.addrTxWits(wits).toVector.map(w => (w.vkey, w.signature))),
      failing(t.witsOf.bootAddrTxWits(wits).map(w => (w.vkey, w.signature)))
    ).mapN(_ ++ _)
      .bimap(
        LedgerFailure.Unverifiable(txBodyHash, _),
        failed =>
          Test.failureOnNonEmpty(failed)(_.isEmpty)(keys =>
            utxow(UtxowFailure.InvalidWitnesses(keys))
          )
      )

  /** `validateNeededWitnesses` (`Shelley/Rules/Utxow.hs:409-423`, cardano-ledger f649f975): the
    * hashes of the keys that must sign (`getWitsVKeyNeeded`) and that no witness provides.
    */
  def validateNeededWitnesses[E <: EraTag, L <: TxLevel](
      witsKeyHashes: Set[Bytes],
      certState: xyz.kd5ujc.scarab.ledger.state.CertState[E],
      utxo: UTxO[E],
      txBody: TxBody[L, E]
  )(using u: EraUTxO[E], l: u.txOf.bodyOf.Level[L]): Test[PredicateFailure] =
    val needed = u.getWitsVKeyNeeded[L](certState, utxo, txBody)
    Test.failureOnNonEmpty(needed -- witsKeyHashes)(_.isEmpty)(missing =>
      utxow(UtxowFailure.MissingVKeyWitnesses(missing))
    )

/** Alonzo's predicates of UTXOW (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Utxow.hs`,
  * cardano-ledger f649f975).
  */
object AlonzoUtxow:
  /** `missingRequiredDatums` (`Alonzo/Rules/Utxow.hs:210-234`, cardano-ledger f649f975): the spent
    * Plutus outputs that need a datum and have none (`UnspendableUTxONoDatumHash`), the datums the
    * spent outputs name that the witnesses lack (`MissingRequiredDatums`, with the witnesses'
    * hashes), and the witnesses' other datums that are not supplemental
    * (`NotAllowedSupplementalDatums`, with the supplemental ones), in that order.
    */
  def missingRequiredDatums[E <: EraTag, L <: TxLevel](
      scriptsProvided: ScriptsProvided[E],
      utxo: UTxO[E],
      tx: Tx[L, E]
  )(using
      t: AlonzoEraTx[E],
      l: t.bodyOf.Level[L],
      u: AlonzoEraUTxO[E],
      lu: u.txOf.bodyOf.Level[L]
  ): Test[PredicateFailure] =
    val txBody = t.body[L](tx)
    val (inputHashes, txInsNoDataHash) = AlonzoUTxO.getInputDataHashesTxBody[E, L](
      utxo,
      txBody,
      scriptsProvided
    )(using t.bodyOf, l, t.bodyOf.outputsOf, t.witsOf.scriptsOf)
    val txHashes: Set[Bytes] = t.witsOf.datsTxWits(t.wits[L](tx)).datums.keySet
    val unmatchedDatumHashes = inputHashes -- txHashes
    val allowedSupplementalDataHashes = u.getSupplementalDataHashes[L](utxo, txBody)
    val supplimentalDatumHashes = txHashes -- inputHashes
    val (okSupplimentalDHs, notOkSupplimentalDHs) =
      supplimentalDatumHashes.partition(allowedSupplementalDataHashes.contains)
    Test.all(
      Test.failureOnNonEmpty(txInsNoDataHash)(_.isEmpty)(ins =>
        utxow(UtxowFailure.UnspendableUTxONoDatumHash(ins))
      ),
      Test.failureOnNonEmpty(unmatchedDatumHashes)(_.isEmpty)(unmatched =>
        utxow(UtxowFailure.MissingRequiredDatums(unmatched, txHashes))
      ),
      Test.failureOnNonEmpty(notOkSupplimentalDHs)(_.isEmpty)(notOk =>
        utxow(UtxowFailure.NotAllowedSupplementalDatums(notOk, okSupplimentalDHs))
      )
    )

  /** `hasExactSetOfRedeemers` (`Alonzo/Rules/Utxow.hs:239-262`, cardano-ledger f649f975): the
    * redeemers are exactly those of the purposes needed whose script provided is a Plutus script.
    * The redeemers no such purpose names, in their map's order (`ExtraRedeemers`, as tag and
    * index), then the purposes needed that no redeemer names, in the order of the scripts needed
    * with their scripts' hashes (`MissingRedeemers`).
    *
    * The node's failure names each missing purpose as its flat [[Purpose]], with its index and
    * item, which `stored` gives: the reference keeps its item alone (`toAsItem`). A purpose is
    * Conway's (the evidence), as the purpose builders and `hoistPlutusPurpose` are not members of
    * `AlonzoEraScript` yet (#85).
    */
  def hasExactSetOfRedeemers[E <: EraTag, L <: TxLevel](
      tx: Tx[L, E],
      scriptsProvided: ScriptsProvided[E],
      scriptsNeeded: AlonzoScriptsNeeded[E]
  )(stored: PlutusPurpose[AsIxItem, E] => Purpose)(using
      t: AlonzoEraTx[E],
      l: t.bodyOf.Level[L],
      ev: PlutusPurpose[AsIxItem, E] =:= ConwayPlutusPurpose[AsIxItem],
      evIx: PlutusPurpose[AsIx, E] =:= ConwayPlutusPurpose[AsIx]
  ): Test[PredicateFailure] =
    given EraScript[E] = t.witsOf.scriptsOf
    val toAsIx = ConwayPlutusPurpose.hoistPlutusPurpose[AsIxItem, AsIx](
      [ix, it] => (asIxItem: AsIxItem[ix, it]) => AsIxItem.toAsIx(asIxItem)
    )
    val redeemersNeeded = scriptsNeeded.unAlonzoScriptsNeeded.flatMap { (sp, sh) =>
      scriptsProvided.unScriptsProvided
        .get(sh)
        .filterNot(EraScript.isNativeScript[E](_))
        .map(_ => (toAsIx(ev(sp)), (sp, sh)))
    }
    val (extraRdmrs, missingRdmrs) =
      extSymmetricDifference(
        t.witsOf.rdmrsTxWits(t.wits[L](tx)).entries.keys.toVector.map(evIx(_)),
        identity[ConwayPlutusPurpose[AsIx]],
        redeemersNeeded,
        _._1
      )
    Test.all(
      Test.failureOnNonEmpty(extraRdmrs)(_.isEmpty)(extra =>
        utxow(UtxowFailure.ExtraRedeemers(extra.map(p => (p.tag, p.ix.value))))
      ),
      Test.failureOnNonEmpty(missingRdmrs.map(_._2))(_.isEmpty)(missing =>
        utxow(UtxowFailure.MissingRedeemers(missing.map((sp, sh) => (stored(sp), sh))))
      )
    )

  /** `extSymmetricDifference` (`Alonzo/Rules/Utxow.hs:378-383`, cardano-ledger f649f975): the
    * elements of each list whose key the other list lacks, each list's order and duplicates kept.
    */
  def extSymmetricDifference[A, B, K](
      as: Vector[A],
      fa: A => K,
      bs: Vector[B],
      fb: B => K
  ): (Vector[A], Vector[B]) =
    val intersection = as.map(fa).toSet.intersect(bs.map(fb).toSet)
    val extraA = as.filterNot(x => intersection.contains(fa(x)))
    val extraB = bs.filterNot(x => intersection.contains(fb(x)))
    (extraA, extraB)

  /** `checkScriptIntegrityHash` (`Alonzo/Rules/Utxow.hs:266-286`, cardano-ledger f649f975): the
    * body's script integrity hash is the hash of `scriptIntegrity` (none for none). A mismatch is
    * `PPViewHashesDontMatch` below protocol version 11, and from 11 `ScriptIntegrityHashMismatch`
    * with the bytes the expected hash is over.
    */
  def checkScriptIntegrityHash[E <: EraTag, L <: TxLevel](
      tx: Tx[L, E],
      pp: PParams[E],
      scriptIntegrity: Option[ScriptIntegrity[E]]
  )(using
      t: AlonzoEraTx[E],
      p: EraPParams[E],
      era: Era[E],
      l: t.bodyOf.Level[L]
  ): Test[PredicateFailure] =
    val computedScriptIntegrityHash = scriptIntegrity.map(ScriptIntegrity.hashScriptIntegrity(_))
    val suppliedScriptIntegrityHash = t.bodyOf.scriptIntegrityHash[L](t.body[L](tx))
    val expectedScriptIntegrity = scriptIntegrity.map(ScriptIntegrity.originalBytes(_))
    val mismatch = Mismatch(suppliedScriptIntegrityHash, computedScriptIntegrityHash)
    Test.failureUnless(
      suppliedScriptIntegrityHash == computedScriptIntegrityHash,
      utxow(
        if p.ppProtocolVersionL.get(pp).pvMajor.value < 11 then
          UtxowFailure.PPViewHashesDontMatch(mismatch)
        else UtxowFailure.ScriptIntegrityHashMismatch(mismatch, expectedScriptIntegrity)
      )
    )

/** Babbage's predicates of UTXOW (`eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/Utxow.hs`,
  * cardano-ledger f649f975).
  */
object BabbageUtxow:
  /** `babbageMissingScripts` (`Babbage/Rules/Utxow.hs:193-208`, cardano-ledger f649f975): the
    * scripts received are exactly the needed ones less those the reference inputs and the inputs
    * provide. The extraneous first, then the missing. The parameters are unused, as in the
    * reference.
    */
  def babbageMissingScripts[E <: EraTag](
      pp: PParams[E],
      sNeeded: Set[Bytes],
      sRefs: Set[Bytes],
      sReceived: Set[Bytes]
  ): Test[PredicateFailure] =
    val _ = pp
    val neededNonRefs = sNeeded -- sRefs
    val missing = neededNonRefs -- sReceived
    val extra = sReceived -- neededNonRefs
    Test.all(
      Test.failureOnNonEmpty(extra)(_.isEmpty)(hs =>
        utxow(UtxowFailure.ExtraneousScriptWitnesses(hs))
      ),
      Test.failureOnNonEmpty(missing)(_.isEmpty)(hs =>
        utxow(UtxowFailure.MissingScriptWitnesses(hs))
      )
    )
