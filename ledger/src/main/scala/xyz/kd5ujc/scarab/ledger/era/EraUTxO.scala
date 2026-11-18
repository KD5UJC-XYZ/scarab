// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, TxIn, Unsigned}
import xyz.kd5ujc.scarab.ledger.state.{
  Accounts,
  Coin,
  Conway,
  Credential,
  EraTag,
  KeyHash,
  PState,
  StakePool,
  Staking
}

/** What a transaction of era `E` consumes and produces, and its minimum fee given the UTxO set
  * (`class EraTx era => EraUTxO era`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/State/UTxO.hs:242-285`). The superclass `EraTx era`
  * is a member ([[txOf]]), as the other classes' superclasses of other families are.
  *
  * The keys that must sign a transaction (`getWitsVKeyNeeded`) are UTXOW's last member, and come
  * with `validateNeededWitnesses` (#85).
  */
trait EraUTxO[E <: EraTag]:
  /** The transactions' class (the superclass `EraTx era`). */
  val txOf: EraTx[E]

  /** `getConsumedValue`: what the body consumes, given the deposit of a registered staking
    * credential.
    */
  def getConsumedValue[L <: TxLevel](
      pp: PParams[E],
      lookupStakingDeposit: Credential[Staking] => Option[Coin],
      utxo: UTxO[E],
      body: TxBody[L, E]
  )(using txOf.bodyOf.Level[L]): Value[E]

  /** `getProducedValue`: what a top-level body produces, given whether a pool is registered. */
  def getProducedValue(
      pp: PParams[E],
      isPoolRegistered: KeyHash[StakePool] => Boolean,
      body: TxBody[TxLevel.Top, E]
  ): Value[E]

  /** `getScriptsProvided`: the scripts the transaction provides, from its witnesses and, from
    * Babbage, the outputs of its inputs the UTxO set holds.
    */
  def getScriptsProvided[L <: TxLevel](utxo: UTxO[E], tx: Tx[L, E])(using
      txOf.bodyOf.Level[L]
  ): ScriptsProvided[E]

  /** `getScriptsNeeded`: what finds the scripts the body needs, by purpose. */
  def getScriptsNeeded[L <: TxLevel](utxo: UTxO[E], body: TxBody[L, E])(using
      txOf.bodyOf.Level[L]
  ): ScriptsNeeded[E]

  /** `getScriptsHashesNeeded`: the hashes of the scripts needed. */
  def getScriptsHashesNeeded(scriptsNeeded: ScriptsNeeded[E]): Set[Bytes]

  /** `getMinFeeTxUtxo`: the minimum fee, its witnesses excluded, the reference scripts the UTxO set
    * gives it counted.
    */
  def getMinFeeTxUtxo[L <: TxLevel](pp: PParams[E], tx: Tx[L, E], utxo: UTxO[E])(using
      txOf.bodyOf.Level[L]
  ): Coin

/** `ScriptsProvided` (`State/UTxO.hs:229-232`, cardano-ledger f649f975): the scripts a transaction
  * provides, by hash.
  */
final case class ScriptsProvided[E <: EraTag](unScriptsProvided: Map[Bytes, Script[E]])

/** `AlonzoScriptsNeeded` (`Alonzo/UTxO.hs:102-104`, cardano-ledger f649f975): each script needed
  * with the purpose that needs it, in the reference's order; a script may be needed for several
  * purposes.
  */
final case class AlonzoScriptsNeeded[E <: EraTag](
    unAlonzoScriptsNeeded: Vector[(PlutusPurpose[AsIxItem, E], Bytes)]
):
  /** The derived `Semigroup` (`Alonzo/UTxO.hs:104`, cardano-ledger f649f975): both, in order. */
  def ++(that: AlonzoScriptsNeeded[E]): AlonzoScriptsNeeded[E] =
    AlonzoScriptsNeeded(unAlonzoScriptsNeeded ++ that.unAlonzoScriptsNeeded)

/** Alonzo's functions of the UTxO set (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/UTxO.hs`,
  * cardano-ledger f649f975). The reference builds each purpose through the era's script class
  * (`SpendingPurpose` and the rest, `AlonzoEraScript`), whose builders are not members here yet
  * (#85): these functions take the evidence that the era's purpose is Conway's, as CERTS takes
  * Conway's certificates.
  */
