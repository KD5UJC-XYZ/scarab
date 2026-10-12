// SPDX-License-Identifier: Apache-2.0
package lab.node.consensus

import cats.syntax.all.*
import lab.cbor.Bytes
import lab.node.{Block, ByronGenesis, Header}
import lab.node.Header.ByronSignature
import lab.node.ledger.{LedgerGlobals, LedgerState}
import lab.node.ledger.byron.*
import lab.witness.{StrictEd25519, VerificationResult}

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

/** The PBFT signing window: the last `k` blocks' (slot, genesis key hash) pairs. */
final case class PBftState(signers: Vector[(BigInt, Bytes)]):
  def lastSignedSlot: Option[BigInt] = signers.lastOption.map(_._1)
  def countSignedBy(genesisKey: Bytes): Int = signers.count(_._2 == genesisKey)

  /** `PBftState.append`: the oldest signer leaves once the window is full. */
  def append(slot: BigInt, genesisKey: Bytes, windowSize: Int): PBftState =
    val grown = signers :+ ((slot, genesisKey))
    PBftState(if signers.size == windowSize then grown.tail else grown)

object PBftState:
  val empty: PBftState = PBftState(Vector.empty)

/** What the Byron era needs besides the genesis: the node configuration's network magic requirement
  * and PBFT signature threshold, the configured Shelley epoch (a `Test` override) and how the Byron
  * ledger becomes the Shelley one.
  */
final case class ByronRulesConfig(
    ledger: ByronLedger.Config,
    signatureThreshold: Double,
    /** `TestShelleyHardForkAtEpoch`: when set, the transition epoch is not derived. */
    configuredShelleyEpoch: Option[BigInt],
    /** The Shelley-era ledger state entered from the final Byron UTxO, for the given globals. */
    handover: (ByronLedgerState, LedgerGlobals) => Either[String, LedgerState]
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
  ): Either[String, (State, ByronCheck)] =
    val header = block.header
    val genesis = config.ledger.genesis
    val expectedPrev = state.tip.map(_.hash).getOrElse(genesis.hash)
    for
      _ <- Either.cond(
        header.protocolMagic == config.ledger.magicId,
        (),
        s"protocol magic ${header.protocolMagic} differs from the genesis ${genesis.protocolMagic}"
      )
      _ <- Either.cond(
        header.prevHash.contains(expectedPrev),
        (),
        s"previous hash ${header.prevHash.fold("none")(_.hex)} is not the tip ${expectedPrev.hex}"
      )
      _ <- Either.cond(
        header.slotInEpoch < config.ledger.epochSlots,
        (),
        s"slot ${header.slotInEpoch} outside a ${config.ledger.epochSlots}-slot epoch"
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
  ): Either[String, (State, ByronCheck)] =
    val header = block.header
    val ticked = ByronLedger.tick(config.ledger, state.ledger, header.slot)
    ByronLedger
      .applyBoundary(config.ledger, ticked, header, block.size)
      .leftMap(_.message)
      .map(ledger => State(Some(tipOf(header)), ledger, state.pbft) -> ByronCheck.Boundary)

  private def main(
      state: State,
      block: Block.Byron,
      config: Config
  ): Either[String, (State, ByronCheck)] =
    val header = block.header
    val genesis = config.ledger.genesis
    // The tick to the block's slot comes first: it activates the delegations due, which the
    // header is validated against, and adopts a confirmed update at an epoch boundary.
    val ticked = ByronLedger.tick(config.ledger, state.ledger, header.slot)
    for
      main <- header.main.toRight("main header without consensus data")
      delegated <- main.signature match
        case d: ByronSignature.Delegated => Right(d)
      _ <- verifySignature(main, delegated, config)
      delegate = ByronCbor.keyHash(delegated.delegate)
      genesisKey <- ticked.delegation
        .delegatorOf(delegate)
        .toRight(s"signer ${delegate.hex.take(16)} is not a delegate of any genesis key")
      _ <- Either.cond(
        state.pbft.lastSignedSlot.forall(_ <= header.slot),
        (),
        s"slot ${header.slot} is below the last signed slot ${state.pbft.lastSignedSlot.getOrElse(0)}"
      )
      k = config.ledger.genesis.securityParam
      pbft = state.pbft.append(header.slot, genesisKey, k)
      threshold = math.floor(config.signatureThreshold * k.toDouble).toLong
      _ <- Either.cond(
        pbft.countSignedBy(genesisKey) <= threshold,
        (),
        s"genesis key ${genesisKey.hex.take(16)} signed ${pbft.countSignedBy(genesisKey)} of the last $k blocks, above the threshold $threshold"
      )
      body <- ByronBody.decode(block.body)
      ledger <- ByronLedger
        .applyMain(config.ledger, ticked, header, block.size, body)
        .leftMap(_.message)
    yield
      val onSchedule = ByronCbor.keyHash(main.leader) == scheduledLeader(genesis, header.slot)
      State(Some(tipOf(header)), ledger, pbft) -> ByronCheck.Verified(onSchedule, body.txs.size)

  private def orderOk(tip: Option[ByronTip], h: Header.Byron): Either[String, Unit] =
    tip match
      case None =>
        Either.cond(
          h.boundary && h.epoch == 0,
          (),
          "the chain must start with the epoch 0 boundary"
        )
      case Some(t) if h.boundary =>
        Either.cond(
          h.epoch > t.epoch && !t.boundary,
          (),
          s"boundary block of epoch ${h.epoch} after epoch ${t.epoch}${
              if t.boundary then " boundary" else ""
            }"
        )
      case Some(t) =>
        Either.cond(
          h.epoch >= t.epoch && (h.slot > t.slot || (t.boundary && h.slot == t.slot)),
          (),
          s"slot ${h.slot} does not follow ${t.slot}"
        )

  /** The delegate's signature over `[prev, proof, slot, difficulty, extra]` under the heavy block
    * tag of the genesis key and the protocol magic, and the genesis key's certificate.
    */
  private def verifySignature(
      main: Header.ByronMain,
      d: ByronSignature.Delegated,
      config: Config
  ): Either[String, Unit] =
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
        case Right(VerificationResult.SignatureRejected) => Left("block signature rejected")
        case Left(other)                                 => Left(s"signature check failed: $other")
    yield ()
