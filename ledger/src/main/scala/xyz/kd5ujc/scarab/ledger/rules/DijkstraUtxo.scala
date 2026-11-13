// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Block,
  Certificate,
  LedgerFailure,
  PlutusStats,
  PredicateFailure,
  ProtocolParams,
  Tx,
  TxEnv,
  TxChecks,
  TxBody,
  TxIn,
  TxOut,
  UtxoFailure,
  UtxowFailure,
  Utxo as NodeUtxo,
  Value
}
import xyz.kd5ujc.scarab.ledger.dijkstra.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.Era

/** `UtxoEnv` of Dijkstra (`Dijkstra/Rules/Utxo.hs:101-107`, cardano-ledger 9df9f98c): the slot and
  * the clock (`ctx`), the parameters, the pool state after the sub-transactions, and the
  * certificate state and the UTxO set before the transaction, which every lookup of UTXOW and UTXO
  * reads (`Ledger.hs:397-400`).
  */
final case class DijkstraUtxoEnv(
    ctx: TxEnv,
    pp: ProtocolParams,
    pState: PState,
    original: ConwayCertState,
    originalUtxo: NodeUtxo
)

/** A Dijkstra transaction with its Conway form: the transaction the node's Conway checks read,
  * decoded from the same original bytes (body, witnesses, auxiliary data; the id is the body's),
  * and what they resolve from the UTxO set before it ([[StAnnTx]]).
  *
  * Only a transaction whose Dijkstra parts are all empty has one. Its Conway form then means what
  * the Dijkstra one does: Dijkstra's body adds the guards (key 14, in place of the required
  * signers), the sub-transactions, the direct deposits and the balance intervals
  * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxBody.hs`), and its scripts needed and
  * provided are Conway's but for the guards and the sub-transactions (`getDijkstraScriptsNeeded`,
  * `getDijkstraScriptsProvided`, `Dijkstra/UTxO.hs`). Any of those, a Plutus script, a redeemer or
  * a datum (whose contexts are Dijkstra's), or a part the Conway decoder of the same bytes refuses
  * (a PlutusV4 script, a Dijkstra native script) leaves it without one ([[DijkstraPending]]).
  */
final case class DijkstraStAnnTx(tx: DijkstraTx, conway: StAnnTx)

object DijkstraStAnnTx:
  def of(tx: DijkstraTx, utxo: NodeUtxo): Either[DijkstraPending, DijkstraStAnnTx] =
    val b = tx.body
    val w = tx.witnesses
    def plutus = w.scripts.exists {
      case DijkstraScript.Plutus(_, _) => true
      case DijkstraScript.Native(_)    => false
    }
    for
      _ <- Either.cond(b.subTransactions.isEmpty, (), DijkstraPending.SubLedgers)
      _ <- Either.cond(
        b.guards.isEmpty && b.requiredTopLevelGuards.isEmpty,
        (),
        DijkstraPending.Guards
      )
      _ <- Either.cond(
        b.directDeposits.isEmpty && b.accountBalanceIntervals.isEmpty &&
          b.startingAccountBalanceIntervals.isEmpty,
        (),
        DijkstraPending.AccountFields
      )
      _ <- Either.cond(
        w.redeemers.isEmpty && w.datums.isEmpty && !plutus,
        (),
        DijkstraPending.Phase2
      )
      conway <- Tx
        .fromBlock(
          Block.Transaction(b.raw, w.raw, tx.auxiliary.map(_.raw), tx.isValid),
          Era.Conway
        )
        .leftMap(DijkstraPending.ConwayForm(_))
      ann = StAnnTx(conway, utxo)
      // a needed Plutus script, of an input or a reference script, whatever the witnesses hold
      _ <- Either.cond(ann.plutusPurposes.isEmpty, (), DijkstraPending.Phase2)
    yield DijkstraStAnnTx(tx, ann)

