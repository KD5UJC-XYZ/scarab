// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.codec.DomainError

/** PeerStateActions.hs:PeerStatus and the requested transition that can fail (2fb69829). */
enum PeerStatus:
  case Cold, Cooling, Warm, Hot

enum PeerOperation:
  case Activate, Deactivate, Close

/** The complete action failure crosses the governor job boundary as a value. A mux abort has one
  * cause in today's API; this aggregate must not be projected to that single cause at the action
  * boundary (PeerStateActions.hs:994–1104).
  */
enum PeerActionError extends DomainError:
  case ConnectionFailure(error: NetworkError)
  case ApplicationFailures(failures: NonEmptyVector[ProtocolResults.MiniProtocolFailure])
  case TimedOut(operation: PeerOperation)
  case Inactive(operation: PeerOperation, status: PeerStatus)

  def message: String = this match
    case ConnectionFailure(error) => error.message
    case ApplicationFailures(failures) =>
      failures.toVector.map(f => s"protocol ${f.protocol}: ${f.error.message}").mkString("; ")
    case TimedOut(operation)         => s"$operation timed out"
    case Inactive(operation, status) => s"$operation requires an active connection, found $status"
