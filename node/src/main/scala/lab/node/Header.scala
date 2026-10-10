// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.network.ChainSync

/** Decoded Babbage/Conway (Praos) block header. `raw` is the exact signed header encoding; its
  * Blake2b-256 digest is the block hash.
  */
final case class Header(
    raw: Bytes,
    hash: Bytes,
    blockNo: BigInt,
    slot: BigInt,
    prevHash: Option[Bytes],
    issuerVkey: Bytes,
    vrfVkey: Bytes,
    vrfOutput: Bytes,
    vrfProof: Bytes,
    bodySize: BigInt,
    bodyHash: Bytes,
    opCert: Header.OpCert,
    protocolVersion: (BigInt, BigInt),
    kesSignature: Bytes
):
  def point: ChainSync.Point =
    ChainSync.Point.Block(ChainSync.UInt64.from(slot).toOption.get, hash)
  def prevPoint: Option[Bytes] = prevHash

object Header:
  final case class OpCert(hotVkey: Bytes, sequence: BigInt, kesPeriod: BigInt, sigma: Bytes)

  /** Hard-fork-combinator era index of Conway in node-to-node envelopes. */
  val ConwayEra: BigInt = 6

  private val envelopeLimits = Cbor.Limits(65535, 24, 8192, 65535)
  private val headerLimits = Cbor.Limits(4096, 16, 256, 4096)

  /** Decode a ChainSync node-to-node header envelope `[era, #6.24(bytes .cbor header)]`. */
  def fromEnvelope(envelope: Bytes): Either[ChainError, Header] =
    for
      root <- Cbor.decode(envelope, envelopeLimits).leftMap(fail)
      parts <- array(root, 2)
      era <- uint(parts(0))
      _ <- Either.cond(era == ConwayEra, (), fail(s"era $era is not Conway"))
      raw <- parts(1).value match
        case Value.Tag(n, Node(Value.ByteString(b), _)) if n == 24 => Right(b)
        case _ => Left(fail("expected tag-24 wrapped header bytes"))
      header <- fromRaw(raw)
    yield header

  /** Decode a raw header `[header_body, kes_signature]`. */
  def fromRaw(raw: Bytes): Either[ChainError, Header] =
    for
      root <- Cbor.decode(raw, headerLimits).leftMap(fail)
      outer <- array(root, 2)
      body <- array(outer(0), 10)
      kes <- bytes(outer(1), 448)
      blockNo <- uint(body(0))
      slot <- uint(body(1))
      prev <- body(2).value match
        case Value.Null => Right(None)
        case _          => bytes(body(2), 32).map(Some(_))
      issuer <- bytes(body(3), 32)
      vrfVkey <- bytes(body(4), 32)
      vrf <- array(body(5), 2)
      vrfOutput <- bytes(vrf(0), 64)
      vrfProof <- bytes(vrf(1), 80)
      bodySize <- uint(body(6))
      bodyHash <- bytes(body(7), 32)
      oc <- array(body(8), 4)
      hot <- bytes(oc(0), 32)
      seq <- uint(oc(1))
      kesPeriod <- uint(oc(2))
      sigma <- bytes(oc(3), 64)
      pv <- array(body(9), 2)
      major <- uint(pv(0))
      minor <- uint(pv(1))
    yield Header(
      raw,
      Blake2b.hash256.hash(raw),
      blockNo,
      slot,
      prev,
      issuer,
      vrfVkey,
      vrfOutput,
      vrfProof,
      bodySize,
      bodyHash,
      OpCert(hot, seq, kesPeriod, sigma),
      (major, minor),
      kes
    )

  private def fail(reason: String): ChainError = ChainError.Decode("header", reason)
  private def array(n: Node, size: Int): Either[ChainError, Vector[Node]] = n.value match
    case Value.Arr(xs) if xs.size == size => Right(xs)
    case _                                => Left(fail(s"expected $size-element array"))
  private def uint(n: Node): Either[ChainError, BigInt] = n.value match
    case Value.UInt(v) => Right(v)
    case _             => Left(fail("expected unsigned integer"))
  private def bytes(n: Node, size: Int): Either[ChainError, Bytes] = n.value match
    case Value.ByteString(v) if v.size == size => Right(v)
    case _                                     => Left(fail(s"expected $size-byte string"))
