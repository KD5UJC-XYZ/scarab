// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, ShelleyLedgerState}
import xyz.kd5ujc.scarab.ledger.state.*

/** What the fields Haskell derives (and the mirror derives with it) are in a real state, against
  * the rules the mirror derives them by (`LedgerMirror`: plan §8). Each field is a fact about one
  * decoded state, so that a real Preview state and a devnet state are held to the same questions.
  */
final case class Derivations(
    /** The size of `psVRFKeyHashes`. */
    vrfRecorded: Int,
    /** The distinct VRF key hashes of the registered and of the pending pools. */
    vrfDerived: Int,
    /** The key hashes the rule has and the state does not, and the other way round. */
    vrfMissing: Int,
    vrfExtra: Int,
    /** The key hashes whose count is not 1: the recorded count, the registered pools with the key
      * and the pending re-registrations with it.
      */
    vrfCountAnomalies: Vector[(Long, Int, Int)],
    /** `nesPd` equals `calculatePoolDistr` of the `set` snapshot, and of the others. */
    pdIsSet: Boolean,
    pdIsMark: Boolean,
    pdIsGo: Boolean,
    /** Per snapshot (mark, set, go): the pools, the pools whose `numDelegators` is the number of
      * stake entries delegated to them, above it, and below it, and the pools whose `numDelegators`
      * is the number of delegators the pool has now (`spsDelegators`).
      */
    numDelegators: Vector[Derivations.DelegatorCounts],
    /** The pools whose `spsDelegators` is the set of accounts delegating to them, and the others.
      */
    delegatorsMatching: Int,
    delegatorsDiffering: Int,
    /** The case of the future parameters. */
    future: String
)

object Derivations:
  /** How `numDelegators` of the pools of one snapshot compares with the stake entries. */
  final case class DelegatorCounts(
      pools: Int,
      equal: Int,
      above: Int,
      below: Int,
      maxAbove: Long,
      equalNow: Int
  )

  private def counts(s: SnapShot, now: PState): DelegatorCounts =
    val members =
      s.activeStake.entries.valuesIterator.toVector.groupMapReduce(_.delegation)(_ => 1L)(_ + _)
    val diffs =
      s.pools.toVector.map((id, p) => p.numDelegators.toBigInt.toLong - members.getOrElse(id, 0L))
    DelegatorCounts(
      s.pools.size,
      diffs.count(_ == 0),
      diffs.count(_ > 0),
      diffs.count(_ < 0),
      diffs.maxOption.getOrElse(0L),
      s.pools.count((id, p) =>
        now.stakePools.get(id).exists(_.delegators.size.toLong == p.numDelegators.toBigInt.toLong)
      )
    )

  /** The pool state and the pool each account delegates to, from the certificate state of the era.
    */
  private def poolsOf[E <: EraTag](cert: CertState[E])(using
      era: ShelleyBasedEra[E]
  ): (PState, Vector[(Credential[Staking], KeyHash[StakePool])]) = era match
    case ShelleyBasedEra.SConway =>
      (
        cert.pState,
        cert.dState.accounts.states.toVector.flatMap((c, a) => a.pool.map(c -> _))
      )
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      (
        cert.pState,
        cert.dState.accounts.states.toVector.flatMap((c, a) => a.pool.map(c -> _))
      )

  private def futureOf[E <: EraTag](g: GovState[E])(using era: ShelleyBasedEra[E]): String =
    era match
      case ShelleyBasedEra.SConway => g.futurePParams.productPrefix
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        g.futurePParams.productPrefix

  /** The derivations of the current era of a file, if it is a Shelley-based one. */
  def ofFile(file: HaskellLedgerFile): Option[Derivations] = file.ledger.state match
    case LedgerFileEra.Mirrored(EraLedgerState.InShelleyEra(era, state)) => Some(in(era, state))
    case _                                                               => None

  private def in[E <: EraTag](era: ShelleyBasedEra[E], state: ShelleyLedgerState[E]): Derivations =
    given ShelleyBasedEra[E] = era
    of(state.nes)

  def of[E <: EraTag](nes: NewEpochState[E])(using era: ShelleyBasedEra[E]): Derivations =
    val (pState, delegations) = poolsOf[E](nes.es.ls.cert)
    val recorded: Set[Bytes] = pState.vrfKeyHashes.keysIterator.map(_.bytes).toSet
    val derived: Set[Bytes] =
      (pState.stakePools.valuesIterator.map(_.vrf.bytes) ++
        pState.future.valuesIterator.map(_.vrfKeyHash)).toSet
    val byPool = delegations.groupMap(_._2)(_._1)
    val matching = pState.stakePools.count((id, sp) =>
      sp.delegators.toSet == byPool.getOrElse(id, Vector.empty).toSet
    )
    val snaps = nes.es.snapshots
    Derivations(
      recorded.size,
      derived.size,
      (derived -- recorded).size,
      (recorded -- derived).size,
      pState.vrfKeyHashes.toVector.collect {
        case (k, n) if n.value.toBigInt != 1 =>
          (
            n.value.toBigInt.toLong,
            pState.stakePools.valuesIterator.count(_.vrf.bytes == k.bytes),
            pState.future.valuesIterator.count(_.vrfKeyHash == k.bytes)
          )
      },
      nes.pd === PoolDistr.of(snaps.set),
      nes.pd === PoolDistr.of(snaps.mark),
      nes.pd === PoolDistr.of(snaps.go),
      Vector(snaps.mark, snaps.set, snaps.go).map(counts(_, pState)),
      matching,
      pState.stakePools.size - matching,
      futureOf[E](nes.es.ls.utxo.gov)
    )
