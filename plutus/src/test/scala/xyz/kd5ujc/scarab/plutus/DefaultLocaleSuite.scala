// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.syntax.all.*
import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.plutus.FlatProgram.*
import xyz.kd5ujc.scarab.uplc.{DefaultFun, DefaultLocale, DefaultLocaleFixture}
import xyz.kd5ujc.scarab.uplc.SemanticsVariant as Variant
import xyz.kd5ujc.scarab.uplc.cost.EvaluationContext

/** The cost-model parameter names, the evaluation contexts of real cost-model lists and a script of
  * the builtins whose names start with `I`, under the root, Turkish and Azeri default locales.
  * `showParamName` lowers the first letter with GHC's `toLower` (plutus 1.70.0.0 `507cab8c`,
  * `PlutusLedgerApi/Common/ParamName.hs` lines 67-75), which maps `I` to `i` in every locale, where
  * `String.toLowerCase` under a Turkish or Azeri default gives a dotless `ı`.
  *
  * [[ParamNames]] computes its tables once, when they are first used, so the check of
  * [[ParamNames.show]] under each locale is the one that does not depend on which suite used them
  * first.
  */
class DefaultLocaleSuite extends FunSuite with DefaultLocaleFixture:
  private def recorded(version: Int): Vector[String] =
    PlutusConformance
      .resource(s"V$version-param-names.txt")
      .linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .toVector

  /** `lowerKebab` of `showParamName`: the first letter by its simple lower-case mapping, as GHC's
    * `toLower`, and every quote as a hyphen.
    */
  private def reference(constructor: String): String =
    constructor.take(1).map(_.toLower) + constructor.drop(1).replace('\'', '-')

  /** The names the own evaluator tags a list with: those plutus 1.71.0.0 records for the language a
    * script is evaluated as (`ledger-api/` of its view of `plutus-reference`).
    */
  private def referenceNames(language: PlutusLanguage): Vector[String] =
    PlutusReference
      .view(PlutusRelease.Plutus1_71)
      .read(s"ledger-api/V${language.evaluatedAs.id + 1}-param-names.txt")
      .linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(reference)
      .toVector

  /** The lists of the fixtures: every epoch of the Preview governance record (Koios's
    * `epoch_params`), the PlutusV3 list of the mainnet Conway genesis, and the reference's default
    * parameters at protocols 10 and 11.
    */
  private val lists: Vector[(String, PlutusLanguage, Vector[Long])] =
    def json(path: String): Json =
      parser.parse(Files.readString(Path.of(path))).fold(throw _, identity)
    def longs(value: Json): Vector[Long] =
      value.as[Vector[Long]].fold(e => fail(e.message), identity)
    val preview = json("fixtures/preview/conway-governance.json").hcursor.downField("epochParams")
    val epochs = preview.keys.getOrElse(fail("no epochParams")).toVector.sortBy(_.toInt)
    val languages = Vector(
      "PlutusV1" -> PlutusLanguage.V1,
      "PlutusV2" -> PlutusLanguage.V2,
      "PlutusV3" -> PlutusLanguage.V3
    )
    val recordedLists = for
      epoch <- epochs
      (key, language) <- languages
      value <- preview.downField(epoch).downField("costModels").downField(key).focus.toVector
    yield (s"preview epoch $epoch $key", language, longs(value))
    val mainnetV3 = longs(
      json("fixtures/mainnet/genesis/conway-genesis.json").hcursor
        .downField("plutusV3CostModel")
        .focus
        .getOrElse(fail("no plutusV3CostModel"))
    )
    val defaults = for
      protocol <- Vector(10, 11)
      (key, language) <- languages
    yield (
      s"reference defaults protocol $protocol $key",
      language,
      PlutusBoundaryCorpus.costModel(language, protocol)
    )
    recordedLists ++ (("mainnet conway genesis PlutusV3", PlutusLanguage.V3, mainnetV3) +: defaults)

  /** `\ctx -> force (ifThenElse (equalsData (iData (indexByteString #01ff 1)) (iData 255)) (delay
    * ()) (delay error))`, under as many lambdas as the language's script takes arguments.
    */
  private def builtin(fun: DefaultFun): Tm = Builtin(fun.flatTag)
  private val body: Tm =
    val bytes = Const(Vector(1), _.byteString(Array(0x01.toByte, 0xff.toByte)))
    val index = Ap(Ap(builtin(DefaultFun.IndexByteString), bytes), integer(1))
    val condition = Ap(
      Ap(builtin(DefaultFun.EqualsData), Ap(builtin(DefaultFun.IData), index)),
      Ap(builtin(DefaultFun.IData), integer(255))
    )
    Force(Ap(Ap(Ap(Force(builtin(DefaultFun.IfThenElse)), condition), Delay(unit)), Delay(Error)))

  private def request(language: PlutusLanguage, protocol: Int, costModel: Vector[Long]) =
    val arguments = if language.evaluatedAs == PlutusLanguage.V3 then 1 else 3
    EvalRequest(
      language,
      protocol,
      FlatProgram.script((1, 0, 0), (0 until arguments).foldLeft(body)((t, _) => Lam(t))),
      Vector.fill(arguments)(PlutusData.unit),
      costModel,
      PlutusBoundaryCorpus.budget
    )

  private val requests = for
    list <- lists
    protocol <- Vector(10, 11)
  yield (list, protocol)

  /** The result of each request, evaluated when the suite is made, under the JVM's default locale
    * then; each succeeds. The root locale is one of those the test runs under.
    */
  private val underRoot: Map[(String, Int), EvalResult] =
    val scarab = ScarabEvaluator.pure
    requests.map { case ((name, language, values), protocol) =>
      val result = scarab.evaluate(request(language, protocol, values))
      result match
        case EvalResult.Success(_, _) => ()
        case other                    => fail(s"$name protocol $protocol: $other")
      (name, protocol) -> result
    }.toMap

  DefaultLocale.checked.foreach { locale =>
    val under = underDefault(locale)
    val label = DefaultLocale.label(locale)

    under.test(s"the parameter names are showParamName's ($label)") { _ =>
      assertEquals(ParamNames.show("IData'cpu'arguments"), "iData-cpu-arguments")
      Vector(1 -> ParamNames.v1, 2 -> ParamNames.v2, 3 -> ParamNames.v3).foreach { (v, table) =>
        val expected = recorded(v).map(reference)
        assertEquals(recorded(v).map(ParamNames.show), expected, s"show, PlutusV$v")
        assertEquals(table, expected, s"the table of PlutusV$v")
      }
    }

    under.test(
      s"each real list's evaluation context is the one the reference's names give ($label)"
    ) { _ =>
      PlutusLanguage.values.foreach { language =>
        assertEquals(
          EvaluationContext.parameterNames(language),
          referenceNames(language),
          s"the names, in order, the evaluator tags a $language list with"
        )
      }
      assert(lists.size >= 10, s"only ${lists.size} lists")
      lists.foreach { (name, language, values) =>
        val names = referenceNames(language)
        val tagged = names.zip(values.padTo(names.size, Long.MaxValue)).toMap
        val expected =
          Variant.ofContext(language).traverse(EvaluationContext.costsOf(_, tagged))
        assert(expected.isRight, s"$name: the reference's names give no context: $expected")
        assertEquals(
          EvaluationContext.build(language, values)._2.map(_.variants.map(_.model)),
          expected.map(_.map(_.model)),
          name
        )
      }
    }

    under.test(
      s"a script of iData, indexByteString and ifThenElse costs the same in every locale ($label)"
    ) { _ =>
      val scarab = ScarabEvaluator.pure
      for
        ((name, language, values), protocol) <- requests
        r = request(language, protocol, values)
      do assertEquals(scarab.evaluate(r), underRoot((name, protocol)), s"$name protocol $protocol")
    }
  }
