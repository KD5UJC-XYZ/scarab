// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.Monad
import cats.data.{Chain, NonEmptyList, NonEmptySet}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, TxIn}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.Relation.*
import xyz.kd5ujc.scarab.ledger.rules.{Judge, Sts, Test}
import xyz.kd5ujc.scarab.ledger.state.{CertState, Coin, Dijkstra, EraTag, UTxOState}

// SUBUTXO of Dijkstra as the reference writes it (cardano-ledger 9df9f98c,
// `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/SubUtxo.hs`): its failures, events and
// environment, and its transition over one sub-transaction, which runs the predicates of Shelley's,
// Allegra's, Alonzo's and Babbage's UTXO (`Utxo.scala`) and moves the sub-transaction's outputs.
// Its injections, which the reference leaves partial (`error "Impossible"`,
// `dijkstraUtxoToDijkstraSubUtxoPredFailure`, `SubUtxo.hs:304-329`), are total on what it reaches
// ([[DijkstraSubUtxo.UtxoFailure]] and the others), as Dijkstra's UTXO's are ([[DijkstraUtxo]]).

/** `data DijkstraSubUtxoPredFailure era` (`Dijkstra/Rules/SubUtxo.hs:70-98`, cardano-ledger
  * 9df9f98c), its constructors in the reference's order; their tags are `:270-281`, 5 unused.
  */
enum DijkstraSubUtxoPredFailure[E <: EraTag]:
  case SubBadInputsUTxO(inputs: NonEmptySet[TxIn])
  case SubOutsideValidityIntervalUTxO(interval: ValidityInterval, slot: BigInt)
  case SubMaxTxSizeUTxO(mismatch: Mismatch[RelLTEQ, BigInt])
  case SubInputSetEmptyUTxO()
  case SubWrongNetwork(expected: Network, addresses: NonEmptySet[Address])
  case SubOutputBootAddrAttrsTooBig(outputs: NonEmptyList[TxOut[E]])
  case SubOutputTooBigUTxO(outputs: NonEmptyList[(Int, Int, TxOut[E])])
  case SubWrongNetworkInTxBody(mismatch: Mismatch[RelEQ, Network])
  case SubOutsideForecast(slot: BigInt)
  case SubBabbageOutputTooSmallUTxO(outputs: NonEmptyList[(TxOut[E], Coin)])

object DijkstraSubUtxoPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraSubUtxoPredFailure[E], DijkstraSubUtxoPredFailure[E]] =
    CanEqual.derived

/** `data DijkstraSubUtxoEvent era` (`Dijkstra/Rules/SubUtxo.hs:173-181`, cardano-ledger 9df9f98c):
  * the change of the deposits by the hash of a body, which SUBUTXO never tells, and the outputs a
  * sub-transaction spends and creates.
  */
enum DijkstraSubUtxoEvent[E <: EraTag]:
  case TotalDeposits(txBodyHash: Bytes, depositChange: Coin)
  case TxUTxODiff(consumed: UTxO[E], created: UTxO[E])

object DijkstraSubUtxoEvent:
  given [E <: EraTag]: CanEqual[DijkstraSubUtxoEvent[E], DijkstraSubUtxoEvent[E]] =
    CanEqual.derived

/** `EraRuleFailure "SUBUTXO" era`: Dijkstra's (`Dijkstra/Rules/SubUtxo.hs:134`, cardano-ledger
  * 9df9f98c), the one era with the rule.
  */
type SubUtxoPredFailure[E <: EraTag] = E match
  case Dijkstra => DijkstraSubUtxoPredFailure[Dijkstra]

/** `EraRuleEvent "SUBUTXO" era`: Dijkstra's (`Dijkstra/Rules/SubUtxo.hs:136`, cardano-ledger
  * 9df9f98c).
  */
type SubUtxoEvent[E <: EraTag] = E match
  case Dijkstra => DijkstraSubUtxoEvent[Dijkstra]

/** `SubUtxoEnv` (`Dijkstra/Rules/SubUtxo.hs:62-68`, cardano-ledger 9df9f98c): the slot, the
  * parameters, the certificate state SUBLEDGER is given (`SubLedger.hs:307`), the UTxO set before
  * the whole transaction, and the validity flag of its top level. The UTxO set holds at least the
  * outputs of the sub-transaction's inputs and reference inputs, every output a lookup of SUBUTXOW
  * reads.
  */
