// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import cats.syntax.all.*
import io.circe.Json
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.{Block, ConfigError, EpochGeometry}
import xyz.kd5ujc.scarab.ledger.{GenDelegPair, LedgerView, Nonce, StakeView}

/** One protocol-parameter update: field index -> original CBOR bytes. Two proposals are "the same
  * update" when their fields are byte-identical (what the quorum vote counts).
  */
final case class ParamUpdate(fields: Map[Int, Bytes])

object ParamUpdate:
  private val CostModelsField = 18
  private val ExtraEntropyField = 13

  /** The update keys of the `PParamsUpdate` of a pre-Conway era: the `ppUpdate` tags of its
    * `eraPParams` (cardano-ledger f649f975). Shelley, Allegra and Mary have 0 to 16
    * (`Shelley/PParams.hs:344-496`); Alonzo drops the minimum UTxO value (15) and adds 17 to 24
    * (`Alonzo/PParams.hs:344-369, 664-727`); Babbage also drops `d` (12) and the extra entropy (13)
    * (`Babbage/PParams.hs:203-226, 411`).
    */
  enum Keys(val keys: Set[Int]):
    case ShelleyToMary extends Keys((0 to 16).toSet)
    case Alonzo extends Keys(((0 to 14) ++ (16 to 24)).toSet)
    case Babbage extends Keys(((0 to 11) ++ Seq(14) ++ (16 to 24)).toSet)

  object Keys:
    /** The keys of the update of `era`; none for Byron and Conway, which have no PPUP. */
    def of(era: Era): Option[Keys] = era match
      case Era.Shelley | Era.Allegra | Era.Mary => Some(ShelleyToMary)
      case Era.Alonzo                           => Some(Alonzo)
      case Era.Babbage                          => Some(Babbage)
      case Era.Byron | Era.Conway               => None

  /** The update of a transaction or proposal from its map of field index to value, as the shared
    * sparse decoder of `PParamsUpdate` reads it below decoder version 12 (`SparseKeyed`,
    * `Core/PParams.hs:257-293`; `applyField`, `Coders.hs:562-570`): entries in order, the key a
    * `Word`, a key read before refused (`duplicateKey`), a key that is not one of the era's update
    * keys refused (`invalidKey`). The cost models of field 18 are checked by the rule of the
    * decoder version of the era ([[CostModelDecode]]), and the extra entropy of field 13 (Shelley
    * to Alonzo, `ppExtraEntropy`, `Shelley/PParams.hs:463-469`) is read as a [[Nonce]], its hash in
    * the form `ByteStrings` gives; the other fields are kept as they are and read when the update
    * is applied.
    */
  def read(
      fields: Vector[(Node, Node)],
      keys: Keys,
      costModels: CostModelDecode.Format
  )(using ByteStrings): Either[LedgerDecodeError, ParamUpdate] =
    fields
      .foldLeftM(Map.empty[Int, Bytes]) { case (acc, (k, v)) =>
        for
          index <- Unsigned.read[Unsigned.Width.W64](k, DecodeField.ParameterIndex).map(_.value)
          _ <- Either.cond(
            !(index.isValidInt && acc.contains(index.toInt)),
            (),
            LedgerDecodeError.DuplicateKey(DecodeField.ParameterUpdate)
          )
          key <- Option
            .when(index.isValidInt && keys.keys.contains(index.toInt))(index.toInt)
            .toRight(LedgerDecodeError.UnknownKey(DecodeField.ParameterUpdate, index))
          _ <-
            if key == CostModelsField then CostModelDecode.decode(v, costModels).void
            else if key == ExtraEntropyField then Nonce.decode(v, Nonce.NeutralFields.Exact).void
            else Right(())
        yield acc.updated(key, v.original)
      }
      .map(ParamUpdate(_))

  /** [[read]] at the update keys of `era`; an era without PPUP has no update (key 6 is not a field
    * of its body).
    */
  def read(
      fields: Vector[(Node, Node)],
      era: Era,
      costModels: CostModelDecode.Format
  )(using ByteStrings): Either[LedgerDecodeError, ParamUpdate] =
    Keys
      .of(era)
      .toRight(LedgerDecodeError.UnknownFields(DecodeField.TransactionBody, Set(6)))
      .flatMap(read(fields, _, costModels))

