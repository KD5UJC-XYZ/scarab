// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}

/** Whether a block's body carries a Leios certificate. */
enum LeiosCert derives CanEqual:
  /** It does: the block certifies the endorser block its predecessor announced. */
  case Carried

  /** It does not, or the block is of an era without Leios. */
  case Absent

/** What chain selection reads of a block for the acquired-endorser-block gate: the two Leios fields
  * of the reference's VolatileDB `BlockInfo`, `biHasLeiosCert` and `biLeiosAnnouncedEb`
  * (ouroboros-consensus 2416493b `Storage/VolatileDB/API.hs:189-215`), taken from the block as
  * `extractBlockInfo` takes them (`Storage/VolatileDB/Impl/Parser.hs:119-140`): the certificate of
  * the body (`blockLeiosCert`) and the endorser block the header announces
  * (`headerLeiosAnnouncement`), both of a Dijkstra block only
  * (`ouroboros-consensus-cardano/.../Cardano/Block.hs:1605-1615`).
  */
final case class LeiosBlockInfo(cert: LeiosCert, announced: Option[EbHash])

object LeiosBlockInfo:
  /** A block of an era without Leios. */
  val Absent: LeiosBlockInfo = LeiosBlockInfo(LeiosCert.Absent, None)

  def of(block: Block): LeiosBlockInfo = block match
    case b: Block.Dijkstra =>
      LeiosBlockInfo(
        b.block.leiosCertificate.fold(LeiosCert.Absent)(_ => LeiosCert.Carried),
        b.block.header.leios.announcement.map(_.hash)
      )
    case _: Block.Shelley | _: Block.Byron => Absent

/** The endorser blocks whose closure is acquired, each with the youngest slot it was announced at
  * (`AcquiredLeiosEbs`, ouroboros-consensus 2416493b `LeiosDemoTypes.hs:213-290`): what the
  * ChainDB's `cdbAcquiredLeiosEbs` holds. `bySlot` is the reverse index of `youngest`, which the
  * pruning reads.
  */
final case class AcquiredLeiosEbs private (
    youngest: Map[EbHash, BigInt],
    bySlot: SortedMap[BigInt, Set[EbHash]]
):
  /** `acquiredLeiosEbsSetMember`. */
  def contains(hash: EbHash): Boolean = youngest.contains(hash)

  /** `insertAcquiredLeiosEb`: `None` if nothing changed (the hash is held at this slot or a younger
    * one); else whether the hash is novel, and the set with it at the youngest of its slots.
    */
  def inserted(point: EbPoint): Option[(Boolean, AcquiredLeiosEbs)] =
    val slot = point.slot.toBigInt
    youngest.get(point.hash) match
      case None =>
        Some(
          (
            true,
            AcquiredLeiosEbs(youngest.updated(point.hash, slot), added(bySlot, slot, point.hash))
          )
        )
      case Some(previous) if slot <= previous => None
      case Some(previous) =>
        val moved = added(removed(bySlot, previous, point.hash), slot, point.hash)
        Some((false, AcquiredLeiosEbs(youngest.updated(point.hash, slot), moved)))

  /** `pruneAcquiredLeiosEbs`: without every hash whose youngest announcement is older than the
    * immutable tip's slot, `None` if there is none (or the tip is the origin).
    */
  def pruned(immutableTip: Option[BigInt]): Option[AcquiredLeiosEbs] =
    immutableTip.flatMap { tip =>
      val (gone, kept) = bySlot.span((slot, _) => slot < tip)
      Option.when(gone.nonEmpty)(
        AcquiredLeiosEbs(youngest -- gone.valuesIterator.flatten, kept)
      )
    }

  private def added(
      index: SortedMap[BigInt, Set[EbHash]],
      slot: BigInt,
      hash: EbHash
  ): SortedMap[BigInt, Set[EbHash]] =
    index.updated(slot, index.getOrElse(slot, Set.empty) + hash)

  private def removed(
      index: SortedMap[BigInt, Set[EbHash]],
      slot: BigInt,
      hash: EbHash
  ): SortedMap[BigInt, Set[EbHash]] =
    index.get(slot).map(_ - hash).fold(index) { left =>
      if left.isEmpty then index - slot else index.updated(slot, left)
    }

