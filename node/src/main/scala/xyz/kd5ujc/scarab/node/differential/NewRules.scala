// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.Monad
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.consensus.state.{MirrorExtras, MirrorState, MirrorStep, Placement}
import xyz.kd5ujc.scarab.node.read.ChainRead
import xyz.kd5ujc.scarab.ledger.{Block, BlockFailure, Header, Prepared, TxIn}
import xyz.kd5ujc.scarab.ledger.byron.ByronOut
import xyz.kd5ujc.scarab.ledger.rules.{
  EraBlock,
  EraNes,
  FromByron,
  Globals,
  Handover,
  Hfc,
  TickFailure
}
import xyz.kd5ujc.scarab.ledger.state.{
  EpochNo,
  EraTag,
  NewEpochState,
  ShelleyBasedEra,
  SlotNo,
  UtxoTables
}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.node.LegacySeed
import xyz.kd5ujc.scarab.storage.UtxoStore

/** The two sides of the harness' state: the old node state, and the new side's, which is the mirror
  * (`MirrorState`: the Haskell `ExtLedgerState` and the node-only bookkeeping) until the
  * era-indexed rules make it the node's own (the plan's `NodeState(ext, extras)` of §2.1, which
  * does not exist yet).
  *
  * `neu` is the new side's state *after* the block `at` when it has one, and `None` when it has not
  * advanced with the old side: a side whose rules are not ported does not move, and is seeded again
  * from the old state when it is next needed (the harness does so at its sampling points,
  * [[Sampling]]). `at` is the block the old state is the state after, none before the first block
  * the harness saw.
  */
final case class Paired(old: NodeState, neu: Option[MirrorState], at: Option[BlockRef])

object Paired:
  /** The state of a node that starts from `old`, the new side not yet seeded. */
  def of(old: NodeState, at: Option[BlockRef] = None): Paired = Paired(old, None, at)

/** What the new side did with a block. */
enum NewOutcome:
  /** The block is valid and this is the state after it. */
  case Applied(next: MirrorState)

  /** The block is refused. */
  case Refused(failure: BlockFailure)

  /** The new side cannot say for this block (the state it was given is of an earlier era than the
    * block's, which the telescope of the mirror would have to be extended for): like a rule that is
    * not ported, the harness takes the old side's state.
    */
  case NotPorted

/** What the new side is asked of a block: the state before it, as the mirror, the block and its
  * header, and what the rules read of the network ([[EraBlock.Context]]: the constants and the era
  * history of the old side's tick of the block, its configuration's choice of era; none when the
  * harness has no old tick to take them from, where the rules that need them are not asked) and
  * where the telescopes sit on the era history (`placement`, for a block that enters an era).
  */
final case class BlockInput(
    from: MirrorState,
    header: Header,
    raw: Bytes,
    context: Option[EraBlock.Context],
    placement: Option[Placement] = None
)

/** The era-indexed rules, as the harness sees them (design §2.5): given the state before a block,
  * as the mirror, they say what became of the block. A rule that is not ported is not asked: the
  * harness takes the old side's state at its sampling points instead (`Divergence.NotPorted`), so
  * the plan's "reseed everything" start shows zero divergences by construction and measures what
  * the conversion costs.
  */
trait NewRules[F[_]]:
  /** The rules for blocks of `era`, none while they are not ported. */
  def forEra(era: Era): Option[NewRules.Apply[F]]

  /** The tick of a block of `era` (stage `Tick`, J2.3): the ledger state brought to the block's
    * slot, none while it is not ported. A port of a stage answers with the state of that stage, not
    * of the whole block, which is why it is not [[forEra]].
    */
  def tick(era: Era): Option[NewRules.Tick[F]] = None

