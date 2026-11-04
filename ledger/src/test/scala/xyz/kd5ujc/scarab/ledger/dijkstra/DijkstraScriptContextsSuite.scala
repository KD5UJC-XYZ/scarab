// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.ledger.CborRead
import xyz.kd5ujc.scarab.plutus.PlutusData

/** The PlutusV4 script contexts of Dijkstra transactions against the build of the Leios testnet
  * (cardano-ledger 9df9f98c, plutus-ledger-api 1.71.0.0).
  * `fixtures/reference/leios-testnet-contexts` holds transactions with the outputs they spend
  * (`leios_context_cases.py`) and the contexts the reference collected for them before evaluation
  * (`mkDijkstraStAnnTopTx`, the oracle's "context" kind): each the `ToCBOR PlutusWithContext`
  * encoding, whose third element is the script's argument, the `ScriptContext`. The reference's
  * collection lists them in the reverse of the order of the scripts needed
  * (`scriptsWithContextFromLedgerTxInfoWithResult` conses each onto the list), and so does
  * [[DijkstraScriptContexts.transaction]]. A refused collection must be refused here, for the same
  * reasons.
  */
class DijkstraScriptContextsSuite extends FunSuite:
  private val root = Path.of("fixtures/reference/leios-testnet-contexts")

  private def json(name: String): Vector[Json] =
    parser
      .parse(Files.readString(root.resolve(name)))
      .fold(e => fail(e.toString), identity)
      .asArray
      .getOrElse(fail(s"$name is not an array"))

  private def hex(j: Json): Bytes =
    j.asString.flatMap(Bytes.fromHex(_).toOption).getOrElse(fail(s"not hex: $j"))

  private def node(b: Bytes) =
    CborRead.decode(b, V12.nestedLimits).fold(e => fail(e.toString), identity)

  private val cases = json("cases.json")
  private val results = json("results.json")

  private def field(j: Json, name: String): Json =
    j.hcursor.downField(name).focus.getOrElse(fail(s"no $name in $j"))

  private val env = (utxo: Map[DijkstraTxIn, DijkstraTxOut]) =>
    DijkstraScriptContexts.Environment(utxo, slot => Right(slot * 1000))

  private def read(c: Json): (DijkstraTx, Map[DijkstraTxIn, DijkstraTxOut]) =
    val tx = DijkstraTx.inBlock(node(hex(field(c, "bytes")))).fold(e => fail(e.toString), identity)
    val utxo = field(c, "utxo").asArray.getOrElse(fail("utxo")).map { pair =>
      val Vector(i, o) = pair.asArray.getOrElse(fail("pair")): @unchecked
      DijkstraTxIn.decode(node(hex(i))).fold(e => fail(e.toString), identity) ->
        DijkstraTxOut.decode(node(hex(o))).fold(e => fail(e.toString), identity)
    }
    (tx, utxo.toMap)

  /** The argument of a `PlutusWithContext` encoding: its third element. */
  private def argument(pwc: Bytes): PlutusData =
    Cbor.decode(pwc).fold(e => fail(e.toString), identity).value match
      case Value.Arr(items) if items.size == 5 =>
        PlutusData.fromNode(items(2)).fold(e => fail(e.toString), identity)
      case other => fail(s"not a PlutusWithContext: $other")

  private def contexts(collected: Json): Vector[PlutusData] =
    field(collected, "contexts").asArray.getOrElse(fail("contexts")).map(j => argument(hex(j)))

  private def named(name: String): (Json, Json) =
    val c = cases.find(field(_, "name").asString.contains(name)).getOrElse(fail(name))
    val r = results.find(field(_, "name").asString.contains(name)).getOrElse(fail(name))
    (c, field(r, "result"))

  test("the cases and results are the same transactions") {
    assertEquals(cases.size, 11)
    assertEquals(results.map(field(_, "name")), cases.map(field(_, "name")))
  }

  private def arguments(
      c: DijkstraScriptContexts.Collection
  ): Vector[DijkstraScriptContexts.Context] =
    c.fold(
      e => fail(e.toString),
      _.map {
        case DijkstraScriptContexts.Collected.V4(context) => context
        case other                                        => fail(s"not PlutusV4: $other")
      }
    )

  Vector("everything", "guarded", "governance", "sub-transaction scripts").foreach { name =>
    test(s"$name: every PlutusV4 context is the reference's") {
      val (c, r) = named(name)
      val (tx, utxo) = read(c)
      assertEquals(Json.fromString(tx.id.hex), field(r, "id"))
      val (topCollection, subCollections) = DijkstraScriptContexts.transaction(tx, env(utxo))
      val top = arguments(topCollection)
      val expectedTop = contexts(field(r, "top"))
      val expectedSubsAll = field(r, "subtxs").asArray.getOrElse(fail("subtxs")).map(contexts)
      assert(
        expectedTop.nonEmpty || expectedSubsAll.exists(_.nonEmpty),
        "the reference collected contexts"
      )
      assertEquals(top.size, expectedTop.size, "top-level contexts")
      top
        .zip(expectedTop)
        .foreach((ours, theirs) => assertEquals(ours.argument, theirs, s"${ours.purpose}"))
      if expectedTop.size > 1 then
        assertNotEquals(top(0).argument, expectedTop(1), "the contexts differ by purpose")
      val subs = subCollections.map(arguments)
      val expectedSubs = field(r, "subtxs").asArray.getOrElse(fail("subtxs")).map(contexts)
      assertEquals(subs.map(_.size), expectedSubs.map(_.size), "sub-transaction contexts")
      subs.flatten
        .zip(expectedSubs.flatten)
        .foreach((ours, theirs) => assertEquals(ours.argument, theirs, s"${ours.purpose}"))
    }
  }

  /** The reference's refusal, as the constructor its failure shows, and Scarab's. */
  private val refusals = Vector(
    "pointer output" -> "PointerPresentInOutput (TxOutFromOutput (TxIx {unTxIx = 0}))",
    "byron output" -> "ByronTxOutInContext (TxOutFromOutput (TxIx {unTxIx = 0}))",
    "unresolved input" -> "TranslationLogicMissingInput",
    "reference input also an input" -> "ReferenceInputsNotDisjointFromInputs",
    "redeemer pointing nowhere" -> "RedeemerPointerPointsToNothing",
    "no redeemer" -> "NoRedeemer (DijkstraMinting",
    "redeemer of a key withdrawal" -> "ScriptHashNotFoundForPurpose"
  )

  private def shown(e: DijkstraCollectError): String = e match
    case DijkstraCollectError.NoRedeemer(_: DijkstraPurpose.Minting) =>
      "NoRedeemer (DijkstraMinting"
    case DijkstraCollectError.NoRedeemer(p) => s"NoRedeemer $p"
    case DijkstraCollectError.BadTranslation(cause) =>
      cause match
        case DijkstraContextError.PointerPresentInOutput(DijkstraTxOutSource.FromOutput(ix)) =>
          s"PointerPresentInOutput (TxOutFromOutput (TxIx {unTxIx = $ix}))"
        case DijkstraContextError.ByronTxOutInContext(DijkstraTxOutSource.FromOutput(ix)) =>
          s"ByronTxOutInContext (TxOutFromOutput (TxIx {unTxIx = $ix}))"
        case DijkstraContextError.UnresolvedInput(_) => "TranslationLogicMissingInput"
        case DijkstraContextError.ReferenceInputsNotDisjointFromInputs(_) =>
          "ReferenceInputsNotDisjointFromInputs"
        case DijkstraContextError.RedeemerPointerPointsToNothing(_, _) =>
          "RedeemerPointerPointsToNothing"
        case DijkstraContextError.ScriptHashNotFoundForPurpose(_, _) =>
          "ScriptHashNotFoundForPurpose"
        case other => other.toString

  refusals.foreach { (name, constructor) =>
    test(s"$name: refused as the reference refuses it") {
      val (c, r) = named(name)
      val (tx, utxo) = read(c)
      val failure = field(r, "failure").asString.getOrElse(fail("the reference collected it"))
      assert(failure.contains(constructor), failure)
      assert(failure.endsWith(":| []"), s"one failure: $failure")
      DijkstraScriptContexts.transaction(tx, env(utxo))._1 match
        case Left(errors) => assertEquals(errors.toVector.map(shown), Vector(constructor))
        case Right(_)     => fail("collected")
    }
  }

  test("a guard's script context at the top level carries the whole transaction") {
    val (c, _) = named("guarded")
    val (tx, utxo) = read(c)
    val (top, subs) = DijkstraScriptContexts.transaction(tx, env(utxo))
    assert(arguments(top).exists(_.purpose.isInstanceOf[DijkstraPurpose.Guarding]))
    assertEquals(subs.map(arguments(_).size), Vector(1, 0))
  }
