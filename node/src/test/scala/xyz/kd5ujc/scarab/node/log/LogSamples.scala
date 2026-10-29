// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import io.circe.Json
import io.circe.syntax.*
import com.comcast.ip4s.{Host, Port, SocketAddress, host, port}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.blst.{BlstReport, CpuFeature, CpuSupport, LibraryBuild, Platform}
import xyz.kd5ujc.scarab.plutus.{CryptoLibrary, CryptoPlatform, NativeCryptoReport}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, FollowerEvent, SchedulingReport}
import xyz.kd5ujc.scarab.consensus.state.StateMirrorError
import xyz.kd5ujc.scarab.ledger.{
  Block,
  BlockFailure,
  ChainError,
  FormatVersion,
  FileKind,
  ForgeError,
  Header,
  NetworkIdentity,
  OCertError,
  PeerError,
  PlutusStats,
  StorageError,
  ValidationError
}
import xyz.kd5ujc.scarab.network.{
  ConnectionEnd,
  ConnectionError,
  ConnectionEvent,
  DiffTime,
  DiffusionFailure,
  Errno,
  ErrnoCause,
  FileDescriptor,
  LocalAddress,
  LocalConnectionId,
  LocalServer,
  NetworkError,
  SocketConfigError,
  SystemCall,
  SystemCallError,
  Word32
}
import xyz.kd5ujc.scarab.node.{
  InboundEnd,
  InboundEvent,
  InboundGovernor,
  DnsName,
  LedgerPeersTrace,
  LocalRootsTrace,
  NodeSetup,
  PeerSnapshotError,
  Topology
}
import xyz.kd5ujc.scarab.ledger.state.{Derived, MirrorError}
import xyz.kd5ujc.scarab.ledger.PoolRelayError
import xyz.kd5ujc.scarab.node.forge.{
  CannotForge,
  ClockTrace,
  ForgeEpochStats,
  ForgeEvent,
  ForgeTrace,
  KesEvolutionError,
  KesInfo,
  OutsideForecastRange,
  ReloadRefusal
}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.mempool.{MempoolEvent, Rejection}
import xyz.kd5ujc.scarab.protocol.{ChainSync, ConnectionState, Era}
import xyz.kd5ujc.scarab.storage.{
  ImmutableStore,
  Migrated,
  SnapshotRefusal,
  StorageEvent,
  VolatileStore
}

/** One value of every event the node logs, and of every case of the event types it logs, for the
  * rendering golden (`node/src/test/resources/log/golden.jsonl`).
  */
