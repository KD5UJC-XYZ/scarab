// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.annotation.unused
import cats.{Applicative, Eval, Monad, Monoid}
import cats.data.NonEmptyList
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.TranslationError
import xyz.kd5ujc.scarab.ledger.state.EraTag
import xyz.kd5ujc.scarab.plutus.{
  EvalFailure,
  EvalRequest,
  EvalResult,
  ExUnits,
  PlutusLanguage as Lang,
  ScriptEvaluator
}

// A Plutus script ready to run with its arguments, and the collection of a transaction's
// (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Evaluate.hs`,
// `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/Evaluate.hs`, cardano-ledger f649f975; the
// collection of a transaction's information with its scripts used at 9df9f98c).

/** A Plutus script of language `L` to run (`data PlutusRunnable (l :: Language)`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs:135-141`, cardano-ledger
  * f649f975): its binary and its hash. The reference also holds the script decoded for the protocol
  * version (`plutusRunnableResult`); here decoding is the `plutus` module's capability
  * ([[xyz.kd5ujc.scarab.plutus.ScriptDecoder]]), whose answer can be that it has none
  * (`Unavailable`, a defect rather than a verdict), so its result is not held in ledger data. The
  * hash is computed when first asked for, as the reference's field is lazy, so a runnable whose
  * hash is known ([[withScriptHash]]) never computes it.
  */
final class PlutusRunnable[L <: Language] private (
    val plutusRunnableBinary: Plutus[L],
    scriptHash: => Bytes
):
  /** `plutusRunnableScriptHash`, lazy. */
  lazy val plutusRunnableScriptHash: Bytes = scriptHash

  /** The same script with `hash` as its hash, as the reference's record update installs a known
    * one: the resolver's alone, with the key of the provided script
    * ([[AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose]]).
    */
  private[era] def withScriptHash(hash: Bytes): PlutusRunnable[L] =
    new PlutusRunnable(plutusRunnableBinary, hash)

  private def fields: Any = (plutusRunnableBinary, plutusRunnableScriptHash)
  override def equals(that: Any): Boolean = that match
    case o: PlutusRunnable[?] => fields == o.fields
    case _                    => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"PlutusRunnable($fields)"

object PlutusRunnable:
  given [L <: Language]: CanEqual[PlutusRunnable[L], PlutusRunnable[L]] = CanEqual.derived

  /** A runnable of `plutus` whose hash is `scriptHash`, which the caller vouches for: the package's
    * own, for a hash already known.
    */
  private[era] def apply[L <: Language](plutus: Plutus[L], scriptHash: Bytes): PlutusRunnable[L] =
    new PlutusRunnable(plutus, scriptHash)

  /** `hashPlutusScript` (`Plutus/Language.hs:200-204`, cardano-ledger f649f975): blake2b-224 of the
    * language's tag and the binary.
    */
  def hashPlutusScript[L <: Language](plutus: Plutus[L])(using l: PlutusLanguage[L]): Bytes =
    Blake2b.hash224.hash(Bytes(Vector(l.tag)) ++ plutus.binary)

  /** `decodePlutusRunnable` (`Plutus/Language.hs:470-475` and each instance, cardano-ledger
    * f649f975): the script with its hash. The reference decodes it for `version` there, lazily;
    * here the decoder is asked where the script is checked or run, so the version is not used.
    */
  def decodePlutusRunnable[L <: Language](@unused version: Version, plutus: Plutus[L])(using
      PlutusLanguage[L]
  ): PlutusRunnable[L] =
    new PlutusRunnable(plutus, hashPlutusScript(plutus))

/** A runnable script of a language era `E` supports, with its translation (`data
  * SupportedPlutusRunnable era`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/Context.hs:
  * 295-308`, cardano-ledger f649f975): equal when the language and the script hash are, as the
  * reference's `Eq` takes them.
  */
