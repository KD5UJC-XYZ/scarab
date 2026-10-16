// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import java.nio.charset.StandardCharsets

/** Where the items of one decoded CBOR input lie: two ints an item, numbered in the order the items
  * begin (pre-order, so a container is followed by its first child and the starts increase), in
  * blocks that are never copied when the index grows. Item `i` begins at `start(i)`, where its head
  * is, and ends before `end(i)`, so that its wire bytes are `[start(i), end(i))`, an indefinite
  * item's break included.
  *
  * This is Haskell's `ByteSpan` of an item (`Cardano.Ledger.Binary.Decoding.Annotated`) for all of
  * the items of a document at once, 8 bytes each in place of the 104 of an object tree.
  */
private[codec] final class Index(sizeBound: Int):
  import Index.*

  // Every item takes a byte, so an input of at most `BlockSize` bytes never needs a second block
  // and the first block is not larger than the input.
  private val first = math.max(1, math.min(sizeBound, BlockSize))
  private var starts = new Array[Array[Int]](1)
  private var ends = new Array[Array[Int]](1)
  starts(0) = new Array[Int](first)
  ends(0) = new Array[Int](first)
  private var count = 0

  def size: Int = count

  def start(i: Int): Int = starts(i >>> Shift)(i & Mask)
  def end(i: Int): Int = ends(i >>> Shift)(i & Mask)

  /** Begin an item whose head is at `at`; its number. */
  def begin(at: Int): Int =
    val i = count
    val b = i >>> Shift
    if b == starts.length then
      starts = java.util.Arrays.copyOf(starts, b * 2)
      ends = java.util.Arrays.copyOf(ends, b * 2)
    if starts(b) == null then
      starts(b) = new Array[Int](BlockSize)
      ends(b) = new Array[Int](BlockSize)
    starts(b)(i & Mask) = at
    count = i + 1
    i

  /** The item `i` ends before `at`. */
  def finish(i: Int, at: Int): Unit = ends(i >>> Shift)(i & Mask) = at

  /** The number of the first item at or after `from` that begins at `target`, which must be one:
    * galloping from `from`, so that the next sibling of a small item is found at once and that of a
    * large one in a logarithmic number of steps.
    */
  def firstAt(from: Int, target: Int): Int =
    if start(from) == target then from
    else
      // start(lo) < target <= start(hi)
      var lo = from
      var step = 1
      var hi = math.min(count - 1, from + step)
      while start(hi) < target do
        lo = hi
        step <<= 1
        hi = math.min(count - 1, hi + step)
      while hi - lo > 1 do
        val mid = (lo + hi) >>> 1
        if start(mid) < target then lo = mid else hi = mid
      hi

private[codec] object Index:
  private val Shift = 12
  private val BlockSize = 1 << Shift
  private val Mask = BlockSize - 1

/** The input of a decode and the [[Index]] of its items: what an indexed [[Node]] is a view of.
  * Nothing in it is a value: [[valueAt]] reads the item from the bytes each time it is asked, so
  * that a document costs its index and not an object per item, and that a decoder which walks a
  * large collection holds the items it is looking at and no others.
  *
  * The decoder that built the index has checked what a value needs (the heads, the lengths, the
  * UTF-8 of text), so reading it cannot fail.
  */