/** The protocol parameters the bootstrap ledger tracks: those that header validation reads. */
final case class LiteParams(
    protocolVersion: (BigInt, BigInt),
    d: Rational,
    extraEntropy: Nonce,
    maxBlockBodySize: BigInt,
    maxTxSize: BigInt,
    maxBlockHeaderSize: BigInt
)

/** A pool the bootstrap ledger has seen: every VRF key hash it registered and, once retired, the
  * epoch of retirement (entries linger three epochs so the stake-snapshot lag stays covered).
  */
final case class PoolEntry(vrfKeys: Set[Bytes], retireEpoch: Option[BigInt])

/** A genesis delegation scheduled for adoption at `slot`. */
final case class FutureGenDeleg(slot: BigInt, genesisKey: Bytes, pair: GenDelegPair)

/** "Ledger-lite": the part of the pre-Conway ledger that TPraos/Praos header validation depends on,
  * maintained from block bodies without a UTxO. Update proposals (PPUP), their epoch-boundary
  * adoption (NEWPP/UPEC), genesis delegation certificates and pool registrations. It does not
  * validate transactions: it trusts the body that matches a validated header.
  */
final case class LiteLedger(
    epoch: BigInt,
    params: LiteParams,
    genDelegs: Map[Bytes, GenDelegPair],
    futureGenDelegs: Vector[FutureGenDeleg],
    curProposals: Map[Bytes, ParamUpdate],
    futureProposals: Map[Bytes, ParamUpdate],
    pools: Map[Bytes, PoolEntry],
    quorum: Int
):
  def view: LedgerView =
    LedgerView(
      params.d,
      params.extraEntropy,
      genDelegs,
      StakeView.Unknown(pools.view.mapValues(_.vrfKeys).toMap)
    )

