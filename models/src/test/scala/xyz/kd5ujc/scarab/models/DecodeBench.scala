// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era, Handshake, NtNHeader}
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.models.chainsync.ChainSyncMessage
import xyz.kd5ujc.scarab.models.handshake.HandshakeMessage

/** A timed loop (no JMH: that would add an sbt plugin). Run with
  * `sbt "models/Test/runMain xyz.kd5ujc.scarab.models.DecodeBench"`. Each variant decodes the
  * same bytes, including the CBOR parse, so the numbers compare like with like.
  */
object DecodeBench:
  private var sink = 0L

  private def time(name: String, inputs: Vector[Bytes], rounds: Int, perRound: Int)(
      f: Bytes => Long
  ): Double =
    var best = Double.MaxValue
    var r = 0
    while r < rounds do
      val t0 = System.nanoTime()
      var i = 0
      while i < perRound do
        var j = 0
        while j < inputs.size do
          sink += f(inputs(j))
          j += 1
        i += 1
      val ns = (System.nanoTime() - t0).toDouble / (perRound.toLong * inputs.size)
      if ns < best then best = ns
      r += 1
    println(f"$name%-52s $best%9.0f ns/item")
    best

  def main(args: Array[String]): Unit =
    val blocks = Fixtures.hexLines("fixtures/preview/conway.hex")
    val headers = blocks.map(Fixtures.headerBytes)
    val rounds = 5
    val n = 1500
    println(
      s"conway headers: ${headers.size} items, avg ${headers.map(_.size).sum / headers.size} bytes; $rounds rounds of ${n}x"
    )

    println("-- header only")
    time("Cbor.decode only (lower bound for both)", headers, rounds, n)(h =>
      Cbor.decode(h).toOption.get.original.size.toLong
    )
    val hand = time("hand-written Header.fromRaw", headers, rounds, n)(h =>
      Header.fromRaw(Era.Conway, h).toOption.get.slot.toLong
    )
    val gen = time("generated conway.Header.decodeBytes", headers, rounds, n)(h =>
      conway.Header.decodeBytes(h).toOption.get.headerBody.slot.value.toLong
    )
    val genHashed = time("generated + Blake2b-256 of the preserved bytes", headers, rounds, n) {
      h =>
        val d = conway.Header.decodeBytes(h).toOption.get
        xyz.kd5ujc.scarab.crypto.Blake2b.hash256
          .hash(d.raw)
          .size
          .toLong + d.headerBody.slot.value.toLong
    }
    println(
      f"   generated / hand-written = ${gen / hand}%.2f (hand-written also hashes); with hash: ${genHashed / hand}%.2f"
    )

    println("-- ChainSync RollForward message")
    val point = ChainSync.Point.Block(
      ChainSync.UInt64.saturating(5),
      Bytes.fromArray(Array.fill(32)(1.toByte))
    )
    val tip = ChainSync.Tip(point, ChainSync.UInt64.saturating(7))
    val messages = headers.map { h =>
      ChainSync
        .encode(
          ChainSync.State.NextCanAwait,
          ChainSync.Role.Server,
          ChainSync.Message.RollForward(NtNHeader(Era.Conway, h, None), tip),
          NtNHeader.codec
        )
        .toOption
        .get
    }
    val handMsg = time("hand-written ChainSync.decodePrefix + NtNHeader", messages, rounds, n) {
      m =>
        ChainSync.decodePrefix(
          ChainSync.State.NextCanAwait,
          ChainSync.Role.Server,
          m,
          NtNHeader.codec
        ) match
          case ChainSync.DecodeResult.Decoded(ChainSync.Message.RollForward(h, _), _) =>
            h.raw.size.toLong
          case _ => 0L
    }
    val genMsg =
      time("generated ChainSyncMessage.decodeBytes (header inside)", messages, rounds, n) { m =>
        ChainSyncMessage.decodeBytes(m).toOption.get match
          case ChainSyncMessage.RollForward(chain.hfc.Header.Conway(h), _) => h.raw.size.toLong
          case _                                                           => 0L
      }
    println(f"   generated / hand-written = ${genMsg / handMsg}%.2f")

    println("-- Handshake propose (versions 14..16)")
    val propose = Handshake
      .encode(
        Handshake.Suite.NodeToNode,
        Handshake
          .proposal(
            Handshake.Suite.NodeToNode,
            Vector(
              14 -> Handshake.Data(2),
              15 -> Handshake.Data(2),
              16 -> Handshake.Data(2, peras = true)
            )
          )
          .toOption
          .get
      )
      .toOption
      .get
    val handHs = time("hand-written Handshake.decode", Vector(propose), rounds, n * 20) { m =>
      Handshake
        .decode(Handshake.Suite.NodeToNode, Handshake.State.Confirm, m)
        .toOption
        .get
        .hashCode
        .toLong
    }
    val genHs = time("generated HandshakeMessage.decodeBytes", Vector(propose), rounds, n * 20) {
      m =>
        HandshakeMessage.decodeBytes(m).toOption.get.hashCode.toLong
    }
    println(f"   generated / hand-written = ${genHs / handHs}%.2f")
    println(s"(checksum $sink)")
