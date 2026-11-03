// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.DetachedRecordsReferenceSuite
import xyz.kd5ujc.scarab.network.{FailsOnUncaught, LocalStateQueryServer, Mux, Peers}
import xyz.kd5ujc.scarab.protocol.LocalStateQuery
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Role}

import DetachedRecordsReferenceSuite.{Kind, Row}
import LocalStateQuery.{Message, QueryReader, State}
import Peers.typed

/** A node-to-client query that carries a `RecD` record reads it as the reference does (#700). The
  * only such argument is the set of `GovActionId` of `GetProposals`, which the reference reads with
  * the era's decoder at Conway's lowest protocol version (`Shelley/Ledger/Query.hs:981`,
  * ouroboros-consensus 82ecba32): an id written as an empty indefinite list `9f ff` has its fields
  * after it, and one written as a non-empty indefinite list is refused (`decodeCount (RecD cn) n`,
  * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Coders.hs:482`;
  * `decodeListLikeT`, `Decoding/Decoder.hs:690-708`, cardano-ledger f649f975).
  *
  * The `govactionids` lines of `fixtures/reference/recd-detached/cases.jsonl` carry the reference's
  * verdict on each set and the ids it read. Each is put in the query `GetProposals`, bare and under
  * `GetCBOR`, and the query in `MsgQuery`: the codec must find where the query ends as the
  * reference's decoder of queries does, and the query must read the same ids.
  */
