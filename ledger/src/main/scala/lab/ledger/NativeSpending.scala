// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as V}

/** Checked ADA payment-credential binding. Only enterprise native-script inputs and ordinary
  * key-payment inputs/outputs; no datum, reference script, mint, or phase-2 path.
  */
object NativeSpending:
  val ProfileId = "conway-pv9-ada-native-spending-v1"
  enum Error:
    case UnsupportedProfile(detail: String)
    case DecodeRejected(detail: String)
    case Malformed(detail: String)
    case ResourceLimit(detail: String)
    case UnsupportedInput(input: TxIn, detail: String)
    case UnresolvedInputs(inputs: Set[TxIn])
    case MissingKeys(hashes: Set[Bytes])
    case MissingScripts(hashes: Set[Bytes])
    case WrongScriptHashes(missing: Set[Bytes], extra: Set[Bytes])
    case ExtraneousScripts(hashes: Set[Bytes])
    case FailedScripts(hashes: Set[Bytes])
    case InvalidSignature
    case WitnessProfileRejected(detail: String)
    case CaptureRejected(detail: String)
    case OutsideValidityInterval
    case MinimumOutputFailed
    case ValueNotConserved(consumed: BigInt, produced: BigInt)
    case FeeTooSmall(supplied: BigInt, minimum: BigInt)
    case TransactionTooLarge(size: BigInt, maximum: BigInt)
    case StateMismatch(detail: String)
  import Error.*
  private[ledger] final case class Output(
      address: Bytes,
      coin: BigInt,
      script: Boolean,
      credential: Bytes
  )
  final class Admission private[NativeSpending] (
      val interval: ValidityInterval.Interval,
      val verifiedKeys: NativeScript.VerifiedKeys,
      val requiredKeys: Set[Bytes],
      val requiredScripts: Set[Bytes],
      val scriptInputs: Map[TxIn, Bytes],
      val scriptEvaluations: Vector[NativeScript.Evaluation],
      val memoSize: BigInt,
      private[ledger] val projection: Coverage.Projection,
      private[ledger] val semanticEnvelope: Bytes,
      private[ledger] val consumedCoin: BigInt
  ):
    val profileId = ProfileId
    val credentialBound = true
    val fullLedgerValidated = false

  private[ledger] def decode(raw: Bytes): Either[Error, Node] =
    Cbor.decode(raw, Cbor.Limits(1048576, 16, 65536, 1048576)).left.map(DecodeRejected.apply)

  private[ledger] def snapshot(raw: Bytes): Either[Error, Map[TxIn, Node]] =
    decode(raw).flatMap { root =>
      root.value match
        case V.Map(xs) if xs.size <= 4096 =>
          xs.foldLeft[Either[Error, Map[TxIn, Node]]](Right(Map.empty)) {
            case (acc, (key, value)) =>
              for
                seen <- acc
                input <- key.value match
                  case V.Arr(Vector(id, index)) =>
                    (id.value, index.value) match
                      case (V.ByteString(b), V.UInt(i)) =>
                        TxIn.create(b, i).left.map(e => Malformed(e.toString))
                      case _ => Left(Malformed("UTxO reference requires bytes and uint16"))
                  case _ => Left(Malformed("UTxO reference requires two fields"))
                _ <- Either.cond(!seen.contains(input), (), Malformed("duplicate UTxO reference"))
              yield seen.updated(input, value)
          }
        case V.Map(_) => Left(ResourceLimit("snapshot exceeds 4096 entries"))
        case _        => Left(Malformed("UTxO map required"))
    }

  private[ledger] def output(node: Node, allowScript: Boolean): Either[String, Output] =
    val pair = node.value match
      case V.Arr(Vector(address, coin)) => Right((address, coin))
      case V.Map(fields)
          if fields.size == 2 && fields.map(_._1.value).toSet == Set(V.UInt(0), V.UInt(1)) =>
        Right(
          (fields.find(_._1.value == V.UInt(0)).get._2, fields.find(_._1.value == V.UInt(1)).get._2)
        )
      case _ => Left("only address/coin outputs, without datum/reference script, supported")
    for
      selected <- pair
      (address, amount) = selected
      bytes <- address.value match
        case V.ByteString(b) if b.size > 0 && (address.original.value.head & 31) != 31 => Right(b)
        case _ => Left("definite address bytes required")
      kind = (bytes.value.head & 0xff) >>> 4
      network = bytes.value.head & 15
      _ <- Either.cond(
        network == 0 &&
          ((kind == 0 && bytes.size == 57) || (kind == 6 && bytes.size == 29) ||
            (allowScript && kind == 7 && bytes.size == 29)),
        (),
        "unsupported payment address kind/network/length"
      )
      coin <- amount.value match
        case V.UInt(n) => Right(n)
        case _         => Left("scalar ADA required, including no encoded empty multiasset map")
    yield Output(bytes, coin, kind == 7, Bytes(bytes.value.slice(1, 29)))

  /** Reuse historical input/output semantic checks only. Original authentication, script hashes,
    * IDs and memo sizing never consume this interval/witness-free projection envelope.
    */
  private def semantic(root: Node): Either[Error, Bytes] = root.value match
    case V.Arr(Vector(body, _, _, _)) =>
      body.value match
        case V.Map(fields) =>
          val retained =
            fields.filter((key, _) => Set(V.UInt(0), V.UInt(1), V.UInt(2)).contains(key.value))
          Right(
            Bytes(
              Vector(0x84.toByte, 0xa3.toByte) ++
                retained.flatMap((key, value) => key.original.value ++ value.original.value) ++
                Vector(0xa0.toByte, 0xf5.toByte, 0xf6.toByte)
            )
          )
        case _ => Left(Malformed("body map required"))
    case _ => Left(Malformed("four-field transaction required"))

  def check(original: Bytes, resolved: Bytes): Either[Error, Admission] =
    for
      root <- decode(original)
      interval <- ValidityInterval.decode(original).left.map(UnsupportedProfile.apply)
      projectedRaw <- semantic(root)
      tx <- Coverage.decode(projectedRaw).left.map {
        case CoverageError.TypedUnsupported(reason) => UnsupportedProfile(reason)
        case other                                  => Malformed(other.toString)
      }
      _ <- Either.cond(
        tx.inputs.size <= 128 && tx.outputs.size <= 128,
        (),
        ResourceLimit("128 inputs/outputs maximum")
      )
      _ <- tx.outputs.foldLeft[Either[Error, Unit]](Right(())) { (acc, out) =>
        for
          _ <- acc; node <- decode(out.original);
          _ <- output(node, false).left.map(UnsupportedProfile.apply)
        yield ()
      }
      before <- snapshot(resolved)
      missing = tx.inputs -- before.keySet
      _ <- Either.cond(missing.isEmpty, (), UnresolvedInputs(missing))
      consumed <- tx.inputs.toVector
        .sortBy(_.toString)
        .foldLeft[Either[Error, Map[TxIn, Output]]](Right(Map.empty)) { (acc, input) =>
          for
            previous <- acc; out <- output(before(input), true).left.map(UnsupportedInput(input, _))
          yield previous.updated(input, out)
        }
      scriptInputs = consumed.collect { case (input, out) if out.script => input -> out.credential }
      requiredScripts = scriptInputs.values.toSet
      requiredKeys = consumed.values.filterNot(_.script).map(_.credential).toSet
      _ <- Either.cond(
        requiredScripts.nonEmpty,
        (),
        UnsupportedProfile("at least one enterprise native-script input required")
      )
      inspection <- NativeScriptWitnesses.inspect(original).left.map {
        case "native witness signature rejected" => InvalidSignature
        case other                               => WitnessProfileRejected(other)
      }
      received = inspection.scripts.map(_.hash).toSet
      failed = inspection.evaluations
        .filter(e => requiredScripts.contains(e.scriptHash) && !e.satisfied)
        .map(_.scriptHash)
        .toSet
      _ <- Either.cond(failed.isEmpty, (), FailedScripts(failed))
      absent = requiredScripts -- received
      extra = received -- requiredScripts
      _ <-
        if absent.nonEmpty && extra.nonEmpty then Left(WrongScriptHashes(absent, extra))
        else if absent.nonEmpty then Left(MissingScripts(absent))
        else if extra.nonEmpty then Left(ExtraneousScripts(extra))
        else Right(())
      missingKeys = requiredKeys -- inspection.verifiedKeys.hashes
      _ <- Either.cond(missingKeys.isEmpty, (), MissingKeys(missingKeys))
      witnessBytes <- root.value match
        case V.Arr(Vector(_, witnesses, _, _)) => Right(witnesses.original)
        case _                                 => Left(Malformed("four-field transaction required"))
      size <- FeeSize
        .componentSize(BigInt(interval.originalBody.size), BigInt(witnessBytes.size))
        .left
        .map(e => ResourceLimit(e.toString))
    yield new Admission(
      interval,
      inspection.verifiedKeys,
      requiredKeys,
      requiredScripts,
      scriptInputs,
      inspection.evaluations,
      size,
      tx,
      projectedRaw,
      consumed.values.map(_.coin).sum
    )
