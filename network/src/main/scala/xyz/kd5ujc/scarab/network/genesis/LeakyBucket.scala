// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.genesis

import xyz.kd5ujc.scarab.codec.Rational

/** Exact state transitions of consensus 82ecba329, Util/LeakyBucket.hs:377–419. Time is
  * picoseconds; snapshots debit elapsed time before applying fills, pauses or configuration
  * changes.
  */
object LeakyBucket:
  val PicosPerSecond: BigInt = BigInt(10).pow(12)

  final case class Config(capacity: Rational, rate: Rational, fillOnOverflow: Boolean = true)
  object Config:
    val disabled: Config = Config(Rational.zero, Rational.zero)
    // Node/Genesis.hs, defaultChainSyncLoPBucketConfig.
    val genesis: Config = Config(Rational(100000), Rational(500))

  final case class State(
      level: Rational,
      timePicos: BigInt,
      paused: Boolean,
      generation: BigInt,
      config: Config
  ):
    def snapshot(nowPicos: BigInt): State = fill(Rational.zero, nowPicos)._1

    def fill(amount: Rational, nowPicos: BigInt): (State, Boolean) =
      val elapsed = Rational(nowPicos - timePicos, PicosPerSecond)
      val leaked = if paused then Rational.zero else elapsed * config.rate
      val remaining = clamp(level - leaked, config.capacity)
      val offered = remaining + amount
      val overflow = offered > config.capacity
      val next =
        if overflow && !config.fillOnOverflow then remaining else clamp(offered, config.capacity)
      (copy(level = next, timePicos = nowPicos), overflow)

    def setPaused(value: Boolean, nowPicos: BigInt): State =
      snapshot(nowPicos).copy(paused = value)

    def reconfigure(next: Config, nextLevel: Rational, nowPicos: BigInt): State =
      snapshot(nowPicos).copy(
        config = next,
        level = clamp(nextLevel, next.capacity),
        generation = generation + 1
      )

    /** The native timer uses integer microseconds and expires a positive sub-microsecond remainder.
      * Pausing inhibits leakage, but does not disable the empty action on an already empty bucket.
      */
    def expired: Boolean =
      config.rate > Rational.zero &&
        (level <= Rational.zero || (level / config.rate * Rational(1000000)).floor <= 0)

    def waitMicros: Option[BigInt] =
      Option.when(config.rate > Rational.zero)((level / config.rate * Rational(1000000)).floor)

  def initial(config: Config, nowPicos: BigInt): State =
    State(config.capacity, nowPicos, false, 0, config)

  private def clamp(value: Rational, capacity: Rational): Rational =
    value.max(Rational.zero).min(capacity)
