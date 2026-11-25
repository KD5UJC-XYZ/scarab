// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes

/** A header body as the reference encodes it: the bytes its KES signature is over, and the bytes
  * the forger signs. The reference checks the signature over `getSignableRepresentation hb =
  * serialize' (pvMajor (hbProtVer hb)) hb`, the decoded header body encoded again
  * (`libs/cardano-protocol/src/Cardano/Protocol/Praos/BlockHeader.hs:113-117`,
  * `TPraos/BlockHeader.hs:170-171`, cardano-ledger f649f975; `KES.verifySignedKES` in
  * `Ouroboros/Consensus/Protocol/Praos.hs:639` and `TPraos/Rules/OCert.hs`), not over the bytes a
  * header was read from: a body with a non-minimal head, an indefinite list its decoders take, or a
  * detached `RecD` record is signed as its canonical form. The header hash stays over the original
  * bytes ([[Header.Shelley.raw]]). A Leios header is signed over its body's original bytes instead
  * ([[LeiosHeader.signable]]).
  *
  * The encoders write definite lists and the shortest heads (`encodeListLen`, `encodeWord64`,
  * `encodeWord32`, `encodeBytes`, `encodeNull`), none of which depends on the version `serialize'`
  * is given, so the header's major protocol version changes no byte here. The header decoding
  * oracle confirms it: every accepted case of `fixtures/reference/header-decoding/cases.jsonl`,
  * majors 2 to 13, signs exactly this encoding (#698).
  */
object HeaderBodyEncoding:

  /** A header body's fields, decoded. `vrf` says the layout: TPraos (Shelley to Alonzo) writes the
    * nonce and leader certificates and then the operational certificate and protocol version
    * inline, 15 items (`TPraos/BlockHeader.hs:175-191`, cardano-ledger f649f975); Praos (Babbage,
    * Conway) one certificate and the operational certificate and protocol version as lists, 10
    * items (`Praos/BlockHeader.hs:176-201`; `CBORGroup`, `Binary/Group.hs:30-31`).
    */
  final case class Fields(
      blockNo: BigInt,
      slot: BigInt,
      prevHash: Option[Bytes],
      issuerVkey: Bytes,
      vrfVkey: Bytes,
      vrf: HeaderVrf,
      bodySize: BigInt,
      bodyHash: Bytes,
      opCert: Header.OpCert,
      protocolVersion: (BigInt, BigInt)
  )

  /** The header body of `fields` in its layout. */
  def encode(fields: Fields): Bytes = join(body(fields))

  /** A Leios header body, `encCBOR` of `HeaderBodyRaw`
    * (`libs/cardano-protocol/src/Cardano/Protocol/ Leios/BlockHeader.hs:278-306`, cardano-ledger
    * 9df9f98c): the Praos fields with the protocol version replaced by the version info, then the
    * certificate flag and the announcement or null, 12 items, every list definite and every head
    * the shortest (`encodeNullStrictMaybe`; `BlockHeaderVersionInfo` and
    * `EbReferencesAnnouncement`, `libs/cardano-ledger-core/src/Cardano/ Ledger/Block.hs:160-161,
    * 178-182`). The reference memoizes this encoding when it forges (`mkHeaderBody`,
    * `BlockHeader.hs:214-220`, through `extendHeaderBodyWithLeios`, consensus 2416493b
    * `Shelley/Protocol/Praos.hs:275-300`), and its KES signature is over those bytes
    * (`getSignableRepresentation = originalBytes`, `BlockHeader.hs:164-165`): what the forger signs
    * and what the header holds.
    */
  def leios(f: LeiosHeaderBody): Bytes =
    val announcement = f.leios.announcement.fold(Vector(Null))(a =>
      Vector(list(2), bytes(a.hash.bytes), uint(BigInt(a.size)))
    )
    join(
      Vector(
        list(12),
        uint(f.blockNo),
        uint(f.slot),
        f.prevHash.fold(Null)(bytes),
        bytes(f.issuerVkey),
        bytes(f.vrfVkey),
        list(2),
        bytes(f.vrf.output),
        bytes(f.vrf.proof),
        uint(f.bodySize),
        bytes(f.bodyHash),
        list(4),
        bytes(f.opCert.hotVkey),
        uint(f.opCert.sequence),
        uint(f.opCert.kesPeriod),
        bytes(f.opCert.sigma),
        list(2),
        uint(f.leios.versionInfo.highestSupportedMajor),
        uint(f.leios.versionInfo.softwareTag),
        if f.leios.blockBodyContainsLeiosCert then True else False
      ) ++ announcement
    )

  private def body(f: Fields): Vector[Array[Byte]] =
    def cert(c: VrfCert) = Vector(list(2), bytes(c.output), bytes(c.proof))
    val opCert =
      Vector(
        bytes(f.opCert.hotVkey),
        uint(f.opCert.sequence),
        uint(f.opCert.kesPeriod),
        bytes(f.opCert.sigma)
      )
    val version = Vector(uint(f.protocolVersion._1), uint(f.protocolVersion._2))
    def start(size: Int) = Vector(
      list(size),
      uint(f.blockNo),
      uint(f.slot),
      f.prevHash.fold(Null)(bytes),
      bytes(f.issuerVkey),
      bytes(f.vrfVkey)
    )
    f.vrf match
      case HeaderVrf.TPraos(nonce, leader) =>
        start(15) ++ cert(nonce) ++ cert(leader) ++ Vector(uint(f.bodySize), bytes(f.bodyHash)) ++
          opCert ++ version
      case HeaderVrf.Praos(result) =>
        start(10) ++ cert(result) ++ Vector(
          uint(f.bodySize),
          bytes(f.bodyHash),
          list(4)
        ) ++ opCert ++
          Vector(list(2)) ++ version

  /** A head of major type `major` and argument `n`, an unsigned integer below `2^64`, in its
    * shortest form.
    */
  private[ledger] def head(major: Int, n: BigInt): Array[Byte] =
    val m = major << 5
    if n < 24 then Array((m | n.toInt).toByte)
    else
      val (code, width) =
        if n < 256 then (24, 1)
        else if n < 65536 then (25, 2)
        else if n < 4294967296L then (26, 4)
        else (27, 8)
      (m | code).toByte +: Array.tabulate[Byte](width)(i => (n >> (8 * (width - 1 - i))).toByte)

  private[ledger] def uint(n: BigInt): Array[Byte] = head(0, n)
  private[ledger] def bytes(b: Bytes): Array[Byte] = head(2, BigInt(b.size)) ++ b.toArray
  private[ledger] def list(n: Int): Array[Byte] = head(4, BigInt(n))
  private[ledger] val Null: Array[Byte] = Array(0xf6.toByte)
  private[ledger] val True: Array[Byte] = Array(0xf5.toByte)
  private[ledger] val False: Array[Byte] = Array(0xf4.toByte)

  /** The parts, one after another, copied once into one array (`Array.concat`, not a generic
    * `flatten`, which would box every byte).
    */
  private[ledger] def join(parts: Vector[Array[Byte]]): Bytes = Bytes.wrap(Array.concat(parts*))
