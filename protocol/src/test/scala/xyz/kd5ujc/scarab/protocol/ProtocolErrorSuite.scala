// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}

class ProtocolErrorSuite extends FunSuite:
  test("unsigned constructors retain the rejected value") {
    assertEquals(ChainSync.UInt64.from(-1), Left(ProtocolError.OutsideUint64(BigInt(-1))))
    assertEquals(
      KeepAlive.Cookie.from(65536),
      Left(ProtocolError.KeepAliveCookieOutsideUint16(BigInt(65536)))
    )
  }

  test("cookie correlation retains both cookies") {
    val expected = KeepAlive.Cookie.from(1).toOption.get
    val actual = KeepAlive.Cookie.from(2).toOption.get
    assertEquals(
      KeepAlive.matchResponse(Some(expected), KeepAlive.Message.Response(actual)),
      Left(ProtocolError.KeepAliveCookieMismatch(expected, actual))
    )
    assertEquals(
      KeepAlive.matchResponse(None, KeepAlive.Message.Response(actual)),
      Left(ProtocolError.UnsolicitedKeepAliveResponse)
    )
  }

  test("illegal agency retains the concrete protocol state and sender") {
    assertEquals(
      ChainSync.transition(ChainSync.State.Idle, ChainSync.Role.Server, ChainSync.Message.Done),
      Left(ProtocolError.ChainSyncAgency(ChainSync.State.Idle, ChainSync.Role.Server))
    )
  }

  test("block byte limits retain actual size and local maximum") {
    assertEquals(
      CardanoBlockFetch.RawNtNBlock.from(Bytes(Vector[Byte](1, 2)), 1),
      Left(ProtocolError.RawBlockByteLimitExceeded(2, 1))
    )
  }

  test("framing distinguishes an incomplete item from a reserved head") {
    assertEquals(ChainSync.decodePoint(Bytes.empty), ChainSync.DecodeResult.NeedMore)
    assertEquals(
      ChainSync.decodePoint(Bytes(Vector(0x9c.toByte))),
      ChainSync.DecodeResult.Failed(ProtocolError.ReservedOrIndefiniteArgument)
    )
  }

  test("hard-fork payload adapters retain syntax causes across the protocol boundary") {
    val bytes = Bytes(Vector(0xff.toByte))
    assertEquals(
      HfcHeader.codec.decode(bytes),
      Left(ProtocolError.HfcHeader(HfcHeaderError.Syntax(Cbor.Error.UnsupportedSimpleValue(31))))
    )
  }

  test("handshake decoding retains a structured error and prefix status") {
    assertEquals(
      Handshake.decodePrefix(Handshake.Suite.NodeToNode, Handshake.State.Propose, Bytes.empty),
      Left(Handshake.DecodeError(ProtocolError.IncompleteCbor, incomplete = true))
    )
    assertEquals(
      Handshake.wireVersion(Handshake.Suite.NodeToNode, 0),
      Left(ProtocolError.UnsupportedLogicalVersion)
    )
  }

  test("functional handshake encoding refuses hostile depth on a small stack") {
    val nested = (0 until 100000).foldLeft[Handshake.Term](Handshake.Term.Integer(0)) {
      (inner, _) => Handshake.Term.Tag(24, inner)
    }
    val completed = new java.util.concurrent.CompletableFuture[Either[ProtocolError, Bytes]]()
    val thread = new Thread(
      null,
      () => {
        try
          completed.complete(
            Handshake.encode(
              Handshake.Suite.NodeToNode,
              Handshake.Message.Accept(14, nested)
            )
          )
        catch case cause: Throwable => completed.completeExceptionally(cause)
        ()
      },
      "handshake-small-stack",
      256 * 1024
    )
    thread.start()
    assertEquals(
      completed.get(10, java.util.concurrent.TimeUnit.SECONDS),
      Left(ProtocolError.CBORResourceLimitExceeded)
    )
    thread.join()
  }
