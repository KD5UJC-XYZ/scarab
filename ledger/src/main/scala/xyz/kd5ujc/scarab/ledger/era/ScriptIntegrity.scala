// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.DecoderVersion
import xyz.kd5ujc.scarab.ledger.rules.EraNes
import xyz.kd5ujc.scarab.ledger.state.{EraTag, ShelleyBasedEra}
import xyz.kd5ujc.scarab.plutus.PlutusLanguage

/** A language's view of the protocol parameters (`data LangDepView`, cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/PParams.hs:547-548`): the encoded language tag and
  * the encoded cost model, each already serialised. Ordered by the tag, then the parameters, as the
  * derived `Ord`.
  */
final case class LangDepView(tag: Bytes, params: Bytes)

object LangDepView:
  given CanEqual[LangDepView, LangDepView] = CanEqual.derived

  given ordering: Ordering[LangDepView] = (a, b) =>
    val tags = a.tag.compareTo(b.tag)
    if tags != 0 then tags else a.params.compareTo(b.params)

  /** `getLanguageView` (`Alonzo/PParams.hs:566-585`, cardano-ledger f649f975): PlutusV1's tag is
    * the language serialised twice and its parameters the cost model's encoding serialised as bytes
    * (to keep a past bug); the later languages' tag is the language serialised and the parameters
    * the cost model's encoding. The cost model is the language's valid one, `null` without one.
    * `CostModels` keeps the valid and the unknown models in one map, a known language's model only
    * among the valid, so a lookup by a language's tag is `costModelsValid` 's. The encodings are at
    * the protocol version's major, which none of these encodings depend on.
    */
  def getLanguageView[E <: EraTag](pp: PParams[E], lang: PlutusLanguage)(using
      p: AlonzoEraPParams[E]
  ): LangDepView =
    val costModel =
      PParams.lensHKD[E].andThen(p.hkdCostModelsL[Identity]).get(pp).models.get(lang.id)
    val costModelEncoding = costModel.fold(Cbor.Null)(encodeCostModel(lang, _))
    lang match
      case PlutusLanguage.V1 =>
        // the double bagging keeps compatibility with a past bug
        LangDepView(Cbor.bytes(Cbor.uint(lang.id)), Cbor.bytes(costModelEncoding))
      case PlutusLanguage.V2 | PlutusLanguage.V3 | PlutusLanguage.V4 =>
        LangDepView(Cbor.uint(lang.id), costModelEncoding)

  /** `encodeCostModel` (`Alonzo/PParams.hs:550-564`, cardano-ledger f649f975): PlutusV1's
    * parameters as an indefinite list (the Alonzo encoding, kept), the later languages' as a
    * definite one.
    */
  private def encodeCostModel(lang: PlutusLanguage, params: Vector[BigInt]): Bytes =
    val items = params.foldLeft(Bytes.empty)(_ ++ Cbor.int(_))
    lang match
      case PlutusLanguage.V1 =>
        Bytes(Vector(0x9f.toByte)) ++ items ++ Bytes(Vector(0xff.toByte))
      case PlutusLanguage.V2 | PlutusLanguage.V3 | PlutusLanguage.V4 =>
        Cbor.head(4, BigInt(params.size)) ++ items

  /** `encodeLangViews` (`Alonzo/PParams.hs:587-597`, cardano-ledger f649f975): a definite map of
    * each view's tag to its parameters, already encoded, in the short-lex order of the tags.
    */
  def encodeLangViews(views: Set[LangDepView]): Bytes =
    val shortLex: Ordering[Bytes] = (a, b) =>
      if a.size != b.size then Integer.compare(a.size, b.size) else a.compareTo(b)
    val ascending = views.toVector.sortBy(_.tag)(using shortLex)
    ascending.foldLeft(Cbor.head(5, BigInt(views.size)))((acc, v) => acc ++ v.tag ++ v.params)

  /** The CBOR heads a language view needs: an unsigned or negative integer, a byte string's and a
    * container's head, and `null`, each in its shortest form, as cardano-ledger-binary writes them.
    */
  private object Cbor:
    val Null: Bytes = Bytes(Vector(0xf6.toByte))

    def head(major: Int, n: BigInt): Bytes =
      val initial = major << 5
      if n < 24 then Bytes(Vector((initial | n.toInt).toByte))
      else
        val width =
          if n < 256 then 1 else if n < 65536 then 2 else if n < (BigInt(1) << 32) then 4 else 8
        val ai = width match
          case 1 => 24
          case 2 => 25
          case 4 => 26
          case _ => 27
        Bytes(
          (initial | ai).toByte +: Vector.tabulate(width)(i => (n >> (8 * (width - 1 - i))).toByte)
        )

    def uint(n: BigInt): Bytes = head(0, n)
    def int(n: BigInt): Bytes = if n >= 0 then head(0, n) else head(1, -1 - n)
    def bytes(b: Bytes): Bytes = head(2, BigInt(b.size)) ++ b

