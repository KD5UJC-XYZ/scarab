// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import xyz.kd5ujc.scarab.codec.Bytes
import java.nio.ByteBuffer
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.crypto.kes.{LegacySum6KesSigning, Sum6KesSigning}
import xyz.kd5ujc.scarab.crypto.secret.KeyText
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover
import xyz.kd5ujc.scarab.ledger.{
  ForgeError,
  ForgeKeyProblem,
  Header,
  KesFileFailureCause,
  KesFileOperation
}

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
        // duplicates that conflict: another type, another key's bytes
        1 -> Gen
          .oneOf(KeyFiles.OpCertType, KeyFiles.VrfSigningKeyType, KeyFiles.KesSigningKeyType)
          .map(other => List("type" -> quoted(other, escape = false))),
        1 -> Gen.const(
          List("cborHex" -> quoted(head(payload.size) + payload.hex.reverse, escape = false))
        ),
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

  /** A failure as it can be compared across two reads: the typed error, its circe and I/O causes by
    * their own fields (an exception is a new object each time), and as it is rendered.
    */
  private def typed(e: ForgeError): (Any, String) =
    val cause: Any = e match
      case ForgeError.KeyFile(f, ForgeKeyProblem.Json(pf)) => ("json", f, pf.message)
      case ForgeError.KeyFile(f, ForgeKeyProblem.Field(n, df)) =>
        ("field", f, n, df.message, df.history)
      case ForgeError.KeyFile(f, ForgeKeyProblem.Io(t)) => ("io", f, t.getClass, t.getMessage)
      case ForgeError.KeyFile(f, ForgeKeyProblem.Vrf(Draft03Prover.Failure.InternalFailure(t))) =>
        ("vrf", f, t.getClass)
      case other => other
    (cause, e.toString)

  /** What a read gave, as it can be compared: the key's period, verification key and bytes, or the
    * failure, typed and rendered.
    */
  private def kesSeen(
      r: Either[ForgeError, Sum6KesSigning.SigningKey]
  ): Either[(Any, String), (Int, Bytes, Bytes)] =
    r.left.map(typed).map(k => (k.period, k.verificationKey, k.toBytes))
  private def vrfSeen(
      r: Either[ForgeError, Draft03Prover.SigningKey]
  ): Either[(Any, String), (Bytes, Bytes)] =
    r.left.map(typed).map(k => (k.publicKey, k.toBytes))

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
            KeyFiles.KesSigningKeyType
          )(Sum6KesSigning.fromEvolvedPayload) match
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
    // the expected bytes are the previous writer's, of the payload of the key implementation it
    // wrote for, evolved alongside the node's key from the same seed
    val key = Sum6KesSigning.generate(hex("5e" * 32)).toOption.get
    val legacy = LegacySum6KesSigning.generate(hex("5e" * 32)).toOption.get
    (0 until Sum6KesSigning.Periods).foreach { p =>
      key.evolveTo(p)
      legacy.evolveTo(p)
      val written = KeyFiles.kesEnvelope(key) { buffer =>
        val out = new Array[Byte](buffer.remaining())
        buffer.duplicate().get(out)
        out
      }
      val expected = LegacyKeyFiles
        .kesEnvelope("kes.skey", legacy.toBytes)
        .toOption
        .get
        .getBytes(StandardCharsets.UTF_8)
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

  private final class Injected extends RuntimeException("a failure injected for the suite")

  /** Whether this process holds a descriptor open on `path`. */
  private def openOn(path: Path): Boolean =
    val fds = Files.list(Path.of("/proc/self/fd"))
    try
      fds.iterator.asScala.exists(fd =>
        scala.util.Try(Files.readSymbolicLink(fd)).toOption.contains(path.toAbsolutePath)
      )
    finally fds.close()

  private def withKeyDir[A](body: (Path, Sum6KesSigning.SigningKey) => A): A =
    val dir = Files.createTempDirectory("kes-stage")
    try
      val file = dir.resolve("kes.skey")
      val key = Sum6KesSigning.generate(hex("7a" * 32)).toOption.get
      Files.writeString(file, LegacyKeyFiles.kesEnvelope(file.toString, key).toOption.get)
      key.evolveTo(5)
      body(file, key)
    finally
      Files.list(dir).forEach(Files.delete(_))
      Files.delete(dir)

  test(
    "a stage whose envelope cannot be made fails typed, makes no stage and holds no descriptor"
  ) {
    withKeyDir { (file, key) =>
      val before = Files.readAllBytes(file).toVector
      val failing = new KesFiles.FileOps:
        override def envelope[A](key: Sum6KesSigning.SigningKey)(use: ByteBuffer => A): A =
          throw new Injected
      val next = file.resolveSibling("kes.skey.next")
      KesFiles.persistWith(file, key, failing) match
        case Persisted.Failed(
              ForgeError.KesFileFailure(
                _,
                KesFileOperation.StageWrite,
                KesFileFailureCause.FileSystem(_: Injected)
              )
            ) =>
          ()
        case other => fail(s"expected the stage write's typed failure, found $other")
      assert(!Files.exists(next), "a stage was made for an envelope that was never made")
      assert(!openOn(next), "a descriptor on the stage is left open")
      assertEquals(Files.readAllBytes(file).toVector, before, "the key file changed")
    }
  }

  test("a stage whose envelope fails after it is written fails typed, with its channel closed") {
    withKeyDir { (file, key) =>
      val failing = new KesFiles.FileOps:
        override def envelope[A](key: Sum6KesSigning.SigningKey)(use: ByteBuffer => A): A =
          KeyFiles.kesEnvelope(key)(use)
          throw new Injected
      val next = file.resolveSibling("kes.skey.next")
      KesFiles.persistWith(file, key, failing) match
        case Persisted.Failed(
              ForgeError.KesFileFailure(
                _,
                KesFileOperation.StageWrite,
                KesFileFailureCause.FileSystem(_: Injected)
              )
            ) =>
          ()
        case other => fail(s"expected the stage write's typed failure, found $other")
      assert(!openOn(next), "a descriptor on the stage is left open")
    }
  }

  test("a KES key read for credentials that are not taken is erased") {
    val dir = Files.createTempDirectory("kes-erased")
    try
      val kesFile = dir.resolve("kes.skey")
      val certificate = dir.resolve("node.cert")
      Files.writeString(certificate, "not a certificate")
      // the certificate cannot be read: the KES key read before it is erased
      val read = Sum6KesSigning.generate(hex("7b" * 32)).toOption.get
      val unread = KesFiles.readHotWith(kesFile, certificate, _ => Right(read))
      assert(unread.isLeft, s"$unread")
      assert(read.isForgotten, "the KES key read before an unreadable certificate was kept")
      // the credentials do not belong together: the KES key is erased with the VRF key
      val vrf = dir.resolve("vrf.skey")
      val vrfKey = Draft03Prover.SigningKey.fromSeed(hex("7c" * 32)).toOption.get
      Files.writeString(
        vrf,
        s"""{"type": "${KeyFiles.VrfSigningKeyType}", "description": "", "cborHex": "${head(
            64
          )}${vrfKey.toBytes.hex}"}"""
      )
      val held = Sum6KesSigning.generate(hex("7d" * 32)).toOption.get
      val other = Sum6KesSigning.generate(hex("7e" * 32)).toOption.get
      val mismatched = KeyFiles.loadWith(
        vrf,
        kesFile,
        certificate,
        None,
        (_, _) =>
          Right(
            HotCredentials(
              held,
              Header.OpCert(other.verificationKey, 0, 0, Bytes.empty),
              hex("03" * 32)
            )
          )
      )
      assert(
        mismatched.left.exists(_.isInstanceOf[ForgeError.KesKeyNotCertified]),
        s"$mismatched"
      )
      assert(held.isForgotten, "the KES key of credentials that were refused was kept")
      // credentials that are taken keep their KES key
      assert(!other.isForgotten)
    finally
      Files.list(dir).forEach(Files.delete(_))
      Files.delete(dir)
  }