sealed abstract class SupportedPlutusRunnable[E <: EraTag]:
  type L <: Language
  def plutusTxInfo: EraPlutusTxInfo[L, E]
  def plutusRunnable: PlutusRunnable[L]

  /** The language, as a value (`plutusLanguage` of the runnable). */
  final def language: Lang = plutusTxInfo.plutusLanguage.language

  /** The same runnable with `scriptHash` as its hash, as the reference's record update does where
    * the hash is known: the resolver's alone, with the key of the provided script
    * ([[AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose]]).
    */
  private[era] final def withScriptHash(scriptHash: Bytes): SupportedPlutusRunnable[E] =
    SupportedPlutusRunnable(
      plutusTxInfo,
      plutusRunnable.withScriptHash(scriptHash)
    )

  private def key: (Lang, Bytes) = (language, plutusRunnable.plutusRunnableScriptHash)
  override def equals(that: Any): Boolean = that match
    case o: SupportedPlutusRunnable[?] => key == o.key
    case _                             => false
  override def hashCode(): Int = key.hashCode
  override def toString: String =
    s"SupportedPlutusRunnable($language, ${plutusRunnable.plutusRunnableScriptHash.hex})"

object SupportedPlutusRunnable:
  /** `SupportedPlutusRunnable`, of a script whose language's translation `t` is. */
  def apply[E <: EraTag, L0 <: Language](
      t: EraPlutusTxInfo[L0, E],
      runnable: PlutusRunnable[L0]
  ): SupportedPlutusRunnable[E] =
    new SupportedPlutusRunnable[E]:
      type L = L0
      def plutusTxInfo: EraPlutusTxInfo[L0, E] = t
      def plutusRunnable: PlutusRunnable[L0] = runnable

  given [E <: EraTag]: CanEqual[SupportedPlutusRunnable[E], SupportedPlutusRunnable[E]] =
    CanEqual.derived

/** The cost model of language `L` (`data CostModel`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/CostModels.hs:105-122`, cardano-ledger
  * f649f975): its parameters, each an `Int64`, equal as the reference's `Eq` takes it, by the
  * language and the parameters. The language is in the type, so a model is the one of the script it
  * is given with; it is made only from the parameters' models ([[lookupCostModel]]). The reference
  * also holds the evaluation context the parameters make (`mkCostModel`); here the evaluator makes
  * it ([[EvalRequest]]).
  */
final class CostModel[L <: Language] private (val cmValues: Vector[Long])(using
    l: PlutusLanguage[L]
):
  /** `cmLanguage`. */
  def cmLanguage: Lang = l.language

  private def fields: Any = (cmLanguage, cmValues)
  override def equals(that: Any): Boolean = that match
    case o: CostModel[?] => fields == o.fields
    case _               => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"CostModel($fields)"

object CostModel:
  given [L <: Language]: CanEqual[CostModel[L], CostModel[L]] = CanEqual.derived

  /** The model of language `L` among the valid ones (`Map.lookup lang (costModelsValid
    * costModels)`, `costModelsValid` at `CostModels.hs:391-392`, cardano-ledger f649f975). The
    * era's [[CostModels]] holds the valid and the unknown models in one map, by tag (their split is
    * #85's item on the reference's order of the parameters); a known language's model is always
    * valid there, as the reference's decoder refuses one `mkCostModel` refuses
    * (`mkCostModelsLenient`, `CostModels.hs:426-440`, cardano-ledger f649f975) and, from version 9,
    * `mkCostModel` refuses no list of `Int64` ([[xyz.kd5ujc.scarab.ledger.CostModelDecode]]). So
    * the model under a known language's tag is its valid model.
    */
  def lookupCostModel[L <: Language](costModels: CostModels)(using
      l: PlutusLanguage[L]
  ): Option[CostModel[L]] =
    lookupCostModelIn[L](costModels.int64Model)

  /** [[lookupCostModel]] in the models `int64` gives, by tag, as `Int64` s
    * ([[CostModels.int64Model]]).
    */
  def lookupCostModelIn[L <: Language](int64: Int => Option[Vector[Long]])(using
      l: PlutusLanguage[L]
  ): Option[CostModel[L]] =
    int64(l.language.id).map(new CostModel[L](_))

/** A Plutus script with everything its evaluation needs (`data PlutusWithContext`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Evaluate.hs:97-115`, cardano-ledger
  * f649f975): the major protocol version, the script, its arguments, its budget and the cost model
  * of its language. Equal as the reference's `Eq` takes it (`:128-140`), the script by its
  * language, binary and hash.
  */
