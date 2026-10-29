// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{DecodeField, LedgerDecodeError}

/** A node-to-client query argument reads its byte strings as definite-length strings: consensus
  * decodes the argument with `fromEraCBOR @era` (the `(2, 15)` GetUTxOByTxIn case of
  * `Ouroboros/Consensus/Shelley/Ledger/Query.hs:960` at ouroboros-consensus 82ecba32), which is
  * `eraDecoder @era decCBOR` at the era's *lowest* protocol version (`Era.hs:236-243`,
  * cardano-ledger f649f975) — 2..9, below the version 12 where `decodeBytes` starts accepting a
  * chunked string. Evidence: #437.
  */
class NtcQueryByteStringsSuite extends FunSuite:
  private val hash28 = Bytes(Vector.fill(28)(0xab.toByte))

  private def bytes(xs: Int*): Bytes = Bytes.fromArray(xs.map(_.toByte).toArray)

  private def chunked(b: Bytes): Bytes =
    // one definite-length chunk of `b`, wrapped in an indefinite (chunked) string
    val n = b.size
    val head =
      if n < 24 then bytes(0x40 | n)
      else if n < 256 then bytes(0x58, n)
      else bytes(0x59, n >> 8, n & 0xff)
    bytes(0x5f) ++ head ++ b ++ bytes(0xff)

  /** `[0, [0, [era, <ledger query>]]]`: BlockQuery, QueryIfCurrent, at any era index. */
  private def ifCurrent(era: Int)(query: CborOut => Unit): Bytes =
    CborOut.build { w =>
      w.array(2).uint(0).array(2).uint(0).array(2).uint(era)
      query(w)
    }

  /** The same envelope at Conway (era index 6), the era the node runs. */
  private def conway(query: CborOut => Unit): Bytes = ifCurrent(6)(query)

  /** The DRep query with a chunked key hash, under any era envelope. */
  private def chunkedDRepKeyHash(era: Int): Bytes =
    ifCurrent(era) { w =>
      w.array(2).uint(39).set(3).array(1).uint(2)
      w.array(1).uint(3)
      w.array(2).uint(0).raw(chunked(hash28))
    }

  test("a chunked input id is refused: the query argument reads a byte string as definite") {
    val txid = Bytes(Vector.fill(32)(0x11.toByte))
    assertEquals(
      NtcQuery.decode(conway(w => w.array(2).uint(15).set(1).array(2).raw(chunked(txid)).uint(2))),
      Left(NtcQuery.Error.InvalidTxIn(LedgerDecodeError.IndefiniteLength(DecodeField.InputId)))
    )
  }

  test("a chunked key hash in a DRep argument is refused by the query's own byte reader") {
    assertEquals(
      NtcQuery.decode(chunkedDRepKeyHash(6)),
      Left(NtcQuery.Error.IndefiniteBytes("key hash"))
    )
  }

  test("a chunked argument under a GetCBOR wrapper is still refused") {
    // GetCBOR (tag 9) wraps another query; `shelley` recurses into it, so this proves the policy
    // survives the recursion rather than being re-installed with a different answer.
    val txid = Bytes(Vector.fill(32)(0x11.toByte))
    assertEquals(
      NtcQuery.decode(
        conway(w => w.array(2).uint(9).array(2).uint(15).set(1).array(2).raw(chunked(txid)).uint(2))
      ),
      Left(NtcQuery.Error.InvalidTxIn(LedgerDecodeError.IndefiniteLength(DecodeField.InputId)))
    )
  }

  test("an era index this node does not know reads the definite policy too") {
    // #437 records this as our choice, not a reference verdict: the reference decodes an argument only
    // for an era it knows. The answer is the one every era this node knows gives, and this pins it.
    assertEquals(
      NtcQuery.decode(chunkedDRepKeyHash(99)),
      Left(NtcQuery.Error.IndefiniteBytes("key hash"))
    )
  }
