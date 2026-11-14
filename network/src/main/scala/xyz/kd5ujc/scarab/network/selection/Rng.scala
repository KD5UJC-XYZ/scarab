// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

/** A pure splittable random number generator (SplitMix64, Steele, Lea and Flood, "Fast splittable
  * pseudorandom number generators", OOPSLA 2014), the algorithm behind the `StdGen` of the
  * reference's `random` package. The governor draws every random choice (the peers it picks, the
  * fuzz on a retry time) from the generator in its state and keeps the advanced generator, so a run
  * is a pure function of its seed and its events ([[PeerSelection]]).
  */
final case class Rng(state: Long):
  import Rng.*

  /** The next 64 random bits and the advanced generator. */
  def nextLong: (Long, Rng) =
    val advanced = state + Gamma
    (mix64(advanced), Rng(advanced))

  /** A uniform value of the unsigned 32-bit range, as the reference's `Word32` draws are. */
  def nextWord32: (Long, Rng) =
    val (bits, next) = nextLong
    ((bits >>> 32) & 0xffffffffL, next)

  /** A uniform value in `[low, high]` (both ends included, in either order), drawn as the
    * reference's `uniformR` draws an `Int` (`random-1.3.1`, `System/Random/Internal.hs`,
    * `signedBitmaskWithRejectionRM` and `unsignedBitmaskWithRejectionM`): 64 bits masked to the
    * width of the range, a value past its end rejected and drawn again, so no value is favoured. A
    * range of one value takes no draw. (A remainder of the 64 bits would favour the low values
    * slightly whenever the width does not divide 2^64^.) `randomR` on an `Int` is this draw:
    * random-1.2 and later define it as `uniformRM` (`System/Random.hs` 436-438, 501).
    */
  def uniformR(low: Long, high: Long): (Long, Rng) =
    if low == high then (high, this)
    else
      val (bottom, range) = if low > high then (high, low - high) else (low, high - low)
      val mask = -1L >>> java.lang.Long.numberOfLeadingZeros(range | 1L)
      @scala.annotation.tailrec
      def draw(g: Rng): (Long, Rng) =
        val (bits, next) = g.nextLong
        val x = bits & mask
        if java.lang.Long.compareUnsigned(x, range) > 0 then draw(next) else (bottom + x, next)
      draw(this)

  /** A uniform value in `[low, high]` (both ends included, in either order), drawn as the
    * reference's `uniformR` and `randomR` draw a `Word16` or a `Word32` (`random-1.3.1`,
    * `System/Random/Internal.hs` 1107-1108 and 1616-1648, `unbiasedWordMult32RM`): Lemire's
    * multiplication of a 32-bit word by the width of the range, with a word whose low half falls
    * short of `2^32^ mod width` rejected and drawn again. The word is the low 32 bits of the next
    * 64, as splitmix's `nextWord32` truncates (`splitmix-0.1.3.2`, `System/Random/SplitMix.hs`
    * `nextWord32`; random's `genWord16` truncates that again, which this range never needs). Unlike
    * [[uniformR]], a range of one value still takes a word. Both ends lie in `[0, 2^32^ - 1]`.
    */
  def uniformWord32R(low: Long, high: Long): (Long, Rng) =
    val (bottom, range) = if low <= high then (low, high - low) else (high, low - high)
    if range == Word32Max then
      val (bits, next) = nextLong
      (bottom + (bits & Word32Max), next)
    else
      val width = range + 1
      // `(-r) `mod` r` in Word32: 2^32 mod width
      val threshold = (Word32Max + 1) % width
      @scala.annotation.tailrec
      def draw(g: Rng): (Long, Rng) =
        val (bits, next) = g.nextLong
        val product = (bits & Word32Max) * width
        if (product & Word32Max) >= threshold then (bottom + (product >>> 32), next)
        else draw(next)
      draw(this)

  /** A uniform `Double` in `[low, high]`, drawn as the reference's `uniformR` and `randomR` draw a
    * `Double` (`random-1.3.1`, `System/Random/Internal.hs` 1357-1372; `randomR` is `uniformRM`,
    * `System/Random.hs` 545-547): the bound itself when the two are equal, their sum without a draw
    * when either is infinite, else one 64-bit word scaled into the range by [[Rng.scaleFloating]].
    */
  def uniformDouble(low: Double, high: Double): (Double, Rng) =
    if low == high then (low, this)
    else if low.isInfinite || high.isInfinite then (high + low, this)
    else
      val (bits, next) = nextLong
      (scaleFloating(low, high, bits), next)

  /** Two generators with independent streams, the reference's `splitGen`. */
  def split: (Rng, Rng) =
    val (a, next) = nextLong
    (Rng(next.state), Rng(mix64(a) ^ Gamma))

object Rng:
  private val Gamma = 0x9e3779b97f4a7c15L

  private val Word32Max = 0xffffffffL

  // `fromIntegral (maxBound :: Word64) :: Double`, which rounds to 2^64
  private val Word64MaxDouble = 18446744073709551616.0

  /** `scaleFloating` of `random-1.3.1` (`System/Random/Internal.hs` 1426-1449) for a `Double` and a
    * 64-bit word: the word's low 63 bits as a fraction of 2^64^ measure the distance from `low`
    * when its top bit is set, from `high` when it is clear; a range too wide for a `Double` mixes
    * the two bounds instead and is clamped to them. Haskell's `min` and `max` on `Double` are its
    * `<=` comparisons, which `Math.min` and `Math.max` are not for a zero's sign or a NaN.
    */
  def scaleFloating(low: Double, high: Double, word: Long): Double =
    def min(x: Double, y: Double): Double = if x <= y then x else y
    def max(x: Double, y: Double): Double = if x <= y then y else x
    val diff = high - low
    if diff.isInfinite then
      val x = unsignedToDouble(word) / Word64MaxDouble
      val y = x * low + (1 - x) * high
      max(min(y, max(low, high)), min(low, high))
    else
      val x = (word & Long.MaxValue).toDouble / Word64MaxDouble
      if word < 0 then low + diff * x else high + -diff * x

  /** A 64-bit word read as unsigned, rounded to the nearest `Double` (`fromIntegral :: Word64 ->
    * Double`): halved with its low bit kept as a sticky bit, converted, and doubled.
    */
  private def unsignedToDouble(word: Long): Double =
    if word >= 0 then word.toDouble else ((word >>> 1) | (word & 1L)).toDouble * 2.0

  private def mix64(z0: Long): Long =
    val z1 = (z0 ^ (z0 >>> 30)) * 0xbf58476d1ce4e5b9L
    val z2 = (z1 ^ (z1 >>> 27)) * 0x94d049bb133111ebL
    z2 ^ (z2 >>> 31)