sealed abstract class PlutusWithContext:
  type L <: Language
  def plutusLanguage: PlutusLanguage[L]
  def pwcProtocolVersion: Version
  def pwcScript: PlutusRunnable[L]
  def pwcArgs: PlutusArgs[L]
  def pwcExUnits: ExUnits
  def pwcCostModel: CostModel[L]

  private def fields: Any =
    (plutusLanguage.language, pwcProtocolVersion, pwcScript, pwcArgs, pwcExUnits, pwcCostModel)
  override def equals(that: Any): Boolean = that match
    case o: PlutusWithContext => fields == o.fields
    case _                    => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"PlutusWithContext($fields)"

object PlutusWithContext:
  /** `PlutusWithContext`, of a script of language `L0`. */
  def apply[L0 <: Language](
      protocolVersion: Version,
      script: PlutusRunnable[L0],
      args: PlutusArgs[L0],
      exUnits: ExUnits,
      costModel: CostModel[L0]
  )(using l: PlutusLanguage[L0]): PlutusWithContext =
    new PlutusWithContext:
      type L = L0
      def plutusLanguage: PlutusLanguage[L0] = l
      def pwcProtocolVersion: Version = protocolVersion
      def pwcScript: PlutusRunnable[L0] = script
      def pwcArgs: PlutusArgs[L0] = args
      def pwcExUnits: ExUnits = exUnits
      def pwcCostModel: CostModel[L0] = costModel

  given CanEqual[PlutusWithContext, PlutusWithContext] = CanEqual.derived

  /** The request the `plutus` module's evaluator takes for `pwc`: what the reference's
    * `evaluatePlutusWithContext` hands Plutus (`Evaluate.hs:382-392`, cardano-ledger f649f975), the
    * arguments as the language applies them ([[PlutusLanguage.plutusArgsToData]]) and the model of
    * the script's language.
    */
  def evalRequest(pwc: PlutusWithContext): EvalRequest =
    EvalRequest(
      pwc.plutusLanguage.language,
      pwc.pwcProtocolVersion.value,
      pwc.pwcScript.plutusRunnableBinary.binary,
      pwc.plutusLanguage.plutusArgsToData(pwc.pwcArgs),
      pwc.pwcCostModel.cmValues,
      pwc.pwcExUnits
    )

/** Why the scripts of a transaction of era `E` cannot be collected with their contexts (`data
  * CollectError era`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/Context.hs:357-361`,
  * cardano-ledger f649f975). The reference gives `NoWitness` nowhere: a needed script that is not
  * provided is not collected (`resolveNeededPlutusScriptsWithPurpose`), and UTXOW refuses it.
  */
enum CollectError[E <: EraTag]:
  case NoRedeemer(purpose: PlutusPurpose[AsItem, E])
  case NoWitness(scriptHash: Bytes)
  case NoCostModel(language: Lang)
  case BadTranslation(error: ContextError[E])

object CollectError:
  given [E <: EraTag]: CanEqual[CollectError[E], CollectError[E]] = CanEqual.derived

/** Alonzo's collection of the Plutus scripts of a transaction with their contexts
  * (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/Evaluate.hs`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/TxInfo.hs:100-127`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/UTxO.hs:431-465`, cardano-ledger f649f975), for an
  * era whose purposes have the voting and proposing ones ([[ConwayEraScript]], whose
  * `hoistPlutusPurpose` is the reference's `AlonzoEraScript` member).
  */
