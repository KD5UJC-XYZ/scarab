// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.consensus as live
import xyz.kd5ujc.scarab.consensus.state.{
  ChainDepState,
  PBftSigner,
  PBftState as LivePBft,
  PraosState as LivePraos,
  PrtclState,
  ProtocolExtras as LiveProtocolExtras,
  ProtocolState,
  TPraosLedgerState,
  TPraosState,
  TicknState
}
import xyz.kd5ujc.scarab.ledger.state.{
  BlockIssuer,
  Genesis,
  KeyHash,
  SlotNo,
  Sorted,
  VocabError,
  WithOrigin,
  Word64
}
import xyz.kd5ujc.scarab.protocol.Era

/** The frozen node state brought to the live one: the only way the old state types reach the rest
  * of the node. What the old state does not hold is derived exactly as the mirror of the builds
  * that wrote it did (the last slot of the chain-dependent state is the slot of the anchor), and
  * nothing is invented: a value that does not fit the live type (a counter beyond 64 bits, a key of
  * another length) is the [[VocabError]] of the type, and the snapshot is left as it was.
  */
object Upgrade:
  /** The era an old state is in: Byron while the chain-follow state holds the Byron ledger, else
    * the era of the bootstrap mode, else the one its version and epoch put it in
    * (`StateMirror.eraOf` of the live state).
    */
  def eraOf(forks: live.HardForkConfig, old: NodeState): Era =
    if old.byron.ledger.isDefined && old.byron.firstShelleyEpoch.isEmpty then Era.Byron
    else
      old.boot.era
        .filter(_ != Era.Byron)
        .getOrElse(forks.eraAt(old.ledger.epoch, old.ledger.params.protocolMajor))

  /** The PBFT window of the old state, with the counts it implies. */
  def pbft(old: PBftState): Either[VocabError, LivePBft] =
    old.signers.toList
      .traverse((slot, key) =>
        (SlotNo.fromBigInt(slot), KeyHash.fromBytes[Genesis](key)).mapN(PBftSigner(_, _))
      )
      .map(window => LivePBft.ofWindow(window.toVector))

  /** The Byron chain-follow state of the old state. */
  def byron(old: ByronState): Either[VocabError, live.ByronState] =
    pbft(old.pbft).map(p =>
      live.ByronState(old.tip, old.stats, old.completed, old.ledger, p, old.firstShelleyEpoch)
    )

  /** The extras of a V3 sidecar's protocol. */
  def protocol(old: ProtocolExtras): LiveProtocolExtras = old match
    case ProtocolExtras.OfTPraos(epoch, previous) => LiveProtocolExtras(epoch, previous)
    case ProtocolExtras.OfPraos(epoch) =>
      LiveProtocolExtras(epoch, xyz.kd5ujc.scarab.ledger.Nonce.Neutral)

  private def counters(
      old: Map[xyz.kd5ujc.scarab.codec.Bytes, BigInt]
  ): Either[VocabError, SortedMap[KeyHash[BlockIssuer], Word64]] =
    old.toList
      .traverse((key, count) =>
        (
          KeyHash.fromBytes[BlockIssuer](key),
          Word64.fromBigInt(VocabError.Kind.OcertCounter, count)
        ).tupled
      )
      .map(Sorted.map(_))

  /** The chain-dependent state of the protocol of `era`, from the one record of the old state; `at`
    * is the slot of the last header (the anchor's).
    */
  def chainDep(
      era: Era,
      at: WithOrigin[SlotNo],
      old: PraosState
  ): Either[VocabError, ProtocolState] =
    counters(old.counters).map { cs =>
      if era == Era.Byron || era.usesTPraos then
        ProtocolState(
          ChainDepState.TPraos(
            TPraosState(
              if era == Era.Byron then WithOrigin.Origin else at,
              TPraosLedgerState(
                PrtclState(cs, old.evolving, old.candidate),
                TicknState(old.epochNonce, old.lastEpochBlock),
                old.lab
              )
            )
          ),
          LiveProtocolExtras(old.epoch, old.previousEpochNonce)
        )
      else
        ProtocolState(
          ChainDepState.Praos(
            LivePraos(
              at,
              cs,
              old.evolving,
              old.candidate,
              old.epochNonce,
              old.previousEpochNonce,
              old.lab,
              old.lastEpochBlock
            )
          ),
          LiveProtocolExtras(old.epoch, xyz.kd5ujc.scarab.ledger.Nonce.Neutral)
        )
    }

  /** The live state of an old one, after the block `anchor` (none: before the first). */
  def node(
      old: NodeState,
      forks: live.HardForkConfig,
      anchor: Option[live.BlockRef]
  ): Either[VocabError, live.NodeState] =
    val era = eraOf(forks, old)
    for
      at <- anchor.traverse(a => SlotNo.fromBigInt(a.slot)).map {
        case None       => WithOrigin.Origin
        case Some(slot) => WithOrigin.At(slot)
      }
      p <- chainDep(era, at, old.praos)
      b <- byron(old.byron)
    yield live.NodeState(p, old.ledger, old.boot, b)
