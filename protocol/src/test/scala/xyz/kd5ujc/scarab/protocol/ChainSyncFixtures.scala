// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.*

/** These types attest only to bounded serialization envelopes (NtC block fixtures; the era-aware
  * NtN header codec is [[NtNHeader]]). The tag-24 interior is opaque: even upstream ASCII
  * placeholders are accepted, and no header/block validity is implied.
  */
object ChainSyncFixtures:
  final class OpaqueNtCBlockFixture private[ChainSyncFixtures] (val bytes: Bytes)

  private def envelope(bytes: Bytes): Either[ProtocolError, Unit] =
    validateItem(bytes).flatMap { _ =>
      ProtocolWire.attempt {
        val r = new ChainSyncWire.Reader(bytes, Limits())
        if r.tag() != 24 then bad(ProtocolError.ExpectedTag24Header)
        r.bytes(65535)
        if r.position != bytes.size then bad(ProtocolError.TrailingHeaderBytes)
        ()
      }
    }

  val ntcBlock: PayloadCodec[OpaqueNtCBlockFixture] = new PayloadCodec[OpaqueNtCBlockFixture]:
    def encode(value: OpaqueNtCBlockFixture): Either[ProtocolError, Bytes] = Right(value.bytes)
    def decode(bytes: Bytes): Either[ProtocolError, OpaqueNtCBlockFixture] =
      envelope(bytes).map(_ => new OpaqueNtCBlockFixture(bytes))

  /** Generic test payload, still checked as exactly one bounded CBOR item. */
  val rawItem: PayloadCodec[Bytes] = new PayloadCodec[Bytes]:
    def encode(value: Bytes): Either[ProtocolError, Bytes] = validateItem(value).map(_ => value)
    def decode(bytes: Bytes): Either[ProtocolError, Bytes] = validateItem(bytes).map(_ => bytes)

  /** Cardano fixture hash width is local adapter policy, not generic Point wire syntax. */
  def cardanoPoint(point: Point): Either[ProtocolError, Point] = point match
    case Point.Origin                            => Right(point)
    case Point.Block(_, hash) if hash.size == 32 => Right(point)
    case Point.Block(_, hash) => Left(ProtocolError.CardanoPointHashMustContain32Bytes(hash.size))
