// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

/** The reference's `StdGen`: the generator of `random-1.3.1`, a newtype over the `SMGen` of
  * `splitmix-0.1.3.2` (`System/Random/Internal.hs` 749-770 of `random` d83e66f4, which the Leios
  * build links as unit `random-1.3.1-126384cd`; `System/Random/SplitMix.hs` of `splitmix` 419c7638,
  * unit `splitmix-0.1.3.2-f11367c6`). A seed and an odd gamma, both 64-bit words held in the bits
  * of a `Long`; every step is arithmetic modulo 2^64^, as on a `Word64`.
  *
  * It is the reference's own stream, not another generator of the same family: the Leios fetch
  * decision draws its tie-breaks from it (`leiosFetchPrng`, `LeiosDemoTypes.hs` 588-593 of
  * ouroboros-consensus 2416493b), so a run with the reference's seed draws the reference's jobs.
  */
final case class StdGen private (seed: Long, gamma: Long):
  import StdGen.*

  /** `nextWord64` (`SplitMix.hs` 114-117): the seed advanced by the gamma, mixed. */
  def nextWord64: (Long, StdGen) =
    val advanced = seed + gamma
    (mix64(advanced), StdGen(advanced, gamma))

  /** `splitSMGen` (`SplitMix.hs` 218-224), the `splitGen` of `random` for a `StdGen` (`Internal.hs`
    * 768-770).
    */
  def split: (StdGen, StdGen) =
    val once = seed + gamma
    val twice = once + gamma
    (StdGen(twice, gamma), StdGen(mix64(once), mixGamma(twice)))

  /** `uniformR` on an `Int` (`Internal.hs` 1061-1063, 1666-1700 of `random` d83e66f4), whose `Int`
    * is 64 bits: `signedBitmaskWithRejectionRM` over `Word`, so a 64-bit word masked to the width
    * of the range and drawn again while past its end. Either end may be the lower; a range of one
    * value takes no draw.
    */
  def uniformR(low: Long, high: Long): (Long, StdGen) =
    if low == high then (high, this)
    else
      val (bottom, range) = if low > high then (high, low - high) else (low, high - low)
      val mask = -1L >>> java.lang.Long.numberOfLeadingZeros(range | 1L)
      @scala.annotation.tailrec
      def draw(g: StdGen): (Long, StdGen) =
        val (word, next) = g.nextWord64
        val x = word & mask
        if java.lang.Long.compareUnsigned(x, range) > 0 then draw(next) else (bottom + x, next)
      draw(this)

object StdGen:
  private val GoldenGamma = 0x9e3779b97f4a7c15L

  /** `mkStdGen` (`Internal.hs` 786-797): `mkSMGen` of the `Int` as a `Word64` (`SplitMix.hs`
    * 370-371).
    */
  def mk(seed: Long): StdGen = StdGen(mix64(seed), mixGamma(seed + GoldenGamma))

  /** `seedSMGen` (`SplitMix.hs` 351-355): a generator of this seed and gamma, the gamma made odd.
    */
  def seeded(seed: Long, gamma: Long): StdGen = StdGen(seed, gamma | 1L)

  /** `mix64` (`SplitMix.hs` 240-245): the finaliser of MurmurHash3. */
  private def mix64(z0: Long): Long =
    val z1 = shiftXor(33, z0) * 0xff51afd7ed558ccdL
    val z2 = shiftXor(33, z1) * 0xc4ceb9fe1a85ec53L
    shiftXor(33, z2)

  /** `mix64variant13` (`SplitMix.hs` 249-254). */
  private def mix64variant13(z0: Long): Long =
    val z1 = shiftXor(30, z0) * 0xbf58476d1ce4e5b9L
    val z2 = shiftXor(27, z1) * 0x94d049bb133111ebL
    shiftXor(31, z2)

  /** `mixGamma` (`SplitMix.hs` 260-266): odd, and with at least 24 bit transitions. */
  private def mixGamma(z0: Long): Long =
    val z1 = mix64variant13(z0) | 1L
    val n = java.lang.Long.bitCount(z1 ^ (z1 >>> 1))
    if n >= 24 then z1 else z1 ^ 0xaaaaaaaaaaaaaaaaL

  private def shiftXor(n: Int, w: Long): Long = w ^ (w >>> n)
