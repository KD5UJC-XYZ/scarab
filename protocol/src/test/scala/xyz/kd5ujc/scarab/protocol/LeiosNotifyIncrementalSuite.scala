// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.crypto.bls.{SeatId, Seed, SecretKey, Vote}
import xyz.kd5ujc.scarab.codec.Bytes

import LeiosWord.{Word32, Word64}

/** The LeiosNotify decoder a client reads the server's messages with ([[LeiosNotify.incremental]]):
  * read in steps across the parts a message arrives in, each vote once, as cborg's incremental
  * decoder reads `codecLeiosNotify` (`LeiosDemoOnlyTestNotify.hs:259-396` at ouroboros-consensus
  * 2416493b).
  */
class LeiosNotifyIncrementalSuite extends munit.FunSuite:
  private given LeiosNotify.AnnouncementReader = _ => Right(())

  private def bs(xs: Int*): Bytes = Bytes.wrap(xs.map(_.toByte).toArray)
  private def ok[A, E](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)
  private def hash(n: Int): EbHash = EbHash.fromBytes(Bytes.wrap(Array.fill[Byte](32)(n.toByte)))
  private def word32(value: BigInt): Word32 = ok(Word32.from(value))
  private def word64(value: BigInt): Word64 = ok(Word64.from(value))
  private lazy val sk: SecretKey =
    ok(
      SecretKey.generate(
        ok(Seed.fromBytes(Bytes.wrap(Array.tabulate[Byte](32)(i => (i + 3).toByte))))
      )
    )
  private def vote(seat: Int, n: Int): Vote =
    ok(
      Vote.sign(
        sk,
        ok(SeatId.fromInt(seat)),
        ok(
          xyz.kd5ujc.scarab.crypto.bls.RbHash
            .fromBytes(Bytes.wrap(Array.fill[Byte](32)(n.toByte)))
            .toRight("hash")
        )
      )
    )

  /** `incremental` fed `bytes` in parts of `step` bytes, each a longer prefix: the first verdict.
    */
  private def fedInParts(
      decoder: IncrementalDecoder[LeiosNotify.Message],
      bytes: Bytes,
      step: Int
  ): ChainSync.DecodeResult[LeiosNotify.Message] =
    val prefixes = (step to bytes.size by step).toVector :+ bytes.size
    prefixes
      .foldLeft[(decoder.Cursor, Option[ChainSync.DecodeResult[LeiosNotify.Message]])](
        (decoder.initial, None)
      ) {
        case ((cursor, None), n) =>
          decoder.resume(cursor, bytes.take(n)) match
            case (next, ChainSync.DecodeResult.NeedMore) => (next, None)
            case (next, verdict)                         => (next, Some(verdict))
        case (done, _) => done
      }
      ._2
      .getOrElse(ChainSync.DecodeResult.NeedMore)

  test("LeiosNotify: a client's decoder reads a message in parts as from whole bytes") {
    import LeiosNotify.*
    val vs = Vector(vote(1, 1), vote(2, 1), vote(900, 2))
    val messages = Vector(
      Message.Votes(vs, vs.map(_.encode)),
      Message.BlockOffer(EbPoint(word64(77), hash(1)), word32(123456)),
      Message.BlockTxsOffer(EbPoint(word64(77), hash(1))),
      Message.BlockAnnouncement(bs(0x82, 0x07, 0xd8, 0x18, 0x42, 0xab, 0xcd)),
      Message.Canceled
    )
    for
      m <- messages
      step <- Vector(1, 7, 64)
    do
      val bytes = ok(encode(m))
      assertEquals(
        fedInParts(incremental(State.Busy), bytes, step),
        ChainSync.DecodeResult.Decoded(m, bytes.size),
        (m, step)
      )
    val done = ok(encode(Message.Done))
    assertEquals(
      fedInParts(incremental(State.Quit), done, 1),
      ChainSync.DecodeResult.Decoded(Message.Done, done.size)
    )
    // a vote it refuses fails the message once it has arrived, however the message is cut
    val bad = ok(encode(Message.Votes(vs, vs.map(_.encode)))).toArray
    bad(bad.length - 1) = (bad(bad.length - 1) ^ 0xff).toByte
    assertEquals(
      fedInParts(incremental(State.Busy), Bytes.wrap(bad), 5),
      ChainSync.DecodeResult.Failed(
        ProtocolError.Leios(ok(decodePrefix(State.Busy, Bytes.wrap(bad)).swap))
      )
    )
  }
