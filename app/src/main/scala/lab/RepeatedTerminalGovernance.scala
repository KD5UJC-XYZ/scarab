// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.ledger.{
  ConwayEmptyGovernance as G,
  ConwayRegisteredDRepCompletion as D,
  ConwayStake as S
}
import RepeatedTerminalCbor.{Node, Value as V}
import RepeatedTerminalSupport.*
import ReferenceJson.Json as J
import scala.util.boundary

/** Empty-proposal endpoint observation. Completing a captured DRep pulser uses the checked
  * completion capability; it never substitutes chain treasury for RATIFY's cleared treasury.
  */
private[lab] object RepeatedTerminalGovernance:
  enum Future:
    case NoUpdate, PotentialNone
  final case class Enact(
      committee: Option[G.Committee],
      constitution: G.Constitution,
      current: Bytes,
      previous: Bytes,
      treasury: BigInt
  )
  final case class Completed(snapshot: G.CompletedSnapshot, enact: Enact)
  final case class Projection(
      dormant: BigInt,
      dreps: Map[S.Credential, G.DRepState],
      committee: Option[G.Committee],
      constitution: G.Constitution,
      current: Bytes,
      previous: Bytes,
      future: Future,
      completed: Completed
  )

  private def supportedRoots(roots: Map[G.Purpose, Option[G.ActionId]])(using Scope): Unit =
    equal(roots.keySet, G.Purpose.values.toSet, "governance purpose domain")
    ensure(roots.values.forall(_.isEmpty), Failure.Unsupported("nonempty governance roots"))
  private def completed(snapshot: G.CompletedSnapshot, ratify: G.Ratify)(using Scope): Completed =
    ensure(
      snapshot.proposals.isEmpty && ratify.enacted.isEmpty && ratify.expired.isEmpty &&
        !ratify.delayed && ratify.enact.withdrawals.isEmpty,
      Failure.Unsupported("nonempty proposal/RATIFY effects")
    )
    supportedRoots(ratify.enact.roots)
    val e = ratify.enact
    Completed(
      snapshot,
      Enact(e.committee, e.constitution, e.current.original, e.previous.original, e.treasury)
    )

  def fromState(state: SyntheticBoundaryState.State): Either[Failure, Projection] = boundary:
    valid(state != null && state.repeated, "repeated governance state")
    val input = state.governanceInput
    ensure(
      input.proposals.isEmpty && input.committeeState.isEmpty && input.poolUpdates.isEmpty &&
        input.retirements.isEmpty && input.proposalDeposits.isEmpty,
      Failure.Unsupported("governance actions, committee authorizations or pool changes")
    )
    supportedRoots(input.roots)
    val future = input.parameters.future match
      case G.FutureParameters.NoUpdate      => Future.NoUpdate
      case G.FutureParameters.PotentialNone => Future.PotentialNone
      case G.FutureParameters.Pending(_) =>
        reject(Failure.Unsupported("pending parameter update"))
    val old = state.governanceAfter match
      case Some(after) =>
        val checked = get(D.complete(after, after.id, after.epoch), "registered DRep completion")
        get(D.forSource(checked, after, after.id, after.epoch), "DRep captured source")
      case None => input.oldDRep
    val c = old match
      case G.OldDRep.Complete(snapshot, ratify) => completed(snapshot, ratify)
      case _ => reject(Failure.Unsupported("unknown or active DRep completion source"))
    Right(
      Projection(
        input.dormant,
        input.dreps,
        input.committee,
        input.constitution,
        state.roles.current.original,
        state.roles.previous.original,
        future,
        c
      )
    )

  private def anchor(n: Node)(using Scope): G.Anchor =
    val a = arr(n, 2)
    val url = a(0).value match
      case V.Text(s) if s.getBytes("UTF-8").length <= 128 => s
      case _ => reject(Failure.Invalid("anchor", "bounded URL required"))
    G.Anchor(url, bytes(a(1), 32))
  private def drep(n: Node)(using Scope): G.DRepState =
    val a = arr(n, 4)
    G.DRepState(uint(a(0)), maybe(a(1))(anchor), uint(a(2)), set(a(3))(credential))
  def vote(n: Node)(using Scope): G.Vote = rows(n) match
    case Vector(tag) =>
      uint(tag) match
        case n if n == 2 => G.Vote.AlwaysAbstain
        case n if n == 3 => G.Vote.AlwaysNoConfidence
        case _           => reject(Failure.Invalid("vote", "special voting target tag"))
    case xs if xs.size == 2 => G.Vote.Credential(credential(n))
    case _ => reject(Failure.Invalid("vote", "credential or special target required"))
  def account(n: Node)(using Scope): G.Account =
    val a = arr(n, 4)
    G.Account(uint(a(0)), uint(a(1)), nullable(a(2))(bytes(_, 28)), nullable(a(3))(vote))
  private def committee(n: Node)(using Scope): Option[G.Committee] = maybe(n) { inner =>
    val a = arr(inner, 2)
    G.Committee(mapping(a(0))(credential, uint), ratio(a(1)))
  }
  private def constitution(n: Node)(using Scope): G.Constitution =
    val a = arr(n, 2)
    G.Constitution(anchor(a(0)), nullable(a(1))(bytes(_, 28)))
  private def roots(n: Node)(using Scope): Unit =
    arr(n, 4).foreach(emptySeq(_, "nonempty governance roots"))
  private def parameter(n: Node)(using Scope): Bytes =
    get(
      GovernanceParameterPayload.decode(n.original, sha(n.original)),
      "parameter payload"
    ).original
  private def future(n: Node)(using Scope): Future =
    val fields = rows(n)
    valid(fields.nonEmpty, "future parameter sum")
    // Pinned core-1.21.0.0 State/Governance.hs encodes distinct sum constructors.
    // binary-1.9.1.0 encodeMaybe encodes Nothing as []; a pending value is never erased.
    uint(fields.head) match
      case tag if tag == 0 =>
        equal(fields.size, 1, "NoUpdate width")
        Future.NoUpdate
      case tag if tag == 2 =>
        equal(fields.size, 2, "PotentialNone width")
        emptySeq(fields(1), "potential parameter update with payload")
        Future.PotentialNone
      case _ => reject(Failure.Unsupported("pending or unknown parameter update"))
  private def decodeCompleted(n: Node)(using Scope): Completed =
    val a = arr(n, 2); val snapshot = arr(a(0), 4)
    emptySeq(snapshot(0), "DRep proposals")
    val s = G.CompletedSnapshot(
      Vector.empty,
      mapping(snapshot(1))(vote, uint),
      mapping(snapshot(2))(credential, drep),
      mapping(snapshot(3))(bytes(_, 28), uint)
    )
    val ratify = arr(a(1), 4); val e = arr(ratify(0), 7)
    emptySeq(ratify(1), "enacted proposals")
    ensure(set(ratify(2))(x => x.original).isEmpty, Failure.Unsupported("expired proposals"))
    equal(ratify(3).value, V.Bool(false), "ratification delay")
    emptyMap(e(5), "treasury withdrawals"); roots(e(6))
    Completed(
      s,
      Enact(committee(e(0)), constitution(e(1)), parameter(e(2)), parameter(e(3)), uint(e(4)))
    )

  def decode(voting: Node, governance: Node): Either[Failure, Projection] = boundary:
    val v = arr(voting, 3); val g = arr(governance, 7)
    emptyMap(v(1), "committee authorizations")
    val proposals = arr(g(0), 2); roots(proposals(0)); emptySeq(proposals(1), "proposals")
    Right(
      Projection(
        uint(v(2)),
        mapping(v(0))(credential, drep),
        committee(g(1)),
        constitution(g(2)),
        parameter(g(3)),
        parameter(g(4)),
        future(g(5)),
        decodeCompleted(g(6))
      )
    )

  def voteKey(v: G.Vote): String = v match
    case G.Vote.Credential(c)      => cred(c)
    case G.Vote.AlwaysAbstain      => "always-abstain"
    case G.Vote.AlwaysNoConfidence => "always-no-confidence"
  def accountJson(a: G.Account): J = record(
    "rewards" -> num(a.rewards),
    "deposit" -> num(a.deposit),
    "pool" -> option(a.pool)(b => str(b.hex)),
    "vote" -> option(a.vote)(v => str(voteKey(v)))
  )
  private def anchorJson(a: G.Anchor): J = J.Arr(Vector(str(a.url), str(a.hash.hex)))
  private def drepJson(d: G.DRepState): J = record(
    "expiry" -> num(d.expiry),
    "anchor" -> option(d.anchor)(anchorJson),
    "deposit" -> num(d.deposit),
    "delegators" -> J.Arr(d.delegators.toVector.map(cred).sorted.map(str))
  )
  private def committeeJson(c: Option[G.Committee]): J = option(c)(x =>
    record("members" -> credentialCoins(x.members), "threshold" -> ratioJson(x.threshold))
  )
  private def constitutionJson(c: G.Constitution): J =
    record("anchor" -> anchorJson(c.anchor), "script" -> option(c.script)(b => str(b.hex)))
  def json(p: Projection): J =
    val s = p.completed.snapshot; val e = p.completed.enact
    record(
      "dormant" -> num(p.dormant),
      "dreps" -> mapJson(p.dreps)(cred, drepJson),
      "committee" -> committeeJson(p.committee),
      "constitution" -> constitutionJson(p.constitution),
      "currentParameters" -> str(p.current.hex),
      "previousParameters" -> str(p.previous.hex),
      "futureParameters" -> str(p.future match
        case Future.NoUpdate      => "no-update"
        case Future.PotentialNone => "potential-none"),
      "completed" -> record(
        "drepDistribution" -> mapJson(s.drepDistribution)(voteKey, num),
        "dreps" -> mapJson(s.dreps)(cred, drepJson),
        "poolDistribution" -> poolCoins(s.poolDistribution),
        "enact" -> record(
          "committee" -> committeeJson(e.committee),
          "constitution" -> constitutionJson(e.constitution),
          "currentParameters" -> str(e.current.hex),
          "previousParameters" -> str(e.previous.hex),
          "treasury" -> num(e.treasury)
        )
      )
    )
