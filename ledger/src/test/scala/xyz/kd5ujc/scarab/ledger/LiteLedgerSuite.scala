// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.GenDelegPair
import xyz.kd5ujc.scarab.ledger.{LiteLedger, ParamUpdate}
import xyz.kd5ujc.scarab.codec.Rational
import munit.FunSuite

/** The bootstrap ledger: update proposals, their adoption at the epoch boundary, certificates. */
class LiteLedgerSuite extends FunSuite:
  private val genesis = Genesis
    .shelley(Files.readString(Path.of("fixtures/preview/genesis/shelley-genesis.json")))
    .fold(e => fail(e.message), identity)
  private val lite = LiteLedger.fromGenesis(genesis.raw).fold(e => fail(e.message), identity)
  private val geometry = genesis.geometry

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)
  private val gkey = lite.genDelegs.keys.toVector.sortBy(_.hex).head
  private val zero = hex("d81e820001") // d = 0/1

  /** `{6: [{gkey: {12: d}}, epoch]}` */
  private def proposalBody(epoch: Int, d: Bytes = zero): Bytes =
    hex(
      "a10682a1581c" + gkey.hex + "a10c" + d.hex + epoch.toHexString.reverse.padTo(2, '0').reverse
    )

  test("genesis: d = 1, neutral entropy, 7 genesis delegates, quorum 5") {
    assertEquals(lite.params.d, Rational(1))
    assertEquals(lite.genDelegs.size, 7)
    assertEquals(lite.quorum, 5)
    assertEquals(lite.params.protocolVersion, (BigInt(6), BigInt(0)))
  }

  test("a PPUP proposal in the first part of an epoch is a current-epoch vote") {
    val effects =
      LiteLedger.effects(proposalBody(0), Era.Alonzo).fold(e => fail(e.toString), identity)
    assertEquals(effects.size, 1)
    val applied = effects.foldLeft(lite) { (l, e) =>
      e match
        case LiteLedger.Effect.Proposal(u, ep) =>
          assertEquals(ep, BigInt(0))
          l.copy(curProposals = l.curProposals ++ u)
        case other => fail(s"unexpected $other")
    }
    assertEquals(applied.curProposals, Map(gkey -> ParamUpdate(Map(12 -> zero))))
  }

  test("the proposal point of no return is monus: a window past the next epoch gives slot 0") {
    // `getTheSlotOfNoReturn` (cardano-ledger f649f975, `Slot.hs:106-114`): with 2 * 3k/f beyond the
    // first slot of the next epoch the point is slot 0, so every slot proposes for the next epoch
    val short = geometry.copy(epochLength = geometry.stabilityWindow.toBigInt.toLong)
    val update = Map(lite.genDelegs.keys.head -> ParamUpdate(Map(12 -> zero)))
    assertEquals(
      LiteLedger.applyEffect(lite, LiteLedger.Effect.Proposal(update, 0), 0, short),
      Left(LedgerDecodeError.ProposalEpochMismatch(0, 0, false))
    )
    assertEquals(
      LiteLedger
        .applyEffect(lite, LiteLedger.Effect.Proposal(update, 1), 0, short)
        .map(_.futureProposals),
      Right(update)
    )
  }

  test("a quorum of identical proposals changes d at the next epoch boundary; fewer do not") {
    val keys = lite.genDelegs.keys.toVector.sortBy(_.hex)
    def withVotes(n: Int) =
      lite.copy(curProposals = keys.take(n).map(_ -> ParamUpdate(Map(12 -> zero))).toMap)
    val adopted = LiteLedger.tick(withVotes(5), geometry.firstSlot(1) + 5, geometry)
    assertEquals(adopted.map(_.params.d), Right(Rational(0)))
    assertEquals(adopted.map(_.curProposals), Right(Map.empty))
    assertEquals(adopted.map(_.epoch), Right(BigInt(1)))
    val short = LiteLedger.tick(withVotes(4), geometry.firstSlot(1) + 5, geometry)
    assertEquals(short.map(_.params.d), Right(Rational(1)))
  }

  test("pool registration and retirement certificates are tracked") {
    val pool = Bytes(Vector.fill(28)(9.toByte))
    val vrf = Bytes(Vector.fill(32)(7.toByte))
    val reg = hex("a10481" + "83" + "03" + "581c" + pool.hex + "5820" + vrf.hex)
    val effects = LiteLedger.effects(reg, Era.Babbage).fold(e => fail(e.toString), identity)
    assertEquals(effects, Vector(LiteLedger.Effect.PoolRegistered(pool, vrf)))
    val retire = hex("a10481" + "83" + "04" + "581c" + pool.hex + "05")
    assertEquals(
      LiteLedger.effects(retire, Era.Babbage),
      Right(Vector(LiteLedger.Effect.PoolRetiring(pool, 5)))
    )
  }

  test("genesis delegation certificates are scheduled and adopted after the stability window") {
    val delegate = Bytes(Vector.fill(28)(1.toByte))
    val vrf = Bytes(Vector.fill(32)(2.toByte))
    val cert =
      hex("a10481" + "84" + "05" + "581c" + gkey.hex + "581c" + delegate.hex + "5820" + vrf.hex)
    val effects = LiteLedger.effects(cert, Era.Alonzo).fold(e => fail(e.toString), identity)
    assertEquals(
      effects,
      Vector(LiteLedger.Effect.GenesisDelegation(gkey, GenDelegPair(delegate, vrf)))
    )
    val scheduled = lite.copy(futureGenDelegs =
      Vector(
        xyz.kd5ujc.scarab.ledger
          .FutureGenDeleg(
            100 + geometry.stabilityWindow.toBigInt,
            gkey,
            GenDelegPair(delegate, vrf)
          )
      )
    )
    val before = LiteLedger.tick(scheduled, 100, geometry).toOption.get
    assertEquals(before.genDelegs(gkey), lite.genDelegs(gkey))
    val after =
      LiteLedger.tick(scheduled, 100 + geometry.stabilityWindow.toBigInt, geometry).toOption.get
    assertEquals(after.genDelegs(gkey), GenDelegPair(delegate, vrf))
  }

  test("applying a parameter update sets only the fields it names") {
    val update = ParamUpdate(Map(14 -> hex("820700"), 12 -> zero))
    val updated = LiteLedger.applyUpdate(lite.params, update).fold(e => fail(e.toString), identity)
    assertEquals(updated.protocolVersion, (BigInt(7), BigInt(0)))
    assertEquals(updated.d, Rational(0))
    assertEquals(updated.maxBlockBodySize, lite.params.maxBlockBodySize)
  }

  test("the bootstrap ledger reads an update at its era's key set and refuses a repeated key") {
    // Babbage has no `d` (key 12, `Babbage/PParams.hs`); Alonzo has (`Alonzo/PParams.hs`)
    assertEquals(
      LiteLedger.effects(proposalBody(0), Era.Babbage),
      Left(LedgerDecodeError.UnknownKey(DecodeField.ParameterUpdate, 12))
    )
    assert(LiteLedger.effects(proposalBody(0), Era.Alonzo).isRight)
    val repeated = hex("a10682a1581c" + gkey.hex + "a2" + "0c" + zero.hex + "0c" + zero.hex + "00")
    assertEquals(
      LiteLedger.effects(repeated, Era.Alonzo),
      Left(LedgerDecodeError.DuplicateKey(DecodeField.ParameterUpdate))
    )
  }
