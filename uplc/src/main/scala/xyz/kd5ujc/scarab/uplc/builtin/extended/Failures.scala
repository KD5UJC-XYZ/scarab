// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import xyz.kd5ujc.scarab.codec.Bytes

/** The log lines of a failure. The reference emits each line separately (`emit`,
  * `PlutusCore/Builtin/Result.hs:150-152`, plutus 1.70.0.0 `507cab8c`) and then fails with
  * `builtinResultFailure` (231-232); `fail` emits one line.
  */
trait LogLines:
  def lines: Vector[String]

/** Haskell's `show` of a byte string unpacked to `[Word8]`, as the `Value` builtins render a key:
  * `[170,187]`.
  */
private[extended] def showWord8s(bytes: Bytes): String =
  bytes.value.map(b => (b & 0xff).toString).mkString("[", ",", "]")

/** The scheme of a signature builtin, named as its failures name it (`loc` in
  * `PlutusCore/Crypto/Ed25519.hs:43-44` and `PlutusCore/Crypto/Secp256k1.hs:64-65,105-106`).
  */
enum SignatureScheme:
  case Ed25519, EcdsaSecp256k1, SchnorrSecp256k1

  def location: String = this match
    case Ed25519          => "Ed25519 signature verification"
    case EcdsaSecp256k1   => "ECDSA SECP256k1 signature verification"
    case SchnorrSecp256k1 => "Schnorr SECP256k1 signature verification"

/** An argument of a signature builtin that does not deserialise: the builtin fails
  * (`failWithMessage`, `PlutusCore/Crypto/Utils.hs:30-33`). A signature that deserialises but does
  * not verify is the answer `False`, never one of these.
  */
enum SignatureInputFailure extends LogLines:
  /** `Ed25519.hs:34`, `Secp256k1.hs:55,98`. */
  case InvalidVerificationKey(scheme: SignatureScheme)

  /** `Ed25519.hs:36`, `Secp256k1.hs:57,100`. */
  case InvalidSignature(scheme: SignatureScheme)

  /** ECDSA only: a message that is not 32 bytes (`Secp256k1.hs:59`). */
  case InvalidMessageHash

  def lines: Vector[String] = this match
    case InvalidVerificationKey(s) => Vector(s"${s.location}: Invalid verification key.")
    case InvalidSignature(s)       => Vector(s"${s.location}: Invalid signature.")
    case InvalidMessageHash =>
      Vector(s"${SignatureScheme.EcdsaSecp256k1.location}: Invalid message hash.")

/** The failures of the conversions and bitwise builtins (`PlutusCore/Bitwise.hs`), each with the
  * lines the reference emits before it fails.
  */
enum BitwiseFailure extends LogLines:
  /** `integerToByteString` (`Bitwise.hs:92-96`). */
  case NegativeLength(length: BigInt)

  /** `integerToByteString` (`Bitwise.hs:99-105`). */
  case LengthTooLong(length: BigInt)

  /** `integerToByteString` with length 0 and an input of more than 8192 bytes
    * (`Bitwise.hs:110-120`).
    */
  case InputTooLong(bytesRequired: BigInt)

  /** `integerToByteString` of a negative integer (`Bitwise.hs:127-133`). */
  case NegativeInput(input: BigInt)

  /** `integerToByteString` of an integer longer than the length asked for (`Bitwise.hs:134-140`).
    */
  case NotEnoughDigits(input: BigInt, length: BigInt)

  /** `readBit` (`Bitwise.hs:514-521`). */
  case ReadBitOutOfBounds(index: BigInt)

  /** `writeBits` (`Bitwise.hs:536-539`). */
  case WriteBitsOutOfBounds(index: BigInt)

  /** `writeBits` of variants D and E (`Default/Builtins.hs:2206-2214`). */
  case WriteBitsInputTooLong(length: Int)

  /** `replicateByte` (`Bitwise.hs:578-580`). */
  case ReplicateNegativeLength(length: BigInt)

  /** `replicateByte` (`Bitwise.hs:581-587`). */
  case ReplicateLengthTooLong(length: BigInt)

  def lines: Vector[String] = this match
    case NegativeLength(n) =>
      Vector("integerToByteString: negative length argument", s"Length requested: $n")
    case LengthTooLong(n) =>
      Vector(
        s"integerToByteString: requested length is too long (maximum is ${Bitwise.MaximumOutputLength} bytes)",
        s"Length requested: $n"
      )
    case InputTooLong(required) =>
      Vector(
        s"integerToByteString: input too long (maximum is 2^${8 * Bitwise.MaximumOutputLength}-1)",
        s"Length required: $required"
      )
    case NegativeInput(input) =>
      Vector("integerToByteString: cannot convert negative Integer", s"Input: $input")
    case NotEnoughDigits(input, n) =>
      Vector(
        "integerToByteString: cannot represent Integer in given number of bytes",
        s"Input: $input",
        s"Bytes requested: $n"
      )
    case ReadBitOutOfBounds(i)   => Vector("readBit: index out of bounds", s"Index: $i")
    case WriteBitsOutOfBounds(i) => Vector("writeBits: index out of bounds", s"Index: $i")
    case WriteBitsInputTooLong(n) =>
      Vector(
        s"writeBits: input too long (maximum is ${Bitwise.MaximumInputLength} bytes)",
        s"Length: $n"
      )
    case ReplicateNegativeLength(_) => Vector("replicateByte: negative length requested")
    case ReplicateLengthTooLong(n) =>
      Vector(
        s"replicateByte: requested length is too long (maximum is ${Bitwise.MaximumOutputLength} bytes)",
        s"Length requested: $n"
      )