object AlonzoUTxO:
  private type Ix = Unsigned[Unsigned.Width.W32]

  /** `zipAsIxItem` (`Alonzo/UTxO.hs:364-368`, cardano-ledger f649f975): each item with its
    * position, the `Int` narrowed to a `Word32` (`fromIntegral`).
    */
  def zipAsIxItem[A, C](items: Iterable[A])(f: AsIxItem[Ix, A] => C): Vector[C] =
    items.iterator.zipWithIndex
      .map((item, ix) => f(AsIxItem(Unsigned.wrapping[Unsigned.Width.W32](BigInt(ix)), item)))
      .toVector

  /** The script hash of a credential (`credScriptHash`). */
  private def credScriptHash(credential: xyz.kd5ujc.scarab.ledger.Credential): Option[Bytes] =
    credential match
      case xyz.kd5ujc.scarab.ledger.Credential.Script(h) => Some(h)
      case xyz.kd5ujc.scarab.ledger.Credential.Key(_)    => None

  /** `getSpendingScriptsNeeded` (`Alonzo/UTxO.hs:370-383`, cardano-ledger f649f975): each input, by
    * its position among the inputs, whose output the UTxO set holds at a script's payment address
    * (`getScriptHash`).
    */
  def getSpendingScriptsNeeded[E <: EraTag, L <: TxLevel](utxo: UTxO[E], body: TxBody[L, E])(using
      b: EraTxBody[E],
      l: b.Level[L],
      ev: PlutusPurpose[AsIxItem, E] =:= conway.ConwayPlutusPurpose[AsIxItem]
  ): AlonzoScriptsNeeded[E] =
    val o = b.outputsOf
    AlonzoScriptsNeeded(
      zipAsIxItem(b.inputs[L](body)) { asIxItem =>
        for
          out <- utxo.unUTxO.get(asIxItem.item)
          hash <- o.address(out).kind match
            case Address.Kind.Shelley(_, payment, _, _) => credScriptHash(payment)
            case Address.Kind.Byron(_)                  => None
        yield (ev.flip(conway.ConwayPlutusPurpose.ConwaySpending(asIxItem)), hash)
      }.flatten
    )

  /** `getWithdrawingScriptsNeeded` (`Alonzo/UTxO.hs:385-394`, cardano-ledger f649f975): each
    * withdrawal from a script's account, by its position in the withdrawals' order (`Map.keys` of
    * the accounts).
    */
  def getWithdrawingScriptsNeeded[E <: EraTag, L <: TxLevel](body: TxBody[L, E])(using
      b: EraTxBody[E],
      l: b.Level[L],
      ev: PlutusPurpose[AsIxItem, E] =:= conway.ConwayPlutusPurpose[AsIxItem]
  ): AlonzoScriptsNeeded[E] =
    AlonzoScriptsNeeded(
      zipAsIxItem(b.withdrawals[L](body).keys.toVector.sorted) { asIxItem =>
        credScriptHash(asIxItem.item.credential).map(hash =>
          (ev.flip(conway.ConwayPlutusPurpose.ConwayWithdrawing(asIxItem)), hash)
        )
      }.flatten
    )

  /** `getMintingScriptsNeeded` (`Alonzo/UTxO.hs:403-411`, cardano-ledger f649f975): each policy the
    * body mints or burns, by its position in the policies' order (`mintedTxBodyF`, a set).
    */
  def getMintingScriptsNeeded[E <: EraTag, L <: TxLevel](body: TxBody[L, E])(using
      b: MaryEraTxBody[E],
      l: b.Level[L],
      ev: PlutusPurpose[AsIxItem, E] =:= conway.ConwayPlutusPurpose[AsIxItem]
  ): AlonzoScriptsNeeded[E] =
    AlonzoScriptsNeeded(
      zipAsIxItem(b.mint[L](body).keys.toVector.sorted) { asIxItem =>
        (ev.flip(conway.ConwayPlutusPurpose.ConwayMinting(asIxItem)), asIxItem.item)
      }
    )

  /** `getAlonzoScriptsHashesNeeded` (`Alonzo/UTxO.hs:241-242`, cardano-ledger f649f975). */
  def getAlonzoScriptsHashesNeeded[E <: EraTag](scriptsNeeded: AlonzoScriptsNeeded[E]): Set[Bytes] =
    scriptsNeeded.unAlonzoScriptsNeeded.map(_._2).toSet

