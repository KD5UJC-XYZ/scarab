// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Monad
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{
  BlockRules,
  ByronRules,
  Compute,
  ConsensusExtras,
  EpochStats,
  PraosConfig
}
import xyz.kd5ujc.scarab.consensus.state.{
  EraHistory,
  MirrorClock,
  MirrorState,
  MirrorStep,
  Placement,
  ProtocolState,
  StateMirrorError
}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  BlockFailure,
  Header,
  LedgerGlobals,
  Phase2,
  PlutusStats,
  Prepared,
  ValidationError
}
import xyz.kd5ujc.scarab.ledger.rules.{EraBlock, EraNes, FromByron, Globals, Handover, TickFailure}
import xyz.kd5ujc.scarab.ledger.state.{EpochNo, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.read.MirrorEraRead
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.storage.UtxoStore

/** Full block validation on the era-indexed state: the rules of [[NodeRules]] with the Haskell
  * ledger state in place of the node's flat one (design `ledger-mirror-step2.md` §4, J2.8).
  *
  * The chain's state is the mirror ([[MirrorState]]: the Haskell `ExtLedgerState` and the node's
  * bookkeeping). A block is applied as the hard-fork combinator applies it: the ledger state is
  * ticked to the block's slot (`Hfc.tick`: the epoch boundaries on the way and the translation into
  * the era the chain has entered), the header is validated against the ticked state (the envelope,
  * the leader check against `nesPd`, the operational certificate, the KES signature), the body is
  * applied by the era's BBODY on the ticked state, and the mirror is rebuilt with the tip, the
  * state, the chain-dependent state and, where the state is of a later era than the mirror's, its
  * two telescopes extended into the era ([[MirrorStep]]). Nothing is converted between the node's
  * flat state and the mirror: the state is the mirror from the first block to the last.
  *
  * The chain's Byron prefix is the Byron rules' ([[NodeRules.byronStep]]) on the Byron state the
  * mirror carries; its hand-over to Shelley is the reference's translation (`Handover`,
  * `translateToShelleyLedgerStateFromUtxo`), built from the Shelley genesis ([[FromByron]]).
  *
  * @param handOver
  *   what the Shelley genesis gives the state a chain enters Shelley with; none for a chain that
  *   starts in Shelley
  * @param byronParams
  *   the Byron parameters of the era history, where the telescopes are placed
  *
  * @param utxoStore
  *   The backing store of the UTxO set. Without one the state's set holds all of it.
  * @param afterBlock
  *   Called with each block's header and the state it produces (the node uses it to tell the
  *   verification pipeline which epoch nonce is current, and to write state digests).
  * @param compute
  *   Measures the pure rules; without one they are not timed.
  */
final class MirrorRules[F[_]: Monad](
    config: PraosConfig,
    globals: LedgerGlobals,
    byron: Option[ByronRules.Config],
    handOver: Option[FromByron],
    byronParams: EraHistory.ByronParams,
    phase2Observer: Option[Phase2.Observation => Unit] = None,
    utxoStore: Option[UtxoStore[F]] = None,
    afterBlock: Option[
      (Header, MirrorState) => Raise[F, xyz.kd5ujc.scarab.ledger.StorageError] ?=> F[Unit]
    ] = None,
    compute: Option[Compute[F]] = None,
    parallelPlutus: Option[ParallelPlutus[F]] = None
) extends BlockRules[F, MirrorState]:

  private val cpu: Compute[F] = compute.getOrElse(Compute.inline[F])

  def apply(state: MirrorState, header: Header, raw: Bytes)(using
      Raise[F, ValidationError],
      Raise[F, xyz.kd5ujc.scarab.ledger.StorageError]
  ): F[MirrorState] =
    def run(s: MirrorState, prepared: Option[Prepared]): F[MirrorState] =
      val applied = parallelPlutus match
        case None =>
          cpu.timed(MirrorRules.stepLabel(s))(
            MirrorRules.applyBlock(
              s,
              header,
              raw,
              config,
              globals,
              byron,
              handOver,
              byronParams,
              phase2Observer,
              prepared
            )
          )
        case Some(workers) =>
          cpu.timedF(MirrorRules.stepLabel(s))(
            MirrorRules.applyBlockWith[F](
              s,
              header,
              raw,
              config,
              globals,
              byron,
              handOver,
              byronParams,
              phase2Observer,
              prepared
            )(workers.apply)
          )
      applied
        .flatMap(
          _.fold(
            failure => ValidationError(header.point, failure).raise[F, MirrorState],
            next => afterBlock.fold(next.pure[F])(f => f(header, next).as(next))
          )
        )
    (utxoStore, header) match
      case (Some(store), h: Header.Shelley) =>
        // The read set: what the block's transactions spend or reference, fetched before the pure
        // rules run (the rules then see a complete view of the entries they need).
        val prepared = NodeRules.prepare(h, raw, config.verdicts)
        val inByron = state.ledger(None).fold(_ => false, _.isEmpty)
        val wanted =
          prepared.fold(Set.empty[xyz.kd5ujc.scarab.ledger.TxIn])(p => state.utxo.missing(p.inputs))
        for
          // The block that enters Shelley replaces the genesis placeholder by the Byron UTxO, built
          // as a whole at stamp 0: a store that still holds the placeholder (written by a build
          // before the backing skipped Byron states) would treat those entries as stored. Start
          // it empty, so that every entry is written (see `UtxoBacking`).
          _ <- if inByron then store.reset else ().pure[F]
          fetched <-
            if wanted.isEmpty || inByron then
              Map.empty[xyz.kd5ujc.scarab.ledger.TxIn, xyz.kd5ujc.scarab.ledger.TxOut].pure[F]
            else store.read(wanted)
          next <- run(MirrorStep.mapUtxo(state)(_.withResident(fetched)), prepared)
          flushed <- store.flushed
        yield MirrorStep.mapUtxo(next)(_.settled(flushed))
      case _ => run(state, None)

object MirrorRules:
  /** The label a block application is recorded under: a block that crossed an epoch boundary or ran
    * Plutus scripts says so, as those are the steps that take long.
    */
  private[node] def stepLabel(
      before: MirrorState
  )(result: Either[BlockFailure, MirrorState]): String =
    result match
      case Left(_) => "rejected"
      case Right(after) =>
        if after.ledgerEpoch != before.ledgerEpoch ||
          after.consensus.boot.completed != before.consensus.boot.completed
        then "epochBoundary"
        else if after.plutus.scripts != before.plutus.scripts then "plutus"
        else "block"

  private def mirror(error: StateMirrorError): BlockFailure =
    BlockFailure.Mirror(error)

  /** The state of a Shelley-based block's chain before it: the new-epoch state of whichever era the
    * chain is in (the Byron hand-over's state for the first Shelley block), the chain-dependent
    * state and the node's bookkeeping.
    */
  private final case class Pre(
      nes: EraNes,
      protocol: ProtocolState,
      consensus: ConsensusExtras,
      plutus: PlutusStats,
      entered: Boolean
  )

  /** The state a block is applied to: the mirror's, or, for the first block of Shelley, the one the
    * reference's translation builds from the final Byron UTxO.
    */
  private def before(
      state: MirrorState,
      entry: NodeRules.Entry,
      handOver: Option[FromByron]
  ): Either[BlockFailure, Pre] =
    entry match
      case NodeRules.Entry.Entering(first, ledger, _, _) =>
        for
          context <- handOver.toRight(
            BlockFailure.Handover(xyz.kd5ujc.scarab.ledger.HandoverProblem.MissingShelleyContext)
          )
          epoch <- EpochNo.fromBigInt(first).leftMap(v => BlockFailure.Tick(TickFailure.Vocab(v)))
          nes <- Handover.toShelley(context, epoch, ledger.utxo).leftMap(BlockFailure.Tick(_))
          extras = state.consensus
        yield Pre(
          EraNes.InEra(ShelleyBasedEra.SShelley, nes),
          state.protocol,
          extras.copy(byron =
            extras.byron.finish.copy(ledger = None, firstShelleyEpoch = Some(first))
          ),
          PlutusStats.zero,
          entered = true
        )
      case NodeRules.Entry.Within(_, _) =>
        MirrorStep
          .nesOf(state)
          .toRight(
            BlockFailure.Handover(xyz.kd5ujc.scarab.ledger.HandoverProblem.MissingByronRules)
          )
          .map(nes => Pre(nes, state.protocol, state.consensus, state.plutus, false))

  /** The state after `header` and its block, or why it is refused: a Byron block by the Byron
    * rules, a Shelley-based one by the hard-fork combinator's tick, the header checks and BBODY of
    * its era.
    */
  def applyBlock(
      state: MirrorState,
      header: Header,
      raw: Bytes,
      config: PraosConfig,
      globals: LedgerGlobals,
      byron: Option[ByronRules.Config],
      handOver: Option[FromByron],
      byronParams: EraHistory.ByronParams,
      phase2Observer: Option[Phase2.Observation => Unit] = None,
      prepared: Option[Prepared] = None
  ): Either[BlockFailure, MirrorState] =
    applyBlockWith[cats.Id](
      state,
      header,
      raw,
      config,
      globals,
      byron,
      handOver,
      byronParams,
      phase2Observer,
      prepared
    )(_.serial)

  private[node] def applyBlockWith[F[_]: Monad](
      state: MirrorState,
      header: Header,
      raw: Bytes,
      config: PraosConfig,
      globals: LedgerGlobals,
      byron: Option[ByronRules.Config],
      handOver: Option[FromByron],
      byronParams: EraHistory.ByronParams,
      phase2Observer: Option[Phase2.Observation => Unit],
      prepared: Option[Prepared]
  )(validate: EraBlock.Body => F[EraBlock.Outcome]): F[Either[BlockFailure, MirrorState]] =
    config.checkpoints.validate(header) match
      case Left(error) =>
        (Left(BlockFailure.Envelope(error)): Either[BlockFailure, MirrorState]).pure[F]
      case Right(_) =>
        header match
          case h: Header.Byron =>
            val chain = state.consensus
            NodeRules
              .byronStep(chain.byron, chain.boot, h, raw, byron)
              .flatMap((next, boot) => MirrorStep.byronBlock(state, h, next, boot).leftMap(mirror))
              .pure[F]
          case h: Header.Shelley =>
            val ready = for
              entry <- NodeRules.entry(state.consensus.byron, h, config, globals, byron)
              pre <- before(state, entry, handOver)
              body <- shelley(
                state,
                pre,
                h,
                raw,
                entry.configuration,
                entry.constants,
                byronParams,
                phase2Observer,
                prepared
              )
            yield body
            ready.traverse { (body, finish) => validate(body).map(finish) }.map(_.flatten)

  private def shelley(
      state: MirrorState,
      pre: Pre,
      announced: Header.Shelley,
      raw: Bytes,
      config: PraosConfig,
      globals: LedgerGlobals,
      byronParams: EraHistory.ByronParams,
      phase2Observer: Option[Phase2.Observation => Unit],
      prepared: Option[Prepared]
  ): Either[BlockFailure, (EraBlock.Body, EraBlock.Outcome => Either[BlockFailure, MirrorState])] =
    val geometry = config.geometry
    val epoch = geometry.epochOf(announced.slot)
    val context = EraBlock.Context(
      Globals(globals, geometry),
      config.forks.eraAt,
      verdicts = config.verdicts,
      observer = phase2Observer,
      clock = Some(
        MirrorClock.of(
          state,
          Placement(geometry, byronParams, config.forks),
          announced.era,
          announced.slot
        )
      )
    )
    for
      decoded <- prepared.fold(Block.decodeShelley(raw).leftMap(BlockFailure.Decode(_)))(p =>
        Right(p.block)
      )
      block <- decoded match
        case b: Block.Shelley if b.era == announced.era => Right(b)
        case b => Left(BlockFailure.EraAnnounced(b.era, announced.era))
      _ <- Either.cond(
        block.header.hash == announced.hash,
        (),
        BlockFailure.HeaderDiffers
      )
      _ <- Either.cond(
        block.bodySize == announced.bodySize && block.bodyHash == announced.bodyHash,
        (),
        BlockFailure.BodyCommitment
      )
      // Stamped first: the tick can change the set (the redeem outputs leave it on entering
      // Allegra), and a change stamped with the previous block may count as already stored.
      stamped = EraNes.mapUtxo(pre.nes)(_.at(announced.blockNo.toLong))
      ticked <- EraBlock.ticked(context)(stamped, announced, block)
      view = ledgerView(ticked, announced.slot)
      validated <- NodeRules.headerChecks(
        pre.protocol,
        announced,
        block.bodySize,
        config,
        globals,
        view.params,
        view.genDelegs,
        view.pools
      )
      (praos, leader) = validated
      txs = prepared.fold(EraBlock.decode(block))(_.txs)
    yield
      val program = EraBlock.program(context)(ticked, announced, block, txs, pre.plutus)
      val finish = (outcome: EraBlock.Outcome) =>
        for
          applied <- outcome match
            case EraBlock.Outcome.Applied(nes, plutus) => Right((nes, plutus))
            case EraBlock.Outcome.Refused(failure)     => Left(failure)
            case EraBlock.Outcome.NotInEra(era) => Left(BlockFailure.LedgerEra(era, announced.era))
          (nes, plutus) = applied
          boot = pre.consensus.boot
          previous = boot.stats.filter(_.epoch == epoch)
          finished = boot.stats.filter(_.epoch != epoch).orElse(boot.completed)
          consensus = pre.consensus.copy(
            boot = boot.copy(
              era = Some(announced.era),
              stats = Some(
                previous.getOrElse(EpochStats(epoch, announced.era, 0, 0, 0)).record(leader)
              ),
              completed = finished
            ),
            byron = pre.consensus.byron.finish
          )
          placement = Placement(geometry, byronParams, config.forks)
          next <-
            (if pre.entered then
               MirrorStep.handover(state, placement, announced, nes, praos, consensus)
             else MirrorStep.block(state, placement, announced, nes, praos, consensus, plutus))
              .leftMap(mirror)
        yield next
      (program, finish)

  /** What the header rules read of the ticked ledger: the parameters in force, the genesis
    * delegations at the slot and the pool distribution of the leader check (`nesPd`).
    */
  private final case class View(
      params: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      genDelegs: Map[Bytes, xyz.kd5ujc.scarab.ledger.GenDelegPair],
      pools: xyz.kd5ujc.scarab.ledger.PoolDistribution
  )

  private def ledgerView(ticked: EraNes, slot: BigInt): View = ticked match
    case i: EraNes.InEra[e] =>
      val ledger = new MirrorEraRead[e](i.nes)(using i.era)
      View(ledger.params, ledger.genDelegsAt(slot), ledger.leaderPools(nextEpoch = false))
