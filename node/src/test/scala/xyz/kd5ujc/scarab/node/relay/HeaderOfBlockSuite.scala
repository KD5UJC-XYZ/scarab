// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.relay

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{Block, EraBlocks}
import xyz.kd5ujc.scarab.ledger.ChainError

/** The header ChainSync sends for a block the server holds ([[ChainServer.headerOfBlock]]): read
  * from the block's envelope and header, the contents of its body not decoded, as the reference's
  * ChainSync server sends a stored block's header without reading its body (`readHeader`,
  * `Storage/ImmutableDB/Impl/Iterator.hs` 694-704, ouroboros-consensus 82ecba32). The blocks are
  * the first of every envelope tag ([[EraBlocks]]).
  */
class HeaderOfBlockSuite extends FunSuite:
  import EraBlocks.*

  private val headerOf = ChainServer.headerOfBlock(ByronEpochSlots)

  // A Byron block's body is not decoded by Block.decode either, and its header carries the size of
  // the whole block (the size hint), so it is left out.
  test("the header of a block of every Shelley-based tag is read whatever item its body is") {
    firstOfTag.filter(_._1 >= 2).foreach { case (tag, raw) =>
      val header = headerOf(raw)
      val unread = withoutBody(raw)
      assert(header.isRight, s"tag $tag: $header")
      assert(
        Block.decode(unread, ByronEpochSlots) match {
          case Left(ChainError.Decode(_, _)) => true
          case _                             => false
        },
        s"tag $tag: the body still decodes"
      )
      assertEquals(headerOf(unread), header, s"tag $tag")
    }
  }
