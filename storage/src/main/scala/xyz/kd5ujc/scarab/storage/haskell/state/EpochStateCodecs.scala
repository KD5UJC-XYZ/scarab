// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.codec.Value
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.{Codec, Decoded, Node, Writer}

import Wire.*

/** The encoding version an era's state is written at: the lowest protocol version of the era
  * (`eraProtVerLow`, which `toEraCBOR` uses, `Core/Era.hs:233`). A Conway state at protocol 11 is
  * still written at 9.
  */
object Versions:
  def eraLow[E <: EraTag](era: ShelleyBasedEra[E]): ProtVer =
    val major = era match
      case ShelleyBasedEra.SShelley => 2L
      case ShelleyBasedEra.SAllegra => 3L
      case ShelleyBasedEra.SMary    => 4L
      case ShelleyBasedEra.SAlonzo  => 5L
      case ShelleyBasedEra.SBabbage => 7L
      case ShelleyBasedEra.SConway  => 9L
    ProtVer(Word64.fromUnsigned(major), Word64.zero)

/** The era-dependent parts of the state and the records that hold them, up to `NewEpochState`
  * (`LedgerState/Types.hs`). The parts that depend on the era are chosen by the [[ShelleyBasedEra]]
  * witness, as the types are (`Eras.scala`): matching on it refines `E`, so the match types reduce
  * and each branch has the codec of its record.
  */
trait EpochStateCodecs extends RewardCodecs:
  /** The certificate state of the era: voting, pools and delegation in Conway, pools and delegation
    * before it.
    */
  def certState[E <: EraTag](using era: ShelleyBasedEra[E], v: ProtVer): Codec[CertState[E]] =
    era match
      case ShelleyBasedEra.SConway => conwayCertState
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        shelleyCertState

  /** The governance state of the era, the parameters those of the era. */
  def govState[E <: EraTag](using era: ShelleyBasedEra[E], v: ProtVer): Codec[GovState[E]] =
    era match
      case ShelleyBasedEra.SConway => conwayGovState
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        shelleyGovState(Params.codec(era))

  def instantStake[E <: EraTag](using era: ShelleyBasedEra[E], v: ProtVer): Codec[InstantStake[E]] =
    era match
      case ShelleyBasedEra.SConway => conwayInstantStake
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        shelleyInstantStake

  /** `StashedAVVMAddresses` (`Types.hs:357-359`): the UTxO in Shelley, `()` (a `null`) after. */
  def stashedAvvm[E <: EraTag](using era: ShelleyBasedEra[E]): Codec[StashedAVVM[E]] = era match
    case ShelleyBasedEra.SShelley => utxoTables
    case ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary | ShelleyBasedEra.SAlonzo |
        ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway =>
      unit

  private val unit: Codec[Unit] = new Codec[Unit]:
    def decode(n: Node): Decoded[Unit] = n.value match
      case Value.Null => Right(())
      case _          => Left(Codec.mismatch("null", n))
    def encode(a: Unit, out: Writer): Unit = out.nul()

  given chainAccountState: Codec[ChainAccountState] = Record.auto[ChainAccountState]

  /** `BlocksMade` (`BaseTypes.hs:910`, a newtype over the map): blocks per pool, a `Natural` each,
    * read as a `Word64`.
    */
  given blocksMade(using ProtVer): Codec[BlocksMade] =
    mapOf(keyHash[StakePool], word64Codec).imap(BlocksMade(_))(b => BlocksMade.blocks(b))

  /** `UTxOState` (`Types.hs:253-266`): the UTxO, deposits, fees, governance state, instant stake and
    * donations.
    */
  def utxoState[E <: EraTag](using era: ShelleyBasedEra[E], v: ProtVer): Codec[UTxOState[E]] =
    Record.of[UTxOState[E]](
      (utxoTables, coin, coin, govState[E], instantStake[E], coin)
    )

  /** `LedgerState` (`Types.hs:504-509`): `[certificate state, UTxO state]`, in that order. */
  def ledgerState[E <: EraTag](using era: ShelleyBasedEra[E], v: ProtVer): Codec[LedgerState[E]] =
    Record.of[LedgerState[E]]((certState[E], utxoState[E]))

  /** `EpochState` (`Types.hs:136-142`): `[chain account, ledger state, snapshots, non-myopic]`. */
  def epochState[E <: EraTag](using era: ShelleyBasedEra[E], v: ProtVer): Codec[EpochState[E]] =
    Record.of[EpochState[E]]((chainAccountState, ledgerState[E], snapShots, nonMyopic))

  /** `NewEpochState` (`Types.hs:397-405`): `[epoch, blocks of the previous epoch, blocks of this
    * one, epoch state, reward update, pool distribution, stashed AVVM addresses]`, at the version
    * `v`. [[newEpochState]] is the given at the era's own.
    */
  def newEpochStateAt[E <: EraTag](using
      era: ShelleyBasedEra[E],
      v: ProtVer
  ): Codec[NewEpochState[E]] =
    Record.of[NewEpochState[E]](
      (
        epochNo,
        blocksMade,
        blocksMade,
        epochState[E],
        strictMaybe(pulsingRewUpdate),
        poolDistr,
        stashedAvvm[E]
      )
    )

  /** The codec of the state of an era, at the version the reference writes it at. */
  given newEpochState[E <: EraTag](using era: ShelleyBasedEra[E]): Codec[NewEpochState[E]] =
    given ProtVer = Versions.eraLow(era)
    newEpochStateAt[E]
