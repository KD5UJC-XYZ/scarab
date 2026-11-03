// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes

/** A header body as the reference encodes it: the bytes its KES signature is over, and the bytes
  * the forger signs. The reference checks the signature over `getSignableRepresentation hb =
  * serialize' (pvMajor (hbProtVer hb)) hb`, the decoded header body encoded again
  * (`libs/cardano-protocol/src/Cardano/Protocol/Praos/BlockHeader.hs:113-117`,
  * `TPraos/BlockHeader.hs:170-171`, `Leios/BlockHeader.hs:139-142`, cardano-ledger f649f975;
  * `KES.verifySignedKES` in `Ouroboros/Consensus/Protocol/Praos.hs:639` and
  * `TPraos/Rules/OCert.hs`), not over the bytes a header was read from: a body with a non-minimal
  * head, an indefinite list its decoders take, or a detached `RecD` record is signed as its
  * canonical form. The header hash stays over the original bytes ([[Header.Shelley.raw]]).
  *
  * The encoders write definite lists and the shortest heads (`encodeListLen`, `encodeWord64`,
  * `encodeWord32`, `encodeBytes`, `encodeNull`, `encodeBool`), none of which depends on the version
  * `serialize'` is given, so the header's major protocol version changes no byte here. The header
  * decoding oracle confirms it: every accepted case of
  * `fixtures/reference/header-decoding/cases.jsonl`, majors 2 to 13, signs exactly this encoding
  * (#698).
  */
object HeaderBodyEncoding:

  /** A header body's fields, decoded. `vrf` says the layout: TPraos (Shelley to Alonzo) writes the
    * nonce and leader certificates and then the operational certificate and protocol version
    * inline, 15 items (`TPraos/BlockHeader.hs:175-191`); Praos (Babbage, Conway) one certificate
    * and the operational certificate and protocol version as lists, 10 items
    * (`Praos/BlockHeader.hs:176-201`; `CBORGroup`, `Binary/Group.hs:30-31`).
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
  def encode(fields: Fields): Bytes = join(body(fields, extra = 0))

  /** A Leios header body: the Praos body with the certificate flag and the endorser block
    * announcement, null or the `RecD` record of its hash and size, after it, 12 items
    * (`Leios/BlockHeader.hs:198-227`; `EbAnnouncement`, `:92-97`).
    */
  def leios(
      fields: Fields,
      containsCertificate: Boolean,
      announcement: Option[(Bytes, Long)]
  ): Bytes =
    join(
      body(fields, extra = 2) ++ Vector(bool(containsCertificate)) ++
        announcement.fold(Vector(Null))((hash, size) =>
          Vector(list(2), bytes(hash), uint(BigInt(size)))
        )
    )

  private def body(f: Fields, extra: Int): Vector[Array[Byte]] =
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
      list(size + extra),
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
  private def head(major: Int, n: BigInt): Array[Byte] =
    val m = major << 5
    if n < 24 then Array((m | n.toInt).toByte)
    else
      val (code, width) =
        if n < 256 then (24, 1)
        else if n < 65536 then (25, 2)
        else if n < 4294967296L then (26, 4)
        else (27, 8)
      (m | code).toByte +: Array.tabulate[Byte](width)(i => (n >> (8 * (width - 1 - i))).toByte)

  private def uint(n: BigInt): Array[Byte] = head(0, n)
  private def bytes(b: Bytes): Array[Byte] = head(2, BigInt(b.size)) ++ b.toArray
  private def list(n: Int): Array[Byte] = head(4, BigInt(n))
  private def bool(b: Boolean): Array[Byte] = Array((if b then 0xf5 else 0xf4).toByte)
  private val Null: Array[Byte] = Array(0xf6.toByte)
  private def join(parts: Vector[Array[Byte]]): Bytes = Bytes.wrap(parts.flatten.toArray)
