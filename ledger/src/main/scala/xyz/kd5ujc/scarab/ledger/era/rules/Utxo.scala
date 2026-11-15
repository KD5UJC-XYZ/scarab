// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import xyz.kd5ujc.scarab.ledger.{Address, Mismatch, PredicateFailure, TxIn, UtxoFailure}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.Test
import xyz.kd5ujc.scarab.ledger.state.EraTag

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
