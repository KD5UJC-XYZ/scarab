// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}

/** What a node-to-node ChainSync header is when the era may be one this node does not implement:
  * the hard-fork-combinator envelope `[era_index, payload]` of consensus `header.cddl`, kept open
  * for the eras after Conway. [[NtNHeader]] is the closed form for the eras the node follows; a
  * later-era peer would otherwise fail with [[ProtocolError.UnknownEraIndex]]. This envelope names
  * Dijkstra explicitly and retains the index and payload for an unrecognised era.
  *
  * Upstream: `ouroboros-consensus-cardano/cddl/node-to-node/chainsync/header.cddl` (the pinned copy
  * in `models/src/main/cddl/upstream` has the `Dijkstra` case of `header` at index 7).
  */
enum HfcHeader:
  /** An era this node implements, decoded as before. */
  case Supported(header: NtNHeader)

  /** Index 7: `[7, tag24(header)]`, the header of `dijkstra.cddl`. `raw` is the exact header
    * encoding, whose Blake2b-256 digest is the block hash.
    */
  case Dijkstra(raw: Bytes)

  /** An index no era of this node or of the pinned CDDL has; `payload` is the second element of the
    * envelope as it arrived.
    */
  case Unrecognised(index: BigInt, payload: Bytes)

/** Closed set of reasons an envelope is not a header. */
enum HfcHeaderError:
  /** Not CBOR within the local limits. */
  case Syntax(cause: Cbor.Error)

  /** Not a two-element array, or the first element is not an unsigned integer. */
  case NotAnEnvelope

  /** A Dijkstra payload that is not `tag24(bytes)`. */
  case NotEmbeddedCbor

  /** The envelope of a supported era fails [[NtNHeader]]'s decoder; `cause` retains its typed
    * error.
    */
  case Supported(era: Era, cause: ProtocolError)

object HfcHeader:
  /** The index of Dijkstra in the hard-fork combinator's list of eras, after Conway's 6. */
  val DijkstraIndex: Int = 7

  /** The BlockFetch block tag of a Dijkstra block (`[8, block]`), after Conway's 7. */
  val DijkstraBlockTag: Int = 8

  /** Local envelope payload ceiling, shared with [[NtNHeader]]. Consensus header-size validation
    * belongs to the ledger/header layer; this codec only bounds decoding work.
    */
  private val MaxHeader = 65535
  private val limits = Cbor.Limits(MaxHeader + 16, 8, 64, MaxHeader)

  def decode(bytes: Bytes): Either[HfcHeaderError, HfcHeader] =
    for
      root <- Cbor.decode(bytes, limits).leftMap(HfcHeaderError.Syntax(_))
      parts <- root.value match
        // `decodeListLen` of the reference reads a definite length only (see `definite`)
        case Value.Arr(Vector(index, payload)) if definite(root) => Right((index, payload))
        case _ => Left(HfcHeaderError.NotAnEnvelope)
      (index, payload) = parts
      era <- index.value match
        case Value.UInt(i) => Right(i)
        case _             => Left(HfcHeaderError.NotAnEnvelope)
      header <- Era.fromIndex(era) match
        case Some(known) =>
          NtNHeader.codec
            .decode(bytes)
            .bimap(HfcHeaderError.Supported(known, _), Supported(_))
        case None if era == DijkstraIndex => embedded(payload).map(Dijkstra(_))
        case None                         => Right(Unrecognised(era, payload.original))
    yield header

  /** Whether the item was written with a definite length. The decoder of the reference reads the
    * envelope with `decodeListLen` and the embedded header with `decodeBytes`, which take the
    * definite forms only (cborg `Codec.CBOR.Read.tryConsumeListLen` accepts 0x80 to 0x9b and
    * `tryConsumeBytes` 0x40 to 0x5b; the indefinite forms are `decodeListLenIndef` and
    * `decodeBytesIndef`), so `9f 07 .. ff` is no envelope there. Shortest-form integer heads are
    * not demanded (`[7, ..]` as `98 02 18 07 ..` reads), and are not here: the era and the header
    * bytes are what an envelope means, the encoder writes the canonical form as `encodeNS` does.
    */
  private def definite(item: Node): Boolean =
    !item.original.headOption.exists(head => head == 0x9f.toByte || head == 0x5f.toByte)

  /** The bytes of `tag24(bytes)`. */
  private def embedded(payload: Node): Either[HfcHeaderError, Bytes] = payload.value match
    case Value.Tag(t, inner) if t == 24 =>
      inner.value match
        case Value.ByteString(b) if b.size <= MaxHeader && definite(inner) => Right(b)
        case _ => Left(HfcHeaderError.NotEmbeddedCbor)
    case _ => Left(HfcHeaderError.NotEmbeddedCbor)

  /** Encode an envelope for tests and servers. Supported and Dijkstra headers retain their embedded
    * header bytes; an unrecognised payload is decoded and re-encoded with [[Cbor.encode]], so its
    * integer widths and container forms may change. Preserve received wire bytes separately when
    * their exact representation matters.
    */
  def encode(header: HfcHeader): Either[ProtocolError, Bytes] = header match
    case Supported(h) => NtNHeader.codec.encode(h)
    case Dijkstra(raw) =>
      Cbor
        .encode(
          Value.Arr(
            Vector(
              Node(Value.UInt(DijkstraIndex), Bytes.empty),
              Node(
                Value.Tag(24, Node(Value.ByteString(raw), Bytes.empty)),
                Bytes.empty
              )
            )
          )
        )
        .leftMap(ProtocolError.Codec(_))
    case Unrecognised(index, payload) =>
      Cbor
        .decode(payload, limits)
        .flatMap { p =>
          Cbor.encode(Value.Arr(Vector(Node(Value.UInt(index), Bytes.empty), p)))
        }
        .leftMap(ProtocolError.Codec(_))

  /** As a ChainSync payload: any era index is admitted, subject to envelope and resource checks.
    * [[ProtocolError.HfcHeader]] retains the structured [[HfcHeaderError]] cause.
    */
  val codec: ChainSync.PayloadCodec[HfcHeader] = new ChainSync.PayloadCodec[HfcHeader]:
    def encode(value: HfcHeader): Either[ProtocolError, Bytes] = HfcHeader.encode(value)
    def decode(bytes: Bytes): Either[ProtocolError, HfcHeader] =
      HfcHeader.decode(bytes).leftMap(ProtocolError.HfcHeader(_))
end HfcHeader
