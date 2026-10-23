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

  /** The update of a transaction or proposal from its map of field index to value. The cost models
    * of field 18 are checked by the rule of the decoder version of the era ([[CostModelDecode]]);
    * the other fields are kept as they are and read when the update is applied.
    */
  def read(
      fields: Vector[(Node, Node)],
      costModels: CostModelDecode.Format
  ): Either[LedgerDecodeError, ParamUpdate] =
    fields
      .traverse { case (k, v) =>
        for
          index <- CborRead.uint(k, "parameter index").map(_.toInt)
          _ <-
            if index == CostModelsField then CostModelDecode.decode(v, costModels).void
            else Right(())
        yield index -> v.original
      }
      .map(kvs => ParamUpdate(kvs.toMap))

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
      .arrayN(n, 2, "protocol version")
      .flatMap(xs => (CborRead.uint(xs(0), "major"), CborRead.uint(xs(1), "minor")).tupled)

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
        (p, n) => CborRead.uint(n, "maxBlockBodySize").map(v => p.copy(maxBlockBodySize = v))
      )
      p2 <- field(3, p1, (p, n) => CborRead.uint(n, "maxTxSize").map(v => p.copy(maxTxSize = v)))
      p3 <- field(
        4,
        p2,
        (p, n) => CborRead.uint(n, "maxBlockHeaderSize").map(v => p.copy(maxBlockHeaderSize = v))
      )
      p4 <- field(12, p3, (p, n) => CborRead.rational(n, "d").map(v => p.copy(d = v)))
      p5 <- field(13, p4, (p, n) => nonce(n).map(v => p.copy(extraEntropy = v)))
      p6 <- field(14, p5, (p, n) => parseVersion(n).map(v => p.copy(protocolVersion = v)))
    yield p6

  private def nonce(n: Node): Either[LedgerDecodeError, Nonce] =
    CborRead.array(n, "nonce").flatMap { xs =>
      xs.headOption
        .toRight(LedgerDecodeError.Empty("nonce"))
        .flatMap(CborRead.uint(_, "nonce tag"))
        .flatMap {
          case t if t == 0                 => Right(Nonce.Neutral)
          case t if t == 1 && xs.size == 2 => CborRead.bytesN(xs(1), 32, "nonce").map(Nonce.Hash(_))
          case t => Left(LedgerDecodeError.TaggedArity("nonce", t, xs.size))
        }
    }

  /** What a transaction does to the bootstrap ledger. */
  enum Effect:
    case Proposal(updates: Map[Bytes, ParamUpdate], epoch: BigInt)
    case PoolRegistered(pool: Bytes, vrf: Bytes)
    case PoolRetiring(pool: Bytes, epoch: BigInt)
    case GenesisDelegation(genesisKey: Bytes, pair: GenDelegPair)

  private val bodyLimits = Cbor.Limits(2500000, 64, 1000000, 2500000)

  /** The effects of a transaction body. Update proposals (key 6) exist up to Babbage only. */
  def effects(body: Bytes, era: Era): Either[LedgerDecodeError, Vector[Effect]] =
    for
      root <- CborRead.decode(body, bodyLimits)
      fields <- CborRead.fields(root, "transaction body")
      proposal <- fields.get(6).filter(_ => era.ordinal <= Era.Babbage.ordinal).traverse(proposal)
      certs <- fields.get(4).traverse(n => certificates(n).flatMap(_.flatTraverse(cert)))
    yield proposal.toVector ++ certs.getOrElse(Vector.empty)

  private def certificates(n: Node): Either[LedgerDecodeError, Vector[Node]] = n.value match
    case Value.Tag(t, inner) if t == 258 => CborRead.array(inner, "certificates")
    case _                               => CborRead.array(n, "certificates")

  private def proposal(n: Node): Either[LedgerDecodeError, Effect] =
    for
      xs <- CborRead.arrayN(n, 2, "update")
      kvs <- CborRead.map(xs(0), "proposed updates")
      updates <- kvs.traverse { case (k, v) =>
        for
          key <- CborRead.bytesN(k, 28, "genesis key hash")
          fs <- CborRead.map(v, "parameter update")
          update <- ParamUpdate.read(fs, CostModelDecode.Format.Legacy)
        yield key -> update
      }
      epoch <- CborRead.uint(xs(1), "update epoch")
    yield Effect.Proposal(updates.toMap, epoch)

  private def cert(n: Node): Either[LedgerDecodeError, Vector[Effect]] =
    CborRead.array(n, "certificate").flatMap { xs =>
      xs.headOption
        .toRight(LedgerDecodeError.Empty("certificate"))
        .flatMap(CborRead.uint(_, "certificate tag"))
        .flatMap {
          case t if t == 3 && xs.size >= 3 =>
            (CborRead.bytesN(xs(1), 28, "pool operator"), CborRead.bytesN(xs(2), 32, "pool vrf"))
              .mapN((p, v) => Vector(Effect.PoolRegistered(p, v)))
          case t if t == 4 && xs.size == 3 =>
            (CborRead.bytesN(xs(1), 28, "pool"), CborRead.uint(xs(2), "retirement epoch"))
              .mapN((p, e) => Vector(Effect.PoolRetiring(p, e)))
          case t if t == 5 && xs.size == 4 =>
            (
              CborRead.bytesN(xs(1), 28, "genesis key"),
              CborRead.bytesN(xs(2), 28, "genesis delegate"),
              CborRead.bytesN(xs(3), 32, "genesis vrf")
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
      val tooLate = geometry.firstSlot(l.epoch + 1) - 2 * geometry.stabilityWindow
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
          l.futureGenDelegs :+ FutureGenDeleg(slot + geometry.stabilityWindow, gk, pair)
        )
      )
