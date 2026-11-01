// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.plutus.ExUnits
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.state.Coin

/** DRep a stake credential delegates its vote to. */
enum DRep:
  case Registered(credential: Credential)
  case AlwaysAbstain
  case AlwaysNoConfidence

  def render: String = this match
    case Registered(Credential.Key(h))    => s"drep-keyHash-${h.hex}"
    case Registered(Credential.Script(h)) => s"drep-scriptHash-${h.hex}"
    case AlwaysAbstain                    => "drep-alwaysAbstain"
    case AlwaysNoConfidence               => "drep-alwaysNoConfidence"

object DRep:
  def decode(n: Node)(using ByteStrings): Either[LedgerDecodeError, DRep] =
    CborRead.array(n, DecodeField.Drep).flatMap {
      case Vector(t, h) =>
        // `Summands "DRep"` (`DRep.hs:90-99`): the tag is a full-width `Word`
        (
          CborRead.uint(t, DecodeField.Drep),
          CborRead.bytesN(h, 28, DecodeField.DrepHash)
        ).tupled
          .flatMap { (tag, hash) =>
            CborRead.small(tag) match
              case Some(0) => Right(Registered(Credential.Key(hash)))
              case Some(1) => Right(Registered(Credential.Script(hash)))
              case _       => Left(LedgerDecodeError.UnknownTag(DecodeField.Drep, tag))
          }
      case Vector(t) =>
        CborRead.uint(t, DecodeField.Drep).flatMap { tag =>
          CborRead.small(tag) match
            case Some(2) => Right(AlwaysAbstain)
            case Some(3) => Right(AlwaysNoConfidence)
            case _       => Left(LedgerDecodeError.UnknownTag(DecodeField.Drep, tag))
        }
      case _ =>
        Left(LedgerDecodeError.Expected(DecodeField.Drep, LedgerDecodeError.Shape.Array, n.value))
    }

/** The comparison key of a pool registration: the parameters without the fields Scarab keeps as
  * their original encodings, and the meaning of those.
  */
private[ledger] final case class PoolIdentity(
    fixed: PoolParams,
    relays: Semantic,
    metadata: Option[Semantic]
)

final case class PoolParams(
    operator: Bytes,
    vrfKeyHash: Bytes,
    pledge: BigInt,
    cost: BigInt,
    margin: Rational,
    rewardAccount: RewardAccount,
    owners: Set[Bytes],
    relays: Bytes,
    metadata: Option[Bytes]
)

object PoolParams:
  /** `PoolMetadata` (`State/StakePool.hs:516-524`, f649f975) of a registration in a body read at
    * `version`: its url is a `Url` ([[CborRead.url]]); the hash is arbitrary bytes, whose upper
    * bound POOL, rather than decoding, enforces after protocol 4.0. The original metadata term is
    * kept for hashing, snapshots and transaction presentation.
    */
  private[ledger] def decodeMetadata(raw: Bytes, version: DecoderVersion)(using
      ByteStrings
  ): Either[LedgerDecodeError, Bytes] =
    metadata(raw, CborRead.url(_, DecodeField.PoolMetadataURL, version))

  /** The hash of a metadata term the body decoder took ([[decodeMetadata]]), for POOL. */
  private[ledger] def metadataHash(
      raw: Bytes
  )(using ByteStrings): Either[LedgerDecodeError, Bytes] =
    metadata(raw, CborRead.text(_, DecodeField.PoolMetadataURL))

  private def metadata(raw: Bytes, url: Node => Either[LedgerDecodeError, String])(using
      ByteStrings
  ): Either[LedgerDecodeError, Bytes] =
    for
      node <- CborRead.decode(raw)
      fields <- CborRead.arrayN(node, 2, DecodeField.PoolMetadata)
      _ <- url(fields(0))
      hash <- CborRead.bytes(fields(1), DecodeField.PoolMetadataHash)
    yield hash

  /** The values the reference's derived `Ord StakePoolParams` compares
    * (`State/StakePool.hs:424-435`, f649f975): the relays (`StrictSeq StakePoolRelay`) and the
    * metadata (`PoolMetadata`, derived `Ord`, `State/StakePool.hs:293-297`) as what they mean, not
    * as their original encodings.
    */
  private[ledger] def identity(p: PoolParams): PoolIdentity =
    PoolIdentity(
      p.copy(relays = Bytes.empty, metadata = None),
      Semantic.ofBytes(p.relays),
      p.metadata.map(Semantic.ofBytes)
    )

  extension (p: PoolParams)
    /** The parameters of a registered pool as the reference reads them back: `StakePoolState` and
      * `StakePoolSnapShot` keep the reward account's credential only (`mkStakePoolState`,
      * `State/StakePool.hs:256-269`), and `stakePoolStateToStakePoolParams`
      * (`State/StakePool.hs:275-292`) puts the account on the node's `network`. A registration
      * before protocol version 5 may name an account on another network (B22); the future
      * parameters of a re-registration keep it (`StakePoolParams`).
      */
    def registeredOn(network: Int): PoolParams =
      if p.rewardAccount.network == network then p
      else p.copy(rewardAccount = p.rewardAccount.copy(network = network))