object LogSamples:
  private def bytes(hex: String): Bytes =
    Bytes.fromHex(hex).fold(e => sys.error(e.toString), identity)
  val hash: Bytes = bytes("ab" * 32)
  val other: Bytes = bytes("cd" * 28)
  val block: BlockRef = BlockRef(BigInt(4200), hash, BigInt(17))
  val point: ChainSync.Point = ChainSync.Point.Block(ChainSync.UInt64.saturating(4200), hash)
  val tip: ChainSync.Tip = ChainSync.Tip(point, ChainSync.UInt64.saturating(17))

  /** The eras capture: a real genesis and a real header. */
  private val fixtures = Path.of("fixtures/eras")
  val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => sys.error(e.message), identity)
  val header: Header =
    val raw = Files.readAllLines(fixtures.resolve("blocks.hex")).asScala.find(_.nonEmpty).get
    Block
      .decode(bytes(raw), setup.geometry.byron.epochSlots)
      .fold(e => sys.error(e.message), _.header)

  val peers: List[SocketAddress[Host]] =
    List(SocketAddress(Host.fromString("127.0.0.1").get, Port.fromInt(3001).get))
  val topology: Topology = Topology
    .parse(
      """{"localRoots":[{"accessPoints":[{"address":"127.0.0.1","port":3001}],"advertise":false,"valency":1}],"publicRoots":[{"accessPoints":[],"advertise":false}],"bootstrapPeers":null,"useLedgerAfterSlot":-1}"""
    )
    .fold(e => sys.error(e.message), identity)

  val blstReports: List[BlstReport] = List(
    BlstReport(
      Platform.LinuxAmd64,
      CpuSupport.Known(Set(CpuFeature.Adx, CpuFeature.Bmi2)),
      LibraryBuild.Portable
    ),
    BlstReport(Platform.Other, CpuSupport.Unknown, LibraryBuild.Other("00ff")),
    BlstReport(Platform.LinuxAmd64, CpuSupport.Known(Set.empty), LibraryBuild.OnLibraryPath)
  )

  val plutusCryptoReports: List[NativeCryptoReport] = List(
    NativeCryptoReport(
      CryptoPlatform.Linux64,
      CryptoLibrary.Resource(CryptoPlatform.Linux64.resource, CryptoPlatform.Linux64.sha256)
    ),
    NativeCryptoReport(
      CryptoPlatform.LinuxArm64,
      CryptoLibrary.OnLibraryPath(
        Path.of("/usr/lib/libscalus_crypto.so"),
        CryptoPlatform.LinuxArm64.sha256
      )
    )
  )

  private val peerA: SocketAddress[Host] = SocketAddress(host"10.0.0.1", port"3001")
  private val peerB: SocketAddress[Host] = SocketAddress(host"relay.example.org", port"3001")
  private val peerC: SocketAddress[Host] = SocketAddress(host"10.0.0.3", port"3001")

  /** Every case of the governor's trace. */
  val peerSelection: List[Trace[SocketAddress[Host]]] = List(
    Trace.TargetsChanged(Targets.relay),
    Trace.LocalRootsChanged(3, 2),
    Trace.JudgementChanged(LedgerStateJudgement.TooOld),
    Trace.OnlyBootstrapPeers(),
    Trace.RootsRequest(60, 2),
    Trace.RootsResults(Set(peerB, peerA), 0, 3600.seconds),
    Trace.RootsFailure(-2, 4.seconds),
    Trace.Promote(Temperature.Cold, List(peerA, peerB), local = true, 30, 1),
    Trace.PromoteDone(Temperature.Warm, peerA, 30, 2),
    Trace.PromoteFailed(Temperature.Cold, peerB, FailureClass.Transient, Some(5.seconds), false),
    Trace.PromoteFailed(Temperature.Warm, peerB, FailureClass.Misbehaved, None, true),
    Trace.PromoteAborted(Temperature.Warm, peerA),
    Trace.Demote(
      Temperature.Hot,
      List(peerA),
      local = false,
      20,
      21,
      Map(peerA -> None, peerB -> Some(0))
    ),
    Trace.DemoteDone(Temperature.Warm, peerA, 29),
    Trace.DemoteFailed(Temperature.Hot, peerB, timedOut = true),
    Trace.DemoteAsynchronous(
      peerA,
      Temperature.Warm,
      None,
      None,
      xyz.kd5ujc.scarab.node.SessionEnd.returnReason(xyz.kd5ujc.scarab.node.SessionEnd.Returned),
      Some(10.seconds)
    ),
    Trace.DemoteAsynchronous(
      peerA,
      Temperature.Warm,
      None,
      None,
      xyz.kd5ujc.scarab.node.SessionEnd
        .returnReason(xyz.kd5ujc.scarab.node.SessionEnd.NoMoreIntersection),
      Some(120.seconds)
    ),
    Trace.DemoteAsynchronous(
      peerA,
      Temperature.Warm,
      None,
      None,
      xyz.kd5ujc.scarab.node.SessionEnd
        .returnReason(xyz.kd5ujc.scarab.node.SessionEnd.RolledBackPastIntersection(point)),
      Some(180.seconds)
    ),
    Trace.DemoteAsynchronous(
      peerB,
      Temperature.Cold,
      Some(FailureClass.Transient),
      Some("unavailable: connection closed")
    ),
    Trace.PeerActionFailed(
      peerA,
      xyz.kd5ujc.scarab.network.PeerOperation.Deactivate,
      xyz.kd5ujc.scarab.network.PeerActionError.ApplicationFailures(
        cats.data.NonEmptyVector.of(
          xyz.kd5ujc.scarab.network.ProtocolResults
            .MiniProtocolFailure(2, xyz.kd5ujc.scarab.network.NetworkError.Timeout(2, "idle")),
          xyz.kd5ujc.scarab.network.ProtocolResults.MiniProtocolFailure(
            3,
            xyz.kd5ujc.scarab.network.NetworkError
              .BearerClosed(xyz.kd5ujc.scarab.network.NetworkError.Closure.PeerClosed)
          )
        )
      )
    ),
    Trace.CachedBlockRejected(
      peerA,
      SourceRefusal.Typed(ValidationError(point, BlockFailure.HeaderDiffers)),
      BigInt(7),
      true
    ),
    Trace.CachedBlockRejected(
      peerA,
      SourceRefusal.Typed(ValidationError(point, BlockFailure.HeaderDiffers)),
      BigInt(8),
      false
    ),
    Trace.ForgetCold(150, 160, List(peerB)),
    Trace.Forgotten(List(peerA)),
    Trace.BigLedgerRequest(15, 3),
    Trace.BigLedgerResults(Set(peerB, peerA), 0, 5.seconds),
    Trace.BigLedgerFailure(-1, 2.seconds),
    Trace.ForgetBigLedger(15, 17, List(peerA)),
    Trace.PromoteBigLedger(Temperature.Cold, List(peerA, peerB), 10, 4),
    Trace.DemoteBigLedger(Temperature.Hot, List(peerB), 5, 6, Map(peerA -> Some(3), peerB -> None)),
    Trace.PickInbound(List(peerA)),
    Trace.ShareRequests(8, List(peerA)),
    Trace.ShareResults(List(peerA -> PeerShare.Result.Peers(Vector(peerB))), List(peerB))
  )

  /** Every case of the ledger-peers worker's trace. */
  val ledgerPeers: List[LedgerPeersTrace] = List(
    LedgerPeersTrace.SnapshotLoaded(WithOrigin.At(BigInt(123624952)), 37),
    LedgerPeersTrace.SnapshotIgnored(PeerSnapshotError.Missing("bigLedgerPools")),
    LedgerPeersTrace.SnapshotVerified(matches = true),
    LedgerPeersTrace.Refreshed(StakeSource.Ledger, 612, 118, WithOrigin.At(BigInt(125430904))),
    LedgerPeersTrace.Refreshed(StakeSource.Nothing, 0, 0, WithOrigin.Origin),
    LedgerPeersTrace
      .PoolSkipped(Bytes.fromArray(Array.fill(28)(7.toByte)), PoolRelayError.BadPort(1)),
    LedgerPeersTrace.Unreadable(MirrorError.DerivedMismatch(Derived.VrfKeyHashes))
  )

  /** Every case of the local roots' DNS trace (an invalid name leaves it empty, and the coverage
    * check fails).
    */
  val localRoots: List[LocalRootsTrace] =
    DnsName
      .from("relay.example")
      .toOption
      .toList
      .map(LocalRootsTrace.Error(_, new IllegalStateException("injected lookup defect")))

  private val counters: GovernorState.Counters =
    given Ordering[SocketAddress[Host]] = Ordering.by(_.toString)
    val roots = LocalRoots.fromGroups(
      List(
        RootGroupSpec(
          1,
          2,
          List(
            peerA -> RootConfig(
              false,
              DiffusionMode.InitiatorOnly,
              ConnectionState.Provenance.Outbound,
              true
            )
          )
        )
      )
    )
    val state = GovernorState
      .initial[SocketAddress[Host]](1L, roots, Targets.relay, Sensitivity.initial(true))
      .copy(
        peers = scala.collection.immutable.SortedMap(
          peerA -> PeerRecord.cold(Origin.Local).copy(status = Status.Hot),
          peerB -> PeerRecord.cold(Origin.Bootstrap).copy(status = Status.Warm(Duration.Zero)),
          peerC -> PeerRecord.cold(Origin.BigLedger).copy(status = Status.Hot)
        ),
        localRoots = roots
      )
    state.view(1.second).counters

  val peerCounters: List[GovernorState.Counters] = List(counters)

  /** One of each trace of the churn governor. */
  val churnTraces: List[Churn.Trace] = List(
    Churn.Trace.Mode(FetchMode.Deadline),
    Churn.Trace.Action(Churn.Step.DecreaseActive, 1500.millis, -4),
    Churn.Trace.Timeout(Churn.Step.IncreaseKnown, 80.seconds, 0),
    Churn.Trace.Wait(3412.seconds)
  )

  val outboundStates: List[OutboundConnectionsState] = OutboundConnectionsState.values.toList

  val gsmEvents: List[(Gsm.Phase, Gsm.Event)] = List(
    (Gsm.Phase.Syncing, Gsm.Event.PreSyncingToSyncing),
    (Gsm.Phase.PreSyncing, Gsm.Event.SyncingToPreSyncing),
    (Gsm.Phase.CaughtUp, Gsm.Event.EnterCaughtUp(2)),
    (Gsm.Phase.PreSyncing, Gsm.Event.LeaveCaughtUp(Gsm.DurationFromNow.Already)),
    (Gsm.Phase.PreSyncing, Gsm.Event.LeaveCaughtUp(Gsm.DurationFromNow.After(90.seconds)))
  )

  private val socket = LocalAddress("/ipc/node.socket")
  private val descriptor = FileDescriptor(20)
  private val exhausted =
    SystemCallError(SystemCall.Accept, ErrnoCause.Known(Errno.EMFILE), None)

  /** The node-to-client socket set up, warned of (a socket left to the umask 022, `0o140755`, and a
    * directory anyone may write, `0o41777`), and its server: a client accepted, an accept that
    * failed, the server stopped and ended by an error.
    */
  val localServer: List[LocalServer.Trace] = List(
    LocalServer.Trace.CreateSystemdSocketForSnocketPath(socket),
    LocalServer.Trace.CreatedLocalSocket(socket),
    LocalServer.Trace.ConfiguringLocalSocket(socket, descriptor),
    LocalServer.Trace.ConfiguredLocalSocket(socket, descriptor),
    LocalServer.Trace.ListeningLocalSocket(socket, descriptor),
    LocalServer.Trace.LocalSocketUp(socket, descriptor),
    LocalServer.Trace.InsecureLocalSocketPermissions(socket, 0xc1ed),
    LocalServer.Trace.InsecureLocalSocketDirectory(LocalAddress("/tmp/node é.socket"), 0x43ff),
    LocalServer.Trace.RunLocalServer(socket),
    LocalServer.Trace.ServerStarted(List(socket)),
    LocalServer.Trace.AcceptConnection(
      LocalConnectionId(socket, LocalAddress("/ipc/node.socket@0"))
    ),
    LocalServer.Trace.AcceptError(exhausted),
    LocalServer.Trace.ServerStopped,
    LocalServer.Trace.ServerError(DiffusionFailure.System(exhausted))
  )

  /** A directory where the socket should be, as the reference names it. */
  val socketConfigError: SocketConfigError = SocketConfigError.LocalSocketError(
    "/ipc/node.socket",
    SystemCallError(SystemCall.RemoveLink, ErrnoCause.Known(Errno.EISDIR), Some("/ipc/node.socket"))
  )

  /** A bind into a directory that is not there. */
  val diffusionErrored: DiffusionFailure =
    DiffusionFailure.System(SystemCallError(SystemCall.Bind, ErrnoCause.Known(Errno.ENOENT), None))

  val inbound: List[InboundEvent] = List(
    InboundEvent.Accepted,
    InboundEvent.Duplex,
    InboundEvent.Unidirectional,
    InboundEvent.Following(point),
    InboundEvent.SessionEnded(
      InboundGovernor.SessionFailure
        .Peer(PeerError.NoIntersection),
      dropped = false
    ),
    InboundEvent.SessionEnded(
      InboundGovernor.SessionFailure
        .Peer(PeerError.NoIntersection),
      dropped = true
    ),
    InboundEvent.SessionReturned(InboundGovernor.ReturnReason.NoMoreIntersection, 120.seconds),
    InboundEvent.SessionReturned(
      InboundGovernor.ReturnReason.RolledBackPastIntersection(point),
      180.seconds
    ),
    InboundEvent.CachedBlockRejected(
      ValidationError(point, BlockFailure.HeaderDiffers),
      generation = BigInt(7),
      sourceLive = true
    ),
    InboundEvent.CachedBlockRejected(
      ValidationError(point, BlockFailure.HeaderDiffers),
      generation = BigInt(8),
      sourceLive = false
    ),
    InboundEvent.Closed(
      InboundEnd.Connection(
        ConnectionEnd
          .Closed(NetworkError.Closure.PeerClosed)
      )
    ),
    InboundEvent.Refused(ConnectionError.InboundLimit("10.0.0.2:4000", 64))
  )

  val connection: List[ConnectionEvent] = List(
    ConnectionEvent.Opened("10.0.0.1:3001", ConnectionState.Provenance.Outbound),
    ConnectionEvent.Changed(
      "10.0.0.1:3001",
      ConnectionState.Reserved,
      ConnectionState.Unnegotiated(ConnectionState.Provenance.Outbound)
    ),
    ConnectionEvent.Refused(ConnectionError.InboundLimit("10.0.0.2:4000", 64)),
    ConnectionEvent.Closed(
      "10.0.0.1:3001",
      ConnectionState.Provenance.Inbound,
      ConnectionEnd
        .Closed(NetworkError.Closure.PeerClosed)
    ),
    ConnectionEvent.AcceptRateLimited(DiffTime.of(2500.millis), 448),
    ConnectionEvent.AcceptHardLimit(Word32.fromUnsigned(512)),
    ConnectionEvent.AcceptResumed(511)
  )

  val mempool: List[MempoolEvent] = List(
    MempoolEvent.Added(hash, 300, 2, 600L),
    MempoolEvent.Rejected(
      None,
      Rejection.Malformed(
        xyz.kd5ujc.scarab.ledger.TransactionDecodeProblem
          .Envelope(xyz.kd5ujc.scarab.codec.Value.Null)
      )
    ),
    MempoolEvent
      .Rejected(Some(hash), Rejection.Full(TxMeasure(300, 0, 0, 0), TxMeasure(100, 0, 0, 0))),
    MempoolEvent.Dropped(hash, Rejection.AlreadyHeld(hash)),
    MempoolEvent.Waiting(Some(hash), TxMeasure(300, 0, 0, 0), TxMeasure(100, 0, 0, 0)),
    MempoolEvent.Adopted(hash),
    MempoolEvent.Removed(hash),
    MempoolEvent.Synced(3, 900L, 1),
    MempoolEvent.Synced(3, 900L, 0)
  )

  val forgeStats: ForgeEpochStats = ForgeEpochStats(
    slotsChecked = 100,
    blockFromFuture = 2,
    noLedgerView = 1,
    notLeader = 92,
    leaderSlots = 5,
    forged = 5,
    adopted = 4,
    didntAdopt = 1,
    replaced = 1,
    latencyCount = 4,
    latencyTotalMs = 50,
    latencyMaxMs = 20
  )

  /** Every trace of the forging loop once. */
  val forgeTraces: List[ForgeTrace] =
    val tip = BlockRef(BigInt(4203), Bytes.fromArray(Array.fill(32)(9.toByte)), BigInt(170)).point
    List(
      ForgeTrace.StartLeadershipCheck(BigInt(4201)),
      ForgeTrace.SlotIsImmutable(BigInt(4203), tip, BigInt(170)),
      ForgeTrace.BlockFromFuture(BigInt(4204), BigInt(4206)),
      ForgeTrace.BlockContext(BigInt(4201), BigInt(18), point),
      ForgeTrace.LedgerState(BigInt(4201), point),
      ForgeTrace.NoLedgerView(BigInt(4201), OutsideForecastRange(Some(BigInt(10)), 141, 4201)),
      ForgeTrace.LedgerView(BigInt(4201)),
      ForgeTrace.ForgeStateUpdateError(
        BigInt(6200),
        KesEvolutionError.CouldNotEvolve(KesInfo(BigInt(0), BigInt(62), 61), BigInt(62))
      ),
      ForgeTrace.NodeCannotForge(BigInt(4201), CannotForge.KeyNotUsableYet(33, 40)),
      ForgeTrace.NodeNotLeader(BigInt(4201)),
      ForgeTrace.NodeIsLeader(BigInt(4201)),
      ForgeTrace.ForgeTickedLedgerState(BigInt(4201), point),
      ForgeTrace.ForgingMempoolSnapshot(BigInt(4201), point, Some(hash), BigInt(4201)),
      ForgeTrace.ForgedBlock(BigInt(4201), point, hash, BigInt(18), Some(hash)),
      ForgeTrace.DidntAdoptBlock(BigInt(4201), hash),
      ForgeTrace.ForgedInvalidBlock(
        BigInt(4201),
        hash,
        ValidationError(point, BlockFailure.HeaderDiffers)
      ),
      ForgeTrace.AdoptedBlock(BigInt(4201), hash, 1890L, Vector(hash))
    ) ++ List(
      // the first block, on genesis; a key already poisoned; the mempool at the origin
      ForgeTrace.BlockContext(BigInt(1), BigInt(0), ChainSync.Point.Origin),
      ForgeTrace.ForgeStateUpdateError(
        BigInt(6201),
        KesEvolutionError.KeyAlreadyPoisoned(KesInfo(BigInt(0), BigInt(62), 61), BigInt(62))
      ),
      ForgeTrace.ForgingMempoolSnapshot(BigInt(1), ChainSync.Point.Origin, None, BigInt(0)),
      ForgeTrace.ForgedBlock(BigInt(1), ChainSync.Point.Origin, hash, BigInt(0), None)
    )
  val forge: List[ForgeEvent] = List(
    ForgeEvent.Started(other, hash, BigInt(0), BigInt(62), BigInt(3), BigInt(10))
  ) ++ forgeTraces.map(ForgeEvent.Loop(_)) ++ List(
    // far from expiry, at seven periods left, and at one
    ForgeEvent.Clock(
      ClockTrace.StartTimeInTheFuture(java.time.Instant.parse("2026-10-01T00:00:00Z"), 1500.millis)
    ),
    ForgeEvent.Clock(
      ClockTrace.CurrentSlotUnknown(java.time.Instant.parse("2026-10-01T05:00:00Z"))
    ),
    ForgeEvent.Clock(
      ClockTrace.SystemClockMovedBack(
        java.time.Instant.parse("2026-10-01T05:00:10Z"),
        java.time.Instant.parse("2026-10-01T05:00:02Z")
      )
    ),
    ForgeEvent.StateInfo(KesInfo(BigInt(30), BigInt(92), 3)),
    ForgeEvent.StateInfo(KesInfo(BigInt(30), BigInt(92), 55)),
    ForgeEvent.StateInfo(KesInfo(BigInt(30), BigInt(92), 61)),
    ForgeEvent.Forged(
      BigInt(4200),
      BigInt(3),
      BigInt(17),
      hash,
      Some(hash),
      Vector(hash),
      1024L,
      BigInt(32),
      1,
      BigInt(500),
      BigInt(1000),
      BigInt(3)
    ),
    ForgeEvent.Forged(
      BigInt(1),
      BigInt(0),
      BigInt(0),
      hash,
      None,
      Vector.empty,
      0L,
      BigInt(0),
      0,
      BigInt(0),
      BigInt(0),
      BigInt(0)
    ),
    ForgeEvent.Adopted(block, Some(12L)),
    ForgeEvent.Adopted(block, None),
    ForgeEvent.Lost(BigInt(4200), hash, "slot battle"),
    ForgeEvent.KesEvolved(BigInt(33), 2),
    ForgeEvent.KesReloaded(BigInt(4), BigInt(30), BigInt(62), BigInt(33)),
    ForgeEvent.KesReloadRefused(ReloadRefusal.Counter(OCertError.CounterTooSmall(5, 3))),
    ForgeEvent.KesPersistFailed(
      BigInt(33),
      ForgeError.KeyFile(
        "kes.skey",
        xyz.kd5ujc.scarab.ledger.ForgeKeyProblem.Io(new java.io.IOException("disk full"))
      )
    ),
    ForgeEvent.KeyFileGroupAccess("vrf.skey", 0x1a0),
    ForgeEvent.Failed(BigInt(4202), ForgeError.KeyPeriodOutOfRange(33, 0, 32)),
    ForgeEvent.EpochSummary(BigInt(3), forgeStats),
    ForgeEvent.EpochSummary(BigInt(4), ForgeEpochStats())
  )

  val scheduling: SchedulingReport = SchedulingReport(
    120L,
    35L,
    2L,
    1L,
    Map(
      "rules" -> SchedulingReport.Step(10L, 40L, 200L),
      "select" -> SchedulingReport.Step(5L, 3L, 9L)
    )
  )

  val follower: List[FollowerEvent] = List(
    FollowerEvent.Connected(ChainSync.Point.Origin),
    FollowerEvent.Connected(point),
    FollowerEvent.Extended(block, BigInt(3), tip, Era.Conway),
    FollowerEvent.RolledBack(point, ChainSync.Point.Origin, 2),
    FollowerEvent.EpochEntered(BigInt(4), block),
    FollowerEvent.Immutable(block),
    FollowerEvent.Reconnecting(
      PeerError.Unavailable(
        PeerError.Unavailability.Network(
          xyz.kd5ujc.scarab.protocol.NetworkError
            .Connect("10.0.0.1:3001", new java.io.IOException("connection refused"))
        )
      ),
      3
    ),
    FollowerEvent.EraEntered(None, Era.Byron, block, BigInt(0)),
    FollowerEvent.EraEntered(Some(Era.Babbage), Era.Conway, block, BigInt(5)),
    FollowerEvent.EpochValidated(BigInt(2), Era.Shelley, 10L, 20L, 3L),
    FollowerEvent.ByronEpochValidated(BigInt(1), 1L, 40L, 2L, 7L),
    FollowerEvent.LedgerEpoch(
      BigInt(5),
      1000,
      20,
      3,
      BigInt("1000000000"),
      BigInt("44000000000000000"),
      (BigInt(10), BigInt(0)),
      PlutusStats(4, 1, 7, 5)
    )
  )

  val anchor: Option[BlockRef] = Some(block)
  val network: NetworkIdentity = NetworkIdentity(
    42L,
    hash,
    Some(hash),
    xyz.kd5ujc.scarab.ledger.GenesisFileHash(hash),
    xyz.kd5ujc.scarab.ledger.GenesisFileHash(hash)
  )
  val storage: List[StorageEvent] = List(
    StorageEvent.ImmutableOpened(ImmutableStore.OpenReport(anchor, 100L, 2, 1, 64L)),
    StorageEvent.ImmutableOpened(ImmutableStore.OpenReport(None, 0L, 0, 0, 0L)),
    StorageEvent.FormatMigrated(
      Migrated(FileKind.SnapshotFile, FormatVersion.V3, FormatVersion.V4, 3)
    ),
    StorageEvent.SnapshotSkipped(
      "4200-abab",
      StorageError.Corrupt("snapshot", xyz.kd5ujc.scarab.codec.BinaryError.UnexpectedEnd(0))
    ),
    StorageEvent.SnapshotRestored("4200-abab", anchor),
    StorageEvent.SnapshotRestored("genesis", None),
    StorageEvent.SnapshotUnbound("4200-abab", anchor),
    StorageEvent.SnapshotWritten("4200-abab", anchor, 2048L, 15L),
    StorageEvent.SnapshotFailed(
      xyz.kd5ujc.scarab.storage.SnapshotFailure.Defect(new java.io.IOException("disk full"))
    ),
    StorageEvent.SnapshotRefused(
      "4200-abab",
      anchor,
      SnapshotRefusal.Mirror(StateMirrorError.MissingEra(Era.Conway))
    ),
    StorageEvent.ReplayStarted(None, anchor),
    StorageEvent.ReplayProgress(block, 1000L, 2000L),
    StorageEvent.ReplayFinished(anchor, 1000L, 2500L),
    StorageEvent.NetworkRecorded(network, adopted = false),
    StorageEvent.NetworkRecorded(
      NetworkIdentity(
        1L,
        hash,
        None,
        xyz.kd5ujc.scarab.ledger.GenesisFileHash(hash),
        xyz.kd5ujc.scarab.ledger.GenesisFileHash(hash)
      ),
      adopted = true
    ),
    StorageEvent.VolatileOpened(VolatileStore.OpenReport(10, 2, 0L, 0)),
    StorageEvent.VolatileResumed(10, 1, 300L),
    StorageEvent.ShutdownSnapshot(anchor, written = true)
  )

  val errors: List[xyz.kd5ujc.scarab.ledger.NodeError] = List(
    PeerError.NoIntersection,
    ChainError.Decode(
      xyz.kd5ujc.scarab.ledger.DecodeField.Block,
      xyz.kd5ujc.scarab.ledger.LedgerDecodeError
        .Syntax(xyz.kd5ujc.scarab.codec.Cbor.Error.UnexpectedEnd)
    ),
    StorageError.Missing("immutable/format")
  )

  val memory: Json = Json.obj("heapUsed" -> 1024L.asJson, "rssAnon" -> Json.Null)