object AlonzoPlutusEvaluate:
  private type Used[E <: EraTag] = (PlutusPurpose[AsIxItem, E], SupportedPlutusRunnable[E])

  private def asItem[E <: EraTag](p: PlutusPurpose[AsIxItem, E])(using
      s: ConwayEraScript[E]
  ): PlutusPurpose[AsItem, E] =
    s.hoistPlutusPurpose[
      AsIxItem,
      AsItem
    ]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a))(p)

  private def asIx[E <: EraTag](p: PlutusPurpose[AsIxItem, E])(using
      s: ConwayEraScript[E]
  ): PlutusPurpose[AsIx, E] =
    s.hoistPlutusPurpose[AsIxItem, AsIx]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a))(p)

  /** `resolveNeededPlutusScriptsWithPurpose` (`Alonzo/UTxO.hs:431-465`, cardano-ledger f649f975):
    * the cache with the scripts provided added, the cached one kept where both have a hash, and
    * each needed purpose whose script is a provided Plutus script, with the cached runnable if
    * there is one. The cache must have been made at the same protocol version. The keys of the
    * scripts provided and of the cache are their scripts' hashes, trusted as the reference trusts
    * them: a runnable taken from the scripts provided gets its key as its hash, unchecked.
    */
  def resolveNeededPlutusScriptsWithPurpose[E <: EraTag](
      protVer: ProtVer,
      scriptsProvided: ScriptsProvided[E],
      scriptsNeeded: AlonzoScriptsNeeded[E],
      plutusScriptsCache: Map[Bytes, SupportedPlutusRunnable[E]]
  )(using
      s: AlonzoEraScript[E],
      c: EraPlutusContext[E]
  ): (Map[Bytes, SupportedPlutusRunnable[E]], List[Used[E]]) =
    val version = protVer.pvMajor
    val plutusScriptsProvided = scriptsProvided.unScriptsProvided.flatMap((hash, script) =>
      s.toPlutusScript(script).map(p => hash -> c.mkSupportedPlutusRunnable(version, p))
    )
    // `Map.union` keeps the left map's value for a key both have
    val updatedPlutusScriptCache = plutusScriptsProvided ++ plutusScriptsCache
    // the provided scripts are looked in first: the cache can hold scripts of earlier
    // transactions of the block
    def lookupPlutusScriptRunnable(hash: Bytes): Option[SupportedPlutusRunnable[E]] =
      plutusScriptsProvided
        .get(hash)
        .map(runnable => plutusScriptsCache.getOrElse(hash, runnable.withScriptHash(hash)))
    val neededPlutusScriptsWithPurpose = scriptsNeeded.unAlonzoScriptsNeeded.toList.flatMap(
      (purpose, hash) => lookupPlutusScriptRunnable(hash).map(purpose -> _)
    )
    (updatedPlutusScriptCache, neededPlutusScriptsWithPurpose)

  /** `mkPlutusWithContext` (`Alonzo/Plutus/TxInfo.hs:102-130`, cardano-ledger 9df9f98c): the script
    * with the arguments of its language for the purpose, from the language's memoised information,
    * of a transaction of any level. The arguments read the spending datum themselves
    * (`toPlutusArgs`), where f649f975's reads it here (`:100-127`).
    */
  def mkPlutusWithContext[E <: EraTag, T <: TxLevel](script: SupportedPlutusRunnable[E])(
      plutusPurpose: PlutusPurpose[AsIxItem, E],
      lti: LedgerTxInfo[T, E],
      txInfoResult: TxInfoResult[E],
      redeemerData: Data,
      exUnits: ExUnits,
      costModel: CostModel[script.L]
  )(using c: EraPlutusContext[E]): Either[ContextError[E], PlutusWithContext] =
    val t: EraPlutusTxInfo[script.L, E] = script.plutusTxInfo
    for
      txInfo <- c.lookupTxInfoResult[script.L](txInfoResult)(using t).unPlutusTxInfoResult
      plutusArgs <- t.toPlutusArgs(lti, txInfo, plutusPurpose, redeemerData)
    yield PlutusWithContext[script.L](
      lti.ltiProtVer.pvMajor,
      script.plutusRunnable,
      plutusArgs,
      exUnits,
      costModel
    )(using t.plutusLanguage)

  /** `scriptsWithContextFromLedgerTxInfo` (`Alonzo/Plutus/Evaluate.hs:120-129`, cardano-ledger
    * 9df9f98c): with the era's memoised information of the transaction.
    */
  def scriptsWithContextFromLedgerTxInfo[E <: EraTag, T <: TxLevel](
      lti: LedgerTxInfo[T, E],
      costModels: CostModels
  )(using
      x: AlonzoEraTx[E],
      s: ConwayEraScript[E],
      c: EraPlutusContext[E]
  ): Either[NonEmptyList[CollectError[E]], List[PlutusWithContext]] =
    scriptsWithContextFromLedgerTxInfoWithResult(lti, c.mkTxInfoResult(lti), costModels)

  /** `scriptsWithContextFromLedgerTxInfoWithResult` (`Alonzo/Plutus/Evaluate.hs:131-182`,
    * cardano-ledger 9df9f98c): each script the information uses (`ltiScriptsUsed`) with its
    * redeemer, its language's cost model and its context, every failure collected. As the
    * reference's `merge`, the successes come in reverse order, once there is a failure no context
    * is made, and the failures come in reverse order too. f649f975's takes the transaction's
    * information without the scripts used, which its callers resolve (`:131-185`).
    */
  def scriptsWithContextFromLedgerTxInfoWithResult[E <: EraTag, T <: TxLevel](
      lti: LedgerTxInfo[T, E],
      txInfoResult: TxInfoResult[E],
      costModels: CostModels
  )(using
      x: AlonzoEraTx[E],
      s: ConwayEraScript[E],
      c: EraPlutusContext[E]
  ): Either[NonEmptyList[CollectError[E]], List[PlutusWithContext]] =
    type Found = (SupportedPlutusRunnable[E], PlutusPurpose[AsIxItem, E], Data, ExUnits)
    val redeemers = x.witsOf
      .rdmrsTxWits(x.wits[T](lti.ltiTx)(using LedgerTxInfo.toSTxLevel(lti)(using x.bodyOf)))
      .entries
    // each language's model read as `Int64` s once for the transaction's scripts, not for each
    // script (#85)
    val int64 = costModels.models.keySet.iterator
      .map(tag => tag -> Eval.later(costModels.int64Model(tag)))
      .toMap
    def getScriptWithRedeemer(used: Used[E]): Either[CollectError[E], Found] =
      val (plutusPurpose, plutusScriptRunnable) = used
      redeemers.get(asIx(plutusPurpose)) match
        case Some((d, exUnits)) => Right((plutusScriptRunnable, plutusPurpose, d, exUnits))
        case None               => Left(CollectError.NoRedeemer(asItem(plutusPurpose)))
    def apply(found: Found): Either[CollectError[E], PlutusWithContext] =
      val (plutusScriptRunnable, plutusPurpose, redeemerData, exUnits) = found
      for
        costModel <- CostModel
          .lookupCostModelIn[plutusScriptRunnable.L](tag => int64.get(tag).flatMap(_.value))(using
            plutusScriptRunnable.plutusTxInfo.plutusLanguage
          )
          .toRight(CollectError.NoCostModel(plutusScriptRunnable.language))
        pwc <- mkPlutusWithContext(plutusScriptRunnable)(
          plutusPurpose,
          lti,
          txInfoResult,
          redeemerData,
          exUnits,
          costModel
        ).left.map(CollectError.BadTranslation(_))
      yield pwc
    merge(apply)(lti.ltiScriptsUsed.map(getScriptWithRedeemer), Right(Nil))

  /** `merge` (`Alonzo/Plutus/Evaluate.hs:168-182`, cardano-ledger 9df9f98c): the failures of `xs`
    * and of `f`, once one is found, or else every success, each put in front.
    */
  private def merge[T, B, A](f: T => Either[A, B])(
      xs: List[Either[A, T]],
      answer: Either[NonEmptyList[A], List[B]]
  ): Either[NonEmptyList[A], List[B]] =
    xs.foldLeft(answer) { (zs, x) =>
      (x, zs) match
        case (Right(t), Right(cs)) => f(t).fold(e => Left(NonEmptyList.one(e)), c => Right(c :: cs))
        case (Left(a), Right(_))   => Left(NonEmptyList.one(a))
        case (Right(_), Left(cs))  => Left(cs)
        case (Left(a), Left(cs))   => Left(a :: cs)
    }

  /** `collectPlutusScriptsWithContext` (`Alonzo/Plutus/Evaluate.hs:83-118`, cardano-ledger
    * 9df9f98c): the scripts the top-level transaction needs and provides, with their contexts, at
    * the parameters' protocol version and with their cost models, and no sub-transaction's
    * information (`LedgerTopTxInfo mempty`). The epoch information and the system start are the one
    * clock function of [[LedgerTxInfo]].
    */
  def collectPlutusScriptsWithContext[E <: EraTag](
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      pp: PParams[E],
      tx: Tx[TxLevel.Top, E],
      utxo: UTxO[E]
  )(using
      p: AlonzoEraPParams[E],
      x: AlonzoEraTx[E],
      u: AlonzoEraUTxO[E],
      s: ConwayEraScript[E],
      c: EraPlutusContext[E],
      needed: ScriptsNeeded[E] =:= AlonzoScriptsNeeded[E]
  ): Either[NonEmptyList[CollectError[E]], List[PlutusWithContext]] =
    val protVer = p.ppProtocolVersionL.get(pp)
    val top = u.txOf.bodyOf.top
    val (_, plutusScriptsUsed) = resolveNeededPlutusScriptsWithPurpose(
      protVer,
      u.getScriptsProvided[TxLevel.Top](utxo, tx)(using top),
      needed(
        u.getScriptsNeeded[TxLevel.Top](utxo, u.txOf.body[TxLevel.Top](tx)(using top))(using top)
      ),
      Map.empty
    )
    val ledgerTxInfo = LedgerTxInfo(
      protVer,
      slotToPOSIXTime,
      utxo,
      tx,
      plutusScriptsUsed,
      LedgerLevelTxInfo.LedgerTopTxInfo(Map.empty)
    )
    val costModels = PParams.lensHKD[E].andThen(p.hkdCostModelsL[Identity]).get(pp)
    scriptsWithContextFromLedgerTxInfo(ledgerTxInfo, costModels)

