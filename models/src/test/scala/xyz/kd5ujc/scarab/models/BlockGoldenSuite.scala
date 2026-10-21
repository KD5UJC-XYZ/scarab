// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.Block
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
      auxiliary: Vector[(Int, Bytes)],
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
            b.auxiliaryDataSet
              .map((index, data) => index.value -> conway.AuxiliaryData.encodeUnchecked(data)),
            b.invalidTransactions.map(_.value)
          )
        }
      case Era.Babbage =>
        babbage.Block.decodeBytes(block, limits).map { b =>
          View(
            b.header.raw,
            b.transactionBodies.map(_.raw),
            b.transactionWitnessSets.map(_.raw),
            b.auxiliaryDataSet
              .map((index, data) => index.value -> babbage.AuxiliaryData.encodeUnchecked(data)),
            b.invalidTransactions.map(_.value)
          )
        }
      case Era.Alonzo =>
        alonzo.Block.decodeBytes(block, limits).map { b =>
          View(
            b.header.raw,
            b.transactionBodies.map(_.raw),
            b.transactionWitnessSets.map(_.raw),
            b.auxiliaryDataSet
              .map((index, data) => index.value -> alonzo.AuxiliaryData.encodeUnchecked(data)),
            b.invalidTransactions.map(_.value)
          )
        }
      case other => fail(s"no block model for $other")

  private val files = Vector(
    ("alonzo-first-20", 20, 2, 0),
    ("alonzo-txs", 2, 2, 0),
    ("babbage-transition", 30, 0, 0),
    ("babbage-mint", 1, 1, 1),
    ("conway", 30, 8, 4),
    ("conway-governance", 23, 54, 4),
    ("deeply-nested-datum", 1, 2, 1),
    ("duplicate-redeemer-conway", 1, 3, 1),
    ("empty-redeemers", 6, 17, 3)
  )

  for (file, expectedBlocks, expectedTransactions, expectedAuxiliary) <- files do
    test(
      s"fixtures/preview/$file.hex: every block decodes to the same transactions as the hand-written decoder"
    ) {
      val fetched = Fixtures.hexLines(s"fixtures/preview/$file.hex")
      assertEquals(fetched.size, expectedBlocks, "the complete original block corpus is required")
      val (transactions, auxiliary) = fetched.map { f =>
        val hand = Block.decodeShelley(f).fold(e => fail(e.message), identity) match
          case b: Block.Shelley => b
          case other            => fail(s"unexpected $other")
        assertEquals(
          hand.bodyHash,
          hand.header.bodyHash,
          "the original body matches its header commitment"
        )
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
        assertAuxiliary(
          v.auxiliary,
          hand.transactions.zipWithIndex.flatMap { case (transaction, index) =>
            transaction.auxiliaryData.map(index -> _)
          }
        )
        assertEquals(
          v.invalid.sorted,
          hand.transactions.zipWithIndex.collect { case (t, i) if !t.isValid => i }
        )
        // The transaction id is the hash of the original body bytes, not of a re-encoding.
        assertEquals(v.bodies.map(Blake2b.hash256.hash), hand.transactions.map(_.id))
        (v.bodies.size, v.auxiliary.size)
      }.unzip
      assertEquals(
        transactions.sum,
        expectedTransactions,
        "all original transactions are exercised"
      )
      assertEquals(
        auxiliary.sum,
        expectedAuxiliary,
        "all original auxiliary payloads are exercised"
      )
    }

  private def assertAuxiliary(actual: Vector[(Int, Bytes)], expected: Vector[(Int, Bytes)]): Unit =
    assertEquals(
      actual.sortBy(_._1),
      expected.sortBy(_._1),
      "auxiliary indices and complete payloads"
    )

  test("changing an auxiliary payload at the same index fails the complete comparison") {
    val original = files.iterator
      .flatMap { case (file, _, _, _) => Fixtures.hexLines(s"fixtures/preview/$file.hex") }
      .map { raw =>
        val block = Block.decodeShelley(raw).fold(e => fail(e.message), identity)
        view(block.era, inner(raw)).fold(e => fail(e.render), identity).auxiliary
      }
      .find(_.nonEmpty)
      .getOrElse(fail("the corpus must include auxiliary data"))
    val replacement = Bytes.fromHex("a10001").fold(e => fail(e.message), identity)
    val different =
      if original.head._2 == replacement then
        Bytes.fromHex("a10002").fold(e => fail(e.message), identity)
      else replacement
    val tampered = original.updated(0, original.head._1 -> different)
    assertEquals(tampered.map(_._1), original.map(_._1), "positions alone cannot detect corruption")
    intercept[munit.ComparisonFailException](assertAuxiliary(tampered, original))
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