/** The failures of `multiIndexArray` (`PlutusCore/Arrays.hs:48-69`). */
enum ArrayFailure extends LogLines:
  /** More than 1024 indices (`Arrays.hs:56-61`, `maximumIndexCount` 34-35). */
  case TooManyIndices

  /** `Arrays.hs:65-68`. */
  case IndexOutOfBounds(index: BigInt)

  def lines: Vector[String] = this match
    case TooManyIndices =>
      Vector(s"multiIndexArray: too many indices (maximum is ${Arrays.MaximumIndexCount})")
    case IndexOutOfBounds(i) => Vector("multiIndexArray: array index out of bounds", s"Index: $i")

/** The failures of `expModInteger` (`Default/Builtins.hs:2314-2326` and
  * `PlutusCore/Crypto/ExpMod.hs:17-35`).
  */
enum ExpModFailure extends LogLines:
  /** `Builtins.hs:2322`. */
  case NegativeModulus

  /** A modulus of 0 or of more than 8191 bits (`ExpMod.hs:19`). */
  case InvalidModulus

  /** `ExpMod.hs:24,30,32-34`. */
  case NotInvertible(base: BigInt, modulus: BigInt)

  /** A base or exponent outside `[-2^8191, 2^8191 - 1]` (`ExpMod.hs:26,49-50`). */
  case OutOfBounds

  def lines: Vector[String] = this match
    case NegativeModulus     => Vector("expModInteger: negative modulus")
    case InvalidModulus      => Vector("expMod: invalid modulus")
    case NotInvertible(b, m) => Vector(s"expMod: $b is not invertible modulo $m")
    case OutOfBounds         => Vector("expMod: out of bounds")

/** Why `unValueData` refuses its argument (`PlutusCore/Value.hs:557-586` and `buildValueWith`,
  * 637-694).
  */
enum UnValueDataCause:
  case NonMap, NonB, NonI, InvalidQuantity
  case InvalidKey(bytes: Bytes)
  case CurrencySymbolsNotAscending, TokenNamesNotAscending, EmptyInnerMap, ZeroQuantity

  def line: String = this match
    case NonMap                      => "unValueData: non-Map constructor"
    case NonB                        => "unValueData: non-B constructor"
    case NonI                        => "unValueData: non-I constructor"
    case InvalidQuantity             => "unValueData: invalid quantity"
    case InvalidKey(b)               => s"unValueData: invalid key: ${showWord8s(b)}"
    case CurrencySymbolsNotAscending => "unValueData: currency symbols not strictly ascending"
    case TokenNamesNotAscending      => "unValueData: token names not strictly ascending"
    case EmptyInnerMap               => "unValueData: empty inner map"
    case ZeroQuantity                => "unValueData: zero quantity"

/** The failures of the `Value` builtins (`PlutusCore/Value.hs`). */
enum ValueFailure extends LogLines:
  /** `insertCoin` (`Value.hs:349`). */
  case InvalidCurrency(currency: Bytes)

  /** `insertCoin` (`Value.hs:350`). */
  case InvalidToken(token: Bytes)

  /** `insertCoin` (`Value.hs:351`). */
  case QuantityOutOfBounds(amount: BigInt)

  /** `valueContains` (`Value.hs:489`). */
  case FirstValueNegative

  /** `valueContains` (`Value.hs:490`). */
  case SecondValueNegative

  /** `unionValue` (`Value.hs:526`). */
  case UnionOutOfBounds

  /** `valueData` (`Value.hs:540-549`). */
  case ValueDataTooLarge

  /** `unValueData` (`Value.hs:557-586`, 663-691). */
  case UnValueData(cause: UnValueDataCause)

  /** `scaleValue` (`Value.hs:619-628`). */
  case ScaleOutOfBounds(factor: BigInt, quantity: BigInt)

  def lines: Vector[String] = this match
    case InvalidCurrency(c)     => Vector(s"insertCoin: invalid currency: ${showWord8s(c)}")
    case InvalidToken(t)        => Vector(s"insertCoin: invalid token: ${showWord8s(t)}")
    case QuantityOutOfBounds(a) => Vector(s"insertCoin: quantity out of bounds: $a")
    case FirstValueNegative     => Vector("valueContains: first value contains negative amounts")
    case SecondValueNegative    => Vector("valueContains: second value contains negative amounts")
    case UnionOutOfBounds =>
      Vector("unionValue: quantity is out of the signed 128-bit integer bounds")
    case ValueDataTooLarge =>
      Vector(s"valueData: maximum input size (${Values.ValueDataMaxSize}) exceeded")
    case UnValueData(cause)     => Vector(cause.line)
    case ScaleOutOfBounds(c, q) => Vector(s"scaleValue: quantity out of bounds: $c * $q")

/** A library a builtin calls failed in a way the reference's cannot: a defect of this
  * implementation, never a verdict on the script. It has no log line, since the reference has none.
  */
enum LibraryDefect:
  /** The library threw. */
  case Thrown(cause: Throwable)

  /** [[xyz.kd5ujc.scarab.crypto.witness.StrictEd25519]] refused an input other than a key or
    * signature of the wrong length, which it cannot do.
    */
  case UnexpectedInput(error: xyz.kd5ujc.scarab.crypto.witness.WitnessInputError)

  /** Bouncy Castle hashed to a point that jblst finds outside the group. */
  case HashedOutsideGroup(group: xyz.kd5ujc.scarab.uplc.bls.Group)

  /** A Miller-loop result made from bytes alone ([[xyz.kd5ujc.scarab.uplc.bls.MlResult.fromRaw]],
    * for tests) reached the pairing code, which needs the library's own value.
    */
  case DetachedMlResult
