// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.crypto.secret.KeyText
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover
import xyz.kd5ujc.scarab.ledger.ForgeError

/** The key files read and written as bytes against the readers and the writer they replaced. A VRF
  * or KES key file read by its bytes gives what the text readers give on the file's text, read with
  * `Files.readString`: the same key, or the same failure, rendered alike, on generated envelopes
  * (layouts, member orders and extra members, escapes, duplicates, case and parity of the hex, CBOR
  * heads and sizes, types, trailing text) and on bytes that are not UTF-8. A KES key file the node
  * writes, staged or in place, holds the bytes the previous writer wrote ([[LegacyKeyFiles]]), at
  * every period, so the format of `kes.skey` is unchanged.
  */
class KeyFilesBytesSuite extends munit.ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(400)

  private def hex(s: String) = Bytes.fromHex(s).toOption.get

  private val kesKeys: Vector[Sum6KesSigning.SigningKey] =
    Vector(0, 1, 17, 32, 63).map { p =>
      val k = Sum6KesSigning.generate(Bytes.fromArray(Array.fill(32)((p + 1).toByte))).toOption.get
      k.evolveTo(p)
      k
    }
  private val vrfKeys: Vector[Draft03Prover.SigningKey] =
    Vector("01", "7f", "c3").map(b => Draft03Prover.SigningKey.fromSeed(hex(b * 32)).toOption.get)

  private def head(n: Int): String =
    if n < 24 then f"${0x40 + n}%02x" else if n < 256 then f"58$n%02x" else f"59$n%04x"

  /** A string in JSON, with some of its characters, or none, escaped. */
  private def quoted(s: String, escape: Boolean): String =
    if !escape || s.isEmpty then "\"" + s + "\""
    else "\"" + f"\\u${s.head.toInt}%04x" + s.tail + "\""

  private val whitespace: Gen[String] = Gen.oneOf("", " ", "\n    ", "\t", "\r\n", "  \n  ")

  /** Envelopes: half as the tools write them (any layout and order, the type right or not), half
    * with any of the variations below.
    */
  private def envelope(typ: String, payload: Bytes): Gen[String] =
    Gen.frequency(1 -> plain(typ, payload), 1 -> varied(typ, payload))

  private def plain(typ: String, payload: Bytes): Gen[String] =
    for
      ws <- whitespace
      found <- Gen.frequency(5 -> Gen.const(typ), 1 -> Gen.const(KeyFiles.OpCertType))
      upper <- Gen.oneOf(true, false)
      reversed <- Gen.oneOf(true, false)
    yield
      val cbor = head(payload.size) + payload.hex
      val members = List(
        "type" -> quoted(found, escape = false),
        "description" -> "\"\"",
        "cborHex" -> quoted(if upper then cbor.toUpperCase else cbor, escape = false)
      )
      val body = (if reversed then members.reverse else members)
        .map((name, value) => s"$ws\"$name\"$ws:$ws$value")
        .mkString(",")
      "{" + body + ws + "}"

  private def varied(typ: String, payload: Bytes): Gen[String] =
    for
      ws <- whitespace
      typeValue <- Gen.frequency(
        8 -> Gen.const(quoted(typ, escape = false)),
        1 -> Gen.const(quoted(typ, escape = true)),
        1 -> Gen.oneOf(
          quoted("NodeOperationalCertificate", escape = false),
          quoted(KeyFiles.VrfSigningKeyType, escape = false),
          quoted(KeyFiles.KesSigningKeyType, escape = false),
          "5",
          "null",
          "[]"
        )
      )
      cbor <- Gen.frequency(
        10 -> Gen.const(head(payload.size) + payload.hex),
        2 -> Gen.const((head(payload.size) + payload.hex).toUpperCase),
        1 -> Gen.const(head(payload.size) + payload.hex + "0"),
        1 -> Gen.const(head(payload.size) + payload.hex.updated(9, 'x')),
        1 -> Gen.const(f"5a${payload.size}%08x" + payload.hex),
        1 -> Gen.const(head(payload.size - 1) + payload.hex.dropRight(2)),
        1 -> Gen.const(head(payload.size + 1) + payload.hex),
        1 -> Gen.const(head(payload.size) + payload.hex + "00"),
        1 -> Gen.const("81" + head(payload.size) + payload.hex),
        1 -> Gen.const("5f" + head(payload.size) + payload.hex + "ff"),
        1 -> Gen.const(head(payload.size) + payload.hex.take(64) + "00" * (payload.size - 32)),
        1 -> Gen.const("")
      )
      cborValue <- Gen.frequency(
        9 -> Gen.const(quoted(cbor, escape = false)),
        1 -> Gen.const(quoted(cbor, escape = true)),
        1 -> Gen.oneOf("7", "{}")
      )
      description <- Gen.oneOf(
        "\"\"",
        "\"KES Signing Key\"",
        "\"caf\u00e9 \\u00e9\"",
        "1.5e3",
        "{\"a\": [1, \"b\", null]}"
      )
      members <- Gen.pick(
        3,
        List("type" -> typeValue, "description" -> description, "cborHex" -> cborValue)
      )
      order <- Gen.oneOf(true, false)
      extra <- Gen.frequency(
        6 -> Gen.const(Nil),
        1 -> Gen.const(List("type" -> quoted(typ, escape = false))),
        1 -> Gen.const(List("cborHex" -> cborValue)),
        1 -> Gen.const(List("x" -> "\"y\"")),
        1 -> Gen.const(List("x" -> "true"))
      )
      drop <- Gen.frequency(8 -> Gen.const(None), 1 -> Gen.oneOf("type", "cborHex").map(Some(_)))
      around <- Gen.frequency(
        10 -> Gen.const(("", "")),
        1 -> Gen.oneOf(("\ufeff", ""), ("", "x"), ("[", "]"), ("", ","), ("", "\n\n"))
      )
    yield
      val chosen = (if order then members.toList else members.toList.reverse) ++ extra
      val kept = chosen.filterNot((name, _) => drop.contains(name))
      val body = kept.map((name, value) => s"$ws\"$name\"$ws:$ws$value").mkString(",")
      around._1 + "{" + body + ws + "}" + around._2

  /** What a read gave, as it can be compared: the key's period, verification key and bytes, or the
    * failure as it is rendered.
    */
  private def kesSeen(
      r: Either[ForgeError, Sum6KesSigning.SigningKey]
  ): Either[String, (Int, Bytes, Bytes)] =
    r.left.map(_.toString).map(k => (k.period, k.verificationKey, k.toBytes))
  private def vrfSeen(
      r: Either[ForgeError, Draft03Prover.SigningKey]
  ): Either[String, (Bytes, Bytes)] =
    r.left.map(_.toString).map(k => (k.publicKey, k.toBytes))

  private def temp[A](body: Path => A): A =
    val file = Files.createTempFile("key-bytes", ".skey")
    try body(file)
    finally Files.deleteIfExists(file)

  property("a KES key file read by its bytes gives what the text reader gives on its text") {
    temp { file =>
      forAllNoShrink(
        Gen.oneOf(kesKeys).flatMap(k => envelope(KeyFiles.KesSigningKeyType, k.toBytes))
      ) { text =>
        Files.writeString(file, text)
        assertEquals(
          kesSeen(KeyFiles.kesSigningKey(file)),
          kesSeen(KeyFiles.read(file).flatMap(KeyFiles.kesSigningKey(file.toString, _))),
          text
        )
      }
    }
  }

  property("a VRF key file read by its bytes gives what the text reader gives on its text") {
    temp { file =>
      forAllNoShrink(
        Gen.oneOf(vrfKeys).flatMap(k => envelope(KeyFiles.VrfSigningKeyType, k.toBytes))
      ) { text =>
        Files.writeString(file, text)
        assertEquals(
          vrfSeen(KeyFiles.vrfSigningKey(file)),
          vrfSeen(KeyFiles.read(file).flatMap(KeyFiles.vrfSigningKey(file.toString, _))),
          text
        )
      }
    }
  }

  test("the generated envelopes are read in place, by their type, and as text, each often") {
    val outcomes = (1 to 400).flatMap { i =>
      Gen
        .oneOf(kesKeys)
        .flatMap(k => envelope(KeyFiles.KesSigningKeyType, k.toBytes))
        .apply(Gen.Parameters.default, org.scalacheck.rng.Seed(i.toLong))
        .map { text =>
          KeyText.read(
            s => s.adopt(text.getBytes(StandardCharsets.UTF_8)),
            KeyFiles.KesSigningKeyType,
            Sum6KesSigning.KeyBytes
          )(p => Sum6KesSigning.fromEvolved(Bytes.fromArray(p))) match
            case KeyText.Read.Payload(_)    => "in place"
            case KeyText.Read.WrongType(_)  => "by type"
            case KeyText.Read.Other(_)      => "as text"
            case KeyText.Read.Unreadable(_) => "unreadable"
        }
    }
    val counts = outcomes.groupBy(identity).view.mapValues(_.size).toMap
    assert(counts.getOrElse("in place", 0) >= 60, s"$counts")
    assert(counts.getOrElse("by type", 0) >= 10, s"$counts")
    assert(counts.getOrElse("as text", 0) >= 100, s"$counts")
  }

  test("a key file that is not UTF-8, or not there, fails as Files.readString fails") {
    val good = s"""{"type": "${KeyFiles.KesSigningKeyType}", "description": "", "cborHex": "${head(
        608
      ) + kesKeys(0).toBytes.hex}"}""".getBytes(StandardCharsets.UTF_8)
    val bad = Vector(
      Array(0x80),
      Array(0xc3),
      Array(0xe2, 0x82),
      Array(0xed, 0xa0, 0x80),
      Array(0xf4, 0x90, 0x80, 0x80)
    )
      .map(_.map(_.toByte))
    temp { file =>
      bad.foreach { b =>
        List(10, 40, good.length - 3, good.length).foreach { at =>
          val bytes = good.take(at) ++ b ++ good.drop(at)
          Files.write(file, bytes)
          assertEquals(
            kesSeen(KeyFiles.kesSigningKey(file)),
            kesSeen(KeyFiles.read(file).flatMap(KeyFiles.kesSigningKey(file.toString, _))),
            Bytes.fromArray(bytes).hex
          )
        }
      }
      Files.delete(file)
      val missing = KeyFiles.kesSigningKey(file)
      assertEquals(
        kesSeen(missing),
        kesSeen(KeyFiles.read(file).flatMap(KeyFiles.kesSigningKey(file.toString, _)))
      )
      assert(missing match
        case Left(
              ForgeError.KeyFile(
                _,
                xyz.kd5ujc.scarab.ledger.ForgeKeyProblem.Io(_: java.nio.file.NoSuchFileException)
              )
            ) =>
          true
        case _ => false)
    }
  }

  test(
    "the KES key file the node writes is the previous writer's, byte for byte, at every period"
  ) {
    val key = Sum6KesSigning.generate(hex("5e" * 32)).toOption.get
    (0 until Sum6KesSigning.Periods).foreach { p =>
      key.evolveTo(p)
      val written = KeyFiles.kesEnvelope(key) { buffer =>
        val out = new Array[Byte](buffer.remaining())
        buffer.duplicate().get(out)
        out
      }
      val expected =
        LegacyKeyFiles.kesEnvelope("kes.skey", key).toOption.get.getBytes(StandardCharsets.UTF_8)
      assertEquals(Bytes.fromArray(written), Bytes.fromArray(expected), s"period $p")
    }
    key.forget()
    val forgotten =
      KeyFiles.kesEnvelope(key)(b => Bytes.fromArray(Array.tabulate(b.remaining())(b.get(_))))
    assertEquals(
      forgotten,
      Bytes.fromArray(
        LegacyKeyFiles.kesEnvelope("kes.skey", key).toOption.get.getBytes(StandardCharsets.UTF_8)
      )
    )
  }

  test("a staged and a persisted KES key file are the previous writer's bytes") {
    val dir = Files.createTempDirectory("kes-bytes")
    try
      val file = dir.resolve("kes.skey")
      val key = Sum6KesSigning.generate(hex("6f" * 32)).toOption.get
      Files.writeString(file, LegacyKeyFiles.kesEnvelope(file.toString, key).toOption.get)
      key.evolveTo(5)
      assertEquals(KesFiles.stage(file, key), Right(()))
      val staged = dir.resolve("kes.skey.next")
      assertEquals(
        Bytes.fromArray(Files.readAllBytes(staged)),
        Bytes.fromArray(
          LegacyKeyFiles
            .kesEnvelope(file.toString, key)
            .toOption
            .get
            .getBytes(StandardCharsets.UTF_8)
        )
      )
      Files.delete(staged)
      key.evolveTo(9)
      assert(KesFiles.persist(file, key).isInstanceOf[Persisted.Written], "persisted")
      assertEquals(
        Bytes.fromArray(Files.readAllBytes(file)),
        Bytes.fromArray(
          LegacyKeyFiles
            .kesEnvelope(file.toString, key)
            .toOption
            .get
            .getBytes(StandardCharsets.UTF_8)
        )
      )
      assertEquals(KeyFiles.kesSigningKey(file).map(_.period), Right(9))
    finally
      Files.list(dir).forEach(Files.delete(_))
      Files.delete(dir)
  }

  test("the byte reader and the text reader read the stage of a write alike") {
    val key = Sum6KesSigning.generate(hex("71" * 32)).toOption.get
    key.evolveTo(12)
    val text = LegacyKeyFiles.kesEnvelope("k", key).toOption.get
    val source: KeyText.Source = s => s.adopt(text.getBytes(StandardCharsets.UTF_8))
    assertEquals(
      KeyFiles.kesSigningKeyRead("k", source).map(kesSeen),
      Right(kesSeen(KeyFiles.kesSigningKey("k", text)))
    )
  }
