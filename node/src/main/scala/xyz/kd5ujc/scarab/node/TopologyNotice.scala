// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.network.selection.ConsensusMode

/** Whether the node forges: the reference's `hasProtocolFile` (cardano-node 11.1.3,
  * `cardano-node/src/Cardano/Node/Types.hs` 189-203), here `--forge` and its key files.
  */
enum NodeRole:
  case BlockProducer, Relay

object NodeRole:
  def of(forge: Option[Main.ForgeFiles]): NodeRole = forge.fold(Relay)(_ => BlockProducer)

/** The reference's warning of a node that forges with peer sharing on (`handleSimpleNode`,
  * cardano-node 11.1.3 `cardano-node/src/Cardano/Node/Run.hs` 360-366): a
  * `NetworkConfigUpdateWarning` without the prefix of `readTopologyFile`, at start-up only, once
  * the topology is read and before the peer snapshot is; a reload does not say it again.
  */
object PeerSharingWarning:
  val message: String =
    "Mainnet block producers may not meet the Praos performance guarantees and host IP " +
      "address will be leaked since peer sharing is enabled."

  /** Whether the node says it: it forges (`hasProtocolFile`) and `peerSharing` is on, as configured
    * or by the reference's default, off for a producer (`POM.hs` 899-905).
    */
  def due(role: NodeRole, peerSharing: Boolean): Boolean = role match
    case NodeRole.BlockProducer => peerSharing
    case NodeRole.Relay         => false

/** What the reference says of a topology as it reads it, at start-up and at every SIGHUP reload
  * (`readTopologyFile`, cardano-node 11.1.3
  * `cardano-node/src/Cardano/Node/Configuration/TopologyP2P.hs` 66-108): advice that changes
  * nothing, except [[TopologyNotice.BootstrapPeersInGenesisMode]], whose bootstrap peers are
  * dropped. Each is logged as the reference traces it (`NetworkConfigUpdateWarning`, or
  * `NetworkConfigUpdateInfo` for [[TopologyNotice.SnapshotOffOnProducer]]), with its text.
  */
enum TopologyNotice:
  /** A block producer with ledger peers on (66-69). */
  case LedgerPeersOnProducer

  /** A block producer whose topology names a snapshot with ledger peers off (71-74). */
  case SnapshotOffOnProducer

  /** Praos mode, a snapshot named and ledger peers off: the file is not read (76-87). */
  case SnapshotWithoutLedgerPeers(role: NodeRole)

  /** A relay in Genesis mode with ledger peers off (90-93). */
  case GenesisRelayWithoutLedgerPeers

  /** A relay in Genesis mode with ledger peers on and no snapshot named (95-100). */
  case GenesisRelayWithoutSnapshot

  /** Genesis mode with `bootstrapPeers`, even an empty list: the node goes on without them, as with
    * `"bootstrapPeers": null` (103-108, 124-131, 139-140).
    */
  case BootstrapPeersInGenesisMode

  def level: TopologyNotice.Level = this match
    case SnapshotOffOnProducer => TopologyNotice.Level.Info
    case LedgerPeersOnProducer | SnapshotWithoutLedgerPeers(_) | GenesisRelayWithoutLedgerPeers |
        GenesisRelayWithoutSnapshot | BootstrapPeersInGenesisMode =>
      TopologyNotice.Level.Warning

  /** The reference's text, with the prefix of its `createMsg` (110-111). */
  def message: String = "Cardano.Node.Configuration.Topology.readTopologyFile: " + (this match
    case LedgerPeersOnProducer => "Use of ledger peers is not recommended for BP operation"
    case SnapshotOffOnProducer =>
      "Ledger peers and peer snapshot, although specified in the topology file, are disabled in " +
        "line with recommended BP operation"
    case SnapshotWithoutLedgerPeers(NodeRole.BlockProducer) =>
      "Peer snapshot file specified but topology disables ledger peers - ignoring file. " +
        "To turn off this message remove peerSnapshotFile from the topology file."
    case SnapshotWithoutLedgerPeers(NodeRole.Relay) =>
      "Peer snapshot file specified but topology disables ledger peers - ignoring file. " +
        "To turn off this message enable the use of ledger peers or remove peerSnapshotFile " +
        "from the topology file."
    case GenesisRelayWithoutLedgerPeers =>
      "It is recommended to use ledger peers and peer snapshot file for relay operations in " +
        "Genesis mode"
    case GenesisRelayWithoutSnapshot =>
      "It is recommended to specify an up-to-date ledger peer snapshot file for relay " +
        "operations in Genesis mode when the use of ledger peers is enabled."
    case BootstrapPeersInGenesisMode =>
      "Bootstrap peers (field 'bootstrapPeers') are not compatible with Genesis syncing mode, " +
        "reverting to 'DontUseBootstrapPeers'. Big ledger peers will be leveraged for " +
        "decentralized syncing - it is recommended to provide an up-to-date big ledger peer " +
        "snapshot file (field 'peerSnapshotFile' in topology configuration) to facilitate this " +
        "process.")

object TopologyNotice:
  /** The reference's severity of the trace: `Info` or `Warning` (`Tracers/Startup.hs` 374-375). */
  enum Level:
    case Info, Warning

  /** The topology as the node uses it, and what is said of it, in the reference's order. */
  final case class Read(topology: Topology, notices: List[TopologyNotice])

  /** `readTopologyFile` after the parse and its trustable-peer check, which [[Topology.parse]]
    * does: the notices the consensus mode and the node's role call for, and the topology without
    * its bootstrap peers in Genesis mode.
    */
  def read(topology: Topology, mode: ConsensusMode, role: NodeRole): Read =
    val ledgerPeers = topology.useLedgerAfterSlot.isEnabled
    val snapshot = topology.peerSnapshotFile.isDefined
    val producer = role match
      case NodeRole.BlockProducer => true
      case NodeRole.Relay         => false
    val praos = mode match
      case ConsensusMode.PraosMode   => true
      case ConsensusMode.GenesisMode => false
    val genesis = !praos
    val bootstrap = genesis && topology.bootstrapPeers.isDefined
    val notices = List(
      Option.when(producer && ledgerPeers)(LedgerPeersOnProducer),
      Option.when(snapshot && !ledgerPeers && producer)(SnapshotOffOnProducer),
      Option.when(praos && snapshot && !ledgerPeers)(SnapshotWithoutLedgerPeers(role)),
      Option.when(genesis && !ledgerPeers && !producer)(GenesisRelayWithoutLedgerPeers),
      Option.when(genesis && !snapshot && ledgerPeers && !producer)(GenesisRelayWithoutSnapshot),
      Option.when(bootstrap)(BootstrapPeersInGenesisMode)
    ).flatten
    val used =
      if bootstrap then topology.copy(bootstrapPeers = None, bootstrapSrvPeers = Vector.empty)
      else topology
    Read(used, notices)
