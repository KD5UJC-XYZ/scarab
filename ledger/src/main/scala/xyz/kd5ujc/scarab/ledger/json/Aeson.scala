// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError, HaskellShow}

/** An element of aeson 2.2.5.0's `JSONPath` (`Data/Aeson/Types/Internal.hs`, `JSONPathElement`). */
enum PathElement:
  case Key(key: JsonKey)
  case Index(index: Int)

object JsonPath:
  /** `formatPath` and `formatRelativePath` (`Data/Aeson/Types/Internal.hs:602-631`): `$`, then
    * `.key` for a key that `isIdentifierKey` accepts, `['key']` with `'` and `\` escaped for any
    * other, and `[n]` for an index.
    */
  def format(path: List[PathElement]): String =
    "$" + path.map {
      case PathElement.Index(i) => s"[$i]"
      case PathElement.Key(k)   => formatKey(k.text.asString)
    }.mkString

  private def formatKey(key: String): String =
    if identifier(key) then "." + key
    else
      "['" + key.flatMap {
        case '\'' => "\\'"
        case '\\' => "\\\\"
        case c    => c.toString
      } + "']"

  /** `isIdentifierKey`: a first character that `isAlpha` accepts, the rest `isAlphaNum`. base's
    * `isAlpha` is a letter general category and `isAlphaNum` a letter or number category
    * (`GHC/Unicode.hs`); the categories are the JDK's, of its Unicode version.
    */
  private def identifier(key: String): Boolean =
    val points = key.codePoints.toArray.toVector
    points.headOption.exists(letter) && points.drop(1).forall(c => letter(c) || number(c))

  private def letter(c: Int): Boolean = Character.getType(c) match
    case Character.UPPERCASE_LETTER | Character.LOWERCASE_LETTER | Character.TITLECASE_LETTER |
        Character.MODIFIER_LETTER | Character.OTHER_LETTER =>
      true
    case _ => false

  private def number(c: Int): Boolean = Character.getType(c) match
    case Character.DECIMAL_DIGIT_NUMBER | Character.LETTER_NUMBER | Character.OTHER_NUMBER => true
    case _                                                                                 => false

/** The names the reference's instances give `withObject`, `withText`, `withArray`,
  * `withScientific`, `parseBoundedIntegral`, `parseIntegral` and `prependContext`, as each instance
  * spells it.
  */
enum ParserName(val text: String):
  case ShelleyGenesis extends ParserName("ShelleyGenesis")
  case ShelleyPParams extends ParserName("ShelleyPParams")
  case Nonce extends ParserName("Nonce")
  case ProtVer extends ParserName("ProtVer")
  case GenDelegPair extends ParserName("GenDelegPair")
  case ShelleyGenesisStaking extends ParserName("ShelleyGenesisStaking")
  case ShelleyExtraConfig extends ParserName("ShelleyExtraConfig")
  case AlonzoGenesis extends ParserName("Alonzo Genesis")
  case AlonzoExtraConfig extends ParserName("Extra Config")
  case Prices extends ParserName("prices")
  case ExUnits extends ParserName("exUnits")
  case CostModels extends ParserName("CostModels")
  case ConwayGenesis extends ParserName("ConwayGenesis")
  case UpgradeConwayPParams extends ParserName("UpgradeConwayPParams")
  case PoolVotingThresholds extends ParserName("PoolVotingThresholds")
  case DRepVotingThresholds extends ParserName("DRepVotingThresholds")
  case Constitution extends ParserName("Constitution")
  case Anchor extends ParserName("Anchor")
  case Committee extends ParserName("Committee")
  case ConwayExtraConfig extends ParserName("ConwayExtraConfig")
  case Map extends ParserName("Map")
  case MapText extends ParserName("Map ~Text")
  case ListMap extends ParserName("ListMap")
  case Vector extends ParserName("Vector")
  case List extends ParserName("[]")
  case Hash extends ParserName("hash")
  case StakePoolParams extends ParserName("StakePoolParams")
  case StakePoolRelay extends ParserName("StakePoolRelay")
  case SingleHostAddr extends ParserName("SingleHostAddr")
  case SingleHostName extends ParserName("SingleHostName")
  case MultiHostName extends ParserName("MultiHostName")
  case PoolMetadata extends ParserName("PoolMetadata")
  case AccountAddress extends ParserName("AccountAddress")
  case Credential extends ParserName("Credential")
  case Ipv4 extends ParserName("IPv4")
  case Ipv6 extends ParserName("IPv6")
  case Delegatee extends ParserName("Delegatee")
  case DRep extends ParserName("DRep")
  case DRepState extends ParserName("DRepState")
  case InjectionData extends ParserName("InjectionData")
  case Text extends ParserName("Text")
  case UtcTime extends ParserName("UTCTime")
  case Network extends ParserName("Cardano.Ledger.BaseTypes.Network")
  case Rational extends ParserName("Rational")
  case Ratio extends ParserName("Ratio")
  case Fixed extends ParserName("Fixed")
  case Scientific extends ParserName("Scientific")
  case Integer extends ParserName("Integer")
  case Natural extends ParserName("Natural")
  case Int64 extends ParserName("Int64")
  case Word8 extends ParserName("Word8")
  case Word16 extends ParserName("Word16")
  case Word32 extends ParserName("Word32")
  case Word64 extends ParserName("Word64")