/** Why a Plutus script failed (`data ScriptFailure`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Evaluate.hs:146-150`, cardano-ledger
  * f649f975): the evaluator's failure and the script with its context. The reference's
  * `scriptFailureMessage` is a text it writes from the failure (`explainPlutusEvaluationError`,
  * `:401-420`); here the failure keeps its type ([[xyz.kd5ujc.scarab.plutus.EvalFailure]]), and the
  * text is written where it is shown.
  */
final case class ScriptFailure(
    scriptFailureCause: EvalFailure,
    scriptFailurePlutus: PlutusWithContext
)

/** The outcome of running Plutus scripts (`data ScriptResult`, `Evaluate.hs:152-181`,
  * cardano-ledger f649f975): the scripts that passed, and the failures if any failed. Results are
  * put together in the order of the scripts, as the reference's `Semigroup` and `Monoid` put them.
  */
enum ScriptResult:
  case Passes(passed: List[PlutusWithContext])
  case Fails(passed: List[PlutusWithContext], failures: NonEmptyList[ScriptFailure])

object ScriptResult:
  /** `scriptPass` (`Evaluate.hs:157-158`, cardano-ledger f649f975). */
  def scriptPass(pwc: PlutusWithContext): ScriptResult = Passes(List(pwc))

  /** `scriptFail` (`Evaluate.hs:160-161`, cardano-ledger f649f975). */
  def scriptFail(sf: ScriptFailure): ScriptResult = Fails(Nil, NonEmptyList.one(sf))

  /** `instance Semigroup ScriptResult` and `Monoid` (`Evaluate.hs:174-181`, cardano-ledger
    * f649f975).
    */
  given Monoid[ScriptResult] with
    def empty: ScriptResult = Passes(Nil)
    def combine(x: ScriptResult, y: ScriptResult): ScriptResult = (x, y) match
      case (Passes(ps), Passes(qs))       => Passes(ps ++ qs)
      case (Passes(ps), Fails(qs, xs))    => Fails(ps ++ qs, xs)
      case (Fails(ps, xs), Passes(qs))    => Fails(ps ++ qs, xs)
      case (Fails(ps, xs), Fails(qs, ys)) => Fails(ps ++ qs, xs.concatNel(ys))

