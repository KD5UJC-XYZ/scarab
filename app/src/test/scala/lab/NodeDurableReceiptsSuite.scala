// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.nio.file.{Files, Path}
import lab.cbor.Bytes

class NodeDurableReceiptsSuite extends munit.FunSuite:
  private def raw(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
  private def hash(b: Bytes) = ClusterHeaderObservation.sha256(b)
  private def hex(s: String) = Bytes.fromHex(s).toOption.get
  private val token = ValidatedCheckpoint.Token(hex("11" * 32), hex("22" * 32), 3, hex("33" * 32))
  private val valid = NodeDurableReceipts.acknowledgedBytes(token)
  private def parse(b: Bytes) = NodeDurableReceipts.parse(b, hash(b), token.contextId)
  private def text(b: Bytes) = new String(b.toArray, "UTF-8")
  test("strict acknowledged receipt requires exact independent hash and context") {
    assertEquals(parse(valid), Right(token))
    assert(NodeDurableReceipts.parse(valid, hex("44" * 32), token.contextId).isLeft)
    assert(NodeDurableReceipts.parse(valid, hash(valid), hex("44" * 32)).isLeft)
    assert(NodeDurableReceipts.parse(valid, Bytes.empty, token.contextId).isLeft)
  }
  test("pending wrong-shape duplicate and generation encodings never become acknowledgment") {
    val good = text(valid)
    Vector(
      good.replace(NodeDurableReceipts.AckFormat, NodeDurableReceipts.PendingFormat),
      good.replace("\"capacity\":8", "\"capacity\":4"),
      good.replace("\"generation\":\"3\"", "\"generation\":3"),
      good.replace("\"generation\":\"3\"", "\"generation\":\"03\""),
      good.replace("\"generation\":\"3\"", "\"generation\":\"9223372036854775808\""),
      good.replace("\"generation\":\"3\"", "\"generation\":\"-1\""),
      good.replace("\"storeId\":", "\"extra\":0,\"storeId\":"),
      good.replace("\"capacity\":8", "\"capacity\":8,\"capacity\":8"),
      good.replace("\"format\":\"node-durable-acknowledged-v1\",", ""),
      good.replace(token.digest.hex, "AA" * 32)
    ).foreach(s => assert(parse(raw(s)).isLeft))
    assert(parse(Bytes(Vector(0xc3.toByte, 0x28.toByte))).isLeft)
    assert(parse(raw("x" * 4097)).isLeft)
    assert(parse(Bytes.empty).isLeft)
  }
  test("storage authority paths reject traversal nesting and relative locations without I/O") {
    val store = Path.of("/state/store"); val receipts = Path.of("/authority/receipts")
    assert(
      NodeDurableReceipts.validatePaths(store, receipts, Some(receipts.resolve("ack.json"))).isRight
    )
    Vector(
      (Path.of("relative"), receipts, None),
      (store, store.resolve("receipts"), None),
      (store, Path.of("/state"), None),
      (store, receipts, Some(store.resolve("ack.json"))),
      (store, receipts, Some(Path.of("/authority/../ack.json")))
    )
      .foreach { (root, output, input) =>
        assert(NodeDurableReceipts.validatePaths(root, output, input).isLeft)
      }
  }
  test("receipt load rejects symlinks directories empty oversized and mismatched originals") {
    val base = Files.createTempDirectory("node-receipt-load-")
    val store = base.resolve("store")
    val real = base.resolve("ack.json"); Files.write(real, valid.toArray)
    val link = base.resolve("link.json"); Files.createSymbolicLink(link, real)
    val empty = base.resolve("empty.json"); Files.write(empty, Array.emptyByteArray)
    val large = base.resolve("large.json"); Files.write(large, Array.fill[Byte](4097)(0))
    val directory = Files.createDirectory(base.resolve("directory"))
    val parentLink = base.resolve("parent-link"); Files.createSymbolicLink(parentLink, base)
    val cases = Vector(
      link,
      empty,
      large,
      directory,
      parentLink.resolve("ack.json"),
      base.resolve("directory/../ack.json")
    )
    import cats.syntax.all.*
    (for
      good <- NodeDurableReceipts.load(real, hash(valid), token.contextId, store)
      _ <- cases.traverse_(p =>
        NodeDurableReceipts
          .load(p, hash(valid), token.contextId, store)
          .attempt
          .map(r => assert(r.isLeft))
      )
      wrong <- NodeDurableReceipts.load(real, hex("44" * 32), token.contextId, store).attempt
    yield
      assertEquals(good.token, token)
      assertEquals(good.reference.sha256, hash(valid))
      assert(wrong.isLeft)
    ).unsafeToFuture()
  }
  test("immutable pending and acknowledged records are distinct; repeated ack preserves identity") {
    val base = Files.createTempDirectory("node-receipt-record-")
    val store = base.resolve("store"); val receipts = base.resolve("receipts")
    def files =
      val listing = Files.list(receipts)
      try listing.toArray.toVector.map(_.asInstanceOf[Path])
      finally listing.close()
    (for
      _ <- NodeDurableReceipts.recordPending(
        receipts,
        store,
        CoherentSequence.PendingTokens(Some(token.copy(generation = 2)), token)
      )
      pending <- IO(files.head)
      rawPending <- IO(Bytes.fromArray(Files.readAllBytes(pending)))
      rejected <- NodeDurableReceipts
        .load(pending, hash(rawPending), token.contextId, store)
        .attempt
      first <- NodeDurableReceipts.recordAcknowledged(receipts, store, token)
      second <- NodeDurableReceipts.recordAcknowledged(receipts, store, token)
      saved <- NodeDurableReceipts.load(first.path, first.sha256, token.contextId, store)
    yield
      assert(rejected.isLeft)
      assertEquals(first, second)
      assertEquals(saved.token, token)
      assertEquals(files.size, 2)
      assert(Files.exists(pending))
    ).unsafeToFuture()
  }
  test("changed immutable destination is rejected and never overwritten") {
    val base = Files.createTempDirectory("node-receipt-conflict-")
    val store = base.resolve("store"); val receipts = base.resolve("receipts")
    (for
      first <- NodeDurableReceipts.recordAcknowledged(receipts, store, token)
      _ <- IO(Files.write(first.path, raw("preserve conflicting evidence").toArray))
      result <- NodeDurableReceipts.recordAcknowledged(receipts, store, token).attempt
      remaining <- IO(Files.readString(first.path))
    yield
      assert(result.isLeft)
      assertEquals(remaining, "preserve conflicting evidence")
    ).unsafeToFuture()
  }
  test("symlink receipt directory never writes through to a different location") {
    val base = Files.createTempDirectory("node-receipt-parent-")
    val actual = Files.createDirectory(base.resolve("actual"))
    val link = base.resolve("receipts"); Files.createSymbolicLink(link, actual)
    NodeDurableReceipts
      .recordAcknowledged(link, base.resolve("store"), token)
      .attempt
      .map { result =>
        assert(result.isLeft)
        val list = Files.list(actual)
        try assertEquals(list.count(), 0L)
        finally list.close()
      }
      .unsafeToFuture()
  }