object AcquiredLeiosEbs:
  val empty: AcquiredLeiosEbs = AcquiredLeiosEbs(Map.empty, SortedMap.empty)

  /** `acquiredLeiosEbsFromList`: the completed points of the store's scan at open. */
  def of(points: Vector[EbPoint]): AcquiredLeiosEbs =
    points.foldLeft(empty)((acquired, p) => acquired.inserted(p).fold(acquired)(_._2))

/** What the gate reads of a block of the forest: its predecessor's hash (none: genesis) and its
  * Leios fields.
  */
final case class LeiosForestBlock(prevHash: Option[Bytes], info: LeiosBlockInfo)

/** The acquired-endorser-block gate of chain selection (ouroboros-consensus 2416493b
  * `Storage/ChainDB/Impl/ChainSel.hs:1778-1844`): a ranking block whose certificate references an
  * endorser block whose closure is not acquired is hidden from candidate construction, both as a
  * tip and as a successor, as invalid blocks are, but only until the closure is acquired.
  */
object LeiosCertGate:
  /** `isUnacquiredCertRB` (`ChainSel.hs:1796-1821` at ouroboros-consensus 2416493b): `lookup` is
    * the raw forest, so that the predecessor's announcement is read whatever else hides it.
    *
    *   - a block not in the forest, or without a certificate, is not hidden;
    *   - a certifying block on genesis certifies nothing, and is hidden;
    *   - one whose predecessor is not in the forest or announced nothing is hidden;
    *   - otherwise it is hidden unless its predecessor's endorser block is acquired.
    */
  def unacquired(acquired: AcquiredLeiosEbs, lookup: Bytes => Option[LeiosForestBlock])(
      hash: Bytes
  ): Boolean =
    lookup(hash) match
      case None                                                               => false
      case Some(LeiosForestBlock(_, LeiosBlockInfo(LeiosCert.Absent, _)))     => false
      case Some(LeiosForestBlock(None, LeiosBlockInfo(LeiosCert.Carried, _))) => true
      case Some(LeiosForestBlock(Some(previous), LeiosBlockInfo(LeiosCert.Carried, _))) =>
        lookup(previous).flatMap(_.info.announced).forall(eb => !acquired.contains(eb))

  /** The hashes of `forest` the gate hides (`ignoreUnacquiredCertRB`, `ignoreUnacquiredCertRBSuc`,
    * `ChainSel.hs:1828-1844` at ouroboros-consensus 2416493b).
    */
  def hidden(acquired: AcquiredLeiosEbs, forest: Map[Bytes, LeiosForestBlock]): Set[Bytes] =
    forest.keysIterator.filter(unacquired(acquired, forest.get)).toSet

  /** The ranking blocks parked on `eb`: the certifying successors, not known invalid, of the blocks
    * of `forest` that announce it (`reprocessPoints` of `chainSelReprocessLeiosEb`,
    * `ChainSel.hs:654-673` at ouroboros-consensus 2416493b).
    */
  def parkedOn(
      eb: EbHash,
      forest: Map[Bytes, LeiosForestBlock],
      invalid: Bytes => Boolean
  ): Set[Bytes] =
    val announcers = forest.collect {
      case (hash, LeiosForestBlock(_, LeiosBlockInfo(_, Some(announced)))) if announced == eb =>
        hash
    }.toSet
    forest.collect {
      case (hash, LeiosForestBlock(Some(previous), LeiosBlockInfo(LeiosCert.Carried, _)))
          if announcers(previous) && !invalid(hash) =>
        hash
    }.toSet
