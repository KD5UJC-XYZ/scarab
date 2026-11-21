// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.DecodeResult
import LeiosWord.{Word16, Word32, Word64}

/** The incremental LeiosFetch decoder gives the verdict of [[LeiosFetch.decodePrefix]] on the whole
  * message, however the message is cut into the parts it arrives in, with its cursor carried from
  * each part to the next; and it refuses a message as soon as the token the codec refuses has
  * arrived, before the rest of the message, as cborg's incremental decoder does (#54).
  */
class LeiosFetchIncrementalSuite extends ScalaCheckSuite:
  private def word32(n: Long): Word32 = Word32.from(BigInt(n)).fold(e => fail(e.toString), identity)
  private def hash(i: Int): LeiosTxHash = LeiosTxHash.fromBytes(Bytes(Vector.fill(32)(i.toByte)))

  /** The decoder fed every longer prefix of `bytes` at the cuts, as the multiplexer feeds it. */
  private def fed(
      state: LeiosFetch.State,
      bytes: Bytes,
      cuts: Vector[Int]
  ): DecodeResult[LeiosFetch.Message] =
    val decoder = LeiosFetch.incremental(state)
    val ends = (cuts.filter(c => c > 0 && c < bytes.size).sorted.distinct :+ bytes.size)
    ends
      .foldLeft[(decoder.Cursor, Option[DecodeResult[LeiosFetch.Message]])](
        (decoder.initial, None)
      ) {
        case ((cursor, None), end) =>
          decoder.resume(cursor, bytes.take(end)) match
            case (next, DecodeResult.NeedMore) => (next, None)
            case (next, done)                  => (next, Some(done))
        case (done, _) => done
      }
      ._2
      .getOrElse(DecodeResult.NeedMore)

  /** The verdict after each of the prefixes `ends` of `bytes`, the cursor carried from each to the
    * next.
    */
  private def verdicts(
      state: LeiosFetch.State,
      bytes: Bytes,
      ends: Vector[Int]
  ): Vector[DecodeResult[LeiosFetch.Message]] =
    val decoder = LeiosFetch.incremental(state)
    ends
      .foldLeft((decoder.initial, Vector.empty[DecodeResult[LeiosFetch.Message]])) {
        case ((cursor, seen), end) =>
          val (next, verdict) = decoder.resume(cursor, bytes.take(end))
          (next, seen :+ verdict)
      }
      ._2

  private def refused(error: LeiosWireError): DecodeResult[LeiosFetch.Message] =
    DecodeResult.Failed(ProtocolError.Leios(error))

  private def bytes(values: Int*): Bytes = Bytes(values.toVector.map(_.toByte))
  private def encoded(message: LeiosFetch.Message): Bytes =
    LeiosFetch.encode(message).fold(e => fail(e.toString), identity)

  test("an Idle frame the server does not admit is refused before its body arrives") {
    // `[2, ...]` with the key of `MsgLeiosBlock`, which the client never sends: refused on the key,
    // its body never sent
    assertEquals(
      verdicts(LeiosFetch.State.Idle, bytes(0x82, 0x02), Vector(1, 2)),
      Vector(DecodeResult.NeedMore, refused(LeiosWireError.NotAdmitted(2)))
    )
    // `MsgLeiosBlockRequest` in a list of three: refused on its key, before its point
    assertEquals(
      verdicts(LeiosFetch.State.Idle, bytes(0x83, 0x01), Vector(1, 2)),
      Vector(DecodeResult.NeedMore, refused(LeiosWireError.WrongArity(1, 1, 2)))
    )
    // an unknown key whose head comes in two parts
    assertEquals(
      verdicts(LeiosFetch.State.Idle, bytes(0x82, 0x18, 0x07), Vector(1, 2, 3)),
      Vector(
        DecodeResult.NeedMore,
        DecodeResult.NeedMore,
        refused(LeiosWireError.UnknownTag(BigInt(7)))
      )
    )
    // an indefinite list is refused on its first byte (`decodeListLen`)
    assertEquals(
      verdicts(LeiosFetch.State.Idle, bytes(0x9f), Vector(1)),
      Vector(refused(LeiosWireError.NotAnArray))
    )
  }

  test("a field the codec refuses fails the message once it arrives, before the rest") {
    def word16(n: Int) = Word16.from(BigInt(n)).fold(e => fail(e.toString), identity)
    def word64(n: Long) = Word64.from(BigInt(n)).fold(e => fail(e.toString), identity)
    val point = EbPoint(word64(5), EbHash.fromBytes(Bytes(Vector.fill(32)(3))))
    // a request whose second window is not above the first: refused on that window's bitmap, with
    // more windows and the map's break still to come
    val request = encoded(
      LeiosFetch.Message.BlockTxsRequest(
        point,
        TxBitmaps(Vector(word16(4) -> word64(1), word16(4) -> word64(2), word16(9) -> word64(1)))
      )
    )
    // the second window's bitmap ends where the third window (two bytes) and the break are all
    // that is left
    val second = request.size - 3
    assertEquals(
      verdicts(LeiosFetch.State.Idle, request, Vector(second - 1, second)),
      Vector(DecodeResult.NeedMore, refused(LeiosWireError.BitmapWindowsNotAscending(3, 4)))
    )
    // an answer whose second transaction is not `#6.24(bytes)`: refused on its head, before the
    // transactions after it
    val good = Bytes(Vector.fill(20)(1))
    val answer = encoded(LeiosFetch.Message.BlockTxs(Vector(good, good, good)))
    val at = 3 + (2 + good.size + 1)
    val broken = answer.take(at) ++ bytes(0x40) ++ answer.drop(at + 1)
    assertEquals(
      verdicts(LeiosFetch.State.BlockTxs, broken, Vector(at, at + 1)),
      Vector(DecodeResult.NeedMore, refused(LeiosWireError.BadTransaction(1)))
    )
  }

  property("every proper prefix of a message waits for more, wherever it is cut") {
    Prop.forAll(Gen.choose(1, 40), Gen.listOf(Gen.choose(1, 2000))) { (n, cuts) =>
      val message =
        LeiosFetch.Message.Block(Vector.tabulate(n)(i => hash(i % 256) -> word32(i.toLong + 1)))
      val bytes = encoded(message)
      val ends = cuts.filter(_ < bytes.size).sorted.distinct.toVector
      verdicts(LeiosFetch.State.Block, bytes, ends).forall(_ == DecodeResult.NeedMore) &&
      verdicts(LeiosFetch.State.Block, bytes, ends :+ bytes.size).lastOption.contains(
        DecodeResult.Decoded(message, bytes.size)
      )
    }
  }

  property("a body arriving in parts decodes as the whole") {
    Prop.forAll(Gen.choose(1, 300), Gen.listOf(Gen.choose(0, 12000))) { (n, cuts) =>
      val message =
        LeiosFetch.Message.Block(Vector.tabulate(n)(i => hash(i % 256) -> word32(i.toLong + 1)))
      val bytes = LeiosFetch.encode(message).fold(e => fail(e.toString), identity)
      fed(LeiosFetch.State.Block, bytes, cuts.toVector) == DecodeResult.Decoded(message, bytes.size)
    }
  }

  test(
    "transactions arriving one byte at a time decode as the whole, and a refusal is the codec's"
  ) {
    val message = LeiosFetch.Message.BlockTxs(
      Vector.tabulate(40)(i => Bytes(Vector.fill(i + 1)((i + 1).toByte)))
    )
    val bytes = LeiosFetch.encode(message).fold(e => fail(e.toString), identity)
    assertEquals(
      fed(LeiosFetch.State.BlockTxs, bytes, Vector.range(1, bytes.size)),
      DecodeResult.Decoded(message, bytes.size)
    )
    // a Block answer where BlockTxs is awaited is refused as decodePrefix refuses it
    val block = LeiosFetch
      .encode(LeiosFetch.Message.Block(Vector(hash(1) -> word32(5))))
      .fold(e => fail(e.toString), identity)
    val expected =
      LeiosFetch.decodePrefix(LeiosFetch.State.BlockTxs, block).left.map(ProtocolError.Leios(_))
    assertEquals(
      fed(LeiosFetch.State.BlockTxs, block, Vector(3, 10)),
      expected.fold(DecodeResult.Failed(_), (m, c) => DecodeResult.Decoded(m, c))
    )
  }
