// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.assertions

import io.circe.{Json, JsonObject}
import io.circe.syntax.*

/** The lines of the Antithesis fallback SDK (Antithesis docs,
  * `/docs/reference/sdk/fallback/assert/` and its schema, `/docs/reference/sdk/fallback/schema/`):
  * one JSON object on one line, under `antithesis_assert`, with the fields in the order of the
  * docs' examples. A declaration has `hit` and `condition` false and no `details`; an evaluation
  * has `hit` true, the condition (false for an unreachable property, which has none) and its
  * details.
  */
object SdkLine:
  /** The declaration of a property in the catalogue, written at start. */
  def declaration(kind: Kind, message: String, location: Location): String =
    line(kind, message, location, hit = false, condition = false, details = None)

  /** An evaluation of a property: the first true or the first false one of a run. */
  def evaluation(
      kind: Kind,
      message: String,
      location: Location,
      condition: Boolean,
      details: JsonObject
  ): String =
    val held = kind match
      case Kind.Always | Kind.Sometimes => condition
      case Kind.Unreachable             => false
    line(kind, message, location, hit = true, condition = held, details = Some(details))

  private def line(
      kind: Kind,
      message: String,
      location: Location,
      hit: Boolean,
      condition: Boolean,
      details: Option[JsonObject]
  ): String =
    val fields = List(
      "hit" -> hit.asJson,
      "must_hit" -> kind.mustHit.asJson,
      "assert_type" -> kind.assertType.asJson,
      "display_type" -> kind.displayType.asJson,
      "message" -> message.asJson,
      "condition" -> condition.asJson,
      "id" -> message.asJson,
      "location" -> Json.obj(
        "class" -> location.className.asJson,
        "function" -> "".asJson,
        "file" -> location.file.asJson,
        "begin_line" -> 0.asJson,
        "begin_column" -> 0.asJson
      )
    ) ++ details.map(d => "details" -> Json.fromJsonObject(d))
    Json.obj("antithesis_assert" -> Json.fromFields(fields)).noSpaces