/** Why `B16.decode` (base16-bytestring 1.0.2.0 `Data/ByteString/Base16.hs:101-114`, `Internal.hs`
  * `decodeLoop`) refuses text.
  */
enum Base16Error:
  /** `"invalid bytestring size"`: an odd number of bytes. */
  case Size

  /** `"invalid character at offset: n"`, `n` the byte offset in the UTF-8 encoding. */
  case Character(offset: Int)

  def message: String = this match
    case Size              => "invalid bytestring size"
    case Character(offset) => s"invalid character at offset: $offset"

object Base16:
  /** `B16.decode . Text.encodeUtf8`: pairs of hexadecimal digits, either case; the first byte of a
    * refused pair that is not a digit is the one reported.
    */
  def decode(text: JsonText): Either[Base16Error, Bytes] =
    val bytes = text.asString.getBytes(java.nio.charset.StandardCharsets.UTF_8).toVector
    def digit(b: Byte): Option[Int] = Character.digit(b & 0xff, 16) match
      case d if d >= 0 && (b & 0xff) < 0x80 => Some(d)
      case _                                => None
    if bytes.size % 2 != 0 then Left(Base16Error.Size)
    else
      bytes
        .grouped(2)
        .zipWithIndex
        .toVector
        .traverse { (pair, i) =>
          (pair.headOption.flatMap(digit), pair.lift(1).flatMap(digit)) match
            case (Some(hi), Some(lo)) => Right(((hi << 4) | lo).toByte)
            case (None, _)            => Left(Base16Error.Character(2 * i))
            case (_, None)            => Left(Base16Error.Character(2 * i + 1))
        }
        .map(Bytes(_))

/** A refusal of the reference's genesis parsers, one case per message they can give; `message` is
  * the reference's text.
  */
