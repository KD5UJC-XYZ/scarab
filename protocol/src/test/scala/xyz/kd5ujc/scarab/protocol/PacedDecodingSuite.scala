// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.util.concurrent.atomic.AtomicInteger
import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.{DecodeResult, Role}

/** A message whose SDUs a peer paces, so that the multiplexer decodes after every one: each decoder
  * of the node's mini-protocols resumes from its cursor and reads none of the bytes it read whole
  * again ([[Paced.poisoned]]), and answers on every prefix as when started afresh on it. The same
  * checks on a decoder that reads each prefix from its start, as the multiplexer's receive did
  * before it kept every decoder's cursor, fail.
  */
class PacedDecodingSuite extends munit.FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)
  private def bytes(parts: Bytes*): Bytes = parts.foldLeft(Bytes.empty)(_ ++ _)
  private def repeat(part: String, times: Int): Bytes = hex(part * times)

  /** The verdicts of `decoder` on `message` paced one byte at a time after `head` bytes, the bytes
    * of `[from, until)` poisoned once read until the item they are in is whole at `whole`: they
    * must be those of the decoder started afresh on each true prefix, and those of the decoder that
    * reads each prefix from its start must not.
    */
  private def readsOnce[A](
      decoder: IncrementalDecoder[A],
      message: Bytes,
      head: Int,
      from: Int,
      until: Int,
      whole: Int = Int.MaxValue
  )(using munit.Location): DecodeResult[A] =
    val inputs = Paced.poisoned(message, head, from, until, whole.min(message.size))
    val expected = Paced.afresh(decoder, message, Paced.dribbled(message, head))
    val found = Paced.resumedOn(decoder, inputs)
    assertEquals(found.size, expected.size, "the decoder stopped at another prefix")
    assertEquals(found, expected, "a resumed decoder answered otherwise than started afresh")
    assertNotEquals(
      Paced.resumedOn(Paced.restarting(decoder), inputs),
      expected,
      "a decoder that reads each prefix from its start did not read the bytes it had read"
    )
    expected.lastOption.getOrElse(fail("no verdict"))

  private final class CountingReader extends TxSubmission2.TransactionReader:
    val reads = new AtomicInteger(0)
    def body(original: Bytes): Either[ProtocolError, Bytes] = Right(original)
    def read(original: Bytes): Either[ProtocolError, Bytes] =
      reads.incrementAndGet()
      Right(original)

  // [2, 1, 24(bytes)] of 2,000 bytes: one transaction of MsgReplyTxs
  private val transaction = bytes(hex("8206d81859"), hex("07d0"), repeat("01", 2000))

  /** MsgReplyTxs of `whole` transactions then one more, as `82 03 9f ... ff`, and where the last
    * transaction starts.
    */
  private def replyTxs(whole: Int): (Bytes, Int) =
    val head = bytes((hex("82039f") +: Vector.fill(whole)(transaction))*)
    (bytes(head, transaction, hex("ff")), head.size)

  test("h-1: MsgReplyTxs paced after seven whole transactions reads each transaction once") {
    val (message, head) = replyTxs(7)
    val reader = new CountingReader
    val codec = TxSubmission2.eraCodec(reader)
    val decoder = codec.incremental(TxSubmission2.State.Txs, Role.Client)
    readsOnce(decoder, message, head, 3, head) match
      case DecodeResult.Decoded(TxSubmission2Engine.Message.ReplyTxs(txs), consumed) =>
        assertEquals(txs.size, 8)
        assertEquals(consumed, message.size)
      case other => fail(s"not the reply: $other")
    val paced = new CountingReader
    val sdus = Paced.dribbled(message, head)
    val verdict = Paced.resumed(
      TxSubmission2.eraCodec(paced).incremental(TxSubmission2.State.Txs, Role.Client),
      message,
      sdus
    )
    assertEquals(verdict.size, sdus.size, "every SDU but the last left the message incomplete")
    assertEquals(paced.reads.get, 8, "each transaction read once, over all the SDUs")
    val restarted = new CountingReader
    Paced.resumed(
      Paced.restarting(
        TxSubmission2.eraCodec(restarted).incremental(TxSubmission2.State.Txs, Role.Client)
      ),
      message,
      sdus
    )
    assert(
      restarted.reads.get >= 7 * (sdus.size - 1),
      s"a decoder read from the start reads the whole transactions on every SDU: ${restarted.reads.get}"
    )
  }

  test("h-1: MsgReplyTxs refusals and verdicts are the same paced and whole") {
    val reader = new CountingReader
    val decoder = TxSubmission2.eraCodec(reader).incremental(TxSubmission2.State.Txs, Role.Client)
    val (one, _) = replyTxs(1)
    val (nine, _) = replyTxs(8)
    val messages = Vector(one, replyTxs(3)._1, nine, hex("820380"), hex("82039f8206d818"))
    assertEquals(Paced.differences(decoder, messages, 1L), Vector.empty)
    assertEquals(
      decoder.decodePrefix(nine),
      DecodeResult.Failed(ProtocolError.BatchLimitExceeded(8, 8))
    )
    // the ninth transaction is refused paced as whole, once eight are held
    assertEquals(
      Paced.resumed(decoder, nine, Paced.dribbled(nine, 3)).lastOption,
      Some(DecodeResult.Failed(ProtocolError.BatchLimitExceeded(8, 8)))
    )
  }

  // [0, {1: [_ 1, 1, ...], 14: [1, false, 0, false]}]: an unknown version whose data is a long
  // indefinite array, and version 14
  private val proposal =
    bytes(hex("8200a2019f"), repeat("01", 5000), hex("ff0e8401f400f4"))

  test("d-1: a handshake proposal paced one byte an SDU reads each head once") {
    val decoder = Handshake.incremental(Handshake.Suite.NodeToNode, Handshake.State.Propose)
    readsOnce(decoder, proposal, 4000, 6, 3990) match
      case DecodeResult.Decoded(Handshake.Message.Propose(versions), consumed) =>
        // the codec keeps the versions it recognizes
        assertEquals(versions.map(_._1), Vector(14))
        assertEquals(consumed, proposal.size)
      case other => fail(s"not the proposal: $other")
  }

  test("d-1: chunked strings, nested containers and replies are read once, as whole") {
    // an indefinite text of empty chunks, nested arrays, tags, and a refusal's reason
    val chunks = bytes(hex("8200a2017f"), repeat("60", 3000), hex("ff0e8401f400f4"))
    val nested = bytes(hex("8200a201"), repeat("81", 2000), hex("00"), hex("0e8401f400f4"))
    val tags = bytes(hex("8200a201"), repeat("c1", 2000), hex("00"), hex("0e8401f400f4"))
    val refuse = bytes(hex("82028300"), hex("9f"), repeat("0e", 2000), hex("ff"), hex("00"))
    val propose = Handshake.incremental(Handshake.Suite.NodeToNode, Handshake.State.Propose)
    val confirm = Handshake.incremental(Handshake.Suite.NodeToNode, Handshake.State.Confirm)
    readsOnce(propose, chunks, 1000, 6, 990)
    readsOnce(propose, nested, 1000, 5, 990)
    readsOnce(propose, tags, 1000, 5, 990)
    readsOnce(confirm, refuse, 1000, 6, 990)
    assertEquals(
      Paced.differences(propose, Vector(proposal, chunks, nested, tags), 2L),
      Vector.empty
    )
    assertEquals(Paced.differences(confirm, Vector(refuse, proposal), 3L), Vector.empty)
    // past 5760 bytes the handshake's limit refuses it, paced as whole
    val long = bytes(hex("8200a2019f"), repeat("01", 6000), hex("ff0e8401f400f4"))
    assertEquals(
      Paced.resumed(propose, long, Paced.dribbled(long, 1)).lastOption,
      Some(propose.decodePrefix(long))
    )
    assertEquals(
      propose.decodePrefix(long),
      DecodeResult.Failed(ProtocolError.HandshakeByteLimitExceeded)
    )
  }

  // [1, [_ [0, 0, 0] * 1439]]: 5,760 bytes of three-byte addresses
  private val sharePeers = bytes(hex("82019f"), repeat("83000000", 1439), hex("ff"))

  test("h-2: MsgSharePeers paced one byte an SDU reads each address once") {
    val decoder = PeerSharing.incremental(PeerSharing.State.Busy, PeerSharing.Role.Server)
    readsOnce(decoder, sharePeers, 3 + 4 * 1300, 3, 3 + 4 * 1300) match
      case DecodeResult.Decoded(PeerSharing.Message.SharePeers(peers), consumed) =>
        assertEquals(peers.size, 1439)
        assertEquals(consumed, sharePeers.size)
      case other => fail(s"not the peers: $other")
    val definite = bytes(hex("8201990064"), repeat("83000000", 100))
    val messages = Vector(sharePeers, definite, hex("82019b8000000000000000"), hex("8200"))
    assertEquals(Paced.differences(decoder, messages, 4L), Vector.empty)
  }

  private final class CountingPayload extends ChainSync.PayloadCodec[Bytes]:
    val decodes = new AtomicInteger(0)
    def encode(value: Bytes): Either[ProtocolError, Bytes] = Right(value)
    def decode(bytes: Bytes): Either[ProtocolError, Bytes] =
      decodes.incrementAndGet()
      ChainSyncFixtures.rawItem.decode(bytes)

  // [2, header, tip]: a header of 8,000 small integers, and a tip
  private val tip = hex("82820158" + "20" + "aa" * 32 + "01")
  private val rollForward = bytes(hex("83029f"), repeat("01", 8000), hex("ff"), tip)

  private def chainSync(
      state: ChainSync.State,
      sender: Role,
      payload: ChainSync.PayloadCodec[Bytes]
  ) =
    ChainSyncEngine.incremental(
      state,
      sender,
      ChainSync.enginePointCodec,
      ChainSync.engineTipCodec,
      ChainSync.enginePayloadCodec(payload),
      ChainSync.Limits()
    )

  test("h-3: MsgRollForward paced one byte an SDU reads each head of the header once") {
    val payload = new CountingPayload
    val decoder = chainSync(ChainSync.State.NextCanAwait, Role.Server, payload)
    readsOnce(decoder, rollForward, 4003, 3, 3990, whole = 8004) match
      case DecodeResult.Decoded(ChainSyncEngine.Message.RollForward(header, _), consumed) =>
        assertEquals(header.size, 8002)
        assertEquals(consumed, rollForward.size)
      case other => fail(s"not the header: $other")
    // the header is decoded once, though its tip arrives a byte an SDU after it
    val counted = new CountingPayload
    val sdus = Paced.dribbled(rollForward, 8005)
    Paced.resumed(chainSync(ChainSync.State.NextCanAwait, Role.Server, counted), rollForward, sdus)
    assertEquals(counted.decodes.get, 1)
    val restarted = new CountingPayload
    Paced.resumed(
      Paced.restarting(chainSync(ChainSync.State.NextCanAwait, Role.Server, restarted)),
      rollForward,
      sdus
    )
    assertEquals(restarted.decodes.get, sdus.size)
  }

  test("h-3: ChainSync's messages and refusals are the same paced and whole") {
    val point = hex("820158" + "20" + "bb" * 32)
    val findIntersect = bytes((hex("82049f") +: Vector.fill(64)(point))*) ++ hex("ff")
    val tooMany = bytes((hex("82049f") +: Vector.fill(65)(point))*) ++ hex("ff")
    val idle = chainSync(ChainSync.State.Idle, Role.Client, ChainSyncFixtures.rawItem)
    readsOnce(idle, findIntersect, 3 + 36 * 30, 3, 3 + 36 * 30) match
      case DecodeResult.Decoded(ChainSyncEngine.Message.FindIntersect(points), _) =>
        assertEquals(points.size, 64)
      case other => fail(s"not the points: $other")
    assertEquals(
      idle.decodePrefix(tooMany),
      DecodeResult.Failed(ProtocolError.CandidateLimitExceeded)
    )
    assertEquals(Paced.differences(idle, Vector(findIntersect, tooMany), 5L), Vector.empty)
    val next = chainSync(ChainSync.State.NextCanAwait, Role.Server, ChainSyncFixtures.rawItem)
    val deep = bytes(hex("8302"), repeat("81", 30), hex("00"), tip)
    val strings = bytes(hex("83025f"), repeat("41aa", 2000), hex("ff"), tip)
    assertEquals(Paced.differences(next, Vector(rollForward, deep, strings), 6L), Vector.empty)
    readsOnce(next, strings, 2003, 3, 1990, whole = 4004)
  }

  // [7, item]: a HasTx whose id is a long array
  private val hasTx = bytes(hex("82079f"), repeat("01", 8000), hex("ff"))

  test("h-4: LocalTxMonitor's MsgHasTx paced one byte an SDU reads each head once") {
    val decoder = LocalTxMonitor.incremental(LocalTxMonitor.State.Acquired, Role.Client)
    readsOnce(decoder, hasTx, 6003, 3, 5990) match
      case DecodeResult.Decoded(LocalTxMonitor.Message.HasTx(id), consumed) =>
        assertEquals(id.size, 8002)
        assertEquals(consumed, hasTx.size)
      case other => fail(s"not the id: $other")
    assertEquals(Paced.differences(decoder, Vector(hasTx, hex("820782")), 7L), Vector.empty)
  }

  test("h-4: LocalStateQuery's MsgQuery paced one byte an SDU reads each head once") {
    val query = bytes(hex("82039f"), repeat("01", 8000), hex("ff"))
    val decoder = LocalStateQueryEngine.incremental(
      LocalStateQuery.State.Acquired,
      Role.Client,
      ChainSync.enginePointCodec,
      LocalStateQuery.QueryReader.items
    )
    readsOnce(decoder, query, 6003, 3, 5990) match
      case DecodeResult.Decoded(LocalStateQueryEngine.Message.Query(q), consumed) =>
        assertEquals(q.size, 8002)
        assertEquals(consumed, query.size)
      case other => fail(s"not the query: $other")
    assertEquals(Paced.differences(decoder, Vector(query), 8L), Vector.empty)
  }

  test("h-4: LocalTxMonitor's ReplyGetMeasures paced one byte an SDU reads each entry once") {
    import scala.collection.immutable.SortedMap
    val decoder = LocalTxMonitor.incremental(
      LocalTxMonitor.State.Busy(LocalTxMonitor.Kind.GetMeasures),
      Role.Server
    )
    val entries =
      (0 until 300).map(k => f"m$k%04d" -> LocalTxMonitor.Measure(BigInt(k), BigInt(2).pow(70) + k))
    val message = LocalTxMonitor
      .encode(
        LocalTxMonitor.State.Busy(LocalTxMonitor.Kind.GetMeasures),
        Role.Server,
        LocalTxMonitor.Message.ReplyGetMeasures(LocalTxMonitor.Measures(7, SortedMap.from(entries)))
      )
      .fold(e => fail(e.toString), identity)
    val whole = message.size - 30 * 25
    readsOnce(decoder, message, whole, 8, whole - 30) match
      case DecodeResult.Decoded(LocalTxMonitor.Message.ReplyGetMeasures(measures), consumed) =>
        assertEquals(measures.byName.size, 300)
        assertEquals(consumed, message.size)
      case other => fail(s"not the measures: $other")
    // one entry: its size an integer of n bytes, then its capacity of n bytes, a byte an SDU; the
    // size is read once, where it was copied again on every byte of the capacity
    def run(n: Int): Unit =
      val big = "c259" + f"$n%04x" + "07" * n
      val one = hex("830c07a1" + "616d" + "82" + big + big)
      val verdicts = Paced.resumed(decoder, one, Paced.dribbled(one, one.size - n - 4))
      assert(
        verdicts.lastOption.exists(_.isInstanceOf[DecodeResult.Decoded[?]]),
        verdicts.lastOption
      )
    val growth = Paced.growth(2000)(run)
    assert(growth < 8.0, s"allocation grew $growth times for an integer four times as long")
  }

  test("KeepAlive and BlockFetch answer paced as whole") {
    val keepAlive = KeepAlive.incremental(KeepAlive.State.Client, KeepAlive.Role.Client)
    assertEquals(
      Paced.differences(keepAlive, Vector(hex("8200190102"), hex("8102"), hex("820019")), 9L),
      Vector.empty
    )
    val block = bytes(hex("8204d81859"), hex("2710"), repeat("00", 10000))
    val fetch = BlockFetchEngine.incremental(
      BlockFetchEngine.State.Streaming,
      BlockFetchEngine.Role.Server,
      CardanoBlockFetch.pointCodec,
      CardanoBlockFetch.payloadCodec(),
      BlockFetch.Limits()
    )
    assertEquals(Paced.differences(fetch, Vector(block), 10L), Vector.empty)
  }

  /** Run `body` on a thread whose stack (256 KiB) a recursion over thousands of levels overflows.
    */
  private def onSmallStack[A](body: => A): A =
    val outcome = new java.util.concurrent.CompletableFuture[A]()
    val thread = new Thread(
      null,
      () =>
        try outcome.complete(body)
        catch case cause: Throwable => outcome.completeExceptionally(cause)
        ()
      ,
      "paced-small-stack",
      256 * 1024
    )
    thread.start()
    thread.join()
    outcome.join()

  test("nestings thousands of levels deep, paced, are resumed on a small stack (STK-3)") {
    val deepProposal = bytes(hex("8200a201"), repeat("81", 5000), hex("00"), hex("0e8401f400f4"))
    val deepId = bytes(hex("8207"), repeat("81", 60000), hex("00"))
    val handshake = Handshake.incremental(Handshake.Suite.NodeToNode, Handshake.State.Propose)
    val monitor = LocalTxMonitor.incremental(LocalTxMonitor.State.Acquired, Role.Client)
    val (proposed, held) = onSmallStack(
      (
        Paced.resumed(handshake, deepProposal, Paced.dribbled(deepProposal, 1)).lastOption,
        Paced.resumed(monitor, deepId, (2 to deepId.size by 97).toVector :+ deepId.size).lastOption
      )
    )
    assertEquals(proposed, Some(handshake.decodePrefix(deepProposal)))
    assertEquals(held, Some(monitor.decodePrefix(deepId)))
    assert(held.exists(_.isInstanceOf[DecodeResult.Decoded[?]]), held.toString)
  }
