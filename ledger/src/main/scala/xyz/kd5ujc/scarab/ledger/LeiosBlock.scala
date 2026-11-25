// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.bls.Certificate
import xyz.kd5ujc.scarab.ledger.era.{EraTxError, Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx as EraDijkstraTx
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
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

/** The twelve fields of a Leios header body, decoded or to be signed: `HeaderBodyRaw`
  * (`libs/cardano-protocol/src/Cardano/Protocol/Leios/BlockHeader.hs`, cardano-ledger 9df9f98c).
  * [[encode]] is the encoding the reference forges and signs ([[HeaderBodyEncoding.leios]]): the
  * bytes a forger gives the KES key. It is not the signed bytes of a header that was read, whose
  * body may have been written otherwise ([[LeiosHeader.signable]], [[LeiosHeader.signed]]).
  */
final case class LeiosHeaderBody(
    blockNo: BigInt,
    slot: BigInt,
    prevHash: Option[Bytes],
    issuerVkey: Bytes,
    vrfVkey: Bytes,
    vrf: VrfCert,
    bodySize: BigInt,
    bodyHash: Bytes,
    opCert: Header.OpCert,
    leios: LeiosHeaderFields
):
  def encode: Bytes = HeaderBodyEncoding.leios(this)

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
  def praosView: Header.Shelley = viewAs(Era.Conway)

  /** The header as the node's readers of a Dijkstra block or ChainSync header hold it
    * ([[Header.fromRaw]], [[Block.Dijkstra]]): the [[praosView]] tagged with its own era, so that
    * the hard-fork combinator's checks see a header of Dijkstra. `signable` stays the body's
    * original bytes and `protocolVersion` holds the version info.
    */
  def dijkstraView: Header.Shelley = viewAs(Era.Dijkstra)

  private def viewAs(era: Era): Header.Shelley =
    Header.Shelley(
      era,
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

  /** The fields of the header body, without its bytes. */
  def body: LeiosHeaderBody =
    LeiosHeaderBody(
      blockNo,
      slot,
      prevHash,
      issuerVkey,
      vrfVkey,
      vrf,
      bodySize,
      bodyHash,
      opCert,
      leios
    )

object LeiosHeader:
  /** `encCBOR (HeaderRaw body sig)` (`libs/cardano-protocol/src/Cardano/Protocol/Leios/
    * BlockHeader.hs:335-337`, cardano-ledger 9df9f98c): `[header_body, body_signature]`, the header
    * body written as the bytes `kesSignature` signs, and the signature whole (`encodeFixedSized`, a
    * byte string of its 448 bytes). The body is memoized, its encoding its bytes
    * (`encodePreEncoded`), and the KES signature is over them (`getSignableRepresentation =
    * originalBytes`, `:164-165`): `signable` is the bytes the KES key was given, never a body
    * encoded again. For a header that was read, they are its [[LeiosHeader.signable]], which a
    * header body with a non-minimal head or an indefinite list holds as it came and an encoding of
    * its fields would not; for a block being forged, the [[LeiosHeaderBody.encode]] that was
    * signed. The reference memoizes the header so (`mkHeader`, `:251-258`): these are its bytes,
    * and their Blake2b-256 digest the block hash.
    */
  def signed(signable: Bytes, kesSignature: Bytes): Bytes =
    import HeaderBodyEncoding.{bytes as bstr, join, list}
    join(Vector(list(2), signable.toArray, bstr(kesSignature)))

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

  /** A ChainSync header of the Leios testnet's chain read by its era, as the testnet's build reads
    * it (`CardanoShelleyEras`, ouroboros-consensus 2416493b
    * `ouroboros-consensus-cardano/src/ouroboros-consensus-cardano/Ouroboros/Consensus/Cardano/Block.hs:269-278`):
    * index 7 is Dijkstra's Leios header, read by [[decode]], and an earlier era's index that era's
    * header, read by [[Header.fromRaw]] (Babbage and Conway a 10-field Praos header). The Praos
    * rules read either as a [[Header.Shelley]], a Leios header through [[LeiosHeader.praosView]].
    */
  def byEra(header: HfcHeader): Either[ChainError, Header.Shelley] = header match
    case HfcHeader.Dijkstra(raw) => decode(raw).map(_.praosView)
    case HfcHeader.Supported(h)  => Header.fromRaw(h.era, h.raw)
    case HfcHeader.Unrecognised(index, _) =>
      Left(fail(LedgerDecodeError.UnknownTag(DecodeField.BlockEra, index)))

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
    * `Decoding/DecCBOR.hs:627-652`, cardano-ledger 9df9f98c): `decodeRecordNamed` of the output and
    * the proof, a definite or an indefinite list; the output must be the 64 bytes of a Praos VRF
    * output (`:630-636`), the proof is the 80 bytes of `decodeFixedSized`.
    */
  private def vrfCert(n: Node): Either[ChainError, VrfCert] =
    for
      xs <- array(n, 2)
      output <- bytes(xs(0), 64)
      proof <- bytes(xs(1), 80)
    yield VrfCert(output, proof)

  /** `BlockHeaderVersionInfo` (`Block.hs:163-166`, cardano-ledger 9df9f98c): two `Word32`, neither
    * validated.
    */
  private def versionInfo(n: Node): Either[ChainError, HeaderVersionInfo] =
    for
      xs <- array(n, 2)
      major <- Header.word32(xs(0), DecodeField.Major)
      tag <- Header.word32(xs(1), DecodeField.Minor)
    yield HeaderVersionInfo(major, tag)

  /** Null, or `EbReferencesAnnouncement` (`Block.hs:184-189`, cardano-ledger 9df9f98c): the hash
    * and a `Word32` size.
    */
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
  * `[body, witnesses, auxiliary_data / nil, is_valid]`, read as the build decodes it straight into
  * Dijkstra's types ([[era.dijkstra.DijkstraTx.inBlock]]), and the block body hash is the
  * Blake2b-256 digest of the whole encoded body (cardano-ledger 9df9f98c
  * `Dijkstra/BlockBody/Internal.hs:120`: `hashBlockBody = extractHash . getMemoBytesHash`), not a
  * merkle of component hashes (the CDDL comment "merkle triple root" is stale).
  *
  * Every part keeps its original bytes. A transaction whose reading met a value the decoder's own
  * bounds rule out is not among `transactions` but in `unread`, by its index among the block's
  * items: the node's defect, no fault of the bytes, which the block decoder accepts as the build's
  * decoder does, and only the application of the block in the era's types meets
  * ([[era.dijkstra.DijkstraBlock.decode]], `DijkstraBlockError.Projection`), so the block is
  * withheld and no peer is charged for it.
  */
final case class LeiosBlock(
    header: LeiosHeader,
    transactions: Vector[EraDijkstraTx[TxLevel.Top]],
    leiosCertificate: Option[LeiosBlock.CertificateBytes],
    perasCertificate: Option[Bytes],
    bodySize: Long,
    bodyHash: Bytes,
    unread: Vector[LeiosBlock.Unread] = Vector.empty
):
  /** How many transactions the body holds, those read and those unread. */
  def transactionCount: Int = transactions.size + unread.size

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
      Option.when(leiosCertificate.isDefined && transactionCount > 0)(
        LeiosBlock.Fault.CertificateWithTransactions(transactionCount)
      )
    ).flatten

