// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import java.security.MessageDigest

/** edwards25519 in exact integer arithmetic, written from RFC 8032 section 5.1
  * (https://www.rfc-editor.org/rfc/rfc8032#section-5.1): the field modulo p = 2^255 - 19 on
  * `BigInt`, points in extended homogeneous coordinates with the complete addition of 5.1.4, and
  * the encoding and decoding of 5.1.2 and 5.1.3. It shares no code with the vendored
  * curve25519-elisabeth arithmetic, the JDK's provider or Bouncy Castle, so the suites that check
  * those build their inputs and expected values with it. Variable time and slow: tests only.
  */
object Ed25519Model:
  val p: BigInt = (BigInt(1) << 255) - 19
  val L: BigInt = (BigInt(1) << 252) + BigInt("27742317777372353535851937790883648493")

  def mod(a: BigInt): BigInt = a.mod(p)
  def inverse(a: BigInt): BigInt = a.modPow(p - 2, p)

  val d: BigInt = mod(BigInt(-121665) * inverse(BigInt(121666)))
  val sqrtM1: BigInt = BigInt(2).modPow((p - 1) / 4, p)

  /** A point (X : Y : Z : T) with x = X/Z, y = Y/Z and x y = T/Z. */
  final case class Point(x: BigInt, y: BigInt, z: BigInt, t: BigInt):
    def affineX: BigInt = mod(x * inverse(z))
    def affineY: BigInt = mod(y * inverse(z))

    /** RFC 8032 5.1.4, the complete formula for a = -1. */
    def +(q: Point): Point =
      val a = mod((y - x) * (q.y - q.x))
      val b = mod((y + x) * (q.y + q.x))
      val c = mod(t * 2 * d * q.t)
      val dd = mod(z * 2 * q.z)
      val e = b - a
      val f = dd - c
      val g = dd + c
      val h = b + a
      Point(mod(e * f), mod(g * h), mod(f * g), mod(e * h))

    def unary_- : Point = Point(mod(-x), y, z, mod(-t))
    def -(q: Point): Point = this + (-q)

    /** [k]P for k >= 0, by double and add from the top bit. */
    def *(k: BigInt): Point =
      require(k >= 0, "the model multiplies by non-negative integers")
      (k.bitLength - 1 to 0 by -1).foldLeft(identity) { (acc, i) =>
        val doubled = acc + acc
        if k.testBit(i) then doubled + this else doubled
      }

    def same(q: Point): Boolean = mod(x * q.z - q.x * z) == 0 && mod(y * q.z - q.y * z) == 0
    def isIdentity: Boolean = same(identity)
    def isSmallOrder: Boolean = (this * 8).isIdentity
    def inPrimeSubgroup: Boolean = (this * L).isIdentity

    /** The order of a point of small order: the least n of 1, 2, 4, 8 with [n]P the identity. */
    def smallOrder: Int = Vector(1, 2, 4, 8).find(n => (this * n).isIdentity).getOrElse(0)

    /** RFC 8032 5.1.2: y little-endian, the low bit of x in bit 255. */
    def encode: Array[Byte] =
      val out = le(affineY, 32)
      if affineX.testBit(0) then out(31) = (out(31) | 0x80).toByte
      out

  object Point:
    def affine(x: BigInt, y: BigInt): Point = Point(mod(x), mod(y), 1, mod(x * y))

  val identity: Point = Point.affine(0, 1)

  /** The x of a point with this y whose low bit is `sign`, RFC 8032 5.1.3 steps 2 to 4 without its
    * refusal of x = 0 with sign 1 (the caller decides that), or nothing when y is no point's.
    */
  def recoverX(y: BigInt, sign: Int): Option[BigInt] =
    val u = mod(y * y - 1)
    val v = mod(d * y * y + 1)
    val candidate = mod(u * v.modPow(3, p) * (u * v.modPow(7, p)).modPow((p - 5) / 8, p))
    val vx2 = mod(v * candidate * candidate)
    val root =
      if vx2 == u then Some(candidate)
      else if vx2 == mod(-u) then Some(mod(candidate * sqrtM1))
      else None
    root.map(x => if x.testBit(0) == (sign == 1) then x else mod(-x))

  /** How the y of an encoding is read. */
  enum Reading:
    /** RFC 8032 5.1.3: y must be below p, and x = 0 with the sign bit set is refused. */
    case Strict

    /** y modulo p, and x = 0 whatever the sign bit: how libsodium's `ge25519_frombytes` and the
      * vendored `CompressedEdwardsY.decompress` read an encoding.
      */
    case Lenient

  def y(encoding: Array[Byte]): BigInt = fromLe(encoding) & ((BigInt(1) << 255) - 1)
  def sign(encoding: Array[Byte]): Int = (encoding(31) >> 7) & 1

  def decode(encoding: Array[Byte], reading: Reading): Option[Point] =
    val raw = y(encoding)
    val s = sign(encoding)
    reading match
      case Reading.Strict =>
        if raw >= p then None
        else recoverX(raw, s).filter(x => !(x == 0 && s == 1)).map(Point.affine(_, raw))
      case Reading.Lenient => recoverX(mod(raw), s).map(Point.affine(_, mod(raw)))

  val basepoint: Point =
    val y = mod(4 * inverse(5))
    Point.affine(recoverX(y, 0).get, y)

  /** A point of order 8: the y of libsodium's blacklist entry
    * `26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05`, sign 0.
    */
  val order8: Point =
    decode(
      hex("26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05"),
      Reading.Strict
    ).get

  /** The eight points of order dividing 8, [i]T for i = 0 to 7 and T of order 8. */
  val torsion: Vector[Point] = Vector.tabulate(8)(i => order8 * i)

  def le(value: BigInt, size: Int): Array[Byte] =
    require(value >= 0 && value.bitLength <= 8 * size, s"$value does not fit $size bytes")
    Array.tabulate(size)(i => ((value >> (8 * i)) & 255).toByte)

  def fromLe(bytes: Array[Byte]): BigInt = BigInt(1, bytes.reverse)

  def hex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  def sha512(parts: Array[Byte]*): Array[Byte] =
    val digest = MessageDigest.getInstance("SHA-512")
    parts.foreach(digest.update)
    digest.digest()

  /** k = SHA-512(R || A || M) modulo L, over the encodings as given. */
  def challenge(r: Array[Byte], a: Array[Byte], message: Array[Byte]): BigInt =
    fromLe(sha512(r, a, message)).mod(L)

  /** RFC 8032 5.1.5: the clamped secret scalar of a 32-byte seed. */
  def secretScalar(seed: Array[Byte]): BigInt =
    val h = sha512(seed).take(32)
    h(0) = (h(0) & 248).toByte
    h(31) = ((h(31) & 127) | 64).toByte
    fromLe(h)

  /** `R || S` with S = r + k a modulo L, k over the encodings as given: what a signer with discrete
    * logarithm `a` of the key's prime-order part and nonce `r` writes for those encodings.
    */
  def signature(
      key: Array[Byte],
      a: BigInt,
      r: Array[Byte],
      nonce: BigInt,
      message: Array[Byte]
  ): Array[Byte] =
    r ++ le((nonce + challenge(r, key, message) * a).mod(L), 32)
