// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import cats.{Eq, Show}
import cats.derived.*
import monocle.{Focus, Lens}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.state.{
  BlockIssuer,
  Genesis,
  KeyHash,
  SlotNo,
  Sorted,
  Word64,
  WithOrigin
}
import xyz.kd5ujc.scarab.protocol.Era

// The chain-dependent state of the three protocols a Cardano chain has run: PBFT (Byron), TPraos
// (Shelley to Alonzo) and Praos (Babbage, Conway), as the telescope of `HeaderState` holds it. Field
// orders are the Haskell records'. The names are the Haskell names. Since step 2, jump 1 (J1.5) these
// are the types the node itself runs on: `Praos`, `TPraos` and the PBFT window of `ByronRules` evolve
// them, and `NodeState` holds them in its `HeaderState`.

/** `PBftSigner` (`Protocol/PBFT/State.hs:146-149`): who signed the block of a slot. */
final case class PBftSigner(slot: SlotNo, genesisKey: KeyHash[Genesis]) derives Eq, Show

object PBftSigner:
  val slot: Lens[PBftSigner, SlotNo] = Focus[PBftSigner](_.slot)
  val genesisKey: Lens[PBftSigner, KeyHash[Genesis]] = Focus[PBftSigner](_.genesisKey)

/** `PBftState` (`State.hs:82-92`): the signatures of the last `n` slots, and the cached count per
  * genesis key (which [[PBftState.ofWindow]] recomputes: Haskell's `computeCounts`,
  * `State.hs:102-109`).
  */
final case class PBftState(
    inWindow: Vector[PBftSigner],
    counts: SortedMap[KeyHash[Genesis], Word64]
) derives Eq,
      Show

object PBftState:
  /** No block signed yet. */
  val empty: PBftState = PBftState(Vector.empty, Sorted.empty[KeyHash[Genesis], Word64])

  extension (p: PBftState)
    /** The slot of the newest signer in the window. */
    def lastSignedSlot: Option[SlotNo] = p.inWindow.lastOption.map(_.slot)

    /** How many of the blocks in the window `genesisKey` signed. */
    def countSignedBy(genesisKey: KeyHash[Genesis]): Word64 =
      p.counts.getOrElse(genesisKey, Word64.zero)

    /** `PBftState.append` (`State.hs:125-139`): the newest signer joins, and the oldest leaves once
      * the window holds `windowSize`. The counts are kept up to date, not recomputed; they stay
      * those of [[ofWindow]] (a key with no signer in the window has no entry).
      */
    def append(signer: PBftSigner, windowSize: Int): PBftState =
      val joined = p.counts.updated(
        signer.genesisKey,
        Word64.fromUnsigned(p.countSignedBy(signer.genesisKey).bits + 1)
      )
      if p.inWindow.size == windowSize then
        val oldest = p.inWindow.head
        val left = Word64.fromUnsigned(joined.getOrElse(oldest.genesisKey, Word64.zero).bits - 1)
        PBftState(
          p.inWindow.tail :+ signer,
          if left.isZero then joined.removed(oldest.genesisKey)
          else joined.updated(oldest.genesisKey, left)
        )
      else PBftState(p.inWindow :+ signer, joined)

  /** The state of a window of signers, with the counts it implies. */
  def ofWindow(window: Vector[PBftSigner]): PBftState =
    PBftState(
      window,
      Sorted.map(
        window.groupMapReduce(_.genesisKey)(_ => 1L)(_ + _).view.mapValues(Word64.fromUnsigned)
      )
    )

  val inWindow: Lens[PBftState, Vector[PBftSigner]] = Focus[PBftState](_.inWindow)
  val counts: Lens[PBftState, SortedMap[KeyHash[Genesis], Word64]] = Focus[PBftState](_.counts)

/** `PrtclState` (`TPraos/Rules/Prtcl.hs:67-75`): the operational-certificate counters, the evolving
  * and the candidate nonce.
  */
final case class PrtclState(
    ocertCounters: SortedMap[KeyHash[BlockIssuer], Word64],
    evolvingNonce: Nonce,
    candidateNonce: Nonce
) derives Eq,
      Show

object PrtclState:
  val ocertCounters: Lens[PrtclState, SortedMap[KeyHash[BlockIssuer], Word64]] =
    Focus[PrtclState](_.ocertCounters)
  val evolvingNonce: Lens[PrtclState, Nonce] = Focus[PrtclState](_.evolvingNonce)
  val candidateNonce: Lens[PrtclState, Nonce] = Focus[PrtclState](_.candidateNonce)

