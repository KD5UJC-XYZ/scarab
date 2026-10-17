// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import scala.annotation.unused
import xyz.kd5ujc.scarab.codec.Rational
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.GenDelegPair

/** UTXOW, UTXO, UTXOS and DELEGS for Shelley, Allegra, Mary, Alonzo and Babbage. Era behaviour is
  * selected by the protocol major version of the parameters in force: Allegra 3, Mary 4, Alonzo 5,
  * Babbage 7. Plutus scripts are evaluated (see [[Phase2]]) and the block's `is_valid` flag must
  * equal the verdict: a transaction flagged `true` is applied, one flagged `false` consumes its
  * collateral, and a flag that disagrees with the evaluation is a validation error.
  */
object PreConwayRules:
  import TxRules.{Env, Result}

  /** A redeemer pointer: tag 0 spend, 1 mint, 2 certificate, 3 reward, then index. */
  private[ledger] type Pointer = (Int, BigInt)

  def apply(state: LedgerState, tx: Tx, env: Env): Result[LedgerState] =
    val body = tx.body
    val id = body.id
    val pp = state.params
    val pv = pp.protocolMajor
    val alonzo = pv >= 5
    def check(ok: Boolean, failure: => UtxoFailure): Result[Unit] =
      if ok then Right(()) else Left(LedgerFailure.Rejected(id, PredicateFailure.utxo(failure)))
    def checkW(ok: Boolean, failure: => UtxowFailure): Result[Unit] =
      if ok then Right(()) else Left(LedgerFailure.Rejected(id, PredicateFailure.utxow(failure)))

    val wits = tx.witnesses
    val vkeyHashes: Vector[Bytes] = wits.vkeys.map(w => env.verdicts.keyHash(w._1))
    val scriptFee =
      if alonzo then
        val u = wits.totalExUnits
        (pp.priceMem * Rational(u.mem) + pp.priceSteps * Rational(u.steps)).ceil
      else BigInt(0)
    val minFee = pp.minFeeA * tx.size + pp.minFeeB + scriptFee

    // Deposits and refunds depend on the state before the transaction, not on its own order.
    val newPools = body.certificates.collect {
      case Certificate.RegisterPool(p) if !state.pools.contains(p.operator) => p.operator
    }.toSet
    val deposits = pp.keyDeposit * body.certificates.count {
      case Certificate.Register(_, _) => true
      case _                          => false
    } + pp.poolDeposit * newPools.size
    val refunds = pp.keyDeposit * body.certificates.count {
      case Certificate.Unregister(_, _) => true
      case _                            => false
    }

    // `BadInputsUTxO` names every input, and reference input, that is not in the UTxO set
    val missing = (body.inputs ++ body.referenceInputs).filterNot(state.utxo.contains).toSet
    for
      _ <- Either.cond(
        !body.hasGovernance && body.donation == 0,
        (),
        LedgerFailure.Undecodable(id, TxDefect.GovernanceFieldsBeforeConway)
      )
      _ <- check(body.inputs.nonEmpty, UtxoFailure.InputSetEmpty)
      _ <- check(missing.isEmpty, UtxoFailure.BadInputs(missing))
      spent = body.inputs.toVector.flatMap(state.utxo.get)
      refOuts = body.referenceInputs.flatMap(state.utxo.get)
      // Shelley's time to live is the last slot the transaction is valid in (`validateTimeToLive`,
      // cardano-ledger Shelley/Rules/Utxo.hs lines 414-423, Shelley era only); from Allegra the
      // validity interval's upper bound is exclusive (`inInterval`).
      _ <-
        if pv <= 2 then
          check(
            body.ttl.forall(env.slot <= _),
            UtxoFailure.ExpiredUTxO(Mismatch(body.ttl.getOrElse(env.slot), env.slot))
          )
        else
          check(
            body.ttl.forall(env.slot < _) && body.validityStart.forall(_ <= env.slot),
            UtxoFailure.OutsideValidityInterval(body.validityStart, body.ttl, env.slot)
          )
      _ <- check(
        tx.size <= pp.maxTxSize,
        UtxoFailure.MaxTxSize(Mismatch(BigInt(tx.size), pp.maxTxSize))
      )
      _ <- check(body.fee >= minFee, UtxoFailure.FeeTooSmall(Mismatch(body.fee, minFee)))
      _ <- check(
        body.networkId.forall(_ == env.network),
        UtxoFailure.WrongNetworkInTxBody(Mismatch(body.networkId.fold(0)(_.toInt), env.network))
      )
      _ <- outputChecks(pp, env, id, body.outputs ++ body.collateralReturn.toVector)
      _ <- check(
        !alonzo || wits.totalExUnits.fits(pp.maxTxExUnits),
        UtxoFailure.ExUnitsTooBig(Mismatch(wits.totalExUnits, pp.maxTxExUnits))
      )
      collateral <- collateralChecks(state, tx, env)
      scripts = providedScripts(wits, spent ++ refOuts)
      needed = scriptsNeeded(body, spent)
      _ <- checkW(
        needed.keySet.subsetOf(scripts.keySet),
        UtxowFailure.MissingScriptWitnesses(needed.keySet -- scripts.keySet)
      )
      extraneous = wits.scripts.map(_.hash).toSet -- needed.keySet
      _ <- checkW(extraneous.isEmpty, UtxowFailure.ExtraneousScriptWitnesses(extraneous))
      plutusPurposes = needed
        .collect { case (h, ps) if scripts(h).kind.isPlutus => ps }
        .flatten
        .toSet
      _ <- alonzoChecks(state, tx, spent, refOuts, scripts, plutusPurposes)
      _ <- check(
        tx.isValid || plutusPurposes.nonEmpty,
        UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(false, None))
      )
      _ <- auxiliaryChecks(tx)
      _ <- witnessChecks(state, tx, spent, collateral, scripts, needed, vkeyHashes, env.verdicts)
      // `validateMIRInsufficientGenesisSigs` (`Shelley/Rules/Utxow.hs:449`): a MIR certificate needs
      // the quorum of the genesis delegates' signatures
      genesisSigners = state.genDelegs.values.map(_.delegate).toSet.intersect(vkeyHashes.toSet)
      _ <- checkW(
        !body.certificates.exists {
          case Certificate.MoveRewards(_, _) => true
          case _                             => false
        } || genesisSigners.size >= env.updateQuorum,
        UtxowFailure.MIRInsufficientGenesisSigs(genesisSigners)
      )
      _ <- scriptsWellFormed(state, tx, env)
      // Value conservation holds for valid and invalid transactions alike.
      withdrawn = body.withdrawals.map(_._2).sum
      consumed = spent.foldLeft(Value.zero)(_ + _.value) + Value.coin(withdrawn + refunds) +
        Value(0, body.mint)
      produced = body.outputs.foldLeft(Value.zero)(_ + _.value) + Value.coin(body.fee + deposits)
      _ <- check(consumed == produced, UtxoFailure.ValueNotConserved(Mismatch(consumed, produced)))
      plutusStats <- phase2(state, tx, env, scripts, spent, refOuts, plutusPurposes)
      result <-
        if tx.isValid then
          applyValid(state, tx, env, deposits, refunds).map(_.copy(plutus = plutusStats))
        else Right(applyInvalid(state, tx, collateral).copy(plutus = plutusStats))
    yield result

  /** The script data hash must equal the recomputed hash, and the block's `is_valid` flag must
    * equal the outcome of evaluating every Plutus script.
    */
  private[ledger] def phase2(
      state: LedgerState,
      tx: Tx,
      env: Env,
      scripts: Map[Bytes, Script],
      spent: Vector[TxOut],
      refOuts: Vector[TxOut],
      plutusPurposes: Set[Pointer]
  ): Result[PlutusStats] =
    phase2(state.params, state.plutus, tx, env, scripts, spent, refOuts, plutusPurposes)

  /** [[phase2]] over what it reads of the state: the parameters and the counters it adds to. */
  private[ledger] def phase2(
      pp: ProtocolParams,
      plutus: PlutusStats,
      tx: Tx,
      env: Env,
      scripts: Map[Bytes, Script],
      spent: Vector[TxOut],
      refOuts: Vector[TxOut],
      plutusPurposes: Set[Pointer]
  ): Result[PlutusStats] =
    val body = tx.body
    val id = body.id
    if pp.protocolMajor < 5 || (plutusPurposes.isEmpty && body.scriptDataHash.isEmpty) then
      Right(plutus)
    else
      val needed = scriptsNeeded(body, spent)
      val languages = needed.keys.flatMap(scripts.get).map(_.kind).toSet
      val recomputed = ScriptIntegrity.hash(tx, pp, languages)
      val stats = plutus.copy(integrityChecked = plutus.integrityChecked + 1)
      if recomputed != body.scriptDataHash then
        Left(
          LedgerFailure.Rejected(
            id,
            PredicateFailure.utxow(
              UtxowFailure.PPViewHashesDontMatch(Mismatch(body.scriptDataHash, recomputed))
            )
          )
        )
      else if plutusPurposes.isEmpty then Right(stats)
      else
        val inputs = body.inputs.zip(spent).toMap
        val references = body.referenceInputs.zip(refOuts).toMap
        val posix = env.clock.toRight(TranslationError.NoEraHistory)
        val outcome = Phase2.evaluate(
          tx,
          pp,
          scripts,
          inputs,
          references,
          slot => posix.map(_(slot)),
          env.evaluator
        )
        env.observer.foreach(
          _(Phase2.Observation(tx, pp, env.slot, inputs, references, env.clock, outcome))
        )
        outcome.left
          .map(e =>
            LedgerFailure.Rejected(
              id,
              PredicateFailure.utxo(UtxoFailure.Utxos(UtxosFailure.CollectErrors(Vector(e))))
            )
          )
          .flatMap {
            case Phase2.Outcome.Passed(n) =>
              if tx.isValid then
                Right(stats.copy(succeeded = stats.succeeded + 1, scripts = stats.scripts + n))
              else
                Left(
                  LedgerFailure.Rejected(
                    id,
                    PredicateFailure.utxo(
                      UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(false, None))
                    )
                  )
                )
            case Phase2.Outcome.Failed(n, redeemer, reason) =>
              if !tx.isValid then
                Right(stats.copy(failed = stats.failed + 1, scripts = stats.scripts + n + 1))
              else
                Left(
                  LedgerFailure.Rejected(
                    id,
                    PredicateFailure.utxo(
                      UtxoFailure.Utxos(
                        UtxosFailure.ValidationTagMismatch(
                          true,
                          Some(Vector(reason.message -> Bytes.empty))
                        )
                      )
                    )
                  )
                )
          }

  // ---------------------------------------------------------------------------------------------
  // UTXO

  /** The minimum ada of an output. Shelley and Allegra: the flat `minUTxOValue`
    * (`getMinCoinTxOut`); Mary: `scaledMinDeposit`, which is `minUTxOValue` for an ada-only value
    * and otherwise `max(minUTxOValue, (minUTxOValue / 27) * (27 + size v))` (`coinSize` is 0 there,
    * unlike Alonzo where the ada-only entry counts 2 words); Alonzo and Babbage:
    * `coinsPerUTxOWord * (27 + size v + datum hash)`; Babbage onward: `coinsPerUTxOByte` times the
    * serialised size plus 160.
    */
  private[ledger] def minUTxO(pp: ProtocolParams, out: TxOut): BigInt =
    if pp.protocolMajor >= 7 then (160 + out.ledgerSize) * pp.coinsPerUTxOByte
    else if pp.protocolMajor >= 5 then pp.coinsPerUTxOWord * entrySize(out)
    else if pp.protocolMajor >= 4 then
      if out.value.assets.isEmpty then pp.minUTxOValue
      else
        val perWord = pp.minUTxOValue / UtxoEntrySizeWithoutVal
        pp.minUTxOValue.max(perWord * (UtxoEntrySizeWithoutVal + valueWords(out.value)))
    else pp.minUTxOValue

  /** `utxoEntrySizeWithoutVal`: 6 + 14 (output) + 7 (input) words. */
  private val UtxoEntrySizeWithoutVal = BigInt(27)

  /** `size` of a multi-asset `MaryValue` in 8-byte words: `repOverhead` (4 + 1 ada word + 1 for the
    * asset count) plus the packed representation, which stores 12 bytes per (policy, name) triple,
    * one 28-byte policy id per distinct policy and the bytes of every distinct asset name. An
    * ada-only value counts 2 words (the Alonzo entry size; Mary bypasses it).
    */
  private[ledger] def valueWords(value: Value): BigInt =
    if value.assets.isEmpty then BigInt(2)
    else
      val triples = value.assets.valuesIterator.map(_.size).sum
      val names = value.assets.valuesIterator.flatMap(_.keysIterator).toSet
      val bytes = triples * 12 + names.iterator.map(_.size).sum + value.assets.size * 28
      BigInt(6 + (bytes + 7) / 8)

  /** `utxoEntrySizeWithoutVal` (27 words) plus the value and datum hash sizes. */
  private def entrySize(out: TxOut): BigInt =
    val datum = out.datum match
      case Datum.Hash(_) => 10
      case _             => 0
    UtxoEntrySizeWithoutVal + valueWords(out.value) + datum

  /** The largest serialised value an output may carry: 4000 bytes in Allegra and Mary (hard-coded
    * in `validateOutputTooBigUTxO`), `maxValueSize` from Alonzo, no limit in Shelley.
    */
  private[ledger] def maxOutputValueSize(pp: ProtocolParams): Option[BigInt] =
    if pp.protocolMajor >= 5 then Some(pp.maxValueSize)
    else if pp.protocolMajor >= 3 then Some(BigInt(4000))
    else None

  /** The output checks of UTXO before Conway, each reporting every offending output: on the wrong
    * network (`WrongNetwork`), too small (`OutputTooSmallUTxO`, with the outputs) and too big
    * (`OutputTooBigUTxO`, with the size and the limit). From Babbage the checks are
    * [[outputsOk]]'s.
    */
  private[ledger] def outputChecks(
      pp: ProtocolParams,
      env: Env,
      id: Bytes,
      outs: Vector[TxOut]
  ): Result[Unit] =
    if pp.protocolMajor >= 7 then outputsOk(pp, env, outs).leftMap(LedgerFailure.Rejected(id, _))
    else
      // Allegra and Mary test `pointwise (<) v (inject min)`: every component must be below, so a value
      // that carries assets is never too small (Shelley compares the coin, Alonzo negates `pointwise (>=)`).
      def minimumApplies(out: TxOut) =
        pp.protocolMajor <= 2 || pp.protocolMajor >= 5 || out.value.assets.isEmpty
      val wrong = outs.filter(_.address.kind match
        case Address.Kind.Shelley(n, _, _, _) => n != env.network
        case Address.Kind.Byron(_)            => false)
      val small = outs.filter(o => minimumApplies(o) && o.value.coin < minUTxO(pp, o))
      val big = outs.flatMap { o =>
        maxOutputValueSize(pp).filter(max => BigInt(o.value.serializedSize) > max).map { max =>
          (o.value.serializedSize, max.toInt, o)
        }
      }
      def refuse(f: UtxoFailure) = Left(LedgerFailure.Rejected(id, PredicateFailure.utxo(f)))
      for
        _ <-
          if wrong.isEmpty then Right(())
          else refuse(UtxoFailure.WrongNetwork(env.network, wrong.map(_.address.raw).toSet))
        _ <- if small.isEmpty then Right(()) else refuse(UtxoFailure.OutputTooSmall(small))
        _ <- if big.isEmpty then Right(()) else refuse(UtxoFailure.OutputTooBig(big))
      yield ()

  /** The output checks of Babbage and Conway UTXO, each reporting every offending output: too small
    * (with the minimum), too big (with the size and the limit) and on the wrong network.
    */
  private[ledger] def outputsOk(
      pp: ProtocolParams,
      env: Env,
      outs: Vector[TxOut]
  ): Either[PredicateFailure, Unit] =
    val small = outs.filter(o => o.value.coin < minUTxO(pp, o)).map(o => o -> minUTxO(pp, o))
    val big = outs.flatMap { o =>
      maxOutputValueSize(pp).filter(max => BigInt(o.value.serializedSize) > max).map { max =>
        (o.value.serializedSize, max.toInt, o)
      }
    }
    val wrong = outs.filter(_.address.kind match
      case Address.Kind.Shelley(n, _, _, _) => n != env.network
      case Address.Kind.Byron(_)            => false)
    for
      _ <- Either.cond(
        small.isEmpty,
        (),
        PredicateFailure.utxo(UtxoFailure.BabbageOutputTooSmall(small))
      )
      _ <- Either.cond(big.isEmpty, (), PredicateFailure.utxo(UtxoFailure.OutputTooBig(big)))
      _ <- Either.cond(
        wrong.isEmpty,
        (),
        PredicateFailure.utxo(UtxoFailure.WrongNetwork(env.network, wrong.map(_.address.raw).toSet))
      )
    yield ()

  /** Collateral rules (Alonzo+): only for transactions that carry redeemers. Returns the collateral
    * outputs and their ada balance after the return output.
    */
  private[ledger] def collateralChecks(
      state: LedgerState,
      tx: Tx,
      @unused env: Env
  ): Result[Collateral] = collateralChecks(state.utxo, state.params, tx)

  /** [[collateralChecks]] over what it reads of the state: the UTxO set and the parameters. */
  private[ledger] def collateralChecks(
      utxo: Utxo,
      pp: ProtocolParams,
      tx: Tx
  ): Result[Collateral] =
    val body = tx.body
    val id = body.id
    def check(ok: Boolean, failure: => UtxoFailure): Result[Unit] =
      if ok then Right(())
      else Left(LedgerFailure.Rejected(id, PredicateFailure.utxo(failure)))
    if pp.protocolMajor < 5 || tx.witnesses.redeemers.isEmpty then Right(Collateral.none)
    else
      val missing = body.collateral.filterNot(utxo.contains).toSet
      for
        _ <- check(missing.isEmpty, UtxoFailure.BadInputs(missing))
        outs = body.collateral.flatMap(utxo.get)
        required = tx.witnesses.redeemers.nonEmpty
        _ <-
          if !required then Right(())
          else
            val total = outs.foldLeft(Value.zero)(_ + _.value)
            val balance = body.collateralReturn.fold(total)(r => total - r.value)
            val needed = (body.fee * pp.collateralPercentage + 99) / 100
            val scriptLocked = body.collateral.zip(outs).filter { case (_, o) =>
              o.address.kind match
                case Address.Kind.Shelley(_, Credential.Script(_), _, _) => true
                case _                                                   => false
            }
            for
              _ <- check(
                body.collateral.size <= pp.maxCollateralInputs,
                UtxoFailure.TooManyCollateralInputs(
                  Mismatch(body.collateral.size, pp.maxCollateralInputs.toInt)
                )
              )
              _ <- check(body.collateral.nonEmpty, UtxoFailure.NoCollateralInputs)
              _ <- check(scriptLocked.isEmpty, UtxoFailure.ScriptsNotPaid(scriptLocked.toMap))
              _ <- check(balance.assets.isEmpty, UtxoFailure.CollateralContainsNonAda(balance))
              _ <- check(
                balance.coin * 100 >= body.fee * pp.collateralPercentage,
                UtxoFailure.InsufficientCollateral(balance.coin, needed)
              )
              _ <- check(
                body.totalCollateral.forall(_ == balance.coin),
                UtxoFailure.IncorrectTotalCollateralField(
                  balance.coin,
                  body.totalCollateral.getOrElse(balance.coin)
                )
              )
            yield ()
      yield
        val total = outs.foldLeft(Value.zero)(_ + _.value)
        val balance = body.collateralReturn.fold(total)(r => total - r.value)
        Collateral(outs, balance.coin)

  /** Collateral outputs and the ada they pay as fees when the transaction fails phase 2. */
  private[ledger] final case class Collateral(outputs: Vector[TxOut], fee: BigInt)
  private[ledger] object Collateral:
    val none: Collateral = Collateral(Vector.empty, 0)

  // ---------------------------------------------------------------------------------------------
  // UTXOW

  /** Scripts the transaction can use: its witnesses and the reference scripts of spent and
    * reference outputs (Babbage).
    */
  private[ledger] def providedScripts(wits: Witnesses, outs: Vector[TxOut]): Map[Bytes, Script] =
    outs.flatMap(_.scriptRef).map(s => s.hash -> s).toMap ++ wits.scriptsByHash

  /** Script hashes the transaction must provide, with the redeemer pointers that use each. */
  private[ledger] def scriptsNeeded(body: TxBody, spent: Vector[TxOut]): Map[Bytes, Set[Pointer]] =
    val byInput = body.inputs.zip(spent).toMap
    Purpose
      .all(body, byInput)
      .flatMap(p => p.scriptHash.map(h => h -> ((p.tag, BigInt(p.index)))))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap

  /** Alonzo UTXOW: redeemers match the Plutus purposes, datums, script data hash presence. */
  private[ledger] def alonzoChecks(
      state: LedgerState,
      tx: Tx,
      spent: Vector[TxOut],
      @unused refOuts: Vector[TxOut],
      scripts: Map[Bytes, Script],
      plutusPurposes: Set[Pointer]
  ): Result[Unit] = alonzoChecks(state.params, tx, spent, scripts, plutusPurposes)

  /** [[alonzoChecks]] over what it reads of the state: the parameters. */
  private[ledger] def alonzoChecks(
      pp: ProtocolParams,
      tx: Tx,
      spent: Vector[TxOut],
      scripts: Map[Bytes, Script],
      plutusPurposes: Set[Pointer]
  ): Result[Unit] =
    val pv = pp.protocolMajor
    val body = tx.body
    val id = body.id
    val wits = tx.witnesses
    def checkWith(ok: Boolean, failure: => UtxowFailure): Result[Unit] =
      if ok then Right(())
      else Left(LedgerFailure.Rejected(id, PredicateFailure.utxow(failure)))
    if pv < 5 then
      Either.cond(
        wits.redeemers.isEmpty && wits.datums.isEmpty && body.scriptDataHash.isEmpty,
        (),
        LedgerFailure.Undecodable(id, TxDefect.PlutusFieldsBeforeAlonzo)
      )
    else
      val provided = wits.redeemers.map(r => (r.tag, r.index)).toSet
      // Datums required by spent outputs locked with a Plutus script.
      val required = spent.flatMap { out =>
        out.address.kind match
          case Address.Kind.Shelley(_, Credential.Script(h), _, _)
              if scripts.get(h).exists(_.kind.isPlutus) =>
            Some(out.datum -> scripts(h).kind)
          case _ => None
      }
      // Plutus purposes without a redeemer, with the script each one needs
      val unredeemed = Purpose
        .all(body, body.inputs.zip(spent).toMap)
        .flatMap(p =>
          p.scriptHash
            .filter(h => scripts.get(h).exists(_.kind.isPlutus))
            .filter(_ => !provided.contains((p.tag, BigInt(p.index))))
            .map(p -> _)
        )
      for
        _ <- checkWith(unredeemed.isEmpty, UtxowFailure.MissingRedeemers(unredeemed))
        _ <- checkWith(
          provided.subsetOf(plutusPurposes),
          UtxowFailure.ExtraRedeemers((provided -- plutusPurposes).toVector.sorted)
        )
        // Datum hashes the spent outputs ask for and the witnesses do not carry
        requiredHashes = required.collect { case (Datum.Hash(h), _) => h }.toSet
        _ <- checkWith(
          requiredHashes.subsetOf(wits.datumHashes),
          UtxowFailure.MissingRequiredDatums(requiredHashes -- wits.datumHashes, wits.datumHashes)
        )
        // Outputs locked by a Plutus script without a usable datum hash
        unspendable = spent.zip(body.inputs).flatMap { case (out, in) =>
          out.address.kind match
            case Address.Kind.Shelley(_, Credential.Script(h), _, _)
                if scripts.get(h).exists(_.kind.isPlutus) =>
              out.datum match
                case Datum.Hash(_)   => None
                case Datum.Inline(_) => Option.when(pv < 7)(in)
                // Plutus V3 scripts need no datum (CIP-69)
                case Datum.NoDatum => Option.when(scripts(h).kind != ScriptKind.PlutusV3)(in)
            case _ => None
        }
        _ <- checkWith(
          unspendable.isEmpty,
          UtxowFailure.UnspendableUTxONoDatumHash(unspendable.toSet)
        )
        needsHash = wits.redeemers.nonEmpty || wits.datums.nonEmpty
        languages = scriptsNeeded(body, spent).keys.flatMap(scripts.get).map(_.kind).toSet
        _ <- checkWith(
          body.scriptDataHash.isDefined == needsHash,
          UtxowFailure.PPViewHashesDontMatch(
            Mismatch(body.scriptDataHash, ScriptIntegrity.hash(tx, pp, languages))
          )
        )
      yield ()

  /** Babbage onward: every Plutus script in the witness set and in the outputs (the collateral
    * return included) must deserialise for its language and the protocol version in force, which
    * rejects a builtin the pair does not allow (`validateScriptsWellFormed`:
    * `MalformedScriptWitnesses`, `MalformedReferenceScripts`). Alonzo has no such check; there an
    * undecodable script that a transaction needs fails phase 2.
    */
  private[ledger] def scriptsWellFormed(state: LedgerState, tx: Tx, env: Env): Result[Unit] =
    scriptsWellFormed(state.params, tx, env)

  /** [[scriptsWellFormed]] over what it reads of the state: the parameters. */
  private[ledger] def scriptsWellFormed(pp: ProtocolParams, tx: Tx, env: Env): Result[Unit] =
    val pv = pp.protocolMajor.toInt
    def malformed(failure: Set[Bytes] => UtxowFailure, scripts: Vector[Script]): Result[Unit] =
      val bad = scripts.filter(_.kind.isPlutus).flatMap { s =>
        for
          language <- Phase2.languageOf(s).toOption.toVector
          code <- s.plutus.toVector
          _ <- env.decoder.validate(language, pv, code).left.toOption.toVector
        yield s.hash
      }
      if bad.isEmpty then Right(())
      else Left(LedgerFailure.Rejected(tx.body.id, PredicateFailure.utxow(failure(bad.toSet))))
    if pv < 7 then Right(())
    else
      for
        _ <- malformed(UtxowFailure.MalformedScriptWitnesses(_), tx.witnesses.scripts)
        _ <- malformed(
          UtxowFailure.MalformedReferenceScripts(_),
          (tx.body.outputs ++ tx.body.collateralReturn.toVector).flatMap(_.referenceScript)
        )
      yield ()

  private[ledger] def auxiliaryChecks(tx: Tx): Result[Unit] =
    val id = tx.body.id
    (tx.body.auxDataHash, tx.auxiliary) match
      case (None, None) => Right(())
      case (Some(h), Some(aux)) =>
        val computed = Blake2b.hash256.hash(aux)
        Either.cond(
          computed == h,
          (),
          LedgerFailure.Rejected(
            id,
            PredicateFailure.utxow(UtxowFailure.ConflictingMetadataHash(Mismatch(h, computed)))
          )
        )
      case (Some(h), None) =>
        Left(
          LedgerFailure.Rejected(id, PredicateFailure.utxow(UtxowFailure.MissingTxMetadata(h)))
        )
      case (None, Some(aux)) =>
        Left(
          LedgerFailure.Rejected(
            id,
            PredicateFailure.utxow(
              UtxowFailure.MissingTxBodyMetadataHash(Blake2b.hash256.hash(aux))
            )
          )
        )

  /** Key hashes that must sign (`witsVKeyNeeded`) and the address roots that need a bootstrap
    * witness.
    */
  private[ledger] def witnessChecks(
      state: LedgerState,
      tx: Tx,
      spent: Vector[TxOut],
      collateral: Collateral,
      scripts: Map[Bytes, Script],
      needed: Map[Bytes, Set[Pointer]],
      vkeyHashes: Vector[Bytes],
      verdicts: xyz.kd5ujc.scarab.ledger.Verdicts
  ): Result[Unit] =
    witnessChecks(
      state.genDelegs,
      tx,
      spent,
      collateral,
      scripts,
      needed,
      vkeyHashes,
      verdicts
    )

  /** [[witnessChecks]] over what it reads of the state: the genesis delegations (empty from Conway,
    * which has no update proposals).
    */
  private[ledger] def witnessChecks(
      genDelegs: Map[Bytes, GenDelegPair],
      tx: Tx,
      spent: Vector[TxOut],
      collateral: Collateral,
      scripts: Map[Bytes, Script],
      needed: Map[Bytes, Set[Pointer]],
      vkeyHashes: Vector[Bytes],
      verdicts: xyz.kd5ujc.scarab.ledger.Verdicts
  ): Result[Unit] =
    val body = tx.body
    val id = body.id
    val wits = tx.witnesses
    val locked = spent ++ collateral.outputs
    val inputKeys = locked.flatMap(_.address.kind match
      case Address.Kind.Shelley(_, Credential.Key(h), _, _) => Some(h)
      case _                                                => None)
    val bootRoots = locked.flatMap(_.address.kind match
      case Address.Kind.Byron(root) => Some(root)
      case _                        => None)
    val withdrawalKeys = body.withdrawals
      .collect { case (a, _) =>
        a.credential
      }
      .collect { case Credential.Key(h) => h }
    val certKeys = body.certificates.flatMap {
      case Certificate.Unregister(Credential.Key(h), _)     => Vector(h)
      case Certificate.Delegate(Credential.Key(h), _, _, _) => Vector(h)
      case Certificate.RegisterPool(p)                      => p.operator +: p.owners.toVector
      case Certificate.RetirePool(p, _)                     => Vector(p)
      case Certificate.GenesisDelegate(g, _, _)             => Vector(g)
      // Conway: the deposit-carrying registration and the governance certificates.
      case Certificate.Register(Credential.Key(h), Some(_))     => Vector(h)
      case Certificate.RegisterDRep(Credential.Key(h), _, _)    => Vector(h)
      case Certificate.UnregisterDRep(Credential.Key(h), _)     => Vector(h)
      case Certificate.UpdateDRep(Credential.Key(h), _)         => Vector(h)
      case Certificate.CommitteeAuthorize(Credential.Key(h), _) => Vector(h)
      case Certificate.CommitteeResign(Credential.Key(h), _)    => Vector(h)
      case _                                                    => Vector.empty
    }
    val voterKeys = body.votes.map(_._1).collect {
      case Voter.CommitteeHot(Credential.Key(h)) => h
      case Voter.DRepVoter(Credential.Key(h))    => h
      case Voter.Pool(h)                         => h
    }
    val updateKeys = body.update.toVector.flatMap(
      _.proposals.keys.toVector.flatMap(g => genDelegs.get(g).map(_.delegate))
    )
    val required =
      (inputKeys ++ bootRoots ++ withdrawalKeys ++ certKeys ++ voterKeys ++ updateKeys ++ body.requiredSigners).toSet
    // `witsFromTxWitnesses`: the hashes of the vkey witnesses and the address roots of the bootstrap
    // witnesses form one set, and a Byron input's `bootstrapKeyHash` is one of the needed hashes.
    // A vkey witness whose key hashes to the root therefore also spends the Byron output.
    val provided = vkeyHashes.toSet ++ wits.bootstrap.map(_.addressRoot)
    val invalidKeys = wits.vkeys.collect {
      case (vkey, signature) if !verdicts.witness(vkey, signature, id) => vkey
    } ++ wits.bootstrap.collect {
      case w if !verdicts.witness(w.vkey, w.signature, id) => w.vkey
    }
    for
      _ <- Either.cond(
        invalidKeys.isEmpty,
        (),
        LedgerFailure.Rejected(
          id,
          PredicateFailure.utxow(UtxowFailure.InvalidWitnesses(invalidKeys))
        )
      )
      missing = required -- provided
      _ <- Either.cond(
        missing.isEmpty,
        (),
        LedgerFailure.Rejected(
          id,
          PredicateFailure.utxow(UtxowFailure.MissingVKeyWitnesses(missing))
        )
      )
      // Native scripts (phase 1): the ones the transaction needs must hold.
      failing = needed.keys.toSet.filter { h =>
        scripts(h).native.exists(s => !s.eval(provided, body.validityStart, body.ttl))
      }
      _ <- Either.cond(
        failing.isEmpty,
        (),
        LedgerFailure.Rejected(
          id,
          PredicateFailure.utxow(UtxowFailure.ScriptWitnessNotValidating(failing))
        )
      )
    yield ()

  // ---------------------------------------------------------------------------------------------
  // Application

  private[ledger] def applyInvalid(
      state: LedgerState,
      tx: Tx,
      collateral: Collateral
  ): LedgerState =
    val body = tx.body
    val returned = body.collateralReturn.map(r => TxIn(body.id, body.outputs.size) -> r)
    state.copy(
      utxo = state.utxo -- body.collateral ++ returned,
      fees = state.fees + collateral.fee
    )

  private def applyValid(
      state: LedgerState,
      tx: Tx,
      env: Env,
      deposits: BigInt,
      refunds: BigInt
  ): Result[LedgerState] =
    val body = tx.body
    val id = body.id
    def refuse(f: PredicateFailure) = LedgerFailure.Rejected(id, f)
    // LEDGER's checks of the withdrawals, each over all of them as the reference has them: the
    // accounts on another network (UTXO), the accounts that are not registered and the amounts that
    // are not the balance (`testIncompleteAndMissingWithdrawals`, `Shelley/Rules/Ledger.hs:341`)
    val wrongNetwork = body.withdrawals.map(_._1).filter(_.network != env.network).toSet
    val missing = body.withdrawals.filterNot(w => state.accounts.contains(w._1.credential)).toMap
    val incomplete = body.withdrawals.flatMap { case (account, amount) =>
      state.accounts.get(account.credential).filter(_.balance != amount).map { a =>
        account -> Mismatch(amount, a.balance)
      }
    }.toMap
    for
      _ <- Either.cond(
        wrongNetwork.isEmpty,
        (),
        refuse(
          PredicateFailure.utxo(UtxoFailure.WrongNetworkWithdrawal(env.network, wrongNetwork))
        )
      )
      _ <- Either.cond(
        missing.isEmpty,
        (),
        refuse(PredicateFailure.WithdrawalsMissingAccounts(missing))
      )
      _ <- Either.cond(
        incomplete.isEmpty,
        (),
        refuse(PredicateFailure.IncompleteWithdrawals(incomplete))
      )
      afterWithdrawals = body.withdrawals.foldLeft(state) { case (s, (account, _)) =>
        s.copy(accounts = s.accounts.updatedWith(account.credential)(_.map(_.copy(balance = 0))))
      }
      afterUpdate <- body.update.fold(Right(afterWithdrawals): Result[LedgerState]) { u =>
        PpupRules
          .propose(afterWithdrawals, u, env.slot, env.firstSlotNextEpoch, env.stabilityWindow)
          .leftMap(_.inTx(id))
      }
      afterCerts <- body.certificates.zipWithIndex.foldLeftM(afterUpdate) { case (s, (cert, ix)) =>
        PreConwayCerts(s, cert, ix, env, tx)
      }
    yield
      val created = body.outputs.zipWithIndex.map { case (out, i) => TxIn(id, i) -> out }
      afterCerts.copy(
        utxo = afterCerts.utxo -- body.inputs ++ created,
        fees = afterCerts.fees + body.fee,
        deposited = afterCerts.deposited + deposits - refunds
      )

