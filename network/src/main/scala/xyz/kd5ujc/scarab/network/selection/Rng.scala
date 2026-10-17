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

  /** A uniform value in `[low, high]` (both ends included). */
  def between(low: Long, high: Long): (Long, Rng) =
    require(low <= high, s"empty range [$low, $high]")
    val width = high - low + 1
    val (bits, next) = nextLong
    (low + java.lang.Long.remainderUnsigned(bits, width), next)

  /** Two generators with independent streams, the reference's `splitGen`. */
  def split: (Rng, Rng) =
    val (a, next) = nextLong
    (Rng(next.state), Rng(mix64(a) ^ Gamma))

object Rng:
  private val Gamma = 0x9e3779b97f4a7c15L

  private def mix64(z0: Long): Long =
    val z1 = (z0 ^ (z0 >>> 30)) * 0xbf58476d1ce4e5b9L
    val z2 = (z1 ^ (z1 >>> 27)) * 0x94d049bb133111ebL
    z2 ^ (z2 >>> 31)
