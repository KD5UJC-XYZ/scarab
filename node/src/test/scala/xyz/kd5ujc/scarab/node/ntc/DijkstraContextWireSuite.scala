// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.Json
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Value}
import xyz.kd5ujc.scarab.ledger.{TxIn, Unsigned}
import xyz.kd5ujc.scarab.ledger.era.{
  CostModels,
  EraPParams,
  Identity,
  PParams,
  PlutusWithContext,
  ProtVer,
  UTxO,
  Version
}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraStAnnTx, DijkstraTx, DijkstraTxOut}
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.plutus.PlutusLanguage
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.{contextCases, hex, json, node, str}

/** A PlutusV4 script's failure payload (`ToCBOR PlutusWithContext`, `PlutusFailureCbor.context`)
  * against the bytes the Leios testnet build wrote for the contexts of
  * `fixtures/reference/leios-testnet-contexts`: the protocol version, language 3 with the script,
  * the context as one argument (not a list, as for PlutusV1 and PlutusV2), the execution units and
  * the cost model the oracle used (251 zeros).
  *
  * Each context is the era's (`EraPlutusTxInfo 'PlutusV4 DijkstraEra`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs`, cardano-ledger 9df9f98c, in the
  * encodings of plutus-ledger-api 1.71.0.0), collected by the makers of the reference's annotated
  * transaction (`mkDijkstraStAnnTopTx`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra.hs:108-219`, cardano-ledger 9df9f98c) at the top
  * level and in each sub-transaction.
  */
class DijkstraContextWireSuite extends FunSuite:
  private val results = json("fixtures/reference/leios-testnet-contexts/results.json").asArray
    .getOrElse(fail("results.json is not an array"))

  /** The oracle's environment (the fixture's provenance): protocol version 12.0, slots of one
    * second from the POSIX epoch and a PlutusV4 cost model of 251 zeros.
    */
  private val pp: PParams[Dijkstra] =
    val p = EraPParams.dijkstra
    val pv = ProtVer(
      Version.mkVersion(12).getOrElse(fail("version 12")),
      Unsigned.from[Unsigned.Width.W32](BigInt(0)).getOrElse(fail("minor"))
    )
    val costModels = CostModels
      .from(Map(PlutusLanguage.V4.id -> Vector.fill(251)(BigInt(0))))
      .fold(e => fail(e.toString), identity)
    PParams
      .lensHKD[Dijkstra]
      .andThen(p.hkdCostModelsL[Identity])
      .replace(costModels)(p.ppProtocolVersionL.replace(pv)(p.emptyPParams))

  /** `TxIn` as CBOR, `[transaction id, index]`. */
  private def txIn(raw: Bytes): TxIn = node(raw).value match
    case Value.Arr(Vector(id, ix)) =>
      (id.value, ix.value) match
        case (Value.ByteString(id), Value.UInt(ix)) => TxIn(id, ix)
        case other                                  => fail(s"not an input: $other")
    case other => fail(s"not an input: $other")

  /** The failure payloads of a case's contexts, the top level's then each sub-transaction's. */
  private def ours(c: Json): Vector[Vector[Bytes]] =
    val tx = DijkstraTx.inBlock(node(hex(str(c, "bytes")))).fold(e => fail(e.toString), identity)
    val utxo = c.hcursor
      .get[Vector[(String, String)]]("utxo")
      .fold(e => fail(e.message), identity)
      .map((i, o) =>
        txIn(hex(i)) -> DijkstraTxOut.decode(node(hex(o))).fold(e => fail(e.toString), identity)
      )
    val top =
      DijkstraStAnnTx.mkDijkstraStAnnTopTx(
        slot => Right(slot * 1000),
        pp,
        UTxO[Dijkstra](utxo.toMap),
        Map.empty,
        tx
      )
    val levels = top.dsattPlutusScriptsWithContext.value +:
      top.dsattSubTransactions.value.toVector.map { case sub: DijkstraStAnnTx.SubTx =>
        sub.dsastPlutusScriptsWithContext.value
      }
    levels.map(
      _.fold(e => fail(e.toString), identity).toVector.map { pwc =>
        val request = PlutusWithContext.evalRequest(pwc)
        assertEquals(request.language, PlutusLanguage.V4)
        PlutusFailureCbor.context(request)
      }
    )

  /** The reference's payloads of a case, the top level's then each sub-transaction's. */
  private def theirs(name: String): Vector[Vector[Bytes]] =
    val r = results
      .find(_.hcursor.get[String]("name").toOption.contains(name))
      .getOrElse(fail(name))
      .hcursor
      .downField("result")
    def contexts(level: io.circe.ACursor): Vector[Bytes] =
      level.get[Vector[String]]("contexts").fold(e => fail(e.message), identity).map(hex)
    contexts(r.downField("top")) +:
      r.get[Vector[Json]]("subtxs")
        .fold(e => fail(e.message), identity)
        .map(j => contexts(j.hcursor))

  test("every PlutusV4 context's failure payload is the reference's, byte for byte") {
    val named = Vector("everything", "guarded", "governance", "sub-transaction scripts")
    val checked = named.map { name =>
      val c = contextCases.find(str(_, "name") == name).getOrElse(fail(name))
      val payloads = ours(c)
      assertEquals(payloads, theirs(name), name)
      payloads.map(_.size)
    }
    // ten at the top levels, four in sub-transactions
    assertEquals(checked.map(_.head).sum, 10)
    assertEquals(checked.flatMap(_.drop(1)).sum, 4)
  }
