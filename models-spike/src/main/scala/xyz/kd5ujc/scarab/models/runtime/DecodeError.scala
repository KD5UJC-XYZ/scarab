// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.runtime

import lab.cbor.{Bytes as CborBytes, Node as CborNode}

type Bytes = CborBytes
type Node = CborNode

/** The result of decoding: a value or a typed, located error. */
type Decoded[+A] = Either[DecodeError, A]

/** Why a CBOR item does not match its CDDL type. `path` lists the CDDL member names from the
  * outermost item inwards; `offset` is the byte offset of the offending item from the start of
  * the innermost enclosing byte string that was decoded as CBOR (the whole input for top-level
  * items). Errors are plain data: nothing here is rendered to text except by [[render]].
  */
final case class DecodeError(kind: DecodeError.Kind, path: List[String], offset: Int, rooted: Boolean = false):
  /** Prefix the path with the member `segment` this error was found under. */
  def at(segment: String): DecodeError = copy(path = segment :: path)

  /** Make the offset relative to `root` (the array position of the document's first byte). */
  def rebased(root: Int): DecodeError =
    if rooted then this
    else
      val kind2 = kind match
        case DecodeError.Kind.NoAlternative(causes) => DecodeError.Kind.NoAlternative(causes.map(_.rebased(root)))
        case other                                  => other
      copy(kind = kind2, offset = offset - root, rooted = true)

  /** The most specific failure: for a choice, the cause that got furthest into the input. */
  def deepest: DecodeError = kind match
    case DecodeError.Kind.NoAlternative(causes) if causes.nonEmpty =>
      causes.map(_.deepest).maxBy(e => (e.path.size, e.offset))
    case _ => this

  def render: String = s"${path.mkString("/")} @$offset: ${DecodeError.describe(kind)}"

object DecodeError:
  enum Kind:
    /** The bytes are not well-formed CBOR (the CBOR layer reports a message only). */
    case Syntax(message: String)
    case TypeMismatch(expected: String, found: String)
    case BadSize(expected: String, actual: Long)
    case OutOfRange(expected: String, actual: BigInt)
    case LiteralMismatch(expected: String, actual: String)
    case BadArity(expected: String, actual: Int)
    case BadTag(expected: BigInt, actual: BigInt)
    case MissingKey(key: String)
    case DuplicateKey(key: String)
    case UnexpectedKey(key: String)
    case NoAlternative(causes: Vector[DecodeError])
    /** A decoder threw. This is a bug in a decoder, reported rather than propagated. */
    case Internal(exception: String)

  def describe(kind: Kind): String = kind match
    case Kind.Syntax(m)                => s"malformed CBOR: $m"
    case Kind.TypeMismatch(e, f)       => s"expected $e, found $f"
    case Kind.BadSize(e, a)            => s"expected size $e, got $a"
    case Kind.OutOfRange(e, a)         => s"expected $e, got $a"
    case Kind.LiteralMismatch(e, a)    => s"expected literal $e, got $a"
    case Kind.BadArity(e, a)           => s"expected $e elements, got $a"
    case Kind.BadTag(e, a)             => s"expected tag $e, got $a"
    case Kind.MissingKey(k)            => s"missing key $k"
    case Kind.DuplicateKey(k)          => s"duplicate key $k"
    case Kind.UnexpectedKey(k)         => s"unexpected key $k"
    case Kind.NoAlternative(cs)        => s"no alternative matched (${cs.size} tried)"
    case Kind.Internal(e)              => s"internal error: $e"

  def at(node: Node, kind: Kind): DecodeError = DecodeError(kind, Nil, node.original.unsafeOffset)

/** A value rejected by a smart constructor (`Hash32.from`): the constraint and what was offered. */
final case class ConstraintViolation(typeName: String, constraint: String, actual: String)

extension [A](d: Decoded[A])
  /** Record that `d` was decoding the member `segment` (only touches the error path). */
  inline def at(inline segment: String): Decoded[A] = d match
    case Left(e) => Left(e.at(segment))
    case right   => right
