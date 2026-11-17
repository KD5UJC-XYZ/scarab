// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{Address, Credential, Mismatch, PredicateFailure, TxIn, UtxoFailure}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.Test
import xyz.kd5ujc.scarab.ledger.state.{Coin, EraTag}

// The predicates of UTXO as the reference writes them, each generic in its era and asking for the
// reference's classes (cardano-ledger f649f975, the `Rules/Utxo.hs` of each era). Each object is the
// module of the era that defines its functions, and each function has the reference's name and the
// constraints of its signature. A predicate reports the failures of the node's `UtxoFailure`, so its
// verdict is the one the flat rules give.

private def failure(f: UtxoFailure): PredicateFailure = PredicateFailure.utxo(f)

/** The network a body names, by its wire value (`Network`'s constructors in order,
  * `BaseTypes.hs:878-881`, cardano-ledger f649f975), as the failures carry it.
  */
private def wire(n: Network): Int = n.ordinal

/** Shelley's predicates of UTXO (`eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Utxo.hs`,
  * cardano-ledger f649f975).
  */
object ShelleyUtxo:
  /** `validateInputSetEmptyUTxO` (`Shelley/Rules/Utxo.hs:428-435`, cardano-ledger f649f975). */
  def validateInputSetEmptyUTxO[E <: EraTag, L <: TxLevel](body: TxBody[L, E])(using
      b: EraTxBody[E],
      l: b.Level[L]
  ): Test[PredicateFailure] =
    Test.failureUnless(b.inputs(body).nonEmpty, failure(UtxoFailure.InputSetEmpty))

  /** `validateBadInputsUTxO` (`Shelley/Rules/Utxo.hs:461-469`, cardano-ledger f649f975): the inputs
    * the UTxO set does not hold.
    */
  def validateBadInputsUTxO[E <: EraTag](
      utxo: UTxO[E],
      inputs: Set[TxIn]
  ): Test[PredicateFailure] =
    Test.failureOnNonEmpty(inputs.filterNot(utxo.unUTxO.contains))(_.isEmpty)(bad =>
      failure(UtxoFailure.BadInputs(bad))
    )

  /** `validateWrongNetwork` (`Shelley/Rules/Utxo.hs:474-485`, cardano-ledger f649f975): the
    * addresses of `outputs` on another network than `netId`, a bootstrap address's network taken
    * from its attributes (`getNetwork`).
    */
  def validateWrongNetwork[E <: EraTag](netId: Network, outputs: Vector[TxOut[E]])(using
      o: EraTxOut[E]
  ): Test[PredicateFailure] =
    val wrong = outputs
      .map(o.address)
      .filter(a => networkOf(a).exists(_ != wire(netId)))
      .map(_.raw)
      .toSet
    Test.failureOnNonEmpty(wrong)(_.isEmpty)(addrs =>
      failure(UtxoFailure.WrongNetwork(wire(netId), addrs))
    )

  /** `getNetwork` of an address: a Shelley address's header network, a bootstrap address's
    * attributes' network. A bootstrap address the node cannot read has none, as in the flat check.
    */
  private def networkOf(a: Address): Option[Int] = a.kind match
    case Address.Kind.Shelley(n, _, _, _) => Some(n)
    case Address.Kind.Byron(_) =>
      xyz.kd5ujc.scarab.ledger.byron.ByronAddress.decode(a.raw).toOption.map(_.network)

  /** `validateWrongNetworkWithdrawal` (`Shelley/Rules/Utxo.hs:490-501`, cardano-ledger f649f975):
    * the withdrawals from accounts on another network than `netId`.
    */
  def validateWrongNetworkWithdrawal[E <: EraTag, L <: TxLevel](netId: Network, body: TxBody[L, E])(
      using
      b: EraTxBody[E],
      l: b.Level[L]
  ): Test[PredicateFailure] =
    val wrong = b.withdrawals(body).keySet.filter(_.network != wire(netId))
    Test.failureOnNonEmpty(wrong)(_.isEmpty)(accounts =>
      failure(UtxoFailure.WrongNetworkWithdrawal(wire(netId), accounts))
    )

  /** `validateMaxTxSizeUTxO` (`Shelley/Rules/Utxo.hs:563-577`, cardano-ledger f649f975): the
    * transaction's size (`sizeTxF`) at most `ppMaxTxSize`.
    */
  def validateMaxTxSizeUTxO[E <: EraTag, L <: TxLevel](pp: PParams[E], tx: Tx[L, E])(using
      t: EraTx[E],
      l: t.bodyOf.Level[L],
      p: EraPParams[E]
  ): Test[PredicateFailure] =
    val txSize = BigInt(t.sizeTxF(tx))
    val maxTxSize = EraPParams.ppMaxTxSizeL[E].get(pp).value
    Test.failureUnless(
      txSize <= maxTxSize,
      failure(UtxoFailure.MaxTxSize(Mismatch(txSize, maxTxSize)))
    )

  /** `validateOutputBootAddrAttrsTooBig` (`Shelley/Rules/Utxo.hs:544-558`, cardano-ledger
    * f649f975): the outputs to a bootstrap address whose attributes take more than 64 bytes. It
    * reports Shelley's own failure; each later era's UTXO injects it as its build has it, which the
    * era's rule does when it runs this. A bootstrap address the node cannot read has no attributes,
    * as in the flat check (a stored output's address was read when its output was).
    */
  def validateOutputBootAddrAttrsTooBig[E <: EraTag](outputs: Vector[TxOut[E]])(using
      o: EraTxOut[E],
      s: StoredTxOut[E]
  ): Test[PredicateFailure] =
    val outputsAttrsTooBig = outputs.filter(out =>
      o.address(out).kind match
        case Address.Kind.Byron(_) =>
          xyz.kd5ujc.scarab.ledger.byron.ByronAddress
            .decode(o.address(out).raw)
            .toOption
            .exists(_.attributesSize > 64)
        case Address.Kind.Shelley(_, _, _, _) => false
    )
    Test.failureOnNonEmpty(outputsAttrsTooBig)(_.isEmpty)(big =>
      failure(UtxoFailure.OutputBootAddrAttrsTooBig(big.map(s.toStored)))
    )

