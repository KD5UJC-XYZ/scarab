// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.kes.secret

import java.security.MessageDigest
import java.util.Arrays
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning.{Failure, SeedState}
import xyz.kd5ujc.scarab.crypto.secret.Scratch

/** The secret half of a Sum6 KES signing key (cardano-base `SumKES` over `Ed25519`, Blake2b-256),
  * in the binary layout of `kes.skey`: a key of depth `d` is `subKey(d-1) || r1 (32) || vk0 (32) ||
  * vk1 (32)`, depth 0 the 32-byte Ed25519 seed of the leaf that signs now. `subKey` is the key of
  * the half that holds the current period and `r1` the seed of the right half, zero once the key
  * has moved into it. The depth-6 key is 608 bytes.
  *
  * Between operations the key holds that array, its period and whether it is forgotten, and nothing
  * else. Every value an operation computes from it lives in a [[Scratch]] and is zeroed when the
  * operation ends, on failure too: the seeds a generation expands and the subtrees it builds
  * ([[Blake2b256]]), and the leaf's expanded key, scalar, nonce and their digits and encodings
  * ([[Leaf]]). One operation has one scratch, so what it held is gone when it returns.
  *
  * Evolution replaces what the new period cannot use: moving into the right half of a level zeroes
  * the left half, generates the right half over it from `r1`, and zeroes `r1`. Each step is built
  * in the scratch and then copied over the key's array with the period, so the array only ever
  * holds one period's key whole: the seeds of the current period and the ones after it, and none of
  * a period the key has left (forward security).
  *
  * Every read and write of the array, the period and the flag holds this key's monitor, and a
  * signature and an evolution hold it until their scratch is erased, so a sign, an evolution, a
  * copy and [[forget]] each see the key whole, before or after another, and none returns while
  * another still holds what it computed: a signature is the key's at its period, or a refusal once
  * it is forgotten, never one from a key part evolved or part zeroed. [[forget]] zeroes the array,
  * and the key then signs and evolves no more.
  *
  * What this does not cover: the curve library's immutable intermediates (the field elements and
  * points of the multiplications), values the JIT kept in registers or spilled to the stack, copies
  * a moving collector made of an array before it was zeroed, a payload copied into a caller's
  * scratch ([[payloadIn]]), which is the caller's to erase, and [[toBytes]], a copy its caller
  * owns.
  */
