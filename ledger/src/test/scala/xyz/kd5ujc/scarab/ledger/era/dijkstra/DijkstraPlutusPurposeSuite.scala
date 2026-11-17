// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{CborRead, Credential as FlatCredential, Unsigned, Voter}
import xyz.kd5ujc.scarab.ledger.era.{AsItem, AsIx, EraScript, EraTxCert}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPlutusPurpose
import xyz.kd5ujc.scarab.ledger.state.{EpochNo, KeyHash, ScriptHash, StakePool}

/** Dijkstra's purposes ([[DijkstraPlutusPurpose]], cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:122-129`) and its script class
  * (`ConwayEraScript DijkstraEra` and `DijkstraEraScript DijkstraEra`, `Scripts.hs:556-581`): each
  * builder's purpose is read back by its own reader only, in each form; the redeemer tags and order
  * are the reference's; a Conway redeemer key is carried in unchanged; and the guard of a native
  * script is the one it was read with.
  */
class DijkstraPlutusPurposeSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)
  private def w32(n: Int): Unsigned[Unsigned.Width.W32] =
    Unsigned.from[Unsigned.Width.W32](BigInt(n)).getOrElse(fail(s"$n is no Word32"))

  private val scripts = EraScript.dijkstra
  private val guard = ScriptHash.fromBytes(hex("22" * 28)).fold(e => fail(e.toString), identity)

  import DijkstraPlutusPurpose.*

  // One purpose of each constructor, in the reference's order.
  private val all: Vector[DijkstraPlutusPurpose[AsIx]] = Vector(
    DijkstraSpending(AsIx(w32(0))),
    DijkstraMinting(AsIx(w32(0))),
    DijkstraCertifying(AsIx(w32(0))),
    DijkstraWithdrawing(AsIx(w32(0))),
    DijkstraVoting(AsIx(w32(0))),
    DijkstraProposing(AsIx(w32(0))),
    DijkstraGuarding(AsIx(w32(0)))
  )

  test("the redeemer tags are spend 0 to guard 6, as the encoder writes them") {
    // `EncCBORGroup (DijkstraPlutusPurpose f era)` (`Scripts.hs:184-199`)
    assertEquals(all.map(_.tag), Vector(0, 1, 2, 3, 4, 5, 6))
  }

  test("the guarding, voting and proposing purposes build and read back, and only those") {
    val ix = w32(3)
    val guarding = scripts.mkGuardingPurpose[AsIx](AsIx(ix))
    assertEquals(guarding, DijkstraGuarding[AsIx](AsIx(ix)))
    assertEquals(scripts.toGuardingPurpose[AsIx](guarding), Some(AsIx(ix)))
    val voting = scripts.mkVotingPurpose[AsIx](AsIx(ix))
    assertEquals(voting, DijkstraVoting[AsIx](AsIx(ix)))
    assertEquals(scripts.toVotingPurpose[AsIx](voting), Some(AsIx(ix)))
    val proposing = scripts.mkProposingPurpose[AsIx](AsIx(ix))
    assertEquals(proposing, DijkstraProposing[AsIx](AsIx(ix)))
    assertEquals(scripts.toProposingPurpose[AsIx](proposing), Some(AsIx(ix)))
    all.foreach { p =>
      assertEquals(scripts.toGuardingPurpose[AsIx](p).isDefined, p.tag == 6, p.toString)
      assertEquals(scripts.toVotingPurpose[AsIx](p).isDefined, p.tag == 4, p.toString)
      assertEquals(scripts.toProposingPurpose[AsIx](p).isDefined, p.tag == 5, p.toString)
    }
    // and with the item
    val item = scripts.mkGuardingPurpose[AsItem](AsItem(guard))
    assertEquals(scripts.toGuardingPurpose[AsItem](item), Some(AsItem(guard)))
    val voter = Voter.Pool(hex("50" * 28))
    val vote = scripts.mkVotingPurpose[AsItem](AsItem(voter))
    assertEquals(scripts.toVotingPurpose[AsItem](vote), Some(AsItem(voter)))
    assertEquals(scripts.toGuardingPurpose[AsItem](vote), None)
    // a certifying purpose holds a Dijkstra certificate
    val pk = KeyHash.fromBytes[StakePool](hex("50" * 28)).fold(e => fail(e.toString), identity)
    val cert = EraTxCert.dijkstra.mkRetirePoolTxCert(pk, EpochNo.fromUnsigned(10))
    assertEquals(
      scripts.toGuardingPurpose[AsItem](DijkstraCertifying[AsItem](AsItem(cert))),
      None
    )
  }

  test("redeemer keys order by constructor, then index, as the derived Ord") {
    // `Ord (DijkstraPlutusPurpose AsIx era)` (`Scripts.hs:260`)
    val keys = Vector(
      DijkstraGuarding[AsIx](AsIx(w32(0))),
      DijkstraSpending[AsIx](AsIx(w32(5))),
      DijkstraVoting[AsIx](AsIx(w32(2))),
      DijkstraSpending[AsIx](AsIx(w32(1)))
    )
    assertEquals(
      keys.sorted.map(p => (p.tag, p.ix.value)),
      Vector((0, BigInt(1)), (0, BigInt(5)), (4, BigInt(2)), (6, BigInt(0)))
    )
  }

  test("a Conway redeemer key is carried into Dijkstra with its purpose and index") {
    // `upgradePlutusPurposeAsIx` (`Scripts.hs:528-534`)
    val conway = Vector[ConwayPlutusPurpose[AsIx]](
      ConwayPlutusPurpose.ConwaySpending(AsIx(w32(1))),
      ConwayPlutusPurpose.ConwayMinting(AsIx(w32(2))),
      ConwayPlutusPurpose.ConwayCertifying(AsIx(w32(3))),
      ConwayPlutusPurpose.ConwayWithdrawing(AsIx(w32(4))),
      ConwayPlutusPurpose.ConwayVoting(AsIx(w32(5))),
      ConwayPlutusPurpose.ConwayProposing(AsIx(w32(6)))
    )
    val dijkstra = conway.map(DijkstraPlutusPurpose.translate)
    assertEquals(dijkstra.map(_.tag), conway.map(_.tag))
    assertEquals(dijkstra.map(_.ix), conway.map(p => ConwayPlutusPurpose.ix(p)))
  }

  test("a native script's guard is read through the class, and no other form has one") {
    def native(h: String): DijkstraNativeScript =
      CborRead
        .decode(hex(h))
        .left
        .map(_.toString)
        .flatMap(DijkstraNativeScript.decode(_).left.map(_.toString))
        .fold(fail(_), identity)
    // `[6, credential]`, `DijkstraRequireGuard` (`Scripts.hs:270-277, 666-668`)
    val guarded = native("8206" + "8201581c" + "22" * 28)
    assertEquals(scripts.getRequireGuard(guarded), Some(FlatCredential.Script(hex("22" * 28))))
    assertEquals(scripts.getRequireSignature(guarded), None)
    val signed = native("8200581c" + "4b" * 28)
    assertEquals(scripts.getRequireGuard(signed), None)
  }