/** Allegra's predicates of UTXO (`eras/allegra/impl/src/Cardano/Ledger/Allegra/Rules/Utxo.hs`,
  * cardano-ledger f649f975).
  */
object AllegraUtxo:
  /** `validateOutsideValidityIntervalUTxO` (`Allegra/Rules/Utxo.hs:230-237`, cardano-ledger
    * f649f975), with `inInterval` (`Allegra/Scripts.hs:504-509`): from `invalidBefore`, and before
    * `invalidHereafter`.
    */
  def validateOutsideValidityIntervalUTxO[E <: EraTag, L <: TxLevel](
      slot: BigInt,
      body: TxBody[L, E]
  )(using
      b: AllegraEraTxBody[E],
      l: b.Level[L]
  ): Test[PredicateFailure] =
    val vi = b.vldt(body)
    Test.failureUnless(
      vi.invalidBefore.forall(_ <= slot) && vi.invalidHereafter.forall(slot < _),
      failure(UtxoFailure.OutsideValidityInterval(vi.invalidBefore, vi.invalidHereafter, slot))
    )

/** Alonzo's predicates of UTXO (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Utxo.hs`,
  * cardano-ledger f649f975).
  */
object AlonzoUtxo:
  /** `validateWrongNetworkInTxBody` (`Alonzo/Rules/Utxo.hs:436-446`, cardano-ledger f649f975). */
  def validateWrongNetworkInTxBody[E <: EraTag, L <: TxLevel](netId: Network, body: TxBody[L, E])(
      using
      b: AlonzoEraTxBody[E],
      l: b.Level[L]
  ): Test[PredicateFailure] =
    b.networkId(body)
      .fold(Test.pass[PredicateFailure])(bid =>
        Test.failureUnless(
          bid == netId,
          failure(UtxoFailure.WrongNetworkInTxBody(Mismatch(wire(bid), wire(netId))))
        )
      )

  /** `validateTooManyCollateralInputs` (`Alonzo/Rules/Utxo.hs:470-481`, cardano-ledger f649f975):
    * at most `ppMaxCollateralInputs` collateral inputs, counted as a set and the count narrowed to
    * a `Word16`, as the reference compares it.
    */
  def validateTooManyCollateralInputs[E <: EraTag](pp: PParams[E], body: TxBody[TxLevel.Top, E])(
      using
      b: AlonzoEraTxBody[E],
      p: AlonzoEraPParams[E]
  ): Test[PredicateFailure] =
    val maxColl = PParams.lensHKD[E].andThen(p.hkdMaxCollateralInputsL[Identity]).get(pp).value
    // `fromIntegral . Set.size`: the size narrowed to a `Word16`, so modulo 2^16
    val numColl = BigInt(b.collateralInputs(body).size) % (BigInt(1) << 16)
    Test.failureUnless(
      numColl <= maxColl,
      failure(UtxoFailure.TooManyCollateralInputs(Mismatch(numColl.toInt, maxColl.toInt)))
    )

  /** `isKeyHashAddr` and `vKeyLocked` (`Alonzo/Rules/Utxo.hs:252-266`, cardano-ledger f649f975): an
    * output locked by a key, a bootstrap address's included.
    */
  def vKeyLocked[E <: EraTag](out: TxOut[E])(using o: EraTxOut[E]): Boolean =
    o.address(out).kind match
      case Address.Kind.Shelley(_, Credential.Key(_), _, _)    => true
      case Address.Kind.Shelley(_, Credential.Script(_), _, _) => false
      case Address.Kind.Byron(_)                               => true

  /** `validateScriptsNotPaidUTxO` (`Alonzo/Rules/Utxo.hs:327-332`, cardano-ledger f649f975): the
    * collateral outputs not locked by a key.
    */
  def validateScriptsNotPaidUTxO[E <: EraTag](utxoCollateral: Map[TxIn, TxOut[E]])(using
      o: EraTxOut[E],
      s: StoredTxOut[E]
  ): Test[PredicateFailure] =
    Test.failureOnNonEmpty(utxoCollateral.filterNot((_, out) => vKeyLocked(out)))(_.isEmpty)(
      notPaid => failure(UtxoFailure.ScriptsNotPaid(notPaid.view.mapValues(s.toStored).toMap))
    )

  /** `validateInsufficientCollateral` (`Alonzo/Rules/Utxo.hs:335-350`, cardano-ledger f649f975): a
    * hundred times the balance at least the fee times `ppCollateralPercentage`; the failure names
    * the least balance, rounded up.
    */
  def validateInsufficientCollateral[E <: EraTag](
      pp: PParams[E],
      body: TxBody[TxLevel.Top, E],
      bal: BigInt
  )(using b: EraTxBody[E], p: AlonzoEraPParams[E]): Test[PredicateFailure] =
    val txfee = b.fee(body).value
    val collPerc = AlonzoEraPParams.ppCollateralPercentageL[E].get(pp).value
    Test.failureUnless(
      bal * 100 >= collPerc * txfee,
      failure(UtxoFailure.InsufficientCollateral(bal, Rational(collPerc * txfee, 100).ceil))
    )

  /** `validateExUnitsTooBigUTxO` (`Alonzo/Rules/Utxo.hs:451-465`, cardano-ledger f649f975): the
    * units of every redeemer (`totExUnits`) pointwise at most `ppMaxTxExUnits`.
    */
  def validateExUnitsTooBigUTxO[E <: EraTag, L <: TxLevel](pp: PParams[E], tx: Tx[L, E])(using
      t: AlonzoEraTx[E],
      l: t.bodyOf.Level[L],
      p: AlonzoEraPParams[E]
  ): Test[PredicateFailure] =
    val maxTxExUnits = AlonzoEraPParams.ppMaxTxExUnitsL[E].get(pp).units
    val totalExUnits = AlonzoTx.totExUnits(tx)
    Test.failureUnless(
      totalExUnits.fits(maxTxExUnits),
      failure(UtxoFailure.ExUnitsTooBig(Mismatch(totalExUnits, maxTxExUnits)))
    )

  /** `validateOutputTooBigUTxO` (`Alonzo/Rules/Utxo.hs:412-431`, cardano-ledger f649f975): the
    * outputs whose value takes more than `ppMaxValSize` bytes serialised, collected by a left fold
    * that puts each one in front, so the last first. A value's serialised size is the size of the
    * encoding the node keeps of it (`Value.serializedSize`), which is the encoding of `MaryValue`
    * (`Mary/Value.hs:342-350`, cardano-ledger f649f975) from protocol major 2, where a map of more
    * than 23 entries is indefinite (cardano-ledger-binary `Encoding/Encoder.hs:402-407`,
    * cardano-ledger f649f975); every major an era with this check runs at is 5 or more.
    */
  def validateOutputTooBigUTxO[E <: EraTag](pp: PParams[E], outputs: Vector[TxOut[E]])(using
      o: EraTxOut[E],
      p: AlonzoEraPParams[E],
      s: StoredTxOut[E]
  ): Test[PredicateFailure] =
    val maxValSize = AlonzoEraPParams.ppMaxValSizeL[E].get(pp).value
    val outputsTooBig = outputs.foldLeft(List.empty[(Int, Int, xyz.kd5ujc.scarab.ledger.TxOut)]) {
      (ans, txOut) =>
        val serSize = s.storedValue(o.value(txOut)).serializedSize
        if BigInt(serSize) > maxValSize then (serSize, maxValSize.toInt, s.toStored(txOut)) :: ans
        else ans
    }
    Test.failureOnNonEmpty(outputsTooBig.toVector)(_.isEmpty)(big =>
      failure(UtxoFailure.OutputTooBig(big))
    )

