// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes
import ChainSync.*

/** A node-to-node ChainSync header as carried by the hard-fork combinator: `[eraIndex, payload]`.
  * Shelley-based eras carry `tag24(headerBytes)`; Byron carries
  * `[[subtag, blockSizeHint], tag24(headerBytes)]` with subtag 0 = epoch-boundary, 1 = main block.
  * `raw` is the exact inner header encoding. Nothing inside it is validated here.
  */
final case class NtNHeader(era: Era, raw: Bytes, byron: Option[NtNHeader.ByronInfo])

object NtNHeader:
  /** Byron wrapper: whether the block is an epoch-boundary block, and the size hint. */
  final case class ByronInfo(boundary: Boolean, sizeHint: BigInt)

  private val MaxHeader = 65535

  val codec: PayloadCodec[NtNHeader] = new PayloadCodec[NtNHeader]:
    def encode(value: NtNHeader): Either[String, Bytes] =
      val w = new ChainSyncWire.Writer(Limits())
      try
        w.head(4, 2)
        w.head(0, value.era.index)
        (value.era, value.byron) match
          case (Era.Byron, Some(info)) =>
            w.head(4, 2)
            w.head(4, 2)
            w.head(0, if info.boundary then 0 else 1)
            if info.sizeHint < 0 || info.sizeHint > Int.MaxValue then bad("size hint")
            w.head(0, info.sizeHint.toLong)
          case (Era.Byron, None) => bad("Byron header requires its wrapper")
          case (_, None)         => ()
          case (_, Some(_))      => bad("only Byron headers carry a wrapper")
        w.head(6, 24)
        w.head(2, value.raw.size)
        w.raw(value.raw)
        val out = w.result
        validateItem(out).map(_ => out)
      catch case Failure(message, _) => Left(message)

    def decode(bytes: Bytes): Either[String, NtNHeader] =
      validateItem(bytes).flatMap { _ =>
        try
          val r = new ChainSyncWire.Reader(bytes, Limits())
          if r.arrayLength() != 2 then bad("expected [era, header]")
          val era = Era.fromIndex(r.uint().value).getOrElse(bad("unknown era index"))
          val header =
            if era == Era.Byron then
              if r.arrayLength() != 2 then bad("expected Byron [[subtag, size], header]")
              if r.arrayLength() != 2 then bad("expected Byron [subtag, size]")
              val subtag = r.uint().value
              if subtag != 0 && subtag != 1 then bad("unknown Byron subtag")
              val hint = r.uint().value
              if r.tag() != 24 then bad("expected tag24 header")
              NtNHeader(era, r.bytes(MaxHeader), Some(ByronInfo(subtag == 0, hint)))
            else
              if r.tag() != 24 then bad("expected tag24 header")
              NtNHeader(era, r.bytes(MaxHeader), None)
          if r.position != bytes.size then bad("trailing header bytes")
          Right(header)
        catch case Failure(message, _) => Left(message)
      }
