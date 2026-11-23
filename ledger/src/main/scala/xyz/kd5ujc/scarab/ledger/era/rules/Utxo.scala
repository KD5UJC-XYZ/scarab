// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.Monad
import cats.data.{Chain, NonEmptyList, NonEmptyMap, NonEmptySet}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Credential,
  Horizon,
  PosixClock,
  SystemStart,
  TxEnv,
  TxIn,
  Unsigned
}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.W16
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.{Judge, Sts, Test}
import xyz.kd5ujc.scarab.plutus.ScriptEvaluator
import xyz.kd5ujc.scarab.ledger.state.{
  CertState,
  Coin,
  Conway,
  DeltaCoin,
  EraTag,
  UTxOState,
  UtxoTables,
  VocabError
}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

// The predicates of UTXO as the reference writes them, each generic in its era and asking for the
// reference's classes (cardano-ledger f649f975, the `Rules/Utxo.hs` of each era), and Conway's UTXO
// transition, which runs them and embeds UTXOS. Each object is the module of the era that defines its
// functions, and each function has the reference's name and the constraints of its signature. A
// predicate reports the failure type of its era (`UtxoFailures.scala`), and the rule injects it into
// its own.

/** The network a body names, by its wire value (`Network`'s constructors in order,
  * `BaseTypes.hs:878-881`, cardano-ledger f649f975), as the failures carry it.
  */
private def wire(n: Network): Int = n.ordinal

/** `UtxoEnv` (`Shelley/Rules/Utxo.hs:107-111`, cardano-ledger f649f975): the slot, the protocol
  * parameters and the certificate state UTXO judges a transaction against.
  */
final case class UtxoEnv[E <: EraTag](slot: BigInt, pp: PParams[E], certState: CertState[E])

object InjectShelleyUtxoFailure:
  private[rules] def apply[E <: EraTag](
      inject: ShelleyUtxoPredFailure[E] => UtxoPredFailure[E]
  ): InjectShelleyUtxoFailure[E] = new InjectShelleyUtxoFailure[E](inject)

/** `InjectRuleFailure "UTXO" ShelleyUtxoPredFailure era` (`Core/Era.hs:120-126`, cardano-ledger
  * f649f975): era `E` 's injection of the failures of Shelley's UTXO predicates into its own, which
  * differs by reference build (Shelley's into Allegra's, [[AllegraUtxo]]). Only this package makes
  * one, for each era and reference build the reference has ([[ConwayUtxo]]), so a rule cannot be
  * given another era's or an arbitrary rewrite.
  */
final class InjectShelleyUtxoFailure[E <: EraTag] private[rules] (
    val injectFailure: ShelleyUtxoPredFailure[E] => UtxoPredFailure[E]
)

/** What the reference's UTXO reads besides its environment, gathered for the era-typed rule of era
  * `E`:
  *   - `netId`, `systemStart` and `epochInfo`, which it asks of `Globals` (`liftSTS $ asks`); the
  *     node's [[xyz.kd5ujc.scarab.ledger.rules.Globals]] has the geometry but not the era history
  *     at the tip, so `epochInfo` comes with the transaction, as the flat rule's clock does;
  *   - `validation`, the policy under which `runTestOnSignal` skips its tests;
  *   - `injectShelley`, the era's injection of the failures of Shelley's predicates, which differs
  *     by reference build ([[ConwayUtxo.injectShelleyFailure]]).
  */
final case class UtxoContext[E <: EraTag](
    netId: Network,
    systemStart: SystemStart,
    epochInfo: Horizon,
    validation: TxEnv.Validation,
    injectShelley: InjectShelleyUtxoFailure[E]
):
  /** `runTestOnSignal` (`Rules/ValidationMode.hs:88-89`, cardano-ledger f649f975): `test` when the
    * policy validates the signal, a pass otherwise.
    */
  def onSignal[A](test: => Test[A]): Test[A] = validation match
    case TxEnv.Validation.All       => test
    case TxEnv.Validation.NonStatic => Test.pass

  /** The injection `runTest` and `runTestOnSignal` apply to a test of Shelley's predicates
    * (`validateTrans injectFailure`, `Rules/ValidationMode.hs:85-89`, cardano-ledger f649f975).
    */
  private[rules] def shelley(test: Test[ShelleyUtxoPredFailure[E]]): Test[UtxoPredFailure[E]] =
    test.leftMap(_.map(injectShelley.injectFailure))

/** The injection of a test's failures into the rule's (`injectFailure`, as `runTest` applies it
  * through `validateTrans`, `Rules/ValidationMode.hs:85`, cardano-ledger f649f975).
  */
private[rules] def injected[F, E <: EraTag](test: Test[F])(using
    inject: InjectUtxoFailure[F, E]
): Test[UtxoPredFailure[E]] =
  test.leftMap(_.map(inject.injectFailure))

/** `data AlonzoUtxoEvent era` (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Utxo.hs:218-227`,
  * cardano-ledger f649f975): UTXO's events, Babbage's and Conway's too: UTXOS's, the change of the
  * deposits a valid transaction makes, by the hash of its body (`hashAnnotated`, its id), and the
  * outputs it spends and creates.
  */
enum AlonzoUtxoEvent[E <: EraTag]:
  case UtxosEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.UtxosEvent[E])
  case TotalDeposits(txBodyHash: Bytes, depositChange: Coin)
  case TxUTxODiff(consumed: UTxO[E], created: UTxO[E])

/** Why the UTxO state could not be changed: what only a damaged store or an output the state's
  * vocabulary cannot hold gives, where the reference reads its own state with no refusal. Neither
  * is a verdict of the rules.
  */
enum UtxoStateDefect:
  /** A stored output the era does not read (design §3.6). */
  case StoredOutput(input: TxIn, error: EraTxError)

  /** An output's stake credential or lovelace outside the state's vocabulary ([[EraStake]]). */
  case Vocab(error: VocabError)

/** Shelley's predicates of UTXO (`eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Utxo.hs`,
  * cardano-ledger f649f975).
  */
