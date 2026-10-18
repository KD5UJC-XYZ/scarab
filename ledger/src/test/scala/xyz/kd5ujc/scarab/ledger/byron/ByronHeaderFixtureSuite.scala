// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, Header}

/** Actual pinned CLI full-block decoder verdicts, not reference signature/ledger acceptance. */
class ByronHeaderFixtureSuite extends FunSuite:
  private val directory = Path.of("fixtures/byron-version/headers")
  private val cases = parse(Files.readString(directory.resolve("manifest.json")))
    .flatMap(_.hcursor.get[Vector[io.circe.Json]]("cases"))
    .fold(e => fail(e.getMessage), identity)

  cases.foreach { json =>
    val row = json.hcursor
    val name = row.get[String]("name").fold(e => fail(e.message), identity)
    val file = row.get[String]("file").fold(e => fail(e.message), identity)
    val fields = row.get[Vector[BigInt]]("version").fold(e => fail(e.message), identity)
    val exit = row.downField("reference").get[Int]("exitCode").fold(e => fail(e.message), identity)

    test(s"complete public block $name agrees with the executed reference decoder") {
      val raw =
        Bytes
          .fromHex(Files.readString(directory.resolve(file)).trim)
          .fold(e => fail(e.toString), identity)
      val decoded = Block.decode(raw, 21600)
      if exit == 0 then
        val version = fields match
          case Vector(major, minor, alt) =>
            ByronVersion.from(major, minor, alt).fold(e => fail(e.message), identity)
          case _ => fail("fixture version must have three components")
        decoded.fold(
          e => fail(e.toString),
          {
            case b: Block.Byron =>
              assertEquals(b.header.blockVersion, version)
              assertEquals(Header.byronFromRaw(b.header.raw, false, 21600), Right(b.header))
            case _ => fail("expected Byron block")
          }
        )
      else assert(decoded.isLeft)
    }
  }
