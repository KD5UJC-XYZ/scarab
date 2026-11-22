// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.Monad
import cats.data.{Chain, NonEmptyList, NonEmptyMap, NonEmptySet}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{Address, Horizon, TxEnv, TxIn}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraUtxoFailure
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.Relation.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraUTxO, PlutusLegacyMode}
import xyz.kd5ujc.scarab.ledger.rules.{Judge, Sts, Test}
import xyz.kd5ujc.scarab.ledger.state.{CertState, Coin, Dijkstra, EraTag, PState, UTxOState}
import xyz.kd5ujc.scarab.plutus.{ExUnits, ScriptEvaluator}

// UTXO of Dijkstra as the reference writes it (cardano-ledger 9df9f98c,
// `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Utxo.hs`): its failures, its environment,
// its own predicates and its transition, which runs Conway's UTXOS. The predicates it shares with
// the earlier eras are theirs (`Utxo.scala`), each declaring the constructors it reports, so that
// Dijkstra's injections of them, which the reference leaves partial (`error "Impossible"` on
// `WrongNetworkWithdrawal` and `OutputTooSmallUTxO`, `Utxo.hs:572-573`), are total on what its UTXO
// reaches ([[DijkstraUtxo.ShelleyFailure]] and the others).

/** `data DijkstraUtxoPredFailure era` (`Dijkstra/Rules/Utxo.hs:110-171`, cardano-ledger 9df9f98c),
  * its constructors in the reference's order; their tags are `:496-520`, 8 unused.
  */
enum DijkstraUtxoPredFailure[E <: EraTag]:
  case UtxosFailure(failure: UtxosPredFailure[E])
  case BadInputsUTxO(inputs: NonEmptySet[TxIn])
  case OutsideValidityIntervalUTxO(interval: ValidityInterval, slot: BigInt)
  case MaxTxSizeUTxO(mismatch: Mismatch[RelLTEQ, BigInt])
  case InputSetEmptyUTxO()
  case FeeTooSmallUTxO(mismatch: Mismatch[RelGTEQ, Coin])

  /** What the batch consumes (supplied) and what it produces (expected). */
  case ValueNotConservedUTxO(mismatch: Mismatch[RelEQ, Value[E]])
  case WrongNetwork(expected: Network, addresses: NonEmptySet[Address])
  case OutputBootAddrAttrsTooBig(outputs: NonEmptyList[TxOut[E]])
  case OutputTooBigUTxO(outputs: NonEmptyList[(Int, Int, TxOut[E])])
  case InsufficientCollateral(balance: BigInt, required: Coin)
  case ScriptsNotPaidUTxO(outputs: NonEmptyMap[TxIn, TxOut[E]])
  case ExUnitsTooBigUTxO(mismatch: Mismatch[RelLTEQ, ExUnits])
  case CollateralContainsNonADA(value: Value[E])
  case WrongNetworkInTxBody(mismatch: Mismatch[RelEQ, Network])
  case OutsideForecast(slot: BigInt)
  case TooManyCollateralInputs(mismatch: Mismatch[RelLTEQ, Int])
  case NoCollateralInputs()
  case IncorrectTotalCollateralField(provided: BigInt, declared: Coin)
  case BabbageOutputTooSmallUTxO(outputs: NonEmptyList[(TxOut[E], Coin)])
  case BabbageNonDisjointRefInputs(inputs: NonEmptyList[TxIn])
  case PtrPresentInCollateralReturn(output: TxOut[E])

  /** What the top level consumes and produces in the legacy mode, its sub-transactions taken out.
    */
  case ValueNotConservedInLegacyMode(mismatch: Mismatch[RelEQ, Value[E]])

object DijkstraUtxoPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraUtxoPredFailure[E], DijkstraUtxoPredFailure[E]] =
    CanEqual.derived

  /** The failure in the node's flat form (`DijkstraUtxoFailure`), which the flat Dijkstra rules
    * report: glue until the era failures have their own encoders (#85), one-way, as
    * [[ConwayUtxoPredFailure.toStored]] is. UTXOS's `CollectErrors` are returned as they are.
    */
  def toStored(
      failure: DijkstraUtxoPredFailure[Dijkstra]
  ): Either[NonEmptyList[CollectError[Dijkstra]], DijkstraUtxoFailure] =
    val s = StoredTxOut.dijkstra
    def outs(os: NonEmptyList[TxOut[Dijkstra]]) = os.toList.toVector.map(s.toStored)
    def ok(f: DijkstraUtxoFailure) = Right(f)
    failure match
      case UtxosFailure(f) =>
        ConwayUtxoPredFailure.storedUtxos(f).map(DijkstraUtxoFailure.UtxosFailure(_))
      case BadInputsUTxO(ins) => ok(DijkstraUtxoFailure.BadInputs(ins.toSortedSet))
      case OutsideValidityIntervalUTxO(vi, slot) =>
        ok(DijkstraUtxoFailure.OutsideValidityInterval(vi.invalidBefore, vi.invalidHereafter, slot))
      case MaxTxSizeUTxO(m)    => ok(DijkstraUtxoFailure.MaxTxSize(m.stored))
      case InputSetEmptyUTxO() => ok(DijkstraUtxoFailure.InputSetEmpty)
      case FeeTooSmallUTxO(m) =>
        ok(DijkstraUtxoFailure.FeeTooSmall(m.storedBy(_.value)))
      case ValueNotConservedUTxO(m) =>
        ok(
          DijkstraUtxoFailure.ValueNotConserved(
            m.storedBy(s.storedValue)
          )
        )
      case WrongNetwork(n, addrs) =>
        ok(DijkstraUtxoFailure.WrongNetwork(n.ordinal, addrs.toSortedSet.map(_.raw)))
      case OutputBootAddrAttrsTooBig(os) =>
        ok(DijkstraUtxoFailure.OutputBootAddrAttrsTooBig(outs(os)))
      case OutputTooBigUTxO(os) =>
        ok(
          DijkstraUtxoFailure.OutputTooBig(
            os.toList.toVector.map((a, b, o) => (a, b, s.toStored(o)))
          )
        )
      case InsufficientCollateral(bal, required) =>
        ok(DijkstraUtxoFailure.InsufficientCollateral(bal, required.value))
      case ScriptsNotPaidUTxO(os) =>
        ok(DijkstraUtxoFailure.ScriptsNotPaid(os.toSortedMap.view.mapValues(s.toStored).toMap))
      case ExUnitsTooBigUTxO(m) => ok(DijkstraUtxoFailure.ExUnitsTooBig(m.stored))
      case CollateralContainsNonADA(v) =>
        ok(DijkstraUtxoFailure.CollateralContainsNonAda(s.storedValue(v)))
      case WrongNetworkInTxBody(m) =>
        ok(
          DijkstraUtxoFailure.WrongNetworkInTxBody(m.storedBy(_.ordinal))
        )
      case OutsideForecast(slot)      => ok(DijkstraUtxoFailure.OutsideForecast(slot))
      case TooManyCollateralInputs(m) => ok(DijkstraUtxoFailure.TooManyCollateralInputs(m.stored))
      case NoCollateralInputs()       => ok(DijkstraUtxoFailure.NoCollateralInputs)
      case IncorrectTotalCollateralField(provided, declared) =>
        ok(DijkstraUtxoFailure.IncorrectTotalCollateralField(provided, declared.value))
      case BabbageOutputTooSmallUTxO(os) =>
        ok(
          DijkstraUtxoFailure.BabbageOutputTooSmall(
            os.toList.toVector.map((o, c) => (s.toStored(o), c.value))
          )
        )
      case BabbageNonDisjointRefInputs(ins) =>
        ok(DijkstraUtxoFailure.BabbageNonDisjointRefInputs(ins.toList.toVector))
      case PtrPresentInCollateralReturn(o) =>
        ok(DijkstraUtxoFailure.PtrPresentInCollateralReturn(s.toStored(o)))
      case ValueNotConservedInLegacyMode(m) =>
        ok(
          DijkstraUtxoFailure.ValueNotConservedInLegacyMode(
            m.storedBy(s.storedValue)
          )
        )

/** `UtxoEnv` of Dijkstra (`Dijkstra/Rules/Utxo.hs:101-107`, cardano-ledger 9df9f98c): the slot, the
  * parameters, the pool state after the sub-transactions, and the certificate state and the UTxO
  * set before the transaction, which LEDGER gives UTXOW and UTXOW passes on (`Ledger.hs:475-486`).
  * The UTxO set holds at least the outputs of the batch's inputs, reference inputs and collateral
  * inputs (`allInputsTxBodyF`), every output a lookup of UTXO and UTXOW reads.
  */
final case class DijkstraUtxoEnv[E <: EraTag](
    slot: BigInt,
    pp: PParams[E],
    pState: PState,
    originalCertState: CertState[E],
    originalUtxo: UTxO[E]
)