enum Certificate:
  case Register(credential: Credential, deposit: Option[BigInt])
  case Unregister(credential: Credential, refund: Option[BigInt])
  case Delegate(
      credential: Credential,
      pool: Option[Bytes],
      drep: Option[DRep],
      deposit: Option[BigInt]
  )
  case RegisterPool(params: PoolParams)
  case RetirePool(pool: Bytes, epoch: BigInt)

  /** Pre-Conway genesis key delegation (tag 5). */
  case GenesisDelegate(genesisKey: Bytes, delegate: Bytes, vrf: Bytes)

  /** Pre-Conway move of instantaneous rewards (tag 6): either a coin amount moved to the other pot,
    * or per-credential amounts (deltas from Alonzo) paid from `pot`.
    */
  case MoveRewards(pot: Pot, transfer: Either[BigInt, Map[Credential, BigInt]])
  case RegisterDRep(credential: Credential, deposit: BigInt, anchor: Option[Anchor] = None)
  case UnregisterDRep(credential: Credential, refund: BigInt)
  case UpdateDRep(credential: Credential, anchor: Option[Anchor] = None)
  case CommitteeAuthorize(cold: Credential, hot: Credential)
  case CommitteeResign(cold: Credential, anchor: Option[Anchor] = None)

enum Pot:
  case Reserves
  case Treasury

