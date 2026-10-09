// SPDX-License-Identifier: Apache-2.0
package lab.vm

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import scalus.uplc.Program

/** Source-only draft, not run. Add temporarily as an unmanaged source in an
  * isolated cached sbt execution; do not install it in production/CLI admission.
  */
object ExportReviewedSpend:
  private def read(path: String): String =
    val in = Files.newInputStream(Path.of(path))
    val bytes = try in.readNBytes(16385) finally in.close()
    require(bytes.length <= 16384, "resource exceeds limit")
    new String(bytes, UTF_8)

  def main(args: Array[String]): Unit =
    require(args.length == 2, "arguments: pinned cost-model.json pinned spend.uplc")
    val model = read(args(0))
    val source = read(args(1))
    // Existing exact model/script hash and closed-AST admission, before parser use.
    Pv9Profile.create(model, source).fold(e => throw new IllegalArgumentException(e), _ => ())
    val program = Program.parseUplc(source).fold(e => throw new IllegalArgumentException(e.toString), identity)
    val db = program.deBruijnedProgram
    val flat = db.flatEncoded
    val cbor = db.cborEncoded
    def hex(bytes: Array[Byte]) = bytes.map(b => f"${b & 0xff}%02x").mkString
    def digest(bytes: Array[Byte]) = hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
    println(ujson.Obj(
      "classification" -> "local serialization; not reference execution",
      "sourceSha256" -> sha256(source),
      "modelSha256" -> sha256(model),
      "flatHex" -> hex(flat),
      "flatSha256" -> digest(flat),
      "ledgerScriptHex" -> hex(cbor),
      "ledgerScriptSha256" -> digest(cbor)
    ).render())