enum AesonCause:
  /** `prependContext name` (`Data/Aeson/Types/FromJSON.hs:709-710`). */
  case Context(name: ParserName, cause: AesonCause)

  /** `typeMismatch` (`FromJSON.hs:537-543`) of a JSON type. */
  case TypeMismatch(expected: JsonKind, found: JsonKind)

  /** `typeMismatch` of a named type: `typeMismatch "Nonce" (Object obj)`
    * (`Shelley/Genesis.hs:436`).
    */
  case Mismatch(expected: ParserName, found: JsonKind)

  /** `explicitParseField` of an absent key (`FromJSON.hs:916-919`). */
  case KeyNotFound(key: JsonKey)

  /** `parseIntegralFromScientific` (`FromJSON.hs:194-198`). */
  case Floating(value: Scientific)

  /** `parseBoundedIntegralFromScientific` (`FromJSON.hs:204-208`). */
  case OverUnderflow(value: Scientific)

  /** `withBoundedScientific_` (`FromJSON.hs:796-805`). */
  case ExponentAbove(exponent: Long)

  /** The number form of `FromJSON (Ratio a)` (`FromJSON.hs:1708-1716`). */
  case RatioExponent(exponent: Long)

  /** `parseNatural` (`FromJSON.hs:1759-1762`). */
  case NegativeNatural(value: BigInt)

  /** The object form of `FromJSON (Ratio a)` (`FromJSON.hs:1722-1723`). */
  case ZeroDenominator

  /** `parseScientificText` and `scanScientific` (`FromJSON.hs:214-217`, `Internal/Scientific.hs`):
    * a key that is not a number.
    */
  case NumberLiteral(cause: NumberTextError)

  /** The generic all-nullary sum `Network` (`FromJSON.hs:1121-1131`): a text other than its tags.
    */
  case NetworkTag(found: JsonText)

  /** `failWith` (`BaseTypes.hs:464-465`) of a bounded ratio, `NumberError.Negative`, `TooPrecise`,
    * `TooBig` or `OutsideOfBounds`.
    */
  case Bounded(cause: NumberError)

  /** `nonZeroM` (`BaseTypes/NonZero.hs:126-129`). */
  case Zero

  /** `mkVersion32` (`Binary/Version.hs:111-126`, cardano-ledger-binary 1.9.1.0). */
  case UnsupportedVersion(value: BigInt)

  /** `checkWord64Bounds` of `FromJSON ExUnits` (`Plutus/ExUnits.hs:124-128`). */
  case UnitOutOfBounds(value: BigInt)

  /** `parseHash` (`Cardano/Crypto/Hash/Class.hs:340-350`, cardano-crypto-class 2.5.1.0). */
  case HashHex, HashLength

  /** `parseCredential` (`Credential.hs:173-190`). */
  case InvalidHash(text: JsonText)
  case InvalidCredential(text: JsonText)

  /** aeson's `Time.run` (`Data/Aeson/Parser/Time.hs:21-24`) of text-iso8601's `parseUTCTime`. */
  case Date(cause: Iso8601.Error)

  /** `parseAddr`'s `badHex` (`Address.hs:256-261`). */
  case AddressHex(cause: Base16Error)

  /** `decodeAddr` of a Shelley address (`Address.hs:643-860`). */
  case Address(cause: AddressDecodeError)

  /** `parseCostModel` (`Plutus/CostModels.hs:173-180`): a cost model neither array nor object. */
  case CostModelShape(value: JsonValue)

  /** `costModelFromMap` (`Plutus/CostModels.hs:205-215`): `unlines` of the header and one line per
    * absent name.
    */
  case MissingParameters(language: String, names: Vector[String])

  /** `guardNumberOfParameters` (`Plutus/CostModels.hs:194-203`). */
  case ParameterCount(supplied: Int, expected: Int)

  /** `FromJSON AlonzoGenesis` (`Alonzo/Genesis.hs:233-241`). */
  case MissingPlutusV1

  /** `asum` with every alternative refused ends in aeson's `empty` (`Internal.hs:326-327`). */
  case Empty

  /** `parseJsonBase16` (`State/StakePool.hs:315-321`). */
  case NonAsciiText(text: JsonText)
  case HexText(cause: Base16Error)

  /** `FromJSON IPv4` and `FromJSON IPv6` (`Cardano/Base/IP.hs:63-67,96-100`, cardano-base 0.1.6.0).
    */
  case NotIpv4(text: JsonText)
  case NotIpv6(text: JsonText)

  /** `Delegatee` (`Conway/TxCert.hs:398-406`). */
  case NoDelegatee

  /** `parseDRep` (`DRep.hs:148-155`). */
  case InvalidDRep(text: JsonText)

  /** `FromJSON (InjectionData k v)` (`Shelley/Genesis.hs:833-846`). */
  case InvalidHashFormat(text: JsonText)
  case FileAndData

  /** `textSizeN` (`BaseTypes.hs:644-657`). */
  case TextSize(limit: Int, text: JsonText, bytes: Int)

  def message: String = this match
    case Context(name, cause) => s"parsing ${name.text} failed, ${cause.message}"
    case TypeMismatch(e, f)   => s"expected $e, but encountered $f"
    case Mismatch(e, f)       => s"expected ${e.text}, but encountered $f"
    case KeyNotFound(key)     => s"key ${HaskellShow.text(key.text.asString)} not found"
    case Floating(value)      => s"unexpected floating number ${value.shown}"
    case OverUnderflow(value) =>
      s"value is either floating or will cause over or underflow ${value.shown}"
    case ExponentAbove(e) =>
      s"found a number with exponent $e, but it must not be greater than 1024"
    case RatioExponent(e) =>
      s"found a number with exponent $e, but it must not be greater than 1024 or less than -1024"
    case NegativeNatural(n)   => s"parsing Natural failed, unexpected negative number $n"
    case ZeroDenominator      => "Ratio denominator was 0"
    case NumberLiteral(cause) => cause.message
    case NetworkTag(found) =>
      s"expected one of the tags [\"Testnet\",\"Mainnet\"], but found tag ${HaskellShow.text(found.asString)}"
    case Bounded(cause) => cause.message
    case Zero           => "Encountered zero while trying to construct a NonZero value"
    case UnsupportedVersion(v) =>
      s"Unsupported version value: $v. Expected value in bounds: [0, ${Aeson.MaxVersion}]"
    case UnitOutOfBounds(n)      => s"Unit out of bounds for Word64: $n"
    case HashHex                 => "Hashes are expected in hex encoding"
    case HashLength              => "Hash is the wrong length"
    case InvalidHash(text)       => s"Invalid hash: ${HaskellShow.text(text.asString)}"
    case InvalidCredential(text) => s"Invalid credential: ${HaskellShow.text(text.asString)}"
    case Date(cause)             => s"could not parse date: ${cause.message}"
    case AddressHex(cause) =>
      s"Addresses are expected in hex encoding for now: ${HaskellShow.text(cause.message)}"
    case Address(cause)        => cause.message
    case CostModelShape(value) => s"Expected either an Array or an Object, but got: $value"
    case MissingParameters(lang, names) =>
      (s"Cost model language: $lang" +: names.map(n =>
        s"  Parameter name missing from cost model: ${HaskellShow.text(n)}"
      )).map(_ + "\n").mkString
    case ParameterCount(supplied, expected) =>
      s"Number of parameters supplied $supplied does not match the expected number of $expected"
    case MissingPlutusV1 => "Expected \"PlutusV1\" cost model to be supplied"
    case Empty           => "empty"
    case NonAsciiText(text) =>
      s"Supplied text contains non-ASCII characters: ${HaskellShow.text(text.asString)}"
    case HexText(cause)       => cause.message
    case NotIpv4(text)        => s"failed to read as IPv4 ${HaskellShow.text(text.asString)}"
    case NotIpv6(text)        => s"failed to read as IPv6 ${HaskellShow.text(text.asString)}"
    case NoDelegatee          => "Object does not contain a dRep or a poolId field"
    case InvalidDRep(text)    => s"Invalid DRep: ${HaskellShow.text(text.asString)}"
    case InvalidHashFormat(t) => s"Invalid hash format: ${HaskellShow.text(t.asString)}"
    case FileAndData          => "InjectionData: cannot specify both \"file\" and \"data\" fields"
    case TextSize(limit, text, bytes) =>
      s"Text exceeds $limit bytes:${HaskellShow.text(text.asString)}\n  Got $bytes bytes instead.\n"

