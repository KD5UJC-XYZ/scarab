// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import xyz.kd5ujc.scarab.uplc.{Availability, Constant, DefaultUni, PlutusData, Value}

/** Why the reference's Flat decoder refuses a program, one case per `fail` and per exception of the
  * decoders of plutus 1.70.0.0 (`507cab8c`) that a script reaches. The reference's messages are
  * strings inside `DecodeException` (`PlutusCore/Flat/Decoder/Types.hs:192-197`); each case names
  * the one it stands for. Positions count bits from the start of the Flat bytes.
  */
enum FlatError:
  /** `notEnoughSpace` (`Decoder/Types.hs:201-202`): the input ends before the `needed` bits a read
    * asks for at `at` (`ensureBits`, `Decoder/Prim.hs:171-172`; `dBool`, `Prim.hs:200-211`).
    */
  case NotEnoughSpace(at: Long, needed: Long)

  /** `tooMuchSpace` (`Decoder/Run.hs:19-24`): the filler after the program ends at `at`, before the
    * last bit of the input.
    */
  case TooMuchSpace(at: Long)

  /** "usedBits /= 0" (`getChunksInfo`, `Decoder/Prim.hs:441-445`): the filler before a byte string
    * ends inside a byte.
    */
  case Misaligned(at: Long)

  /** "Unexpected extra byte in unsigned integer" (`lastStep`, `Decoder/Strict.hs:228-239`): the
    * tenth group of a `Word64` holds more than bit 63, or says that another group follows.
    */
  case Word64Overflow(at: Long)

  /** "Unknown term constructor tag" (`UntypedPlutusCore/Core/Instance/Flat.hs:186`). */
  case UnknownTermTag(tag: Int)

  /** "Failed to decode builtin tag" (`PlutusCore/Default/Builtins.hs:2784`). */
  case UnknownBuiltinTag(tag: Int)

  /** "Failed to decode a universe" (`decodeKindedUniFlat`, `PlutusCore/FlatInstances.hs:129-135`):
    * the tags of a constant's type are not one well-kinded type.
    */
  case UndecodableType(tags: Vector[Int], reason: TypeHeader.Failure)

  /** "A non-star type can't have a value to decode" (`Flat.hs:151-152`): the type is an operator,
    * or a pair with one argument.
    */
  case NonStarType(tags: Vector[Int])

  /** A constant of a type whose Flat decoder always fails: the BLS12-381 types
    * (`Crypto/BLS12_381/G1.hs:86-90`, `G2.hs:72-76`, `Pairing.hs:56-58`). It is refused only when a
    * value is read, so an empty list of them decodes.
    */
  case NoFlatValue(uni: DefaultUni)

  /** "Input contains invalid UTF-8 data" (`dUTF8`, `Decoder/Strict.hs:293-299`) for the string that
    * starts at `at`.
    */
  case InvalidUtf8(at: Long)

  /** The `data` constant's bytes do not start with a CBOR item `decodeData` reads
    * (`FlatViaSerialise`, `Codec/Extras/FlatViaSerialise.hs:32-39`).
    */
  case MalformedData(cause: PlutusData.DecodeError)

  /** A `value` constant the reference refuses: a key longer than 32 bytes or an amount outside 128
    * bits as it is read (`Value.hs:103-108`, 137-144), or the entries `buildValueWith` refuses
    * (`Value.hs:637-694`).
    */
  case InvalidValue(cause: Value.Invalid)

  /** A refusal of the ledger's predicates or of the version gate of `constr` and `case`: the `fail`
    * s of `decodeTerm` (`Flat.hs:154-156`, 164-166, 167-180, 181-184) with the checks of
    * `scriptCBORDecoder` (`PlutusLedgerApi/Common/SerialisedScript.hs:207-234`).
    */
  case Refused(refusal: Availability.Refusal)

  /** Elements of a decoded list or array whose type is not its element type. Every element is
    * decoded at the element type, so this does not arise; it keeps the decoder total over
    * [[Constant.List.of]].
    */
  case IllTypedElements(mismatch: Constant.ElementTypeMismatch)

  /** A version with a negative component. Its components are naturals, so this does not arise; it
    * keeps the decoder total over `ProgramVersion.of`.
    */
  case NegativeVersion(major: BigInt, minor: BigInt, patch: BigInt)
