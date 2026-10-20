// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

/** What the consensus layer says of the age of the ledger tip (`Cardano.Network.
  * LedgerStateJudgement`): a node whose ledger is `TooOld` has to catch up and, when bootstrap
  * peers are configured, may then talk only to trusted peers. The source is the Genesis State
  * Machine ([[Gsm]], `gsmStateToLedgerJudgement`).
  */
enum LedgerStateJudgement:
  case TooOld, YoungEnough

/** The peer-selection behavior selected by consensus. Genesis policy is deliberately limited to
  * sync targets and the trusted-state signal; chain density and fork choice remain in consensus.
  */
enum ConsensusMode:
  case PraosMode, GenesisMode

/** The Cardano extension of the governor's state (`ExtraState`,
  * `Cardano/Network/PeerSelection/Governor/PeerSelectionState.hs` 33-61): the judgement, whether
  * bootstrap peers are in use, whether the governor has reached the clean state of the sensitive
  * state, and the deadline for reaching it.
  *
  * The node is in the *sensitive state* when bootstrap peers are in use and the ledger is `TooOld`
  * (`requiresBootstrapPeers`, `Cardano/Network/PeerSelection/Bootstrap.hs` 44-46): only trustable
  * peers may be used. It makes progress there only once it has dropped every other peer
  * (`hasOnlyBootstrapPeers`); until then its targets are zero.
  *
  * @param timeout
  *   The time by which the clean state must be reached; the node stops when it passes
  *   ([[GovernorError.BootstrapPeersCriticalTimeout]]).
  */
final case class Sensitivity(
    judgement: LedgerStateJudgement,
    useBootstrapPeers: Boolean,
    hasOnlyBootstrapPeers: Boolean,
    timeout: Option[Time],
    consensusMode: ConsensusMode = ConsensusMode.PraosMode,
    syncTargets: Targets = Targets.sync,
    deadlineTargets: Targets = Targets.relay,
    minBigLedgerPeersForTrustedState: Int = 5
):
  /** The sensitive state. */
  def sensitive: Boolean =
    useBootstrapPeers && judgement == LedgerStateJudgement.TooOld

  /** `isNodeAbleToMakeProgress`: not in the sensitive state, or already clean in it. */
  def ableToMakeProgress: Boolean = !sensitive || hasOnlyBootstrapPeers

  /** Genesis uses the sync target set until consensus reports that the ledger is recent enough. */
  def syncingGenesis: Boolean =
    consensusMode == ConsensusMode.GenesisMode && judgement == LedgerStateJudgement.TooOld

object Sensitivity:
  /** The reference starts with a `TooOld` judgement, and `monitorBootstrapPeersFlag` sets it to
    * `YoungEnough` when it first reads the bootstrap flag; the real judgement then arrives as a
    * change. This is the state after the flag was read.
    */
  def initial(
      useBootstrapPeers: Boolean,
      consensusMode: ConsensusMode = ConsensusMode.PraosMode,
      syncTargets: Targets = Targets.sync,
      deadlineTargets: Targets = Targets.relay,
      minBigLedgerPeersForTrustedState: Int = 5
  ): Sensitivity =
    Sensitivity(
      LedgerStateJudgement.YoungEnough,
      useBootstrapPeers,
      false,
      None,
      consensusMode,
      syncTargets,
      deadlineTargets,
      minBigLedgerPeersForTrustedState
    )

  /** The time a node may take to reach the clean state (`governor_BOOTSTRAP_PEERS_TIMEOUT`,
    * `Cardano/Network/PeerSelection/Governor/Monitor.hs` 65).
    */
  val CleanStateTimeout: Time = scala.concurrent.duration.DurationInt(15).minutes

/** Whether the node is connected only to peers it trusts, which consensus reads to decide it may
  * consider itself caught up (`OutboundConnectionsState`, `Cardano/Network/PeerSelection/Governor/
  * Types.hs` 57-109). The Genesis State Machine takes it as the Honest Availability Assumption
  * ([[Gsm]]).
  */
enum OutboundConnectionsState:
  case TrustedStateWithExternalPeers, UntrustedState

/** `AssociationMode` (`Types.hs` 177-203): a node that uses no ledger peers, no peer sharing and no
  * non-empty bootstrap list talks only to its local roots.
  */
enum AssociationMode:
  case LocalRootsOnly, Unrestricted

object AssociationMode:
  /** `readAssociationMode`. */
  def of(
      useLedgerPeers: Boolean,
      peerSharing: Boolean,
      useBootstrapPeers: Boolean,
      bootstrapEmpty: Boolean
  ): AssociationMode =
    if !useLedgerPeers && !peerSharing && (!useBootstrapPeers || bootstrapEmpty) then LocalRootsOnly
    else Unrestricted
