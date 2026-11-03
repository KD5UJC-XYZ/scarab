// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era, NtNHeader, ProtocolError}
import xyz.kd5ujc.scarab.ledger.byron.{ByronTransport, ByronVersion}

/** VRF certificate: the output, 64 bytes in any certificate that verifies, and the 80-byte proof.
  */
final case class VrfCert(output: Bytes, proof: Bytes)

/** The VRF evidence of a header: TPraos (Shelley-Alonzo) has separate nonce and leader
  * certificates, Praos (Babbage-Conway) a single one for both uses.
  */
enum HeaderVrf:
  case TPraos(nonce: VrfCert, leader: VrfCert)
  case Praos(result: VrfCert)

/** A decoded block header. `raw` is the exact encoding; its Blake2b-256 digest is the block hash
  * (for Byron, of the tagged `[subtag, header]` wrapper).
  */
sealed trait Header:
  def era: Era
  def raw: Bytes
  def hash: Bytes
  def blockNo: BigInt
  def slot: BigInt
  def prevHash: Option[Bytes]
  def point: ChainSync.Point =
    ChainSync.Point.Block(ChainSync.UInt64.saturating(slot), hash)
  def prevPoint: Option[Bytes] = prevHash

  /** A Byron epoch-boundary block: it shares its slot with the epoch's first slot and its block
    * number with the previous block.
    */
  def isBoundary: Boolean = false

