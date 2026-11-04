// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.dijkstra.*
import xyz.kd5ujc.scarab.plutus.{EvalRequest, ExUnits, PlutusLanguage}

/** A PlutusV4 script's failure payload (`ToCBOR PlutusWithContext`, `PlutusFailureCbor.context`)
  * against the bytes the Leios testnet build wrote for the contexts of
  * `fixtures/reference/leios-testnet-contexts`: the protocol version, language 3 with the script,
  * the context as one argument (not a list, as for PlutusV1 and PlutusV2), the execution units and
  * the cost model the oracle used (251 zeros).
  */
class DijkstraContextWireSuite extends FunSuite:
  private val root = Path.of("fixtures/reference/leios-testnet-contexts")

  private def json(name: String): Vector[Json] =
    parser
      .parse(Files.readString(root.resolve(name)))
      .fold(e => fail(e.toString), identity)
      .asArray
      .getOrElse(fail(name))

  private def field(j: Json, name: String): Json =
    j.hcursor.downField(name).focus.getOrElse(fail(name))
  private def hex(j: Json): Bytes =
    j.asString.flatMap(Bytes.fromHex(_).toOption).getOrElse(fail(s"not hex: $j"))
  private def node(b: Bytes) =
    Cbor.decode(b).fold(e => fail(e.toString), identity)

  test("every PlutusV4 context's failure payload is the reference's, byte for byte") {
    val results = json("results.json")
    val checked = json("cases.json")
      .filter(c => field(c, "name").asString.exists(Set("everything", "guarded", "governance")))
      .map { c =>
        val tx =
          DijkstraTx.inBlock(node(hex(field(c, "bytes")))).fold(e => fail(e.toString), identity)
        val utxo = field(c, "utxo").asArray
          .getOrElse(fail("utxo"))
          .map { pair =>
            val items = pair.asArray.getOrElse(fail("pair"))
            DijkstraTxIn.decode(node(hex(items(0)))).fold(e => fail(e.toString), identity) ->
              DijkstraTxOut.decode(node(hex(items(1)))).fold(e => fail(e.toString), identity)
          }
          .toMap
        val scripts =
          (tx.witnesses.scripts ++ tx.body.subTransactions.flatMap(_.witnesses.scripts) ++
            utxo.values.flatMap(_.referenceScript)).collect {
            case s @ DijkstraScript.Plutus(_, code) =>
              s.hash -> code
          }.toMap
        val env = DijkstraScriptContexts.Environment(utxo, slot => Right(slot * 1000))
        val (top, _) = DijkstraScriptContexts.transaction(tx, env)
        val ours = top.fold(e => fail(e.toString), identity).map {
          case DijkstraScriptContexts.Collected.V4(context) =>
            PlutusFailureCbor.context(
              EvalRequest(
                PlutusLanguage.V4,
                12,
                scripts.getOrElse(context.scriptHash, fail("script")),
                Vector(context.argument),
                Vector.fill(251)(0L),
                ExUnits(BigInt(1000), BigInt(2000))
              )
            )
          case other => fail(s"not PlutusV4: $other")
        }
        val result =
          results.find(r => field(r, "name") == field(c, "name")).getOrElse(fail("result"))
        val theirs = field(field(field(result, "result"), "top"), "contexts").asArray
          .getOrElse(fail("contexts"))
          .map(hex)
        assertEquals(ours, theirs, field(c, "name").toString)
        ours.size
      }
    assertEquals(checked.sum, 10)
  }
