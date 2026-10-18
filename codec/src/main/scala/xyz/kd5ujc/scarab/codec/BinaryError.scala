// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

/** Failures of the length-prefixed snapshot binary representation. */
enum BinaryError:
  case BadMagic
  case UnexpectedEnd(position: Long)
  case LengthExceedsRemaining(length: Long, remaining: Long)
  case VarintTooLong(position: Long)
  case CountExceedsRemaining(count: Long, remaining: Long)
  case TrailingBytes(count: Long)
  case InvalidBoolean(value: Int)
  case IntOutOfRange(value: Long)
  case EmptyBigInt
  case NonPositiveDenominator(value: BigInt)
  case InvalidOptionTag(value: Int)
  case InvalidNativeScriptTag(value: Int)
  case InvalidVariant(typeName: String, index: Long, variants: Int)
  case InvalidKeyHashLength(actual: Int, expected: Int)
  case InvalidByronVersion(field: String, value: BigInt)

  def message: String = this match
    case BadMagic => "bad magic or too short"
    case UnexpectedEnd(_) => "unexpected end of data"
    case LengthExceedsRemaining(n, _) => s"length $n exceeds remaining data"
    case VarintTooLong(_) => "varint too long"
    case CountExceedsRemaining(n, _) => s"count $n exceeds remaining data"
    case TrailingBytes(n) => s"$n trailing bytes"
    case InvalidBoolean(n) => s"invalid boolean $n"
    case IntOutOfRange(n) => s"int out of range: $n"
    case EmptyBigInt => "empty BigInt"
    case NonPositiveDenominator(_) => "non-positive denominator"
    case InvalidOptionTag(n) => s"invalid option tag $n"
    case InvalidNativeScriptTag(n) => s"invalid native script tag $n"
    case InvalidVariant(name, n, _) => s"$name: invalid variant $n"
    case InvalidKeyHashLength(n, expected) => s"key hash of $n bytes, expected $expected"
    case InvalidByronVersion(field, value) => s"Byron $field out of range: $value"
