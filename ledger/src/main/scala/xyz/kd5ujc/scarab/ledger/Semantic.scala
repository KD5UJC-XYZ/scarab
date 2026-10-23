// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.{Bytes, Node, Rational, Value}

/** The meaning of a CBOR item that a decoder of the reference reads into a typed value, without the
  * choices of the encoding: integer and length widths, definite or indefinite containers and
  * strings, the order of map entries, and the form of a rational (`[2, 4]` is `[1, 2]`).
  *
  * Scarab keeps some decoded fields as their original encodings (the relays and metadata of a pool,
  * the fields of a parameter update) so that hashes, snapshots and presentation stay over the
  * original bytes. The reference compares the decoded values (`Ord`), so an ordered set refuses two
  * elements whose values agree under any encoding. [[Semantic]] is the comparison key for those
  * fields and is never written or hashed.
  */
private[ledger] enum Semantic:
  case Num(value: BigInt)
  case Str(value: Bytes)
  case Txt(value: String)
  case Items(items: Vector[Semantic])

  /** The entries as a set; `size` keeps a repeated key distinct from a set of fewer entries. */
  case Entries(pairs: Set[(Semantic, Semantic)], size: Int)
  case Tagged(tag: BigInt, inner: Semantic)
  case Ratio(value: Rational)
  case Flag(value: Boolean)
  case Absent

  /** A form that is compared by its original bytes: simple values, floats, and nesting deeper than
    * any field of a protocol structure.
    */
  case Opaque(original: Bytes)

private[ledger] object Semantic:
  /** Parameter structures are at most four levels deep (cost models, thresholds, prices). Deeper
    * input is not a value the reference accepts, and is kept opaque so that the recursion here is
    * bounded whatever the depth of the input (ADR 0011).
    */
  private val MaxDepth = 8

  def of(n: Node): Semantic = of(n, 0)

  /** The meaning of encoded bytes: empty bytes (the empty relay sequence Scarab keeps as the empty
    * value) are the empty sequence; bytes that are not CBOR are opaque.
    */
  def ofBytes(raw: Bytes): Semantic =
    if raw.isEmpty then Items(Vector.empty)
    else CborRead.decode(raw).fold(_ => Opaque(raw), of)

  private def of(n: Node, depth: Int): Semantic =
    if depth > MaxDepth then Opaque(n.original)
    else
      n.value match
        case Value.UInt(v)       => Num(v)
        case Value.NInt(v)       => Num(v)
        case Value.ByteString(v) => Str(v)
        case Value.Text(v)       => Txt(v)
        case Value.Bool(v)       => Flag(v)
        case Value.Null          => Absent
        case Value.Arr(xs)       => Items(xs.map(of(_, depth + 1)))
        case Value.Map(kvs) =>
          Entries(kvs.map((k, v) => (of(k, depth + 1), of(v, depth + 1))).toSet, kvs.size)
        case Value.Tag(t, _) if t == 30 => ratio(n)
        case Value.Tag(t, inner)        => Tagged(t, of(inner, depth + 1))
        case _                          => Opaque(n.original)

  /** A tag 30 the reference decodes (`CborRead.rational`, any sign or integer form, reduced, within
    * the `Word64` range of every `BoundedRatio`) is its reduced value, so `30([1, 2])`,
    * `30([-1, -2])` and `30([2^64, 2^65])` are one key; any other is opaque, and the reference
    * refuses it in every field.
    */
  private def ratio(n: Node): Semantic =
    CborRead
      .ratio(n, "rational")
      .toOption
      .filter(CborRead.fits(_, CborRead.Bound.NonNegative))
      .fold(Opaque(n.original))(Ratio(_))
