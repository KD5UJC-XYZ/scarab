// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes

import LeiosWord.{Word16, Word32, Word64}

/** Native word and hash constructors (consensus 7b239a16 Leios/Types.hs), independently of the
  * Blueprint's narrower hash description.
  */
class LeiosWordSuite extends FunSuite:
  private def ok[A](value: Either[LeiosWireError, A]): A =
    value.fold(e => fail(e.toString), identity)

  test("unsigned word constructors retain both bounds and refuse the adjacent integers") {
    val constructors = Vector(
      (LeiosField.Word16, Word16.Max, (n: BigInt) => Word16.from(n).map(v => BigInt(v.toInt))),
      (LeiosField.Word32, Word32.Max, (n: BigInt) => Word32.from(n).map(v => BigInt(v.toLong))),
      (LeiosField.Word64, Word64.Max, (n: BigInt) => Word64.from(n).map(_.toBigInt))
    )
    constructors.foreach { (field, maximum, construct) =>
      Vector(BigInt(0), maximum).foreach(n => assertEquals(construct(n), Right(n)))
      Vector(BigInt(-1), maximum + 1).foreach(n =>
        assertEquals(construct(n), Left(LeiosWireError.OutsideWord(field, n)))
      )
    }
  }

  test("slots above signed Long retain all 64 bits on both protocols") {
    Vector(BigInt(Long.MaxValue) + 1, Word64.Max).foreach { slot =>
      val point = EbPoint(ok(Word64.from(slot)), EbHash.fromBytes(Bytes.empty))
      val request = LeiosFetch.Message.BlockRequest(point)
      val fetched = ok(LeiosFetch.encode(request))
      assertEquals(
        LeiosFetch.decodePrefix(LeiosFetch.State.Idle, fetched),
        Right((request, fetched.size))
      )
      val offer = LeiosNotify.Message.BlockOffer(point, ok(Word32.from(Word32.Max)))
      val notified = ok(LeiosNotify.encode(offer))
      assertEquals(
        LeiosNotify.decodePrefix(LeiosNotify.State.Busy, notified),
        Right((offer, notified.size))
      )
    }
  }

  test("native EB and transaction hash wrappers retain arbitrary bytes") {
    Vector(0, 1, 16, 31, 32, 33).foreach { size =>
      val bytes = Bytes.wrap(Array.fill[Byte](size)(7))
      val hash = EbHash.fromBytes(bytes)
      val txHash = LeiosTxHash.fromBytes(bytes)
      assertEquals(hash.bytes, bytes)
      assertEquals(txHash.bytes, bytes)
      val point = EbPoint(ok(Word64.from(1)), hash)
      val offer = LeiosNotify.Message.BlockOffer(point, ok(Word32.from(0)))
      val notified = ok(LeiosNotify.encode(offer))
      assertEquals(
        LeiosNotify.decodePrefix(LeiosNotify.State.Busy, notified),
        Right((offer, notified.size))
      )
      val block = LeiosFetch.Message.Block(Vector(txHash -> ok(Word32.from(Word32.Max))))
      val fetched = ok(LeiosFetch.encode(block))
      assertEquals(
        LeiosFetch.decodePrefix(LeiosFetch.State.Block, fetched),
        Right((block, fetched.size))
      )
    }
  }

  test("the bitmap keeps its high bit and Word64 maximum") {
    val point = EbPoint(ok(Word64.from(0)), EbHash.fromBytes(Bytes.empty))
    val bitmaps = TxBitmaps(Vector(ok(Word16.from(0)) -> ok(Word64.from(Word64.Max))))
    assertEquals(bitmaps.count, 64L)
    val message = LeiosFetch.Message.BlockTxsRequest(point, bitmaps)
    val bytes = ok(LeiosFetch.encode(message))
    assertEquals(
      LeiosFetch.decodePrefix(LeiosFetch.State.Idle, bytes),
      Right((message, bytes.size))
    )
  }

  test("constructors cannot exchange hash roles or bypass unsigned words") {
    assert(compileErrors("""
      import xyz.kd5ujc.scarab.protocol.*
      import xyz.kd5ujc.scarab.codec.Bytes
      val hash: LeiosTxHash = EbHash.fromBytes(Bytes.empty)
    """).nonEmpty)
    assert(compileErrors("""
      import xyz.kd5ujc.scarab.protocol.*
      import xyz.kd5ujc.scarab.codec.Bytes
      val point = EbPoint(-1L, EbHash.fromBytes(Bytes.empty))
    """).nonEmpty)
    assert(compileErrors("""
      import xyz.kd5ujc.scarab.protocol.*
      val bitmaps = TxBitmaps(Vector(0 -> BigInt(-1)))
    """).nonEmpty)
  }
