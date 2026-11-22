// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.plutus.PlutusBackend
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
import xyz.kd5ujc.scarab.ledger.state.LeiosCommitteeError
import xyz.kd5ujc.scarab.ledger.state.{EpochNo, ShelleyBasedEra, VocabError}
import xyz.kd5ujc.scarab.node.read.MirrorEraRead
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.storage.UtxoStore
import xyz.kd5ujc.scarab.protocol.{DijkstraNotYet, Era, LeiosTxHash}
import xyz.kd5ujc.scarab.storage.LeiosStoreError
import xyz.kd5ujc.scarab.consensus.leios.TipCommittee
import xyz.kd5ujc.scarab.ledger.LeiosCertificationDefect
import xyz.kd5ujc.scarab.ledger.rules.contramap

/** Full block validation on the era-indexed state: the rules of [[NodeRules]] with the Haskell
  * ledger state in place of the node's flat one (design `ledger-mirror-step2.md` §4, J2.8).
  *
  * The chain's state is the mirror ([[MirrorState]]: the Haskell `ExtLedgerState` and the node's
  * bookkeeping). A block is applied as the hard-fork combinator applies it: the ledger state is
  * ticked to the block's slot (`Hfc.tick`: the translation into the era the chain has entered), the
  * header is validated against the ticked state (the envelope, the leader check against `nesPd`,
  * the operational certificate, the KES signature), the body is applied by the era's BBODY on the
  * ticked state, and the mirror is rebuilt with the tip, the state, the chain-dependent state and,
  * where the state is of a later era than the mirror's, its two telescopes extended into the era
  * ([[MirrorStep]]). Nothing is converted between the node's flat state and the mirror: the state
  * is the mirror from the first block to the last.
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
  * @param plutus
  *   the Plutus backend of the blocks' scripts ([[PlutusBackend]]): their evaluation and their
  *   admission, with no default
  *
  * @param utxoStore
  *   The backing store of the UTxO set. Without one the state's set holds all of it.
  * @param afterBlock
  *   Called with each block's header, the state it was applied to and the state it produces (the
  *   node uses it to tell the verification pipeline which epoch nonce is current, and to write
  *   state digests).
  * @param compute
  *   Measures the pure rules; without one they are not timed.
  * @param leios
  *   The endorser-block store a certifying Dijkstra block's closure is read from, and the Leios
  *   committee kept for the epoch ([[MirrorRules.Leios]]); without one no closure is held, and a
  *   certifying block that passes its checks stops the node, as one whose closure is missing does.
  */
