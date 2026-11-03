// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.bls.Certificate
import xyz.kd5ujc.scarab.protocol.{ChainSync, EbHash, Era, HfcHeader}

import Header.{array, bytes, fail, headerLimits, optHash, record, uint, vrfCert}

/** `header_version_info = [highest_supported_major_version, self_reported_software_tag]`: what the
  * Leios header carries where the earlier eras have `protocol_version`. Same wire shape, decoded as
  * the reference's `ProtVer` (a major up to `MaxVersion` and a `Word32`,
  * `Leios/BlockHeader.hs:229-243`), but neither field is validated by the ledger
  * (cardano-ledger-core `Block.hs` 147-156, issue 5763).
  */
final case class HeaderVersionInfo(highestSupportedMajor: BigInt, softwareTag: BigInt)

/** `eb_references_announcement = [eb_hash, eb_size]`: the ranking block announces the endorser
  * block whose references it commits to (`dijkstra.cddl` 105-108).
  */
final case class EbAnnouncement(hash: EbHash, size: Long)

/** The two header fields Leios adds to the Praos header body, after the version info
  * (`cardano-protocol` `Cardano/Protocol/Leios/BlockHeader.hs`, `HeaderBodyRaw`).
  */
final case class LeiosHeaderFields(
    versionInfo: HeaderVersionInfo,
    blockBodyContainsLeiosCert: Boolean,
    announcement: Option[EbAnnouncement]
)

/** A Leios block header: the Praos header body with `protocol_version` replaced by
  * `header_version_info` and the two Leios fields appended, 12 elements (`dijkstra.cddl` 56-71).
  * The public Leios testnet sends this shape in blocks tagged Conway as well as Dijkstra (see
  * `docs/leios.md` section 11), so it is not tied to an era here. `raw` is the exact encoding; the
  * block hash is its Blake2b-256 digest. The KES signature is over `signable`, the header body as
  * the reference encodes it again (`Leios/BlockHeader.hs:139-142`, [[HeaderBodyEncoding.leios]]),
  * not over the body's original bytes (#698).
  */
final case class LeiosHeader(
    raw: Bytes,
    signable: Bytes,
    hash: Bytes,
    blockNo: BigInt,
    slot: BigInt,
    prevHash: Option[Bytes],
    issuerVkey: Bytes,
    vrfVkey: Bytes,
    vrf: VrfCert,
    bodySize: BigInt,
    bodyHash: Bytes,
    opCert: Header.OpCert,
    leios: LeiosHeaderFields,
    kesSignature: Bytes
):
  /** The header as the Praos rules read it: the first ten fields mean what they mean in Babbage and
    * Conway (VRF input, leader value, operational certificate, KES signature over `signable`), so
    * `consensus.Praos` validates it unchanged. Tagged `Era.Conway` because the node has no Dijkstra
    * era (docs/leios.md section 11); the "protocol version" is the version info, which the Praos
    * rules do not read. Not a claim that the block is a Conway block.
    */
  def praosView: Header.Shelley =
    Header.Shelley(
      Era.Conway,
      raw,
      signable,
      hash,
      blockNo,
      slot,
      prevHash,
      issuerVkey,
      vrfVkey,
      HeaderVrf.Praos(vrf),
      bodySize,
      bodyHash,
      opCert,
      (leios.versionInfo.highestSupportedMajor, leios.versionInfo.softwareTag),
      kesSignature
    )

  def point: ChainSync.Point =
    ChainSync.Point.Block(ChainSync.UInt64.saturating(slot), hash)

