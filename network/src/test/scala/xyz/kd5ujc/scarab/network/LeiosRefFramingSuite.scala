// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.codec.Bytes

/** The private probe's raw framing must not confuse an unsigned length with a JVM offset. */
class LeiosRefFramingSuite extends munit.FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(fail(_), identity)

  test("unsigned lengths cannot become negative offsets or wrap container counts") {
    Vector("5b", "7b", "9b", "bb").foreach { head =>
      Vector("8000000000000000", "ffffffffffffffff", "4000000000000000").foreach { n =>
        assertEquals(LeiosRefProbe.extent(bytes(head + n + "00")), None)
      }
    }
  }

  test("framing consumes only the first item and waits for every truncated prefix") {
    Vector("83010203", "9f010203ff", "bf0005ff", "d81843820102").foreach { hex =>
      val item = bytes(hex)
      assertEquals(LeiosRefProbe.extent(item ++ bytes("00")), Some(item.size))
      (0 until item.size).foreach { n =>
        assertEquals(LeiosRefProbe.extent(item.take(n)), None, s"$hex prefix$n")
      }
    }
  }

  test("four seeded raw-input campaigns keep completed extents inside their input") {
    (1L to 4L).foreach { seed =>
      val random = new scala.util.Random(seed)
      (0 until 100000).foreach { _ =>
        val raw = new Array[Byte](random.nextInt(256))
        random.nextBytes(raw)
        LeiosRefProbe.extent(Bytes.wrap(raw)).foreach { n =>
          assert(n > 0 && n <= raw.length, s"seed$seed extent$n length${raw.length}")
        }
      }
    }
  }

  test("raw framing is stack safe for 20000 nested array items") {
    val deep = Bytes.wrap(Array.fill[Byte](20000)(0x81.toByte) :+ 0.toByte)
    val result = new java.util.concurrent.FutureTask[Option[Int]](() => LeiosRefProbe.extent(deep))
    val worker = new Thread(null, result, "leios-framing-small-stack", 256L * 1024)
    worker.start()
    assertEquals(result.get(10, java.util.concurrent.TimeUnit.SECONDS), Some(deep.size))
    worker.join()
  }
