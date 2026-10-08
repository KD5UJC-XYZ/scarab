// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.network.ChainSync

class ReferenceCaptureCommandSuite extends munit.FunSuite:
  private def fixture(name: String): Bytes =
    val relative = Path.of("fixtures", "chain-sync", name)
    val path = if Files.exists(relative) then relative else Path.of("..").resolve(relative)
    Bytes.fromArray(Files.readAllBytes(path))

  test("decode advertised header version from bytes without equating it to ledger version") {
    val h = ReferenceCaptureCommand.header(fixture("ntn-header-conway.cbor")).toOption.get
    assertEquals(h.hash, Blake2b.hash256.hash(h.raw))
    assert(h.raw.size > 448)
    assert(h.major >= 0)
  }

  test("reject wrong era, truncated header and opaque placeholder") {
    val raw = fixture("ntn-header-conway.cbor")
    assert(ReferenceCaptureCommand.header(Bytes(raw.value.dropRight(1))).isLeft)
    assert(ReferenceCaptureCommand.header(fixture("ntn-placeholder-header.cbor")).isLeft)
    val root = Cbor.decode(raw).toOption.get
    val fields = root.value.asInstanceOf[Value.Arr].value
    val wrong =
      Cbor.encode(Value.Arr(fields.updated(0, Node(Value.UInt(5), Bytes.empty)))).toOption.get
    assert(ReferenceCaptureCommand.header(wrong).isLeft)
  }

  test("bounded capture arguments reject invalid anchors") {
    val hash = "00" * 32
    assert(ReferenceCaptureCommand.options(List("3001", "1082026", "1", hash)).isRight)
    assert(ReferenceCaptureCommand.options(List("3001", "1082026", "-1", hash)).isLeft)
    assert(
      ReferenceCaptureCommand.options(List("3001", "1082026", "18446744073709551616", hash)).isLeft
    )
    assert(ReferenceCaptureCommand.options(List("3001", "1082026", "1", "00")).isLeft)
    assert(ReferenceCaptureCommand.options(List("remote", "1082026", "1", hash)).isLeft)
  }

  test("comparison refuses malformed block bytes") {
    val h = ReferenceCaptureCommand.header(fixture("ntn-header-conway.cbor")).toOption.get
    val anchor = ChainSync.Point.Block(ChainSync.UInt64.Zero, h.parent)
    assert(ReferenceCaptureCommand.compare(h, Bytes.empty, anchor).isLeft)
  }