/** Why `scanScientific` (`Data/Aeson/Internal/Scientific.hs`) or `parseScientificText`
  * (`FromJSON.hs:214-217`) refuses a text.
  */
enum NumberTextError:
  case End
  case Unexpected(char: Int)
  case Trailing(rest: String)

  def message: String = this match
    case End              => "Unexpected end-of-input while parsing number literal"
    case Unexpected(char) => s"Unexpected ${HaskellShow.char(char)} while parsing number literal"
    case Trailing(rest)   => s"Expecting end-of-input, got ${HaskellShow.text(rest)}"

/** Why `decodeAddrStateLenientT False False` (`Address.hs:643-675`) refuses the bytes of a Shelley
  * address: `failDecoding name msg` (`Address.hs:828-829`) or a Byron address the Byron decoder
  * refuses, whose `DecoderError` text is not reproduced ([[ByronAddress]]).
  */
enum AddressDecodeError:
  /** `guardLength name _`: `"Not enough bytes for decoding"`. */
  case NotEnoughBytes(site: AddressSite)

  /** The header has a bit of `headerNonShelleyBits` set (`Address.hs:663-666`). */
  case InvalidHeader(header: Int)

  /** `decodeHash`: fewer than `expected` bytes left. */
  case ShortHash(left: Int, expected: Int)

  /** `decodeVariableLengthWord32`/`Word16`: the fifth (third) group continues. */
  case TooManyBytes(site: AddressSite)

  /** `decodeVariableLengthWord32`/`Word16`: the first group carries bits past 32 (16). */
  case Overlong(site: AddressSite, bits: Int)

  /** `ensureBufIsConsumed "Addr"`. */
  case LeftOver(bytes: Int)

  /** A Byron address its decoder refuses. */
  case ByronAddress(cause: xyz.kd5ujc.scarab.ledger.ConfigProblem)

  def message: String = this match
    case NotEnoughBytes(site) => s"Decoding ${site.text}: Not enough bytes for decoding"
    case InvalidHeader(header) =>
      s"Decoding Shelley Address: Invalid header. Unused bits are not suppose to be set: 0b${header.toBinaryString}"
    case ShortHash(left, expected) =>
      s"Decoding Hash: Not enough bytes supplied: $left. Expected: $expected"
    case TooManyBytes(site)   => s"Decoding ${site.text}: too many bytes."
    case Overlong(site, bits) => s"Decoding ${site.text}: More than ${bits}bits was supplied"
    case LeftOver(n)          => s"Decoding Addr: Left over bytes: $n"
    case ByronAddress(cause)  => cause.message

