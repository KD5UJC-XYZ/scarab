// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import java.nio.file.{Files, Path}
import munit.FunSuite

class AncillaryManifestSuite extends FunSuite:
  private val root = Path.of("fixtures/mempack/ancillary")
  private val text = Files.readString(root.resolve("preprod-manifest.json"))
  private val key = AncillaryManifest
    .verificationKey(Files.readString(root.resolve("preprod.vkey")))
    .fold(e => fail(e.toString), identity)

  test("the pinned preprod ancillary signature authenticates ordered path and digest pairs") {
    val manifest = AncillaryManifest.parse(text).fold(e => fail(e.toString), identity)
    assertEquals(manifest.files.size, 6)
    assertEquals(manifest.verify(key), Right(()))
    val json = io.circe.parser.parse(text).fold(e => fail(e.message), identity)
    val reordered = json.mapObject(o => io.circe.JsonObject.fromIterable(o.toVector.reverse))
    assertEquals(AncillaryManifest.parse(reordered.noSpaces).map(_.hash), Right(manifest.hash))
  }

  test("a changed digest, signature or key is refused") {
    val changed = text.replace("4d5ee78c", "5d5ee78c")
    assertEquals(
      AncillaryManifest.parse(changed).flatMap(_.verify(key)),
      Left(AncillaryManifest.Error.SignatureRejected)
    )
    val signature = text.replace("2aa3b9a8", "3aa3b9a8")
    assertEquals(
      AncillaryManifest.parse(signature).flatMap(_.verify(key)),
      Left(AncillaryManifest.Error.SignatureRejected)
    )
    assert(AncillaryManifest.verificationKey("00").isLeft)
    val bytes = key.toArray
    bytes(0) = (bytes(0) ^ 1).toByte
    val wrong = xyz.kd5ujc.scarab.crypto.witness.PublicKey32
      .fromArray(bytes)
      .fold(e => fail(e.toString), identity)
    assertEquals(
      AncillaryManifest.parse(text).flatMap(_.verify(wrong)),
      Left(AncillaryManifest.Error.SignatureRejected)
    )
  }

  test("untrusted local manifest paths and malformed digests are refused") {
    Vector("../outside", "/absolute", "a/../b", "a//b", "a\\b", "a/./b", "C:/absolute").foreach {
      path =>
        assert(!AncillaryManifest.safePath(path), path)
    }
    assert(AncillaryManifest.parse(text.replace("immutable/06321.chunk", "../outside")).isLeft)
    assert(AncillaryManifest.parse(text.replace("4d5ee78c", "4D5EE78C")).isLeft)
    assert(AncillaryManifest.parse("{\"data\":{}}").isLeft)
    val duplicate =
      text.replace("{\"data\":{", "{\"data\":{\"ledger/136511997/meta\":\"" + "00" * 32 + "\",")
    assert(AncillaryManifest.parse(duplicate).isLeft)
  }

  test("twenty thousand manifest paths parse, sort and hash on a 256 KiB worker") {
    xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack {
      val files =
        Vector.tabulate(20000)(i => s"ledger/$i/state" -> io.circe.Json.fromString("00" * 32))
      val text = io.circe.Json
        .obj(
          "data" -> io.circe.Json.obj(files.reverse*),
          "signature" -> io.circe.Json.fromString("00" * 64)
        )
        .noSpaces
      val manifest = AncillaryManifest.parse(text).fold(e => fail(e.toString), identity)
      assertEquals(manifest.files.size, 20000)
      assertEquals(
        AncillaryManifest.parse(AncillaryManifest.encode(manifest)).map(_.hash),
        Right(manifest.hash)
      )
    }
  }
