// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Credential, DRep, TxIn}
import munit.FunSuite

/** The query envelope the way `cardano-cli` (Haskell `encodeNodeToClient`) writes it. */
class NtcQuerySuite extends FunSuite:
  private val hash28 = Bytes(Vector.fill(28)(0xab.toByte))
  private val key = Credential.Key(hash28)

  /** `[0, [0, [6, <ledger query>]]]`: BlockQuery, QueryIfCurrent, Conway. */
  private def conway(query: CborOut => Unit): Bytes =
    CborOut.build { w =>
      w.array(2).uint(0).array(2).uint(0).array(2).uint(6)
      query(w)
    }

  private def credential(w: CborOut): CborOut = w.array(2).uint(0).bytes(hash28)

  private def decode(b: Bytes) = NtcQuery.decode(b)

  test("top-level queries: system start, block number, chain point, debug ledger config") {
    def top(tag: Int) = CborOut.build(_.array(1).uint(tag))
    assertEquals(decode(top(1)), Right(NtcQuery.SystemStart))
    assertEquals(decode(top(2)), Right(NtcQuery.ChainBlockNo))
    assertEquals(decode(top(3)), Right(NtcQuery.ChainPoint))
    assertEquals(decode(top(4)), Right(NtcQuery.DebugLedgerConfig))
  }

  test("hard-fork queries: the interpreter and the current era") {
    def hardFork(tag: Int) = CborOut.build(_.array(2).uint(0).array(2).uint(2).array(1).uint(tag))
    assertEquals(decode(hardFork(0)), Right(NtcQuery.HardFork(HardForkQuery.GetInterpreter)))
    assertEquals(decode(hardFork(1)), Right(NtcQuery.HardFork(HardForkQuery.GetCurrentEra)))
  }

  test("Conway queries without arguments, in the QueryIfCurrent envelope") {
    val cases = Vector(
      0 -> LedgerQuery.GetLedgerTip,
      1 -> LedgerQuery.GetEpochNo,
      3 -> LedgerQuery.GetCurrentPParams,
      7 -> LedgerQuery.GetUTxOWhole,
      16 -> LedgerQuery.GetStakePools,
      23 -> LedgerQuery.GetConstitution,
      24 -> LedgerQuery.GetGovState,
      29 -> LedgerQuery.GetAccountState,
      32 -> LedgerQuery.GetRatifyState,
      37 -> LedgerQuery.GetStakeDistribution2,
      38 -> LedgerQuery.GetMaxMajorProtocolVersion
    )
    cases.foreach { (tag, query) =>
      assertEquals(
        decode(conway(_.array(1).uint(tag))),
        Right(NtcQuery.IfCurrent(6, query)),
        tag.toString
      )
    }
  }

  test("sets are tag 258 arrays; a plain array is accepted too") {
    val expected = Right(NtcQuery.IfCurrent(6, LedgerQuery.GetStakeDelegDeposits(Set(key))))
    assertEquals(
      decode(conway { w =>
        w.array(2).uint(22).set(1); credential(w)
      }),
      expected
    )
    assertEquals(
      decode(conway { w =>
        w.array(2).uint(22).array(1); credential(w)
      }),
      expected
    )
    assertEquals(
      decode(conway { w => w.array(2).uint(22).set(0) }),
      Right(NtcQuery.IfCurrent(6, LedgerQuery.GetStakeDelegDeposits(Set.empty)))
    )
  }

  test("inputs, DReps, maybe-sets of pools and the committee query's three sets") {
    val txid = Bytes(Vector.fill(32)(0x11.toByte))
    assertEquals(
      decode(conway { w => w.array(2).uint(15).set(1).array(2).bytes(txid).uint(2) }),
      Right(NtcQuery.IfCurrent(6, LedgerQuery.GetUTxOByTxIn(Set(TxIn(txid, 2)))))
    )
    assertEquals(
      decode(conway { w =>
        w.array(2).uint(39).set(3).array(1).uint(2); w.array(1).uint(3);
        w.array(2).uint(0).bytes(hash28)
      }),
      Right(
        NtcQuery.IfCurrent(
          6,
          LedgerQuery.GetDRepDelegations(
            Set(DRep.AlwaysAbstain, DRep.AlwaysNoConfidence, DRep.Registered(key))
          )
        )
      )
    )
    assertEquals(
      decode(conway(_.array(2).uint(19).array(0))),
      Right(NtcQuery.IfCurrent(6, LedgerQuery.GetPoolState(None)))
    )
    assertEquals(
      decode(conway { w => w.array(2).uint(19).array(1).set(1).bytes(hash28) }),
      Right(NtcQuery.IfCurrent(6, LedgerQuery.GetPoolState(Some(Set(hash28)))))
    )
    assertEquals(
      decode(conway { w =>
        w.array(4).uint(27).set(0); w.set(0); w.set(1).uint(0)
      }),
      Right(
        NtcQuery.IfCurrent(6, LedgerQuery.GetCommitteeMembersState(Set.empty, Set.empty, Set(0)))
      )
    )
  }

  test("GetCBOR wraps another query; unknown tags and bad arguments are errors") {
    assertEquals(
      decode(conway(_.array(2).uint(9).array(1).uint(37))),
      Right(NtcQuery.IfCurrent(6, LedgerQuery.GetCBOR(LedgerQuery.GetStakeDistribution2)))
    )
    assert(decode(CborOut.build(_.array(1).uint(9))).isLeft)
    assert(decode(conway(_.array(1).uint(99))).isLeft)
    assert(decode(conway { w => w.array(2).uint(22).set(1).uint(5) }).isLeft)
    assert(decode(conway { w => w.array(2).uint(22).set(1).array(2).uint(7).bytes(hash28) }).isLeft)
    assert(decode(CborOut.build(_.map(0))).isLeft)
  }
