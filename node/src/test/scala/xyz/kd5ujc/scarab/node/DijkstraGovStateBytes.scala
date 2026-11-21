// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{GovActionId, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraGovActionId
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayGovState,
  DRepPulsingState,
  GovActionState,
  EraTxError,
  GovStateBases,
  GovStateReadError,
  GovStateRecord,
  ProposalProcedure,
  Proposals,
  ProposalsReadError,
  ProposalsWriteError
}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraProposals
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.{ConstraintViolation, DecodeError}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, LeiosStateCodecs}

/** Why [[DijkstraGovStateBytes]] cannot read or write a state, each with its typed cause. */
enum GovStateBytesError:
  case Cbor(error: xyz.kd5ujc.scarab.codec.Cbor.Error)
  case NotAnArray(value: Value)
  case NoItem(index: Int)
  case ProposalItems(size: Int)
  case ActionId(error: LedgerDecodeError)
  case State(error: DecodeError)
  case Written(error: ConstraintViolation)
  case Record(error: GovStateReadError)
  case Proposal(error: EraTxError)
  case Rebuilt(error: ProposalsReadError)
  case Unstorable(error: ProposalsWriteError)

  /** An action whose proposal is not the era reader's value of the item it would be written as. */
  case Changed(id: GovActionId)

/** A Dijkstra `NewEpochState` as the Leios build writes it, read into the node's state with its
  * governance state as the era's record, and written back, where the actions it holds may update
  * the keys Dijkstra adds (34 to 56), which the node's record of an action cannot hold
  * (`ProposalsWriteError.UnstorableUpdate`), so the node's codec neither reads nor writes such a
  * state. The era's record has no codec of its own yet: that is the format change that flips the
  * stored record (FMT-1, #85).
  *
  * Read: each action's proposal (the fifth item of an action's state, in the proposals, the
  * pulser's snapshot and its ratification's enacted actions) is replaced by an information action
  * of the same deposit, return account and anchor; the state so made is read by the node's codec,
  * its governance record into the era's ([[GovStateRecord.fromStored]]), and each action's proposal
  * is then the one Dijkstra's reader of a stored proposal reads from the original item
  * ([[DijkstraProposals.proposal]]), the proposals rebuilt by `mkProposals`. Write: the same
  * replacement made of the era's record, the state written by the node's codec, and each
  * replacement's bytes then the original item's, once each action's proposal is checked to be the
  * era reader's value of that item. So every byte but the proposals' own is the node's codec's, and
  * each proposal is the era reader's value of the bytes it is written as.
  */
