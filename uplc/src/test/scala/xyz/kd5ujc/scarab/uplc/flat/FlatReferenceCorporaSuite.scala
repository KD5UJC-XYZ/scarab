// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import io.circe.{Decoder, Json}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as Cv}
import xyz.kd5ujc.scarab.uplc.*

/** `deserialiseScript` against the verdicts the pinned Haskell reference (plutus-ledger-api
  * 1.70.0.0, `scripts/oracle/plutus`) gave for the scripts of the reference corpora, row by row:
  * whether it admits the script, and, for an admitted one, whether the evaluation refuses it at
  * set-up because of its version (the stage `setup`, `mkTermToEvaluate`, `Common/Eval.hs:124-127`).
  * The scope, budget and evaluation stages are the machine's and are not judged here.
  */
final class FlatReferenceCorporaSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private val Root = Path.of("fixtures/reference")

  private def jsonl(file: String): Vector[Json] =
    Files
      .readAllLines(Root.resolve(file))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(line => io.circe.parser.parse(line).fold(e => fail(s"$file: ${e.message}"), identity))

  private def field[A: Decoder](row: Json, path: String*): A =
    path
      .foldLeft(row.hcursor: io.circe.ACursor)(_.downField(_))
      .as[A]
      .fold(e => fail(s"${path.mkString(".")}: ${e.message} in $row"), identity)

  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)

  private def sha256(b: Bytes): String =
    MessageDigest.getInstance("SHA-256").digest(b.toArray).map(x => f"${x & 0xff}%02x").mkString

  private def language(n: Int): PlutusLanguage =
    PlutusLanguage.values.find(_.id + 1 == n).getOrElse(fail(s"no language $n"))

  /** A verdict of the reference: a script at a language (1 to 3) and protocol. */
  private final case class Verdict(
      id: String,
      language: Int,
      protocol: Int,
      sha: String,
      admitted: Boolean,
      stage: String
  )

  /** Every row where `deserialiseScript` disagrees with the reference, with its script. */
  private def disagreements(
      verdicts: Vector[Verdict],
      scripts: Map[String, Bytes]
  ): Vector[String] =
    verdicts.flatMap { v =>
      val where = s"${v.id} at V${v.language}/${v.protocol}"
      scripts.get(v.id) match
        case None => Vector(s"$where: no script")
        case Some(script) if sha256(script) != v.sha =>
          Vector(s"$where: the script's SHA-256 is ${sha256(script)}, the verdict's ${v.sha}")
        case Some(script) =>
          val lang = language(v.language)
          val protocol = ProtocolMajor(v.protocol)
          SerialisedScript.deserialiseScript(lang, protocol, script) match
            case Left(e) if v.admitted =>
              Vector(s"$where: the reference admits ${script.hex}, ours refuses with $e")
            case Right(p) if !v.admitted =>
              Vector(s"$where: the reference refuses ${script.hex}, ours admits ${p.version.show}")
            case Right(p) =>
              val setup = SerialisedScript.forEvaluation(lang, protocol, p).isLeft
              if setup == (v.stage == "setup") then Vector.empty
              else
                Vector(s"$where: stage ${v.stage}, ours refuses the version for evaluation: $setup")
            case Left(_) => Vector.empty
    }

  private def scripts(file: String): Map[String, Bytes] =
    jsonl(file).map(row => field[String](row, "id") -> bytes(field[String](row, "script"))).toMap

  private def plain(row: Json): Verdict =
    Verdict(
      field[String](row, "id"),
      field[Int](row, "language"),
      field[Int](row, "protocol"),
      field[String](row, "scriptSha256"),
      field[Boolean](row, "admitted"),
      field[String](row, "stage")
    )

  test("plutus-boundaries: every verdict of the reference") {
    val verdicts = jsonl("plutus-boundaries/verdicts.jsonl").map(plain)
    assertEquals(verdicts.size, 477)
    assertEquals(disagreements(verdicts, scripts("plutus-boundaries/scripts.jsonl")), Vector.empty)
  }

  // `released` is the unchanged 1.70.0.0 build; `boundsBackport` a patched one (its README)
  test("plutus-upstream-boundaries: every verdict of the released reference") {
    val verdicts = jsonl("plutus-upstream-boundaries/verdicts.jsonl").map { row =>
      Verdict(
        field[String](row, "id"),
        field[Int](row, "language"),
        field[Int](row, "protocol"),
        field[String](row, "scriptSha256"),
        field[Boolean](row, "released", "admitted"),
        field[String](row, "released", "stage")
      )
    }
    assertEquals(verdicts.size, 54)
    assertEquals(
      disagreements(verdicts, scripts("plutus-upstream-boundaries/scripts.jsonl")),
      Vector.empty
    )
  }

  test(
    "plutus-flat-decode: every probe, written again and bound by its hash, as the reference judged it"
  ) {
    val verdicts = jsonl("plutus-flat-decode/verdicts.jsonl").map(plain)
    val probes = FlatProbes.probes.map(p => p.id -> p.script).toMap
    assertEquals(probes.size, FlatProbes.probes.size, "probe ids are unique")
    assertEquals(verdicts.map(_.id).toSet, probes.keySet)
    assertEquals(verdicts.size, 435)
    assertEquals(disagreements(verdicts, probes), Vector.empty)
  }

  test("the malformed value constants of the conformance corpus are refused by the Flat decoder") {
    val sidecars = ReferenceCorpus.view("1.70.0.0").collect {
      case (name, path) if name.endsWith(".flat.cbor.hex") =>
        name -> bytes(Files.readString(path).trim)
    }
    assertEquals(sidecars.size, 6)
    sidecars.foreach { (name, script) =>
      val result =
        SerialisedScript.deserialiseScript(PlutusLanguage.V3, ProtocolMajor.VanRossem, script)
      assert(
        result match
          case Left(ScriptDecodeError.Flat(FlatError.InvalidValue(_))) => true
          case _                                                       => false
        ,
        s"$name: $result"
      )
    }
  }

  // The legacy rows are transactions the reference node 11.1.3 admitted or judged in phase 2, so
  // each script of their witness sets deserialised; the auxiliary rows' scripts are not evaluated
  // and their verdicts are not `deserialiseScript`'s (fixtures/reference/auxiliary-scripts).
  test("plutus-admission: every script of the witness sets of the node-admitted transactions") {
    val rows = Vector("admissions.json", "rebased-admissions.json").flatMap { file =>
      val doc = io.circe.parser
        .parse(Files.readString(Root.resolve(s"plutus-admission/$file")))
        .fold(e => fail(e.message), identity)
      field[Vector[Json]](doc, "observations").filter(field[String](_, "family") == "legacy")
    }
    val found = rows.flatMap { row =>
      val protocol = field[Int](row, "referenceBefore", "protocolVersion", "major")
      witnessScripts(bytes(field[String](row, "tx"))).map((lang, script) =>
        (field[String](row, "case"), protocol, lang, script)
      )
    }
    assert(found.size >= rows.size, s"${found.size} scripts in ${rows.size} transactions")
    val refused = found.flatMap { (name, protocol, lang, script) =>
      SerialisedScript
        .deserialiseScript(lang, ProtocolMajor(protocol), script)
        .left
        .toOption
        .map(e => s"$name $lang/$protocol ${script.hex}: $e")
    }
    assertEquals(refused, Vector.empty)
  }

  /** The Plutus scripts of a transaction's witness set (keys 3, 6 and 7), each as the ledger holds
    * it.
    */
  private def witnessScripts(tx: Bytes): Vector[(PlutusLanguage, Bytes)] =
    def items(n: Node): Vector[Node] = n.value match
      case Cv.Arr(xs)    => xs
      case Cv.Tag(_, xs) => items(xs)
      case _             => Vector.empty
    val node = Cbor.decode(tx, Cbor.Limits.Unbounded).fold(e => fail(e.message), identity)
    val witnesses = items(node).lift(1).map(_.value)
    witnesses match
      case Some(Cv.Map(entries)) =>
        entries.flatMap { (k, v) =>
          val lang = k.value match
            case Cv.UInt(n) if n == 3 => Some(PlutusLanguage.V1)
            case Cv.UInt(n) if n == 6 => Some(PlutusLanguage.V2)
            case Cv.UInt(n) if n == 7 => Some(PlutusLanguage.V3)
            case _                    => None
          lang.toVector.flatMap(l =>
            items(v).flatMap(n =>
              n.value match
                case Cv.ByteString(b) => Some(l -> b)
                case _                => None
            )
          )
        }
      case other => fail(s"no witness set: $other")
