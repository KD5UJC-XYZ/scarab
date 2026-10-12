// SPDX-License-Identifier: Apache-2.0
package lab.node

import java.nio.file.{Files, Path}
import lab.Blake2b
import lab.cbor.Bytes
import lab.node.consensus.*
import lab.node.ledger.{LiteLedger, ParamUpdate, Rational}
import lab.vrf.PraosLeaderThreshold as Leader
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** TPraos validation over the first 20 real Preview blocks (the oracle: a wrong nonce, seed or
  * overlay formula makes the VRF proofs fail).
  */
class TPraosSuite extends FunSuite:
  private val genesisFile = Path.of("fixtures/preview/genesis/shelley-genesis.json")
  private val genesisBytes = Files.readAllBytes(genesisFile)
  private val genesisText = new String(genesisBytes, "UTF-8")
  private val genesis = Genesis.shelley(genesisText).fold(e => fail(e.message), identity)
  private val lite = LiteLedger.fromGenesis(genesis.raw).fold(e => fail(e.message), identity)
  private val config = PraosConfig(
    genesis.geometry,
    genesis.slotsPerKesPeriod,
    genesis.maxKesEvolutions,
    Leader.Fraction.checked(1, 20).toOption.get
  )
  private val initial = Praos.initial(Blake2b.hash256.hash(Bytes.fromArray(genesisBytes)))

  private val headers: Vector[Header.Shelley] =
    Files
      .readAllLines(Path.of("fixtures/preview/alonzo-first-20.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))
      .map(Block.decodeShelley(_).fold(e => fail(e.message), identity))
      .collect { case b: Block.Shelley => b.header }

  test("initial nonce is the Shelley genesis hash from the node config") {
    assertEquals(
      initial.epochNonce,
      Nonce.Hash(
        Bytes
          .fromHex("363498d1024f84bb39d3fa9593ce391483cb40d479b87233f868d6e57c3a400d")
          .toOption
          .get
      )
    )
  }

  test("the first 20 Preview headers validate as active overlay slots") {
    val (last, leaders) = headers.foldLeft((initial, Vector.empty[LeaderCheck])) {
      case ((s, acc), h) =>
        TPraos.update(s, h, lite.view, config) match
          case Right(v) => (v.state, acc :+ v.leader)
          case Left(e)  => fail(s"block ${h.blockNo} slot ${h.slot}: ${e.message}")
    }
    assertEquals(leaders.size, 20)
    assert(leaders.forall(_.isInstanceOf[LeaderCheck.Overlay]), leaders.toString)
    assertEquals(last.evolving == initial.evolving, false)
    assert(last.counters.nonEmpty)
    assertEquals(last.lab, Nonce.Hash(headers.last.prevHash.get))
  }

  private def firstError(h: Header.Shelley, view: LedgerView = lite.view) =
    TPraos.update(initial, h, view, config).left.toOption

  private def flip(b: Bytes, i: Int): Bytes =
    Bytes(b.value.updated(i, (b.value(i) ^ 1).toByte))

  test("flipping a leader VRF proof byte is a typed leader-VRF error") {
    val h = headers.head
    val vrf = h.vrf.asInstanceOf[HeaderVrf.TPraos]
    val bad = h.copy(vrf = vrf.copy(leader = vrf.leader.copy(proof = flip(vrf.leader.proof, 40))))
    assert(
      firstError(bad).exists(_.isInstanceOf[TPraosError.BadLeaderVrf]),
      firstError(bad).toString
    )
  }

  test("flipping a nonce VRF output byte is a typed nonce-VRF error") {
    val h = headers.head
    val vrf = h.vrf.asInstanceOf[HeaderVrf.TPraos]
    val bad = h.copy(vrf = vrf.copy(nonce = vrf.nonce.copy(output = flip(vrf.nonce.output, 3))))
    assert(
      firstError(bad).exists(_.isInstanceOf[TPraosError.BadNonceVrf]),
      firstError(bad).toString
    )
  }

  test("a block from the wrong genesis delegate in an active overlay slot is rejected") {
    val h = headers.head
    val rotated = lite.genDelegs.keys.toVector.sortBy(_.hex)
    val values = rotated.map(lite.genDelegs)
    val shifted = rotated.zip(values.tail :+ values.head).toMap
    val view = lite.view.copy(genDelegs = shifted)
    assert(
      firstError(h, view).exists(_.isInstanceOf[TPraosError.WrongGenesisColdKey]),
      firstError(h, view).toString
    )
  }

  test("a block in a non-active overlay slot is rejected") {
    val h = headers.head.copy(slot = BigInt(7))
    assertEquals(firstError(h), Some(TPraosError.NotActiveOverlaySlot(7)))
  }

  test("a tampered KES signature is a typed opcert error") {
    val h = headers.head
    val bad = h.copy(kesSignature = flip(h.kesSignature, 10))
    assert(firstError(bad).exists(_.isInstanceOf[TPraosError.OCert]), firstError(bad).toString)
  }

  test("a non-overlay slot of an unregistered issuer is rejected before any crypto") {
    val view = lite.view.copy(d = Rational(0))
    assert(
      firstError(headers.head, view).exists(_.isInstanceOf[TPraosError.VrfKeyUnknown]),
      firstError(headers.head, view).toString
    )
  }

  test("overlay schedule: d = 1 owns every slot, every 20th active, round robin by sorted key") {
    val keys = lite.genDelegs.keys.toVector.sortBy(_.hex)
    val one = Rational(1)
    assert((0 until 100).forall(s => TPraos.isOverlaySlot(0, one, s)))
    assertEquals(TPraos.overlayOwner(0, keys, one, BigDecimal("0.05"), 0), Some(keys(0)))
    assertEquals(TPraos.overlayOwner(0, keys, one, BigDecimal("0.05"), 20), Some(keys(1)))
    assertEquals(TPraos.overlayOwner(0, keys, one, BigDecimal("0.05"), 140), Some(keys(0)))
    assertEquals(TPraos.overlayOwner(0, keys, one, BigDecimal("0.05"), 21), None)
  }

  test("overlay schedule: d = 1/2 owns every other slot, active every 20th overlay slot") {
    val keys = lite.genDelegs.keys.toVector.sortBy(_.hex)
    val half = Rational(1, 2)
    assertEquals((0 until 10).filter(s => TPraos.isOverlaySlot(0, half, s)), Vector(0, 2, 4, 6, 8))
    assertEquals(TPraos.overlayOwner(0, keys, half, BigDecimal("0.05"), 40), Some(keys(1)))
    assertEquals(TPraos.overlayOwner(0, keys, half, BigDecimal("0.05"), 20), None)
    assert(!TPraos.isOverlaySlot(0, Rational(0), 5))
  }

  test("a 512-bit leader value decides like the equivalent 256-bit one") {
    val f = Leader.Fraction.checked(1, 20).toOption.get
    val stake = Leader.Fraction.checked(1, 10).toOption.get
    for n <- Seq(0, 1, 5, 40, 90, 99) do
      val v256 = BigInt(n) * (Leader.Range / 100)
      val v512 = BigInt(n) * (Leader.Range512 / 100)
      assertEquals(Leader.check(v512, stake, f, Leader.Range512), Leader.check(v256, stake, f))
  }

  test("protocol parameter updates need a quorum of identical proposals") {
    val d = Bytes.fromHex("d81e820102").toOption.get // 1/2
    val e = Bytes.fromHex("d81e820103").toOption.get // 1/3
    val a = ParamUpdate(Map(12 -> d))
    val b = ParamUpdate(Map(12 -> e))
    def key(i: Int) = Bytes(Vector.fill(28)(i.toByte))
    val params = lite.params
    assertEquals(
      LiteLedger.voted(Map(key(1) -> a, key(2) -> a, key(3) -> a), params, 3).map(_.map(_.d)),
      Right(Some(Rational(1, 2)))
    )
    assertEquals(
      LiteLedger.voted(Map(key(1) -> a, key(2) -> a, key(3) -> b), params, 3),
      Right(None)
    )
  }
