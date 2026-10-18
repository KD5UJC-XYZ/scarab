// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.BinaryError
import xyz.kd5ujc.scarab.ledger.state.{MirrorError, Shape}

class DomainBoundaryContractSuite extends FunSuite:
  test("storage and mirror boundaries retain the exact admitted cause") {
    val binary = BinaryError.InvalidBoolean(2)
    assert(StorageError.Corrupt("snapshot", binary).cause eq binary)
    val mirror = MirrorError.IllFormed(Shape.PulsingRewardUpdate)
    assert(BlockFailure.Mirror(mirror).cause eq mirror)
    assert(ConfigProblem.StateMirror(mirror).cause eq mirror)
    assert(GovernorFault.InvariantBroken(List(binary)).violations.head eq binary)
    assert(ConfigProblem.PeerTargets(binary).cause eq binary)
    assert(ConfigProblem.PeerAddress(binary).cause eq binary)
    assert(ConfigProblem.PeerSnapshot(binary).cause eq binary)
    assert(ConfigProblem.Ancillary(binary).cause eq binary)
  }

  test("storage corruption rejects strings options and tuples") {
    assert(
      compileErrors("""xyz.kd5ujc.scarab.ledger.StorageError.Corrupt("disk", "message")""").nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.ledger.StorageError.Corrupt("disk", Some("message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.ledger.StorageError.Corrupt("disk", (1, "message"))"""
      ).nonEmpty
    )
  }

  test("mirror corruption rejects strings options and tuples") {
    assert(compileErrors("""xyz.kd5ujc.scarab.ledger.BlockFailure.Mirror("message")""").nonEmpty)
    assert(
      compileErrors("""xyz.kd5ujc.scarab.ledger.BlockFailure.Mirror(Some("message"))""").nonEmpty
    )
    assert(
      compileErrors("""xyz.kd5ujc.scarab.ledger.BlockFailure.Mirror((1, "message"))""").nonEmpty
    )
  }

  test("invariant faults require explicitly admitted violations") {
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.ledger.GovernorFault.InvariantBroken(List("message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.ledger.GovernorFault.InvariantBroken(List(Some("message")))"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.ledger.GovernorFault.InvariantBroken(List((1, "message")))"""
      ).nonEmpty
    )
  }

  test("configuration bridges require explicitly admitted causes") {
    assert(
      compileErrors("""xyz.kd5ujc.scarab.ledger.ConfigProblem.PeerTargets("message")""").nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.ledger.ConfigProblem.PeerTargets(Some("message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.ledger.ConfigProblem.PeerTargets((1, "message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors("""xyz.kd5ujc.scarab.ledger.ConfigProblem.PeerAddress("message")""").nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.ledger.ConfigProblem.PeerAddress(Some("message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.ledger.ConfigProblem.PeerAddress((1, "message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors("""xyz.kd5ujc.scarab.ledger.ConfigProblem.PeerSnapshot("message")""").nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.ledger.ConfigProblem.PeerSnapshot(Some("message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.ledger.ConfigProblem.PeerSnapshot((1, "message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors("""xyz.kd5ujc.scarab.ledger.ConfigProblem.StateMirror("message")""").nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.ledger.ConfigProblem.StateMirror(Some("message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.ledger.ConfigProblem.StateMirror((1, "message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors("""xyz.kd5ujc.scarab.ledger.ConfigProblem.Ancillary("message")""").nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.ledger.ConfigProblem.Ancillary(Some("message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors("""xyz.kd5ujc.scarab.ledger.ConfigProblem.Ancillary((1, "message"))""").nonEmpty
    )
  }
