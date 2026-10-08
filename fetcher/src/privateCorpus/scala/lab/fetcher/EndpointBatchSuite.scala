// SPDX-License-Identifier: Apache-2.0
package lab.fetcher

import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.network.{BlockFetch, CardanoBlockFetch, ChainSync}
import CardanoBlockFetch.{InclusiveRange, RawNtNBlock, SpecificPoint}
import BlockFetch.Message

class EndpointBatchSuite extends munit.FunSuite:
  private def right[E, A](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)
  private val fixtures =
    if Files.exists(Path.of("fixtures/chain-fetch")) then Path.of("fixtures/chain-fetch")
    else Path.of("../fixtures/chain-fetch")
  private def bytes(era: String, i: Int): Bytes =
    Bytes.fromArray(Files.readAllBytes(fixtures.resolve(s"$era/$era-$i.cbor")))
  private def raw(b: Bytes): RawNtNBlock = right(RawNtNBlock.from(b))
  private def point(slot: BigInt, hash: String): SpecificPoint = right(
    SpecificPoint.from(
      ChainSync.Point.Block(right(ChainSync.UInt64.from(slot)), right(Bytes.fromHex(hash)))
    )
  )
  // Literal points copied from the independently recorded expectations in CardanoBlockIndexSuite;
  // see fixtures/chain-fetch/PROVENANCE.md (Allegra uses the earlier retained acquisition).
  // Fixture expectations are not generated with CardanoBlockIndex.
  private val expected = Map(
    "shelley" -> Vector(
      point(BigInt(4492800), "aa83acbf5904c0edfe4d79b3689d3d00fcfc553cf360fd2229b98d464c28e9de"),
      point(BigInt(4492840), "4aff331a7b3022c0e3f94694f9a5f5739a78727db344a7b0331d1a0376e923e9"),
      point(BigInt(4492860), "3b5acf05f4f58290acee1654c9e1ec3a787aa9c27810c296103afd206702fb1b"),
      point(BigInt(4492880), "23fd3b638e8f286978681567d52597b73f7567e18719cef2cbd66bba31303d98")
    ),
    "allegra" -> Vector(
      point(BigInt(16588800), "078d102d0247463f91eef69fc77f3fbbf120f3118e68cd5e6a493c15446dbf8c"),
      point(BigInt(16588845), "d320eb16082133a24b11d00f687a55740a3efddf6de2a104bb826d6113f1bfcd"),
      point(BigInt(16588862), "2563b8f81e5881452c25533ce0d8976a1f845041d3a2cbb3a0f98fe8eee6dd20"),
      point(BigInt(16588889), "f77e011ef1ad383dced90e32e18c28c0fdc6e363066ab5adf191044fdbcf668d")
    ),
    "babbage" -> Vector(
      point(BigInt(43610428), "40a3d87d796ade6686c21c35ef0cedc7e2a792452f418c24b34403d606ef9a0e"),
      point(BigInt(43610443), "1fc52cb9c93e7ea7d5985a581c43a91f9b51c011173717c2d60d9f37d55fc129"),
      point(BigInt(43610470), "c576d670f8c011b1ddb507bc3b70bab50738d0e6290e29804c99e969cb74a156"),
      point(BigInt(43610483), "d51f1cd7d29585e4faeb97202b09124eb7d4789d1a32a0309516d00d66551e42")
    )
  )
  private def anchor(era: String): Point =
    val lines = Files.readString(fixtures.resolve(s"$era.tsv")).split("\n")
    right(Point.parse(lines.find(_.startsWith("after\t")).get.stripPrefix("after\t")))
  private def spec(
      era: String = "shelley",
      count: Option[Int] = None,
      all: Boolean = false,
      limits: EndpointBatch.Limits = EndpointBatch.Limits.Default
  ) =
    val ps = expected(era)
    right(
      EndpointBatch.Spec.checked(
        anchor(era),
        InclusiveRange(ps.head, ps.last),
        count,
        if all then Some(ps) else None,
        limits
      )
    )
  private def stream(s: EndpointBatch.Spec): EndpointBatch.Batch =
    right(EndpointBatch.begin(s).accept(Message.StartBatch))
  private def add(b: EndpointBatch.Batch, rawBytes: Bytes): EndpointBatch.Batch =
    right(b.accept(Message.Block(raw(rawBytes))))
  private def prefix(s: EndpointBatch.Spec, era: String, n: Int): EndpointBatch.Batch =
    (0 until n).foldLeft(stream(s))((b, i) => add(b, bytes(era, i)))

  for era <- Vector("shelley", "allegra", "babbage") do
    test(s"$era endpoint-only and independent exact-list batches preserve all four originals") {
      for all <- Vector(false, true) do
        val b = prefix(spec(era, if all then Some(4) else None, all), era, 4)
        assertEquals(b.state, BlockFetch.State.Streaming)
        assertEquals(b.result, EndpointBatch.Result.Pending)
        assert(b.endOfInput.isLeft) // Even after the endpoint, EOF is not BatchDone.
        val done = right(b.accept(Message.BatchDone))
        assertEquals(
          done.result,
          EndpointBatch.Result.Complete((0 until 4).map(i => raw(bytes(era, i))).toVector)
        )
        assertEquals(done.endOfInput, Right(done.result))
        assert(done.accept(Message.BatchDone).isLeft)
        assert(done.accept(Message.Block(raw(bytes(era, 3)))).isLeft)
    }
    test(s"$era empty, missing, reordered, duplicated, extra and wrong-endpoint blocks fail") {
      val s = spec(era)
      assert(stream(s).accept(Message.BatchDone).isLeft)
      assert(stream(s).accept(Message.Block(raw(bytes(era, 1)))).isLeft)
      for n <- 1 until 4 do
        val b = prefix(s, era, n)
        assert(b.accept(Message.BatchDone).isLeft)
        assert(b.endOfInput.isLeft)
      val one = prefix(s, era, 1)
      assert(one.accept(Message.Block(raw(bytes(era, 0)))).isLeft)
      assert(one.accept(Message.Block(raw(bytes(era, 2)))).isLeft)
      assert(prefix(s, era, 4).accept(Message.Block(raw(bytes(era, 3)))).isLeft)
      val ps = expected(era)
      val wrongEnd = point(ps.last.slot.value, "00" * 32)
      val badSpec =
        right(EndpointBatch.Spec.checked(anchor(era), InclusiveRange(ps.head, wrongEnd)))
      assert(prefix(badSpec, era, 3).accept(Message.Block(raw(bytes(era, 3)))).isLeft)
    }

  test("single endpoint requires one original and BatchDone") {
    val p = expected("shelley").head
    val s = right(EndpointBatch.Spec.checked(anchor("shelley"), InclusiveRange.single(p), Some(1)))
    val b = add(stream(s), bytes("shelley", 0))
    assert(b.endOfInput.isLeft)
    assert(right(b.accept(Message.BatchDone)).endOfInput.isRight)
    assert(b.accept(Message.Block(raw(bytes("shelley", 1)))).isLeft)
  }

  test("NoBlocks is unavailable, never complete; wrong agency and EOF fail") {
    val begin = EndpointBatch.begin(spec())
    val unavailable = right(begin.accept(Message.NoBlocks))
    assertEquals(unavailable.result, EndpointBatch.Result.Unavailable)
    assertEquals(unavailable.endOfInput, Right(EndpointBatch.Result.Unavailable))
    assert(unavailable.accept(Message.StartBatch).isLeft)
    assert(begin.endOfInput.isLeft)
    assert(begin.accept(Message.BatchDone).isLeft)
    assert(begin.accept(Message.Block(raw(bytes("shelley", 0)))).isLeft)
    assert(stream(spec()).accept(Message.NoBlocks).isLeft)
    assert(stream(spec()).accept(Message.ClientDone).isLeft)
  }

  test("wrong anchor, corrupt original header, unsupported disk era and malformed bytes fail") {
    val ps = expected("shelley")
    val badAnchor = right(Point.parse(s"0:${"00" * 32}"))
    val s = right(EndpointBatch.Spec.checked(badAnchor, InclusiveRange(ps.head, ps.last)))
    assert(stream(s).accept(Message.Block(raw(bytes("shelley", 0)))).isLeft)
    val original = bytes("shelley", 0)
    // Change original header signature byte, preserving structural shape but changing identity.
    val corrupt = Bytes(original.value.updated(600, (original.value(600) ^ 1).toByte))
    for b <- Vector(
        corrupt,
        Bytes(original.value.updated(1, 8.toByte)),
        Bytes(original.value.dropRight(1)),
        Bytes(Vector(0x80.toByte))
      )
    do assert(stream(spec()).accept(Message.Block(raw(b))).isLeft)
  }

  test("exact count and independent intermediate identities add independent checks") {
    assert(
      prefix(spec(count = Some(3)), "shelley", 3)
        .accept(Message.Block(raw(bytes("shelley", 3))))
        .isLeft
    )
    val ps = expected("shelley")
    val altered = ps.updated(1, point(ps(1).slot.value, "01" * 32))
    val s = right(
      EndpointBatch.Spec
        .checked(anchor("shelley"), InclusiveRange(ps.head, ps.last), Some(4), Some(altered))
    )
    assert(prefix(s, "shelley", 1).accept(Message.Block(raw(bytes("shelley", 1)))).isLeft)
  }

  test("checked constructors reject invalid, unbounded and incompatible selections") {
    for n <- Vector(-1, 0, 5, Int.MaxValue) do
      assert(EndpointBatch.Limits.checked(maxBlocks = n).isLeft)
    for n <- Vector(-1L, 0L, 4194305L, Long.MaxValue) do
      assert(EndpointBatch.Limits.checked(maxRawBytes = n).isLeft)
    for n <- Vector(-1, 0, 1048577, Int.MaxValue) do
      assert(EndpointBatch.Limits.checked(maxBlockBytes = n).isLeft)
    val ps = expected("shelley")
    val range = InclusiveRange(ps.head, ps.last)
    for count <- Vector(0, 1, 5, Int.MaxValue) do
      assert(EndpointBatch.Spec.checked(anchor("shelley"), range, Some(count)).isLeft)
    for list <- Vector(Vector.empty, ps.reverse, ps.updated(1, ps.head), ps.take(3)) do
      assert(EndpointBatch.Spec.checked(anchor("shelley"), range, None, Some(list)).isLeft)
    assert(EndpointBatch.Spec.checked(anchor("shelley"), range, Some(3), Some(ps)).isLeft)
    assert(EndpointBatch.Spec.checked(anchor("shelley"), InclusiveRange(ps.last, ps.head)).isLeft)
    assert(
      EndpointBatch.Spec
        .checked(anchor("shelley"), InclusiveRange(ps.head, point(ps.head.slot.value, "00" * 32)))
        .isLeft
    )
    assert(SpecificPoint.from(ChainSync.Point.Origin).isLeft)
    assert(
      EndpointBatch.Spec
        .checked(right(Point.parse(s"${ps.head.slot.value}:${ps.head.hash.hex}")), range)
        .isLeft
    )
  }

  test("aggregate and per-block exact boundaries pass; one byte less and one block less fail") {
    val originals = (0 until 4).map(i => bytes("shelley", i))
    val total = originals.map(_.size.toLong).sum
    val max = originals.map(_.size).max
    val exact = right(EndpointBatch.Limits.checked(4, total, max))
    assert(
      right(prefix(spec(limits = exact), "shelley", 4).accept(Message.BatchDone)).endOfInput.isRight
    )
    val short = right(EndpointBatch.Limits.checked(4, total - 1, max))
    assert(
      prefix(spec(limits = short), "shelley", 3)
        .accept(Message.Block(raw(originals(3))))
        .isLeft
    )
    val small = right(EndpointBatch.Limits.checked(maxBlockBytes = originals.head.size - 1))
    assert(stream(spec(limits = small)).accept(Message.Block(raw(originals.head))).isLeft)
    val three = right(EndpointBatch.Limits.checked(maxBlocks = 3))
    assert(
      prefix(spec(limits = three), "shelley", 3)
        .accept(Message.Block(raw(originals(3))))
        .isLeft
    )
  }

  test("one MiB raw and four MiB aggregate hard ceilings are inclusive") {
    // Synthetic auxiliary bytes preserve each original header. This is solely a size/structural
    // test and deliberately makes no body commitment or ledger-validity claim.
    def padded(i: Int, size: Int): Bytes =
      val original = bytes("shelley", i)
      assertEquals(original.value.last, 0xa0.toByte)
      val n = size - (original.size - 1) - 7
      val head = Vector(
        0xa1.toByte,
        0.toByte,
        0x5a.toByte,
        (n >>> 24).toByte,
        (n >>> 16).toByte,
        (n >>> 8).toByte,
        n.toByte
      )
      Bytes(original.value.dropRight(1) ++ head ++ Vector.fill(n)(0.toByte))
    val b = (0 until 4).foldLeft(stream(spec()))((b, i) => add(b, padded(i, 1048576)))
    assertEquals(b.received.rawBytes, 4194304L)
    assert(right(b.accept(Message.BatchDone)).endOfInput.isRight)
    assert(stream(spec()).accept(Message.Block(raw(padded(0, 1048577)))).isLeft)
  }
