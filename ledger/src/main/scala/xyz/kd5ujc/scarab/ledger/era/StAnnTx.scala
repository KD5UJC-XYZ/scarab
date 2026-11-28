// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.data.NonEmptyList
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Phase2, TranslationError}
import xyz.kd5ujc.scarab.ledger.state.EraTag
import xyz.kd5ujc.scarab.plutus.PlutusLanguage as Lang

/** A top-level transaction annotated with what the ledger state gives it (`data AlonzoStAnnTx l
  * era`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:556-565`, cardano-ledger f649f975): the
  * scripts it needs and provides, the languages its Plutus scripts use, the cache of runnable
  * scripts with its own added, and its Plutus scripts with their contexts or why they cannot be
  * collected. The reference's only constructor is of a top-level transaction, as here, where the
  * family [[StAnnTx]] is this at the top level alone.
  */
final case class AlonzoStAnnTx[E <: EraTag](
    asatTx: Tx[TxLevel.Top, E],
    asatScriptsNeeded: ScriptsNeeded[E],
    asatScriptsProvided: ScriptsProvided[E],
    asatPlutusLanguagesUsed: Set[Lang],
    asatPlutusRunnableCache: Map[Bytes, SupportedPlutusRunnable[E]],
    asatPlutusScriptsWithContext: Either[NonEmptyList[CollectError[E]], List[PlutusWithContext]]
)

/** Alonzo's annotated transactions (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo.hs:125-166`,
  * cardano-ledger 9df9f98c; `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/UTxO.hs:177-214`,
  * cardano-ledger f649f975).
  */
object AlonzoStAnnTx:
  /** `mkAlonzoStAnnTx` (`Alonzo.hs:125-166`, cardano-ledger 9df9f98c; `:121-160` at f649f975): the
    * transaction with the scripts it needs and provides, the needed Plutus scripts resolved against
    * those provided and the cache, the languages they use, and the scripts with their contexts at
    * the parameters' version and with their cost models, of the transaction's information with the
    * scripts used and no sub-transaction's (`LedgerTopTxInfo mempty`). The epoch information and
    * the system start are the one clock function of [[LedgerTxInfo]]. The reference's `mkStAnnTx`
    * member of `ApplyTx`, which this is for Conway, comes with the mempool.
    */
  def mkAlonzoStAnnTx[E <: EraTag](
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      pp: PParams[E],
      utxo: UTxO[E],
      stAnnTxCache: Map[Bytes, SupportedPlutusRunnable[E]],
      tx: Tx[TxLevel.Top, E],
      ahead: ContextsAhead[E] = ContextsAhead.none[E]
  )(using
      p: AlonzoEraPParams[E],
      x: AlonzoEraTx[E],
      u: AlonzoEraUTxO[E],
      s: AlonzoEraScript[E],
      c: EraPlutusContext[E],
      needed: ScriptsNeeded[E] =:= AlonzoScriptsNeeded[E]
  ): AlonzoStAnnTx[E] =
    val resolved = resolve[E](pp, utxo, stAnnTxCache, tx)
    // the contexts made ahead of the rules where they are these, else collected here (#1499)
    val contexts = ContextInputs.collected(resolved.inputs, slotToPOSIXTime, ahead)
    AlonzoStAnnTx(
      asatTx = tx,
      asatScriptsNeeded = resolved.scriptsNeeded,
      asatScriptsProvided = resolved.scriptsProvided,
      asatPlutusLanguagesUsed = resolved.inputs.used.map((_, r) => r.language).toSet,
      asatPlutusRunnableCache = resolved.cache,
      asatPlutusScriptsWithContext = contexts
    )

  /** What [[mkAlonzoStAnnTx]] resolves before it collects the contexts: the scripts the transaction
    * needs and provides, the cache with its own added, and what its contexts are collected from
    * ([[ContextInputs]]): the parameters' version, the outputs `utxo`, the transaction, the needed
    * Plutus scripts resolved against those provided and the cache, and the parameters' cost models.
    */
  final case class Resolved[E <: EraTag](
      scriptsNeeded: ScriptsNeeded[E],
      scriptsProvided: ScriptsProvided[E],
      cache: Map[Bytes, SupportedPlutusRunnable[E]],
      inputs: ContextInputs[E]
  )

  /** [[Resolved]] of `tx` with the parameters `pp`, the outputs `utxo` and the cache
    * `stAnnTxCache`, as `mkAlonzoStAnnTx` resolves them (`Alonzo.hs:125-166`, cardano-ledger
    * 9df9f98c): what the speculation of a block's scripts ahead of the rules collects its contexts
    * from too (#1499).
    */
  def resolve[E <: EraTag](
      pp: PParams[E],
      utxo: UTxO[E],
      stAnnTxCache: Map[Bytes, SupportedPlutusRunnable[E]],
      tx: Tx[TxLevel.Top, E]
  )(using
      p: AlonzoEraPParams[E],
      x: AlonzoEraTx[E],
      u: AlonzoEraUTxO[E],
      s: AlonzoEraScript[E],
      c: EraPlutusContext[E],
      needed: ScriptsNeeded[E] =:= AlonzoScriptsNeeded[E]
  ): Resolved[E] =
    val protVer = p.ppProtocolVersionL.get(pp)
    val top = u.txOf.bodyOf.top
    val scriptsNeeded =
      u.getScriptsNeeded[TxLevel.Top](utxo, u.txOf.body[TxLevel.Top](tx)(using top))(using top)
    val scriptsProvided = u.getScriptsProvided[TxLevel.Top](utxo, tx)(using top)
    val (newStAnnTxCache, plutusScriptsUsed) =
      AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose(
        protVer,
        scriptsProvided,
        needed(scriptsNeeded),
        stAnnTxCache
      )
    Resolved(
      scriptsNeeded,
      scriptsProvided,
      newStAnnTxCache,
      new ContextInputs[E](
        protVer,
        utxo,
        tx,
        plutusScriptsUsed,
        PParams.lensHKD[E].andThen(p.hkdCostModelsL[Identity]).get(pp)
      )
    )

  /** `scriptsProvidedAlonzoStAnnTx` (`Alonzo/UTxO.hs:177-184`, cardano-ledger f649f975). */
  def scriptsProvidedAlonzoStAnnTx[E <: EraTag](stAnnTx: AlonzoStAnnTx[E]): ScriptsProvided[E] =
    stAnnTx.asatScriptsProvided

  /** `scriptsNeededAlonzoStAnnTx` (`Alonzo/UTxO.hs:186-193`, cardano-ledger f649f975). */
  def scriptsNeededAlonzoStAnnTx[E <: EraTag](stAnnTx: AlonzoStAnnTx[E]): ScriptsNeeded[E] =
    stAnnTx.asatScriptsNeeded

  /** `plutusScriptsWithContextAlonzoStAnnTx` (`Alonzo/UTxO.hs:195-204`, cardano-ledger f649f975).
    */
  def plutusScriptsWithContextAlonzoStAnnTx[E <: EraTag](
      stAnnTx: AlonzoStAnnTx[E]
  ): Either[NonEmptyList[CollectError[E]], List[PlutusWithContext]] =
    stAnnTx.asatPlutusScriptsWithContext

  /** `plutusLanguagesUsedAlonzoStAnnTx` (`Alonzo/UTxO.hs:206-214`, cardano-ledger f649f975). */
  def plutusLanguagesUsedAlonzoStAnnTx[E <: EraTag](stAnnTx: AlonzoStAnnTx[E]): Set[Lang] =
    stAnnTx.asatPlutusLanguagesUsed

