// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{GovActionId, PreprodDeepTx}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraCorpus, DijkstraProposals}
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayEpoch
import xyz.kd5ujc.scarab.ledger.state.{
  ChainAccountState,
  Coin,
  CompactCoin,
  ConwayAccountState,
  DeltaCoin,
  ConwayAccounts,
  Credential,
  DState,
  Dijkstra,
  EpochNo,
  InstantaneousRewards,
  KeyHash,
  Sorted,
  Staking,
  VState,
  CommitteeState
}

/** The functions of Dijkstra's EPOCH on the era types (`era/rules/Epoch.scala`,
  * `era/Proposals.scala`, `era/Governance.scala`; cardano-ledger 9df9f98c `Conway/Rules/Epoch.hs`,
  * `Governance/Proposals.hs:431-551`, `Conway/Governance.hs:300-339`,
  * `State/Governance.hs:116-140`):
  *
  *   - `proposalsApplyEnactment` on a forest of every shape, against the node's flat rule
  *     (`xyz.kd5ujc.scarab.ledger.Proposals.applyEnactment`, which the recorded epoch cases bind):
  *     the same proposals, roots and removed actions, over random forests too (TST-6); and its
  *     removal of 10,000 descendants on a 256 KiB stack (STK-3);
  *   - `returnProposalDeposits`, `applyEnactedWithdrawals` and `updateNumDormantEpochs`: a
  *     registered account is paid and an unregistered one's amount is kept, and a dormant epoch is
  *     one with no action still to vote on;
  *   - `predictFuturePParams`, `solidifyFuturePParams` and `nextEpochPParams`.
  */
