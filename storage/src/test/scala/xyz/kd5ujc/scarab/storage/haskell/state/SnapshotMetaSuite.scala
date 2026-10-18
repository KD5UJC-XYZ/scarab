// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import io.circe.Json
import munit.FunSuite

class SnapshotMetaSuite extends FunSuite:
  private val valid = Json.obj(
    "backend" -> Json.fromString("utxohd-mem"),
    "checksum" -> Json.fromLong(0xffffffffL),
    "tablesCodecVersion" -> Json.fromInt(1)
  )
  private def changed(field: MetaField, value: Json): String =
    valid.mapObject(_.add(field.name, value)).noSpaces

  test("invalid JSON retains the parser failure") {
    SnapshotMeta.parse("{") match
      case Left(MetaError.JsonSyntax(cause)) => assert(cause.message.nonEmpty)
      case other                             => fail(s"expected a JSON syntax failure, got $other")
  }

  test("non-object roots retain the supplied JSON value") {
    Vector(Json.Null, Json.arr(), Json.True, Json.fromInt(1), Json.fromString("meta")).foreach {
      json =>
        assertEquals(SnapshotMeta.parse(json.noSpaces), Left(MetaError.ExpectedObject(json)))
    }
  }

  test("missing fields name the reference metadata field") {
    MetaField.values.foreach { field =>
      val text = valid.mapObject(_.remove(field.name)).noSpaces
      assertEquals(SnapshotMeta.parse(text), Left(MetaError.MissingField(field)))
    }
  }

  test("mistyped fields retain their field and original JSON value") {
    val cases = Vector(
      MetaField.Backend -> Vector(Json.Null, Json.True, Json.fromInt(1), Json.arr()),
      MetaField.Checksum -> Vector(
        Json.Null,
        Json.True,
        Json.fromString("1"),
        Json.fromBigDecimal(BigDecimal("1.5")),
        Json.arr()
      ),
      MetaField.TablesCodecVersion -> Vector(
        Json.Null,
        Json.False,
        Json.fromString("1"),
        Json.fromBigDecimal(BigDecimal("1.5")),
        Json.obj()
      )
    )
    cases.foreach { (field, values) =>
      values.foreach { json =>
        assertEquals(
          SnapshotMeta.parse(changed(field, json)),
          Left(MetaError.FieldTypeMismatch(field, json))
        )
      }
    }
  }

  test("range, backend and version failures remain distinct from field type failures") {
    for crc <- Vector(BigInt(-1), BigInt(1) << 32) do
      assertEquals(
        SnapshotMeta.parse(changed(MetaField.Checksum, Json.fromBigInt(crc))),
        Left(MetaError.BadChecksum(crc))
      )
    assertEquals(
      SnapshotMeta.parse(changed(MetaField.Backend, Json.fromString("rocks"))),
      Left(MetaError.UnknownBackend("rocks"))
    )
    for version <- Vector(BigInt(-1), BigInt(0), BigInt(2), BigInt(1) << 80) do
      assertEquals(
        SnapshotMeta.parse(changed(MetaField.TablesCodecVersion, Json.fromBigInt(version))),
        Left(MetaError.UnknownTablesCodecVersion(version))
      )
  }

  test("checksum boundaries and every named backend still parse and re-encode") {
    for backend <- SnapshotBackend.values; checksum <- Vector(0L, 0xffffffffL) do
      val meta = SnapshotMeta(backend, checksum, 1)
      val result = SnapshotMeta.encode(meta).flatMap(SnapshotMeta.parse)
      assertEquals(result, Right(meta))
  }

  test("failure precedence still follows backend, checksum, then tables version") {
    assertEquals(SnapshotMeta.parse("{}"), Left(MetaError.MissingField(MetaField.Backend)))
    assertEquals(
      SnapshotMeta.parse("""{"backend":"rocks"}"""),
      Left(MetaError.UnknownBackend("rocks"))
    )
    assertEquals(
      SnapshotMeta.parse("""{"backend":"utxohd-mem","checksum":-1}"""),
      Left(MetaError.BadChecksum(-1))
    )
  }

  test("field order, unknown fields and exact integral decimals keep their existing acceptance") {
    val input =
      """{"tablesCodecVersion":1.0,"extra":true,"checksum":4294967295.0,"backend":"utxohd-mem"}"""
    assertEquals(
      SnapshotMeta.parse(input),
      Right(SnapshotMeta(SnapshotBackend.InMemory, 0xffffffffL, 1))
    )
  }
