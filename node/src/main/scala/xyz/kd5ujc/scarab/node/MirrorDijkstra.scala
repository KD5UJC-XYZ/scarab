// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Monad
import cats.data.{EitherT, NonEmptyChain}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.{DijkstraCrossing, Praos2, PraosConfig}
import xyz.kd5ujc.scarab.consensus.leios.{LeiosCertification, TipCommittee}
import xyz.kd5ujc.scarab.consensus.state.{
  ChainDepState,
  EraHistory,
  MirrorClock,
  MirrorExtras,
  MirrorState,
  MirrorStep,
  Placement,
  ProtocolState,
  StateMirror
}
import xyz.kd5ujc.scarab.crypto.bls.Certificate
import xyz.kd5ujc.scarab.ledger.{
  BlockFailure,
  BlockRule,
  ChainError,
  DijkstraBlockDefect,
  ForgeCertifyProblem,
  ForgeError,
  Header,
  LeaderCheck,
  LedgerGlobals,
  LeiosCertificationDefect,
  LeiosExtValidationError,
  LeiosHeader,
  NodeError,
  PlutusStats,
  PosixClock,
  Praos2Defect,
  Praos2Error,
  ProviderDefect,
  TxEnv,
  TxIn
}
import xyz.kd5ujc.scarab.ledger.era.{
  BothLevels,
  EraBlockBody,
  EraTx,
  PParams,
  Proposals,
  Tx,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.Block as EraBlockOf
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{
  DijkstraBlock,
  DijkstraBlockError,
  DijkstraBlockHeader
}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  BbodyEnv,
  BbodyPredFailure,
  DijkstraBlockRun,
  DijkstraClosure,
  DijkstraPhase2,
  DijkstraRuleDefect,
  DijkstraUtxoContext,
  GovDefect,
  LeiosClosureDefect,
  PoolDefect,
  ShelleyBbodyState,
  UtxoStateDefect
}
import xyz.kd5ujc.scarab.ledger.rules.{EraBlock, EraNes, Globals}
import xyz.kd5ujc.scarab.ledger.state.{
  Dijkstra,
  LeiosCommittee,
  NewEpochState,
  NodeExtras,
  ShelleyBasedEra,
  SlotNo,
  WithOrigin,
  Word64
}
import xyz.kd5ujc.scarab.plutus.PlutusBackend
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, Era, LeiosTxHash}

/** A Dijkstra block applied on the era types in the Leios build, as `Forker.applyBlock` applies one
  * (ouroboros-consensus 2416493b `Storage/LedgerDB/Forker.hs:624-687`). Every path of the node runs
  * it: block application, and the replay of the received log and of the immutable store, all
  * through [[MirrorRules]], which has no reapplication of its own; so a certifying block's closure
  * is applied before its tick wherever the reference applies or reapplies the block (`ReapplyVal`,
  * `:580-622`; `LedgerDB/V2/LedgerSeq.hs:279-310`), with every check.
  *
  * The order is the reference's:
  *   1. for a certifying ranking block, the Leios checks against the unticked state of its parent
  *      ([[certify]], `:631-666`), a refusal the block's ([[BlockFailure.LeiosCertificate]]);
  *   1. only then the certified endorser block's closure read from the store (`lookupEbClosure`),
  *      resolved once, and applied to that state, unticked, with its parameters and account
  *      (`resolveAndApplyLeiosClosure`, `:668-678`; [[DijkstraClosure]]): a missing or undecodable
  *      closure stops the node ([[DijkstraBlockDefect.Closure]]), as the reference's `error` does;
  *   1. the block ticked, BBODY run on it, then its header checked by Praos2 with the ledger view
  *      of the state ticked before BBODY (`tickThenApply`, `:680-685`; `applyHelper`,
  *      `Ledger/Extended.hs:297-311`), the state's cumulative transaction bytes gaining the block's
  *      and the closure's (`Shelley/Ledger/Leios.hs:223-230`).
  *
  * BBODY is a body program ([[DijkstraBlockRun.program]]) the caller runs serially or through the
  * node's bounded workers (ADR 0018), each to the same result ([[ready]]), the node's phase-2
  * observer (`--plutus-cases`), if any, told of its batches' scripts as the serial run tells it;
  * the closure's transactions run no script (`ValidateNone`) and are not observed. The steps before
  * it and after it run in the caller's order around it. A verdict on the block is a
  * [[BlockFailure]]; what the node has no verdict on is a [[ChainError]], never a verdict: the
  * follower withholds the block for a limit of this build valid peer input can meet
  * (`BlockNoVerdict.ofDijkstra`), and stops for the rest.
  *
  * The header gate ([[Praos2Headers]]) and this application share Praos2's configuration, the
  * chain-dependent state's translation and the tip ([[Praos2Headers.config]],
  * [[Praos2Headers.translated]], [[Praos2Headers.tipOf]]), and report differently where the
  * reference's two checks do: the gate validates a header against the ledger view forecast from its
  * anchor (`validateHeader` in the ChainSync client, `MiniProtocol/ChainSync/Client.hs:1934-1990`),
  * the envelope checks of every era first, while the application validates it against the view of
  * the block's own ticked state and only after BBODY (`applyHelper`), where Praos2's envelope
  * checks the checkpoints, the block number, slot and previous hash.
  */