final class MirrorRules[F[_]: Monad](
    config: PraosConfig,
    globals: LedgerGlobals,
    byron: Option[ByronRules.Config],
    handOver: Option[FromByron],
    byronParams: EraHistory.ByronParams,
    plutus: PlutusBackend,
    phase2Observer: Option[Phase2.Observation => Unit] = None,
    utxoStore: Option[UtxoStore[F]] = None,
    afterBlock: Option[
      (Header, MirrorState, MirrorState) => Raise[F, xyz.kd5ujc.scarab.ledger.StorageError] ?=> F[
        Unit
      ]
    ] = None,
    compute: Option[Compute[F]] = None,
    parallelPlutus: Option[ParallelPlutus[F]] = None,
    leios: Option[MirrorRules.Leios[F]] = None
) extends BlockRules[F, MirrorState]:

  private val cpu: Compute[F] = compute.getOrElse(Compute.inline[F])

  def apply(state: MirrorState, header: Header, raw: Bytes)(using
      Raise[F, ValidationError],
      Raise[F, xyz.kd5ujc.scarab.ledger.StorageError],
      Raise[F, xyz.kd5ujc.scarab.ledger.ChainError]
  ): F[MirrorState] =
    def run(
        s: MirrorState,
        prepared: Option[Prepared],
        dijkstra: MirrorDijkstra.Read,
        entered: MirrorRules.DijkstraCrossing => F[MirrorState] = c => c.after.pure[F]
    ): F[MirrorState] =
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
              plutus,
              phase2Observer,
              prepared,
              dijkstra
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
              plutus,
              phase2Observer,
              prepared,
              dijkstra
            )(workers.apply)
          )
      applied
        .flatMap {
          // the node's own defect: no verdict on the block, and no peer blamed for it
          case Left(stop) => stop.raise[F, MirrorState]
          case Right(Left(failure)) =>
            MirrorRules.stateOutOfRange(header, failure) match
              case Some(fault) => fault.raise[F, MirrorState]
              case None        => ValidationError(header.point, failure).raise[F, MirrorState]
          case Right(Right(accepted)) =>
            MirrorRules.DijkstraCrossing
              .of(s, accepted)
              .fold(accepted.pure[F])(entered)
              .flatTap(next => afterBlock.fold(().pure[F])(f => f(header, s, next)))
        }
    // The read set: what the block's transactions spend or reference, fetched before the pure
    // rules run (the rules then see a complete view of the entries they need).
    def backed(
        store: UtxoStore[F],
        inputs: Set[xyz.kd5ujc.scarab.ledger.TxIn],
        prepared: Option[Prepared],
        dijkstra: MirrorDijkstra.Read
    ): F[MirrorState] =
      val inByron = state.ledger(None).fold(_ => false, _.isEmpty)
      val wanted = state.utxo.missing(inputs)
      for
        // The block that enters Shelley replaces the genesis placeholder by the Byron UTxO, built
        // as a whole at stamp 0: a store that still holds the placeholder (written by a build
        // before the backing skipped Byron states) would treat those entries as stored. Start it
        // empty, so that every entry is written (see `UtxoBacking`).
        _ <- if inByron then store.reset else ().pure[F]
        fetched <-
          if wanted.isEmpty || inByron then
            Map.empty[xyz.kd5ujc.scarab.ledger.TxIn, xyz.kd5ujc.scarab.ledger.TxOut].pure[F]
          else store.read(wanted)
        // A block that enters Dijkstra upgrades the stored outputs its tick did not see, once the
        // rules have accepted it (`MirrorRules.DijkstraCrossing`), so a header or a body that
        // fails them never starts the pass over the store. The mempool, the forger and the local
        // state queries tick the state on their own: until the crossing block is applied, a state
        // they tick into Dijkstra reads the stored outputs with their Conway bytes.
        next <- run(
          MirrorStep.mapUtxo(state)(_.withResident(fetched)),
          prepared,
          dijkstra,
          MirrorRules.entered(store, _)
        )
        flushed <- store.flushed
      yield MirrorStep.mapUtxo(next)(_.settled(flushed))
    header match
      case h: Header.Shelley if MirrorRules.runsDijkstra(globals, h) =>
        dijkstraRead(state, h, raw).flatMap { (read, inputs) =>
          utxoStore.fold(run(state, None, read))(backed(_, inputs, None, read))
        }
      case h: Header.Shelley =>
        utxoStore.fold(run(state, None, MirrorDijkstra.Read.none)) { store =>
          val prepared = NodeRules.prepare(h, raw, config.verdicts)
          backed(
            store,
            prepared.fold(Set.empty[xyz.kd5ujc.scarab.ledger.TxIn])(_.inputs.toSet),
            prepared,
            MirrorDijkstra.Read.none
          )
        }
      case _ => run(state, None, MirrorDijkstra.Read.none)

  /** What a Dijkstra block's rules read beyond the state, read before they run
    * ([[MirrorDijkstra.Read]]), and the inputs whose stored outputs they read
    * ([[MirrorDijkstra.readSet]]): the block in the era's types; for a certifying block, its Leios
    * checks with the committee of the state, kept for its basis, and only once they hold the
    * closure the endorser-block store holds for the endorser block they verified, resolved once
    * (`verifyLeiosCert`, then `lookupEbClosure` and `readTables`, `Forker.hs:660-682`, ouroboros-
    * consensus 2416493b; [[MirrorDijkstra.certify]]). A block that does not decode reads nothing,
    * and its failure is carried to the rules, so the bytes are decoded once.
    */
  private def dijkstraRead(state: MirrorState, header: Header.Shelley, raw: Bytes)(using
      Raise[F, xyz.kd5ujc.scarab.ledger.StorageError]
  ): F[(MirrorDijkstra.Read, Set[xyz.kd5ujc.scarab.ledger.TxIn])] =
    xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraBlock.decode(raw) match
      // the bytes' failure carried to the rules, which give its verdict; nothing is read
      case Left(error) =>
        (MirrorDijkstra.Read(Some(Left(error)), None), Set.empty[xyz.kd5ujc.scarab.ledger.TxIn])
          .pure[F]
      case Right(block) =>
        val txs = xyz.kd5ujc.scarab.ledger.era.EraBlockBody.dijkstra.txSeqBlockBody(block.blockBody)
        val lookup: xyz.kd5ujc.scarab.protocol.EbHash => F[Option[Vector[(LeiosTxHash, Bytes)]]] =
          hash =>
            leios.fold(Option.empty[Vector[(LeiosTxHash, Bytes)]].pure[F]) { l =>
              given Raise[F, LeiosStoreError] =
                summon[Raise[F, xyz.kd5ujc.scarab.ledger.StorageError]]
                  .contramap(xyz.kd5ujc.scarab.ledger.StorageError.LeiosStore(_))
              l.store.lookupEbClosure(hash)
            }
        block.blockBody.leiosCert
          .traverse(cert =>
            MirrorDijkstra.certify[F](
              header.slot,
              state.protocol.dep,
              header.prevHash,
              ().pure[F].flatMap(_ => committeeOf(state)).map(_.getOrElse(Right(None))),
              cert.certificate,
              lookup
            )
          )
          .map(certification =>
            (
              MirrorDijkstra.Read(Some(Right(block)), certification),
              MirrorDijkstra.readSet(txs, certification)
            )
          )

  /** The committee of `state` 's ledger ([[LeiosCertification.leiosCommitteeOfTip]]), the one kept
    * for an equal basis where there is one, as a committee is kept for its epoch; none for a state
    * of Byron's, whose rules compute none.
    */
  private def committeeOf(state: MirrorState): F[
    Option[Either[LeiosCertificationDefect, Option[TipCommittee]]]
  ] =
    MirrorStep.nesOf(state).traverse { nes =>
      MirrorDijkstra.committeeBasis(nes) match
        case Left(defect) => Left(defect).pure[F]
        case Right(None)  => Right(None).pure[F]
        case Right(Some(key)) =>
          leios.fold(
            xyz.kd5ujc.scarab.consensus.leios.LeiosCertification.tipCommittee
              .tupled(key)
              .map(Some(_))
              .pure[F]
          ) { l =>
            l.committees.get.flatMap {
              case Some((kept, committee)) if kept == key => Right(Some(committee)).pure[F]
              case _ =>
                xyz.kd5ujc.scarab.consensus.leios.LeiosCertification
                  .tipCommittee(key._1, key._2)
                  .traverse(c => l.committees.set(Some((key, c))).as(Some(c)))
            }
          }
    }

