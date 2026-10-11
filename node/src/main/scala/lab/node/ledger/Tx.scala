// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node}
import lab.network.Era
import lab.node.Block

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
  def decode(n: Node): Either[String, DRep] =
    CborRead.array(n, "drep").flatMap {
      case Vector(t, h) =>
        (CborRead.uint(t, "drep").map(_.toInt), CborRead.bytesN(h, 28, "drep hash")).tupled
          .flatMap {
            case (0, hash) => Right(Registered(Credential.Key(hash)))
            case (1, hash) => Right(Registered(Credential.Script(hash)))
            case (tag, _)  => Left(s"drep tag $tag")
          }
      case Vector(t) =>
        CborRead.uint(t, "drep").map(_.toInt).flatMap {
          case 2   => Right(AlwaysAbstain)
          case 3   => Right(AlwaysNoConfidence)
          case tag => Left(s"drep tag $tag")
        }
      case _ => Left("drep: malformed")
    }

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

  /** Pre-Conway move of instantaneous rewards (tag 6): either a coin amount moved to the other
    * pot, or per-credential amounts (deltas from Alonzo) paid from `pot`.
    */
  case MoveRewards(pot: Pot, transfer: Either[BigInt, Map[Credential, BigInt]])
  case RegisterDRep(credential: Credential, deposit: BigInt)
  case UnregisterDRep(credential: Credential, refund: BigInt)
  case UpdateDRep(credential: Credential)
  case CommitteeAuthorize(cold: Credential, hot: Credential)
  case CommitteeResign(cold: Credential)

enum Pot:
  case Reserves
  case Treasury

