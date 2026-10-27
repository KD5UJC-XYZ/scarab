// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.syntax.all.*
import monocle.Lens
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen, Prop}
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.ledger.state.EpochStateGen.given

/** `Diff` on populated states: the native generators fill the certificate records (accounts with
  * their delegations, pools, DReps) and the snapshots, so the deep paths are tried on states that
  * have entries: an edit made through a lens at a place is found at that place, and nowhere else.
  */
class DiffDeepSuite extends ScalaCheckSuite:
  private val states: Gen[NewEpochState[Conway]] = Arbitrary.arbitrary[NewEpochState[Conway]]

  private def edit[B](lens: Lens[NewEpochState[Conway], B], prefix: String): Prop =
    forAll(states, states) { (a, b) =>
      val edited = lens.replace(lens.get(b))(a)
      a.firstDifference(edited) match
        case None    => a === edited
        case Some(d) => !(a === edited) && d.path.render.startsWith(prefix)
    }

  property("pools")(edit(ConwayNewEpochState.pools, "es.ls.cert.pState.stakePools"))
  property("DReps")(edit(ConwayNewEpochState.dreps, "es.ls.cert.vState.dreps"))
  property("accounts")(edit(ConwayNewEpochState.accounts, "es.ls.cert.dState.accounts"))
  property("governance")(edit(ConwayNewEpochState.gov, "es.ls.utxo.gov"))
  property("snapshots")(edit(ConwayNewEpochState.snapshots, "es.snapshots"))
  property("treasury")(edit(ConwayNewEpochState.treasury, "es.chainAccount.treasury"))

  property("no difference exactly when equal, on populated states") {
    forAll(states, states)((a, b) => a.firstDifference(b).isEmpty == (a === b))
  }

  test("the edits are real: each place differs between some pair of states") {
    val pairs = List.fill(60)((states.sample, states.sample)).collect { case (Some(a), Some(b)) =>
      (a, b)
    }
    def differs[B](lens: Lens[NewEpochState[Conway], B]) =
      pairs.exists((a, b) => !(a === lens.replace(lens.get(b))(a)))
    assert(differs(ConwayNewEpochState.pools), "pools")
    assert(differs(ConwayNewEpochState.dreps), "dreps")
    assert(differs(ConwayNewEpochState.accounts), "accounts")
    assert(differs(ConwayNewEpochState.snapshots), "snapshots")
    assert(differs(ConwayNewEpochState.treasury), "treasury")
  }

  test("an account's DRep is named down to the field") {
    val accounts = List.fill(20)(states.sample).flatten.flatMap { nes =>
      ConwayNewEpochState.accounts.get(nes).states.headOption.map(nes -> _)
    }
    assert(accounts.nonEmpty, "no account in twenty states")
    accounts.foreach { case (nes, (credential, state)) =>
      val drep = if state.drep.isEmpty then Some(DRep.DRepAlwaysAbstain) else None
      val changed = ConwayNewEpochState.accounts.modify(a =>
        ConwayAccounts(a.states.updated(credential, state.copy(drep = drep)))
      )(nes)
      val path = nes.firstDifference(changed).map(_.path.render)
      assert(
        path.exists(p =>
          p.startsWith("es.ls.cert.dState.accounts.states[") && p.endsWith("].drep")
        ),
        path.toString
      )
    }
  }
