// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  ChainError,
  DecodeField,
  LedgerDecodeError,
  Tx,
  TxBody,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.mempack.{MemPack, TxOutMemPack}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.DecodeError
import xyz.kd5ujc.scarab.protocol.{DijkstraNotYet, Era, HfcHeader, NtNHeader, ProtocolError}
import xyz.kd5ujc.scarab.storage.haskell.state.{Envelope, HaskellStateCodecs, Params}

/** The Dijkstra era is named (index 7, block tag 8) but not implemented (#54): every reader that
  * resolves the era refuses its data with a typed [[DijkstraNotYet]], and the raw-bytes Leios paths
  * keep working.
  */
class DijkstraNotYetSuite extends FunSuite:
  private val raw = Bytes.fromArray(Array(0x82.toByte, 0x01, 0x02))

  private def cbor(value: Value): Bytes =
    Cbor.encode(value).fold(e => fail(e.toString), identity)

  private def node(value: Value): Node = Node(value, Bytes.empty)

  test("the era of index 7 and block tag 8 is Dijkstra, which has no invalid-transactions list") {
    assertEquals(Era.fromIndex(7), Some(Era.Dijkstra))
    assertEquals(Era.fromBlockTag(8), Some(Era.Dijkstra))
    assertEquals((HfcHeader.DijkstraIndex, HfcHeader.DijkstraBlockTag), (7, 8))
    assert(!Era.Dijkstra.hasInvalidTransactions)
    assert(Era.Conway.hasInvalidTransactions)
    assert(!Era.Dijkstra.usesTPraos)
  }

  test(
    "a header of index 7 is the envelope's raw Dijkstra header, and the closed codec's refusal"
  ) {
    val envelope = HfcHeader.encode(HfcHeader.Dijkstra(raw)).fold(e => fail(e.toString), identity)
    assertEquals(HfcHeader.decode(envelope), Right(HfcHeader.Dijkstra(raw)))
    assertEquals(
      NtNHeader.codec.decode(envelope),
      Left(ProtocolError.NotYet(DijkstraNotYet.Header))
    )
    assertEquals(
      NtNHeader.codec.encode(NtNHeader(Era.Dijkstra, raw, None)),
      Left(ProtocolError.NotYet(DijkstraNotYet.Header))
    )
    assertEquals(
      xyz.kd5ujc.scarab.ledger.Header.fromNtN(NtNHeader(Era.Dijkstra, raw, None), 21600),
      Left(ChainError.Decode(DecodeField.Header, LedgerDecodeError.NotYet(DijkstraNotYet.Header)))
    )
  }

  test("a block of tag 8 is refused by the ledger's block decoder") {
    val body = Value.Arr(Vector(node(Value.Arr(Vector.empty)), node(Value.Null), node(Value.Null)))
    val block = cbor(
      Value.Arr(Vector(node(Value.UInt(8)), node(Value.Arr(Vector(node(Value.Null), node(body))))))
    )
    assertEquals(
      Block.decodeShelley(block),
      Left(ChainError.Decode(DecodeField.Block, LedgerDecodeError.NotYet(DijkstraNotYet.Block)))
    )
  }

  test("a Dijkstra transaction, body or witness set is refused before it is read") {
    val refused = Left(LedgerDecodeError.NotYet(DijkstraNotYet.Transaction))
    val empty = cbor(Value.Map(Vector.empty))
    assertEquals(TxBody.decode(empty, Era.Dijkstra), refused)
    assertEquals(Witnesses.decode(empty, Era.Dijkstra), refused)
    assertEquals(Tx.fromEnvelope(empty, Era.Dijkstra), refused)
  }

  test("a Dijkstra ledger state is refused in the Haskell state layout") {
    def domain(result: Either[DecodeError, ?]): Option[DecodeError.Kind] =
      result.left.toOption.map(_.kind)
    val refused = Some(DecodeError.Kind.Domain(DijkstraNotYet.LedgerState))
    // a telescope of eight eras ends in Dijkstra
    val eight = cbor(Value.Arr(Vector.fill(8)(node(Value.Null))))
    assertEquals(
      domain(Envelope.telescope(HaskellStateCodecs.ledgerFileEra).decodeBytes(eight)),
      refused
    )
    val tip = cbor(Value.Arr(Vector(node(Value.UInt(7)), node(Value.Arr(Vector.empty)))))
    assertEquals(domain(HaskellStateCodecs.annTip.decodeBytes(tip)), refused)
    assertEquals(domain(Params.codec(ShelleyBasedEra.SDijkstra).decodeBytes(eight)), refused)
    assertEquals(domain(HaskellStateCodecs.newEpochState[Dijkstra].decodeBytes(eight)), refused)
    assert(Params.codec(ShelleyBasedEra.SDijkstra).check(Params.empty).isDefined)
    assertEquals(
      Params.complete(ShelleyBasedEra.SDijkstra, Params.empty, Params.empty),
      Left(DijkstraNotYet.LedgerState)
    )
    assertEquals(
      TxOutMemPack.decode[Dijkstra](Bytes.empty),
      Left(MemPack.Failure.NotYet(DijkstraNotYet.LedgerState))
    )
  }
