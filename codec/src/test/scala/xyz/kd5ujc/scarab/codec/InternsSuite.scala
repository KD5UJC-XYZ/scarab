// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import java.lang.ref.{Reference, WeakReference}
import java.util.concurrent.atomic.AtomicReference
import scala.noinline

/** What a decode shares (issue #94): equal values come out as one instance within a document, and
  * only there.
  */
class InternsSuite extends munit.FunSuite:
  private def root(hex: String): Node =
    Cbor
      .decode(Bytes.fromHex(hex).fold(e => fail(e.toString), identity))
      .fold(e => fail(e.toString), identity)

  private def bytes(n: Int): Bytes = Bytes.fromArray(Array(n.toByte, 7.toByte))

  private val hashes = new Interned[Bytes]
  private val others = new Interned[Bytes]

  test("equal values of one kind are one instance in a document, and unequal ones are not") {
    val items = root("83010203").items.fold(fail("not an array"))(_.toVector)
    val first = items(0).share(hashes)(bytes(1))
    val again = items(1).share(hashes)(bytes(1))
    val other = items(2).share(hashes)(bytes(2))
    assert(first eq again)
    assert(first ne other)
    assertEquals(other, bytes(2))
    assertEquals(first, bytes(1))
  }

  test("a kind has a table of its own") {
    val node = root("01")
    val a = node.share(hashes)(bytes(1))
    val b = node.share(others)(bytes(1))
    assert(a ne b)
    assert(node.share(others)(bytes(1)) eq b)
  }

  test("two documents share nothing, and a node built by hand shares nothing") {
    val x = bytes(1)
    val y = bytes(1)
    val z = bytes(1)
    assert(root("01").share(hashes)(x) eq x)
    assert(root("01").share(hashes)(y) eq y)
    val strict = Node(Value.Null, Bytes.empty)
    assert(strict.share(hashes)(x) eq x)
    assert(strict.share(hashes)(z) eq z)
  }

  private final case class Collision(value: Int):
    override def hashCode(): Int = 1

  test("collisions and repeated growth retain the first instance of every value") {
    val node = root("01")
    val kind = new Interned[Collision]
    val first = Vector.tabulate(4096)(Collision.apply)
    first.foreach(value => assert(node.share(kind)(value) eq value))
    first.reverse.foreach(value => assert(node.share(kind)(Collision(value.value)) eq value))
  }

  test("concurrent documents using one kind retain independent first instances") {
    val kind = new Interned[Bytes]
    val results = Vector.fill(8)(new AtomicReference[Either[Throwable, Bytes]]())
    val threads = results.zipWithIndex.map { (result, index) =>
      new Thread(
        () => {
          try
            val node = root("01")
            val first = node.share(kind)(bytes(1))
            (0 until 2000).foreach(_ => assert(node.share(kind)(bytes(1)) eq first))
            result.set(Right(first))
          catch case error: Throwable => result.set(Left(error))
        },
        s"intern-$index"
      )
    }
    threads.foreach(_.start())
    threads.foreach(_.join())
    val firsts = results.map(result =>
      Option(result.get())
        .getOrElse(fail("thread did not finish"))
        .fold(error => fail("concurrent decode defect", error), identity)
    )
    firsts.indices.foreach(i =>
      firsts.indices.filter(_ != i).foreach(j => assert(firsts(i) ne firsts(j)))
    )
  }

  @noinline private def expired(
      kind: Interned[Bytes]
  ): (WeakReference[Interns], WeakReference[Bytes]) =
    val scope = new Interns
    val first = bytes(2)
    assert(scope.share(kind)(first) eq first)
    (new WeakReference(scope), new WeakReference(first))

  test("a live document retains its table; a kind does not retain expired scopes or values") {
    val kind = new Interned[Bytes]
    val node = root("01")
    val kept = new WeakReference(node.share(kind)(bytes(1)))
    System.gc()
    assert(!kept.refersTo(null), "the document owns its first value strongly")
    assert(node.share(kind)(bytes(1)) eq kept.get())
    val (scope, value) = expired(kind)
    var attempts = 0
    while (!scope.refersTo(null) || !value.refersTo(null)) && attempts < 20 do
      System.gc()
      Thread.sleep(10)
      attempts += 1
    assert(scope.refersTo(null), "a stable kind does not keep a completed decode alive")
    assert(value.refersTo(null), "a stable kind does not keep the decode's values alive")
    Reference.reachabilityFence(node)
    Reference.reachabilityFence(kind)
  }

  test("generic and phantom kind arguments stay distinct even with identical runtime classes") {
    final case class Box[A](value: Int)
    val ints = new Interned[Box[Int]]
    val strings = new Interned[Box[String]]
    val node = root("01")
    val i = node.share(ints)(Box[Int](1))
    val s = node.share(strings)(Box[String](1))
    assert(i ne s)
    assert(node.share(ints)(Box[Int](1)) eq i)
    assert(node.share(strings)(Box[String](1)) eq s)
    val positive = scala.compiletime.testing.typeCheckErrors("""
      import xyz.kd5ujc.scarab.codec.*
      case class Box[A](value: Int)
      val node = Node(Value.Null, Bytes.empty)
      node.share(new Interned[Box[Int]])(Box[Int](1))
    """)
    val negative = scala.compiletime.testing.typeCheckErrors("""
      import xyz.kd5ujc.scarab.codec.*
      case class Box[A](value: Int)
      val node = Node(Value.Null, Bytes.empty)
      node.share(new Interned[Box[Int]])(Box[String](1))
    """)
    assertEquals(positive, Nil)
    assert(negative.nonEmpty)
  }
