// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf.secret

import xyz.kd5ujc.scarab.codec.Bytes

/** The erasure scope of the secret path: every buffer a scratch made or adopted is zero once the
  * scratch ends, whether its computation returned, threw an exception or threw a fatal error; a
  * buffer, an array or a function cannot be its result; and a scratch that escaped anyway (only a
  * suite can make one escape, through a `var`) refuses every use after its end.
  */
class ScratchSuite extends munit.FunSuite:
  private def zero(a: Array[Byte]): Boolean = a.forall(_ == 0)

  private val pattern: Array[Byte] = Array.tabulate(48)(i => (i * 37 + 11).toByte)

  test("every buffer is zero once the computation returns, and what it published is kept") {
    var seen: List[Array[Byte]] = Nil
    val published = Scratch.within { s =>
      val a = s.buffer(48)
      System.arraycopy(pattern, 0, s.array(a), 0, 48)
      val b = s.buffer(16)
      s.array(b)(3) = 9
      seen = List(s.array(a), s.array(b))
      s.publish(a, 8, 16)
    }
    assertEquals(seen.size, 2)
    assert(seen.forall(zero), "a buffer kept its bytes after the scratch ended")
    assertEquals(published, Bytes.fromArray(pattern.slice(8, 24)))
  }

  test("every buffer is zero once the computation throws, and the exception passes through") {
    var seen: Option[Array[Byte]] = None
    val thrown = intercept[ArithmeticException](Scratch.within { s =>
      val a = s.buffer(48)
      System.arraycopy(pattern, 0, s.array(a), 0, 48)
      seen = Some(s.array(a))
      if s.array(a)(0) != 0 then throw new ArithmeticException("the computation failed")
      s.publish(a)
    })
    assertEquals(thrown.getClass, classOf[ArithmeticException])
    assert(seen.exists(zero), s"after a failure the buffer is ${seen.map(_.toVector)}")
  }

  test("a fatal error erases too") {
    var seen: Option[Array[Byte]] = None
    // munit's intercept does not catch a fatal error, so the suite catches it here
    val caught =
      try
        Scratch.within { s =>
          val a = s.buffer(48)
          System.arraycopy(pattern, 0, s.array(a), 0, 48)
          seen = Some(s.array(a))
          if s.array(a)(0) != 0 then throw new StackOverflowError()
          ()
        }
        None
      catch case e: StackOverflowError => Some(e)
    assert(caught.isDefined, "the fatal error did not pass through")
    assert(seen.exists(zero), "a fatal error left the buffer as it was")
  }

  test("an observer that throws does not keep the buffers from being zeroed") {
    var seen: List[Array[Byte]] = Nil
    val thrown = intercept[IllegalArgumentException](
      Scratch.observed[Unit] { arrays =>
        seen = arrays
        throw new IllegalArgumentException("the observer failed")
      } { s =>
        System.arraycopy(pattern, 0, s.array(s.buffer(48)), 0, 48)
        ()
      }
    )
    assertEquals(thrown.getClass, classOf[IllegalArgumentException])
    assertEquals(seen.size, 1)
    assert(seen.forall(zero), "the buffer kept its bytes after the observer threw")
  }

  test("what a scratch publishes is copied once into the result, through a buffer it zeroes") {
    var before: List[Array[Byte]] = Nil
    var after: List[Array[Byte]] = Nil
    val published = Scratch.observed[Bytes] { arrays =>
      before = arrays.map(_.clone())
      after = arrays
    } { s =>
      val a = s.buffer(48)
      System.arraycopy(pattern, 0, s.array(a), 0, 48)
      s.publish(a, 8, 16)
    }
    assertEquals(published, Bytes.fromArray(pattern.slice(8, 24)))
    assert(
      before.exists(java.util.Arrays.equals(_, pattern.slice(8, 24))),
      "the range was not cut into a buffer of the scratch"
    )
    assertEquals(after.size, 2)
    assert(after.forall(zero), "a buffer kept its bytes")
  }

  test("an adopted array is zeroed with the buffers") {
    val made = pattern.clone()
    Scratch.within { s =>
      val adopted = s.adopt(made)
      assertEquals(s.size(adopted), 48)
      s.publish(adopted, 0, 1)
    }
    assert(zero(made), "the adopted array kept its bytes")
  }

  test("a scratch that escaped refuses every use after its end") {
    var escaped: Option[Scratch] = None
    Scratch.within { s =>
      escaped = Some(s)
      ()
    }
    val s = escaped.get
    intercept[IllegalStateException](s.buffer(1))
    intercept[IllegalStateException](s.adopt(new Array[Byte](1)))
  }

  test("a buffer that escaped in a closure is refused after the end, not read as zeros") {
    var read: Option[() => Array[Byte]] = None
    var published: Option[() => Bytes] = None
    Scratch.within { s =>
      val b = s.buffer(4)
      read = Some(() => s.array(b))
      published = Some(() => s.publish(b))
      ()
    }
    intercept[IllegalStateException](read.get())
    intercept[IllegalStateException](published.get())
  }

  test("the result of a scratch cannot be a buffer, an array, a function or the scratch") {
    assertEquals(compileErrors("Scratch.within(s => s.publish(s.buffer(1)))"), "")
    assertNotEquals(compileErrors("Scratch.within(s => s.buffer(1))"), "")
    assertNotEquals(compileErrors("Scratch.within(s => s.array(s.buffer(1)))"), "")
    assertNotEquals(compileErrors("Scratch.within(s => () => s.publish(s.buffer(1)))"), "")
    assertNotEquals(compileErrors("Scratch.within(s => s)"), "")
    assertNotEquals(compileErrors("Scratch.within(s => Option(s.buffer(1)))"), "")
  }
