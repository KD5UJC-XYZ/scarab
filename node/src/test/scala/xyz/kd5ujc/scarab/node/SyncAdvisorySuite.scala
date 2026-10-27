// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.TestGeometry
import com.comcast.ip4s.*
import java.time.Instant
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.network.selection.{ConsensusMode, UseLedgerPeers}
import xyz.kd5ujc.scarab.node.SyncAdvisory.Position
import xyz.kd5ujc.scarab.node.log.NodeEvent

class SyncAdvisorySuite extends FunSuite:
  private val start = Instant.parse("2026-01-01T00:00:00Z")
  private val geometry =
    EpochGeometry(start, 1000L, 100L, TestGeometry.k(10), TestGeometry.f("0.5"))
  // 100 slots of 1 s per epoch
  private val now = start.plusSeconds(1000)

  private def point = AccessPoint(host"10.0.0.1", port"3001")

  private def roots(trustable: Boolean) =
    LocalRoots(Vector(point), false, 1, 1, trustable, false, "InitiatorAndResponder")

  private def topology(
      local: Vector[LocalRoots] = Vector.empty,
      bootstrap: Option[Vector[AccessPoint]] = None
  ) = Topology(local, Vector.empty, bootstrap, UseLedgerPeers.Disabled, None)

  test("no block is an origin start; a tip an epoch or less behind is near") {
    assertEquals(SyncAdvisory.position(None, now, geometry), Position.FromOrigin)
    assertEquals(SyncAdvisory.position(Some(BigInt(900)), now, geometry), Position.Near)
    assertEquals(
      SyncAdvisory.position(Some(BigInt(900)), now.plusSeconds(1), geometry),
      Position.Behind(BigInt(101))
    )
    assertEquals(
      SyncAdvisory.position(Some(BigInt(899)), now, geometry),
      Position.Behind(BigInt(101))
    )
  }

  test("trusted roots are bootstrap peers or a trustable local root") {
    assert(!SyncAdvisory.hasTrustedRoots(None))
    assert(!SyncAdvisory.hasTrustedRoots(Some(topology())))
    assert(!SyncAdvisory.hasTrustedRoots(Some(topology(local = Vector(roots(false))))))
    assert(SyncAdvisory.hasTrustedRoots(Some(topology(local = Vector(roots(true))))))
    assert(SyncAdvisory.hasTrustedRoots(Some(topology(bootstrap = Some(Vector(point))))))
    assert(!SyncAdvisory.hasTrustedRoots(Some(topology(bootstrap = Some(Vector.empty)))))
  }

  test("Praos mode from origin or far behind without trusted roots warns") {
    List(Position.FromOrigin, Position.Behind(BigInt(500))).foreach { p =>
      assertEquals(
        SyncAdvisory.assess(ConsensusMode.PraosMode, None, p),
        Some(NodeEvent.PraosSyncUntrusted(p))
      )
    }
  }

  test("Genesis mode, a near tip or trusted roots do not warn") {
    assertEquals(SyncAdvisory.assess(ConsensusMode.GenesisMode, None, Position.FromOrigin), None)
    assertEquals(SyncAdvisory.assess(ConsensusMode.PraosMode, None, Position.Near), None)
    assertEquals(
      SyncAdvisory.assess(
        ConsensusMode.PraosMode,
        Some(topology(bootstrap = Some(Vector(point)))),
        Position.FromOrigin
      ),
      None
    )
  }