object LiteLedger:
  /** Pool entries are kept this many epochs after retirement. */
  val RetiredPoolGrace: BigInt = 3

  /** The ledger at the start of the first Shelley-based epoch `startEpoch`. */
  def fromGenesis(shelley: Json, startEpoch: BigInt = 0): Either[ConfigError, LiteLedger] =
    val c = shelley.hcursor
    val pp = c.downField("protocolParams")
    def err(what: String, e: io.circe.DecodingFailure) = ConfigError(
      ConfigProblem.JsonField("genesis", what, e)
    )
    for
      major <- pp
        .downField("protocolVersion")
        .downField("major")
        .as[BigInt]
        .leftMap(err("major", _))
      minor <- pp
        .downField("protocolVersion")
        .downField("minor")
        .as[BigInt]
        .leftMap(err("minor", _))
      d <- pp.downField("decentralisationParam").as[BigDecimal].leftMap(err("d", _))
      tag <- pp
        .downField("extraEntropy")
        .downField("tag")
        .as[Option[String]]
        .leftMap(err("entropy", _))
      entropy <- tag match
        case None | Some("NeutralNonce") => Right(Nonce.Neutral)
        case Some(_) =>
          pp.downField("extraEntropy")
            .downField("contents")
            .as[String]
            .leftMap(err("entropy", _))
            .flatMap(h =>
              Bytes.fromHex(h).leftMap(e => ConfigError(ConfigProblem.Hex(e))).map(Nonce.Hash(_))
            )
      maxBB <- pp.downField("maxBlockBodySize").as[BigInt].leftMap(err("maxBlockBodySize", _))
      maxTx <- pp.downField("maxTxSize").as[BigInt].leftMap(err("maxTxSize", _))
      maxBH <- pp.downField("maxBlockHeaderSize").as[BigInt].leftMap(err("maxBlockHeaderSize", _))
      quorum <- c.downField("updateQuorum").as[Int].leftMap(err("updateQuorum", _))
      delegs <- c
        .downField("genDelegs")
        .as[Option[Map[String, Map[String, String]]]]
        .leftMap(err("genDelegs", _))
        .flatMap(_.getOrElse(Map.empty).toVector.traverse { case (g, m) =>
          for
            gk <- Bytes.fromHex(g).leftMap(e => ConfigError(ConfigProblem.Hex(e)))
            dk <- m
              .get("delegate")
              .toRight(ConfigError(ConfigProblem.MissingField("genDeleg", "delegate")))
              .flatMap(Bytes.fromHex(_).leftMap(e => ConfigError(ConfigProblem.Hex(e))))
            vk <- m
              .get("vrf")
              .toRight(ConfigError(ConfigProblem.MissingField("genDeleg", "vrf")))
              .flatMap(Bytes.fromHex(_).leftMap(e => ConfigError(ConfigProblem.Hex(e))))
          yield gk -> GenDelegPair(dk, vk)
        })
    yield LiteLedger(
      startEpoch,
      LiteParams((major, minor), Rational.fromDecimal(d), entropy, maxBB, maxTx, maxBH),
      delegs.toMap,
      Vector.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      quorum
    )

  /** Advance to the block's slot: every epoch boundary on the way, then genesis delegation adoption
    * for the slot.
    */
  def tick(
      l: LiteLedger,
      slot: BigInt,
      geometry: EpochGeometry
  ): Either[LedgerDecodeError, LiteLedger] =
    val epoch = geometry.epochOf(slot)
    for crossed <- (l.epoch + 1 to epoch).toVector.foldLeftM(l)(boundary)
    yield adopt(crossed, slot)

  private def adopt(l: LiteLedger, slot: BigInt): LiteLedger =
    val (due, later) = l.futureGenDelegs.partition(_.slot <= slot)
    if due.isEmpty then l
    else
      l.copy(
        genDelegs = due
          .sortBy(f => (f.slot, f.genesisKey.hex))
          .foldLeft(l.genDelegs)((m, f) => m.updated(f.genesisKey, f.pair)),
        futureGenDelegs = later
      )

  /** NEWPP/UPEC at the start of `epoch`: adopt the voted update, rotate proposals, drop pools
    * retired long enough ago.
    */
  private def boundary(l: LiteLedger, epoch: BigInt): Either[LedgerDecodeError, LiteLedger] =
    for
      voted <- voted(l.curProposals, l.params, l.quorum)
      params = voted.getOrElse(l.params)
      legal <- l.futureProposals.values.toVector.forallM(legalProtocolVersion(params, _))
    yield l.copy(
      epoch = epoch,
      params = params,
      curProposals = if legal then l.futureProposals else Map.empty,
      futureProposals = Map.empty,
      pools = l.pools.filterNot { case (_, p) =>
        p.retireEpoch.exists(_ + RetiredPoolGrace <= epoch)
      }
    )

  /** `votedFuturePParams`: exactly one update proposed identically by at least `quorum` genesis
    * delegates, applied to `params` (and rejected if it breaks the size relation).
    */
  def voted(
      proposals: Map[Bytes, ParamUpdate],
      params: LiteParams,
      quorum: Int
  ): Either[LedgerDecodeError, Option[LiteParams]] =
    val winners = proposals.values.toVector
      .groupBy(identity)
      .collect {
        case (u, vs) if vs.size >= quorum => u
      }
      .toVector
    winners match
      case Vector(u) =>
        applyUpdate(params, u).map(p =>
          Option.when(p.maxTxSize + p.maxBlockHeaderSize < p.maxBlockBodySize)(p)
        )
      case _ => Right(None)

  private def parseVersion(n: Node): Either[LedgerDecodeError, (BigInt, BigInt)] =
    CborRead
      .arrayN(n, 2, DecodeField.ProtocolVersion)
      .flatMap(xs =>
        (CborRead.uint(xs(0), DecodeField.Major), CborRead.uint(xs(1), DecodeField.Minor)).tupled
      )

  private def legalProtocolVersion(
      params: LiteParams,
      u: ParamUpdate
  ): Either[LedgerDecodeError, Boolean] =
    u.fields.get(14) match
      case None => Right(true)
      case Some(raw) =>
        CborRead.decode(raw).flatMap(parseVersion).map { (major, minor) =>
          val (cm, cn) = params.protocolVersion
          (major == cm + 1 && minor == 0) || (major == cm && minor == cn + 1)
        }

  def applyUpdate(params: LiteParams, u: ParamUpdate): Either[LedgerDecodeError, LiteParams] =
    // The fields are the original bytes of an update that was read before, from a body or from a
    // stored state: they are applied in any form that read took.
    given ByteStrings = ByteStrings.Chunked
    def field[A](
        key: Int,
        p: LiteParams,
        f: (LiteParams, Node) => Either[LedgerDecodeError, LiteParams]
    ) =
      u.fields.get(key).fold(Right(p))(raw => CborRead.decode(raw).flatMap(f(p, _)))
    for
      p1 <- field(
        2,
        params,
        (p, n) =>
          CborRead.uint(n, DecodeField.MaxBlockBodySize).map(v => p.copy(maxBlockBodySize = v))
      )
      p2 <- field(
        3,
        p1,
        (p, n) => CborRead.uint(n, DecodeField.MaxTxSize).map(v => p.copy(maxTxSize = v))
      )
      p3 <- field(
        4,
        p2,
        (p, n) =>
          CborRead.uint(n, DecodeField.MaxBlockHeaderSize).map(v => p.copy(maxBlockHeaderSize = v))
      )
      p4 <- field(
        12,
        p3,
        (p, n) => CborRead.rational(n, DecodeField.D, CborRead.Bound.Unit).map(v => p.copy(d = v))
      )
      p5 <- field(
        13,
        p4,
        (p, n) => Nonce.decode(n, Nonce.NeutralFields.Stored).map(v => p.copy(extraEntropy = v))
      )
      p6 <- field(14, p5, (p, n) => parseVersion(n).map(v => p.copy(protocolVersion = v)))
    yield p6

  /** What a transaction does to the bootstrap ledger. */
  enum Effect:
    case Proposal(updates: Map[Bytes, ParamUpdate], epoch: BigInt)
    case PoolRegistered(pool: Bytes, vrf: Bytes)
    case PoolRetiring(pool: Bytes, epoch: BigInt)
    case GenesisDelegation(genesisKey: Bytes, pair: GenDelegPair)

  private val bodyLimits = Cbor.Limits(2500000, 64, 1000000, 2500000)

  /** The effects of a transaction body. Update proposals (key 6) exist up to Babbage only. */
  def effects(body: Bytes, era: Era): Either[LedgerDecodeError, Vector[Effect]] =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(era))
    for
      root <- CborRead.decode(body, bodyLimits)
      fields <- CborRead.fields(root, DecodeField.TransactionBody)
      proposal <- fields
        .get(6)
        .filter(_ => era.ordinal <= Era.Babbage.ordinal)
        .traverse(proposal(_, era))
      certs <- fields.get(4).traverse(n => certificates(n).flatMap(_.flatTraverse(cert)))
    yield proposal.toVector ++ certs.getOrElse(Vector.empty)

  private def certificates(n: Node): Either[LedgerDecodeError, Vector[Node]] = n.value match
    case Value.Tag(t, inner) if t == 258 => CborRead.array(inner, DecodeField.Certificates)
    case _                               => CborRead.array(n, DecodeField.Certificates)

  private def proposal(n: Node, era: Era)(using ByteStrings): Either[LedgerDecodeError, Effect] =
    for
      xs <- CborRead.arrayN(n, 2, DecodeField.Update)
      kvs <- CborRead.map(xs(0), DecodeField.ProposedUpdates)
      updates <- kvs.traverse { case (k, v) =>
        for
          key <- CborRead.bytesN(k, 28, DecodeField.GenesisKeyHash)
          fs <- CborRead.map(v, DecodeField.ParameterUpdate)
          update <- ParamUpdate.read(fs, era, CostModelDecode.Format.Legacy)
        yield key -> update
      }
      epoch <- CborRead.uint(xs(1), DecodeField.UpdateEpoch)
    yield Effect.Proposal(updates.toMap, epoch)

  private def cert(n: Node)(using ByteStrings): Either[LedgerDecodeError, Vector[Effect]] =
    CborRead.array(n, DecodeField.Certificate).flatMap { xs =>
      xs.headOption
        .toRight(LedgerDecodeError.Empty(DecodeField.Certificate))
        .flatMap(CborRead.uint(_, DecodeField.CertificateTag))
        .flatMap {
          case t if t == 3 && xs.size >= 3 =>
            (
              CborRead.bytesN(xs(1), 28, DecodeField.PoolOperator),
              CborRead.bytesN(xs(2), 32, DecodeField.PoolVrf)
            )
              .mapN((p, v) => Vector(Effect.PoolRegistered(p, v)))
          case t if t == 4 && xs.size == 3 =>
            (
              CborRead.bytesN(xs(1), 28, DecodeField.Pool),
              CborRead.uint(xs(2), DecodeField.RetirementEpoch)
            )
              .mapN((p, e) => Vector(Effect.PoolRetiring(p, e)))
          case t if t == 5 && xs.size == 4 =>
            (
              CborRead.bytesN(xs(1), 28, DecodeField.GenesisKey),
              CborRead.bytesN(xs(2), 28, DecodeField.GenesisDelegate),
              CborRead.bytesN(xs(3), 32, DecodeField.GenesisVrf)
            ).mapN((g, d, v) => Vector(Effect.GenesisDelegation(g, GenDelegPair(d, v))))
          case _ => Right(Vector.empty)
        }
    }

  /** Apply the valid transactions of a block at `slot` (the ledger already ticked to it). */
  def applyBlock(
      l: LiteLedger,
      block: Block.Shelley,
      geometry: EpochGeometry
  ): Either[LedgerDecodeError, LiteLedger] =
    val slot = block.header.slot
    block.transactions.filter(_.isValid).foldLeftM(l) { (s, tx) =>
      effects(tx.body, block.era)
        .leftMap(LedgerDecodeError.Transaction(tx.id, _))
        .flatMap(_.foldLeftM(s)(apply(_, _, slot, geometry)))
    }

  private def apply(
      l: LiteLedger,
      e: Effect,
      slot: BigInt,
      geometry: EpochGeometry
  ): Either[LedgerDecodeError, LiteLedger] = e match
    case Effect.Proposal(updates, target) =>
      val tooLate = geometry.firstSlot(l.epoch + 1) - 2 * geometry.stabilityWindow.toBigInt
      if slot < tooLate then
        Either.cond(
          target == l.epoch,
          l.copy(curProposals = l.curProposals ++ updates),
          LedgerDecodeError.ProposalEpochMismatch(target, l.epoch, true)
        )
      else
        Either.cond(
          target == l.epoch + 1,
          l.copy(futureProposals = l.futureProposals ++ updates),
          LedgerDecodeError.ProposalEpochMismatch(target, l.epoch, false)
        )
    case Effect.PoolRegistered(pool, vrf) =>
      Right(l.copy(pools = l.pools.updatedWith(pool) {
        case Some(p) => Some(PoolEntry(p.vrfKeys + vrf, None))
        case None    => Some(PoolEntry(Set(vrf), None))
      }))
    case Effect.PoolRetiring(pool, epoch) =>
      Right(l.copy(pools = l.pools.updatedWith(pool)(_.map(_.copy(retireEpoch = Some(epoch))))))
    case Effect.GenesisDelegation(gk, pair) =>
      Right(
        l.copy(futureGenDelegs =
          l.futureGenDelegs :+ FutureGenDeleg(slot + geometry.stabilityWindow.toBigInt, gk, pair)
        )
      )
