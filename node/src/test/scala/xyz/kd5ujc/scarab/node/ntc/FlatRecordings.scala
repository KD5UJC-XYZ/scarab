// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.{Assertions, Location}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayLedgerPredFailure
import xyz.kd5ujc.scarab.ledger.state.{Conway, LedgerState, ProtVer, ShelleyBasedEra, Word64}
import xyz.kd5ujc.scarab.protocol.{ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellLedgerFile

/** The Conway refusals the flat rules' encoder wrote before it was deleted, and the judgments of
  * the flat LEDGER, LEDGERS and GOV, recorded for the suites that hold Conway's era encoder and
  * rules to them (`fixtures/conway-flat-refusals`, #85).
  */
object FlatRecordings:
  private val Dir = Path.of("fixtures/conway-flat-refusals")

  /** The first 16 hex digits of the SHA-256 of `bytes`, the recordings' digest. */
  def digest(bytes: Array[Byte]): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .take(8)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  def digest(bytes: Bytes): String = digest(bytes.toArray)

  private def lines(name: String): Vector[io.circe.Json] =
    Files
      .readAllLines(Dir.resolve(name))
      .asScala
      .toVector
      .map(l => io.circe.parser.parse(l).fold(e => Assertions.fail(s"$name: $e"), identity))

  private def field(name: String, j: io.circe.Json, key: String): String =
    j.hcursor.get[String](key).fold(e => Assertions.fail(s"$name: $key, $e"), identity)

  /** The records of `name` by their field `key`, failing the test where a key occurs twice. */
  private def keyed(name: String, key: String)(
      value: io.circe.Json => String
  ): Map[String, String] =
    val rows = lines(name).map(j => field(name, j, key) -> value(j))
    val ids = rows.map(_._1)
    val twice = ids.diff(ids.distinct).distinct
    if twice.nonEmpty then Assertions.fail(s"$name: $key more than once: $twice")
    rows.toMap

  /** `ConwayLedgerChecksSuite` 's judgments of each case: the digest of each refusal's bytes, `-`
    * for an admission, in the recording's order.
    */
  lazy val ledgerChecks: Map[String, Vector[String]] =
    keyed("ledger-checks.jsonl", "case")(field("ledger-checks.jsonl", _, "r"))
      .map((k, v) => k -> v.split(' ').toVector)

  /** `ConwayMempoolRunSuite` 's root judgments of each corpus case: `R:` and the digest of a
    * refusal's bytes, or `A:` and that of the ledger state an admission leaves, in the recording's
    * order.
    */
  lazy val mempoolRun: Map[String, Vector[String]] =
    keyed("mempool-run.jsonl", "case")(field("mempool-run.jsonl", _, "r"))
      .map((k, v) => k -> v.split(' ').toVector)

  /** `ConwayFailureCborSuite` 's cases the flat encoder wrote: each case's id to the digest of its
    * bytes.
    */
  lazy val failureConstructors: Map[String, String] =
    keyed("failure-constructors.jsonl", "id")(field("failure-constructors.jsonl", _, "bytes"))

  /** The digest of the bytes the flat encoder wrote for the case `id`, failing the test where none
    * was recorded.
    */
  def wrote(id: String): String =
    failureConstructors.getOrElse(id, Assertions.fail(s"no recording of $id"))

  /** The judgments of Conway's era LEDGER and LEDGERS that the flat ones gave the same of, in the
    * suites that compared them (`ledger-judgments.jsonl`): each id to its cells ([[refused]],
    * [[admitted]]), separated by spaces.
    */
  lazy val ledgerJudgments: Map[String, Vector[String]] =
    keyed("ledger-judgments.jsonl", "id")(field("ledger-judgments.jsonl", _, "r"))
      .map((k, v) => k -> v.split(' ').toVector)

  /** The recorded judgments whose ids start with `prefix`, failing the test where none is. */
  def judgments(prefix: String): Map[String, Vector[String]] =
    val found = ledgerJudgments.filter((id, _) => id.startsWith(prefix))
    if found.isEmpty then Assertions.fail(s"no recording under $prefix")
    found

  /** The judgments of Conway's era GOV and its predicates that the flat GOV gave the same of, in
    * `ConwayGovChecksSuite` (`gov-judgments.jsonl`): each id to its cells, separated by spaces.
    */
  lazy val govJudgments: Map[String, Vector[String]] =
    keyed("gov-judgments.jsonl", "id")(field("gov-judgments.jsonl", _, "r"))
      .map((k, v) => k -> v.split(' ').toVector)

  /** A refusal's cell: `R:` and the digest of its `ApplyTxErr` bytes, every failure in its order.
    */
  def refused(failures: Iterable[ConwayLedgerPredFailure[Conway]])(using Location): String =
    "R:" + digest(ConwayFailureCborCheck.direct(failures))

  /** The layout an admission's state is written in, for a judgment under `build`: the build's own,
    * and 11.1's for 11.2's, whose layout is not written yet (`ReferenceBuildNotYet.Layout`).
    */
  def layoutOf(build: ReferenceBuild): StateLayout =
    StateLayout.of(build).getOrElse(StateLayout.Node11_1)

  /** An admission's cell: `A:` and the digest of the ledger state it leaves, in the Haskell state's
    * encoding of `layout` at protocol version 9, then the counters of the scripts it ran: those
    * that succeeded, those that failed, and all it ran.
    */
  def admitted(
      state: LedgerState[Conway],
      succeeded: Long,
      failed: Long,
      scripts: Long,
      layout: StateLayout = StateLayout.Node11_1
  )(using Location): String =
    given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
    given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
    val bytes = HaskellLedgerFile
      .codecs(layout)
      .ledgerState[Conway]
      .write(state)
      .fold(e => Assertions.fail(s"the state: $e"), identity)
    s"A:${digest(bytes)}/$succeeded/$failed/$scripts"
