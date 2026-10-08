// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.Bytes

class ClusterTransferSuite extends munit.FunSuite:
  private val digest = Bytes(Vector.fill(32)(1.toByte))
  private def context(
      major: Int = 9,
      postEpoch: BigInt = 0,
      postSlot: BigInt = 2,
      beforeFees: BigInt = 0,
      afterFees: BigInt = 200000
  ) =
    ClusterTransfer.Context.checked(
      digest,
      digest,
      1082026,
      digest,
      digest,
      1,
      postSlot,
      0,
      postEpoch,
      major,
      0,
      1,
      0,
      16384,
      beforeFees,
      afterFees
    )
  test("new cluster context accepts explicit PV9 parameters without historical fixture pins") {
    assert(context().isRight)
    assertEquals(RestrictedReplay.FixedResearchSlot, BigInt(3883681))
    assert(RestrictedReplay.environment(Bytes.empty).isLeft)
  }
  test("reject header version as ledger version and cross-epoch or reversed observations") {
    assert(context(major = 11).isLeft)
    assert(context(postEpoch = 1).isLeft)
    assert(context(postSlot = 1).isLeft)
    assert(context(beforeFees = 2, afterFees = 1).isLeft)
  }
  test("malformed state or transaction cannot yield a transfer receipt") {
    val c = context().toOption.get
    assert(ClusterTransfer.compare(c, Bytes.empty, Bytes.empty, Bytes.empty).isLeft)
  }
