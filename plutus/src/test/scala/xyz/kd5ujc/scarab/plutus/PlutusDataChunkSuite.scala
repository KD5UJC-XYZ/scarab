// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import scalus.uplc.builtin.{Builtins, ByteString}

/** Native CLI parser/hash verdicts, including the original chunk framing that CBOR joins. */
class PlutusDataChunkSuite extends FunSuite:
  private final case class Probe(id: String, bytes: Bytes, accepted: Boolean, hash: String)

  private val rows = Files
    .readAllLines(Path.of("fixtures/reference/plutus-data-chunks/cases.tsv"))
    .asScala
    .toVector
    .filterNot(_.startsWith("#"))
    .map { line =>
      line.split("\t").toVector match
        case Vector(id, hex, verdict, hash, _) =>
          val accepted = verdict match
            case "true"  => true
            case "false" => false
            case other   => fail(s"$id: invalid native verdict $other")
          Probe(id, Bytes.fromHex(hex).fold(e => fail(e.toString), identity), accepted, hash)
        case other => fail(s"malformed native chunk fixture: $other")
    }

  test("all twelve native byte-string and bignum probes are retained") {
    assertEquals(rows.size, 12)
  }

  for probe <- rows do
    test(s"native chunk boundary: ${probe.id}") {
      val decoded = PlutusData.decode(probe.bytes)
      assertEquals(decoded.isRight, probe.accepted, probe.id)
      val node = Cbor.decode(probe.bytes).fold(e => fail(e.toString), identity)
      assertEquals(PlutusData.fromNode(node), decoded, s"${probe.id}: retained original node")
      if !probe.accepted then
        assertEquals(decoded, Left(PlutusData.DecodeError.ByteStringChunkLimit), probe.id)
      decoded.foreach { data =>
        val encoded = Bytes.fromArray(DataSerialiser.serialise(ScalusEvaluator.toData(data)))
        val hashed = Builtins.blake2b_256(ByteString.fromArray(encoded.toArray))
        assertEquals(
          Bytes.fromArray(hashed.bytes).hex,
          probe.hash,
          s"${probe.id}: native script-data hash"
        )
      }
    }