object Certificate:
  /** What `Ord (ConwayTxCert era)` compares (`Conway/TxCert.hs:473-486, 568-574, 643-647`,
    * f649f975): the decoded fields, so a registration is its pool parameters by meaning.
    */
  private[ledger] def identity(c: Certificate): Either[PoolIdentity, Certificate] = c match
    case RegisterPool(params) => Left(PoolParams.identity(params))
    case other                => Right(other)

  def decode(n: Node, version: DecoderVersion): Either[LedgerDecodeError, Certificate] =
    given ByteStrings = ByteStrings.of(version)
    def cred(i: Int, xs: Vector[Node]) = Credential.decode(xs(i), DecodeField.CertificateCredential)
    def pool(i: Int, xs: Vector[Node]) = CborRead.bytesN(xs(i), 28, DecodeField.PoolId)
    def coin(i: Int, xs: Vector[Node]) = CborRead.uint(xs(i), DecodeField.CertificateCoin)
    def drep(i: Int, xs: Vector[Node]) = DRep.decode(xs(i))
    CborRead.array(n, DecodeField.Certificate).flatMap { xs =>
      for
        tag <- xs.headOption
          .toRight(LedgerDecodeError.Empty(DecodeField.Certificate))
          .flatMap(CborRead.uint(_, DecodeField.CertificateTag))
        arity = Map(
          0 -> 2,
          1 -> 2,
          2 -> 3,
          3 -> 10,
          4 -> 3,
          5 -> 4,
          6 -> 2,
          7 -> 3,
          8 -> 3,
          9 -> 3,
          10 -> 4,
          11 -> 4,
          12 -> 4,
          13 -> 5,
          14 -> 3,
          15 -> 3,
          16 -> 4,
          17 -> 3,
          18 -> 3
        )
        // `decodeRecordSum` (`Shelley/TxCert.hs:475-487`, `Conway/TxCert.hs`): the tag is a
        // full-width `Word`, and a tag beyond `Int` names no certificate
        small <- CborRead
          .small(tag)
          .filter(t => arity.get(t).contains(xs.size))
          .toRight(LedgerDecodeError.TaggedArity(DecodeField.Certificate, tag, xs.size))
        cert <- small match
          case 0 => cred(1, xs).map(Register(_, None))
          case 1 => cred(1, xs).map(Unregister(_, None))
          case 2 => (cred(1, xs), pool(2, xs)).mapN((c, p) => Delegate(c, Some(p), None, None))
          case 3 => poolParams(xs.drop(1), version).map(RegisterPool(_))
          case 4 =>
            (pool(1, xs), CborRead.uint(xs(2), DecodeField.RetirementEpoch)).mapN(RetirePool(_, _))
          case 5 =>
            (
              CborRead.bytesN(xs(1), 28, DecodeField.GenesisKey),
              CborRead.bytesN(xs(2), 28, DecodeField.GenesisDelegate),
              CborRead.bytesN(xs(3), 32, DecodeField.GenesisVrf)
            ).mapN(GenesisDelegate(_, _, _))
          case 6 => moveRewards(xs(1))
          case 7 => (cred(1, xs), coin(2, xs)).mapN((c, d) => Register(c, Some(d)))
          case 8 => (cred(1, xs), coin(2, xs)).mapN((c, d) => Unregister(c, Some(d)))
          case 9 => (cred(1, xs), drep(2, xs)).mapN((c, d) => Delegate(c, None, Some(d), None))
          case 10 =>
            (cred(1, xs), pool(2, xs), drep(3, xs))
              .mapN((c, p, d) => Delegate(c, Some(p), Some(d), None))
          case 11 =>
            (cred(1, xs), pool(2, xs), coin(3, xs))
              .mapN((c, p, d) => Delegate(c, Some(p), None, Some(d)))
          case 12 =>
            (cred(1, xs), drep(2, xs), coin(3, xs))
              .mapN((c, v, d) => Delegate(c, None, Some(v), Some(d)))
          case 13 =>
            (cred(1, xs), pool(2, xs), drep(3, xs), coin(4, xs))
              .mapN((c, p, v, d) => Delegate(c, Some(p), Some(v), Some(d)))
          case 14 => (cred(1, xs), cred(2, xs)).mapN(CommitteeAuthorize(_, _))
          case 15 => (cred(1, xs), Anchor.optional(xs(2), version)).mapN(CommitteeResign(_, _))
          case 16 =>
            (cred(1, xs), coin(2, xs), Anchor.optional(xs(3), version)).mapN(RegisterDRep(_, _, _))
          case 17 => (cred(1, xs), coin(2, xs)).mapN(UnregisterDRep(_, _))
          case 18 => (cred(1, xs), Anchor.optional(xs(2), version)).mapN(UpdateDRep(_, _))
          case t  => Left(LedgerDecodeError.UnknownTag(DecodeField.Certificate, t))
      yield cert
    }

  private def moveRewards(n: Node)(using ByteStrings): Either[LedgerDecodeError, Certificate] =
    CborRead.arrayN(n, 2, DecodeField.MoveInstantaneousRewards).flatMap { xs =>
      for
        pot <- CborRead.uint(xs(0), DecodeField.MIRPot).flatMap {
          case p if p == 0 => Right(Pot.Reserves)
          case p if p == 1 => Right(Pot.Treasury)
          case p           => Left(LedgerDecodeError.UnknownTag(DecodeField.MIRPot, p))
        }
        transfer <- xs(1).value match
          case xyz.kd5ujc.scarab.codec.Value.Map(_) =>
            CborRead
              .map(xs(1), DecodeField.MIRMap)
              .flatMap(_.traverse { case (k, v) =>
                (
                  Credential.decode(k, DecodeField.MIRCredential),
                  CborRead.int(v, DecodeField.MIRAmount)
                ).tupled
              })
              .map(kvs => Right(kvs.toMap))
          case _ => CborRead.uint(xs(1), DecodeField.MIRTransfer).map(Left(_))
      yield MoveRewards(pot, transfer)
    }

  private def poolParams(
      xs: Vector[Node],
      version: DecoderVersion
  )(using ByteStrings): Either[LedgerDecodeError, PoolParams] =
    for
      operator <- CborRead.bytesN(xs(0), 28, DecodeField.PoolOperator)
      vrf <- CborRead.bytesN(xs(1), 32, DecodeField.PoolVrf)
      pledge <- CborRead.uint(xs(2), DecodeField.PoolPledge)
      cost <- CborRead.uint(xs(3), DecodeField.PoolCost)
      margin <- CborRead.rational(xs(4), DecodeField.PoolMargin, CborRead.Bound.Unit)
      account <- CborRead.bytes(xs(5), DecodeField.PoolRewardAccount).flatMap(RewardAccount.decode)
      owners <- CborRead.set(xs(6), DecodeField.PoolOwners, version)(
        CborRead.bytesN(_, 28, DecodeField.PoolOwner)
      )
      _ <- PoolRelays.fromBody(xs(7), version).leftMap(LedgerDecodeError.PoolRelay(_))
      metadata <- xs(8).value match
        case xyz.kd5ujc.scarab.codec.Value.Null => Right(None)
        case _ => PoolParams.decodeMetadata(xs(8).original, version).map(_ => Some(xs(8).original))
    // Match the ledger-state codec's typed representation: the empty relay sequence is the
    // empty value (Bytes.empty), while non-empty relay sequences retain their original wire.
    yield PoolParams(
      operator,
      vrf,
      pledge,
      cost,
      margin,
      account,
      owners.toSet,
      if xs(7).original == Bytes(Vector(0x80.toByte)) then Bytes.empty else xs(7).original,
      metadata
    )