enum AddressSite(val text: String):
  case Header extends AddressSite("Header")
  case SlotNo extends AddressSite("SlotNo")
  case TxIx extends AddressSite("TxIx")
  case CertIx extends AddressSite("CertIx")

/** aeson's `IError path msg`, the failure `eitherDecodeStrict` formats (`Decoding.hs:53`). */
final case class AesonError(path: List[PathElement], cause: AesonCause) extends DomainError:
  /** `formatError` (`Data/Aeson/Types/Internal.hs:597-598`). */
  def message: String = s"Error in ${JsonPath.format(path)}: ${cause.message}"

/** aeson's `Parser` (`Data/Aeson/Types/Internal.hs:300-345`): run at a path (innermost element
  * first), it fails with the path it failed at. `<*>` and `>>=` stop at the first failure; `<|>`
  * runs the second parser after any failure of the first, and when both fail the second's failure
  * is the one reported.
  */
final case class Parser[+A](run: List[PathElement] => Either[AesonError, A]):
  def map[B](f: A => B): Parser[B] = Parser(p => run(p).map(f))
  def flatMap[B](f: A => Parser[B]): Parser[B] = Parser(p => run(p).flatMap(a => f(a).run(p)))
  def *>[B](next: => Parser[B]): Parser[B] = flatMap(_ => next)
  def void: Parser[Unit] = map(_ => ())
  def orElse[B >: A](that: => Parser[B]): Parser[B] = Parser(p => run(p).orElse(that.run(p)))

  /** `<?>` (`Data/Aeson/Types/Internal.hs:568-570`). */
  def at(element: PathElement): Parser[A] = Parser(p => run(element :: p))

  /** `prependContext`: every failure of this parser, wherever it is. */
  def context(name: ParserName): Parser[A] =
    Parser(p => run(p).leftMap(e => e.copy(cause = AesonCause.Context(name, e.cause))))

  /** `iparseEither` at the root (`Data/Aeson/Types/Internal.hs:591-592`). */
  def parse: Either[AesonError, A] = run(Nil)

/** The aeson 2.2.5.0 combinators and `FromJSON` instances the genesis instances are written with.
  */
