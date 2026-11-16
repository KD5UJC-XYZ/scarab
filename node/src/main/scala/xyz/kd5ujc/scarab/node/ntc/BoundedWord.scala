// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.{DomainError, Rational}

/** The width of a Haskell `Word` type an answer writes a number as. */
enum WordWidth(val bits: Int):
  case W8 extends WordWidth(8)
  case W16 extends WordWidth(16)
  case W32 extends WordWidth(32)
  case W64 extends WordWidth(64)

/** An unsigned integer known to fit the word it was checked against: the writers of the ledger
  * configuration and of the genesis configuration write a number read from the configuration only
  * as one of these ([[CborOut.word]]), so none can be outside the range of its type when it is
  * written.
  */
opaque type BoundedWord = BigInt

object BoundedWord:
  /** `value`, if it is a word of `width`. */
  def of(value: BigInt, width: WordWidth): Either[WordOutOfRange, BoundedWord] =
    Either.cond(
      value.signum >= 0 && value.bitLength <= width.bits,
      value,
      WordOutOfRange(value, width)
    )

  extension (w: BoundedWord) def value: BigInt = w

/** A number outside the range of the word it is read and written as. */
final case class WordOutOfRange(value: BigInt, width: WordWidth) extends DomainError:
  def message: String = s"$value is not a word of ${width.bits} bits"

/** A ratio known to be two `Word64` s, the numerator unsigned and the denominator positive, as a
  * bounded ratio holds it (`BoundedRatio b Word64`, `BaseTypes.hs:294`, cardano-ledger f649f975):
  * what the writers write a ratio read from the configuration as ([[CborOut.ratio]]).
  */
opaque type WordRatio = Rational

object WordRatio:
  private val limit = BigInt(1) << 64

  /** `r`, if both its numbers are `Word64`s and its denominator is positive. */
  def of(r: Rational): Either[RatioOutOfRange, WordRatio] =
    Either.cond(
      r.numerator.signum >= 0 && r.numerator < limit && r.denominator.signum > 0 &&
        r.denominator < limit,
      r,
      RatioOutOfRange(r)
    )

  extension (r: WordRatio)
    def numerator: BigInt = r.numerator
    def denominator: BigInt = r.denominator

/** A ratio outside the range of a bounded ratio of two `Word64`s. */
final case class RatioOutOfRange(value: Rational) extends DomainError:
  def message: String = s"$value is not a ratio of two 64-bit words"

import BoundedWord.value

extension (out: CborOut)
  /** A word, written as the unsigned integer it is checked to be. */
  def word(n: BoundedWord): CborOut = out.uint(n.value)

  /** A bounded ratio, tag 30 and `[numerator, denominator]`. */
  def ratio(r: WordRatio): CborOut = out.rational(r.numerator, r.denominator)

  /** A bounded ratio as `[numerator, denominator]` without the tag: the active slot coefficient of
    * the Shelley genesis (`activeSlotsCoeffEncCBOR`, `Shelley/Genesis.hs:617-620`, cardano-ledger
    * f649f975).
    */
  def untaggedRatio(r: WordRatio): CborOut = out.array(2).uint(r.numerator).uint(r.denominator)