/** The Conway predicate failures of the reused UTXO and UTXOW checks as Dijkstra's, one to one
  * (`conwayToDijkstraUtxoPredFailure`, `Dijkstra/Rules/Utxo.hs:560-587`;
  * `conwayToDijkstraUtxowPredFailure`, `Utxow.hs:436-459`). The cases the reference maps to `error
  * "Impossible"` (a withdrawal on the wrong network, which is ENTITIES', and Alonzo's
  * `OutputTooSmallUTxO`) have none, and the redeemer failures none in a transaction without
  * redeemers or Plutus scripts.
  */
private[rules] object DijkstraFromConway:
  def utxo: PartialFunction[UtxoFailure, DijkstraUtxoFailure] = {
    case UtxoFailure.Utxos(f)       => DijkstraUtxoFailure.UtxosFailure(f)
    case UtxoFailure.BadInputs(ins) => DijkstraUtxoFailure.BadInputs(ins)
    case UtxoFailure.OutsideValidityInterval(a, b, s) =>
      DijkstraUtxoFailure.OutsideValidityInterval(a, b, s)
    case UtxoFailure.MaxTxSize(m)         => DijkstraUtxoFailure.MaxTxSize(m)
    case UtxoFailure.InputSetEmpty        => DijkstraUtxoFailure.InputSetEmpty
    case UtxoFailure.FeeTooSmall(m)       => DijkstraUtxoFailure.FeeTooSmall(m)
    case UtxoFailure.ValueNotConserved(m) => DijkstraUtxoFailure.ValueNotConserved(m)
    case UtxoFailure.WrongNetwork(n, as)  => DijkstraUtxoFailure.WrongNetwork(n, as)
    case UtxoFailure.OutputBootAddrAttrsTooBig(os) =>
      DijkstraUtxoFailure.OutputBootAddrAttrsTooBig(os)
    case UtxoFailure.OutputTooBig(os) => DijkstraUtxoFailure.OutputTooBig(os)
    case UtxoFailure.InsufficientCollateral(b, r) =>
      DijkstraUtxoFailure.InsufficientCollateral(b, r)
    case UtxoFailure.ScriptsNotPaid(os)          => DijkstraUtxoFailure.ScriptsNotPaid(os)
    case UtxoFailure.ExUnitsTooBig(m)            => DijkstraUtxoFailure.ExUnitsTooBig(m)
    case UtxoFailure.CollateralContainsNonAda(v) => DijkstraUtxoFailure.CollateralContainsNonAda(v)
    case UtxoFailure.WrongNetworkInTxBody(m)     => DijkstraUtxoFailure.WrongNetworkInTxBody(m)
    case UtxoFailure.OutsideForecast(s)          => DijkstraUtxoFailure.OutsideForecast(s)
    case UtxoFailure.TooManyCollateralInputs(m)  => DijkstraUtxoFailure.TooManyCollateralInputs(m)
    case UtxoFailure.NoCollateralInputs          => DijkstraUtxoFailure.NoCollateralInputs
    case UtxoFailure.IncorrectTotalCollateralField(p, d) =>
      DijkstraUtxoFailure.IncorrectTotalCollateralField(p, d)
    case UtxoFailure.BabbageOutputTooSmall(os) => DijkstraUtxoFailure.BabbageOutputTooSmall(os)
    case UtxoFailure.BabbageNonDisjointRefInputs(ins) =>
      DijkstraUtxoFailure.BabbageNonDisjointRefInputs(ins)
  }

  def utxow: PartialFunction[UtxowFailure, DijkstraUtxowFailure] = {
    case UtxowFailure.Utxo(f) if utxo.isDefinedAt(f) => DijkstraUtxowFailure.UtxoFailure(utxo(f))
    case UtxowFailure.InvalidWitnesses(ks)           => DijkstraUtxowFailure.InvalidWitnesses(ks)
    case UtxowFailure.MissingVKeyWitnesses(hs)   => DijkstraUtxowFailure.MissingVKeyWitnesses(hs)
    case UtxowFailure.MissingScriptWitnesses(hs) => DijkstraUtxowFailure.MissingScriptWitnesses(hs)
    case UtxowFailure.ScriptWitnessNotValidating(hs) =>
      DijkstraUtxowFailure.ScriptWitnessNotValidating(hs)
    case UtxowFailure.MissingTxBodyMetadataHash(h) =>
      DijkstraUtxowFailure.MissingTxBodyMetadataHash(h)
    case UtxowFailure.MissingTxMetadata(h)       => DijkstraUtxowFailure.MissingTxMetadata(h)
    case UtxowFailure.ConflictingMetadataHash(m) => DijkstraUtxowFailure.ConflictingMetadataHash(m)
    case UtxowFailure.InvalidMetadata            => DijkstraUtxowFailure.InvalidMetadata
    case UtxowFailure.ExtraneousScriptWitnesses(hs) =>
      DijkstraUtxowFailure.ExtraneousScriptWitnesses(hs)
    case UtxowFailure.MissingRequiredDatums(m, r) =>
      DijkstraUtxowFailure.MissingRequiredDatums(m, r)
    case UtxowFailure.NotAllowedSupplementalDatums(u, a) =>
      DijkstraUtxowFailure.NotAllowedSupplementalDatums(u, a)
    case UtxowFailure.PPViewHashesDontMatch(m) => DijkstraUtxowFailure.PPViewHashesDontMatch(m)
    case UtxowFailure.ScriptIntegrityHashMismatch(m, e) =>
      DijkstraUtxowFailure.ScriptIntegrityHashMismatch(m, e)
    case UtxowFailure.UnspendableUTxONoDatumHash(ins) =>
      DijkstraUtxowFailure.UnspendableUTxONoDatumHash(ins)
    case UtxowFailure.MalformedScriptWitnesses(hs) =>
      DijkstraUtxowFailure.MalformedScriptWitnesses(hs)
    case UtxowFailure.MalformedReferenceScripts(hs) =>
      DijkstraUtxowFailure.MalformedReferenceScripts(hs)
  }

  val ofUtxo: PartialFunction[PredicateFailure, DijkstraUtxoFailure] = {
    case PredicateFailure.Utxow(UtxowFailure.Utxo(f)) if utxo.isDefinedAt(f) => utxo(f)
  }

  val ofUtxow: PartialFunction[PredicateFailure, DijkstraUtxowFailure] = {
    case PredicateFailure.Utxow(f) if utxow.isDefinedAt(f) => utxow(f)
  }

