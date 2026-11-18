// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.DiffTime

/** The time limits of the mempool's validation of a new transaction, the reference's
  * `MempoolTimeoutConfig` (ouroboros-consensus 82ecba32 `Mempool/API.hs` 272-304), which
  * cardano-node reads from `MempoolTimeoutSoft`, `MempoolTimeoutHard` and `MempoolTimeoutCapacity`
  * and always passes to its mempool (node 11.1.3 `Configuration/POM.hs` 400-402, 889-897;
  * `Node/Run.hs` 388-392).
  *
  * @param soft
  *   a validation that took longer is refused where the era has an error for it
  * @param hard
  *   a validation that reaches it is cut off ([[hardLimit]])
  * @param capacity
  *   while the validations of the held transactions took longer, together, no transaction is
  *   validated: it waits for the pool to change
  */
final case class MempoolTimeouts(soft: DiffTime, hard: DiffTime, capacity: DiffTime):
  /** `timeout hard` as the reference's runtime takes it (io-classes 1.8.0.1
    * `si-timers/src/Control/Monad/Class/MonadTimer/SI.hs` 43-49, 262; base 4.18.3.0
    * `System/Timeout.hs` 99-101): the limit floored to whole microseconds and narrowed to a 64-bit
    * `Int`; a negative one is no limit, zero cuts the validation off before it starts.
    */
  def hardLimit: MempoolTimeouts.HardLimit =
    val (quotient, remainder) = hard.toPicoseconds /% BigInt(1000000)
    val micros = (if remainder.signum < 0 then quotient - 1 else quotient).toLong
    if micros < 0 then MempoolTimeouts.HardLimit.Unbounded
    else if micros == 0 then MempoolTimeouts.HardLimit.Immediate
    else
      // past 2^63 - 1 nanoseconds (292 years) a limit is never reached in the life of a node
      Option
        .when(micros <= Long.MaxValue / 1000)(MempoolTimeouts.HardLimit.After(micros.micros))
        .getOrElse(MempoolTimeouts.HardLimit.Unbounded)

object MempoolTimeouts:
  /** cardano-node's limits when its configuration sets none of the three: soft 1 s, hard 1.5 s,
    * capacity 5 s (11.1.3 `Configuration/POM.hs` 893-897; 11.2.1 943-947).
    */
  val default: MempoolTimeouts =
    MempoolTimeouts(DiffTime.of(1.second), DiffTime.of(1500.millis), DiffTime.of(5.seconds))

  /** When the hard limit cuts a validation off. */
  enum HardLimit:
    case Unbounded
    case Immediate
    case After(limit: FiniteDuration)
