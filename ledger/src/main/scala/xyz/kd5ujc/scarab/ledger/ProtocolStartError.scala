// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.{Bytes, HaskellShow}
import xyz.kd5ujc.scarab.ledger.json.{AesonError, NativeJson}

/** The step of `readGenesisAny` an `IOException` comes from, as its location reads (cardano-node
  * 11.1.3 `Protocol/Shelley.hs:126-138`): `getFileSize` (directory 1.3, through `getFileStatus`) or
  * `BS.readFile` (`withBinaryFile`).
  */
enum GenesisFileStep(val location: String):
  case Size extends GenesisFileStep("getFileSize:getFileStatus")
  case Read extends GenesisFileStep("withBinaryFile")

/** The `IOErrorType` of a failed read and the `strerror` description GHC shows with it. A directory
  * is refused by GHC's own `openFile` check, not by the operating system.
  */
enum GenesisFileErrno(val kind: String, val description: String):
  case NoSuchFile extends GenesisFileErrno("does not exist", "No such file or directory")
  case PermissionDenied extends GenesisFileErrno("permission denied", "Permission denied")
  case IsDirectory extends GenesisFileErrno("inappropriate type", "is a directory")

/** An `IOException` of reading a genesis file. `file` is the path as the configuration gave it.
  * `shown` is base's `instance Show IOException` (GHC/IO/Exception.hs): the file, the location, the
  * error type and the description; precedence does not parenthesise it.
  */
final case class GenesisFileFailure(file: String, step: GenesisFileStep, errno: GenesisFileErrno):
  def shown: String = s"$file: ${step.location}: ${errno.kind} (${errno.description})"

/** Why `eitherDecodeStrict'` refused a genesis file (aeson 2.2.5.0 `Decoding.hs:48-53`): the bytes
  * are not one JSON value, or the instance refused the value. The text is `GenesisDecodeError` 's
  * string.
  */
enum GenesisDecodeFailure:
  case Value(cause: NativeJson.DecodeError)
  case Field(cause: AesonError)

  def message: String = this match
    case Value(cause) => cause.message
    case Field(cause) => cause.message

/** `GenesisReadError` (cardano-node 11.1.3 `Protocol/Shelley.hs:319-337`). */
enum GenesisReadError:
  case ReadFile(cause: GenesisFileFailure)

  /** The hash of the file's bytes and the one the configuration gives. */
  case HashMismatch(actual: Bytes, expected: Bytes)

  case Decode(file: String, cause: GenesisDecodeFailure)

  /** The derived `Show` at precedence 11: in parentheses. */
  def shown: String = this match
    case ReadFile(cause) =>
      s"(GenesisReadFileError ${HaskellShow.text(cause.file)} ${cause.shown})"
    case HashMismatch(actual, expected) =>
      s"(GenesisHashMismatch ${ProtocolStartError.hash(actual)} ${ProtocolStartError.hash(expected)})"
    case Decode(file, cause) =>
      s"(GenesisDecodeError ${HaskellShow.text(file)} ${HaskellShow.text(cause.message)})"

  /** `prettyError`. */
  def pretty: String = this match
    case ReadFile(cause) =>
      s"There was an error reading the genesis file: ${HaskellShow.text(cause.file)} Error: ${cause.shown}"
    case HashMismatch(actual, expected) =>
      s"Wrong genesis file: the actual hash is ${ProtocolStartError.hash(actual)}, but the " +
        s"expected genesis hash given in the node configuration file is ${ProtocolStartError.hash(expected)}"
    case Decode(file, cause) =>
      s"There was an error parsing the genesis file: ${HaskellShow.text(file)} Error: ${HaskellShow.text(cause.message)}"

/** A genesis file read by `readGenesisAny`, with the constructor of
  * `CardanoProtocolInstantiationError` that wraps its refusal and the prefix `prettyError` gives it
  * (cardano-node 11.1.3 `Protocol/Cardano.hs:276-312`, the Conway and Dijkstra ones with a space
  * before the colon).
  */
