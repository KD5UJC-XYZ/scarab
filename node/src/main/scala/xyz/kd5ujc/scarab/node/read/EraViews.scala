// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.state.*

/** The parts of the certificate and governance state that every era has, whichever record it is in
  * (`EraCertState`, `EraAccounts`, `EraGov` of the reference): one match on the witness each, in
  * whose branches the match types reduce.
  *
  * These are `EraLedger` 's accessors (`ledger/rules/`) over the certificate state alone: a reader
  * is given the parts of a ledger state one at a time ([[EraRead]]) and has no whole
  * `LedgerState[E]` to hand `EraLedger`. The functions are the same one match each; if `EraLedger`
  * takes the parts, these go.
  */
object EraViews:
  /** One stake account as every era's accounts see it. */
  final case class Account(
      balance: CompactCoin,
      deposit: CompactCoin,
      pool: Option[KeyHash[StakePool]],
      drep: Option[DRep]
  )

  def pState[E <: EraTag](c: CertState[E])(using era: ShelleyBasedEra[E]): PState = era match
    case ShelleyBasedEra.SConway => c.pState
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      c.pState

  def genDelegs[E <: EraTag](c: CertState[E])(using
      era: ShelleyBasedEra[E]
  ): SortedMap[KeyHash[Genesis], flat.GenDelegPair] = era match
    case ShelleyBasedEra.SConway => c.dState.genDelegs
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      c.dState.genDelegs

  def futureGenDelegs[E <: EraTag](c: CertState[E])(using
      era: ShelleyBasedEra[E]
  ): SortedMap[FutureGenDeleg, flat.GenDelegPair] = era match
    case ShelleyBasedEra.SConway => c.dState.futureGenDelegs
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      c.dState.futureGenDelegs

  def iRewards[E <: EraTag](c: CertState[E])(using era: ShelleyBasedEra[E]): InstantaneousRewards =
    era match
      case ShelleyBasedEra.SConway => c.dState.iRewards
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        c.dState.iRewards

  def accounts[E <: EraTag](c: CertState[E])(using
      era: ShelleyBasedEra[E]
  ): Iterator[(Credential[Staking], Account)] = era match
    case ShelleyBasedEra.SConway =>
      c.dState.accounts.states.iterator
        .map((k, a) => k -> Account(a.balance, a.deposit, a.pool, a.drep))
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      c.dState.accounts.states.iterator
        .map((k, a) => k -> Account(a.balance, a.deposit, a.pool, None))

  /** The voting state, from Conway. */
  def vState[E <: EraTag](c: CertState[E])(using era: ShelleyBasedEra[E]): Option[VState] =
    era match
      case ShelleyBasedEra.SConway => Some(c.vState)
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        None

  /** The governance state of Conway, from Conway. */
  def conwayGov[E <: EraTag](g: GovState[E])(using
      era: ShelleyBasedEra[E]
  ): Option[ConwayGovState] =
    era match
      case ShelleyBasedEra.SConway => Some(g)
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        None

  /** The DRep as the node writes it. */
  def drep(d: DRep): flat.DRep = d match
    case DRep.DRepKeyHash(h)         => flat.DRep.Registered(flat.Credential.Key(h.bytes))
    case DRep.DRepScriptHash(h)      => flat.DRep.Registered(flat.Credential.Script(h.bytes))
    case DRep.DRepAlwaysAbstain      => flat.DRep.AlwaysAbstain
    case DRep.DRepAlwaysNoConfidence => flat.DRep.AlwaysNoConfidence
