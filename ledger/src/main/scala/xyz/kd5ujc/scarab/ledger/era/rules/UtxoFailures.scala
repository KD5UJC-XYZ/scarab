// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.Order
import cats.data.{NonEmptyList, NonEmptyMap, NonEmptySet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Mismatch,
  PlutusFailure,
  PredicateFailure,
  TxIn,
  UtxoFailure,
  UtxosFailure as FlatUtxosFailure
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{AccountAddress, Coin, Conway, Dijkstra, EraTag}
import xyz.kd5ujc.scarab.plutus.ExUnits

// The failures of UTXO as the reference declares them (cardano-ledger f649f975, the `Rules/Utxo.hs`
// of Shelley, Allegra, Alonzo, Babbage and Conway): each era's own type, parameterised by the era as
// the reference's `data ... era` is, with the injections that carry one era's failures into a later
// era's (`InjectRuleFailure "UTXO"`). A predicate reports the failure type of the era that defines
// it, and the rule that runs it injects it into its own. At cardano-ledger 29a7d656 and 9df9f98c the
// types are the same but for the derived `Ord` instances and Alonzo's and Conway's units, held as
// `OrdExUnits` for that `Ord`; Shelley's injection into Allegra differs ([[AllegraUtxo]]).

/** The order of the sets the failures hold: `TxIn` 's is the reference's derived one, as is
  * `AccountAddress` 's, in its companion. An address is held by the bytes it was read from, whose
  * order is the bytes', where the reference collects decoded addresses (`Set Addr`, equal by value;
  * the Gap of `validateWrongNetwork`, #796).
  */
private[rules] given txInOrder: Order[TxIn] = Order.fromOrdering
private[rules] given addressOrder: Order[Address] =
  Order.by[Address, Bytes](_.raw)(using Order.fromOrdering)

/** `data ShelleyUtxoPredFailure era` (`eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Utxo.hs:
  * 166-189`, cardano-ledger f649f975): the failures Shelley's UTXO predicates report.
  */
enum ShelleyUtxoPredFailure[E <: EraTag]:
  case BadInputsUTxO(inputs: NonEmptySet[TxIn])

  /** The time to live (supplied) and the slot (expected). */
  case ExpiredUTxO(mismatch: Mismatch[BigInt])
  case MaxTxSizeUTxO(mismatch: Mismatch[BigInt])
  case InputSetEmptyUTxO()
  case FeeTooSmallUTxO(mismatch: Mismatch[Coin])

  /** What is consumed (supplied) and what is produced (expected). */
  case ValueNotConservedUTxO(mismatch: Mismatch[Value[E]])
  case WrongNetwork(expected: Network, addresses: NonEmptySet[Address])
  case WrongNetworkWithdrawal(expected: Network, accounts: NonEmptySet[AccountAddress])
  case OutputTooSmallUTxO(outputs: NonEmptyList[TxOut[E]])
  case UpdateFailure(failure: PpupPredFailure[E])
  case OutputBootAddrAttrsTooBig(outputs: NonEmptyList[TxOut[E]])

/** `data AllegraUtxoPredFailure era` (`eras/allegra/impl/src/Cardano/Ledger/Allegra/Rules/Utxo.hs:
  * 66-88`, cardano-ledger f649f975).
  */
enum AllegraUtxoPredFailure[E <: EraTag]:
  case BadInputsUTxO(inputs: NonEmptySet[TxIn])
  case OutsideValidityIntervalUTxO(interval: ValidityInterval, slot: BigInt)
  case MaxTxSizeUTxO(mismatch: Mismatch[BigInt])
  case InputSetEmptyUTxO()
  case FeeTooSmallUTxO(mismatch: Mismatch[Coin])
  case ValueNotConservedUTxO(mismatch: Mismatch[Value[E]])
  case WrongNetwork(expected: Network, addresses: NonEmptySet[Address])
  case WrongNetworkWithdrawal(expected: Network, accounts: NonEmptySet[AccountAddress])
  case OutputTooSmallUTxO(outputs: NonEmptyList[TxOut[E]])
  case UpdateFailure(failure: PpupPredFailure[E])
  case OutputBootAddrAttrsTooBig(outputs: NonEmptyList[TxOut[E]])
  case OutputTooBigUTxO(outputs: NonEmptyList[TxOut[E]])

/** `data AlonzoUtxoPredFailure era` (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Utxo.hs:
  * 120-174`, cardano-ledger f649f975).
  */
enum AlonzoUtxoPredFailure[E <: EraTag]:
  case BadInputsUTxO(inputs: NonEmptySet[TxIn])
  case OutsideValidityIntervalUTxO(interval: ValidityInterval, slot: BigInt)
  case MaxTxSizeUTxO(mismatch: Mismatch[BigInt])
  case InputSetEmptyUTxO()
  case FeeTooSmallUTxO(mismatch: Mismatch[Coin])
  case ValueNotConservedUTxO(mismatch: Mismatch[Value[E]])
  case WrongNetwork(expected: Network, addresses: NonEmptySet[Address])
  case WrongNetworkWithdrawal(expected: Network, accounts: NonEmptySet[AccountAddress])
  case OutputTooSmallUTxO(outputs: NonEmptyList[TxOut[E]])
  case UtxosFailure(failure: UtxosPredFailure[E])
  case OutputBootAddrAttrsTooBig(outputs: NonEmptyList[TxOut[E]])

  /** Each output with the size of its value and the parameters' maximum. */
  case OutputTooBigUTxO(outputs: NonEmptyList[(Int, Int, TxOut[E])])

  /** The balance (a `DeltaCoin`) and the collateral the fee requires. */
  case InsufficientCollateral(balance: BigInt, required: Coin)
  case ScriptsNotPaidUTxO(outputs: NonEmptyMap[TxIn, TxOut[E]])
  case ExUnitsTooBigUTxO(mismatch: Mismatch[ExUnits])
  case CollateralContainsNonADA(value: Value[E])

  /** The body's network (supplied) and the node's (expected). */
  case WrongNetworkInTxBody(mismatch: Mismatch[Network])
  case OutsideForecast(slot: BigInt)

  /** The number of collateral inputs, narrowed to a `Word16`, and the parameters' maximum. */
  case TooManyCollateralInputs(mismatch: Mismatch[Int])
  case NoCollateralInputs()

/** `data BabbageUtxoPredFailure era` (`eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/Utxo.hs:
  * 83-97`, cardano-ledger f649f975).
  */
enum BabbageUtxoPredFailure[E <: EraTag]:
  case AlonzoInBabbageUtxoPredFailure(failure: AlonzoUtxoPredFailure[E])

  /** The collateral provided (a `DeltaCoin`) and the total the body declares. */
  case IncorrectTotalCollateralField(provided: BigInt, declared: Coin)

  /** Each output with its least lovelace. */
  case BabbageOutputTooSmallUTxO(outputs: NonEmptyList[(TxOut[E], Coin)])
  case BabbageNonDisjointRefInputs(inputs: NonEmptyList[TxIn])

/** `data ConwayUtxoPredFailure era` (`eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Utxo.hs:
  * 72-144`, cardano-ledger f649f975), its constructors in the reference's order, which is their tag
  * (`:311-333`).
  */
enum ConwayUtxoPredFailure[E <: EraTag]:
  case UtxosFailure(failure: UtxosPredFailure[E])
  case BadInputsUTxO(inputs: NonEmptySet[TxIn])
  case OutsideValidityIntervalUTxO(interval: ValidityInterval, slot: BigInt)
  case MaxTxSizeUTxO(mismatch: Mismatch[BigInt])
  case InputSetEmptyUTxO()
  case FeeTooSmallUTxO(mismatch: Mismatch[Coin])
  case ValueNotConservedUTxO(mismatch: Mismatch[Value[E]])
  case WrongNetwork(expected: Network, addresses: NonEmptySet[Address])
  case WrongNetworkWithdrawal(expected: Network, accounts: NonEmptySet[AccountAddress])
  case OutputTooSmallUTxO(outputs: NonEmptyList[TxOut[E]])
  case OutputBootAddrAttrsTooBig(outputs: NonEmptyList[TxOut[E]])
  case OutputTooBigUTxO(outputs: NonEmptyList[(Int, Int, TxOut[E])])
  case InsufficientCollateral(balance: BigInt, required: Coin)
  case ScriptsNotPaidUTxO(outputs: NonEmptyMap[TxIn, TxOut[E]])
  case ExUnitsTooBigUTxO(mismatch: Mismatch[ExUnits])
  case CollateralContainsNonADA(value: Value[E])
  case WrongNetworkInTxBody(mismatch: Mismatch[Network])
  case OutsideForecast(slot: BigInt)
  case TooManyCollateralInputs(mismatch: Mismatch[Int])
  case NoCollateralInputs()
  case IncorrectTotalCollateralField(provided: BigInt, declared: Coin)
  case BabbageOutputTooSmallUTxO(outputs: NonEmptyList[(TxOut[E], Coin)])
  case BabbageNonDisjointRefInputs(inputs: NonEmptyList[TxIn])

object ConwayUtxoPredFailure:
  given [E <: EraTag]: CanEqual[ConwayUtxoPredFailure[E], ConwayUtxoPredFailure[E]] =
    CanEqual.derived

  /** UTXOS's failure in the node's flat form (`UtxosFailure`), as [[toStored]] gives it, for the
    * eras whose UTXOS is Conway's: its `CollectErrors` returned as they are, a script's failure the
    * flat one with the evaluator's request and no reconstruction bytes.
    */
  def storedUtxos[E <: EraTag](
      failure: ConwayUtxosPredFailure[E]
  ): Either[NonEmptyList[CollectError[E]], FlatUtxosFailure] = failure match
    case ConwayUtxosPredFailure.CollectErrors(errors) => Left(errors)
    case ConwayUtxosPredFailure.ValidationTagMismatch(isValid, description) =>
      Right(
        FlatUtxosFailure.ValidationTagMismatch(
          isValid == IsPhase2Valid.Phase2Valid,
          description match
            case TagMismatchDescription.PassedUnexpectedly => None
            case TagMismatchDescription.FailedUnexpectedly(fs) =>
              Some(fs.toList.toVector.map { case FailureDescription.PlutusFailure(cause, pwc) =>
                PlutusFailure(cause, Bytes.empty, Some(PlutusWithContext.evalRequest(pwc)))
              })
        )
      )

  /** The failure in the node's flat form (`PredicateFailure`), which its encoders write and the
    * flat rules report: glue until the failures of the era types have their own encoders (#85). It
    * is one-way. UTXOS's `CollectErrors`, whose purposes and context errors the flat failure holds
    * in its own vocabulary, are returned as they are. A script's failure is the flat one with the
    * evaluator's request and no reconstruction bytes, as the flat rule gives it: the request is
    * what the script is run with, while the typed `PlutusWithContext` also holds the runnable
    * script's hash, which its equality reads and the flat form does not keep.
    */
  def toStored(
      failure: ConwayUtxoPredFailure[Conway]
  ): Either[NonEmptyList[CollectError[Conway]], PredicateFailure] =
    val s = StoredTxOut.conway
    def utxo(f: UtxoFailure): Either[NonEmptyList[CollectError[Conway]], PredicateFailure] =
      Right(PredicateFailure.utxo(f))
    def outs(os: NonEmptyList[TxOut[Conway]]) = os.toList.toVector.map(s.toStored)
    failure match
      case UtxosFailure(f) => storedUtxos(f).map(u => PredicateFailure.utxo(UtxoFailure.Utxos(u)))
      case BadInputsUTxO(ins) => utxo(UtxoFailure.BadInputs(ins.toSortedSet))
      case OutsideValidityIntervalUTxO(vi, slot) =>
        utxo(UtxoFailure.OutsideValidityInterval(vi.invalidBefore, vi.invalidHereafter, slot))
      case MaxTxSizeUTxO(m)    => utxo(UtxoFailure.MaxTxSize(m))
      case InputSetEmptyUTxO() => utxo(UtxoFailure.InputSetEmpty)
      case FeeTooSmallUTxO(m) =>
        utxo(UtxoFailure.FeeTooSmall(Mismatch(m.supplied.value, m.expected.value)))
      case ValueNotConservedUTxO(m) =>
        utxo(
          UtxoFailure.ValueNotConserved(
            Mismatch(s.storedValue(m.supplied), s.storedValue(m.expected))
          )
        )
      case WrongNetwork(n, addrs) =>
        utxo(UtxoFailure.WrongNetwork(n.ordinal, addrs.toSortedSet.map(_.raw)))
      case WrongNetworkWithdrawal(n, accounts) =>
        utxo(
          UtxoFailure.WrongNetworkWithdrawal(
            n.ordinal,
            accounts.toSortedSet.map(Convert.rewardAccountBack)
          )
        )
      case OutputTooSmallUTxO(os)        => utxo(UtxoFailure.OutputTooSmall(outs(os)))
      case OutputBootAddrAttrsTooBig(os) => utxo(UtxoFailure.OutputBootAddrAttrsTooBig(outs(os)))
      case OutputTooBigUTxO(os) =>
        utxo(UtxoFailure.OutputTooBig(os.toList.toVector.map((a, b, o) => (a, b, s.toStored(o)))))
      case InsufficientCollateral(bal, required) =>
        utxo(UtxoFailure.InsufficientCollateral(bal, required.value))
      case ScriptsNotPaidUTxO(os) =>
        utxo(UtxoFailure.ScriptsNotPaid(os.toSortedMap.view.mapValues(s.toStored).toMap))
      case ExUnitsTooBigUTxO(m) => utxo(UtxoFailure.ExUnitsTooBig(m))
      case CollateralContainsNonADA(v) =>
        utxo(UtxoFailure.CollateralContainsNonAda(s.storedValue(v)))
      case WrongNetworkInTxBody(m) =>
        utxo(UtxoFailure.WrongNetworkInTxBody(Mismatch(m.supplied.ordinal, m.expected.ordinal)))
      case OutsideForecast(slot)      => utxo(UtxoFailure.OutsideForecast(slot))
      case TooManyCollateralInputs(m) => utxo(UtxoFailure.TooManyCollateralInputs(m))
      case NoCollateralInputs()       => utxo(UtxoFailure.NoCollateralInputs)
      case IncorrectTotalCollateralField(provided, declared) =>
        utxo(UtxoFailure.IncorrectTotalCollateralField(provided, declared.value))
      case BabbageOutputTooSmallUTxO(os) =>
        utxo(
          UtxoFailure.BabbageOutputTooSmall(
            os.toList.toVector.map((o, c) => (s.toStored(o), c.value))
          )
        )
      case BabbageNonDisjointRefInputs(ins) =>
        utxo(UtxoFailure.BabbageNonDisjointRefInputs(ins.toList.toVector))

/** `EraRuleFailure "UTXO" era`: Conway's is its own (`Conway/Rules/Utxo.hs:146`, cardano-ledger
  * f649f975), and so is Dijkstra's (`Dijkstra/Rules/Utxo.hs:172`, cardano-ledger 9df9f98c). Each
  * era adds its case with its rule.
  */
type UtxoPredFailure[E <: EraTag] = E match
  case Conway   => ConwayUtxoPredFailure[Conway]
  case Dijkstra => DijkstraUtxoPredFailure[Dijkstra]

/** `InjectRuleFailure "UTXO" f era` (`Core/Era.hs:120-126`, cardano-ledger f649f975): how era `E`
  * reports a failure `F` of a UTXO predicate defined in an earlier era, or its own. One instance
  * per era and failure type, in the companion. Shelley's differs by reference build, so it is a
  * value of the rule's context ([[InjectShelleyUtxoFailure]]).
  *
  * It is contravariant in `F`: an instance over a failure type serves every narrower type. Where
  * the reference's injection is partial (`error "Impossible"` on a constructor its rule never
  * reaches), the era's instance is over the union of the constructors its rule does reach, each
  * predicate declaring the constructors it reports ([[DijkstraUtxo]]), so every instance is total.
  */
trait InjectUtxoFailure[-F, E <: EraTag]:
  def injectFailure(failure: F): UtxoPredFailure[E]

object InjectUtxoFailure:
  /** `instance InjectRuleFailure "UTXO" ConwayUtxoPredFailure ConwayEra` (`Conway/Rules/Utxo.hs:
    * 150`, cardano-ledger f649f975): the identity.
    */
  given conway: InjectUtxoFailure[ConwayUtxoPredFailure[Conway], Conway] with
    def injectFailure(failure: ConwayUtxoPredFailure[Conway]): UtxoPredFailure[Conway] = failure

  /** `Conway/Rules/Utxo.hs:152-153`, cardano-ledger f649f975. */
  given conwayBabbage: InjectUtxoFailure[BabbageUtxoPredFailure[Conway], Conway] with
    def injectFailure(failure: BabbageUtxoPredFailure[Conway]): UtxoPredFailure[Conway] =
      ConwayUtxo.babbageToConwayUtxoPredFailure(failure)

  /** `Conway/Rules/Utxo.hs:155-156`, cardano-ledger f649f975. */
  given conwayAlonzo: InjectUtxoFailure[AlonzoUtxoPredFailure[Conway], Conway] with
    def injectFailure(failure: AlonzoUtxoPredFailure[Conway]): UtxoPredFailure[Conway] =
      ConwayUtxo.alonzoToConwayUtxoPredFailure(failure)

  /** `Conway/Rules/Utxo.hs:163-164`, cardano-ledger f649f975. */
  given conwayAllegra: InjectUtxoFailure[AllegraUtxoPredFailure[Conway], Conway] with
    def injectFailure(failure: AllegraUtxoPredFailure[Conway]): UtxoPredFailure[Conway] =
      ConwayUtxo.allegraToConwayUtxoPredFailure[Conway](failure)

  /** `Conway/Rules/Utxo.hs:166-170`, cardano-ledger f649f975: `alonzoToConwayUtxoPredFailure .
    * Alonzo.UtxosFailure . injectFailure`, Alonzo's UTXOS failure made Conway's UTXOS failure
    * ([[InjectAlonzoUtxos]]) and wrapped as UTXO's.
    */
  given conwayAlonzoUtxos: InjectUtxoFailure[AlonzoUtxosPredFailure[Conway], Conway] with
    def injectFailure(failure: AlonzoUtxosPredFailure[Conway]): UtxoPredFailure[Conway] =
      ConwayUtxo.alonzoToConwayUtxoPredFailure(
        AlonzoUtxoPredFailure.UtxosFailure[Conway](InjectAlonzoUtxos.conway.injectFailure(failure))
      )

  /** `Conway/Rules/Utxo.hs:428-429`, cardano-ledger f649f975: `UtxosFailure`. */
  given conwayUtxos: InjectUtxoFailure[ConwayUtxosPredFailure[Conway], Conway] with
    def injectFailure(failure: ConwayUtxosPredFailure[Conway]): UtxoPredFailure[Conway] =
      ConwayUtxoPredFailure.UtxosFailure(failure)

  /** `instance InjectRuleFailure "UTXO" DijkstraUtxoPredFailure DijkstraEra`
    * (`Dijkstra/Rules/Utxo.hs:177`, cardano-ledger 9df9f98c): the identity.
    */
  given dijkstra: InjectUtxoFailure[DijkstraUtxoPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraUtxoPredFailure[Dijkstra]): UtxoPredFailure[Dijkstra] =
      failure

  /** `Dijkstra/Rules/Utxo.hs:179-180`, cardano-ledger 9df9f98c, on the constructors the reference
    * maps ([[DijkstraUtxo.ConwayFailure]]).
    */
  given dijkstraConway: InjectUtxoFailure[DijkstraUtxo.ConwayFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraUtxo.ConwayFailure[Dijkstra]): UtxoPredFailure[Dijkstra] =
      DijkstraUtxo.conwayToDijkstraUtxoPredFailure(failure)

  /** `Dijkstra/Rules/Utxo.hs:182-183`, cardano-ledger 9df9f98c, on the constructors Dijkstra's UTXO
    * reports.
    */
  given dijkstraBabbage: InjectUtxoFailure[DijkstraUtxo.BabbageFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraUtxo.BabbageFailure[Dijkstra]): UtxoPredFailure[Dijkstra] =
      DijkstraUtxo.babbageToDijkstraUtxoPredFailure(failure)

  /** `Dijkstra/Rules/Utxo.hs:185-186`, cardano-ledger 9df9f98c, on the constructors Dijkstra's UTXO
    * reports.
    */
  given dijkstraAlonzo: InjectUtxoFailure[DijkstraUtxo.AlonzoFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraUtxo.AlonzoFailure[Dijkstra]): UtxoPredFailure[Dijkstra] =
      DijkstraUtxo.alonzoToDijkstraUtxoPredFailure(failure)

  /** `Dijkstra/Rules/Utxo.hs:188-192`, cardano-ledger 9df9f98c, on the constructors Dijkstra's UTXO
    * reports: one injection, as Dijkstra is of one build.
    */
  given dijkstraShelley: InjectUtxoFailure[DijkstraUtxo.ShelleyFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraUtxo.ShelleyFailure[Dijkstra]): UtxoPredFailure[Dijkstra] =
      DijkstraUtxo.shelleyToDijkstraUtxoPredFailure(failure)

  /** `Dijkstra/Rules/Utxo.hs:194-195`, cardano-ledger 9df9f98c, on the constructor Dijkstra's UTXO
    * reports.
    */
  given dijkstraAllegra: InjectUtxoFailure[DijkstraUtxo.AllegraFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraUtxo.AllegraFailure[Dijkstra]): UtxoPredFailure[Dijkstra] =
      DijkstraUtxo.allegraToDijkstraUtxoPredFailure(failure)

  /** `Dijkstra/Rules/Utxo.hs:197-198`, cardano-ledger 9df9f98c: `UtxosFailure`. */
  given dijkstraUtxos: InjectUtxoFailure[ConwayUtxosPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ConwayUtxosPredFailure[Dijkstra]): UtxoPredFailure[Dijkstra] =
      DijkstraUtxoPredFailure.UtxosFailure(failure)

  /** `Dijkstra/Rules/Utxo.hs:200-205`, cardano-ledger 9df9f98c: `conwayToDijkstraUtxoPredFailure .
    * alonzoToConwayUtxoPredFailure . Alonzo.UtxosFailure . injectFailure`, whose every value is
    * `UtxosFailure` of UTXOS's failure made Conway's ([[InjectAlonzoUtxos]]).
    */
  given dijkstraAlonzoUtxos: InjectUtxoFailure[AlonzoUtxosPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: AlonzoUtxosPredFailure[Dijkstra]): UtxoPredFailure[Dijkstra] =
      DijkstraUtxoPredFailure.UtxosFailure(InjectAlonzoUtxos.dijkstra.injectFailure(failure))

/** `Embed (UTXOS era) (UTXO era)` (`Conway/Rules/Utxo.hs:286-295`, cardano-ledger f649f975): how
  * UTXOS's failures and events sit in UTXO's (`wrapFailed`, `wrapEvent`).
  */
trait EmbedUtxosInUtxo[E <: EraTag]:
  def wrapFailed(failure: UtxosPredFailure[E]): UtxoPredFailure[E]
  def wrapEvent(event: UtxosEvent[E]): AlonzoUtxoEvent[E]

object EmbedUtxosInUtxo:
  /** Conway's: `UtxosFailure` and Alonzo's `UtxosEvent`. */
  given conway: EmbedUtxosInUtxo[Conway] with
    def wrapFailed(failure: UtxosPredFailure[Conway]): UtxoPredFailure[Conway] =
      ConwayUtxoPredFailure.UtxosFailure(failure)
    def wrapEvent(event: UtxosEvent[Conway]): AlonzoUtxoEvent[Conway] =
      AlonzoUtxoEvent.UtxosEvent(event)

  /** Dijkstra's (`Dijkstra/Rules/Utxo.hs:474-482`, cardano-ledger 9df9f98c): `UtxosFailure` and
    * Alonzo's `UtxosEvent`.
    */
  given dijkstra: EmbedUtxosInUtxo[Dijkstra] with
    def wrapFailed(failure: UtxosPredFailure[Dijkstra]): UtxoPredFailure[Dijkstra] =
      DijkstraUtxoPredFailure.UtxosFailure(failure)
    def wrapEvent(event: UtxosEvent[Dijkstra]): AlonzoUtxoEvent[Dijkstra] =
      AlonzoUtxoEvent.UtxosEvent(event)
