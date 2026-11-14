// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus.ndc

import io.circe.Json
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.plutus.{PlutusLanguage, PlutusVm}
import xyz.kd5ujc.scarab.uplc.{ProtocolMajor, SemanticsVariant}
import xyz.kd5ujc.scarab.uplc.cost.DefaultCostModels
import xyz.kd5ujc.scarab.uplc.flat.FlatWriter
import xyz.kd5ujc.scarab.uplc.text.{UplcParser, UplcPrinter}

/** The harness of the `uplc-conformance` scenario on hand-written programs (the corpus carries no
  * licence, so none of it is checked in): ids, the context of a scenario, the classes the README
  * names and the report's items, on both backends.
  */
class UplcConformanceMainSuite extends FunSuite:
  import UplcConformanceMain.*

  private val V3 = PlutusLanguage.V3

  /** The default cost-model list of a language at a protocol, for a context. */
  private def costs(language: PlutusLanguage, protocol: Int): Vector[Long] =
    DefaultCostModels.list(language, SemanticsVariant.of(language, ProtocolMajor(protocol)))

  /** A Flat program in the CBOR byte string the ledger holds. */
  private def script(bits: String): Bytes =
    val bytes = bits.grouped(8).map(b => Integer.parseInt(b, 2).toByte).toArray
    Cbor.encode(CborValue.ByteString(Bytes.fromArray(bytes))).fold(e => fail(e.toString), identity)

  /** The version `major.minor.patch` and a term's bits, then the filler. */
  private def program(version: (Int, Int, Int), term: String): Bytes =
    val head = FlatWriter.groups(version._1, version._2, version._3)
    script(head + term + FlatWriter.filler(term.length))

  /** `(con unit ())`: the constant tag, the type list of `unit`, no value bits. */
  private val unit = FlatWriter.bits(4, 4) + FlatWriter.tagList(Seq(3))

  /** A variable of index 0, which no `lam` binds. */
  private val free = FlatWriter.bits(0, 4) + FlatWriter.natural(0)

  private def scenario(id: String, language: PlutusLanguage, protocol: Int) =
    Scenario(id, "t", "g", NdcReport.languageName(language), NdcReport.protocolName(protocol))

  /** The report's item of `input` on `vm`. */
  private def observed(vm: PlutusVm, language: PlutusLanguage, protocol: Int, input: Input): Json =
    val backend = UplcBackend.of(vm)
    val context = backend
      .context(language, protocol, costs(language, protocol))
      .fold(e => fail(e.message), identity)
    val (_, version, run) = evaluate(backend)(context, input)
    item(scenario("x", language, protocol), version, run)

  private def evaluation(json: Json): Option[String] =
    json.hcursor.get[String]("evaluation").toOption
  private def budget(json: Json): Option[Json] = json.hcursor.downField("budget").focus

  test("a scenario's id is its group and directory name, folded as the site's ids are") {
    assertEquals(
      scenarioId("builtin/constant/array", "emptyArray", V3),
      "builtin_constant_array_emptyarray_v3"
    )
    assertEquals(
      scenarioId("program/version", "1-1-0-before-pv11", PlutusLanguage.V1),
      "program_version_1_1_0_before_pv11_v1"
    )
    assertEquals(
      scenarioId("builtin/semantics/bls12_381_G1_scalarMul", "mul19+25", V3),
      "builtin_semantics_bls12_381_g1_scalarmul_mul19_25_v3"
    )
  }

  test("the versions of a scenario are read as the site writes them") {
    assertEquals(NdcReport.protocol("11.0"), Some(11))
    assertEquals(NdcReport.protocol("eleven"), None)
    assertEquals(NdcReport.language("V2"), Some(PlutusLanguage.V2))
    assertEquals(NdcReport.language("V4").map(NdcReport.languageName), Some("V4"))
    assertEquals(NdcReport.protocolName(8), "8.0")
  }

  PlutusVm.values.foreach { vm =>
    test(s"${vm.name}: a result is the program's text and the budget it spent") {
      val text = Input.Text("(program 1.0.0 [(lam x x) (con integer 42)])")
      val json = observed(vm, V3, 11, text)
      assertEquals(evaluation(json), Some("(program 1.0.0 (con integer 42))"))
      // the start-up cost, and four steps of 16000 CPU and 100 memory units in variant E
      assertEquals(
        budget(json),
        Some(Json.obj("cpu" -> Json.fromLong(64100), "mem" -> Json.fromLong(500)))
      )
      assertEquals(json.hcursor.downField("error").focus, None)
    }

    test(
      s"${vm.name}: a Flat script is admitted as the ledger admits it at its language and protocol"
    ) {
      val v110 = Input.Script(program((1, 1, 0), unit))
      assertEquals(
        evaluation(observed(vm, PlutusLanguage.V1, 10, v110)),
        Some("parse/decode error")
      )
      assertEquals(budget(observed(vm, PlutusLanguage.V1, 10, v110)), Some(Json.Null))
      assertEquals(
        evaluation(observed(vm, PlutusLanguage.V1, 11, v110)),
        Some("(program 1.1.0 (con unit ()))")
      )
      assertEquals(evaluation(observed(vm, V3, 10, v110)), Some("(program 1.1.0 (con unit ()))"))
    }

    test(
      s"${vm.name}: a free variable fails the evaluation, as the ledger's scope check fails it"
    ) {
      assertEquals(
        evaluation(observed(vm, V3, 11, Input.Script(program((1, 0, 0), free)))),
        Some("evaluation failure")
      )
    }

    test(s"${vm.name}: a program that runs past the README's budget is out of budget") {
      val omega = Input.Text("(program 1.0.0 [(lam x [x x]) (lam x [x x])])")
      val json = observed(vm, V3, 11, omega)
      assertEquals(evaluation(json), Some("out of budget"))
      assertEquals(budget(json), Some(Json.Null))
    }

    test(s"${vm.name}: text the test-tree parser refuses gets no verdict, never a README outcome") {
      val json = observed(vm, V3, 11, Input.Text("(program 1.0.0 (con integer))"))
      assertEquals(evaluation(json), Some("no verdict"))
      assert(json.hcursor.get[String]("error").toOption.exists(_.contains("text parser")))
    }

    test(s"${vm.name}: text the parser reads is admitted in Flat by the backend's decoder") {
      // 1.1.0 is not V1's at protocol 10: the production check refuses what the parser read
      val text = Input.Text("(program 1.1.0 (con unit ()))")
      assertEquals(
        evaluation(observed(vm, PlutusLanguage.V1, 10, text)),
        Some("parse/decode error")
      )
      val backend = UplcBackend.of(vm)
      val context = backend
        .context(V3, 11, costs(V3, 11))
        .fold(e => fail(e.message), identity)
      assertEquals(evaluate(backend)(context, text)._1, Route.Encoded)
    }

    test(s"${vm.name}: the program's version is checked before its scope, as in production") {
      val both = Input.Script(program((1, 1, 0), free))
      assertEquals(
        evaluation(observed(vm, PlutusLanguage.V1, 10, both)),
        Some("parse/decode error")
      )
    }

    test(s"${vm.name}: a BLS12-381 constant read from text evaluates to itself") {
      val g1 =
        "0x97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb"
      val text = s"(program 1.0.0 (con bls12_381_G1_element $g1))"
      assertEquals(evaluation(observed(vm, V3, 11, Input.Text(text))), Some(text))
      val backend = UplcBackend.of(vm)
      val context = backend.context(V3, 11, costs(V3, 11)).fold(e => fail(e.message), identity)
      assertEquals(evaluate(backend)(context, Input.Text(text))._1, Route.Unencodable)
    }
  }

  test("without the native crypto library the Scalus backend gives no context, never a verdict") {
    val language = V3
    assertEquals(
      ScalusBackend
        .contextWith(nativeCrypto = false)(language, 11, costs(language, 11))
        .left
        .toOption,
      Some(Unanswered.NativeCryptoMissing)
    )
    assert(
      ScalusBackend.contextWith(nativeCrypto = true)(language, 11, costs(language, 11)).isRight
    )
  }

  test("no verdict is written as such, with why, and never as one of the README's outcomes") {
    val json = item(scenario("x", V3, 11), None, Run.NoVerdict(Unanswered.NoFlatContent))
    assertEquals(evaluation(json), Some("no verdict"))
    assertEquals(budget(json), Some(Json.Null))
    assertEquals(json.hcursor.get[String]("error").toOption, Some(Unanswered.NoFlatContent.message))
  }

  test("an item has the schema's fields and keeps the scenario's names and versions") {
    val json = item(
      Scenario("an_id", "a title", "a/group", "V2", "10.0"),
      None,
      Run.Failed(Verdict.EvaluationFailure, "detail")
    )
    assertEquals(
      json.asObject.map(_.keys.toVector),
      Some(
        Vector("id", "title", "group", "plutus_version", "protocol_version", "evaluation", "budget")
      )
    )
    assertEquals(json.hcursor.get[String]("protocol_version").toOption, Some("10.0"))
  }

  test("binders get the reference's fresh names: sibling lambdas differ, unlike depth names") {
    val parsed = UplcParser
      .program("(program 1.0.0 [(lam a a) (lam b (lam c b))])")
      .fold(e => fail(e.toString), identity)
    assertEquals(
      UplcPrinter.program(parsed, UplcPrinter.Naming.Fresh),
      "(program 1.0.0 [(lam i-0 i-0) (lam i-1 (lam i-2 i-1))])"
    )
    assertEquals(
      UplcPrinter.program(parsed),
      "(program 1.0.0 [(lam x-0 x-0) (lam x-0 (lam x-1 x-0))])"
    )
  }

  test("terms with every kind of constant convert to Scalus and back unchanged") {
    val text =
      """(program 1.1.0 [(lam x (constr 3 x (case (con integer 1) (error))))
        |  (con (list (pair integer bytestring)) [(1, #ff), (-2, #)])])""".stripMargin
    val constants = Vector(
      "(con data (Constr 1 [I 2, B #0a, List [Map [(I 1, I 2)]]]))",
      "(con string \"é\")",
      "(con (array bool) [True, False])",
      "(con value [(#aa, [(#bb, 5)])])",
      "(con bls12_381_G2_element 0x93e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049334cf11213945d57e5ac7d055d042b7e024aa2b2f08f0a91260805272dc51051c6e47ad4fa403b02b4510b647ae3d1770bac0326a805bbefd48056c8c121bdb8)"
    )
    (text +: constants.map(c => s"(program 1.0.0 $c)")).foreach { source =>
      val program = UplcParser.program(source).fold(e => fail(s"$source: $e"), identity)
      val back = ScalusTerms.toScalus(program.term).flatMap(ScalusTerms.fromScalus)
      assertEquals(back, Right(program.term), source)
    }
  }
