// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import com.comcast.ip4s.{host, port}
import munit.FunSuite
import xyz.kd5ujc.scarab.network.selection.{AfterSlot, ConsensusMode, UseLedgerPeers}

/** What the reading of a topology says of it, and the topology it leaves, as the reference's
  * `readTopologyFile` (cardano-node 11.1.3,
  * `cardano-node/src/Cardano/Node/Configuration/TopologyP2P.hs` 66-108).
  */
class TopologyNoticeSuite extends FunSuite:
  import ConsensusMode.{GenesisMode, PraosMode}
  import NodeRole.{BlockProducer, Relay}
  import TopologyNotice.*

  private val on = UseLedgerPeers.Use(AfterSlot.After(BigInt(100)))
  private val relay = AccessPoint(host"127.0.0.1", port"3001")
  private val srv = DnsName.from("srv.example").fold(e => fail(e.toString), identity)

  private def topology(
      ledger: UseLedgerPeers,
      snapshot: Boolean,
      bootstrap: Option[Vector[AccessPoint]] = None
  ): Topology =
    Topology(
      Vector.empty,
      Vector.empty,
      bootstrap,
      ledger,
      Option.when(snapshot)("peer-snapshot.json"),
      bootstrap.fold(Vector.empty[DnsName])(_ => Vector(srv))
    )

  private def said(t: Topology, mode: ConsensusMode, role: NodeRole) =
    TopologyNotice.read(t, mode, role).notices

  test("a relay in Praos mode: only a snapshot without ledger peers is said, with its hint") {
    assertEquals(said(topology(on, snapshot = true), PraosMode, Relay), Nil)
    assertEquals(said(topology(on, snapshot = false), PraosMode, Relay), Nil)
    assertEquals(
      said(topology(UseLedgerPeers.Disabled, snapshot = true), PraosMode, Relay),
      List(SnapshotWithoutLedgerPeers(Relay))
    )
    assertEquals(said(topology(UseLedgerPeers.Disabled, snapshot = false), PraosMode, Relay), Nil)
  }

  test("a block producer: ledger peers not recommended, a snapshot disabled, in either mode") {
    assertEquals(
      said(topology(on, snapshot = true), PraosMode, BlockProducer),
      List(LedgerPeersOnProducer)
    )
    assertEquals(
      said(topology(UseLedgerPeers.Disabled, snapshot = true), PraosMode, BlockProducer),
      List(SnapshotOffOnProducer, SnapshotWithoutLedgerPeers(BlockProducer))
    )
    assertEquals(
      said(topology(UseLedgerPeers.Disabled, snapshot = true), GenesisMode, BlockProducer),
      List(SnapshotOffOnProducer)
    )
    assertEquals(
      said(topology(on, snapshot = false), GenesisMode, BlockProducer),
      List(LedgerPeersOnProducer),
      "the Genesis recommendations are for relays"
    )
  }

  test("a relay in Genesis mode: ledger peers and a snapshot recommended") {
    assertEquals(
      said(topology(UseLedgerPeers.Disabled, snapshot = false), GenesisMode, Relay),
      List(GenesisRelayWithoutLedgerPeers)
    )
    assertEquals(
      said(topology(UseLedgerPeers.Disabled, snapshot = true), GenesisMode, Relay),
      List(GenesisRelayWithoutLedgerPeers),
      "no Praos snapshot warning in Genesis mode"
    )
    assertEquals(
      said(topology(on, snapshot = false), GenesisMode, Relay),
      List(GenesisRelayWithoutSnapshot)
    )
    assertEquals(said(topology(on, snapshot = true), GenesisMode, Relay), Nil)
  }

  test("Genesis mode drops the bootstrap peers, an empty list too, and says so last") {
    List(Vector(relay), Vector.empty[AccessPoint]).foreach { listed =>
      val read = TopologyNotice.read(
        topology(UseLedgerPeers.Disabled, snapshot = false, Some(listed)),
        GenesisMode,
        Relay
      )
      assertEquals(
        read.notices,
        List(GenesisRelayWithoutLedgerPeers, BootstrapPeersInGenesisMode),
        s"bootstrap $listed"
      )
      assertEquals(read.topology.bootstrapPeers, None, "the bootstrap peers dropped")
      assertEquals(read.topology.bootstrapSrvPeers, Vector.empty, "the SRV bootstrap peers dropped")
    }
    val praos = topology(on, snapshot = true, Some(Vector(relay)))
    assertEquals(
      TopologyNotice.read(praos, PraosMode, Relay),
      TopologyNotice.Read(praos, Nil),
      "Praos mode keeps them"
    )
  }

  test("each notice is the reference's text at its severity") {
    val prefix = "Cardano.Node.Configuration.Topology.readTopologyFile: "
    assertEquals(
      BootstrapPeersInGenesisMode.message,
      prefix + "Bootstrap peers (field 'bootstrapPeers') are not compatible with Genesis syncing " +
        "mode, reverting to 'DontUseBootstrapPeers'. Big ledger peers will be leveraged for " +
        "decentralized syncing - it is recommended to provide an up-to-date big ledger peer " +
        "snapshot file (field 'peerSnapshotFile' in topology configuration) to facilitate this " +
        "process."
    )
    assertEquals(
      GenesisRelayWithoutLedgerPeers.message,
      prefix + "It is recommended to use ledger peers and peer snapshot file for relay " +
        "operations in Genesis mode"
    )
    assertEquals(
      SnapshotWithoutLedgerPeers(Relay).message,
      prefix + "Peer snapshot file specified but topology disables ledger peers - ignoring file. " +
        "To turn off this message enable the use of ledger peers or remove peerSnapshotFile " +
        "from the topology file."
    )
    assertEquals(
      SnapshotWithoutLedgerPeers(BlockProducer).message,
      prefix + "Peer snapshot file specified but topology disables ledger peers - ignoring file. " +
        "To turn off this message remove peerSnapshotFile from the topology file."
    )
    assertEquals(
      LedgerPeersOnProducer.message,
      prefix + "Use of ledger peers is not recommended for BP operation"
    )
    assertEquals(
      SnapshotOffOnProducer.message,
      prefix + "Ledger peers and peer snapshot, although specified in the topology file, are " +
        "disabled in line with recommended BP operation"
    )
    assertEquals(
      GenesisRelayWithoutSnapshot.message,
      prefix + "It is recommended to specify an up-to-date ledger peer snapshot file for relay " +
        "operations in Genesis mode when the use of ledger peers is enabled."
    )
    assertEquals(SnapshotOffOnProducer.level, Level.Info)
    List(
      LedgerPeersOnProducer,
      SnapshotWithoutLedgerPeers(BlockProducer),
      SnapshotWithoutLedgerPeers(Relay),
      GenesisRelayWithoutLedgerPeers,
      GenesisRelayWithoutSnapshot,
      BootstrapPeersInGenesisMode
    ).foreach(n => assertEquals(n.level, Level.Warning, n.toString))
  }

  test("a producer with peer sharing on is warned, in the reference's words, and nobody else") {
    // `handleSimpleNode`, cardano-node 11.1.3 `cardano-node/src/Cardano/Node/Run.hs` 360-366: no
    // `readTopologyFile` prefix
    assertEquals(
      PeerSharingWarning.message,
      "Mainnet block producers may not meet the Praos performance guarantees and host IP " +
        "address will be leaked since peer sharing is enabled."
    )
    assertEquals(PeerSharingWarning.due(BlockProducer, peerSharing = true), true)
    assertEquals(PeerSharingWarning.due(BlockProducer, peerSharing = false), false)
    assertEquals(PeerSharingWarning.due(Relay, peerSharing = true), false)
    assertEquals(PeerSharingWarning.due(Relay, peerSharing = false), false)
  }
