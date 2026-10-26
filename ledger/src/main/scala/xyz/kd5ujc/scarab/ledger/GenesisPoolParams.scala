// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{ACursor, Json}
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError, Rational}

/** The complete genesis `StakePoolParams` JSON boundary (`State/StakePool.hs:467-482`, f649f975).
  * The outer map key and the parameter's pool ID are independent; `mkStakePoolState` subsequently
  * drops the latter. JSON's numeric and alias rules differ from the wire decoder's contracts.
  */
object GenesisPoolParams:
  enum Error extends DomainError:
    case Number(field: String, value: Json)
    case IntegerExponent(field: String, exponent: BigInt)
    case FractionalInteger(field: String, value: Json)
    case NegativeMargin(value: Json)
    case MarginPrecision(exponent: BigInt)
    case MarginBounds(value: Rational)
    case ZeroDenominator
    def message: String = toString

  private final case class Scientific(coefficient: BigInt, exponent: BigInt):
    def normalized: Scientific =
      if coefficient == 0 then Scientific(0, 0)
      else
        val digits = coefficient.toString
        val reduced = digits.reverse.dropWhile(_ == '0').reverse
        Scientific(BigInt(reduced), exponent + digits.length - reduced.length)

  private val number = "(-?)([0-9]+)(?:\\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?".r
  private val maxWord64 = (BigInt(1) << 64) - 1

  private def failure(e: Error): ConfigError = ConfigError(ConfigProblem.GenesisPoolParams(e))

  private def scientific(field: String, value: Json): Either[ConfigError, Scientific] =
    value.asNumber.map(_.toString) match
      case Some(number(sign, whole, fraction, power)) =>
        val decimals = Option(fraction).getOrElse("")
        // JsonNumber supplies valid numeric tokens; each BigInt input consists of signed digits.
        val exponent = Option(power).fold(BigInt(0))(BigInt(_)) - decimals.length
        Right(Scientific(BigInt(sign + whole + decimals), exponent))
      case _ => Left(failure(Error.Number(field, value)))

  /** Aeson's Integer reader checks the original exponent before integral conversion
    * (`aeson-2.2.5.0`, `Types/FromJSON.hs:194-202,797-804,1743-1744`). This is not a Coin bound.
    */
  private def integer(field: String, value: Json): Either[ConfigError, BigInt] =
    scientific(field, value).flatMap { n =>
      if n.exponent > 1024 then Left(failure(Error.IntegerExponent(field, n.exponent)))
      else if n.coefficient == 0 then Right(BigInt(0))
      else if n.exponent >= 0 then Right(n.coefficient * BigInt(10).pow(n.exponent.toInt))
      else
        val zeros = n.coefficient.toString.reverse.takeWhile(_ == '0').length
        if -n.exponent > zeros then Left(failure(Error.FractionalInteger(field, value)))
        else Right(n.coefficient / BigInt(10).pow((-n.exponent).toInt))
    }

  private def integerField(c: ACursor, field: String): Either[ConfigError, BigInt] =
    GenesisState.get[Json](c, field).flatMap(integer(field, _))

  /** `UnitInterval` JSON normalizes Scientific before its nineteen-decimal restriction; rational
    * objects reduce before the Word64 and [0,1] checks (`BaseTypes.hs:348-376,437-465`, f649f975).
    */
  private def margin(value: Json): Either[ConfigError, Rational] =
    val parsed = value.asObject match
      case Some(_) =>
        for
          n <- integerField(value.hcursor, "numerator")
          d <- integerField(value.hcursor, "denominator")
          _ <- Either.cond(d != 0, (), failure(Error.ZeroDenominator))
        yield Rational(n, d)
      case None =>
        scientific("margin", value).flatMap { input =>
          val n = input.normalized
          if n.coefficient < 0 then Left(failure(Error.NegativeMargin(value)))
          else if n.exponent < -19 || n.exponent > 19 then
            Left(failure(Error.MarginPrecision(n.exponent)))
          else if n.exponent < 0 then
            Right(Rational(n.coefficient, BigInt(10).pow((-n.exponent).toInt)))
          else Right(Rational(n.coefficient * BigInt(10).pow(n.exponent.toInt)))
        }
    parsed.flatMap { r =>
      Either.cond(
        r.numerator >= 0 && r.numerator <= r.denominator &&
          r.numerator <= maxWord64 && r.denominator <= maxWord64,
        r,
        failure(Error.MarginBounds(r))
      )
    }

  private def hash(c: ACursor, field: String, size: Int): Either[ConfigError, Bytes] =
    GenesisState.get[String](c, field).flatMap { text =>
      Bytes.fromHex(text).leftMap(e => ConfigError(ConfigProblem.Hex(e))).flatMap { bytes =>
        Either.cond(
          bytes.size == size,
          bytes,
          ConfigError(ConfigProblem.ByteLength(field, size, bytes.size))
        )
      }
    }

  private def credential(c: ACursor): Either[ConfigError, Credential] =
    // Each alternative is attempted after any failure, not just an absent field
    // (`Credential.hs:159-165`, f649f975); script credentials precede key credentials.
    hash(c, "scriptHash", 28)
      .orElse(hash(c, "script hash", 28))
      .map(Credential.Script(_))
      .orElse(hash(c, "keyHash", 28).orElse(hash(c, "key hash", 28)).map(Credential.Key(_)))

  private def account(c: ACursor): Either[ConfigError, RewardAccount] =
    for
      name <- GenesisState.get[String](c, "network")
      network <- name match
        case "Testnet" => Right(0)
        case "Mainnet" => Right(1)
        case other =>
          Left(ConfigError(ConfigProblem.Selection("network", other, Set("Testnet", "Mainnet"))))
      cred <- credential(c.downField("credential"))
    yield RewardAccount(network, cred)

  def read(id: String, value: Json): Either[ConfigError, (Bytes, PoolParams)] =
    val c = value.hcursor
    for
      key <- GenesisState.hex28(id)
      operator <- hash(c, "poolId", 28).orElse(hash(c, "publicKey", 28))
      vrf <- hash(c, "vrf", 32)
      pledge <- integerField(c, "pledge")
      cost <- integerField(c, "cost")
      ratio <- GenesisState.get[Json](c, "margin").flatMap(margin)
      reward <- account(c.downField("accountAddress")).orElse(account(c.downField("rewardAccount")))
      owners <- GenesisState
        .get[Vector[String]](c, "owners")
        .flatMap(_.traverse(GenesisState.hex28))
      fields <- GenesisPoolFields.read(c)
    yield key -> PoolParams(
      operator,
      vrf,
      pledge,
      cost,
      ratio,
      reward,
      owners.toSet,
      fields._1,
      fields._2
    )
