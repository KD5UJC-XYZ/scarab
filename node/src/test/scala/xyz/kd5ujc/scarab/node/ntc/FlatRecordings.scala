// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.Assertions
import xyz.kd5ujc.scarab.codec.Bytes

/** The Conway refusals the flat rules' encoder wrote before it was deleted, recorded for the suites
  * that hold Conway's era encoder to them (`fixtures/conway-flat-refusals`, #85).
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
