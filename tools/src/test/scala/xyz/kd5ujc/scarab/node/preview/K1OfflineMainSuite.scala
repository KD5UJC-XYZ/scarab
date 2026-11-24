// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.preview

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.{PlutusStats, PreparedBlockFixture}

class K1OfflineMainSuite extends FunSuite:
  test("genesis replay checks the first parent and every subsequent stored link") {
    val hash = Bytes.fromArray(Array.fill[Byte](32)(1))
    val first = PreparedBlockFixture.header.copy(blockNo = 0, slot = 0, prevHash = None)
    assert(K1OfflineMain.link(None, None, first).isRight)
    assert(K1OfflineMain.link(None, Some(hash), first) match {
      case Left(K1OfflineMain.Error.InitialParent(Some(_), None)) => true
      case _                                                      => false
    })
    assert(K1OfflineMain.link(None, None, first.copy(blockNo = 1)) match {
      case Left(K1OfflineMain.Error.BlockOrder(_, _)) => true
      case _                                          => false
    })
    val previous = BlockRef(0, hash, 0)
    val next = first.copy(blockNo = 1, slot = 1, prevHash = Some(hash))
    assert(K1OfflineMain.link(Some(previous), None, next).isRight)
    assert(K1OfflineMain.link(Some(previous), None, next.copy(prevHash = None)) match {
      case Left(K1OfflineMain.Error.Link(ReplayInputs.Error.PreviousHash(_, None))) => true
      case _                                                                        => false
    })
    assert(K1OfflineMain.link(Some(previous), None, next.copy(slot = 0)) match {
      case Left(K1OfflineMain.Error.Link(ReplayInputs.Error.SlotOrder(_, _))) => true
      case _                                                                  => false
    })
    assert(K1OfflineMain.link(Some(previous), None, next.copy(blockNo = 2)) match {
      case Left(K1OfflineMain.Error.Link(ReplayInputs.Error.BlockNumber(_, _))) => true
      case _                                                                    => false
    })
  }

  test("observer totals must equal applied state counters including a nonzero initial baseline") {
    val initial = PlutusStats(10, 2, 45, 100)
    val applied = PlutusStats(12, 3, 52, 103)
    val observed = ReplayTrace.Counts(
      reachedTransactions = 3,
      evaluatedScripts = 7,
      failedTransactions = 1,
      failedScripts = 2
    )
    assert(K1OfflineMain.reconcile(initial, applied, observed).isRight)
    assert(K1OfflineMain.reconcile(initial, applied, observed.copy(reachedTransactions = 2)) match {
      case Left(K1OfflineMain.Error.ObservationCounters(_, _, _)) => true
      case _                                                      => false
    })
    assert(K1OfflineMain.reconcile(initial, applied, observed.copy(evaluatedScripts = 6)) match {
      case Left(K1OfflineMain.Error.ObservationCounters(_, _, _)) => true
      case _                                                      => false
    })
    assert(K1OfflineMain.reconcile(initial, applied, observed.copy(failedTransactions = 0)) match {
      case Left(K1OfflineMain.Error.ObservationCounters(_, _, _)) => true
      case _                                                      => false
    })
    assert(
      K1OfflineMain.reconcile(initial, applied, observed.copy(unavailableTransactions = 1)) match {
        case Left(K1OfflineMain.Error.ObservationCounters(_, _, _)) => true
        case _                                                      => false
      }
    )
    assert(K1OfflineMain.reconcile(initial, applied, observed.copy(collectionFailures = 1)) match {
      case Left(K1OfflineMain.Error.ObservationCounters(_, _, _)) => true
      case _                                                      => false
    })
  }