class EraEpochGovSuite extends ScalaCheckSuite:
  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  private def bytes(n: Int, size: Int): Bytes = Bytes(Vector.fill(size)(n.toByte))

  // ---- CBOR of crafted proposals, as the reference's encoders write them

  private def head(major: Int, n: BigInt): String =
    val m = major << 5
    if n < 24 then f"${m | n.toInt}%02x"
    else
      Vector(24 -> 1, 25 -> 2, 26 -> 4, 27 -> 8)
        .collectFirst {
          case (code, width) if n < (BigInt(1) << (8 * width)) =>
            f"${m | code}%02x" + n.toString(16).reverse.padTo(2 * width, '0').reverse
        }
        .getOrElse(fail(s"$n"))

  private def uint(n: BigInt): String = head(0, n)
  private def bstr(b: Bytes): String = head(2, b.size) + b.hex
  private def arr(items: String*): String = head(4, items.size) + items.mkString

  private val txId = bytes(6, 32)
  private def actionId(ix: Int): GovActionId = GovActionId(txId, BigInt(ix))
  private val anchor = arr(
    head(3, 19) + "https://example.com".getBytes.map(b => f"$b%02x").mkString,
    bstr(bytes(0, 32))
  )
  private def account(n: Int): String = bstr(DijkstraCorpus.hex("e0" + f"$n%02x" * 28))

  /** A proposal of `action`, a deposit of 100 returned to account `n`. */
  private def procedure(action: String, n: Int): ProposalProcedure[Dijkstra] =
    DijkstraCorpus
      .eraProposals(
        DijkstraCorpus.hex("d90102" + arr(arr(uint(100), account(n), action, anchor)))
      )
      .toVector
      .headOption
      .getOrElse(fail("no proposal"))

  /** A change of Conway's key 0 (`txFeePerByte`), which the node's flat record holds, of the
    * purpose of parameter changes, under `parent`.
    */
  private def change(parent: Option[Int]): String =
    arr(uint(0), parent.fold("f6")(ix => arr(bstr(txId), uint(ix))), "a1" + "00" + "01", "f6")

  /** A hard fork to 13.0, of its own purpose, under `parent`. */
  private def hardFork(parent: Option[Int]): String =
    arr(uint(1), parent.fold("f6")(ix => arr(bstr(txId), uint(ix))), arr(uint(13), uint(0)))

  private val info = arr(uint(6))

  private def gas(
      ix: Int,
      action: String,
      expiresAfter: Long = 20,
      n: Int = 1
  ): GovActionState[Dijkstra] =
    GovActionState[Dijkstra](
      actionId(ix),
      Sorted.empty,
      Sorted.empty,
      Sorted.empty,
      procedure(action, n),
      EpochNo.fromUnsigned(9),
      EpochNo.fromUnsigned(expiresAfter)
    )

  private def proposals(actions: GovActionState[Dijkstra]*): Proposals[Dijkstra] =
    ok(
      Proposals.mkProposals[Dijkstra](GovRelation[Option](None, None, None, None), actions.toVector)
    )

  /** The era's `proposalsApplyEnactment` and the node's flat rule on the same proposals: the
    * proposals after, by the node's record, and the ids of the enacted, competing and expired.
    */
  private def bothRules(
      ps: Proposals[Dijkstra],
      enacted: Vector[GovActionState[Dijkstra]],
      expired: Set[GovActionId]
  ): Unit =
    val (era, eraEnacted, eraCompeting, eraExpired) =
      Proposals.proposalsApplyEnactment(enacted, expired, ps)
    val stored = ok(DijkstraProposals.toStored(ps))
    val (flat, flatCompeting, flatExpired) =
      stored.applyEnactment(enacted.map(g => ok(DijkstraProposals.storedAction(g))), expired)
    assertEquals(ok(DijkstraProposals.toStored(era)), flat)
    assertEquals(eraEnacted.keySet, enacted.map(_.gasId).toSet.filter(ps.pProps.toMap.contains))
    assertEquals(eraCompeting.keySet, flatCompeting.map(_.id).toSet)
    assertEquals(eraExpired.keySet, flatExpired.map(_.id).toSet)
    // the store after is the one `mkProposals` rebuilds of its actions over its roots
    assertEquals(
      era,
      ok(
        Proposals
          .mkProposals[Dijkstra](Proposals.toPrevGovActionIds(era.pRoots), era.pProps.toVector)
      )
    )

  // the forest: changes 0 and 1 under no root, 2 under 0, 3 under 1, 4 under 2; hard forks 5 and 6
  // under no root; an information action 7
  private val forest = Vector(
    gas(0, change(None)),
    gas(1, change(None)),
    gas(2, change(Some(0))),
    gas(3, change(Some(1))),
    gas(4, change(Some(2))),
    gas(5, hardFork(None)),
    gas(6, hardFork(None)),
    gas(7, info)
  )
  private val ps = proposals(forest*)

  test(
    "enacting an action makes it its tree's root and removes its siblings with their descendants"
  ) {
    val (after, enacted, competing, expired) = Proposals.proposalsApplyEnactment(
      Vector(forest(0)),
      Set.empty,
      ps
    )
    assertEquals(after.pProps.keys, Vector(2, 4, 5, 6, 7).map(actionId))
    assertEquals(enacted.keySet, Set(actionId(0)))
    assertEquals(competing.keySet, Set(1, 3).map(actionId))
    assert(expired.isEmpty)
    val root = after.pRoots.grPParamUpdate
    assertEquals(root.prRoot, Some(GovPurposeId[GovActionPurpose.PParamUpdate](actionId(0))))
    assertEquals(root.prChildren.map(_.unGovPurposeId), SortedSet(actionId(2)))
    bothRules(ps, Vector(forest(0)), Set.empty)
  }

  test("enactments in order, of several purposes and of none, and expiries with descendants") {
    bothRules(ps, Vector(forest(0), forest(2), forest(6), forest(7)), Set.empty)
    bothRules(ps, Vector.empty, Set(actionId(0)))
    bothRules(ps, Vector(forest(1)), Set(actionId(2), actionId(5)))
    bothRules(ps, Vector(forest(5)), Set(actionId(1)))
    // enacted actions the proposals no longer hold (expired, 2 below 0), each its tree's root all the
    // same, as the reference's compiled-out assertions leave it
    val (after, enacted, _, expired) =
      Proposals.proposalsApplyEnactment(Vector(forest(0), forest(2)), Set(actionId(0)), ps)
    assertEquals(
      after.pRoots.grPParamUpdate.prRoot,
      Some(GovPurposeId[GovActionPurpose.PParamUpdate](actionId(2)))
    )
    assert(enacted.isEmpty)
    assertEquals(expired.keySet, Set(0, 2, 4).map(actionId))
  }

  /** A random forest of changes, each under no root or an earlier change, and information actions;
    * enacted changes along one line of it and expired others.
    */
  private val scenarios =
    for
      size <- Gen.choose(1, 24)
      parents <- Gen.sequence[Vector[Option[Int]], Option[Int]](
        (0 until size).map(ix =>
          if ix == 0 then Gen.const(None) else Gen.option(Gen.choose(0, ix - 1))
        )
      )
      infos <- Gen.listOfN(size, Gen.prob(0.2))
      expired <- Gen.containerOf[Set, Int](Gen.choose(0, size - 1))
    yield
      // a change's parent is a change, so an information action's would-be children are roots
      val parentOf = (0 until size).map(ix => parents(ix).filterNot(p => infos(p)))
      val actions =
        (0 until size).toVector.map(ix => gas(ix, if infos(ix) then info else change(parentOf(ix))))
      // the enacted: a line from a root down, each the child of the one before
      val line = Vector.unfold(Option(0)) {
        case Some(ix) if !infos(ix) =>
          Some(ix -> (ix + 1 until size).find(c => !infos(c) && parentOf(c).contains(ix)))
        case _ => None
      }
      (actions, line.map(actions), expired.map(actionId).filterNot(line.map(actionId).contains))

  property("the era's enactment and the node's agree on random forests") {
    forAll(scenarios) { (actions, enacted, expired) =>
      bothRules(proposals(actions*), enacted, expired)
    }
  }

  test("expiring the root of a chain of 10,000 removes them all, on a small stack") {
    val n = 10000
    val chain = (0 until n).map(ix => gas(ix, change(Option.when(ix > 0)(ix - 1))))
    val (after, _, _, expired) = PreprodDeepTx.onSmallStack(
      Proposals.proposalsApplyEnactment(Vector.empty, Set(actionId(0)), proposals(chain*))
    )
    assertEquals(expired.size, n)
    assertEquals(after.pProps.size, 0)
  }

  // ---- the deposits and the withdrawals

  private given EraAccounts[Dijkstra] = EraAccounts.dijkstra

  private def credential(n: Int): Credential[Staking] =
    Credential.KeyHashObj(ok(KeyHash.fromBytes[Staking](bytes(n, 28))))

  private val accounts = ConwayAccounts(
    Sorted.map(
      Vector(
        credential(1) -> ConwayAccountState(
          CompactCoin.fromUnsigned(5),
          CompactCoin.zero,
          None,
          None
        )
      )
    )
  )

  test("returnProposalDeposits pays a registered account and keeps an unregistered one's") {
    val (paid, unclaimed) = ok(
      ConwayEpoch.returnProposalDeposits[Dijkstra](
        Vector(gas(0, info, n = 1), gas(1, info, n = 2)),
        accounts
      )
    )
    assertEquals(paid.states(credential(1)).balance, CompactCoin.fromUnsigned(105))
    assertEquals(unclaimed.toMap, Map(actionId(1) -> Coin(100)))
  }

  test("applyEnactedWithdrawals moves the withdrawals to registered accounts out of the treasury") {
    val dState = DState[ConwayAccounts](
      accounts,
      Sorted.empty,
      Sorted.empty,
      InstantaneousRewards(Sorted.empty, Sorted.empty, DeltaCoin(0), DeltaCoin(0))
    )
    val enact = EnactState
      .empty[Dijkstra](using EraPParams.dijkstra)
      .copy(
        ensTreasury = Coin(50),
        ensWithdrawals = Sorted.map(Vector(credential(1) -> Coin(7), credential(2) -> Coin(11)))
      )
    val (chain, d, reset) =
      ok(
        ConwayEpoch
          .applyEnactedWithdrawals[Dijkstra](ChainAccountState(Coin(1000), Coin(0)), dState, enact)
      )
    assertEquals(chain.treasury, Coin(993))
    assertEquals(d.accounts.states(credential(1)).balance, CompactCoin.fromUnsigned(12))
    assertEquals(reset.ensWithdrawals.size, 0)
    assertEquals(reset.ensTreasury, Coin.zero)
  }

  test("updateNumDormantEpochs counts an epoch with no action still to vote on") {
    val vState = VState(Sorted.empty, CommitteeState(Sorted.empty), EpochNo.fromUnsigned(3))
    val live = proposals(gas(0, info, expiresAfter = 10))
    assertEquals(
      ok(ConwayEpoch.updateNumDormantEpochs(EpochNo.fromUnsigned(10), live, vState)),
      vState
    )
    assertEquals(
      ok(
        ConwayEpoch.updateNumDormantEpochs(EpochNo.fromUnsigned(11), live, vState)
      ).numDormantEpochs,
      EpochNo.fromUnsigned(4)
    )
  }

  // ---- the future parameters

  private val pp = DijkstraCorpus.testnetPParams
  private val other =
    ConwayEraPParams
      .ppCommitteeMinSizeL[Dijkstra](using EraPParams.dijkstra)
      .replace(
        xyz.kd5ujc.scarab.ledger.Unsigned.lit[xyz.kd5ujc.scarab.ledger.Unsigned.Width.W16](9)
      )(pp)

  test("solidifyFuturePParams makes a potential update definite, or none") {
    assertEquals(
      FuturePParams.solidifyFuturePParams(FuturePParams.PotentialPParamsUpdate[Dijkstra](Some(pp))),
      FuturePParams.DefinitePParamsUpdate(pp)
    )
    assertEquals(
      FuturePParams.solidifyFuturePParams(FuturePParams.PotentialPParamsUpdate[Dijkstra](None)),
      FuturePParams.NoPParamsUpdate[Dijkstra]()
    )
    assertEquals(
      FuturePParams.solidifyFuturePParams(FuturePParams.DefinitePParamsUpdate(pp)),
      FuturePParams.DefinitePParamsUpdate(pp)
    )
  }

  test(
    "predictFuturePParams takes the enacted parameters of a parameter change, and nextEpochPParams the known ones"
  ) {
    val empty = ConwayGovState.empty[Dijkstra](using EraPParams.dijkstra).copy(cgsCurPParams = pp)
    def withRatified(enacted: Vector[GovActionState[Dijkstra]], future: FuturePParams[Dijkstra]) =
      empty.copy(
        cgsFuturePParams = future,
        cgsDRepPulsingState = DRepPulsingState.DRComplete(
          PulsingSnapshot.empty[Dijkstra],
          RatifyState
            .empty[Dijkstra](using EraPParams.dijkstra)
            .copy(
              rsEnactState =
                EnactState.empty[Dijkstra](using EraPParams.dijkstra).copy(ensCurPParams = other),
              rsEnacted = enacted
            )
        )
      )
    val potential = FuturePParams.PotentialPParamsUpdate[Dijkstra](None)
    assertEquals(
      ConwayGovState
        .predictFuturePParams(withRatified(Vector(gas(0, change(None))), potential))
        .cgsFuturePParams,
      FuturePParams.PotentialPParamsUpdate(Some(other))
    )
    assertEquals(
      ConwayGovState
        .predictFuturePParams(withRatified(Vector(gas(0, info)), potential))
        .cgsFuturePParams,
      potential
    )
    val none = withRatified(Vector(gas(0, change(None))), FuturePParams.NoPParamsUpdate())
    assertEquals(ConwayGovState.predictFuturePParams(none), none)
    given EraGov[Dijkstra] = EraGov.dijkstra
    assertEquals(
      EraGov.nextEpochPParams[Dijkstra](
        withRatified(Vector.empty, FuturePParams.PotentialPParamsUpdate(Some(other)))
      ),
      pp
    )
    assertEquals(
      EraGov.nextEpochPParams[Dijkstra](
        withRatified(Vector.empty, FuturePParams.DefinitePParamsUpdate(other))
      ),
      other
    )
  }
