// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Value}
import lab.network.Era
import lab.node.Block
import xyz.kd5ujc.scarab.models.runtime.Codec

/** Whole blocks of every fixture, decoded by the models generated from the era CDDL, against the
  * hand-written block decoder: same transactions, same original body and witness bytes, same
  * transaction ids, same validity flags and auxiliary data positions.
  */
class BlockGoldenSuite extends munit.FunSuite:
  private final case class View(
      header: Bytes,
      bodies: Vector[Bytes],
      witnesses: Vector[Bytes],
      auxiliaryAt: Vector[Int],
      invalid: Vector[Int]
  )

  /** `[tag, block]` -> the block item. */
  private def inner(fetched: Bytes): Bytes =
    val root = Cbor.decode(fetched, Cbor.Limits(2500000, 2048, 1000000, 2500000)).toOption.get
    val Value.Arr(Vector(_, block)) = root.value: @unchecked
    block.original

  private def view(era: Era, block: Bytes): Either[runtime.DecodeError, View] =
    val limits = Cbor.Limits(2500000, 2048, 1000000, 2500000)
    era match
      case Era.Conway =>
        conway.Block.decodeBytes(block, limits).map { b =>
          View(
            b.header.raw,
            b.transactionBodies.map(_.raw),
            b.transactionWitnessSets.map(_.raw),
            b.auxiliaryDataSet.map(_._1.value),
            b.invalidTransactions.map(_.value)
          )
        }
      case Era.Babbage =>
        babbage.Block.decodeBytes(block, limits).map { b =>
          View(
            b.header.raw,
            b.transactionBodies.map(_.raw),
            b.transactionWitnessSets.map(_.raw),
            b.auxiliaryDataSet.map(_._1.value),
            b.invalidTransactions.map(_.value)
          )
        }
      case Era.Alonzo =>
        alonzo.Block.decodeBytes(block, limits).map { b =>
          View(
            b.header.raw,
            b.transactionBodies.map(_.raw),
            b.transactionWitnessSets.map(_.raw),
            b.auxiliaryDataSet.map(_._1.value),
            b.invalidTransactions.map(_.value)
          )
        }
      case other => fail(s"no block model for $other")

  private val files = Vector(
    "alonzo-first-20",
    "alonzo-txs",
    "babbage-transition",
    "babbage-mint",
    "conway",
    "conway-governance",
    "deeply-nested-datum",
    "duplicate-redeemer-conway",
    "empty-redeemers"
  )

  for file <- files do
    test(
      s"fixtures/preview/$file.hex: every block decodes to the same transactions as the hand-written decoder"
    ) {
      val fetched = Fixtures.hexLines(s"fixtures/preview/$file.hex")
      var txs = 0
      fetched.foreach { f =>
        val hand = Block.decodeShelley(f).fold(e => fail(e.message), identity) match
          case b: Block.Shelley => b
          case other            => fail(s"unexpected $other")
        val raw = inner(f)
        val v = view(hand.era, raw).fold(
          e =>
            fail(
              s"block ${hand.header.blockNo}: ${e.render} ;; deepest: ${e.deepest.render} ;; ${e.deepest.kind}"
            ),
          identity
        )
        assertEquals(v.header, hand.header.raw)
        assertEquals(
          v.bodies,
          hand.transactions.map(_.body),
          s"bodies of block ${hand.header.blockNo}"
        )
        assertEquals(v.witnesses, hand.transactions.map(_.witnesses))
        assertEquals(
          v.auxiliaryAt.sorted,
          hand.transactions.zipWithIndex.collect {
            case (t, i) if t.auxiliaryData.isDefined => i
          }.sorted
        )
        assertEquals(
          v.invalid.sorted,
          hand.transactions.zipWithIndex.collect { case (t, i) if !t.isValid => i }
        )
        // The transaction id is the hash of the original body bytes, not of a re-encoding.
        assertEquals(v.bodies.map(Blake2b.hash256.hash), hand.transactions.map(_.id))
        txs += v.bodies.size
      }
      assert(txs >= 0)
    }

  test("a block that is not the era's layout is a typed error pointing into the block") {
    val raw = inner(Fixtures.hexLines("fixtures/preview/conway.hex").head)
    val err = alonzo.Block
      .decodeBytes(raw, Cbor.Limits(2500000, 2048, 1000000, 2500000))
      .left
      .toOption
      .getOrElse(fail("expected error"))
    assert(err.path.nonEmpty)
    val _ = Codec
  }