/** What the contexts of a top-level transaction's Plutus scripts are collected from but the clock
  * (#1499): the protocol version, the outputs the transaction spends and reads, the transaction,
  * its needed Plutus scripts with their purposes as resolved, and the cost models; the contexts are
  * a function of these and of the clock's answers alone ([[collect]], `mkAlonzoStAnnTx` 's
  * collection: the transaction's information with the scripts used and no sub-transaction's,
  * `LedgerTopTxInfo mempty`).
  *
  * Two are equal where the collection reads them alike: the version, the cost models, the outputs
  * (by the era's equality and their bytes too) and the transaction (its parts with their bytes) by
  * value; each script used by its purpose, its runnable (its binary and hash) and its language's
  * translation by identity, where the runnable's own equality takes the language and hash only; and
  * the era's instances the collection runs with by identity. The outputs are compared as a map, not
  * in their order: the rules' own read of them (`StoredOutputs.readAt`) puts them in an order that
  * depends on what their reader holds already, so their collection is a function of the outputs
  * whatever their order, as the reference's map is ordered by its keys.
  */
final class ContextInputs[E <: EraTag] private[era] (
    val protVer: ProtVer,
    val utxo: UTxO[E],
    val tx: Tx[TxLevel.Top, E],
    val used: List[(PlutusPurpose[AsIxItem, E], SupportedPlutusRunnable[E])],
    val costModels: CostModels
)(using x: AlonzoEraTx[E], s: AlonzoEraScript[E], c: EraPlutusContext[E], u: AlonzoEraUTxO[E]):
  /** `scriptsWithContextFromLedgerTxInfo` of these inputs with the clock `posix`. */
  def collect(
      posix: Phase2.Posix
  ): Either[NonEmptyList[CollectError[E]], List[PlutusWithContext]] =
    AlonzoPlutusEvaluate.scriptsWithContextFromLedgerTxInfo(
      LedgerTxInfo(
        protVer,
        posix,
        utxo,
        tx,
        used,
        LedgerLevelTxInfo.LedgerTopTxInfo(Map.empty)
      ),
      costModels
    )

  private def instances: List[AnyRef] = List[AnyRef](x, s, c, u)

  private def raw(out: TxOut[E]): Bytes = u.txOf.bodyOf.outputsOf.raw(out)

  /** The sizes of the outputs' bytes, for the bounds of what keeps these inputs. */
  def outputBytes: Vector[Long] = utxo.unUTxO.valuesIterator.map(raw(_).size.toLong).toVector

  override def equals(that: Any): Boolean = that match
    case o: ContextInputs[?] =>
      (this eq o) || (
        protVer == o.protVer && costModels == o.costModels &&
          instances.corresponds(o.instances)(_ eq _) &&
          used.corresponds(o.used) { case ((p, r), (q, w)) =>
            (r.plutusTxInfo eq w.plutusTxInfo) && p == q &&
            (r.plutusRunnable: Any) == (w.plutusRunnable: Any)
          } &&
          utxo == o.utxo && o.sameRaw(utxo.unUTxO.view.mapValues(raw).toMap) && tx == o.tx
      )
    case _ => false

  /** Whether the outputs of `raws` are these inputs' outputs' bytes, input for input. */
  private def sameRaw(raws: Map[xyz.kd5ujc.scarab.ledger.TxIn, Bytes]): Boolean =
    raws.sizeIs == utxo.unUTxO.size &&
      raws.forall((in, bytes) => utxo.unUTxO.get(in).exists(out => raw(out) == bytes))

  override def hashCode(): Int = (protVer, utxo.unUTxO.keySet, used.map(_._1)).hashCode
  override def toString: String = s"ContextInputs($protVer, ${utxo.unUTxO.keySet}, $used)"

