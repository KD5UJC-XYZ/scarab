// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger



import java.nio.file.{Files, Path}
import cats.syntax.all.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes

import TestLedger.*
import xyz.kd5ujc.scarab.legacy.FlatTestLedger.*

/** The mainnet block at slot 4,495,760 (epoch 208, Shelley, protocol version 2, header hash
  * `fb4e87a00f0eea0d...`) holds transaction 49be5965c31a60c3, a pool registration whose reward
  * account is not on mainnet. The second mainnet sync from genesis stopped there with
  * `WrongNetworkPOOL`. The reference checks the network of the reward account only from protocol
  * version 5: `hardforkAlonzoValidatePoolAccountAddressNetID pv = pvMajor pv > natVersion @4`
  * (`Cardano.Ledger.Shelley.Era`), applied in `Cardano.Ledger.Shelley.Rules.Pool`
  * (`poolTransition`). The fixture is the block as the relay sent it
  * (`fixtures/mainnet/README.md`).
  */
class MainnetPoolNetworkSuite extends FunSuite:
  private val rs = RuleSet
  private val EpochSlots: BigInt = BigInt(21600)
  private val Mainnet = 1

  private val block: Block.Shelley =
    val hex = Files.readString(Path.of("fixtures/mainnet/shelley-pool-network-block.hex")).trim
    Block.decode(Bytes.fromHex(hex).fold(e => fail(e), identity), EpochSlots) match
      case Right(b: Block.Shelley) => b
      case other                   => fail(s"unexpected $other")

  private val tx: Tx =
    block.transactions
      .map(Tx.fromBlock(_, block.era).fold(e => fail(e), identity))
      .find(_.body.id.hex.startsWith("49be5965c31a60c3"))
      .getOrElse(fail("transaction 49be5965c31a60c3 is not in the block"))

  private val registrations: Vector[(Certificate.RegisterPool, Int)] =
    tx.body.certificates.zipWithIndex.collect { case (c: Certificate.RegisterPool, i) => c -> i }

  // the fixture holds one registration, so each is judged on the state it starts from
  private def register(pv: Int): Either[LedgerFailure, Unit] =
    registrations.traverse_ { case (cert, ix) =>
      rs.registerPool(state(pv, Vector.empty), cert, ix, env.copy(network = Mainnet), tx)
    }

  private def rule(r: Either[LedgerFailure, Unit]): String = r match
    case Left(LedgerFailure.Rejected(_, f)) => f.ruleName
    case Left(other)                        => other.message
    case Right(_)                           => "accepted"

  test(
    "the fixture is the block at slot 4,495,760 with a pool whose reward account is not mainnet"
  ) {
    assertEquals(block.header.slot, BigInt(4495760))
    assert(block.header.hash.hex.startsWith("fb4e87a00f0eea0d"), block.header.hash.hex)
    assertEquals(registrations.map(_._1.params.rewardAccount.network), Vector(0))
    assert(registrations.head._1.params.operator.hex.startsWith("ff5181ba"))
  }

  test("protocol versions 2 to 4 accept the registration: the network is not checked") {
    (2 to 4).foreach(pv => assertEquals(rule(register(pv)), "accepted", s"pv $pv"))
  }

  test("protocol version 5 and later reject it with WrongNetworkPOOL") {
    List(5, 6, 7, 8).foreach(pv => assertEquals(rule(register(pv)), "WrongNetworkPOOL", s"pv $pv"))
  }

