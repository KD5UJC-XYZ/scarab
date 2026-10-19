// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}

class SclsCommandSuite extends FunSuite:
  private val base = List(
    "--db",
    "/data/source",
    "--scratch",
    "/scratch",
    "--genesis-dir",
    "/genesis",
    "--slot",
    "42",
    "--block-no",
    "17",
    "--block-hash",
    "01" * 32
  )

  test("export parser builds a Conway point and accepts an optional comment") {
    val parsed =
      SclsCommand.parseExport(base ++ List("--out", "/tmp/state.scls", "--comment", "offline"))
    assert(parsed.isRight, parsed.toString)
    val options = parsed.fold(error => fail(error.message), identity)
    assertEquals(options.base.anchor.slot, BigInt(42))
    assertEquals(options.base.anchor.blockNo, BigInt(17))
    assertEquals(options.base.anchor.hash.size, 32)
    assertEquals(options.base.anchor.boundary, false)
    assertEquals(options.comment, Some("offline"))
    assertEquals(options.recordBytes, 16 * 1024 * 1024)
  }

  test("record byte overrides preserve the independent CBOR item ceiling") {
    val itemCount = Cbor.Limits().maxItems + 1
    val encoded = Array.ofDim[Byte](5 + itemCount)
    encoded(0) = 0x9a.toByte
    encoded(1) = ((itemCount >>> 24) & 0xff).toByte
    encoded(2) = ((itemCount >>> 16) & 0xff).toByte
    encoded(3) = ((itemCount >>> 8) & 0xff).toByte
    encoded(4) = (itemCount & 0xff).toByte
    java.util.Arrays.fill(encoded, 5, encoded.length, 0.toByte)
    val bytes = Bytes.wrap(encoded)

    assertEquals(
      Cbor.decode(bytes, SclsCommand.valueLimits(16 * 1024 * 1024)),
      Left(Cbor.Error.ItemLimitExceeded)
    )
    assertEquals(
      Cbor.decode(bytes, SclsCommand.valueLimits(256 * 1024 * 1024)),
      Left(Cbor.Error.ItemLimitExceeded)
    )
  }

  test("import parser requires explicit input and target while preserving exact base point") {
    val parsed = SclsCommand.parseImport(
      base ++ List("--input", "/tmp/state.scls", "--target-db", "/data/target")
    )
    assert(parsed.isRight, parsed.toString)
    val options = parsed.fold(error => fail(error.message), identity)
    assertEquals(options.base.anchor.slot, BigInt(42))
    assertEquals(options.base.anchor.blockNo, BigInt(17))
    assertEquals(options.base.anchor.boundary, false)
    assertEquals(options.target.toString, "/data/target")
    assertEquals(options.recordBytes, 16 * 1024 * 1024)
  }

  test("SCLS parsers reject duplicate, unknown, missing, and malformed point flags") {
    assert(SclsCommand.parseExport(base ++ List("--out", "a", "--out", "b")).isLeft)
    assert(SclsCommand.parseExport(base ++ List("--out", "a", "--surprise", "x")).isLeft)
    assert(
      SclsCommand
        .parseImport(base.dropRight(2) ++ List("--input", "in", "--target-db", "db"))
        .isLeft
    )
    assert(
      SclsCommand.parseExport(base.updated(base.indexOf("42"), "-1") ++ List("--out", "a")).isLeft
    )
    assert(
      SclsCommand
        .parseExport(base.updated(base.indexOf("01" * 32), "abcd") ++ List("--out", "a"))
        .isLeft
    )
    assert(
      SclsCommand
        .parseImport(
          base.filterNot(_ == "--genesis-dir") ++ List("--input", "in", "--target-db", "db")
        )
        .isLeft
    )
    assert(SclsCommand.parseExport(base ++ List("--out", "a", "--record-bytes", "0")).isLeft)
    assert(
      SclsCommand
        .parseImport(
          base ++ List("--input", "in", "--target-db", "db", "--record-bytes", "4096")
        )
        .exists(_.recordBytes == 4096)
    )
  }
