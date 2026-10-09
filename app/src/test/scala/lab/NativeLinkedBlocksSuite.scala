// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.header.PraosCertificateState as Certificate

/** Generated originals only: no retained database, native output, or live peer. */
class NativeLinkedBlocksSuite extends munit.FunSuite:
  private val F = SyntheticBoundaryCompositionFixture
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), value => value)
  private def b(n: Int) = Bytes(Vector.fill(32)(n.toByte))
  private def sha(raw: Bytes) = ClusterHeaderObservation.sha256(raw)
  private lazy val originals = F.blocks.map(_.original.block)
  private val anchor = Certificate.Point(F.anchorHash, 0, 0)
  private def terminal(raws: Vector[Bytes]): Certificate.Point =
    val h = get(lab.chain.CardanoBlockIndex.inspect(raws.last))
    Certificate.Point(h.headerHash, h.slot, h.blockNo)
  private def bind(
      raws: Vector[Bytes] = originals,
      start: Certificate.Point = anchor,
      end: Option[Certificate.Point] = None,
      pins: Option[Vector[Bytes]] = None,
      source: Bytes = F.pin,
      length: BigInt = F.epochLength
  ) =
    NativeLinkedBlocks.bind(
      start,
      end.getOrElse(terminal(raws)),
      length,
      source,
      raws,
      pins.getOrElse(raws.map(sha))
    )
  private def rewriteHeader(raw: Bytes, field: Int, replacement: Value): Bytes =
    def arr(n: Node) = n.value match
      case Value.Arr(xs) => xs
      case _             => fail("synthetic array required")
    def node(v: Value) = Node(v, Bytes.empty)
    val root = arr(get(Cbor.decode(raw)))
    val block = arr(root(1))
    val header = arr(block.head)
    val body = arr(header.head)
    val changedHeader = node(
      Value.Arr(header.updated(0, node(Value.Arr(body.updated(field, node(replacement))))))
    )
    get(Cbor.encode(Value.Arr(root.updated(1, node(Value.Arr(block.updated(0, changedHeader)))))))

  test("generated supplied originals preserve bytes and derive only the header envelope") {
    val checked = get(bind())
    assertEquals(checked.originals, originals)
    assertEquals(checked.blocks.map(_.original.block), originals)
    assertEquals(checked.blocks.map(_.header.raw), F.blocks.map(_.header.raw))
    assertEquals(checked.blocks.map(_.original.envelope), F.blocks.map(_.original.envelope))
    assertEquals(checked.sourcePins, originals.map(sha))
    assertEquals(checked.anchor, anchor)
    assertEquals(checked.terminal, terminal(originals))
    assert(checked.derivedHeaderEnvelope)
    assert(
      !checked.authenticatedSnapshot && !checked.actualAcquisitionVerified &&
        !checked.signaturesChecked && !checked.fullLedgerValidated &&
        !checked.nativeConformance && !checked.runtimeImport && !checked.rewardSeedAdmission
    )
  }
  test("independent ordered SHA pins are mandatory") {
    assert(bind(pins = Some(originals.map(sha).updated(0, b(3)))).isLeft)
    assert(bind(pins = Some(originals.map(sha).reverse)).isLeft)
    assert(bind(pins = Some(originals.map(sha).drop(1))).isLeft)
    assert(bind(pins = Some(originals.map(sha).updated(0, Bytes.empty))).isLeft)
  }
  test("coherently repinned gaps duplicate and reordered originals fail linkage") {
    assert(bind(raws = originals.patch(3, Vector.empty, 1)).isLeft)
    assert(bind(raws = originals.updated(3, originals(2))).isLeft)
    assert(bind(raws = originals.updated(2, originals(3)).updated(3, originals(2))).isLeft)
  }
  test("first successor must extend the full anchor including block number") {
    assert(bind(start = anchor.copy(hash = b(4))).isLeft)
    assert(bind(start = anchor.copy(blockNo = 1)).isLeft)
    assert(bind(start = anchor.copy(slot = 1)).isLeft)
    val changed = originals.updated(0, rewriteHeader(originals.head, 0, Value.UInt(2)))
    assert(bind(raws = changed).isLeft)
  }
  test("full terminal and ordered source identity are bound") {
    val end = terminal(originals)
    assert(bind(end = Some(end.copy(hash = b(4)))).isLeft)
    assert(bind(end = Some(end.copy(slot = end.slot + 1))).isLeft)
    assert(bind(end = Some(end.copy(blockNo = end.blockNo + 1))).isLeft)
    assertNotEquals(get(bind()).id, get(bind(source = b(5))).id)
    assert(bind(source = Bytes.empty).isLeft)
  }
  test("header and body tampering fails structural or body commitment checks with fresh pins") {
    val body = F.invalidBody(F.blocks.last).original.block
    assert(bind(raws = originals.updated(originals.size - 1, body)).isLeft)
    val bad = rewriteHeader(originals.last, 7, Value.ByteString(b(7)))
    assert(bind(raws = originals.updated(originals.size - 1, bad)).isLeft)
    val stale = rewriteHeader(originals.last, 1, Value.UInt(F.slots(F.slots.size - 2)))
    assert(bind(raws = originals.updated(originals.size - 1, stale)).isLeft)
    assert(
      bind(
        raws = originals.updated(originals.size - 1, Bytes(originals.last.value :+ 0.toByte)),
        end = Some(terminal(originals))
      ).isLeft
    )
  }
  test("epoch-zero to epoch-one geometry is required without a second boundary") {
    assert(bind(length = 0).isLeft)
    assert(bind(length = 100).isLeft)
    assert(bind(length = 20).isLeft)
    assert(bind(start = anchor.copy(slot = 40)).isLeft)
    val nextEpoch = rewriteHeader(originals.last, 1, Value.UInt(80))
    assert(bind(raws = originals.updated(originals.size - 1, nextEpoch)).isLeft)
  }
  test("hard block count individual bytes and aggregate limits precede parsing") {
    assert(
      NativeLinkedBlocks
        .bind(anchor, terminal(originals), 40, F.pin, Vector.empty, Vector.empty)
        .isLeft
    )
    assert(bind(raws = Vector.fill(129)(originals.head), end = Some(terminal(originals))).isLeft)
    val oversized = Bytes(Vector.fill(NativeLinkedBlocks.MaxBlockBytes + 1)(0.toByte))
    assert(bind(raws = Vector(oversized), end = Some(terminal(originals))).isLeft)
    val large = Bytes(Vector.fill(NativeLinkedBlocks.MaxBlockBytes)(0.toByte))
    val aggregate = bind(raws = Vector.fill(65)(large), end = Some(terminal(originals)))
    assert(aggregate.left.toOption.exists(_.contains("aggregate")))
  }
  test("a repinned altered signature remains supplied structure and changes identity") {
    val changed = F.invalidSignature(F.blocks.last).original.block
    val checked = get(bind(raws = originals.updated(originals.size - 1, changed)))
    assertNotEquals(checked.id, get(bind()).id)
    assert(!checked.signaturesChecked && !checked.runtimeImport && !checked.rewardSeedAdmission)
  }