enum GenesisEra(val constructor: String, val prefix: String):
  case Shelley
      extends GenesisEra("CardanoProtocolInstantiationShelleyGenesisReadError", "Shelley related: ")
  case Alonzo
      extends GenesisEra("CardanoProtocolInstantiationAlonzoGenesisReadError", "Alonzo related: ")
  case Conway
      extends GenesisEra("CardanoProtocolInstantiationConwayGenesisReadError", "Conway related : ")
  case Dijkstra
      extends GenesisEra(
        "CardanoProtocolInstantiationDijkstraGenesisReadError",
        "Dijkstra related : "
      )

/** A `ProtocolInstantiationError` that `runNode` throws while it reads the genesis files
  * (cardano-node 11.1.3 `Run.hs:195-206`, `Protocol.hs:51-63`): the reference's top-level handler
  * writes [[rendered]] to standard error and exits with 1.
  */
enum ProtocolStartError:
  case Genesis(era: GenesisEra, cause: GenesisReadError)

  /** `GenesisHashMismatch` of `ByronProtocolInstantiationError`
    * (`Protocol/Byron.hs:104-113,175-178`): the hash of the canonical JSON and the configured one.
    */
  case ByronHash(actual: Bytes, expected: Bytes)

  /** `GenesisReadError file (GenesisDataSchemaError (SchemaError expected Nothing))`
    * (`Protocol/Byron.hs:93,185-187`): a field the Byron genesis lacks, which canonical-json's
    * `fromJSField` expects as `field <key>`.
    */
  case ByronMissingField(file: String, field: ByronGenesisField)

  /** The derived `Show` of `CardanoProtocolInstantiationError (...)`. */
  def shown: String = this match
    case Genesis(era, cause) =>
      s"CardanoProtocolInstantiationError (${era.constructor} ${cause.shown})"
    case ByronHash(actual, expected) =>
      "CardanoProtocolInstantiationError (CardanoProtocolInstantiationErrorByron " +
        s"(GenesisHashMismatch ${ProtocolStartError.hash(actual)} ${ProtocolStartError.hash(expected)}))"
    case ByronMissingField(file, field) =>
      "CardanoProtocolInstantiationError (CardanoProtocolInstantiationErrorByron " +
        s"(GenesisReadError ${HaskellShow.text(file)} (${ProtocolStartError.schema(field)})))"

  /** `displayException = docToString . prettyError` (`Protocol.hs:57-63`). */
  def pretty: String = this match
    case Genesis(era, cause) => era.prefix + cause.pretty
    case ByronMissingField(file, field) =>
      s"There was an error parsing the genesis file: ${HaskellShow.text(file)} Error: ${ProtocolStartError.schema(field)}"
    case ByronHash(actual, expected) =>
      s"Wrong Byron genesis file: the actual hash is ${ProtocolStartError.hash(actual)}, but the " +
        s"expected Byron genesis hash given in the node configuration file is ${ProtocolStartError.hash(expected)}"

  /** `toplevelExceptionHandler` 's `renderException` (cardano-node 11.1.3
    * `Handlers/TopLevel.hs:190-209`): `show` and `displayException` differ, so both, the second
    * after the program's name (`getProgName`); `hPutStr` adds no newline.
    */
  def rendered(program: String): String = s"$shown\n\n$program: $pretty"

object ProtocolStartError:
  /** `show` of a `GenesisHash`: its hexadecimal as a string. */
  def hash(bytes: Bytes): String = HaskellShow.text(bytes.hex)

  /** The derived `Show` of `GenesisDataSchemaError (SchemaError {..})` for an absent field, at
    * precedence 0.
    */
  private[ledger] def schema(field: ByronGenesisField): String =
    "GenesisDataSchemaError (SchemaError {seExpected = " +
      HaskellShow.text(s"field ${field.key}") + ", seActual = Nothing})"