/** A script the evaluator gave no verdict for
  * ([[xyz.kd5ujc.scarab.plutus.EvalFailure.Unsupported]]): a limit or a defect of this
  * implementation, which the reference does not have. It is a defect, never a script's failure, so
  * the transaction it is in has no verdict.
  */
final case class ScriptVerdictUnavailable(
    plutus: PlutusWithContext,
    reason: EvalFailure.Unsupported
)

/** The evaluation of Plutus scripts with their contexts, through the evaluator the `plutus` module
  * gives ([[xyz.kd5ujc.scarab.plutus.ScriptEvaluator]]).
  */
object PlutusEvaluation:
  /** `runPlutusScript` (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Evaluate.hs:370-376`,
    * cardano-ledger f649f975): the script run with its arguments, its cost model and its budget at
    * its protocol version ([[PlutusWithContext.evalRequest]]), a pass or a failure; no verdict is
    * raised as [[ScriptVerdictUnavailable]].
    */
  def runPlutusScript[F[_]: Monad](pwc: PlutusWithContext)(using
      evaluator: ScriptEvaluator[F],
      raise: Raise[F, ScriptVerdictUnavailable]
  ): F[ScriptResult] =
    evaluator.evaluate(PlutusWithContext.evalRequest(pwc)).flatMap {
      case EvalResult.Success(_, _) => ScriptResult.scriptPass(pwc).pure[F]
      case EvalResult.Failure(reason: EvalFailure.Unsupported, _, _) =>
        raise.raise(ScriptVerdictUnavailable(pwc, reason))
      case EvalResult.Failure(reason, _, _) =>
        ScriptResult.scriptFail(ScriptFailure(reason, pwc)).pure[F]
    }

  /** `evalPlutusScripts` (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/Evaluate.hs:188-207`,
    * cardano-ledger f649f975): each script run in turn, their results put together in order. The
    * first script with no verdict ends the evaluation.
    */
  def evalPlutusScripts[F[_]: Monad](pwcs: List[PlutusWithContext])(using
      ScriptEvaluator[F],
      Raise[F, ScriptVerdictUnavailable]
  ): F[ScriptResult] =
    pwcs.foldLeftM(Monoid[ScriptResult].empty)((results, pwc) =>
      runPlutusScript[F](pwc).map(results |+| _)
    )

