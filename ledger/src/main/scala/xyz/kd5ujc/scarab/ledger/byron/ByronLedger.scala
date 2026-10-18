// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import xyz.kd5ujc.scarab.ledger.LedgerDecodeError
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{ByronGenesis, Header}
import xyz.kd5ujc.scarab.ledger.{CborRead, TxIn}

/** Why a Byron block is refused by the ledger rules (`ChainValidationError`). */
enum ByronError:
  case ProtocolMagicMismatch(block: BigInt, genesis: BigInt)
  case HeaderTooLarge(max: BigInt, actual: BigInt)
  case BlockTooLarge(max: BigInt, actual: BigInt)
  case Decode(cause: LedgerDecodeError)
  case ProofMismatch(which: String)
  case Delegation(error: DelegationError)
  case Update(error: UpdateError)
  case TxTooLarge(max: BigInt, actual: BigInt)
  case MissingInput(input: TxIn)
  case OverlappingOutputs(id: Bytes)
  case WitnessCount(inputs: Int, witnesses: Int)
  case WrongSignature(input: TxIn)
  case WrongKey(input: TxIn)
  case FeeTooSmall(min: BigInt, actual: BigInt)
  case LovelaceOutOfRange(what: String)
  case NetworkMagicMismatch(expected: Option[Long], actual: Option[Long])
  case UnknownAttributes(what: String)
  case MissingDelegatedSignature

  def message: String = this match
    case ProtocolMagicMismatch(b, g) => s"block protocol magic $b differs from the genesis $g"
    case HeaderTooLarge(m, a)        => s"header of $a bytes exceeds $m"
    case BlockTooLarge(m, a)         => s"block of $a bytes exceeds $m"
    case Decode(r)                   => s"block body does not decode: $r"
    case ProofMismatch(w)            => s"$w payload does not match the header's proof"
    case Delegation(e)               => s"delegation: ${e.message}"
    case Update(e)                   => s"update: ${e.message}"
    case TxTooLarge(m, a)            => s"transaction of $a bytes exceeds $m"
    case MissingInput(i)             => s"input ${i.id.hex.take(16)}#${i.index} is not in the UTxO"
    case OverlappingOutputs(id)      => s"outputs of ${id.hex.take(16)} overlap existing entries"
    case WitnessCount(i, w)          => s"$i inputs but $w witnesses"
    case WrongSignature(i) => s"witness signature of ${i.id.hex.take(16)}#${i.index} rejected"
    case WrongKey(i) => s"witness key does not match the address of ${i.id.hex.take(16)}#${i.index}"
    case FeeTooSmall(m, a)          => s"fee $a is below the minimum $m"
    case LovelaceOutOfRange(w)      => s"lovelace out of range ($w)"
    case NetworkMagicMismatch(e, a) => s"output network magic $a, expected $e"
    case UnknownAttributes(w)       => s"too many unknown attribute bytes in $w"
    case MissingDelegatedSignature  => "main header without a delegated signature"

/** A Byron block's body, decoded: `[tx payload, ssc payload, delegation payload, update payload]`.
  * The SSC payload is not used on these networks and is not interpreted.
  */
final case class ByronBody(
    txs: Vector[ByronTxAux],
    delegation: Vector[ByronCert],
    rawDelegation: Bytes,
    update: UpdatePayload
)

object ByronBody:
  def decode(body: Bytes): Either[LedgerDecodeError, ByronBody] =
    for
      root <- CborRead.decode(body, ByronCbor.BodyLimits)
      xs <- CborRead.arrayN(root, 4, "Byron body")
      txs <- CborRead.array(xs(0), "Byron tx payload").flatMap(_.traverse(ByronTx.decodeAux))
      certs <- CborRead
        .array(xs(2), "Byron delegation payload")
        .flatMap(_.traverse(ByronCert.decode))
      update <- UpdatePayload.decode(xs(3))
      raw = xs(2).original
    yield ByronBody(txs, certs, raw, update.copy(raw = xs(3).original))

/** The Byron ledger: the `ChainValidationState` of cardano-ledger
  * (`Cardano.Chain.Block.Validation`) minus the previous-block hash, which the chain-follow state
  * tracks. Pure.
  */
final case class ByronLedgerState(
    lastSlot: BigInt,
    tipBlockNo: BigInt,
    utxo: Map[TxIn, ByronOut],
    update: UpdateState,
    delegation: DelegationState,
    /** Per candidate protocol version, the number of the block in which it became a candidate
      * (`ByronTransition` of ouroboros-consensus).
      */
    candidateBlocks: Map[ByronVersion, BigInt]
)

