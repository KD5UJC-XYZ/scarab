// SPDX-License-Identifier: Apache-2.0
package lab.node

import io.circe.Json
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import lab.node.ledger.*

/** Records phase-2 evaluations as self-contained JSON cases (the transaction, the outputs it spends
  * and references, the cost models and the clock samples) so they can be replayed offline as test
  * fixtures. A recorder keeps at most `perKind` cases of each kind (languages, validity, features)
  * and `max` overall, and is only used by tooling (`follow --plutus-cases <dir>`).
  */
final class PlutusCases(dir: Path, perKind: Int, max: Int):
  // Deliberate instance-local mutable state, not global: the phase-2 observer is a synchronous
  // callback (`Observation => Unit`) invoked from the pure evaluator, outside any effect, so the
  // counters cannot live in a Ref. Every access goes through `observe`, which is synchronized, and
  // the instance exists only when `--plutus-cases` is given (tooling, off by default).
  private val seen = collection.mutable.Map.empty[String, Int]
  private var written = 0

  def observe(o: Phase2.Observation): Unit = synchronized {
    val body = o.tx.body
    val languages = o.tx.witnesses.scripts.filter(_.kind.isPlutus).map(_.kind.tag).distinct.sorted
    val outs = (o.spent.values ++ o.references.values ++ body.outputs).toVector
    val features = Vector(
      "ref" -> body.referenceInputs.nonEmpty,
      "inline" -> outs.exists(_.datum.isInstanceOf[Datum.Inline]),
      "refscript" -> outs.exists(_.scriptRef.isDefined),
      "mint" -> body.mint.nonEmpty,
      "cert" -> body.certificates.nonEmpty,
      "wdrl" -> body.withdrawals.nonEmpty,
      "signers" -> body.requiredSigners.nonEmpty,
      "ttl" -> (body.ttl.isDefined || body.validityStart.isDefined),
      "multi" -> (o.tx.witnesses.redeemers.size > 1)
    ).collect { case (n, true) => n }
    val outcome = o.outcome match
      case Right(Phase2.Outcome.Passed(_))         => "passed"
      case Right(Phase2.Outcome.Failed(_, r, why)) => s"failed ${r.tag}.${r.index} ${why.message}"
      case Left(e)                                 => s"translation ${e.reason}"
    val kind =
      s"v${languages.mkString("")}-${if o.tx.isValid then "valid" else "invalid"}-${features.mkString(",")}-${outcome.take(40)}"
    val count = seen.getOrElse(kind, 0)
    if count < perKind && written < max then
      seen.update(kind, count + 1)
      written += 1
      val name = s"slot${o.slot}-${body.id.hex.take(8)}"
      val slots = (body.validityStart ++ body.ttl).toVector.distinct
      val clock =
        o.clock.fold(Json.Null)(c => Json.obj(slots.map(s => s.toString -> c(s).toString.asJson)*))
      def utxo(m: Map[TxIn, TxOut]) =
        m.toVector.map { case (in, out) =>
          Json.obj("in" -> in.render.asJson, "out" -> out.raw.hex.asJson)
        }.asJson
      val json = Json.obj(
        "name" -> name.asJson,
        "kind" -> kind.asJson,
        "slot" -> o.slot.toString.asJson,
        "protocolMajor" -> o.params.protocolMajor.toInt.asJson,
        "isValid" -> o.tx.isValid.asJson,
        "outcome" -> outcome.asJson,
        "body" -> body.raw.hex.asJson,
        "witnesses" -> o.tx.witnesses.raw.map(_.hex).asJson,
        "auxiliary" -> o.tx.auxiliary.map(_.hex).asJson,
        "spent" -> utxo(o.spent),
        "references" -> utxo(o.references),
        "costModels" -> Json.obj(
          o.params.costModels.toVector.sortBy(_._1).map { case (l, m) =>
            l.toString -> m.map(_.toString).asJson
          }*
        ),
        "clock" -> clock
      )
      Files.writeString(dir.resolve(s"$name.json"), json.spaces2, StandardCharsets.UTF_8)
  }
