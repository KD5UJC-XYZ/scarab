// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import java.time.Instant
import io.circe.Json
import io.circe.syntax.*

/** A [[Line]] as text, without the newline. */
object Render:
  /** One JSON object: `event`, `at`, then the event's fields in order. The scenarios
    * (`scripts/devnet`) and the run watchers parse these; the golden (`fixtures/log/golden.jsonl`)
    * keeps them byte for byte.
    */
  def json(line: Line, at: Instant): String =
    Json
      .obj(
        (("event" -> line.kind.name.asJson) +: ("at" -> at.toString.asJson) +: line.fields)*
      )
      .noSpaces

  /** For a person: `[time][namespace](Severity) event key=value ...`, after trace-dispatcher's
    * human format (`[time][host:namespace](Severity,thread) message`, hermod-tracing
    * `trace-dispatcher/src/Cardano/Logging/Formatter.hs` 188-212) without the host and the thread.
    * A string value is written as it is, any other value as compact JSON.
    */
  def human(line: Line, at: Instant): String =
    val fields =
      line.fields.map((key, value) => s"$key=${value.asString.getOrElse(value.noSpaces)}")
    (s"[$at][${line.kind.namespace}](${line.severity}) ${line.kind.name}" :: fields).mkString(" ")

  def apply(format: Format, line: Line, at: Instant): String = format match
    case Format.Json  => json(line, at)
    case Format.Human => human(line, at)