object ShelleyUtxo:
  /** `validateInputSetEmptyUTxO` (`Shelley/Rules/Utxo.hs:428-435`, cardano-ledger f649f975). */
  def validateInputSetEmptyUTxO[E <: EraTag, L <: TxLevel](body: TxBody[L, E])(using
      b: EraTxBody[E],
      l: b.Level[L]
  ): Test[ShelleyUtxoPredFailure.InputSetEmptyUTxO[E]] =
    Test.failureUnless(b.inputs(body).nonEmpty, new ShelleyUtxoPredFailure.InputSetEmptyUTxO[E]())

  /** `validateFeeTooSmallUTxO` (`Shelley/Rules/Utxo.hs:447-463`, cardano-ledger 9df9f98c): the fee
    * at least the minimum (`getMinFeeTxUtxo`), the failure with the fee (supplied) and the minimum
    * (expected). Dijkstra's UTXO runs it (`Dijkstra/Rules/Utxo.hs:357`, cardano-ledger 9df9f98c),
    * where Babbage's and Conway's run `feesOK`.
    */
  def validateFeeTooSmallUTxO[E <: EraTag](pp: PParams[E], tx: Tx[TxLevel.Top, E], utxo: UTxO[E])(
      using e: EraUTxO[E]
  ): Test[ShelleyUtxoPredFailure.FeeTooSmallUTxO[E]] =
    val minFee = e.getMinFeeTxUtxo[TxLevel.Top](pp, tx, utxo)(using e.txOf.bodyOf.top)
    val txFee = e.txOf.bodyOf.fee(e.txOf.body[TxLevel.Top](tx)(using e.txOf.bodyOf.top))
    Test.failureUnless(
      minFee.value <= txFee.value,
      new ShelleyUtxoPredFailure.FeeTooSmallUTxO[E](Mismatch(txFee, minFee))
    )

  /** `validateBadInputsUTxO` (`Shelley/Rules/Utxo.hs:461-469`, cardano-ledger f649f975): the inputs
    * the UTxO set does not hold.
    */
  def validateBadInputsUTxO[E <: EraTag](
      utxo: UTxO[E],
      inputs: Set[TxIn]
  ): Test[ShelleyUtxoPredFailure.BadInputsUTxO[E]] =
    Test.failureOnJust(NonEmptySet.fromSet(inputs.filterNot(utxo.unUTxO.contains).to(SortedSet)))(
      new ShelleyUtxoPredFailure.BadInputsUTxO[E](_)
    )

  /** `validateWrongNetwork` (`Shelley/Rules/Utxo.hs:474-485`, cardano-ledger f649f975): the
    * addresses of `outputs` on another network than `netId`, a bootstrap address's network taken
    * from its attributes (`getNetwork`).
    */
  def validateWrongNetwork[E <: EraTag](netId: Network, outputs: Vector[TxOut[E]])(using
      o: EraTxOut[E]
  ): Test[ShelleyUtxoPredFailure.WrongNetwork[E]] =
    val wrong = outputs
      .map(o.address)
      .filter(a => networkOf(a).exists(_ != wire(netId)))
    val wrongSet = SortedSet.from(wrong)(using addressOrder.toOrdering)
    Test.failureOnJust(NonEmptySet.fromSet(wrongSet))(
      new ShelleyUtxoPredFailure.WrongNetwork[E](netId, _)
    )

  /** `getNetwork` of an address: a Shelley address's header network, a bootstrap address's
    * attributes' network. A bootstrap address the node cannot read has none, as in the flat check.
    */
  private def networkOf(a: Address): Option[Int] = a.kind match
    case Address.Kind.Shelley(n, _, _, _) => Some(n)
    case Address.Kind.Byron(_) =>
      xyz.kd5ujc.scarab.ledger.byron.ByronAddress.decode(a.raw).toOption.map(_.network)

  /** `validateWrongNetworkWithdrawal` (`Shelley/Rules/Utxo.hs:490-501`, cardano-ledger f649f975):
    * the withdrawals from accounts on another network than `netId`. It reports one constructor,
    * which its result type declares, so that Dijkstra's ENTITIES injects it alone
    * ([[ENTITIES.shelleyUtxoToDijkstraEntitiesPredFailure]]).
    */
  def validateWrongNetworkWithdrawal[E <: EraTag, L <: TxLevel](netId: Network, body: TxBody[L, E])(
      using
      b: EraTxBody[E],
      l: b.Level[L]
  ): Test[ShelleyUtxoPredFailure.WrongNetworkWithdrawal[E]] =
    val wrong = b.withdrawals(body).unWithdrawals.keySet.filter(_.networkId != netId)
    Test.failureOnJust(NonEmptySet.fromSet(wrong))(
      new ShelleyUtxoPredFailure.WrongNetworkWithdrawal[E](netId, _)
    )

  /** `validateMaxTxSizeUTxO` (`Shelley/Rules/Utxo.hs:563-577`, cardano-ledger f649f975): the
    * transaction's size (`sizeTxF`) at most `ppMaxTxSize`.
    */
  def validateMaxTxSizeUTxO[E <: EraTag, L <: TxLevel](pp: PParams[E], tx: Tx[L, E])(using
      t: EraTx[E],
      l: t.bodyOf.Level[L],
      p: EraPParams[E]
  ): Test[ShelleyUtxoPredFailure.MaxTxSizeUTxO[E]] =
    val txSize = BigInt(t.sizeTxF(tx))
    val maxTxSize = EraPParams.ppMaxTxSizeL[E].get(pp).value
    Test.failureUnless(
      txSize <= maxTxSize,
      new ShelleyUtxoPredFailure.MaxTxSizeUTxO[E](Mismatch(txSize, maxTxSize))
    )

  /** `validateValueNotConservedUTxO` (`Shelley/Rules/Utxo.hs:503-519`, cardano-ledger f649f975):
    * what the body consumes, the deposits refunded found in the state's accounts, equal to what it
    * produces, the pools registered found in the state's pools. The failure carries what is
    * consumed (supplied) and what is produced (expected).
    */
  def validateValueNotConservedUTxO[E <: EraTag](
      pp: PParams[E],
      utxo: UTxO[E],
      certState: CertState[E],
      txBody: TxBody[TxLevel.Top, E]
  )(using u: EraUTxO[E], c: EraCertState[E]): Test[ShelleyUtxoPredFailure[E]] =
    val accounts = c.certDStateL.get(certState).accounts
    val consumedValue =
      ShelleyUTxO.shelleyConsumed[E, TxLevel.Top](pp, accounts, utxo, txBody)(using
        u,
        c,
        u.txOf.bodyOf.top
      )
    val producedValue = ShelleyUTxO.produced(pp, c.certPStateL.get(certState), txBody)
    Test.failureUnless(
      u.txOf.bodyOf.outputsOf.valueOps.eqv(consumedValue, producedValue),
      ShelleyUtxoPredFailure.ValueNotConservedUTxO(Mismatch(consumedValue, producedValue))
    )

  /** `validateOutputBootAddrAttrsTooBig` (`Shelley/Rules/Utxo.hs:544-558`, cardano-ledger
    * f649f975): the outputs to a bootstrap address whose attributes take more than 64 bytes. It
    * reports Shelley's own failure; each later era's UTXO injects it as its build has it, which the
    * era's rule does when it runs this. A bootstrap address the node cannot read has no attributes,
    * as in the flat check (a stored output's address was read when its output was).
    */
  def validateOutputBootAddrAttrsTooBig[E <: EraTag](outputs: Vector[TxOut[E]])(using
      o: EraTxOut[E]
  ): Test[ShelleyUtxoPredFailure.OutputBootAddrAttrsTooBig[E]] =
    val outputsAttrsTooBig = outputs.filter(out =>
      o.address(out).kind match
        case Address.Kind.Byron(_) =>
          xyz.kd5ujc.scarab.ledger.byron.ByronAddress
            .decode(o.address(out).raw)
            .toOption
            .exists(_.attributesSize > 64)
        case Address.Kind.Shelley(_, _, _, _) => false
    )
    Test.failureOnJust(NonEmptyList.fromList(outputsAttrsTooBig.toList))(
      new ShelleyUtxoPredFailure.OutputBootAddrAttrsTooBig[E](_)
    )

  /** `updateUTxOState` (`Shelley/Rules/Utxo.hs:584-600`, cardano-ledger f649f975): the deposits,
    * then the outputs and the instant stake, then the fee. `depositChangeEvent` and `utxoDiffEvent`
    * are told the change of the deposits and the outputs spent and created.
    */
  def updateUTxOState[F[_]: Monad, E <: EraTag](
      pp: PParams[E],
      txBody: TxBody[TxLevel.Top, E],
      certState: CertState[E],
      depositChangeEvent: Coin => F[Unit],
      utxoDiffEvent: (UTxO[E], UTxO[E]) => F[Unit],
      utxoState: UTxOState[E]
  )(using
      b: EraTxBody[E],
      c: EraCertState[E],
      k: EraStake[E],
      s: StoredTxOut[E],
      raise: Raise[F, UtxoStateDefect]
  ): F[UTxOState[E]] =
    for
      withDeposits <- updateUTxOStateDeposits[F, E](
        pp,
        certState,
        txBody,
        depositChangeEvent,
        utxoState
      )
      withUtxo <- updateUTxOAndInstantStake[F, E, TxLevel.Top](txBody, utxoDiffEvent, withDeposits)(
        using
        summon[Monad[F]],
        b,
        b.top
      )
    yield withUtxo.copy(fees = Coin(withUtxo.fees.value + b.fee(txBody).value))

  /** `updateUTxOAndInstantStake` (`Shelley/Rules/Utxo.hs:602-628`, cardano-ledger f649f975): the
    * body's inputs taken out of the UTxO set and its outputs (`txouts`) put in, the set's own entry
    * kept where both have one (`Map.union` is left-biased), and the instant stake with the outputs
    * created added and then the outputs spent deleted. Each output spent is read from the store
    * into the era.
    */
  def updateUTxOAndInstantStake[F[_]: Monad, E <: EraTag, L <: TxLevel](
      txBody: TxBody[L, E],
      utxoDiffEvent: (UTxO[E], UTxO[E]) => F[Unit],
      utxoState: UTxOState[E]
  )(using
      b: EraTxBody[E],
      l: b.Level[L],
      k: EraStake[E],
      s: StoredTxOut[E],
      raise: Raise[F, UtxoStateDefect]
  ): F[UTxOState[E]] =
    val utxoAdd = UTxOs.txouts[E, L](txBody)
    val (utxoWithout, utxoDel) = extractKeys(utxoState.utxo.utxo, b.inputs[L](txBody))
    for
      deletedUtxo <- readStored[F, E](utxoDel)
      newInstantStake <- vocab[F, xyz.kd5ujc.scarab.ledger.state.InstantStake[E]](
        k.addInstantStake(utxoAdd, utxoState.instantStake)
          .flatMap(k.deleteInstantStake(deletedUtxo, _))
      )
      _ <- utxoDiffEvent(deletedUtxo, utxoAdd)
    yield utxoState.copy(
      utxo = UtxoTables(union(utxoWithout, utxoAdd)),
      instantStake = newInstantStake
    )

  /** `updateUTxOStateDeposits` (`Shelley/Rules/Utxo.hs:630-647`, cardano-ledger f649f975): the
    * deposits the body pays less the refunds it collects, told and added to the deposit pot.
    */
  def updateUTxOStateDeposits[F[_]: Monad, E <: EraTag](
      pp: PParams[E],
      certState: CertState[E],
      txBody: TxBody[TxLevel.Top, E],
      depositChangeEvent: Coin => F[Unit],
      utxoState: UTxOState[E]
  )(using b: EraTxBody[E], c: EraCertState[E]): F[UTxOState[E]] =
    val totalDeposits = c.certsTotalDepositsTxBody(pp, certState, txBody)
    val totalRefunds = c.certsTotalRefundsTxBody[TxLevel.Top](
      pp,
      c.certDStateL.get(certState).accounts,
      txBody
    )(using b, b.top)
    val depositChange = Coin(totalDeposits.value - totalRefunds.value)
    depositChangeEvent(depositChange).as(
      utxoState.copy(deposited = Coin(utxoState.deposited.value + depositChange.value))
    )

  /** `extractKeys` (`Data.Map`): the stored set without `keys`, and its entries at `keys`. */
  private[rules] def extractKeys(
      utxo: xyz.kd5ujc.scarab.ledger.Utxo,
      keys: Iterable[TxIn]
  ): (xyz.kd5ujc.scarab.ledger.Utxo, Map[TxIn, xyz.kd5ujc.scarab.ledger.TxOut]) =
    (utxo -- keys, restrictKeys(utxo, keys))

  /** `restrictKeys` (`Data.Map`): the stored set's entries at `keys`, the second of
    * [[extractKeys]], for a rule that reads them and keeps the set: without the set less `keys`,
    * whose removal adjusts the stake and coin it tracks for each key (#85).
    */
  private[rules] def restrictKeys(
      utxo: xyz.kd5ujc.scarab.ledger.Utxo,
      keys: Iterable[TxIn]
  ): Map[TxIn, xyz.kd5ujc.scarab.ledger.TxOut] =
    keys.iterator.flatMap(in => utxo.get(in).map(in -> _)).toMap

  /** `Map.union` of the stored set and `added`, which is left-biased: the stored set's entry is
    * kept where both have one.
    */
  private[rules] def union[E <: EraTag](
      utxo: xyz.kd5ujc.scarab.ledger.Utxo,
      added: UTxO[E]
  )(using s: StoredTxOut[E]): xyz.kd5ujc.scarab.ledger.Utxo =
    utxo ++ added.unUTxO.toVector.sortBy(_._1).collect {
      case (in, out) if !utxo.contains(in) => in -> s.toStored(out)
    }

  /** The stored outputs `stored`, read into the era: the first, in input order, the era does not
    * read raised. They are read as a value and the result lifted once, not each read a step of
    * the rules' program (#85).
    */
  private[rules] def readStored[F[_], E <: EraTag](
      stored: Map[TxIn, xyz.kd5ujc.scarab.ledger.TxOut]
  )(using F: Monad[F], s: StoredTxOut[E], raise: Raise[F, UtxoStateDefect]): F[UTxO[E]] =
    stored.toVector
      .sortBy(_._1)
      .traverse((in, out) => s.fromStored(out).bimap(UtxoStateDefect.StoredOutput(in, _), in -> _))
      .fold(raise.raise[UtxoStateDefect, UTxO[E]], outs => F.pure(UTxO[E](outs.toMap)))

  /** A change of the instant stake, or its refusal raised. */
  private[rules] def vocab[F[_], A](result: Either[VocabError, A])(using
      F: Monad[F],
      raise: Raise[F, UtxoStateDefect]
  ): F[A] =
    result.fold(e => raise.raise[UtxoStateDefect, A](UtxoStateDefect.Vocab(e)), F.pure)

