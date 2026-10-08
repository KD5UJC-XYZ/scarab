// SPDX-License-Identifier: Apache-2.0
package lab.fetcher

import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.chain.CardanoBlockIndex
import lab.network.{CardanoBlockFetch, NumericPeer, TcpLimits}
import scala.jdk.CollectionConverters.*

/** Pure/local-file tests only: never creates a transport, resolves DNS, or opens a socket. */
class TcpDirectRangeDescriptorSuite extends munit.FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private val tcp = right(TcpLimits.checked())
  private val limits = right(TcpFetchLimits.checked(tcp))
  private val provenance =
    "Endpoint claims only; synthetic descriptor; no authenticated block bodies.\n"
  private val provenanceHash = Digests.text(provenance)
  private def point(slot: BigInt, fill: String): String = s"$slot:${fill * 32}"
  private val anchor = point(9, "09")
  private val first = point(10, "10")
  private val middle = point(15, "15")
  private val last = point(20, "20")
  private val defaults = Vector(
    "format" -> "tcp-direct-range-v1",
    "peer" -> "127.0.0.1",
    "port" -> "12345",
    "networkLabel" -> "synthetic-claims",
    "networkMagic" -> "42",
    "anchor" -> anchor,
    "first" -> first,
    "last" -> last,
    "provenanceSha256" -> provenanceHash,
    "attribution" -> "local-script",
    "expectedCount" -> "-",
    "expectedPoints" -> "-",
    "bytePins" -> "-"
  )
  private def text(changes: (String, String)*): String =
    val updated = changes.toMap
    defaults
      .map { (key, value) => s"$key\t${updated.getOrElse(key, value)}" }
      .mkString("", "\n", "\n")
  private def parse(changes: (String, String)*): Either[String, TcpDirectRangeDescriptor] =
    TcpDirectRangeDescriptor.parse(text(changes*), provenanceHash, limits)
  private def pin(p: String, size: Int = 100, hash: String = "aa" * 32): String = s"$p,$size,$hash"

  test("canonical numeric IPv6 aliases have identical endpoint and descriptor identities") {
    val aliases = Vector("2001:db8::a", "2001:0db8:0000:0000:0000:0000:0000:000a", "2001:DB8::A")
    val descriptors = aliases.map(peer => right(parse("peer" -> peer)))
    assertEquals(descriptors.map(_.identity.digest).distinct.size, 1)
    assertEquals(descriptors.map(_.peer.identity).distinct.size, 1)
    assertNotEquals(right(parse()).identity.digest, descriptors.head.identity.digest)
    assertNotEquals(right(parse("port" -> "12346")).identity.digest, right(parse()).identity.digest)
    // Global numeric literals are accepted only by this pure parser. They are never connected.
    assert(NumericPeer.checked("1.1.1.1", 3001).isRight)
    for invalid <- Vector(
        "localhost",
        "https://127.0.0.1",
        "127.1",
        "127.00.0.1",
        "0.0.0.0",
        "224.0.0.1",
        "::",
        "ff02::1",
        "fe80::1%lo",
        "::ffff:7f00:1",
        "::ffff:127.0.0.1"
      )
    do assert(NumericPeer.checked(invalid, 3001).isLeft, invalid)
  }

  test("UInt64 maximum endpoints and UInt32 maximum magic remain unsigned") {
    val max = (BigInt(1) << 64) - 1
    val d = right(
      parse(
        "anchor" -> point(max - 2, "09"),
        "first" -> point(max - 1, "10"),
        "last" -> point(max, "20"),
        "networkMagic" -> "4294967295"
      )
    )
    assertEquals(d.batch.first.slot.value, max - 1)
    assertEquals(d.batch.last.slot.value, max)
    assertEquals(d.data.magic, 4294967295L)
    assert(parse("last" -> point(max + 1, "20")).isLeft)
    assert(parse("networkMagic" -> "4294967296").isLeft)
    assert(parse("first" -> point(-1, "10")).isLeft)
  }

  test("file names do not enter identity, actual provenance bytes do") {
    val root = Files.createTempDirectory("tcp-descriptor-pure-")
    try
      val descriptor = root.resolve("one.tsv")
      val claims = root.resolve("one.txt")
      Files.writeString(descriptor, text())
      Files.writeString(claims, provenance)
      val before = TcpDirectRangeDescriptor.load(descriptor, claims, limits)
      val renamedDescriptor = Files.move(descriptor, root.resolve("renamed.tsv"))
      val renamedClaims = Files.move(claims, root.resolve("renamed.txt"))
      val after = TcpDirectRangeDescriptor.load(renamedDescriptor, renamedClaims, limits)
      assertEquals(after.canonical, before.canonical)
      assertEquals(after.identity.digest, before.identity.digest)
      Files.writeString(renamedClaims, provenance + "changed content\n")
      val failure = intercept[FetchError](
        TcpDirectRangeDescriptor.load(renamedDescriptor, renamedClaims, limits)
      )
      assertEquals(failure.code, 2)
    finally
      val walk = Files.walk(root)
      try
        walk
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(Files.deleteIfExists(_))
      finally walk.close()
  }

  test("partial byte pins canonicalize order while keeping absence distinct from assertions") {
    val forward = right(parse("bytePins" -> s"${pin(first)};${pin(last)}"))
    val reverse = right(parse("bytePins" -> s"${pin(last)};${pin(first)}"))
    assertEquals(forward.canonical, reverse.canonical)
    assertEquals(forward.identity.digest, reverse.identity.digest)
    assertEquals(forward.bytePins.map(_.point.encoded), Vector(first, last))
    assertNotEquals(forward.identity.digest, right(parse()).identity.digest)
    assert(right(parse("bytePins" -> pin(middle))).batch.expectedPoints.isEmpty)
  }

  test("duplicate, malformed and provably outside pins reject before acquisition") {
    for pins <- Vector(
        s"${pin(first)};${pin(first)}",
        pin(anchor),
        pin(point(21, "21")),
        pin(point(10, "ff")),
        pin(point(20, "ff")),
        pin(first, 0),
        pin(first, 1048577),
        pin(first, 100, "AA" * 32),
        pin(first, 100, "not-a-hash")
      )
    do assert(parse("bytePins" -> pins).isLeft, pins)
    assert(parse("expectedPoints" -> s"$first,$last", "bytePins" -> pin(middle)).isLeft)
    assert(
      parse(
        "expectedCount" -> "2",
        "bytePins" -> s"${pin(first)};${pin(middle)};${pin(last)}"
      ).isLeft
    )
  }

  test("aggregate mandatory pin sizes cannot exceed raw acquisition budget") {
    val small = right(TcpFetchLimits.checked(tcp, maxRawBytes = 150))
    val value = TcpDirectRangeDescriptor.parse(
      text("bytePins" -> s"${pin(first)};${pin(last)}"),
      provenanceHash,
      small
    )
    assert(value.isLeft, "two required 100-byte pins cannot fit a 150-byte total raw budget")
  }

  test("exact point lists and counts reject incompatible endpoints, ordering and counts") {
    assert(parse("expectedCount" -> "3", "expectedPoints" -> s"$first,$middle,$last").isRight)
    for changes <- Vector(
        Vector("expectedCount" -> "2", "expectedPoints" -> s"$first,$middle,$last"),
        Vector("expectedCount" -> "1"),
        Vector("expectedCount" -> "5"),
        Vector("expectedPoints" -> s"$middle,$last"),
        Vector("expectedPoints" -> s"$first,$middle"),
        Vector("expectedPoints" -> s"$first,$last,$middle,$last"),
        Vector("expectedPoints" -> s"$first,$first,$last")
      )
    do assert(parse(changes*).isLeft, changes.toString)
    assert(parse("last" -> first, "expectedCount" -> "1", "expectedPoints" -> first).isRight)
    assert(parse("last" -> first, "expectedCount" -> "2").isLeft)
  }

  test("unmatched interior pin remains optional-point metadata until acquired batch verification") {
    val dir = Vector(Path.of("fixtures/chain-fetch"), Path.of("../fixtures/chain-fetch"))
      .find(Files.isDirectory(_))
      .getOrElse(throw new IllegalStateException("missing fixtures"))
    val raw = (0 until 4)
      .map(i => Bytes.fromArray(Files.readAllBytes(dir.resolve(s"shelley/shelley-$i.cbor"))))
      .toVector
    val indexed = raw.map(b => right(CardanoBlockIndex.inspect(b, 1048576)))
    val originals = raw.map(b => right(CardanoBlockFetch.RawNtNBlock.from(b)))
    val actual = indexed.map(Point.of)
    val fake = right(Point.parse(s"${actual(1).slot}:${"ff" * 32}"))
    val policy = new ByteExpectationPolicy.OptionalKnownBytePins(
      Vector(ByteExpectationPolicy.KnownBytePin(fake, raw(1).size, Digests.sha256(raw(1).toArray)))
    )
    assert(policy.verify(originals).isLeft)
    val matched = new ByteExpectationPolicy.OptionalKnownBytePins(
      Vector(
        ByteExpectationPolicy.KnownBytePin(actual(1), raw(1).size, Digests.sha256(raw(1).toArray))
      )
    )
    assert(matched.verify(originals).isRight)
    val full = new ByteExpectationPolicy.OptionalKnownBytePins(
      actual
        .zip(raw)
        .map((p, b) => ByteExpectationPolicy.KnownBytePin(p, b.size, Digests.sha256(b.toArray)))
    )
    assert(full.verify(originals).isRight)
    assert(new ByteExpectationPolicy.OptionalKnownBytePins(Vector.empty).verify(originals).isRight)
  }
