// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import munit.FunSuite
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Credential,
  GovAction as FlatGovAction,
  GovActionId,
  Proposal,
  RewardAccount
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayProcedures
import xyz.kd5ujc.scarab.ledger.era.rules.{ConwayGov, GovDefect}
import xyz.kd5ujc.scarab.ledger.state.{Conway, EpochInterval, EpochNo}

/** The reference's store of proposals on the era types (`Governance/Proposals.hs`, cardano-ledger
  * f649f975): the trees' roots and edges as actions are added, the read of a record that holds an
  * id twice, and the proposals' numbering (`indexedGovProps`, `Governance/Procedures.hs:490-496`).
  */
class EraProposalsSuite extends FunSuite:
  private def id(n: Int): GovActionId =
    GovActionId(Bytes.fromArray(Array.fill(32)(n.toByte)), BigInt(0))

  private val account = RewardAccount(0, Credential.Key(Bytes.fromArray(Array.fill(28)(1.toByte))))

  /** The state of a new action of id `n`, from the flat action as Conway's decoder reads it. */
  private def gas(n: Int, action: FlatGovAction): GovActionState[Conway] =
    val proposal = ConwayProcedures
      .proposal(Proposal(BigInt(0), account, action))
      .fold(e => fail(s"proposal: $e"), identity)
    ConwayGov.mkGovActionState(id(n), proposal, EpochInterval.zero, EpochNo.fromUnsigned(0))

  private def hardFork(n: Int, parent: Option[Int]): GovActionState[Conway] =
    gas(n, FlatGovAction.HardFork(parent.map(id), BigInt(10), BigInt(0)))

  private val noRoots = GovRelation[Option](None, None, None, None)

  private def hf(n: Int) = GovPurposeId[GovActionPurpose.HardFork](id(n))

  private def added(actions: GovActionState[Conway]*): Proposals[Conway] =
    actions.foldLeft(Proposals.empty[Conway](noRoots))((ps, g) =>
      Proposals.proposalsAddAction(g, ps).getOrElse(fail(s"${g.gasId} does not attach"))
    )

  test("a chain of hard forks: the root's child, and each action's parent and children"):
    val ps = added(hardFork(1, None), hardFork(2, Some(1)), hardFork(3, Some(2)), hardFork(4, None))
    assertEquals(ps.pRoots.grHardFork, PRoot(None, SortedSet(hf(1), hf(4))))
    assertEquals(
      ps.pGraph.grHardFork,
      PGraph(
        SortedMap(
          hf(1) -> PEdges(None, SortedSet(hf(2))),
          hf(2) -> PEdges(Some(hf(1)), SortedSet(hf(3))),
          hf(3) -> PEdges(Some(hf(2)), SortedSet.empty),
          hf(4) -> PEdges(None, SortedSet.empty)
        )
      )
    )
    assertEquals(ps.pProps.keys, Vector(1, 2, 3, 4).map(id))
    // the other purposes' trees are untouched
    assert(ps.pRoots.grPParamUpdate.prChildren.isEmpty)
    assert(ps.pGraph.grCommittee.unPGraph.isEmpty)
    // an action whose parent is in no tree does not attach
    assertEquals(Proposals.proposalsAddAction(hardFork(5, Some(9)), ps), None)

  test("an action replacing one of its id that names it as parent: the reference's edges"):
    // `Map.insert newId (PEdges parent empty) . Map.adjust (insert newId) parentId`
    // (`Proposals.hs:330-333`, cardano-ledger f649f975): the new edge is inserted last, so the
    // action is not its own child
    val ps = added(hardFork(1, None))
    val replaced = Proposals.proposalsAddAction(hardFork(1, Some(1)), ps).getOrElse(fail("refused"))
    assertEquals(
      replaced.pGraph.grHardFork.unPGraph,
      SortedMap(hf(1) -> PEdges(Some(hf(1)), SortedSet.empty))
    )
    assertEquals(replaced.pProps.keys, Vector(id(1)))

  test("a record holding an id twice is malformed, never one action silently"):
    val first = hardFork(1, None)
    val second = gas(1, FlatGovAction.Info)
    assertEquals(
      Proposals.mkProposals[Conway](noRoots, Vector(first, second)),
      Left(ProposalsReadError.Malformed(Vector(id(1), id(1)), Vector(id(1))))
    )
    assertEquals(
      Proposals
        .mkProposals[Conway](noRoots, Vector(first, hardFork(2, Some(1))))
        .map(_.pProps.keys),
      Right(Vector(id(1), id(2)))
    )
    assertEquals(
      Proposals.mkProposals[Conway](noRoots, Vector(hardFork(2, Some(1)))),
      Left(ProposalsReadError.Unattached(id(2)))
    )

  test("proposals are numbered by a Word16: the last index is 65535, and a 65,537th has none"):
    val last = ConwayGov.indexedGovProps(Vector.fill(65536)(()))
    assertEquals(last.map(_.lastOption.map(_._1.value)), Right(Some(BigInt(65535))))
    assertEquals(
      ConwayGov.indexedGovProps(Vector.fill(65537)(())),
      Left(GovDefect.ProposalIndexOverflow(65537))
    )
