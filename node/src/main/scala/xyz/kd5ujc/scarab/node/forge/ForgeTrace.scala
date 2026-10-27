// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.ValidationError
import xyz.kd5ujc.scarab.protocol.ChainSync

/** The KES key's state, `HotKey.KESInfo` (consensus 82ecba329 `Protocol/Ledger/HotKey.hs` 61-79).
  *
  * @param start
  *   The certificate's first KES period, `kesStartPeriod`.
  * @param end
  *   The first period the key is no longer valid in, `kesEndPeriod`: `start` plus
  *   `maxKESEvolutions`.
  * @param evolution
  *   How many times the key has evolved, `kesEvolution`.
  */
final case class KesInfo(start: BigInt, end: BigInt, evolution: Int):
  /** The key's absolute period, `kesAbsolutePeriod`. */
  def period: BigInt = start + evolution

  /** The periods left before the certificate expires, `kesPeriodsUntilExpiry` of cardano-node
    * 11.1.3 `Tracers/KESInfo.hs` 62-65 (never negative).
    */
  def remaining: BigInt = (end - period).max(0)

/** Why the forge state could not be updated: `KESEvolutionError` (`HotKey.hs` 125-138). */
enum KesEvolutionError:
  /** The target period is at or past the end of the key's window; the key is poisoned now. */
  case CouldNotEvolve(info: KesInfo, target: BigInt)

  /** The key was poisoned at an earlier slot and no new one has been installed since. */
  case KeyAlreadyPoisoned(info: KesInfo, target: BigInt)

  def info: KesInfo
  def target: BigInt

/** Why a slot leader cannot forge: `PraosCannotForge` (consensus 82ecba329 `Protocol/Praos.hs`
  * 673-689).
  */
enum CannotForge:
  /** The certificate's first KES period is after the wall clock's period
    * (`PraosCannotForgeKeyNotUsableYet`).
    */
  case KeyNotUsableYet(currentKesPeriod: BigInt, opCertStartingKesPeriod: BigInt)

/** The slot is outside the range the ledger view can be forecast to: `OutsideForecastRange`
  * (consensus 82ecba329 `Forecast.hs`), as `ledgerViewForecastAt` of
  * `Shelley/Ledger/SupportsProtocol.hs` 100-127 returns it.
  *
  * @param at
  *   The slot of the ledger state the forecast is made from, none at Origin.
  * @param maxFor
  *   The first slot that cannot be forecast: the stability window after the successor of `at`.
  * @param forSlot
  *   The slot asked for.
  */
final case class OutsideForecastRange(at: Option[BigInt], maxFor: BigInt, forSlot: BigInt)

/** What the forging loop traces, in the order `forkBlockForging` traces it: `TraceForgeEvent` of
  * consensus 82ecba329 (`Node/Tracers.hs`; emitted by `NodeKernel.hs` 549-791), one case per
  * constructor the loop emits, with its fields. The log renders each as cardano-node 11.1.3 does
  * (`Tracers/Consensus.hs` 1277-1410) and the counters fold over them ([[ForgeStats]]), so the two
  * cannot disagree.
  *
  * `TraceNoLedgerState` and `TraceAdoptionThreadDied` have no case: the ledger state of every block
  * of the chain is held with it, so the state of the predecessor is always there, and the block is
  * adopted in the forging fiber itself.
  */