object LeiosHeader:
  /** Decode `[header_body, body_signature]` with the 12-element body, as the reference's Leios
    * header (`libs/cardano-protocol/src/Cardano/Protocol/Leios/BlockHeader.hs:229-257`,
    * cardano-ledger f649f975) reads it: the header, its body and the endorser block announcement
    * are `RecD` records, definite or detached ([[DetachedRecords.Shapes.leiosHeader]]); the rest as
    * in a Praos header ([[Header.fromRaw]]), the version info a `ProtVer`.
    */
  def decode(raw: Bytes): Either[ChainError, LeiosHeader] =
    DetachedRecords.read(raw, headerLimits, DetachedRecords.Shapes.leiosHeader)(e =>
      fail(LedgerDecodeError.Syntax(e))
    )(read(raw, _))

  private def read(raw: Bytes, root: Node): Either[ChainError, LeiosHeader] =
    for
      outer <- record(root, 2)
      kes <- bytes(outer(1), 448)
      body <- record(outer(0), 12)
      blockNo <- uint(body(0))
      slot <- uint(body(1))
      prev <- optHash(body(2))
      issuer <- bytes(body(3), 32)
      vrfVkey <- bytes(body(4), 32)
      vrf <- vrfCert(body(5))
      size <- uint32(body(6))
      bodyHash <- bytes(body(7), 32)
      oc <- array(body(8), 4)
      hot <- bytes(oc(0), 32)
      seq <- uint(oc(1))
      period <- uint(oc(2))
      sigma <- bytes(oc(3), 64)
      info <- versionInfo(body(9))
      cert <- bool(body(10))
      announcement <- announcement(body(11))
      opCert = Header.OpCert(hot, seq, period, sigma)
      fields = HeaderBodyEncoding.Fields(
        blockNo,
        slot,
        prev,
        issuer,
        vrfVkey,
        HeaderVrf.Praos(vrf),
        size,
        bodyHash,
        opCert,
        (info.highestSupportedMajor, info.softwareTag)
      )
    yield LeiosHeader(
      raw,
      HeaderBodyEncoding.leios(fields, cert, announcement.map(a => (a.hash.bytes, a.size))),
      Blake2b.hash256.hash(raw),
      blockNo,
      slot,
      prev,
      issuer,
      vrfVkey,
      vrf,
      size,
      bodyHash,
      opCert,
      LeiosHeaderFields(info, cert, announcement),
      kes
    )

  private val Max32 = (BigInt(1) << 32) - 1

  private def uint32(n: Node): Either[ChainError, BigInt] =
    uint(n).flatMap(v =>
      Either.cond(
        v <= Max32,
        v,
        fail(LedgerDecodeError.NumberOutOfRange(DecodeField.LeiosUint32, v, 0, Max32))
      )
    )

  private def bool(n: Node): Either[ChainError, Boolean] = n.value match
    case Value.Bool(b) => Right(b)
    case _ =>
      Left(
        fail(
          LedgerDecodeError.Expected(
            DecodeField.LeiosFlag,
            LedgerDecodeError.Shape.Boolean,
            n.value
          )
        )
      )

  /** The `ProtVer` of the reference's header body (`hbProtVer`, read as in a Praos header,
    * [[Header.protocolVersion]]): a major up to `MaxVersion` and a `Word32`.
    */
  private def versionInfo(n: Node): Either[ChainError, HeaderVersionInfo] =
    for
      xs <- array(n, 2)
      pv <- Header.protocolVersion(xs(0), xs(1))
    yield HeaderVersionInfo(pv._1, pv._2)

  private def announcement(n: Node): Either[ChainError, Option[EbAnnouncement]] = n.value match
    case Value.Null => Right(None)
    case _ =>
      for
        xs <- record(n, 2)
        hash <- bytes(xs(0), 32)
        eb = EbHash.fromBytes(hash)
        size <- uint32(xs(1))
      yield Some(EbAnnouncement(eb, size.toLong))
end LeiosHeader

/** A block of the Leios testnet. Two body layouts occur, told apart by the block tag:
  *
  *   - `Segregated` (tag 7, Conway): `[header, bodies, witnesses, auxiliary data, invalid]` as in
  *     Conway, with the Leios header; the body hash is the merkle digest of the component hashes;
  *   - `Whole` (tag 8, Dijkstra): `[header, block_body]` with `block_body = [transactions,
  *     leios_certificate / nil, peras_certificate / nil]`. Each transaction is whole, `[body,
  *     witnesses, auxiliary_data / nil, is_valid]`, and the block body hash is the Blake2b-256
  *     digest of the whole encoded body (cardano-ledger `Dijkstra/BlockBody/Internal.hs`:
  *     `hashBlockBody = extractHash . getMemoBytesHash`), not a merkle of component hashes (the
  *     CDDL comment "merkle triple root" is stale).
  *
  * Every part keeps its original bytes.
  */
final case class LeiosBlock(
    layout: LeiosBlock.Layout,
    header: LeiosHeader,
    transactions: Vector[Block.Transaction],
    leiosCertificate: Option[LeiosBlock.CertificateBytes],
    perasCertificate: Option[Bytes],
    bodySize: Long,
    bodyHash: Bytes
):
  /** Does the block belong to its header, and is it well formed as a ranking block? The checks of
    * consensus `blockMatchesHeader` (`Shelley/Ledger/Block.hs` 224-231) and the size of the
    * ledger's BBODY rule, each reported as its own case.
    */
  def faults: Vector[LeiosBlock.Fault] =
    Vector(
      Option.when(bodyHash != header.bodyHash)(LeiosBlock.Fault.BodyHashDiffers),
      Option.when(BigInt(bodySize) != header.bodySize)(
        LeiosBlock.Fault.BodySizeDiffers(bodySize, header.bodySize)
      ),
      Option.when(leiosCertificate.isDefined != header.leios.blockBodyContainsLeiosCert)(
        LeiosBlock.Fault.CertificateFlagDiffers(
          header.leios.blockBodyContainsLeiosCert,
          leiosCertificate.isDefined
        )
      ),
      Option.when(leiosCertificate.isDefined && transactions.nonEmpty)(
        LeiosBlock.Fault.CertificateWithTransactions(transactions.size)
      )
    ).flatten

