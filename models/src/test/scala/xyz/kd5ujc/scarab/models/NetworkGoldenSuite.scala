// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era, Handshake, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ChainSync.{
  Point as HandPoint,
  Role,
  State,
  Tip as HandTip,
  UInt64
}
import xyz.kd5ujc.scarab.ledger.{Block, Header}
import xyz.kd5ujc.scarab.models.chain.hfc
import xyz.kd5ujc.scarab.models.chainsync.ChainSyncMessage
import xyz.kd5ujc.scarab.models.handshake.{
  HandshakeMessage,
  MsgAcceptVersion,
  RefuseReason,
  VersionNumbers
}

/** The generated handshake and ChainSync codecs against the hand-written ones, on real and
  * constructed bytes.
  */
class NetworkGoldenSuite extends munit.FunSuite:

  // Handshake ---------------------------------------------------------------------------------------------

  /** One common shape for both implementations' results. */
  private enum Norm:
    case Propose(versions: Vector[(Int, Handshake.Data)])
    case Accept(version: Int, data: Handshake.Data)
    case Query(versions: Vector[(Int, Handshake.Data)])
    case Refuse(kind: Int, versions: Vector[Int], text: Option[String])

  private def data(v: handshake.v14.NodeToNodeVersionData): Handshake.Data =
    Handshake.Data(
      v.networkMagic.value,
      v.initiatorOnlyDiffusionMode.value,
      v.peerSharing.value == 1,
      v.query.value
    )

  private def data(v: handshake.v16.NodeToNodeVersionData): Handshake.Data =
    Handshake.Data(
      v.networkMagic.value,
      v.initiatorOnlyDiffusionMode.value,
      v.peerSharing.value == 1,
      v.query.value,
      v.perasSupport.value
    )

  private def table(t: handshake.VersionTable): Vector[(Int, Handshake.Data)] =
    (t.versionNumberV14ToV15.map((v, d) => v.value -> data(d)) ++
      t.versionNumberV16ToLast.map((v, d) => v.value -> data(d))).sortBy(_._1)

  private def number(v: VersionNumbers): Int = v match
    case VersionNumbers.V14ToV15(n)  => n.value
    case VersionNumbers.V16ToLast(n) => n.value

  private def normGenerated(m: HandshakeMessage): Norm = m match
    case HandshakeMessage.ProposeVersions(t) => Norm.Propose(table(t))
    case HandshakeMessage.QueryReply(t)      => Norm.Query(table(t))
    case HandshakeMessage.AcceptVersion(MsgAcceptVersion.V14ToV15(v, d)) =>
      Norm.Accept(v.value, data(d))
    case HandshakeMessage.AcceptVersion(MsgAcceptVersion.V16(v, d)) => Norm.Accept(v.value, data(d))
    case HandshakeMessage.Refuse(RefuseReason.VersionMismatch(vs)) =>
      Norm.Refuse(0, vs.map(number), None)
    case HandshakeMessage.Refuse(RefuseReason.HandshakeDecodeError(v, t)) =>
      Norm.Refuse(1, Vector(number(v)), Some(t))
    case HandshakeMessage.Refuse(RefuseReason.Refused(v, t)) =>
      Norm.Refuse(2, Vector(number(v)), Some(t))

  private def normHand(bytes: Bytes): Norm =
    val suite = Handshake.Suite.NodeToNode
    def dataOf(v: Int, t: Handshake.Term) =
      Handshake.decodeData(suite, v, t).fold(e => fail(e.message), identity)
    Handshake
      .decode(suite, Handshake.State.Confirm, bytes)
      .fold(e => fail(e.message), identity) match
      case Handshake.Message.Propose(vs)    => Norm.Propose(vs.map((v, t) => v -> dataOf(v, t)))
      case Handshake.Message.QueryReply(vs) => Norm.Query(vs.map((v, t) => v -> dataOf(v, t)))
      case Handshake.Message.Accept(v, t)   => Norm.Accept(v, dataOf(v, t))
      case Handshake.Message.Refuse(Handshake.Reason.VersionMismatch(vs, _)) =>
        Norm.Refuse(0, vs, None)
      case Handshake.Message.Refuse(Handshake.Reason.DecodeError(v, t)) =>
        Norm.Refuse(1, Vector(v), Some(t))
      case Handshake.Message.Refuse(Handshake.Reason.Refused(v, t)) =>
        Norm.Refuse(2, Vector(v), Some(t))

  private def checkHandshake(bytes: Bytes): Unit =
    val generated = HandshakeMessage.decodeBytes(bytes).fold(e => fail(e.render), identity)
    assertEquals(normGenerated(generated), normHand(bytes))
    // Canonical encoding of what was decoded gives the same bytes (definite, shortest, ascending versions).
    assertEquals(HandshakeMessage.encodeUnchecked(generated), bytes)

  for name <- Vector(
      "ntn-accept14-preview",
      "scalus-ntn-propose14",
      "scalus-ntn-accept16",
      "scalus-ntn-refuse"
    )
  do
    test(
      s"fixtures/network/$name.hex decodes like the hand-written handshake and re-encodes identically"
    ) {
      Fixtures.hexLines(s"fixtures/network/$name.hex").foreach(checkHandshake)
    }

  test(
    "handshake messages built by the hand-written side decode identically (versions 14, 15, 16)"
  ) {
    val suite = Handshake.Suite.NodeToNode
    val offers = Vector(
      14 -> Handshake.Data(2),
      15 -> Handshake.Data(764824073, initiatorOnly = false, peerSharing = true, query = false),
      16 -> Handshake.Data(1, initiatorOnly = true, peerSharing = false, query = true, peras = true)
    )
    val propose = Handshake.proposal(suite, offers).fold(e => fail(e.message), identity)
    checkHandshake(Handshake.encode(suite, propose).fold(e => fail(e.message), identity))
    val local = Handshake.proposal(suite, offers).toOption.get
    val Handshake.Message.Propose(vs) = local: @unchecked
    checkHandshake(
      Handshake.encode(suite, Handshake.Message.QueryReply(vs)).fold(e => fail(e.message), identity)
    )
    val accept = Handshake.Message.Accept(
      16,
      Handshake
        .dataTerm(suite, 16, Handshake.Data(1, peras = true))
        .fold(e => fail(e.message), identity)
    )
    checkHandshake(Handshake.encode(suite, accept).fold(e => fail(e.message), identity))
    val decodeError = Handshake.Message.Refuse(Handshake.Reason.DecodeError(14, "bad data"))
    checkHandshake(Handshake.encode(suite, decodeError).fold(e => fail(e.message), identity))
  }

  test("handshake: an unknown version number is a typed error naming the version and its place") {
    // [0, {13: [2, true, 0, false]}]: 13 is below the supported range.
    val bytes = Bytes.fromHex("8200a10d8402f500f4").toOption.get
    val err = HandshakeMessage.decodeBytes(bytes).left.toOption.getOrElse(fail("expected an error"))
    import xyz.kd5ujc.scarab.models.runtime.DecodeError.Kind
    err.deepest.kind match
      case Kind.UnexpectedKey(_) | Kind.NoAlternative(_) | Kind.OutOfRange(_, _) => ()
      case other => fail(s"unexpected kind $other")
    assert(err.offset >= 0)
  }

  // ChainSync -----------------------------------------------------------------------------------------------

  private val conwayBlocks = Fixtures.hexLines("fixtures/preview/conway.hex")
  private val headers: Vector[NtNHeader] =
    conwayBlocks.map(b => NtNHeader(Era.Conway, Fixtures.headerBytes(b), None))

  private def handBytes(state: State, sender: Role, msg: ChainSync.Message[NtNHeader]): Bytes =
    ChainSync.encode(state, sender, msg, NtNHeader.codec).fold(e => fail(e.message), identity)

  private def point(slot: BigInt, hash: Bytes): HandPoint =
    HandPoint.Block(UInt64.saturating(slot), hash)
  private def tip(p: HandPoint, n: BigInt): HandTip = HandTip(p, UInt64.saturating(n))

  private def genPoint(p: chain.Point): HandPoint = p match
    case chain.Point.Origin            => HandPoint.Origin
    case chain.Point.Block(slot, hash) => HandPoint.Block(UInt64.saturating(slot.value), hash.value)

  private def genTip(t: common.Tip): HandTip =
    HandTip(genPoint(t.point), UInt64.saturating(t.blockno.value))

  test(
    "RollForward of real Conway headers: era, preserved header bytes, hash, point and tip agree"
  ) {
    Fixtures.hexLines("fixtures/preview/conway.hex").zip(headers).foreach { (block, ntn) =>
      val handBlock = Block.decodeShelley(block).toOption.get.header
      val tipPoint = point(handBlock.slot + 7, Blake2b.hash256.hash(ntn.raw))
      val bytes = handBytes(
        State.NextCanAwait,
        Role.Server,
        ChainSync.Message.RollForward(ntn, tip(tipPoint, handBlock.blockNo + 7))
      )
      val message = ChainSyncMessage.decodeBytes(bytes).fold(e => fail(e.render), identity)
      message match
        case ChainSyncMessage.RollForward(hfc.Header.Conway(h), t) =>
          assertEquals(h.raw, ntn.raw)
          assertEquals(Blake2b.hash256.hash(h.raw), handBlock.hash)
          assertEquals(h.headerBody.slot.value, handBlock.slot)
          assertEquals(genTip(t), tip(tipPoint, handBlock.blockNo + 7))
        case other => fail(s"unexpected $other")
      // Re-encoding keeps the header's original bytes: the whole message is reproduced.
      assertEquals(ChainSyncMessage.encodeUnchecked(message), bytes)
    }
  }

  test("the other ChainSync messages decode like the hand-written ones") {
    val h = Blake2b.hash256.hash(headers.head.raw)
    val p = point(1234, h)
    val t = tip(point(99999, h), 4242)
    def decode(bytes: Bytes) =
      ChainSyncMessage.decodeBytes(bytes).fold(e => fail(e.render), identity)

    assertEquals(
      decode(handBytes(State.Idle, Role.Client, ChainSync.Message.RequestNext)),
      ChainSyncMessage.RequestNext
    )
    assertEquals(
      decode(handBytes(State.NextCanAwait, Role.Server, ChainSync.Message.AwaitReply)),
      ChainSyncMessage.AwaitReply
    )
    assertEquals(
      decode(handBytes(State.Idle, Role.Client, ChainSync.Message.Done)),
      ChainSyncMessage.Done
    )

    decode(handBytes(State.NextCanAwait, Role.Server, ChainSync.Message.RollBackward(p, t))) match
      case ChainSyncMessage.RollBackward(gp, gt) => assertEquals((genPoint(gp), genTip(gt)), (p, t))
      case other                                 => fail(s"unexpected $other")
    decode(handBytes(State.Intersect, Role.Server, ChainSync.Message.IntersectFound(p, t))) match
      case ChainSyncMessage.IntersectFound(gp, gt) =>
        assertEquals((genPoint(gp), genTip(gt)), (p, t))
      case other => fail(s"unexpected $other")
    decode(handBytes(State.Intersect, Role.Server, ChainSync.Message.IntersectNotFound(t))) match
      case ChainSyncMessage.IntersectNotFound(gt) => assertEquals(genTip(gt), t)
      case other                                  => fail(s"unexpected $other")
    // Origin tips and points: the empty array.
    decode(
      handBytes(
        State.Intersect,
        Role.Server,
        ChainSync.Message.IntersectFound(HandPoint.Origin, HandTip.Origin)
      )
    ) match
      case ChainSyncMessage.IntersectFound(gp, gt) =>
        assertEquals((genPoint(gp), genTip(gt)), (HandPoint.Origin, HandTip.Origin))
      case other => fail(s"unexpected $other")
    // FindIntersect: the hand-written writer (like the Haskell node) uses an indefinite-length list; the
    // generated decoder accepts it and re-encodes canonically (definite), which is a different, equivalent encoding.
    val candidates = Vector(p, HandPoint.Origin, point(5, h))
    val find = handBytes(State.Idle, Role.Client, ChainSync.Message.FindIntersect(candidates))
    decode(find) match
      case m @ ChainSyncMessage.FindIntersect(ps) =>
        assertEquals(ps.map(genPoint), candidates)
        assertNotEquals(ChainSyncMessage.encodeUnchecked(m), find)
        assertEquals(decode(ChainSyncMessage.encodeUnchecked(m)), m)
      case other => fail(s"unexpected $other")
  }

  test(
    "RollForward of a real Byron header (the generated byron module) decodes with its original bytes"
  ) {
    val block = Fixtures.hexLines("fixtures/mainnet/byron-main-blocks.hex").head
    val hand = Block.decode(block, BigInt(21600)).fold(e => fail(e.message), identity) match
      case b: Block.Byron => b
      case other          => fail(s"unexpected $other")
    val ntn = NtNHeader(
      Era.Byron,
      hand.header.raw,
      Some(NtNHeader.ByronInfo(boundary = false, sizeHint = BigInt(hand.size)))
    )
    val bytes =
      handBytes(State.NextCanAwait, Role.Server, ChainSync.Message.RollForward(ntn, HandTip.Origin))
    ChainSyncMessage.decodeBytes(bytes).fold(e => fail(e.render), identity) match
      case ChainSyncMessage.RollForward(hfc.Header.Byron(hfc.ByronHeader.Regular(_, head)), _) =>
        assertEquals(head.raw, hand.header.raw)
      case other => fail(s"unexpected $other")
  }

  test(
    "ChainSync: a message with the wrong tag or arity is a typed error listing every alternative tried"
  ) {
    import xyz.kd5ujc.scarab.models.runtime.DecodeError.Kind
    val err = ChainSyncMessage.decodeBytes(Bytes.fromHex("820905").toOption.get).left.toOption.get
    err.kind match
      case Kind.NoAlternative(causes) => assertEquals(causes.size, 8)
      case other                      => fail(s"unexpected $other")
    val _ = (Files.exists(Path.of(".")), Header)
  }
