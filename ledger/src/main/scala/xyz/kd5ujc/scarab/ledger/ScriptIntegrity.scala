// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes

/** The script integrity hash (`script_data_hash`) of a transaction:
  *
  * {{{
  * blake2b-256(redeemers ‖ datums ‖ language views)
  * }}}
  *
  * as the ledger's `hashScriptIntegrity` computes it.
  *
  *   - The redeemers and the datums are the witness set's own bytes, not a re-encoding. The datums
  *     are omitted when the witness set has none. Without redeemers the redeemer part is the
  *     encoding of an empty collection: an empty list before Conway, an empty map from Conway.
  *   - The language views are a map from language to cost model for exactly the Plutus languages of
  *     the scripts the transaction needs, with the key order of the canonical CBOR map (shorter
  *     encodings first). PlutusV2 and V3 are `language id => [cost model]` with a definite list.
  *     PlutusV1 keeps its historical encoding: the key is the byte string `0x4100` (the language id
  *     serialised, then wrapped as bytes) and the value is a byte string wrapping the cost model as
  *     an indefinite-length list. Because of that key, V1 sorts after V2 and V3.
  *   - No languages gives the empty map `0xa0`.
  *   - A transaction with no redeemers, no datums and no languages has no hash.
  *
  * The cost model of a language is the list of its parameters in canonical order, from the protocol
  * parameters in force for the block.
  */
object ScriptIntegrity:
  def hash(tx: Tx, pp: ProtocolParams, languages: Set[ScriptKind]): Option[Bytes] =
    val wits = tx.witnesses
    val views = languageViews(pp, languages)
    if wits.redeemers.isEmpty && wits.datums.isEmpty && languages.forall(!_.isPlutus) then None
    else
      val emptyRedeemers = Vector((if pp.protocolMajor >= 9 then 0xa0 else 0x80).toByte)
      val redeemers = wits.redeemersRaw.fold(emptyRedeemers)(_.value)
      val datums =
        if wits.datums.isEmpty then Vector.empty[Byte]
        else wits.datumsRaw.fold(Vector.empty[Byte])(_.value)
      Some(Blake2b.hash256.hash(Bytes(redeemers ++ datums ++ views)))

  /** The encoded language views for `languages`. */
  def languageViews(pp: ProtocolParams, languages: Set[ScriptKind]): Vector[Byte] =
    val plutus = languages.toVector.filter(_.isPlutus).sortBy(_.tag)
    // Encoded keys sort by length first: the one-byte keys of V2 and V3 precede V1's two bytes.
    val ordered = plutus.filter(_ != ScriptKind.PlutusV1) ++ plutus.filter(_ == ScriptKind.PlutusV1)
    val entries = ordered.flatMap { kind =>
      val language = kind.tag - 1
      pp.costModels.get(language).toVector.map { model =>
        val ints = model.flatMap(Cbor.int)
        if kind == ScriptKind.PlutusV1 then
          // Key 0x41 0x00; value: bytes( 0x9f ints 0xff ).
          Vector(0x41, 0x00).map(_.toByte) ++ Cbor.bytesHeader(
            ints.size + 2
          ) ++ (0x9f.toByte +: ints) :+ 0xff.toByte
        else Cbor.int(language) ++ Cbor.arrayHeader(model.size) ++ ints
      }
    }
    if entries.isEmpty then Vector(0xa0.toByte) else Cbor.mapHeader(entries.size) ++ entries.flatten

  /** Minimal CBOR writers for the integers and headers a language view needs. */
  private object Cbor:
    private def head(major: Int, v: BigInt): Vector[Byte] =
      if v < 24 then Vector(((major << 5) | v.toInt).toByte)
      else if v < 256 then Vector(((major << 5) | 24).toByte, v.toByte)
      else if v < 65536 then Vector(((major << 5) | 25).toByte, (v >> 8).toByte, v.toByte)
      else if v < (BigInt(1) << 32) then
        ((major << 5) | 26).toByte +: Vector(24, 16, 8, 0).map(s => (v >> s).toByte)
      else
        ((major << 5) | 27).toByte +: Vector(56, 48, 40, 32, 24, 16, 8, 0).map(s => (v >> s).toByte)
    def int(n: BigInt): Vector[Byte] = if n >= 0 then head(0, n) else head(1, -1 - n)
    def bytesHeader(n: Int): Vector[Byte] = head(2, n)
    def arrayHeader(n: Int): Vector[Byte] = head(4, n)
    def mapHeader(n: Int): Vector[Byte] = head(5, n)
