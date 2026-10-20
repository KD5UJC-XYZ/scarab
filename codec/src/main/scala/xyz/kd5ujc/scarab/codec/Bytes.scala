// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

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

  /** The first four bytes, big-endian, with the sign bit flipped so that the signed order of the
    * `Int` is the unsigned order of the bytes; a shorter value is padded with zeros. Computed on
    * the first comparison and kept (0 is "not yet", and the one value it could be, the bytes `80 00
    * 00 00`, is computed again each time). It fills the padding the object has anyway (the header,
    * the array, the offset, the length and the hash take 28 of 32 bytes), so it costs no memory,
    * and a comparison that the prefixes settle, which for hashes is nearly every one, reads two
    * objects and no arrays.
    */
  private var cachedPrefix = 0

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

  private def prefix: Int =
    var p = cachedPrefix
    if p == 0 then
      var acc = 0
      var i = 0
      while i < 4 do
        acc = (acc << 8) | (if i < size then array(offset + i) & 0xff else 0)
        i += 1
      p = acc ^ Int.MinValue
      cachedPrefix = p
    p

  /** Lexicographic unsigned comparison: negative, zero or positive. The prefixes settle most
    * comparisons; the rest is `Arrays.compareUnsigned`, a vectorised intrinsic. It matters to the
    * sorted maps of the ledger, which repeatedly compare 28-byte or 32-byte keys during lookup.
    */
  def compareTo(other: Bytes): Int =
    val p = prefix
    val q = other.prefix
    if p != q then Integer.compare(p, q)
    else
      Arrays.compareUnsigned(
        array,
        offset,
        offset + size,
        other.array,
        other.offset,
        other.offset + other.size
      )

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

  /** Lexicographic by unsigned byte, a shorter prefix first: the order of the ledger's `ByteString`
    * keys, and the order of the lower-case hex of equal-length strings, without building the hex.
    */
  given Ordering[Bytes] = (a, b) => a.compareTo(b)

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

  enum HexError:
    case OddLength(length: Int)
    case InvalidCharacter(index: Int, character: Char)
    def message: String = this match
      case OddLength(length) => s"hex must have an even number of characters (got $length)"
      case InvalidCharacter(index, character) => s"invalid hex character '$character' at $index"

  def fromHex(hex: String): Either[HexError, Bytes] =
    if hex.length % 2 != 0 then Left(HexError.OddLength(hex.length))
    else if hex.exists(c => Character.digit(c, 16) < 0 || c > 127) then
      val index = hex.indexWhere(c => Character.digit(c, 16) < 0 || c > 127)
      Left(HexError.InvalidCharacter(index, hex.charAt(index)))
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
