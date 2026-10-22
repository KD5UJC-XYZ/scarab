// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.genesis

import cats.effect.Temporal
import cats.syntax.all.*
import scala.concurrent.duration.*

/** Picosecond policy time. The system interpreter rounds only the final timer delay to the host
  * clock's nanosecond resolution; deterministic reference interpreters can retain every picosecond.
  */
trait PicosClock[F[_]]:
  def now: F[BigInt]
  def sleep(delayPicos: BigInt): F[Unit]

object PicosClock:
  def system[F[_]: Temporal]: PicosClock[F] = new PicosClock[F]:
    private val F = Temporal[F]
    def now: F[BigInt] = F.monotonic.map(t => BigInt(t.toNanos) * 1000)
    def sleep(delayPicos: BigInt): F[Unit] =
      F.tailRecM((delayPicos.max(0) + 999) / 1000) { remaining =>
        if remaining == 0 then F.pure(Right(()))
        else
          val chunk = remaining.min(BigInt(Long.MaxValue)).toLong
          F.sleep(chunk.nanos).as(Left(remaining - chunk))
      }