final case class SubUtxoEnv[E <: EraTag](
    slot: BigInt,
    pp: PParams[E],
    certState: CertState[E],
    originalUtxo: UTxO[E],
    topTxIsPhase2Valid: IsPhase2Valid
)

/** `InjectRuleFailure "SUBUTXO" f era` (`Core/Era.hs:120-126`, cardano-ledger 9df9f98c): how era
  * `E` reports a failure `F` of a predicate SUBUTXO runs in its own. Contravariant in `F`, as
  * [[InjectUtxoFailure]] is; where the reference's injection is partial, the instance is over the
  * constructors the rule reaches, so every instance is total.
  */
trait InjectSubUtxoFailure[-F, E <: EraTag]:
  def injectFailure(failure: F): SubUtxoPredFailure[E]

object InjectSubUtxoFailure:
  /** `Dijkstra/Rules/SubUtxo.hs:138`, cardano-ledger 9df9f98c: the identity. */
  given dijkstra: InjectSubUtxoFailure[DijkstraSubUtxoPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubUtxoPredFailure[Dijkstra]
    ): SubUtxoPredFailure[Dijkstra] = failure

  /** `Dijkstra/Rules/SubUtxo.hs:140-141`, cardano-ledger 9df9f98c, on the constructors the
    * reference maps ([[DijkstraSubUtxo.UtxoFailure]]).
    */
  given dijkstraUtxo: InjectSubUtxoFailure[DijkstraSubUtxo.UtxoFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubUtxo.UtxoFailure[Dijkstra]
    ): SubUtxoPredFailure[Dijkstra] =
      DijkstraSubUtxo.dijkstraUtxoToDijkstraSubUtxoPredFailure(failure)

  /** `Dijkstra/Rules/SubUtxo.hs:143-144`, cardano-ledger 9df9f98c, on the constructors the
    * reference maps ([[DijkstraSubUtxo.ConwayFailure]]).
    */
  given dijkstraConway: InjectSubUtxoFailure[DijkstraSubUtxo.ConwayFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubUtxo.ConwayFailure[Dijkstra]
    ): SubUtxoPredFailure[Dijkstra] = DijkstraSubUtxo.conwayToDijkstraSubUtxoPredFailure(failure)

  /** `Dijkstra/Rules/SubUtxo.hs:146-150`, cardano-ledger 9df9f98c, on the constructors SUBUTXO
    * reports.
    */
  given dijkstraAlonzo: InjectSubUtxoFailure[DijkstraSubUtxo.AlonzoFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubUtxo.AlonzoFailure[Dijkstra]
    ): SubUtxoPredFailure[Dijkstra] = DijkstraSubUtxo.alonzoToDijkstraSubUtxoPredFailure(failure)

  /** `Dijkstra/Rules/SubUtxo.hs:152-156`, cardano-ledger 9df9f98c, on the constructor SUBUTXO
    * reports.
    */
  given dijkstraBabbage: InjectSubUtxoFailure[DijkstraSubUtxo.BabbageFailure[Dijkstra], Dijkstra]
  with
    def injectFailure(
        failure: DijkstraSubUtxo.BabbageFailure[Dijkstra]
    ): SubUtxoPredFailure[Dijkstra] = DijkstraSubUtxo.babbageToDijkstraSubUtxoPredFailure(failure)

  /** `Dijkstra/Rules/SubUtxo.hs:158-162`, cardano-ledger 9df9f98c, on the constructor SUBUTXO
    * reports.
    */
  given dijkstraAllegra: InjectSubUtxoFailure[DijkstraSubUtxo.AllegraFailure[Dijkstra], Dijkstra]
  with
    def injectFailure(
        failure: DijkstraSubUtxo.AllegraFailure[Dijkstra]
    ): SubUtxoPredFailure[Dijkstra] = DijkstraSubUtxo.allegraToDijkstraSubUtxoPredFailure(failure)

  /** `Dijkstra/Rules/SubUtxo.hs:164-169`, cardano-ledger 9df9f98c, on the constructors SUBUTXO
    * reports.
    */
  given dijkstraShelley: InjectSubUtxoFailure[DijkstraSubUtxo.ShelleyFailure[Dijkstra], Dijkstra]
  with
    def injectFailure(
        failure: DijkstraSubUtxo.ShelleyFailure[Dijkstra]
    ): SubUtxoPredFailure[Dijkstra] = DijkstraSubUtxo.shelleyToDijkstraSubUtxoPredFailure(failure)