class NtcQueryRecordsSuite extends FailsOnUncaught:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private val rows: Vector[Row] =
    DetachedRecordsReferenceSuite.load.filter(_.kind == Kind.GovActionIds)

  /** `[0, [0, [6, q]]]`: `q` of Conway, in the hard-fork combinator's `QueryIfCurrent`. */
  private def inConway(q: Bytes): Bytes = hex("820082008206") ++ q

  /** `GetProposals` of `ids`: `[31, ids]`. */
  private def proposals(ids: Bytes): Bytes = inConway(hex("82181f") ++ ids)

  /** `GetProposals` of `ids` under `GetCBOR`: `[9, [31, ids]]`. */
  private def wrapped(ids: Bytes): Bytes = inConway(hex("820982181f") ++ ids)

  /** `MsgQuery` of `query`, `[3, query]`. */
  private def message(query: Bytes): Bytes = hex("8203") ++ query

  private def ids(query: NtcQuery): Option[Set[String]] = query match
    case NtcQuery.IfCurrent(6, LedgerQuery.GetProposals(set)) =>
      Some(set.map(id => s"${id.txId.hex}#${id.index}"))
    case NtcQuery.IfCurrent(6, LedgerQuery.GetCBOR(LedgerQuery.GetProposals(set))) =>
      Some(set.map(id => s"${id.txId.hex}#${id.index}"))
    case _ => None

  private def shown(ids: Option[Set[String]]): String =
    ids.fold("none")(_.toVector.sorted.mkString(","))

  private def decodeQuery(wire: Bytes): DecodeResult[LocalStateQuery.Message] =
    LocalStateQuery.decodePrefix(State.Acquired, Role.Client, wire, NtcQuery.reader)

  test("the corpus has GetProposals' ids, detached and not, accepted and refused") {
    assert(rows.exists(r => r.accepted && r.name.contains("detached")))
    assert(rows.exists(r => r.accepted && !r.name.contains("detached")))
    assert(rows.exists(r => !r.accepted && r.name.contains("indefinite id")))
    assert(rows.exists(r => !r.accepted && r.name.contains("detached")))
  }

  for
    row <- rows;
    (form, query) <- Vector("bare" -> proposals(row.raw), "GetCBOR" -> wrapped(row.raw))
  do
    test(s"${row.name}, $form: the query reads as the reference reads it") {
      val read = NtcQuery.decode(query)
      if row.accepted then
        assertEquals(read.map(ids), Right(row.actions.map(_.toSet)))
        // the message ends where the query does, and a message after it is read on its own
        val wire = message(query)
        assertEquals(decodeQuery(wire), DecodeResult.Decoded(Message.Query(query), wire.size))
        val release = hex("8105")
        assertEquals(
          decodeQuery(wire ++ release),
          DecodeResult.Decoded(Message.Query(query), wire.size)
        )
        // each part of the message, as a mux delivers it, waits for the rest
        for n <- 1 until wire.size do
          assertEquals(decodeQuery(wire.slice(0, n)), DecodeResult.NeedMore, s"$n bytes")
      else
        assert(read.isLeft, read.toString)
        // the codec frames it or refuses it; framed, the query is refused when it is read
        decodeQuery(message(query)) match
          case DecodeResult.Decoded(Message.Query(framed), _) =>
            assert(NtcQuery.decode(framed).isLeft)
          case DecodeResult.Decoded(other, _) => fail(s"not a query: $other")
          case DecodeResult.Failed(_)         => ()
          case DecodeResult.NeedMore          => ()
    }

  test("read as a tree, as before, a detached id ends the query early and the message after it") {
    // in a definite set; an indefinite one closes at its own break either way
    val detached = rows.filter(r =>
      r.accepted && r.name.contains("detached") && !r.raw.headOption.contains(0x9f.toByte)
    )
    assert(detached.nonEmpty)
    for row <- detached do
      val wire = message(proposals(row.raw))
      LocalStateQuery.decodePrefix(State.Acquired, Role.Client, wire, QueryReader.items) match
        case DecodeResult.Decoded(_, consumed) => assert(consumed < wire.size, row.name)
        case other                             => fail(s"${row.name}: $other")
  }

  test("an id written as a non-empty indefinite list, or with an index over 16 bits, is refused") {
    val txId = "5820" + "66" * 32
    assertEquals(
      NtcQuery.decode(proposals(hex("819f" + txId + "00ff"))).left.map(_.message),
      Left(NtcQuery.Error.IndefiniteRecord("gov action id").message)
    )
    assertEquals(
      NtcQuery.decode(proposals(hex("8182" + txId + "1a00010000"))),
      Left(NtcQuery.Error.Word16Range("gov action index"))
    )
    assertEquals(
      NtcQuery.decode(proposals(hex("8182581f" + "66" * 31 + "00"))),
      Left(NtcQuery.Error.InvalidTxId)
    )
  }

  test(
    "a session answers GetProposals of detached ids as it answers the same ids written definite"
  ) {
    // the server's own codec and runtime, with an answer that is the query as the node read it
    val detached = rows.filter(r => r.accepted && r.name.contains("detached"))
    val definite = rows.find(r => r.accepted && r.name == "conway govactionids an id")
    val source = new LocalStateQueryServer.Source[IO, Nothing]:
      def queries = NtcQuery.reader
      def acquire(target: LocalStateQuery.Target) =
        Resource.pure(
          Right(
            new LocalStateQueryServer.Session[IO, Nothing]:
              def answer(query: Bytes) =
                IO.pure(
                  Right(
                    NtcQuery
                      .decode(query)
                      .leftMap(e =>
                        LocalStateQueryServer.Refusal.Undecodable(e, query.hex.take(64))
                      )
                      .map(q => CborOut.build(_.text(shown(ids(q)))))
                  )
                )
          )
        )
    def ask(query: Bytes): IO[Either[xyz.kd5ujc.scarab.network.NetworkError, Bytes]] =
      typed {
        Peers.linked(protocols = Set(7)).use { (client, server) =>
          for
            c <- client.channel(7)
            s <- server.channel(7, Mux.Role.Responder)
            answered <- LocalStateQueryServer.run(s, source).background.use { _ =>
              val acquire = hex("8108")
              (c.send(acquire) *> c.recv(LocalStateQuery.MaxResultBytes)(
                LocalStateQuery.decodePrefix(State.Acquiring, Role.Server, _)
              ) *> c.send(message(query)) *> c.recv(LocalStateQuery.MaxResultBytes)(
                LocalStateQuery.decodePrefix(State.Querying, Role.Server, _)
              )).timeout(10.seconds)
            }
          yield answered match
            case Message.Result(result) => result
            case other                  => fail(s"not a result: $other")
        }
      }
    for
      plain <- definite.traverse(r => ask(proposals(r.raw)))
      each <- detached.traverse(r => ask(proposals(r.raw)).map(r -> _))
    yield
      assert(plain.exists(_.isRight), plain.toString)
      each.foreach { (row, answer) =>
        assertEquals(
          answer,
          Right(CborOut.build(_.text(shown(row.actions.map(_.toSet))))),
          row.name
        )
      }
  }
