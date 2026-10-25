// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scalus.uplc.Program
import xyz.kd5ujc.scarab.codec.Bytes

/** Phase-2 probes for `serialiseData` of constructor data with a wide index: a script hashes the
  * serialised data and compares the digest with a constant. The index, the reference bytes and both
  * digests come from `fixtures/reference/plutus-serialise-data/vectors.tsv`, which the reference
  * tool wrote; nothing here computes an expected value.
  *
  * The "reference" probe compares with the digest of the reference encoding; the "chunked" probe
  * (only where the chunked-integer encoding differs) compares with the digest of the bytes a
  * constructor index written like `Data.I` would give.
  */
object SerialiseDataCorpus:
  final case class Row(
      bits: Int,
      negative: Boolean,
      index: BigInt,
      bytes: String,
      sha: String,
      chunkedBytes: String,
      chunkedSha: String
  ):
    def name: String = s"$bits${if negative then "-" else "+"}"

  final case class Probe(id: String, script: Bytes, vector: Row)

  val vectors: Vector[Row] = Files
    .readAllLines(Path.of("fixtures/reference/plutus-serialise-data/vectors.tsv"))
    .asScala
    .toVector
    .filterNot(_.startsWith("#"))
    .map { line =>
      line.split("\t").toVector match
        case Vector(bits, sign, index, bytes, sha, chunkedBytes, chunkedSha) =>
          Row(bits.toInt, sign == "-", BigInt(index), bytes, sha, chunkedBytes, chunkedSha)
        case other => sys.error(s"malformed serialise-data vector: $other")
    }

  private def script(index: BigInt, digest: String): Bytes =
    val program =
      s"""(program 1.0.0
         |  (force
         |    [ (force (builtin ifThenElse))
         |      [ (builtin equalsByteString)
         |        [ (builtin sha2_256)
         |          [ (builtin serialiseData)
         |            [ (builtin constrData) (con integer $index) (con (list data) []) ] ] ]
         |        (con bytestring #$digest) ]
         |      (delay (con unit ()))
         |      (delay (error)) ]))""".stripMargin
    Bytes.fromArray(Program.parseUplc(program).fold(e => sys.error(e.toString), _.cborEncoded))

  val probes: Vector[Probe] = vectors.flatMap { v =>
    val reference = Probe(s"constr-index-${v.name}-reference-digest", script(v.index, v.sha), v)
    val chunked =
      if v.bytes == v.chunkedBytes then Vector.empty
      else Vector(Probe(s"constr-index-${v.name}-chunked-digest", script(v.index, v.chunkedSha), v))
    reference +: chunked
  }

  val languages: Vector[PlutusLanguage] = Vector(PlutusLanguage.V2, PlutusLanguage.V3)
  val protocols: Vector[Int] = Vector(9, 10, 11)

  /** Writes the requests for the reference oracle (`scripts/oracle/plutus/README.md`). */
  def main(args: Array[String]): Unit =
    val out = Path.of(args.headOption.getOrElse("/oracle/requests.jsonl"))
    val rows = for
      probe <- probes
      language <- languages
      protocol <- protocols
    yield Json.obj(
      "id" -> Json.fromString(probe.id),
      "language" -> Json.fromInt(language.id + 1),
      "protocol" -> Json.fromInt(protocol),
      "script" -> Json.fromString(probe.script.hex),
      "costModel" -> Json
        .arr(PlutusBoundaryCorpus.costModel(language, protocol).map(Json.fromLong)*)
    )
    Files.writeString(out, rows.map(_.noSpaces).mkString("\n") + "\n")
