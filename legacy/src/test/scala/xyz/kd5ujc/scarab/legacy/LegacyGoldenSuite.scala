// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import munit.FunSuite
import xyz.kd5ujc.scarab.consensus.BlockRef

/** The schemas of the frozen formats. A fingerprint is made of the simple names, the fields and
  * their order, and the shapes of the codecs, so a change to a type of this module (or to one of
  * `ledger` or `consensus` that it reaches) shows up here before it misreads an old snapshot.
  */
class LegacyGoldenSuite extends FunSuite:
  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  test("golden: the schema fingerprint of the V2 node snapshot") {
    assertEquals(hex(SnapshotV2.schema), "2293c5cf7bb77492")
  }

  test("golden: the schema fingerprint of the V3 sidecar") {
    assertEquals(hex(SnapshotSidecar.fingerprint), "65d0043c049b7b7a")
  }

  test("golden: the header of a V2 file") {
    given Codec[StateSnapshot[Vector[Int]]] = Codec.auto
    given Codec[BlockRef] = NodeCodecs.blockRef
    val content = SnapshotV2.encode(StateSnapshot(Option.empty[BlockRef], Vector.empty[Int]))
    assertEquals(hex(content.take(12)), "43534c534e415031" + "00000002")
    assertEquals(SnapshotV2.numberOf(content), Some(2))
  }
