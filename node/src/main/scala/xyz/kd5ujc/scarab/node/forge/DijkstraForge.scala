// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.Monad
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.leios.{CertifyDecision, CertifyDefect, LeiosVoting}
import xyz.kd5ujc.scarab.crypto.bls.RbHash
import xyz.kd5ujc.scarab.ledger.{ForgeCertifyProblem, ForgeError, NodeError, PosixClock}
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.Fatal
import xyz.kd5ujc.scarab.node.mempool.{EbMeasure, Rejection}
import xyz.kd5ujc.scarab.protocol.EbPoint
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64

/** The parent a Dijkstra ranking block of a slot is forged on: the chain `chain` at its tip `tip`,
  * the tip's ledger state unticked (`unticked`) and ticked to the slot (`ticked`), and what the tip
  * announced, read from its chain-dependent state ticked to the slot
  * (`tickedProtocolStateLeiosAnnouncement`, which the tick clears across an epoch boundary).
  */
final case class DijkstraParent[S](
    chain: S,
    tip: Option[BlockRef],
    unticked: NewEpochState[Dijkstra],
    ticked: NewEpochState[Dijkstra],
    announced: Option[EbPoint]
)

/** What the forge loop's partition of a slot comes to (`partitionMempool`): the ranking block's
  * body and the endorser block it announces, the pool's held transactions the rules had no verdict
  * on, and the certify decision it was made by.
  */
final case class DijkstraPartition(
    body: BlockBuilder.Assembly,
    withheld: Map[Bytes, Rejection],
    decision: CertifyDecision
)

/** The Dijkstra branch of the forge loop (ouroboros-consensus 2416493b
  * `ouroboros-consensus-diffusion/src/ouroboros-consensus-diffusion/Ouroboros/Consensus/NodeKernel/Forge.hs`):
  * `decideLeiosCertify` (`:324-396`) and `partitionMempool` (`:731-869`), with the body
  * `forgeShelleyBlock` makes of their outcome (`mkBody`, `mkEb`,
  * `ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Forge.hs:146-212`).
  */
