// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{TestLedger, TxIn, TxOut, Unsigned, testByteStrings}
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.era.{
  EraBlockHeader,
  EraTx,
  EraTxBody,
  ProtVer,
  StoredTxOut,
  UTxO,
  Version
}
import xyz.kd5ujc.scarab.ledger.era.rules.{BbodyEnv, ConwayBbody, ConwayBlockRun, ShelleyBbodyState}
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  Build,
  ChainAccountState,
  Coin,
  Conway,
  LedgerState,
  StakePool
}
import xyz.kd5ujc.scarab.plutus.ScarabEvaluator

/** Conway's BBODY on its own, on the era run the node applies Conway blocks with
  * ([[ConwayBlockRun]]; `conwayBbodyTransition >> alonzoBbodyTransition`,
  * `Conway/Rules/Bbody.hs:268`, cardano-ledger f649f975), on blocks crafted as the Imp's
  * ([[ConwayImp.blockOf]]): what the rule does with a block beyond its transactions' own checks,
  * which the reference's cases hold (`ConwayBbodyImpSuite`) where the reference has one. The cases
  * of the flat BBODY's suite that no reference case holds: the block counted for its issuer, once
  * each; the reference scripts of a transaction summed past a 32-bit Int; and a block of many
  * transactions on a small stack (STK-3).
  */
class ConwayBlockRunSuite extends FunSuite:
  import ConwayImp.{B, Built}

  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  private val major = 10

  /** The era run on the block `raw` from `ls` and the blocks `made` so far, serially. */
  private def run(raw: Bytes, ls: LedgerState[Conway], made: BlocksMade): ConwayBlockRun.Result =
    ConwayBlockRun
      .program(
        ConwayImp.globals,
        BbodyEnv[Conway](ConwayImp.pp(major), ChainAccountState(Coin(0), Coin(0))),
        ConwayImp.decoded(raw),
        ConwayImp.globals.systemStart,
        ConwayImp.horizon,
        ShelleyBbodyState[Conway](ls, made),
        ScarabEvaluator.default,
        flat.Verdicts.Direct,
        ScarabEvaluator.defaultDecoder,
        ConwayImp.slotToPosix
      )
      .serial

  private def applied(result: ConwayBlockRun.Result): ConwayBlockRun.Applied = result match
    case Right(Right(applied)) => applied
    case other                 => fail(s"not admitted: $other")

  test("a block with no transactions is counted for its issuer, once each") {
    // `incrBlocks False` (`Shelley/BlockBody/Internal.hs:295-313`, cardano-ledger f649f975): Conway
    // has no overlay, so every block counts for its issuer
    val raw = ConwayImp.blockOf(major, Vector.empty)
    val issuer =
      EraBlockHeader.conway.blockIssuerBlockHeader(ConwayImp.decoded(raw)).coerceKeyRole[StakePool]
    val ls = ConwayImp.start(major).ls
    val first = applied(run(raw, ls, BlocksMade.empty))
    assertEquals(first.state.blocksMade.blocks.get(issuer).map(_.bits), Some(1L))
    val second = applied(run(raw, first.state.ls, first.state.blocksMade))
    assertEquals(second.state.blocksMade.blocks.get(issuer).map(_.bits), Some(2L))
    assertEquals(second.state.blocksMade.blocks.size, 1)
  }

  test("the reference scripts of one transaction are added up in 64 bits, as the reference's Int") {
    // `totalRefScriptSizeInBlock` sums `txNonDistinctRefScriptsSize :: ... -> Int`
    // (`Conway/Rules/Bbody.hs:357-370`, cardano-ledger f649f975): 2048 reference inputs, each
    // naming a script of 1 MiB, hold 2^31 bytes, one more than a 32-bit Int holds
    val payer = TestLedger.key()
    // the output's bytes written here: the batch's encoder holds no output of a megabyte
    def head(major: Int, n: Int): Vector[Byte] =
      if n < 24 then Vector(((major << 5) | n).toByte)
      else if n < 256 then Vector(((major << 5) | 24).toByte, n.toByte)
      else if n < 65536 then Vector(((major << 5) | 25).toByte, (n >> 8).toByte, n.toByte)
      else ((major << 5) | 26).toByte +: Vector(24, 16, 8, 0).map(s => (n >> s).toByte)
    def cborBytes(b: Vector[Byte]) = head(2, b.size) ++ b
    val wrapped = head(4, 2) ++ Vector(0x03.toByte) ++ cborBytes(Vector.fill(1 << 20)(0x42.toByte))
    val raw = head(5, 3) ++ Vector(0x00.toByte) ++ cborBytes(B.keyAddress(payer).value) ++
      Vector(0x01.toByte) ++ head(0, 5) ++ Vector(0x03.toByte, 0xd8.toByte, 0x18.toByte) ++
      cborBytes(wrapped)
    val stored = ok(TxOut.fromBytes(Bytes(raw)))
    val script = ok(StoredTxOut.conway.fromStored(stored))
    val held = Vector.tabulate(2048)(i => TxIn(Bytes(Vector.fill(32)(9.toByte)), i))
    val naming = B.body(
      0 -> B.set(B.fresh(2).node),
      1 -> B.arr(),
      2 -> B.uint(0),
      18 -> B.set(held.map(ConwayImp.inNode)*)
    )
    val tx = Built(B.encode(naming), B.encode(B.cmap()), true).era
    val utxo = UTxO[Conway](held.map(_ -> script).toMap)
    val t = EraTx.conway
    Vector(10, 11).foreach { m =>
      val pv = ProtVer(
        Version.mkVersion(m).getOrElse(fail("major")),
        Unsigned.lit[Unsigned.Width.W32](0)
      )
      assertEquals(
        ConwayBbody.totalRefScriptSizeInBlock[Conway](pv, Vector(tx), utxo)(using
          t,
          EraTxBody.conway,
          t.bodyOf.top,
          EraTxBody.conway.top
        ),
        1L << 31,
        s"$m"
      )
    }
  }

  test("a block of 10,000 transactions runs on a 256 KiB stack") {
    val payer = TestLedger.key()
    val (coin, fee) = (BigInt(10000000), BigInt(300000))
    val inputs = Vector.tabulate(10000)(i =>
      TxIn(Bytes(Vector.tabulate[Byte](32)(j => (i >> (8 * (j % 4))).toByte)), 0)
    )
    val funded = Build.funded[Conway](
      Build.nes[Conway](0L, ConwayImp.stored(major)),
      ConwayImp.outputs(inputs.map(_ -> ConwayImp.Out(B.keyAddress(payer), coin))*)
    )
    val txs = inputs.map { in =>
      val body = B.body(
        0 -> B.set(ConwayImp.inNode(in)),
        1 -> B.arr(B.out(B.keyAddress(payer), coin - fee)),
        2 -> B.uint(fee)
      )
      Built(B.encode(body), B.encode(B.witnesses(body, Vector(payer))), true)
    }
    val raw = ConwayImp.blockOf(major, txs)
    var result: Option[Either[Throwable, ConwayBlockRun.Result]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(run(raw, funded.es.ls, BlocksMade.empty))
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    val after = applied(result.getOrElse(fail("the thread did not finish")).fold(throw _, identity))
    assertEquals(after.state.ls.utxo.fees.value, fee * 10000)
    assertEquals(after.state.ls.utxo.utxo.utxo.count, 10000L)
  }
