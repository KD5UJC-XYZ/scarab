// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ByronRules, PraosConfig}
import xyz.kd5ujc.scarab.consensus.ConsensusExtras
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorExtras, MirrorState}
import xyz.kd5ujc.scarab.ledger.{BlockFailure, Header}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.{Diff, ShelleyBasedEra, Sorted}

/** The era-indexed block rules on one block, as the suites over captured chains run them: pure,
  * from the mirror before the block to the mirror after it, with the configuration of a
  * [[NodeSetup]].
  */
object MirrorSteps:
  /** The block `raw` (announced by `header`) applied to `state`; `praos` and `byron` replace the
    * setup's configuration where a test wants another.
    */
  def block(
      setup: NodeSetup,
      state: MirrorState,
      header: Header,
      raw: Bytes,
      praos: Option[PraosConfig] = None,
      byron: Option[ByronRules.Config] = None
  ): Either[BlockFailure, MirrorState] =
    MirrorRules.applyBlock(
      state,
      header,
      raw,
      praos.getOrElse(setup.praos),
      setup.globals,
      byron.orElse(setup.byronRules),
      setup.handOver,
      setup.byronHistory
    )

  /** The ledger state of the Shelley-based era a mirror is in, read as a test reads it. */
  def ledger(s: MirrorState): Option[InEra] = s.ledger(None).toOption.flatten

  /** The mirror with its consensus bookkeeping (the bootstrap state, the Byron state, the protocol
    * extras) changed by `f`: a state of a chain at another point of its history, for the tests of
    * what is read from it.
    */
  def withConsensus(m: MirrorState)(f: ConsensusExtras => ConsensusExtras): MirrorState =
    (m.extras, m.ext.ledgerState.state) match
      case (MirrorExtras.InShelley(ledger, consensus), _) =>
        m.copy(extras = MirrorExtras.InShelley(ledger, f(consensus)))
      case (MirrorExtras.InByron(pending, boot, protocol), EraLedgerState.ByronCurrent(b)) =>
        val next = f(ConsensusExtras(boot, b, protocol))
        m.copy(
          ext = m.ext.copy(ledgerState =
            m.ext.ledgerState.withState(EraLedgerState.ByronCurrent(next.byron))
          ),
          extras = MirrorExtras.InByron(pending, next.boot, next.protocol)
        )
      case _ => m

  /** A pool registered with the empty relay list holds the item as the certificate gave it (`80`)
    * and reads back as one with none (`Bytes.empty`): the Haskell encoding writes both as the empty
    * array. The state does not tell them apart, and nothing reads the relays.
    */
  def bare(m: MirrorState): MirrorState =
    val empty = Bytes.fromArray(Array(0x80.toByte))
    def plain(r: Bytes) = if r == empty then Bytes.empty else r
    m.ext.ledgerState.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        given ShelleyBasedEra[e] = i.era
        val nes = i.state.nes
        val ps = EraLedger.pState(nes.es.ls)
        val bared = ps.copy(
          stakePools =
            Sorted.map(ps.stakePools.toList.map((k, v) => k -> v.copy(relays = plain(v.relays)))),
          future = Sorted.map(ps.future.toList.map((k, v) => k -> v.copy(relays = plain(v.relays))))
        )
        val state = EraLedgerState.InShelleyEra(
          i.era,
          i.state.copy(nes =
            nes.copy(es = nes.es.copy(ls = EraLedger.withPState(nes.es.ls, bared)))
          )
        )
        m.copy(ext = m.ext.copy(ledgerState = m.ext.ledgerState.withState(state)))
      case _ => m

  /** The first difference between two states (the empty relay list apart), rendered, if any. */
  def firstDifference(got: MirrorState, want: MirrorState): Option[String] =
    Diff[MirrorState]
      .diff(bare(got), bare(want))
      .map(d => s"${d.path.render}: ${d.left} /= ${d.right}")