/** Where UTXOS evaluates the scripts of a transaction it collected them for: Scarab's own, which
  * the reference has no counterpart for, since it evaluates them there (`evalPlutusScripts`, then
  * the verdict checked against the flag, `Babbage/Rules/Utxos.hs:130-155` and `:192-222`,
  * cardano-ledger f649f975). A block run evaluates them there too ([[Phase2Deferral.now]]), but
  * while the node prepares a block whose scripts it evaluates in bounded parallel workers (ADR
  * 0018) the run records them and evaluates none, which supplies no verdict: the rules go on as the
  * transaction's flag announces, and the block's plan checks every verdict after, in order. UTXOS
  * also tells it of a transaction whose scripts it could not collect, before it records why
  * ([[uncollected]]), so that a block run observes that transaction for the node as it observes one
  * whose scripts ran. It is told the annotated transaction the scripts were collected from, so that
  * a run can say which level of a Dijkstra batch and which redeemer a script is (#85).
  */
trait Phase2Deferral[F[_], E <: EraTag]:
  /** The scripts `scripts` of the annotated transaction `stAnnTx`, collected from it
    * (`plutusScriptsWithContextStAnnTx`), which `now` evaluates and checks.
    */
  def evalScripts(stAnnTx: StAnnTx[TxLevel.Top, E], scripts: NonEmptyList[PlutusWithContext])(
      now: F[Unit]
  ): F[Unit]

  /** `tx`, whose scripts UTXOS could not collect with their contexts, told before UTXOS records
    * their collection's failures (`CollectErrors`): what the run does with it changes no rule's
    * result.
    */
  def uncollected(tx: Tx[TxLevel.Top, E]): F[Unit]

object Phase2Deferral:
  /** The scripts evaluated where they are collected, as the reference evaluates them, and a
    * transaction whose scripts could not be collected told to no one.
    */
  def now[F[_]: Applicative, E <: EraTag]: Phase2Deferral[F, E] = new Phase2Deferral[F, E]:
    def evalScripts(stAnnTx: StAnnTx[TxLevel.Top, E], scripts: NonEmptyList[PlutusWithContext])(
        now: F[Unit]
    ): F[Unit] = now
    def uncollected(tx: Tx[TxLevel.Top, E]): F[Unit] = Applicative[F].unit
