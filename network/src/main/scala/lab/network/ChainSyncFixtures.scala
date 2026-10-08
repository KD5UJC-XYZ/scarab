// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes
import ChainSync.*

/** These types attest only to bounded serialization envelopes. The tag-24 interior is opaque: even
  * upstream ASCII placeholders are accepted, and no header/block validity is implied.
  */
object ChainSyncFixtures:
  final class OpaqueNtNHeaderFixture private[ChainSyncFixtures] (val bytes: Bytes)
  final class OpaqueNtCBlockFixture private[ChainSyncFixtures] (val bytes: Bytes)

  private def envelope(bytes: Bytes, header: Boolean): Either[String, Unit] =
    validateItem(bytes).flatMap { _ =>
      try
        val r = new ChainSyncWire.Reader(bytes, Limits())
        if header then
          if r.arrayLength() != 2 || r.uint().value != 6 then
            bad("only Conway era-index-6 two-element NtN fixture envelope supported")
        if r.tag() != 24 then bad("fixture requires tag24")
        r.bytes(65535)
        if r.position != bytes.size then bad("trailing fixture bytes")
        Right(())
      catch case Failure(message, _) => Left(message)
    }

  val ntnHeader: PayloadCodec[OpaqueNtNHeaderFixture] = new PayloadCodec[OpaqueNtNHeaderFixture]:
    def encode(value: OpaqueNtNHeaderFixture): Either[String, Bytes] = Right(value.bytes)
    def decode(bytes: Bytes): Either[String, OpaqueNtNHeaderFixture] =
      envelope(bytes, true).map(_ => new OpaqueNtNHeaderFixture(bytes))

  val ntcBlock: PayloadCodec[OpaqueNtCBlockFixture] = new PayloadCodec[OpaqueNtCBlockFixture]:
    def encode(value: OpaqueNtCBlockFixture): Either[String, Bytes] = Right(value.bytes)
    def decode(bytes: Bytes): Either[String, OpaqueNtCBlockFixture] =
      envelope(bytes, false).map(_ => new OpaqueNtCBlockFixture(bytes))

  /** Generic test payload, still checked as exactly one bounded CBOR item. */
  val rawItem: PayloadCodec[Bytes] = new PayloadCodec[Bytes]:
    def encode(value: Bytes): Either[String, Bytes] = validateItem(value).map(_ => value)
    def decode(bytes: Bytes): Either[String, Bytes] = validateItem(bytes).map(_ => bytes)

  /** Cardano fixture hash width is local adapter policy, not generic Point wire syntax. */
  def cardanoPoint(point: Point): Either[String, Point] = point match
    case Point.Origin                            => Right(point)
    case Point.Block(_, hash) if hash.size == 32 => Right(point)
    case _ => Left("Cardano fixture point requires 32-byte hash")
