// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import io.circe.Json
import munit.Assertions
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.{Block, Tx}
import xyz.kd5ujc.scarab.protocol.Era

/** The cases of `fixtures/reference/dijkstra-crossing` (cardano-ledger 9df9f98c,
  * ouroboros-consensus 2416493b; provenance in its README): each a whole Conway transaction and
  * what the Leios testnet build does with it, read by the suites that compare Scarab's two ways of
  * carrying a Conway transaction into Dijkstra with it.
  */
object DijkstraCrossingCases extends Assertions:
  /** A case: its name, the Conway transaction's parts as the flat decoder splits them, and the
    * reference's record.
    */
  final case class Case(name: String, conway: Block.Transaction, reference: Json)

  lazy val all: Vector[Case] =
    DijkstraCorpus
      .jsonLines("fixtures/reference/dijkstra-crossing/cases.jsonl")
      .map(c =>
        Case(string(c, "name"), parts(DijkstraCorpus.hex(string(c, "tx"))), field(c, "reference"))
      )

  def field(j: Json, path: String*): Json =
    path.foldLeft(j)((at, k) => at.hcursor.downField(k).focus.getOrElse(fail(s"no $k in $at")))
  def string(j: Json, path: String*): String =
    field(j, path*).asString.getOrElse(fail(s"${path.mkString(".")} is not a string"))
  def bool(j: Json, path: String*): Boolean =
    field(j, path*).asBoolean.getOrElse(fail(s"${path.mkString(".")} is not a boolean"))

  /** A whole Conway transaction's parts, each as the bytes it was read from. */
  def parts(raw: Bytes): Block.Transaction =
    Cbor
      .decode(raw, Cbor.Limits.Unbounded)
      .left
      .map(_.toString)
      .flatMap(Tx.envelopeParts(_, Era.Conway).left.map(_.message))
      .fold(e => fail(s"not a Conway transaction: $e"), identity)

  /** The first part the reference refuses alone, in the order the translation reads them. */
  def refusedPart(reference: Json): Option[DijkstraTxPart] =
    val partsOf = field(reference, "parts")
    Vector(
      "body" -> DijkstraTxPart.TxBody,
      "witnesses" -> DijkstraTxPart.TxWits,
      "auxiliaryData" -> DijkstraTxPart.TxAuxData
    ).collectFirst {
      case (key, part)
          if partsOf.hcursor.downField(key).focus.exists(p => !p.isNull) &&
            !bool(partsOf, key, "accepted") =>
        part
    }

  /** A carried transaction against the reference's: each part's bytes, the flag, the id and the
    * Dijkstra mempool form (`toCBORForMempoolSubmission`).
    */
  def same(
      name: String,
      expected: Json,
      tx: DijkstraTx[?],
      isValid: Boolean,
      mempool: Bytes
  ): Unit =
    assertEquals(tx.body.raw.hex, string(expected, "body"), s"$name: body")
    assertEquals(tx.wits.raw.hex, string(expected, "witnesses"), s"$name: witnesses")
    assertEquals(
      tx.auxData.map(_.raw.hex),
      field(expected, "auxiliaryData").asString,
      s"$name: auxiliary data"
    )
    assertEquals(isValid, bool(expected, "isValid"), s"$name: flag")
    assertEquals(tx.id.hex, string(expected, "id"), s"$name: id")
    assertEquals(mempool.hex, string(expected, "mempool"), s"$name: mempool form")