object ByronLedger:
  val MaxLovelace: BigInt = BigInt("45000000000000000")

  /** The protocol major version whose adoption is the transition to Shelley. */
  val ShelleyMajor: BigInt = 2

  final case class Config(genesis: ByronGenesis, requiresNetworkMagic: Boolean):
    def magicId: BigInt = BigInt(genesis.protocolMagic)
    def magic: Bytes = ByronCbor.uint(magicId)
    def k: BigInt = BigInt(genesis.securityParam)
    def epochSlots: BigInt = genesis.epochSlots

    /** The network magic carried by addresses: none on a `RequiresNoMagic` network (mainnet). */
    def addressMagic: Option[Long] = Option.when(requiresNetworkMagic)(genesis.protocolMagic)

  /** The UTxO of the genesis file: AVVM balances at redeem addresses, then the non-AVVM balances;
    * each entry spends from the pseudo transaction whose id is the address hash.
    */
  def genesisUtxo(
      genesis: ByronGenesis,
      addressMagic: Option[Long]
  ): Either[LedgerDecodeError, Map[TxIn, ByronOut]] =
    genesisUtxo(genesis.avvm, genesis.nonAvvm, addressMagic)

  def genesisUtxo(
      avvm: Vector[(Bytes, BigInt)],
      nonAvvm: Vector[(Bytes, BigInt)],
      addressMagic: Option[Long]
  ): Either[LedgerDecodeError, Map[TxIn, ByronOut]] =
    val balances =
      avvm.map((key, coin) => ByronAddress.redeem(key, addressMagic) -> coin) ++ nonAvvm
    balances.foldLeftM(Map.empty[TxIn, ByronOut]) { case (acc, (address, coin)) =>
      val in = TxIn(Blake2b.hash256.hash(address), 0)
      Either.cond(
        !acc.contains(in),
        acc.updated(in, ByronOut(address, coin)),
        LedgerDecodeError.DuplicateGenesisAddress
      )
    }

  def initial(config: Config): Either[LedgerDecodeError, ByronLedgerState] =
    val g = config.genesis
    for
      utxo <- genesisUtxo(g, config.addressMagic)
      certs = g.delegations.map(cert)
      delegation <- DelegationRules
        .initial(config.magic, g.genesisKeys, certs)
        .leftMap(LedgerDecodeError.Delegation(_))
    yield ByronLedgerState(
      0,
      0,
      utxo,
      ByronUpdate.initial(g.params),
      delegation,
      Map.empty
    )

  /** A genesis delegation entry as a certificate (its signature is the `cert` field). */
  private def cert(d: xyz.kd5ujc.scarab.ledger.ByronDelegation): ByronCert =
    ByronCert(Bytes.empty, d.epoch, ByronCbor.uint(d.epoch), d.issuer, d.delegate, d.cert)

  private def epochOf(config: Config, slot: BigInt): BigInt = slot / config.epochSlots

  // ---------------------------------------------------------------- tick

  /** `applyChainTick`: entering a new epoch may adopt a confirmed protocol update; delegations due
    * by `slot` take effect.
    */
  def tick(config: Config, st: ByronLedgerState, slot: BigInt): ByronLedgerState =
    val epoch = epochOf(config, slot)
    val update =
      if epoch > epochOf(config, st.lastSlot) then
        ByronUpdate.registerEpoch(st.update, config.k, config.epochSlots, epoch)
      else st.update
    st.copy(update = update, delegation = DelegationRules.tick(st.delegation, epoch, slot))

  // ---------------------------------------------------------------- blocks

  /** The ledger rules for a main block (`updateBlock` of cardano-ledger) after the tick to its
    * slot: sizes, proofs, delegation certificates, transactions and the update payload.
    */
  def applyMain(
      config: Config,
      ticked: ByronLedgerState,
      header: Header.Byron,
      blockSize: Int,
      body: ByronBody
  ): Either[ByronError, ByronLedgerState] =
    val params = ticked.update.adoptedParams
    val slot = header.slot
    val main = header.main
    for
      _ <- Either.cond(
        header.protocolMagic == config.magicId,
        (),
        ByronError.ProtocolMagicMismatch(header.protocolMagic, config.magicId)
      )
      _ <- Either.cond(
        BigInt(header.raw.size) <= params.maxHeaderSize,
        (),
        ByronError.HeaderTooLarge(params.maxHeaderSize, header.raw.size)
      )
      _ <- Either.cond(
        BigInt(blockSize) <= params.maxBlockSize,
        (),
        ByronError.BlockTooLarge(params.maxBlockSize, blockSize)
      )
      _ <- checkProofs(header, body)
      issuerDelegate <- main
        .flatMap(_.signature match
          case d: Header.ByronSignature.Delegated => Some(ByronCbor.keyHash(d.delegate)))
        .toRight(ByronError.MissingDelegatedSignature)
      delegation <- DelegationRules
        .update(
          DelegationRules.Env(
            config.magic,
            config.genesis.genesisKeys.toSet,
            epochOf(config, slot),
            slot,
            config.k
          ),
          ticked.delegation,
          body.delegation
        )
        .leftMap(ByronError.Delegation(_))
      utxo <- body.txs.foldLeftM(ticked.utxo)(applyTx(config, params, _, _))
      update <- ByronUpdate
        .register(
          ByronUpdate.Env(
            config.magicId,
            config.magic,
            config.k,
            slot,
            config.genesis.genesisKeys.size,
            ticked.delegation.delegationMap
          ),
          ticked.update,
          body.update,
          header.blockVersion,
          issuerDelegate
        )
        .leftMap(ByronError.Update(_))
    yield
      val nowCandidates = update.candidates.map(_.version).toSet
      val candidateBlocks =
        ticked.candidateBlocks.view.filterKeys(nowCandidates.contains).toMap ++
          nowCandidates.filterNot(ticked.candidateBlocks.contains).map(_ -> header.blockNo)
      ticked.copy(
        lastSlot = slot,
        tipBlockNo = header.blockNo,
        utxo = utxo,
        update = update,
        delegation = delegation,
        candidateBlocks = candidateBlocks
      )

  /** An epoch-boundary block changes only the last slot (the first slot of its epoch). */
  def applyBoundary(
      config: Config,
      ticked: ByronLedgerState,
      header: Header.Byron,
      blockSize: Int
  ): Either[ByronError, ByronLedgerState] =
    Either.cond(
      blockSize <= 2000000,
      ticked.copy(lastSlot = header.epoch * config.epochSlots, tipBlockNo = header.blockNo),
      ByronError.BlockTooLarge(2000000, blockSize)
    )

  /** The header's proof against the body: transactions (count, Merkle root, witnesses hash),
    * delegation payload hash and update payload hash. The SSC proof is not checked, as upstream.
    */
  private[scarab] def checkProofs(header: Header.Byron, body: ByronBody): Either[ByronError, Unit] =
    val expected = for
      root <- CborRead.decode(header.bodyProof)
      xs <- CborRead.arrayN(root, 4, "body proof")
      tx <- CborRead.arrayN(xs(0), 3, "tx proof")
      count <- CborRead.uint(tx(0), "tx count")
      merkle <- CborRead.bytes(tx(1), "tx root")
      witnesses <- CborRead.bytes(tx(2), "witnesses hash")
      dlg <- CborRead.bytes(xs(2), "delegation proof")
      upd <- CborRead.bytes(xs(3), "update proof")
    yield (count, merkle, witnesses, dlg, upd)
    expected.leftMap(ByronError.Decode(_)).flatMap { (count, merkle, witnesses, dlg, upd) =>
      val witnessBytes =
        ByronCbor.of(0x9f) ++ body.txs.foldLeft(Bytes.empty)(_ ++ _.rawWitnesses) ++ ByronCbor.of(
          0xff
        )
      for
        _ <- Either.cond(
          dlg == Blake2b.hash256.hash(body.rawDelegation),
          (),
          ByronError.ProofMismatch("delegation")
        )
        _ <- Either.cond(
          count == body.txs.size &&
            merkle == ByronTx.merkleRoot(body.txs.map(_.tx.raw)) &&
            witnesses == Blake2b.hash256.hash(witnessBytes),
          (),
          ByronError.ProofMismatch("transaction")
        )
        _ <- Either.cond(
          upd == Blake2b.hash256.hash(body.update.raw),
          (),
          ByronError.ProofMismatch("update")
        )
      yield ()
    }

  // ---------------------------------------------------------------- transactions

  private[scarab] def outputs(config: Config, tx: ByronTx): Either[ByronError, Unit] =
    tx.outputs.traverse_ { out =>
      ByronAddress
        .decode(out.address)
        .leftMap(ByronError.Decode(_))
        .flatMap { a =>
          for
            _ <- Either.cond(
              a.unknownAttributesLength < 128,
              (),
              ByronError.UnknownAttributes("address")
            )
            _ <- Either.cond(
              a.networkMagic == config.addressMagic,
              (),
              ByronError.NetworkMagicMismatch(config.addressMagic, a.networkMagic)
            )
          yield ()
        }
    }

  /** `updateUTxOTxWitness`: the witnesses and the fee, then the UTxO update. */
  def applyTx(
      config: Config,
      params: ByronParams,
      utxo: Map[TxIn, ByronOut],
      aux: ByronTxAux
  ): Either[ByronError, Map[TxIn, ByronOut]] =
    val tx = aux.tx
    val inputSet = tx.inputs.toSet
    for
      resolved <- tx.inputs.traverse(i =>
        utxo.get(i).toRight(ByronError.MissingInput(i)).map(i -> _)
      )
      addresses <- resolved.traverse((i, o) =>
        ByronAddress.decode(o.address).leftMap(ByronError.Decode(_)).map(i -> _)
      )
      // `zip`: surplus witnesses or surplus inputs are ignored, as upstream does
      _ <- addresses.zip(aux.witnesses).traverse_ { case ((in, address), w) =>
        witness(config, tx, in, address, w)
      }
      _ <- Either.cond(
        BigInt(aux.raw.size) <= params.maxTxSize,
        (),
        ByronError.TxTooLarge(params.maxTxSize, aux.raw.size)
      )
      _ <- Either.cond(tx.attributesLength < 128, (), ByronError.UnknownAttributes("transaction"))
      _ <- outputs(config, tx)
      spent = inputSet.toVector.map(utxo)
      balanceIn = spent.map(_.value).sum
      balanceOut = tx.outputs.map(_.value).sum
      _ <- Either.cond(balanceIn <= MaxLovelace, (), ByronError.LovelaceOutOfRange("input balance"))
      _ <- Either.cond(
        balanceOut <= MaxLovelace,
        (),
        ByronError.LovelaceOutOfRange("output balance")
      )
      _ <- Either.cond(balanceIn >= balanceOut, (), ByronError.LovelaceOutOfRange("fee"))
      allRedeem = spent.forall(o => ByronAddress.decode(o.address).exists(_.isRedeem))
      minFee = if allRedeem then BigInt(0) else params.txFeePolicy.minimumFee(aux.raw.size)
      _ <- Either.cond(
        balanceIn - balanceOut >= minFee,
        (),
        ByronError.FeeTooSmall(minFee, balanceIn - balanceOut)
      )
      created = tx.outputs.zipWithIndex.map((o, ix) => TxIn(tx.id, ix) -> o)
      remaining = utxo -- inputSet
      _ <- Either.cond(
        created.forall((i, _) => !remaining.contains(i)),
        (),
        ByronError.OverlappingOutputs(tx.id)
      )
    yield remaining ++ created

  private def witness(
      config: Config,
      tx: ByronTx,
      input: TxIn,
      address: ByronAddress,
      w: ByronWitness
  ): Either[ByronError, Unit] =
    val signedHash = ByronCbor.of(0x58, 0x20) ++ tx.id
    val (kind, key, signature) = w match
      case ByronWitness.VerKey(k, s) => (ByronSig.SignTx, k, s)
      case ByronWitness.Redeem(k, s) => (ByronSig.SignRedeemTx, k, s)
    val message = ByronSig.tag(kind, config.magic) ++ signedHash
    val verified = w match
      case _: ByronWitness.VerKey => ByronSig.verify(key, signature, message)
      case _: ByronWitness.Redeem => ByronSig.verifyRedeem(key, signature, message)
    for
      _ <- Either.cond(verified, (), ByronError.WrongSignature(input))
      _ <- Either.cond(
        address.addrType == (w match
          case _: ByronWitness.VerKey => ByronAddress.VerKey
          case _: ByronWitness.Redeem => ByronAddress.Redeem) && address.checks(key),
        (),
        ByronError.WrongKey(input)
      )
    yield ()

  // ---------------------------------------------------------------- transition to Shelley

  /** The epoch in which the chain continues in Shelley, once known: the first epoch after
    * `candidate + 4k` slots for a candidate update to protocol major version 2 that is stable.
    * Stability is `2k` slots (the Byron ledger's own notion) or, as the hard-fork combinator
    * insists, `k` blocks since the block in which it became a candidate
    * (`Ouroboros.Consensus.Byron.ByronHFC.byronTransition`).
    */
  def transition(config: Config, st: ByronLedgerState): Option[BigInt] =
    st.update.candidates
      .filter(_.version.major == ShelleyMajor)
      .collectFirst {
        case c if st.lastSlot - c.slot >= 2 * config.k => adoptionEpoch(config, c)
        case c if st.candidateBlocks.get(c.version).exists(b => st.tipBlockNo - b >= config.k) =>
          adoptionEpoch(config, c)
      }

  private def adoptionEpoch(config: Config, c: CandidateUpdate): BigInt =
    epochOf(config, c.slot + 4 * config.k) + 1
