// SPDX-License-Identifier: Apache-2.0
package lab.chain

import java.nio.charset.StandardCharsets
import lab.Blake2b
import lab.cbor.Bytes
import lab.chain.CardanoBodyCommitment as B

/** Expectations were projected offline by independent Python code, never by this implementation. */
class CardanoBodyCommitmentSuite extends munit.FunSuite:
  private def resource(name: String): Bytes =
    val in = getClass.getResourceAsStream(s"/body-commitment/$name")
    require(in != null, s"missing fixture $name")
    try Bytes.fromArray(in.readAllBytes())
    finally in.close()
  private def rows(name: String): Vector[Vector[String]] =
    new String(resource(name).toArray, StandardCharsets.UTF_8).linesIterator
      .map(_.split("\t", -1).toVector)
      .toVector
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(fail(_), identity)
  private def right(raw: Bytes): B.Observation =
    B.inspect(raw).fold(e => fail(e.toString), identity)
  private val corpus = rows("expectations.tsv")
  private val edges = rows("edges.tsv")
  private def edge(id: String): Bytes = hex(edges.find(_.head == id).get(2))
  private def outcome(raw: Bytes): String = B.inspect(raw) match
    case Left(B.Failure.Malformed(_)) => "malformed"
    case Left(other)                  => fail(s"unexpected internal failure: $other")
    case Right(o) =>
      if o.bodyCommitmentMatched then "matched"
      else if o.bodySizeMatched then "hash-mismatch"
      else if o.bodyHashMatched then "size-mismatch"
      else "both-mismatch"

  for row <- corpus do
    test(s"original-byte body commitment: ${row.head}") {
      val raw = resource(s"blocks/${row(0)}")
      val o = right(raw)
      val i = CardanoBlockIndex.inspect(raw).fold(fail(_), identity)
      assertEquals(o.era.diskTag, row(1).toInt)
      assertEquals(o.rawSha256.hex, row(2))
      assertEquals(o.headerHash.hex, row(3))
      assertEquals(o.declaredSize, row(4).toLong)
      assertEquals(o.actualSize, row(5).toLong)
      assertEquals(o.declaredHash.hex, row(6))
      assertEquals(o.actualHash.hex, row(7))
      assertEquals(o.bodySizeMatched, row(8).toBoolean)
      assertEquals(o.bodyHashMatched, row(9).toBoolean)
      assertEquals(o.bodyCommitmentMatched, row(8).toBoolean && row(9).toBoolean)
      assertEquals(o.components.map(_.offset), row(10).split(",").toVector.map(_.toInt))
      assertEquals(o.components.map(_.length), row(11).split(",").toVector.map(_.toInt))
      assertEquals(o.components.map(_.hash.hex), row(12).split(",").toVector)
      assertEquals(o.rawSha256, i.rawSha256)
      assertEquals(o.headerHash, i.headerHash)
      assertEquals(
        o.components.map(_.kind),
        B.ComponentKind.values.take(o.components.size).toVector
      )
      assertEquals(o.components.head.offset, i.headerOffset + i.headerLength)
      o.components.foreach { c =>
        assert(c.offset >= 0 && c.length >= 1 && c.offset + c.length <= raw.size)
        val original = Bytes(raw.value.slice(c.offset, c.offset + c.length))
        assertEquals(Blake2b.hash256.hash(original), c.hash)
      }
      val flat = Bytes(o.components.flatMap(c => raw.value.slice(c.offset, c.offset + c.length)))
      assertNotEquals(Blake2b.hash256.hash(flat), o.actualHash)
    }

  for row <- edges do
    test(s"independent synthetic edge: ${row.head}") {
      assertEquals(outcome(hex(row(2))), row(1))
      if row.head.endsWith("word32-overflow") || row.head.endsWith("word64-max") then
        assert(
          CardanoBlockIndex.inspect(hex(row(2))).isRight,
          "new Word32 predicate must not change existing structural uint64 acceptance"
        )
      if row(1) != "malformed" then assert(CardanoBlockIndex.inspect(hex(row(2))).isRight)
    }

  test("corpus accounting and empty body known answers") {
    assertEquals(corpus.size, 36)
    assertEquals(corpus.count(r => r(8).toBoolean && r(9).toBoolean), 35)
    assertEquals(corpus.count(_(9).toBoolean), 36)
    assertEquals(corpus.count(_.head.startsWith("mainnet-")), 16)
    assertEquals(edges.size, 347)
    assertEquals(
      right(edge("era-2-empty")).actualHash.hex,
      "1033376be025cb705fd8dd02eda11cc73975a062b5d14ffd74d6ff69e69a2ff7"
    )
    assertEquals(
      right(edge("era-6-empty")).actualHash.hex,
      "29571d16f081709b3c48651860077bebf9340abb3fc7133443c54f1f5a5edcf1"
    )
    val toy = right(resource("blocks/previous-19-Block_Conway.cbor"))
    assertEquals(toy.declaredSize, 2345L)
    assertEquals(toy.actualSize, 6950L)
    assert(toy.bodyHashMatched && !toy.bodySizeMatched)
  }

  test("same-header component mutations cannot retain a matching hash") {
    for era <- 2 to 7 do
      val base = right(edge(s"era-$era-synthetic-nonempty"))
      for i <- 0 until (if era < 5 then 3 else 4) do
        val mutation = right(edge(s"era-$era-component-$i-payload"))
        assertEquals(mutation.headerHash, base.headerHash)
        assertEquals(mutation.actualSize, base.actualSize)
        assertNotEquals(mutation.rawSha256, base.rawSha256)
        assert(mutation.bodySizeMatched && !mutation.bodyHashMatched)
  }

  test("outer changes preserve header and commitments, header changes alter only its identity") {
    for era <- 2 to 7 do
      val original = right(edge(s"era-$era-empty"))
      for label <- Vector(
          "outer-wide",
          "outer-indefinite",
          "block-wide",
          "block-indefinite",
          "era-wide"
        )
      do
        val o = right(edge(s"era-$era-$label"))
        assertEquals(o.headerHash, original.headerHash)
        assertEquals(o.actualHash, original.actualHash)
        assertEquals(o.actualSize, original.actualSize)
        assertNotEquals(o.rawSha256, original.rawSha256)
      val header = right(edge(s"era-$era-header-indefinite"))
      assertNotEquals(header.headerHash, original.headerHash)
      assertEquals(header.actualHash, original.actualHash)
      assert(header.bodyCommitmentMatched)
  }

  test("tightened byte, depth, item and string limits: exact threshold plus or minus one") {
    val raw = edge("era-6-empty")
    assertEquals(raw.size, 864)
    val limits = Vector(
      (n: Int) => CardanoBlockIndex.Limits(maxBytes = n),
      (n: Int) => CardanoBlockIndex.Limits(maxDepth = n),
      (n: Int) => CardanoBlockIndex.Limits(maxItems = n),
      (n: Int) => CardanoBlockIndex.Limits(maxStringBytes = n)
    )
    limits.zip(Vector(864, 5, 28, 448)).foreach { case (make, boundary) =>
      assert(B.inspect(raw, make(boundary - 1)).isLeft)
      assert(B.inspect(raw, make(boundary)).toOption.get.bodyCommitmentMatched)
      assert(B.inspect(raw, make(boundary + 1)).toOption.get.bodyCommitmentMatched)
    }
    Vector(
      CardanoBlockIndex.Limits(maxBytes = 0),
      CardanoBlockIndex.Limits(maxBytes = 1048577),
      CardanoBlockIndex.Limits(maxDepth = -1),
      CardanoBlockIndex.Limits(maxDepth = 33),
      CardanoBlockIndex.Limits(maxItems = 0),
      CardanoBlockIndex.Limits(maxItems = 100001),
      CardanoBlockIndex.Limits(maxStringBytes = -1),
      CardanoBlockIndex.Limits(maxStringBytes = 1048577)
    ).foreach(l => assert(B.inspect(raw, l).isLeft))
    assert(B.inspect(raw, null).isLeft)
    assert(B.inspect(null).isLeft)
    assert(B.inspect(Bytes(null)).isLeft)
  }

  test("every truncation of a small block is malformed, as is one byte over the hard input cap") {
    val raw = edge("era-6-empty")
    (0 until raw.size).foreach(n => assertEquals(outcome(Bytes(raw.value.take(n))), "malformed"))
    assertEquals(outcome(Bytes(Vector.fill(1048577)(0.toByte))), "malformed")
  }

  test("hard input, depth and item ceilings accept the boundary and reject one above") {
    val raw = edge("era-6-empty")
    val o = right(raw)
    val aux = o.components(2)
    def withAux(value: Bytes): Bytes =
      Bytes(raw.value.take(aux.offset) ++ value.value ++ raw.value.drop(aux.offset + aux.length))
    def uint32Head(prefix: Int, n: Int): Vector[Byte] =
      Vector(prefix.toByte) ++ Vector.tabulate(4)(i => ((n >>> (24 - 8 * i)) & 255).toByte)
    val payload = 1048576 - (raw.size - aux.length + 7)
    val exact = withAux(
      Bytes(
        Vector(0xa1.toByte, 0.toByte) ++
          uint32Head(0x5a, payload) ++ Vector.fill(payload)(0.toByte)
      )
    )
    assertEquals(exact.size, 1048576)
    assert(B.inspect(exact).isRight)
    assert(B.inspect(Bytes(exact.value :+ 0.toByte)).isLeft)
    def nested(n: Int): Bytes = withAux(
      Bytes(
        Vector(0xa1.toByte, 0.toByte) ++
          Vector.fill(n)(0x81.toByte) :+ 0.toByte
      )
    )
    assert(B.inspect(nested(29)).isRight)
    assert(B.inspect(nested(30)).isLeft)
    def items(n: Int): Bytes = withAux(
      Bytes(
        Vector(0xa1.toByte, 0.toByte) ++
          uint32Head(0x9a, n) ++ Vector.fill(n)(0.toByte)
      )
    )
    assert(B.inspect(items(99970)).isRight)
    assert(B.inspect(items(99971)).isLeft)
    val chunk = Vector(0x59.toByte, 1.toByte, 0.toByte) ++ Vector.fill(256)(0.toByte)
    val chunks = withAux(
      Bytes(
        Vector(0xa1.toByte, 0.toByte, 0x5f.toByte) ++
          chunk ++ chunk :+ 0xff.toByte
      )
    )
    assert(B.inspect(chunks, CardanoBlockIndex.Limits(maxStringBytes = 512)).isRight)
    assert(B.inspect(chunks, CardanoBlockIndex.Limits(maxStringBytes = 511)).isLeft)
  }

  test(
    "coordinated semantically invalid bodies change the header identity while matching commitments"
  ) {
    for era <- 2 to 7 do
      val original = right(edge(s"era-$era-empty"))
      val labels =
        Vector("transaction-witness-count-mismatch", "aux-out-of-range", "aux-duplicate") ++
          (if era >= 5 then Vector("invalid-out-of-range", "invalid-duplicate", "invalid-unsorted")
           else Vector.empty)
      labels.foreach { label =>
        val o = right(edge(s"era-$era-$label"))
        assert(o.bodyCommitmentMatched)
        assertNotEquals(o.headerHash, original.headerHash)
        assertNotEquals(o.rawSha256, original.rawSha256)
      }
  }

  test("caller array and returned digest array mutation cannot alter the observation") {
    val original = edge("era-6-empty")
    val source = original.toArray
    val o = right(Bytes.fromArray(source))
    val saved = (o.rawSha256, o.headerHash, o.declaredHash, o.actualHash, o.components.map(_.hash))
    source(0) = 0
    (Vector(o.rawSha256, o.headerHash, o.declaredHash, o.actualHash) ++ o.components.map(_.hash))
      .foreach { bytes =>
        val array = bytes.toArray
        array(0) = 0
      }
    assertEquals(
      (o.rawSha256, o.headerHash, o.declaredHash, o.actualHash, o.components.map(_.hash)),
      saved
    )
    assert(o.bodyCommitmentMatched)
  }
