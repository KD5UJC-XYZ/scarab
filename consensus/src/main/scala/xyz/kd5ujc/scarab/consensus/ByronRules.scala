// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, ByronGenesis, Header, ByronChainError}
import xyz.kd5ujc.scarab.ledger.Header.ByronSignature
import xyz.kd5ujc.scarab.ledger.byron.*
import xyz.kd5ujc.scarab.consensus.state.{PBftSigner, PBftState}
import xyz.kd5ujc.scarab.ledger.state.{Genesis, KeyHash, SlotNo}
import xyz.kd5ujc.scarab.crypto.witness.{StrictEd25519, VerificationResult}

/** How a Byron block was established. Every main block is fully checked (there is no "unchecked"
  * outcome): signature, delegation, PBFT window, body proofs and the ledger rules.
  */
enum ByronCheck:
  /** A main block: its delegate's signature, PBFT window, body proofs and ledger rules held.
    * `onSchedule` says whether the genesis key it was made for is the round-robin one for its slot
    * (informational: upstream validation does not require it).
    */
  case Verified(onSchedule: Boolean, txs: Int)

  /** An epoch-boundary block: structure and linkage only. */
  case Boundary

  def label: String = this match
    case Verified(_, _) => "verified"
    case Boundary       => "boundary"

/** Byron chain-follow state: the last accepted header. */
final case class ByronTip(hash: Bytes, epoch: BigInt, slot: BigInt, boundary: Boolean)

/** What the Byron era needs besides the genesis: the node configuration's network magic requirement
  * and PBFT signature threshold and the configured Shelley epoch (a `Test` override).
  */
final case class ByronRulesConfig(
    ledger: ByronLedger.Config,
    signatureThreshold: Double,
    /** `TestShelleyHardForkAtEpoch`: when set, the transition epoch is not derived. */
    configuredShelleyEpoch: Option[BigInt]
)

/** Byron block validation: linkage to the previous block (or the genesis hash), slot and epoch
  * order, the protocol magic, and for main blocks the PBFT rules of ouroboros-consensus (the
  * delegate's signature, the delegate being the current delegate of a genesis key, the slot not
  * below the last signed one, the signature window threshold) and the ledger rules of
  * cardano-ledger (body proofs, delegation certificates, transactions, update payload).
  *
  * Upstream does not validate the round-robin leader schedule; the schedule is reported per block.
  */