/** UTXO of Dijkstra (`Dijkstra/Rules/Utxo.hs:326-425`, `dijkstraUtxoTransition`, cardano-ledger
  * 9df9f98c) of a transaction with its Conway form ([[DijkstraStAnnTx]]). The checks are Conway's
  * predicates (`UtxoTests`), in Dijkstra's order and selection, against the UTxO set before the
  * transaction (`ueOriginalUtxo`):
  *
  *   - the validity interval; the forecast of the interval's end, which concerns a transaction with
  *     redeemers only (`Alonzo.validateOutsideForecast`, `Alonzo/Rules/Utxo.hs:390-398`), so none
  *     here; an empty input set (on the signal);
  *   - the spent, reference and collateral inputs against the set before the transaction, then the
  *     spent inputs against the current set (`Utxo.hs:350-354`), two checks that both report an
  *     input neither holds;
  *   - the fee: `getConwayMinFeeTxUtxo` (`Conway/UTxO.hs:157-158`, `Conway/Tx.hs:113-146`), the
  *     reference scripts priced with the stride and multiplier of the parameters;
  *   - the collateral, only for a batch with redeemers (`validateBatchCollateral`), so none here;
  *   - for a phase-2-valid transaction only, the value consumed against the value produced
  *     (`Utxo.hs:369-387`; the legacy-mode check concerns Plutus up to V3, so none here);
  *   - the outputs and the collateral return: their minimum, their size, their bootstrap attributes
  *     and their network (the last two on the signal); the network of the body (on the signal); no
  *     pointer in the collateral return (`validateNoPtrInCollateralReturn`, `Utxo.hs:245-256`, on
  *     the signal); the size of the transaction (`runTest`, not on the signal as in Conway); its
  *     execution units; the number of collateral inputs.
  *
  * Conway's withdrawal network (ENTITIES' in Dijkstra) and disjoint reference inputs are not
  * checked. Then UTXOS, Conway's (`Utxos.hs`), and the state changed (`Babbage.updateUTxOState`
  * with `Conway.updateTreasuryDonation`, `Utxo.hs:421-425`) from the certificate state before the
  * transaction.
  */
