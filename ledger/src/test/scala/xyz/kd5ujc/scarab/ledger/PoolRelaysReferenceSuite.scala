// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.Era

import ReferenceCorpus.{Case, Corpus, Refusal}

/** The reference decodes a pool registration's relays with the transaction body
  * (`decodeStakePoolParamsFlat`, `instance DecCBOR StakePoolRelay`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:406-421, 578-589`,
  * cardano-ledger f649f975), and so does Scarab ([[PoolRelays.fromBody]]).
  *
  * `fixtures/reference/pool-relays/cases.jsonl` holds an original body of each era with relays the
  * unchanged released decoders accept, and the same body with one relay changed: an address of the
  * wrong length or as one chunk, a port of 65536, a DNS name as one chunk or at and over the bound
  * of the era's decoder version. Evidence for #436.
  */
class PoolRelaysReferenceSuite extends FunSuite:
  private val rows: Vector[Case] =
    ReferenceCorpus.load(Corpus.PoolRelays).fold(e => fail(e.toString), identity)

  test("the corpus covers every era from Shelley to Conway, accepted and refused") {
    assertEquals(
      rows.map(_.era).toSet,
      Set(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
    )
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    assert(rows.exists(_.accepted) && rows.exists(!_.accepted))
    for row <- rows do
      assertEquals(row.accepted, row.expect.isEmpty, row.name)
      row.expect.foreach(e => assertEquals(e, Refusal.PoolRelay, row.name))
      row.rule.foreach(r => assert(row.failure.exists(r.explains), s"${row.name}: ${row.failure}"))
  }

  test("a DNS name of 65 bytes is refused below decoder version 9 and taken from it") {
    // [[1, null, name]] with a name of 65 bytes
    val name = "r" * 57 + ".example"
    val raw = Bytes(
      Vector(0x81, 0x83, 0x01, 0xf6, 0x78, 65).map(_.toByte) ++ name.getBytes("UTF-8").toVector
    )
    val node = CborRead.decode(raw).fold(e => fail(e.toString), identity)
    assertEquals(
      PoolRelays.fromBody(node, DecoderVersion.of(Era.Babbage)),
      Left(PoolRelayError.DnsNameTooLong(0, 65))
    )
    assertEquals(
      PoolRelays.fromBody(node, DecoderVersion.of(Era.Conway)),
      Right(Vector(PoolRelay.SingleHostName(None, name)))
    )
  }

  for row <- rows do
    test(s"${row.era}: ${row.name}") {
      (row.expect, TxBody.decode(row.raw, row.era)) match
        case (None, Right(body)) =>
          assertEquals(body.raw, row.raw, "the original bytes are retained")
          assertEquals(body.id, Blake2b.hash256.hash(row.raw), "the id hashes the original bytes")
        case (None, Left(e))     => fail(s"the reference accepts this encoding: $e")
        case (Some(_), Right(_)) => fail(s"the reference refuses this encoding: ${row.failure}")
        case (Some(refusal), Left(e)) =>
          assertEquals(ReferenceCorpus.Refusal.of(e), Some(refusal), s"Scarab gave $e")
    }