/** Dijkstra's functions of SUBUTXO
  * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/SubUtxo.hs`, cardano-ledger 9df9f98c).
  */
object DijkstraSubUtxo:
  /** The constructors of Dijkstra's UTXO failure that `dijkstraUtxoToDijkstraSubUtxoPredFailure`
    * maps (`Dijkstra/Rules/SubUtxo.hs:304-329`, cardano-ledger 9df9f98c): its domain here, the 13
    * it calls impossible left out.
    */
  type UtxoFailure[E <: EraTag] = DijkstraUtxoPredFailure.BadInputsUTxO[E] |
    DijkstraUtxoPredFailure.OutsideValidityIntervalUTxO[E] |
    DijkstraUtxoPredFailure.MaxTxSizeUTxO[E] | DijkstraUtxoPredFailure.InputSetEmptyUTxO[E] |
    DijkstraUtxoPredFailure.WrongNetwork[E] | DijkstraUtxoPredFailure.OutputBootAddrAttrsTooBig[E] |
    DijkstraUtxoPredFailure.OutputTooBigUTxO[E] | DijkstraUtxoPredFailure.WrongNetworkInTxBody[E] |
    DijkstraUtxoPredFailure.OutsideForecast[E] |
    DijkstraUtxoPredFailure.BabbageOutputTooSmallUTxO[E]

  /** The constructors of Conway's UTXO failure that `conwayToDijkstraUtxoPredFailure` carries into
    * [[UtxoFailure]] (`Dijkstra/Rules/SubUtxo.hs:143-144`, cardano-ledger 9df9f98c).
    */
  type ConwayFailure[E <: EraTag] = ConwayUtxoPredFailure.BadInputsUTxO[E] |
    ConwayUtxoPredFailure.OutsideValidityIntervalUTxO[E] | ConwayUtxoPredFailure.MaxTxSizeUTxO[E] |
    ConwayUtxoPredFailure.InputSetEmptyUTxO[E] | ConwayUtxoPredFailure.WrongNetwork[E] |
    ConwayUtxoPredFailure.OutputBootAddrAttrsTooBig[E] | ConwayUtxoPredFailure.OutputTooBigUTxO[E] |
    ConwayUtxoPredFailure.WrongNetworkInTxBody[E] | ConwayUtxoPredFailure.OutsideForecast[E] |
    ConwayUtxoPredFailure.BabbageOutputTooSmallUTxO[E]

  /** The failures of Shelley's type that SUBUTXO reports, each from the predicate that declares it.
    */
  type ShelleyFailure[E <: EraTag] = ShelleyUtxoPredFailure.BadInputsUTxO[E] |
    ShelleyUtxoPredFailure.InputSetEmptyUTxO[E] | ShelleyUtxoPredFailure.WrongNetwork[E] |
    ShelleyUtxoPredFailure.OutputBootAddrAttrsTooBig[E]

  /** The failure of Allegra's type that SUBUTXO reports. */
  type AllegraFailure[E <: EraTag] = AllegraUtxoPredFailure.OutsideValidityIntervalUTxO[E]

  /** The failures of Alonzo's type that SUBUTXO reports. */
  type AlonzoFailure[E <: EraTag] = AlonzoUtxoPredFailure.OutsideForecast[E] |
    AlonzoUtxoPredFailure.OutputTooBigUTxO[E] | AlonzoUtxoPredFailure.WrongNetworkInTxBody[E]

  /** The failure of Babbage's type that SUBUTXO reports. */
  type BabbageFailure[E <: EraTag] = BabbageUtxoPredFailure.BabbageOutputTooSmallUTxO[E]

  /** `dijkstraUtxoToDijkstraSubUtxoPredFailure` (`Dijkstra/Rules/SubUtxo.hs:304-329`,
    * cardano-ledger 9df9f98c), on the constructors it maps ([[UtxoFailure]]).
    */
  def dijkstraUtxoToDijkstraSubUtxoPredFailure[E <: EraTag](
      failure: UtxoFailure[E]
  ): DijkstraSubUtxoPredFailure[E] = failure match
    case DijkstraUtxoPredFailure.BadInputsUTxO(x) => DijkstraSubUtxoPredFailure.SubBadInputsUTxO(x)
    case DijkstraUtxoPredFailure.OutsideValidityIntervalUTxO(vi, slotNo) =>
      DijkstraSubUtxoPredFailure.SubOutsideValidityIntervalUTxO(vi, slotNo)
    case DijkstraUtxoPredFailure.MaxTxSizeUTxO(m) => DijkstraSubUtxoPredFailure.SubMaxTxSizeUTxO(m)
    case DijkstraUtxoPredFailure.InputSetEmptyUTxO() =>
      DijkstraSubUtxoPredFailure.SubInputSetEmptyUTxO()
    case DijkstraUtxoPredFailure.WrongNetwork(x, y) =>
      DijkstraSubUtxoPredFailure.SubWrongNetwork(x, y)
    case DijkstraUtxoPredFailure.OutputBootAddrAttrsTooBig(xs) =>
      DijkstraSubUtxoPredFailure.SubOutputBootAddrAttrsTooBig(xs)
    case DijkstraUtxoPredFailure.OutputTooBigUTxO(xs) =>
      DijkstraSubUtxoPredFailure.SubOutputTooBigUTxO(xs)
    case DijkstraUtxoPredFailure.WrongNetworkInTxBody(m) =>
      DijkstraSubUtxoPredFailure.SubWrongNetworkInTxBody(m)
    case DijkstraUtxoPredFailure.OutsideForecast(sno) =>
      DijkstraSubUtxoPredFailure.SubOutsideForecast(sno)
    case DijkstraUtxoPredFailure.BabbageOutputTooSmallUTxO(outs) =>
      DijkstraSubUtxoPredFailure.SubBabbageOutputTooSmallUTxO(outs)

  /** `dijkstraUtxoToDijkstraSubUtxoPredFailure . conwayToDijkstraUtxoPredFailure`
    * (`Dijkstra/Rules/SubUtxo.hs:143-144`, cardano-ledger 9df9f98c), on [[ConwayFailure]]: each
    * passes both unchanged.
    */
  def conwayToDijkstraSubUtxoPredFailure[E <: EraTag](
      failure: ConwayFailure[E]
  ): DijkstraSubUtxoPredFailure[E] = failure match
    case ConwayUtxoPredFailure.BadInputsUTxO(x) => DijkstraSubUtxoPredFailure.SubBadInputsUTxO(x)
    case ConwayUtxoPredFailure.OutsideValidityIntervalUTxO(vi, slotNo) =>
      DijkstraSubUtxoPredFailure.SubOutsideValidityIntervalUTxO(vi, slotNo)
    case ConwayUtxoPredFailure.MaxTxSizeUTxO(m) => DijkstraSubUtxoPredFailure.SubMaxTxSizeUTxO(m)
    case ConwayUtxoPredFailure.InputSetEmptyUTxO() =>
      DijkstraSubUtxoPredFailure.SubInputSetEmptyUTxO()
    case ConwayUtxoPredFailure.WrongNetwork(x, y) =>
      DijkstraSubUtxoPredFailure.SubWrongNetwork(x, y)
    case ConwayUtxoPredFailure.OutputBootAddrAttrsTooBig(xs) =>
      DijkstraSubUtxoPredFailure.SubOutputBootAddrAttrsTooBig(xs)
    case ConwayUtxoPredFailure.OutputTooBigUTxO(xs) =>
      DijkstraSubUtxoPredFailure.SubOutputTooBigUTxO(xs)
    case ConwayUtxoPredFailure.WrongNetworkInTxBody(m) =>
      DijkstraSubUtxoPredFailure.SubWrongNetworkInTxBody(m)
    case ConwayUtxoPredFailure.OutsideForecast(sno) =>
      DijkstraSubUtxoPredFailure.SubOutsideForecast(sno)
    case ConwayUtxoPredFailure.BabbageOutputTooSmallUTxO(outs) =>
      DijkstraSubUtxoPredFailure.SubBabbageOutputTooSmallUTxO(outs)

  /** `injectFailure` of `InjectRuleFailure "SUBUTXO" ShelleyUtxoPredFailure DijkstraEra`
    * (`Dijkstra/Rules/SubUtxo.hs:164-169`, cardano-ledger 9df9f98c), on the constructors SUBUTXO
    * reports: each passes the four injections unchanged.
    */
  def shelleyToDijkstraSubUtxoPredFailure[E <: EraTag](
      failure: ShelleyFailure[E]
  ): DijkstraSubUtxoPredFailure[E] = failure match
    case ShelleyUtxoPredFailure.BadInputsUTxO(ins) =>
      DijkstraSubUtxoPredFailure.SubBadInputsUTxO(ins)
    case ShelleyUtxoPredFailure.InputSetEmptyUTxO() =>
      DijkstraSubUtxoPredFailure.SubInputSetEmptyUTxO()
    case ShelleyUtxoPredFailure.WrongNetwork(n, as) =>
      DijkstraSubUtxoPredFailure.SubWrongNetwork(n, as)
    case ShelleyUtxoPredFailure.OutputBootAddrAttrsTooBig(outs) =>
      DijkstraSubUtxoPredFailure.SubOutputBootAddrAttrsTooBig(outs)

  /** `injectFailure` of `InjectRuleFailure "SUBUTXO" AllegraUtxoPredFailure DijkstraEra`
    * (`Dijkstra/Rules/SubUtxo.hs:158-162`, cardano-ledger 9df9f98c), on the constructor SUBUTXO
    * reports.
    */
  def allegraToDijkstraSubUtxoPredFailure[E <: EraTag](
      failure: AllegraFailure[E]
  ): DijkstraSubUtxoPredFailure[E] = failure match
    case AllegraUtxoPredFailure.OutsideValidityIntervalUTxO(vi, slotNo) =>
      DijkstraSubUtxoPredFailure.SubOutsideValidityIntervalUTxO(vi, slotNo)

  /** `injectFailure` of `InjectRuleFailure "SUBUTXO" AlonzoUtxoPredFailure DijkstraEra`
    * (`Dijkstra/Rules/SubUtxo.hs:146-150`, cardano-ledger 9df9f98c), on the constructors SUBUTXO
    * reports.
    */
  def alonzoToDijkstraSubUtxoPredFailure[E <: EraTag](
      failure: AlonzoFailure[E]
  ): DijkstraSubUtxoPredFailure[E] = failure match
    case AlonzoUtxoPredFailure.OutsideForecast(sno) =>
      DijkstraSubUtxoPredFailure.SubOutsideForecast(sno)
    case AlonzoUtxoPredFailure.OutputTooBigUTxO(xs) =>
      DijkstraSubUtxoPredFailure.SubOutputTooBigUTxO(xs)
    case AlonzoUtxoPredFailure.WrongNetworkInTxBody(m) =>
      DijkstraSubUtxoPredFailure.SubWrongNetworkInTxBody(m)

  /** `injectFailure` of `InjectRuleFailure "SUBUTXO" BabbageUtxoPredFailure DijkstraEra`
    * (`Dijkstra/Rules/SubUtxo.hs:152-156`, cardano-ledger 9df9f98c), on the constructor SUBUTXO
    * reports.
    */
  def babbageToDijkstraSubUtxoPredFailure[E <: EraTag](
      failure: BabbageFailure[E]
  ): DijkstraSubUtxoPredFailure[E] = failure match
    case BabbageUtxoPredFailure.BabbageOutputTooSmallUTxO(ts) =>
      DijkstraSubUtxoPredFailure.SubBabbageOutputTooSmallUTxO(ts)

  private val t = EraTx.dijkstra
  private val b = EraTxBody.dijkstra
  private given BothLevels[TxLevel.Sub] = BothLevels.Sub

  private def injected[F](test: Test[F])(using
      inject: InjectSubUtxoFailure[F, Dijkstra]
  ): Test[SubUtxoPredFailure[Dijkstra]] =
    test.leftMap(_.map(inject.injectFailure))

  /** The predicates of `dijkstraSubUtxoTransition` (`Dijkstra/Rules/SubUtxo.hs:232-253`,
    * cardano-ledger 9df9f98c) in its order, every failure accumulated and injected into SUBUTXO's:
    * the validity interval and the forecast; the outputs' size; an empty input set; the inputs
    * against `current`, the threaded state's UTxO set (an earlier sub-transaction may have spent
    * one); on the signal the outputs' bootstrap attributes; the outputs' least lovelace; on the
    * signal the outputs' network and the body's.
    */
  def dijkstraSubUtxoValidation(
      env: SubUtxoEnv[Dijkstra],
      current: UTxO[Dijkstra],
      stAnnTx: StAnnTx[TxLevel.Sub, Dijkstra],
      context: DijkstraUtxoContext
  ): Test[SubUtxoPredFailure[Dijkstra]] =
    given EraTxOut[Dijkstra] = b.outputsOf
    val tx = t.txStAnnTx[TxLevel.Sub](stAnnTx)
    val txBody = t.body[TxLevel.Sub](tx)
    val allSizedOutputs = b.allSizedOutputs[TxLevel.Sub](txBody)
    val allOutputs = allSizedOutputs.map(_.value)
    Test.all(
      injected(
        AllegraUtxo.validateOutsideValidityIntervalUTxO[Dijkstra, TxLevel.Sub](env.slot, txBody)
      ),
      injected(
        AlonzoUtxo.validateOutsideForecast[Dijkstra, TxLevel.Sub](
          context.epochInfo,
          env.slot,
          context.systemStart,
          tx
        )
      ),
      injected(AlonzoUtxo.validateOutputTooBigUTxO[Dijkstra](env.pp, allOutputs)),
      injected(ShelleyUtxo.validateInputSetEmptyUTxO[Dijkstra, TxLevel.Sub](txBody)),
      injected(ShelleyUtxo.validateBadInputsUTxO[Dijkstra](current, b.inputs[TxLevel.Sub](txBody))),
      context.onSignal(
        injected(ShelleyUtxo.validateOutputBootAddrAttrsTooBig[Dijkstra](allOutputs))
      ),
      injected(BabbageUtxo.validateOutputTooSmallUTxO[Dijkstra](env.pp, allSizedOutputs)),
      context.onSignal(
        injected(ShelleyUtxo.validateWrongNetwork[Dijkstra](context.netId, allOutputs))
      ),
      context.onSignal(
        injected(
          AlonzoUtxo.validateWrongNetworkInTxBody[Dijkstra, TxLevel.Sub](context.netId, txBody)
        )
      )
    )

  /** `dijkstraSubUtxoTransition` (`Dijkstra/Rules/SubUtxo.hs:211-262`, cardano-ledger 9df9f98c):
    * SUBUTXO's predicates ([[dijkstraSubUtxoValidation]]), every failure recorded; then, when the
    * top level is flagged valid (the environment's flag, not the sub-transaction's), the
    * sub-transaction's donation added and its inputs and outputs moved with the instant stake
    * (Shelley's `updateUTxOAndInstantStake`, its `TxUTxODiff` told), from the state the transition
    * was given, whatever was recorded; no deposit and no fee. Flagged invalid, the state is left as
    * it is.
    *
    * The threaded state's outputs of the inputs are read from the store into the era for the check
    * of the inputs; a stored output the era does not read, and a change of the instant stake
    * outside the state's vocabulary, are raised ([[UtxoStateDefect]]), as in Dijkstra's UTXO.
    */
  def dijkstraSubUtxoTransition[F[_]: Monad](
      env: SubUtxoEnv[Dijkstra],
      stAnnTx: StAnnTx[TxLevel.Sub, Dijkstra],
      context: DijkstraUtxoContext
  )(using
      state: Stateful[F, UTxOState[Dijkstra]],
      judge: Judge[F, SubUtxoPredFailure[Dijkstra]],
      events: Tell[F, Chain[DijkstraSubUtxoEvent[Dijkstra]]],
      defect: Raise[F, UtxoStateDefect]
  ): F[Unit] =
    val tx = t.txStAnnTx[TxLevel.Sub](stAnnTx)
    val txBody = t.body[TxLevel.Sub](tx)
    for
      utxoState <- state.get
      current <- ShelleyUtxo.readStored[F, Dijkstra](
        ShelleyUtxo.restrictKeys(utxoState.utxo.utxo, b.inputs[TxLevel.Sub](txBody))
      )
      _ <- Sts.runTest[F, SubUtxoPredFailure[Dijkstra]](
        dijkstraSubUtxoValidation(env, current, stAnnTx, context)
      )
      _ <- env.topTxIsPhase2Valid match
        case IsPhase2Valid.Phase2Valid =>
          ShelleyUtxo
            .updateUTxOAndInstantStake[F, Dijkstra, TxLevel.Sub](
              txBody,
              (consumed, created) =>
                events.tell(Chain.one(DijkstraSubUtxoEvent.TxUTxODiff(consumed, created))),
              utxoState.copy(donation =
                Coin(utxoState.donation.value + b.treasuryDonation[TxLevel.Sub](txBody).value)
              )
            )(using summon[Monad[F]], b, summon[BothLevels[TxLevel.Sub]])
            .flatMap(state.set)
        case IsPhase2Valid.Phase2Invalid => Monad[F].unit
    yield ()
