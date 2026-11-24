// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.ledger.{ChainError, Header, HeaderStructureProblem}
import cats.Eq
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync

/** Identity of an applied block. `boundary` marks a Byron epoch-boundary block, which shares its
  * block number with its predecessor and its slot with the first block of the epoch.
  */
final case class BlockRef(slot: BigInt, hash: Bytes, blockNo: BigInt, boundary: Boolean = false):
  def point: ChainSync.Point =
    ChainSync.Point.Block(ChainSync.UInt64.saturating(slot), hash)

object BlockRef:
  given Eq[BlockRef] = Eq.fromUniversalEquals

/** The immutable boundary of the volatile chain: the newest block older than `k` blocks (or
  * genesis), together with the state after it. Rolling back past the anchor is impossible.
  */
final case class Anchor[S](block: Option[BlockRef], state: S):
  def point: ChainSync.Point = block.fold(ChainSync.Point.Origin)(_.point)
  def blockNo: Option[BigInt] = block.map(_.blockNo)

/** The last `k` blocks, each paired with the state after applying it. Rollback restores the exact
  * state retained at the target, so state transitions never need inverse operations. `origin` is
  * the hash the first block must name as its predecessor: none for a Shelley-start chain, the Byron
  * genesis hash for a chain that starts in Byron.
  */
final case class VolatileChain[S](
    k: Int,
    anchor: Anchor[S],
    blocks: Vector[(BlockRef, S)],
    origin: Option[Bytes] = None
):
  def tip: ChainSync.Point = blocks.lastOption.fold(anchor.point)(_._1.point)
  def tipRef: Option[BlockRef] = blocks.lastOption.map(_._1).orElse(anchor.block)
  def state: S = blocks.lastOption.fold(anchor.state)(_._2)
  def length: Int = blocks.size

  /** Append a block that extends the tip. Blocks older than `k` move into the anchor; the returned
    * vector holds blocks that became immutable, oldest first.
    *
    * A header that does not extend the tip is [[ChainError.NotConnected]] with the check it fails,
    * in the words header-first validation refuses it in (`NodeHeaderRules`): its block number, its
    * slot, then its previous hash, the order of the envelope's checks, which the extended ledger's
    * block application runs on every block (ouroboros-consensus 82ecba32 `validateEnvelope`,
    * `Ouroboros/Consensus/HeaderValidation.hs:364-376`, from `Ledger/Extended.hs:189-211`). The
    * first block of a chain is held to its previous hash only.
    */
  def extend(header: Header, next: S): Either[ChainError, (VolatileChain[S], Vector[BlockRef])] =
    val ref = BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
    // A boundary block after a main block repeats its number (`expectedNextBlockNo`), and a main
    // block after a boundary block may share its slot (`minimumNextSlotNo`); a boundary block
    // after a boundary block has the next number and a later slot, as any other block does
    // (ouroboros-consensus 82ecba32 `Byron/Ledger/HeaderValidation.hs:42-52`, #818).
    val placed: Either[HeaderStructureProblem, Unit] = tipRef match
      case None =>
        Either.cond(
          header.prevHash == origin,
          (),
          HeaderStructureProblem.PreviousHash(header.prevHash, origin)
        )
      case Some(t) =>
        val number = if header.isBoundary && !t.boundary then t.blockNo else t.blockNo + 1
        val minimum = if t.boundary && !header.isBoundary then t.slot else t.slot + 1
        for
          _ <- Either.cond(
            header.blockNo == number,
            (),
            HeaderStructureProblem.BlockNumber(header.blockNo, number)
          )
          _ <- Either.cond(
            header.slot >= minimum,
            (),
            HeaderStructureProblem.SlotNumber(header.slot, minimum)
          )
          _ <- Either.cond(
            header.prevHash.contains(t.hash),
            (),
            HeaderStructureProblem.PreviousHash(header.prevHash, Some(t.hash))
          )
        yield ()
    placed match
      case Left(problem) => Left(ChainError.NotConnected(header.point, tip, problem))
      case Right(_) =>
        val grown = blocks :+ (ref -> next)
        // the blocks past `k` become immutable, the newest of them the anchor
        val (immutable, kept) = grown.splitAt(grown.size - k)
        Right(
          immutable.lastOption.fold(copy(blocks = kept) -> Vector.empty) { (newest, newestState) =>
            copy(anchor = Anchor(Some(newest), newestState), blocks = kept) -> immutable.map(_._1)
          }
        )

  /** Roll back to `target`, which must be the anchor or a retained block. */
  def rollback(target: ChainSync.Point): Either[ChainError, VolatileChain[S]] =
    if target == anchor.point then Right(copy(blocks = Vector.empty))
    else
      val index = blocks.indexWhere(_._1.point == target)
      Either.cond(
        index >= 0,
        copy(blocks = blocks.take(index + 1)),
        ChainError.RollbackBeyondAnchor(target, anchor.point)
      )

  /** Intersection candidates, newest first: every retained block, then the anchor. */
  def candidates: Vector[ChainSync.Point] =
    blocks.reverseIterator.map(_._1.point).toVector :+ anchor.point

object VolatileChain:
  def genesis[S](k: Int, initial: S, origin: Option[Bytes] = None): VolatileChain[S] =
    VolatileChain(k, Anchor(None, initial), Vector.empty, origin)

  given [S: Eq]: Eq[VolatileChain[S]] = Eq.instance { (a, b) =>
    a.k == b.k && a.anchor.block === b.anchor.block && a.anchor.state === b.anchor.state &&
    a.blocks.sizeIs == b.blocks.size && a.blocks.zip(b.blocks).forall { case ((r1, s1), (r2, s2)) =>
      r1 === r2 && s1 === s2
    }
  }
