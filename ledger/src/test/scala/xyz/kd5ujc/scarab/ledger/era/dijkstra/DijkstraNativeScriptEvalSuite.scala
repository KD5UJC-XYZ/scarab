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

  // `validateNativeScript` of Dijkstra's transaction (`validateDijkstraNativeScript`,
  // `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs:427-438`, cardano-ledger 9df9f98c): the
  // script for the key hashes of the transaction's key witnesses (`witVKeyHash` of `addrTxWitsL`),
  // its body's validity interval and its body's guards (`guardsTxBodyL`)
  test("validateNativeScript reads the witnesses, the interval and the guards of the transaction") {
    import xyz.kd5ujc.scarab.ledger.Verdicts
    import xyz.kd5ujc.scarab.ledger.dijkstra.V12
    import xyz.kd5ujc.scarab.ledger.state.FlatView
    import xyz.kd5ujc.scarab.ledger.era.{EraTx, EraTxBody, TxLevel}
    def bytesHex(b: Bytes): String = b.hex
    // the body's guards, as the node's credentials, in their order
    def guardsOf(e: DijkstraTx[TxLevel.Top]): Vector[Credential] =
      EraTxBody.dijkstra
        .guards[TxLevel.Top](e.body)(using xyz.kd5ujc.scarab.ledger.era.BothLevels.Top)
        .toVector
        .map(FlatView.credBack(_))
    def uint(n: BigInt): String =
      if n < 24 then f"${n.toInt}%02x"
      else if n < 256 then f"18${n.toInt}%02x"
      else if n < 65536 then f"19${n.toInt}%04x"
      else if n < BigInt(1L << 32) then f"1a${n.toLong}%08x"
      else f"1b${n.toLong}%016x"
    def credential(c: Credential): String = c match
      case Credential.Key(h)    => "8200581c" + bytesHex(h)
      case Credential.Script(h) => "8201581c" + bytesHex(h)
    // every transaction of the Leios corpus the decoder reads, with guards and key witnesses, and
    // each again with a key guard added to its body (the corpus's guards are scripts')
    import xyz.kd5ujc.scarab.codec.{Cbor, Node, Value}
    val keyGuard = Credential.Key(Bytes(Vector.fill(28)(0x5e.toByte)))
    def leaf(v: Value): Node = Node(v, Bytes.empty)
    def withKeyGuard(n: Node): Option[Node] = n.value match
      case Value.Arr(parts) =>
        parts.headOption.map(_.value).collect { case Value.Map(fields) =>
          val guard = leaf(
            Value.Arr(Vector(leaf(Value.UInt(0)), leaf(Value.ByteString(keyGuard.hash))))
          )
          val edited = fields.map { (k, v) =>
            if k.value == Value.UInt(14) then
              k -> (v.value match
                case Value.Tag(tag, inner) =>
                  inner.value match
                    case Value.Arr(gs) => leaf(Value.Tag(tag, leaf(Value.Arr(gs :+ guard))))
                    case _             => v
                case Value.Arr(gs) => leaf(Value.Arr(gs :+ guard))
                case _             => v)
            else k -> v
          }
          val raw = Cbor
            .encode(Value.Arr(leaf(Value.Map(edited)) +: parts.tail), Cbor.Limits.Unbounded)
            .fold(e => fail(e.toString), identity)
          CborRead.decode(raw, V12.nestedLimits).fold(e => fail(e.toString), identity)
        }
      case _ => None
    val read = DijkstraCorpus.leiosCorpus
      .filter(j => DijkstraCorpus.str(j, "kind") == "tx")
      .flatMap(j => CborRead.decode(hex(DijkstraCorpus.str(j, "bytes")), V12.nestedLimits).toOption)
    val txs = (read ++ read.flatMap(withKeyGuard))
      .flatMap(n => DijkstraTx.inBlock(n).toOption)
      .filter(e => guardsOf(e).nonEmpty && e.wits.vkeys.nonEmpty)
    assert(txs.nonEmpty, "no transaction with guards and key witnesses")
    val t = EraTx.dijkstra
    val kinds = txs.flatMap { era =>
      val signers = era.wits.vkeys.toVector.map(w => Verdicts.Direct.keyHash(w.vkey)).toSet
      val guards = guardsOf(era).toSet
      val vi = EraTxBody.dijkstra.vldt[TxLevel.Top](era.body)(using t.bodyOf.top)
      val signer = signers.head
      val absent = Credential.Key(Bytes(Vector.fill(28)(0x7f.toByte)))
      val bound = vi.invalidBefore.orElse(vi.invalidHereafter).getOrElse(BigInt(100))
      // the interval's end, and the slot before it: an expiry at the end holds, one before does not
      val end = vi.invalidHereafter.getOrElse(bound)
      val scripts = guardsOf(era).map(g => "8206" + credential(g)) ++ Vector(
        "8206" + credential(absent),
        "8200581c" + bytesHex(signer),
        "8204" + uint(bound),
        "8205" + uint(bound),
        "8205" + uint(end),
        "8205" + uint((end - 1).max(BigInt(0))),
        "8303" + "02" + "83" + ("8206" + credential(guardsOf(era).head)) +
          ("8200581c" + bytesHex(signer)) + ("8206" + credential(absent)),
        "8303" + "03" + "83" + ("8206" + credential(guardsOf(era).head)) +
          ("8200581c" + bytesHex(signer)) + ("8206" + credential(absent))
      )
      scripts.map { raw =>
        val native = script(hex(raw))
        val ours = t.validateNativeScript[TxLevel.Top](era, native)(using t.bodyOf.top)
        assertEquals(
          ours,
          native.eval(signers, vi.invalidBefore, vi.invalidHereafter, guards),
          s"${era.id.hex}: $raw"
        )
        (
          guardsOf(era).exists {
            case Credential.Script(_) => true
            case Credential.Key(_)    => false
          },
          ours
        )
      }
    }
    assert(kinds.exists(_._2) && kinds.exists(!_._2), "no script that holds and one that fails")
    // the body's guards of both kinds: a key's and a script's credential, each read as its own
    assert(kinds.exists(_._1), "no transaction with a script guard")
    assert(
      txs.exists(e =>
        guardsOf(e).exists {
          case Credential.Key(_)    => true
          case Credential.Script(_) => false
        }
      ),
      "no transaction with a key guard"
    )
  }
