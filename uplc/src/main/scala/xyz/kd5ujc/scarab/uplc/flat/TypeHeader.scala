// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.uplc.DefaultUni

/** The type of a constant as Flat holds it: a list of four-bit tags (`decodeListWith
  * decodeConstant`, `PlutusCore/FlatInstances.hs:118-135`, plutus 1.70.0.0 `507cab8c`), a type
  * written in prefix form, tag 7 applying the type after it to the one after that
  * (`PlutusCore/Default/Universe.hs:1068-1100`). The tags are read whole before they are parsed.
  */
object TypeHeader:
  /** A type of the universe at any kind: `DefaultUni` at the kinds its Flat decoder can produce
    * (`withDecodedUni`, `Universe.hs:1085-1100`; `withApplicable`, `Universe/Core.hs:596-615`).
    */
  enum Kinded:
    /** A type of kind `*`. */
    case Star(uni: DefaultUni)

    /** `DefaultUniProtoList`, of kind `* -> *`. */
    case ListOperator

    /** `DefaultUniProtoArray`, of kind `* -> *`. */
    case ArrayOperator

    /** `DefaultUniProtoPair`, of kind `* -> * -> *`. */
    case PairOperator

    /** `DefaultUniProtoPair` applied to its first argument, of kind `* -> *`. */
    case PairWith(first: DefaultUni)

  /** Why the tags are not one type: each a way for `decodeKindedUni` to return `Nothing`
    * (`Universe/Core.hs:479-492`).
    */
  enum Failure:
    /** A tag with no type (`withDecodedUni`'s `empty`, `Universe.hs:1100`). */
    case UnknownTag(tag: Int)

    /** An application whose function is not an operator expecting a type of kind `*`
      * (`withApplicable`'s `mzero`).
      */
    case NotApplicable(function: Kinded, argument: Kinded)

    /** The tags end before the type does (`peelUniTag` on no tags). */
    case MissingTags

    /** Tags remain after the type (`decodeKindedUni` requires none). */
    case TrailingTags(count: Int)

  private val Apply = 7

  /** The type of each tag that is a type on its own (`Universe.hs:1086-1099`). */
  private def leaf(tag: Int): Option[Kinded] = tag match
    case 0  => Some(Kinded.Star(DefaultUni.Integer))
    case 1  => Some(Kinded.Star(DefaultUni.ByteString))
    case 2  => Some(Kinded.Star(DefaultUni.String))
    case 3  => Some(Kinded.Star(DefaultUni.Unit))
    case 4  => Some(Kinded.Star(DefaultUni.Bool))
    case 5  => Some(Kinded.ListOperator)
    case 6  => Some(Kinded.PairOperator)
    case 8  => Some(Kinded.Star(DefaultUni.Data))
    case 9  => Some(Kinded.Star(DefaultUni.Bls12_381_G1_Element))
    case 10 => Some(Kinded.Star(DefaultUni.Bls12_381_G2_Element))
    case 11 => Some(Kinded.Star(DefaultUni.Bls12_381_MlResult))
    case 12 => Some(Kinded.ArrayOperator)
    case 13 => Some(Kinded.Star(DefaultUni.Value))
    case _  => None

  /** `withApplicable` (`Universe/Core.hs:596-615`): every operator takes an argument of kind `*`. */
  private def applied(function: Kinded, argument: Kinded): Either[Failure, Kinded] =
    (function, argument) match
      case (Kinded.ListOperator, Kinded.Star(a))  => Right(Kinded.Star(DefaultUni.List(a)))
      case (Kinded.ArrayOperator, Kinded.Star(a)) => Right(Kinded.Star(DefaultUni.Array(a)))
      case (Kinded.PairOperator, Kinded.Star(a))  => Right(Kinded.PairWith(a))
      case (Kinded.PairWith(a), Kinded.Star(b))   => Right(Kinded.Star(DefaultUni.Pair(a, b)))
      case _                                      => Left(Failure.NotApplicable(function, argument))

  // An application whose function or argument is still to be parsed.
  private enum Pending:
    case Function
    case Argument(function: Kinded)

  private enum Step:
    case Parse(tags: List[Int])
    case Parsed(kinded: Kinded, rest: List[Int])

  /** `decodeKindedUni` (`Universe/Core.hs:479-482`): the one type the tags spell, parsed on the
    * heap, the function of an application before its argument and both before the check that one
    * applies to the other.
    */
  def parse(tags: Vector[Int]): Either[Failure, Kinded] =
    @tailrec def go(step: Step, pending: List[Pending]): Either[Failure, Kinded] = step match
      case Step.Parse(Nil) => Left(Failure.MissingTags)
      case Step.Parse(Apply :: rest) => go(Step.Parse(rest), Pending.Function :: pending)
      case Step.Parse(tag :: rest) =>
        leaf(tag) match
          case Some(kinded) => go(Step.Parsed(kinded, rest), pending)
          case None         => Left(Failure.UnknownTag(tag))
      case Step.Parsed(kinded, rest) =>
        pending match
          case Nil =>
            if rest.isEmpty then Right(kinded) else Left(Failure.TrailingTags(rest.size))
          case Pending.Function :: below =>
            go(Step.Parse(rest), Pending.Argument(kinded) :: below)
          case Pending.Argument(function) :: below =>
            applied(function, kinded) match
              case Right(result) => go(Step.Parsed(result, rest), below)
              case Left(failure) => Left(failure)
    go(Step.Parse(tags.toList), Nil)

  /** The tags of a type at `at`: a continuation bit before each tag of four bits. */
  def tags(input: Input, at: Long): Either[FlatError, Got[Vector[Int]]] =
    @tailrec def go(from: Long, read: Vector[Int]): Either[FlatError, Got[Vector[Int]]] =
      input.bit(from) match
        case Left(e)                     => Left(e)
        case Right(Got(false, after))    => Right(Got(read, after))
        case Right(Got(true, after)) =>
          input.bits(after, 4) match
            case Left(e)               => Left(e)
            case Right(Got(tag, next)) => go(next, read :+ tag)
    go(at, Vector.empty)

  /** The type at `at` and the position after it: its tags, parsed, of kind `*`
    * (`Flat.hs:148-153`).
    */
  def read(input: Input, at: Long): Either[FlatError, Got[DefaultUni]] =
    tags(input, at).flatMap { case Got(tags, after) =>
      parse(tags) match
        case Left(reason)              => Left(FlatError.UndecodableType(tags, reason))
        case Right(Kinded.Star(uni))   => Right(Got(uni, after))
        case Right(_)                  => Left(FlatError.NonStarType(tags))
    }