object DijkstraForge:

  /** `decideLeiosCertify` (`NodeKernel/Forge.hs:324-396`, ouroboros-consensus 2416493b) of the
    * block forged in `slot` on `parent`: what the parent announced, the certification gap of its
    * unticked state's parameters at the era's slot length `slotMillis` (`getMinCertificationGap`,
    * `Shelley/Ledger/Ledger.hs: 969-973`), the store's closure and the vote state's certificate for
    * the parent ([[LeiosVoting.decideLeiosCertify]]). A gap that cannot be computed, a parent hash
    * of another width and a certificate on genesis stop the node, as the reference's `error` s do.
    */
  def decide[F[_]: Monad, S](
      slot: BigInt,
      slotMillis: BigInt,
      parent: DijkstraParent[S],
      certifying: LeiosCertifying[F, S]
  )(using storage: Raise[F, StorageError], fatal: Raise[F, Fatal]): F[CertifyDecision] =
    def stop[A](problem: ForgeCertifyProblem): F[A] =
      fatal.raise[Fatal, A](Fatal.Internal(ForgeError.Certify(problem)))
    val pp = EraLedger.curPParams(parent.unticked.es.ls.utxo.gov)
    val gap = LeiosVoting.minCertificationGap(
      slotMillis,
      pp.leiosAnnouncementPeriodLength,
      pp.leiosVotePeriodLength,
      pp.leiosDiffusionPeriodLength
    )
    val tip: Either[ForgeCertifyProblem, Option[RbHash]] = parent.tip.traverse(t =>
      RbHash.fromBytes(t.hash).toRight(ForgeCertifyProblem.TipHashWidth(t.hash.size))
    )
    (gap.leftMap(ForgeCertifyProblem.Gap(_)), tip).tupled match
      case Left(problem) => stop(problem)
      case Right((minGap, tipHash)) =>
        given Raise[F, CertifyDefect] = new Raise[F, CertifyDefect]:
          def functor = Monad[F]
          def raise[E <: CertifyDefect, A](e: E): F[A] = e match
            case CertifyDefect.OnGenesis => stop(ForgeCertifyProblem.OnGenesis)
        LeiosVoting.decideLeiosCertify[F](
          Word64.clamped(slot),
          parent.announced,
          Some(minGap),
          tipHash,
          certifying.closureHeld(_),
          certifying.certificate
        )

  /** `partitionMempool` (`NodeKernel/Forge.hs:760-869`, ouroboros-consensus 2416493b) of the block
    * forged in `slot` on `parent`, by `decision`:
    *   - without a certificate, the pool's snapshot of the slot on the parent ticked to it
    *     (`getSnapshotFor`, `:792-797`), its longest prefix that fits the ranking block's
    *     `blockLimit` for the block and the run after it that fits `ebLimit` for its endorser block
    *     (`snapshotPartition snap rbCap ebCap`, `:799-803`);
    *   - with one, the certified closure applied to the unticked parent
    *     ([[LeiosCertifying.closed]], `:826-841`), that state ticked to the slot by `tickAfter`
    *     (`:847-856`), the pool's snapshot of the slot on it (`getSnapshotForNoCache`, `:858-860`),
    *     and the run of it that fits `ebLimit` for the endorser block, the block itself taking none
    *     (`snapshotPartition snap Data.Measure.zero ebCap`, `:862-867`). The reference reads a
    *     certifying view of the pool kept up to date off the forging path first
    *     (`getCertifyingSnapshotFor`, `:805-821`), revalidated on top of the same closure; this
    *     node keeps none, and always takes the second way.
    *
    * The body carries the certificate and no transaction when it certifies (`mkBody`,
    * `Shelley/Ledger/Forge.hs:152-160`; [[BlockBuilder.Assembly.dijkstra]]), and announces the
    * endorser block of the run when the run is not empty (`mkEb`, `:176-212`).
    */
  def partition[F[_]: Monad, S](
      slot: BigInt,
      parent: DijkstraParent[S],
      decision: CertifyDecision,
      blockLimit: TxMeasure,
      ebLimit: EbMeasure,
      snapshots: DijkstraSnapshots[F],
      certifying: LeiosCertifying[F, S],
      tickAfter: NewEpochState[Dijkstra] => Either[NodeError, NewEpochState[Dijkstra]],
      clock: PosixClock
  )(using storage: Raise[F, StorageError], fatal: Raise[F, Fatal]): F[DijkstraPartition] =
    decision match
      case CertifyDecision.Certify(certificate, announced) =>
        for
          closed <- certifying.closed(parent.chain, parent.tip, parent.unticked, announced, slot)
          after <- closed
            .flatMap(tickAfter)
            .fold(e => fatal.raise[Fatal, NewEpochState[Dijkstra]](Fatal.of(e)), Monad[F].pure)
          snapshot <- snapshots.forSlot(after, slot, clock)
        yield
          val split = BlockBuilder.partition(snapshot, TxMeasure.zero, ebLimit)
          DijkstraPartition(
            BlockBuilder.Assembly.dijkstra(Vector.empty, Some(certificate), split.endorsing(slot)),
            snapshot.withheld,
            decision
          )
      case CertifyDecision.NothingAnnounced | CertifyDecision.TooEarly(_, _) |
          CertifyDecision.ClosureNotDownloaded(_) | CertifyDecision.NoCertificate(_) =>
        snapshots.forSlot(parent.ticked, slot, clock).map { snapshot =>
          val split = BlockBuilder.partition(snapshot, blockLimit, ebLimit)
          DijkstraPartition(
            BlockBuilder.Assembly.dijkstra(split.ranking.transactions, None, split.endorsing(slot)),
            snapshot.withheld,
            decision
          )
        }

  /** The lines `decideLeiosCertify` traces of `decision` for the block of `slot`
    * (`NodeKernel/Forge.hs:356-383`, ouroboros-consensus 2416493b): the two kernel messages and
    * `TraceLeiosBlockCertified`.
    */
  def decided(slot: BigInt, decision: CertifyDecision): Vector[LeiosForgeTrace] = decision match
    case CertifyDecision.ClosureNotDownloaded(point) => Vector(LeiosForgeTrace.NotDownloaded(point))
    case CertifyDecision.NoCertificate(point)        => Vector(LeiosForgeTrace.NoCertificate(point))
    case CertifyDecision.Certify(_, point) => Vector(LeiosForgeTrace.BlockCertified(slot, point))
    case CertifyDecision.NothingAnnounced | CertifyDecision.TooEarly(_, _) => Vector.empty

  /** The lines `forgeShelleyBlock` traces of the block `rbHash` forged in `slot` with `body`
    * (`Shelley/Ledger/Forge.hs:124-138, 202-208`, ouroboros-consensus 2416493b): for an endorser
    * block forged with it, its `TraceLeiosBlockForged` and `TraceLeiosBlockAnnounced`, and
    * `TraceLeiosCertifiedAndAnnounced` when the block also certifies; nothing for a block that
    * announces none.
    */
  def forged(slot: BigInt, rbHash: Bytes, body: BlockBuilder.Assembly): Vector[LeiosForgeTrace] =
    body match
      case BlockBuilder.Assembly.InDijkstra(b, Some(e)) =>
        val eb = e.forged
        Vector(
          LeiosForgeTrace.BlockForged(
            slot,
            eb.point,
            eb.closure.length,
            eb.body.encodedSize,
            eb.closure.toVector.map(_._2.size.toLong).sum
          ),
          LeiosForgeTrace.BlockAnnounced(rbHash, eb.point)
        ) ++ Option.when(b.leiosCertificate.isDefined)(
          LeiosForgeTrace.CertifiedAndAnnounced(slot, rbHash)
        )
      case BlockBuilder.Assembly.InDijkstra(_, None) | BlockBuilder.Assembly.InConway(_) =>
        Vector.empty
