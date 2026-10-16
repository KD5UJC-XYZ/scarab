// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import cats.Order

/** How much an event matters: the eight levels of trace-dispatcher's `SeverityS`, which are those
  * of syslog (RFC 5424 §6.2.1), least severe first (hermod-tracing
  * `trace-dispatcher/src/Cardano/Logging/Types.hs` 266-277).
  */
enum Severity:
  case Debug, Info, Notice, Warning, Error, Critical, Alert, Emergency

object Severity:
  given Order[Severity] = Order.by(_.ordinal)

  /** The level of this name (`Debug`, `Info`, ... as the Haskell configuration spells them). */
  def parse(text: String): Option[Severity] = values.find(_.toString == text)

/** What a sink lets through, by severity: trace-dispatcher's `SeverityF` (`Types.hs` 279-283),
  * where `Silence` is its `SeverityF Nothing`.
  */
enum Threshold:
  /** Events of this severity and above. */
  case AtLeast(minimum: Severity)

  /** Nothing. */
  case Silence

  def admits(severity: Severity): Boolean = this match
    case AtLeast(minimum) => Order[Severity].gteqv(severity, minimum)
    case Silence          => false

object Threshold:
  /** `Silence` or a severity, as `--log-severity` takes them. */
  def parse(text: String): Option[Threshold] =
    if text == "Silence" then Some(Silence) else Severity.parse(text).map(AtLeast(_))