/** A pre-Conway protocol-parameter update proposal: per genesis key hash the proposed fields, and
  * the epoch the proposal targets.
  */
final case class Update(proposals: Map[Bytes, ParamUpdate], epoch: BigInt)

/** Decoded transaction body (all eras); `raw` is the original encoding whose hash is the tx id. */
final case class TxBody(
    raw: Bytes,
    inputs: Vector[TxIn],
    outputs: Vector[TxOut],
    fee: BigInt,
    ttl: Option[BigInt],
    validityStart: Option[BigInt],
    certificates: Vector[Certificate],
    withdrawals: Map[RewardAccount, Coin],
    mint: Map[Bytes, Map[Bytes, BigInt]],
    collateral: Vector[TxIn],
    requiredSigners: Vector[Bytes],
    networkId: Option[BigInt],
    referenceInputs: Vector[TxIn],
    scriptDataHash: Option[Bytes],
    hasGovernance: Boolean,
    donation: BigInt,
    auxDataHash: Option[Bytes] = None,
    update: Option[Update] = None,
    collateralReturn: Option[TxOut] = None,
    totalCollateral: Option[BigInt] = None,
    votes: Vector[(Voter, Vector[(GovActionId, VotingProcedure)])] = Vector.empty,
    proposals: Vector[Proposal] = Vector.empty,
    currentTreasury: Option[BigInt] = None
):
  lazy val id: Bytes = Blake2b.hash256.hash(raw)
  def hasScriptData: Boolean = scriptDataHash.isDefined

