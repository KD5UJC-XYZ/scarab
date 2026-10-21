// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import scala.concurrent.duration.FiniteDuration

/** A protocol-state timer, including the reference's explicit absence of a deadline. */
enum ProtocolDeadline:
  case After(delay: FiniteDuration)
  case Unbounded

  def remaining(elapsed: FiniteDuration): ProtocolDeadline = this match
    case After(delay) => After(delay - elapsed)
    case Unbounded    => Unbounded