object LeiosBlock:
  /** `[8, [header, block_body]]`, the block in the hard-fork envelope as BlockFetch carries it and
    * [[decode]] reads it: `encCBOR (Block h body)` of Dijkstra, `encodeListLen 2` of the header and
    * the body (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Block.hs:27-29`, cardano-ledger
    * 9df9f98c), each written as the bytes it memoizes, after Dijkstra's tag (`prependTag 8`,
    * `encodeDiskHfcBlock`, ouroboros-consensus 2416493b
    * `ouroboros-consensus-cardano/src/ouroboros-consensus-cardano/Ouroboros/Consensus/Cardano/Node.hs:173`).
    * `header` is [[LeiosHeader.signed]]'s, `body` [[LeiosBlockBody.encode]]'s.
    */
  def encode(header: Bytes, body: Bytes): Bytes =
    import HeaderBodyEncoding.{join, list as listOf, uint as word}
    join(
      Vector(
        listOf(2),
        word(BigInt(HfcHeader.DijkstraBlockTag)),
        listOf(2),
        header.toArray,
        body.toArray
      )
    )

  /** A transaction of the body, at `index` among its items, whose reading met a value the decoder's
    * own bounds rule out (an [[EraTxError]] other than `Decode`): the node's defect.
    */
  final case class Unread(index: Int, cause: EraTxError)

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

  // no budget of items, string bytes or nesting, as for a block of another era (`Block`, #749)
  private val limits = Cbor.Limits.Unbounded

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
    * refused where the reference refuses it. Each transaction is decoded in full at version 12, in
    * one pass into Dijkstra's types ([[era.dijkstra.DijkstraTx.inBlock]]): its body, witness set,
    * auxiliary data and sub-transactions, each with its original bytes; no ledger rule is applied.
    * The decoder's refusal is the block's, the first in the order of the items; a value its own
    * bounds rule out is no refusal ([[LeiosBlock.unread]]).
    */
  def decode(raw: Bytes): Either[ChainError, LeiosBlock] = decodeWith(raw, EraDijkstraTx.inBlock)

  /** [[decode]] with the transactions read by `reader`: the era's decoder, or a test's. */
  private[ledger] def decodeWith(
      raw: Bytes,
      reader: Node => Either[EraTxError, EraDijkstraTx[TxLevel.Top]]
  ): Either[ChainError, LeiosBlock] =
    CborRead.decode(raw, limits).leftMap(fail).flatMap(readWith(_, reader))

  /** [[decode]] of a block already read as a tree of items; [[Block.decode]] reads a block of tag 8
    * with it. A Dijkstra block holds no `RecD` record at its decoder version 12
    * ([[DetachedRecords.Shapes.block]]), so the tree is the reference's reading of it.
    */
  private[ledger] def read(root: Node): Either[ChainError, LeiosBlock] =
    readWith(root, EraDijkstraTx.inBlock)

  /** [[read]] with the transactions read by `reader`. The first refusal of the decoder in the order
    * of the items is the block's; the transactions whose reading met a value the decoder's own
    * bounds rule out are kept apart ([[Unread]]), as the flat decoder accepted them before the one
    * pass and only their projection into the era's types met them (#85).
    */
  private def readWith(
      root: Node,
      reader: Node => Either[EraTxError, EraDijkstraTx[TxLevel.Top]]
  ): Either[ChainError, LeiosBlock] =
    for
      (header, body) <- framed(root)
      xs <- array(body, 3)
      items <- list(xs(0))
      results = items.map(reader)
      _ <- results.collectFirst { case Left(EraTxError.Decode(e)) => fail(e) }.toLeft(())
      txs = results.collect { case Right(t) => t }
      unread = results.zipWithIndex.collect {
        case (Left(cause), i) if (cause match
              case EraTxError.Decode(_) => false
              case _                    => true
            ) =>
          Unread(i, cause)
      }
      cert <- nullable(xs(1))(certificate)
      peras <- nullable(xs(2))(c =>
        CborRead.bytes(c, DecodeField.PerasCertificate)(using ByteStrings.Chunked).leftMap(fail)
      )
      bytes = body.original
    yield LeiosBlock(
      header,
      txs,
      cert,
      peras,
      bytes.size.toLong,
      Blake2b.hash256.hash(bytes),
      unread
    )

  /** The header of a block already read as a tree of items, its envelope and header read as
    * [[read]] reads them and its body's contents not decoded ([[Block.decodeHeader]]).
    */
  private[ledger] def header(root: Node): Either[ChainError, LeiosHeader] = framed(root).map(_._1)

  /** The envelope, the block's two parts and its header: what [[read]] and [[header]] share. */
  private def framed(root: Node): Either[ChainError, (LeiosHeader, Node)] =
    for
      outer <- CborRead.enforceSize(root, 2, DecodeField.LeiosBlock).leftMap(fail)
      tag <- uint(outer(0))
      _ <- Either.cond(
        tag == HfcHeader.DijkstraBlockTag,
        (),
        fail(LedgerDecodeError.UnknownTag(DecodeField.LeiosBlockTag, tag))
      )
      parts <- array(outer(1), 2)
      header <- LeiosHeader.decode(parts(0).original)
    yield (header, parts(1))

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

