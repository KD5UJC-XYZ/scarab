// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.network.selection.{AfterSlot, UseLedgerPeers}

/** What the node does with `peerSnapshotFile` (`updateLedgerPeerSnapshot`, `cardano-node` `Run.hs`
  * 705-760): used only with ledger peers on, an unreadable file is ignored, and a legacy version,
  * the origin, another network and a snapshot older than `useLedgerAfterSlot` stop the node.
  */
class PeerSnapshotUseSuite extends FunSuite:
  private val Always = UseLedgerPeers.Use(AfterSlot.Always)
  private def after(n: Int) = UseLedgerPeers.Use(AfterSlot.After(BigInt(n)))

  private val hash = "85f25eb3a3a6eed5ca6e18aa47701992febc6a277cc7481f20a32c8bc1f91d69"
  private val pools =
    """[{"accumulatedStake": 1, "relativeStake": 1, "relays": [{"address": "r.example", "port": 3001}]}]"""

  private def file(text: String): Path =
    val p = Files.createTempFile("peer-snapshot", ".json")
    p.toFile.deleteOnExit()
    Files.writeString(p, text)
    p

  private def v23(slot: String, magic: Long = 2L): Path =
    val point =
      if slot == "origin" then "\"origin\""
      else s"""{"blockPointSlot": $slot, "blockPointHash": "$hash"}"""
    file(
      s"""{"NodeToClientVersion": 23, "NetworkMagic": $magic, "Point": $point, "bigLedgerPools": $pools}"""
    )

  test("nothing is used without ledger peers or without a file") {
    assertEquals(
      PeerSnapshot.use(Some(v23("100")), 2L, UseLedgerPeers.Disabled),
      Right(SnapshotUse.Unused)
    )
    assertEquals(PeerSnapshot.use(None, 2L, Always), Right(SnapshotUse.Unused))
  }

  test("a readable current snapshot is loaded; one at the slot exactly is not too old") {
    assert(PeerSnapshot.use(Some(v23("100")), 2L, Always).exists(_.snapshot.isDefined))
    assert(PeerSnapshot.use(Some(v23("100")), 2L, after(100)).exists(_.snapshot.isDefined))
  }

  test("an unreadable or malformed file is ignored, as the reference's Praos mode does") {
    val missing = Path.of("/nonexistent/peer-snapshot.json")
    assert(PeerSnapshot.use(Some(missing), 2L, Always).exists {
      case SnapshotUse.Ignored(PeerSnapshotError.Unreadable(_, _)) => true
      case _                                                       => false
    })
    assert(PeerSnapshot.use(Some(file("{")), 2L, Always).exists {
      case SnapshotUse.Ignored(PeerSnapshotError.NotJson(_)) => true
      case _                                                 => false
    })
  }

  test("a legacy version, the origin, another network and an old snapshot stop the node") {
    val legacy = file(s"""{"version": 2, "slotNo": 100, "bigLedgerPools": $pools}""")
    assertEquals(
      PeerSnapshot.use(Some(legacy), 2L, Always),
      Left(PeerSnapshotRefusal.LegacyVersion)
    )
    assertEquals(
      PeerSnapshot.use(Some(v23("origin")), 2L, Always),
      Left(PeerSnapshotRefusal.AtOrigin)
    )
    assertEquals(
      PeerSnapshot.use(Some(v23("100", magic = 764824073L)), 2L, Always),
      Left(PeerSnapshotRefusal.WrongMagic(2L, 764824073L))
    )
    assertEquals(
      PeerSnapshot.use(Some(v23("100")), 2L, after(101)),
      Left(PeerSnapshotRefusal.TooOld(BigInt(101), BigInt(100)))
    )
  }

  test("the Leios testnet file is accepted by its topology and refused on another network") {
    val leios = Path.of("fixtures/leios/testnet/config/peer-snapshot.json")
    assert(PeerSnapshot.use(Some(leios), 164L, after(151200)).exists(_.snapshot.isDefined))
    assert(PeerSnapshot.use(Some(leios), 2L, after(151200)).isLeft)
  }
