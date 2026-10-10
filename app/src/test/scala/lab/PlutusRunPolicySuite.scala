// SPDX-License-Identifier: Apache-2.0
package lab

class PlutusRunPolicySuite extends munit.FunSuite:
  import PlutusRunPolicy.*
  private val failure = new IllegalStateException("private diagnostic")
  private val rejected = CoherentSequence.Failure.Unsupported("epoch", "fixture")

  test("all service terminal variants have explicit stable category and wire codes") {
    val cases = Vector(
      (ServiceStop.DurationLimit, "durationLimit", TerminalCategory.Stopped),
      (ServiceStop.EpochLimit, "epochLimit", TerminalCategory.Stopped),
      (ServiceStop.BlockLimit, "blockLimit", TerminalCategory.Stopped),
      (ServiceStop.EpochBoundaryRefused, "epochBoundaryRefused", TerminalCategory.Failed),
      (ServiceStop.RelaySessionLimit, "relaySessionLimit", TerminalCategory.Failed),
      (ServiceStop.ServiceUnavailable, "serviceUnavailable", TerminalCategory.Failed),
      (ServiceStop.RelayUnexpectedCompletion, "relayUnexpectedCompletion", TerminalCategory.Failed),
      (ServiceStop.RelayFailure(failure), "relayFailure", TerminalCategory.Failed),
      (
        ServiceStop.FollowerFailure(FollowStop.Failed(failure)),
        "followerFailure",
        TerminalCategory.Failed
      )
    )
    cases.foreach { (stop, wire, expected) =>
      assertEquals(serviceWire(stop), wire)
      assertEquals(category(stop), expected)
    }
    assertEquals(categoryWire(TerminalCategory.Stopped), "stopped")
    assertEquals(categoryWire(TerminalCategory.Failed), "failed")
  }
  test("completion, deadline, epoch and every streaming stop map exhaustively") {
    val stops = Vector(
      FollowStop.Completed(CompletionGoal.Inclusion),
      FollowStop.Completed(CompletionGoal.PublishedBlockBudget),
      FollowStop.Deadline,
      FollowStop.EpochBoundary(CompletionGoal.Inclusion),
      FollowStop.EpochBoundary(CompletionGoal.PublishedBlockBudget),
      FollowStop.Driver(EphemeralStreaming.Stop.End),
      FollowStop.Driver(EphemeralStreaming.Stop.EventLimit),
      FollowStop.Driver(EphemeralStreaming.Stop.BlockLimit),
      FollowStop.Driver(EphemeralStreaming.Stop.ByteLimit),
      FollowStop.Driver(EphemeralStreaming.Stop.Deadline),
      FollowStop.Driver(EphemeralStreaming.Stop.Rejected(rejected)),
      FollowStop.Failed(failure)
    )
    val wire = Vector(
      "inclusionApplied",
      "blockLimit",
      "deadline",
      "failure:announcement does not extend applied fullpoint within initial epoch",
      "epochBoundaryRefused",
      "End",
      "EventLimit",
      "BlockLimit",
      "ByteLimit",
      "Deadline",
      "Rejected(Unsupported(epoch,fixture))",
      "failure:private diagnostic"
    )
    assertEquals(stops.map(followWire), wire)
    WindowEnd.values.foreach { window =>
      val expected = Vector(
        "followerFailure",
        "blockLimit",
        if window == WindowEnd.Duration then "durationLimit" else "epochLimit",
        "followerFailure",
        "epochBoundaryRefused",
        "followerFailure",
        "followerFailure",
        "blockLimit",
        "followerFailure",
        if window == WindowEnd.Duration then "durationLimit" else "epochLimit",
        "followerFailure",
        "followerFailure"
      )
      assertEquals(stops.map(s => serviceWire(fromFollow(s, window))), expected)
    }
  }
  test("relay terminal values are distinct from error messages that resemble policy codes") {
    assertEquals(fromRelay(RelayStop.SessionLimit), ServiceStop.RelaySessionLimit)
    assertEquals(fromRelay(RelayStop.ServiceUnavailable), ServiceStop.ServiceUnavailable)
    assertEquals(fromRelay(RelayStop.UnexpectedCompletion), ServiceStop.RelayUnexpectedCompletion)
    Vector(
      "relaySessionLimit",
      "serviceUnavailable",
      "deadline",
      "blockLimit",
      "epochBoundaryRefused"
    ).foreach { message =>
      val error = new IllegalStateException(message)
      val relay = fromRace(Left(Left(error)), WindowEnd.Duration)
      assertEquals(serviceWire(relay), "relayFailure")
      assertEquals(category(relay), TerminalCategory.Failed)
      val follow = fromRace(Right(FollowStop.Failed(error)), WindowEnd.Duration)
      assertEquals(serviceWire(follow), "followerFailure")
      assertEquals(category(follow), TerminalCategory.Failed)
      assert(relay.asInstanceOf[ServiceStop.RelayFailure].cause eq error)
    }
  }