object Header:
  /** Shelley..Conway header. `raw` is the exact encoding, which the hash is over; `signable` is the
    * header body as the reference encodes it again, which the KES signature is over
    * ([[HeaderBodyEncoding]]): not the body's original bytes, from which it differs when they hold
    * a non-minimal head, an indefinite list or a detached record (#698).
    */
  final case class Shelley(
      era: Era,
      raw: Bytes,
      signable: Bytes,
      hash: Bytes,
      blockNo: BigInt,
      slot: BigInt,
      prevHash: Option[Bytes],
      issuerVkey: Bytes,
      vrfVkey: Bytes,
      vrf: HeaderVrf,
      bodySize: BigInt,
      bodyHash: Bytes,
      opCert: OpCert,
      protocolVersion: (BigInt, BigInt),
      kesSignature: Bytes
  ) extends Header

  /** A Byron header decoded without its BlockFetch size hint: native UnsizedHeader, consensus
    * 82ecba329 Byron/Ledger/Block.hs:274–338. Raw header bytes cannot determine the block's size.
    * Joining the actual wire hint or full-block estimate creates a Header.
    */
  final case class UnsizedByron(
      raw: Bytes,
      hash: Bytes,
      boundary: Boolean,
      epoch: BigInt,
      slotInEpoch: BigInt,
      epochSlots: BigInt,
      blockNo: BigInt,
      prevHash: Option[Bytes],
      protocolMagic: BigInt = 0,
      bodyProof: Bytes = Bytes(Vector.empty),
      main: Option[ByronMain] = None,
      blockVersion: ByronVersion = ByronVersion.zero
  ):
    def slot: BigInt = epoch * epochSlots + slotInEpoch
    def withSizeHint(blockSizeHint: Unsigned[Unsigned.Width.W32]): Byron = Byron(
      raw,
      hash,
      boundary,
      epoch,
      slotInEpoch,
      epochSlots,
      blockNo,
      prevHash,
      blockSizeHint,
      protocolMagic,
      bodyProof,
      main,
      blockVersion
    )

  /** Byron main or epoch-boundary header. For a boundary block `slotInEpoch` is 0; `blockNo` is the
    * header's chain difficulty (a boundary block repeats its predecessor's). `main` holds what a
    * main header adds: issuer key, signature and the bytes the signature covers.
    */
  final case class Byron(
      raw: Bytes,
      hash: Bytes,
      boundary: Boolean,
      epoch: BigInt,
      slotInEpoch: BigInt,
      epochSlots: BigInt,
      blockNo: BigInt,
      prevHash: Option[Bytes],
      blockSizeHint: Unsigned[Unsigned.Width.W32],
      protocolMagic: BigInt = 0,
      bodyProof: Bytes = Bytes(Vector.empty),
      main: Option[ByronMain] = None,
      blockVersion: ByronVersion = ByronVersion.zero
  ) extends Header:
    def era: Era = Era.Byron
    def slot: BigInt = epoch * epochSlots + slotInEpoch
    override def isBoundary: Boolean = boundary
    def withoutSizeHint: UnsizedByron = UnsizedByron(
      raw,
      hash,
      boundary,
      epoch,
      slotInEpoch,
      epochSlots,
      blockNo,
      prevHash,
      protocolMagic,
      bodyProof,
      main,
      blockVersion
    )

  /** How a Byron main block header is signed: only heavyweight delegation exists (the signature
    * tags for no delegation and light delegation are not decodable upstream). `cert` is the
    * issuer's (the genesis key's) signature over the delegation, valid from `epoch`.
    */
  enum ByronSignature:
    case Delegated(
        epoch: BigInt,
        issuer: Bytes,
        delegate: Bytes,
        cert: Bytes,
        signature: Bytes
    )

  /** The consensus data of a Byron main header: slot leader key (64-byte extended public key), its
    * signature, and the exact bytes `[prev, proof, slot, difficulty, extra]` the signature covers.
    */
  final case class ByronMain(leader: Bytes, signature: ByronSignature, signed: Bytes)

  final case class OpCert(hotVkey: Bytes, sequence: BigInt, kesPeriod: BigInt, sigma: Bytes)

  /** A header is read as the reference reads it, with no budget of items, string bytes or nesting
    * (`decodeFullAnnotator`): what bounds it is the ChainSync codec's 65535 bytes a message
    * (`smallByteLimit`, `Ouroboros/Network/Protocol/ChainSync/Codec.hs:47-51`, ouroboros-network
    * 2fb69829), which the codec here enforces, and after decoding `maxBHSize`
    * (`Shelley/Protocol/EnvelopeChecks.hs:86-88`, ouroboros-consensus 82ecba32). From decoder
    * version 12 a byte string may be chunked and every chunk is an item, so a header within
    * `maxBHSize` can hold any number of items (#749).
    */
  private[ledger] val headerLimits = Cbor.Limits.Unbounded

  extension (h: Shelley)
    /** The single VRF result for Praos headers; `None` for TPraos. */
    def praosVrf: Option[VrfCert] = h.vrf match
      case HeaderVrf.Praos(r) => Some(r)
      case _                  => None

  /** Decode a ChainSync node-to-node header envelope `[era, payload]`. */
  def fromEnvelope(
      envelope: Bytes,
      byronEpochSlots: BigInt
  ): Either[ChainError, Header] =
    NtNHeader.codec
      .decode(envelope)
      .leftMap(e => fail(LedgerDecodeError.Protocol(e)))
      .flatMap(fromNtN(_, byronEpochSlots))

  def fromNtN(
      h: NtNHeader,
      byronEpochSlots: BigInt
  ): Either[ChainError, Header] =
    h.byron match
      case Some(info) =>
        for
          hint <- byronSizeHint(info.sizeHint)
          header <- byronFromRaw(h.raw, info.boundary, byronEpochSlots)
        yield header.withSizeHint(hint)
      case None => fromRaw(h.era, h.raw)

  /** The native SizeInBytes is a Word32; keep an invalid outer wrapper out of a sized header. */
  private[ledger] def byronSizeHint(
      value: BigInt
  ): Either[ChainError, Unsigned[Unsigned.Width.W32]] =
    Unsigned
      .from[Unsigned.Width.W32](value)
      .toRight(
        fail(LedgerDecodeError.Protocol(ProtocolError.SizeHint(value)))
      )

  /** Decode a Shelley-based raw header `[header_body, kes_signature]` of the given era, as the
    * reference reads it at the era's decoder version (`eraDecoder`, consensus 82ecba32
    * `Shelley/Ledger/Block.hs:340-350`).
    *
    * A Praos header (Babbage, Conway) and its body are `RecD` records
    * (`libs/cardano-protocol/src/Cardano/Protocol/Praos/BlockHeader.hs:203-229`, cardano-ledger
    * f649f975): a definite list or a detached one, `9f ff` with the fields after it, whose fields
    * are read by [[DetachedRecords]] ([[CborRead.record]]); its operational certificate and
    * protocol version are `CBORGroup` lists (`decodeRecordNamed`, `Binary/Group.hs:27-35`),
    * definite or indefinite. A TPraos header (Shelley to Alonzo) and its body are read by
    * `decodeRecordNamed` (`TPraos/BlockHeader.hs:193-241`), definite or indefinite, never detached.
    * A VRF certificate is a definite list in both (`enforceSize`, `Decoding/DecCBOR.hs:568-573`).
    * The body size is a `Word32`, the protocol version a `Version` (a `Word32` up to
    * [[DecoderVersion.MaxVersion]], `decodeVersion`, `Decoding/Decoder.hs:462-463`) and a `Word32`.
    * The hash is over `raw`.
    */
  def fromRaw(era: Era, raw: Bytes): Either[ChainError, Header.Shelley] =
    for
      _ <- Either.cond(era != Era.Byron, (), fail(LedgerDecodeError.ByronLayout))
      header <- DetachedRecords.read(raw, headerLimits, DetachedRecords.Shapes.header(era))(e =>
        fail(LedgerDecodeError.Syntax(e))
      )(read(era, raw, _))
    yield header

  private def read(era: Era, raw: Bytes, root: Node): Either[ChainError, Header.Shelley] =
    val strings = ByteStrings.of(DecoderVersion.of(era))
    val list: (Node, Int) => Either[ChainError, Vector[Node]] =
      if era.usesTPraos then array else record
    for
      outer <- list(root, 2)
      kes <- bytes(outer(1), 448, strings)
      body <- list(outer(0), if era.usesTPraos then 15 else 10)
      blockNo <- uint(body(0))
      slot <- uint(body(1))
      prev <- optHash(body(2), strings)
      issuer <- bytes(body(3), 32, strings)
      vrfVkey <- bytes(body(4), 32, strings)
      layout <- if era.usesTPraos then tpraosTail(body, strings) else praosTail(body, strings)
      (vrf, bodySize, bodyHash, opCert, pv) = layout
      fields = HeaderBodyEncoding.Fields(
        blockNo,
        slot,
        prev,
        issuer,
        vrfVkey,
        vrf,
        bodySize,
        bodyHash,
        opCert,
        pv
      )
    yield Shelley(
      era,
      raw,
      HeaderBodyEncoding.encode(fields),
      Blake2b.hash256.hash(raw),
      blockNo,
      slot,
      prev,
      issuer,
      vrfVkey,
      vrf,
      bodySize,
      bodyHash,
      opCert,
      pv,
      kes
    )

  private type Tail = (HeaderVrf, BigInt, Bytes, OpCert, (BigInt, BigInt))

  /** TPraos tail: nonce_vrf, leader_vrf, size, hash, hot_vkey, seq, kes_period, sigma, major,
    * minor.
    */
  private def tpraosTail(body: Vector[Node], strings: ByteStrings): Either[ChainError, Tail] =
    for
      nonce <- vrfCert(body(5), strings)
      leader <- vrfCert(body(6), strings)
      size <- word32(body(7), DecodeField.BlockBodySize)
      hash <- bytes(body(8), 32, strings)
      hot <- bytes(body(9), 32, strings)
      seq <- uint(body(10))
      period <- uint(body(11))
      sigma <- bytes(body(12), 64, strings)
      pv <- protocolVersion(body(13), body(14))
    yield (
      HeaderVrf.TPraos(nonce, leader),
      size,
      hash,
      OpCert(hot, seq, period, sigma),
      pv
    )

  /** Praos tail: vrf_result, size, hash, operational_cert, protocol_version. */
  private def praosTail(body: Vector[Node], strings: ByteStrings): Either[ChainError, Tail] =
    for
      vrf <- vrfCert(body(5), strings)
      size <- word32(body(6), DecodeField.BlockBodySize)
      hash <- bytes(body(7), 32, strings)
      oc <- array(body(8), 4)
      hot <- bytes(oc(0), 32, strings)
      seq <- uint(oc(1))
      period <- uint(oc(2))
      sigma <- bytes(oc(3), 64, strings)
      version <- array(body(9), 2)
      pv <- protocolVersion(version(0), version(1))
    yield (HeaderVrf.Praos(vrf), size, hash, OpCert(hot, seq, period, sigma), pv)

  /** `ProtVer` (`BaseTypes.hs:211-244`): the major a `Version` (`decodeVersion`, a `Word32` that
    * `mkVersion32` takes up to [[DecoderVersion.MaxVersion]], `Binary/Version.hs:111-124`), the
    * minor a `Word32`.
    */
  private[ledger] def protocolVersion(
      major: Node,
      minor: Node
  ): Either[ChainError, (BigInt, BigInt)] =
    for
      ma <- word32(major, DecodeField.Major)
      _ <- Either.cond(
        ma <= DecoderVersion.MaxVersion,
        (),
        fail(
          LedgerDecodeError.NumberOutOfRange(DecodeField.Major, ma, 0, DecoderVersion.MaxVersion)
        )
      )
      mi <- word32(minor, DecodeField.Minor)
    yield (ma, mi)

  /** `decodeWord32`: an unsigned integer of any head width whose value fits 32 bits. */
  private[ledger] def word32(n: Node, site: DecodeSite): Either[ChainError, BigInt] =
    Unsigned.read[Unsigned.Width.W32](n, site).bimap(fail, _.value)

  private[ledger] def vrfCert(n: Node): Either[ChainError, VrfCert] =
    vrfCert(n, ByteStrings.Chunked)

  /** `DecCBOR (CertifiedVRF v a)` (`Decoding/DecCBOR.hs:568-573`): `enforceSize`, a definite list
    * of two (`decodeListLen`, `Decoding/Decoder.hs:1403-1404`), of the output and the proof. The
    * output is `OutputVRF <$> decCBOR` (`:564-565`), a byte string of any length; the proof is
    * `decodeFixedSized`, 80 bytes. An output of another length than the proof's hash is refused by
    * the VRF check (`verifyCertified` compares the two, cardano-crypto-class 2.5.1.0
    * `Cardano/Crypto/VRF/Class.hs:359-369`), as a proof that does not verify is (#712).
    */
  private[ledger] def vrfCert(n: Node, strings: ByteStrings): Either[ChainError, VrfCert] =
    for
      _ <- CborRead.definite(n, DecodeField.Header).leftMap(fail)
      xs <- array(n, 2)
      out <- CborRead.bytes(xs(0), DecodeField.Header)(using strings).leftMap(fail)
      proof <- bytes(xs(1), 80, strings)
    yield VrfCert(out, proof)

  /** Byron header. Main: `[magic, prev, bodyProof,
    * [[epoch, slot], pubkey, [difficulty], sig], extra]`; boundary: `[magic, prev, bodyProof, [epoch, [difficulty]],
    * extra]`. The block hash is Blake2b-256 of `0x82 subtag header` (the CBOR pair
    * `[subtag, header]`).
    *
    * Its byte strings are read at Byron's decoder version 1 (`byronProtVer`,
    * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Version.hs:162-163`, cardano-ledger
    * f649f975), where `decodeBytes` takes a definite string only (`Decoder.hs:350-373`): the
    * previous hash, the leader's key and the delegation certificate and signature
    * (`Block/Header.hs:351-374`), a verification key also with its length in the shortest form
    * ([[verificationKey]]), and the body proof ([[byron.ByronTransport.bodyProof]]), whose original
    * bytes are kept.
    */
  def byronFromRaw(
      raw: Bytes,
      boundary: Boolean,
      epochSlots: BigInt
  ): Either[ChainError, Header.UnsizedByron] =
    for
      root <- CborRead.decode(raw, headerLimits).leftMap(fail)
      h <- array(root, 5)
      prev <- bytes(h(1), 32, ByteStrings.Definite)
      magic <- uint(h(0))
      proof <- ByronTransport
        .bodyProof(h(2), boundary)
        .leftMap(fail)
        .as(Bytes(h(2).original.value))
      consensus <- array(h(3), if boundary then 2 else 4)
      parsed <-
        if boundary then
          for
            epoch <- uint(consensus(0))
            diff <- array(consensus(1), 1)
            no <- uint(diff(0))
          yield (epoch, BigInt(0), no, None, ByronVersion.zero)
        else
          for
            slotId <- array(consensus(0), 2)
            epoch <- uint(slotId(0))
            inEpoch <- uint(slotId(1))
            leader <- verificationKey(consensus(1))
            diff <- array(consensus(2), 1)
            no <- uint(diff(0))
            signature <- byronSignature(consensus(3))
            extra <- array(h(4), 4)
            version <- ByronVersion
              .decode(extra(0))
              .leftMap(e => fail(LedgerDecodeError.Version(e)))
            signed = Bytes(
              Vector(0x85.toByte) ++ Vector(h(1), h(2), consensus(0), consensus(2), h(4))
                .flatMap(_.original.value)
            )
          yield (epoch, inEpoch, no, Some(ByronMain(leader, signature, signed)), version)
      (epoch, inEpoch, no, main, version) = parsed
    yield Header.UnsizedByron(
      raw,
      Blake2b.hash256.hash(
        Bytes(Vector(0x82.toByte, (if boundary then 0 else 1).toByte) ++ raw.value)
      ),
      boundary,
      epoch,
      inEpoch,
      epochSlots,
      no,
      Some(prev),
      magic,
      proof,
      main,
      version
    )

  /** A Byron `VerificationKey`: 64 bytes read with `decodeBytesCanonical` (`decCBORXPub`,
    * `eras/byron/crypto/src/Cardano/Crypto/Signing/VerificationKey.hs:96-97`, cardano-ledger
    * f649f975), so the length is in its shortest form. A `Signature` reads plain `decodeBytes`
    * (`Signing/Signature.hs:129-130`).
    */
  private def verificationKey(n: Node): Either[ChainError, Bytes] =
    ByronTransport.canonicalBytes(n, DecodeField.Header).leftMap(fail) *>
      bytes(n, 64, ByteStrings.Definite)

  /** `[2, [[epoch, issuer, delegate, cert], sig]]`: heavyweight delegation, the only kind. */
  private def byronSignature(n: Node): Either[ChainError, ByronSignature] =
    for
      outer <- array(n, 2)
      kind <- uint(outer(0))
      _ <- Either.cond(
        kind == 2,
        (),
        fail(LedgerDecodeError.UnknownTag(DecodeField.ByronBlockSignature, kind))
      )
      proxy <- array(outer(1), 2)
      psk <- array(proxy(0), 4)
      epoch <- uint(psk(0))
      issuer <- verificationKey(psk(1))
      delegate <- verificationKey(psk(2))
      cert <- bytes(psk(3), 64, ByteStrings.Definite)
      sig <- bytes(proxy(1), 64, ByteStrings.Definite)
    yield ByronSignature.Delegated(epoch, issuer, delegate, cert, sig)

  private[ledger] def optHash(n: Node): Either[ChainError, Option[Bytes]] =
    optHash(n, ByteStrings.Chunked)

  private[ledger] def optHash(
      n: Node,
      strings: ByteStrings
  ): Either[ChainError, Option[Bytes]] = n.value match
    case Value.Null => Right(None)
    case _          => bytes(n, 32, strings).map(Some(_))

  private[ledger] def fail(reason: LedgerDecodeError): ChainError =
    ChainError.Decode(DecodeField.Header, reason)
  private[ledger] def array(n: Node, size: Int): Either[ChainError, Vector[Node]] =
    CborRead.arrayN(n, size, DecodeField.Header).leftMap(fail)
  private[ledger] def record(n: Node, size: Int): Either[ChainError, Vector[Node]] =
    CborRead.record(n, size, DecodeField.Header).leftMap(fail)
  private[ledger] def uint(n: Node): Either[ChainError, BigInt] = n.value match
    case Value.UInt(v) => Right(v)
    case _ =>
      Left(
        fail(
          LedgerDecodeError.Expected(DecodeField.Header, LedgerDecodeError.Shape.Unsigned, n.value)
        )
      )
  // Shelley headers read a byte string in the form of the era's own decoder version, as the reference
  // does. Two decoder paths, both bottoming out in `decodeBytes`, which refuses an indefinite string
  // below decoder version 12 (`Decoder.hs:350-373`, cardano-ledger f649f975): the fixed-size fields —
  // the 448-byte KES signature and the VRF certificate's 64-byte output and 80-byte proof — use
  // `decodeFixedSized`, which is `rawDecodeFixedSized =<< decodeBytes` (`Decoder.hs:375-376`); the
  // issuer key, the VRF key, the previous hash and the body hash use their own `DecCBOR` instances —
  // `DecCBOR (VerKeyDSIGN)` (`DecCBOR.hs:472`) and `DecCBOR (Hash h a)` through
  // `DecCBOR ByteString = decodeBytes` (`DecCBOR.hs:353, 492-496`). A Shelley header is serialised and
  // decoded at the era's lowest protocol version — the header's own codec at ouroboros-consensus
  // 82ecba32: `Block.hs:337` is `encodeShelleyHeader = toEraCBOR @era`, and `:346` is the
  // `eraDecoder @era` inside `decodeShelleyHeader` — 2..9 from Shelley to Conway, so `Header.fromRaw`
  // passes `ByteStrings.of(DecoderVersion.of(era))` and every era Scarab reads takes a definite string.
  //
  // The policy-less forms (`bytes(n, size)`, `optHash(n)`, `vrfCert(n)`) keep the policy this file had
  // before #437: `Chunked`. They serve `LeiosBlock`, which #437 did not measure (#570 Leios). The
  // Byron header reads at decoder version 1, `Definite` (#574). Evidence: #437.
  private[ledger] def bytes(n: Node, size: Int): Either[ChainError, Bytes] =
    bytes(n, size, ByteStrings.Chunked)

  private[ledger] def bytes(
      n: Node,
      size: Int,
      strings: ByteStrings
  ): Either[ChainError, Bytes] =
    CborRead.bytesN(n, size, DecodeField.Header)(using strings).leftMap(fail)
