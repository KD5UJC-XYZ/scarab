// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{GovActionId, GovActionState as StoredGovActionState, Proposal}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraProposals
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Sts, TickFailure}
import xyz.kd5ujc.scarab.ledger.state.{
  Dijkstra,
  EpochState,
  EraTag,
  GovState as StoredGovState,
  LedgerState,
  UTxOState
}

/** Why the node's record of the governance state does not hold the era's [[ConwayGovState]] the
  * tick works on: the record does not read into it, or the state the tick leaves cannot be written
  * back. Neither is a verdict on a block: the reference holds the era's record itself
  * (`Dijkstra/Governance.hs:29`, cardano-ledger 9df9f98c), so neither arises there.
  */
enum StoredGovError:
  /** The record does not read into the era's ([[GovStateReadError]]): only a damaged record gives
    * it, as the reader's own causes say.
    */
  case Read(cause: GovStateReadError)

  /** The era's state does not write back into the record ([[ProposalsWriteError]]). The tick adds
    * no action, so every action it writes back is one it read from the record, which holds no
    * update of keys 34 to 56 (the node's codec refuses one): unreachable while the record is the
    * node's (#85).
    */
  case Write(cause: ProposalsWriteError)

/** How the tick of era `E` holds the era's governance state ([[ConwayGovState]]) in the node's
  * ledger state, whose record is still the flat one ([[StoredGovState]]) until the format change
  * that stores the era's record (#85, FMT-1). The one place Dijkstra's tick reads or writes the
  * governance record: before the tick the record is read into the era's state, after it the era's
  * state is written back over the positions of the record it was read from
  * ([[GovStateRecord.toStored]], [[GovStateBases.of]]), which is lossless, so the tick works on the
  * era's state alone. Once the ledger state holds the era's record, reading and writing it are the
  * identity and [[synced]] is too.
  */
sealed trait StoredGov[E <: EraTag]:
  /** The node's record `record` as the era's governance state. */
  def read(record: StoredGovState[E]): Either[StoredGovError, ConwayGovState[E]]

  /** The era's governance state `gov` written back over `record`, the record it was read from as
    * `read`.
    */
  def write(
      gov: ConwayGovState[E],
      read: ConwayGovState[E],
      record: StoredGovState[E]
  ): Either[StoredGovError, StoredGovState[E]]

  /** `es` with the current and previous parameters of `gov` written over its record's own: what the
    * node's rules that read the record during the tick, the reward update and RUPD
    * (`Rewards.start`), read of the governance state.
    */
  def synced(es: EpochState[E], gov: ConwayGovState[E]): EpochState[E]

object StoredGov:
  /** Dijkstra's, through its reader of the record ([[GovStateRecord.dijkstra]]). */
  given dijkstra: StoredGov[Dijkstra] with
    private val r = GovStateRecord.dijkstra

    def read(record: StoredGovState[Dijkstra]): Either[StoredGovError, ConwayGovState[Dijkstra]] =
      GovStateRecord.fromStored[Dijkstra](record).leftMap(StoredGovError.Read(_))

    def write(
        gov: ConwayGovState[Dijkstra],
        read: ConwayGovState[Dijkstra],
        record: StoredGovState[Dijkstra]
    ): Either[StoredGovError, StoredGovState[Dijkstra]] =
      GovStateRecord
        .toStored[Dijkstra](gov, GovStateBases.of(record))
        .map(
          withProposalsOf(record, _, gov.cgsDRepPulsingState == read.cgsDRepPulsingState)
        )
        .leftMap(StoredGovError.Write(_))

    /** `written` with each action's proposal the one `record` holds for it where the action came
      * from, where both read as the same proposal: an action's proposal never changes
      * (`gasProposalProcedure`, set by `proposalsAddAction` and never written after), and the
      * record keeps the original encoding of each field of an update
      * ([[xyz.kd5ujc.scarab.ledger.ConwayParamUpdate.encodings]]), which the era's values do not,
      * and may keep another one for the same action in each place it holds it. Each place keeps its
      * own: the proposals the record's proposals; the pulser's snapshot and its enacted actions the
      * record's pulser's, where the tick `carried` the pulser, and else, where the boundary started
      * a fresh one from the proposals, the record's proposals.
      */
    private def withProposalsOf(
        record: StoredGovState[Dijkstra],
        written: StoredGovState[Dijkstra],
        carried: Boolean
    ): StoredGovState[Dijkstra] =
      def byId(actions: Vector[StoredGovActionState]): Map[GovActionId, Proposal] =
        actions.iterator.map(a => a.id -> a.proposal).toMap
      val proposals = byId(record.proposals.actions)
      val pulsing = record.drepPulsing
      val snapshot = if carried then byId(pulsing.snapshot.proposals) else proposals
      val enacted = if carried then byId(pulsing.ratify.enacted) else proposals
      def kept(
          originals: Map[GovActionId, Proposal]
      )(a: StoredGovActionState): StoredGovActionState =
        originals
          .get(a.id)
          .filter(o =>
            o != a.proposal && DijkstraProposals.fromFlat(o) == DijkstraProposals.fromFlat(
              a.proposal
            )
          )
          .fold(a)(o => a.copy(proposal = o))
      val p = written.drepPulsing
      written.copy(
        proposals =
          written.proposals.copy(actions = written.proposals.actions.map(kept(proposals))),
        drepPulsing = p.copy(
          snapshot = p.snapshot.copy(proposals = p.snapshot.proposals.map(kept(snapshot))),
          ratify = p.ratify.copy(enacted = p.ratify.enacted.map(kept(enacted)))
        )
      )

    def synced(es: EpochState[Dijkstra], gov: ConwayGovState[Dijkstra]): EpochState[Dijkstra] =
      recordOf[Dijkstra].modify(g =>
        g.copy(
          curPParams = r.storedPParams(gov.cgsCurPParams, g.curPParams),
          prevPParams = r.storedPParams(gov.cgsPrevPParams, g.prevPParams)
        )
      )(es)

  /** The node's record of the governance state in an epoch state. */
  def recordOf[E <: EraTag]: Lens[EpochState[E], StoredGovState[E]] =
    EpochState.ls[E].andThen(LedgerState.utxo[E]).andThen(UTxOState.gov[E])

  /** `rule`, which works on `S` with the era's governance state beside it (`G`, made by `pair` and
    * taken apart by `unpair`), as a rule on `S` whose node's record (`record`) holds that state:
    * the record read before the rule and the state the rule leaves written back over it after.
    */
  def onRecord[E <: EraTag, S, G, Sig](
      rule: Sts[Unit, G, Sig, TickFailure],
      record: Lens[S, StoredGovState[E]],
      pair: (S, ConwayGovState[E]) => G,
      unpair: G => (S, ConwayGovState[E])
  )(using store: StoredGov[E]): Sts[Unit, S, Sig, TickFailure] =
    new Sts[Unit, S, Sig, TickFailure]:
      def transition[F[_]: Monad](env: Unit, signal: Sig)(using
          ask: Ask[F, Globals],
          st: Stateful[F, S],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        def lift[A](result: Either[StoredGovError, A]): F[A] =
          Sts.lift[F, TickFailure, A](result.leftMap(TickFailure.GovRecord(_)))
        for
          before <- st.get
          stored = record.get(before)
          gov <- lift(store.read(stored))
          globals <- Sts.globals[F]
          after <- Sts.lift[F, TickFailure, G](Sts(rule)(globals, env, pair(before, gov), signal))
          (s, g) = unpair(after)
          written <- lift(store.write(g, gov, stored))
          _ <- st.set(record.replace(written)(s))
        yield ()
