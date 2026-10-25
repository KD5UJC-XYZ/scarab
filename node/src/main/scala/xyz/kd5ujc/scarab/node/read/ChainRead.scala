// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import xyz.kd5ujc.scarab.consensus.{BlockRef, ByronState, ConsensusExtras}
import xyz.kd5ujc.scarab.consensus.state.*
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.ledger.state.{EraTag, ShelleyBasedEra}
import scala.annotation.unused
import xyz.kd5ujc.scarab.protocol.Era

/** The ledger state of a Shelley-based era, with the witness of which: the era parameter packed so
  * that it is existential. Match on it and the witness is in scope, with no cast.
  */
enum InEra:
  case Of[E <: EraTag](era: ShelleyBasedEra[E], read: EraRead[E])

/** What the read paths ask of the state the node's chain holds, whichever form it is in: the
  * consensus half (the chain-dependent state, the bootstrap and Byron state), the era the chain is
  * in and the ledger state of that era as an [[EraRead]].
  *
  * The node's state is the mirror ([[MirrorState]]), the state of the era-indexed rules, read as it
  * is by the given instance. A reader takes `[S: ChainRead]`, so that a test can read a state of
  * its own; nothing at run time chooses (design `ledger-mirror-step2.md` §4, J2.7).
  */
trait ChainRead[S]:
  extension (s: S)
    /** The era the state is in: Byron while the Byron ledger is the chain's, else the era of the
      * bootstrap mode, else the one the protocol version and epoch put it in.
      */
    def era: Era

    /** Completeness and era observations belonging to this exact acquired/rollback state. */
    def provenance: MirrorProvenance

    /** The epoch of the ledger state (of the placeholder ledger while the chain is in Byron). */
    def ledgerEpoch: BigInt

    /** The chain-dependent state of the protocol the chain runs, with what the node keeps beside
      * it.
      */
    def protocol: ProtocolState

    /** What the node keeps of the consensus side beside the header state. */
    def consensus: ConsensusExtras

    /** Plutus phase-2 counters. */
    def plutus: flat.PlutusStats

    /** The UTxO set of the state: the handle on the diff over the backing store. */
    def utxo: flat.Utxo

    /** The ledger state of the era the chain is in, at the tip `at`; none while the chain is in
      * Byron, which has no new-epoch state.
      */
    def ledger(at: Option[BlockRef]): Read[Option[InEra]]

    /** The slot to time translation of the transactions of a block of `era` at `slot` (or of the
      * mempool's next block) applied on this state: the era history at its tip with the forecast
      * horizon ([[MirrorClock]]). A state with no era history of its own has no horizon.
      */
    def clock(
        placement: Placement,
        @unused era: Era,
        @unused slot: BigInt
    ): flat.PosixClock =
      flat.PosixClock.total(placement.geometry)

    /** The era history as this state knows it: a chain whose Byron span is derived learns the first
      * Shelley epoch when it enters Shelley.
      */
    def geometry(base: EpochGeometry): EpochGeometry =
      consensus.byron.firstShelleyEpoch.fold(base)(base.resolved)

object ChainRead:
  def apply[S](using read: ChainRead[S]): ChainRead[S] = read

  /** A mirror is read as it is: the header state's telescope and the ledger state's say the era,
    * the extras the rest.
    */
  given ChainRead[MirrorState] with
    extension (m: MirrorState)
      def era: Era = m.ext.ledgerState.era
      def provenance: MirrorProvenance = m.provenance
      def ledgerEpoch: BigInt = m.ext.ledgerState.state match
        case EraLedgerState.ByronCurrent(_)    => BigInt(0)
        case i: EraLedgerState.InShelleyEra[e] => i.state.nes.epoch.toBigInt
      def protocol: ProtocolState = ProtocolState(depState(m), consensus.protocol)
      def consensus: ConsensusExtras = m.extras match
        case MirrorExtras.InShelley(_, c) => c
        case MirrorExtras.InByron(_, boot, protocol) =>
          ConsensusExtras(
            boot,
            m.ext.ledgerState.state match
              case EraLedgerState.ByronCurrent(b) => b
              case _                              => ByronState.initial
            ,
            protocol
          )
      def plutus: flat.PlutusStats = m.extras match
        case MirrorExtras.InShelley(x, _)  => x.plutus
        case MirrorExtras.InByron(_, _, _) => flat.PlutusStats.zero
      def utxo: flat.Utxo = m.ext.ledgerState.state match
        case EraLedgerState.ByronCurrent(_)    => flat.Utxo.empty
        case i: EraLedgerState.InShelleyEra[e] => i.state.nes.es.ls.utxo.utxo.utxo
      override def clock(placement: Placement, era: Era, slot: BigInt): flat.PosixClock =
        MirrorClock.of(m, placement, era, slot)
      def ledger(at: Option[BlockRef]): Read[Option[InEra]] =
        Right(m.ext.ledgerState.state match
          case EraLedgerState.ByronCurrent(_) => None
          case i: EraLedgerState.InShelleyEra[e] =>
            Some(InEra.Of(i.era, new MirrorEraRead[e](i.state.nes)(using i.era))))

  /** The chain-dependent state of a mirror: the current state of the header state's telescope, or
    * the one Shelley will start from while the chain is in Byron.
    */
  private def depState(m: MirrorState): ChainDepState = m.extras match
    case MirrorExtras.InByron(pending, _, _) => pending
    case MirrorExtras.InShelley(_, _)        => m.ext.headerState.chainDep.state
