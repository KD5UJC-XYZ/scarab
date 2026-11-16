// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  LedgerDefect,
  LedgerFailure,
  Mismatch,
  PredicateFailure,
  UtxowFailure,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.era.*
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

/** Alonzo's predicates of UTXOW (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Utxow.hs`,
  * cardano-ledger f649f975).
  */
object AlonzoUtxow:
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
