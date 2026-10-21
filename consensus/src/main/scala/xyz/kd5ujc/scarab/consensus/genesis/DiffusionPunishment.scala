// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.genesis

import xyz.kd5ujc.scarab.consensus.Praos
import xyz.kd5ujc.scarab.ledger.{Header, TentativeHeaderError}
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash, VocabError, Word64}
import xyz.kd5ujc.scarab.ledger.state.{EraTag, ShelleyBasedEra}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}
import cats.syntax.all.*

/** Consensus82ecba329 InvalidBlockPunishment.hs and the Byron/Shelley/HFC diffusion-pipelining
  * instances. This state belongs to one BlockFetch client run, independently of connection reuse.
  */
object DiffusionPunishment:
  enum Invalidity:
    case BlockItself, BlockPrefix
  enum Outcome:
    case Forgive, Punish
  final case class HotIdentity(issuer: KeyHash[BlockIssuer], issueNo: Word64)

  sealed trait View:
    def era: Era
    def blockNo: Word64
  object View:
    final case class Byron(blockNo: Word64, isEBB: Boolean) extends View:
      def era: Era = Era.Byron
    final case class Shelley[E <: EraTag](
        witness: ShelleyBasedEra[E],
        blockNo: Word64,
        identity: HotIdentity
    ) extends View:
      def era: Era = protocolEra(witness)

  sealed trait State:
    def era: Era
  object State:
    final case class Byron(previous: Option[View.Byron]) extends State:
      def era: Era = Era.Byron
    final case class Shelley[E <: EraTag](
        witness: ShelleyBasedEra[E],
        lastBlockNo: Option[Word64],
        badIdentities: Set[HotIdentity]
    ) extends State:
      def era: Era = protocolEra(witness)

  final case class Decision(state: State, outcome: Outcome)
  val initial: State = State.Byron(None)

  private def protocolEra[E <: EraTag](era: ShelleyBasedEra[E]): Era = era match
    case ShelleyBasedEra.SShelley => Era.Shelley
    case ShelleyBasedEra.SAllegra => Era.Allegra
    case ShelleyBasedEra.SMary    => Era.Mary
    case ShelleyBasedEra.SAlonzo  => Era.Alonzo
    case ShelleyBasedEra.SBabbage => Era.Babbage
    case ShelleyBasedEra.SConway  => Era.Conway

  def view(header: Header): Either[TentativeHeaderError, View] =
    Word64
      .fromBigInt(VocabError.Kind.BlockNo, header.blockNo)
      .leftMap(TentativeHeaderError.Vocabulary(_))
      .flatMap { blockNo =>
        header match
          case h: Header.Byron => Right(View.Byron(blockNo, h.boundary))
          case h: Header.Shelley =>
            for
              _ <- Either.cond(
                h.issuerVkey.size == 32,
                (),
                TentativeHeaderError.IssuerKeyLength(h.issuerVkey.size)
              )
              issuer <- Praos.issuerKey(h.issuerVkey).leftMap(TentativeHeaderError.Vocabulary(_))
              issue <- Word64
                .fromBigInt(VocabError.Kind.OcertCounter, h.opCert.sequence)
                .leftMap(TentativeHeaderError.Vocabulary(_))
              identity = HotIdentity(issuer, issue)
              result <- h.era match
                case Era.Byron   => Left(TentativeHeaderError.ShelleyHeaderByron)
                case Era.Shelley => Right(View.Shelley(ShelleyBasedEra.SShelley, blockNo, identity))
                case Era.Allegra => Right(View.Shelley(ShelleyBasedEra.SAllegra, blockNo, identity))
                case Era.Mary    => Right(View.Shelley(ShelleyBasedEra.SMary, blockNo, identity))
                case Era.Alonzo  => Right(View.Shelley(ShelleyBasedEra.SAlonzo, blockNo, identity))
                case Era.Babbage => Right(View.Shelley(ShelleyBasedEra.SBabbage, blockNo, identity))
                case Era.Conway  => Right(View.Shelley(ShelleyBasedEra.SConway, blockNo, identity))
            yield result
      }

  /** ChainSel.hs1326–1341: a later invalid suffix does not implicate the adding block, and a
    * same-slot EBB already present as the last valid ledger tip is not an invalid prefix.
    */
  def classify(
      added: ChainSync.Point.Block,
      invalid: ChainSync.Point.Block,
      lastValid: ChainSync.Point
  ): Option[Invalidity] =
    Option.when(
      invalid.slot.value < added.slot.value ||
        (invalid.slot == added.slot && lastValid != added)
    )(if added == invalid then Invalidity.BlockItself else Invalidity.BlockPrefix)

  def judge(state: State, header: View, invalidity: Invalidity): Decision = invalidity match
    case Invalidity.BlockPrefix => Decision(state, Outcome.Punish)
    case Invalidity.BlockItself if header.era.index < state.era.index =>
      Decision(state, Outcome.Punish)
    case Invalidity.BlockItself =>
      header match
        case next: View.Byron =>
          val previous = state match
            case State.Byron(value)  => value
            case _: State.Shelley[?] => None
          val allowed = previous.forall { old =>
            val order = Word64.order.compare(old.blockNo, next.blockNo)
            order < 0 || (order == 0 && !old.isEBB && next.isEBB)
          }
          if allowed then Decision(State.Byron(Some(next)), Outcome.Forgive)
          else Decision(state, Outcome.Punish)
        case next: View.Shelley[?] =>
          val previous = state match
            case old: State.Shelley[?] if old.era == next.era => Some(old)
            case _                                            => None
          val order = previous.flatMap(_.lastBlockNo).fold(1)(Word64.order.compare(next.blockNo, _))
          val identities = previous.fold(Set.empty[HotIdentity])(_.badIdentities)
          if order < 0 || (order == 0 && identities(next.identity)) then
            Decision(state, Outcome.Punish)
          else
            Decision(
              State.Shelley(
                next.witness,
                Some(next.blockNo),
                (if order > 0 then Set.empty[HotIdentity] else identities) + next.identity
              ),
              Outcome.Forgive
            )
