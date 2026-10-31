// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Eq, Show}
import cats.syntax.show.*
import monocle.{Focus, Lens}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.{Proposals, Utxo}

/** `UTxO era` as the mirror holds it: an opaque handle on the node's [[Utxo]] (a diff over a
  * RocksDB-backed set), never the map itself. Haskell's `state` file encodes the UTxO of the ledger
  * state as an empty `map(0)` and keeps the entries in the separate `tables` file, and the mirror's
  * codec does the same, so no step of the mirror reads through the handle.
  */
opaque type UtxoTables = Utxo

object UtxoTables:
  def apply(utxo: Utxo): UtxoTables = utxo

  extension (t: UtxoTables) def utxo: Utxo = t

  // Structural equality of the node's value: heavy on a full set, meant for the small ones of tests.
  given Eq[UtxoTables] = Eq.fromUniversalEquals
  given Show[UtxoTables] = Show.show(t => s"UtxoTables(count = ${t.count}, coin = ${t.coin})")

/** `UTxOState era` (`LedgerState/Types.hs:198-206`). */
final case class UTxOState[E <: EraTag](
    utxo: UtxoTables,
    deposited: Coin,
    fees: Coin,
    gov: GovState[E],
    instantStake: InstantStake[E],
    donation: Coin
)

object UTxOState:
  import EraInstances.given

  given [E <: EraTag](using ShelleyBasedEra[E]): Eq[UTxOState[E]] = Eq.instance((a, b) =>
    Eq[UtxoTables].eqv(a.utxo, b.utxo) &&
      Eq[Coin].eqv(a.deposited, b.deposited) &&
      Eq[Coin].eqv(a.fees, b.fees) &&
      Eq[GovState[E]].eqv(a.gov, b.gov) &&
      Eq[InstantStake[E]].eqv(a.instantStake, b.instantStake) &&
      Eq[Coin].eqv(a.donation, b.donation)
  )

  given [E <: EraTag](using ShelleyBasedEra[E]): Diff[UTxOState[E]] = Diff.record(
    Diff.at("utxo")(_.utxo),
    Diff.at("deposited")(_.deposited),
    Diff.at("fees")(_.fees),
    Diff.at("gov")(_.gov),
    Diff.at("instantStake")(_.instantStake),
    Diff.at("donation")(_.donation)
  )

  given [E <: EraTag](using ShelleyBasedEra[E]): Show[UTxOState[E]] = Show.show(u =>
    s"UTxOState(utxo = ${u.utxo.show}, deposited = ${u.deposited.show}, fees = ${u.fees.show}, " +
      s"gov = ${Show[GovState[E]].show(u.gov)}, " +
      s"instantStake = ${Show[InstantStake[E]].show(u.instantStake)}, donation = ${u.donation.show})"
  )

  def utxo[E <: EraTag]: Lens[UTxOState[E], UtxoTables] = Focus[UTxOState[E]](_.utxo)
  def deposited[E <: EraTag]: Lens[UTxOState[E], Coin] = Focus[UTxOState[E]](_.deposited)
  def fees[E <: EraTag]: Lens[UTxOState[E], Coin] = Focus[UTxOState[E]](_.fees)
  def gov[E <: EraTag]: Lens[UTxOState[E], GovState[E]] = Focus[UTxOState[E]](_.gov)
  def instantStake[E <: EraTag]: Lens[UTxOState[E], InstantStake[E]] =
    Focus[UTxOState[E]](_.instantStake)
  def donation[E <: EraTag]: Lens[UTxOState[E], Coin] = Focus[UTxOState[E]](_.donation)

/** `LedgerState era` (`LedgerState/Types.hs:447-452`). Haskell encodes the certificate state before
  * the UTxO state (`Types.hs:504-509`); the fields here follow the encoding.
  */
final case class LedgerState[E <: EraTag](cert: CertState[E], utxo: UTxOState[E])

object LedgerState:
  import EraInstances.given

  given [E <: EraTag](using ShelleyBasedEra[E]): Eq[LedgerState[E]] = Eq.instance((a, b) =>
    Eq[CertState[E]].eqv(a.cert, b.cert) && Eq[UTxOState[E]].eqv(a.utxo, b.utxo)
  )

  given [E <: EraTag](using ShelleyBasedEra[E]): Diff[LedgerState[E]] =
    Diff.record(Diff.at("cert")(_.cert), Diff.at("utxo")(_.utxo))

  given [E <: EraTag](using ShelleyBasedEra[E]): Show[LedgerState[E]] =
    Show.show(l => s"LedgerState(cert = ${Show[CertState[E]].show(l.cert)}, utxo = ${l.utxo.show})")

  def cert[E <: EraTag]: Lens[LedgerState[E], CertState[E]] = Focus[LedgerState[E]](_.cert)
  def utxo[E <: EraTag]: Lens[LedgerState[E], UTxOState[E]] = Focus[LedgerState[E]](_.utxo)

  /** The pools of the ledger state of any era (both certificate states hold a `PState`), by the
    * witness: the one path that does not depend on the era being known.
    */
  def pState[E <: EraTag](using era: ShelleyBasedEra[E]): Lens[LedgerState[E], PState] =
    Lens[LedgerState[E], PState](l => era.cert.on(l.cert)(_.pState, _.pState))(p =>
      l => l.copy[E](cert = era.cert.update(l.cert)(_.copy(pState = p), _.copy(pState = p)))
    )

/** Paths into a Conway ledger state, composed from the one lens of each record (`andThen`): a path
  * may not cross a match-type field in a single `Focus`, but each step of these does so at the
  * concrete era, where the family reduces.
  */
object ConwayLedgerState:
  val cert: Lens[LedgerState[Conway], ConwayCertState] = LedgerState.cert[Conway]
  val gov: Lens[LedgerState[Conway], ConwayGovState] =
    LedgerState.utxo[Conway].andThen(UTxOState.gov[Conway])
  val instantStake: Lens[LedgerState[Conway], ConwayInstantStake] =
    LedgerState.utxo[Conway].andThen(UTxOState.instantStake[Conway])

  val pState: Lens[LedgerState[Conway], PState] = cert.andThen(ConwayCertState.pState)
  val vState: Lens[LedgerState[Conway], VState] = cert.andThen(ConwayCertState.vState)
  val dState: Lens[LedgerState[Conway], DState[ConwayAccounts]] =
    cert.andThen(ConwayCertState.dState)

  val pools: Lens[LedgerState[Conway], SortedMap[KeyHash[StakePool], StakePoolState]] =
    pState.andThen(PState.stakePools)
  val dreps: Lens[LedgerState[Conway], SortedMap[Credential[DRepRole], DRepState]] =
    vState.andThen(VState.dreps)
  val accounts: Lens[LedgerState[Conway], ConwayAccounts] = dState.andThen(DState.accounts)
  val proposals: Lens[LedgerState[Conway], Proposals] =
    gov.andThen(ConwayGovState.proposals)
