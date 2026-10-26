// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

/** Mux-owned states, distinct from the connection manager's states and application temperatures.
  * Mirrors network-mux Network/Mux/Types.hs:169–201,226–229 at ouroboros-network 2fb69829.
  */
private[network] object MuxLifecycle:
  enum Status:
    case Ready
    case Failed(cause: NetworkError)
    case Stopping, Stopped

  enum ProtocolStatus:
    case Idle, StartOnDemand, Running, StartOnDemandAny

  enum StartMode:
    case Eagerly, OnDemand, OnDemandAny

    def initialStatus: ProtocolStatus = this match
      case Eagerly     => ProtocolStatus.Running
      case OnDemand    => ProtocolStatus.StartOnDemand
      case OnDemandAny => ProtocolStatus.StartOnDemandAny

  /** The completion action gives an already published result precedence over mux shutdown
    * (Network/Mux.hs:798–812). The original failure is retained at both boundaries.
    */
  enum CompletionError:
    case Application(cause: NetworkError)
    case Shutdown(status: ShutdownStatus)

  enum ShutdownStatus:
    case Failed(cause: NetworkError)
    case Stopping, Stopped

  def completion[A](
      status: Status,
      result: Option[Either[NetworkError, A]]
  ): Option[Either[CompletionError, A]] =
    result.map(_.left.map(CompletionError.Application(_))).orElse {
      status match
        case Status.Ready => None
        case Status.Failed(cause) =>
          Some(Left(CompletionError.Shutdown(ShutdownStatus.Failed(cause))))
        case Status.Stopping => Some(Left(CompletionError.Shutdown(ShutdownStatus.Stopping)))
        case Status.Stopped  => Some(Left(CompletionError.Shutdown(ShutdownStatus.Stopped)))
    }
