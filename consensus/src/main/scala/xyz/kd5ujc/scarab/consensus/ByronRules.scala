// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, ByronChainError, ByronGenesis, Header, ProviderDefect}
import xyz.kd5ujc.scarab.ledger.Header.ByronSignature
import xyz.kd5ujc.scarab.ledger.byron.*
import xyz.kd5ujc.scarab.ledger.byron.ByronLedger.refusedAs
import xyz.kd5ujc.scarab.consensus.state.{PBftSigner, PBftState}
import xyz.kd5ujc.scarab.ledger.state.{Genesis, KeyHash, SlotNo}
import xyz.kd5ujc.scarab.crypto.witness.VerificationResult

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
  * @param configuredShelleyEpoch
  *   `TestShelleyHardForkAtEpoch`: when set, the transition epoch is not derived.
  * @param signatures
  *   The Ed25519 verifier of the delegate's block signature (the reference's Byron semantics; a
  *   test injects a failing provider here). The ledger's own signatures (witnesses, certificates,
  *   proposals, votes) take theirs from [[ByronLedger.Config.signatures]].
  */
final case class ByronRulesConfig(
    ledger: ByronLedger.Config,
    signatureThreshold: Double,
    configuredShelleyEpoch: Option[BigInt],
    signatures: ByronRulesConfig.Verifier = ByronRulesConfig.ByronSignatures
)

