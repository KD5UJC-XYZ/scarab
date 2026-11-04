// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Rational}
import xyz.kd5ujc.scarab.crypto.bls.{ProofOfPossession, VerificationKey}
import xyz.kd5ujc.scarab.ledger.{
  Credential,
  DRep,
  DecodeField,
  DecodeSite,
  LedgerDecodeError,
  RewardAccount
}

import V12.{Result, given}

/** An anchor: a URL of at most 128 bytes and the hash of what it points to. */
final case class DijkstraAnchor(url: String, hash: Bytes)

object DijkstraAnchor:
  /** `Anchor` from version 12
    * (`libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:1003-1011`, cardano-ledger
    * 9df9f98c): `decodeRecordNamed` of a `Url` (`textDecCBOR 128`, `:679-686`) and a 32-byte hash.
    */
  def decode(n: Node): Result[DijkstraAnchor] =
    V12
      .record(n, 2, DecodeField.Anchor)
      .flatMap(xs =>
        (
          V12.textMax(xs(0), DecodeField.AnchorUrl, 128),
          V12.bytesN(xs(1), 32, DecodeField.AnchorHash)
        )
          .mapN(DijkstraAnchor(_, _))
      )

  def optional(n: Node): Result[Option[DijkstraAnchor]] = V12.nullable(n)(decode)

/** `AccountAddress` (`fromCborAccountAddress`, `Address.hs:913-916`): a byte string, chunked or
  * not, read as an account address in full.
  */
private[dijkstra] object Account:
  def decode(n: Node, what: DecodeSite): Result[RewardAccount] =
    V12.bytes(n, what).flatMap(RewardAccount.decode)

/** A stake pool relay. The addresses are kept as their bytes. */
enum DijkstraRelay:
  case SingleHostAddr(port: Option[Int], ipv4: Option[Bytes], ipv6: Option[Bytes])
  case SingleHostName(port: Option[Int], dnsName: String)
  case MultiHostName(dnsName: String)

object DijkstraRelay:
  /** `StakePoolRelay` (`libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:458-472`):
    * `decodeRecordSum` of `[0, port / null, ipv4 / null, ipv6 / null]`, `[1, port / null, dns]` and
    * `[2, dns]`. A port is a `Word16`; an IPv4 address a byte string of exactly 4 bytes and an IPv6
    * one of exactly 16 (`binaryGetDecoder`, which fails on fewer and on bytes left over,
    * `DecCBOR.hs:228-254`); a DNS name a text of at most 128 bytes (`BaseTypes.hs:692-698`).
    */
  def decode(n: Node): Result[DijkstraRelay] =
    def port(x: Node) = V12.nullable(x)(V12.word16(_, DecodeField.PoolRelay))
    def ip(x: Node, size: Int) = V12.nullable(x)(V12.bytesN(_, size, DecodeField.PoolRelay))
    def dns(x: Node) = V12.textMax(x, DecodeField.PoolRelay, 128)
    V12.sum(n, DecodeField.PoolRelay) {
      case t if t == 0 =>
        (4, xs => (port(xs(1)), ip(xs(2), 4), ip(xs(3), 16)).mapN(SingleHostAddr(_, _, _)))
      case t if t == 1 => (3, xs => (port(xs(1)), dns(xs(2))).mapN(SingleHostName(_, _)))
      case t if t == 2 => (2, xs => dns(xs(1)).map(MultiHostName(_)))
    }

/** A pool's voting key: a BLS12-381 MinSig verification key and its proof of possession, as their
  * compressed encodings (the order of `Ord BlsKey`, `StakePool.hs:496-503`).
  */
final case class DijkstraBlsKey(key: Bytes, proofOfPossession: Bytes)

object DijkstraBlsKey:
  /** `BlsKey` (`StakePool.hs:538-542`): `decodeRecordNamed` of two `decodeFixedSized`, a byte
    * string (chunked or not) read by `rawDecodeFixedSized`: the key a compressed G2 point of the
    * subgroup other than the identity, the proof a compressed G1 point of the subgroup other than
    * the identity (cardano-base c1e1ed8d, `cardano-crypto-class/src/Cardano/Crypto/DSIGN/BLS12381/
    * Internal.hs:441-458, 605-623`).
    */
  def decode(n: Node): Result[DijkstraBlsKey] =
    V12
      .record(n, 2, DecodeField.BlsKey)
      .flatMap(xs =>
        for
          key <- V12.bytes(xs(0), DecodeField.BlsKey)
          _ <- VerificationKey.fromBytes(key).leftMap(LedgerDecodeError.LeiosCertificate(_))
          proof <- V12.bytes(xs(1), DecodeField.BlsKey)
          _ <- ProofOfPossession.fromBytes(proof).leftMap(LedgerDecodeError.LeiosCertificate(_))
        yield DijkstraBlsKey(key, proof)
      )