object Certificate:
  def decode(n: Node): Either[String, Certificate] =
    def cred(i: Int, xs: Vector[Node]) = Credential.decode(xs(i), "certificate credential")
    def pool(i: Int, xs: Vector[Node]) = CborRead.bytesN(xs(i), 28, "pool id")
    def coin(i: Int, xs: Vector[Node]) = CborRead.uint(xs(i), "certificate coin")
    def drep(i: Int, xs: Vector[Node]) = DRep.decode(xs(i))
    CborRead.array(n, "certificate").flatMap { xs =>
      for
        tag <- xs.headOption
          .toRight("empty certificate")
          .flatMap(CborRead.uint(_, "certificate tag"))
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
        _ <- arity
          .get(tag.toInt)
          .filter(_ == xs.size)
          .toRight(s"certificate $tag with ${xs.size} fields")
        cert <- tag.toInt match
          case 0 => cred(1, xs).map(Register(_, None))
          case 1 => cred(1, xs).map(Unregister(_, None))
          case 2 => (cred(1, xs), pool(2, xs)).mapN((c, p) => Delegate(c, Some(p), None, None))
          case 3 => poolParams(xs.drop(1)).map(RegisterPool(_))
          case 4 => (pool(1, xs), CborRead.uint(xs(2), "retirement epoch")).mapN(RetirePool(_, _))
          case 5 =>
            (
              CborRead.bytesN(xs(1), 28, "genesis key"),
              CborRead.bytesN(xs(2), 28, "genesis delegate"),
              CborRead.bytesN(xs(3), 32, "genesis vrf")
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
          case 15 => cred(1, xs).map(CommitteeResign(_))
          case 16 => (cred(1, xs), coin(2, xs)).mapN(RegisterDRep(_, _))
          case 17 => (cred(1, xs), coin(2, xs)).mapN(UnregisterDRep(_, _))
          case 18 => cred(1, xs).map(UpdateDRep(_))
          case t  => Left(s"certificate tag $t")
      yield cert
    }

  private def moveRewards(n: Node): Either[String, Certificate] =
    CborRead.arrayN(n, 2, "move instantaneous rewards").flatMap { xs =>
      for
        pot <- CborRead.uint(xs(0), "MIR pot").flatMap {
          case p if p == 0 => Right(Pot.Reserves)
          case p if p == 1 => Right(Pot.Treasury)
          case p           => Left(s"MIR pot $p")
        }
        transfer <- xs(1).value match
          case lab.cbor.Value.Map(_) =>
            CborRead
              .map(xs(1), "MIR map")
              .flatMap(_.traverse { case (k, v) =>
                (Credential.decode(k, "MIR credential"), CborRead.int(v, "MIR amount")).tupled
              })
              .map(kvs => Right(kvs.toMap))
          case _ => CborRead.uint(xs(1), "MIR transfer").map(Left(_))
      yield MoveRewards(pot, transfer)
    }

  private def poolParams(xs: Vector[Node]): Either[String, PoolParams] =
    for
      operator <- CborRead.bytesN(xs(0), 28, "pool operator")
      vrf <- CborRead.bytesN(xs(1), 32, "pool vrf")
      pledge <- CborRead.uint(xs(2), "pool pledge")
      cost <- CborRead.uint(xs(3), "pool cost")
      margin <- CborRead.rational(xs(4), "pool margin")
      account <- CborRead.bytes(xs(5), "pool reward account").flatMap(RewardAccount.decode)
      owners <- CborRead
        .set(xs(6), "pool owners")
        .flatMap(_.traverse(CborRead.bytesN(_, 28, "pool owner")))
      metadata = xs(8).value match
        case lab.cbor.Value.Null => None
        case _                   => Some(xs(8).original)
    yield PoolParams(
      operator,
      vrf,
      pledge,
      cost,
      margin,
      account,
      owners.toSet,
      xs(7).original,
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
    withdrawals: Vector[(RewardAccount, BigInt)],
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
    totalCollateral: Option[BigInt] = None
):
  lazy val id: Bytes = Blake2b.hash256.hash(raw)
  def hasScriptData: Boolean = scriptDataHash.isDefined

object TxBody:
  private val Known = Set(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22)

  private def update(n: Node): Either[String, Update] =
    for
      xs <- CborRead.arrayN(n, 2, "update")
      kvs <- CborRead.map(xs(0), "proposed updates")
      proposals <- kvs.traverse { case (k, v) =>
        for
          key <- CborRead.bytesN(k, 28, "genesis key hash")
          fs <- CborRead
            .map(v, "parameter update")
            .flatMap(_.traverse { case (fk, fv) =>
              CborRead.uint(fk, "parameter index").map(_.toInt -> fv.original)
            })
        yield key -> ParamUpdate(fs.toMap)
      }
      epoch <- CborRead.uint(xs(1), "update epoch")
    yield Update(proposals.toMap, epoch)

  def decode(raw: Bytes): Either[String, TxBody] =
    for
      root <- Cbor.decode(raw)
      f <- CborRead.fields(root, "transaction body")
      _ <- Either.cond(
        f.keySet.subsetOf(Known),
        (),
        s"transaction body: unknown fields ${f.keySet -- Known}"
      )
      inputs <- f
        .get(0)
        .toRight("missing inputs")
        .flatMap(CborRead.set(_, "inputs"))
        .flatMap(_.traverse(TxIn.decode))
      outputs <- f
        .get(1)
        .toRight("missing outputs")
        .flatMap(CborRead.array(_, "outputs"))
        .flatMap(_.traverse(TxOut.decode))
      fee <- f.get(2).toRight("missing fee").flatMap(CborRead.uint(_, "fee"))
      ttl <- f.get(3).traverse(CborRead.uint(_, "ttl"))
      start <- f.get(8).traverse(CborRead.uint(_, "validity start"))
      certs <- f
        .get(4)
        .traverse(CborRead.list(_, "certificates").flatMap(_.traverse(Certificate.decode)))
      withdrawals <- f
        .get(5)
        .traverse(
          CborRead
            .map(_, "withdrawals")
            .flatMap(_.traverse { case (k, v) =>
              (
                CborRead.bytes(k, "withdrawal account").flatMap(RewardAccount.decode),
                CborRead.uint(v, "withdrawal")
              ).tupled
            })
        )
      upd <- f.get(6).traverse(update)
      auxHash <- f.get(7).traverse(CborRead.bytesN(_, 32, "auxiliary data hash"))
      mint <- f.get(9).traverse(Value.multiasset(_, "mint", signed = true))
      dataHash <- f.get(11).traverse(CborRead.bytesN(_, 32, "script data hash"))
      collateral <- f
        .get(13)
        .traverse(CborRead.set(_, "collateral").flatMap(_.traverse(TxIn.decode)))
      signers <- f
        .get(14)
        .traverse(
          CborRead.set(_, "required signers").flatMap(_.traverse(CborRead.bytesN(_, 28, "signer")))
        )
      network <- f.get(15).traverse(CborRead.uint(_, "network id"))
      collateralReturn <- f.get(16).traverse(TxOut.decode)
      totalCollateral <- f.get(17).traverse(CborRead.uint(_, "total collateral"))
      refs <- f
        .get(18)
        .traverse(CborRead.set(_, "reference inputs").flatMap(_.traverse(TxIn.decode)))
      donation <- f.get(22).traverse(CborRead.uint(_, "donation"))
    yield TxBody(
      raw,
      inputs,
      outputs,
      fee,
      ttl,
      start,
      certs.getOrElse(Vector.empty),
      withdrawals.getOrElse(Vector.empty),
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
      totalCollateral = totalCollateral
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

/** Witness set. Plutus scripts are only hashed: phase-2 evaluation is not performed. */
final case class Witnesses(
    vkeys: Vector[(Bytes, Bytes)],
    bootstrap: Vector[BootstrapWitness] = Vector.empty,
    scripts: Vector[Script] = Vector.empty,
    datums: Vector[Bytes] = Vector.empty,
    redeemers: Vector[Redeemer] = Vector.empty
):
  lazy val scriptsByHash: Map[Bytes, Script] = scripts.map(s => s.hash -> s).toMap
  lazy val datumHashes: Set[Bytes] = datums.map(Blake2b.hash256.hash).toSet
  def hasPlutus: Boolean = scripts.exists(_.kind.isPlutus)
  def totalExUnits: ExUnits = redeemers.foldLeft(ExUnits.zero)(_ + _.exUnits)

object Witnesses:
  val empty: Witnesses = Witnesses(Vector.empty)

  def decode(raw: Bytes): Either[String, Witnesses] =
    for
      root <- Cbor.decode(raw)
      f <- CborRead.fields(root, "witness set")
      _ <- Either.cond(f.keySet.subsetOf(Set(0, 1, 2, 3, 4, 5, 6, 7)), (), "witness set: unknown")
      vkeys <- f
        .get(0)
        .traverse(
          CborRead
            .set(_, "vkey witnesses")
            .flatMap(_.traverse { w =>
              CborRead
                .arrayN(w, 2, "vkey witness")
                .flatMap(xs =>
                  (
                    CborRead.bytesN(xs(0), 32, "vkey"),
                    CborRead.bytesN(xs(1), 64, "signature")
                  ).tupled
                )
            })
        )
      native <- f
        .get(1)
        .traverse(CborRead.set(_, "native scripts").flatMap(_.traverse(Script.native)))
      bootstrap <- f
        .get(2)
        .traverse(
          CborRead
            .set(_, "bootstrap witnesses")
            .flatMap(_.traverse { w =>
              CborRead.arrayN(w, 4, "bootstrap witness").flatMap { xs =>
                (
                  CborRead.bytesN(xs(0), 32, "bootstrap vkey"),
                  CborRead.bytesN(xs(1), 64, "bootstrap signature"),
                  CborRead.bytesN(xs(2), 32, "chain code"),
                  CborRead.bytes(xs(3), "attributes")
                ).mapN(BootstrapWitness(_, _, _, _))
              }
            })
        )
      plutus = (tag: Int, kind: ScriptKind) =>
        f.get(tag)
          .traverse(
            CborRead
              .set(_, "plutus scripts")
              .flatMap(_.traverse(CborRead.bytes(_, "plutus script").map(Script.plutus(kind, _))))
          )
      v1 <- plutus(3, ScriptKind.PlutusV1)
      v2 <- plutus(6, ScriptKind.PlutusV2)
      v3 <- plutus(7, ScriptKind.PlutusV3)
      datums <- f.get(4).traverse(CborRead.set(_, "datums").map(_.map(_.original)))
      redeemers <- f.get(5).traverse(redeemerList)
    yield Witnesses(
      vkeys.getOrElse(Vector.empty),
      bootstrap.getOrElse(Vector.empty),
      native.getOrElse(Vector.empty) ++ v1.getOrElse(Vector.empty) ++ v2.getOrElse(Vector.empty) ++
        v3.getOrElse(Vector.empty),
      datums.getOrElse(Vector.empty),
      redeemers.getOrElse(Vector.empty)
    )

  private def exUnits(n: Node): Either[String, ExUnits] =
    CborRead
      .arrayN(n, 2, "ex units")
      .flatMap(xs => (CborRead.uint(xs(0), "mem"), CborRead.uint(xs(1), "steps")).mapN(ExUnits(_, _)))

  /** `[* [tag, index, data, ex_units]]`, or (Conway) `{[tag, index] => [data, ex_units]}`. */
  private def redeemerList(n: Node): Either[String, Vector[Redeemer]] = n.value match
    case lab.cbor.Value.Map(kvs) =>
      kvs.traverse { case (k, v) =>
        for
          key <- CborRead.arrayN(k, 2, "redeemer key")
          tag <- CborRead.uint(key(0), "redeemer tag")
          index <- CborRead.uint(key(1), "redeemer index")
          value <- CborRead.arrayN(v, 2, "redeemer value")
          units <- exUnits(value(1))
        yield Redeemer(tag.toInt, index, value(0).original, units)
      }
    case _ =>
      CborRead.array(n, "redeemers").flatMap(_.traverse { r =>
        for
          xs <- CborRead.arrayN(r, 4, "redeemer")
          tag <- CborRead.uint(xs(0), "redeemer tag")
          index <- CborRead.uint(xs(1), "redeemer index")
          units <- exUnits(xs(3))
        yield Redeemer(tag.toInt, index, xs(2).original, units)
      })

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
  /** Ledger size is the size of the serialized transaction: `[body, witnesses, isValid, aux]` from
    * Alonzo, `[body, witnesses, aux]` before.
    */
  def fromBlock(t: Block.Transaction, era: Era): Either[String, Tx] =
    for
      body <- TxBody.decode(t.body)
      witnesses <- Witnesses.decode(t.witnesses)
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
