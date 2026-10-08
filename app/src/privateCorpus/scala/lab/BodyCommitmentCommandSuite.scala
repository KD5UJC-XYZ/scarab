// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.unsafe.implicits.global
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.chain.CardanoBodyCommitment as B

class BodyCommitmentCommandSuite extends munit.FunSuite:
  private val corpus = Path.of("fixtures/body-commitment")
  private def positive: Path =
    // The stable independent table, rather than any filename inference, identifies a positive.
    val line = Files.readString(corpus.resolve("expectations.tsv")).linesIterator.next()
    corpus.resolve("blocks").resolve(line.split("\t")(0))

  test("bounded reader preserves exact raw bytes") {
    assertEquals(
      BodyCommitmentCommand.read(positive).unsafeRunSync(),
      Bytes.fromArray(Files.readAllBytes(positive))
    )
  }

  test("CLI returns 0 for matching declarations and 1 for retained Conway size negative") {
    assertEquals(BodyCommitmentCommand.run(List(positive.toString)).unsafeRunSync().code, 0)
    assertEquals(
      BodyCommitmentCommand
        .run(List(corpus.resolve("blocks/previous-19-Block_Conway.cbor").toString))
        .unsafeRunSync()
        .code,
      1
    )
  }

  test("read-only report includes independent digests, separate outcomes and unchecked claims") {
    val o = B.inspect(Bytes.fromArray(Files.readAllBytes(positive))).toOption.get
    val report = BodyCommitmentCommand.render(o)
    Vector(
      "\"bodyCommitmentMatched\":true",
      "\"bodySizeMatched\":true",
      "\"bodyHashMatched\":true",
      "\"structurallyIndexed\":true",
      "\"headerCryptography\":\"not_checked\"",
      "\"consensus\":\"not_checked\"",
      "\"ledger\":\"not_checked\"",
      "\"sourceAuthentication\":\"not_checked\"",
      o.rawSha256.hex,
      o.headerHash.hex,
      o.actualHash.hex
    )
      .foreach(expected => assert(report.contains(expected), expected))
    assert(report.length < 4096)
  }

  test("malformed input, missing files, invalid paths and usage return 2; help returns 0") {
    val tmp = Files.createTempDirectory("body-commitment-cli-")
    try
      val empty = tmp.resolve("empty.cbor"); Files.write(empty, Array.emptyByteArray)
      val trailing = tmp.resolve("trailing.cbor")
      Files.write(trailing, Files.readAllBytes(positive) :+ 0.toByte)
      val tooLarge = tmp.resolve("oversized.cbor")
      Files.write(tooLarge, new Array[Byte](BodyCommitmentCommand.MaxBytes + 1))
      Vector(
        List.empty[String],
        List("--bad"),
        List("a", "b"),
        List("bad\u0000path"),
        List(tmp.resolve("missing").toString),
        List(tmp.toString),
        List(empty.toString),
        List(trailing.toString),
        List(tooLarge.toString)
      ).foreach(args => assertEquals(BodyCommitmentCommand.run(args).unsafeRunSync().code, 2))
      assertEquals(BodyCommitmentCommand.run(List("--help")).unsafeRunSync().code, 0)
      assertEquals(Files.size(empty), 0L)
      assertEquals(Files.size(tooLarge), BodyCommitmentCommand.MaxBytes.toLong + 1)
    finally
      val files = Files.list(tmp)
      try files.forEach(p => { Files.delete(p); () })
      finally files.close()
      Files.delete(tmp)
  }

  test("symlink input is rejected without following it") {
    val tmp = Files.createTempDirectory("body-commitment-link-")
    try
      val link = Files.createSymbolicLink(tmp.resolve("linked.cbor"), positive.toAbsolutePath)
      assertEquals(BodyCommitmentCommand.run(List(link.toString)).unsafeRunSync().code, 2)
      assert(Files.isSymbolicLink(link))
      Files.delete(link)
    finally Files.delete(tmp)
  }
