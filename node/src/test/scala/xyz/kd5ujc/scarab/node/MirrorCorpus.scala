// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.Kleisli
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusExtras,
  ConsensusMirrorEnv,
  EraLedgerState,
  MirrorState,
  StateMirror,
  StateMirrorError,
  StateTampers
}
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.state.{Gap, LedgerState}
import xyz.kd5ujc.scarab.protocol.Era

/** What the whole mirror (`StateMirror`: the consensus side over the ledger side) does to a state
  * of the node, observed: the shared body of the corpus checks (the eras fixture of the suite, the
  * real Preview snapshots of the runner).
  */
object MirrorCorpus:
  type Result[A] = Either[StateMirrorError, A]
  type M[A] = Kleisli[Result, ConsensusMirrorEnv, A]

  val mirror: StateMirror[M] = StateMirror[M]

  /** What is observed of one state. */
  final case class Report(
      error: Option[StateMirrorError],
      era: Era,
      eraAgrees: Boolean,
      residueEmpty: Boolean,
      gaps: Set[Gap],
      distributionIsSet: Boolean,
      retraction: Boolean,
      tampers: Vector[(String, Boolean)],
      pools: Int = 0,
      vrfKeyHashes: Int = 0
  ):
    def ok: Boolean =
      error.isEmpty && eraAgrees && residueEmpty && retraction && tampers.forall(_._2)

  /** The state as a comparison sees it: the UTxO is a handle over a RocksDB-backed set, compared by
    * what the mirror may not change (its totals and stake maps) and not entry by entry.
    */
  private def view(s: NodeState) =
    (
      s.copy(ledger = s.ledger.copy(utxo = node.Utxo.empty)),
      s.ledger.utxo.count,
      s.ledger.utxo.coin,
      s.ledger.utxo.stake,
      s.ledger.utxo.ptrStake,
      s.ledger.utxo.stamp,
      s.ledger.utxo.flushed,
      s.ledger.utxo.diff.size
    )

  /** The number of pools and of VRF key hashes of a mirror's Shelley-based state. */
  private def poolCounts(m: MirrorState): (Int, Int) = m.ext.ledgerState.state match
    case _: EraLedgerState.ByronCurrent => (0, 0)
    case i: EraLedgerState.InShelleyEra[e] =>
      val p = LedgerState.pState[e](using i.era).get(i.state.nes.es.ls)
      (p.stakePools.size, p.vrfKeyHashes.size)

  /** The report of the state `s` after `anchor`; `expected` is the era it should be in, where the
    * corpus knows it (the era of the block, for the fixture).
    */
  def check(
      env: ConsensusMirrorEnv,
      anchor: Option[BlockRef],
      s: NodeState,
      expected: Option[Era] = None,
      withTampers: Boolean = true
  ): Report =
    val era = StateMirror.eraOf(env, s)
    val agrees = expected.forall(_ == era)
    mirror.toMirror(anchor, s).run(env) match
      case Left(e) => Report(Some(e), era, agrees, false, Set.empty, false, false, Vector.empty)
      case Right(m) =>
        val back = mirror.fromMirror(m.value).run(env)
        val retraction = back.exists((a, b) =>
          a == anchor && view(b) == view(StateMirror.normalised(env, s))
        )
        val tampers =
          if withTampers then
            StateTampers
              .all(s, m.value)
              .map(t => t.name -> (mirror.fromMirror(t.state).run(env) == Left(t.expected)))
              .toVector
          else Vector.empty
        val (residueEmpty, distributionIsSet) = m.value.extras match
          case ConsensusExtras.InShelley(ledger, _, _, _) =>
            (ledger.residue.isEmpty, ledger.poolDistribution.isEmpty)
          case _: ConsensusExtras.InByron => (true, true)
        val (pools, vrfs) = poolCounts(m.value)
        Report(
          back.left.toOption,
          era,
          agrees,
          residueEmpty,
          m.gaps,
          distributionIsSet,
          retraction,
          tampers,
          pools,
          vrfs
        )

  /** The era-independent summary of many reports, for the log. */
  def summary(label: String, reports: Seq[(String, Report)]): String =
    val bad = reports.filterNot(_._2.ok)
    val gaps = reports.flatMap(_._2.gaps).groupBy(identity).view.mapValues(_.size).toMap
    val carried = reports.count(!_._2.distributionIsSet)
    val tampered = reports.flatMap(_._2.tampers)
    val eras = reports.map(_._2.era).groupBy(identity).view.mapValues(_.size).toMap
    s"""$label: ${reports.size} states, ${bad.size} not ok
       |  eras: ${eras.toList.sortBy(_._1.ordinal).map((e, n) => s"$e=$n").mkString(", ")}
       |  era as expected: ${reports.count(_._2.eraAgrees)} of ${reports.size}
       |  residue empty: ${reports.count(_._2.residueEmpty)} of ${reports.size}
       |  retraction: ${reports.count(_._2.retraction)} of ${reports.size}
       |  nesPd != PoolDistr.of(set), carried in the extras: $carried
       |  pools / vrfKeyHashes of the last state: ${reports.lastOption
        .map(r => s"${r._2.pools} / ${r._2.vrfKeyHashes}")
        .getOrElse("-")}
       |  gaps seen: ${gaps.toList.sortBy(_._1.ordinal).map((g, n) => s"$g=$n").mkString(", ")}
       |  tampers applied: ${tampered.size}, refused as expected: ${tampered.count(_._2)}
       |  tampers by kind: ${tampered
        .groupBy(_._1)
        .toList
        .sortBy(_._1)
        .map((n, ts) => s"$n ${ts.count(_._2)}/${ts.size}")
        .mkString("; ")}
       |${bad
        .take(5)
        .map((n, r) =>
          s"  FAILED $n: ${r.error} era=${r.era} agrees=${r.eraAgrees} residue=${r.residueEmpty} retraction=${r.retraction} tampers=${r.tampers.filterNot(_._2)}"
        )
        .mkString("\n")}""".stripMargin
