// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, CollidingBytes, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxWits
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayLedgerPredFailure,
  ConwayUtxoPredFailure,
  ConwayUtxowPredFailure
}
import xyz.kd5ujc.scarab.node.mempool.{Rejection, Source, TxKit}
import xyz.kd5ujc.scarab.node.ntc.NtcQuery
import xyz.kd5ujc.scarab.protocol.{NtcVersion, StateLayout}

import SharedHashCodeInputs.*

/** What a peer or a local client sends costs the same whatever the hash codes of the byte strings
  * in it: the decoders find repeats by sorting, as the reference's `Set.fromList` and
  * `Map.fromList` do, and the rules sum the values a transaction spends in sorted maps. Every input
  * here holds byte strings of one `Bytes.hashCode`, which a hash set or map holds in one bucket, so
  * each check would cost the square of their count.
  *
  * The bound is wall-clock time read from `Clock[IO]`. On a loaded 24-core host each operation took
  * under 50 ms on these inputs, and from 12 seconds to a minute where it was quadratic, so a bound
  * of 3 s leaves a margin of over 50 times for the operations as they are and of several times for
  * a quadratic one, which grows by four for each doubling of the input.
  */
class SharedHashCodeSuite extends CatsEffectSuite:
  override val munitIOTimeout: Duration = 10.minutes

  private val bound = 3.seconds
  private val n = 40000
  private val colliding: Strings = CollidingBytes.of

  private def within[A](body: => A): IO[A] =
    IO(body).timed.flatMap { (took, result) =>
      IO(assert(took < bound, s"took ${took.toMillis} ms, more than ${bound.toMillis} ms"))
        .as(result)
    }

  test("a fetched body whose witness datums share one hash code is refused by its body hash") {
    val raw = datumsBody(colliding, n)
    within(BlockTransport.check(conway.header, raw)).map {
      case Left(BlockTransportError.WrongBodyHash(_, _)) => ()
      case other => fail(s"expected WrongBodyHash, got ${other.left.map(_.productPrefix)}")
    }
  }

  test("a fetched body whose certificates name credentials of one hash code is refused") {
    val raw = certificatesBody(colliding, n)
    within(BlockTransport.check(conway.header, raw)).map {
      case Left(BlockTransportError.WrongBodyHash(_, _)) => ()
      case other => fail(s"expected WrongBodyHash, got ${other.left.map(_.toString.take(200))}")
    }
  }

  test("a fetched body whose proposals name accounts of one hash code is refused") {
    val raw = proposalsBody(colliding, n)
    within(BlockTransport.check(conway.header, raw)).map {
      case Left(BlockTransportError.WrongBodyHash(_, _)) => ()
      case other => fail(s"expected WrongBodyHash, got ${other.left.map(_.toString.take(200))}")
    }
  }

  test("a fetched body whose auxiliary data names byte strings of one hash code is refused") {
    val raw = auxiliaryKeysBody(colliding, 2 * n)
    within(BlockTransport.check(conway.header, raw)).map {
      case Left(
            BlockTransportError.Decode(
              ChainError.Decode(_, LedgerDecodeError.TransactionIndex(CborValue.ByteString(_), 1))
            )
          ) =>
        ()
      case other => fail(s"expected TransactionIndex, got ${other.left.map(_.toString.take(200))}")
    }
  }

  test("a value of more names than compact, all of one hash code, is refused by its size") {
    val node: Node = Cbor.decode(mint(colliding, n)).fold(e => fail(e.message), identity)
    within(
      Value.multiasset(node, DecodeField.Mint, signed = true, Value.Decoding.Rejecting)(using
        ByteStrings.Definite
      )
    ).map {
      case Left(LedgerDecodeError.MultiAssetTooBig(_, count, 1)) => assertEquals(count, n)
      case other => fail(s"expected MultiAssetTooBig, got ${other.left.map(_.productPrefix)}")
    }
  }

  test("a pre-Conway mint of names of one hash code, all of quantity zero, is read") {
    val node: Node = Cbor.decode(mint(colliding, n, 0)).fold(e => fail(e.message), identity)
    within(
      Value.multiasset(node, DecodeField.Mint, signed = true, Value.Decoding.Pruning)(using
        ByteStrings.Definite
      )
    ).map(assets => assertEquals(assets, Right(Map.empty[Bytes, Map[Bytes, BigInt]])))
  }

  test("a pre-Conway mint of policies of one hash code, all left empty, is read") {
    val node: Node = Cbor
      .decode(emptyPolicies(colliding, n), Cbor.Limits.Unbounded)
      .fold(e => fail(e.message), identity)
    within(
      Value.multiasset(node, DecodeField.Mint, signed = true, Value.Decoding.Pruning)(using
        ByteStrings.Definite
      )
    ).map(assets => assertEquals(assets, Right(Map.empty[Bytes, Map[Bytes, BigInt]])))
  }

  test("the inputs a client asks for, of one hash code, are found missing from the set") {
    val asked = inputs(colliding, 3 * n / 2)
    within(Utxo.empty.missing(asked)).map(missing => assertEquals(missing.size, asked.size))
  }

  test("an unbalanced transaction spending names of one hash code is refused by the ledger") {
    val (pool, raw) = unbalanced(colliding, 80)
    within(pool.add(raw, TxKit.env, Source.Remote)).map {
      case Left(Rejection.ConwayRefused(_, failures)) =>
        assert(
          failures.exists {
            case ConwayLedgerPredFailure.ConwayUtxowFailure(
                  ConwayUtxowPredFailure.UtxoFailure(ConwayUtxoPredFailure.ValueNotConservedUTxO(_))
                ) =>
              true
            case _ => false
          },
          s"expected the value not to be conserved, got ${failures.map(_.toString.take(80))}"
        )
      case other => fail(s"expected a ledger refusal, got $other")
    }
  }

  test("a Dijkstra witness set of vkeys of one hash code decodes") {
    val node = Cbor
      .decode(dijkstraWitnesses(colliding, n), Cbor.Limits.Unbounded)
      .fold(e => fail(e.message), identity)
    within(DijkstraTxWits.decode(node)).map(w => assertEquals(w.map(_.vkeys.size), Right(n)))
  }

  test("a query for pools by key hashes of one hash code decodes") {
    val raw = poolsQuery(colliding, 3 * n / 2)
    within(NtcQuery.decode(raw, StateLayout.Node11_1, NtcVersion.V23))
      .map(q => assert(q.isRight, q.left.map(_.message)))
  }

  test("a query for accounts by credentials of one hash code decodes") {
    val raw = accountsQuery(colliding, n)
    within(NtcQuery.decode(raw, StateLayout.Node11_1, NtcVersion.V23))
      .map(q => assert(q.isRight, q.left.map(_.message)))
  }