object TxBody:
  /** The keys of a body that an era's decoder knows, and `invalidField` refuses the others
    * (`boxBody` in `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxBody.hs`; `bodyFields` in
    * `eras/allegra/impl/src/Cardano/Ledger/Allegra/TxBody.hs`, shared by Mary, and in the Alonzo,
    * Babbage and Conway `TxBody.hs`, cardano-ledger f649f975).
    */
  private def known(era: Era): Set[Int] =
    val shelley = (0 to 7).toSet
    val allegra = shelley + 8 + 9
    val alonzo = allegra ++ Set(11, 13, 14, 15)
    val babbage = alonzo ++ Set(16, 17, 18)
    era match
      case Era.Byron | Era.Shelley => shelley
      case Era.Allegra | Era.Mary  => allegra
      case Era.Alonzo              => alonzo
      case Era.Babbage             => babbage
      case Era.Conway              => babbage - 6 ++ Set(19, 20, 21, 22)

  /** Allegra reads the key 9 as `()`, whose `DecCBOR` is `decodeNull` (`AllegraTxBodyRaw () l`,
    * `eras/allegra/impl/src/Cardano/Ledger/Allegra/TxBody.hs`;
    * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/DecCBOR.hs`, cardano-ledger
    * f649f975): the mint of an era without tokens is `null`, and carries nothing.
    */
  private def unit(n: Node, what: DecodeSite): Either[LedgerDecodeError, Unit] = n.value match
    case xyz.kd5ujc.scarab.codec.Value.Null => Right(())
    case other => Left(LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Null, other))

  private def update(n: Node, era: Era): Either[LedgerDecodeError, Update] =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(era))
    for
      xs <- CborRead.arrayN(n, 2, DecodeField.Update)
      kvs <- CborRead.map(xs(0), DecodeField.ProposedUpdates)
      proposals <- kvs.traverse { case (k, v) =>
        for
          key <- CborRead.bytesN(k, 28, DecodeField.GenesisKeyHash)
          fs <- CborRead.map(v, DecodeField.ParameterUpdate)
          update <- ParamUpdate.read(fs, era, CostModelDecode.Format.Legacy)
        yield key -> update
      }
      epoch <- CborRead.uint(xs(1), DecodeField.UpdateEpoch)
    yield Update(proposals.toMap, epoch)

  /** A body of a block of `era`, read at the era's decoder version ([[DecoderVersion]]). Conway's
    * `bodyFields` (cardano-ledger `eras/conway/impl/src/Cardano/Ledger/Conway/TxBody.hs` at
    * f649f975) has no key 6, refuses a present optional collection that is empty (`fieldGuarded`)
    * and a donation of zero (`decodePositiveCoin`); its sets, ordered sets and maps refuse
    * duplicates from version 9 (`Decoder.hs` `decodeSet`, `decodeMapByKey`; `Data/OSet/Strict.hs`
    * `decodeOSet`).
    */
  def decode(raw: Bytes, era: Era): Either[LedgerDecodeError, TxBody] =
    val mode = Address.Mode.of(era)
    val version = DecoderVersion.of(era)
    given ByteStrings = ByteStrings.of(version)
    val conway = era.ordinal >= Era.Conway.ordinal
    val forms = TxOut.Forms.of(era)
    // `fieldGuarded`: Conway fails a key that is present with an empty value.
    def present[A](what: DecodeSite)(
        r: Either[LedgerDecodeError, Option[Vector[A]]]
    ): Either[LedgerDecodeError, Option[Vector[A]]] =
      r.flatMap(_.traverse(CborRead.nonEmpty(_, what, conway)))
    for
      root <- CborRead.decode(raw)
      f <- CborRead.fields(root, DecodeField.TransactionBody)
      _ <- Either.cond(
        f.keySet.subsetOf(known(era)),
        (),
        LedgerDecodeError.UnknownFields(DecodeField.TransactionBody, f.keySet -- known(era))
      )
      inputs <- f
        .get(0)
        .toRight(LedgerDecodeError.MissingField(DecodeField.TransactionBody, DecodeField.Inputs))
        .flatMap(CborRead.set(_, DecodeField.Inputs, version)(TxIn.decode))
      outputs <- f
        .get(1)
        .toRight(LedgerDecodeError.MissingField(DecodeField.TransactionBody, DecodeField.Outputs))
        .flatMap(CborRead.array(_, DecodeField.Outputs))
        .flatMap(_.traverse(TxOut.decode(_, mode, forms)))
      fee <- f
        .get(2)
        .toRight(LedgerDecodeError.MissingField(DecodeField.TransactionBody, DecodeField.Fee))
        .flatMap(CborRead.uint(_, DecodeField.Fee))
      // Shelley lists the time to live among its required keys (`boxBody`'s list); Allegra made it optional.
      _ <- Either.cond(
        f.contains(3) || era.ordinal > Era.Shelley.ordinal,
        (),
        LedgerDecodeError.MissingField(DecodeField.TransactionBody, DecodeField.Ttl)
      )
      ttl <- f.get(3).traverse(CborRead.uint(_, DecodeField.Ttl))
      start <- f.get(8).traverse(CborRead.uint(_, DecodeField.ValidityStart))
      certs <- present(DecodeField.Certificates)(
        f.get(4)
          .traverse(
            CborRead.oset(_, DecodeField.Certificates, version)(Certificate.decode(_, version))(
              Certificate.identity
            )
          )
      )
      withdrawals <- present(DecodeField.Withdrawals)(
        f.get(5)
          .traverse(
            CborRead
              .map(_, DecodeField.Withdrawals)
              .flatMap(_.traverse { case (k, v) =>
                (
                  CborRead.bytes(k, DecodeField.WithdrawalAccount).flatMap(RewardAccount.decode),
                  CborRead.uint(v, DecodeField.Withdrawal).map(Coin(_))
                ).tupled
              })
              // The decoded/API map enforces the unique keys of Withdrawals (Map AccountAddress Coin).
              // Decode every pair before checking repeats; version 9 and later refuse them. `raw` remains intact
              // (f649f975 Address.hs:980-983; Binary/Decoding/Decoder.hs:810-824).
              .flatMap(ws =>
                if version.atLeast(9) then
                  CborRead.unique(ws.map(_._1), DecodeField.Withdrawals).as(ws)
                else Right(ws)
              )
          )
      ).map(_.map(_.toMap))
      upd <- f.get(6).traverse(update(_, era))
      auxHash <- f.get(7).traverse(CborRead.bytesN(_, 32, DecodeField.AuxiliaryDataHash))
      mint <-
        if era == Era.Allegra then f.get(9).traverse(unit(_, DecodeField.Mint)).as(None)
        else f.get(9).traverse(Value.multiasset(_, DecodeField.Mint, signed = true, mode.values))
      _ <- Either.cond(
        !(conway && mint.exists(_.isEmpty)),
        (),
        LedgerDecodeError.Empty(DecodeField.Mint)
      )
      dataHash <- f.get(11).traverse(CborRead.bytesN(_, 32, DecodeField.ScriptDataHash))
      collateral <- present(DecodeField.Collateral)(
        f.get(13).traverse(CborRead.set(_, DecodeField.Collateral, version)(TxIn.decode))
      )
      signers <- present(DecodeField.RequiredSigners)(
        f.get(14)
          .traverse(
            CborRead.set(_, DecodeField.RequiredSigners, version)(
              CborRead.bytesN(_, 28, DecodeField.Signer)
            )
          )
      )
      network <- f.get(15).traverse(CborRead.uint(_, DecodeField.NetworkId))
      collateralReturn <- f.get(16).traverse(TxOut.decode(_, mode, forms))
      totalCollateral <- f.get(17).traverse(CborRead.uint(_, DecodeField.TotalCollateral))
      refs <- present(DecodeField.ReferenceInputs)(
        f.get(18).traverse(CborRead.set(_, DecodeField.ReferenceInputs, version)(TxIn.decode))
      )
      votes <- present(DecodeField.VotingProcedures)(
        f.get(19)
          .traverse(
            VotingProcedures
              .decode(_, version)
              .flatMap(v => if conway then VotingProcedures.requireBodyDecodable(v) else Right(v))
              .leftMap(LedgerDecodeError.Voting(_))
          )
      )
      proposals <- present(DecodeField.ProposalProcedures)(
        f.get(20)
          .traverse(
            CborRead.oset(_, DecodeField.ProposalProcedures, version)(Proposal.decode(_, version))(
              Proposal.identity
            )
          )
      )
      treasury <- f.get(21).traverse(CborRead.uint(_, DecodeField.CurrentTreasuryValue))
      donation <- f.get(22).traverse(CborRead.uint(_, DecodeField.Donation))
      _ <- Either.cond(
        !(conway && donation.contains(BigInt(0))),
        (),
        LedgerDecodeError.Zero(DecodeField.Donation)
      )
    yield TxBody(
      raw,
      inputs,
      outputs,
      fee,
      ttl,
      start,
      certs.getOrElse(Vector.empty),
      withdrawals.getOrElse(Map.empty),
      mint.getOrElse(Map.empty),
      collateral.getOrElse(Vector.empty),
      signers.getOrElse(Vector.empty),
      network,
      refs.getOrElse(Vector.empty),
      scriptDataHash = dataHash,
      hasGovernance = f.contains(19) || f.contains(20) || f.contains(21),
      donation = donation.getOrElse(0),
      auxDataHash = auxHash,
      update = upd,
      collateralReturn = collateralReturn,
      totalCollateral = totalCollateral,
      votes = votes.getOrElse(Vector.empty),
      proposals = proposals.getOrElse(Vector.empty),
      currentTreasury = treasury
    )

