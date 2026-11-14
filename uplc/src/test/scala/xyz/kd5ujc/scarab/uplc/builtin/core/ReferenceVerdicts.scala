// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import io.circe.parser
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** What the reference did with one probe script in one ledger profile: a row of a `verdicts.jsonl`
  * of `fixtures/reference` (written by the pinned plutus 1.70.0.0 oracle). The scripts are this
  * repository's, built by the corpora in the `plutus` test tree; the suites here rebuild each
  * probe's builtin applications from the same table and compare the verdict, not the budget, which
  * needs the machine and the costing.
  *
  * @param language
  *   1 to 3 for PlutusV1 to PlutusV3
  * @param stage
  *   `success`, or the stage that failed (`evaluation`)
  */
final case class Verdict(id: String, language: Int, protocol: Int, stage: String):
  def succeeded: Boolean = stage == "success"
  def variant: CoreVariant = CoreTesting.variantOf(language, protocol)

object ReferenceVerdicts:
  def read(path: String): Vector[Verdict] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map { line =>
        parser
          .parse(line)
          .flatMap { json =>
            val c = json.hcursor
            for
              id <- c.get[String]("id")
              language <- c.get[Int]("language")
              protocol <- c.get[Int]("protocol")
              stage <- c.get[String]("stage")
            yield Verdict(id, language, protocol, stage)
          }
          .fold(e => sys.error(s"$path: $e"), identity)
      }
