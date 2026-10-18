// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import io.circe.parser
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, ByronGenesisFile}
import xyz.kd5ujc.scarab.ledger.TxIn
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** Real mainnet Byron blocks with transactions (`fixtures/mainnet`): the first redemption of an
  * AVVM balance (epoch 0) and two ordinary transactions (epoch 20). The genesis file is trimmed to
  * the one AVVM entry the first block spends; its hash is therefore not the real genesis hash,
  * which nothing here depends on.
  *
  * The blocks are not contiguous, so header and PBFT rules are out of scope here; what they pin
  * down is the body: Merkle roots and witness hashes against the headers' proofs, the genesis UTxO
  * (the redeem address of the AVVM key is the one the chain spent), witness signatures and address
  * roots, and the minimum fee.
  */
class ByronMainnetSuite extends FunSuite:
  private val EpochSlots: BigInt = BigInt(2160) * 10 // 10k, k = 2160

  private val genesis = ByronGenesisFile
    .fromJson(
      parser
        .parse(Files.readString(Path.of("fixtures/mainnet/byron-genesis-trimmed.json")))
        .toOption
        .get,
      None
    )
    .fold(e => fail(e.message), identity)

  // mainnet does not require a network magic in addresses
  private val config = ByronLedger.Config(genesis, requiresNetworkMagic = false)

  private val blocks: Vector[Block.Byron] =
    Files
      .readAllLines(Path.of("fixtures/mainnet/byron-tx-blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map { l =>
        Block.decode(Bytes.fromHex(l).fold(e => fail(e.toString), identity), EpochSlots) match
          case Right(b: Block.Byron) => b
          case other                 => fail(s"unexpected $other")
      }

  /** The spent outputs, as the explorer API reports them (Koios `tx_info`). */
  private val inputs: Map[TxIn, ByronOut] =
    val json =
      parser.parse(Files.readString(Path.of("fixtures/mainnet/byron-tx-inputs.json"))).toOption.get
    json.asArray.get.map { j =>
      val c = j.hcursor
      TxIn(
        Bytes.fromHex(c.get[String]("txid").toOption.get).toOption.get,
        c.get[Int]("index").toOption.get
      ) -> ByronOut(
        Bytes.fromHex(c.get[String]("address").toOption.get).toOption.get,
        BigInt(c.get[String]("lovelace").toOption.get)
      )
    }.toMap

  private val genesisUtxo =
    ByronLedger.genesisUtxo(genesis, None).fold(e => fail(e.toString), identity)

  test("the fixture is three Byron main blocks with transactions") {
    assertEquals(blocks.size, 3)
    assert(blocks.forall(!_.header.boundary))
    assertEquals(blocks.map(_.header.slot), Vector(BigInt(3313), BigInt(432001), BigInt(432004)))
    assertEquals(blocks.map(b => ByronBody.decode(b.body).toOption.get.txs.size), Vector(1, 1, 2))
  }

  test("the AVVM redeem address of the key in the witness is the address the chain spent") {
    val spent = inputs.find((i, _) => i.id.hex.startsWith("a12a839c")).get
    assert(genesisUtxo.contains(spent._1), "the pseudo transaction id is the hash of the address")
    assertEquals(genesisUtxo(spent._1), spent._2)
    assertEquals(spent._2.value, BigInt(1000000))
  }

  test("body proofs: the Merkle root, the witnesses hash, the delegation and update hashes") {
    blocks.foreach { b =>
      val body = ByronBody.decode(b.body).fold(e => fail(e.toString), identity)
      assertEquals(ByronLedger.checkProofs(b.header, body), Right(()), s"slot ${b.header.slot}")
    }
  }

  test("a changed transaction no longer matches the proof in the header") {
    val b = blocks.head
    val body = ByronBody.decode(b.body).toOption.get
    val tx = body.txs.head
    val changed = tx.copy(tx =
      tx.tx.copy(raw = Bytes(tx.tx.raw.value.updated(40, (tx.tx.raw.value(40) ^ 1).toByte)))
    )
    assert(ByronLedger.checkProofs(b.header, body.copy(txs = Vector(changed))).isLeft)
    assert(ByronLedger.checkProofs(b.header, body.copy(txs = Vector.empty)).isLeft)
  }

  /** The transactions of all blocks in order against the genesis UTxO and the reported inputs. */
  private def applyAll(
      txs: Vector[ByronTxAux],
      utxo: Map[TxIn, ByronOut]
  ): Either[ByronError, Map[TxIn, ByronOut]] =
    txs.foldLeft[Either[ByronError, Map[TxIn, ByronOut]]](Right(utxo))((acc, tx) =>
      acc.flatMap(ByronLedger.applyTx(config, genesis.params, _, tx))
    )

  private lazy val allTxs = blocks.flatMap(b => ByronBody.decode(b.body).toOption.get.txs)
  private lazy val utxo = genesisUtxo ++ inputs

  test("every transaction validates: witnesses, address roots, value conservation, minimum fee") {
    val after = applyAll(allTxs, utxo).fold(e => fail(e.message), identity)
    // the inputs are spent, the outputs exist
    allTxs.flatMap(_.tx.inputs).foreach(i => assert(!after.contains(i), i.toString))
    allTxs.foreach(t =>
      assert(t.tx.outputs.indices.forall(ix => after.contains(TxIn(t.tx.id, ix))))
    )
  }

  test("the redemption pays no fee, the ordinary transactions pay at least the minimum") {
    val redeem = allTxs.head
    val paid = redeem.tx.outputs.map(_.value).sum
    assert(paid <= BigInt(1000000))
    allTxs.tail.foreach { t =>
      val in = t.tx.inputs.distinct.map(utxo(_).value).sum
      val fee = in - t.tx.outputs.map(_.value).sum
      assert(fee >= genesis.params.txFeePolicy.minimumFee(t.raw.size), s"${t.tx.id.hex}")
    }
  }

  test("tampering is caught: a signature, a missing input, a raised output") {
    val tx = allTxs.head
    val bad = tx.witnesses.head match
      case ByronWitness.Redeem(k, s) =>
        tx.copy(witnesses =
          Vector(ByronWitness.Redeem(k, Bytes(s.value.updated(9, (s.value(9) ^ 1).toByte))))
        )
      case other => fail(s"unexpected $other")
    assert(applyAll(Vector(bad), utxo).left.exists(_.isInstanceOf[ByronError.WrongSignature]))
    assert(
      applyAll(Vector(tx), genesisUtxo - tx.tx.inputs.head).left
        .exists(_.isInstanceOf[ByronError.MissingInput])
    )
    val richer =
      tx.copy(tx = tx.tx.copy(outputs = tx.tx.outputs.map(o => o.copy(value = o.value + 1))))
    assert(applyAll(Vector(richer), utxo).isLeft)
  }

  test("a verification-key transaction fails when its input's address is another key's") {
    val vk = allTxs(1)
    val swapped = utxo.updated(vk.tx.inputs.head, utxo(allTxs(2).tx.inputs.head))
    assert(
      applyAll(Vector(vk), swapped).left.exists(e =>
        e.isInstanceOf[ByronError.WrongKey] || e.isInstanceOf[ByronError.WrongSignature]
      )
    )
  }
