// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Credential,
  Mismatch,
  PredicateFailure,
  ProtocolParams,
  Tx,
  TxBody,
  TxChecks,
  TxEnv,
  TxIn,
  TxOut,
  Utxo,
  UtxoFailure,
  Value
}

/** The predicates of UTXO as the reference writes them, each a pure [[Test]] that reports every
  * offending item (cardano-ledger `f649f975`: `Shelley/Rules/Utxo.hs:412-560`,
  * `Allegra/Rules/Utxo.hs:225-275`, `Alonzo/Rules/Utxo.hs:278-481`,
  * `Babbage/Rules/Utxo.hs:170-412`), and the sequence of them each era's UTXO runs: Shelley
  * (`utxoInductive`), Allegra and Mary (`Allegra.utxoTransition`), Alonzo (`utxoTransition`), and
  * Babbage and Conway (`babbageUtxoValidation`). A rule records a sequence with [[Sts.runTest]];
  * every test of it runs, whatever failed before.
  */
object UtxoTests:
  private def failure(f: UtxoFailure): PredicateFailure = PredicateFailure.utxo(f)

  private def unless(cond: Boolean, f: => UtxoFailure): Test[PredicateFailure] =
    Test.failureUnless(cond, failure(f))

  private def nonEmpty[C](items: C)(isEmpty: C => Boolean)(f: C => UtxoFailure) =
    Test.failureOnNonEmpty(items)(isEmpty)(c => failure(f(c)))

  /** `validateTimeToLive` (`Shelley/Rules/Utxo.hs:414-423`): the last slot the transaction is valid
    * in (Shelley).
    */
  def timeToLive(body: TxBody, slot: BigInt): Test[PredicateFailure] =
    body.ttl.fold(Test.pass[PredicateFailure])(ttl =>
      unless(ttl >= slot, UtxoFailure.ExpiredUTxO(Mismatch(ttl, slot)))
    )

  /** `validateOutsideValidityIntervalUTxO` (`Allegra/Rules/Utxo.hs:225-235`, `inInterval`). */
  def validityInterval(body: TxBody, slot: BigInt): Test[PredicateFailure] =
    unless(
      body.ttl.forall(slot < _) && body.validityStart.forall(_ <= slot),
      UtxoFailure.OutsideValidityInterval(body.validityStart, body.ttl, slot)
    )

  /** `validateInputSetEmptyUTxO` (`Shelley/Rules/Utxo.hs:425-434`). */
  def inputSetEmpty(body: TxBody): Test[PredicateFailure] =
    unless(body.inputs.nonEmpty, UtxoFailure.InputSetEmpty)

  /** `validateFeeTooSmallUTxO` (`Shelley/Rules/Utxo.hs:436-454`), `feesOK` part 1. */
  def feeTooSmall(body: TxBody, minFee: BigInt): Test[PredicateFailure] =
    unless(minFee <= body.fee, UtxoFailure.FeeTooSmall(Mismatch(body.fee, minFee)))

  /** `validateBadInputsUTxO` (`Shelley/Rules/Utxo.hs:456-467`): the inputs the UTxO set does not
    * hold.
    */
  def badInputs(utxo: Utxo, inputs: Iterable[TxIn]): Test[PredicateFailure] =
    nonEmpty(inputs.iterator.filterNot(utxo.contains).toSet)(_.isEmpty)(UtxoFailure.BadInputs(_))

  /** The addresses of `outs` on another network than `network`, a bootstrap address's network taken
    * from its attributes (`getNetwork`).
    */
  private def wrongNetworkAddresses(network: Int, outs: Vector[TxOut]): Set[Bytes] =
    outs
      .filter(_.address.kind match
        case Address.Kind.Shelley(n, _, _, _) => n != network
        case _                                => false)
      .map(_.address.raw)
      .toSet ++ TxChecks.bootstrapOutputs(outs).collect {
      case (out, address) if address.network != network => out.address.raw
    }

  /** `validateWrongNetwork` (`Shelley/Rules/Utxo.hs:469-483`). */
  def wrongNetwork(network: Int, outs: Vector[TxOut]): Test[PredicateFailure] =
    nonEmpty(wrongNetworkAddresses(network, outs))(_.isEmpty)(
      UtxoFailure.WrongNetwork(network, _)
    )

  /** `validateWrongNetworkWithdrawal` (`Shelley/Rules/Utxo.hs:485-498`). */
  def wrongNetworkWithdrawal(network: Int, body: TxBody): Test[PredicateFailure] =
    nonEmpty(body.withdrawals.map(_._1).filter(_.network != network).toSet)(_.isEmpty)(
      UtxoFailure.WrongNetworkWithdrawal(network, _)
    )

  /** `validateValueNotConservedUTxO` (`Shelley/Rules/Utxo.hs:500-515`). */
  def valueNotConserved(consumed: Value, produced: Value): Test[PredicateFailure] =
    unless(consumed == produced, UtxoFailure.ValueNotConserved(Mismatch(consumed, produced)))

  /** The outputs below their minimum ada: by coin in Shelley (`Shelley/Rules/Utxo.hs:517-532`),
    * pointwise below in Allegra and Mary, so an output that carries assets never is
    * (`Allegra/Rules/Utxo.hs:256-270`), pointwise not at least from Alonzo
    * (`Alonzo/Rules/Utxo.hs:382-398`), with the minimum from Babbage
    * (`Babbage/Rules/Utxo.hs:302-321`).
    */
  def outputTooSmall(pp: ProtocolParams, outs: Vector[TxOut]): Test[PredicateFailure] =
    val pv = pp.protocolMajor
    def applies(out: TxOut) = pv <= 2 || pv >= 5 || out.value.assets.isEmpty
    val small = outs.filter(o => applies(o) && o.value.coin < TxChecks.minUTxO(pp, o))
    nonEmpty(small)(_.isEmpty)(s =>
      if pv >= 7 then UtxoFailure.BabbageOutputTooSmall(s.map(o => o -> TxChecks.minUTxO(pp, o)))
      else UtxoFailure.OutputTooSmall(s)
    )

  /** The outputs whose serialised value is over the limit: 4000 bytes in Allegra and Mary, in the
    * order of the outputs (`Allegra/Rules/Utxo.hs:237-254`); `maxValSize` from Alonzo, collected by
    * a left fold that puts each one in front, so the last output first
    * (`Alonzo/Rules/Utxo.hs:400-424`).
    */
  def outputTooBig(pp: ProtocolParams, outs: Vector[TxOut]): Test[PredicateFailure] =
    val big = outs.flatMap { o =>
      TxChecks
        .maxOutputValueSize(pp)
        .filter(max => BigInt(o.value.serializedSize) > max)
        .map(max => (o.value.serializedSize, max.toInt, o))
    }
    nonEmpty(if pp.protocolMajor >= 5 then big.reverse else big)(_.isEmpty)(
      UtxoFailure.OutputTooBig(_)
    )

  /** `validateOutputBootAddrAttrsTooBig` (`Shelley/Rules/Utxo.hs:534-552`). */
  def bootAddrAttrsTooBig(outs: Vector[TxOut]): Test[PredicateFailure] =
    nonEmpty(TxChecks.bootstrapOutputs(outs).collect {
      case (out, address) if address.attributesSize > 64 => out
    })(_.isEmpty)(UtxoFailure.OutputBootAddrAttrsTooBig(_))

  /** `validateMaxTxSizeUTxO` (`Shelley/Rules/Utxo.hs:554-570`). */
  def maxTxSize(pp: ProtocolParams, tx: Tx): Test[PredicateFailure] =
    unless(tx.size <= pp.maxTxSize, UtxoFailure.MaxTxSize(Mismatch(BigInt(tx.size), pp.maxTxSize)))

  /** `validateWrongNetworkInTxBody` (`Alonzo/Rules/Utxo.hs:426-440`). */
  def wrongNetworkInTxBody(network: Int, body: TxBody): Test[PredicateFailure] =
    body.networkId.fold(Test.pass[PredicateFailure])(id =>
      unless(id == network, UtxoFailure.WrongNetworkInTxBody(Mismatch(id.toInt, network)))
    )

  /** `validateExUnitsTooBigUTxO` (`Alonzo/Rules/Utxo.hs:442-460`). */
  def exUnitsTooBig(pp: ProtocolParams, tx: Tx): Test[PredicateFailure] =
    val total = tx.witnesses.totalExUnits
    unless(total.fits(pp.maxTxExUnits), UtxoFailure.ExUnitsTooBig(Mismatch(total, pp.maxTxExUnits)))

  /** `validateTooManyCollateralInputs` (`Alonzo/Rules/Utxo.hs:470-481`), which Babbage's UTXO runs
    * and Alonzo's does not.
    */
  def tooManyCollateralInputs(pp: ProtocolParams, body: TxBody): Test[PredicateFailure] =
    val count = TxChecks.collateralInputs(body).size
    unless(
      count <= pp.maxCollateralInputs,
      UtxoFailure.TooManyCollateralInputs(Mismatch(count, pp.maxCollateralInputs.toInt))
    )

  /** `disjointRefInputs` (`Babbage/Rules/Utxo.hs:194-207`): the inputs that are also reference
    * inputs, refused at protocol versions 9 and 10 only.
    */
  def disjointRefInputs(pp: ProtocolParams, body: TxBody): Test[PredicateFailure] =
    if pp.protocolMajor <= 8 || pp.protocolMajor >= 11 then Test.pass
    else
      nonEmpty(body.inputs.toSet.intersect(body.referenceInputs.toSet).toVector.sorted)(_.isEmpty)(
        UtxoFailure.BabbageNonDisjointRefInputs(_)
      )

  /** The collateral inputs the UTxO set holds, in the order of their set (`Map.restrictKeys utxo
    * collateral`).
    */
  private def utxoCollateral(utxo: Utxo, body: TxBody): Vector[(TxIn, TxOut)] =
    TxChecks.collateralInputs(body).flatMap(in => utxo.get(in).map(in -> _))

  /** `validateScriptsNotPaidUTxO` (`Alonzo/Rules/Utxo.hs:326-332`): collateral locked by a script.
    */
  private def scriptsNotPaid(collateral: Vector[(TxIn, TxOut)]): Test[PredicateFailure] =
    nonEmpty(collateral.filter { case (_, o) =>
      o.address.kind match
        case Address.Kind.Shelley(_, Credential.Script(_), _, _) => true
        case _                                                   => false
    })(_.isEmpty)(c => UtxoFailure.ScriptsNotPaid(c.toMap))

  /** `validateInsufficientCollateral` (`Alonzo/Rules/Utxo.hs:333-347`) of the balance `bal`. */
  private def insufficientCollateral(
      pp: ProtocolParams,
      body: TxBody,
      bal: BigInt
  ): Test[PredicateFailure] =
    unless(
      bal * 100 >= body.fee * pp.collateralPercentage,
      UtxoFailure.InsufficientCollateral(bal, (body.fee * pp.collateralPercentage + 99) / 100)
    )

  private def noCollateralInputs(collateral: Vector[(TxIn, TxOut)]): Test[PredicateFailure] =
    unless(collateral.nonEmpty, UtxoFailure.NoCollateralInputs)

  private def sum(outs: Iterable[TxOut]): Value = outs.foldLeft(Value.zero)(_ + _.value)

  /** `validateCollateral` (`Alonzo/Rules/Utxo.hs:304-324`): `feesOK` part 2 in Alonzo. */
  private def alonzoCollateral(pp: ProtocolParams, utxo: Utxo, body: TxBody) =
    val collateral = utxoCollateral(utxo, body)
    val total = sum(collateral.map(_._2))
    Test.all(
      scriptsNotPaid(collateral),
      insufficientCollateral(pp, body, total.coin),
      unless(total.assets.isEmpty, UtxoFailure.CollateralContainsNonAda(total)),
      noCollateralInputs(collateral)
    )

  /** `validateTotalCollateral` (`Babbage/Rules/Utxo.hs:209-240`): `feesOK` part 2 from Babbage, the
    * collateral return taken off the balance (`collAdaBalance`), the non-ada value reported as
    * `validateCollateralContainsNonADA` does (`Babbage/Rules/Utxo.hs:252-296`).
    */
  private def babbageCollateral(pp: ProtocolParams, utxo: Utxo, body: TxBody) =
    val collateral = utxoCollateral(utxo, body)
    val collateralBalance = sum(collateral.map(_._2))
    val returned = body.collateralReturn
    val utxoAdaOnly = collateral.forall(_._2.value.assets.isEmpty)
    val total = returned.fold(collateralBalance)(r => collateralBalance - r.value)
    val adaOnly =
      (utxoAdaOnly && returned.forall(_.value.assets.isEmpty)) || total.assets.isEmpty
    val withNonAda = returned match
      case Some(r) if utxoAdaOnly => r.value
      case _                      => collateralBalance
    val bal = collateralBalance.coin - returned.fold(BigInt(0))(_.value.coin)
    Test.all(
      scriptsNotPaid(collateral),
      unless(adaOnly, UtxoFailure.CollateralContainsNonAda(withNonAda)),
      insufficientCollateral(pp, body, bal),
      body.totalCollateral.fold(Test.pass[PredicateFailure])(declared =>
        unless(bal == declared, UtxoFailure.IncorrectTotalCollateralField(bal, declared))
      ),
      noCollateralInputs(collateral)
    )

  /** `feesOK` (`Alonzo/Rules/Utxo.hs:278-302`, `Babbage/Rules/Utxo.hs:170-192`): the fee, and the
    * collateral of a transaction with redeemers.
    */
  def feesOK(pp: ProtocolParams, utxo: Utxo, tx: Tx, minFee: BigInt): Test[PredicateFailure] =
    val body = tx.body
    Test.all(
      feeTooSmall(body, minFee),
      (if tx.witnesses.redeemers.isEmpty then Test.pass[PredicateFailure]
       else if pp.protocolMajor >= 7 then babbageCollateral(pp, utxo, body)
       else alonzoCollateral(pp, utxo, body))
    )

  /** What UTXO checks of a transaction of Shelley, Allegra or Mary before PPUP
    * (`Shelley/Rules/Utxo.hs:366-388`, `Allegra/Rules/Utxo.hs:176-197`).
    */
  def shelleyBeforePpup(
      pp: ProtocolParams,
      network: Int,
      slot: BigInt,
      ann: StAnnTx,
      minFee: BigInt,
      consumed: Value,
      produced: Value
  ): Test[PredicateFailure] =
    val body = ann.tx.body
    Test.all(
      (if pp.protocolMajor <= 2 then timeToLive(body, slot) else validityInterval(body, slot)),
      inputSetEmpty(body),
      feeTooSmall(body, minFee),
      badInputs(ann.utxo, body.inputs),
      wrongNetwork(network, body.outputs),
      wrongNetworkWithdrawal(network, body),
      valueNotConserved(consumed, produced)
    )

  /** What UTXO checks of a transaction of Shelley, Allegra or Mary after PPUP
    * (`Shelley/Rules/Utxo.hs:393-403`, `Allegra/Rules/Utxo.hs:202-216`).
    */
  def shelleyAfterPpup(pp: ProtocolParams, tx: Tx): Test[PredicateFailure] =
    val outs = tx.body.outputs
    Test.all(
      outputTooSmall(pp, outs),
      (if pp.protocolMajor >= 3 then outputTooBig(pp, outs) else Test.pass[PredicateFailure]),
      bootAddrAttrsTooBig(outs),
      maxTxSize(pp, tx)
    )

  /** What Alonzo's UTXO checks of a transaction before UTXOS (`Alonzo/Rules/Utxo.hs:505-568`); the
    * checks under `runTestOnSignal` run only when `env` runs the static ones ([[TxEnv.static]]).
    */
  def alonzo(
      pp: ProtocolParams,
      env: TxEnv,
      ann: StAnnTx,
      minFee: BigInt,
      consumed: Value,
      produced: Value
  ): Test[PredicateFailure] =
    val tx = ann.tx
    val body = tx.body
    val outs = body.outputs
    val network = env.network
    Test.all(
      validityInterval(body, env.slot),
      env.static(inputSetEmpty(body)),
      feesOK(pp, ann.utxo, tx, minFee),
      badInputs(ann.utxo, body.inputs ++ body.collateral),
      valueNotConserved(consumed, produced),
      outputTooSmall(pp, outs),
      outputTooBig(pp, outs),
      env.static(bootAddrAttrsTooBig(outs)),
      env.static(wrongNetwork(network, outs)),
      env.static(wrongNetworkWithdrawal(network, body)),
      env.static(wrongNetworkInTxBody(network, body)),
      env.static(maxTxSize(pp, tx)),
      exUnitsTooBig(pp, tx)
    )

  /** `babbageUtxoValidation` (`Babbage/Rules/Utxo.hs:340-412`), which Conway's UTXO runs too
    * (`Conway/Rules/Utxo.hs:236`). The outputs are the outputs and the collateral return
    * (`allSizedOutputsTxBodyF`); the inputs checked against the UTxO set are the spent, collateral
    * and reference inputs (`allInputsTxBodyF`). The checks under `runTestOnSignal` (lines 368-406)
    * run only when `env` runs the static ones ([[TxEnv.static]]).
    */
  def babbage(
      pp: ProtocolParams,
      env: TxEnv,
      ann: StAnnTx,
      minFee: BigInt,
      consumed: Value,
      produced: Value
  ): Test[PredicateFailure] =
    val tx = ann.tx
    val body = tx.body
    val outs = body.outputs ++ body.collateralReturn.toVector
    val network = env.network
    Test.all(
      disjointRefInputs(pp, body),
      validityInterval(body, env.slot),
      env.static(inputSetEmpty(body)),
      feesOK(pp, ann.utxo, tx, minFee),
      badInputs(ann.utxo, body.inputs ++ body.collateral ++ body.referenceInputs),
      valueNotConserved(consumed, produced),
      outputTooSmall(pp, outs),
      outputTooBig(pp, outs),
      env.static(bootAddrAttrsTooBig(outs)),
      env.static(wrongNetwork(network, outs)),
      env.static(wrongNetworkWithdrawal(network, body)),
      env.static(wrongNetworkInTxBody(network, body)),
      env.static(maxTxSize(pp, tx)),
      exUnitsTooBig(pp, tx),
      tooManyCollateralInputs(pp, body)
    )
