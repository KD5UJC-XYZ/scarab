// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.runtime

import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.codec.{Bytes as CborBytes, Node as CborNode}

type Bytes = CborBytes
type Node = CborNode

/** The result of decoding: a value or a typed, located error. */
type Decoded[+A] = Either[DecodeError, A]

/** Why a CBOR item does not match its CDDL type. `path` lists the CDDL member names from the
  * outermost item inwards; `offset` is the byte offset of the offending item from the start of the
  * innermost enclosing byte string that was decoded as CBOR (the whole input for top-level items).
  * Errors are plain data: nothing here is rendered to text except by [[render]].
  */
final case class DecodeError(
    kind: DecodeError.Kind,
    path: List[String],
    offset: Int,
    rooted: Boolean = false
) extends DomainError:
  /** Prefix the path with the member `segment` this error was found under. */
  def at(segment: String): DecodeError = copy(path = segment :: path)

  /** Make the offset relative to `root` (the array position of the document's first byte). */
  def rebased(root: Int): DecodeError =
    if rooted then this
    else
      val kind2 = kind match
        case DecodeError.Kind.NoAlternative(causes) =>
          DecodeError.Kind.NoAlternative(causes.map(_.rebased(root)))
        case other => other
      copy(kind = kind2, offset = offset - root, rooted = true)

  /** The most specific failure: for a choice, the cause that got furthest into the input. */
  def deepest: DecodeError = kind match
    case DecodeError.Kind.NoAlternative(causes) if causes.nonEmpty =>
      val best = causes.map(_.deepest).maxBy(e => (e.offset, e.path.size))
      best.copy(path = path ++ best.path)
    case _ => this

  def render: String = s"${path.mkString("/")} @$offset: ${DecodeError.describe(kind)}"

object DecodeError:
  enum Kind:
    case Domain[E <: DomainError](cause: E) extends Kind

    /** The bytes are not well-formed CBOR; retain the codec failure as the cause. */
    case Syntax(cause: xyz.kd5ujc.scarab.codec.Cbor.Error)
    case TypeMismatch(expected: String, found: String)
    case BadSize(expected: String, actual: Long)
    case OutOfRange(expected: String, actual: BigInt)
    case LiteralMismatch(expected: String, actual: String)
    case BadArity(expected: Arity, actual: Int)
    case BadTag(expected: BigInt, actual: BigInt)
    case MissingKey(key: String)
    case DuplicateKey(key: String)
    case UnexpectedKey(key: String)
    case NoAlternative(causes: Vector[DecodeError])

    /** A set holds the same element twice (`rendered` is a short rendering of it). */
    case DuplicateElement(rendered: String)

    /** A value that a smart constructor refuses (the hash length, a non-zero count, ...). */
    case Violation(violation: ConstraintViolation)

    /** A form this decoder does not read (named by `what`) though the format allows it. */
    case Unsupported(what: String)

    /** A decoder threw. This is a bug in a decoder, reported rather than propagated. */
    case Internal(cause: Throwable)

  /** The element count an array or map is required to have. Rendered only by [[describe]]. */
  enum Arity:
    /** Exactly `n` elements. */
    case Exactly(n: Int)

    /** `n` or more elements. */
    case AtLeast(n: Int)

    /** Between `min` and `max` elements; `max` is absent when the occurrence is unbounded. */
    case Range(min: Int, max: Option[Int])

    /** Either `first` or `second` elements. */
    case OneOf(first: Int, second: Int)

  def describe(arity: Arity): String = arity match
    case Arity.Exactly(n)           => n.toString
    case Arity.AtLeast(n)           => s"at least $n"
    case Arity.Range(min, max)      => s"$min..${max.fold("*")(_.toString)}"
    case Arity.OneOf(first, second) => s"$first or $second"

  def describe(kind: Kind): String = kind match
    case Kind.Domain(cause)         => cause.toString
    case Kind.Syntax(m)             => s"malformed CBOR: ${m.message}"
    case Kind.TypeMismatch(e, f)    => s"expected $e, found $f"
    case Kind.BadSize(e, a)         => s"expected size $e, got $a"
    case Kind.OutOfRange(e, a)      => s"expected $e, got $a"
    case Kind.LiteralMismatch(e, a) => s"expected literal $e, got $a"
    case Kind.BadArity(e, a)        => s"expected ${describe(e)} elements, got $a"
    case Kind.BadTag(e, a)          => s"expected tag $e, got $a"
    case Kind.MissingKey(k)         => s"missing key $k"
    case Kind.DuplicateKey(k)       => s"duplicate key $k"
    case Kind.UnexpectedKey(k)      => s"unexpected key $k"
    case Kind.NoAlternative(cs)     => s"no alternative matched (${cs.size} tried)"
    case Kind.DuplicateElement(e)   => s"duplicate element $e"
    case Kind.Violation(v)          => s"${v.typeName} requires ${v.constraint}, got ${v.actual}"
    case Kind.Unsupported(w)        => s"unsupported: $w"
    case Kind.Internal(e)           => s"internal error: $e"

  def at(node: Node, kind: Kind): DecodeError = DecodeError(kind, Nil, node.offset)

/** A value rejected by a smart constructor (`Hash32.from`) or refused by a writer: the constraint
  * and what was offered, and where in the value it is (outermost member first; empty for the value
  * itself).
  */
final case class ConstraintViolation(
    typeName: String,
    constraint: String,
    actual: String,
    path: List[String] = Nil
):
  /** The violation found under the member `segment`. */
  def at(segment: String): ConstraintViolation = copy(path = segment :: path)

  def render: String =
    s"${if path.isEmpty then "" else path.mkString("/") + ": "}$typeName requires $constraint, got $actual"

extension [A](d: Decoded[A])
  /** Record that `d` was decoding the member `segment` (only touches the error path). */
  inline def at(inline segment: String): Decoded[A] = d match
    case Left(e) => Left(e.at(segment))
    case right   => right
