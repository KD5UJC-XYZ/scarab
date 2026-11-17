// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{CborRead, Credential, NativeScript, PreprodDeepTx}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScript as FlatDijkstraScript

/** `evalDijkstraNativeScript` ([[DijkstraNativeScript.eval]], `Scripts.hs:594-615`, cardano-ledger
  * 9df9f98c): each form as the reference evaluates it, a guard among the body's guards or not, and
  * a guard-free script as the time-lock language evaluates it ([[NativeScript.eval]]), which a
  * script carried into Dijkstra keeps (`upgradeTimelock`).
  */
class DijkstraNativeScriptEvalSuite extends ScalaCheckSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def script(raw: Bytes): DijkstraNativeScript =
    CborRead.decode(raw).flatMap(DijkstraNativeScript.read).fold(e => fail(e.toString), identity)

  private val signer = Bytes(Vector.fill(28)(0x22.toByte))
  private val guardKey = Bytes(Vector.fill(28)(0x33.toByte))
  private val sig = "8200581c" + "22" * 28
  // `[6, [0, keyhash]]`: the key credential of `guardKey` as a guard
  private val guard = "8206" + "8200581c" + "33" * 28
  private val guards = Set[Credential](Credential.Key(guardKey))

  private def holds(
      raw: String,
      signers: Set[Bytes] = Set.empty,
      start: Option[BigInt] = None,
      end: Option[BigInt] = None,
      gs: Set[Credential] = Set.empty
  ): Boolean = script(hex(raw)).eval(signers, start, end, gs)

  test("a guard holds when the body has it, by its credential") {
    assert(holds(guard, gs = guards))
    assert(!holds(guard))
    // a script credential with the same hash is another credential
    assert(!holds(guard, gs = Set(Credential.Script(guardKey))))
  }

  test("any of a guard and a signature holds by the signature without the guard") {
    val anyOf = "8202" + "82" + guard + sig
    assert(holds(anyOf, signers = Set(signer)))
    assert(holds(anyOf, gs = guards))
    assert(!holds(anyOf))
    val allOf = "8201" + "82" + guard + sig
    assert(holds(allOf, signers = Set(signer), gs = guards))
    assert(!holds(allOf, signers = Set(signer)))
  }

  test("m of a list holds when m of its scripts do, and a non-positive m always") {
    def mOf(m: String) = "8303" + m + "82" + guard + sig
    assert(holds(mOf("02"), signers = Set(signer), gs = guards))
    assert(!holds(mOf("02"), signers = Set(signer)))
    assert(holds(mOf("01"), signers = Set(signer)))
    assert(holds(mOf("00")))
    // -1
    assert(holds(mOf("20")))
    // of an empty list
    assert(holds("8303" + "00" + "80"))
    assert(!holds("8303" + "01" + "80"))
  }

  test("a validity start or end holds within a bounded interval only") {
    // `[4, 10]`: valid from slot 10; `[5, 20]`: valid until slot 20
    assert(holds("82040a", start = Some(10)))
    assert(!holds("82040a", start = Some(9)))
    assert(!holds("82040a"))
    assert(holds("820514", end = Some(20)))
    assert(!holds("820514", end = Some(21)))
    assert(!holds("820514"))
  }

  // ---- guard-free scripts evaluate as the time-lock language does

  private val keys = Vector.tabulate(3)(i => Bytes(Vector.fill(28)((0x40 + i).toByte)))

  private def genTimelock(depth: Int): Gen[NativeScript] =
    val leaf = Gen.oneOf(
      Gen.oneOf(keys).map(NativeScript.Sig(_)),
      Gen.choose(0, 30).map(s => NativeScript.InvalidBefore(BigInt(s))),
      Gen.choose(0, 30).map(s => NativeScript.InvalidHereafter(BigInt(s)))
    )
    if depth <= 0 then leaf
    else
      val subs = Gen.choose(0, 3).flatMap(Gen.listOfN(_, genTimelock(depth - 1))).map(_.toVector)
      Gen.frequency(
        3 -> leaf,
        1 -> subs.map(NativeScript.AllOf(_)),
        1 -> subs.map(NativeScript.AnyOf(_)),
        1 -> Gen.zip(Gen.choose(-1, 3), subs).map((m, ss) => NativeScript.NOfK(BigInt(m), ss))
      )

  private val genSlot: Gen[Option[BigInt]] = Gen.option(Gen.choose(0, 30).map(BigInt(_)))

  property("a guard-free script evaluates as its time-lock tree does") {
    Prop.forAll(
      genTimelock(3),
      Gen.someOf(keys).map(_.toSet),
      genSlot,
      genSlot
    ) { (tree, signers, start, end) =>
      val carried = script(FlatDijkstraScript.upgradeTimelock(tree))
      carried.timelock.isDefined &&
      carried.eval(signers, start, end, guards) == tree.eval(signers, start, end)
    }
  }

  test("a script as deep as a block evaluates on a small stack") {
    // `[1, [[1, [ … [6, guard] … ]]]]`, 20,000 levels
    val deep = script(hex(("8201" + "81").repeat(20000) + guard))
    assertEquals(PreprodDeepTx.onSmallStack(deep.eval(Set.empty, None, None, guards)), true)
    assertEquals(PreprodDeepTx.onSmallStack(deep.eval(Set.empty, None, None, Set.empty)), false)
  }
