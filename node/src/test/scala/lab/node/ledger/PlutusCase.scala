// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.network.Era
import lab.node.Block
import scala.jdk.CollectionConverters.*

/** A recorded phase-2 evaluation (`follow --plutus-cases`): a Preview transaction with the outputs
  * it spends and references, the cost models in force and the slot clock, with the block's
  * `is_valid` flag.
  */
final case class PlutusCase(
    name: String,
    kind: String,
    slot: BigInt,
    protocolMajor: Int,
    tx: Tx,
    spent: Map[TxIn, TxOut],
    references: Map[TxIn, TxOut],
    costModels: Map[Int, Vector[BigInt]],
    clock: Map[BigInt, BigInt]
):
  def params: ProtocolParams =
    TestLedger.params(protocolMajor).copy(costModels = costModels)

  def posix(slot: BigInt): Either[TranslationError, BigInt] =
    clock.get(slot).toRight(TranslationError(s"no clock sample for slot $slot"))

object PlutusCase:
  val directory: Path = Path.of("fixtures/preview/plutus")

  def all: Vector[PlutusCase] =
    if !Files.isDirectory(directory) then Vector.empty
    else
      Files
        .list(directory)
        .iterator
        .asScala
        .filter(_.toString.endsWith(".json"))
        .toVector
        .sortBy(_.getFileName.toString)
        .map(load)

  private def hex(j: Json): Bytes = Bytes.fromHex(j.asString.get).toOption.get

  def load(path: Path): PlutusCase =
    val j = parse(Files.readString(path)).toOption.get
    val c = j.hcursor
    def text(f: String) = c.get[String](f).toOption.get
    def utxo(f: String) =
      c.downField(f)
        .focus
        .get
        .asArray
        .get
        .map { e =>
          val in = e.hcursor.get[String]("in").toOption.get.split('#')
          TxIn(hex(Json.fromString(in(0))), BigInt(in(1))) ->
            TxOut.fromBytes(hex(e.hcursor.get[Json]("out").toOption.get)).toOption.get
        }
        .toMap
    val block = Block.Transaction(
      hex(c.get[Json]("body").toOption.get),
      hex(c.get[Json]("witnesses").toOption.get),
      c.get[Option[String]]("auxiliary").toOption.flatten.map(h => Bytes.fromHex(h).toOption.get),
      c.get[Boolean]("isValid").toOption.get
    )
    val pv = c.get[Int]("protocolMajor").toOption.get
    PlutusCase(
      text("name"),
      text("kind"),
      BigInt(text("slot")),
      pv,
      Tx.fromBlock(block, if pv >= 7 then Era.Babbage else Era.Alonzo).toOption.get,
      utxo("spent"),
      utxo("references"),
      c.downField("costModels").focus.get.asObject.get.toMap.map { case (k, v) =>
        k.toInt -> v.asArray.get.map(x => BigInt(x.asString.get))
      },
      c.downField("clock")
        .focus
        .flatMap(_.asObject)
        .fold(Map.empty[BigInt, BigInt])(
          _.toMap.map { case (k, v) => BigInt(k) -> BigInt(v.asString.get) }
        )
    )
