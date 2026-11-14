// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.codec.Value
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.{
  Codec,
  ConstraintViolation,
  DecodeError,
  Decoded,
  Node,
  Writer,
  at
}
import xyz.kd5ujc.scarab.protocol.{DijkstraNotYet, Era, StateLayout}

import Wire.*

/** `NewEpochState` of the Leios layout: the six fields it writes, in their order. */
private[state] final case class NewEpochStateLeios[E <: EraTag](
    epoch: EpochNo,
    bprev: BlocksMade,
    bcur: BlocksMade,
    es: EpochState[E],
    ru: Option[PulsingRewUpdate],
    avvm: StashedAVVM[E]
)

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
      // `type ProtVerLow DijkstraEra = 12` (cardano-ledger 9df9f98c
      // `libs/cardano-ledger-core/internal/Cardano/Ledger/Internal/Definition/Era.hs:140`)
      case ShelleyBasedEra.SDijkstra => 12L
    ProtVer(Word64.fromUnsigned(major), Word64.zero)

  /** A codec of a Dijkstra value this build does not read or write yet (#54): its decoder refuses
    * with [[DijkstraNotYet.LedgerState]], and [[Codec.check]] refuses any value, so nothing is
    * written.
    */
  def notYet[A](what: String): Codec[A] = new Codec[A]:
    def decode(n: Node): Decoded[A] =
      Left(DecodeError.at(n, DecodeError.Kind.Domain(DijkstraNotYet.LedgerState)))
    // refused by `check`; never written
    def encode(a: A, out: Writer): Unit = ()
    override def check(a: A): Option[ConstraintViolation] =
      Some(ConstraintViolation(what, "a value of an era this build writes", "Dijkstra"))

  /** The witness of a Shelley-based era; none for Byron. */
  def shelleyBased(era: Era): Option[ShelleyBasedEra[?]] = era match
    case Era.Byron    => None
    case Era.Shelley  => Some(ShelleyBasedEra.SShelley)
    case Era.Allegra  => Some(ShelleyBasedEra.SAllegra)
    case Era.Mary     => Some(ShelleyBasedEra.SMary)
    case Era.Alonzo   => Some(ShelleyBasedEra.SAlonzo)
    case Era.Babbage  => Some(ShelleyBasedEra.SBabbage)
    case Era.Conway   => Some(ShelleyBasedEra.SConway)
    case Era.Dijkstra => Some(ShelleyBasedEra.SDijkstra)

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
    era.cert.instance(shelleyCertState, conwayCertState)

  /** The governance state of the era, the parameters those of the era. */
  def govState[E <: EraTag](using era: ShelleyBasedEra[E], v: ProtVer): Codec[GovState[E]] =
    era.gov.instance(shelleyGovState(Params.codec(era)), conwayGovState)

  def instantStake[E <: EraTag](using era: ShelleyBasedEra[E], v: ProtVer): Codec[InstantStake[E]] =
    era.instantStake.instance(shelleyInstantStake, conwayInstantStake)

  /** `StashedAVVMAddresses` (`Types.hs:357-359`): the UTxO in Shelley, `()` (a `null`) after. */
  def stashedAvvm[E <: EraTag](using era: ShelleyBasedEra[E]): Codec[StashedAVVM[E]] =
    era.stashedAvvm.instance(utxoTables, unit)

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

  /** `UTxOState` (`Types.hs:253-266`): the UTxO, deposits, fees, governance state, instant stake
    * and donations.
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

  /** The pool distribution of the 11.1 layout, `nesPd`, as its [[NewEpochState.Layout]]. */
  def storedPoolDistr(using ProtVer): Codec[NewEpochState.Layout] =
    new Codec[NewEpochState.Layout]:
      private val pd = poolDistr
      def decode(n: Node): Decoded[NewEpochState.Layout] =
        pd.decode(n).map(NewEpochState.Layout.Node11_1(_)).at("pd")
      def encode(a: NewEpochState.Layout, out: Writer): Unit = a match
        case NewEpochState.Layout.Node11_1(stored) => pd.encode(stored, out)
        case NewEpochState.Layout.Leios            => () // refused by `check`
      override def check(a: NewEpochState.Layout): Option[ConstraintViolation] = a match
        case NewEpochState.Layout.Node11_1(stored) => pd.check(stored).map(_.at("pd"))
        case NewEpochState.Layout.Leios            => ofThisLayout("NewEpochState", a)

  /** `NewEpochState` (`Types.hs:397-405`): `[epoch, blocks of the previous epoch, blocks of this
    * one, epoch state, reward update, pool distribution, stashed AVVM addresses]`, at the version
    * `v`. The Leios layout has no pool distribution: six fields (cardano-ledger 9df9f98c
    * `Shelley/LedgerState/Types.hs:393-422`). [[newEpochState]] is the given at the era's own.
    */
  def newEpochStateAt[E <: EraTag](using
      era: ShelleyBasedEra[E],
      v: ProtVer
  ): Codec[NewEpochState[E]] = stateLayout match
    case StateLayout.Node11_1 =>
      Record.of[NewEpochState[E]](
        (
          epochNo,
          blocksMade,
          blocksMade,
          epochState[E],
          strictMaybe(pulsingRewUpdate),
          storedPoolDistr,
          stashedAvvm[E]
        )
      )
    case StateLayout.Leios =>
      val record = Record.of[NewEpochStateLeios[E]](
        (
          epochNo,
          blocksMade,
          blocksMade,
          epochState[E],
          strictMaybe(pulsingRewUpdate),
          stashedAvvm[E]
        )
      )
      new Codec[NewEpochState[E]]:
        def decode(n: Node): Decoded[NewEpochState[E]] = record.decode(n).map { s =>
          NewEpochState(s.epoch, s.bprev, s.bcur, s.es, s.ru, NewEpochState.Layout.Leios, s.avvm)
        }
        private def view(s: NewEpochState[E]): NewEpochStateLeios[E] =
          NewEpochStateLeios(s.epoch, s.bprev, s.bcur, s.es, s.ru, s.avvm)
        def encode(a: NewEpochState[E], out: Writer): Unit = record.encode(view(a), out)
        override def check(a: NewEpochState[E]): Option[ConstraintViolation] =
          ofThisLayout("NewEpochState", a.layout).map(_.at("layout")).orElse(record.check(view(a)))

  /** The codec of the state of an era, at the version the reference writes it at. Dijkstra's state
    * is refused both ways ([[Versions.notYet]]): its parameters and its EPOCH's committee are not
    * Conway's in this layout.
    */
  given newEpochState[E <: EraTag](using era: ShelleyBasedEra[E]): Codec[NewEpochState[E]] =
    given ProtVer = Versions.eraLow(era)
    if era == ShelleyBasedEra.SDijkstra then Versions.notYet("NewEpochState")
    else newEpochStateAt[E]