private[node] object MirrorDijkstra:
  /** The application's steps: the node's stop, the block's refusal, or a value. */
  private[node] type Step[A] = EitherT[[x] =>> Either[ChainError, x], BlockFailure, A]

  /** What a block comes to: the node's stop, the block's refusal, or the state after it. */
  type Applied = Either[ChainError, Either[BlockFailure, MirrorState]]

  /** A certifying block's Leios checks held: the endorser block it certifies, and what the
    * endorser-block store holds for it, read only once the checks held (`lookupEbClosure` after
    * `verifyLeiosCert`, `Forker.hs:660-682`, ouroboros-consensus 2416493b) and resolved once
    * ([[DijkstraClosure.resolveLeiosClosure]]).
    */
  final case class Verified(
      point: EbPoint,
      closure: Either[LeiosClosureDefect, Vector[(LeiosTxHash, Tx[TxLevel.Top, Dijkstra])]]
  )

  /** The Leios checks of a certifying block ([[certify]]): the node's stop, the block's refusal, or
    * the closure of the endorser block they verified.
    */
  type Certification = Either[ChainError, Either[BlockFailure, Verified]]

  /** What [[MirrorRules]] read for a Dijkstra block before its rules run: the block decoded into
    * the era's types, or why its bytes are not one ([[DijkstraBlockError]]), and for a certifying
    * block its Leios checks with the closure they verified ([[certify]]). What is not read is
    * computed here: the block decoded, and the checks run with the committee of the parent's state
    * and no store, so a certificate that holds meets a missing closure.
    */
  final case class Read(
      block: Option[Either[DijkstraBlockError, EraBlockOf[DijkstraBlockHeader, Dijkstra]]],
      certification: Option[Certification]
  )

  object Read:
    val none: Read = Read(None, None)

  /** The basis of a state's Leios committee ([[LeiosCertification.committeeBasis]]), none before
    * Dijkstra: what [[MirrorRules]] keeps a committee by.
    */
  def committeeBasis(
      nes: EraNes
  ): Either[LeiosCertificationDefect, Option[(LeiosCommittee, Rational)]] = nes match
    case i: EraNes.InEra[e] => LeiosCertification.committeeBasis(i.nes)(using i.era)

  /** The inputs of transactions, at every level, whose stored outputs the rules read: the keys of
    * an endorser block's closure, each transaction's own (`leiosClosureTxKeySets`, which is
    * `getTransactionKeySets`, `Shelley/Ledger/Leios.hs:198`, ouroboros-consensus 2416493b).
    */
  def inputs(txs: Vector[Tx[TxLevel.Top, Dijkstra]]): Set[TxIn] =
    txs.foldLeft(Set.empty[TxIn])((keys, tx) =>
      keys ++ EraTx.dijkstra.bodyOf.allInputs(
        EraTx.dijkstra.body[TxLevel.Top](tx)(using BothLevels.Top)
      )
    )

  /** The keys of a ranking block's transactions (`getBlockKeySets`, `Shelley/Ledger/Ledger.hs:669`,
    * through `neededTxInsForBlock`, cardano-ledger 9df9f98c `Cardano/Ledger/Block.hs:104-110`):
    * their inputs at every level, less those that spend an output of a transaction of the same
    * block, which no store holds.
    */
  def blockInputs(txs: Vector[Tx[TxLevel.Top, Dijkstra]]): Set[TxIn] =
    val b = EraTx.dijkstra.bodyOf
    val ids = txs
      .map(tx =>
        b.id[TxLevel.Top](EraTx.dijkstra.body[TxLevel.Top](tx)(using BothLevels.Top))(using b.top)
      )
      .toSet
    inputs(txs).filterNot(in => ids.contains(in.id))

  /** What a Dijkstra block's rules read from the store: its own keys ([[blockInputs]]) and, once
    * its certificate is verified, its closure's ([[inputs]]), read with them as `readTables` reads
    * both after the checks (`Forker.hs:668-678`, ouroboros-consensus 2416493b).
    */
  def readSet(
      txs: Vector[Tx[TxLevel.Top, Dijkstra]],
      certification: Option[Certification]
  ): Set[TxIn] =
    blockInputs(txs) ++ (certification match
      case Some(Right(Right(Verified(_, Right(closure))))) => inputs(closure.map(_._2))
      case Some(Right(Right(Verified(_, Left(_))))) | Some(Right(Left(_))) | Some(Left(_)) | None =>
        Set.empty)

  private def halt[A](e: Either[ChainError, A]): Step[A] = EitherT.liftF(e)
  private def refuse[A](e: Either[BlockFailure, A]): Step[A] =
    EitherT.fromEither[[x] =>> Either[ChainError, x]](e)

  private def stop(slot: BigInt, defect: DijkstraBlockDefect): ChainError =
    ChainError.DijkstraBlock(slot, defect)

  /** A defect of the rules as the node meets it on the other eras' path: a quantity of the state
    * outside its type stops the node ([[ChainError.StateOutOfRange]]); a transaction's defect is a
    * [[BlockFailure.Tx]], which `MirrorRules` and the follower read as no verdict where it is one;
    * a BLS library's failure is a provider defect ([[BlockFailure.Unverifiable]]); a script the
    * evaluator cannot judge, named by its transaction, its level and its redeemer, is a
    * transaction's defect too (`LedgerFailure.Unavailable`), which the follower withholds the block
    * for as it does the other eras' (`BlockNoVerdict.Evaluation`, #85), and one the run could not
    * name is [[DijkstraBlockDefect.Unavailable]], withheld the same way; the rest stop the node
    * ([[DijkstraBlockDefect]]).
    */
  private[node] def ruleDefect(
      slot: BigInt,
      defect: DijkstraRuleDefect
  ): Either[ChainError, BlockFailure] =
    defect match
      case DijkstraRuleDefect.Unavailable(cause) =>
        Left(stop(slot, DijkstraBlockDefect.Unavailable(cause)))
      case DijkstraRuleDefect.UtxoState(UtxoStateDefect.Vocab(error)) =>
        Left(ChainError.StateOutOfRange(slot, error))
      case DijkstraRuleDefect.UtxoState(cause: UtxoStateDefect.StoredOutput) =>
        Left(stop(slot, DijkstraBlockDefect.UtxoState(cause)))
      case DijkstraRuleDefect.Ledger(cause) => Right(BlockFailure.Tx(cause))
      case DijkstraRuleDefect.Pool(PoolDefect.BlsKeyUnverifiable(_, error)) =>
        Right(BlockFailure.Unverifiable(BlockRule.Ledger, ProviderDefect.Bls(error)))
      case DijkstraRuleDefect.Gov(GovDefect.Vocab(error)) =>
        Left(ChainError.StateOutOfRange(slot, error))
      case DijkstraRuleDefect.Gov(cause: GovDefect.ProposalIndexOverflow) =>
        Left(stop(slot, DijkstraBlockDefect.Gov(cause)))

  /** A run of the era rules: a defect ([[ruleDefect]]), the refusal's failures, or its value. */
  private def judged[A](
      slot: BigInt,
      run: Either[DijkstraRuleDefect, Either[NonEmptyChain[BbodyPredFailure[Dijkstra]], A]]
  ): Step[A] =
    EitherT(run match
      case Left(defect)          => ruleDefect(slot, defect).map(Left(_))
      case Right(Left(failures)) => Right(Left(BlockFailure.DijkstraBbody(failures)))
      case Right(Right(value))   => Right(Right(value)))

  /** The checks' outcome as the node meets it: a refusal is the block's; the native library's
    * failure a provider defect; any other defect stops the node.
    */
  private def certification[A](
      slot: BigInt,
      outcome: Either[LeiosCertificationDefect, Either[LeiosExtValidationError, A]]
  ): Step[A] =
    EitherT(outcome match
      case Left(LeiosCertificationDefect.Native(cause)) =>
        Right(Left(BlockFailure.Unverifiable(BlockRule.Ledger, ProviderDefect.Bls(cause))))
      case Left(defect)         => Left(stop(slot, DijkstraBlockDefect.Certification(defect)))
      case Right(Left(invalid)) => Right(Left(BlockFailure.LeiosCertificate(invalid)))
      case Right(Right(value))  => Right(Right(value)))

  private[node] def inDijkstra(nes: EraNes): Option[NewEpochState[Dijkstra]] = nes match
    case i: EraNes.InEra[e] =>
      i.era match
        case ShelleyBasedEra.SDijkstra => Some(i.nes)
        case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
            ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway =>
          None

  /** The parameters in force in `nes`. */
  private def pparamsOf(nes: NewEpochState[Dijkstra]): PParams[Dijkstra] =
    nes.es.ls.utxo.gov.cgsCurPParams

  /** The proposals of `nes` 's governance state. */
  private def proposalsOf(nes: NewEpochState[Dijkstra]): Proposals[Dijkstra] =
    nes.es.ls.utxo.gov.cgsProposals

  /** `nes` with `proposals` as its governance state's. */
  private def withProposals(
      nes: NewEpochState[Dijkstra],
      proposals: Proposals[Dijkstra]
  ): NewEpochState[Dijkstra] =
    val ls = nes.es.ls
    nes.copy[Dijkstra](es =
      nes.es.copy[Dijkstra](ls =
        ls.copy[Dijkstra](utxo =
          ls.utxo.copy[Dijkstra](gov = ls.utxo.gov.copy(cgsProposals = proposals))
        )
      )
    )

  /** What every run of the era rules on the block reads beyond the state. */
  private[node] final case class Env(
      slot: BigInt,
      globals: Globals,
      context: DijkstraUtxoContext,
      clock: PosixClock,
      backend: PlutusBackend,
      config: PraosConfig
  )

  /** `resolveAndApplyLeiosClosure` (`Forker.hs:1066-1104`, ouroboros-consensus 2416493b) of the
    * endorser block the checks `verified` on `parent`, the unticked state before the certifying
    * block whose tip is at `tipSlot`: the closure as the store held it, resolved
    * ([[DijkstraClosure.resolveLeiosClosure]]), then applied with `mkMempoolEnv` at that slot with
    * `parent` 's parameters and account ([[DijkstraClosure.applyLeiosClosure]]); the state after it
    * and the closure's bytes.
    */
  private def closure(
      env: Env,
      verified: Verified,
      parent: NewEpochState[Dijkstra],
      tipSlot: Option[SlotNo]
  ): Step[(NewEpochState[Dijkstra], Word64)] =
    for
      resolved <- halt(
        verified.closure.leftMap(d => stop(env.slot, DijkstraBlockDefect.Closure(d)))
      )
      txs = resolved.map(_._2)
      pp = pparamsOf(parent)
      proposals = proposalsOf(parent)
      ledgerEnv = DijkstraClosure.mkMempoolEnv(
        DijkstraClosure.tipSlot(tipSlot),
        pp,
        parent.es.chainAccount
      )
      applied <- judged(
        env.slot,
        DijkstraBlockRun.closure(
          env.globals,
          ledgerEnv,
          txs,
          env.context,
          parent.es.ls,
          proposals,
          env.backend.evaluator,
          env.config.verdicts,
          env.backend.decoder,
          env.clock.posix
        )
      )
      (ls, after) = applied
      nes = withProposals(parent.copy[Dijkstra](es = parent.es.copy[Dijkstra](ls = ls)), after)
    yield (nes, DijkstraClosure.closureTxBytes(txs))

  /** `resolveAndApplyLeiosClosure` on the forging path (`partitionMempool`,
    * `NodeKernel/Forge.hs:826-841`, ouroboros-consensus 2416493b): the closure of the endorser
    * block at `point`, as the store held it and resolved (`resolved`), applied to `parent`, the
    * unticked state of the block forged in `env` 's slot, whose tip is at `tipSlot`, as block
    * application applies it ([[closure]]). Whatever keeps it from applying is the node's stop, the
    * reference's `error` (`:837-841`): a defect of the rules as block application meets it, and a
    * refusal there a [[ForgeCertifyProblem.ClosureRefused]].
    */
  private[node] def closedForForging(
      env: Env,
      point: EbPoint,
      resolved: Either[LeiosClosureDefect, Vector[(LeiosTxHash, Tx[TxLevel.Top, Dijkstra])]],
      parent: NewEpochState[Dijkstra],
      tipSlot: Option[SlotNo]
  ): Either[NodeError, NewEpochState[Dijkstra]] =
    closure(env, Verified(point, resolved), parent, tipSlot).value match
      case Left(halted) => Left(halted)
      case Right(Left(refused)) =>
        Left(ForgeError.Certify(ForgeCertifyProblem.ClosureRefused(point.hash, refused)))
      case Right(Right((nes, _))) => Right(nes)

  /** The Leios checks of a certifying block against the unticked state of its parent
    * ([[LeiosCertification.check]], `Forker.hs:631-666`, ouroboros-consensus 2416493b): `parent`
    * its chain-dependent state, `previousHash` the block's, `committee` the committee of its ledger
    * (none before Dijkstra, `leiosCommitteeOfTip`, `LeiosDemoTypes.hs:1494-1498`); and only once
    * they hold, the certified endorser block's closure from `lookup` (`lookupEbClosure`,
    * `:668-678`), resolved once. A refusal is the block's ([[BlockFailure.LeiosCertificate]]); the
    * native library's failure a provider defect; another defect stops the node. The committee is
    * asked for only where the checks reach it: an announcement and a parent to certify.
    */
  def certify[F[_]: Monad](
      slot: BigInt,
      parent: ChainDepState,
      previousHash: Option[Bytes],
      committee: F[Either[LeiosCertificationDefect, Option[TipCommittee]]],
      cert: Certificate,
      lookup: EbHash => F[Option[Vector[(LeiosTxHash, Bytes)]]]
  ): F[Certification] =
    val reached = LeiosCertification.announcement(parent).isDefined && previousHash.isDefined
    val asked: F[Either[LeiosCertificationDefect, Option[TipCommittee]]] =
      if reached then committee
      else Monad[F].pure(Right(None))
    asked.flatMap(c =>
      certification(
        slot,
        c.flatMap(LeiosCertification.check(parent, previousHash, _, cert))
      ).value match
        case Right(Right(ok)) =>
          lookup(ok.point.hash).map(stored =>
            Right(
              Right(Verified(ok.point, DijkstraClosure.resolveLeiosClosure(ok.point.hash, stored)))
            )
          )
        case Right(Left(refused)) => Monad[F].pure(Right(Left(refused)))
        case Left(halted)         => Monad[F].pure(Left(halted))
    )

  /** The checks of a certifying block and its closure ([[certify]]), or the parent's state
    * unchanged for a block without a certificate: the state the block is ticked from and the
    * closure's bytes. A verified closure is applied to the parent in Dijkstra; a parent before it
    * has no committee, so the checks refused the block before ([[LeiosExtValidationError]]).
    */
  private[node] def certified(
      env: Env,
      state: MirrorState,
      stamped: EraNes,
      certifying: Option[(Certificate, Certification)]
  ): Step[(EraNes, Word64)] =
    val none: Step[(EraNes, Word64)] = EitherT.pure((stamped, Word64.fromUnsigned(0L)))
    certifying.fold(none) { (c, certification) =>
      for
        verified <- EitherT(certification)
        applied <- inDijkstra(stamped) match
          case None =>
            refuse[(NewEpochState[Dijkstra], Word64)](
              Left(
                BlockFailure.LeiosCertificate(
                  LeiosExtValidationError.LeiosMissingCommittee(verified.point, c)
                )
              )
            )
          case Some(parent) =>
            closure(
              env,
              verified,
              parent,
              state.ext.headerState.tip match
                case WithOrigin.At(tip) => Some(tip.slot)
                case WithOrigin.Origin  => None
            )
        (nes, bytes) = applied
      yield (EraNes.InEra(ShelleyBasedEra.SDijkstra, nes), bytes)
    }

  /** What the hard-fork combinator's tick reads for a block on `state` (`EraBlock.Context`). */
  private[node] def tickContextOf(env: Env, state: MirrorState): EraBlock.Context =
    EraBlock.Context(
      env.globals,
      env.config.forks.transition(env.config.geometry.k),
      MirrorStep.afterVotingOf(state),
      verdicts = env.config.verdicts,
      evaluator = env.backend.evaluator,
      decoder = env.backend.decoder,
      clock = Some(env.clock)
    )

  /** The state BBODY runs on: the closure of a certifying block applied to its parent, unticked
    * ([[certified]], `Forker.hs:668-678`, ouroboros-consensus 2416493b), and only then the tick to
    * the block's slot (`tickThenApply`, `:680-685`), in Dijkstra; and the closure's bytes.
    */
  private[node] def closedThenTicked(
      env: Env,
      tickContext: EraBlock.Context,
      state: MirrorState,
      announced: Header.Shelley,
      stamped: EraNes,
      certifying: Option[(Certificate, Certification)]
  ): Step[(NewEpochState[Dijkstra], Word64)] =
    for
      closed <- certified(env, state, stamped, certifying)
      (unticked, closureBytes) = closed
      ticked <- refuse(
        EraBlock.tickedTo(tickContext)(unticked, announced, Era.Dijkstra, announced.hash)
      )
      tickedNes <- refuse(
        inDijkstra(ticked).toRight(BlockFailure.LedgerEra(ticked.toEra, announced.era))
      )
    yield (tickedNes, closureBytes)

  /** The header checks of Praos2 against the state ticked before BBODY, with the header gate's
    * setup (`Praos2Headers`; `validateHeader`, `HeaderValidation.hs:374-386, 518-526`,
    * ouroboros-consensus 2416493b): the parent's chain-dependent state, translated into Praos2's
    * where it is Praos's (`translateChainDepState`, `Protocol/Praos2.hs:189-209`), ticked with the
    * ticked state's own ledger view (`protocolLedgerView`, `currentForecast`,
    * `Shelley/Ledger/SupportsProtocol.hs:93-102`), then the header's envelope and protocol rules
    * ([[Praos2.validate]]).
    */
  private def headerChecks(
      env: Env,
      state: MirrorState,
      pre: MirrorRules.Pre,
      header: LeiosHeader,
      ticked: NewEpochState[Dijkstra],
      epoch: BigInt
  ): Step[ProtocolState] =
    val config = Praos2Headers.config(env.config)
    val translated = Praos2Headers.translated(pre.protocol)
    def vocab[A](e: Either[xyz.kd5ujc.scarab.ledger.state.VocabError, A]): Step[A] =
      halt(e.leftMap(v => stop(env.slot, DijkstraBlockDefect.Header(Praos2Defect.Vocabulary(v)))))
    for
      slot <- vocab(SlotNo.fromBigInt(env.slot))
      view <- halt(
        DijkstraCrossing
          .forecastInEra(env.globals, slot, WithOrigin.At(slot), ticked)
          .leftMap(e => stop(env.slot, DijkstraBlockDefect.View(e)))
      )
      chainDep <- refuse(
        Praos2Headers.chainDep(translated, Era.Dijkstra).leftMap(BlockFailure.Praos(_))
      )
      at <- vocab(Praos2Headers.tipOf(StateMirror.anchorOf(state.ext.headerState.tip)))
      tick <- halt(
        Praos2
          .tick(config, view, slot, Praos2.ChainState(at, chainDep))
          .leftMap(d => stop(env.slot, DijkstraBlockDefect.Header(d)))
      )
      next <- EitherT[[x] =>> Either[ChainError, x], BlockFailure, Praos2.ChainState](
        Praos2.validate(config, header, tick) match
          case Right(next)             => Right(Right(next))
          case Left(e: Praos2Error)    => Right(Left(BlockFailure.Praos2(e)))
          case Left(d: Praos2Defect)   => Left(stop(env.slot, DijkstraBlockDefect.Header(d)))
          case Left(p: ProviderDefect) => Right(Left(BlockFailure.Unverifiable(BlockRule.Praos, p)))
      )
    yield ProtocolState(ChainDepState.Praos2(next.protocol), translated.extras.copy(epoch = epoch))

  /** BBODY on the ticked state as a body program (`dijkstraBbodyTransition`,
    * [[DijkstraBlockRun.program]]), with its parameters, account and proposals, and the system
    * start and era history of the context, which the node runs serially or through its bounded
    * workers (ADR 0018), each to the same result. The network and the validation policy are the
    * run's own, from the globals.
    */
  private def bbody(
      env: Env,
      ticked: NewEpochState[Dijkstra],
      block: EraBlockOf[DijkstraBlockHeader, Dijkstra],
      observer: Option[DijkstraPhase2.Observation => Unit]
  ): DijkstraBlockRun.Body =
    DijkstraBlockRun.program(
      env.globals,
      BbodyEnv[Dijkstra](pparamsOf(ticked), ticked.es.chainAccount),
      block,
      env.context.systemStart,
      env.context.epochInfo,
      ShelleyBbodyState[Dijkstra](ticked.es.ls, ticked.bcur),
      proposalsOf(ticked),
      env.backend.evaluator,
      env.config.verdicts,
      env.backend.decoder,
      env.clock.posix,
      observer
    )

  /** What BBODY's result `result` on the ticked state makes of it: the state after it, and the
    * node's phase-2 counters of the block's scripts, from zero; or the refusal or defect.
    */
  private def bbodyAfter(
      env: Env,
      ticked: NewEpochState[Dijkstra],
      result: DijkstraBlockRun.Result
  ): Step[(NewEpochState[Dijkstra], PlutusStats)] =
    judged(env.slot, result).map(applied =>
      (
        withProposals(
          ticked.copy[Dijkstra](
            bcur = applied.state.blocksMade,
            es = ticked.es.copy[Dijkstra](ls = applied.state.ls)
          ),
          applied.proposals
        ),
        applied.plutus
      )
    )

  /** What the era rules read for `announced` 's block on `state`: the slot, the globals, the UTxO
    * rules' context, with every check, and the clock of the state's era history at its tip
    * ([[MirrorClock]]), the plutus backend and the configuration.
    */
  private[node] def envOf(
      state: MirrorState,
      announced: Header.Shelley,
      config: PraosConfig,
      globals: LedgerGlobals,
      byronParams: EraHistory.ByronParams,
      backend: PlutusBackend
  ): Env = envAt(state, announced.slot, announced.era, config, globals, byronParams, backend)

  /** What the era rules read for a block of `era` in `slot` on `state` ([[envOf]]). */
  private[node] def envAt(
      state: MirrorState,
      slot: BigInt,
      era: Era,
      config: PraosConfig,
      globals: LedgerGlobals,
      byronParams: EraHistory.ByronParams,
      backend: PlutusBackend
  ): Env =
    val geometry = config.geometry
    val placement = Placement(geometry, byronParams, config.forks)
    Env(
      slot,
      Globals(globals, geometry),
      DijkstraUtxoContext(
        globals.network,
        globals.systemStart,
        MirrorClock.horizon(state, placement, era, slot),
        TxEnv.Validation.All
      ),
      MirrorClock.of(globals.systemStart, state, placement, era, slot),
      backend,
      config
    )

  /** The state after `announced` 's block `raw` on `state`, whose Shelley-based part before the
    * block is `pre`, its BBODY run serially: see the object's description for the order.
    */
  def apply(
      state: MirrorState,
      pre: MirrorRules.Pre,
      announced: Header.Shelley,
      raw: Bytes,
      config: PraosConfig,
      globals: LedgerGlobals,
      byronParams: EraHistory.ByronParams,
      backend: PlutusBackend,
      read: Read,
      observer: Option[DijkstraPhase2.Observation => Unit] = None
  ): Applied =
    ready(state, pre, announced, raw, config, globals, byronParams, backend, read, observer) match
      case MirrorRules.Ready.Done(applied)   => applied
      case run: MirrorRules.Ready.Running[a] => run.finish(run.program.serial)

  /** [[apply]] up to its BBODY: what the block already came to before it (a refusal, or the node's
    * stop), or BBODY's program on the ticked state ([[DijkstraBlockRun.program]]), which the caller
    * runs serially or through the node's bounded workers (ADR 0018), and what its result makes of
    * the block: the state after it, then its header checked against the state ticked before BBODY,
    * and the state assembled, in the reference's order (`applyHelper`,
    * `Ledger/Extended.hs:297-311`, ouroboros-consensus d91b3557, main's pin, the same at 2416493b).
    */
  private[node] def ready(
      state: MirrorState,
      pre: MirrorRules.Pre,
      announced: Header.Shelley,
      raw: Bytes,
      config: PraosConfig,
      globals: LedgerGlobals,
      byronParams: EraHistory.ByronParams,
      backend: PlutusBackend,
      read: Read,
      observer: Option[DijkstraPhase2.Observation => Unit]
  ): MirrorRules.Ready =
    val slot = announced.slot
    val geometry = config.geometry
    val epoch = geometry.epochOf(slot)
    val placement = Placement(geometry, byronParams, config.forks)
    val env = envOf(state, announced, config, globals, byronParams, backend)
    val tickContext = tickContextOf(env, state)
    // a refusal of the bytes is the block's; the node's own readings that disagree are no verdict
    val decoded: Step[EraBlockOf[DijkstraBlockHeader, Dijkstra]] =
      EitherT[[x] =>> Either[ChainError, x], BlockFailure, EraBlockOf[
        DijkstraBlockHeader,
        Dijkstra
      ]](
        read.block.getOrElse(DijkstraBlock.decode(raw)) match
          case Right(block)                           => Right(Right(block))
          case Left(DijkstraBlockError.Decode(cause)) => Right(Left(BlockFailure.Decode(cause)))
          case Left(DijkstraBlockError.Era(cause)) => Right(Left(BlockFailure.DijkstraEra(cause)))
          case Left(DijkstraBlockError.Projection(cause)) =>
            Left(stop(slot, DijkstraBlockDefect.Projection(cause)))
      )
    val before: Step[(EraBlockOf[DijkstraBlockHeader, Dijkstra], NewEpochState[Dijkstra], Word64)] =
      for
        block <- decoded
        header = block.blockHeader.header
        body = block.blockBody
        _ <- refuse(Either.cond(header.hash == announced.hash, (), BlockFailure.HeaderDiffers))
        // what BlockFetch checks (`blockMatchesHeader`, `Shelley/Ledger/Block.hs:224-231`,
        // ouroboros-consensus 2416493b): the body hash; the declared size is BBODY's
        // (`validateBlockBodySize`, cardano-ledger 9df9f98c `Dijkstra/Rules/Bbody.hs:362`)
        _ <- refuse(
          Either.cond(body.hash.bytes == announced.bodyHash, (), BlockFailure.BodyCommitment)
        )
        // the changes the block makes to the UTxO set carry its number, its closure's among them
        stamped = EraNes.mapUtxo(pre.nes)(_.at(announced.blockNo.toLong))
        // the checks as read, or run here with no store
        certifying = body.leiosCert.map(_.certificate).map { c =>
          c -> read.certification.getOrElse(
            certify[cats.Id](
              slot,
              pre.protocol.dep,
              announced.prevHash,
              inDijkstra(pre.nes).fold(Right(None))(parent =>
                LeiosCertification.leiosCommitteeOfTip(parent)(using ShelleyBasedEra.SDijkstra)
              ),
              c,
              _ => None
            )
          )
        }
        closed <- closedThenTicked(env, tickContext, state, announced, stamped, certifying)
        (tickedNes, closureBytes) = closed
      yield (block, tickedNes, closureBytes)
    before.value match
      case Left(halted)         => MirrorRules.Ready.Done(Left(halted))
      case Right(Left(refused)) => MirrorRules.Ready.refused(refused)
      case Right(Right((block, tickedNes, closureBytes))) =>
        MirrorRules.Ready.Running[DijkstraBlockRun.Result](
          bbody(env, tickedNes, block, observer),
          result =>
            // `applyHelper` (`Ledger/Extended.hs:297-311`, ouroboros-consensus d91b3557): the
            // ledger's BBODY first, then `validateHeader` with the ledger view of the state ticked
            // before BBODY
            (for
              ran <- bbodyAfter(env, tickedNes, result)
              (nes, plutus) = ran
              praos <- headerChecks(env, state, pre, block.blockHeader.header, tickedNes, epoch)
              next <- refuse(
                assembled(
                  state,
                  pre,
                  announced,
                  placement,
                  closureBytes,
                  block.blockBody,
                  nes,
                  praos,
                  plutus
                )
              )
            yield next).value
        )

  /** The state after `announced` 's block, once its rules passed: `nes` and `praos` the ledger and
    * protocol states after it, the cumulative transaction bytes gaining the closure's
    * `closureBytes` and the block's own (`Shelley/Ledger/Leios.hs:223-230`, ouroboros-consensus
    * 2416493b), and the node's bookkeeping, its phase-2 counters gaining the block's `plutus`,
    * field by field, as a Conway block's do (#85).
    */
  private[node] def assembled(
      state: MirrorState,
      pre: MirrorRules.Pre,
      announced: Header.Shelley,
      placement: Placement,
      closureBytes: Word64,
      body: xyz.kd5ujc.scarab.ledger.era.BlockBody[Dijkstra],
      nes: NewEpochState[Dijkstra],
      praos: ProtocolState,
      plutus: PlutusStats
  ): Either[BlockFailure, MirrorState] =
    val epoch = placement.geometry.epochOf(announced.slot)
    // modulo 2^64, as `Word64` 's `+`
    val txBytes = Word64.fromUnsigned(
      closureBytes.bits +
        DijkstraClosure.closureTxBytes(EraBlockBody.dijkstra.txSeqBlockBody(body)).bits
    )
    val consensus = MirrorRules.followed(pre, announced, epoch, LeaderCheck.Threshold)
    MirrorStep
      .dijkstraBlock(
        if pre.entered then state.copy(extras = MirrorExtras.InShelley(NodeExtras.empty, consensus))
        else state,
        placement,
        announced,
        txBytes,
        EraNes.InEra(ShelleyBasedEra.SDijkstra, nes),
        praos,
        consensus,
        (if pre.entered then PlutusStats.zero else pre.plutus) |+| plutus
      )
      .leftMap(BlockFailure.Mirror(_))
end MirrorDijkstra
