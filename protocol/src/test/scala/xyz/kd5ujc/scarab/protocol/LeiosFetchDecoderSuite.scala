// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes

import LeiosFetch.{Message, State, decodePrefix}

class LeiosFetchDecoderSuite extends FunSuite:
  private def bs(values: Int*): Bytes = Bytes.wrap(values.map(_.toByte).toArray)
  private def ok[A, E](value: Either[E, A]): A =
    value.fold(error => throw new AssertionError(error.toString), identity)
  private val point = bs(0x82, 0x00, 0x40)
  private val transaction = bs(0xd8, 0x18, 0x41, 0xa0)

  test("native decodeBytes refuses chunked payloads and hashes, including empty chunks") {
    Vector(bs(0x5f, 0xff), bs(0x5f, 0x41, 0xa0, 0xff)).foreach { chunked =>
      assertEquals(
        decodePrefix(State.BlockTxs, bs(0x82, 4, 0x81, 0xd8, 0x18) ++ chunked),
        Left(LeiosWireError.BadTransaction(0))
      )
      assertEquals(
        decodePrefix(State.Idle, bs(0x82, 1, 0x82, 0) ++ chunked),
        Left(LeiosWireError.BadField(1, LeiosField.Hash))
      )
      assertEquals(
        decodePrefix(State.Block, bs(0x82, 2, 0xa1) ++ chunked ++ bs(0)),
        Left(LeiosWireError.BadField(2, LeiosField.Hash))
      )
    }
  }

  test("native non-shortest tag and definite bytes framing preserve the opaque payload") {
    val raw = bs(0x82, 4, 0x81, 0xd9, 0, 0x18, 0x58, 1, 0xa0)
    assertEquals(
      decodePrefix(State.BlockTxs, raw),
      Right(Message.BlockTxs(Vector(bs(0xa0))) -> raw.size)
    )
  }

  test("every valid framing prefix asks for more; complete message leaves trailing bytes") {
    val messages = Vector(
      State.Idle -> bs(0x9f, 1, 0x9f, 0, 0x40, 0xff, 0xff),
      State.Idle -> (bs(0x83, 3) ++ point ++ bs(0xbf, 0, 1, 0xff)),
      State.Block -> bs(0x9f, 2, 0xbf, 0x41, 0, 0x1a, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff),
      State.BlockTxs -> (bs(0x9f, 4, 0x9f) ++ transaction ++ bs(0xff, 0xff)),
      State.BlockTxs -> (bs(0x9f, 4) ++ point ++ bs(0xa0, 0x9f, 0xa0, 0xff, 0xff))
    )
    messages.foreach { (state, raw) =>
      (0 until raw.size).foreach { n =>
        assertEquals(
          decodePrefix(state, raw.take(n)),
          Left(LeiosWireError.Incomplete),
          s"${raw.hex}: $n"
        )
      }
      val result = ok(decodePrefix(state, raw))
      assertEquals(result._2, raw.size)
      assertEquals(decodePrefix(state, raw ++ bs(0xff, 0x1c)), Right(result))
    }
  }

  test("declared collection bounds fail before any entry or declared body arrives") {
    assertEquals(
      decodePrefix(State.Block, bs(0x82, 2, 0xb9, 0x42, 0xf7)),
      Left(LeiosWireError.TooMany(2, LeiosField.EndorserBlock, 17142, 17143))
    )
    assertEquals(
      decodePrefix(State.BlockTxs, bs(0x82, 4, 0x99, 0x42, 0xf7)),
      Left(LeiosWireError.TooMany(4, LeiosField.Transactions, 17142, 17143))
    )
    assertEquals(
      decodePrefix(State.Idle, bs(0x83, 3) ++ point ++ bs(0xb9, 1, 0x0d)),
      Left(LeiosWireError.TooMany(3, LeiosField.Bitmaps, 268, 269))
    )
    assertEquals(
      decodePrefix(State.BlockTxs, bs(0x9f, 4, 0x99, 0x42, 0xf7)),
      Left(LeiosWireError.TooMany(4, LeiosField.Transactions, 17142, 17143))
    )
    assertEquals(
      decodePrefix(State.Block, bs(0x82, 2, 0xbb, 0x7f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)),
      Left(LeiosWireError.TooMany(2, LeiosField.EndorserBlock, 17142, BigInt(Long.MaxValue)))
    )
  }

  test("bitmap entry errors precede later truncated fields and outer record arity") {
    val prefix = bs(0x82, 3) ++ point
    assertEquals(
      decodePrefix(State.Idle, prefix ++ bs(0xa2, 0, 0)),
      Left(LeiosWireError.ZeroBitmap(3, 0))
    )
    assertEquals(
      decodePrefix(State.Idle, prefix ++ bs(0xbf, 0x19, 1, 0x0c, 1)),
      Left(LeiosWireError.BitmapWindowOutOfRange(3, 268, 268))
    )
    assertEquals(
      decodePrefix(State.Idle, prefix ++ bs(0xa3, 1, 1, 1, 1)),
      Left(LeiosWireError.BitmapWindowsNotAscending(3, 1))
    )
    // A zero bitmap wins over an out-of-range window, exactly as decodeBitmaps does.
    assertEquals(
      decodePrefix(State.Idle, prefix ++ bs(0xa1, 0x19, 1, 0x0c, 0)),
      Left(LeiosWireError.ZeroBitmap(3, 268))
    )
  }

  test("record arity follows fields and agency precedes the message body") {
    assertEquals(decodePrefix(State.Idle, bs(0x81, 1)), Left(LeiosWireError.Incomplete))
    assertEquals(
      decodePrefix(State.Idle, bs(0x81, 1) ++ point),
      Left(LeiosWireError.WrongArity(1, 1, 0))
    )
    assertEquals(decodePrefix(State.Idle, bs(0x82, 0)), Left(LeiosWireError.WrongArity(0, 0, 1)))
    assertEquals(decodePrefix(State.Idle, bs(0x9f, 0, 0)), Left(LeiosWireError.ExcessTerms(0)))
    assertEquals(decodePrefix(State.Idle, bs(0x82, 2)), Left(LeiosWireError.NotAdmitted(2)))
    assertEquals(
      decodePrefix(State.Block, bs(0x82, 0x1a, 0, 1, 0, 0)),
      Left(LeiosWireError.UnknownTag(BigInt(65536)))
    )
    assertEquals(decodePrefix(State.Idle, bs(0x82, 1, 0x81, 0)), Left(LeiosWireError.Incomplete))
    assertEquals(
      decodePrefix(State.Idle, bs(0x82, 1, 0x81, 0, 0x40)),
      Left(LeiosWireError.BadField(1, LeiosField.Point))
    )
  }

  test("indefinite bound checks inspect break before refusing the next entry") {
    val items =
      Bytes.wrap(Array.fill(LeiosFetch.MaxTxsPerEb)(Array[Byte](0xd8.toByte, 0x18, 0x40)).flatten)
    val prefix = bs(0x82, 4, 0x9f) ++ items
    assertEquals(decodePrefix(State.BlockTxs, prefix), Left(LeiosWireError.Incomplete))
    assertEquals(ok(decodePrefix(State.BlockTxs, prefix ++ bs(0xff)))._2, prefix.size + 1)
    assertEquals(
      decodePrefix(State.BlockTxs, prefix ++ bs(0xd8)),
      Left(LeiosWireError.TooMany(4, LeiosField.Transactions, 17142, 17143))
    )
  }

  test("maximum-width list decoding uses a 256 KiB stack") {
    val message = Message.BlockTxs(Vector.fill(LeiosFetch.MaxTxsPerEb)(bs(0)))
    val raw = ok(LeiosFetch.encode(message))
    val result = new CompletableFuture[Either[Throwable, Either[LeiosWireError, (Message, Int)]]]()
    val thread = new Thread(
      null,
      () => {
        try result.complete(Right(decodePrefix(State.BlockTxs, raw)))
        catch case error: Throwable => result.complete(Left(error))
        ()
      },
      "leios-fetch-small-stack",
      256 * 1024
    )
    thread.start()
    try
      val actual = result.get(30, TimeUnit.SECONDS)
      assertEquals(actual, Right(Right(message -> raw.size)))
    finally thread.join()
  }

  test("native pure encoders do not apply receiver byte limits or the generic CBOR one-MiB cap") {
    val payload = Bytes.wrap(Array.fill[Byte](1048577)(0x5a))
    val slot = ok(LeiosWord.Word64.from(0))
    val size = ok(LeiosWord.Word32.from(0))
    val messages = Vector(
      State.Idle -> Message.BlockRequest(EbPoint(slot, EbHash.fromBytes(payload))),
      State.Block -> Message.Block(Vector(LeiosTxHash.fromBytes(payload) -> size)),
      State.BlockTxs -> Message.BlockTxs(Vector(payload))
    )
    messages.foreach { (state, message) =>
      val encoded = ok(LeiosFetch.encode(message))
      assert(encoded.size > 1048576)
      assertEquals(decodePrefix(state, encoded), Right(message -> encoded.size))
    }
    // Pinned encodeLeiosTx (consensus 7b239a16) with cardano-binary 1.9.1.0/cborg 0.2.10.0:
    // remove the message/list framing and compare the entire native tag-24 encoding.
    val txEncoding = ok(LeiosFetch.encode(Message.BlockTxs(Vector(payload)))).drop(3)
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(txEncoding.toArray)
    assertEquals(
      Bytes.wrap(digest).hex,
      "5c64a6dff9bbb0ffd5544d1cb695ccef70c791f230f7e9e2de335b4877056abe"
    )
  }

  test("native encoders write empty and over-bound collections and unchecked bitmap pairs") {
    val slot = ok(LeiosWord.Word64.from(0))
    val p = EbPoint(slot, EbHash.fromBytes(Bytes.empty))
    val zero = TxBitmaps(Vector(ok(LeiosWord.Word16.from(5)) -> slot))
    assertEquals(
      LeiosFetch.encode(Message.BlockTxsRequest(p, zero)),
      Right(bs(0x83, 3, 0x82, 0, 0x40, 0xbf, 5, 0, 0xff))
    )
    Vector(
      Vector(268 -> 1) -> bs(0xbf, 0x19, 1, 0x0c, 1, 0xff),
      Vector(3 -> 1, 2 -> 1) -> bs(0xbf, 3, 1, 2, 1, 0xff)
    ).foreach { (pairs, native) =>
      val bitmaps = TxBitmaps(pairs.map { (window, bitmap) =>
        ok(LeiosWord.Word16.from(window)) -> ok(LeiosWord.Word64.from(bitmap))
      })
      assertEquals(ok(LeiosFetch.encode(Message.BlockTxsRequest(p, bitmaps))).drop(5), native)
    }
    val repeated = Message.Block(
      Vector.fill(LeiosFetch.MaxTxsPerEb + 1)(
        LeiosTxHash.fromBytes(Bytes.empty) -> ok(LeiosWord.Word32.from(0))
      )
    )
    val encoded = ok(LeiosFetch.encode(repeated))
    assertEquals(encoded.take(5), bs(0x82, 2, 0xb9, 0x42, 0xf7))
    assertEquals(encoded.size - 2, 34289)
    assertEquals(
      decodePrefix(State.Block, encoded),
      Left(LeiosWireError.TooMany(2, LeiosField.EndorserBlock, 17142, 17143))
    )
  }

  test("reference byte policy is separate for each receiving state") {
    assertEquals(LeiosFetch.byteLimit(State.Idle), Some(65535))
    assertEquals(LeiosFetch.byteLimit(State.Block), Some(660000))
    assertEquals(LeiosFetch.byteLimit(State.BlockTxs), Some(550000))
    assertEquals(LeiosFetch.byteLimit(State.Done), None)
  }
