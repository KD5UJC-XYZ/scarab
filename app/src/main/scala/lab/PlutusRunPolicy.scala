// SPDX-License-Identifier: Apache-2.0
package lab

/** Domain termination policy. Wire spellings are presentation only, never control inputs. */
private[lab] object PlutusRunPolicy:
  enum CompletionGoal:
    case Inclusion, PublishedBlockBudget
  enum FollowStop:
    case Completed(goal: CompletionGoal)
    case Deadline
    case EpochBoundary(goal: CompletionGoal)
    case Driver(stop: EphemeralStreaming.Stop)
    case Failed(cause: Throwable)
  enum RelayStop:
    case SessionLimit, ServiceUnavailable, UnexpectedCompletion
  enum WindowEnd:
    case Duration, Epoch
  enum ServiceStop:
    case DurationLimit, EpochLimit, BlockLimit, EpochBoundaryRefused
    case RelaySessionLimit, ServiceUnavailable, RelayUnexpectedCompletion
    case RelayFailure(cause: Throwable)
    case FollowerFailure(stop: FollowStop)
  enum TerminalCategory:
    case Stopped, Failed

  def fromFollow(stop: FollowStop, window: WindowEnd): ServiceStop = stop match
    case FollowStop.Completed(CompletionGoal.PublishedBlockBudget) => ServiceStop.BlockLimit
    case FollowStop.Deadline | FollowStop.Driver(EphemeralStreaming.Stop.Deadline) =>
      window match
        case WindowEnd.Duration => ServiceStop.DurationLimit
        case WindowEnd.Epoch    => ServiceStop.EpochLimit
    case FollowStop.Driver(EphemeralStreaming.Stop.BlockLimit) => ServiceStop.BlockLimit
    case FollowStop.EpochBoundary(CompletionGoal.PublishedBlockBudget) =>
      ServiceStop.EpochBoundaryRefused
    case FollowStop.Completed(CompletionGoal.Inclusion) |
        FollowStop.EpochBoundary(CompletionGoal.Inclusion) | FollowStop.Driver(
          EphemeralStreaming.Stop.End | EphemeralStreaming.Stop.EventLimit |
          EphemeralStreaming.Stop.ByteLimit | EphemeralStreaming.Stop.Rejected(_)
        ) | FollowStop.Failed(_) =>
      ServiceStop.FollowerFailure(stop)

  def fromRelay(stop: RelayStop): ServiceStop = stop match
    case RelayStop.SessionLimit         => ServiceStop.RelaySessionLimit
    case RelayStop.ServiceUnavailable   => ServiceStop.ServiceUnavailable
    case RelayStop.UnexpectedCompletion => ServiceStop.RelayUnexpectedCompletion

  def fromRace(
      result: Either[Either[Throwable, RelayStop], FollowStop],
      window: WindowEnd
  ): ServiceStop = result match
    case Left(Left(error)) => ServiceStop.RelayFailure(error)
    case Left(Right(stop)) => fromRelay(stop)
    case Right(stop)       => fromFollow(stop, window)

  def category(stop: ServiceStop): TerminalCategory = stop match
    case ServiceStop.DurationLimit | ServiceStop.EpochLimit | ServiceStop.BlockLimit =>
      TerminalCategory.Stopped
    case ServiceStop.EpochBoundaryRefused | ServiceStop.RelaySessionLimit |
        ServiceStop.ServiceUnavailable | ServiceStop.RelayUnexpectedCompletion |
        ServiceStop.RelayFailure(_) | ServiceStop.FollowerFailure(_) =>
      TerminalCategory.Failed

  def categoryWire(value: TerminalCategory): String = value match
    case TerminalCategory.Stopped => "stopped"
    case TerminalCategory.Failed  => "failed"
  def serviceWire(stop: ServiceStop): String = stop match
    case ServiceStop.DurationLimit             => "durationLimit"
    case ServiceStop.EpochLimit                => "epochLimit"
    case ServiceStop.BlockLimit                => "blockLimit"
    case ServiceStop.EpochBoundaryRefused      => "epochBoundaryRefused"
    case ServiceStop.RelaySessionLimit         => "relaySessionLimit"
    case ServiceStop.ServiceUnavailable        => "serviceUnavailable"
    case ServiceStop.RelayUnexpectedCompletion => "relayUnexpectedCompletion"
    case ServiceStop.RelayFailure(_)           => "relayFailure"
    case ServiceStop.FollowerFailure(_)        => "followerFailure"

  /** Legacy diagnostic text preserves cause detail; no consumer may derive policy from it. */
  def followWire(stop: FollowStop): String = stop match
    case FollowStop.Completed(CompletionGoal.Inclusion)                => "inclusionApplied"
    case FollowStop.Completed(CompletionGoal.PublishedBlockBudget)     => "blockLimit"
    case FollowStop.Deadline                                           => "deadline"
    case FollowStop.EpochBoundary(CompletionGoal.PublishedBlockBudget) => "epochBoundaryRefused"
    case FollowStop.EpochBoundary(CompletionGoal.Inclusion) =>
      "failure:announcement does not extend applied fullpoint within initial epoch"
    case FollowStop.Driver(value) =>
      value match
        case EphemeralStreaming.Stop.End               => "End"
        case EphemeralStreaming.Stop.EventLimit        => "EventLimit"
        case EphemeralStreaming.Stop.BlockLimit        => "BlockLimit"
        case EphemeralStreaming.Stop.ByteLimit         => "ByteLimit"
        case EphemeralStreaming.Stop.Deadline          => "Deadline"
        case EphemeralStreaming.Stop.Rejected(failure) => s"Rejected($failure)"
    case FollowStop.Failed(cause) => s"failure:${cause.getMessage}"