/** Babbage's predicates of UTXO (`eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/Utxo.hs`,
  * cardano-ledger f649f975).
  */
object BabbageUtxo:
  /** `disjointRefInputs` (`Babbage/Rules/Utxo.hs:200-214`, cardano-ledger f649f975): the inputs
    * that are also reference inputs, refused above Babbage's highest version and below 11, so at 9
    * and 10.
    */
  def disjointRefInputs[E <: EraTag](
      pp: PParams[E],
      inputs: Set[TxIn],
      refInputs: Set[TxIn]
  )(using p: EraPParams[E]): Test[PredicateFailure] =
    val major = p.ppProtocolVersionL.get(pp).pvMajor.value
    if major > BabbageProtVerHigh && major < 11 then
      Test.failureOnNonEmpty(inputs.intersect(refInputs).toVector.sorted)(_.isEmpty)(common =>
        failure(UtxoFailure.BabbageNonDisjointRefInputs(common))
      )
    else Test.pass

  /** `eraProtVerHigh @BabbageEra` (`type ProtVerHigh BabbageEra = 8`,
    * `libs/cardano-ledger-core/internal/Cardano/Ledger/Internal/Definition/Era.hs:125`,
    * cardano-ledger f649f975).
    */
  private val BabbageProtVerHigh = 8

  /** The number of values of a `Word64`, by which `sumAllCoin` 's sum wraps. */
  private val Word64Values = BigInt(1) << 64

  /** `feesOK` (`Babbage/Rules/Utxo.hs:168-197`, cardano-ledger f649f975): the fee at least the
    * minimum (`getMinFeeTxUtxo`), and the collateral of a transaction with redeemers.
    */
  def feesOK[E <: EraTag](pp: PParams[E], tx: Tx[TxLevel.Top, E], u: UTxO[E])(using
      e: EraUTxO[E],
      b: BabbageEraTxBody[E],
      w: AlonzoEraTxWits[E],
      p: AlonzoEraPParams[E],
      s: StoredTxOut[E]
  ): Test[PredicateFailure] =
    val txBody = e.txOf.body[TxLevel.Top](tx)(using e.txOf.bodyOf.top)
    val collateral = b.collateralInputs(txBody)
    val utxoCollateral = u.unUTxO.filter((in, _) => collateral.contains(in))
    val theFee = b.fee(txBody).value
    val minFee = e.getMinFeeTxUtxo[TxLevel.Top](pp, tx, u)(using e.txOf.bodyOf.top).value
    Test.all(
      Test.failureUnless(
        minFee <= theFee,
        failure(UtxoFailure.FeeTooSmall(Mismatch(theFee, minFee)))
      ),
      if w.rdmrsTxWits(e.txOf.wits[TxLevel.Top](tx)(using e.txOf.bodyOf.top)).entries.isEmpty
      then Test.pass
      else validateTotalCollateral(pp, txBody, utxoCollateral)
    )

  /** `validateTotalCollateral` (`Babbage/Rules/Utxo.hs:216-241`, cardano-ledger f649f975): the
    * collateral locked by keys, ada only once the return is taken off, enough, equal to the
    * declared total if there is one, and not empty.
    */
  def validateTotalCollateral[E <: EraTag](
      pp: PParams[E],
      txBody: TxBody[TxLevel.Top, E],
      utxoCollateral: Map[TxIn, TxOut[E]]
  )(using
      b: BabbageEraTxBody[E],
      p: AlonzoEraPParams[E],
      s: StoredTxOut[E]
  ): Test[PredicateFailure] =
    given EraTxOut[E] = b.outputsOf
    val bal = collAdaBalance(txBody, utxoCollateral)
    Test.all(
      AlonzoUtxo.validateScriptsNotPaidUTxO(utxoCollateral),
      validateCollateralContainsNonADA(txBody, utxoCollateral),
      AlonzoUtxo.validateInsufficientCollateral(pp, txBody, bal),
      validateCollateralEqBalance(bal, b.totalCollateral(txBody)),
      Test.failureUnless(utxoCollateral.nonEmpty, failure(UtxoFailure.NoCollateralInputs))
    )

  /** `validateCollateralContainsNonADA` (`Babbage/Rules/Utxo.hs:253-292`, cardano-ledger f649f975):
    * the collateral ada only, or its assets all sent to the return; the failure names the return's
    * value if the collateral outputs are ada only, else their balance.
    */
  def validateCollateralContainsNonADA[E <: EraTag](
      txBody: TxBody[TxLevel.Top, E],
      utxoCollateral: Map[TxIn, TxOut[E]]
  )(using b: BabbageEraTxBody[E], s: StoredTxOut[E]): Test[PredicateFailure] =
    val o = b.outputsOf
    val v = o.valueOps
    given EraTxOut[E] = o
    val ret = b.sizedCollateralReturn(txBody).map(_.value)
    def areAllAdaOnly(outs: Iterable[TxOut[E]]) = outs.forall(out => v.isAdaOnly(o.value(out)))
    val utxoCollateralHasOnlyAda = areAllAdaOnly(utxoCollateral.values)
    val collateralBalance = UTxOs.sumAllValue(utxoCollateral.values)
    val totalCollateralBalance =
      ret.fold(collateralBalance)(r => v.minus(collateralBalance, o.value(r)))
    val onlyAdaInCollateral =
      (utxoCollateralHasOnlyAda && areAllAdaOnly(ret)) || v.isAdaOnly(totalCollateralBalance)
    val valueWithNonAda = ret match
      case Some(r) if utxoCollateralHasOnlyAda => o.value(r)
      case _                                   => collateralBalance
    Test.failureUnless(
      onlyAdaInCollateral,
      failure(UtxoFailure.CollateralContainsNonAda(s.storedValue(valueWithNonAda)))
    )

  /** `validateCollateralEqBalance` (`Babbage/Rules/Utxo.hs:294-300`, cardano-ledger f649f975): the
    * balance equal to the declared total collateral, if the body declares one.
    */
  def validateCollateralEqBalance(bal: BigInt, txcoll: Option[Coin]): Test[PredicateFailure] =
    txcoll.fold(Test.pass[PredicateFailure])(tc =>
      Test.failureUnless(
        bal == tc.value,
        failure(UtxoFailure.IncorrectTotalCollateralField(bal, tc.value))
      )
    )

  /** `collAdaBalance` (`eras/babbage/impl/src/Cardano/Ledger/Babbage/Collateral.hs:30-41`,
    * cardano-ledger f649f975): the lovelace of the collateral outputs less the return's, a
    * `DeltaCoin`. The outputs' lovelace is added up as `sumAllCoin` adds it, in a `Word64` that
    * wraps (`State/UTxO.hs:207-212`, cardano-ledger f649f975). Collateral outputs are distinct
    * entries of the UTxO set, so their sum is at most the supply, far below 2^64, and no reachable
    * input wraps.
    */
  def collAdaBalance[E <: EraTag](
      txBody: TxBody[TxLevel.Top, E],
      utxoCollateral: Map[TxIn, TxOut[E]]
  )(using b: BabbageEraTxBody[E]): BigInt =
    val o = b.outputsOf
    val colbal =
      utxoCollateral.valuesIterator.map(out => o.valueOps.coin(o.value(out)).value).sum %
        Word64Values
    b.sizedCollateralReturn(txBody)
      .fold(colbal)(r => colbal - o.valueOps.coin(o.value(r.value)).value)

  /** `validateOutputTooSmallUTxO` (`Babbage/Rules/Utxo.hs:302-323`, cardano-ledger f649f975): the
    * outputs whose value is not pointwise at least `inject` of their least lovelace
    * (`getMinCoinSizedTxOut`, Babbage's [[BabbageTxOuts.babbageMinUTxOValue]]), with that least
    * lovelace. Against `inject` of a coin, whose asset map is empty, `pointwise (>=)` is the coin
    * at least the least lovelace and every quantity at least zero: `pointWise` of a map against an
    * empty one tests each value against zero (`Data/CanonicalMaps.hs:104-106`, cardano-ledger
    * f649f975), so the order of the maps' trees does not enter.
    */
  def validateOutputTooSmallUTxO[E <: EraTag](pp: PParams[E], outs: Vector[Sized[TxOut[E]]])(using
      o: EraTxOut[E],
      p: BabbageEraPParams[E],
      v: Value[E] =:= MultiAssetValue,
      s: StoredTxOut[E]
  ): Test[PredicateFailure] =
    val outsWithMin = outs.map(out => (out.value, BabbageTxOuts.babbageMinUTxOValue(pp, out)))
    val outputsTooSmall = outsWithMin.filterNot { (out, minSize) =>
      val value = v(o.value(out))
      value.lovelace >= minSize.value && value.assets.valuesIterator.forall(
        _.valuesIterator.forall(_ >= 0)
      )
    }
    Test.failureOnNonEmpty(outputsTooSmall)(_.isEmpty)(small =>
      failure(
        UtxoFailure.BabbageOutputTooSmall(small.map((out, min) => (s.toStored(out), min.value)))
      )
    )