object ContextInputs:
  /** The contexts collected from `inputs` with the clock `posix`: those made ahead where they are
    * that collection ([[KeptContexts.answers]]), and otherwise [[ContextInputs.collect]]. Either is
    * the collection of `inputs` with `posix`. A transaction that uses no Plutus script is not
    * looked for.
    */
  def collected[E <: EraTag](
      inputs: ContextInputs[E],
      posix: Phase2.Posix,
      ahead: ContextsAhead[E]
  ): Either[NonEmptyList[CollectError[E]], List[PlutusWithContext]] =
    Option
      .when(inputs.used.nonEmpty)(inputs)
      .flatMap(ahead.contexts)
      .filter(_.answers(inputs, posix))
      .fold(inputs.collect(posix))(kept => Right(kept.scripts))

/** A top-level transaction's scripts with their contexts, collected ahead of the rules (#1499): the
  * [[ContextInputs]] they were collected from, each slot the collection asked the clock for with
  * its answer, in order, and the scripts. Made only by [[KeptContexts.of]], which runs the
  * collection itself, so the scripts are the collection of the inputs with those answers.
  */
final class KeptContexts[E <: EraTag] private (
    val inputs: ContextInputs[E],
    val asked: Phase2.Asked,
    val scripts: List[PlutusWithContext]
):
  /** Whether the collection of `inputs` with the clock `posix` is this one: `posix` answers every
    * slot this one asked as it was answered, and the inputs are equal ([[ContextInputs]]). The
    * collection is a deterministic function of its inputs and of the answers to what it asks, so it
    * then collects [[scripts]].
    */
  def answers(inputs: ContextInputs[E], posix: Phase2.Posix): Boolean =
    Phase2.answered(asked, posix) && this.inputs == inputs

object KeptContexts:
  /** The contexts collected from `inputs` with `posix`, kept with what the collection asked the
    * clock, where it collected; none where it did not. Each script's request is made within the
    * collection's asking ([[PlutusWithContext.kept]]), so that a part of a context made only when
    * its request is asks the clock there too.
    */
  def of[E <: EraTag](inputs: ContextInputs[E], posix: Phase2.Posix): Option[KeptContexts[E]] =
    val (collected, asked) = Phase2.asking(posix) { clock =>
      inputs
        .collect(clock)
        .map(_.map { pwc =>
          val kept = PlutusWithContext.kept(pwc)
          val _ = PlutusWithContext.evalRequest(kept)
          kept
        })
    }
    collected.toOption.map(new KeptContexts(inputs, asked, _))

/** The contexts made ahead of the rules (#1499): those kept for a transaction like that of
  * `inputs`, if any, which the rules take only where they are the collection of their inputs
  * ([[ContextInputs.collected]]). It is memoisation only, read by the rules.
  */
trait ContextsAhead[E <: EraTag]:
  def contexts(inputs: ContextInputs[E]): Option[KeptContexts[E]]

object ContextsAhead:
  /** Nothing made ahead: every collection is the rules' own. */
  def none[E <: EraTag]: ContextsAhead[E] = new ContextsAhead[E]:
    def contexts(inputs: ContextInputs[E]): Option[KeptContexts[E]] = None