/** What Dijkstra's UTXO reads besides its environment, as [[UtxoContext]] gathers it for Conway's:
  * the network, the system start and the era history it asks of `Globals`, and the validation
  * policy. Dijkstra exists in one build, the Leios build (cardano-ledger 9df9f98c), so its
  * injection of Shelley's failures is a given, not a value of the context.
  */
final case class DijkstraUtxoContext(
    netId: Network,
    systemStart: BigInt,
    epochInfo: Horizon,
    validation: TxEnv.Validation
):
  /** `runTestOnSignal` and the static checks (`Rules/ValidationMode.hs`, cardano-ledger 9df9f98c):
    * `test` when the policy validates the signal, a pass otherwise.
    */
  def onSignal[A](test: => Test[A]): Test[A] = validation match
    case TxEnv.Validation.All       => test
    case TxEnv.Validation.NonStatic => Test.pass

/** Dijkstra's functions of UTXO (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Utxo.hs`,
  * cardano-ledger 9df9f98c).
  */
object DijkstraUtxo:
  /** The failures of Shelley's type that Dijkstra's UTXO reports, each from the predicate that
    * declares it: the domain of its injection of Shelley's failures, which is total on them.
    */
  type ShelleyFailure[E <: EraTag] = ShelleyUtxoPredFailure.BadInputsUTxO[E] |
    ShelleyUtxoPredFailure.InputSetEmptyUTxO[E] | ShelleyUtxoPredFailure.FeeTooSmallUTxO[E] |
    ShelleyUtxoPredFailure.ValueNotConservedUTxO[E] | ShelleyUtxoPredFailure.WrongNetwork[E] |
    ShelleyUtxoPredFailure.OutputBootAddrAttrsTooBig[E] | ShelleyUtxoPredFailure.MaxTxSizeUTxO[E]

  /** The failure of Allegra's type that Dijkstra's UTXO reports. */
  type AllegraFailure[E <: EraTag] = AllegraUtxoPredFailure.OutsideValidityIntervalUTxO[E]

  /** The failures of Alonzo's type that Dijkstra's UTXO reports, the collateral's among them. */
  type AlonzoFailure[E <: EraTag] = AlonzoUtxoPredFailure.OutsideForecast[E] |
    AlonzoUtxoPredFailure.OutputTooBigUTxO[E] | AlonzoUtxoPredFailure.WrongNetworkInTxBody[E] |
    AlonzoUtxoPredFailure.ExUnitsTooBigUTxO[E] | AlonzoUtxoPredFailure.TooManyCollateralInputs[E] |
    CollateralFailure[E]

  /** The failures of Babbage's type that Dijkstra's UTXO reports. */
  type BabbageFailure[E <: EraTag] = BabbageUtxoPredFailure.IncorrectTotalCollateralField[E] |
    BabbageUtxoPredFailure.BabbageOutputTooSmallUTxO[E]

  /** Every constructor of Conway's failure type but the two the reference's
    * `conwayToDijkstraUtxoPredFailure` calls impossible (`WrongNetworkWithdrawal`, which is
    * ENTITIES', and `OutputTooSmallUTxO`, `Dijkstra/Rules/Utxo.hs:572-573`, cardano-ledger
    * 9df9f98c): its domain here.
    */
  type ConwayFailure[E <: EraTag] = ConwayUtxoPredFailure.UtxosFailure[E] |
    ConwayUtxoPredFailure.BadInputsUTxO[E] | ConwayUtxoPredFailure.OutsideValidityIntervalUTxO[E] |
    ConwayUtxoPredFailure.MaxTxSizeUTxO[E] | ConwayUtxoPredFailure.InputSetEmptyUTxO[E] |
    ConwayUtxoPredFailure.FeeTooSmallUTxO[E] | ConwayUtxoPredFailure.ValueNotConservedUTxO[E] |
    ConwayUtxoPredFailure.WrongNetwork[E] | ConwayUtxoPredFailure.OutputBootAddrAttrsTooBig[E] |
    ConwayUtxoPredFailure.OutputTooBigUTxO[E] | ConwayUtxoPredFailure.InsufficientCollateral[E] |
    ConwayUtxoPredFailure.ScriptsNotPaidUTxO[E] | ConwayUtxoPredFailure.ExUnitsTooBigUTxO[E] |
    ConwayUtxoPredFailure.CollateralContainsNonADA[E] |
    ConwayUtxoPredFailure.WrongNetworkInTxBody[E] | ConwayUtxoPredFailure.OutsideForecast[E] |
    ConwayUtxoPredFailure.TooManyCollateralInputs[E] | ConwayUtxoPredFailure.NoCollateralInputs[E] |
    ConwayUtxoPredFailure.IncorrectTotalCollateralField[E] |
    ConwayUtxoPredFailure.BabbageOutputTooSmallUTxO[E] |
    ConwayUtxoPredFailure.BabbageNonDisjointRefInputs[E]

  /** `conwayToDijkstraUtxoPredFailure` (`Dijkstra/Rules/Utxo.hs:560-587`, cardano-ledger 9df9f98c):
    * each failure as Dijkstra's own, on the constructors the reference maps ([[ConwayFailure]]).
    */
  def conwayToDijkstraUtxoPredFailure[E <: EraTag](
      failure: ConwayFailure[E]
  ): DijkstraUtxoPredFailure[E] = failure match
    case ConwayUtxoPredFailure.BadInputsUTxO(x) => DijkstraUtxoPredFailure.BadInputsUTxO(x)
    case ConwayUtxoPredFailure.OutsideValidityIntervalUTxO(vi, slotNo) =>
      DijkstraUtxoPredFailure.OutsideValidityIntervalUTxO(vi, slotNo)
    case ConwayUtxoPredFailure.MaxTxSizeUTxO(m)    => DijkstraUtxoPredFailure.MaxTxSizeUTxO(m)
    case ConwayUtxoPredFailure.InputSetEmptyUTxO() => DijkstraUtxoPredFailure.InputSetEmptyUTxO()
    case ConwayUtxoPredFailure.FeeTooSmallUTxO(m)  => DijkstraUtxoPredFailure.FeeTooSmallUTxO(m)
    case ConwayUtxoPredFailure.ValueNotConservedUTxO(m) =>
      DijkstraUtxoPredFailure.ValueNotConservedUTxO(m)
    case ConwayUtxoPredFailure.WrongNetwork(x, y) => DijkstraUtxoPredFailure.WrongNetwork(x, y)
    case ConwayUtxoPredFailure.UtxosFailure(x)    => DijkstraUtxoPredFailure.UtxosFailure(x)
    case ConwayUtxoPredFailure.OutputBootAddrAttrsTooBig(xs) =>
      DijkstraUtxoPredFailure.OutputBootAddrAttrsTooBig(xs)
    case ConwayUtxoPredFailure.OutputTooBigUTxO(xs) => DijkstraUtxoPredFailure.OutputTooBigUTxO(xs)
    case ConwayUtxoPredFailure.InsufficientCollateral(c1, c2) =>
      DijkstraUtxoPredFailure.InsufficientCollateral(c1, c2)
    case ConwayUtxoPredFailure.ScriptsNotPaidUTxO(u) =>
      DijkstraUtxoPredFailure.ScriptsNotPaidUTxO(u)
    case ConwayUtxoPredFailure.ExUnitsTooBigUTxO(m) => DijkstraUtxoPredFailure.ExUnitsTooBigUTxO(m)
    case ConwayUtxoPredFailure.CollateralContainsNonADA(v) =>
      DijkstraUtxoPredFailure.CollateralContainsNonADA(v)
    case ConwayUtxoPredFailure.WrongNetworkInTxBody(m) =>
      DijkstraUtxoPredFailure.WrongNetworkInTxBody(m)
    case ConwayUtxoPredFailure.OutsideForecast(sno) => DijkstraUtxoPredFailure.OutsideForecast(sno)
    case ConwayUtxoPredFailure.TooManyCollateralInputs(m) =>
      DijkstraUtxoPredFailure.TooManyCollateralInputs(m)
    case ConwayUtxoPredFailure.NoCollateralInputs() => DijkstraUtxoPredFailure.NoCollateralInputs()
    case ConwayUtxoPredFailure.IncorrectTotalCollateralField(dc, c) =>
      DijkstraUtxoPredFailure.IncorrectTotalCollateralField(dc, c)
    case ConwayUtxoPredFailure.BabbageOutputTooSmallUTxO(x) =>
      DijkstraUtxoPredFailure.BabbageOutputTooSmallUTxO(x)
    case ConwayUtxoPredFailure.BabbageNonDisjointRefInputs(txin) =>
      DijkstraUtxoPredFailure.BabbageNonDisjointRefInputs(txin)

  /** `injectFailure` of `InjectRuleFailure "UTXO" ShelleyUtxoPredFailure DijkstraEra`
    * (`Dijkstra/Rules/Utxo.hs:188-192`, cardano-ledger 9df9f98c): `conwayToDijkstraUtxoPredFailure
    * . allegraToConwayUtxoPredFailure . shelleyToAllegraUtxoPredFailure`, Allegra's injection of
    * 9df9f98c (`Allegra/Rules/Utxo.hs:381-394`, the bootstrap attributes' failure kept), on the
    * constructors Dijkstra's UTXO reports. Each passes the three injections unchanged.
    */
  def shelleyToDijkstraUtxoPredFailure[E <: EraTag](
      failure: ShelleyFailure[E]
  ): DijkstraUtxoPredFailure[E] = failure match
    case ShelleyUtxoPredFailure.BadInputsUTxO(ins)  => DijkstraUtxoPredFailure.BadInputsUTxO(ins)
    case ShelleyUtxoPredFailure.InputSetEmptyUTxO() => DijkstraUtxoPredFailure.InputSetEmptyUTxO()
    case ShelleyUtxoPredFailure.FeeTooSmallUTxO(m)  => DijkstraUtxoPredFailure.FeeTooSmallUTxO(m)
    case ShelleyUtxoPredFailure.ValueNotConservedUTxO(m) =>
      DijkstraUtxoPredFailure.ValueNotConservedUTxO(m)
    case ShelleyUtxoPredFailure.WrongNetwork(n, as) => DijkstraUtxoPredFailure.WrongNetwork(n, as)
    case ShelleyUtxoPredFailure.OutputBootAddrAttrsTooBig(outs) =>
      DijkstraUtxoPredFailure.OutputBootAddrAttrsTooBig(outs)
    case ShelleyUtxoPredFailure.MaxTxSizeUTxO(m) => DijkstraUtxoPredFailure.MaxTxSizeUTxO(m)

  /** `injectFailure` of `InjectRuleFailure "UTXO" AllegraUtxoPredFailure DijkstraEra`
    * (`Dijkstra/Rules/Utxo.hs:194-195`, cardano-ledger 9df9f98c): `conwayToDijkstraUtxoPredFailure
    * . allegraToConwayUtxoPredFailure`, on the constructor Dijkstra's UTXO reports.
    */
  def allegraToDijkstraUtxoPredFailure[E <: EraTag](
      failure: AllegraFailure[E]
  ): DijkstraUtxoPredFailure[E] = failure match
    case AllegraUtxoPredFailure.OutsideValidityIntervalUTxO(vi, slotNo) =>
      DijkstraUtxoPredFailure.OutsideValidityIntervalUTxO(vi, slotNo)

  /** `injectFailure` of `InjectRuleFailure "UTXO" AlonzoUtxoPredFailure DijkstraEra`
    * (`Dijkstra/Rules/Utxo.hs:185-186`, cardano-ledger 9df9f98c): `conwayToDijkstraUtxoPredFailure
    * . alonzoToConwayUtxoPredFailure`, on the constructors Dijkstra's UTXO reports.
    */
  def alonzoToDijkstraUtxoPredFailure[E <: EraTag](
      failure: AlonzoFailure[E]
  ): DijkstraUtxoPredFailure[E] = failure match
    case AlonzoUtxoPredFailure.OutsideForecast(sno) => DijkstraUtxoPredFailure.OutsideForecast(sno)
    case AlonzoUtxoPredFailure.OutputTooBigUTxO(xs) => DijkstraUtxoPredFailure.OutputTooBigUTxO(xs)
    case AlonzoUtxoPredFailure.WrongNetworkInTxBody(m) =>
      DijkstraUtxoPredFailure.WrongNetworkInTxBody(m)
    case AlonzoUtxoPredFailure.ExUnitsTooBigUTxO(m) => DijkstraUtxoPredFailure.ExUnitsTooBigUTxO(m)
    case AlonzoUtxoPredFailure.TooManyCollateralInputs(m) =>
      DijkstraUtxoPredFailure.TooManyCollateralInputs(m)
    case AlonzoUtxoPredFailure.ScriptsNotPaidUTxO(u) =>
      DijkstraUtxoPredFailure.ScriptsNotPaidUTxO(u)
    case AlonzoUtxoPredFailure.CollateralContainsNonADA(v) =>
      DijkstraUtxoPredFailure.CollateralContainsNonADA(v)
    case AlonzoUtxoPredFailure.InsufficientCollateral(c1, c2) =>
      DijkstraUtxoPredFailure.InsufficientCollateral(c1, c2)
    case AlonzoUtxoPredFailure.NoCollateralInputs() => DijkstraUtxoPredFailure.NoCollateralInputs()

  /** `injectFailure` of `InjectRuleFailure "UTXO" BabbageUtxoPredFailure DijkstraEra`
    * (`Dijkstra/Rules/Utxo.hs:182-183`, cardano-ledger 9df9f98c): `conwayToDijkstraUtxoPredFailure
    * . babbageToConwayUtxoPredFailure`, on the constructors Dijkstra's UTXO reports.
    */
  def babbageToDijkstraUtxoPredFailure[E <: EraTag](
      failure: BabbageFailure[E]
  ): DijkstraUtxoPredFailure[E] = failure match
    case BabbageUtxoPredFailure.IncorrectTotalCollateralField(c1, c2) =>
      DijkstraUtxoPredFailure.IncorrectTotalCollateralField(c1, c2)
    case BabbageUtxoPredFailure.BabbageOutputTooSmallUTxO(ts) =>
      DijkstraUtxoPredFailure.BabbageOutputTooSmallUTxO(ts)

  private val t = EraTx.dijkstra
  private val b = EraTxBody.dijkstra
  private val u = EraUTxO.dijkstra
  private given BothLevels[TxLevel.Top] = BothLevels.Top

  /** `validateNoPtrInCollateralReturn` (`Dijkstra/Rules/Utxo.hs:245-256`, cardano-ledger 9df9f98c):
    * a collateral return to a pointer address (`StakeRefPtr`).
    */
  def validateNoPtrInCollateralReturn[E <: EraTag](txBody: TxBody[TxLevel.Top, E])(using
      body: BabbageEraTxBody[E]
  ): Test[DijkstraUtxoPredFailure.PtrPresentInCollateralReturn[E]] =
    val hasCollateralTxOut = body
      .collateralReturn(txBody)
      .filter(out =>
        body.outputsOf.address(out).kind match
          case Address.Kind.Shelley(_, _, _, Some(_)) => true
          case Address.Kind.Shelley(_, _, _, None)    => false
          case Address.Kind.Byron(_)                  => false
      )
    Test.failureOnJust(hasCollateralTxOut)(
      new DijkstraUtxoPredFailure.PtrPresentInCollateralReturn[E](_)
    )

  /** `validateBatchCollateral` (`Dijkstra/Rules/Utxo.hs:259-276`, cardano-ledger 9df9f98c):
    * Babbage's collateral checks (`validateTotalCollateral`) if the top level or any
    * sub-transaction has redeemers, on the outputs of the collateral inputs the UTxO set holds.
    */
  def validateBatchCollateral(
      pp: PParams[Dijkstra],
      tx: Tx[TxLevel.Top, Dijkstra],
      utxo: UTxO[Dijkstra]
  ): Test[UtxoPredFailure[Dijkstra]] =
    val txBody = t.body[TxLevel.Top](tx)
    val collateral = b.collateralInputs(txBody)
    val utxoCollateral = utxo.unUTxO.filter((in, _) => collateral.contains(in))
    def hasRedeemers[L <: TxLevel](x: Tx[L, Dijkstra])(using BothLevels[L]) =
      t.witsOf.rdmrsTxWits(t.wits[L](x)).entries.nonEmpty
    val hasAnyRedeemers = hasRedeemers[TxLevel.Top](tx) || b
      .subTransactions(txBody)
      .toVector
      .exists(sub => hasRedeemers[TxLevel.Sub](sub)(using BothLevels.Sub))
    if hasAnyRedeemers then
      BabbageUtxo.validateTotalCollateral[Dijkstra](pp, txBody, utxoCollateral)
    else Test.pass

  /** `validateValueNotConservedUTxO` (`Dijkstra/Rules/Utxo.hs:284-298`, cardano-ledger 9df9f98c):
    * what the batch consumes (`dijkstraConsumed`) equal to what it produces (`produced`, the pools
    * registered found in `pState`), the failure with the consumed value first.
    */
  def validateValueNotConservedUTxO(
      pp: PParams[Dijkstra],
      utxo: UTxO[Dijkstra],
      pState: PState,
      txBody: TxBody[TxLevel.Top, Dijkstra]
  ): Test[Mismatch[RelEQ, Value[Dijkstra]]] =
    valueConserved(
      DijkstraUTxO.dijkstraConsumed[TxLevel.Top](pp, utxo, txBody),
      ShelleyUTxO.produced(pp, pState, txBody)(using u)
    )

  /** `validateValueNotConservedUTxO` of the body with its sub-transactions taken out (`txBody &
    * subTransactionsTxBodyL .~ mempty`, `Dijkstra/Rules/Utxo.hs:380-387`, cardano-ledger 9df9f98c),
    * as the legacy mode checks it: the consumed and produced values of the top level alone
    * ([[DijkstraUTxO.dijkstraConsumedWithoutSubTransactions]],
    * [[DijkstraUTxO.dijkstraProducedValueWithoutSubTransactions]]), which read no id and no bytes,
    * so the body is not made again.
    */
  def validateValueNotConservedWithoutSubTransactions(
      pp: PParams[Dijkstra],
      utxo: UTxO[Dijkstra],
      pState: PState,
      txBody: TxBody[TxLevel.Top, Dijkstra]
  ): Test[Mismatch[RelEQ, Value[Dijkstra]]] =
    valueConserved(
      DijkstraUTxO.dijkstraConsumedWithoutSubTransactions(pp, utxo, txBody),
      DijkstraUTxO.dijkstraProducedValueWithoutSubTransactions(
        pp,
        pState.stakePools.contains,
        txBody
      )
    )

  private def valueConserved(
      consumedValue: Value[Dijkstra],
      producedValue: Value[Dijkstra]
  ): Test[Mismatch[RelEQ, Value[Dijkstra]]] =
    Test.failureUnless(
      b.outputsOf.valueOps.eqv(consumedValue, producedValue),
      Mismatch(consumedValue, producedValue)
    )

  /** The predicates of `dijkstraUtxoTransition` (`Dijkstra/Rules/Utxo.hs:326-418`, cardano-ledger
    * 9df9f98c) in its order, every failure accumulated and injected into Dijkstra's: the validity
    * interval and the forecast; an empty input set (on the signal); the batch's inputs, reference
    * inputs and collateral inputs against the UTxO set before it, then the top level's inputs
    * against `current`, the threaded state's (a sub-transaction may have spent one); the fee
    * against the set before; the batch's collateral; for a transaction flagged valid only, the
    * value conserved, and in the legacy mode the top level's alone against the pool state after the
    * sub-transactions; the outputs and the collateral return (their least lovelace and size, then
    * on the signal their bootstrap attributes and network); the body's network (on the signal); no
    * pointer in the collateral return (static); the size; the execution units; the number of
    * collateral inputs.
    */
  def dijkstraUtxoValidation(
      env: DijkstraUtxoEnv[Dijkstra],
      current: UTxO[Dijkstra],
      stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
      context: DijkstraUtxoContext
  ): Test[UtxoPredFailure[Dijkstra]] =
    given EraTxOut[Dijkstra] = b.outputsOf
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)
    val txBody = t.body[TxLevel.Top](tx)
    val pp = env.pp
    val originalPState = EraCertState.dijkstra.certPStateL.get(env.originalCertState)
    val allSizedOutputs = b.allSizedOutputs[TxLevel.Top](txBody)
    val allOutputs = allSizedOutputs.map(_.value)
    val conserved = t.isValid(tx) match
      case IsPhase2Valid.Phase2Valid =>
        Test.all(
          injected(
            validateValueNotConservedUTxO(pp, env.originalUtxo, originalPState, txBody).leftMap(
              _.map(new ShelleyUtxoPredFailure.ValueNotConservedUTxO[Dijkstra](_))
            )
          ),
          u.plutusLegacyModeStAnnTx(stAnnTx) match
            case PlutusLegacyMode.Legacy =>
              validateValueNotConservedWithoutSubTransactions(
                pp,
                env.originalUtxo,
                env.pState,
                txBody
              ).leftMap(_.map(DijkstraUtxoPredFailure.ValueNotConservedInLegacyMode[Dijkstra](_)))
            case PlutusLegacyMode.NotLegacy => Test.pass
        )
      case IsPhase2Valid.Phase2Invalid => Test.pass
    Test.all(
      injected(
        AllegraUtxo.validateOutsideValidityIntervalUTxO[Dijkstra, TxLevel.Top](env.slot, txBody)
      ),
      injected(
        AlonzoUtxo.validateOutsideForecast[Dijkstra, TxLevel.Top](
          context.epochInfo,
          env.slot,
          context.systemStart,
          tx
        )
      ),
      context.onSignal(
        injected(ShelleyUtxo.validateInputSetEmptyUTxO[Dijkstra, TxLevel.Top](txBody))
      ),
      injected(ShelleyUtxo.validateBadInputsUTxO[Dijkstra](env.originalUtxo, b.allInputs(txBody))),
      injected(ShelleyUtxo.validateBadInputsUTxO[Dijkstra](current, b.inputs[TxLevel.Top](txBody))),
      injected(ShelleyUtxo.validateFeeTooSmallUTxO[Dijkstra](pp, tx, env.originalUtxo)(using u)),
      validateBatchCollateral(pp, tx, env.originalUtxo),
      conserved,
      injected(BabbageUtxo.validateOutputTooSmallUTxO[Dijkstra](pp, allSizedOutputs)),
      injected(AlonzoUtxo.validateOutputTooBigUTxO[Dijkstra](pp, allOutputs)),
      context.onSignal(
        injected(ShelleyUtxo.validateOutputBootAddrAttrsTooBig[Dijkstra](allOutputs))
      ),
      context.onSignal(
        injected(ShelleyUtxo.validateWrongNetwork[Dijkstra](context.netId, allOutputs))
      ),
      context.onSignal(
        injected(
          AlonzoUtxo.validateWrongNetworkInTxBody[Dijkstra, TxLevel.Top](context.netId, txBody)
        )
      ),
      context.onSignal(injected(validateNoPtrInCollateralReturn[Dijkstra](txBody))),
      injected(ShelleyUtxo.validateMaxTxSizeUTxO[Dijkstra, TxLevel.Top](pp, tx)),
      injected(AlonzoUtxo.validateExUnitsTooBigUTxO[Dijkstra, TxLevel.Top](pp, tx)),
      injected(AlonzoUtxo.validateTooManyCollateralInputs[Dijkstra](pp, txBody))
    )

  /** `dijkstraUtxoTransition` (`Dijkstra/Rules/Utxo.hs:301-425`, cardano-ledger 9df9f98c): UTXO's
    * predicates ([[dijkstraUtxoValidation]]), every failure recorded; then Conway's UTXOS as a
    * sub-rule, its failures and events embedded in UTXO's ([[EmbedUtxosInUtxo]]); then Babbage's
    * `updateUTxOState` from the certificate state before the transaction, after the donation of a
    * valid transaction, from the state the transition was given, whatever was recorded.
    *
    * The threaded state's outputs of the top level's inputs are read from the store into the era
    * for the second check of the inputs; a stored output the era does not read, and a change of the
    * instant stake outside the state's vocabulary, are raised ([[UtxoStateDefect]]), as is a script
    * the evaluator gives no verdict for ([[ScriptVerdictUnavailable]]), as in Conway's.
    */
  def dijkstraUtxoTransition[F[_]: Monad](
      env: DijkstraUtxoEnv[Dijkstra],
      stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
      context: DijkstraUtxoContext
  )(using
      state: Stateful[F, UTxOState[Dijkstra]],
      judge: Judge[F, UtxoPredFailure[Dijkstra]],
      events: Tell[F, Chain[AlonzoUtxoEvent[Dijkstra]]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      defect: Raise[F, UtxoStateDefect]
  ): F[Unit] =
    val embed = EmbedUtxosInUtxo.dijkstra
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)
    val txBody = t.body[TxLevel.Top](tx)
    given Judge[F, UtxosPredFailure[Dijkstra]] = judge.contramap(embed.wrapFailed)
    given Tell[F, Chain[UtxosEvent[Dijkstra]]] = new Tell[F, Chain[UtxosEvent[Dijkstra]]]:
      val functor: cats.Functor[F] = events.functor
      def tell(l: Chain[UtxosEvent[Dijkstra]]): F[Unit] = events.tell(l.map(embed.wrapEvent))
    for
      utxoState <- state.get
      current <- ShelleyUtxo.readStored[F, Dijkstra](
        ShelleyUtxo.extractKeys(utxoState.utxo.utxo, b.inputs[TxLevel.Top](txBody))._2
      )
      _ <- Sts.runTest[F, UtxoPredFailure[Dijkstra]](
        dijkstraUtxoValidation(env, current, stAnnTx, context)
      )
      _ <- judge.nest(ConwayUtxos.utxosTransition[F, Dijkstra](stAnnTx, context.validation))
      next <- BabbageUtxo.updateUTxOState[F, Dijkstra](
        env.pp,
        env.originalCertState,
        tx,
        ConwayUtxo.updateTreasuryDonation(tx, utxoState)
      )
      _ <- state.set(next)
    yield ()