/** The body of a Dijkstra block, `DijkstraBlockBodyRaw` (`eras/dijkstra/impl/src/Cardano/Ledger/
  * Dijkstra/BlockBody/Internal.hs:100-106`, cardano-ledger 9df9f98c): the transactions, and the
  * Leios and the Peras certificates, each optional. The reference builds the body of a block it
  * forges from these and memoizes its encoding at Dijkstra's version (`mkBasicBlockBodyDijkstra`
  * and the lenses of `lensMemoRawType`, `:119, 123-127, 138`, `MemoBytes/Internal.hs:299-300,
  * 337-344`; `mkBody`, ouroboros-consensus 2416493b
  * `ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Forge.hs:152-160`):
  * [[encode]], whose digest is the header's body hash and whose length its body size. The
  * transactions are the era's (`StrictSeq (Tx TopTx era)`), each with the flag of its verdict.
  */
final case class LeiosBlockBody(
    transactions: Vector[Tx[TxLevel.Top, Dijkstra]],
    leiosCertificate: Option[Certificate],
    perasCertificate: Option[Bytes]
):
  /** `encCBOR` of `DijkstraBlockBodyRaw` (`Internal.hs:184-189`, cardano-ledger 9df9f98c):
    * `encodeListLen 3`, the transactions each in its block form
    * ([[EraDijkstraTx.forBlockInclusion]]) in a list that is definite up to 23 of them and
    * indefinite above (`encodeFoldableEncoder`,
    * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Encoding/Encoder.hs:363-367, 463-466`),
    * then the Leios certificate (`[signers, signature]`, `Encoding/EncCBOR.hs:481-485`) and the
    * Peras certificate (a byte string: `newtype PerasCert = PerasCert ByteArray`,
    * `Internal.hs:224`), each or null (`encodeNullStrictMaybe`).
    */
  lazy val encode: Bytes =
    import HeaderBodyEncoding.{bytes as bstr, join, list, Null}
    val txs = transactions.map(EraDijkstraTx.forBlockInclusion(_).toArray)
    val seq =
      if txs.sizeIs <= LeiosBlockBody.LengthThreshold then list(txs.size) +: txs
      else (Array(LeiosBlockBody.IndefiniteList) +: txs) :+ Array(LeiosBlockBody.Break)
    join(
      Vector(list(3)) ++ seq ++ Vector(
        leiosCertificate.fold(Null)(_.encode.toArray),
        perasCertificate.fold(Null)(bstr)
      )
    )

  /** `hashBlockBody`: the Blake2b-256 digest of the memoized bytes (`Internal.hs:120`, 9df9f98c).
    */
  def hash: Bytes = Blake2b.hash256.hash(encode)

  /** `blockBodySize (ProtVer v _) = BS.length . serialize' v . encCBOR` (`Internal.hs:121`,
    * 9df9f98c): the encoding of a memoized body is its bytes (`encodePreEncoded`,
    * `MemoBytes/Internal.hs:167-168`), whatever the version.
    */
  def size: Long = encode.size.toLong

object LeiosBlockBody:
  /** `lengthThreshold` (`Encoder.hs:442-443`, cardano-ledger 9df9f98c): the most items a list is
    * written with a definite length.
    */
  private val LengthThreshold = 23
  private val IndefiniteList: Byte = 0x9f.toByte
  private val Break: Byte = 0xff.toByte
