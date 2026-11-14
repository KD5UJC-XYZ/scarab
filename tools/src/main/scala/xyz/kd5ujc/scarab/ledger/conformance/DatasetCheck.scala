// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.conformance

import cats.syntax.all.*
import io.circe.Json
import xyz.kd5ujc.scarab.codec.Bytes

/** The verdict on one sample of the cardano-cbor-dataset, as its `VERIFICATION.md` states it: a
  * sample of `valid` or `manual-valid` must decode and its re-encoding must be its `.expected.cbor`
  * (byte for byte for a byte-exact rule, for the others after [[CborNormalForm]] of both); any
  * other sample must be refused.
  */
object DatasetCheck:
  /** The expectation class of a sample, from the category directory it is in. */
  enum Kind:
    case Valid, Invalid

  object Kind:
    private val zap = "invalid-zap-[0-9]+".r

    def of(category: String): Option[Kind] = category match
      case "valid" | "manual-valid" => Some(Valid)
      case "manual-invalid"         => Some(Invalid)
      case zap()                    => Some(Invalid)
      case _                        => None

  /** The category of a failure in the report format of the dataset. */
  enum Category:
    case Decode, Encode

    def render: String = this match
      case Decode => "decode"
      case Encode => "encode"

  enum Outcome:
    case Met
    case Failed(category: Category, detail: String)

    /** The report's value: `true`, or `<category>: <message>`. */
    def json: Json = this match
      case Met                      => Json.True
      case Failed(category, detail) => Json.fromString(s"${category.render}: $detail")

  /** The rules whose re-encoding must be the input bytes themselves, as `mkByteExactRule` and
    * `mkByteExactAnnotatorRule` name them in the dataset's `app/Generation/EraSpec.hs`.
    */
  val byteExact: Set[String] = Set(
    "header",
    "transaction_body",
    "transaction_witness_set",
    "native_script",
    "plutus_data",
    "redeemers",
    "auxiliary_data"
  )

  /** The outcome of the sample `input` of `rule` (none when Scarab maps no rule of that name), of
    * expectation `kind`, whose expected re-encoding is `expected` when the corpus has one.
    */
  def check(
      ruleName: String,
      rule: Option[DatasetRule],
      kind: Kind,
      input: Bytes,
      expected: Option[Bytes]
  ): Outcome =
    rule match
      case None =>
        Outcome.Failed(Category.Decode, Unmapped.NoDecoder.message)
      case Some(DatasetRule.Unmapped(why)) =>
        Outcome.Failed(Category.Decode, why.message)
      case Some(DatasetRule.Mapped(_, _, writing, run)) =>
        val result = Either.catchNonFatal(run(input)).leftMap(Refusal.Defect(_)).flatten
        (kind, result) match
          case (Kind.Invalid, Left(Refusal.Defect(failure))) =>
            Outcome.Failed(
              Category.Decode,
              s"refused by an exception: ${Refusal.Defect(failure).message}"
            )
          case (Kind.Invalid, Left(_)) => Outcome.Met
          case (Kind.Invalid, Right(_)) =>
            Outcome.Failed(Category.Decode, "succeeded when expected to fail")
          case (Kind.Valid, Left(refusal)) => Outcome.Failed(Category.Decode, refusal.message)
          case (Kind.Valid, Right(Left(unwritten))) =>
            Outcome.Failed(Category.Encode, unwritten.message)
          case (Kind.Valid, Right(Right(written))) =>
            compare(byteExact.contains(ruleName), writing, written, expected)

  private def compare(
      exact: Boolean,
      writing: Writing,
      written: Bytes,
      expected: Option[Bytes]
  ): Outcome =
    expected match
      case None =>
        Outcome.Failed(Category.Encode, "the corpus has no expected bytes for this sample")
      case Some(reference) if exact =>
        if written == reference then Outcome.Met
        else
          Outcome.Failed(
            Category.Encode,
            s"re-encoded bytes differ from the expected bytes of this byte-exact rule (${writing.render})"
          )
      // both sides in the normal form: the expected bytes are the reference's own normal form,
      // which keeps a rational as its encoder wrote it
      case Some(reference) =>
        (CborNormalForm.of(written), CborNormalForm.of(reference)) match
          case (Left(error), _) =>
            Outcome.Failed(
              Category.Encode,
              s"the re-encoding does not read as CBOR: $error (${writing.render})"
            )
          case (_, Left(error)) =>
            Outcome.Failed(Category.Encode, s"the expected bytes do not read as CBOR: $error")
          case (Right(normal), Right(expected)) if normal == expected => Outcome.Met
          case _ =>
            Outcome.Failed(
              Category.Encode,
              s"re-encoded bytes do not match the expected bytes after normalization (${writing.render})"
            )
