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

  /** `ConwayLedgerChecksSuite` 's judgments of each case: the digest of each refusal's bytes, `-`
    * for an admission, in the recording's order.
    */
  lazy val ledgerChecks: Map[String, Vector[String]] =
    lines("ledger-checks.jsonl").map { j =>
      field("ledger-checks", j, "case") ->
        field("ledger-checks", j, "r").split(' ').toVector
    }.toMap

  /** `ConwayFailureCborSuite` 's failure lists: the digest of a list's `toString` to that of its
    * bytes.
    */
  lazy val failureConstructors: Map[String, String] =
    lines("failure-constructors.jsonl").map { j =>
      field("failure-constructors", j, "failure") -> field("failure-constructors", j, "bytes")
    }.toMap

  /** The digest of the bytes the flat encoder wrote for `failures`, failing the test where none was
    * recorded.
    */
  def wrote(failures: List[Any]): String =
    val key = digest(failures.toString.getBytes("UTF-8"))
    failureConstructors.getOrElse(key, Assertions.fail(s"no recording of $failures"))