enum ForgeTrace:
  /** The slot this trace is about: the slot of the block to forge. */
  def slot: BigInt

  /** The first act of every slot (`TraceStartLeadershipCheck`, `NodeKernel.hs` 575). */
  case StartLeadershipCheck(slot: BigInt)

  /** The chain holds no volatile block and its anchor (the immutable tip) is in this slot or a
    * later one (`TraceSlotIsImmutable`, `NodeKernel.hs` 846-850).
    *
    * @param tip
    *   The point of the immutable tip.
    * @param tipBlockNo
    *   The block number of the immutable tip.
    */
  case SlotIsImmutable(slot: BigInt, tip: ChainSync.Point, tipBlockNo: BigInt)

  /** The chain's tip is in a later slot than this one (`TraceBlockFromFuture`, `NodeKernel.hs`
    * 880).
    *
    * @param tip
    *   The slot of the block at the tip of the chain.
    */
  case BlockFromFuture(slot: BigInt, tip: BigInt)

  /** Where the block goes (`TraceBlockContext`, `NodeKernel.hs` 593).
    *
    * @param blockNo
    *   The number of the block to forge, `bcBlockNo`.
    * @param prev
    *   Its predecessor, `bcPrevPoint`.
    */
  case BlockContext(slot: BigInt, blockNo: BigInt, prev: ChainSync.Point)

  /** The ledger state of the predecessor is at hand (`TraceLedgerState`, `NodeKernel.hs` 609). */
  case LedgerState(slot: BigInt, prev: ChainSync.Point)

  /** The slot is too far from the predecessor for its ledger view (`TraceNoLedgerView`,
    * `NodeKernel.hs` 611-629).
    */
  case NoLedgerView(slot: BigInt, range: OutsideForecastRange)

  /** The ledger view of the slot is known (`TraceLedgerView`, `NodeKernel.hs` 633). */
  case LedgerView(slot: BigInt)

  /** The KES key could not be brought to the slot's period (`TraceForgeStateUpdateError`,
    * `NodeKernel.hs` 660-662).
    */
  case ForgeStateUpdateError(slot: BigInt, error: KesEvolutionError)

  /** The pool leads the slot but cannot forge in it (`TraceNodeCannotForge`, `NodeKernel.hs`
    * 663-665).
    */
  case NodeCannotForge(slot: BigInt, reason: CannotForge)

  /** The pool does not lead the slot (`TraceNodeNotLeader`, `NodeKernel.hs` 666-668). */
  case NodeNotLeader(slot: BigInt)

  /** The pool leads the slot and will forge (`TraceNodeIsLeader`, `NodeKernel.hs` 672). */
  case NodeIsLeader(slot: BigInt)

  /** The ledger state is ticked to the slot (`TraceForgeTickedLedgerState`, `NodeKernel.hs` 684).
    */
  case ForgeTickedLedgerState(slot: BigInt, prev: ChainSync.Point)

  /** The transactions of the block are taken (`TraceForgingMempoolSnapshot`, `NodeKernel.hs` 715).
    *
    * @param mempoolHash
    *   The hash of the tip the mempool was last synchronised with, none at Origin
    *   (`snapshotStateHash`).
    * @param mempoolSlot
    *   The slot its ledger state was ticked to (`snapshotSlotNo`).
    */
  case ForgingMempoolSnapshot(
      slot: BigInt,
      prev: ChainSync.Point,
      mempoolHash: Option[Bytes],
      mempoolSlot: BigInt
  )

  /** A block was forged, not yet adopted (`TraceForgedBlock`, `NodeKernel.hs` 738-744).
    *
    * @param onTopOf
    *   The tip of the ledger state the block was built on, `forgingOnTopOf`.
    * @param prevHash
    *   The block's predecessor hash, none on genesis.
    */
  case ForgedBlock(
      slot: BigInt,
      onTopOf: ChainSync.Point,
      hash: Bytes,
      blockNo: BigInt,
      prevHash: Option[Bytes]
  )

  /** The block was valid but did not become our tip (`TraceDidntAdoptBlock`, `NodeKernel.hs`
    * 768-769).
    */
  case DidntAdoptBlock(slot: BigInt, hash: Bytes)

  /** Our own rules refuse the block (`TraceForgedInvalidBlock`, `NodeKernel.hs` 770-783). */
  case ForgedInvalidBlock(slot: BigInt, hash: Bytes, reason: ValidationError)

  /** The block is our tip (`TraceAdoptedBlock`, `NodeKernel.hs` 795).
    *
    * @param blockSize
    *   `estimateBlockSize` of its header: the header bytes, the body size and the ten bytes of the
    *   era and CBOR-in-CBOR wrapping (consensus 82ecba329 `Shelley/Node/Serialisation.hs` 133-144,
    *   `Cardano/Node.hs` 242-252).
    * @param txs
    *   The ids of the transactions taken from the mempool.
    */
  case AdoptedBlock(slot: BigInt, hash: Bytes, blockSize: Long, txs: Vector[Bytes])