/** A Byron bootstrap witness: `[vkey, signature, chain_code, attributes]`. */
final case class BootstrapWitness(
    vkey: Bytes,
    signature: Bytes,
    chainCode: Bytes,
    attributes: Bytes
):
  /** The address root this witness authorises: `blake2b-224(sha3-256([0, [0, vkey‖chaincode],
    * attributes]))`, with the attributes spliced in as the original CBOR map.
    */
  lazy val addressRoot: Bytes =
    val prefix = Vector(0x83, 0x00, 0x82, 0x00, 0x58, 0x40).map(_.toByte)
    val data = prefix ++ vkey.value ++ chainCode.value ++ attributes.value
    val sha3 = java.security.MessageDigest.getInstance("SHA3-256").digest(data.toArray)
    Blake2b.hash224.hash(Bytes.fromArray(sha3))

/** A redeemer: purpose tag (0 spend, 1 mint, 2 cert, 3 reward, 4 vote, 5 propose), index, data and
  * the execution units budget.
  */
final case class Redeemer(tag: Int, index: BigInt, data: Bytes, exUnits: ExUnits)

/** Decoded witnesses and original encodings used by [[ScriptIntegrity]] and [[Phase2]].
  *
  * @param raw
  *   The witness set's original encoding.
  */
final case class Witnesses(
    vkeys: Vector[(Bytes, Bytes)],
    bootstrap: Vector[BootstrapWitness] = Vector.empty,
    scripts: Vector[Script] = Vector.empty,
    datums: Vector[Bytes] = Vector.empty,
    redeemers: Vector[Redeemer] = Vector.empty,
    redeemersRaw: Option[Bytes] = None,
    datumsRaw: Option[Bytes] = None,
    raw: Option[Bytes] = None
):
  lazy val scriptsByHash: Map[Bytes, Script] = scripts.map(s => s.hash -> s).toMap
  lazy val datumHashes: Set[Bytes] = datums.map(Blake2b.hash256.hash).toSet
  def hasPlutus: Boolean = scripts.exists(_.kind.isPlutus)
  def totalExUnits: ExUnits = redeemers.foldLeft(ExUnits.zero)(_ + _.exUnits)

