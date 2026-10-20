// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import scala.compiletime.{constValueTuple, summonAll, summonInline}
import scala.deriving.Mirror
import xyz.kd5ujc.scarab.models.runtime.{Arr, Codec, ConstraintViolation, Decoded, Node, Writer}

/** The Haskell ledger writes a record as a definite array of its fields in declaration order (`Rec`
  * in `Cardano.Ledger.Binary.Coders`, or `encodeListLen n <> ...` by hand). The mirror's records
  * are declared in that order, so one codec shape serves them all, taken from the compiler's
  * `Mirror` of the case class: the arity, the field names (for the path of a decode error) and the
  * way to build and take apart a value.
  *
  * `Record.of[A]((c1, c2, ...))` takes one codec per field, so that a field that is encoded in a
  * particular way (a unit interval, a nullable, a set) says so at the use site; `Record.auto[A]`
  * summons the codec of every field.
  *
  * The codecs are a tuple indexed by the field types (`Tuple.Map[m.MirroredElemTypes, Codec]`), and
  * so is the value of a record ([[Tuple.fromProductTyped]], the compiler's typed accessors);
  * [[Record.Fields]] walks the two together, so each codec is applied at the type of the field it
  * belongs to. No `Codec[Any]` is built and nothing is projected unchecked; the bytes, the arity
  * and the error paths are those of the previous implementation.
  */
object Record:
  /** The codec of `A` as a record, from the codec of each field. */
  inline def of[A <: Product](using
      m: Mirror.ProductOf[A]
  )(
      codecs: Tuple.Map[m.MirroredElemTypes, Codec]
  ): Codec[A] =
    val labels = constValueTuple[m.MirroredElemLabels].productIterator.map(_.toString).toVector
    val fields = summonInline[Fields[m.MirroredElemTypes]]
    val arity = fields.arity
    make[A](
      arity,
      (a, out) => fields.encode(Tuple.fromProductTyped(a), codecs, out),
      n =>
        Arr.exact(n, arity).flatMap { items =>
          fields.decode(codecs, items, 0, labels).map(m.fromProduct)
        },
      a => fields.check(Tuple.fromProductTyped(a), codecs, 0, labels)
    )

  /** The codec of `A` as a record, with the implicit codec of every field. */
  inline def auto[A <: Product](using m: Mirror.ProductOf[A]): Codec[A] =
    of[A](using m)(summonAll[Tuple.Map[m.MirroredElemTypes, Codec]])

  /** A record codec from its parts: the instance is made here, not at the inline site, so a
    * derivation does not duplicate an anonymous class.
    */
  private def make[A](
      arity: Int,
      encodeFn: (A, Writer) => Unit,
      decodeFn: Node => Decoded[A],
      checkFn: A => Option[ConstraintViolation]
  ): Codec[A] = new Codec[A]:
    def encode(a: A, out: Writer): Unit =
      out.arrayHead(arity)
      encodeFn(a, out)
    def decode(n: Node): Decoded[A] = decodeFn(n)
    override def check(a: A): Option[ConstraintViolation] = checkFn(a)

  /** The codecs of a record's fields, one per field, in declaration order: the typed walk over the
    * codec tuple and the record's value tuple (both indexed by the field types), so that each codec
    * is applied at the type of the field it belongs to.
    *
    * The instances are resolved when [[Record.of]] is inlined, where the field types are known.
    */
  private[scarab] sealed trait Fields[Vs <: Tuple]:
    def arity: Int

    /** Encode the fields of `values` (the field types, in order) with `codecs`. */
    def encode(values: Vs, codecs: Tuple.Map[Vs, Codec], out: Writer): Unit

    /** Decode the field at `index` and the fields after it, as a tuple in field order. */
    def decode(
        codecs: Tuple.Map[Vs, Codec],
        nodes: Vector[Node],
        index: Int,
        labels: Vector[String]
    ): Decoded[Vs]

    /** The first field, at `index` or after it, that violates its codec's constraint. */
    def check(
        values: Vs,
        codecs: Tuple.Map[Vs, Codec],
        index: Int,
        labels: Vector[String]
    ): Option[ConstraintViolation]

  private[scarab] object NoFields extends Fields[EmptyTuple.type]:
    val arity: Int = 0
    def encode(
        values: EmptyTuple.type,
        codecs: Tuple.Map[EmptyTuple.type, Codec],
        out: Writer
    ): Unit = ()
    def decode(
        codecs: Tuple.Map[EmptyTuple.type, Codec],
        nodes: Vector[Node],
        index: Int,
        labels: Vector[String]
    ): Decoded[EmptyTuple.type] = Right(EmptyTuple)
    def check(
        values: EmptyTuple.type,
        codecs: Tuple.Map[EmptyTuple.type, Codec],
        index: Int,
        labels: Vector[String]
    ): Option[ConstraintViolation] = None

  private[scarab] final class OneField[T, Ts <: Tuple](tail: Fields[Ts]) extends Fields[T *: Ts]:
    val arity: Int = 1 + tail.arity

    def encode(values: T *: Ts, codecs: Tuple.Map[T *: Ts, Codec], out: Writer): Unit =
      codecs.head.encode(values.head, out)
      tail.encode(values.tail, codecs.tail, out)

    def decode(
        codecs: Tuple.Map[T *: Ts, Codec],
        nodes: Vector[Node],
        index: Int,
        labels: Vector[String]
    ): Decoded[T *: Ts] =
      codecs.head.decode(nodes(index)) match
        case Right(value) => tail.decode(codecs.tail, nodes, index + 1, labels).map(value *: _)
        case Left(error)  => Left(error.at(labels(index)))

    def check(
        values: T *: Ts,
        codecs: Tuple.Map[T *: Ts, Codec],
        index: Int,
        labels: Vector[String]
    ): Option[ConstraintViolation] =
      codecs.head
        .check(values.head)
        .map(_.at(labels(index)))
        .orElse(tail.check(values.tail, codecs.tail, index + 1, labels))

  private[scarab] given noFields: Fields[EmptyTuple.type] = NoFields

  private[scarab] given oneField[T, Ts <: Tuple](using tail: Fields[Ts]): Fields[T *: Ts] =
    new OneField[T, Ts](tail)
