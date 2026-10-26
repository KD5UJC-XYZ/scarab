// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref}
import cats.mtl.Raise
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, EbHash, EbPoint, LeiosFetch, LeiosWord}

class LeiosFetchClientSuite extends FailsOnUncaught:
  test("block requests give the receiving driver the native 660000-byte state limit") {
    Peers
      .typed {
        for
          limits <- Ref.of[IO, Vector[Int]](Vector.empty)
          sent <- Ref.of[IO, Vector[Bytes]](Vector.empty)
          raw = Bytes.wrap(Array[Byte](0x82.toByte, 2, 0xa1.toByte, 0x40, 0))
          channel = new MuxChannel[IO]:
            def protocol = Protocols.LeiosFetch
            def send(message: Bytes)(using Raise[IO, NetworkError]) = sent.update(_ :+ message)
            def flush = IO.unit
            def recv[A](maxBytes: Int)(decode: Bytes => ChainSync.DecodeResult[A])(using
                raise: Raise[IO, NetworkError]
            ): IO[A] =
              limits.update(_ :+ maxBytes) *> (decode(raw) match
                case ChainSync.DecodeResult.Decoded(value, _) => IO.pure(value)
                case ChainSync.DecodeResult.Failed(error) =>
                  raise.raise[NetworkError, A](NetworkError.IllegalTransition(protocol, error))
                case ChainSync.DecodeResult.NeedMore =>
                  raise.raise[NetworkError, A](NetworkError.Timeout(protocol, "test-prefix")))
          slot <- IO.fromEither(
            LeiosWord.Word64.from(0).left.map(e => new AssertionError(e.toString))
          )
          client = LeiosFetchClient[IO](channel)
          observed <- client.block(EbPoint(slot, EbHash.fromBytes(Bytes.empty)))
          _ <- client.done
          seen <- limits.get
          requests <- sent.get
        yield
          assertEquals(seen, Vector(660000))
          assertEquals(observed.raw, raw)
          assertEquals(
            Right(observed.message),
            LeiosFetch.decodePrefix(LeiosFetch.State.Block, raw).map(_._1)
          )
          assertEquals(requests.size, 2)
          assertEquals(
            LeiosFetch.decodePrefix(LeiosFetch.State.Idle, requests.last).map(_._1),
            Right(LeiosFetch.Message.Done)
          )
      }
      .map(result => assertEquals(result, Right(())))
  }