object Witnesses:
  val empty: Witnesses = Witnesses(Vector.empty)

  /** A witness set of a block of `era`, read at the era's decoder version. From version 9 (below
    * 12) each of the vkey, bootstrap, native script, Plutus script and datum lists is optionally
    * tagged and must not be empty, the redeemers must not be empty, and a Plutus script listed
    * twice fails; duplicate vkeys, native scripts, datums and redeemer keys are tolerated
    * (cardano-ledger `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs` at f649f975,
    * `addrWitsSetDecoder`, `nativeScriptsDecoder`, `scriptDecoderV9`, `TxDatsRaw`, `RedeemersRaw`).
    */
  def decode(raw: Bytes, era: Era): Either[LedgerDecodeError, Witnesses] =
    val version = DecoderVersion.of(era)
    given ByteStrings = ByteStrings.of(version)
    val strict = version.atLeast(9)
    for
      root <- CborRead.decode(raw)
      f <- CborRead.fields(root, DecodeField.WitnessSet)
      _ <- Either.cond(
        f.keySet.subsetOf(Set(0, 1, 2, 3, 4, 5, 6, 7)),
        (),
        LedgerDecodeError.UnknownFields(
          DecodeField.WitnessSet,
          f.keySet -- Set(0, 1, 2, 3, 4, 5, 6, 7)
        )
      )
      vkeys <- f
        .get(0)
        .traverse(
          CborRead.tolerantSet(_, DecodeField.VkeyWitnesses, version) { w =>
            CborRead
              .arrayN(w, 2, DecodeField.VkeyWitness)
              .flatMap(xs =>
                (
                  CborRead.bytesN(xs(0), 32, DecodeField.Vkey),
                  CborRead.bytesN(xs(1), 64, DecodeField.Signature)
                ).tupled
              )
          }
        )
      native <- f
        .get(1)
        .traverse(
          CborRead
            .tolerantSet(_, DecodeField.NativeScripts, version)(Script.native)
            .map(_.distinctBy(_.hash))
        )
      bootstrap <- f
        .get(2)
        .traverse(
          CborRead.tolerantSet(_, DecodeField.BootstrapWitnesses, version) { w =>
            CborRead.arrayN(w, 4, DecodeField.BootstrapWitness).flatMap { xs =>
              (
                CborRead.bytesN(xs(0), 32, DecodeField.BootstrapVkey),
                CborRead.bytesN(xs(1), 64, DecodeField.BootstrapSignature),
                CborRead.bytesN(xs(2), 32, DecodeField.ChainCode),
                CborRead.bytes(xs(3), DecodeField.Attributes)
              ).mapN(BootstrapWitness(_, _, _, _))
            }
          }
        )
      plutus = (tag: Int, kind: ScriptKind) =>
        f.get(tag)
          .traverse(n =>
            CborRead
              .set(n, DecodeField.PlutusScripts, version)(
                CborRead.bytes(_, DecodeField.PlutusScript)
              )
              .flatMap(CborRead.nonEmpty(_, DecodeField.PlutusScripts, strict))
              .map(_.map(Script.plutus(kind, _)))
          )
      v1 <- plutus(3, ScriptKind.PlutusV1)
      v2 <- plutus(6, ScriptKind.PlutusV2)
      v3 <- plutus(7, ScriptKind.PlutusV3)
      datums <- f
        .get(4)
        .traverse(
          CborRead
            .tolerantSet(_, DecodeField.Datums, version)(n => Right(n.original))
            .map(_.distinct)
        )
      redeemers <- f.get(5).traverse(redeemerList(_, version))
    yield Witnesses(
      vkeys.getOrElse(Vector.empty),
      bootstrap.getOrElse(Vector.empty),
      native.getOrElse(Vector.empty) ++ v1.getOrElse(Vector.empty) ++ v2.getOrElse(Vector.empty) ++
        v3.getOrElse(Vector.empty),
      datums.getOrElse(Vector.empty),
      redeemers.getOrElse(Vector.empty),
      f.get(5).map(_.original),
      f.get(4).map(_.original),
      Some(raw)
    )

  private def exUnits(n: Node): Either[LedgerDecodeError, ExUnits] =
    CborRead
      .arrayN(n, 2, DecodeField.ExUnits)
      .flatMap(xs =>
        (CborRead.uint(xs(0), DecodeField.Mem), CborRead.uint(xs(1), DecodeField.Steps))
          .mapN(ExUnits(_, _))
      )

  /** `[* [tag, index, data, ex_units]]`, or (Conway) `{[tag, index] => [data, ex_units]}`. The
    * ledger keeps redeemers in a map keyed by purpose, so of several entries for the same
    * `(tag, index)`, in either form, the last one counts (seen on Preview: slot 12924758, six
    * entries for one mint with different data; slot 62567517, one entry twice). From decoder
    * version 9 either form must not be empty; below it only the list is read (`RedeemersRaw`,
    * TxWits.hs).
    */
  private def redeemerList(
      n: Node,
      version: DecoderVersion
  ): Either[LedgerDecodeError, Vector[Redeemer]] =
    val all: Either[LedgerDecodeError, Vector[Redeemer]] = n.value match
      case xyz.kd5ujc.scarab.codec.Value.Map(kvs) if version.atLeast(9) =>
        kvs.traverse { case (k, v) =>
          for
            key <- CborRead.arrayN(k, 2, DecodeField.RedeemerKey)
            tag <- CborRead.uint(key(0), DecodeField.RedeemerTag)
            index <- CborRead.uint(key(1), DecodeField.RedeemerIndex)
            value <- CborRead.arrayN(v, 2, DecodeField.RedeemerValue)
            units <- exUnits(value(1))
          yield Redeemer(tag.toInt, index, value(0).original, units)
        }
      case _ =>
        CborRead
          .array(n, DecodeField.Redeemers)
          .flatMap(_.traverse { r =>
            for
              xs <- CborRead.arrayN(r, 4, DecodeField.Redeemer)
              tag <- CborRead.uint(xs(0), DecodeField.RedeemerTag)
              index <- CborRead.uint(xs(1), DecodeField.RedeemerIndex)
              units <- exUnits(xs(3))
            yield Redeemer(tag.toInt, index, xs(2).original, units)
          })
    all.flatMap(CborRead.nonEmpty(_, DecodeField.Redeemers, version.atLeast(9))).map { rs =>
      val last = rs.zipWithIndex.map((r, i) => (r.tag, r.index) -> i).toMap
      rs.zipWithIndex.collect { case (r, i) if last((r.tag, r.index)) == i => r }
    }

