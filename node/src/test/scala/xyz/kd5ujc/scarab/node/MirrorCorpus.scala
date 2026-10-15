// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.Kleisli
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.state.{
  EraTag,
  Gap,
  LedgerImage,
  LedgerMirror,
  LedgerState,
  MirrorError,
  MirrorEnv,
  MirrorLaws,
  ShelleyBasedEra,
  SlotNo,
  WithOrigin
}
import xyz.kd5ujc.scarab.protocol.Era

/** What the ledger-side mirror does to a state of the node, observed: the shared body of the corpus
  * checks (the eras fixture of the suite, the real Preview snapshots of the runner).
  */
object MirrorCorpus:
  type Result[A] = Either[MirrorError, A]
  type M[A] = Kleisli[Result, MirrorEnv, A]

  val mirror: LedgerMirror[M] = LedgerMirror[M]

  /** An era of the mirror, whichever it is (an existential pack of the witness). */
  enum SomeEra:
    case Of[E <: EraTag](era: ShelleyBasedEra[E])

  object SomeEra:
    def ofBlock(era: Era): Option[SomeEra] = era match
      case Era.Byron   => None
      case Era.Shelley => Some(Of(ShelleyBasedEra.SShelley))
      case Era.Allegra => Some(Of(ShelleyBasedEra.SAllegra))
      case Era.Mary    => Some(Of(ShelleyBasedEra.SMary))
      case Era.Alonzo  => Some(Of(ShelleyBasedEra.SAlonzo))
      case Era.Babbage => Some(Of(ShelleyBasedEra.SBabbage))
      case Era.Conway  => Some(Of(ShelleyBasedEra.SConway))

    /** The era whose rules a state with this protocol version is under. */
    def ofVersion(major: Int): SomeEra =
      if major >= 9 then Of(ShelleyBasedEra.SConway)
      else if major >= 7 then Of(ShelleyBasedEra.SBabbage)
      else if major >= 5 then Of(ShelleyBasedEra.SAlonzo)
      else if major == 4 then Of(ShelleyBasedEra.SMary)
      else if major == 3 then Of(ShelleyBasedEra.SAllegra)
      else Of(ShelleyBasedEra.SShelley)

  /** What is observed of one state. */
  final case class Report(
      error: Option[MirrorError],
      residueEmpty: Boolean,
      gaps: Set[Gap],
      distributionIsSet: Boolean,
      retraction: Boolean,
      tampers: Vector[(String, Boolean)],
      pools: Int = 0,
      vrfKeyHashes: Int = 0
  ):
    def ok: Boolean =
      error.isEmpty && residueEmpty && retraction && tampers.forall(_._2)

  /** The state as a comparison sees it: the UTxO is a handle over a RocksDB-backed set, compared by
    * what the mirror may not change (its totals and stake maps) and not entry by entry.
    */
  private def view(s: node.LedgerState) =
    (
      s.copy(utxo = node.Utxo.empty),
      s.utxo.count,
      s.utxo.coin,
      s.utxo.stake,
      s.utxo.ptrStake,
      s.utxo.stamp,
      s.utxo.flushed,
      s.utxo.diff.size
    )

  private def failed(e: MirrorError): Report =
    Report(Some(e), false, Set.empty, false, false, Vector.empty)

  def check(
      env: MirrorEnv,
      era: SomeEra,
      at: WithOrigin[SlotNo],
      s: node.LedgerState,
      withTampers: Boolean = true
  ): Report = era match
    case of: SomeEra.Of[e] => checkIn[e](env, at, s, withTampers)(using of.era)

  private def checkIn[E <: EraTag](
      env: MirrorEnv,
      at: WithOrigin[SlotNo],
      s: node.LedgerState,
      withTampers: Boolean
  )(using ShelleyBasedEra[E]): Report =
    mirror.to[E](at, s).run(env) match
      case Left(e) => failed(e)
      case Right(m) =>
        val image: LedgerImage[E] = m.value
        val back = mirror.from[E](at, image).run(env)
        val retraction = back.exists(b => view(b) == view(LedgerMirror.normalised(s)))
        val tampers = Option
          .when(withTampers)(MirrorLaws.tampers[E])
          .toList
          .flatten
          .flatMap { t =>
            t.apply(image).map { bad =>
              t.name -> (mirror.from[E](at, bad).run(env) == Left(
                MirrorError.DerivedMismatch(t.field)
              ))
            }
          }
          .toVector
        Report(
          back.left.toOption,
          image.extras.residue.isEmpty,
          m.gaps,
          image.extras.poolDistribution.isEmpty,
          retraction,
          tampers,
          LedgerState.pState[E].get(image.nes.es.ls).stakePools.size,
          LedgerState.pState[E].get(image.nes.es.ls).vrfKeyHashes.size
        )

  /** The era-independent summary of many reports, for the log. */
  def summary(label: String, reports: Seq[(String, Report)]): String =
    val bad = reports.filterNot(_._2.ok)
    val gaps = reports.flatMap(_._2.gaps).groupBy(identity).view.mapValues(_.size).toMap
    val carried = reports.count(!_._2.distributionIsSet)
    val tampered = reports.flatMap(_._2.tampers)
    s"""$label: ${reports.size} states, ${bad.size} not ok
       |  residue empty: ${reports.count(_._2.residueEmpty)} of ${reports.size}
       |  retraction: ${reports.count(_._2.retraction)} of ${reports.size}
       |  nesPd != PoolDistr.of(set), carried in the extras: $carried
       |  pools / vrfKeyHashes of the last state: ${reports.lastOption
        .map(r => s"${r._2.pools} / ${r._2.vrfKeyHashes}")
        .getOrElse("-")}
       |  gaps seen: ${gaps.toList.sortBy(_._1.ordinal).map((g, n) => s"$g=$n").mkString(", ")}
       |  tampers applied: ${tampered.size}, refused with the right field: ${tampered.count(_._2)}
       |${bad
        .take(5)
        .map((n, r) =>
          s"  FAILED $n: ${r.error} residue=${r.residueEmpty} retraction=${r.retraction} tampers=${r.tampers.filterNot(_._2)}"
        )
        .mkString("\n")}""".stripMargin