/** Allegra's predicates of UTXO (`eras/allegra/impl/src/Cardano/Ledger/Allegra/Rules/Utxo.hs`,
  * cardano-ledger f649f975).
  */
object AllegraUtxo:
  /** `shelleyToAllegraUtxoPredFailure` (`Allegra/Rules/Utxo.hs:374-387`, cardano-ledger f649f975,
    * the 11.1 build): each failure as Allegra's own, the time to live passed as the interval it
    * ends, and the bootstrap attributes' failure as `OutputTooBigUTxO`. PPUP's failure is carried
    * over as it is.
    */
  def shelleyToAllegraUtxoPredFailure11_1[E <: EraTag](
      failure: ShelleyUtxoPredFailure[E]
  ): AllegraUtxoPredFailure[E] =
    shelleyToAllegra(failure)(AllegraUtxoPredFailure.OutputTooBigUTxO(_))

  /** `shelleyToAllegraUtxoPredFailure` at cardano-ledger 29a7d656 (the 11.2 build) and 9df9f98c
    * (`Allegra/Rules/Utxo.hs:381-394` at 9df9f98c): the same, but the bootstrap attributes' failure
    * stays itself.
    */
  def shelleyToAllegraUtxoPredFailure11_2[E <: EraTag](
      failure: ShelleyUtxoPredFailure[E]
  ): AllegraUtxoPredFailure[E] =
    shelleyToAllegra(failure)(AllegraUtxoPredFailure.OutputBootAddrAttrsTooBig(_))

  /** Both builds' `shelleyToAllegraUtxoPredFailure`, which differ only in `bootAddrAttrsTooBig`,
    * the bootstrap attributes' failure as Allegra's.
    */
  private def shelleyToAllegra[E <: EraTag](failure: ShelleyUtxoPredFailure[E])(
      bootAddrAttrsTooBig: NonEmptyList[TxOut[E]] => AllegraUtxoPredFailure[E]
  ): AllegraUtxoPredFailure[E] = failure match
    case ShelleyUtxoPredFailure.BadInputsUTxO(ins) => AllegraUtxoPredFailure.BadInputsUTxO(ins)
    case ShelleyUtxoPredFailure.ExpiredUTxO(Mismatch(ttl, current)) =>
      AllegraUtxoPredFailure.OutsideValidityIntervalUTxO(ValidityInterval(None, Some(ttl)), current)
    case ShelleyUtxoPredFailure.MaxTxSizeUTxO(m)    => AllegraUtxoPredFailure.MaxTxSizeUTxO(m)
    case ShelleyUtxoPredFailure.InputSetEmptyUTxO() => AllegraUtxoPredFailure.InputSetEmptyUTxO()
    case ShelleyUtxoPredFailure.FeeTooSmallUTxO(m)  => AllegraUtxoPredFailure.FeeTooSmallUTxO(m)
    case ShelleyUtxoPredFailure.ValueNotConservedUTxO(m) =>
      AllegraUtxoPredFailure.ValueNotConservedUTxO(m)
    case ShelleyUtxoPredFailure.WrongNetwork(n, as) => AllegraUtxoPredFailure.WrongNetwork(n, as)
    case ShelleyUtxoPredFailure.WrongNetworkWithdrawal(n, as) =>
      AllegraUtxoPredFailure.WrongNetworkWithdrawal(n, as)
    case ShelleyUtxoPredFailure.OutputTooSmallUTxO(x) =>
      AllegraUtxoPredFailure.OutputTooSmallUTxO(x)
    case ShelleyUtxoPredFailure.UpdateFailure(x) => AllegraUtxoPredFailure.UpdateFailure(x)
    case ShelleyUtxoPredFailure.OutputBootAddrAttrsTooBig(outs) => bootAddrAttrsTooBig(outs)

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
  ): Test[AllegraUtxoPredFailure.OutsideValidityIntervalUTxO[E]] =
    val vi = b.vldt(body)
    Test.failureUnless(
      vi.invalidBefore.forall(_ <= slot) && vi.invalidHereafter.forall(slot < _),
      new AllegraUtxoPredFailure.OutsideValidityIntervalUTxO[E](vi, slot)
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
  ): Test[AlonzoUtxoPredFailure.WrongNetworkInTxBody[E]] =
    b.networkId(body)
      .fold(Test.pass[AlonzoUtxoPredFailure.WrongNetworkInTxBody[E]])(bid =>
        Test.failureUnless(
          bid == netId,
          new AlonzoUtxoPredFailure.WrongNetworkInTxBody[E](Mismatch(bid, netId))
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
  ): Test[AlonzoUtxoPredFailure.TooManyCollateralInputs[E]] =
    val maxColl = PParams.lensHKD[E].andThen(p.hkdMaxCollateralInputsL[Identity]).get(pp)
    // `fromIntegral . Set.size`: the size narrowed to a `Word16`, so modulo 2^16
    val numColl = Unsigned.wrapping[W16](BigInt(b.collateralInputs(body).size))
    Test.failureUnless(
      numColl.value <= maxColl.value,
      new AlonzoUtxoPredFailure.TooManyCollateralInputs[E](Mismatch(numColl, maxColl))
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
      o: EraTxOut[E]
  ): Test[AlonzoUtxoPredFailure.ScriptsNotPaidUTxO[E]] =
    Test.failureOnJust(
      NonEmptyMap.fromMap(SortedMap.from(utxoCollateral.filterNot((_, out) => vKeyLocked(out))))
    )(new AlonzoUtxoPredFailure.ScriptsNotPaidUTxO[E](_))

  /** `validateInsufficientCollateral` (`Alonzo/Rules/Utxo.hs:335-350`, cardano-ledger f649f975): a
    * hundred times the balance at least the fee times `ppCollateralPercentage`; the failure names
    * the least balance, rounded up.
    */
  def validateInsufficientCollateral[E <: EraTag](
      pp: PParams[E],
      body: TxBody[TxLevel.Top, E],
      bal: BigInt
  )(using
      b: EraTxBody[E],
      p: AlonzoEraPParams[E]
  ): Test[AlonzoUtxoPredFailure.InsufficientCollateral[E]] =
    val txfee = b.fee(body).value
    val collPerc = AlonzoEraPParams.ppCollateralPercentageL[E].get(pp).value
    Test.failureUnless(
      bal * 100 >= collPerc * txfee,
      new AlonzoUtxoPredFailure.InsufficientCollateral[E](
        DeltaCoin(bal),
        Coin(Rational(collPerc * txfee, 100).ceil)
      )
    )

  /** `validateExUnitsTooBigUTxO` (`Alonzo/Rules/Utxo.hs:451-465`, cardano-ledger f649f975;
    * `:464-476` at 9df9f98c): the transaction's units (`getTotalExUnits`, at f649f975 `totExUnits`)
    * pointwise at most `ppMaxTxExUnits`.
    */
  def validateExUnitsTooBigUTxO[E <: EraTag, L <: TxLevel](pp: PParams[E], tx: Tx[L, E])(using
      t: AlonzoEraTx[E],
      l: t.bodyOf.Level[L],
      p: AlonzoEraPParams[E]
  ): Test[AlonzoUtxoPredFailure.ExUnitsTooBigUTxO[E]] =
    val maxTxExUnits = AlonzoEraPParams.ppMaxTxExUnitsL[E].get(pp).units
    val totalExUnits = t.getTotalExUnits[L](tx)
    Test.failureUnless(
      totalExUnits.fits(maxTxExUnits),
      new AlonzoUtxoPredFailure.ExUnitsTooBigUTxO[E](Mismatch(totalExUnits, maxTxExUnits))
    )

  /** `validateOutsideForecast` (`Alonzo/Rules/Utxo.hs:362-385`, cardano-ledger f649f975): the end
    * of the validity interval of a transaction with redeemers translates to a time, through the
    * epoch information `ei` (the era history, from the ledger's system start `sysSt`) extended
    * linearly from the current slot (`unsafeLinearExtendEpochInfo`, cardano-base 060819b5
    * `cardano-slotting/src/Cardano/Slotting/EpochInfo/Extend.hs:23-77`,
    * [[xyz.kd5ujc.scarab.ledger.PosixClock.Extended]]). The extension fails only where the current
    * slot is past `ei`, so an end past the horizon alone passes.
    */
  def validateOutsideForecast[E <: EraTag, L <: TxLevel](
      ei: Horizon,
      slotNo: BigInt,
      sysSt: SystemStart,
      tx: Tx[L, E]
  )(using
      t: AlonzoEraTx[E],
      l: t.bodyOf.Level[L]
  ): Test[AlonzoUtxoPredFailure.OutsideForecast[E]] =
    t.bodyOf.vldt(t.body(tx)).invalidHereafter match
      case Some(ifj) if t.witsOf.rdmrsTxWits(t.wits(tx)).entries.nonEmpty =>
        val extended = PosixClock.Extended(slotNo, PosixClock.Forecast(sysSt, ei))
        Test.failureUnless(
          extended.posix(ifj).isRight,
          new AlonzoUtxoPredFailure.OutsideForecast[E](ifj)
        )
      case _ => Test.pass

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
  ): Test[AlonzoUtxoPredFailure.OutputTooBigUTxO[E]] =
    val maxValSize = AlonzoEraPParams.ppMaxValSizeL[E].get(pp).value
    val outputsTooBig = outputs.foldLeft(List.empty[(Int, Int, TxOut[E])]) { (ans, txOut) =>
      val serSize = s.storedValue(o.value(txOut)).serializedSize
      if BigInt(serSize) > maxValSize then (serSize, maxValSize.toInt, txOut) :: ans
      else ans
    }
    Test.failureOnJust(NonEmptyList.fromList(outputsTooBig))(
      new AlonzoUtxoPredFailure.OutputTooBigUTxO[E](_)
    )