object ByronRules:
  type Config = ByronRulesConfig

  /** Domain tag of heavy-delegation main-block signatures, followed by the CBOR protocol magic. */
  private val SignMainBlockHeavy = 0x09

  /** The chain-follow state the rules transform. */
  final case class State(
      tip: Option[ByronTip],
      ledger: ByronLedgerState,
      pbft: PBftState
  )

  /** The genesis key that is the round-robin leader of `slot`. */
  def scheduledLeader(genesis: ByronGenesis, slot: BigInt): Bytes =
    genesis.genesisKeys((slot % genesis.genesisKeys.size).toInt)

  def update(
      state: State,
      block: Block.Byron,
      config: Config
  ): Either[ByronChainError, (State, ByronCheck)] =
    val header = block.header
    val genesis = config.ledger.genesis
    val expectedPrev = state.tip.map(_.hash).getOrElse(genesis.hash)
    for
      _ <- Either.cond(
        header.protocolMagic == config.ledger.magicId,
        (),
        ByronChainError.ProtocolMagic(header.protocolMagic, genesis.protocolMagic)
      )
      _ <- Either.cond(
        header.prevHash.contains(expectedPrev),
        (),
        ByronChainError.PreviousHash(header.prevHash, expectedPrev)
      )
      _ <- Either.cond(
        header.slotInEpoch < config.ledger.epochSlots,
        (),
        ByronChainError.SlotOutsideEpoch(header.slotInEpoch, config.ledger.epochSlots)
      )
      _ <- orderOk(state.tip, header)
      result <-
        if header.boundary then boundary(state, block, config)
        else main(state, block, config)
    yield result

  private def tipOf(h: Header.Byron) = ByronTip(h.hash, h.epoch, h.slot, h.boundary)

  private def boundary(
      state: State,
      block: Block.Byron,
      config: Config
  ): Either[ByronChainError, (State, ByronCheck)] =
    val header = block.header
    val ticked = ByronLedger.tick(config.ledger, state.ledger, header.slot)
    ByronLedger
      .applyBoundary(config.ledger, ticked, header, block.size)
      .leftMap(ByronChainError.Ledger(_))
      .map(ledger => State(Some(tipOf(header)), ledger, state.pbft) -> ByronCheck.Boundary)

  private def main(
      state: State,
      block: Block.Byron,
      config: Config
  ): Either[ByronChainError, (State, ByronCheck)] =
    val header = block.header
    val genesis = config.ledger.genesis
    // The tick to the block's slot comes first: it activates the delegations due, which the
    // header is validated against, and adopts a confirmed update at an epoch boundary.
    val ticked = ByronLedger.tick(config.ledger, state.ledger, header.slot)
    for
      main <- header.main.toRight(ByronChainError.MissingConsensusData)
      delegated <- main.signature match
        case d: ByronSignature.Delegated => Right(d)
      _ <- verifySignature(main, delegated, config)
      delegate = ByronCbor.keyHash(delegated.delegate)
      genesisKey <- ticked.delegation
        .delegatorOf(delegate)
        .toRight(ByronChainError.UnknownDelegate(delegate))
      slot <- SlotNo.fromBigInt(header.slot).leftMap(ByronChainError.Vocabulary(_))
      signer <- KeyHash.fromBytes[Genesis](genesisKey).leftMap(ByronChainError.Vocabulary(_))
      lastSigned = state.pbft.lastSignedSlot.map(_.toBigInt)
      _ <- Either.cond(
        lastSigned.forall(_ <= header.slot),
        (),
        ByronChainError.SlotBeforeLastSigned(header.slot, lastSigned.getOrElse(0))
      )
      k = config.ledger.genesis.securityParam
      pbft = state.pbft.append(PBftSigner(slot, signer), k)
      threshold = math.floor(config.signatureThreshold * k.toDouble).toLong
      signed = pbft.countSignedBy(signer).toBigInt
      _ <- Either.cond(
        signed <= threshold,
        (),
        ByronChainError.SignatureThreshold(genesisKey, signed, k, threshold)
      )
      body <- ByronBody.decode(block.body).leftMap(ByronChainError.Body(_))
      ledger <- ByronLedger
        .applyMain(config.ledger, ticked, header, block.size, body)
        .leftMap(ByronChainError.Ledger(_))
    yield
      val onSchedule = ByronCbor.keyHash(main.leader) == scheduledLeader(genesis, header.slot)
      State(Some(tipOf(header)), ledger, pbft) -> ByronCheck.Verified(onSchedule, body.txs.size)

  private def orderOk(tip: Option[ByronTip], h: Header.Byron): Either[ByronChainError, Unit] =
    tip match
      case None =>
        Either.cond(
          h.boundary && h.epoch == 0,
          (),
          ByronChainError.InitialBoundaryRequired
        )
      case Some(t) if h.boundary =>
        Either.cond(
          h.epoch > t.epoch && !t.boundary,
          (),
          ByronChainError.BoundaryOrder(h.epoch, t.epoch, t.boundary)
        )
      case Some(t) =>
        Either.cond(
          h.epoch >= t.epoch && (h.slot > t.slot || (t.boundary && h.slot == t.slot)),
          (),
          ByronChainError.SlotOrder(h.slot, t.slot)
        )

  /** The delegate's signature over `[prev, proof, slot, difficulty, extra]` under the heavy block
    * tag of the genesis key and the protocol magic, and the genesis key's certificate.
    */
  private def verifySignature(
      main: Header.ByronMain,
      d: ByronSignature.Delegated,
      config: Config
  ): Either[ByronChainError, Unit] =
    // The genesis key in the signed tag is the header's, not the certificate's (as upstream).
    val message = ByronCbor.concat(
      Bytes.fromArray("01".getBytes("US-ASCII")),
      main.leader,
      ByronCbor.of(SignMainBlockHeavy),
      config.ledger.magic,
      main.signed
    )
    for _ <- StrictEd25519.verify(d.delegate.take(32), d.signature, message) match
        case Right(VerificationResult.SignatureVerified) => Right(())
        case Right(VerificationResult.SignatureRejected) => Left(ByronChainError.SignatureRejected)
        case Left(input: xyz.kd5ujc.scarab.crypto.witness.WitnessInputError) =>
          Left(ByronChainError.SignatureInput(input))
        case Left(other: xyz.kd5ujc.scarab.crypto.witness.VerificationError) =>
          Left(ByronChainError.SignatureFailure(other))
    yield ()
