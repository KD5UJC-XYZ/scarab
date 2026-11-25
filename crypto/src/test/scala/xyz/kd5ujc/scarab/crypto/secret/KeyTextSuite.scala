// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.secret

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.charset.{MalformedInputException, StandardCharsets}
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning

/** The byte-level key envelope: its bytes are UTF-8 exactly when `Files.readString` takes them, and
  * it fails as that does on the first malformed sequence; a flat envelope is read in place and any
  * other handed back as its text; the buffers of a read are zero once it ends; the native memory of
  * a read or a write is zero before it is freed. That the byte reader and the text reader agree on
  * every envelope is the node's `KeyFilesBytesSuite`.
  */
class KeyTextSuite extends munit.FunSuite:
  private val Type = "KesSigningKey_ed25519_kes_2^6"

  private def ascii(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  private val key = Sum6KesSigning.generate(Bytes.fromArray(Array.fill(32)(9.toByte))).toOption.get
  private val payload = key.toBytes
  private val hex = "590260" + payload.hex

  private def envelope(cborHex: String, typ: String = Type): String =
    s"""{\n    "type": "$typ",\n    "description": "",\n    "cborHex": "$cborHex"\n}\n"""

  private def source(bytes: Array[Byte]): KeyText.Source = s => s.adopt(bytes.clone())

  private def readKes(bytes: Array[Byte]) =
    KeyText.read(source(bytes), Type)(p => Sum6KesSigning.fromBytes(Bytes.fromArray(p)))

  /** The JDK's verdict on `bytes` as a file's text: its decoding, or the length its
    * `MalformedInputException` reports.
    */
  private def jdk(file: Path, bytes: Array[Byte]): Either[Int, String] =
    Files.write(file, bytes)
    scala.util.Try(Files.readString(file)).toEither.left.map {
      case e: MalformedInputException => e.getInputLength
      case other                      => throw other
    }

  private def ours(bytes: Array[Byte]): Either[Int, String] =
    KeyText.read(source(bytes), "none")(_ => Right[Boolean, Boolean](true)) match
      case KeyText.Read.Unreadable(e: MalformedInputException) => Left(e.getInputLength)
      case KeyText.Read.Other(text)                            => Right(text)
      case other                                               => fail(s"not read as text: $other")

  test("bytes are UTF-8 exactly when Files.readString takes them, and fail as it fails") {
    val file = Files.createTempFile("key-text", ".skey")
    try
      val follow =
        Vector(0x00, 0x41, 0x7f, 0x80, 0x8f, 0x90, 0x9f, 0xa0, 0xbf, 0xc0, 0xf4, 0xff).map(_.toByte)
      val leads = (0x80 to 0xff).map(_.toByte)
      val sequences =
        leads.map(Array(_)) ++
          leads.flatMap(a => (0 to 0xff).map(b => Array(a, b.toByte))) ++
          (0xe0 to 0xff)
            .map(_.toByte)
            .flatMap(a => follow.flatMap(b => follow.map(c => Array(a, b, c)))) ++
          (0xf0 to 0xf7)
            .map(_.toByte)
            .flatMap(a =>
              follow.flatMap(b => follow.flatMap(c => follow.map(d => Array(a, b, c, d))))
            )
      val checked = sequences.flatMap { seq =>
        // the sequence at the end, where a cut short one is, and before more text
        List(ascii("{\"a\": \"") ++ seq, ascii("{\"a\": \"") ++ seq ++ ascii("\"}"))
      }
      checked.foreach { bytes =>
        assertEquals(
          ours(bytes),
          jdk(file, bytes),
          s"bytes ${Bytes.fromArray(bytes).hex}"
        )
      }
      assert(checked.sizeIs > 70000, s"${checked.size} cases")
    finally Files.deleteIfExists(file)
  }

  test("a flat envelope is read in place; others are handed back as text, or by their type") {
    assert(readKes(ascii(envelope(hex))) match
      case KeyText.Read.Payload(Right(k)) => k.verificationKey == key.verificationKey
      case _                              => false)
    // upper-case hex, other layouts and other members are flat too, and a head of the byte string
    // wider than it needs, or a byte string in chunks, is read in place as the CBOR decoder reads it
    List(
      envelope(hex.toUpperCase),
      envelope("5a00000260" + payload.hex),
      envelope("5b0000000000000260" + payload.hex),
      envelope(
        "5f590130" + payload.hex.take(608) + "58" + "40" + payload.hex
          .slice(608, 736) + "5900f0" + payload.hex.drop(736) + "40ff"
      ),
      s"""{"cborHex":"$hex","type":"$Type"}""",
      s"""\t{ "x" : "é" , "type" : "$Type" ,\r\n "description":"", "cborHex" : "$hex" }  \n"""
    ).foreach(text => assert(readKes(ascii(text)).isInstanceOf[KeyText.Read.Payload[?, ?]], text))
    assertEquals(
      readKes(ascii(envelope(hex, "NodeOperationalCertificate"))),
      KeyText.Read.WrongType("NodeOperationalCertificate")
    )
    List(
      envelope(hex.drop(1)),
      envelope(hex + "00"),
      envelope(hex.updated(10, 'g')),
      envelope("5f" + hex + "5f40ffff"),
      envelope("5f" + hex),
      envelope("5c" + payload.hex),
      envelope("5a00000261" + payload.hex),
      envelope("81" + hex),
      envelope(hex).replace("\"type\"", "\"t\\u0079pe\""),
      envelope(hex).replace("\"description\": \"\"", "\"description\": 1"),
      envelope(hex).replace("{", "{\"type\": \"x\", "),
      envelope(hex) + "x",
      s"""[${envelope(hex)}]""",
      "",
      "{}"
    ).foreach { text =>
      assertEquals(readKes(ascii(text)), KeyText.Read.Other(text), text)
    }
    // a byte string of another size reaches the key's constructor, which refuses it, as before
    assertEquals(
      readKes(ascii(envelope("590261" + payload.hex + "00"))),
      KeyText.Read.Payload(Left(Sum6KesSigning.Failure.InvalidKeyLength(609)))
    )
  }

  /** The bytes `writer` writes into a new named pipe, read by `read` while it writes them. */
  private def throughPipe[A](bytes: Array[Byte])(read: Path => A): A =
    val dir = Files.createTempDirectory("key-pipe")
    val pipe = dir.resolve("kes.skey")
    try
      val made = new ProcessBuilder("mkfifo", pipe.toString).start().waitFor()
      assertEquals(made, 0, "mkfifo")
      // the writer blocks in its open until the reader opens the pipe
      val writer = new Thread(() => Files.write(pipe, bytes): Unit)
      writer.start()
      val result = read(pipe)
      writer.join(30000)
      assert(!writer.isAlive, "the writer did not finish")
      result
    finally
      Files.deleteIfExists(pipe)
      Files.delete(dir)

  test(
    "a key file that is a pipe, whose size is 0, is read forward to its end as Files.readString reads it"
  ) {
    // longer than the first native buffer many times over, so that the read grows it
    val text = ascii(envelope(hex) + " " * 5000)
    val read = throughPipe(text)(pipe => readKes2(KeyText.file(pipe)))
    val loaded = read match
      case KeyText.Read.Payload(Right(k)) => k.verificationKey == key.verificationKey
      case _                              => false
    assert(loaded, s"$read")
    assertEquals(
      throughPipe(text)(Files.readString(_)),
      new String(text, java.nio.charset.StandardCharsets.UTF_8)
    )
  }

  private def readKes2(source: KeyText.Source) =
    KeyText.read(source, Type)(p => Sum6KesSigning.fromBytes(Bytes.fromArray(p)))

  test("a read's every buffer is zero once it ends, and those buffers held the text and the key") {
    val text = ascii(envelope(hex))
    var before: List[Array[Byte]] = Nil
    var after: List[Array[Byte]] = Nil
    val read = Scratch.observed[KeyText.Read[Sum6KesSigning.Failure, Sum6KesSigning.SigningKey]] {
      arrays =>
        before = arrays.map(_.clone())
        after = arrays
    }(s => KeyText.readIn(s, source(text), Type)(p => Sum6KesSigning.fromBytes(Bytes.fromArray(p))))
    assert(read.isInstanceOf[KeyText.Read.Payload[?, ?]], s"$read")
    assert(before.exists(java.util.Arrays.equals(_, text)), "the text was not held by the scratch")
    assert(
      before.exists(java.util.Arrays.equals(_, payload.toArray)),
      "the key was not decoded in the scratch"
    )
    assert(after.nonEmpty && after.forall(_.forall(_ == 0)), "a buffer kept its bytes")
  }

  test("the native memory of a transfer is zero once it ends, whether it returns or throws") {
    Using(Arena.ofConfined()) { arena =>
      val kept = KeyText.nativeIn(arena, 64) { segment =>
        segment.fill(0x5a.toByte)
        segment
      }
      assert((0L until 64L).forall(kept.get(ValueLayout.JAVA_BYTE, _) == 0), "returned")
      var thrown: Option[MemorySegment] = None
      intercept[ArithmeticException](KeyText.nativeIn(arena, 64) { segment =>
        segment.fill(0x5a.toByte)
        thrown = Some(segment)
        throw new ArithmeticException("the transfer failed")
      })
      assert(
        thrown.exists(s => (0L until 64L).forall(s.get(ValueLayout.JAVA_BYTE, _) == 0)),
        "threw"
      )
    }
  }

  test("a written envelope is the text of the key, from memory that is zero once it is used") {
    Using(Arena.ofConfined()) { arena =>
      var seen: Option[java.nio.ByteBuffer] = None
      val text = KeyText.writtenIn(
        arena,
        Type,
        "KES Signing Key",
        Sum6KesSigning.KeyBytes,
        key.payloadIn(_)
      ) { buffer =>
        seen = Some(buffer)
        val out = new Array[Byte](buffer.remaining())
        buffer.duplicate().get(out)
        new String(out, StandardCharsets.UTF_8)
      }
      assertEquals(
        text,
        s"{\n  \"type\" : \"$Type\",\n  \"description\" : \"KES Signing Key\",\n  \"cborHex\" : \"$hex\"\n}"
      )
      val buffer = seen.get
      assert(
        (0 until buffer.capacity()).forall(buffer.get(_) == 0),
        "the envelope's memory kept its bytes"
      )
    }
  }

  private object Using:
    def apply[A](arena: Arena)(body: Arena => A): A = scala.util.Using.resource(arena)(body)