object MirrorRules:
  /** What a Leios build's node reads a certifying block's closure from, and keeps its committee in:
    * the endorser-block store, and the last committee computed with its basis
    * ([[MirrorDijkstra.committeeBasis]]), used again for an equal basis.
    */
  final case class Leios[F[_]](
      store: xyz.kd5ujc.scarab.storage.LeiosStore[F],
      committees: cats.effect.Ref[F, Option[
        (
            (xyz.kd5ujc.scarab.ledger.state.LeiosCommittee, xyz.kd5ujc.scarab.codec.Rational),
            TipCommittee
        )
      ]]
  )

  /** A block that took the chain into Dijkstra from an earlier era and that the rules accepted: the
    * tick, the header checks against the ticked state and the body all passed. Only
    * [[DijkstraCrossing.of]] builds one, from the state before the block and the state the rules
    * produced, so a block refused anywhere (a header that claims Dijkstra but fails its checks
    * among them) has none.
    *
    * The state before may be of any earlier era: a tick can cross several boundaries at once
    * (`extendToSlot`, ouroboros-consensus 2416493b `HardFork/Combinator/State.hs:195-218`), and the
    * reference upgrades the whole table whenever the eras before and after a block differ
    * (`upgradeTables`, `HardFork/Combinator/Ledger.hs:1254-1287`), each output by the translations
    * of every boundary between its era and the new one, composed (`ejectHardForkTxOut`,
    * `Cardano/Ledger.hs:150-162`, through `txOutTranslations`, `HardFork/Combinator/Ledger.hs:
    * 1242-1248`, and `inPairsToTails` /`extendWithTails`, sop-extras 0.4.2.0
    * `src/Data/SOP/Tails.hs:104-140`). Before Dijkstra those translations keep a native script's
    * bytes (`translateTimelock`, cardano-ledger 9df9f98c `eras/allegra/impl/src/Cardano/Ledger/
    * Allegra/Scripts.hs:214-230`, the `upgradeScript` of Babbage and Conway) and the node's output
    * is unchanged by them, so the composition is Dijkstra's `upgradeTxOut` alone.
    *
    * @param after
    *   the state after the block, in Dijkstra
    * @param utxo
    *   its UTxO set
    */
  final case class DijkstraCrossing private (
      after: MirrorState,
      utxo: xyz.kd5ujc.scarab.ledger.Utxo
  )

  /** The state after `crossing` with the stored outputs its tick did not see upgraded
    * ([[UtxoStore.enteredDijkstra]]), as changes of the crossing block.
    */
  def entered[F[_]: cats.Functor](
      store: xyz.kd5ujc.scarab.storage.UtxoReadView[F],
      crossing: DijkstraCrossing
  )(using Raise[F, xyz.kd5ujc.scarab.ledger.StorageError]): F[MirrorState] =
    UtxoStore
      .enteredDijkstra(store, crossing.utxo)
      .map(u => MirrorStep.mapUtxo(crossing.after)(_ => u))

  object DijkstraCrossing:
    /** The crossing from `before` to `after`, the state the rules made of a block on it; none when
      * the block stays in an era or crosses only boundaries before Dijkstra.
      */
    private[node] def of(before: MirrorState, after: MirrorState): Option[DijkstraCrossing] =
      for
        a <- MirrorStep.nesOf(after)
        utxo <- MirrorStep.utxoOf(after).filter(_ => crosses(MirrorStep.nesOf(before), a))
      yield DijkstraCrossing(after, utxo)

    /** Whether `after` is a Dijkstra state and `before` is not: a state of an earlier Shelley-based
      * era, or none (Byron's).
      */
    def crosses(before: Option[EraNes], after: EraNes): Boolean =
      inDijkstra(after) && !before.exists(inDijkstra)

    private def inDijkstra(e: EraNes): Boolean = e match
      case i: EraNes.InEra[x] => i.era == ShelleyBasedEra.SDijkstra

  /** A quantity of the ledger state outside its vocabulary type, met by the tick or by a rule of a
    * transaction ([[BlockFailure.stateOutOfRange]]), the consensus state's per-epoch transition
    * counter past `Word32` ([[StateMirrorError.TransitionCounterOverflow]]), or a state with no
    * Leios committee for SNAP's rotation ([[xyz.kd5ujc.scarab.ledger.ChainError.LeiosCommittee]]):
    * no verdict on the block. The reference aborts with an exception there (`compactCoinOrError`;
    * the counter's `succ`, which throws at `maxBound`) or cannot hold such a state, so this is the
    * node's stop and never a refusal.
    */
  private[node] def stateOutOfRange(
      header: Header,
      failure: BlockFailure
  ): Option[xyz.kd5ujc.scarab.ledger.ChainError] =
    failure match
      // a failure of the BLS library is a provider defect (`ValidationError.unverifiable`)
      case BlockFailure.Tick(
            TickFailure.LeiosCommittee(LeiosCommitteeError.PossessionCheckFailed(_, _))
          ) =>
        None
      case BlockFailure.Tick(TickFailure.LeiosCommittee(cause)) =>
        Some(xyz.kd5ujc.scarab.ledger.ChainError.LeiosCommittee(header.slot, cause))
      case _ =>
        failure.stateOutOfRange
          .orElse(failure match
            case BlockFailure.Mirror(StateMirrorError.TransitionCounterOverflow) =>
              Some(VocabError.OutOfRange(VocabError.Kind.Word32, BigInt(1) << 32))
            case _ => None)
          .map(xyz.kd5ujc.scarab.ledger.ChainError.StateOutOfRange(header.slot, _))

  /** The label a block application is recorded under: a block that crossed an epoch boundary or ran
    * Plutus scripts says so, as those are the steps that take long.
    */
  private[node] def stepLabel(
      before: MirrorState
  )(result: MirrorDijkstra.Applied): String =
    result match
      case Left(_) | Right(Left(_)) => "rejected"
      case Right(Right(after)) =>
        if after.ledgerEpoch != before.ledgerEpoch ||
          after.consensus.follow.completed != before.consensus.follow.completed
        then "epochBoundary"
        else if after.plutus.scripts != before.plutus.scripts then "plutus"
        else "block"

  private def mirror(error: StateMirrorError): BlockFailure =
    BlockFailure.Mirror(error)

  /** The state of a Shelley-based block's chain before it: the new-epoch state of whichever era the
    * chain is in (the Byron hand-over's state for the first Shelley block), the chain-dependent
    * state and the node's bookkeeping.
    */
  private[node] final case class Pre(
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
      case NodeRules.Entry.Entering(first, ledger, _, globals) =>
        for
          context <- handOver.toRight(
            BlockFailure.Handover(xyz.kd5ujc.scarab.ledger.HandoverProblem.MissingShelleyContext)
          )
          epoch <- EpochNo.fromBigInt(first).leftMap(v => BlockFailure.Tick(TickFailure.Vocab(v)))
          nes <- Handover
            .layoutOf(globals.referenceBuild)
            .flatMap(Handover.toShelley(context, epoch, ledger.utxo, _))
            .leftMap(BlockFailure.Tick(_))
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
      plutus: PlutusBackend,
      phase2Observer: Option[Phase2.Observation => Unit] = None,
      prepared: Option[Prepared] = None,
      dijkstra: MirrorDijkstra.Read = MirrorDijkstra.Read.none
  ): MirrorDijkstra.Applied =
    applyBlockWith[cats.Id](
      state,
      header,
      raw,
      config,
      globals,
      byron,
      handOver,
      byronParams,
      plutus,
      phase2Observer,
      prepared,
      dijkstra
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
      plutus: PlutusBackend,
      phase2Observer: Option[Phase2.Observation => Unit],
      prepared: Option[Prepared],
      dijkstra: MirrorDijkstra.Read
  )(validate: EraBlock.Body => F[EraBlock.Outcome]): F[MirrorDijkstra.Applied] =
    header match
      // a Dijkstra block of the Leios build, on the era types, whose checkpoints Praos2's envelope
      // checks after BBODY, as `validateHeader` runs after the ledger (`applyHelper`,
      // `Ledger/Extended.hs:297-311`, ouroboros-consensus 2416493b); a release build's keeps the
      // refusal of its era's path (`BlockFailure.NotYet`)
      case h: Header.Shelley if runsDijkstra(globals, h) =>
        NodeRules
          .entry(state.consensus.byron, h, config, globals, byron)
          .flatMap(entry => before(state, entry, handOver).map(entry -> _))
          .fold(
            failure => Right(Left(failure)),
            (entry, pre) =>
              MirrorDijkstra(
                state,
                pre,
                h,
                raw,
                entry.configuration,
                entry.constants,
                byronParams,
                plutus,
                dijkstra
              )
          )
          .pure[F]
      case _ =>
        config.checkpoints.validate(header) match
          case Left(error) =>
            (Right(Left(BlockFailure.Envelope(error))): MirrorDijkstra.Applied).pure[F]
          case Right(_) =>
            header match
              case h: Header.Byron =>
                val chain = state.consensus
                NodeRules
                  .byronStep(chain.byron, chain.follow, h, raw, byron)
                  .flatMap((next, follow) =>
                    MirrorStep.byronBlock(state, h, next, follow).leftMap(mirror)
                  )
                  .asRight[xyz.kd5ujc.scarab.ledger.ChainError]
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
                    plutus,
                    phase2Observer,
                    prepared
                  )
                yield body
                ready
                  .traverse((body, finish) => validate(body).map(finish))
                  .map(_.flatten.asRight[xyz.kd5ujc.scarab.ledger.ChainError])

  /** Whether `header` 's block runs on Dijkstra's era rules: a Dijkstra block under the Leios
    * build, whose Dijkstra this node runs; under a release build it does not (#54).
    */
  private[node] def runsDijkstra(globals: LedgerGlobals, header: Header.Shelley): Boolean =
    header.era == Era.Dijkstra && (globals.referenceBuild match
      case xyz.kd5ujc.scarab.protocol.ReferenceBuild.LeiosB004fe0c => true
      case xyz.kd5ujc.scarab.protocol.ReferenceBuild.Release11_1 |
          xyz.kd5ujc.scarab.protocol.ReferenceBuild.Release11_2 =>
        false)

  private def shelley(
      state: MirrorState,
      pre: Pre,
      announced: Header.Shelley,
      raw: Bytes,
      config: PraosConfig,
      globals: LedgerGlobals,
      byronParams: EraHistory.ByronParams,
      backend: PlutusBackend,
      phase2Observer: Option[Phase2.Observation => Unit],
      prepared: Option[Prepared]
  ): Either[BlockFailure, (EraBlock.Body, EraBlock.Outcome => Either[BlockFailure, MirrorState])] =
    val geometry = config.geometry
    val epoch = geometry.epochOf(announced.slot)
    val context = EraBlock.Context(
      Globals(globals, geometry),
      config.forks.transition(geometry.k),
      MirrorStep.afterVotingOf(state),
      verdicts = config.verdicts,
      evaluator = backend.evaluator,
      observer = phase2Observer,
      decoder = backend.decoder,
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
        // a Dijkstra block of a release build decodes, and no ledger rule of that build's Dijkstra
        // applies it: the Leios build's runs on the era types (`MirrorDijkstra`)
        case _: Block.Dijkstra if announced.era == Era.Dijkstra =>
          Left(BlockFailure.NotYet(DijkstraNotYet.Rules))
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
        ticked.toEra,
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
          consensus = followed(pre, announced, epoch, leader)
          placement = Placement(geometry, byronParams, config.forks)
          next <-
            (if pre.entered then MirrorStep.handover(state, placement, block, nes, praos, consensus)
             else MirrorStep.block(state, placement, block, nes, praos, consensus, plutus))
              .leftMap(mirror)
        yield next
      (program, finish)

  /** The node's bookkeeping after `announced` 's block, whose leadership `leader` established: the
    * era, the epoch's statistics and the completed epoch's, and the Byron prefix finished.
    */
  private[node] def followed(
      pre: Pre,
      announced: Header.Shelley,
      epoch: BigInt,
      leader: xyz.kd5ujc.scarab.ledger.LeaderCheck
  ): ConsensusExtras =
    val follow = pre.consensus.follow
    val previous = follow.stats.filter(_.epoch == epoch)
    val finished = follow.stats.filter(_.epoch != epoch).orElse(follow.completed)
    pre.consensus.copy(
      follow = follow.copy(
        era = Some(announced.era),
        stats = Some(previous.getOrElse(EpochStats(epoch, announced.era, 0, 0, 0)).record(leader)),
        completed = finished
      ),
      byron = pre.consensus.byron.finish
    )

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
