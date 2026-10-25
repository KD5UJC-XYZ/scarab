// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scalus.uplc.Program
import xyz.kd5ujc.scarab.codec.Bytes

/** The flat bytes of every script the fixtures hold, by an id that names its source: the upstream
  * conformance programs (encoded by Scalus, and the original-byte sidecars), the boundary, costing,
  * upstream-boundary and transaction script corpora, and the flat decoding probes. Shared by
  * [[FlatDecodeParitySuite]] and [[FlatScanImperativeSuite]].
  */
object FlatFixtureScripts:
  /** A fixture whose hex does not parse is a broken fixture, not an input. */
  final case class BadFixture(id: String, cause: String) extends Exception(s"$id: $cause")

  private def hex(id: String, s: String): Array[Byte] =
    Bytes.fromHex(s).fold(e => throw BadFixture(id, e.toString), _.toArray)

  private lazy val conformanceRoot: Path =
    Paths.get(getClass.getResource(s"/${PlutusConformanceSuite.Directory}").toURI)

  /** Flat bytes from the conformance programs Scalus parses and can encode (a BLS constant has no
    * flat encoding), and from the original-byte sidecars.
    */
  lazy val conformance: Vector[(String, Array[Byte])] =
    val files = Files.walk(conformanceRoot).iterator().asScala.toVector
    val encoded = files.filter(_.toString.endsWith(".uplc")).flatMap { path =>
      Program
        .parseUplc(Files.readString(path))
        .toOption
        .flatMap(program => Try(program.flatEncoded).toOption)
        .map(conformanceRoot.relativize(path).toString -> _)
    }
    val sidecars = files.filter(_.toString.endsWith(".flat.cbor.hex")).flatMap { path =>
      val id = conformanceRoot.relativize(path).toString
      PlutusScriptBytes.flatOf(hex(id, Files.readString(path).trim)).map(id -> _)
    }
    encoded ++ sidecars

  private def jsonl(file: String): Vector[(String, Array[Byte])] =
    Files
      .readAllLines(Path.of(file))
      .asScala
      .toVector
      .zipWithIndex
      .flatMap { (line, n) =>
        val id = s"$file:$n"
        val row = io.circe.parser.parse(line).fold(e => throw BadFixture(id, e.message), identity)
        row.hcursor
          .get[String]("script")
          .toOption
          .flatMap(s => PlutusScriptBytes.flatOf(hex(id, s)))
          .map(id -> _)
      }

  /** The flat bytes inside the CBOR byte string of each script. */
  def scripts(probes: Vector[(String, Bytes)]): Vector[(String, Array[Byte])] =
    probes.flatMap((id, script) => PlutusScriptBytes.flatOf(script.toArray).map(id -> _))

  /** The script corpora of the reference fixtures and the boundary probes. */
  lazy val fixtures: Vector[(String, Array[Byte])] =
    Vector(
      "fixtures/reference/plutus-costing/scripts.jsonl",
      "fixtures/reference/plutus-boundaries/scripts.jsonl",
      "fixtures/reference/plutus-boundaries/transaction-scripts.jsonl",
      "fixtures/reference/plutus-upstream-boundaries/scripts.jsonl",
      "fixtures/reference/plutus-boundary-transactions/scripts.jsonl"
    ).flatMap(jsonl) ++ scripts(PlutusBoundaryCorpus.probes.map(p => p.id -> p.script))

  /** The flat decoding probes ([[FlatDecodeCorpus]]). */
  lazy val probes: Vector[(String, Array[Byte])] =
    scripts(FlatDecodeCorpus.probes.map(p => p.id -> p.script))
