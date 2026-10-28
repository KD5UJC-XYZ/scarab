// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.network.{ServerHandlers, TxSubmissionServer}
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.node.relay.ChainServer
import xyz.kd5ujc.scarab.protocol.NtNHeader

/** A node-to-node server that serves the blocks of a forged chain, as a relay does. */
object ServedChain:
  def apply(forged: ForgedChain): IO[ServerHandlers[IO, NtNHeader]] =
    for
      server <- ChainServer.open[IO](
        None,
        None,
        ChainServer.headerOfBlock(ForgedChain.geometry.byron.epochSlots),
        failure => IO.raiseError(new AssertionError(failure.message))
      )
      _ <- Handle
        .allow[xyz.kd5ujc.scarab.ledger.NodeError] {
          forged.raws.traverse_ { raw =>
            val header = Block.decodeShelley(raw).toOption.get.asInstanceOf[Block.Shelley].header
            server
              .persistence[MirrorState]
              .extended(
                BlockRef(header.slot, header.hash, header.blockNo),
                raw,
                Vector.empty,
                Anchor(None, forged.genesis)
              )
          }
        }
        .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      sink = new TxSubmissionServer.Sink[IO]:
        def known(id: Bytes) = IO.pure(false)
        def submit(
            originals: Vector[Bytes]
        )(using cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError]) = IO.unit
    yield ServerHandlers[IO, NtNHeader](NtNHeader.codec, server.source, server.blocks, sink)
