// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.kes

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.secret.Sum6Key
import xyz.kd5ujc.scarab.crypto.secret.Scratch

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
  * The key is mutable on purpose: evolving overwrites the discarded material with zeros (the seeds
  * and sub-keys of periods that can no longer be signed), so a stolen key cannot sign the past.
  * [[SigningKey.forget]] erases everything. What reads the seeds is [[secret.Sum6Key]], in a
  * package of its own that may name no variable-time routine (`scripts/check_crypto_guard.py`): it
  * computes every value it derives from them (the hashes of the seed halves, the leaves' expanded
  * keys, scalars and nonces, the subtrees it builds) in storage that is zeroed when the operation
  * ends, and serializes signing, evolving and forgetting on the key. See it for what is erased and
  * what is not. The reference keeps the key in locked memory outside the heap; a JVM may copy an
  * array before it is zeroed, so the erasure is of the arrays this code owns.
  */
object Sum6KesSigning:
  val Depth: Int = Sum6Key.Depth
  val Periods: Int = Sum6Key.Periods
  val SeedBytes: Int = Sum6Key.SeedBytes
  val KeyBytes: Int = Sum6Key.KeyBytes
  val SignatureBytes: Int = Sum6Key.SignatureBytes

  enum Failure:
    case MissingSeed
    case InvalidSeedLength(actual: Int)
    case MissingKey
    case InvalidKeyLength(actual: Int)
    case PeriodOutOfRange(period: BigInt)
    case SeedStateMismatch(depth: Int, period: Int, actual: SeedState)
    case VerificationKeyMismatch(depth: Int)
    case RightSeedMismatch(depth: Int)

    /** The key is forgotten (its material zeroed): it signs and evolves no more. */
    case Forgotten

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
      case Forgotten => "the KES signing key has been erased"

  enum SeedState:
    case Present, Erased

  /** A signing key, which evolves in place: an evolution zeroes the material of the periods it
    * leaves behind, so that no copy of an earlier period's key survives in it (forward security).
    */
  final class SigningKey private[Sum6KesSigning] (private val key: Sum6Key):
    /** The period this key currently signs for. */
    def period: Int = key.period

    /** The 608-byte `kes.skey` payload for the current period: a copy its caller owns, which
      * nothing erases. The node writes the key through [[payloadIn]] instead.
      */
    def toBytes: Bytes = key.toBytes

    /** The 608-byte payload copied into a buffer of `s` (zero once the key is forgotten), which `s`
      * zeroes when it ends: what the node writes the key file from.
      */
    private[scarab] def payloadIn(s: Scratch): s.Buffer = key.payloadIn(s)

    def verificationKey: Bytes = key.verificationKey

    /** Sign `message` for the current period; refused once the key is forgotten. */
    def sign(message: Bytes): Either[Failure, Bytes] = key.sign(message)

    /** Advance to `target` (not before the current period), erasing what cannot be used any more.
      */
    def evolveTo(target: BigInt): Either[Failure, Unit] =
      if !target.isValidInt then Left(Failure.PeriodOutOfRange(target))
      else evolution(key.evolveTo(target.toInt))

    def evolve(): Either[Failure, Unit] = evolution(key.evolve())

    private def evolution(done: Sum6Key.Evolution): Either[Failure, Unit] = done match
      case Sum6Key.Evolution.Moved              => Right(())
      case Sum6Key.Evolution.OutOfRange(target) => Left(Failure.PeriodOutOfRange(target))
      case Sum6Key.Evolution.Forgotten          => Left(Failure.Forgotten)

    /** A copy with its own storage (the original may be evolved or forgotten independently). */
    def copy: SigningKey = new SigningKey(key.copy)

    /** Whether `other` holds the same 608 bytes, compared in constant time without a copy that
      * outlives the comparison.
      */
    def sameMaterial(other: SigningKey): Boolean = key.sameMaterial(other.key)

    def isForgotten: Boolean = key.isForgotten

    /** Erase the whole key. */
    def forget(): Unit = key.forget()

  /** `genKeyKES seed`: the key for period 0, deterministic in the 32-byte seed. */
  def generate(seed: Bytes): Either[Failure, SigningKey] =
    if seed == null then Left(Failure.MissingSeed)
    else if seed.size != SeedBytes then Left(Failure.InvalidSeedLength(seed.size))
    else Right(new SigningKey(seed.withRange((array, from, _) => Sum6Key.generated(array, from))))

  /** Verification key of the depth-`depth` tree generated from `seed` (test and diagnostics). */
  private[kes] def rootOfSeed(seed: Bytes, depth: Int): Bytes =
    seed.withRange((array, from, _) => Sum6Key.rootOf(array, from, depth))

  /** Load a `kes.skey` payload that is at period `period` (0 for a fresh key). Every level's stored
    * verification keys are recomputed and checked, so a corrupt or mis-sized key is refused. The
    * key copies the payload; `bytes` stays its caller's.
    */
  def fromBytes(bytes: Bytes, period: Int = 0): Either[Failure, SigningKey] =
    if bytes == null then Left(Failure.MissingKey)
    else if bytes.size != KeyBytes then Left(Failure.InvalidKeyLength(bytes.size))
    else if period < 0 || period >= Periods then Left(Failure.PeriodOutOfRange(period))
    else
      bytes
        .withRange((array, from, _) => Sum6Key.loaded(array, from, Some(period)))
        .map(new SigningKey(_))

  /** Load a `kes.skey` payload of any period (a key this node has evolved and written back, see
    * `KesStore`): the period is read off the key itself, because an evolution erases the `r1` seed
    * of a level exactly when the key moves into that level's right half, so the erased seeds, from
    * the outermost level in, spell the period in binary. The result is checked as in [[fromBytes]],
    * so a damaged key (or an all-zero, forgotten one) is refused. The cardano-cli file is the
    * period-0 case. The key copies the payload; `bytes` stays its caller's.
    */
  def fromEvolved(bytes: Bytes): Either[Failure, SigningKey] =
    if bytes == null then Left(Failure.MissingKey)
    else if bytes.size != KeyBytes then Left(Failure.InvalidKeyLength(bytes.size))
    else
      bytes.withRange((array, from, _) => Sum6Key.loaded(array, from, None)).map(new SigningKey(_))