object DijkstraGovStateBytes:
  private given ShelleyBasedEra[Dijkstra] = ShelleyBasedEra.SDijkstra
  private val codec = LeiosStateCodecs.newEpochState[Dijkstra]

  /** A state read: the node's state, the era's governance state, and the original proposal item of
    * each action by its id.
    */
  final case class Read(
      nes: NewEpochState[Dijkstra],
      gov: ConwayGovState[Dijkstra],
      proposals: Map[GovActionId, Node]
  ):
    def state: EraDijkstraTick.State = EraDijkstraTick.State(nes, gov)

  private def items(n: Node): Either[GovStateBytesError, Vector[Node]] = n.value match
    case Value.Arr(xs) => Right(xs)
    case other         => Left(GovStateBytesError.NotAnArray(other))

  private def at(n: Node, path: Int*): Either[GovStateBytesError, Node] =
    path.foldLeft[Either[GovStateBytesError, Node]](Right(n))((m, i) =>
      m.flatMap(items).flatMap(_.lift(i).toRight(GovStateBytesError.NoItem(i)))
    )

  private def decode(bytes: Bytes): Either[GovStateBytesError, Node] =
    Cbor.decode(bytes, HaskellState.limits).leftMap(GovStateBytesError.Cbor(_))

  /** The action states of the governance state of `nes`: the proposals' (`gov[0][1]`), the pulser's
    * snapshot's (`gov[6][0][0]`) and its ratification's enacted actions (`gov[6][1][1]`), the
    * governance state at `[3, 1, 1, 3]` of the `NewEpochState` (`esLState`, `lsUTxOState`,
    * `utxosGovState`).
    */
  private def actionStates(nes: Node): Either[GovStateBytesError, Vector[Node]] =
    for
      gov <- at(nes, 3, 1, 1, 3)
      lists <- Vector(at(gov, 0, 1), at(gov, 6, 0, 0), at(gov, 6, 1, 1)).sequence
      states <- lists.traverse(items)
    yield states.flatten

  private def id(gas: Node): Either[GovStateBytesError, GovActionId] =
    at(gas, 0)
      .flatMap(n => DijkstraGovActionId.decode(n).leftMap(GovStateBytesError.ActionId(_)))
      .map(d => GovActionId(d.txId, BigInt(d.index)))

  /** An information action (`[6]`) of `proposal`'s deposit, return account and anchor. */
  private def placeholder(proposal: Node): Either[GovStateBytesError, Bytes] =
    items(proposal).flatMap {
      case Vector(deposit, account, _, anchor) =>
        Right(
          Bytes.fromArray(Array[Byte](0x84.toByte)) ++ deposit.original ++ account.original ++
            Bytes.fromArray(Array[Byte](0x81.toByte, 0x06)) ++ anchor.original
        )
      case other => Left(GovStateBytesError.ProposalItems(other.size))
    }

  /** `bytes` with each `(node, replacement)` 's item replaced, the nodes being items of `bytes` '
    * decode, each replaced from the last.
    */
  private def splice(bytes: Bytes, replacements: Vector[(Node, Bytes)]): Bytes =
    replacements.sortBy(-_._1.offset).foldLeft(bytes) { case (b, (node, replacement)) =>
      b.take(node.offset) ++ replacement ++ b.drop(node.offset + node.original.size)
    }

  /** `nes`'s bytes with each action's proposal replaced by `replacement` of its id and node. */
  private def replaced(
      bytes: Bytes,
      replacement: (GovActionId, Node) => Either[GovStateBytesError, Option[Bytes]]
  ): Either[GovStateBytesError, (Bytes, Map[GovActionId, Node])] =
    // a fresh array, so that each item's offset is from the start of `bytes`
    val own = Bytes.fromArray(bytes.toArray)
    for
      nes <- decode(own)
      states <- actionStates(nes)
      found <- states.traverse(gas =>
        for
          i <- id(gas)
          proposal <- at(gas, 4)
          r <- replacement(i, proposal)
        yield (i, proposal, r)
      )
    yield (
      splice(own, found.flatMap((_, proposal, r) => r.map(proposal -> _))),
      found.map((i, proposal, _) => i -> proposal).toMap
    )

  private def withProposal(
      gas: GovActionState[Dijkstra],
      proposals: Map[GovActionId, ProposalProcedure[Dijkstra]]
  ): GovActionState[Dijkstra] =
    proposals.get(gas.gasId).fold(gas)(p => gas.copy(gasProposalProcedure = p))

  /** The era's record with each action's proposal that of `proposals` by its id. */
  private def withProposals(
      g: ConwayGovState[Dijkstra],
      proposals: Map[GovActionId, ProposalProcedure[Dijkstra]]
  ): Either[GovStateBytesError, ConwayGovState[Dijkstra]] =
    val pulser = g.cgsDRepPulsingState match
      case DRepPulsingState.DRComplete(snapshot, ratify) =>
        DRepPulsingState.DRComplete(
          snapshot.copy(psProposals = snapshot.psProposals.map(withProposal(_, proposals))),
          ratify.copy(rsEnacted = ratify.rsEnacted.map(withProposal(_, proposals)))
        )
    Proposals
      .mkProposals[Dijkstra](
        Proposals.toPrevGovActionIds(g.cgsProposals.pRoots),
        Proposals.proposalsActions(g.cgsProposals).map(withProposal(_, proposals))
      )
      .bimap(
        GovStateBytesError.Rebuilt(_),
        ps => g.copy(cgsProposals = ps, cgsDRepPulsingState = pulser)
      )

  private def proposalOf(n: Node): Either[GovStateBytesError, ProposalProcedure[Dijkstra]] =
    DijkstraProposals.proposal(n).leftMap(GovStateBytesError.Proposal(_))

  /** A `NewEpochState`'s bytes read ([[DijkstraGovStateBytes]]). */
  def read(bytes: Bytes): Either[GovStateBytesError, Read] =
    for
      (stand, originals) <- replaced(bytes, (_, p) => placeholder(p).map(Some(_)))
      node <- decode(stand)
      nes <- codec.decode(node).leftMap(GovStateBytesError.State(_))
      flatGov <- GovStateRecord
        .fromStored[Dijkstra](nes.es.ls.utxo.gov)
        .leftMap(GovStateBytesError.Record(_))
      proposals <- originals.toVector.traverse((i, n) => proposalOf(n).map(i -> _))
      gov <- withProposals(flatGov, proposals.toMap)
    yield Read(nes, gov, originals)

  /** The state `s` written ([[DijkstraGovStateBytes]]) over the parameter records `bases`, each
    * action's proposal the original item `proposals` holds of its id. An action with none must be
    * one the node's record holds.
    */
  def write(
      s: EraDijkstraTick.State,
      bases: GovStateBases,
      proposals: Map[GovActionId, Node]
  ): Either[GovStateBytesError, Bytes] =
    val held = s.gov.cgsDRepPulsingState match
      case DRepPulsingState.DRComplete(snapshot, ratify) =>
        Proposals.proposalsActions(s.gov.cgsProposals) ++ snapshot.psProposals ++ ratify.rsEnacted
    for
      _ <- held.filter(g => proposals.contains(g.gasId)).traverse_ { g =>
        proposalOf(proposals(g.gasId)).flatMap(p =>
          Either.cond(p == g.gasProposalProcedure, (), GovStateBytesError.Changed(g.gasId))
        )
      }
      stands <- proposals.toVector.traverse((i, n) =>
        placeholder(n).flatMap(decode).flatMap(proposalOf).map(i -> _)
      )
      gov <- withProposals(s.gov, stands.toMap)
      nes <- EraDijkstraTick
        .stored(EraDijkstraTick.State(s.nes, gov), bases)
        .leftMap(GovStateBytesError.Unstorable(_))
      written <- codec.write(nes).leftMap(GovStateBytesError.Written(_))
      (spliced, _) <- replaced(
        written,
        (i, _) => Right(proposals.get(i).map(_.original))
      )
    yield spliced

  /** The `NewEpochState` of a state file the build wrote: the extended state's ledger telescope at
    * its eighth era, `[start, [2, [tip, NewEpochState, transition, bytes]]]`.
    */
  def newEpochStateOfFile(bytes: Bytes): Either[GovStateBytesError, Bytes] =
    decode(bytes).flatMap(root => at(root, 1, 0, 7, 1, 1, 1)).map(_.original)
