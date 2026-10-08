// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.unsafe.implicits.global
import java.nio.file.{Files, Path}

class ChainSyncSessionCommandSuite extends munit.FunSuite:
  private val root =
    if Files.exists(Path.of("fixtures/chain-sync")) then Path.of("fixtures/chain-sync")
    else Path.of("../fixtures/chain-sync")
  test("separate session CLI executes both finite peers and reports honest provenance") {
    ChainSyncSessionCommand
      .verify(root)
      .map { report =>
        Vector(
          "NtN14/protocol2:transitions=14,finalState=Done,doneObserved=true",
          "NtC16/protocol5:transitions=14,finalState=Done,doneObserved=true",
          "clientForkScenarios=2 responderBoundaryScenarios=2",
          "handshakeApplicationCoalescingChecked=true",
          "doneConsumedBeforeClose=true",
          "referenceRuntimeChecked=false",
          "cardanoHeaderValidated=false",
          "cardanoBlockValidated=false",
          "ledgerRollbackImplemented=false",
          "livePeerChecked=false"
        ).foreach(value => assert(report.contains(value), value))
        assert(!report.contains("transportReads=0"))
        assert(!report.contains("transportWrites=0"))
        assert(!report.contains("transportExchanges=0"))
      }
      .unsafeToFuture()
  }
  test("missing payload fails before sessions run") {
    ChainSyncSessionCommand
      .verify(root.resolve("missing-fixtures"))
      .attempt
      .map(result => assert(result.isLeft))
      .unsafeToFuture()
  }
