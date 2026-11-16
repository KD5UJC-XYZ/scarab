// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{EvalRequest, PlutusLanguage}
import xyz.kd5ujc.scarab.uplc.builtin.core.DataSerialisation

/** The script requests the ledger collects ([[Phase2.collect]]) for the recorded Preview and
  * preprod transactions (`fixtures/preview/plutus`, `fixtures/preprod/plutus`), written as requests
  * of the pinned evaluator of `scripts/oracle/plutus/generated` (plutus 1.70.0.0): the script the
  * ledger holds, the `Data` arguments as CBOR, the cost-model list the ledger passes, the protocol,
  * and the redeemer's execution units as the budget. `RecordedRequestsMain` writes them into an
  * ignored directory and their record into [[Directory]] (the script and the arguments by digest),
  * `scripts/oracle/plutus/recorded/run.py` has the pinned evaluator judge them, and
  * `RecordedRequestOracleSuite` collects them again and replays the verdicts.
  */
object RecordedRequests:
  /** The retained fixture, relative to the repository's root. */
  val Directory: String = "fixtures/reference/plutus-recorded-requests"

  /** The plutus release whose evaluator judges the requests. */
  val Release: String = "1.70.0.0"

  /** The recorded cases, each directory with the network it was recorded on. */
  val Sources: Vector[(String, String)] =
    Vector("preview" -> "fixtures/preview/plutus", "preprod" -> "fixtures/preprod/plutus")

  /** A file that is not what its writer wrote, or a recorded case the ledger does not collect. */
  final case class Malformed(what: String) extends Exception(what)

  /** A collected request, named by its case and its redeemer. */
  final case class Recorded(id: String, file: String, request: EvalRequest)

  private def files(directory: String): Vector[Path] =
    val path = Path.of(directory)
    // committed fixtures: a missing directory fails rather than leaving no requests
    if !Files.isDirectory(path) then throw Malformed(s"missing committed fixture directory $path")
    val stream = Files.list(path)
    val all = stream.iterator.asScala.toVector
    stream.close()
    all.filter(_.toString.endsWith(".json")).sortBy(_.getFileName.toString)

  /** The cases of a file: one case, or an array of them. */
  private def cases(network: String, path: Path): Vector[(String, PlutusCase)] =
    val json =
      parse(Files.readString(path)).fold(e => throw Malformed(s"$path: ${e.message}"), identity)
    val stem = s"$network/${path.getFileName.toString.stripSuffix(".json")}"
    json.asArray.fold(Vector(stem -> PlutusCase.fromJson(json)))(
      _.zipWithIndex.map((j, n) => s"$stem[$n]" -> PlutusCase.fromJson(j))
    )

  private def scriptsOf(c: PlutusCase): Map[Bytes, Script] =
    (c.spent.values ++ c.references.values)
      .flatMap(_.referenceScript)
      .map(s => s.hash -> s)
      .toMap ++ c.tx.witnesses.scriptsByHash

  /** The reference's names of the redeemer tags (`PlutusPurpose`), in tag order. */
  private val tags = Vector("spend", "mint", "cert", "reward", "vote", "propose")

  /** The requests of a case, in the order the ledger evaluates them. A case the ledger does not
    * collect is an error: none is dropped.
    */
  private def requestsOf(id: String, file: String, c: PlutusCase): Vector[Recorded] =
    Phase2
      .collect(c.tx, c.params, scriptsOf(c), c.spent, c.references, c.posix)(using
        TestLedger.usualEra(c.protocolMajor)
      )
      .fold(
        errors =>
          throw Malformed(s"$id: not collected: ${errors.toVector.map(_.message).mkString("; ")}"),
        _.map((redeemer, request) =>
          Recorded(
            s"$id/${tags.lift(redeemer.tag).getOrElse(redeemer.tag.toString)}.${redeemer.index}",
            file,
            request
          )
        )
      )

  final case class Source(file: String, sha256: String, cases: Int, requests: Vector[Recorded])

  def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  def sha256(path: Path): String = sha256(Files.readAllBytes(path))

  /** Every recorded case file, with its digest and the requests collected for it. */
  def sources: Vector[Source] =
    Sources.flatMap((network, directory) =>
      files(directory).map { path =>
        val file = path.toString
        val read = cases(network, path)
        Source(file, sha256(path), read.size, read.flatMap((id, c) => requestsOf(id, file, c)))
      }
    )

  /** The cost-model lists of the requests, each language, protocol and list once, in the order the
    * requests first name them.
    */
  def models(requests: Vector[Recorded]): Vector[(PlutusLanguage, Int, Vector[Long])] =
    requests.map(r => (r.request.language, r.request.protocolMajor, r.request.costModel)).distinct

  private def modelJson(model: (PlutusLanguage, Int, Vector[Long])): Json =
    val (language, protocol, costModel) = model
    Json.obj(
      "language" -> Json.fromInt(language.id + 1),
      "protocol" -> Json.fromInt(protocol),
      "costModel" -> Json.arr(costModel.map(Json.fromLong)*)
    )

  private def common(r: Recorded, model: Int): Vector[(String, Json)] =
    Vector(
      "id" -> Json.fromString(r.id),
      "case" -> Json.fromString(r.file),
      "model" -> Json.fromInt(model),
      "language" -> Json.fromInt(r.request.language.id + 1),
      "protocol" -> Json.fromInt(r.request.protocolMajor)
    )

  private def budget(r: Recorded): Vector[(String, Json)] =
    Vector(
      "cpu" -> Json.fromBigInt(r.request.budget.steps),
      "memory" -> Json.fromBigInt(r.request.budget.mem)
    )

  private def arguments(r: Recorded): Vector[Bytes] =
    r.request.args.map(DataSerialisation.serialise)

  /** The request the oracle reads, without the cost-model list, which `models.jsonl` holds: the
    * script and the arguments in hex.
    */
  def oracleJson(r: Recorded, model: Int): Json =
    Json.fromFields(
      common(r, model) ++ Vector(
        "script" -> Json.fromString(r.request.script.hex),
        "arguments" -> Json.arr(arguments(r).map(a => Json.fromString(a.hex))*)
      ) ++ budget(r) :+ ("verbose" -> Json.True)
    )

  /** The request as the fixture retains it: the script and each argument by its SHA-256, since the
    * recorded cases hold them and the context of a large transaction is large.
    */
  def retainedJson(r: Recorded, model: Int): Json =
    Json.fromFields(
      common(r, model) ++ Vector(
        "scriptSha256" -> Json.fromString(sha256(r.request.script.toArray)),
        "argumentsSha256" -> Json.arr(arguments(r).map(a => Json.fromString(sha256(a.toArray)))*)
      ) ++ budget(r)
    )

  /** The files of the corpus as text: `corpus.json`, `models.jsonl` and `requests.jsonl` of the
    * fixture, and the requests the oracle reads, which are not retained.
    */
  final case class Corpus(
      recorded: Vector[Recorded],
      corpus: String,
      models: String,
      requests: String,
      oracleRequests: String
  )

  def corpus: Corpus =
    val all = sources
    val recorded = all.flatMap(_.requests)
    val lists = models(recorded)
    val index = lists.zipWithIndex.toMap
    val numbered = recorded.map(r =>
      r -> index((r.request.language, r.request.protocolMajor, r.request.costModel))
    )
    def jsonl(rows: Vector[Json]) = rows.map(_.noSpaces).mkString("", "\n", "\n")
    val corpusJson = Json.obj(
      "release" -> Json.fromString(Release),
      "collection" -> Json.fromString(
        "Phase2.collect in the era TestLedger.usualEra gives the case's protocol version"
      ),
      "cases" -> Json.fromInt(all.map(_.cases).sum),
      "requests" -> Json.fromInt(recorded.size),
      "sources" -> Json.arr(
        all.map(s =>
          Json.obj(
            "file" -> Json.fromString(s.file),
            "sha256" -> Json.fromString(s.sha256),
            "cases" -> Json.fromInt(s.cases),
            "requests" -> Json.fromInt(s.requests.size)
          )
        )*
      )
    )
    Corpus(
      recorded,
      corpusJson.spaces2 + "\n",
      jsonl(lists.map(modelJson)),
      jsonl(numbered.map(retainedJson)),
      jsonl(numbered.map(oracleJson))
    )

  /** Writes the requests the oracle reads into `args(0)/requests.jsonl` (a git-ignored directory of
    * the checkout, such as `.workspace/data/recorded-requests`, which `run.py --evidence` then
    * names), and `corpus.json`, `models.jsonl` and `requests.jsonl` into the fixture directory
    * `args(1)` ([[Directory]] by default).
    */
  def write(args: Array[String]): Unit =
    val evidence =
      Path.of(args.headOption.getOrElse(throw Malformed("no directory for the oracle's requests")))
    val directory = Path.of(args.lift(1).getOrElse(Directory))
    val written = corpus
    Files.createDirectories(evidence)
    Files.createDirectories(directory)
    Files.writeString(evidence.resolve("requests.jsonl"), written.oracleRequests)
    Files.writeString(directory.resolve("corpus.json"), written.corpus)
    Files.writeString(directory.resolve("models.jsonl"), written.models)
    Files.writeString(directory.resolve("requests.jsonl"), written.requests)
    val byLanguage = written.recorded.groupMapReduce(_.request.language)(_ => 1)(_ + _)
    println(
      s"${written.recorded.size} requests (${byLanguage.toVector.sortBy(_._1.id).map((l, n) => s"$l $n").mkString(", ")}) " +
        s"with ${models(written.recorded).size} cost-model lists into $directory and $evidence"
    )
