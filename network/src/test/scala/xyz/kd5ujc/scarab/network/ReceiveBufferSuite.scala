// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.codec.Bytes
import munit.FunSuite
import scala.util.Random

class ReceiveBufferSuite extends FunSuite:
  private def bytes(from: Int, n: Int): Bytes =
    Bytes.wrap(Array.tabulate(n)(i => (from + i).toByte))

  test("an empty buffer shows no bytes") {
    val buffer = new ReceiveBuffer
    assertEquals(buffer.size, 0)
    assertEquals(buffer.view, Bytes.empty)
    buffer.consume(5)
    assertEquals(buffer.size, 0)
  }

  test("appended bytes are shown in order and consumed from the front") {
    val buffer = new ReceiveBuffer
    assert(buffer.append(bytes(0, 10)))
    assert(buffer.append(bytes(10, 5)))
    assertEquals(buffer.view, bytes(0, 15))
    buffer.consume(4)
    assertEquals(buffer.view, bytes(4, 11))
    buffer.consume(11)
    assertEquals(buffer.size, 0)
    assertEquals(buffer.view, Bytes.empty)
  }

  test("a view already handed out is never overwritten by later appends, growth or reuse") {
    val buffer = new ReceiveBuffer
    buffer.append(bytes(0, 100))
    val first = buffer.view
    buffer.consume(60)
    val rest = buffer.view
    // Appends past the old end, then a growth that moves the live bytes, then an emptied buffer.
    buffer.append(bytes(100, 5000))
    buffer.append(bytes(0, 100000))
    buffer.consume(buffer.size)
    buffer.append(bytes(200, 300))
    assertEquals(first, bytes(0, 100))
    assertEquals(rest, bytes(60, 40))
  }

  test("a random sequence of appends and consumes matches a model") {
    val random = new Random(356)
    val buffer = new ReceiveBuffer
    var model = Vector.empty[Byte]
    var next = 0
    (1 to 2000).foreach { _ =>
      if random.nextInt(3) != 0 then
        val chunk = bytes(next, random.nextInt(3000))
        next += chunk.size
        assert(buffer.append(chunk))
        model = model ++ chunk.toArray
      else
        val n = random.nextInt(model.size + 1)
        buffer.consume(n)
        model = model.drop(n)
      assertEquals(buffer.size, model.size)
      assertEquals(buffer.view.toArray.toVector, model)
    }
  }

  test("clear drops everything") {
    val buffer = new ReceiveBuffer
    buffer.append(bytes(0, 64))
    buffer.clear()
    assertEquals(buffer.size, 0)
    assertEquals(buffer.view, Bytes.empty)
  }

  test("the capacity decision does not overflow near the array limit after partial consumption") {
    import ReceiveBuffer.Room
    val max = Int.MaxValue
    val limit = ReceiveBuffer.MaxArray.toInt
    // end + incoming wraps negative as an Int, while the live bytes are few: it must compact into a fresh array.
    assertEquals(ReceiveBuffer.room(100, max - 10, max - 8, 1000), Room.Grow(1100))
    assertEquals(
      ReceiveBuffer.room(1 << 20, max - 8, max - 8, max - 8 - (1 << 20)),
      Room.Grow(limit)
    )
    // The live total itself over the limit is refused, whatever the tail room.
    assertEquals(ReceiveBuffer.room(limit, limit, limit, 1), Room.TooLarge)
    assertEquals(ReceiveBuffer.room(max, max, max, max), Room.TooLarge)
    // Exactly the tail room fits; one more does not.
    assertEquals(ReceiveBuffer.room(10, 100, 200, 100), Room.Fits)
    assertEquals(ReceiveBuffer.room(10, 100, 200, 101), Room.Grow(1024))
    assertEquals(ReceiveBuffer.room(0, 0, 0, 0), Room.Fits)
    assertEquals(ReceiveBuffer.room(limit - 1, limit - 1, limit, 1), Room.Fits)
  }

  test("a refused append leaves the buffer unchanged and fits once bytes are consumed") {
    val buffer = new ReceiveBuffer(1500)
    assert(buffer.append(bytes(0, 1000)))
    assert(!buffer.append(bytes(0, 600)))
    assertEquals(buffer.view, bytes(0, 1000))
    buffer.consume(1000)
    assert(buffer.append(bytes(0, 600)))
    assertEquals(buffer.view, bytes(0, 600))
  }
