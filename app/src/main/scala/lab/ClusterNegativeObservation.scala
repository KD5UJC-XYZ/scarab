// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, IOApp}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import lab.cbor.Bytes
import lab.ledger.{ClusterTransfer, Coverage}

/** Offline selected-predicate comparison. Never submits or connects to a reference node. */
object ClusterNegativeObservation extends IOApp:
  enum Rejection:
    case MissingRequiredKeys, UnresolvedInputs
  enum Outcome:
    case Rejected(reason: Rejection)
    case UnexpectedAcceptance
    case Unsupported(detail: String)

  final case class Scenario(label: String, reason: Rejection, reference: String, usePost: Boolean)
  val scenarios = Vector(
    Scenario("wrong-key", Rejection.MissingRequiredKeys, "MissingVKeyWitnessesUTXOW", false),
    Scenario("repeated-included", Rejection.UnresolvedInputs, "BadInputsUTxO", true),
    Scenario("conflicting-spend", Rejection.UnresolvedInputs, "BadInputsUTxO", true)
  )

  def classify(result: Either[String, ClusterTransfer.Receipt]): Outcome = result match
    case Left("missing required witness keys") => Outcome.Rejected(Rejection.MissingRequiredKeys)
    case Left("unresolved spending inputs")    => Outcome.Rejected(Rejection.UnresolvedInputs)
    case Left(other)                           => Outcome.Unsupported(other)
    case Right(_)                              => Outcome.UnexpectedAcceptance

  def evaluate(context: ClusterTransfer.Context, state: Bytes, transaction: Bytes): Outcome =
    // These two failures precede transition/output/fee-pot comparison. Reusing the checked
    // positive context does not attribute an advancing point to this paused negative window.
    classify(ClusterTransfer.compare(context, state, state, transaction))

  private def get[A](value: Either[String, A]): A =
    value.fold(e => throw new IllegalArgumentException(e), identity)
  private def read(path: Path): Bytes =
    require(Files.size(path) <= 4194304, "negative evidence file exceeds bound")
    Bytes.fromArray(Files.readAllBytes(path))
  private def hex(path: Path): Bytes =
    get(Bytes.fromHex(new String(read(path).toArray, "UTF-8").trim))
  private def json(path: Path): ReferenceJson.Json = ReferenceJson.parse(read(path))
  private[lab] def sha256(raw: Bytes): String =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(raw.toArray)).hex

  private[lab] def referenceRejection(
      receipt: ReferenceJson.Json,
      scenario: Scenario,
      transactionId: Bytes,
      transaction: Bytes
  ): Unit =
    import ReferenceJson.{field, string, uint}
    import ReferenceJson.Json.Lit
    require(string(field(receipt, "scope")) == "reference-local-submission-observation")
    require(uint(field(receipt, "returncode")) > 0, "reference submission must fail")
    require(field(receipt, "passed") == Lit("true"), "scenario guards did not pass")
    require(field(receipt, "stableStateVerified") == Lit("true"), "stable state unverified")
    require(field(receipt, "singleAcquiredSnapshot") == Lit("false"), "atomic claim unsupported")
    require(string(field(receipt, "transactionId")) == transactionId.hex, "transaction ID differs")
    require(
      string(field(receipt, "transactionCborSha256")) == sha256(transaction),
      "complete submitted transaction digest differs"
    )
    require(
      string(field(receipt, "expectedReason")) == scenario.reference,
      "reference class differs"
    )
    require(
      (string(field(receipt, "stdout")) + string(field(receipt, "stderr")))
        .contains(scenario.reference),
      "reference rejection constructor missing"
    )

  def inspect(dir: Path): Vector[(String, Rejection)] =
    val input = ClusterTransferCommand.load(dir)
    get(ClusterTransfer.compare(input.context, input.pre, input.post, input.tx))
    scenarios.map { scenario =>
      val state = if scenario.usePost then input.post else input.pre
      val baseline = if scenario.usePost then "post" else "pre"
      val transaction = hex(dir.resolve(scenario.label + "-transaction-cbor.md"))
      val decoded = get(Coverage.decode(transaction).left.map(_.toString))
      val original = get(Coverage.decode(input.tx).left.map(_.toString))
      if scenario.label == "repeated-included" then
        require(transaction == input.tx, "repeated transaction bytes differ")
      else if scenario.label == "wrong-key" then
        require(decoded.body.bytes == original.body.bytes, "wrong-key body differs")
      else
        require(
          decoded.body.hash.bytes != original.body.hash.bytes,
          "conflict identity must differ"
        )
        require(decoded.inputs == original.inputs, "conflicting spending inputs differ")
      referenceRejection(
        json(dir.resolve(scenario.label + "-result.md")),
        scenario,
        decoded.body.hash.bytes,
        transaction
      )
      val receipt = json(dir.resolve(scenario.label + "-result.md"))
      val evidenceName = ReferenceJson.string(ReferenceJson.field(receipt, "submissionEvidence"))
      require(
        evidenceName.matches("scenario-submission-[0-9]{1,6}\\.md"),
        "invalid submission evidence path"
      )
      val submission = json(dir.resolve(evidenceName))
      for key <- Vector("transactionCborSha256", "returncode", "stdout", "stderr") do
        require(
          ReferenceJson.field(submission, key) == ReferenceJson.field(receipt, key),
          "submission receipt differs: " + key
        )
      require(
        ReferenceJson.field(submission, "transactionFileUnchanged") == ReferenceJson.Json.Lit(
          "true"
        ),
        "submitted file stability unverified"
      )
      val txFile = scenario.label match
        case "wrong-key"         => "/work/scenario-wrong-key.signed"
        case "repeated-included" => "/work/transfer.signed"
        case "conflicting-spend" => "/work/scenario-conflict.signed"
        case _                   => throw new IllegalArgumentException("unknown scenario")
      val command =
        ReferenceJson.array(ReferenceJson.field(submission, "command")).map(ReferenceJson.string)
      require(
        command == Vector(
          "cardano-cli",
          "conway",
          "transaction",
          "submit",
          "--tx-file",
          txFile,
          "--testnet-magic",
          input.context.networkMagic.toString,
          "--socket-path",
          "/work/env/socket/node3/sock"
        ),
        "submission command differs"
      )
      for phase <- Vector("pre", "post") do
        val prefix = scenario.label + "-" + phase
        require(hex(dir.resolve(prefix + "-utxo-cbor.md")) == state, "negative UTxO bytes differ")
        for kind <- Vector("parameters", "ledger-state") do
          require(
            json(dir.resolve(prefix + "-" + kind + ".md")) == json(
              dir.resolve(baseline + "-" + kind + ".md")
            ),
            "negative state source differs: " + kind
          )
        val expected = ReferenceJson.array(json(dir.resolve(baseline + "-tips.md"))).head
        val tips = ReferenceJson.array(json(dir.resolve(prefix + "-tips.md")))
        require(tips.size >= 2 && tips.size <= 32, "bounded negative tip brackets required")
        for tip <- tips; key <- Vector("hash", "slot", "epoch", "era", "block") do
          require(
            ReferenceJson.field(tip, key) == ReferenceJson.field(expected, key),
            "negative point differs"
          )
      require(
        evaluate(input.context, state, transaction) == Outcome.Rejected(scenario.reason),
        "selected Scala rejection differs or is unsupported"
      )
      scenario.label -> scenario.reason
    }

  def run(args: List[String]): IO[ExitCode] = args match
    case List(directory) =>
      IO.blocking(inspect(Path.of(directory)))
        .flatMap { rows =>
          IO.println(
            rows
              .map { case (label, reason) =>
                s"""{"scenario":"$label","scalaRejection":"$reason","matched":true,"scope":"offline-selected-negative-predicates","fullLedgerValidated":false,"invalidBlockRejection":false,"referenceSnapshotAtomic":false}"""
              }
              .mkString("\n")
          ).as(ExitCode.Success)
        }
        .handleErrorWith(e => IO.println("ERROR: " + e.getMessage).as(ExitCode(2)))
    case _ => IO.println("usage: lab.ClusterNegativeObservation EVIDENCE_DIRECTORY").as(ExitCode(2))
