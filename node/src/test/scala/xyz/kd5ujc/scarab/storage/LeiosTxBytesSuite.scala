// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Ask
import java.nio.file.{Files as JFiles, Path as JPath}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.ByronState
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  EraLedgerState,
  MirrorState,
  MirrorStep,
  Placement,
  ShelleyLedgerState,
  StateMirror,
  StateMirrorError
}
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.state.Word64
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout

/** The Leios build's `shelleyCumulativeTxBytes` (ouroboros-consensus 2416493b
  * `Shelley/Ledger/Ledger.hs:308`) through the mirror's block step: each block adds the `sizeTxF`
  * of every transaction of its body, valid or not (`applyHelper`, `:749-756`), modulo 2^64; the
  * total starts at zero at the chain's origin and is written and read back with the state. The
  * network is the Leios devnet of `fixtures/leios/state`, which starts in Conway; the block is its
  * block of slot 195 with the transactions of each case.
  */
class LeiosTxBytesSuite extends CatsEffectSuite:
  private val fixture = JPath.of("fixtures/leios/state")
  private val setup =
    NodeSetup
      .fromDirectory(ReferenceBuild.Leios)(fixture.resolve("genesis"))
      .fold(e => fail(e.message), identity)
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
  private val placement = Placement(setup.geometry, setup.byronHistory, setup.praos.forks)
  private val start = setup.mirrorStart

  private val slot195: Block.Shelley =
    Block.decode(
      Bytes.wrap(JFiles.readAllBytes(fixture.resolve("conway-195/block"))),
      setup.geometry.byron.epochSlots
    ) match
      case Right(b: Block.Shelley) => b
      case other                   => fail(s"not a Shelley-based block: $other")

  private def bytes(n: Int): Bytes = Bytes.wrap(Array.fill(n)(0x01.toByte))

  // `sizeTxF`: the list header, the body, the witnesses, and the auxiliary data or `null`
  private val plain = Block.Transaction(bytes(10), bytes(5), None, isValid = true) // 1+10+5+1
  private val withAux = Block.Transaction(bytes(20), bytes(7), Some(bytes(4)), isValid = true)
  private val invalid = Block.Transaction(bytes(3), bytes(2), None, isValid = false) // 1+3+2+1

  private def block(slotOffset: Int, txs: Block.Transaction*): Block.Shelley =
    slot195.copy(
      header = slot195.header.copy(slot = slot195.header.slot + slotOffset),
      transactions = txs.toVector
    )

  private def layoutOf(m: MirrorState): ShelleyLedgerState.Layout = m.ext.ledgerState.state match
    case i: EraLedgerState.InShelleyEra[e] => i.state.layout
    case _                                 => fail("expected a Shelley-based state")

  private def withLayout(m: MirrorState, layout: ShelleyLedgerState.Layout): MirrorState =
    m.ext.ledgerState.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        m.copy(ext =
          m.ext.copy(ledgerState =
            m.ext.ledgerState
              .withState(EraLedgerState.InShelleyEra(i.era, i.state.copy(layout = layout)))
          )
        )
      case _ => fail("expected a Shelley-based state")

  private def step(m: MirrorState, b: Block.Shelley): Either[StateMirrorError, MirrorState] =
    MirrorStep.block(
      m,
      placement,
      b,
      MirrorStep.nesOf(m).getOrElse(fail("expected a Shelley-based state")),
      m.protocol,
      m.consensus,
      m.plutus
    )

  private def stepped(m: MirrorState, b: Block.Shelley): MirrorState =
    step(m, b).fold(e => fail(e.toString), identity)

  private def leios(n: Long) = ShelleyLedgerState.Layout.Leios(Word64.fromUnsigned(n))

  test(
    "sizeTxF is the list of the body, the witnesses and the auxiliary data, is_valid uncounted"
  ) {
    assertEquals(plain.size, 17L)
    assertEquals(withAux.size, 32L)
    assertEquals(invalid.size, 7L)
  }

  test("the origin holds no bytes, and each block adds every transaction's, valid or not") {
    assertEquals(layoutOf(start), leios(0))
    val first = stepped(start, block(0, plain, withAux, invalid))
    assertEquals(layoutOf(first), leios(17 + 32 + 7))
    // a block of no transactions keeps the total; the next adds to it
    val second = stepped(first, block(1))
    assertEquals(layoutOf(second), leios(56))
    assertEquals(layoutOf(stepped(second, block(2, plain))), leios(56 + 17))
  }

  test("leaving Byron, the block of the hand-over adds its bytes to the origin's zero") {
    // the start mirror with a Byron state in place of its own: no field precedes the block
    val byron = start.copy(ext =
      start.ext.copy(ledgerState =
        start.ext.ledgerState.withState(EraLedgerState.ByronCurrent(ByronState.initial))
      )
    )
    val after = MirrorStep
      .handover(
        byron,
        placement,
        block(0, plain, invalid),
        MirrorStep.nesOf(start).getOrElse(fail("expected a Shelley-based state")),
        start.protocol,
        start.consensus
      )
      .fold(e => fail(e.toString), identity)
    assertEquals(layoutOf(after), leios(17 + 7))
  }

  test("the total is a Word64: it wraps modulo 2^64, as the reference's sum and + do") {
    val full = withLayout(start, ShelleyLedgerState.Layout.Leios(Word64.max))
    assertEquals(layoutOf(stepped(full, block(0, plain))), leios(16))
  }

  test("a mirror whose ledger state is of another layout than the block's state is refused") {
    val other = withLayout(start, ShelleyLedgerState.Layout.Node11_1(None))
    assertEquals(
      step(other, block(0, plain)),
      Left(StateMirrorError.LayoutMismatch(StateLayout.Node11_1, StateLayout.Leios))
    )
  }

  test("the origin of each layout: no certificate round, or no bytes") {
    assertEquals(
      ShelleyLedgerState.Layout.atOrigin(StateLayout.Node11_1),
      ShelleyLedgerState.Layout.Node11_1(None)
    )
    assertEquals(ShelleyLedgerState.Layout.atOrigin(StateLayout.Leios), leios(0))
  }

  test("the total after a block is written with the state and read back from the snapshot") {
    val after = stepped(start, block(0, plain, withAux, invalid))
    val layout =
      ReferenceLayout.of(ReferenceBuild.Leios).fold(e => fail(e.message), identity)
    val format = SnapshotV10.format[IO](SnapshotCheck.Full, layout)
    val snapshot = StateSnapshot(
      StateMirror.anchorOf(after.ext.headerState.tip),
      after,
      CheckpointBinding.Unbacked
    )
    for
      written <- format.encode(snapshot)
      content = written.fold(e => fail(e.toString), identity)
      read <- format.decode(content)
    yield assertEquals(layoutOf(read.fold(e => fail(e.message), identity).state), leios(56))
  }