object DijkstraUtxo
    extends JudgedSts[DijkstraUtxoEnv, Counted[
      UTxOState[Dijkstra]
    ], DijkstraStAnnTx, DijkstraUtxoFailure, DijkstraHalt]:
  /** `tierRefScriptFee` (`Conway/Tx.hs:126-146`): `base` per byte, the price multiplied by
    * `multiplier` for every `stride` bytes. The stride is a `NonZero Word32` of the parameters
    * (`ppRefScriptCostStrideG`), so at least 1.
    */
  def tierRefScriptFee(multiplier: Rational, stride: BigInt, base: Rational, size: Long): BigInt =
    val step = stride.max(1)
    @annotation.tailrec
    def go(acc: Rational, price: Rational, n: BigInt): BigInt =
      if n < step then (acc + Rational(n) * price).floor
      else go(acc + Rational(step) * price, multiplier * price, n - step)
    go(Rational.zero, base, BigInt(size))

  /** `getConwayMinFeeTxUtxo` of a transaction against the UTxO set before it. */
  def minFee(pp: ProtocolParams, ann: DijkstraStAnnTx, utxo: NodeUtxo): BigInt =
    val tx = ann.conway.tx
    val units = tx.witnesses.totalExUnits
    val scriptFee =
      (pp.priceMem * Rational(units.mem) + pp.priceSteps * Rational(units.steps)).ceil
    pp.minFeeA * tx.size + pp.minFeeB + scriptFee +
      tierRefScriptFee(
        pp.refScriptCostMultiplier,
        pp.refScriptCostStride,
        pp.minFeeRefScriptCostPerByte,
        DijkstraTxMeasures.refScriptsSize(DijkstraTxMeasures.inUtxo(utxo), ann.tx.body)
      )

  /** `dijkstraTotalRefundsTxCerts` (`Dijkstra/TxCert.hs:262-271`): the refunds the certificates
    * declare, which Dijkstra's consumed value and deposit change count (`getTotalRefundsTxCerts _
    * _`, `TxCert.hs:319`), not the deposits the state holds; DELEG and GOVCERT check them. A
    * Dijkstra deregistration always declares one (tags 0 and 1 are refused).
    */
  def declaredRefunds(body: TxBody): BigInt =
    body.certificates.iterator.map {
      case Certificate.Unregister(_, refund)     => refund.getOrElse(BigInt(0))
      case Certificate.UnregisterDRep(_, refund) => refund
      case _                                     => BigInt(0)
    }.sum

  private def pointer(out: TxOut): Boolean = out.address.kind match
    case Address.Kind.Shelley(_, _, _, Some(_)) => true
    case _                                      => false

  /** `validateNoPtrInCollateralReturn` (`Utxo.hs:245-256`): a collateral return to a pointer
    * address.
    */
  def pointerInCollateralReturn(tx: Tx): Test[DijkstraUtxoFailure] =
    Test.failureOnJust(tx.body.collateralReturn.filter(pointer))(
      DijkstraUtxoFailure.PtrPresentInCollateralReturn(_)
    )

  /** The checks before UTXOS, in the reference's order: Conway's predicates with their failures
    * taken into Dijkstra's, or the halt of a failure that has no Dijkstra case.
    */
  def checks(
      env: DijkstraUtxoEnv,
      ann: DijkstraStAnnTx,
      current: NodeUtxo,
      deposits: BigInt,
      refunds: BigInt
  ): Vector[Either[DijkstraHalt, Test[DijkstraUtxoFailure]]] =
    def conway(t: Test[PredicateFailure]) = DijkstraSts.taken(DijkstraFromConway.ofUtxo)(t)
    val tx = ann.conway.tx
    val body = tx.body
    val pp = env.pp
    val ctx = env.ctx
    val original = env.originalUtxo
    val outs = body.outputs ++ body.collateralReturn.toVector
    val withdrawn = body.withdrawals.valuesIterator.map(_.value).sum
    // `dijkstraConsumed` and `dijkstraProducedValue` (`Dijkstra/UTxO.hs:71-146`): the minted
    // assets consumed, the burned produced (`getConsumedMaryValue`, `burnedMultiAssets`)
    val consumed = body.inputs.distinct.flatMap(original.get).foldLeft(Value.zero)(_ + _.value) +
      Value.coin(withdrawn + refunds) + Value.minted(body.mint)
    val produced = body.outputs.foldLeft(Value.zero)(_ + _.value) +
      Value.coin(body.fee + deposits + body.donation) + Value.burned(body.mint)
    val static = ctx.validation == TxEnv.Validation.All
    Vector(
      conway(UtxoTests.validityInterval(body, ctx.slot)),
      conway(ctx.static(UtxoTests.inputSetEmpty(body))),
      conway(
        UtxoTests.badInputs(
          original,
          (body.inputs ++ body.referenceInputs ++ body.collateral).distinct
        )
      ),
      conway(UtxoTests.badInputs(current, body.inputs.distinct)),
      conway(UtxoTests.feeTooSmall(body, minFee(pp, ann, original))),
      conway(if tx.isValid then UtxoTests.valueNotConserved(consumed, produced) else Test.pass),
      conway(UtxoTests.outputTooSmall[Dijkstra](pp, outs)),
      conway(UtxoTests.outputTooBig[Dijkstra](pp, outs)),
      conway(ctx.static(UtxoTests.bootAddrAttrsTooBig(outs))),
      conway(ctx.static(UtxoTests.wrongNetwork(ctx.network, outs))),
      conway(ctx.static(UtxoTests.wrongNetworkInTxBody(ctx.network, body))),
      Right(if static then pointerInCollateralReturn(tx) else Test.pass),
      conway(UtxoTests.maxTxSize(pp, tx)),
      conway(UtxoTests.exUnitsTooBig(pp, tx)),
      conway(UtxoTests.tooManyCollateralInputs(pp, body))
    )

  /** `updateUTxOState` (`Babbage/Rules/Utxo.hs`) and `updateTreasuryDonation`
    * (`Conway/Rules/Utxo.hs`): a valid transaction's inputs spent, its outputs created, the
    * deposits, fees and donation counted; an invalid one's collateral collected.
    */
  def update(
      s: UTxOState[Dijkstra],
      ann: DijkstraStAnnTx,
      collateral: TxChecks.Collateral,
      deposits: BigInt,
      refunds: BigInt
  ): Either[VocabError, UTxOState[Dijkstra]] =
    val tx = ann.conway.tx
    val body = tx.body
    val id = body.id
    val utxo = s.utxo.utxo
    if tx.isValid then
      val created = body.outputs.zipWithIndex.map((out, i) => TxIn(id, i) -> out)
      InstantStakeOps
        .update(s.instantStake, body.outputs, body.inputs.distinct.sorted.flatMap(utxo.get))
        .map(stake =>
          s.copy[Dijkstra](
            utxo = UtxoTables(utxo -- body.inputs ++ created),
            deposited = Coin(s.deposited.value + deposits - refunds),
            fees = Coin(s.fees.value + body.fee),
            instantStake = stake,
            donation = Coin(s.donation.value + body.donation)
          )
        )
    else
      val returned = body.collateralReturn.map(r => TxIn(id, body.outputs.size) -> r)
      InstantStakeOps
        .update(s.instantStake, returned.map(_._2), collateral.outputs)
        .map(stake =>
          s.copy[Dijkstra](
            utxo = UtxoTables(utxo -- body.collateral ++ returned),
            fees = Coin(s.fees.value + collateral.fee),
            instantStake = stake
          )
        )

  def transition[F[_]: Monad](env: DijkstraUtxoEnv, ann: DijkstraStAnnTx)(using
      ask: Ask[F, Globals],
      st: Stateful[F, Counted[UTxOState[Dijkstra]]],
      raise: Raise[F, DijkstraHalt],
      judge: Judge[F, DijkstraUtxoFailure]
  ): F[Unit] =
    val tx = ann.conway.tx
    val (deposits, _) = Deposits.of(env.pp, env.original, tx.body)
    val refunds = declaredRefunds(tx.body)
    val collateral = TxChecks.collateral[Dijkstra](env.originalUtxo, tx)
    for
      before <- st.get
      _ <- DijkstraSts.record[F, DijkstraUtxoFailure](
        checks(env, ann, before.state.utxo.utxo, deposits, refunds)
      )
      _ <- DijkstraSts.conway[F, UtxoEnv[Any], PlutusStats, Counted[
        UTxOState[Dijkstra]
      ], StAnnTx, DijkstraUtxoFailure](
        Utxos.sts[Dijkstra],
        UtxoEnv(env.ctx, env.pp, env.original),
        ann.conway,
        Counted.plutus[UTxOState[Dijkstra]],
        DijkstraFromConway.ofUtxo
      )
      s <- st.get
      next <- Sts.lift[F, DijkstraHalt, UTxOState[Dijkstra]](
        update(s.state, ann, collateral, deposits, refunds).leftMap(e =>
          DijkstraHalt.Conway(LedgerFailure.Vocab(e))
        )
      )
      _ <- st.set(s.copy(state = next))
    yield ()

