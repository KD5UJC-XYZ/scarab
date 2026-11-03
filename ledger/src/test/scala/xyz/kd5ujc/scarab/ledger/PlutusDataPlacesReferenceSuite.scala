// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** Plutus `Data` where a transaction carries it is read as `Data` when the transaction is decoded,
  * as the reference reads it (#721): a datum of the witness set and a redeemer's data are
  * `Data era` (`decodeData`, `PlutusCore/Data.hs:209-265`, plutus 507cab8c), an inline datum is
  * `BinaryData`, its bytes decoded as `Data` in full (`makeBinaryData`, `Plutus/Data.hs:217-239`,
  * cardano-ledger f649f975). Data that is not `Data` fails the witness set or the body, and a block
  * holding it.
  *
  * `fixtures/reference/plutus-data-places/cases.jsonl` holds each place with data the reference
  * reads and data it refuses, in Alonzo, Babbage and Conway, with the verdict of the unchanged
  * released decoders (`decodeFullAnnotator` at the era's version).
  */
class PlutusDataPlacesReferenceSuite extends FunSuite:
  private final case class Row(
      name: String,
      era: String,
      kind: String,
      accepted: Boolean,
      bytes: String
  )

  private given Decoder[Row] =
    Decoder.forProduct5("name", "era", "kind", "accepted", "bytes")(Row.apply)

  private val rows: Vector[Row] =
    Files
      .readString(Path.of("fixtures/reference/plutus-data-places/cases.jsonl"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(l => parser.decode[Row](l).fold(e => fail(e.getMessage), identity))
      .toVector

  private val eras = Map("alonzo" -> Era.Alonzo, "babbage" -> Era.Babbage, "conway" -> Era.Conway)

  test("the corpus has every place, read and refused, in each era") {
    assertEquals(rows.map(_.era).toSet, eras.keySet)
    assertEquals(rows.map(_.kind).toSet, Set("witnesses", "body"))
    assert(rows.exists(_.accepted) && rows.exists(!_.accepted))
  }

  for row <- rows do
    test(row.name) {
      val raw = Bytes.fromHex(row.bytes).fold(e => fail(e.toString), identity)
      val era = eras.getOrElse(row.era, fail(row.era))
      val read = row.kind match
        case "witnesses" => Witnesses.decode(raw, era).map(_ => ())
        case _           => TxBody.decode(raw, era).map(_ => ())
      if row.accepted then assert(read.isRight, s"the reference reads it: $read")
      // a float is refused by the decode of any item, before the data is read as `Data`
      else assert(read.isLeft, s"the reference refuses it: ${row.name}")
    }
