// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import io.circe.Json
import io.circe.syntax.*
import cats.syntax.all.*
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoScripts,
  CollectError as EraCollectError,
  Datum as EraDatum,
  EraTx,
  EraTxBody,
  EraTxOut,
  IsPhase2Valid,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.rules.{ConwayPhase2, DijkstraPhase2}
import xyz.kd5ujc.scarab.ledger.state.{Conway, EraTag}
import xyz.kd5ujc.scarab.plutus.{EvalFailure, EvalRequest, EvalResult}
import xyz.kd5ujc.scarab.uplc.builtin.core.DataSerialisation

/** Records phase-2 evaluations as self-contained JSON cases (the transaction, the outputs it spends
  * and references, the cost models and the clock samples) so they can be replayed offline as test
  * fixtures. A recorder keeps at most `perKind` cases of each kind (languages, validity, features)
  * and `max` overall, and is only used by tooling (`follow --plutus-cases <dir>`).
  *
  * A Conway transaction's case ([[observeConway]]) is read from the era's transaction, and is the
  * one the flat transaction of the same bytes gives ([[observe]]): its collection's failures named
  * in the flat vocabulary (`ConwayPhase2.storedErrors`).
  *
  * A Dijkstra batch's case ([[observeDijkstra]]) is marked `"era": "dijkstra"` and holds, beside
  * the batch's bytes, each script's request as the evaluator was asked it (the script, its
  * arguments, the cost model, the protocol version and the redeemer's units) with its result, in
  * place of the outputs and the clock, which a Dijkstra observation does not hold
  * (`DijkstraPhase2.Observation`), named by the block's slot, the batch's whole id and its flag.
  *
  * Recording a case never fails the observer that calls it, which the rules call while they judge a
  * block: a case that cannot be written, or a failure of the recorder itself, is kept
  * ([[PlutusCases.Failure]]) for the node to report ([[failed]], `NodeEvent.PlutusCaseNotWritten`)
  * and the block's result is the one it is without a recorder.
  */
