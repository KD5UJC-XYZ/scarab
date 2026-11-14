// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.*

/** The Leios committee a mark snapshot memoizes and its rotation into the set position, against the
  * native `mkMarkSnapShot` and `mkSetSnapShot` of the Leios testnet's build
  * (`fixtures/reference/leios-committee`). Every acceptance verdict and every accepted byte is
  * compared; no case is excluded.
  */
class LeiosCommitteeReferenceSuite extends FunSuite:
  private given ProtVer = ProtVer(Word64.fromUnsigned(12), Word64.zero)

  private def hex(value: String): Bytes =
    Bytes.fromHex(value).fold(e => fail(e.toString), identity)

  private val rows = Files
    .readString(Path.of("fixtures/reference/leios-committee/cases.jsonl"))
    .linesIterator
    .filter(_.nonEmpty)
    .map(line => parse(line).fold(e => fail(e.toString), identity).hcursor)
    .toVector

  test("the corpus is present") {
    assert(rows.nonEmpty)
  }

  rows.foreach { row =>
    def field(name: String): String = row.get[String](name).fold(e => fail(e.toString), identity)
    val name = field("name")
    test(s"$name agrees with the native committee and set snapshot") {
      val codecs = LeiosStateCodecs
      val decoded = codecs.snapShots.decodeBytes(hex(field("cbor")), HaskellState.limits)
      val reference = row.downField("reference")
      val accepted = reference.get[Boolean]("accepted").fold(e => fail(e.toString), identity)
      assertEquals(decoded.isRight, accepted, s"native result: ${reference.focus}")
      decoded.foreach { snapshots =>
        val layout = snapshots.layout match
          case l: SnapShots.Layout.Leios => l
          case other                     => fail(s"not the Leios layout: $other")
        val committee = LeiosCommitteeSelection
          .ofMark(snapshots.mark, layout)
          .fold(e => fail(s"no committee: $e"), identity)
        assertEquals(codecs.leiosCommittee.check(committee), None)
        val committeeBytes = codecs.leiosCommittee.encodeUnchecked(committee)
        def expected(name: String): String =
          reference.get[String](name).fold(e => fail(e.toString), identity)
        assertEquals(committeeBytes.hex, expected("committee"))
        // `SetSnapShot` (9df9f98c `State/SnapShots.hs:485-493`): `[snapshot, committee]`.
        val set = "82" + codecs.snapShot.encodeUnchecked(snapshots.mark).hex + committeeBytes.hex
        assertEquals(set, expected("set"))
      }
    }
  }

  // States the reference cannot hold: Scarab gives a typed defect for each, never a keyless seat.

  private def decoded(name: String): (SnapShots, SnapShots.Layout.Leios) =
    val row = rows
      .find(_.get[String]("name") == Right(name))
      .getOrElse(fail(s"missing native case $name"))
    val raw = hex(row.get[String]("cbor").fold(e => fail(e.toString), identity))
    val snapshots = LeiosStateCodecs.snapShots
      .decodeBytes(raw, HaskellState.limits)
      .fold(e => fail(e.render), identity)
    snapshots.layout match
      case l: SnapShots.Layout.Leios => (snapshots, l)
      case other                     => fail(s"not the Leios layout: $other")

  private def withKeys(
      s: SnapShots,
      key: (KeyHash[StakePool], PoolBlsKey) => PoolBlsKey
  ): SnapShots =
    s.copy(mark =
      s.mark.copy(pools =
        Sorted.map(s.mark.pools.iterator.map((id, p) => id -> p.copy(blsKey = key(id, p.blsKey))))
      )
    )

  private def byStake(s: SnapShots): Vector[KeyHash[StakePool]] =
    s.mark.pools.toVector.sortBy(-_._2.stake.toBigInt).map(_._1)

  test("a seated pool of the 11.1 layout is a defect, not a keyless seat") {
    val (s, layout) = decoded("keys-seated-by-stake")
    val stable = withKeys(s, (_, _) => PoolBlsKey.Node11_1)
    assertEquals(
      LeiosCommitteeSelection.ofMark(stable.mark, layout),
      Left(LeiosCommitteeError.LayoutMismatch(byStake(s).head))
    )
    assertEquals(
      LeiosCommitteeSelection.ofMark(stable.mark, layout.copy(markCommitteeSize = 0)),
      Right(LeiosCommittee.empty),
      "a size of zero inspects no pool"
    )
  }

  test("a seated key the reference could not decode is a defect; an unseated one is not read") {
    val (s, layout) = decoded("keys-seated-by-stake")
    val last = byStake(s).last
    val broken = withKeys(
      s,
      (id, k) =>
        k match
          case PoolBlsKey.Leios(Some(state)) if id == last =>
            PoolBlsKey.Leios(
              Some(state.copy(key = state.key.copy(key = Bytes.fromArray(new Array[Byte](96)))))
            )
          case other => other
    )
    assert(
      LeiosCommitteeSelection.ofMark(broken.mark, layout) match
        case Left(LeiosCommitteeError.UndecodableKey(pool, _)) => pool == last
        case _                                                 => false
    )
    assert(LeiosCommitteeSelection.ofMark(broken.mark, layout.copy(markCommitteeSize = 2)).isRight)
  }

  test("a selection parameter outside its word is a defect") {
    val (s, layout) = decoded("keys-seated-by-stake")
    assertEquals(
      LeiosCommitteeSelection.ofMark(s.mark, layout.copy(markCommitteeSize = BigInt(65536))),
      Left(
        LeiosCommitteeError.OutOfRange(LeiosCommitteeError.Parameter.CommitteeSize, BigInt(65536))
      )
    )
    assertEquals(
      LeiosCommitteeSelection.ofMark(s.mark, layout.copy(markMaxKeyAge = BigInt(-1))),
      Left(LeiosCommitteeError.OutOfRange(LeiosCommitteeError.Parameter.MaxKeyAge, BigInt(-1)))
    )
  }

  test("a full-width selection over many pools runs on a small stack (ADR 0011)") {
    val (s, layout) = decoded("absent-key")
    val template = s.mark.pools.values.head
    val pools = Sorted.map((0 until 70000).iterator.map { i =>
      val id = KeyHash
        .fromBytes[StakePool](Bytes.fromArray(java.nio.ByteBuffer.allocate(28).putInt(i).array()))
        .fold(e => fail(e.toString), identity)
      id -> template.copy(stake = CompactCoin.fromUnsigned(i % 97), blsKey = PoolBlsKey.Leios(None))
    })
    val committee = onSmallStack(
      LeiosCommitteeSelection.ofMark(
        s.mark.copy(pools = pools),
        layout.copy(markCommitteeSize = 0xffff)
      )
    ).fold(e => fail(s"no committee: $e"), identity)
    assertEquals(committee.seats.size, 0xffff)
    assert(committee.seats.forall(_.verificationKey.isEmpty))
  }

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
    result.getOrElse(fail("the thread did not finish")).fold(throw _, identity)
