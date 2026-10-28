// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{
  BootstrapState,
  ByronRules,
  ByronRulesConfig,
  ByronState,
  ByronTip
}
import xyz.kd5ujc.scarab.consensus.state.PBftState
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  Block,
  BlockFailure,
  BlockRule,
  Genesis,
  Header,
  LiteLedger,
  PeerError,
  ProviderDefect,
  TxIn,
  ValidationError,
  VerificationDefect
}
import xyz.kd5ujc.scarab.ledger.byron.{ByronLedger, ByronSig}
import xyz.kd5ujc.scarab.ledger.byron.ByronTestKit
import xyz.kd5ujc.scarab.ledger.byron.ByronTestKit.*
import munit.FunSuite

/** A signature of the Byron ledger (a transaction witness, an update proposal) that a failing
  * provider cannot check is no verdict on the block (issue 438, folded into issue 393): the Byron
  * rules return the [[ProviderDefect]] beside their refusals, the node's block rules give
  * [[BlockFailure.Unverifiable]] and not [[BlockFailure.Byron]], and the session stops the node.
  * The same block with a working provider is accepted.
  *
  * [[ByronLedgerDefectKit.failure]] is also the source of the rows of the session matrix of
  * [[SessionFailureSuite]]: the failure it gives is what the real Byron rules produce.
  */
class ByronLedgerDefectSuite extends FunSuite:
  import ByronLedgerDefectKit.*

  private val sites = List(
    "the transaction witness" -> ByronSig.SignTx,
    "the update proposal" -> ByronSig.SignUpdateProposal
  )

  sites.foreach { (name, kind) =>
    test(s"a provider that fails on $name is no verdict on the Byron block") {
      val failing = ByronRules.update(start, block, rules(kind))
      assertEquals(failing.map(_ => ()), Left(ProviderDefect.Ed25519(providerFailure)))
      // the block-level boundary gives the unverifiable failure, never a Byron refusal
      assertEquals(
        step(kind).map(_ => ()),
        Left(BlockFailure.Unverifiable(BlockRule.Byron, ProviderDefect.Ed25519(providerFailure)))
      )
    }

    test(s"a defect on $name stops the node: no refusal, no blame") {
      val refused = ValidationError(header.point, failure(kind))
      val held = VerificationDefect(
        header.point,
        VerificationDefect.Site.Header,
        ProviderDefect.Ed25519(providerFailure)
      )
      assertEquals(refused.unverifiable, Some(held))
      assertEquals(
        SessionEnd.of(PeerError.InvalidBlock(refused)),
        SessionEnd.Unverifiable(held)
      )
      assertEquals(
        FailurePolicy.disposition(SessionEnd.Unverifiable(held)),
        Disposition.StopNode(Fatal.Unverifiable(held))
      )
      assertEquals(SessionEnd.peerError(SessionEnd.Unverifiable(held)), None)
    }
  }

  test("the same block is accepted with a working provider") {
    assert(ByronRules.update(start, block, rules(ByronSig.SignTx)).isLeft)
    assert(ByronRules.update(start, block, working).isRight)
    assert(NodeRules.byronStep(byronState, boot, header, raw, Some(working)).isRight)
  }

object ByronLedgerDefectKit:
  private val genesisKeys = Vector.fill(4)(key())
  private val delegates = Vector.fill(4)(key())
  private val holder = key()
  private val holderAddress = address(holder, Some(Magic))
  val genesis = ByronTestKit.genesis(
    genesisKeys,
    delegates,
    nonAvvm = Vector(holderAddress -> BigInt(10000000))
  )
  private val ledgerConfig = ByronLedger.Config(genesis, requiresNetworkMagic = true)
  private val transaction = aux(
    ledgerConfig,
    Seq((TxIn(Blake2b.hash256.hash(holderAddress), 0), holder, false)),
    Seq(holderAddress -> 9000000L)
  )

  /** A signed Byron block with one transaction and one update proposal. */
  val raw: Bytes = ByronTestKit.signedBlock(
    ledgerConfig,
    genesis,
    genesisKeys(0),
    delegates(0),
    transaction,
    ByronTestKit.signedProposal(ledgerConfig, delegates(0), (1, 0, 0)),
    (1, 0, 0)
  )

  val block: Block.Byron =
    Block.decode(raw, ledgerConfig.epochSlots) match
      case Right(b: Block.Byron) => b
      case other                 => throw new AssertionError(s"expected a Byron block: $other")

  val header: Header.Byron = block.header

  /** The rules over a ledger whose provider fails on the messages of one signing kind. */
  def rules(kind: Int): ByronRules.Config =
    ByronRulesConfig(
      ledgerConfig.copy(signatures = failingOn(kind, ledgerConfig.magic, providerFailure)),
      0.6,
      None
    )

  val working: ByronRules.Config = ByronRulesConfig(ledgerConfig, 0.6, None)

  private val initial =
    ByronLedger.initial(ledgerConfig).fold(e => throw new AssertionError(e.toString), identity)

  val start: ByronRules.State =
    ByronRules.State(
      Some(ByronTip(genesis.hash, 0, 0, boundary = true)),
      initial,
      PBftState.empty
    )

  val byronState: ByronState =
    ByronState.starting(initial).copy(tip = start.tip)

  val boot: BootstrapState =
    val text = new String(
      Files.readAllBytes(Path.of("fixtures/preview/genesis/shelley-genesis.json")),
      "UTF-8"
    )
    val shelley = Genesis.shelley(text).fold(e => throw new AssertionError(e.message), identity)
    BootstrapState.initial(
      LiteLedger.fromGenesis(shelley.raw).fold(e => throw new AssertionError(e.message), identity)
    )

  /** The block through the node's Byron step, over a failing provider for one signing kind. */
  def step(kind: Int) = NodeRules.byronStep(byronState, boot, header, raw, Some(rules(kind)))

  /** The failure the node's block rules give for the block over a provider failing on `kind`. */
  def failure(kind: Int): BlockFailure =
    step(kind).fold(identity, _ => throw new AssertionError("the failing provider was not met"))