/** UTXOW of Dijkstra (`Dijkstra/Rules/Utxow.hs:235-325`, `dijkstraUtxowTransition`) of a
  * transaction with its Conway form: Babbage's checks as Conway runs them (`Utxow.checks`), which
  * are Dijkstra's in its order (the failing native scripts, the missing and extraneous scripts
  * beside the reference scripts, the datums, the redeemers, the signatures, the needed witnesses,
  * the auxiliary data, the well-formed scripts, the script integrity hash), every lookup against
  * the UTxO set before the transaction; the guards' checks (`Utxow.hs:313-321`), which a
  * transaction without guards passes; then UTXO. A transaction without a Conway form halts
  * ([[DijkstraStAnnTx.of]]).
  */
object DijkstraUtxow
    extends JudgedSts[DijkstraUtxoEnv, Counted[
      UTxOState[Dijkstra]
    ], DijkstraTx, DijkstraUtxowFailure, DijkstraHalt]:
  def transition[F[_]: Monad](env: DijkstraUtxoEnv, tx: DijkstraTx)(using
      ask: Ask[F, Globals],
      st: Stateful[F, Counted[UTxOState[Dijkstra]]],
      raise: Raise[F, DijkstraHalt],
      judge: Judge[F, DijkstraUtxowFailure]
  ): F[Unit] =
    DijkstraStAnnTx
      .of(tx, env.originalUtxo)
      .fold(
        DijkstraSts.halt[F, Unit](_),
        ann =>
          for
            _ <- DijkstraSts.record[F, DijkstraUtxowFailure](
              Utxow
                .checks[Dijkstra](
                  UtxoEnv(env.ctx, env.pp, env.original),
                  Map.empty,
                  ann.conway,
                  Test.pass
                )
                .map(
                  _.leftMap(d => DijkstraHalt.Conway(d: LedgerFailure))
                    .flatMap(DijkstraSts.taken(DijkstraFromConway.ofUtxow))
                )
            )
            _ <- DijkstraSts.trans[F, DijkstraUtxoEnv, Counted[UTxOState[Dijkstra]], Counted[
              UTxOState[Dijkstra]
            ], DijkstraStAnnTx, DijkstraUtxoFailure, DijkstraUtxowFailure](
              DijkstraUtxo,
              env,
              ann,
              DijkstraSts.same,
              DijkstraUtxowFailure.UtxoFailure(_)
            )
          yield ()
      )
