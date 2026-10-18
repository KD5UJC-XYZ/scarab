// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Cbor
import xyz.kd5ujc.scarab.models.runtime.DecodeError

class DomainDecodeContractSuite extends FunSuite:
  test("model adaptation retains the admitted domain cause object") {
    val cause = Cbor.Error.InvalidAdditionalInformation(31)
    assert(DecodeError.Kind.Domain(cause).cause eq cause)
  }

  test("model domain adaptation rejects arbitrary products") {
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.models.runtime.DecodeError.Kind.Domain("message")"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.models.runtime.DecodeError.Kind.Domain(Some("message"))"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """xyz.kd5ujc.scarab.models.runtime.DecodeError.Kind.Domain((1, "message"))"""
      ).nonEmpty
    )
  }