@SuppressWarnings(Array("org.wartremover.warts.Var"))
private[kes] final class Sum6Key private (material: Array[Byte], start: Int):
  import Sum6Key.*

  // FUN-2 (d): the key evolves in place, zeroing the periods it leaves, and forget zeroes it all;
  // the array, the period and the flag are read and written under this monitor
  private var current = start
  private var forgotten = false

  /** The period this key signs for now. */
  def period: Int = synchronized(current)

  def isForgotten: Boolean = synchronized(forgotten)

  /** Blake2b-256 of the top level's `vk0 || vk1`, which every signature carries. */
  def verificationKey: Bytes = synchronized {
    Scratch.within { s =>
      val vk = s.buffer(Blake2b256.DigestBytes)
      Blake2b256.hash(s)(material, vk0At(0, Depth), 64, s.array(vk), 0)
      s.publish(vk)
    }
  }

  /** The signature of `message` for the current period: the leaf's Ed25519 signature followed by
    * the `vk0 || vk1` pair of every level, innermost first; a refusal once the key is forgotten.
    */
  def sign(message: Bytes): Either[Failure, Bytes] = signing(message)(Scratch.within[Option[Bytes]])

  /** [[sign]] with `scratch` running the computation: the monitor is held until the scratch is
    * erased, so an evolution or a forget does not return while a signature still holds the leaf's
    * seed, scalar or nonce. A suite of this package passes an observed scratch.
    */
  private[secret] def signing(message: Bytes)(
      scratch: (Scratch => Option[Bytes]) => Option[Bytes]
  ): Either[Failure, Bytes] =
    synchronized(scratch(s => signedIn(s, message))).toRight(Failure.Forgotten)

  /** [[sign]] in the scratch `s`; none once the key is forgotten. `signed` runs once the signature
    * is computed, before it is published: a suite of this package sees there what the computation
    * holds.
    */
  private[secret] def signedIn(
      s: Scratch,
      message: Bytes,
      signed: () => Unit = () => ()
  ): Option[Bytes] =
    synchronized {
      Option.unless(forgotten) {
        val signature = s.buffer(SignatureBytes)
        signAt(s)(material, 0, Depth, message.toArray, s.array(signature), 0)
        signed()
        s.publish(signature)
      }
    }

  /** Move to `target`, which is not before the current period and is one of the key's periods,
    * erasing what the periods after it cannot use.
    */
  def evolveTo(target: Int): Evolution = synchronized {
    if target < current || target >= Periods then Evolution.OutOfRange(target)
    else if forgotten then Evolution.Forgotten
    else
      Scratch.within(s => evolvedIn(s, target))
      Evolution.Moved
  }

  /** The steps of [[evolveTo]] to `target`, checked, in the scratch `s`. Each step builds the next
    * period's key in the scratch, from a copy, and only then puts it in the key's array, with the
    * period, in one copy of fixed length that cannot fail: the key is wholly the old period's or
    * wholly the new one's, never a mixture, whatever the build throws. The old period's material is
    * gone from the key's array once the step is committed, and from the scratch when the evolution
    * returns. `erased` runs inside a build, just after the half it leaves is zeroed; `stepped`
    * after each step: hooks for the suites of this package.
    */
  private[secret] def evolvedIn(
      s: Scratch,
      target: Int,
      erased: () => Unit = () => (),
      stepped: () => Unit = () => ()
  ): Unit =
    synchronized {
      @tailrec def step(): Unit =
        if current < target then
          val next = s.buffer(KeyBytes)
          System.arraycopy(material, 0, s.array(next), 0, KeyBytes)
          update(s)(s.array(next), 0, Depth, current, erased)
          System.arraycopy(s.array(next), 0, material, 0, KeyBytes)
          current += 1
          stepped()
          step()
      step()
    }

  /** [[evolveTo]] the period after the current one. */
  def evolve(): Evolution = synchronized(evolveTo(current + 1))

  /** A key with its own array, at the same period, forgotten if this one is. */
  def copy: Sum6Key = synchronized {
    val other = new Sum6Key(material.clone(), current)
    if forgotten then other.forget()
    other
  }

  /** Zero the whole key. */
  def forget(): Unit = synchronized {
    Arrays.fill(material, 0.toByte)
    forgotten = true
  }

  /** The 608-byte `kes.skey` payload, copied into a buffer of `s` (zero once the key is forgotten),
    * which `s` erases.
    */
  def payloadIn(s: Scratch): s.Buffer = synchronized {
    val payload = s.buffer(KeyBytes)
    System.arraycopy(material, 0, s.array(payload), 0, KeyBytes)
    payload
  }

  /** The 608-byte payload as a value its caller owns, which nothing erases. */
  def toBytes: Bytes = synchronized(Bytes.fromArray(material))

  /** Whether `other` holds the same 608 bytes: their Blake2b-256 digests, each computed under its
    * key's monitor in a scratch erased before the monitor is released, compared in constant time.
    * No copy of either key's bytes is outside its monitor, and neither monitor is taken inside the
    * other.
    */
  def sameMaterial(other: Sum6Key): Boolean =
    MessageDigest.isEqual(materialDigest.toArray, other.materialDigest.toArray)

  private def materialDigest: Bytes = synchronized {
    Scratch.within { s =>
      val digest = s.buffer(Blake2b256.DigestBytes)
      Blake2b256.hash(s)(material, 0, KeyBytes, s.array(digest), 0)
      s.publish(digest)
    }
  }

  // what the suites of this package may ask of the key's array, under its monitor, without
  // holding it: whether it is all zero, starts with `prefix`, or holds `window` anywhere

  private[secret] def storedIsZero: Boolean = synchronized(isZero(material, 0, KeyBytes))

  private[secret] def storedStartsWith(prefix: Array[Byte]): Boolean = synchronized {
    prefix.length <= KeyBytes && differs(material, 0, prefix) == 0
  }

  private[secret] def storedHolds(window: Array[Byte]): Boolean = synchronized {
    (0 to KeyBytes - window.length).map(at => differs(material, at, window)).contains(0)
  }