/** What a transaction's script integrity hash is over (`data ScriptIntegrity era`, cardano-ledger
  * f649f975 `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:375-382`): the witness set's
  * redeemers and datums, and the views of the languages the transaction uses.
  */
final case class ScriptIntegrity[E <: EraTag](
    redeemers: Redeemers[PlutusPurpose[AsIx, E]],
    dats: TxDats,
    views: Set[LangDepView]
)

object ScriptIntegrity:
  /** `originalBytes` of a `ScriptIntegrity` (`SafeToHash`, `Alonzo/Tx.hs:391-395`, cardano-ledger
    * f649f975): the redeemers' bytes, the datums' bytes when there are datums and nothing
    * otherwise, then the language views encoded. A witness set without redeemers has the era's
    * empty redeemers (`mempty`, made by `mkMemoizedEra`, `Alonzo/TxWits.hs:197-198, 207-211`),
    * encoded at the era's `eraProtVerLow` whatever the protocol version
    * (`MemoBytes/Internal.hs:294-295`): a map from version 9, a list below
    * (`Alonzo/TxWits.hs:157-162`). The era's lowest version is found by its witness's exhaustive
    * match ([[EraNes.eraOf]], [[DecoderVersion.of]]).
    */
  def originalBytes[E <: EraTag](si: ScriptIntegrity[E])(using era: ShelleyBasedEra[E]): Bytes =
    val empty = if DecoderVersion.of(EraNes.eraOf(era)).atLeast(9) then 0xa0 else 0x80
    val redeemers = si.redeemers.raw.getOrElse(Bytes(Vector(empty.toByte)))
    val dats = if si.dats.datums.isEmpty then Bytes.empty else si.dats.raw.getOrElse(Bytes.empty)
    redeemers ++ dats ++ LangDepView.encodeLangViews(si.views)

  /** `hashScriptIntegrity` (`Alonzo/Tx.hs:401-402`, cardano-ledger f649f975): blake2b-256 of the
    * original bytes.
    */
  def hashScriptIntegrity[E <: EraTag](si: ScriptIntegrity[E])(using ShelleyBasedEra[E]): Bytes =
    Blake2b.hash256.hash(originalBytes(si))

  /** `mkScriptIntegrity` (`Alonzo/Tx.hs:404-423`, cardano-ledger f649f975): none when the witness
    * set has no redeemers and no datums and `langs` gives no view, otherwise the redeemers, the
    * datums and the view of each language of `langs`.
    */
  def mkScriptIntegrity[E <: EraTag, L <: TxLevel](
      pp: PParams[E],
      tx: Tx[L, E],
      langs: Set[PlutusLanguage]
  )(using
      t: EraTx[E],
      p: AlonzoEraPParams[E],
      w: AlonzoEraTxWits[E],
      l: t.bodyOf.Level[L]
  ): Option[ScriptIntegrity[E]] =
    val langViews = langs.map(LangDepView.getLanguageView(pp, _))
    val txWits = t.wits[L](tx)
    val txRedeemers = w.rdmrsTxWits(txWits)
    val txDats = w.datsTxWits(txWits)
    Option.unless(txRedeemers.entries.isEmpty && langViews.isEmpty && txDats.datums.isEmpty)(
      ScriptIntegrity(txRedeemers, txDats, langViews)
    )