/** The failures of Alonzo's type that Babbage's `validateTotalCollateral` reports, each predicate
  * it runs declaring its own: the injection it asks for need only take these.
  */
type CollateralFailure[E <: EraTag] = AlonzoUtxoPredFailure.ScriptsNotPaidUTxO[E] |
  AlonzoUtxoPredFailure.CollateralContainsNonADA[E] |
  AlonzoUtxoPredFailure.InsufficientCollateral[E] | AlonzoUtxoPredFailure.NoCollateralInputs[E]

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
  )(using p: EraPParams[E]): Test[BabbageUtxoPredFailure[E]] =
    val major = p.ppProtocolVersionL.get(pp).pvMajor.value
    if major > BabbageProtVerHigh && major < 11 then
      Test.failureOnJust(NonEmptyList.fromList(inputs.intersect(refInputs).toList.sorted))(
        BabbageUtxoPredFailure.BabbageNonDisjointRefInputs(_)
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
      alonzo: InjectUtxoFailure[AlonzoUtxoPredFailure[E], E],
      babbage: InjectUtxoFailure[BabbageUtxoPredFailure[E], E]
  ): Test[UtxoPredFailure[E]] =
    val txBody = e.txOf.body[TxLevel.Top](tx)(using e.txOf.bodyOf.top)
    val collateral = b.collateralInputs(txBody)
    val utxoCollateral = u.unUTxO.filter((in, _) => collateral.contains(in))
    val theFee = b.fee(txBody)
    val minFee = e.getMinFeeTxUtxo[TxLevel.Top](pp, tx, u)(using e.txOf.bodyOf.top)
    Test.all(
      Test.failureUnless(
        minFee.value <= theFee.value,
        alonzo.injectFailure(AlonzoUtxoPredFailure.FeeTooSmallUTxO(Mismatch(theFee, minFee)))
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
      alonzo: InjectUtxoFailure[CollateralFailure[E], E],
      babbage: InjectUtxoFailure[BabbageUtxoPredFailure.IncorrectTotalCollateralField[E], E]
  ): Test[UtxoPredFailure[E]] =
    given EraTxOut[E] = b.outputsOf
    val bal = collAdaBalance(txBody, utxoCollateral)
    Test.all(
      injected(AlonzoUtxo.validateScriptsNotPaidUTxO(utxoCollateral)),
      injected(validateCollateralContainsNonADA(txBody, utxoCollateral)),
      injected(AlonzoUtxo.validateInsufficientCollateral(pp, txBody, bal)),
      injected(validateCollateralEqBalance[E](bal, b.totalCollateral(txBody))),
      injected(
        Test.failureUnless(
          utxoCollateral.nonEmpty,
          new AlonzoUtxoPredFailure.NoCollateralInputs[E]()
        )
      )
    )

  /** `validateCollateralContainsNonADA` (`Babbage/Rules/Utxo.hs:253-292`, cardano-ledger f649f975):
    * the collateral ada only, or its assets all sent to the return; the failure names the return's
    * value if the collateral outputs are ada only, else their balance.
    */
  def validateCollateralContainsNonADA[E <: EraTag](
      txBody: TxBody[TxLevel.Top, E],
      utxoCollateral: Map[TxIn, TxOut[E]]
  )(using b: BabbageEraTxBody[E]): Test[AlonzoUtxoPredFailure.CollateralContainsNonADA[E]] =
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
      new AlonzoUtxoPredFailure.CollateralContainsNonADA[E](valueWithNonAda)
    )

  /** `validateCollateralEqBalance` (`Babbage/Rules/Utxo.hs:294-300`, cardano-ledger f649f975): the
    * balance equal to the declared total collateral, if the body declares one.
    */
  def validateCollateralEqBalance[E <: EraTag](
      bal: BigInt,
      txcoll: Option[Coin]
  ): Test[BabbageUtxoPredFailure.IncorrectTotalCollateralField[E]] =
    txcoll.fold(Test.pass[BabbageUtxoPredFailure.IncorrectTotalCollateralField[E]])(tc =>
      Test.failureUnless(
        bal == tc.value,
        new BabbageUtxoPredFailure.IncorrectTotalCollateralField[E](DeltaCoin(bal), tc)
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

  /** `babbageUtxoValidation` (`Babbage/Rules/Utxo.hs:325-412`, cardano-ledger f649f975): UTXO's
    * predicates in the reference's order, every failure accumulated. The tests the reference runs
    * with `runTestOnSignal` run only when the policy validates the signal, and the failures of
    * Shelley's predicates are the era's injection of them and the others' the era's injections
    * (`InjectRuleFailure "UTXO"`). The reference reads the transaction of its signal, the annotated
    * transaction ([[ConwayUtxo.conwayUtxoTransition]]); it is given here.
    */
  def babbageUtxoValidation[E <: EraTag](
      env: UtxoEnv[E],
      utxo: UTxO[E],
      tx: Tx[TxLevel.Top, E],
      context: UtxoContext[E]
  )(using
      e: EraUTxO[E],
      t: AlonzoEraTx[E],
      b: BabbageEraTxBody[E],
      c: EraCertState[E],
      w: AlonzoEraTxWits[E],
      pa: AlonzoEraPParams[E],
      pb: BabbageEraPParams[E],
      s: StoredTxOut[E],
      v: Value[E] =:= MultiAssetValue,
      allegra: InjectUtxoFailure[AllegraUtxoPredFailure[E], E],
      alonzo: InjectUtxoFailure[AlonzoUtxoPredFailure[E], E],
      babbage: InjectUtxoFailure[BabbageUtxoPredFailure[E], E]
  ): Test[UtxoPredFailure[E]] =
    val top = b.top
    val txBody = t.body[TxLevel.Top](tx)(using t.bodyOf.top)
    val allSizedOutputs = b.allSizedOutputs[TxLevel.Top](txBody)(using top)
    val allOutputs = allSizedOutputs.map(_.value)
    given EraTxOut[E] = b.outputsOf
    Test.all(
      injected(
        disjointRefInputs(
          env.pp,
          b.inputs[TxLevel.Top](txBody)(using top),
          b.referenceInputs[TxLevel.Top](txBody)(using top)
        )
      ),
      injected(
        AllegraUtxo.validateOutsideValidityIntervalUTxO[E, TxLevel.Top](env.slot, txBody)(using
          b,
          top
        )
      ),
      injected(
        AlonzoUtxo.validateOutsideForecast[E, TxLevel.Top](
          context.epochInfo,
          env.slot,
          context.systemStart,
          tx
        )(using t, t.bodyOf.top)
      ),
      context.onSignal(
        context.shelley(
          ShelleyUtxo.validateInputSetEmptyUTxO[E, TxLevel.Top](txBody)(using b, top)
        )
      ),
      feesOK(env.pp, tx, utxo),
      context.shelley(ShelleyUtxo.validateBadInputsUTxO(utxo, b.allInputs(txBody))),
      context.shelley(
        ShelleyUtxo.validateValueNotConservedUTxO(env.pp, utxo, env.certState, txBody)
      ),
      injected(validateOutputTooSmallUTxO(env.pp, allSizedOutputs)),
      injected(AlonzoUtxo.validateOutputTooBigUTxO(env.pp, allOutputs)),
      context.onSignal(context.shelley(ShelleyUtxo.validateOutputBootAddrAttrsTooBig(allOutputs))),
      context.onSignal(
        context.shelley(ShelleyUtxo.validateWrongNetwork(context.netId, allOutputs))
      ),
      context.onSignal(
        context.shelley(
          ShelleyUtxo.validateWrongNetworkWithdrawal[E, TxLevel.Top](context.netId, txBody)(using
            b,
            top
          )
        )
      ),
      context.onSignal(
        injected(
          AlonzoUtxo.validateWrongNetworkInTxBody[E, TxLevel.Top](context.netId, txBody)(using
            b,
            top
          )
        )
      ),
      context.onSignal(
        context.shelley(
          ShelleyUtxo.validateMaxTxSizeUTxO[E, TxLevel.Top](env.pp, tx)(using t, t.bodyOf.top)
        )
      ),
      injected(
        AlonzoUtxo.validateExUnitsTooBigUTxO[E, TxLevel.Top](env.pp, tx)(using t, t.bodyOf.top)
      ),
      injected(AlonzoUtxo.validateTooManyCollateralInputs(env.pp, txBody))
    )

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
      v: Value[E] =:= MultiAssetValue
  ): Test[BabbageUtxoPredFailure.BabbageOutputTooSmallUTxO[E]] =
    val outsWithMin = outs.map(out => (out.value, BabbageTxOuts.babbageMinUTxOValue(pp, out)))
    val outputsTooSmall = outsWithMin.filterNot { (out, minSize) =>
      val value = v(o.value(out))
      value.lovelace >= minSize.value && value.assets.valuesIterator.forall(
        _.valuesIterator.forall(_ >= 0)
      )
    }
    Test.failureOnJust(NonEmptyList.fromList(outputsTooSmall.toList))(
      new BabbageUtxoPredFailure.BabbageOutputTooSmallUTxO[E](_)
    )

  /** `collOuts` (`eras/babbage/impl/src/Cardano/Ledger/Babbage/Collateral.hs:43-50`, cardano-ledger
    * f649f975): the collateral return, if the body has one, at [[mkCollateralTxIn]], as
    * [[xyz.kd5ujc.scarab.ledger.TxIn.collOuts]] places it.
    */
  def collOuts[E <: EraTag](txBody: TxBody[TxLevel.Top, E])(using
      b: BabbageEraTxBody[E]
  ): UTxO[E] =
    UTxO(
      TxIn
        .collOuts(
          b.id[TxLevel.Top](txBody)(using b.top),
          b.outputs[TxLevel.Top](txBody)(using b.top).length,
          b.sizedCollateralReturn(txBody).map(_.value)
        )
        .toMap
    )

  /** `mkCollateralTxIn` (`Babbage/Collateral.hs:52-60`, cardano-ledger f649f975): the body's id at
    * the index after its outputs, `maxBound` (65,535) where that index is not a `TxIx`
    * ([[xyz.kd5ujc.scarab.ledger.TxIn.mkCollateralTxIn]]).
    */
  def mkCollateralTxIn[E <: EraTag, L <: TxLevel](txBody: TxBody[L, E])(using
      b: EraTxBody[E],
      l: b.Level[L]
  ): TxIn =
    TxIn.mkCollateralTxIn(b.id[L](txBody), b.outputs[L](txBody).length)

  /** `updateUTxOState` (`Babbage/Rules/Utxo.hs:449-486`, cardano-ledger f649f975): a transaction
    * flagged valid changes the state as Shelley's does, its deposits and outputs told as UTXO's
    * events; one flagged invalid spends its collateral inputs the set holds, creates its collateral
    * return ([[collOuts]]), pays their lovelace less the return's as fees ([[collAdaBalance]]), and
    * changes the instant stake by the return added and then the collateral deleted.
    */
  def updateUTxOState[F[_]: Monad, E <: EraTag](
      pp: PParams[E],
      certState: CertState[E],
      tx: Tx[TxLevel.Top, E],
      utxoState: UTxOState[E]
  )(using
      t: AlonzoEraTx[E],
      b: BabbageEraTxBody[E],
      c: EraCertState[E],
      k: EraStake[E],
      s: StoredTxOut[E],
      events: Tell[F, Chain[AlonzoUtxoEvent[E]]],
      raise: Raise[F, UtxoStateDefect]
  ): F[UTxOState[E]] =
    val txBody = t.body[TxLevel.Top](tx)(using t.bodyOf.top)
    t.isValid(tx) match
      case IsPhase2Valid.Phase2Valid =>
        ShelleyUtxo.updateUTxOState[F, E](
          pp,
          txBody,
          certState,
          depositChange =>
            events.tell(
              Chain.one(
                AlonzoUtxoEvent.TotalDeposits(b.id[TxLevel.Top](txBody)(using b.top), depositChange)
              )
            ),
          (consumed, created) =>
            events.tell(Chain.one(AlonzoUtxoEvent.TxUTxODiff(consumed, created))),
          utxoState
        )
      case IsPhase2Valid.Phase2Invalid =>
        val (utxoKeep, utxoDel) =
          ShelleyUtxo.extractKeys(utxoState.utxo.utxo, b.collateralInputs(txBody))
        val collouts = collOuts(txBody)
        for
          deleted <- ShelleyUtxo.readStored[F, E](utxoDel)
          collateralFees = collAdaBalance(txBody, deleted.unUTxO)
          instantStake <- ShelleyUtxo.vocab[F, xyz.kd5ujc.scarab.ledger.state.InstantStake[E]](
            k.addInstantStake(collouts, utxoState.instantStake)
              .flatMap(k.deleteInstantStake(deleted, _))
          )
        yield utxoState.copy(
          utxo = UtxoTables(ShelleyUtxo.union(utxoKeep, collouts)),
          fees = Coin(utxoState.fees.value + collateralFees),
          instantStake = instantStake
        )

/** Conway's functions of UTXO (`eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Utxo.hs`). */
object ConwayUtxo:
  /** `injectFailure` of `InjectRuleFailure "UTXO" ShelleyUtxoPredFailure ConwayEra`
    * (`Conway/Rules/Utxo.hs:158-161`, cardano-ledger f649f975): `allegraToConwayUtxoPredFailure .
    * shelleyToAllegraUtxoPredFailure`, with Allegra's injection of 11.1
    * ([[AllegraUtxo.shelleyToAllegraUtxoPredFailure11_1]]).
    */
  val injectShelleyFailure11_1: InjectShelleyUtxoFailure[Conway] =
    InjectShelleyUtxoFailure[Conway](f =>
      allegraToConwayUtxoPredFailure[Conway](AllegraUtxo.shelleyToAllegraUtxoPredFailure11_1(f))
    )

  /** The same injection at cardano-ledger 29a7d656 (the 11.2 build) and 9df9f98c (the Leios build)
    * (`Conway/Rules/Utxo.hs:163-166`), with Allegra's injection of those builds
    * ([[AllegraUtxo.shelleyToAllegraUtxoPredFailure11_2]]), through the same
    * `allegraToConwayUtxoPredFailure` (`Conway/Rules/Utxo.hs:424-441` at both).
    */
  val injectShelleyFailure11_2: InjectShelleyUtxoFailure[Conway] =
    InjectShelleyUtxoFailure[Conway](f =>
      allegraToConwayUtxoPredFailure[Conway](AllegraUtxo.shelleyToAllegraUtxoPredFailure11_2(f))
    )

  /** The injection of the build `build`, until the builds are types (`BuildRules`,
    * docs/design/reference-builds-as-types.md, #1063).
    */
  def injectShelleyFailure(build: ReferenceBuild): InjectShelleyUtxoFailure[Conway] =
    build match
      case ReferenceBuild.Release11_1                                => injectShelleyFailure11_1
      case ReferenceBuild.Release11_2 | ReferenceBuild.LeiosB004fe0c => injectShelleyFailure11_2

  /** `babbageToConwayUtxoPredFailure` (`Conway/Rules/Utxo.hs:373-381`, cardano-ledger f649f975). */
  def babbageToConwayUtxoPredFailure[E <: EraTag](
      failure: BabbageUtxoPredFailure[E]
  ): ConwayUtxoPredFailure[E] = failure match
    case BabbageUtxoPredFailure.AlonzoInBabbageUtxoPredFailure(a) =>
      alonzoToConwayUtxoPredFailure(a)
    case BabbageUtxoPredFailure.IncorrectTotalCollateralField(c1, c2) =>
      ConwayUtxoPredFailure.IncorrectTotalCollateralField(c1, c2)
    case BabbageUtxoPredFailure.BabbageOutputTooSmallUTxO(ts) =>
      ConwayUtxoPredFailure.BabbageOutputTooSmallUTxO(ts)
    case BabbageUtxoPredFailure.BabbageNonDisjointRefInputs(ts) =>
      ConwayUtxoPredFailure.BabbageNonDisjointRefInputs(ts)

  /** `alonzoToConwayUtxoPredFailure` (`Conway/Rules/Utxo.hs:383-407`, cardano-ledger f649f975). */
  def alonzoToConwayUtxoPredFailure[E <: EraTag](
      failure: AlonzoUtxoPredFailure[E]
  ): ConwayUtxoPredFailure[E] = failure match
    case AlonzoUtxoPredFailure.BadInputsUTxO(x) => ConwayUtxoPredFailure.BadInputsUTxO(x)
    case AlonzoUtxoPredFailure.OutsideValidityIntervalUTxO(vi, slotNo) =>
      ConwayUtxoPredFailure.OutsideValidityIntervalUTxO(vi, slotNo)
    case AlonzoUtxoPredFailure.MaxTxSizeUTxO(m)    => ConwayUtxoPredFailure.MaxTxSizeUTxO(m)
    case AlonzoUtxoPredFailure.InputSetEmptyUTxO() => ConwayUtxoPredFailure.InputSetEmptyUTxO()
    case AlonzoUtxoPredFailure.FeeTooSmallUTxO(m)  => ConwayUtxoPredFailure.FeeTooSmallUTxO(m)
    case AlonzoUtxoPredFailure.ValueNotConservedUTxO(m) =>
      ConwayUtxoPredFailure.ValueNotConservedUTxO(m)
    case AlonzoUtxoPredFailure.WrongNetwork(x, y) => ConwayUtxoPredFailure.WrongNetwork(x, y)
    case AlonzoUtxoPredFailure.WrongNetworkWithdrawal(x, y) =>
      ConwayUtxoPredFailure.WrongNetworkWithdrawal(x, y)
    case AlonzoUtxoPredFailure.OutputTooSmallUTxO(x) => ConwayUtxoPredFailure.OutputTooSmallUTxO(x)
    case AlonzoUtxoPredFailure.UtxosFailure(x)       => ConwayUtxoPredFailure.UtxosFailure(x)
    case AlonzoUtxoPredFailure.OutputBootAddrAttrsTooBig(xs) =>
      ConwayUtxoPredFailure.OutputBootAddrAttrsTooBig(xs)
    case AlonzoUtxoPredFailure.OutputTooBigUTxO(xs) => ConwayUtxoPredFailure.OutputTooBigUTxO(xs)
    case AlonzoUtxoPredFailure.InsufficientCollateral(c1, c2) =>
      ConwayUtxoPredFailure.InsufficientCollateral(c1, c2)
    case AlonzoUtxoPredFailure.ScriptsNotPaidUTxO(u) => ConwayUtxoPredFailure.ScriptsNotPaidUTxO(u)
    case AlonzoUtxoPredFailure.ExUnitsTooBigUTxO(m)  => ConwayUtxoPredFailure.ExUnitsTooBigUTxO(m)
    case AlonzoUtxoPredFailure.CollateralContainsNonADA(v) =>
      ConwayUtxoPredFailure.CollateralContainsNonADA(v)
    case AlonzoUtxoPredFailure.WrongNetworkInTxBody(m) =>
      ConwayUtxoPredFailure.WrongNetworkInTxBody(m)
    case AlonzoUtxoPredFailure.OutsideForecast(sno) => ConwayUtxoPredFailure.OutsideForecast(sno)
    case AlonzoUtxoPredFailure.TooManyCollateralInputs(m) =>
      ConwayUtxoPredFailure.TooManyCollateralInputs(m)
    case AlonzoUtxoPredFailure.NoCollateralInputs() => ConwayUtxoPredFailure.NoCollateralInputs()

  /** `allegraToConwayUtxoPredFailure` (`Conway/Rules/Utxo.hs:409-426`, cardano-ledger f649f975):
    * PPUP's failure has no value where the era has no PPUP (`absurdEraRule`), which `void`
    * witnesses, and Allegra's outputs too big are given the sizes 0 and 0.
    */
  def allegraToConwayUtxoPredFailure[E <: EraTag](failure: AllegraUtxoPredFailure[E])(using
      void: PpupPredFailure[E] =:= Nothing
  ): ConwayUtxoPredFailure[E] = failure match
    case AllegraUtxoPredFailure.BadInputsUTxO(x) => ConwayUtxoPredFailure.BadInputsUTxO(x)
    case AllegraUtxoPredFailure.OutsideValidityIntervalUTxO(vi, slotNo) =>
      ConwayUtxoPredFailure.OutsideValidityIntervalUTxO(vi, slotNo)
    case AllegraUtxoPredFailure.MaxTxSizeUTxO(m)    => ConwayUtxoPredFailure.MaxTxSizeUTxO(m)
    case AllegraUtxoPredFailure.InputSetEmptyUTxO() => ConwayUtxoPredFailure.InputSetEmptyUTxO()
    case AllegraUtxoPredFailure.FeeTooSmallUTxO(m)  => ConwayUtxoPredFailure.FeeTooSmallUTxO(m)
    case AllegraUtxoPredFailure.ValueNotConservedUTxO(m) =>
      ConwayUtxoPredFailure.ValueNotConservedUTxO(m)
    case AllegraUtxoPredFailure.WrongNetwork(x, y) => ConwayUtxoPredFailure.WrongNetwork(x, y)
    case AllegraUtxoPredFailure.WrongNetworkWithdrawal(x, y) =>
      ConwayUtxoPredFailure.WrongNetworkWithdrawal(x, y)
    case AllegraUtxoPredFailure.OutputTooSmallUTxO(x) =>
      ConwayUtxoPredFailure.OutputTooSmallUTxO(x)
    case AllegraUtxoPredFailure.UpdateFailure(x) => void(x)
    case AllegraUtxoPredFailure.OutputBootAddrAttrsTooBig(xs) =>
      ConwayUtxoPredFailure.OutputBootAddrAttrsTooBig(xs)
    case AllegraUtxoPredFailure.OutputTooBigUTxO(xs) =>
      ConwayUtxoPredFailure.OutputTooBigUTxO(xs.map((0, 0, _)))

  /** `updateTreasuryDonation` (`Conway/Rules/Utxo.hs:200-209`, cardano-ledger f649f975): the
    * donation of a transaction flagged valid added to the state's.
    */
  def updateTreasuryDonation[E <: EraTag](tx: Tx[TxLevel.Top, E], utxos: UTxOState[E])(using
      t: AlonzoEraTx[E],
      b: ConwayEraTxBody[E]
  ): UTxOState[E] =
    t.isValid(tx) match
      case IsPhase2Valid.Phase2Valid =>
        utxos.copy(donation =
          Coin(
            utxos.donation.value + b
              .treasuryDonation[TxLevel.Top](t.body[TxLevel.Top](tx)(using t.bodyOf.top))(using
                b.top
              )
              .value
          )
        )
      case IsPhase2Valid.Phase2Invalid => utxos

  /** `conwayUtxoTransition` (`Conway/Rules/Utxo.hs:211-244`, cardano-ledger f649f975; the same at
    * 29a7d656 and 9df9f98c): UTXO's predicates (`babbageUtxoValidation`), every failure recorded;
    * then UTXOS as a sub-rule (`trans`), its failures and events embedded in UTXO's
    * ([[EmbedUtxosInUtxo]]); then the change of the state (Babbage's `updateUTxOState`) after the
    * donation of a valid transaction (`updateTreasuryDonation`), from the state the transition was
    * given. The state changes whatever was recorded, as the reference's does: a refused
    * transaction's state is the caller's to drop.
    *
    * The reference reads the whole UTxO set; every predicate reads only the outputs of the body's
    * inputs, collateral inputs and reference inputs (`allInputsTxBodyF`), so those are read from
    * the store into the era, which the state keeps in its stored form (design §3.6). A stored
    * output the era does not read, and a change of the instant stake outside the state's
    * vocabulary, are raised ([[UtxoStateDefect]]), as is a script the evaluator gives no verdict
    * for ([[ScriptVerdictUnavailable]]): none is a verdict of the rules.
    */
  def conwayUtxoTransition[F[_]: Monad, E <: EraTag](
      env: UtxoEnv[E],
      stAnnTx: StAnnTx[TxLevel.Top, E],
      context: UtxoContext[E]
  )(using
      e: EraUTxO[E],
      t: AlonzoEraTx[E],
      b: ConwayEraTxBody[E],
      c: EraCertState[E],
      w: AlonzoEraTxWits[E],
      pa: AlonzoEraPParams[E],
      pb: BabbageEraPParams[E],
      k: EraStake[E],
      s: StoredTxOut[E],
      v: Value[E] =:= MultiAssetValue,
      u: AlonzoEraUTxO[E],
      allegra: InjectUtxoFailure[AllegraUtxoPredFailure[E], E],
      alonzo: InjectUtxoFailure[AlonzoUtxoPredFailure[E], E],
      babbage: InjectUtxoFailure[BabbageUtxoPredFailure[E], E],
      utxos: InjectAlonzoUtxos[E],
      embed: EmbedUtxosInUtxo[E],
      state: Stateful[F, UTxOState[E]],
      judge: Judge[F, UtxoPredFailure[E]],
      events: Tell[F, Chain[AlonzoUtxoEvent[E]]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E],
      defect: Raise[F, UtxoStateDefect]
  ): F[Unit] =
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)
    val txBody = t.body[TxLevel.Top](tx)(using t.bodyOf.top)
    given Judge[F, UtxosPredFailure[E]] = judge.contramap(embed.wrapFailed)
    given Tell[F, Chain[UtxosEvent[E]]] = new Tell[F, Chain[UtxosEvent[E]]]:
      val functor: cats.Functor[F] = events.functor
      def tell(l: Chain[UtxosEvent[E]]): F[Unit] = events.tell(l.map(embed.wrapEvent))
    for
      utxoState <- state.get
      utxo <- ShelleyUtxo.readStored[F, E](
        ShelleyUtxo.restrictKeys(utxoState.utxo.utxo, b.allInputs(txBody))
      )
      _ <- Sts.runTest[F, UtxoPredFailure[E]](
        BabbageUtxo.babbageUtxoValidation[E](env, utxo, tx, context)
      )
      _ <- judge.nest(ConwayUtxos.utxosTransition[F, E](stAnnTx, context.validation))
      next <- BabbageUtxo.updateUTxOState[F, E](
        env.pp,
        env.certState,
        tx,
        updateTreasuryDonation(tx, utxoState)
      )
      _ <- state.set(next)
    yield ()