object Aeson:
  type Decoder[A] = JsonValue => Parser[A]

  /** `maxBound :: Version` (`Binary/Version.hs:63`). */
  val MaxVersion: Int = 13

  def pure[A](a: A): Parser[A] = Parser(_ => Right(a))
  val unit: Parser[Unit] = pure(())

  /** `fail`, at the current path (`Data/Aeson/Types/Internal.hs:311-312`). */
  def fail(cause: AesonCause): Parser[Nothing] = Parser(p => Left(AesonError(p.reverse, cause)))

  def fromEither[A](either: Either[AesonCause, A]): Parser[A] = either.fold(fail, pure)

  /** `traverse` in order, stopping at the first failure. */
  def traverse[A, B](items: Vector[A])(f: A => Parser[B]): Parser[Vector[B]] = Parser { p =>
    items.foldLeft[Either[AesonError, Vector[B]]](Right(Vector.empty)) { (acc, a) =>
      acc.flatMap(bs => f(a).run(p).map(bs :+ _))
    }
  }

  private def mismatch(name: ParserName, expected: JsonKind, v: JsonValue) =
    fail(AesonCause.TypeMismatch(expected, JsonKind.of(v))).context(name)

  def withObject[A](name: ParserName)(f: JsonObject => Parser[A]): Decoder[A] = v =>
    v match
      case JsonValue.Object(o) => f(o)
      case v                   => mismatch(name, JsonKind.Object, v)

  def withText[A](name: ParserName)(f: JsonText => Parser[A]): Decoder[A] = v =>
    v match
      case JsonValue.String(t) => f(t)
      case v                   => mismatch(name, JsonKind.String, v)

  def withArray[A](name: ParserName)(f: Vector[JsonValue] => Parser[A]): Decoder[A] = v =>
    v match
      case JsonValue.Array(items) => f(items)
      case v                      => mismatch(name, JsonKind.Array, v)

  def withScientific[A](name: ParserName)(f: Scientific => Parser[A]): Decoder[A] = v =>
    v match
      case JsonValue.Number(n) => f(n)
      case v                   => mismatch(name, JsonKind.Number, v)

  /** `withScientific'`, without context of its own. */
  private def scientificOf[A](f: Scientific => Parser[A]): Decoder[A] = v =>
    v match
      case JsonValue.Number(n) => f(n)
      case v                   => fail(AesonCause.TypeMismatch(JsonKind.Number, JsonKind.of(v)))

  /** `withBoundedScientific'` (`FromJSON.hs:791-805`). */
  private def boundedScientificOf[A](f: Scientific => Parser[A]): Decoder[A] =
    scientificOf(s =>
      if s.base10Exponent > 1024 then fail(AesonCause.ExponentAbove(s.base10Exponent)) else f(s)
    )

  def key(name: String): JsonKey = JsonKey.fromString(name)

  /** `.:` (`explicitParseField`). */
  def field[A](o: JsonObject, name: String)(p: Decoder[A]): Parser[A] =
    val k = key(name)
    o.get(k).fold(fail(AesonCause.KeyNotFound(k)))(v => p(v).at(PathElement.Key(k)))

  /** `.:?` (`explicitParseFieldMaybe`): absent or `null` is `Nothing`. */
  def fieldOpt[A](o: JsonObject, name: String)(p: Decoder[A]): Parser[Option[A]] =
    val k = key(name)
    o.get(k) match
      case None                 => pure(None)
      case Some(JsonValue.Null) => pure(None)
      case Some(v)              => p(v).map(Some(_)).at(PathElement.Key(k))

  /** `o .: first <|> o .: second`. */
  def either[A](o: JsonObject, first: String, second: String)(p: Decoder[A]): Parser[A] =
    field(o, first)(p).orElse(field(o, second)(p))

  val value: Decoder[JsonValue] = v => pure(v)

  // Numbers

  /** `FromJSON Scientific`. */
  val scientific: Decoder[Scientific] = withScientific(ParserName.Scientific)(pure)

  /** `parseBoundedIntegral name` (`FromJSON.hs:210-212`). */
  def boundedIntegral(name: ParserName, min: BigInt, max: BigInt): Decoder[BigInt] = v =>
    scientificOf(s => s.toBoundedInteger(min, max).fold(fail(AesonCause.OverUnderflow(s)))(pure))(v)
      .context(name)

  private def unsigned(name: ParserName, bits: Int): Decoder[BigInt] =
    boundedIntegral(name, BigInt(0), BigInt(2).pow(bits) - 1)

  val word8: Decoder[BigInt] = unsigned(ParserName.Word8, 8)
  val word16: Decoder[BigInt] = unsigned(ParserName.Word16, 16)
  val word32: Decoder[BigInt] = unsigned(ParserName.Word32, 32)
  val word64: Decoder[BigInt] = unsigned(ParserName.Word64, 64)
  val int64: Decoder[BigInt] =
    boundedIntegral(ParserName.Int64, -BigInt(2).pow(63), BigInt(2).pow(63) - 1)

  /** `parseIntegral name` (`FromJSON.hs:200-202`). */
  private def integral(name: ParserName): Decoder[BigInt] = v =>
    boundedScientificOf(s => s.wholeNumber.fold(fail(AesonCause.Floating(s)))(pure))(v)
      .context(name)

  /** `FromJSON Integer`. */
  val integer: Decoder[BigInt] = integral(ParserName.Integer)

  /** `FromJSON Natural` (`FromJSON.hs:1749-1762`). */
  val natural: Decoder[BigInt] = v =>
    integral(ParserName.Natural)(v).flatMap(n =>
      if n < 0 then fail(AesonCause.NegativeNatural(n)) else pure(n)
    )

  /** `FromJSON (Fixed a)` (`FromJSON.hs:1730-1731`): any number whose exponent is at most 1024. */
  val fixed: Decoder[Scientific] = v => boundedScientificOf(pure)(v).context(ParserName.Fixed)

  /** `FromJSON (Ratio Integer)` (`FromJSON.hs:1708-1724`). */
  val rational: Decoder[xyz.kd5ujc.scarab.codec.Rational] = v =>
    v match
      case JsonValue.Number(x) =>
        val e = x.base10Exponent
        if e <= 1024 && e >= -1024 then
          pure(
            if e >= 0 then xyz.kd5ujc.scarab.codec.Rational(x.coefficient * BigInt(10).pow(e.toInt))
            else xyz.kd5ujc.scarab.codec.Rational(x.coefficient, BigInt(10).pow((-e).toInt))
          )
        else fail(AesonCause.RatioExponent(e)).context(ParserName.Ratio)
      case other =>
        withObject(ParserName.Rational) { obj =>
          for
            n <- field(obj, "numerator")(integer)
            d <- field(obj, "denominator")(integer)
            r <-
              if d == 0 then fail(AesonCause.ZeroDenominator)
              else pure(xyz.kd5ujc.scarab.codec.Rational(n, d))
          yield r
        }(other)

  /** `FromJSON (BoundedRatio b Word64)` (`BaseTypes.hs:437-442`): an object through the rational
    * form, anything else through `Scientific`, each `failWith` at the value's path.
    */
  def boundedRatio(interval: BoundedRatio): Decoder[xyz.kd5ujc.scarab.codec.Rational] = v =>
    v match
      case o @ JsonValue.Object(_) =>
        rational(o).flatMap(r =>
          fromEither(interval.fromRational(r).leftMap(AesonCause.Bounded(_)))
        )
      case v =>
        scientific(v).flatMap(s =>
          fromEither(interval.fromScientific(s).leftMap(AesonCause.Bounded(_)))
        )

  // Text

  /** `FromJSON Text`. */
  val text: Decoder[JsonText] = withText(ParserName.Text)(pure)

  /** `parseHash` on the UTF-8 bytes of the text. */
  def hashText(size: Int)(t: JsonText): Parser[Bytes] =
    Base16
      .decode(t)
      .fold(
        _ => fail(AesonCause.HashHex),
        bytes => if bytes.size == size then pure(bytes) else fail(AesonCause.HashLength)
      )

  /** `FromJSON (Hash h a)`: `withText "hash" parseHash`. */
  def hash(size: Int): Decoder[Bytes] = withText(ParserName.Hash)(hashText(size))

  /** `hashFromTextAsHex`. */
  def hashFromText(size: Int)(t: JsonText): Option[Bytes] =
    Base16.decode(t).toOption.filter(_.size == size)

  /** `parseScientificText` (`FromJSON.hs:214-217`, `Internal/Scientific.hs`). */
  def scientificText(t: JsonText): Parser[Scientific] =
    fromEither(NumberText.scan(t.asString).leftMap(AesonCause.NumberLiteral(_)))

  /** `parseBoundedIntegralText name` (`FromJSON.hs:229-232`). */
  def boundedIntegralText(name: ParserName, min: BigInt, max: BigInt)(t: JsonText): Parser[BigInt] =
    scientificText(t)
      .flatMap(s => s.toBoundedInteger(min, max).fold(fail(AesonCause.OverUnderflow(s)))(pure))
      .context(name)

  // Containers

  /** `FromJSON (Vector a)` (`FromJSON.hs:2077-2079`): each item at its index. */
  def vector[A](p: Decoder[A]): Decoder[Vector[A]] = withArray(ParserName.Vector)(items =>
    traverse(items.zipWithIndex)((v, i) => p(v).at(PathElement.Index(i)))
  )

  /** `parseJSONList` of an element type: `withArray "[]"`, each item at its index. */
  def list[A](p: Decoder[A]): Decoder[Vector[A]] = withArray(ParserName.List)(items =>
    traverse(items.zipWithIndex)((v, i) => p(v).at(PathElement.Index(i)))
  )

  /** `FromJSON (Map k v)` with a `FromJSONKeyTextParser` key (`FromJSON.hs:2026-2029`): in key
    * order, each key and then its value, both at the key.
    */
  def map[K, V](k: JsonText => Parser[K], v: Decoder[V]): Decoder[Vector[(K, V)]] =
    withObject(ParserName.Map)(o =>
      traverse(o.toVector) { (key, value) =>
        val at = PathElement.Key(key)
        for
          kk <- k(key.text).at(at)
          vv <- v(value).at(at)
        yield kk -> vv
      }
    )

  /** `FromJSON (Map Text v)` (`FromJSONKeyCoerce`, `FromJSON.hs:2020-2024`): each value at its key.
    */
  def textMap[V](v: Decoder[V]): Decoder[Vector[(JsonText, V)]] =
    withObject(ParserName.MapText)(o =>
      traverse(o.toVector)((key, value) => v(value).at(PathElement.Key(key)).map(key.text -> _))
    )

  /** `FromJSON (ListMap k v)` (cardano-data 1.3.1.0 `Data/ListMap.hs:109-122`): in key order, each
    * key and value at the object's own path.
    */
  def listMap[K, V](k: JsonText => Parser[K], v: Decoder[V]): Decoder[Vector[(K, V)]] =
    withObject(ParserName.ListMap)(o =>
      traverse(o.toVector)((key, value) => k(key.text).flatMap(kk => v(value).map(kk -> _)))
    )

