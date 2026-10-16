// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import munit.FunSuite
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.legacy
import xyz.kd5ujc.scarab.node.NodeSetup

/** The bytes of the frozen formats (the `legacy` module), on the initial state of the eras fixture:
  * a change to the frozen codec, or to a type of `ledger` or `consensus` that it reaches, changes a
  * digest here, and with it the reading of every V2 and V3 snapshot that exists.
  */
class LegacyFormatsSuite extends FunSuite:
  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  private lazy val initial: legacy.NodeState =
    legacy.Downgrade.node(
      NodeSetup
        .fromDirectory(java.nio.file.Path.of("fixtures/eras/genesis"))
        .fold(e => fail(e.message), _.start)
    )

  private def digest(bytes: Array[Byte]): (String, Int) =
    hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) -> bytes.length

  test("golden: the initial state of the eras fixture encodes to a fixed digest (V2 payload)") {
    val encoded = legacy.Codec.encode(legacy.StateSnapshot(Option.empty[BlockRef], initial))(using
      legacy.NodeCodecs.nodeSnapshot
    )
    assertEquals(
      digest(encoded),
      ("228a41672061e4a01954a3a41c706932aa13f0e89185fc5c6636796b56428a4a", 3158)
    )
  }

  test(
    "golden: the V3 sidecar of the initial state of the eras fixture encodes to a fixed digest"
  ) {
    val sidecar = legacy.SnapshotSidecar(
      None,
      Map.empty,
      Set.empty,
      legacy.SnapshotSidecar.Chain.InByron(initial)
    )
    val encoded = legacy.Codec.encode(sidecar)(using legacy.SnapshotSidecar.codec)
    assertEquals(
      digest(encoded),
      ("7bc7dc357f2b64267b3bb7c8bb5b79e4275dca9bd84693d1db02a32d872242a4", 3161)
    )
  }
