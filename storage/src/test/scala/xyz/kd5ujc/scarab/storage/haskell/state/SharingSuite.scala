// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{Credential, Vote}
import xyz.kd5ujc.scarab.ledger.state.{KeyHash, ProtVer, Sorted, Staking, Word64}
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError}
import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given

import DecodeError.Kind
import HaskellStateCodecs.{keyHash, nodeCredential, vote}
import Wire.{mapOf, seqOf, setOf}

/** The decoders of collections fold straight into builders and share what repeats (issue #94,
  * stages 2 and 3, docs/adr/0013): the state decoded is the state with no sharing, the equal
  * credentials of one decode are one instance, the errors name the element, and none of it needs
  * the stack of its input.
  */
class SharingSuite extends munit.FunSuite:
  private given ProtVer = ProtVer(Word64.fromUnsigned(10), Word64.zero)

  private def voter(i: Int): Credential =
    val h = new Array[Byte](28)
    java.nio.ByteBuffer.wrap(h).putInt(24, i)
    Credential.Key(Bytes.wrap(h))

  private def votes(from: Int, to: Int): SortedMap[Credential, Vote] =
    Sorted.map((from until to).map(i => voter(i) -> Vote.fromOrdinal(i % 3)))

  private val committees: Codec[Vector[SortedMap[Credential, Vote]]] =
    seqOf(mapOf(nodeCredential, vote))

  /** The same item with no document behind it, so that nothing it decodes is shared. Recursive,
    * which is fine for the shallow item of a test.
    */
  private def unshared(n: Node): Node = n.value match
    case Value.Arr(xs) => Node(Value.Arr(xs.map(unshared)), n.original)
    case Value.Map(kvs) =>
      Node(Value.Map(kvs.map((k, v) => (unshared(k), unshared(v)))), n.original)
    case Value.Tag(t, x) => Node(Value.Tag(t, unshared(x)), n.original)
    case other           => Node(other, n.original)

  private def decodedBoth[A](codec: Codec[A], bytes: Bytes): (A, A) =
    val root = Cbor.decode(bytes, HaskellState.limits).fold(fail(_), identity)
    def run(n: Node): A = codec.decode(n).fold(e => fail(e.render), identity)
    (run(root), run(unshared(root)))

  test("the credentials of one decode are one instance each, and the state is the same") {
    // the voters of two proposals overlap, as those of a proposal and the pulser's copy of it do
    val maps = Vector(votes(0, 300), votes(100, 400), votes(0, 300))
    val bytes = committees.encodeUnchecked(maps)
    val (shared, plain) = decodedBoth(committees, bytes)
    assertEquals(shared, maps)
    assertEquals(plain, maps)
    assertEquals(shared, plain)
    assertEquals(committees.encodeUnchecked(shared), bytes)
    // each credential is one object across the three maps in the shared decode
    val firsts = shared(0).keys.map(c => c -> c).toMap
    assert(shared(2).keys.forall(c => firsts(c) eq c))
    assert(shared(1).keys.filter(firsts.contains).forall(c => firsts(c) eq c))
    // and several in the decode with nothing shared
    assert(plain(2).keys.exists(c => !(plain(0).keys.find(_ == c).exists(_ eq c))))
  }

  test("the hashes of one decode are one instance each") {
    val staking = KeyHash.fromBytes[Staking]
    val sets = Vector.tabulate(3)(_ =>
      Sorted.set(
        (0 until 50).map(i => staking(voter(i).hash).fold(e => fail(e.toString), identity))
      )
    )
    val codec = seqOf(setOf(keyHash[Staking]))
    val bytes = codec.encodeUnchecked(sets)
    val (shared, plain) = decodedBoth(codec, bytes)
    assertEquals(shared, plain)
    assertEquals(shared, sets)
    val byBytes = shared(0).toVector.map(h => KeyHash.bytes(h))
    assert(shared(1).toVector.map(h => KeyHash.bytes(h)).zip(byBytes).forall(_ eq _))
  }

  test("an element that does not decode is named by its place, in a set, a sequence and a map") {
    val codec = seqOf(setOf(nodeCredential))
    // a credential of 2 bytes in the third place of the second set
    val bad = Bytes.fromHex(
      "82" + "82" + "8200581c" + "00" * 28 + "8200581c" + "01" * 28 + "83" +
        "8200581c" + "02" * 28 + "8200581c" + "03" * 28 + "820042" + "0000"
    )
    codec.decodeBytes(bad.fold(fail(_), identity), HaskellState.limits) match
      case Left(e)  => assert(e.render.contains("[1]") && e.render.contains("[2]"), e.render)
      case Right(a) => fail(s"decoded $a")
    val pairs = Bytes.fromHex("a1" + "8200581c" + "00" * 28 + "09").fold(fail(_), identity)
    mapOf(nodeCredential, vote).decodeBytes(pairs, HaskellState.limits) match
      case Left(e)  => assert(e.render.contains("value[0]"), e.render)
      case Right(a) => fail(s"decoded $a")
  }

  test("a repeat is counted as it was: from version 9 the number of repeated elements") {
    val c = voter(1)
    val twice = Bytes
      .fromHex(
        "83" + "8200581c" + "00" * 27 + "01" + "8200581c" + "00" * 27 + "01" +
          "8200581c" + "00" * 27 + "01"
      )
      .fold(fail(_), identity)
    assertEquals(c.hash.hex, "00" * 27 + "01")
    setOf(nodeCredential).decodeBytes(twice, HaskellState.limits) match
      case Left(e)  => assertEquals(e.kind, Kind.DuplicateElement("2 repeated"))
      case Right(a) => fail(s"decoded $a")
  }

  /** Run `body` on a thread with a small stack, so that a decoder that recurs on its input
    * overflows it for certain.
    */
  private def onSmallStack[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    result.get.fold(throw _, identity)

  test("a set, a sequence and a map of a hundred thousand elements decode in a small stack") {
    val n = 100000
    val all = votes(0, n)
    val bytes = mapOf(nodeCredential, vote).encodeUnchecked(all)
    val got = onSmallStack(
      mapOf(nodeCredential, vote)
        .decodeBytes(bytes, HaskellState.limits)
        .fold(e => fail(e.render), identity)
    )
    assertEquals(got.size, n)
    val set = setOf(nodeCredential).encodeUnchecked(Sorted.set(all.keys))
    assertEquals(
      onSmallStack(
        setOf(nodeCredential)
          .decodeBytes(set, HaskellState.limits)
          .fold(e => fail(e.render), _.size)
      ),
      n
    )
    val seq = seqOf(nodeCredential).encodeUnchecked(all.keys.toVector)
    assertEquals(
      onSmallStack(
        seqOf(nodeCredential)
          .decodeBytes(seq, HaskellState.limits)
          .fold(e => fail(e.render), _.size)
      ),
      n
    )
  }
