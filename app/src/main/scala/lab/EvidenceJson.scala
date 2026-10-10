// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.charset.StandardCharsets.UTF_8
import lab.cbor.Bytes
import ReferenceJson.Json as J

/** Stable private evidence JSON serialization; no projection or validation logic. */
private[lab] object EvidenceJson:
  def render(j: J): String = j match
    case J.Obj(fs) =>
      fs.toVector
        .sortBy(_._1)
        .map((k, v) => render(J.Str(k)) + ":" + render(v))
        .mkString("{", ",", "}")
    case J.Arr(xs) => xs.map(render).mkString("[", ",", "]")
    case J.Str(s) =>
      "\"" + s.flatMap {
        case '\"' => "\\\""; case '\\' => "\\\\"; case c if c < ' ' => f"\\u${c.toInt}%04x";
        case c    => c.toString
      } + "\""
    case J.Num(n) => n
    case J.Lit(l) => l
  def encode(j: J): Bytes = Bytes.fromArray((render(j) + "\n").getBytes(UTF_8))
