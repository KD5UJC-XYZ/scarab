// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

/** Preserve the accepted upstream CERTS row and make its unsupported Scarab mapping explicit. */
class UpstreamCertsCoverageSuite extends FunSuite:
  private val base = Path.of("node/src/test/resources/upstream/conway-certs")
  private val seed = 494101L

  test("the accepted generated CERTS trace is retained as unsupported with verified files") {
    val manifest = parse(Files.readString(base.resolve("manifest.json")))
      .fold(error => fail(s"CERTS manifest: $error"), identity)
    val rows = manifest.hcursor
      .downField("cases")
      .as[Vector[Json]]
      .fold(error => fail(s"CERTS rows: $error"), identity)
    val row = rows
      .find(_.hcursor.get[Long]("seed").toOption.contains(seed))
      .getOrElse(fail(s"missing exact CERTS seed $seed"))
    assertEquals(
      row.hcursor.get[String]("upstreamStatus").toOption,
      Some("implementation-accepted")
    )
    assertEquals(row.hcursor.get[String]("agdaStatus").toOption, Some("agda-accepted"))
    assertEquals(row.hcursor.get[String]("adapterStatus").toOption, Some("unsupported"))

    val dir = base.resolve(s"cases/$seed")
    Vector(
      "context.cbor",
      "environment.cbor",
      "state.cbor",
      "signal.cbor",
      "implementation-state.cbor",
      "result.txt"
    )
      .foreach { name =>
        val path = dir.resolve(name)
        val file = row.hcursor.downField("files").downField(name)
        val digest = MessageDigest
          .getInstance("SHA-256")
          .digest(Files.readAllBytes(path))
          .map(b => f"${b & 0xff}%02x")
          .mkString
        assertEquals(file.get[String]("sha256").toOption, Some(digest), name)
        assertEquals(file.get[Int]("bytes").toOption, Some(Files.size(path).toInt), name)
      }
  }
