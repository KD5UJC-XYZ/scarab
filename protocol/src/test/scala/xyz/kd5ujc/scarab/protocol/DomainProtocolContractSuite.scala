// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Cbor

class DomainProtocolContractSuite extends FunSuite:
  test("query refusal and abort retain the admitted cause object") {
    val cause = Cbor.Error.InvalidAdditionalInformation(31)
    assert(QueryRefusal.Undecodable(cause, "00").cause eq cause)
    assert(QueryRefusal.Unwritable("query", cause).cause eq cause)
    assert(NetworkError.Closure.AbortedBy(cause).cause eq cause)
  }

  test("query refusal rejects strings options and tuples") {
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.protocol.QueryRefusal.Undecodable("message", "00")"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.protocol.QueryRefusal.Unwritable("query", "message")"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.protocol.QueryRefusal.Undecodable(Some("message"), "00")"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.protocol.QueryRefusal.Unwritable("query", Some("message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.protocol.QueryRefusal.Undecodable((1, "message"), "00")"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.protocol.QueryRefusal.Unwritable("query", (1, "message"))"""
      ).nonEmpty
    )
  }

  test("connection abort rejects arbitrary products") {
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.protocol.NetworkError.Closure.AbortedBy("message")"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.protocol.NetworkError.Closure.AbortedBy(Some("message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.protocol.NetworkError.Closure.AbortedBy((1, "message"))"""
      ).nonEmpty
    )
  }