/** A transaction as included in a block. */
final case class Tx(
    body: TxBody,
    witnesses: Witnesses,
    size: Long,
    isValid: Boolean,
    auxiliary: Option[Bytes]
):
  def hasAuxiliaryData: Boolean = auxiliary.isDefined

object Tx:
  /** A whole transaction as a client sends it: `[body, witnesses, auxiliary_data / nil]` before
    * Alonzo and `[body, witnesses, is_valid, auxiliary_data / nil]` from it, decoded exactly as a
    * block's parts are ([[fromBlock]]).
    */
  def fromEnvelope(bytes: Bytes, era: Era): Either[LedgerDecodeError, Tx] =
    CborRead.decode(bytes).flatMap { node =>
      val withFlag = era.ordinal >= Era.Alonzo.ordinal
      def whole(body: Node, witnesses: Node, aux: Node, flag: Boolean) =
        val auxBytes = aux.value match
          case xyz.kd5ujc.scarab.codec.Value.Null => None
          case _                                  => Some(aux.original)
        fromBlock(Block.Transaction(body.original, witnesses.original, auxBytes, flag), era)
      CborRead.array(node, DecodeField.Transaction).flatMap {
        case Vector(body, witnesses, flag, aux) if withFlag =>
          flag.value match
            case xyz.kd5ujc.scarab.codec.Value.Bool(valid) => whole(body, witnesses, aux, valid)
            case other =>
              Left(
                LedgerDecodeError
                  .Expected(DecodeField.IsValid, LedgerDecodeError.Shape.Boolean, other)
              )
        case Vector(body, witnesses, aux) if !withFlag => whole(body, witnesses, aux, true)
        case xs =>
          Left(
            LedgerDecodeError
              .ArrayLength(DecodeField.Transaction, if withFlag then 4 else 3, xs.size)
          )
      }
    }

  /** Ledger size is the size of the serialized transaction: `[body, witnesses, isValid, aux]` from
    * Alonzo, `[body, witnesses, aux]` before.
    */
  def fromBlock(t: Block.Transaction, era: Era): Either[LedgerDecodeError, Tx] =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(era))
    for
      body <- TxBody.decode(t.body, era)
      witnesses <- Witnesses.decode(t.witnesses, era)
      _ <- t.auxiliaryData.traverse_(bytes =>
        AuxiliaryScripts.read(bytes, DecoderVersion.of(era).value)
      )
    yield
      // The fee size is the array header, body, witnesses and auxiliary data (`null` is one byte);
      // the `is_valid` flag is not counted (checked against Preview transactions).
      val aux = t.auxiliaryData.fold(1L)(_.size.toLong)
      Tx(
        body,
        witnesses,
        1L + t.body.size + t.witnesses.size + aux,
        t.isValid,
        t.auxiliaryData
      )
