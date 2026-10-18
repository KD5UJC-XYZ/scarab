// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.Eval
import java.util.concurrent.ConcurrentHashMap

/** Shares one lazy construction per key, retaining successful values and retrying failed attempts.
  * Conditional removal prevents a reader of an old failed attempt from evicting a newer success.
  */
private[plutus] final class RetryingMemo[K, E, A]:
  private val entries = ConcurrentHashMap[K, Eval[Either[E, A]]]()

  def getOrElseUpdate(key: K)(compute: => Either[E, A]): Either[E, A] =
    val attempt = entries.computeIfAbsent(key, _ => Eval.later(compute))
    val result = attempt.value
    result match
      case Left(_) =>
        val _ = entries.remove(key, attempt)
      case Right(_) => ()
    result
