// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.protocol.Era

import ReferenceCorpus.{Case, Corpus}

/** A move-instantaneous-rewards certificate pays stake credentials a `DeltaCoin`, an `Integer` read
  * by cborg's `decodeInteger` (`Shelley/TxCert.hs:341-353`, `Coin.hs:115-119`, cardano-ledger
  * f649f975): an integer item of any head width or a bignum.
  *
  * `fixtures/reference/mir-amounts/cases.jsonl` holds a body of each era from Shelley to Babbage
  * whose MIR certificate writes its amount in each form, with what the unchanged released decoders
  * did with each. Evidence for #707.
  */
class MirAmountsReferenceSuite extends FunSuite:
  private val rows: Vector[Case] =
    ReferenceCorpus.load(Corpus.MirAmounts).fold(e => fail(e.toString), identity)

  test("the corpus covers every era with a MIR certificate, refused and accepted") {
    assertEquals(
      rows.map(_.era).toSet,
      Set(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage)
    )
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    for row <- rows do
      assertEquals(row.accepted, row.expect.isEmpty, row.name)
      assertEquals(row.accepted, row.rule.isEmpty, row.name)
      row.rule.foreach(r => assert(row.failure.exists(r.explains), s"${row.name}: ${row.failure}"))
    for era <- rows.map(_.era).distinct do
      assert(rows.exists(r => r.era == era && r.accepted && r.name.contains("bignum")), s"$era")
  }

  for row <- rows do
    test(s"${row.era}: ${row.name}") {
      (row.expect, TxBody.decode(row.raw, row.era)) match
        case (None, Right(body)) =>
          assertEquals(body.raw, row.raw, "the original bytes are retained")
          assert(
            body.certificates.exists {
              case Certificate.MoveRewards(_, _) => true; case _ => false
            },
            "the certificate is read"
          )
        case (None, Left(e))     => fail(s"the reference accepts this encoding: $e")
        case (Some(_), Right(_)) => fail(s"the reference refuses this encoding: ${row.failure}")
        case (Some(refusal), Left(e)) =>
          assertEquals(ReferenceCorpus.Refusal.of(e), Some(refusal), s"Scarab gave $e")
    }

  test("an amount written as a bignum is the integer it encodes") {
    val amounts = rows
      .filter(_.accepted)
      .map { row =>
        val certs = TxBody.decode(row.raw, row.era).fold(e => fail(e.toString), _.certificates)
        row.name.split(": ").last -> certs.collect { case Certificate.MoveRewards(_, Right(m)) =>
          m.values.toList
        }
      }
      .toMap
    assertEquals(amounts("bignum 1"), Vector(List(BigInt(1))))
    assertEquals(amounts("bignum 2^64"), Vector(List(BigInt(1) << 64)))
    assertEquals(amounts("bignum with leading zeros"), Vector(List(BigInt(5))))
    assertEquals(amounts("bignum, empty"), Vector(List(BigInt(0))))
    assertEquals(amounts("negative bignum -2"), Vector(List(BigInt(-2))))
    assertEquals(amounts("negative bignum -2^64 - 1"), Vector(List(-(BigInt(1) << 64) - 1)))
    assertEquals(amounts("negative, eight-byte head"), Vector(List(BigInt(-5))))
  }
