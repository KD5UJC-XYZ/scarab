// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.DecodeResult
import LeiosWord.Word32

/** The incremental LeiosFetch decoder gives the verdict of [[LeiosFetch.decodePrefix]] on the whole
  * message, however the message is cut into the parts it arrives in, with its cursor carried from
  * each part to the next.
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
