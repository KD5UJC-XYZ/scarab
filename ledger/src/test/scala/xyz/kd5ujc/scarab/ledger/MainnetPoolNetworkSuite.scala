// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import cats.syntax.all.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.rules.{CertEnv, EraNes, NativeRules, Pool, PoolCert, Sts}
import xyz.kd5ujc.scarab.ledger.state.{LedgerState, ShelleyBasedEra}

import TestLedger.*

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
  private val EpochSlots: BigInt = BigInt(21600)
  private val Mainnet = 1

  private val block: Block.Shelley =
    val hex = Files.readString(Path.of("fixtures/mainnet/shelley-pool-network-block.hex")).trim
    Block.decode(Bytes.fromHex(hex).fold(e => fail(e.toString), identity), EpochSlots) match
      case Right(b: Block.Shelley) => b
      case other                   => fail(s"unexpected $other")

  private val tx: Tx =
    block.transactions
      .map(Tx.fromBlock(_, block.era).fold(e => fail(e.toString), identity))
      .find(_.body.id.hex.startsWith("49be5965c31a60c3"))
      .getOrElse(fail("transaction 49be5965c31a60c3 is not in the block"))

  private val registrations: Vector[Certificate.RegisterPool] =
    tx.body.certificates.collect { case c: Certificate.RegisterPool => c }

  /** POOL on the registrations of the transaction, on mainnet, with the parameters of protocol
    * version `pv` in force. The fixture holds one registration, so each is judged on the pool state
    * of the empty ledger of that version.
    */
  private def register(pv: Int): Either[LedgerFailure, Unit] =
    val ctx = env.copy(network = Mainnet)
    NativeRules.state(pv, Vector.empty) match
      case i: EraNes.InEra[x] =>
        given ShelleyBasedEra[x] = i.era
        val pools = LedgerState.pState[x].get(i.nes.es.ls)
        registrations.traverse_ { cert =>
          Sts(Pool)(
            NativeRules.globalsOf(ctx),
            CertEnv(tx.body.id, TestLedger.params(pv), ctx.epoch, None, Map.empty),
            pools,
            PoolCert.RegPool(cert.params)
          )
        }

  test(
    "the fixture is the block at slot 4,495,760 with a pool whose reward account is not mainnet"
  ) {
    assertEquals(block.header.slot, BigInt(4495760))
    assert(block.header.hash.hex.startsWith("fb4e87a00f0eea0d"), block.header.hash.hex)
    assertEquals(registrations.map(_.params.rewardAccount.network), Vector(0))
    assert(registrations.head.params.operator.hex.startsWith("ff5181ba"))
  }

  test("protocol versions 2 to 4 accept the registration: the network is not checked") {
    (2 to 4).foreach(pv => assertEquals(register(pv), Right(()), s"pv $pv"))
  }

  test("protocol version 5 and later reject it with WrongNetworkPOOL") {
    val operator = registrations.head.params.operator
    List(5, 6, 7, 8).foreach { pv =>
      register(pv) match
        case Left(
              LedgerFailure.Rejected(
                _,
                PredicateFailure.Certs(
                  CertsFailure.Cert(
                    CertFailure.Pool(PoolFailure.WrongNetworkPool(Mismatch(0, Mainnet), pool))
                  )
                )
              )
            ) =>
          assertEquals(pool, operator, s"pv $pv")
        case other => fail(s"pv $pv: $other")
    }
  }
