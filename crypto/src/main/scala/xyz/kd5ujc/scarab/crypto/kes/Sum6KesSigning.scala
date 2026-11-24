// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.kes

import java.util.Arrays
import xyz.kd5ujc.scarab.codec.Bytes
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.math.ec.rfc8032.Ed25519

/** Sum6 KES signing keys in the binary layout of cardano-base (`SumKES` over `Ed25519`,
  * Blake2b-256): the `kes.skey` payload of `cardano-cli node key-gen-KES`.
  *
  * A key of depth `d` is `subKey(d-1) || r1 (32) || vk0 (32) || vk1 (32)`; depth 0 is the 32-byte
  * Ed25519 seed. `subKey` is the key of the half that contains the current period (the left half
  * until the middle, then the right half) and `r1` the seed of the not yet generated right half,
  * zero once it has been used. The depth-6 key is 608 bytes. A signature is the leaf signature
  * followed by the `vk0 || vk1` pair of every level, innermost first (448 bytes); the verification
  * key is `Blake2b-256(vk0 || vk1)` of the top level.
  *
  * The key is mutable on purpose: [[evolve]] overwrites the discarded material with zeros (the
  * seeds and sub-keys of periods that can no longer be signed), so a stolen key cannot sign the
  * past. [[forget]] erases everything. Not constant-time beyond what BouncyCastle gives.
  *
  * What the erasure covers: the key's own array and every array this object makes from it. The leaf
  * is signed and its public key derived from the key's array in place, hashes read the seeds where
  * they lie, and scratch copies are zeroed before they are dropped. What it does not cover: Bouncy
  * Castle's Ed25519 leaves the expanded leaf key in arrays of its own that it never clears, its
  * Blake2b leaves each hashed seed in the block it compressed, [[SigningKey.toBytes]] is a copy its
  * caller owns, and a collector that moves an array leaves the old bytes behind. So forward
  * security holds for the stored key, not against a reader of this process's memory (#230); the
  * reference keeps the key in locked memory outside the heap.
  */
