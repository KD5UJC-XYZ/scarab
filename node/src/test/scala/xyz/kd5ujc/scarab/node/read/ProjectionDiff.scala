// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import io.circe.Json

/** Two projections of one state compared: the K1 JSON read from the node's flat state and from its
  * mirror (`ProjectionSuite`, `ReadCorpusMain`).
  */
object ProjectionDiff:
  /** The first path at which two projections differ. */
  def firstDifference(a: Json, b: Json, path: String = ""): Option[String] =
    (a.asObject, b.asObject) match
      case (Some(x), Some(y)) =>
        (x.keys.toSet ++ y.keys.toSet).toVector.sorted.view.flatMap { k =>
          (x(k), y(k)) match
            case (Some(l), Some(r)) => firstDifference(l, r, s"$path/$k")
            case _                  => Some(s"$path/$k is in one only")
        }.headOption
      case _ => Option.when(a != b)(s"$path: ${a.noSpaces.take(200)} / ${b.noSpaces.take(200)}")

  /** Neither is in the mirror of a flat state: the fields of the parameters the era has no place
    * for, and the amount each credential is to be rewarded (the mirror attributes them to pools,
    * which the flat state does not know: `Gap.RewardAttribution`).
    */
  def withoutEraFields(j: Json): Json =
    val params = j.hcursor
      .downField("params")
      .withFocus(
        _.mapObject(_.remove("minUTxOValue").remove("utxoCostPerWord").remove("utxoCostPerByte"))
      )
      .top
      .getOrElse(j)
    params.hcursor
      .downField("rewardUpdate")
      .withFocus(_.mapObject(_.remove("rs")))
      .top
      .getOrElse(params)

  /** The first difference once the fields the mirror of a flat state cannot have are left out. */
  def firstDifferenceOfMirror(flat: Json, mirror: Json): Option[String] =
    firstDifference(withoutEraFields(flat), withoutEraFields(mirror))