final class PlutusCases(dir: Path, perKind: Int, max: Int):
  // Instance-local state, not global: the phase-2 observer is a synchronous callback
  // (`Observation => Unit`) invoked from the pure evaluator, outside any effect, so the counts
  // cannot live in a Ref. They are an immutable value in an atomic reference; every access goes
  // through `observe`, which is synchronized, and the instance exists only when `--plutus-cases`
  // is given (tooling, off by default).
  private final case class Written(seen: Map[String, Int], total: Int)
  private val written = new java.util.concurrent.atomic.AtomicReference(Written(Map.empty, 0))
  // the cases not written since the node last asked ([[failed]]), kept as the counts are
  private val failures =
    new java.util.concurrent.atomic.AtomicReference(Vector.empty[PlutusCases.Failure])

  /** Records the observation of a transaction of an era before Conway; never fails. */
  def observe(o: Phase2.Observation): Unit = contained(recordFlat(o))

  /** Records the observation of a Conway transaction ([[recordConway]]); never fails. */
  def observeConway(o: ConwayPhase2.Observation): Unit = contained(recordConway(o))

  /** Records the observation of a Dijkstra batch ([[recordDijkstra]]); never fails. */
  def observeDijkstra(o: DijkstraPhase2.Observation): Unit = contained(recordDijkstra(o))

  /** The cases not written since the last call, in order, and none kept after. */
  def failed(): Vector[PlutusCases.Failure] = failures.getAndSet(Vector.empty)

  // one recording at a time; whatever it fails with is kept, not thrown into the rules
  private def contained(record: => Option[PlutusCases.Failure]): Unit = synchronized {
    Either
      .catchNonFatal(record)
      .fold(cause => Some(PlutusCases.Failure.Recorder(cause)), identity)
      .foreach(failure => failures.updateAndGet(_ :+ failure))
  }

  // the case written into `file`, or why it was not
  private def write(file: Path, json: Json): Option[PlutusCases.Failure] =
    Either
      .catchOnly[IOException](Files.writeString(file, json.spaces2, StandardCharsets.UTF_8))
      .left
      .toOption
      .map(PlutusCases.Failure.Write(file, _))

  /** Records the observation of a transaction of an era before Dijkstra, read from the flat
    * transaction it holds.
    */
  private def recordFlat(o: Phase2.Observation): Option[PlutusCases.Failure] =
    val body = o.tx.body
    val outs = (o.spent.values ++ o.references.values ++ body.outputs).toVector
    recordShelley(
      PlutusCases.Shelley(
        o.tx.witnesses.scripts.filter(_.kind.isPlutus).map(_.kind.tag).distinct.sorted,
        o.tx.isValid,
        PlutusCases.Features(
          ref = body.referenceInputs.nonEmpty,
          inline = outs.exists(PlutusCases.inline),
          refscript = outs.exists(_.scriptRef.isDefined),
          mint = body.mint.nonEmpty,
          cert = body.certificates.nonEmpty,
          wdrl = body.withdrawals.nonEmpty,
          signers = body.requiredSigners.nonEmpty,
          ttl = body.ttl.isDefined || body.validityStart.isDefined,
          multi = o.tx.witnesses.redeemers.sizeIs > 1
        ),
        o.outcome.leftMap(_.toVector.map(_.message)),
        body.id,
        o.slot,
        o.params,
        body.raw,
        o.tx.witnesses.raw,
        o.tx.auxiliary,
        o.spent,
        o.references,
        (body.validityStart ++ body.ttl).toVector,
        o.clock
      )
    )

  /** Records the observation of a Conway transaction, read from the era's transaction it holds as
    * [[recordFlat]] reads the flat one, so that its case is the one the flat transaction of the
    * same bytes gives: the languages of the Plutus scripts its witnesses hold, the features of its
    * body and of the outputs it spends, reads and creates, and its collection's failures named as
    * the flat collection's are ([[ConwayPhase2.storedErrors]]).
    */
  private def recordConway(o: ConwayPhase2.Observation): Option[PlutusCases.Failure] =
    val t = EraTx.conway
    val b = EraTxBody.conway
    val out = EraTxOut.conway
    given t.bodyOf.Level[TxLevel.Top] = t.bodyOf.top
    val body = t.body[TxLevel.Top](o.tx)
    val wits = t.wits[TxLevel.Top](o.tx)
    val created = b.outputs[TxLevel.Top](body)
    val stored = (o.spent.values ++ o.references.values).toVector
    val validity = b.vldt[TxLevel.Top](body)
    recordShelley(
      PlutusCases.Shelley(
        t.witsOf
          .scriptTxWits(wits)
          .values
          .flatMap(script =>
            out.scripts
              .toPlutusScript(script)
              .map(p => AlonzoScripts.plutusScriptLanguage[Conway](p)(using out.scripts).id + 1)
          )
          .toVector
          .distinct
          .sorted,
        t.isValid(o.tx) == IsPhase2Valid.Phase2Valid,
        PlutusCases.Features(
          ref = b.referenceInputs[TxLevel.Top](body).nonEmpty,
          inline = stored.exists(PlutusCases.inline) || created.exists(c =>
            out.datum(c) match
              case EraDatum.Datum(_)                        => true
              case EraDatum.NoDatum | EraDatum.DatumHash(_) => false
          ),
          refscript = stored.exists(_.scriptRef.isDefined) || created.exists(
            out.referenceScript(_).isDefined
          ),
          mint = b.mint[TxLevel.Top](body).nonEmpty,
          cert = b.certs[TxLevel.Top](body).nonEmpty,
          wdrl = b.withdrawals[TxLevel.Top](body).unWithdrawals.nonEmpty,
          signers = b.reqSignerHashesField[TxLevel.Top](body).nonEmpty,
          ttl = validity.invalidHereafter.isDefined || validity.invalidBefore.isDefined,
          multi = t.witsOf.rdmrsTxWits(wits).entries.sizeIs > 1
        ),
        o.outcome.leftMap(errors =>
          ConwayPhase2
            .storedErrors(o, errors)
            .toList
            .toVector
            .map(_.fold(PlutusCases.collectError, _.message))
        ),
        b.id[TxLevel.Top](body),
        o.slot,
        o.params,
        o.tx.body.raw,
        Some(o.tx.wits.raw),
        o.tx.auxData.map(_.raw),
        o.spent,
        o.references,
        (validity.invalidBefore ++ validity.invalidHereafter).toVector,
        o.clock
      )
    )

  /** Records the case of a transaction of an era before Dijkstra ([[PlutusCases.Shelley]]): its
    * kind is the languages of its Plutus scripts, its flag, its features and its outcome; its name
    * the block's slot and the first eight hex digits of its id.
    */
  private def recordShelley(o: PlutusCases.Shelley): Option[PlutusCases.Failure] = {
    val features = Vector(
      "ref" -> o.features.ref,
      "inline" -> o.features.inline,
      "refscript" -> o.features.refscript,
      "mint" -> o.features.mint,
      "cert" -> o.features.cert,
      "wdrl" -> o.features.wdrl,
      "signers" -> o.features.signers,
      "ttl" -> o.features.ttl,
      "multi" -> o.features.multi
    ).collect { case (n, true) => n }
    val outcome = o.outcome match
      case Right(Phase2.Outcome.Passed(_)) => "passed"
      case Right(Phase2.Outcome.Failed(_, failures)) =>
        failures.toVector
          .map(f => s"failed ${f.redeemer.tag}.${f.redeemer.index} ${f.reason.message}")
          .mkString("; ")
      case Right(Phase2.Outcome.Unavailable(_, r, why)) =>
        s"unavailable ${r.tag}.${r.index} ${why.message}"
      case Left(es) => s"translation ${es.mkString("; ")}"
    val kind =
      s"v${o.languages.mkString("")}-${if o.isValid then "valid" else "invalid"}-${features.mkString(",")}-${outcome.take(40)}"
    val now = written.get
    val count = now.seen.getOrElse(kind, 0)
    if count < perKind && now.total < max then
      written.set(Written(now.seen.updated(kind, count + 1), now.total + 1))
      val name = s"slot${o.slot}-${o.id.hex.take(8)}"
      val slots = o.validity.distinct
      val clock =
        o.clock.fold(Json.Null)(c =>
          Json.obj(
            slots.flatMap(s => c.posix(s).toOption.map(t => s.toString -> t.toString.asJson))*
          )
        )
      def utxo(m: Map[TxIn, TxOut]) =
        m.toVector.map { case (in, out) =>
          Json.obj("in" -> in.render.asJson, "out" -> out.raw.hex.asJson)
        }.asJson
      val json = Json.obj(
        "name" -> name.asJson,
        "kind" -> kind.asJson,
        "slot" -> o.slot.toString.asJson,
        "protocolMajor" -> o.params.protocolMajor.toInt.asJson,
        "isValid" -> o.isValid.asJson,
        "outcome" -> outcome.asJson,
        "body" -> o.body.hex.asJson,
        "witnesses" -> o.witnesses.map(_.hex).asJson,
        "auxiliary" -> o.auxiliary.map(_.hex).asJson,
        "spent" -> utxo(o.spent),
        "references" -> utxo(o.references),
        "costModels" -> Json.obj(
          o.params.costModels.toVector.sortBy(_._1).map { case (l, m) =>
            l.toString -> m.map(_.toString).asJson
          }*
        ),
        "clock" -> clock
      )
      write(dir.resolve(s"$name.json"), json)
    else None
  }

  /** Whether a Dijkstra batch's case of `kind` is written: fewer than `perKind` of it and `max` of
    * all so far, as `recordFlat` admits a case, and then counted. Called only within
    * `recordDijkstra`, under the recorder's lock.
    */
  private def admitted(kind: String): Boolean =
    val now = written.get
    val count = now.seen.getOrElse(kind, 0)
    val admit = count < perKind && now.total < max
    if admit then written.set(Written(now.seen.updated(kind, count + 1), now.total + 1))
    admit

  /** Records the observation of a Dijkstra batch: its bytes, and each script it ran with its
    * request and its result, or the failures of its scripts' collection. Its kind is the languages
    * run, the validity flag, whether a sub-transaction's script ran (`sub`) and more than one
    * script (`multi`), and the outcome.
    */
  private def recordDijkstra(o: DijkstraPhase2.Observation): Option[PlutusCases.Failure] = {
    val evaluations = o.outcome match
      case DijkstraPhase2.Outcome.Ran(es)                  => es.toVector
      case DijkstraPhase2.Outcome.Unavailable(es, _, _, _) => es
      case DijkstraPhase2.Outcome.Uncollected(_)           => Vector.empty
    val languages =
      (evaluations.map(_.request.language) ++ (o.outcome match
        case DijkstraPhase2.Outcome.Unavailable(_, _, request, _) => Vector(request.language)
        case DijkstraPhase2.Outcome.Ran(_) | DijkstraPhase2.Outcome.Uncollected(_) =>
          Vector.empty)).map(_.id + 1).distinct.sorted
    val located = evaluations.map(_.location) ++ (o.outcome match
      case DijkstraPhase2.Outcome.Unavailable(_, location, _, _)                 => Vector(location)
      case DijkstraPhase2.Outcome.Ran(_) | DijkstraPhase2.Outcome.Uncollected(_) => Vector.empty)
    val features = Vector(
      "sub" -> located.exists {
        case ScriptLocation.SubPhase2(_, _)                      => true
        case ScriptLocation.Phase2(_) | ScriptLocation.Phase1(_) => false
      },
      "multi" -> (located.sizeIs > 1)
    ).collect { case (n, true) => n }
    val outcome = o.outcome match
      case DijkstraPhase2.Outcome.Ran(es) =>
        val failed = es.toVector.collect {
          case DijkstraPhase2.Evaluation(location, _, EvalResult.Failure(reason, _, _)) =>
            s"failed ${PlutusCases.where(location)} ${reason.message}"
        }
        if failed.isEmpty then "passed" else failed.mkString("; ")
      case DijkstraPhase2.Outcome.Unavailable(_, location, _, why) =>
        s"unavailable ${PlutusCases.where(location)} ${why.message}"
      case DijkstraPhase2.Outcome.Uncollected(errors) =>
        s"uncollected ${errors.toList.map(PlutusCases.collectError).mkString("; ")}"
    val valid = if o.transaction.isValid then "valid" else "invalid"
    val kind =
      s"dijkstra-v${languages.mkString("")}-$valid-${features.mkString(",")}-${outcome.take(40)}"
    if admitted(kind) then
      // the batch's whole id and its flag: two batches at a slot never share a name
      val name = s"slot${o.slot}-${o.id.hex}-$valid"
      // a script with its request, and its result: passed, or failed with why, and what it spent;
      // or unavailable, with why, where the evaluator gave no verdict
      def script(
          location: ScriptLocation,
          request: EvalRequest,
          result: Either[EvalFailure.Unsupported, EvalResult]
      ): Json =
        val (sub, redeemer) = location match
          case ScriptLocation.SubPhase2(id, r) => (Some(id.hex), Some(r))
          case ScriptLocation.Phase2(r)        => (None, Some(r))
          case ScriptLocation.Phase1(_)        => (None, None)
        val (verdict, reason) = result match
          case Right(EvalResult.Success(_, _))      => ("passed", None)
          case Right(EvalResult.Failure(why, _, _)) => ("failed", Some(why.message))
          case Left(why)                            => ("unavailable", Some(why.message))
        val spent = result.toOption.map(_.spent)
        Json.obj(
          "subTransaction" -> sub.asJson,
          "tag" -> redeemer.map(_.tag).asJson,
          "index" -> redeemer.map(_.index.toString).asJson,
          "redeemer" -> redeemer.map(_.data.hex).asJson,
          "language" -> (request.language.id + 1).asJson,
          "protocol" -> request.protocolMajor.asJson,
          "script" -> request.script.hex.asJson,
          "arguments" -> request.args.map(a => DataSerialisation.serialise(a).hex).asJson,
          "costModel" -> request.costModel.asJson,
          "cpu" -> request.budget.steps.toString.asJson,
          "memory" -> request.budget.mem.toString.asJson,
          "result" -> verdict.asJson,
          "reason" -> reason.asJson,
          "spentCpu" -> spent.map(_.steps.toString).asJson,
          "spentMemory" -> spent.map(_.mem.toString).asJson
        )
      def ran(e: DijkstraPhase2.Evaluation): Json = script(e.location, e.request, Right(e.result))
      val scripts = o.outcome match
        case DijkstraPhase2.Outcome.Ran(es) => es.toVector.map(ran)
        case DijkstraPhase2.Outcome.Unavailable(es, location, request, why) =>
          es.map(ran) :+ script(location, request, Left(why))
        case DijkstraPhase2.Outcome.Uncollected(_) => Vector.empty
      val json = Json.obj(
        "name" -> name.asJson,
        "era" -> "dijkstra".asJson,
        "kind" -> kind.asJson,
        "slot" -> o.slot.toString.asJson,
        "isValid" -> o.transaction.isValid.asJson,
        "outcome" -> outcome.asJson,
        "body" -> o.transaction.body.hex.asJson,
        "witnesses" -> o.transaction.witnesses.hex.asJson,
        "auxiliary" -> o.transaction.auxiliaryData.map(_.hex).asJson,
        "scripts" -> scripts.asJson
      )
      write(dir.resolve(s"$name.json"), json)
    else None
  }