/** `TicknState` (`TPraos/Rules/Tickn.hs:32-35`): the epoch nonce and the hash nonce of the last
  * header of the previous epoch.
  */
final case class TicknState(epochNonce: Nonce, prevHashNonce: Nonce) derives Eq, Show

object TicknState:
  val epochNonce: Lens[TicknState, Nonce] = Focus[TicknState](_.epochNonce)
  val prevHashNonce: Lens[TicknState, Nonce] = Focus[TicknState](_.prevHashNonce)

/** The ledger's `ChainDepState` of TPraos (`TPraos/API.hs:432-437`; Haskell calls it
  * `SL.ChainDepState`, here `TPraosLedgerState` to keep it apart from the enum below).
  */
final case class TPraosLedgerState(protocol: PrtclState, tickn: TicknState, labNonce: Nonce)
    derives Eq,
      Show

object TPraosLedgerState:
  val protocol: Lens[TPraosLedgerState, PrtclState] = Focus[TPraosLedgerState](_.protocol)
  val tickn: Lens[TPraosLedgerState, TicknState] = Focus[TPraosLedgerState](_.tickn)
  val labNonce: Lens[TPraosLedgerState, Nonce] = Focus[TPraosLedgerState](_.labNonce)

/** `TPraosState` (`Protocol/TPraos.hs:263-267` in ouroboros-consensus-protocol): the slot of the
  * last applied header and the ledger's chain-dependent state.
  */
final case class TPraosState(lastSlot: WithOrigin[SlotNo], chainDep: TPraosLedgerState)
    derives Eq,
      Show

object TPraosState:
  val lastSlot: Lens[TPraosState, WithOrigin[SlotNo]] = Focus[TPraosState](_.lastSlot)
  val chainDep: Lens[TPraosState, TPraosLedgerState] = Focus[TPraosState](_.chainDep)

/** `PraosState` (`Protocol/Praos.hs:270-288`, eight fields): the last slot, the operational
  * certificate counters, and the six nonces that evolve over an epoch.
  */
final case class PraosState(
    lastSlot: WithOrigin[SlotNo],
    ocertCounters: SortedMap[KeyHash[BlockIssuer], Word64],
    evolvingNonce: Nonce,
    candidateNonce: Nonce,
    epochNonce: Nonce,
    previousEpochNonce: Nonce,
    labNonce: Nonce,
    lastEpochBlockNonce: Nonce
) derives Eq,
      Show

object PraosState:
  val lastSlot: Lens[PraosState, WithOrigin[SlotNo]] = Focus[PraosState](_.lastSlot)
  val ocertCounters: Lens[PraosState, SortedMap[KeyHash[BlockIssuer], Word64]] =
    Focus[PraosState](_.ocertCounters)
  val evolvingNonce: Lens[PraosState, Nonce] = Focus[PraosState](_.evolvingNonce)
  val candidateNonce: Lens[PraosState, Nonce] = Focus[PraosState](_.candidateNonce)
  val epochNonce: Lens[PraosState, Nonce] = Focus[PraosState](_.epochNonce)
  val previousEpochNonce: Lens[PraosState, Nonce] = Focus[PraosState](_.previousEpochNonce)
  val labNonce: Lens[PraosState, Nonce] = Focus[PraosState](_.labNonce)
  val lastEpochBlockNonce: Lens[PraosState, Nonce] = Focus[PraosState](_.lastEpochBlockNonce)

/** The chain-dependent state of one era of the telescope (`ChainDepState (BlockProtocol blk)`): the
  * protocol that era runs.
  */
enum ChainDepState derives Eq, Show:
  case PBft(state: PBftState)
  case TPraos(state: TPraosState)
  case Praos(state: PraosState)

object ChainDepState:
  /** The first era whose state this is: the era a telescope is placed in while its bounds are
    * unknown ([[Telescope.unplaced]]).
    */
  def firstEra(state: ChainDepState): Era = state match
    case PBft(_)   => Era.Byron
    case TPraos(_) => Era.Shelley
    case Praos(_)  => Era.Babbage

  /** The state of a protocol is in the eras that run it: PBFT in Byron, TPraos from Shelley to
    * Alonzo, Praos from Babbage on (`Era.usesTPraos`).
    */
  given EraIndexed[ChainDepState] = EraIndexed.instance {
    case (era, PBft(_))   => era == Era.Byron
    case (era, TPraos(_)) => era.usesTPraos
    case (era, Praos(_))  => era.ordinal >= Era.Babbage.ordinal
  }