/** `scanScientific` (`Data/Aeson/Internal/Scientific.hs`) with `parseScientificText` 's check for
  * the rest (`FromJSON.hs:214-217`). The exponent is an `Int`: `fromInteger` keeps its low 64 bits.
  */
object NumberText:
  def scan(text: String): Either[NumberTextError, Scientific] =
    val cs = text.codePoints.toArray.toVector
    def isDigit(c: Int) = c >= '0' && c <= '9'
    def digits(from: Int): Int = cs.drop(from).takeWhile(isDigit).size
    def int(from: Int, n: Int): BigInt =
      if n == 0 then BigInt(0) else BigInt(new String(cs.slice(from, from + n).toArray, 0, n))
    def rest(at: Int): Either[NumberTextError, Int] =
      if at == cs.size then Right(at)
      else
        val shown = cs.slice(at, at + 10).toArray
        Left(NumberTextError.Trailing(new String(shown, 0, shown.length)))
    val (sign, start) = cs.headOption match
      case Some('-') => (-1, 1)
      case Some('+') => (1, 1)
      case _         => (1, 0)
    def signed(s: Scientific) = if sign < 0 then Scientific(-s.coefficient, s.base10Exponent) else s
    def expo(coef: BigInt, exp10: Long, at: Int): Either[NumberTextError, (Scientific, Int)] =
      cs.lift(at) match
        case None => Left(NumberTextError.End)
        case Some(c) =>
          val (neg, from) = c match
            case '+' => (false, at + 1)
            case '-' => (true, at + 1)
            case _   => (false, at)
          cs.lift(from) match
            case None => Left(NumberTextError.End)
            case Some(d) if isDigit(d) =>
              val n = digits(from)
              val e = int(from, n).toLong
              Right(Scientific(coef, if neg then exp10 - e else exp10 + e) -> (from + n))
            case Some(d) => Left(NumberTextError.Unexpected(d))
    val scanned: Either[NumberTextError, (Scientific, Int)] = cs.lift(start) match
      case None                   => Left(NumberTextError.End)
      case Some(c) if !isDigit(c) => Left(NumberTextError.Unexpected(c))
      case Some(_) =>
        val n = digits(start)
        val whole = int(start, n)
        val after = start + n
        cs.lift(after) match
          case Some('.') =>
            cs.lift(after + 1) match
              case None                   => Left(NumberTextError.End)
              case Some(d) if !isDigit(d) => Left(NumberTextError.Unexpected(d))
              case Some(_) =>
                val f = digits(after + 1)
                val coef = whole * BigInt(10).pow(f) + int(after + 1, f)
                val end = after + 1 + f
                cs.lift(end) match
                  case Some('e') | Some('E') => expo(coef, -f.toLong, end + 1)
                  case _                     => Right(Scientific(coef, -f.toLong) -> end)
          case Some('e') | Some('E') => expo(whole, 0, after + 1)
          case _                     => Right(Scientific(whole, 0) -> after)
    scanned.flatMap((s, at) => rest(at).map(_ => signed(s)))
