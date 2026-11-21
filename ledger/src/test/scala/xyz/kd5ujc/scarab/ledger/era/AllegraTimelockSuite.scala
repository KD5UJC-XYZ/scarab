// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{PreprodDeepTx, testByteStrings}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayTimelock, ConwayTx}
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.protocol.StateLayout

/** Allegra's native-script evaluation on the era types (`AllegraScripts.evalTimelock`,
  * `AllegraTx.validateTimelock`, Conway's `EraTx#validateNativeScript`; cardano-ledger f649f975
  * `Allegra/Scripts.hs:477-497`, `Allegra/Tx.hs:104-110`) against the flat evaluation the node runs
  * (`NativeScript.eval`): every form, each validity bound unbounded, inside and outside, and `m` of
  * a list at, below and above the scripts that hold, zero and negative among them; the scripts it
  * evaluates, which are the reference's, in its order (`Allegra/Scripts.hs:487-495`); and, on a
  * small stack, a script nesting each kind of list 2,000 times and the preprod transaction whose
  * script nests 5,383 levels.
  */
class AllegraTimelockSuite extends munit.FunSuite:
  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def leaf(v: Value): Node = Node(v, Bytes.empty)
  private def uint(n: BigInt): Node = leaf(Value.UInt(n))
  private def arr(xs: Node*): Node = leaf(Value.Arr(xs.toVector))
  private def key(b: Int): Bytes = Bytes(Vector.fill(28)(b.toByte))

  private def sig(b: Int): Node = arr(uint(0), leaf(Value.ByteString(key(b))))
  private def allOf(xs: Node*): Node = arr(uint(1), arr(xs*))
  private def anyOf(xs: Node*): Node = arr(uint(2), arr(xs*))
  private def mOf(m: Int, xs: Node*): Node =
    arr(uint(3), if m < 0 then leaf(Value.NInt(BigInt(m))) else uint(m), arr(xs*))
  private def start(s: Int): Node = arr(uint(4), uint(s))
  private def expire(s: Int): Node = arr(uint(5), uint(s))

  /** The script read as Conway reads it, from its encoding. */
  private def timelock(n: Node): Timelock =
    val bytes = right("encode")(Cbor.encode(n.value))
    right("decode")(ConwayTimelock.decode(right("read")(Cbor.decode(bytes)))).rep

  private val scripts: Vector[Node] = Vector(
    sig(1),
    sig(9),
    start(10),
    expire(10),
    allOf(),
    anyOf(),
    allOf(sig(1), start(5)),
    anyOf(sig(9), expire(20)),
    mOf(0),
    mOf(0, sig(9)),
    mOf(-1),
    mOf(-1, sig(9)),
    mOf(-3, sig(9), sig(1)),
    mOf(1, sig(9), sig(1)),
    mOf(2, sig(9), sig(1)),
    mOf(2, sig(1), sig(2), sig(9)),
    allOf(anyOf(sig(9), mOf(1, start(10), expire(30))), sig(2))
  )

  private val intervals: Vector[ValidityInterval] =
    for
      s <- Vector(None, Some(BigInt(5)), Some(BigInt(10)), Some(BigInt(15)))
      e <- Vector(None, Some(BigInt(10)), Some(BigInt(20)), Some(BigInt(30)))
    yield ValidityInterval(s, e)

  test("evalTimelock gives the flat evaluation's verdict on every form and bound") {
    val signers = Set(key(1), key(2))
    val verdicts = for
      n <- scripts
      vi <- intervals
    yield
      val t = timelock(n)
      val ours = AllegraScripts.evalTimelock(signers, vi, t)
      assertEquals(
        ours,
        t.script.eval(signers, vi.invalidBefore, vi.invalidHereafter),
        s"${t.script} in $vi"
      )
      ours
    assert(verdicts.contains(true) && verdicts.contains(false))
  }

  /** The key hashes `evalTimelock` asks about, in the order it asks, evaluating `n` for the signers
    * `1`, `2` and `3`, and its verdict.
    */
  private def asked(n: Node): (Vector[Int], Boolean) =
    val signers = Set(1, 2, 3).map(key)
    val seen = scala.collection.mutable.ArrayBuffer.empty[Bytes]
    val probe = new scala.collection.immutable.AbstractSet[Bytes]:
      def contains(h: Bytes): Boolean =
        seen += h
        signers.contains(h)
      def iterator: Iterator[Bytes] = signers.iterator
      def incl(h: Bytes): Set[Bytes] = signers.incl(h)
      def excl(h: Bytes): Set[Bytes] = signers.excl(h)
    val holds = AllegraScripts.evalTimelock(probe, ValidityInterval(None, None), timelock(n))
    (seen.toVector.map(_.headOption.fold(-1)(_.toInt)), holds)

  test("evalTimelock evaluates the scripts the reference evaluates, in order, and stops with it") {
    // `all go xs` stops at the first script that fails, `any go xs` at the first that holds, and
    // `isValidMOf` once `m` hold, evaluating none for a non-positive `m` and every one while fewer
    // than `m` have held (`Allegra/Scripts.hs:487-495`, cardano-ledger f649f975); keys 1 to 3
    // sign, 7 to 9 do not
    val cases = Vector(
      allOf(sig(7), sig(1)) -> (Vector(7), false),
      allOf(sig(1), sig(7), sig(2)) -> (Vector(1, 7), false),
      allOf(sig(1), sig(2), anyOf(sig(7), sig(3))) -> (Vector(1, 2, 7, 3), true),
      anyOf(sig(7), sig(1), sig(8)) -> (Vector(7, 1), true),
      anyOf(sig(7), sig(8)) -> (Vector(7, 8), false),
      mOf(1, sig(1), sig(2)) -> (Vector(1), true),
      mOf(2, sig(7), sig(1), sig(8), sig(2), sig(3)) -> (Vector(7, 1, 8, 2), true),
      mOf(2, sig(7), sig(8), sig(1)) -> (Vector(7, 8, 1), false),
      mOf(0, sig(1), sig(7)) -> (Vector(), true),
      mOf(-1, sig(1), sig(7)) -> (Vector(), true),
      mOf(-1) -> (Vector(), true),
      mOf(1) -> (Vector(), false),
      allOf(sig(7), anyOf(sig(1))) -> (Vector(7), false),
      anyOf(allOf(sig(7), sig(8)), sig(1), sig(2)) -> (Vector(7, 1), true),
      mOf(1, allOf(sig(1), sig(7)), anyOf(sig(8), sig(2)), sig(3)) -> (Vector(1, 7, 8, 2), true)
    )
    cases.foreach((n, expected) => assertEquals(asked(n), expected, timelock(n).script.toString))
  }

  test("evalTimelock on a script of 6,000 levels, each list in turn, on a small stack") {
    // the preprod script nests one form; this one nests all of a list, any of a list and one of a
    // list in turn around a single signature, so each of the three walks runs deep
    def nested(inner: Node): Node =
      (0 until 6000).foldLeft(inner)((n, i) =>
        i % 3 match
          case 0 => allOf(n)
          case 1 => anyOf(n)
          case _ => mOf(1, n)
      )
    Vector(sig(1) -> true, sig(7) -> false).foreach { (inner, holds) =>
      val t = PreprodDeepTx.onSmallStack(timelock(nested(inner)))
      assertEquals(
        PreprodDeepTx.onSmallStack(
          AllegraScripts.evalTimelock(Set(key(1)), ValidityInterval(None, None), t)
        ),
        holds
      )
    }
  }

  test("validateNativeScript on the preprod script of 5,383 levels, on a small stack") {
    val tx =
      PreprodDeepTx.onSmallStack(ConwayTx.fromEnvelope(PreprodDeepTx.bytes, StateLayout.Node11_1))
    val conway = right("the preprod transaction")(tx)
    val flat = PreprodDeepTx.parsed
    val t = EraTx.conway
    val wits = t.wits[TxLevel.Top](conway)(using t.bodyOf.top)
    val natives =
      t.witsOf.scriptTxWits(wits).values.toVector.flatMap(t.witsOf.scriptsOf.getNativeScript)
    assert(natives.nonEmpty)
    val signers =
      flat.witnesses.vkeys.map(w => xyz.kd5ujc.scarab.ledger.Verdicts.Direct.keyHash(w._1)).toSet
    natives.foreach { native =>
      assertEquals(
        PreprodDeepTx.onSmallStack(
          t.validateNativeScript[TxLevel.Top](conway, native)(using t.bodyOf.top)
        ),
        PreprodDeepTx.onSmallStack(
          native.rep.script.eval(signers, flat.body.validityStart, flat.body.ttl)
        )
      )
    }
  }

  test(
    "validateFailedBabbageScripts reports a needed native script that fails, as the flat check"
  ) {
    // on the preprod transaction, its signers and its interval: a script no witness signs fails,
    // one a witness signs holds; a failing script provided but not needed is no failure
    val conway = right("the preprod transaction")(
      PreprodDeepTx.onSmallStack(ConwayTx.fromEnvelope(PreprodDeepTx.bytes, StateLayout.Node11_1))
    )
    val flatTx = PreprodDeepTx.parsed
    val t = EraTx.conway
    val s = EraScript.conway
    val signer = flatTx.witnesses.vkeys
      .map(w => xyz.kd5ujc.scarab.ledger.Verdicts.Direct.keyHash(w._1))
      .headOption
      .getOrElse(fail("no key witness"))
    def signed(by: Bytes): Node = arr(uint(0), leaf(Value.ByteString(by)))
    val failing = timelock(sig(9))
    val holding = timelock(signed(signer))
    val scripts = Vector(failing, holding).map(rep =>
      val native = right("native")(ConwayTimelock.decode(right("read")(Cbor.decode(rep.raw))))
      s.hashScript(s.fromNativeScript(native)) -> (s.fromNativeScript(native), rep)
    )
    val provided = ScriptsProvided[Conway](scripts.map((h, p) => h -> p._1).toMap)
    val flatScripts = scripts
      .map((h, p) =>
        h -> xyz.kd5ujc.scarab.ledger.Script(
          xyz.kd5ujc.scarab.ledger.ScriptKind.Native,
          h,
          Some(p._2.script)
        )
      )
      .toMap
    val signers = flatTx.witnesses.vkeys
      .map(w => xyz.kd5ujc.scarab.ledger.Verdicts.Direct.keyHash(w._1))
      .toSet
    val hashes = scripts.map(_._1)
    val verdicts = Vector(hashes.toSet, Set(hashes(1)), Set.empty[Bytes]).map { needed =>
      val ours = EraUtxoRun
        .utxowFlat(
          rules.BabbageUtxow
            .validateFailedBabbageScripts[Conway, TxLevel.Top](conway, provided, needed)(using
              t,
              t.bodyOf.top
            )
        )(EraUtxoRun.shelleyW)
        .getOrElse(fail("no flat form"))
      assertEquals(
        Right(ours),
        xyz.kd5ujc.scarab.ledger.TxChecks
          .failedNativeScripts(flatTx, flatScripts, Some(needed), signers),
        s"needed $needed"
      )
      ours
    }
    assertEquals(
      verdicts.map(_.isValid),
      Vector(false, true, true),
      "only a needed failing script fails"
    )
  }