/** The core's functions of the UTxO set (`State/UTxO.hs`, cardano-ledger f649f975). */
object UTxOs:
  /** `txInsFilter` (`State/UTxO.hs:168-174`, cardano-ledger f649f975): the outputs of `txIns`. */
  def txInsFilter[E <: EraTag](utxo: UTxO[E], txIns: Set[TxIn]): UTxO[E] =
    UTxO(utxo.unUTxO.filter((in, _) => txIns.contains(in)))

  /** `sumAllValue` (`State/UTxO.hs:199-200`, cardano-ledger f649f975). */
  def sumAllValue[E <: EraTag](outs: Iterable[TxOut[E]])(using o: EraTxOut[E]): Value[E] =
    outs.foldLeft(o.valueOps.zero)((total, out) => o.valueOps.plus(total, o.value(out)))

  /** `sumUTxO` (`State/UTxO.hs:185-186`, cardano-ledger f649f975). */
  def sumUTxO[E <: EraTag](utxo: UTxO[E])(using EraTxOut[E]): Value[E] =
    sumAllValue(utxo.unUTxO.values)

/** Shelley's functions of the UTxO set (`eras/shelley/impl/src/Cardano/Ledger/Shelley/UTxO.hs`,
  * cardano-ledger f649f975).
  */
object ShelleyUTxO:
  /** `shelleyConsumed` (`Shelley/UTxO.hs:109-118`, cardano-ledger f649f975): what the body
    * consumes, each refunded credential's deposit looked up in `accounts`.
    */
  def shelleyConsumed[E <: EraTag, L <: TxLevel](
      pp: PParams[E],
      accounts: Accounts[E],
      utxo: UTxO[E],
      body: TxBody[L, E]
  )(using u: EraUTxO[E], c: EraCertState[E], l: u.txOf.bodyOf.Level[L]): Value[E] =
    u.getConsumedValue(
      pp,
      EraAccounts.lookupAccountDeposit[E](_, accounts)(using c.accountsOf),
      utxo,
      body
    )

  /** `produced` (`Shelley/UTxO.hs:120-129`, cardano-ledger f649f975): what the body produces, a
    * pool registered when `pState` has it.
    */
  def produced[E <: EraTag](pp: PParams[E], pState: PState, body: TxBody[TxLevel.Top, E])(using
      u: EraUTxO[E]
  ): Value[E] =
    u.getProducedValue(pp, pState.stakePools.contains, body)

  /** `shelleyProducedValue` (`Shelley/UTxO.hs:131-143`, cardano-ledger f649f975): the outputs, the
    * fee and the deposits.
    */
  def shelleyProducedValue[E <: EraTag](
      pp: PParams[E],
      isRegPoolId: KeyHash[StakePool] => Boolean,
      body: TxBody[TxLevel.Top, E]
  )(using b: EraTxBody[E]): Value[E] =
    given EraTxOut[E] = b.outputsOf
    val v = b.outputsOf.valueOps
    v.plus(
      UTxOs.sumAllValue(b.outputs[TxLevel.Top](body)(using b.top)),
      v.inject(Coin(b.fee(body).value + b.getTotalDepositsTxBody(pp, isRegPoolId, body).value))
    )

/** Mary's functions of the UTxO set (`eras/mary/impl/src/Cardano/Ledger/Mary/UTxO.hs`,
  * cardano-ledger f649f975), of the eras whose value is `MaryValue` (`Value era ~ MaryValue`, given
  * as `v`).
  */
