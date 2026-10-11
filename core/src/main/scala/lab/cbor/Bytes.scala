// SPDX-License-Identifier: Apache-2.0
package lab.cbor

import java.util.Arrays

/** Immutable bytes: a window onto an array nobody mutates. Arrays crossing the public boundary
  * (`fromArray`, `toArray`) are copied; `wrap`, `slice` and `unsafeArray` share storage and are for
  * code that promises not to write to it. Equality is by content and the hash is cached.
  *
  * The representation is compact (a 32-byte hash is 32 bytes plus a small header), which matters
  * for the ledger: the UTxO set is millions of these.
  */
final class Bytes private (
    private val array: Array[Byte],
    private val offset: Int,
    private val length: Int
) extends Serializable:
  private var cachedHash = 0

  /** A `Bytes` built from a null vector (programming misuse) fails on first use, as it always did.
    */
  def size: Int =
    if array eq null then throw new NullPointerException("malformed Bytes")
    length

  /** The bytes as a vector (allocates; kept for code that wants collection operations). */
  def value: Vector[Byte] =
    if array eq null then null else Vector.tabulate(length)(i => array(offset + i))

  def apply(i: Int): Byte =
    if i < 0 || i >= size then throw new IndexOutOfBoundsException(i.toString)
    array(offset + i)

  def isEmpty: Boolean = size == 0
  def nonEmpty: Boolean = size != 0

  /** A fresh copy of the content. */
  def toArray: Array[Byte] = Arrays.copyOfRange(array, offset, offset + size)

  /** Copy the content into `target` at `at`. */
  def copyTo(target: Array[Byte], at: Int): Unit = System.arraycopy(array, offset, target, at, size)

  /** The backing array and the start of this window in it; read-only for callers. */
  def unsafeArray: Array[Byte] = array
  def unsafeOffset: Int = offset

  /** Feed the content to a consumer of (array, offset, length) without copying. */
  inline def withRange[A](inline f: (Array[Byte], Int, Int) => A): A = f(array, offset, size)

  /** Zero-copy sub-range; it keeps the whole backing array alive (see [[compact]]). */
  def slice(from: Int, until: Int): Bytes =
    val lo = math.max(0, from)
    val hi = math.min(size, until)
    if lo >= hi then Bytes.empty
    else if lo == 0 && hi == size then this
    else new Bytes(array, offset + lo, hi - lo)

  def take(n: Int): Bytes = slice(0, n)
  def drop(n: Int): Bytes = slice(n, size)

  /** A copy that owns exactly its bytes, so it no longer pins a larger buffer. */
  def compact: Bytes =
    if offset == 0 && size == array.length then this else new Bytes(toArray, 0, size)

  def ++(other: Bytes): Bytes =
    if other.size == 0 then this
    else if size == 0 then other
    else
      val out = new Array[Byte](size + other.size)
      System.arraycopy(array, offset, out, 0, size)
      System.arraycopy(other.array, other.offset, out, size, other.size)
      new Bytes(out, 0, out.length)

  def hex: String =
    val digits = "0123456789abcdef"
    val sb = new java.lang.StringBuilder(size * 2)
    var i = 0
    while i < size do
      val b = array(offset + i) & 0xff
      sb.append(digits.charAt(b >>> 4)).append(digits.charAt(b & 0xf))
      i += 1
    sb.toString

  /** Lexicographic unsigned comparison. */
  def compareTo(other: Bytes): Int =
    val n = math.min(size, other.size)
    var i = 0
    while i < n do
      val c = (array(offset + i) & 0xff) - (other.array(other.offset + i) & 0xff)
      if c != 0 then return c
      i += 1
    size - other.size

  override def equals(that: Any): Boolean = that match
    case o: Bytes =>
      (this eq o) || (size == o.size && (cachedHash == 0 || o.cachedHash == 0 || cachedHash == o.cachedHash) &&
        Arrays.equals(array, offset, offset + size, o.array, o.offset, o.offset + o.size))
    case _ => false

  override def hashCode(): Int =
    var h = cachedHash
    if h == 0 then
      h = 1
      var i = 0
      while i < size do
        h = 31 * h + array(offset + i)
        i += 1
      if h == 0 then h = 1
      cachedHash = h
    h

  override def toString: String = s"Bytes($hex)"

object Bytes:
  val empty: Bytes = new Bytes(new Array[Byte](0), 0, 0)

  /** Compatibility constructor from a vector. */
  def apply(bytes: Vector[Byte]): Bytes =
    if bytes eq null then new Bytes(null, 0, 0)
    else if bytes.isEmpty then empty
    else
      val a = new Array[Byte](bytes.size)
      var i = 0
      bytes.foreach { b =>
        a(i) = b; i += 1
      }
      new Bytes(a, 0, a.length)

  /** Copies. */
  def fromArray(bytes: Array[Byte]): Bytes =
    if bytes.length == 0 then empty else new Bytes(bytes.clone(), 0, bytes.length)

  /** Takes ownership of `bytes`: the caller must not write to it afterwards. */
  def wrap(bytes: Array[Byte]): Bytes =
    if bytes.length == 0 then empty else new Bytes(bytes, 0, bytes.length)

  /** Shares `bytes[from, from + length)`; the caller must not write to it afterwards. */
  def wrap(bytes: Array[Byte], from: Int, length: Int): Bytes =
    if length == 0 then empty else new Bytes(bytes, from, length)

  def fromHex(hex: String): Either[String, Bytes] =
    if hex.length % 2 != 0 then Left("hex must have an even number of characters")
    else if !hex.forall(c =>
        (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
      )
    then Left("invalid hex character")
    else
      val out = new Array[Byte](hex.length / 2)
      var i = 0
      while i < out.length do
        out(i) = ((Character.digit(hex.charAt(2 * i), 16) << 4) | Character.digit(
          hex.charAt(2 * i + 1),
          16
        )).toByte
        i += 1
      Right(wrap(out))