object LeiosBlock:
  enum Layout:
    case Segregated, Whole

  /** The certificate of a block body, strictly decoded, with the bytes it arrived as. */
  final case class CertificateBytes(certificate: Certificate, raw: Bytes)

  /** Why a block does not match its header or is not a valid ranking block. A closed set. */
  enum Fault:
    /** The digest of the body is not the header's `block_body_hash`. */
    case BodyHashDiffers

    /** The body size is not the header's `block_body_size`. */
    case BodySizeDiffers(actual: Long, declared: BigInt)

    /** `block_body_contains_leios_cert` disagrees with the presence of the certificate. */
    case CertificateFlagDiffers(declared: Boolean, present: Boolean)

    /** A block that certifies an endorser block carries it instead of transactions, never both. */
    case CertificateWithTransactions(transactions: Int)

  // no budget of items, string bytes or nesting, as for a block of another era (`Block`, #749)
  private val limits = Cbor.Limits.Unbounded

  private val ConwayTag = Era.Conway.blockTag

  /** Decode a BlockFetch block `[tag, block]` of tag 7 or 8. A certificate that is not a strict
    * `leios_certificate` fails the decode; its typed `BlsError` is the reason in the message.
    */
  def decode(raw: Bytes): Either[ChainError, LeiosBlock] =
    for
      root <- CborRead.decode(raw, limits).leftMap(fail)
      outer <- array(root, 2)
      tag <- uint(outer(0))
      block <-
        if tag == ConwayTag then segregated(outer(1))
        else if tag == HfcHeader.DijkstraBlockTag then whole(outer(1))
        else Left(fail(LedgerDecodeError.UnknownTag(DecodeField.LeiosBlockTag, tag)))
    yield block

  private def segregated(n: Node): Either[ChainError, LeiosBlock] =
    for
      parts <- array(n, 5)
      header <- LeiosHeader.decode(parts(0).original)
      body <- Block.segregated(parts, hasInvalid = true)
    yield LeiosBlock(
      Layout.Segregated,
      header,
      body.transactions,
      None,
      None,
      body.size,
      body.hash
    )

  private def whole(n: Node): Either[ChainError, LeiosBlock] =
    for
      parts <- array(n, 2)
      header <- LeiosHeader.decode(parts(0).original)
      xs <- array(parts(1), 3)
      txs <- list(xs(0)).flatMap(_.traverse(transaction))
      cert <- nullable(xs(1))(certificate)
      peras <- nullable(xs(2))(c =>
        c.value match
          case Value.ByteString(b) => Right(b)
          case _ =>
            Left(
              fail(
                LedgerDecodeError.Expected(
                  DecodeField.PerasCertificate,
                  LedgerDecodeError.Shape.Bytes,
                  c.value
                )
              )
            )
      )
      body = parts(1).original
    yield LeiosBlock(
      Layout.Whole,
      header,
      txs,
      cert,
      peras,
      body.size.toLong,
      Blake2b.hash256.hash(body)
    )

  /** `[transaction_body, transaction_witness_set, auxiliary_data / nil, is_valid]`. */
  private def transaction(n: Node): Either[ChainError, Block.Transaction] =
    for
      xs <- array(n, 4)
      valid <- xs(3).value match
        case Value.Bool(b) => Right(b)
        case _ =>
          Left(
            fail(
              LedgerDecodeError.Expected(
                DecodeField.IsValid,
                LedgerDecodeError.Shape.Boolean,
                xs(3).value
              )
            )
          )
      aux = xs(2).value match
        case Value.Null => None
        case _          => Some(xs(2).original)
    yield Block.Transaction(xs(0).original, xs(1).original, aux, valid)

  private def certificate(n: Node): Either[ChainError, CertificateBytes] =
    Certificate
      .decode(n.original)
      .bimap(e => fail(LedgerDecodeError.LeiosCertificate(e)), CertificateBytes(_, n.original))

  private def nullable[A](n: Node)(
      f: Node => Either[ChainError, A]
  ): Either[ChainError, Option[A]] =
    n.value match
      case Value.Null => Right(None)
      case _          => f(n).map(Some(_))

  private def list(n: Node): Either[ChainError, Vector[Node]] = n.value match
    case Value.Arr(xs) => Right(xs)
    case _ =>
      Left(
        fail(
          LedgerDecodeError.Expected(DecodeField.LeiosBlock, LedgerDecodeError.Shape.Array, n.value)
        )
      )
end LeiosBlock