object NewRules:
  /** The block applied to the state before it. */
  type Apply[F[_]] = BlockInput => F[NewOutcome]

  /** The state a tick starts from. */
  enum TickFrom:
    /** The new-epoch state of the era the chain is in, which may be an earlier one than the
      * block's: the combinator translates it.
      */
    case Shelley(state: EraNes)

    /** The Byron ledger, for the first Shelley-based block of a chain that begins in Byron: the
      * hand-over builds the Shelley state ([[Handover]]) with the context of the Shelley genesis.
      */
    case Byron(utxo: Map[TxIn, ByronOut], context: FromByron)

  /** What the tick of a block is asked: the state before it, the block's header, the constants of
    * the network once the first Shelley epoch is known, and the hard-fork configuration's choice of
    * era.
    */
  final case class TickInput(from: TickFrom, header: Header, globals: Globals, eraAt: Hfc.EraAt)

  /** What the new side did with the tick of a block. */
  enum TickOutcome:
    /** The state at the block's slot, in the era the block is of. */
    case Ticked(state: EraNes)

    /** The tick is refused. */
    case Refused(failure: TickFailure)

  type Tick[F[_]] = TickInput => F[TickOutcome]

  /** Nothing is ported. */
  def notPorted[F[_]]: NewRules[F] = new NewRules[F]:
    def forEra(era: Era): Option[Apply[F]] = None

  /** The rules as far as they are ported: the one place that grows with the ports (J2.3 on). The
    * node's `--rules differential` and the replay of a repro bundle both run these.
    *
    * Ported: the tick of every Shelley-based era (J2.3), and their blocks (J2.4: BBODY and the
    * rules under it for Conway; J2.5: the same for Shelley to Babbage, one instance over the
    * witness). `store` is the backing store of the UTxO set, where the node has one: the new side's
    * read set is fetched from it, as the old side's is.
    */
  def current[F[_]: Monad](store: Option[UtxoStore[F]] = None): NewRules[F] = new NewRules[F]:
    def forEra(era: Era): Option[Apply[F]] = era match
      case Era.Byron => None
      case _         => Some(shelleyBased(store))

    override def tick(era: Era): Option[Tick[F]] = era match
      case Era.Byron => None
      case _ =>
        Some { input =>
          val start: Either[TickFailure, EraNes] = input.from match
            case TickFrom.Shelley(state) => Right(state)
            case TickFrom.Byron(utxo, context) =>
              EpochNo
                .fromBigInt(input.globals.ledger.firstEpoch)
                .left
                .map(TickFailure.Vocab(_))
                .flatMap(Handover.toShelley(context, _, utxo))
                .map(EraNes.InEra(ShelleyBasedEra.SShelley, _))
          Monad[F].pure(
            (start, SlotNo.fromBigInt(input.header.slot).left.map(TickFailure.Vocab(_))).tupled
              .flatMap((from, slot) => Hfc.tick(input.globals, input.eraAt)(from, slot))
              .fold(TickOutcome.Refused(_), TickOutcome.Ticked(_))
          )
        }

  /** The new-epoch state of a mirror, with the witness of its era; none for a Byron state. */
  def nesOf(m: MirrorState): Option[EraNes] = MirrorStep.nesOf(m)

  /** The mirror of the old side's state as the new side carries it on:
    *
    *   - the reward update attributed: the old mirror's `rs` is empty and what each credential is
    *     paid is in the node's extras, so each gets its amount as one reward of a placeholder pool
    *     ([[Relations.seed]]; the new side's own update, made at `RUPD`, is attributed);
    *   - the snapshots as they were taken: the mirror it is made from reads the delegators of
    *     `mark` as the snapshot holds them (`SnapshotBasis.AsTaken`), not from the accounts as they
    *     are;
    *   - the pulser's parameters without the fields Conway's parameters have no place for
    *     ([[Relations.withoutDroppedFields]]): the old mirror keeps those in the pulser, and a
    *     state the new rules carry would adopt them as the parameters of the next epoch (the
    *     pulsers the new rules make themselves have none).
    */
  def carried(m: MirrorState): MirrorState = LegacySeed.carried(m)

  /** The block of a Shelley-based era on the new rules ([[EraBlock]]): decoded, its read set
    * fetched from the store, applied to the new-epoch state of the mirror, the changes to the UTxO
    * set settled as the old side settles its own. The first block of an era meets the state of the
    * one before: the combinator's tick translates it ([[EraBlock.ticked]]) and the mirror's
    * telescopes are extended into the era ([[MirrorStep.block]]), as the node's own rules do. Only
    * the first Shelley block, whose pre-state is Byron's, is the old side's to take.
    */
  private def shelleyBased[F[_]: Monad](store: Option[UtxoStore[F]]): Apply[F] = input =>
    (input.header, nesOf(input.from), input.from.extras, input.context, input.placement) match
      case (
            header: Header.Shelley,
            Some(pre: EraNes.InEra[e]),
            MirrorExtras.InShelley(ledger, consensus),
            Some(context),
            Some(placement)
          ) =>
        Block.decodeShelley(input.raw) match
          case Left(error) =>
            Monad[F].pure(NewOutcome.Refused(BlockFailure.Decode(error)))
          case Right(_: Block.Byron) =>
            Monad[F].pure(
              NewOutcome.Refused(BlockFailure.EraAnnounced(Era.Byron, header.era))
            )
          case Right(block: Block.Shelley) =>
            val txs = EraBlock.decode(block)
            val wanted = pre.nes.es.ls.utxo.utxo.utxo.missing(Prepared(block, txs).inputs)
            for
              fetched <- store match
                case Some(s) if wanted.nonEmpty => s.read(wanted)
                case _                          => Monad[F].pure(Map.empty)
              result = EraBlock(context)(
                EraNes.InEra(pre.era, withUtxo[e](pre.nes, _.withResident(fetched))(using pre.era)),
                header,
                block,
                txs,
                ledger.plutus
              )
              // read after the block, as the old side's rules do
              flushed <- store.traverse(_.flushed)
            yield result match
              case EraBlock.Outcome.Applied(applied, plutus) =>
                val settled = settle(applied, flushed)
                val protocol = ChainRead[MirrorState].protocol(input.from).in(header.era)
                MirrorStep
                  .block(input.from, placement, header, settled, protocol, consensus, plutus)
                  .fold(_ => NewOutcome.NotPorted, NewOutcome.Applied(_))
              case EraBlock.Outcome.Refused(failure) => NewOutcome.Refused(failure)
              case EraBlock.Outcome.NotInEra(era) =>
                NewOutcome.Refused(BlockFailure.LedgerEra(era, header.era))
      case _ => Monad[F].pure(NewOutcome.NotPorted)

  /** The state with its UTxO set settled as far as the store has flushed. */
  private def settle(nes: EraNes, flushed: Option[Long]): EraNes = nes match
    case n: EraNes.InEra[e] =>
      EraNes.InEra(
        n.era,
        withUtxo[e](n.nes, u => u.settled(flushed.getOrElse(u.flushed)))(using n.era)
      )

  /** The state with its UTxO set changed by `f`. */
  private def withUtxo[E <: EraTag](
      nes: NewEpochState[E],
      f: xyz.kd5ujc.scarab.ledger.Utxo => xyz.kd5ujc.scarab.ledger.Utxo
  )(using ShelleyBasedEra[E]): NewEpochState[E] =
    val ls = nes.es.ls
    nes.copy(es =
      nes.es.copy(ls = ls.copy[E](utxo = ls.utxo.copy[E](utxo = UtxoTables(f(ls.utxo.utxo.utxo)))))
    )
