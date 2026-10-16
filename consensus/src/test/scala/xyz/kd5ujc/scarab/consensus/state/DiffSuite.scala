// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import cats.syntax.all.*
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.consensus.state.StateGen.given
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.VocabularyGen.given

/** `Diff`, the first difference of two values by its path: it finds a difference exactly where `Eq`
  * does, names the same place from either side, and the path of an edit made through a lens is the
  * path of the lens.
  */
class DiffSuite extends ScalaCheckSuite:
  private def paths[A: Diff](a: A, b: A): (Option[String], Option[String]) =
    (a.firstDifference(b).map(_.path.render), b.firstDifference(a).map(_.path.render))

  property("no difference exactly when equal: the new-epoch state of Conway") {
    forAll { (a: NewEpochState[Conway], b: NewEpochState[Conway]) =>
      a.firstDifference(b).isEmpty == (a === b) && a.firstDifference(a).isEmpty
    }
  }

  property("no difference exactly when equal: the whole extended ledger state") {
    forAll { (a: ExtLedgerState, b: ExtLedgerState) =>
      a.firstDifference(b).isEmpty == (a === b) && a.firstDifference(a).isEmpty
    }
  }

  property("no difference exactly when equal: a ledger state of any one era") {
    forAll { (a: EraLedgerState, b: EraLedgerState) =>
      a.firstDifference(b).isEmpty == (a === b)
    }
  }

  property("either side names the same place") {
    forAll { (a: ExtLedgerState, b: ExtLedgerState) =>
      val (ab, ba) = paths(a, b)
      ab == ba
    }
  }

  property("an edit through a lens is found at the path of the lens") {
    forAll { (a: NewEpochState[Conway], c: Coin) =>
      val edited = ConwayNewEpochState.treasury.replace(c)(a)
      a.firstDifference(edited).map(_.path.render) ==
        Option.when(!(a === edited))("es.chainAccount.treasury")
    }
  }

  test("a path renders fields, keys and positions") {
    val nested = Path.root / Step.Field("nes") / Step.Field("accounts") / Step.Key("ab12") /
      Step.Field("balance") / Step.Index(3)
    assertEquals(nested.render, "nes.accounts[ab12].balance[3]")
  }

  test("sequences, options, sets and maps name the first place they differ") {
    assertEquals(
      Vector(1, 2, 3).firstDifference(Vector(1, 9, 3)).map(_.path.render),
      Some("[1]")
    )
    assertEquals(
      Vector(1, 2).firstDifference(Vector(1, 2, 3)).map(d => (d.path.render, d.left, d.right)),
      Some(("[2]", "<absent>", "3"))
    )
    assertEquals(
      Option(1).firstDifference(None).map(d => (d.left, d.right)),
      Some(("Some(1)", "None"))
    )
    assertEquals(
      Set(1, 2).firstDifference(Set(1, 3)).map(_.path.render),
      Some("{2}")
    )
    val a = SortedMap(1 -> "x", 2 -> "y", 3 -> "z")
    assertEquals(a.firstDifference(a.updated(2, "w")).map(_.path.render), Some("[2]"))
    assertEquals(
      a.firstDifference(a - 3).map(d => (d.path.render, d.right)),
      Some(("[3]", "<absent>"))
    )
    assertEquals(
      (a - 1).firstDifference(a).map(d => (d.path.render, d.left)),
      Some(("[1]", "<absent>"))
    )
  }

  test("a generic record derives, and names its field") {
    val m = Mirrored(7, Set.empty[Gap])
    assertEquals(m.firstDifference(m.copy(value = 8)).map(_.path.render), Some("value"))
    assertEquals(
      m.firstDifference(m.copy(gaps = Set(Gap.NonMyopic))).map(_.path.render),
      Some("gaps{NonMyopic}")
    )
  }

  test("an enum names the case where the cases differ, and the field where they agree") {
    val d = Gap.NonMyopic.firstDifference(Gap.RewardAttribution)
    assertEquals(d.map(_.path.render), Some(""))
    assert(d.exists(_.left.contains("NonMyopic")), d.toString)
  }
