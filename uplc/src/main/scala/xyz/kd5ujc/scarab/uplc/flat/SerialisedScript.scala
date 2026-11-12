// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Availability, PlutusLanguage, Program, ProtocolMajor}

/** Why the ledger refuses to deserialise a script: the reference's `ScriptDecodeError`
  * (`PlutusLedgerApi/Common/SerialisedScript.hs:55-79`, plutus 1.70.0.0 `507cab8c`) with the reasons
  * of `CBORDeserialiseError` as cases of their own (`Codec/Extras/SerialiseViaFlat.hs:74-83`).
  */
enum ScriptDecodeError:
  /** `LedgerLanguageNotAvailableError`: the language arrives at a later protocol version. */
  case LanguageNotAvailable(
      language: PlutusLanguage,
      introducedIn: ProtocolMajor,
      protocol: ProtocolMajor
  )

  /** `CBORDeserialiseError` with `EndOfInput`: the input ends inside the script's CBOR byte string,
    * or before it.
    */
  case EndOfInput

  /** `CBORDeserialiseError` with `ExpectedBytes`: the input does not start with a definite-length
    * CBOR byte string.
    */
  case ExpectedBytes

  /** `CBORDeserialiseError` with `OtherReason`: the Flat decoder refuses the program, which it
    * reports to cborg through `fail` (`decodeViaFlatWith`, `SerialiseViaFlat.hs:28-32`).
    */
  case Flat(error: FlatError)

  /** `RemainderError`: bytes after the script's CBOR byte string, refused unless the script is a V1
    * or V2 script.
    */
  case Remainder(remainder: Bytes)

/** The ledger's side of a script: from the bytes the ledger holds (a CBOR byte string around the
  * Flat program) to the program it evaluates, as `deserialiseScript` does it
  * (`PlutusLedgerApi/Common/SerialisedScript.hs:241-278`, plutus 1.70.0.0 `507cab8c`), and the
  * check of the program's version that evaluation adds (`mkTermToEvaluate`,
  * `PlutusLedgerApi/Common/Eval.hs:107-130`).
  *
  * A script is deserialised for the language it is evaluated as ([[PlutusLanguage.evaluatedAs]]):
  * the node of 1.70.0.0 hands a V4 script to plutus's V3 functions. Its builtins and the bytes it
  * may carry after the CBOR item are therefore those of V3; only the protocol version from which
  * the language exists ([[Availability.languageIntroducedIn]]) is its own.
  */
object SerialisedScript:

  /** `deserialiseScript ll pv` (`SerialisedScript.hs:254-266`), in its order: the language must be
    * available at `protocol`; the script must start with a definite-length CBOR byte string
    * (`decodeBytes`, through `decodeViaFlatWith`), whose content the Flat decoder reads whole with
    * the ledger's [[Checks]]; and bytes after that byte string are refused unless the language is
    * V1 or V2 (`SerialisedScript.hs:262-264`).
    *
    * The program's version is not checked here: it is a failure of the evaluation, not of the
    * decoding ([[forEvaluation]]).
    */
  def deserialiseScript(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      script: Bytes
  ): Either[ScriptDecodeError, Program] =
    val introduced = Availability.languageIntroducedIn(language)
    for
      _ <- Either.cond(
        introduced.value <= protocol.value,
        (),
        ScriptDecodeError.LanguageNotAvailable(language, introduced, protocol)
      )
      item <- byteString(script)
      (content, remainder) = item
      program <- FlatDecoder
        .program(content, Checks.ledger(language, protocol))
        .left
        .map(ScriptDecodeError.Flat(_))
      _ <- language.evaluatedAs match
        case PlutusLanguage.V1 | PlutusLanguage.V2 => Right(())
        case _ =>
          Either.cond(remainder.isEmpty, (), ScriptDecodeError.Remainder(remainder))
    yield program

  /** The check that `mkTermToEvaluate` makes of a deserialised program before it runs
    * (`Eval.hs:124-127`): its version must be one the language has at `protocol`
    * (`plcVersionsAvailableIn`, [[Availability.programVersions]]); otherwise
    * `PlutusCoreLanguageNotAvailableError`, a failure of phase 2. The scope check that follows it
    * (`Eval.hs:129-130`) is the machine's.
    */
  def forEvaluation(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      program: Program
  ): Either[Availability.Refusal, Program] =
    Availability.checkProgramVersion(language, protocol, program.version).map(_ => program)

  /** The content of the definite-length CBOR byte string at the start of `script`, and the bytes
    * after it: what cborg's `decodeBytes` reads and what `deserialiseFromBytes` leaves
    * (`SerialisedScript.hs:268-274`). The length may be written in any of the widths CBOR allows. An
    * indefinite-length (chunked) byte string is refused as cborg refuses it, with `ExpectedBytes`.
    */
  def byteString(script: Bytes): Either[ScriptDecodeError, (Bytes, Bytes)] =
    script.headOption match
      case None => Left(ScriptDecodeError.EndOfInput)
      case Some(head) =>
        val major = (head & 0xff) >>> 5
        val info = head & 0x1f
        val width = info match
          case 24 => 1
          case 25 => 2
          case 26 => 4
          case 27 => 8
          case _  => 0
        if major != 2 || info > 27 then Left(ScriptDecodeError.ExpectedBytes)
        else
          val length =
            if width == 0 then Some(info.toLong)
            else script.bigEndian(1, width)
          val start = 1 + width
          length match
            // a length whose top bit is set is beyond every input
            case Some(n) if n >= 0 && n <= script.size.toLong - start =>
              Right((script.slice(start, start + n.toInt), script.drop(start + n.toInt)))
            case _ => Left(ScriptDecodeError.EndOfInput)