private[kes] object Sum6Key:
  val Depth: Int = 6
  val Periods: Int = 1 << Depth
  val SeedBytes: Int = 32
  val KeyBytes: Int = size(Depth)
  val SignatureBytes: Int = Leaf.SignatureBytes + 64 * Depth

  /** What an evolution did. */
  enum Evolution:
    case Moved

    /** The target is before the current period, or after the last. */
    case OutOfRange(target: Int)

    /** The key is forgotten: it evolves no more. */
    case Forgotten

  /** Bytes of a key of the given depth. */
  private def size(depth: Int): Int = if depth == 0 then SeedBytes else size(depth - 1) + 96

  private def r1At(offset: Int, depth: Int): Int = offset + size(depth - 1)
  private def vk0At(offset: Int, depth: Int): Int = r1At(offset, depth) + 32
  private def vk1At(offset: Int, depth: Int): Int = vk0At(offset, depth) + 32

  /** `genKeyKES`: the key for period 0 of the 32-byte seed at `seed(from, from + 32)`. The tree is
    * built in a scratch and copied into the key's array once it is complete.
    */
  def generated(seed: Array[Byte], from: Int): Sum6Key =
    val material = new Array[Byte](KeyBytes)
    Scratch.within(s => generatedIn(s, seed, from, material))
    new Sum6Key(material, 0)

  /** [[generated]] in the scratch `s`, into `material`. */
  private[secret] def generatedIn(
      s: Scratch,
      seed: Array[Byte],
      from: Int,
      material: Array[Byte]
  ): Unit =
    val built = s.buffer(KeyBytes)
    gen(s)(seed, from, s.array(built), 0, Depth)
    System.arraycopy(s.array(built), 0, material, 0, KeyBytes)

  /** The key of the 608 bytes `payload(from, from + 608)` at `at`, or, when none is given, at the
    * period its erased seeds spell, if every level's stored verification keys are its own. The
    * bytes are checked in a scratch and copied into the key's array only once they pass, so a
    * refused payload leaves no copy.
    */
  def loaded(payload: Array[Byte], from: Int, at: Option[Int]): Either[Failure, Sum6Key] =
    val material = new Array[Byte](KeyBytes)
    Scratch.within(s => loadedIn(s, payload, from, at, material)).map(new Sum6Key(material, _))

  /** [[loaded]] in the scratch `s`: the period, with the payload copied into `material` once it
    * passes the checks.
    */
  private[secret] def loadedIn(
      s: Scratch,
      payload: Array[Byte],
      from: Int,
      at: Option[Int],
      material: Array[Byte]
  ): Either[Failure, Int] =
    val m = s.buffer(KeyBytes)
    System.arraycopy(payload, from, s.array(m), 0, KeyBytes)
    val period = at.getOrElse(periodOf(s.array(m)))
    checkLevel(s)(s.array(m), 0, Depth, period).map { _ =>
      System.arraycopy(s.array(m), 0, material, 0, KeyBytes)
      period
    }

  /** The verification key of the depth-`depth` tree of the 32-byte seed at `seed(from, from + 32)`.
    */
  def rootOf(seed: Array[Byte], from: Int, depth: Int): Bytes = Scratch.within { s =>
    val tree = s.buffer(size(depth))
    gen(s)(seed, from, s.array(tree), 0, depth)
    val vk = s.buffer(32)
    vkInto(s)(s.array(tree), 0, depth, s.array(vk), 0)
    s.publish(vk)
  }

  /** The period a key's bytes stand at, read from which levels' right-half seeds are erased: an
    * evolution erases a level's `r1` exactly when it moves into that level's right half, so the
    * erased seeds, from the outermost level in, spell the period in binary.
    */
  private[secret] def periodOf(m: Array[Byte]): Int =
    (Depth to 1 by -1).foldLeft(0) { (acc, depth) =>
      if isZero(m, r1At(0, depth), SeedBytes) then acc + (1 << (depth - 1)) else acc
    }

  /** Whether `n` bytes of `m` from `at` are all zero, reading every one of them. */
  private def isZero(m: Array[Byte], at: Int, n: Int): Boolean =
    (0 until n).foldLeft(0)((acc, i) => acc | m(at + i)) == 0

  /** Zero exactly when the bytes of `m` from `at` are `expected`, every one of them read. */
  private def differs(m: Array[Byte], at: Int, expected: Array[Byte]): Int =
    expected.indices.foldLeft(0)((acc, i) => acc | (m(at + i) ^ expected(i)))

  /** The verification key of the (sub-)key at `m(offset)` of depth `depth`, into `out` at `outAt`:
    * the leaf's public key, or Blake2b-256 of the level's `vk0 || vk1`.
    */
  private def vkInto(s: Scratch)(
      m: Array[Byte],
      offset: Int,
      depth: Int,
      out: Array[Byte],
      outAt: Int
  ): Unit =
    if depth == 0 then Leaf.publicKey(s)(m, offset, out, outAt)
    else Blake2b256.hash(s)(m, vk0At(offset, depth), 64, out, outAt)

  /** The seeds of the two halves of the seed at `seed(at, at + 32)`: Blake2b-256 of `1 || seed` and
    * of `2 || seed` (cardano-base `expandHashWith`), in `s`.
    */
  private def expand(s: Scratch)(seed: Array[Byte], at: Int): (s.Buffer, s.Buffer) =
    val input = s.buffer(1 + SeedBytes)
    System.arraycopy(seed, at, s.array(input), 1, SeedBytes)
    val r0 = s.buffer(SeedBytes)
    val r1 = s.buffer(SeedBytes)
    s.array(input)(0) = 1
    Blake2b256.hash(s)(s.array(input), 0, 1 + SeedBytes, s.array(r0), 0)
    s.array(input)(0) = 2
    Blake2b256.hash(s)(s.array(input), 0, 1 + SeedBytes, s.array(r1), 0)
    (r0, r1)

  /** Write the period-0 key of depth `depth` of the seed at `seed(at, at + 32)` at `m(offset)`:
    * every byte of its layout. The right half's tree, built only for its verification key, is a
    * buffer of `s`.
    */
  private def gen(s: Scratch)(
      seed: Array[Byte],
      at: Int,
      m: Array[Byte],
      offset: Int,
      depth: Int
  ): Unit =
    if depth == 0 then System.arraycopy(seed, at, m, offset, SeedBytes)
    else
      val (r0, r1) = expand(s)(seed, at)
      gen(s)(s.array(r0), 0, m, offset, depth - 1)
      vkInto(s)(m, offset, depth - 1, m, vk0At(offset, depth))
      val right = s.buffer(size(depth - 1))
      gen(s)(s.array(r1), 0, s.array(right), 0, depth - 1)
      vkInto(s)(s.array(right), 0, depth - 1, m, vk1At(offset, depth))
      System.arraycopy(s.array(r1), 0, m, r1At(offset, depth), SeedBytes)

  /** The signature of `message` by the (sub-)key at `m(offset)`, into `out` at `outAt`: the leaf's
    * signature, then each level's `vk0 || vk1`, innermost first.
    */
  private def signAt(s: Scratch)(
      m: Array[Byte],
      offset: Int,
      depth: Int,
      message: Array[Byte],
      out: Array[Byte],
      outAt: Int
  ): Unit =
    if depth == 0 then Leaf.sign(s)(m, offset, message, out, outAt)
    else
      signAt(s)(m, offset, depth - 1, message, out, outAt)
      System.arraycopy(
        m,
        vk0At(offset, depth),
        out,
        outAt + Leaf.SignatureBytes + 64 * (depth - 1),
        64
      )

  /** Move the key at `m(offset)` from `period` to `period + 1`, which exists for this depth;
    * `erased` runs once the half it leaves is zeroed.
    */
  private def update(s: Scratch)(
      m: Array[Byte],
      offset: Int,
      depth: Int,
      period: Int,
      erased: () => Unit
  ): Unit =
    val half = 1 << (depth - 1)
    if period + 1 < half then update(s)(m, offset, depth - 1, period, erased)
    else if period + 1 == half then
      // the left half is finished: erase it, and generate the right half over it from its seed,
      // which lies after it and is read before anything is written there
      Arrays.fill(m, offset, r1At(offset, depth), 0.toByte)
      erased()
      gen(s)(m, r1At(offset, depth), m, offset, depth - 1)
      Arrays.fill(m, r1At(offset, depth), r1At(offset, depth) + SeedBytes, 0.toByte)
    else update(s)(m, offset, depth - 1, period - half, erased)

  /** Does the key at `m(offset)` agree with its stored verification keys at `period`: the right
    * half's seed present exactly while the key is in the left half, the verification key of the
    * current half the one stored for it, and, in the left half, the right half's seed the one that
    * generates the stored `vk1`.
    */
  private def checkLevel(s: Scratch)(
      m: Array[Byte],
      offset: Int,
      depth: Int,
      period: Int
  ): Either[Failure, Unit] =
    if depth == 0 then Right(())
    else
      val half = 1 << (depth - 1)
      val inRight = period >= half
      val seedZero = isZero(m, r1At(offset, depth), SeedBytes)
      if inRight != seedZero then
        Left(
          Failure.SeedStateMismatch(
            depth,
            period,
            if seedZero then SeedState.Erased else SeedState.Present
          )
        )
      else if !holds(s)(
          m,
          offset,
          depth - 1,
          m,
          if inRight then vk1At(offset, depth) else vk0At(offset, depth)
        )
      then Left(Failure.VerificationKeyMismatch(depth))
      else if !inRight && !rightSeedGenerates(s)(m, offset, depth) then
        Left(Failure.RightSeedMismatch(depth))
      else checkLevel(s)(m, offset, depth - 1, if inRight then period - half else period)

  /** Is the verification key of the depth-`depth` (sub-)key at `tree(offset)` the 32 bytes at
    * `m(storedAt)`? Compared in constant time.
    */
  private def holds(s: Scratch)(
      tree: Array[Byte],
      offset: Int,
      depth: Int,
      m: Array[Byte],
      storedAt: Int
  ): Boolean =
    val vk = s.buffer(32)
    val stored = s.buffer(32)
    vkInto(s)(tree, offset, depth, s.array(vk), 0)
    System.arraycopy(m, storedAt, s.array(stored), 0, 32)
    MessageDigest.isEqual(s.array(vk), s.array(stored))

  /** Does the right half's seed stored at this level generate the stored `vk1`? Its tree is a
    * buffer of `s`.
    */
  private def rightSeedGenerates(s: Scratch)(m: Array[Byte], offset: Int, depth: Int): Boolean =
    val right = s.buffer(size(depth - 1))
    gen(s)(m, r1At(offset, depth), s.array(right), 0, depth - 1)
    holds(s)(s.array(right), 0, depth - 1, m, vk1At(offset, depth))
