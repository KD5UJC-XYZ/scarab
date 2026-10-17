// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Ask
import java.nio.file.{Files as JFiles, Path}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.ledger.{Address, Block, Ptr, TxBody, TxIn}
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.protocol.Era

/** B29: mainnet transaction 9a47e091709e4fe1 (block 5,567,164, slot 26,366,727, Mary) pays 50 ada
  * to a pointer address of slot 16,292,793,057, transaction 1,011,302 and certificate 20
  * (`MainnetPointerSuite`). The node kept the pointer as it came, which the Haskell state cannot
  * hold (a `Word32` and two `Word16` s), so it refused every snapshot from block 5,581,257 on
  * (`ConstraintViolation(Ptr, ...)` at `ledgerState.state.nes.es.ls.utxo.instantStake.ptrStake`).
  * The reference normalises the pointer when it reads the address, so the instant stake holds
  * `Ptr 0 0 0` and the UTxO the address written again (`cAddrNormalized`).
  *
  * The output is put into a Shelley state of the eras capture, on mainnet; the V5 snapshot of that
  * state is written with the full read-back check and comes back as the mirror normalises it.
  */
class MainnetPointerSnapshotSuite extends CatsEffectSuite:
  private val Mainnet = 1

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e), identity)

  private val body: TxBody =
    val tx = Cbor
      .decode(hex(JFiles.readString(Path.of("fixtures/mainnet/mary-pointer-tx-9a47e091.hex")).trim))
      .fold(e => fail(e), identity)
    val parts = tx.value match
      case Value.Arr(xs) => xs
      case other         => fail(s"unexpected $other")
    TxBody.decode(parts.head.original, Address.Mode.of(Era.Mary)).fold(e => fail(e), identity)

  private val fixtures = Path.of("fixtures/eras")
  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)

  /** The capture's configuration, on mainnet. */
  private val env: ConsensusMirrorEnv =
    val e = setup.mirrorEnv()
    e.copy(ledger = e.ledger.copy(network = Mainnet))

  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(env)
  private val format: SnapshotFormat[IO, MirrorState] = SnapshotV5.format[IO](SnapshotCheck.Full)

  /** The state after the first Shelley block of the capture. */
  private lazy val shelley: StateSnapshot[MirrorState] =
    val raws = JFiles
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(hex)
    val blocks =
      raws.map(
        Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity)
      )
    val first = blocks.indexWhere(_.era == Era.Shelley)
    assert(first >= 0, "the capture has no Shelley block")
    val state = (0 to first).foldLeft(setup.mirrorStart) { (s, i) =>
      MirrorSteps
        .block(setup, s, blocks(i).header, raws(i))
        .fold(f => fail(s"block $i rejected by ${f.rule.name}: ${f.reason}"), identity)
    }
    val h = blocks(first).header
    StateSnapshot(Some(BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary)), state)

  private val input = TxIn(body.id, 0)

  private def utxoOf(m: MirrorState) =
    MirrorStep.utxoOf(m).getOrElse(fail("a Byron state has no UTxO handle"))

  /** The state with the output in its UTxO, counted as the rules count it. */
  private lazy val withOutput: StateSnapshot[MirrorState] =
    shelley.copy(state = MirrorStep.mapUtxo(shelley.state)(_ ++ Map(input -> body.outputs.head)))

  private lazy val written: IO[Array[Byte]] =
    format.encode(withOutput).flatMap(r => IO(r.fold(e => fail(s"refused: $e"), identity)))

  test("the state holds the output's 50 ada under Ptr 0 0 0") {
    assertEquals(utxoOf(withOutput.state).ptrStake.get(Ptr.zero), Some(BigInt(50000000)))
    assertEquals(utxoOf(withOutput.state).ptrStake.keys.filter(!_.fits).toList, Nil)
  }

  test("the snapshot is written (it was refused before), read back in full, and comes back equal") {
    written.flatMap(bytes =>
      format.decode(bytes).map { r =>
        val back = r.fold(e => fail(e.message), identity)
        assertEquals(back.anchor, withOutput.anchor)
        assertEquals(utxoOf(back.state).get(input), Some(body.outputs.head))
        assertEquals(utxoOf(back.state).ptrStake.get(Ptr.zero), Some(BigInt(50000000)))
      }
    )
  }
