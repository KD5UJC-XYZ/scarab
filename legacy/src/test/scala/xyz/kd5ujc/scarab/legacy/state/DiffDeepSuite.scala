// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import xyz.kd5ujc.scarab.legacy as flat
import xyz.kd5ujc.scarab.legacy.{LedgerImage, LedgerMirror}
import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import cats.syntax.all.*
import monocle.Lens
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

/** `Diff` on populated states. The generators of the state trees keep their certificate and
  * governance records empty, so the deep paths are tried on the images of the node's own generated
  * states (accounts, pools, DReps, snapshots), which have entries: an edit made through a lens at a
  * place is found at that place, and nowhere else.
  */
class DiffDeepSuite extends ScalaCheckSuite:
  type Result[A] = Either[MirrorError, A]
  type M[A] = Kleisli[Result, MirrorEnv, A]

  private val mirror: LedgerMirror[M] = LedgerMirror[M]
  private val run: FunctionK[M, Result] = new FunctionK[M, Result]:
    def apply[A](fa: M[A]): Result[A] = fa.run(FlatStateGen.env)

  private val images: Gen[LedgerImage[Conway]] =
    FlatStateGen
      .state(conway = true)
      .flatMap(s =>
        run(mirror.to[Conway](WithOrigin.Origin, s)).fold(_ => Gen.fail, i => Gen.const(i.value))
      )

  private def edit[B](lens: Lens[NewEpochState[Conway], B], prefix: String): Prop =
    forAll(images, images) { (a, b) =>
      val edited = a.copy(nes = lens.replace(lens.get(b.nes))(a.nes))
      a.firstDifference(edited) match
        case None    => a === edited
        case Some(d) => !(a === edited) && d.path.render.startsWith(s"nes.$prefix")
    }

  property("pools")(edit(ConwayNewEpochState.pools, "es.ls.cert.pState.stakePools"))
  property("DReps")(edit(ConwayNewEpochState.dreps, "es.ls.cert.vState.dreps"))
  property("accounts")(edit(ConwayNewEpochState.accounts, "es.ls.cert.dState.accounts"))
  property("governance")(edit(ConwayNewEpochState.gov, "es.ls.utxo.gov"))
  property("snapshots")(edit(ConwayNewEpochState.snapshots, "es.snapshots"))
  property("treasury")(edit(ConwayNewEpochState.treasury, "es.chainAccount.treasury"))

  property("no difference exactly when equal, on populated images") {
    forAll(images, images)((a, b) => a.firstDifference(b).isEmpty == (a === b))
  }

  test("the edits are real: each place differs between some pair of images") {
    val pairs = List.fill(60)((images.sample, images.sample)).collect { case (Some(a), Some(b)) =>
      (a.nes, b.nes)
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
    val accounts = List.fill(20)(images.sample).flatten.flatMap { image =>
      ConwayNewEpochState.accounts.get(image.nes).states.headOption.map(image -> _)
    }
    assert(accounts.nonEmpty, "no account in twenty images")
    accounts.foreach { case (image, (credential, state)) =>
      val drep = if state.drep.isEmpty then Some(DRep.DRepAlwaysAbstain) else None
      val changed = ConwayNewEpochState.accounts.modify(a =>
        ConwayAccounts(a.states.updated(credential, state.copy(drep = drep)))
      )(image.nes)
      val path = image.nes.firstDifference(changed).map(_.path.render)
      assert(
        path.exists(p =>
          p.startsWith("es.ls.cert.dState.accounts.states[") && p.endsWith("].drep")
        ),
        path.toString
      )
    }
  }