/** The metadata of a pool registration: its URL and hash. */
final case class DijkstraPoolMetadata(url: String, hash: Bytes)

/** The parameters of a pool registration, decoded, so that two registrations are one exactly when
  * the reference's derived `Ord StakePoolParams` finds them equal: the owners a set, the margin a
  * reduced rational.
  */
final case class DijkstraPoolParams(
    operator: Bytes,
    vrf: Bytes,
    blsKey: Option[DijkstraBlsKey],
    pledge: BigInt,
    cost: BigInt,
    margin: Rational,
    account: RewardAccount,
    owners: Set[Bytes],
    relays: Vector[DijkstraRelay],
    metadata: Option[DijkstraPoolMetadata]
)

object DijkstraPoolParams:
  /** `decodeStakePoolParamsFlat` (`StakePool.hs:744-770`) over the items after a certificate's tag:
    * the operator, the VRF key hash, from version 12 a voting key slot that is read only when it is
    * a list or null (a list is the key, null is no key, and either counts as an item; any other
    * item leaves the slot out), the pledge and cost (`Coin`), the margin (`UnitInterval`), the
    * reward account, the owners (`Set`), the relays (`decodeStrictSeq`) and the metadata or null.
    * The result is the number of items read and the parameters.
    */
  def flat(xs: Vector[Node]): Result[(Int, DijkstraPoolParams)] =
    def at(i: Int): Result[Node] =
      xs.lift(i).toRight(LedgerDecodeError.ArrayLength(DecodeField.Certificate, 10, xs.size))
    val slot =
      xs.lift(2).filter(x => V12.isList(x) || x.value == xyz.kd5ujc.scarab.codec.Value.Null)
    val extra = if slot.isDefined then 1 else 0
    for
      operator <- at(0).flatMap(V12.bytesN(_, 28, DecodeField.PoolOperator))
      vrf <- at(1).flatMap(V12.bytesN(_, 32, DecodeField.PoolVrf))
      bls <- slot.flatTraverse(V12.nullable(_)(DijkstraBlsKey.decode))
      pledge <- at(2 + extra).flatMap(V12.word64(_, DecodeField.PoolPledge))
      cost <- at(3 + extra).flatMap(V12.word64(_, DecodeField.PoolCost))
      margin <- at(4 + extra).flatMap(V12.ratio(_, DecodeField.PoolMargin, V12.Interval.Unit))
      account <- at(5 + extra).flatMap(Account.decode(_, DecodeField.PoolRewardAccount))
      owners <- at(6 + extra).flatMap(
        V12.set(_, DecodeField.PoolOwners)(V12.bytesN(_, 28, DecodeField.PoolOwner))(identity)
      )
      relays <- at(7 + extra).flatMap(
        V12.list(_, DecodeField.PoolRelay).flatMap(_.traverse(DijkstraRelay.decode))
      )
      metadata <- at(8 + extra).flatMap(V12.nullable(_)(metadata))
    yield (
      9 + extra,
      DijkstraPoolParams(
        operator,
        vrf,
        bls,
        pledge,
        cost,
        margin,
        account,
        owners.toSet,
        relays,
        metadata
      )
    )

  /** `PoolMetadata` (`StakePool.hs:683-684`): `decodeRecordNamed` of a `Url` and a byte string. */
  private def metadata(n: Node): Result[DijkstraPoolMetadata] =
    V12
      .record(n, 2, DecodeField.PoolMetadata)
      .flatMap(xs =>
        (
          V12.textMax(xs(0), DecodeField.PoolMetadataURL, 128),
          V12.bytes(xs(1), DecodeField.PoolMetadataHash)
        ).mapN(DijkstraPoolMetadata(_, _))
      )

/** Whom a stake credential delegates to. */
enum DijkstraDelegatee:
  case Stake(pool: Bytes)
  case Vote(drep: DRep)
  case StakeVote(pool: Bytes, drep: DRep)

/** A certificate of the Dijkstra era, decoded, so that two certificates are one exactly when the
  * reference's derived `Ord (DijkstraTxCert era)` finds them equal (`OSet`).
  */
enum DijkstraCert:
  case Register(credential: Credential, deposit: BigInt)
  case Unregister(credential: Credential, refund: BigInt)
  case Delegate(credential: Credential, delegatee: DijkstraDelegatee)
  case RegisterDelegate(credential: Credential, delegatee: DijkstraDelegatee, deposit: BigInt)
  case RegisterPool(params: DijkstraPoolParams)
  case RetirePool(pool: Bytes, epoch: BigInt)
  case AuthorizeCommitteeHotKey(cold: Credential, hot: Credential)
  case ResignCommitteeColdKey(cold: Credential, anchor: Option[DijkstraAnchor])
  case RegisterDRep(credential: Credential, deposit: BigInt, anchor: Option[DijkstraAnchor])
  case UnregisterDRep(credential: Credential, refund: BigInt)
  case UpdateDRep(credential: Credential, anchor: Option[DijkstraAnchor])