object ByronRulesConfig:
  /** An Ed25519 verifier: `(key, signature, message)` to its answer. */
  type Verifier = ByronSig.Verifier

  /** [[ByronEd25519.verify]], one instance so that configurations compare equal. */
  val ByronSignatures: Verifier = ByronSig.Standard

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

  /** The most blocks a genesis key may sign in the window: `pbftWindowParams` 's
    * `floor (pbftSignatureThreshold * fromIntegral winSize) :: Word64` (ouroboros-consensus
    * 82ecba32 `Ouroboros/Consensus/Protocol/PBFT.hs:403-412`). GHC 9.10.3 floors through `Integer`
    * and takes the result modulo 2^64, so a window of 2^64 - 1 (a `k` of -1 in the genesis, #734)
    * at a threshold of 1 is 0; a product that is not finite decodes to a multiple of 2^64 and is 0
    * too.
    */
  def windowThreshold(threshold: Double, window: BigInt): BigInt =
    val product = threshold * window.toDouble
    if product.isNaN || product.isInfinite then BigInt(0)
    else BigDecimal.exact(math.floor(product)).toBigInt.mod(BigInt(1) << 64)

  /** The genesis key that is the round-robin leader of `slot`. */
  def scheduledLeader(genesis: ByronGenesis, slot: BigInt): Bytes =
    genesis.genesisKeys((slot % genesis.genesisKeys.size).toInt)

  def update(
      state: State,
      block: Block.Byron,
      config: Config
  ): Either[ByronChainError | ProviderDefect, (State, ByronCheck)] =
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
      _ <- orderOk(state.tip, header, config.ledger.epochSlots)
      result <-
        if header.boundary then boundary(state, block, config)
        else main(state, block, config)
    yield result

  private def tipOf(h: Header.Byron) = ByronTip(h.hash, h.epoch, h.slot, h.boundary)

  private def boundary(
      state: State,
      block: Block.Byron,
      config: Config
  ): Either[ByronChainError | ProviderDefect, (State, ByronCheck)] =
    val header = block.header
    val ticked = ByronLedger.tick(config.ledger, state.ledger, header.slot)
    ByronLedger
      .applyBoundary(ticked, header, block.size)
      .leftMap(ByronChainError.Ledger(_))
      .map(ledger => State(Some(tipOf(header)), ledger, state.pbft) -> ByronCheck.Boundary)

  private def main(
      state: State,
      block: Block.Byron,
      config: Config
  ): Either[ByronChainError | ProviderDefect, (State, ByronCheck)] =
    val header = block.header
    val genesis = config.ledger.genesis
    // The tick to the block's slot comes first: it activates the delegations due, which the
    // header is validated against, and adopts a confirmed update at an epoch boundary.
    val ticked = ByronLedger.tick(config.ledger, state.ledger, header.slot)
    for
      main <- header.main.toRight(ByronChainError.MissingConsensusData)
      pbft <- validateHeader(header, state.pbft, ticked.delegation, config)
      body <- ByronBody.decode(block.body).leftMap(ByronChainError.Body(_))
      ledger <- ByronLedger
        .applyMain(config.ledger, ticked, header, block.size, body)
        .refusedAs(ByronChainError.Ledger(_))
    yield
      val onSchedule = ByronCbor.keyHash(main.leader) == scheduledLeader(genesis, header.slot)
      State(Some(tipOf(header)), ledger, pbft) -> ByronCheck.Verified(onSchedule, body.txs.size)

  /** Header-only PBFT validation against a forecast delegation view. Body rules and the ledger tick
    * belong to the caller; the same signature/window code is used by complete block validation.
    * Consensus 82ecba329, Byron/Ledger/PBFT.hs and Protocol/PBFT.hs.
    */
  def validateHeader(
      header: Header.Byron,
      previous: PBftState,
      delegation: DelegationState,
      config: Config
  ): Either[ByronChainError | ProviderDefect, PBftState] =
    if header.boundary then Right(previous)
    else
      for
        main <- header.main.toRight(ByronChainError.MissingConsensusData)
        delegated <- main.signature match
          case d: ByronSignature.Delegated => Right(d)
        _ <- verifySignature(main, delegated, config)
        slot <- SlotNo.fromBigInt(header.slot).leftMap(ByronChainError.Vocabulary(_))
        lastSigned = previous.lastSignedSlot.map(_.toBigInt)
        _ <- Either.cond(
          lastSigned.forall(_ <= header.slot),
          (),
          ByronChainError.SlotBeforeLastSigned(header.slot, lastSigned.getOrElse(0))
        )
        delegate = ByronCbor.keyHash(delegated.delegate)
        genesisKey <- delegation
          .delegatorOf(delegate)
          .toRight(ByronChainError.UnknownDelegate(delegate))
        signer <- KeyHash.fromBytes[Genesis](genesisKey).leftMap(ByronChainError.Vocabulary(_))
        k = config.ledger.genesis.securityParam
        pbft = previous.append(PBftSigner(slot, signer), k)
        threshold = ByronRules.windowThreshold(config.signatureThreshold, k)
        signed = pbft.countSignedBy(signer).toBigInt
        _ <- Either.cond(
          signed <= threshold,
          (),
          ByronChainError.SignatureThreshold(genesisKey, signed, k, threshold)
        )
      yield pbft

  /** The order of a block, on the slots as the reference flattens them ([[Header.Byron.slot]],
    * #818). The first block is a boundary block that names the genesis (epoch 0, or the "Genesis"
    * tag), numbered 0 (`expectedFirstBlockNo`), and a later boundary block names its predecessor:
    * `updateChainBoundary` (`Chain/Block/Validation.hs:276-287`, cardano-ledger f649f975, #754).
    * The rest is the envelope, which the extended ledger's block application runs on every block
    * (ouroboros-consensus 82ecba32: `applyBlockLedgerResultWithValidation`,
    * `Ouroboros/Consensus/Ledger/Extended.hs:189-211`, calls `validateHeader`,
    * `Ouroboros/Consensus/HeaderValidation.hs:508-514`, which runs `validateEnvelope`, `:364-376`):
    * a main block after a boundary block may share its slot and any other block comes at a later
    * slot (`minimumNextSlotNo`), and a boundary block, the first one too, is at a multiple of the
    * epoch's slots (`additionalEnvelopeChecks`, `UnexpectedEBBInSlot`,
    * `ouroboros-consensus-cardano/src/byron/Ouroboros/Consensus/Byron/Ledger/HeaderValidation.hs:40-69`).
    * The block numbers after the first are chain selection's ([[VolatileChain.extend]]). No epoch
    * is compared, and no slot count is checked against the epoch's length.
    */
  private def orderOk(
      tip: Option[ByronTip],
      h: Header.Byron,
      epochSlots: BigInt
  ): Either[ByronChainError, Unit] =
    val boundarySlot = Either.cond(
      !h.boundary || h.slot.mod(epochSlots) == 0,
      (),
      ByronChainError.BoundarySlot(h.slot, epochSlots)
    )
    tip match
      case None =>
        for
          _ <- Either.cond(h.namesGenesis, (), ByronChainError.InitialBoundaryRequired)
          _ <- Either.cond(h.blockNo == 0, (), ByronChainError.FirstBlockNumber(h.blockNo))
          _ <- boundarySlot
        yield ()
      case Some(t) =>
        val minimum = if t.boundary && !h.boundary then t.slot else t.slot + 1
        for
          _ <- Either.cond(h.slot >= minimum, (), ByronChainError.SlotOrder(h.slot, minimum))
          _ <- boundarySlot
          _ <- Either.cond(
            !(h.boundary && h.namesGenesis),
            (),
            ByronChainError.GenesisPredecessor(t.hash)
          )
        yield ()

  /** The delegate's signature over `[prev, proof, slot, difficulty, extra]` under the heavy block
    * tag of the genesis key and the protocol magic, and the genesis key's certificate.
    */
  private def verifySignature(
      main: Header.ByronMain,
      d: ByronSignature.Delegated,
      config: Config
  ): Either[ByronChainError | ProviderDefect, Unit] =
    // The genesis key in the signed tag is the header's, not the certificate's (as upstream).
    val message = ByronCbor.concat(
      Bytes.fromArray("01".getBytes("US-ASCII")),
      main.leader,
      ByronCbor.of(SignMainBlockHeavy),
      config.ledger.magic,
      main.signed
    )
    for _ <- config.signatures(d.delegate.take(32), d.signature, message) match
        case Right(VerificationResult.SignatureVerified) => Right(())
        case Right(VerificationResult.SignatureRejected) => Left(ByronChainError.SignatureRejected)
        case Left(input: xyz.kd5ujc.scarab.crypto.witness.WitnessInputError) =>
          Left(ByronChainError.SignatureInput(input))
        // a verifier failure is no verdict on the block, not a rejected signature (ProviderDefect)
        case Left(other: xyz.kd5ujc.scarab.crypto.witness.VerificationError) =>
          Left(ProviderDefect.Ed25519(other))
    yield ()
