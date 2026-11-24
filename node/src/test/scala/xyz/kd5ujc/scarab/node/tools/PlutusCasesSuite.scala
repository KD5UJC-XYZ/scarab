// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.data.{NonEmptyList, NonEmptyVector}
import cats.syntax.all.*
import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{Block, Redeemer, ScriptLocation}
import xyz.kd5ujc.scarab.ledger.era.CollectError
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraPlanned, DijkstraRecordedBlocks}
import xyz.kd5ujc.scarab.ledger.era.rules.DijkstraPhase2
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.plutus.{
  BackendFailure,
  EvalFailure,
  EvalRequest,
  EvalResult,
  ExUnits,
  PlutusLanguage,
  ScarabEvaluator
}
import xyz.kd5ujc.scarab.uplc.PlutusData

/** The cases `--plutus-cases` writes of a Dijkstra block's batches (`PlutusCases.observeDijkstra`,
  * #85): what each holds, and that it is read back, each script's request evaluated again offline
  * to the result the case records. The cases of the other eras are written as before
  * (`PlutusCases.observe`, which this change leaves as it was).
  */
class PlutusCasesSuite extends munit.FunSuite:
  /** A written case, read back: its fields, and each script's request with what it recorded. */
  private final case class Read(
      json: Json,
      scripts: Vector[(EvalRequest, String, Option[ExUnits], Option[String], Option[Int])]
  )

  private def text(j: Json, field: String): String =
    j.hcursor.get[String](field).fold(e => fail(s"$field: $e"), identity)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(s"$e"), identity)

  /** A case file as its reader reads it: each script's request rebuilt from the case alone. */
  private def read(path: Path): Read =
    val json = parser.parse(Files.readString(path)).fold(e => fail(s"$path: $e"), identity)
    val scripts = json.hcursor
      .get[Vector[Json]]("scripts")
      .fold(e => fail(s"$e"), identity)
      .map { s =>
        val c = s.hcursor
        def num(field: String) = c.get[Int](field).fold(e => fail(s"$field: $e"), identity)
        def big(field: String) = BigInt(text(s, field))
        val language = PlutusLanguage.values
          .find(_.id + 1 == num("language"))
          .getOrElse(fail(s"language ${num("language")}"))
        val request = EvalRequest(
          language,
          num("protocol"),
          hex(text(s, "script")),
          c.get[Vector[String]]("arguments")
            .fold(e => fail(s"$e"), identity)
            .map(a => PlutusData.decode(hex(a)).fold(e => fail(s"$e"), identity)),
          c.get[Vector[Long]]("costModel").fold(e => fail(s"$e"), identity),
          ExUnits(big("memory"), big("cpu"))
        )
        val spent = (
          c.get[Option[String]]("spentMemory").toOption.flatten,
          c.get[Option[String]]("spentCpu").toOption.flatten
        ).mapN((m, s) => ExUnits(BigInt(m), BigInt(s)))
        (
          request,
          text(s, "result"),
          spent,
          c.get[Option[String]]("subTransaction").toOption.flatten,
          c.get[Option[Int]]("tag").toOption.flatten
        )
      }
    Read(json, scripts)

  /** A fresh directory of the test's own, removed after `use`. */
  private def inDirectory[A](use: Path => A): A =
    val dir = Files.createTempDirectory("scarab-plutus-cases")
    try use(dir)
    finally
      val stream = Files.walk(dir)
      try stream.iterator.asScala.toVector.reverse.foreach(Files.delete)
      finally stream.close()

  private def files(dir: Path): Vector[Path] =
    val stream = Files.list(dir)
    try stream.iterator.asScala.toVector.sortBy(_.getFileName.toString)
    finally stream.close()

  test(
    "every recorded block's batches written as the node's run observes them, and read back: each script's request evaluated again to the result its case records"
  ) {
    inDirectory { dir =>
      val cases = PlutusCases(dir, perKind = 1000, max = 100000)
      val told = DijkstraPlanned.Observations()
      DijkstraRecordedBlocks.samples.foreach { sample =>
        val observer: DijkstraPhase2.Observation => Unit = o =>
          told.observer(o)
          cases.observeDijkstra(o)
        val _ =
          DijkstraRecordedBlocks.program(sample, ScarabEvaluator.default, Some(observer)).serial
      }
      val written = files(dir).map(read)
      // one case a batch, named by the block's slot and the batch's id, whose body it holds
      assertEquals(written.size, told.all.size)
      assertEquals(
        written.map(r => text(r.json, "name")).toSet,
        told.all.map(o => s"slot${o.slot}-${o.id.hex.take(8)}").toSet
      )
      written.foreach { r =>
        assertEquals(text(r.json, "era"), "dijkstra")
        val body = hex(text(r.json, "body"))
        assert(
          text(r.json, "name").endsWith(Blake2b.hash256.hash(body).hex.take(8)),
          text(r.json, "name")
        )
        // each script evaluated again from its case alone: the result and the units it recorded
        r.scripts.foreach { (request, result, spent, _, _) =>
          ScarabEvaluator.default.evaluate(request) match
            case EvalResult.Success(used, _) =>
              assertEquals((result, spent), ("passed", Some(used)))
            case EvalResult.Failure(_, used, _) =>
              assertEquals((result, spent), ("failed", Some(used)))
        }
      }
      // what the sample's cases hold (`DijkstraBbodySuite` 's count of the observations): 208
      // scripts, 15 a sub-transaction's, seven guarding a batch (tag 6), three failed
      val scripts = written.flatMap(_.scripts)
      assertEquals(scripts.size, 208)
      assertEquals(scripts.count(_._4.isDefined), 15)
      assertEquals(scripts.count(_._5.contains(6)), 7)
      assertEquals(scripts.count(_._2 == "failed"), 3)
      assertEquals(
        written.count(r => text(r.json, "outcome").startsWith("failed")),
        3
      )
    }
  }

  test(
    "a Dijkstra batch with no verdict and one not collected: the case says so, and holds the scripts that ran"
  ) {
    inDirectory { dir =>
      val cases = PlutusCases(dir, perKind = 1000, max = 100000)
      val transaction = Block.Transaction(Bytes.fromHex("a0").toOption.get, Bytes.empty, None, true)
      val request =
        EvalRequest(PlutusLanguage.V4, 12, Bytes.empty, Vector.empty, Vector.empty, ExUnits(1, 2))
      val sub = ScriptLocation.SubPhase2(
        Bytes.fromHex("ab" * 32).toOption.get,
        Redeemer(6, 0, Bytes.fromHex("00").toOption.get, ExUnits(1, 2))
      )
      val top =
        ScriptLocation.Phase2(Redeemer(0, 1, Bytes.fromHex("00").toOption.get, ExUnits(1, 2)))
      val reason: EvalFailure.Unsupported =
        EvalFailure.Unsupported(BackendFailure.BudgetOutOfRange(ExUnits(0, 0)))
      cases.observeDijkstra(
        DijkstraPhase2.Observation(
          transaction.id,
          transaction,
          7,
          DijkstraPhase2.Outcome.Unavailable(
            Vector(
              DijkstraPhase2
                .Evaluation(top, request, EvalResult.Success(ExUnits(1, 1), Vector.empty))
            ),
            sub,
            request,
            reason
          )
        )
      )
      cases.observeDijkstra(
        DijkstraPhase2.Observation(
          transaction.id,
          transaction,
          8,
          DijkstraPhase2.Outcome.Uncollected(
            NonEmptyList.one(CollectError.NoCostModel[Dijkstra](PlutusLanguage.V4))
          )
        )
      )
      files(dir).map(read) match
        case Vector(unavailable, uncollected) =>
          assertEquals(
            text(unavailable.json, "outcome"),
            s"unavailable sub abababab/6.0 ${reason.message}"
          )
          assertEquals(
            unavailable.scripts.map(s => (s._2, s._3, s._4, s._5)),
            Vector(
              ("passed", Some(ExUnits(1, 1)), None, Some(0)),
              ("unavailable", None, Some("ab" * 32), Some(6))
            )
          )
          assertEquals(text(uncollected.json, "outcome"), "uncollected no cost model for V4")
          assertEquals(uncollected.scripts, Vector.empty)
        case other => fail(s"not two cases: ${other.size}")
    }
  }

  test("a Dijkstra batch's cases are kept to `perKind` of a kind, its kind marked as Dijkstra's") {
    inDirectory { dir =>
      val cases = PlutusCases(dir, perKind = 1, max = 100000)
      val transaction = Block.Transaction(Bytes.fromHex("a0").toOption.get, Bytes.empty, None, true)
      val request =
        EvalRequest(PlutusLanguage.V3, 12, Bytes.empty, Vector.empty, Vector.empty, ExUnits(1, 2))
      val top =
        ScriptLocation.Phase2(Redeemer(0, 0, Bytes.fromHex("00").toOption.get, ExUnits(1, 2)))
      // two batches of one kind: the first written, the second not (one case a kind)
      Vector(BigInt(1), BigInt(2)).foreach(slot =>
        cases.observeDijkstra(
          DijkstraPhase2.Observation(
            transaction.id,
            transaction,
            slot,
            DijkstraPhase2.Outcome.Ran(
              NonEmptyVector.one(
                DijkstraPhase2.Evaluation(
                  top,
                  request,
                  EvalResult.Success(ExUnits(1, 1), Vector.empty)
                )
              )
            )
          )
        )
      )
      files(dir).map(read) match
        case Vector(one) =>
          assertEquals(text(one.json, "kind"), "dijkstra-v3-valid--passed")
          assertEquals(text(one.json, "slot"), "1")
        case other => fail(s"not one case: ${other.size}")
    }
  }
