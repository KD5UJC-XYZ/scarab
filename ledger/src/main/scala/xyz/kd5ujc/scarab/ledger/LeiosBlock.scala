// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.bls.Certificate
import xyz.kd5ujc.scarab.protocol.{ChainSync, EbHash, Era, HfcHeader}

import Header.{array, bytes, fail, headerLimits, optHash, uint}

/** `header_version_info = [highest_supported_major_version, self_reported_software_tag]`: what the
  * Leios header carries where the earlier eras have `protocol_version`. The wire shape of a
  * `ProtVer`, read as `BlockHeaderVersionInfo`: two `Word32`, neither validated
  * (cardano-ledger-core `Block.hs:147-166`, cardano-ledger 9df9f98c; issue 5763 there).
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
  * The reference is the build of the public Leios testnet (cardano-node b004fe0c, cardano-ledger
  * 9df9f98c, `docs/leios.md` section 1), whose Dijkstra block holds it (`ShelleyProtocolHeader
  * (Praos2 c) = Leios.Header c`, ouroboros-consensus 2416493b
  * `Ouroboros/Consensus/Protocol/Praos2.hs:57`). `raw` is the exact encoding; the block hash is its
  * Blake2b-256 digest. The KES signature is over `signable`, the header body's original bytes: the
  * body is memoized and `getSignableRepresentation = originalBytes`
  * (`libs/cardano-protocol/src/Cardano/Protocol/Leios/BlockHeader.hs:164-165`).
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
  /** Decode `[header_body, body_signature]` with the 12-element body, as the Leios header of the
    * testnet's build reads it at decoder version 12 (`libs/cardano-protocol/src/Cardano/Protocol/
    * Leios/BlockHeader.hs:308-344`, cardano-ledger 9df9f98c): the header and its body are read by
    * `decodeRecordNamed`, and so are the version info and the endorser block announcement
    * (`libs/cardano-ledger-core/src/Cardano/Ledger/Block.hs:163-166, 184-189`), each a definite
    * list of its fields or an indefinite one with its fields before the break, as is the VRF
    * certificate at that version ([[vrfCert]]). None is a `RecD` record, so an empty indefinite
    * list with the fields after it is refused. The other fields are read as in a Praos header
    * ([[Header.fromRaw]]) at version 12, where a byte string may be chunked.
    */
  def decode(raw: Bytes): Either[ChainError, LeiosHeader] =
    CborRead.decode(raw, headerLimits).leftMap(fail).flatMap(read(raw, _))

  private def read(raw: Bytes, root: Node): Either[ChainError, LeiosHeader] =
    for
      outer <- array(root, 2)
      kes <- bytes(outer(1), 448)
      body <- array(outer(0), 12)
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
    yield LeiosHeader(
      raw,
      outer(0).original,
      Blake2b.hash256.hash(raw),
      blockNo,
      slot,
      prev,
      issuer,
      vrfVkey,
      vrf,
      size,
      bodyHash,
      Header.OpCert(hot, seq, period, sigma),
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

  /** `DecCBOR (CertifiedVRF v a)` at decoder version 12 (cardano-ledger-binary
    * `Decoding/DecCBOR.hs:627-652`): `decodeRecordNamed` of the output and the proof, a definite or
    * an indefinite list; the output must be the 64 bytes of a Praos VRF output (`:630-636`), the
    * proof is the 80 bytes of `decodeFixedSized`.
    */
  private def vrfCert(n: Node): Either[ChainError, VrfCert] =
    for
      xs <- array(n, 2)
      output <- bytes(xs(0), 64)
      proof <- bytes(xs(1), 80)
    yield VrfCert(output, proof)

  /** `BlockHeaderVersionInfo` (`Block.hs:163-166`): two `Word32`, neither validated. */
  private def versionInfo(n: Node): Either[ChainError, HeaderVersionInfo] =
    for
      xs <- array(n, 2)
      major <- Header.word32(xs(0), DecodeField.Major)
      tag <- Header.word32(xs(1), DecodeField.Minor)
    yield HeaderVersionInfo(major, tag)

  /** Null, or `EbReferencesAnnouncement` (`Block.hs:184-189`): the hash and a `Word32` size. */
  private def announcement(n: Node): Either[ChainError, Option[EbAnnouncement]] = n.value match
    case Value.Null => Right(None)
    case _ =>
      for
        xs <- array(n, 2)
        hash <- bytes(xs(0), 32)
        eb = EbHash.fromBytes(hash)
        size <- uint32(xs(1))
      yield Some(EbAnnouncement(eb, size.toLong))
end LeiosHeader

/** A Dijkstra block of the Leios testnet, block tag 8: `[header, block_body]` with `block_body =
  * [transactions, leios_certificate / nil, peras_certificate / nil]`. Each transaction is whole,
  * `[body, witnesses, auxiliary_data / nil, is_valid]`, and the block body hash is the Blake2b-256
  * digest of the whole encoded body (cardano-ledger `Dijkstra/BlockBody/Internal.hs:120`:
  * `hashBlockBody = extractHash . getMemoBytesHash`), not a merkle of component hashes (the CDDL
  * comment "merkle triple root" is stale).
  *
  * Every part keeps its original bytes.
  */
final case class LeiosBlock(
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
  /** The certificate of a block body, decoded, with the bytes it arrived as. */
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

  private val limits = Cbor.Limits(
    maxInputBytes = 2500000,
    maxItems = 1000000,
    maxStringBytes = 2500000
  )

  /** Decode a BlockFetch block `[tag, block]` as the testnet's build does (ouroboros-consensus
    * 2416493b, cardano-ledger 9df9f98c). The envelope is read by `enforceSize "CardanoBlock" 2`, a
    * definite list, and its tag must be 8, Dijkstra's (`decodeDiskHfcBlock`,
    * `Ouroboros/Consensus/Cardano/Node.hs:174-199`; tag 7 is a Conway block with a Praos header,
    * which [[Block.decode]] reads). The block is read at Dijkstra's decoder version 12 by
    * `decodeRecordNamed "Block" (const 2)` (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/
    * Block.hs:34-42`), its body by `decodeRecordNamed "DijkstraBlockBodyRaw" (const 3)` over
    * `decodeSeq` of the transactions and the two nullable certificates (`BlockBody/Internal.hs:
    * 191-208`), and each transaction by `decodeRecordNamed "DijkstraTx" (const 4)`
    * (`Tx.hs:175-182`): each a definite list or an indefinite one with its items before the break.
    * No list of the block is a `RecD` record at version 12 ([[LeiosHeader.decode]]; the records of
    * a transaction are read by `decodeRecordNamed` from version 12, e.g. `Mary/Value.hs:308-321`),
    * so the block is read as a tree of items, and an empty indefinite list with fields after it is
    * refused where the reference refuses it. A transaction's parts are kept as their bytes; they
    * are not decoded.
    */
  def decode(raw: Bytes): Either[ChainError, LeiosBlock] =
    for
      root <- CborRead.decode(raw, limits).leftMap(fail)
      _ <- CborRead.definite(root, DecodeField.LeiosBlock).leftMap(fail)
      outer <- array(root, 2)
      tag <- uint(outer(0))
      _ <- Either.cond(
        tag == HfcHeader.DijkstraBlockTag,
        (),
        fail(LedgerDecodeError.UnknownTag(DecodeField.LeiosBlockTag, tag))
      )
      parts <- array(outer(1), 2)
      header <- LeiosHeader.decode(parts(0).original)
      xs <- array(parts(1), 3)
      txs <- list(xs(0)).flatMap(_.traverse(transaction))
      cert <- nullable(xs(1))(certificate)
      peras <- nullable(xs(2))(c =>
        CborRead.bytes(c, DecodeField.PerasCertificate)(using ByteStrings.Chunked).leftMap(fail)
      )
      body = parts(1).original
    yield LeiosBlock(header, txs, cert, peras, body.size.toLong, Blake2b.hash256.hash(body))

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

  /** `DecCBOR LeiosCert` (cardano-ledger-binary `Decoding/DecCBOR.hs:721-736`, 9df9f98c):
    * `decodeRecordNamed` of the signers, a byte string of at most 8192 bytes, and the signature, a
    * byte string that is a BLS12-381 MinSig signature; at decoder version 12 a byte string is
    * definite or chunked (`decodeByteArray`, `decodeBytes`,
    * `Decoding/Decoder.hs:359-364, 1388-1394`).
    */
  private def certificate(n: Node): Either[ChainError, CertificateBytes] =
    given ByteStrings = ByteStrings.Chunked
    for
      xs <- array(n, 2)
      signers <- CborRead.bytes(xs(0), DecodeField.LeiosCertificate).leftMap(fail)
      signature <- CborRead.bytes(xs(1), DecodeField.LeiosCertificate).leftMap(fail)
      cert <- Certificate
        .fromFields(signers, signature)
        .leftMap(e => fail(LedgerDecodeError.LeiosCertificate(e)))
    yield CertificateBytes(cert, n.original)

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
