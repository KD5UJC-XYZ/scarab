// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.uplc.{DefaultLocale, DefaultLocaleFixture}

/** The Alonzo genesis files of mainnet, preprod and preview, which give the PlutusV1 cost model by
  * name, under the root, Turkish and Azeri default locales: they are read as the reference reads
  * them in every locale. The three files are byte-identical; the reference's reading of the Preview
  * file is the `base-preview-object` row of `fixtures/reference/genesis-cost-models/genesis.jsonl`.
  *
  * The names come from the tables of [[xyz.kd5ujc.scarab.uplc.ParamNames]], computed once when
  * first used: these checks see the locale only if they are the first to use them.
  * `DefaultLocaleSuite` checks `ParamNames.show` itself under each locale.
  */
class GenesisLocaleSuite extends FunSuite with DefaultLocaleFixture:
  private def json(path: String): Json =
    parser.parse(Files.readString(Path.of(path))).fold(throw _, identity)

  private val reference: Vector[Long] =
    Files
      .readString(Path.of("fixtures/reference/genesis-cost-models/genesis.jsonl"))
      .linesIterator
      .map(parser.parse(_).fold(throw _, identity))
      .find(_.hcursor.get[String]("name").contains("base-preview-object"))
      .flatMap(
        _.hcursor.downField("reference").downField("result").get[Vector[Long]]("plutusV1").toOption
      )
      .getOrElse(fail("no base-preview-object row"))

  private val networks = Vector("mainnet", "preprod", "preview")

  DefaultLocale.checked.foreach { locale =>
    val under = underDefault(locale)
    val label = DefaultLocale.label(locale)

    under.test(s"the Alonzo genesis of each network is read as the reference reads it ($label)") {
      _ =>
        networks.foreach { network =>
          val genesis = json(s"fixtures/$network/genesis/alonzo-genesis.json")
          assertEquals(
            GenesisCostModels.alonzo(genesis).map(_.plutusV1),
            Right(reference),
            network
          )
          assertEquals(GenesisSchema.alonzo(genesis), Right(()), network)
        }
    }

    under.test(s"the names a genesis cost model is read by are ASCII ($label)") { _ =>
      CostModelLanguage.values.foreach { language =>
        assert(language.parameterNames.forall(_.forall(_ < 0x80)), language.key)
      }
      assert(CostModelLanguage.PlutusV1.initialNames.contains("iData-cpu-arguments"))
      assert(CostModelLanguage.PlutusV1.initialNames.contains("ifThenElse-cpu-arguments"))
      assert(CostModelLanguage.PlutusV1.initialNames.contains("indexByteString-cpu-arguments"))
    }
  }
