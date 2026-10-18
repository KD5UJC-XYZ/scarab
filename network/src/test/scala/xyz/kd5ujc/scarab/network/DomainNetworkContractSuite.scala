// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Cbor

class DomainNetworkContractSuite extends FunSuite:
  test("service unavailability retains the admitted cause object") {
    val cause = Cbor.Error.InvalidAdditionalInformation(31)
    assert(LocalTxSubmissionServer.Unavailable.ScriptEvaluation(cause).cause eq cause)
  }

  test("local submission unavailability rejects arbitrary products") {
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.network.LocalTxSubmissionServer.Unavailable.ScriptEvaluation("message")"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.network.LocalTxSubmissionServer.Unavailable.ScriptEvaluation(Some("message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.network.LocalTxSubmissionServer.Unavailable.ScriptEvaluation((1, "message"))"""
      ).nonEmpty
    )
  }
