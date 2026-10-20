// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scalus.uplc.{Program, Term, Constant}
import xyz.kd5ujc.scarab.codec.Bytes

/** Deterministic inputs for the independent Haskell ledger-API costing matrix. */
object BuiltinCostingCorpus:
  final case class Probe(id: String, script: Bytes, builtins: Set[String])
  private val source =
    Path.of("plutus/src/test/resources/plutus-conformance-1.70.0.0/builtin/semantics")
  private def compile(id: String, text: String): Probe =
    val p = Program.parseUplc(text).fold(e => sys.error(s"$id: $e"), identity)
    locally {
      val ignored = Term.Apply(Term.LamAbs("ignored", Term.Const(Constant.Unit)), p.term)
      Probe(
        id,
        Bytes.fromArray(Program(p.version, ignored).cborEncoded),
        "\\(builtin\\s+([^\\s)]+)".r.findAllMatchIn(text).map(_.group(1)).toSet
      )
    }
  lazy val upstream: Vector[Probe] =
    val files = Files.walk(source)
    try
      files
        .iterator()
        .asScala
        .filter(_.toString.endsWith(".uplc"))
        .toVector
        .sortBy(_.toString)
        .map(p => compile("upstream/" + source.relativize(p).toString, Files.readString(p)))
    finally files.close()

  private def call(name: String, arguments: String*): String =
    arguments.foldLeft(s"(builtin $name)")((term, arg) => s"[$term $arg]")
  private def integer(n: BigInt): String = s"(con integer $n)"
  private def bytes(n: Int): String = s"(con bytestring #${"01" * n})"
  private def probe(id: String, term: String): Probe =
    compile(id, s"(program 1.0.0 $term)")

  val generated: Vector[Probe] =
    val lengths = Vector(0, 1, 7, 8, 9, 31, 32, 33, 63, 64, 65, 255, 256, 257, 1024)
    val unary = Vector(
      "sha2_256",
      "sha3_256",
      "blake2b_256",
      "blake2b_224",
      "keccak_256",
      "ripemd_160",
      "lengthOfByteString",
      "complementByteString",
      "countSetBits",
      "findFirstSetBit"
    )
    val binary = Vector(
      "appendByteString",
      "equalsByteString",
      "lessThanByteString",
      "lessThanEqualsByteString"
    )
    val words = Vector(1, 2, 3, 4, 7, 16, 40, 120)
    val integers = Vector(
      "addInteger",
      "subtractInteger",
      "multiplyInteger",
      "divideInteger",
      "quotientInteger",
      "remainderInteger",
      "modInteger",
      "equalsInteger",
      "lessThanInteger",
      "lessThanEqualsInteger"
    )
    (for name <- unary; n <- lengths yield probe(s"bytes/$name/$n", call(name, bytes(n)))) ++
      (for name <- binary; n <- lengths; m <- Vector(0, n, n + 1)
      yield probe(s"bytes/$name/$n-$m", call(name, bytes(n), bytes(m)))) ++
      (for name <- integers; x <- words; y <- words
      yield probe(
        s"integer/$name/$x-$y",
        call(name, integer(BigInt(1) << (64 * (x - 1))), integer(BigInt(1) << (64 * (y - 1))))
      )) ++
      (for n <- lengths
      yield probe(
        s"ed25519/message-$n",
        call("verifyEd25519Signature", bytes(32), bytes(n), bytes(64))
      ))

  val structured: Vector[Probe] =
    Vector(0, 1, 7, 8, 9, 31, 32, 33, 64, 128).flatMap { n =>
      val list = s"(con (list integer) [${Vector.fill(n)("1").mkString(",")}])"
      val data = s"(con data (List [${Vector.fill(n)("I 1").mkString(",")}]))"
      val value =
        s"(con value [${(0 until n).map(i => s"(#${f"$i%04x"}, [(#, 1)])").mkString(",")}])"
      Vector(
        probe(s"structured/serialiseData/$n", call("serialiseData", data)),
        probe(s"structured/equalsData/$n", call("equalsData", data, data)),
        probe(s"structured/encodeUtf8/$n", call("encodeUtf8", s"(con string \"${"a" * n}\")")),
        probe(s"structured/listToArray/$n", s"[(force (builtin listToArray)) $list]"),
        probe(
          s"structured/lengthOfArray/$n",
          s"[(force (builtin lengthOfArray)) [(force (builtin listToArray)) $list]]"
        ),
        probe(s"structured/valueData/$n", call("valueData", value)),
        probe(s"structured/unionValue/$n", call("unionValue", value, value)),
        probe(s"structured/valueContains/$n", call("valueContains", value, value))
      )
    }
  lazy val probes: Vector[Probe] = (upstream ++ generated ++ structured).distinctBy(_.id)
  val profiles: Vector[(Int, Int)] = (for
    language <- 1 to 3;
    protocol <- (if language == 1 then 5 else if language == 2 then 7 else 9) to 11
  yield (language, protocol)).toVector
  def parameters(language: Int, protocol: Int): Vector[Long] =
    val variant =
      if language == 3 then (if protocol < 11 then "C" else "E")
      else if protocol < 9 then "A"
      else if protocol < 11 then "B"
      else "D"
    val builtins = PlutusConformanceSuite.json(s"builtinCostModel$variant.json")
    val machine = PlutusConformanceSuite.json(s"cekMachineCosts$variant.json")
    PlutusConformanceSuite
      .resource(s"V$language-param-names.txt")
      .linesIterator
      .filter(_.nonEmpty)
      .map { name =>
        val path = name.split('\'').toVector
        val head = s"${path.head.head.toLower}${path.head.tail}"
        path.tail
          .foldLeft((if head.startsWith("cek") then machine else builtins).hcursor.downField(head))(
            (cursor, field) => cursor.downField(field)
          )
          .as[Long]
          .toOption
      }
      .takeWhile(_.isDefined)
      .flatten
      .toVector

  def main(args: Array[String]): Unit =
    val directory = Path.of(args.head)
    Files.createDirectories(directory)
    Files.writeString(
      directory.resolve("scripts.jsonl"),
      probes
        .map(p =>
          Json
            .obj("id" -> Json.fromString(p.id), "script" -> Json.fromString(p.script.hex))
            .noSpaces
        )
        .mkString("", "\n", "\n")
    )
    val models = for (language, protocol) <- profiles; scale <- Vector(1, 2)
    yield Json.obj(
      "language" -> Json.fromInt(language),
      "protocol" -> Json.fromInt(protocol),
      "scale" -> Json.fromInt(scale),
      "costModel" -> Json.arr(parameters(language, protocol).map(n => Json.fromLong(n * scale))*)
    )
    Files.writeString(
      directory.resolve("models.jsonl"),
      models.map(_.noSpaces).mkString("", "\n", "\n")
    )