object PlutusCases:
  /** What the case of a transaction of an era before Dijkstra holds, read from its flat transaction
    * (`Phase2.Observation`) or its Conway one (`ConwayPhase2.Observation`) alike: the languages of
    * its witnesses' Plutus scripts (their tags), its flag, the features of its kind, what its
    * scripts came to (the collection's failures as text), its id, the block's slot, the parameters,
    * its bytes, the outputs it spends and reads, the slots of its validity interval, its start
    * first, and the clock.
    */
  private final case class Shelley(
      languages: Vector[Int],
      isValid: Boolean,
      features: Features,
      outcome: Either[Vector[String], Phase2.Outcome],
      id: Bytes,
      slot: BigInt,
      params: ProtocolParams,
      body: Bytes,
      witnesses: Option[Bytes],
      auxiliary: Option[Bytes],
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      validity: Vector[BigInt],
      clock: Option[PosixClock]
  )

  /** The features of a transaction a case's kind names: reference inputs, an inline datum or a
    * reference script among the outputs it spends, reads or creates, a mint, a certificate, a
    * withdrawal, a required signer, a validity interval, and more than one redeemer.
    */
  private final case class Features(
      ref: Boolean,
      inline: Boolean,
      refscript: Boolean,
      mint: Boolean,
      cert: Boolean,
      wdrl: Boolean,
      signers: Boolean,
      ttl: Boolean,
      multi: Boolean
  )

  /** Why a case was not written: its file could not be written (`Write`), or the recorder failed
    * otherwise (`Recorder`, a defect of this tool). Neither changes the block's result.
    */
  enum Failure:
    case Write(file: Path, cause: IOException)
    case Recorder(cause: Throwable)

  /** Whether the output `out`, as the node records it, holds an inline datum. */
  private def inline(out: TxOut): Boolean = out.datum match
    case Datum.Inline(_)               => true
    case Datum.NoDatum | Datum.Hash(_) => false

  /** Where a script of a Dijkstra batch is, as a case names it: its redeemer's tag and index, a
    * sub-transaction's script after the first eight hex digits of the sub-transaction's id.
    */
  private def where(location: ScriptLocation): String = location match
    case ScriptLocation.Phase2(r)          => s"${r.tag}.${r.index}"
    case ScriptLocation.SubPhase2(id, r)   => s"sub ${id.hex.take(8)}/${r.tag}.${r.index}"
    case ScriptLocation.Phase1(scriptHash) => s"native ${scriptHash.hex.take(8)}"

  /** A failure of the collection of an era's scripts, as a case describes it: a Dijkstra batch's,
    * and a Conway transaction's that has no flat form ([[ConwayPhase2.storedErrors]]).
    */
  private def collectError[E <: EraTag](error: EraCollectError[E]): String = error match
    case EraCollectError.NoRedeemer(purpose)     => s"no redeemer for ${purpose.toString}"
    case EraCollectError.NoWitness(scriptHash)   => s"script ${scriptHash.hex} is missing"
    case EraCollectError.NoCostModel(language)   => s"no cost model for ${language.toString}"
    case EraCollectError.BadTranslation(context) => s"no context: ${context.toString}"