/** DELEGS for the eras before Conway: stake key registration, delegation, pool registration and
  * retirement, genesis delegation and instantaneous reward certificates.
  */
private[ledger] object PreConwayCerts:
  import TxRules.{Env, Result}

  def apply(s: LedgerState, cert: Certificate, ix: Int, env: Env, tx: Tx): Result[LedgerState] =
    val id = tx.body.id
    val pp = s.params
    def refuse(f: PredicateFailure) = LedgerFailure.Rejected(id, f)
    def check(ok: Boolean, failure: => PredicateFailure): Result[Unit] =
      Either.cond(ok, (), refuse(failure))
    cert match
      case Certificate.Register(c, _) =>
        for _ <- check(
            !s.accounts.contains(c),
            PredicateFailure.deleg(DelegFailure.StakeKeyAlreadyRegistered(c))
          )
        yield s.copy(
          accounts = s.accounts.updated(c, Account(0, pp.keyDeposit, None, None)),
          ptrs = s.ptrs.updated(Ptr(env.slot, env.txIndex, ix), c)
        )
      case Certificate.Unregister(c, _) =>
        for
          a <- s.accounts
            .get(c)
            .toRight(refuse(PredicateFailure.deleg(DelegFailure.StakeKeyNotRegistered(c))))
          _ <- check(
            a.balance == 0,
            PredicateFailure.deleg(DelegFailure.StakeKeyHasNonZeroAccountBalance(a.balance))
          )
        yield s.copy(
          accounts = s.accounts - c,
          ptrs = s.ptrs.removedCredential(c)
        )
      case Certificate.Delegate(c, Some(pool), _, _) =>
        for
          a <- s.accounts
            .get(c)
            .toRight(refuse(PredicateFailure.deleg(DelegFailure.StakeDelegationImpossible(c))))
          _ <- check(
            s.pools.contains(pool),
            PredicateFailure.deleg(DelegFailure.DelegateeNotRegistered(pool))
          )
        yield s.copy(accounts = s.accounts.updated(c, a.copy(pool = Some(pool))))
      case Certificate.RegisterPool(p) =>
        for
          _ <- check(
            !pp.validatesPoolRewardAccountNetwork || p.rewardAccount.network == env.network,
            PredicateFailure.pool(
              PoolFailure.WrongNetworkPool(
                Mismatch(p.rewardAccount.network, env.network),
                p.operator
              )
            )
          )
          _ <- check(
            p.cost >= pp.minPoolCost,
            PredicateFailure.pool(PoolFailure.StakePoolCostTooLow(Mismatch(p.cost, pp.minPoolCost)))
          )
        yield
          if s.pools.contains(p.operator) then
            s.copy(
              futurePools = s.futurePools.updated(p.operator, p),
              retiring = s.retiring - p.operator
            )
          else
            // The deposit is accounted once per transaction.
            s.copy(pools = s.pools.updated(p.operator, PoolState(p, pp.poolDeposit)))
      case Certificate.RetirePool(pool, e) =>
        val limit = s.epoch + pp.eMax
        for
          _ <- check(
            s.pools.contains(pool),
            PredicateFailure.pool(PoolFailure.StakePoolNotRegisteredOnKey(pool))
          )
          _ <- check(
            s.epoch < e && e <= limit,
            PredicateFailure.pool(
              PoolFailure.StakePoolRetirementWrongEpoch(Mismatch(e, s.epoch), Mismatch(e, limit))
            )
          )
        yield s.copy(retiring = s.retiring.updated(pool, e))
      case Certificate.GenesisDelegate(g, d, v) =>
        for _ <- check(
            s.genDelegs.contains(g),
            PredicateFailure.deleg(DelegFailure.GenesisKeyNotInMapping(g))
          )
        yield s.copy(futureGenDelegs =
          s.futureGenDelegs :+ FutureGenDeleg(env.slot + env.stabilityWindow, g, GenDelegPair(d, v))
        )
      case Certificate.MoveRewards(pot, transfer) => moveRewards(s, pot, transfer, env, tx)
      case other =>
        Left(LedgerFailure.Undecodable(id, TxDefect.ConwayCertificate(other)))

  /** `MIRCert` (`Shelley/Rules/Deleg.hs:339-385`). The signatures of the genesis delegates are
    * UTXOW's check (`validateMIRInsufficientGenesisSigs`), made before.
    */
  private def moveRewards(
      s: LedgerState,
      pot: Pot,
      transfer: Either[BigInt, Map[Credential, BigInt]],
      env: Env,
      tx: Tx
  ): Result[LedgerState] =
    val id = tx.body.id
    def refuse(f: DelegFailure) = LedgerFailure.Rejected(id, PredicateFailure.deleg(f))
    def check(ok: Boolean, failure: => DelegFailure): Result[Unit] =
      Either.cond(ok, (), refuse(failure))
    val ir = s.mir
    val availableR = s.reserves + ir.deltaReserves
    val availableT = s.treasury + ir.deltaTreasury
    // hardforkAlonzoAllowMIRTransfer: transfers between pots and negative entries from protocol 5.
    val transfersAllowed = s.params.protocolMajor > 4
    val tooLate = env.firstSlotNextEpoch - env.stabilityWindow
    for
      _ <- check(
        env.slot < tooLate,
        DelegFailure.MIRCertificateTooLateinEpoch(Mismatch(env.slot, tooLate))
      )
      result <- transfer match
        case Left(_) if !transfersAllowed =>
          Left(refuse(DelegFailure.MIRTransferNotCurrentlyAllowed))
        case Left(coin) =>
          check(coin >= 0, DelegFailure.MIRNegativeTransfer(pot, coin)) *> (pot match
            case Pot.Reserves =>
              val available = availableR - ir.reserves.values.sum
              check(
                coin <= available,
                DelegFailure.InsufficientForTransfer(pot, Mismatch(coin, available))
              ).as(
                s.copy(mir =
                  ir.copy(
                    deltaReserves = ir.deltaReserves - coin,
                    deltaTreasury = ir.deltaTreasury + coin
                  )
                )
              )
            case Pot.Treasury =>
              val available = availableT - ir.treasury.values.sum
              check(
                coin <= available,
                DelegFailure.InsufficientForTransfer(pot, Mismatch(coin, available))
              ).as(
                s.copy(mir =
                  ir.copy(
                    deltaReserves = ir.deltaReserves + coin,
                    deltaTreasury = ir.deltaTreasury - coin
                  )
                )
              ))
        case Right(amounts) =>
          val current = if pot == Pot.Reserves then ir.reserves else ir.treasury
          // Before Alonzo a certificate replaces earlier entries of the same credential and may not
          // carry negative amounts; from Alonzo it adds to them and the sum may not go negative.
          val combined =
            if transfersAllowed then
              amounts.foldLeft(current)((m, kv) =>
                m.updated(kv._1, m.getOrElse(kv._1, BigInt(0)) + kv._2)
              )
            else current ++ amounts
          val available = if pot == Pot.Reserves then availableR else availableT
          for
            _ <-
              if transfersAllowed then
                check(combined.values.forall(_ >= 0), DelegFailure.MIRProducesNegativeUpdate)
              else
                check(amounts.values.forall(_ >= 0), DelegFailure.MIRNegativesNotCurrentlyAllowed)
            _ <- check(
              combined.values.sum <= available,
              DelegFailure.InsufficientForInstantaneousRewards(
                pot,
                Mismatch(combined.values.sum, available)
              )
            )
          yield s.copy(mir =
            if pot == Pot.Reserves then ir.copy(reserves = combined)
            else ir.copy(treasury = combined)
          )
    yield result
