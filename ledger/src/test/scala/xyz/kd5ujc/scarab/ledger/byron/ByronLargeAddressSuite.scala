// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import io.circe.parser
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, ByronGenesisFile, TxIn}

/** The mainnet block at slot 1,769,882 (Byron epoch 81, header hash `f277604972be4b11...`), the
  * first block that a sync from genesis refused ("block body does not decode: input byte limit
  * exceeded"). One of its transaction outputs pays an address of 7,689 bytes: the derivation path
  * attribute (key 1) alone is 7,641 bytes. The reference accepts it, as it has no size limit on an
  * address: `Cardano.Chain.Common.Address` (`DecCBOR Address`) and `Common.Attributes`; the ledger
  * rule bounds only the attributes it does not interpret (`unknownAttributesLength < 128`,
  * `Cardano.Chain.UTxO.Validation.validateTxOutNM`), and the derivation path is not one of them.
  * The fixture is the block as the relay sent it (`fixtures/mainnet/README.md`).
  */
class ByronLargeAddressSuite extends FunSuite:
  private val EpochSlots: BigInt = BigInt(2160) * 10

  private val genesis =
    ByronGenesisFile
      .fromJson(
        parser
          .parse(Files.readString(Path.of("fixtures/mainnet/byron-genesis-trimmed.json")))
          .toOption
          .get,
        None
      )
      .fold(e => fail(e.message), identity)

  private val config = ByronLedger.Config(genesis, requiresNetworkMagic = false)

  /** The output the transaction spends (3,000,000 lovelace), as Koios `tx_utxos` reports it. */
  private val inputs: Map[TxIn, ByronOut] =
    parser
      .parse(Files.readString(Path.of("fixtures/mainnet/byron-large-address-inputs.json")))
      .toOption
      .get
      .asArray
      .get
      .map { j =>
        val c = j.hcursor
        TxIn(
          Bytes.fromHex(c.get[String]("txid").toOption.get).toOption.get,
          c.get[Int]("index").toOption.get
        ) -> ByronOut(
          Bytes.fromHex(c.get[String]("address").toOption.get).toOption.get,
          BigInt(c.get[String]("lovelace").toOption.get)
        )
      }
      .toMap

  private val block: Block.Byron =
    val hex = Files.readString(Path.of("fixtures/mainnet/byron-large-address-block.hex")).trim
    Block.decode(Bytes.fromHex(hex).fold(e => fail(e.toString), identity), EpochSlots) match
      case Right(b: Block.Byron) => b
      case other                 => fail(s"unexpected $other")

  private lazy val body = ByronBody.decode(block.body).fold(e => fail(e.toString), identity)

  /** The outputs whose address is larger than the 4096 bytes the decoder used to accept. */
  private lazy val large =
    body.txs.flatMap(_.tx.outputs).filter(_.address.size > 4096)

  test("the fixture is the block at slot 1,769,882 and its body decodes") {
    assertEquals(block.header.slot, BigInt(1769882))
    assert(block.header.hash.hex.startsWith("f277604972be4b11"), block.header.hash.hex)
    assertEquals(ByronLedger.checkProofs(block.header, body), Right(()))
  }

  test("an output address of 7,689 bytes with a 7,641-byte derivation path decodes") {
    assertEquals(large.map(_.address.size), Vector(7689))
    val a = ByronAddress.decode(large.head.address).fold(e => fail(e.toString), identity)
    assertEquals(a.unknownAttributesLength, 0) // key 1 is interpreted: it does not count
    assertEquals(a.networkMagic, None)
    assertEquals(a.addrType, ByronAddress.VerKey)
    assert(a.attributes.size > 7641)
  }

  test("the output rules accept every output of the block, as the reference does") {
    body.txs.foreach(t => assertEquals(ByronLedger.outputs(config, t.tx), Right(())))
  }

  test("the transaction validates in full: witness, fee (922,917), value conservation") {
    // the genesis maximum transaction size is 4096; the transaction is 7,792 bytes, so the
    // parameters of the time (raised by an update proposal) are needed
    val params = genesis.params.copy(maxTxSize = BigInt(65536))
    val after = ByronLedger.applyTx(config, params, inputs, body.txs.head)
    assert(after.isRight, after.left.map(_.message).toString)
    assertEquals(after.map(_.size), Right(2))
  }

  test("an address beyond what the body can hold is still a typed decode failure") {
    val tooBig = Bytes(Vector.fill((1 << 23) + 1)(0.toByte))
    assert(ByronAddress.decode(tooBig).isLeft)
    assertEquals(
      ByronLedger.outputs(config, body.txs.head.tx.copy(outputs = Vector(ByronOut(tooBig, 1)))),
      Left(
        ByronError.Decode(
          xyz.kd5ujc.scarab.ledger.LedgerDecodeError
            .Syntax(xyz.kd5ujc.scarab.codec.Cbor.Error.InputLimitExceeded)
        )
      )
    )
  }
