// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{NtcVersion, StateLayout}
import xyz.kd5ujc.scarab.protocol.LocalStateQuery.QueryPrefix

import NtcQueryPrefix.{Level, Refusal}

/** The ledger queries a session reads by build and version ([[QueryTable]]): under the Leios build
  * at version 23 its `decodeShelleyQuery` (`Shelley/Ledger/Query.hs:838-922`, ouroboros-consensus
  * 2416493b), which rejects tags 4, 5 and 21 and `[34]` and reads `GetStakePoolState` (40) and
  * `GetLeiosCommittee` (41); at versions 16 to 22 under that build, and under the 11.1 build at
  * every version, 11.1's (ouroboros-consensus 82ecba32), as before.
  */
class LeiosQueryTableSuite extends FunSuite:
  import LedgerQuery.*

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  /** `[0, [0, [era, q]]]`: a ledger query of `era`. */
  private def inEra(era: QueryEra, q: String): Bytes = hex(f"8200820082${era.index}%02x" + q)

  private val pool = "11" * 28
  private val other = "22" * 28
  // `Just (Set.fromList [pool, other])` with `fromCBOR`: a list of one, the set under tag 258
  private val justPools = "81" + "d9010282" + "581c" + pool + "581c" + other

  private val removed = Vector("8104", "8105", "821580", "811822")

  private def leios(raw: Bytes) = NtcQuery.decode(raw, StateLayout.Leios, NtcVersion.V23)

  test("the table: the Leios build's at version 23 only under it, 11.1's otherwise") {
    NtcVersion.All.foreach { v =>
      assertEquals(QueryTable.of(StateLayout.Node11_1, v), QueryTable.Node11_1, v.toString)
      val expected = if v == NtcVersion.V23 then QueryTable.Leios else QueryTable.Node11_1
      assertEquals(QueryTable.of(StateLayout.Leios, v), expected, v.toString)
    }
  }

  test("at version 23 of the Leios build, tags 4, 5 and 21 are refused as unknown, in every era") {
    QueryEra.values.filter(_ != QueryEra.Byron).foreach { era =>
      Vector(4 -> "8104", 5 -> "8105", 21 -> "821580").foreach { (tag, q) =>
        assertEquals(leios(inEra(era, q)), Left(NtcQuery.Error.UnknownTag("ledger query", tag)))
      }
    }
  }

  test("at version 23 of the Leios build, GetLedgerPeerSnapshot needs its peer kind") {
    assertEquals(leios(inEra(QueryEra.Dijkstra, "811822")), Left(NtcQuery.Error.PeerKindRequired))
    assertEquals(
      leios(inEra(QueryEra.Dijkstra, "82182201")),
      Right(NtcQuery.IfCurrent(QueryEra.Dijkstra, GetLedgerPeerSnapshot(LedgerPeerKind.Big)))
    )
  }

  test("at version 23 of the Leios build, GetStakePoolState reads its pools and its Bool") {
    val cases = Vector(
      "83182880f5" -> GetStakePoolState(None, PoolDelegators.Included),
      "83182880f4" -> GetStakePoolState(None, PoolDelegators.Omitted),
      ("831828" + justPools + "f4") ->
        GetStakePoolState(Some(Set(hex(pool), hex(other))), PoolDelegators.Omitted)
    )
    QueryEra.values.filter(_ != QueryEra.Byron).foreach { era =>
      cases.foreach { (q, expected) =>
        assertEquals(leios(inEra(era, q)), Right(NtcQuery.IfCurrent(era, expected)), q)
      }
    }
  }

  test(
    "GetStakePoolState is refused with another arity, a Bool that is not one, or pools unsorted"
  ) {
    val unsorted = "81" + "d9010282" + "581c" + other + "581c" + pool
    assertEquals(
      leios(inEra(QueryEra.Dijkstra, "82182880")),
      Left(NtcQuery.Error.Arity("ledger query 40", 3, 2))
    )
    assertEquals(
      leios(inEra(QueryEra.Dijkstra, "83182880" + "01")),
      Left(NtcQuery.Error.ExpectedBool("delegators"))
    )
    assertEquals(
      leios(inEra(QueryEra.Dijkstra, "831828" + unsorted + "f5")),
      Left(NtcQuery.Error.NotAscending("pools"))
    )
  }

  test("at version 23 of the Leios build, GetLeiosCommittee is read, and only without arguments") {
    QueryEra.values.filter(_ != QueryEra.Byron).foreach { era =>
      assertEquals(leios(inEra(era, "811829")), Right(NtcQuery.IfCurrent(era, GetLeiosCommittee)))
    }
    assertEquals(
      leios(inEra(QueryEra.Dijkstra, "82182900")),
      Left(NtcQuery.Error.Arity("ledger query 41", 1, 2))
    )
  }

  test("inside GetCBOR the Leios build's table holds too") {
    assertEquals(
      leios(inEra(QueryEra.Dijkstra, "8209" + "8209" + "811829")),
      Right(NtcQuery.IfCurrent(QueryEra.Dijkstra, GetCBOR(GetCBOR(GetLeiosCommittee))))
    )
    assertEquals(
      leios(inEra(QueryEra.Dijkstra, "8209" + "8104")),
      Left(NtcQuery.Error.UnknownTag("ledger query", 4))
    )
  }

  test(
    "at versions 16 to 22 of the Leios build and under 11.1, the queries read as 11.1 reads them"
  ) {
    for
      layout <- StateLayout.values.toVector
      v <- NtcVersion.All if !(layout == StateLayout.Leios && v == NtcVersion.V23)
    do
      removed.foreach { q =>
        val raw = inEra(QueryEra.Dijkstra, q)
        assertEquals(NtcQuery.decode(raw, layout, v), NtcQuery.decode(raw), s"$layout $v $q")
        assert(NtcQuery.decode(raw).isRight, q)
      }
      assertEquals(
        NtcQuery.decode(inEra(QueryEra.Dijkstra, "83182880f5"), layout, v),
        Left(NtcQuery.Error.UnknownTag("ledger query", 40))
      )
      assertEquals(
        NtcQuery.decode(inEra(QueryEra.Dijkstra, "811829"), layout, v),
        Left(NtcQuery.Error.UnknownTag("ledger query", 41))
      )
  }

  test(
    "a prefix: the Leios build's table refuses the removed queries and waits on the added ones"
  ) {
    def prefix(table: QueryTable, q: String) =
      NtcQueryPrefix.prefix(inEra(QueryEra.Dijkstra, q))(using table)
    Vector("8104" -> (1, 4), "8105" -> (1, 5), "8215" -> (2, 21), "811822" -> (1, 34)).foreach {
      case (q, (size, tag)) =>
        assertEquals(
          prefix(QueryTable.Leios, q),
          QueryPrefix.Refused(Refusal.Constructor(Level.Ledger, size, tag)),
          q
        )
        assertEquals(prefix(QueryTable.Node11_1, q), QueryPrefix.Short, q)
    }
    Vector("831828" -> (3, 40), "811829" -> (1, 41)).foreach { case (q, (size, tag)) =>
      assertEquals(prefix(QueryTable.Leios, q), QueryPrefix.Short, q)
      assertEquals(
        prefix(QueryTable.Node11_1, q),
        QueryPrefix.Refused(Refusal.Constructor(Level.Ledger, size, tag)),
        q
      )
    }
    assertEquals(prefix(QueryTable.Leios, "8209" + "8104"), prefix(QueryTable.Leios, "8104"))
  }

  test("a session's reader follows its table") {
    val removedQuery = inEra(QueryEra.Dijkstra, "8104")
    assertEquals(
      NtcQuery.readerFor(QueryTable.of(StateLayout.Leios, NtcVersion.V23)).prefix(removedQuery),
      QueryPrefix.Refused(Refusal.Constructor(Level.Ledger, 1, 4))
    )
    assertEquals(
      NtcQuery.readerFor(QueryTable.of(StateLayout.Leios, NtcVersion.V22)).prefix(removedQuery),
      QueryPrefix.Short
    )
  }