object DijkstraCert:
  /** `DecCBOR (DijkstraTxCert era)` (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxCert.hs:
    * 233-250`, 9df9f98c): `decodeRecordSum` in which tags 0 and 1 (certificates without deposits),
    * 5 (genesis delegation) and 6 (MIR) are refused; 2 is a stake delegation `[2, credential,
    * pool]`; 3 and 4 are the pool certificates of `poolTxCertDecoder` (`Shelley/TxCert.hs:557-567`:
    * a registration of `1 +` the parameters' items, a retirement `[4, pool, epoch]`); 7 to 18 are
    * `conwayTxCertDelegDecoder` (`Conway/TxCert.hs:729-776`), any other tag `invalidKey`.
    */
  def decode(n: Node): Result[DijkstraCert] =
    def cred(x: Node) = Credential.decode(x, DecodeField.CertificateCredential)
    def pool(x: Node) = V12.bytesN(x, 28, DecodeField.PoolId)
    def coin(x: Node) = V12.word64(x, DecodeField.CertificateCoin)
    def drep(x: Node) = DRep.decode(x)
    def anchor(x: Node) = DijkstraAnchor.optional(x)
    import DijkstraDelegatee.*
    V12.list(n, DecodeField.Certificate).flatMap { xs =>
      val registration = xs
        .lift(0)
        .flatMap(_.value match
          case xyz.kd5ujc.scarab.codec.Value.UInt(t) if t == 3 => Some(())
          case _                                               => None)
      registration match
        case Some(_) =>
          // the length of a pool registration depends on its voting key slot
          DijkstraPoolParams
            .flat(xs.drop(1))
            .flatMap((size, params) =>
              Either.cond(
                xs.size == 1 + size,
                RegisterPool(params),
                LedgerDecodeError.TaggedArity(DecodeField.Certificate, 3, xs.size)
              )
            )
        case None =>
          V12.sum(n, DecodeField.Certificate) {
            case t if t == 2 =>
              (3, xs => (cred(xs(1)), pool(xs(2))).mapN((c, p) => Delegate(c, Stake(p))))
            case t if t == 4 =>
              (
                3,
                xs =>
                  (pool(xs(1)), V12.word64(xs(2), DecodeField.RetirementEpoch))
                    .mapN(RetirePool(_, _))
              )
            case t if t == 7 => (3, xs => (cred(xs(1)), coin(xs(2))).mapN(Register(_, _)))
            case t if t == 8 => (3, xs => (cred(xs(1)), coin(xs(2))).mapN(Unregister(_, _)))
            case t if t == 9 =>
              (3, xs => (cred(xs(1)), drep(xs(2))).mapN((c, d) => Delegate(c, Vote(d))))
            case t if t == 10 =>
              (
                4,
                xs =>
                  (cred(xs(1)), pool(xs(2)), drep(xs(3)))
                    .mapN((c, p, d) => Delegate(c, StakeVote(p, d)))
              )
            case t if t == 11 =>
              (
                4,
                xs =>
                  (cred(xs(1)), pool(xs(2)), coin(xs(3)))
                    .mapN((c, p, d) => RegisterDelegate(c, Stake(p), d))
              )
            case t if t == 12 =>
              (
                4,
                xs =>
                  (cred(xs(1)), drep(xs(2)), coin(xs(3)))
                    .mapN((c, v, d) => RegisterDelegate(c, Vote(v), d))
              )
            case t if t == 13 =>
              (
                5,
                xs =>
                  (cred(xs(1)), pool(xs(2)), drep(xs(3)), coin(xs(4)))
                    .mapN((c, p, v, d) => RegisterDelegate(c, StakeVote(p, v), d))
              )
            case t if t == 14 =>
              (3, xs => (cred(xs(1)), cred(xs(2))).mapN(AuthorizeCommitteeHotKey(_, _)))
            case t if t == 15 =>
              (3, xs => (cred(xs(1)), anchor(xs(2))).mapN(ResignCommitteeColdKey(_, _)))
            case t if t == 16 =>
              (4, xs => (cred(xs(1)), coin(xs(2)), anchor(xs(3))).mapN(RegisterDRep(_, _, _)))
            case t if t == 17 => (3, xs => (cred(xs(1)), coin(xs(2))).mapN(UnregisterDRep(_, _)))
            case t if t == 18 => (3, xs => (cred(xs(1)), anchor(xs(2))).mapN(UpdateDRep(_, _)))
          }
    }

  /** `OSet (TxCert era)` (`decodeOSet`, `Data/OSet/Strict.hs:109-110`): the tag 258 allowed, no
    * certificate twice by value.
    */
  def set(n: Node): Result[Vector[DijkstraCert]] =
    V12.set(n, DecodeField.Certificates)(decode)(identity)
