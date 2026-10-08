// SPDX-License-Identifier: Apache-2.0
package lab.network

import scala.concurrent.duration.*

final class TcpLimits private (
    val connect: FiniteDuration,
    val read: FiniteDuration,
    val write: FiniteDuration,
    val cleanup: FiniteDuration,
    val maxChunkBytes: Int,
    val threads: Int
)
object TcpLimits:
  def checked(
      connect: FiniteDuration = 5.seconds,
      read: FiniteDuration = 10.seconds,
      write: FiniteDuration = 5.seconds,
      cleanup: FiniteDuration = 2.seconds,
      maxChunkBytes: Int = 65543,
      threads: Int = 2
  ): Either[String, TcpLimits] =
    Either.cond(
      connect > Duration.Zero && connect <= 5.seconds &&
        read > Duration.Zero && read <= 10.seconds &&
        write > Duration.Zero && write <= 5.seconds &&
        cleanup > Duration.Zero && cleanup <= 2.seconds &&
        maxChunkBytes > 0 && maxChunkBytes <= 65543 && threads >= 1 && threads <= 4,
      new TcpLimits(connect, read, write, cleanup, maxChunkBytes, threads),
      "invalid TCP limits: positive durations within 5s/10s/5s/2s, chunk 1..65543, threads 1..4 required"
    )