object Sum6KesSigning:
  val Depth: Int = 6
  val Periods: Int = 1 << Depth
  val SeedBytes: Int = 32
  val KeyBytes: Int = size(Depth)
  val SignatureBytes: Int = 64 + 64 * Depth

  enum Failure:
    case MissingSeed
    case InvalidSeedLength(actual: Int)
    case MissingKey
    case InvalidKeyLength(actual: Int)
    case PeriodOutOfRange(period: BigInt)
    case SeedStateMismatch(depth: Int, period: Int, actual: SeedState)
    case VerificationKeyMismatch(depth: Int)
    case RightSeedMismatch(depth: Int)

    def message: String = this match
      case MissingSeed | InvalidSeedLength(_) => "KES seed must be 32 bytes"
      case MissingKey | InvalidKeyLength(_)   => s"Sum6 KES signing key must be $KeyBytes bytes"
      case PeriodOutOfRange(period) =>
        s"KES period $period is outside the remaining signing periods"
      case SeedStateMismatch(depth, period, actual) =>
        val state = actual match
          case SeedState.Erased  => "erased"
          case SeedState.Present => "present"
        s"level $depth: the right-half seed is $state at period $period"
      case VerificationKeyMismatch(depth) =>
        s"level $depth: the stored key does not match its verification key"
      case RightSeedMismatch(depth) =>
        s"level $depth: the right-half seed does not generate the stored verification key"

  enum SeedState:
    case Present, Erased

  /** Bytes of a key of the given depth. */
  private def size(depth: Int): Int = if depth == 0 then 32 else size(depth - 1) + 96

  /** A signing key evolves in place: `update` zeroes the material of the periods it leaves behind,
    * so that no copy of an earlier period's key survives (forward security). The key is therefore a
    * mutable object, and its current period is part of that state.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  final class SigningKey private[Sum6KesSigning] (
      private val material: Array[Byte],
      private var current: Int
  ):
    /** The period this key currently signs for. */
    def period: Int = current

    /** The 608-byte `kes.skey` payload for the current period. */
    def toBytes: Bytes = Bytes.fromArray(material)

    def verificationKey: Bytes = Bytes.fromArray(vkOf(material, 0, Depth))

    /** Sign `message` for the current period. */
    def sign(message: Bytes): Bytes =
      Bytes.fromArray(signAt(material, 0, Depth, current, message.toArray))

    /** Advance to `target` (not before the current period), erasing what cannot be used any more.
      */
    def evolveTo(target: BigInt): Either[Failure, Unit] =
      @scala.annotation.tailrec
      def step(): Unit =
        if current < target then
          update(material, 0, Depth, current)
          current += 1
          step()
      if target < current || target >= Periods then Left(Failure.PeriodOutOfRange(target))
      else Right(step())

    def evolve(): Either[Failure, Unit] = evolveTo(current + 1)

    /** A copy with its own storage (the original may be evolved or forgotten independently). */
    def copy: SigningKey = new SigningKey(material.clone(), current)

    def isForgotten: Boolean = material.forall(_ == 0)

    /** Erase the whole key. */
    def forget(): Unit = Arrays.fill(material, 0.toByte)

  /** `genKeyKES seed`: the key for period 0, deterministic in the 32-byte seed. */
  def generate(seed: Bytes): Either[Failure, SigningKey] =
    if seed == null then Left(Failure.MissingSeed)
    else if seed.size != SeedBytes then Left(Failure.InvalidSeedLength(seed.size))
    else
      val material = new Array[Byte](KeyBytes)
      val root = seed.toArray
      gen(root, material, 0, Depth)
      Arrays.fill(root, 0.toByte)
      Right(new SigningKey(material, 0))

  /** Verification key of the depth-`depth` tree generated from `seed` (test and diagnostics). */
  private[kes] def rootOfSeed(seed: Bytes, depth: Int): Bytes =
    val root = seed.toArray
    val vk = rootOf(root, depth)
    Arrays.fill(root, 0.toByte)
    Bytes.wrap(vk)

  /** [[rootOfSeed]] over a seed array, which stays its caller's to erase. */
  private def rootOf(seed: Array[Byte], depth: Int): Array[Byte] =
    val m = new Array[Byte](size(depth))
    gen(seed, m, 0, depth)
    val vk = vkOf(m, 0, depth)
    Arrays.fill(m, 0.toByte)
    vk

  /** Load a `kes.skey` payload that is at period `period` (0 for a fresh key). Every level's stored
    * verification keys are recomputed and checked, so a corrupt or mis-sized key is refused.
    */
  def fromBytes(bytes: Bytes, period: Int = 0): Either[Failure, SigningKey] =
    if bytes == null then Left(Failure.MissingKey)
    else if bytes.size != KeyBytes then Left(Failure.InvalidKeyLength(bytes.size))
    else if period < 0 || period >= Periods then Left(Failure.PeriodOutOfRange(period))
    else loaded(bytes.toArray, period)

  /** The key over `material`, which becomes the key's own array, if it is a key at `period`; erased
    * if it is not.
    */
  private def loaded(material: Array[Byte], period: Int): Either[Failure, SigningKey] =
    checkLevel(material, 0, Depth, period).fold(
      failure =>
        Arrays.fill(material, 0.toByte)
        Left(failure)
      ,
      _ => Right(new SigningKey(material, period))
    )

  /** Load a `kes.skey` payload of any period (a key this node has evolved and written back, see
    * `KesStore`): the period is read off the key itself, because [[update]] erases the `r1` seed of
    * a level exactly when the key moves into that level's right half, so the erased seeds, from the
    * outermost level in, spell the period in binary. The result is checked as in [[fromBytes]], so
    * a damaged key (or an all-zero, forgotten one) is refused. The cardano-cli file is the period-0
    * case.
    */
  def fromEvolved(bytes: Bytes): Either[Failure, SigningKey] =
    if bytes == null then Left(Failure.MissingKey)
    else if bytes.size != KeyBytes then Left(Failure.InvalidKeyLength(bytes.size))
    else
      // one copy of the payload: the period is read off the array that becomes the key's
      val material = bytes.toArray
      loaded(material, periodOf(material))

  /** The period an evolved key stands at, read from which levels' right-half seeds are erased. */
  private[kes] def periodOf(m: Array[Byte]): Int =
    (Depth to 1 by -1).foldLeft(0) { (acc, depth) =>
      val at = r1At(0, depth)
      val erased = (0 until 32).forall(i => m(at + i) == 0)
      if erased then acc + (1 << (depth - 1)) else acc
    }

  // ---- tree operations over the flat layout

  /** Blake2b-256 of the parts, read where they lie: this object makes no copy of a seed among them.
    * The digest does: into its buffer, which it clears when it finishes, and into the block of
    * words it compresses, which it does not.
    */
  private def hash(parts: Array[Byte]*): Array[Byte] =
    val digest = new Blake2bDigest(256)
    parts.foreach(part => digest.update(part, 0, part.length))
    val output = new Array[Byte](32)
    digest.doFinal(output, 0)
    output

  /** The public key of the leaf seed at `at`, derived from the array in place. */
  private def leafPublic(seed: Array[Byte], at: Int): Array[Byte] =
    val public = new Array[Byte](Ed25519.PUBLIC_KEY_SIZE)
    Ed25519.generatePublicKey(seed, at, public, 0)
    public

  private def r1At(offset: Int, depth: Int): Int = offset + size(depth - 1)
  private def vk0At(offset: Int, depth: Int): Int = r1At(offset, depth) + 32
  private def vk1At(offset: Int, depth: Int): Int = vk0At(offset, depth) + 32

  /** Verification key of the (sub-)key stored at `offset`. */
  private def vkOf(m: Array[Byte], offset: Int, depth: Int): Array[Byte] =
    if depth == 0 then leafPublic(m, offset)
    else
      hash(
        Arrays.copyOfRange(m, vk0At(offset, depth), vk0At(offset, depth) + 32),
        Arrays.copyOfRange(m, vk1At(offset, depth), vk1At(offset, depth) + 32)
      )

  private def expand(seed: Array[Byte]): (Array[Byte], Array[Byte]) =
    (hash(Array(1.toByte), seed), hash(Array(2.toByte), seed))

  /** Write the period-0 key for `seed` at `offset`. */
  private def gen(seed: Array[Byte], m: Array[Byte], offset: Int, depth: Int): Unit =
    if depth == 0 then System.arraycopy(seed, 0, m, offset, 32)
    else
      val (r0, r1) = expand(seed)
      gen(r0, m, offset, depth - 1)
      Arrays.fill(r0, 0.toByte)
      val vk0 = vkOf(m, offset, depth - 1)
      val scratch = new Array[Byte](size(depth - 1))
      gen(r1, scratch, 0, depth - 1)
      val vk1 = vkOf(scratch, 0, depth - 1)
      Arrays.fill(scratch, 0.toByte)
      System.arraycopy(r1, 0, m, r1At(offset, depth), 32)
      System.arraycopy(vk0, 0, m, vk0At(offset, depth), 32)
      System.arraycopy(vk1, 0, m, vk1At(offset, depth), 32)
      Arrays.fill(r1, 0.toByte)

  private def signAt(
      m: Array[Byte],
      offset: Int,
      depth: Int,
      period: Int,
      message: Array[Byte]
  ): Array[Byte] =
    if depth == 0 then
      // signed from the key's array in place: no key object holds a copy of the leaf seed
      val signature = new Array[Byte](Ed25519.SIGNATURE_SIZE)
      Ed25519.sign(m, offset, message, 0, message.length, signature, 0)
      signature
    else
      val half = 1 << (depth - 1)
      signAt(m, offset, depth - 1, period % half, message) ++
        Arrays.copyOfRange(m, vk0At(offset, depth), vk0At(offset, depth) + 32) ++
        Arrays.copyOfRange(m, vk1At(offset, depth), vk1At(offset, depth) + 32)

  /** Move the key at `offset` from `period` to `period + 1` (which exists for this depth). */
  private def update(m: Array[Byte], offset: Int, depth: Int, period: Int): Unit =
    val half = 1 << (depth - 1)
    if period + 1 < half then update(m, offset, depth - 1, period)
    else if period + 1 == half then
      // the left half is finished: erase it and generate the right half from its seed
      val r1 = Arrays.copyOfRange(m, r1At(offset, depth), r1At(offset, depth) + 32)
      Arrays.fill(m, offset, r1At(offset, depth), 0.toByte)
      gen(r1, m, offset, depth - 1)
      Arrays.fill(r1, 0.toByte)
      Arrays.fill(m, r1At(offset, depth), r1At(offset, depth) + 32, 0.toByte)
    else update(m, offset, depth - 1, period - half)

  /** Does the stored key at `offset` agree with its stored verification keys at `period`? */
  private def checkLevel(
      m: Array[Byte],
      offset: Int,
      depth: Int,
      period: Int
  ): Either[Failure, Unit] =
    if depth == 0 then Right(())
    else
      val half = 1 << (depth - 1)
      val inRight = period >= half
      val stored =
        Arrays.copyOfRange(
          m,
          if inRight then vk1At(offset, depth) else vk0At(offset, depth),
          (if inRight then vk1At(offset, depth) else vk0At(offset, depth)) + 32
        )
      val subPeriod = if inRight then period - half else period
      val seedAt = r1At(offset, depth)
      val seedZero = (0 until 32).forall(i => m(seedAt + i) == 0)
      if inRight != seedZero then
        Left(
          Failure.SeedStateMismatch(
            depth,
            period,
            if seedZero then SeedState.Erased else SeedState.Present
          )
        )
      else if !Arrays.equals(vkOf(m, offset, depth - 1), stored) then
        Left(Failure.VerificationKeyMismatch(depth))
      else if !inRight && !rightSeedGenerates(m, offset, depth) then
        Left(Failure.RightSeedMismatch(depth))
      else checkLevel(m, offset, depth - 1, subPeriod)

  /** Does the right-half seed stored at this level generate the stored `vk1`? */
  private def rightSeedGenerates(m: Array[Byte], offset: Int, depth: Int): Boolean =
    val seed = Arrays.copyOfRange(m, r1At(offset, depth), r1At(offset, depth) + 32)
    val root = rootOf(seed, depth - 1)
    Arrays.fill(seed, 0.toByte)
    Arrays.equals(
      root,
      Arrays.copyOfRange(m, vk1At(offset, depth), vk1At(offset, depth) + 32)
    )