private[codec] final class Document(val data: Array[Byte], val index: Index):
  import Value.*

  def node(i: Int): Node = new Node.Indexed(this, i)

  /** The wire bytes of item `i`: a window of the input. */
  def originalAt(i: Int): Bytes =
    val s = index.start(i)
    Bytes.wrap(data, s, index.end(i) - s)

  /** The size of a head with additional information `ai`, its argument bytes included. */
  private def headSize(ai: Int): Int = ai match
    case 24 => 2
    case 25 => 3
    case 26 => 5
    case 27 => 9
    case _  => 1

  /** The argument of the head at `s` as an unsigned 64-bit number in a `Long` (a negative `Long` is
    * above `2^63`).
    */
  private def argBits(s: Int, ai: Int): Long =
    if ai < 24 then ai.toLong
    else
      val count = headSize(ai) - 1
      var n = 0L
      var i = 1
      while i <= count do
        n = (n << 8) | (data(s + i) & 0xffL)
        i += 1
      n

  private def unsigned(bits: Long): BigInt =
    if bits >= 0 then BigInt(bits) else (BigInt(bits >>> 1) << 1) | BigInt(bits & 1L)

  /** The number of children a definite array, map (twice its pairs) or tag has: what the decoder
    * has bounded by `maxItems`, so an int.
    */
  private def childCount(s: Int, major: Int, ai: Int): Int =
    val n = argBits(s, ai).toInt
    if major == 5 then 2 * n else n

  /** The children of the container item `i`, in order. */
  private def children(i: Int, s: Int, major: Int, ai: Int): Array[Node] =
    if ai != 31 then
      val n = childCount(s, major, ai)
      val out = new Array[Node](n)
      var c = i + 1
      var k = 0
      while k < n do
        out(k) = node(c)
        k += 1
        if k < n then c = index.firstAt(c + 1, index.end(c))
      out
    else
      var out = new Array[Node](4)
      var n = 0
      var at = s + 1
      var c = i + 1
      while (data(at) & 0xff) != 0xff do
        if n == out.length then out = java.util.Arrays.copyOf(out, n * 2)
        out(n) = node(c)
        n += 1
        at = index.end(c)
        if (data(at) & 0xff) != 0xff then c = index.firstAt(c + 1, at)
      java.util.Arrays.copyOf(out, n)

  private def vector(nodes: Array[Node]): Vector[Node] =
    if nodes.length == 0 then Vector.empty
    else Vector.from(scala.collection.immutable.ArraySeq.unsafeWrapArray(nodes))

  private def pairs(nodes: Array[Node]): Vector[(Node, Node)] =
    val out = new Array[(Node, Node)](nodes.length / 2)
    var i = 0
    while i < out.length do
      out(i) = (nodes(2 * i), nodes(2 * i + 1))
      i += 1
    Vector.from(scala.collection.immutable.ArraySeq.unsafeWrapArray(out))

  /** The value of the string items (definite or in chunks): the byte ranges of its payloads. */
  private def payloads(s: Int, ai: Int)(each: (Int, Int) => Unit): Unit =
    if ai != 31 then
      val from = s + headSize(ai)
      each(from, argBits(s, ai).toInt)
    else
      var at = s + 1
      while (data(at) & 0xff) != 0xff do
        val chunk = data(at) & 31
        val length = argBits(at, chunk).toInt
        at += headSize(chunk)
        each(at, length)
        at += length

  private def text(s: Int, ai: Int): String =
    if ai != 31 then
      new String(data, s + headSize(ai), argBits(s, ai).toInt, StandardCharsets.UTF_8)
    else
      val out = new java.lang.StringBuilder
      payloads(s, ai)((from, length) =>
        out.append(new String(data, from, length, StandardCharsets.UTF_8))
      )
      out.toString

  private def bytes(s: Int, ai: Int): Bytes =
    // a copy, as always: a value kept in a decoded state must not pin the input
    if ai != 31 then
      val from = s + headSize(ai)
      Bytes.wrap(java.util.Arrays.copyOfRange(data, from, from + argBits(s, ai).toInt))
    else
      val out = new java.io.ByteArrayOutputStream()
      payloads(s, ai)((from, length) => out.write(data, from, length))
      Bytes.wrap(out.toByteArray)

  /** The entries of item `i` if it is a map, found in the index as they are asked for. */
  def entriesAt(i: Int): Option[Iterator[(Node, Node)]] =
    val s = index.start(i)
    val h = data(s) & 0xff
    if (h >>> 5) != 5 then None
    else
      val ai = h & 31
      Some(new Iterator[(Node, Node)]:
        // the entries to come (-1: until the break), where the next key begins in the input and
        // its item number
        private var left = if ai == 31 then -1 else childCount(s, 5, ai) / 2
        private var at = s + headSize(ai)
        private var key = i + 1
        def hasNext: Boolean = if left < 0 then (data(at) & 0xff) != 0xff else left > 0
        def next(): (Node, Node) =
          val value = index.firstAt(key + 1, index.end(key))
          val entry = (node(key), node(value))
          at = index.end(value)
          if left > 0 then left -= 1
          if hasNext then key = index.firstAt(value + 1, at)
          entry)

  /** The value of item `i`, one level: its children are views, not values. */
  def valueAt(i: Int): Value =
    val s = index.start(i)
    val h = data(s) & 0xff
    val ai = h & 31
    (h >>> 5) match
      case 0 => UInt(unsigned(argBits(s, ai)))
      case 1 => NInt(-1 - unsigned(argBits(s, ai)))
      case 2 => ByteString(bytes(s, ai))
      case 3 => Text(text(s, ai))
      case 4 => Arr(vector(children(i, s, 4, ai)))
      case 5 => Map(pairs(children(i, s, 5, ai)))
      case 6 => Tag(unsigned(argBits(s, ai)), node(i + 1))
      case _ =>
        ai match
          case 20 => Bool(false)
          case 21 => Bool(true)
          case 22 => Null
          case 25 => Float(Float16.toFloat(argBits(s, ai).toInt).toDouble, FloatWidth.Half)
          case 26 =>
            Float(java.lang.Float.intBitsToFloat(argBits(s, ai).toInt).toDouble, FloatWidth.Single)
          case _ => Float(java.lang.Double.longBitsToDouble(argBits(s, ai)), FloatWidth.Double)
