// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Random

/** The indexed `Node` against the tree builder it replaced ([[OracleCbor]], issue #94): for every
  * input the same error, or the same tree with the same value, the same original bytes and the same
  * offset at every node, and the original bytes are windows of the input.
  */
class IndexedNodeSuite extends munit.FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(fail(_), identity)

  private val Wide = Cbor.Limits(maxInputBytes = 1 << 24, maxItems = 1 << 22, allowFloats = true)

  /** The labels of two values, apart from their children (which the walk visits). */
  private def sameShallow(x: Node, y: Node): Boolean =
    val (a, b) = (x.value, y.value)
    Value.sameLabel(a, b) && Value.nodes(a).size == Value.nodes(b).size

  /** The entries of a map node are those of its value, and of none other node. */
  private def sameEntries(x: Node, y: Node): Boolean =
    (x.entries, y.value) match
      case (Some(es), Value.Map(ps)) =>
        val got = es.toVector
        got.size == ps.size && got.zip(ps).forall { case ((k, v), (k2, v2)) =>
          k.original == k2.original && v.original == v2.original
        }
      case (None, Value.Map(_)) => false
      case (None, _)            => true
      case (Some(_), _)         => false

  /** The first difference between the tree of `fresh` and that of `oracle`, walking both on the
    * heap, or none. `input` is what both were decoded from.
    */
  private def firstDifference(input: Bytes, fresh: Node, oracle: Node): Option[String] =
    val stack = new java.util.ArrayDeque[(Node, Node)]
    stack.push((fresh, oracle))
    var found: Option[String] = None
    while found.isEmpty && !stack.isEmpty do
      val (a, b) = stack.pop()
      if a.original != b.original then found = Some(s"original ${a.original} / ${b.original}")
      else if a.offset != b.offset then found = Some(s"offset ${a.offset} / ${b.offset}")
      else if !(a.original.unsafeArray eq input.unsafeArray) then
        found = Some("a copy, not a window")
      else if !sameShallow(a, b) then found = Some(s"value ${a.value.name} / ${b.value.name}")
      else if !sameEntries(a, b) then found = Some(s"entries of ${a.original}")
      else
        val (as, bs) = (Value.nodes(a.value), Value.nodes(b.value))
        var i = as.size - 1
        while i >= 0 do
          stack.push((as(i), bs(i)))
          i -= 1
    found

  extension (v: Value) private def name: String = Value.name(v)

  private def agree(input: Bytes, limits: Cbor.Limits = Wide): Unit =
    (Cbor.decode(input, limits), OracleCbor.decode(input, limits)) match
      case (Left(a), Left(b)) => assertEquals(a, b)
      case (Right(a), Right(b)) =>
        assertEquals(firstDifference(input, a, b), None, input.hex.take(200))
        // the structural equality, which compares the labels and the original bytes of both
        assertEquals(a, b)
        assertEquals(a.hashCode, b.hashCode)
      case (a, b) => fail(s"${a.map(_ => "ok")} / ${b.map(_ => "ok")} for ${input.hex.take(200)}")

  // ---- inputs

  /** The items of every committed fixture that is hex (a line each) or CBOR (a file each). */
  private lazy val fixtures: Vector[Bytes] =
    val files =
      Files.walk(Path.of("fixtures")).iterator.asScala.filter(Files.isRegularFile(_)).toVector
    files.flatMap { f =>
      val name = f.toString
      if name.endsWith(".hex") then
        Files
          .readAllLines(f)
          .asScala
          .toVector
          .map(_.trim)
          .filter(_.nonEmpty)
          .flatMap(l => Bytes.fromHex(l).toOption)
      else if name.endsWith(".cbor") then Vector(Bytes.wrap(Files.readAllBytes(f)))
      else Vector.empty
    }

  /** A random well-formed item, the wire forms of RFC 8949 that a decoder must keep: non-minimal
    * heads, indefinite strings and containers, tags, floats.
    */
  private def item(random: Random, out: java.io.ByteArrayOutputStream, depth: Int): Unit =
    def head(major: Int, n: Long): Unit =
      // a random width at least as wide as the shortest
      val shortest =
        if n < 24 then 0
        else if n < 256 then 1
        else if n < 65536 then 2
        else if n < (1L << 32) then 3
        else 4
      val width = if n < 0 then 4 else shortest + random.nextInt(5 - shortest)
      width match
        case 0 => out.write((major << 5) | n.toInt)
        case w =>
          out.write((major << 5) | (23 + w))
          var shift = ((1 << (w - 1)) - 1) * 8
          while shift >= 0 do
            out.write(((n >>> shift) & 0xff).toInt)
            shift -= 8
    def payload(major: Int, chunkable: Boolean): Unit =
      val text = major == 3
      def chunk(): Array[Byte] =
        val n = random.nextInt(40)
        if text then
          val s = new StringBuilder
          (0 until n).foreach(_ => s.append(if random.nextInt(6) == 0 then "é€😀" else "a"))
          s.toString.getBytes("UTF-8")
        else Array.fill[Byte](n)(random.nextInt(256).toByte)
      if chunkable && random.nextInt(4) == 0 then
        out.write((major << 5) | 31)
        (0 until random.nextInt(4)).foreach { _ =>
          val c = chunk()
          head(major, c.length)
          out.write(c)
        }
        out.write(0xff)
      else
        val c = chunk()
        head(major, c.length)
        out.write(c)
    val leafOnly = depth <= 0
    random.nextInt(if leafOnly then 7 else 12) match
      case 0 => head(0, random.nextLong() >>> random.nextInt(64))
      case 1 => head(1, random.nextLong() >>> random.nextInt(64))
      case 2 => payload(2, chunkable = true)
      case 3 => payload(3, chunkable = true)
      case 4 => out.write(0xf4 + random.nextInt(3))
      case 5 =>
        random.nextInt(3) match
          case 0 => out.write(0xf9); out.write(random.nextInt(256)); out.write(random.nextInt(256))
          case 1 => out.write(0xfa); (0 until 4).foreach(_ => out.write(random.nextInt(256)))
          case _ => out.write(0xfb); (0 until 8).foreach(_ => out.write(random.nextInt(256)))
      case 6 => head(0, random.nextInt(30).toLong)
      case 7 | 8 =>
        val n = random.nextInt(5)
        val indefinite = random.nextInt(3) == 0
        if indefinite then out.write(0x9f) else head(4, n.toLong)
        (0 until n).foreach(_ => item(random, out, depth - 1))
        if indefinite then out.write(0xff)
      case 9 | 10 =>
        val n = random.nextInt(4)
        val indefinite = random.nextInt(3) == 0
        if indefinite then out.write(0xbf) else head(5, n.toLong)
        (0 until n).foreach { _ =>
          item(random, out, depth - 1); item(random, out, depth - 1)
        }
        if indefinite then out.write(0xff)
      case _ =>
        head(6, random.nextInt(300).toLong)
        item(random, out, depth - 1)

  private def random(seed: Long, depth: Int): Bytes =
    val out = new java.io.ByteArrayOutputStream
    item(new Random(seed), out, depth)
    Bytes.wrap(out.toByteArray)

  /** `input` with a few bytes changed, dropped or added: mostly an error, sometimes another item.
    */
  private def mutated(input: Bytes, random: Random): Bytes =
    val a = input.toArray
    if a.isEmpty then input
    else
      random.nextInt(4) match
        case 0 =>
          (0 to random.nextInt(3)).foreach(_ =>
            a(random.nextInt(a.length)) = random.nextInt(256).toByte
          )
          Bytes.wrap(a)
        case 1 => Bytes.wrap(a.take(random.nextInt(a.length)))
        case 2 =>
          val at = random.nextInt(a.length)
          Bytes.wrap(a.take(at) ++ Array(random.nextInt(256).toByte) ++ a.drop(at))
        case _ => Bytes.wrap(a ++ Array(random.nextInt(256).toByte))

  // ---- tests

  test("every committed fixture decodes to the tree of the oracle") {
    assert(fixtures.size > 100, s"${fixtures.size} fixtures")
    fixtures.foreach(f => agree(f))
    assert(fixtures.count(f => Cbor.decode(f, Wide).isRight) > 100)
  }

  test("mutated fixtures give the oracle's error, or its tree") {
    val rnd = new Random(94)
    val some = rnd.shuffle(fixtures).take(400)
    some.foreach { f =>
      (1 to 12).foreach(_ => agree(mutated(f, rnd)))
      agree(f, Cbor.Limits(maxItems = rnd.nextInt(200), allowFloats = true))
      agree(f, Cbor.Limits(maxDepth = rnd.nextInt(8), allowFloats = true))
      agree(f, Cbor.Limits(maxStringBytes = rnd.nextInt(60), allowFloats = true))
    }
  }

  test("random items with every wire form, whole and mutated, give the oracle's result") {
    (1L to 3000L).foreach { seed =>
      val input = random(seed, depth = 1 + (seed % 7).toInt)
      assert(Cbor.decode(input, Wide).isRight, s"seed $seed is not well formed")
      agree(input)
      agree(input, Wide.copy(allowFloats = false))
      val rnd = new Random(seed)
      (1 to 4).foreach(_ => agree(mutated(input, rnd)))
    }
  }

  test("items inside a larger input are windows of it, and offsets are those of the array") {
    val inner = random(7, 5)
    val padded =
      Bytes.wrap(Array.fill[Byte](13)(0x55.toByte) ++ inner.toArray ++ Array.fill[Byte](9)(0))
    val window = padded.slice(13, 13 + inner.size)
    val (fresh, oracle) = (Cbor.decode(window, Wide), OracleCbor.decode(window, Wide))
    (fresh, oracle) match
      case (Right(a), Right(b)) =>
        assertEquals(a.offset, 13)
        assertEquals(firstDifference(window, a, b), None)
      case other => fail(other.toString)
  }

  test("the 10,000-level nesting of the block that broke the recursive decoder") {
    // five containers rotating, 10,000 deep, then 90,000 definite ones: both builders on a stack
    // that the recursive decoders overflowed
    val unit = Vector("81", "a100", "c1", "9f", "bf00")
    val close = Vector("", "", "", "ff", "ff")
    val levels = (0 until 10000).map(_ % unit.size)
    val text = levels.map(unit).mkString + "00" + levels.reverse.map(close).mkString
    onSmallStack(agree(bytes(text)))
    onSmallStack(agree(bytes("81" * 90000 + "00")))
  }

  test("a node's value is read from the index at each call, never kept") {
    val node = Cbor.decode(bytes("83010203")).fold(fail(_), identity)
    assert(node.value ne node.value)
    node.value match
      case Value.Arr(items) => assert(items.head.value ne items.head.value)
      case other            => fail(other.toString)
  }

  test("the children of a large flat and a large nested container are found by the index") {
    val n = 20000
    val flat = bytes("99" + f"$n%04x" + "1a000186a0" * n)
    val pairs = bytes("b9" + f"$n%04x" + ("01" + "820102") * n)
    Seq(flat, pairs).foreach(agree(_))
    Cbor.decode(flat).fold(fail(_), identity).value match
      case Value.Arr(items) =>
        assertEquals(items.size, n)
        assertEquals(items(n - 1).original.hex, "1a000186a0")
      case other => fail(other.toString)
  }

  /** Run `body` on a thread with a small stack, so that a deep item overflows it for certain. */
  private def onSmallStack[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    result.get.fold(throw _, identity)