object MaryUTxO:
  /** `getConsumedMaryValue` (`Mary/UTxO.hs:65-81`, cardano-ledger f649f975): the spent outputs, the
    * refunds, the withdrawals and the assets the body mints.
    */
  def getConsumedMaryValue[E <: EraTag, L <: TxLevel](
      pp: PParams[E],
      lookupStakingDeposit: Credential[Staking] => Option[Coin],
      utxo: UTxO[E],
      body: TxBody[L, E]
  )(using b: MaryEraTxBody[E], l: b.Level[L], v: Value[E] =:= MultiAssetValue): MultiAssetValue =
    given EraTxOut[E] = b.outputsOf
    val ops = summon[Val[MultiAssetValue]]
    val mintedMultiAsset =
      MultiAssetValue.of(xyz.kd5ujc.scarab.ledger.Value.minted(b.mint(body)))
    val refunds = b.getTotalRefundsTxBody(pp, lookupStakingDeposit, body)
    val withdrawals = b.withdrawals(body).valuesIterator.map(_.value).sum
    val consumedValue = ops.plus(
      v(UTxOs.sumUTxO(UTxOs.txInsFilter(utxo, b.inputs(body)))),
      ops.inject(Coin(refunds.value + withdrawals))
    )
    ops.plus(consumedValue, mintedMultiAsset)

  /** `getProducedMaryValue` (`Mary/UTxO.hs:83-91`, cardano-ledger f649f975): Shelley's produced
    * value and the assets the body burns.
    */
  def getProducedMaryValue[E <: EraTag](
      pp: PParams[E],
      isPoolRegistered: KeyHash[StakePool] => Boolean,
      body: TxBody[TxLevel.Top, E]
  )(using b: MaryEraTxBody[E], v: Value[E] =:= MultiAssetValue): MultiAssetValue =
    summon[Val[MultiAssetValue]].plus(
      v(ShelleyUTxO.shelleyProducedValue(pp, isPoolRegistered, body)),
      burnedMultiAssets[E, TxLevel.Top](body)(using b, b.top)
    )

  /** `burnedMultiAssets` (`Mary/UTxO.hs:93-97`, cardano-ledger f649f975): the assets the body mints
    * a negative quantity of, negated.
    */
  def burnedMultiAssets[E <: EraTag, L <: TxLevel](body: TxBody[L, E])(using
      b: MaryEraTxBody[E],
      l: b.Level[L]
  ): MultiAssetValue =
    MultiAssetValue.of(xyz.kd5ujc.scarab.ledger.Value.burned(b.mint(body)))

/** Babbage's functions of the UTxO set (`eras/babbage/impl/src/Cardano/Ledger/Babbage/UTxO.hs`,
  * cardano-ledger f649f975).
  */
object BabbageUTxO:
  /** `getReferenceScriptsNonDistinct` (`Babbage/UTxO.hs:157-166`, cardano-ledger f649f975): the
    * reference script of each output of `inputs` that has one, a script as many times as outputs
    * carry it, with its hash, in the order of the inputs (`Map.elems`).
    */
  def getReferenceScriptsNonDistinct[E <: EraTag](utxo: UTxO[E], inputs: Set[TxIn])(using
      o: BabbageEraTxOut[E]
  ): Vector[(xyz.kd5ujc.scarab.codec.Bytes, Script[E])] =
    UTxOs
      .txInsFilter(utxo, inputs)
      .unUTxO
      .toVector
      .sortBy(_._1)
      .flatMap((_, out) => o.referenceScript(out))
      .map(script => (o.scripts.hashScript(script), script))

  /** `getReferenceScripts` (`Babbage/UTxO.hs:150-155`, cardano-ledger f649f975): the reference
    * scripts of `inputs` ' outputs by hash (`Map.fromList`, a later one of a hash kept).
    */
  def getReferenceScripts[E <: EraTag](utxo: UTxO[E], inputs: Set[TxIn])(using
      BabbageEraTxOut[E]
  ): Map[Bytes, Script[E]] =
    getReferenceScriptsNonDistinct(utxo, inputs).toMap

  /** `getBabbageScriptsProvided` (`Babbage/UTxO.hs:136-146`, cardano-ledger f649f975): the
    * reference scripts of the spent and reference inputs, and the witnesses' scripts, a reference
    * script kept where both have a hash (`Map.union`, left-biased).
    */
  def getBabbageScriptsProvided[E <: EraTag, L <: TxLevel](utxo: UTxO[E], tx: Tx[L, E])(using
      t: EraTx[E],
      b: BabbageEraTxBody[E],
      l: t.bodyOf.Level[L],
      lb: b.Level[L],
      o: BabbageEraTxOut[E]
  ): ScriptsProvided[E] =
    val txBody = t.body[L](tx)
    val ins: Set[TxIn] = b.referenceInputs[L](txBody)(using lb) ++ b.inputs[L](txBody)(using lb)
    ScriptsProvided(t.witsOf.scriptTxWits(t.wits[L](tx)) ++ getReferenceScripts(utxo, ins))

/** Conway's instance (`instance EraUTxO ConwayEra`, `eras/conway/impl/src/Cardano/Ledger/Conway/
  * UTxO.hs:119-137`, cardano-ledger f649f975).
  */
object EraUTxO:
  given conway: EraUTxO[Conway] = xyz.kd5ujc.scarab.ledger.era.conway.ConwayUTxO.instance
