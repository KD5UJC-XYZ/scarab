// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.*
import ProtocolWire.attempt

/** A node-to-node ChainSync header as carried by the hard-fork combinator: `[eraIndex, payload]`.
  * Shelley-based eras carry `tag24(headerBytes)`; Byron carries
  * `[[subtag, blockSizeHint], tag24(headerBytes)]` with subtag 0 = epoch-boundary, 1 = main block.
  * `raw` is the exact inner header encoding. Nothing inside it is validated here.
  */
final case class NtNHeader(era: Era, raw: Bytes, byron: Option[NtNHeader.ByronInfo])

object NtNHeader:
  /** Byron wrapper: whether the block is an epoch-boundary block, and the size hint. The hint is a
    * `SizeInBytes`, a `Word32` in the reference (`newtype SizeInBytes = SizeInBytes {
    * getSizeInBytes :: Word32 }`, ouroboros-network `Ouroboros/Network/SizeInBytes.hs`), written
    * and read with `encodeWord32` / `decodeWord32` (`encodeDiskDepIx` / `decodeDiskDepIx` of the
    * Byron `NestedCtxt`, ouroboros-consensus `Ouroboros/Consensus/Byron/Node/Serialisation.hs`).
    * Both directions here take exactly the `Word32` range.
    */
  final case class ByronInfo(boundary: Boolean, sizeHint: BigInt)

  private val MaxHeader = 65535
  private val MaxSizeHint = BigInt(0xffffffffL)

  val codec: PayloadCodec[NtNHeader] = new PayloadCodec[NtNHeader]:
    def encode(value: NtNHeader): Either[ProtocolError, Bytes] = attempt {
      val w = new ChainSyncWire.Writer(Limits())
      w.head(4, 2)
      w.head(0, value.era.index)
      (value.era, value.byron) match
        case (Era.Byron, Some(info)) =>
          w.head(4, 2)
          w.head(4, 2)
          w.head(0, if info.boundary then 0 else 1)
          if info.sizeHint < 0 || info.sizeHint > MaxSizeHint then
            bad(ProtocolError.SizeHint(info.sizeHint))
          w.head(0, info.sizeHint.toLong)
        case (Era.Byron, None) => bad(ProtocolError.ByronHeaderRequiresItsWrapper)
        case (_, None)         => ()
        case (_, Some(_))      => bad(ProtocolError.OnlyByronHeadersCarryAWrapper)
      w.head(6, 24)
      w.head(2, value.raw.size)
      w.raw(value.raw)
      val out = w.result
      get(validateItem(out))
      out
    }

    def decode(bytes: Bytes): Either[ProtocolError, NtNHeader] =
      validateItem(bytes).flatMap { _ =>
        attempt {
          val r = new ChainSyncWire.Reader(bytes, Limits())
          if r.arrayLength() != 2 then bad(ProtocolError.ExpectedEraHeader)
          val era = Era.fromIndex(r.uint().value).getOrElse(bad(ProtocolError.UnknownEraIndex))
          val header =
            if era == Era.Byron then
              if r.arrayLength() != 2 then bad(ProtocolError.ExpectedByronSubtagSizeHeader)
              if r.arrayLength() != 2 then bad(ProtocolError.ExpectedByronSubtagSize)
              val subtag = r.uint().value
              if subtag != 0 && subtag != 1 then bad(ProtocolError.UnknownByronSubtag)
              val hint = r.uint().value
              if hint > MaxSizeHint then bad(ProtocolError.SizeHint(hint))
              if r.tag() != 24 then bad(ProtocolError.ExpectedTag24Header)
              NtNHeader(era, r.bytes(MaxHeader), Some(ByronInfo(subtag == 0, hint)))
            else
              if r.tag() != 24 then bad(ProtocolError.ExpectedTag24Header)
              NtNHeader(era, r.bytes(MaxHeader), None)
          if r.position != bytes.size then bad(ProtocolError.TrailingHeaderBytes)
          header
        }
      }
