// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.ledger.{ClusterIntervalTransfer, ValidityInterval}

/** Explicit retained local evidence only; no reference evaluation slot is inferred from a tip. */
class ClusterIntervalEvidenceSuite extends munit.FunSuite:
  sys.env.get("CLUSTER_INTERVAL_EVIDENCE").foreach { directory =>
    val dir = Path.of(directory)
    import ReferenceJson.{field, string, uint, array}
    import ReferenceJson.Json.Lit
    def get[A](value: Either[String, A]): A = value.fold(fail(_), identity)
    def raw(name: String): Bytes =
      val path = dir.resolve(name)
      require(Files.size(path) <= 4194304)
      Bytes.fromArray(Files.readAllBytes(path))
    def json(name: String) = ReferenceJson.parse(raw(name))
    def hex(name: String): Bytes = get(Bytes.fromHex(new String(raw(name).toArray, "UTF-8").trim))
    def bound(value: ReferenceJson.Json, key: String): Option[BigInt] = field(value, key) match
      case Lit("null") => None
      case x           => Some(uint(x))
    def load = ClusterTransferCommand.load(dir)
    def blocks =
      get(ClusterHeaderObservation.captures(dir.resolve("scala-transfer.md"))).map(_.block)
    def inclusion = get(ClusterIntervalTransfer.bind(load.context, load.tx, blocks))
    def retainedFields(original: Bytes): Vector[(Bytes, Bytes)] =
      val root = get(Cbor.decode(original))
      val body = root.value match
        case Value.Arr(Vector(body, _, _, _)) => body
        case _                                => fail("four-field envelope required")
      body.value match
        case Value.Map(fields) =>
          fields
            .filter((key, _) => key.value != Value.UInt(3) && key.value != Value.UInt(8))
            .map((key, value) => (key.original, value.original))
        case _ => fail("body map required")

    test(
      "historical interval original inclusion still binds; narrower whole checkpoint is Unsupported"
    ) {
      val expected = field(json("interval-plan.md"), "positive")
      assertEquals(inclusion.interval.interval.lower, bound(expected, "lower"))
      assertEquals(inclusion.interval.interval.upper, bound(expected, "upper"))
      assert(inclusion.interval.satisfied)
      val in = load
      assertEquals(
        ClusterIntervalTransfer
          .compare(in.context, in.minimumOutputParameters, in.pre, in.post, in.tx, blocks),
        Left("Unsupported(unsupported payment address kind/network/length)")
      )
    }
    test(
      "historical command receipt retains its original profile and original inclusion identity"
    ) {
      val receipt = json("interval-inclusion.md")
      assertEquals(
        string(field(receipt, "profile")),
        "conway-pv9-cluster-ada-interval-transition-v1"
      )
      assertEquals(string(field(receipt, "slotSource")), "containing-block")
      assertEquals(uint(field(receipt, "slot")), inclusion.interval.slot)
      assertEquals(string(field(receipt, "blockHash")), inclusion.blockHash.hex)
      assertEquals(
        string(field(receipt, "transactionId")),
        get(ValidityInterval.decode(load.tx)).transactionId.hex
      )
      assertEquals(field(receipt, "satisfied"), Lit("true"))
      assertEquals(field(receipt, "fullLedgerValidated"), Lit("false"))
    }
    for label <- Vector("expired", "not-yet-valid") do
      test(label + " original negative bytes and exact submission failure remain bound") {
        val tx = hex(label + "-transaction-cbor.md")
        val decoded = get(ValidityInterval.decode(tx))
        val plan = json("interval-plan.md")
        val expected = field(plan, label)
        assertEquals(decoded.lower, bound(expected, "lower"))
        assertEquals(decoded.upper, bound(expected, "upper"))
        assertEquals(retainedFields(tx), retainedFields(load.tx))
        assert(decoded.transactionId != get(ValidityInterval.decode(load.tx)).transactionId)
        // This is a supplied tip-slot predicate check, not the mempool evaluation slot.
        assert(
          !get(ValidityInterval.atSlot(decoded, uint(field(plan, "observedPreTipSlot")))).satisfied
        )
        val receipt = json(label + "-result.md")
        assertEquals(string(field(receipt, "expectedReason")), "OutsideValidityIntervalUTxO")
        assertEquals(string(field(receipt, "referenceRejectionLayer")), "ledger-rule")
        assert(uint(field(receipt, "returncode")) > 0)
        assert(
          Vector("stdout", "stderr")
            .exists(k => string(field(receipt, k)).contains("OutsideValidityIntervalUTxO"))
        )
        for k <- Vector(
            "passed",
            "recognizedReferenceRejection",
            "recognizedLedgerRejection",
            "stableStateVerified"
          )
        do assertEquals(field(receipt, k), Lit("true"))
        for k <- Vector(
            "singleAcquiredSnapshot",
            "referenceEvaluationSlotEstablished",
            "exactReferenceBoundaryProof"
          )
        do assertEquals(field(receipt, k), Lit("false"))
        assertEquals(string(field(receipt, "transactionId")), decoded.transactionId.hex)
        assertEquals(
          string(field(receipt, "transactionCborSha256")),
          ClusterNegativeObservation.sha256(tx)
        )
        val name = string(field(receipt, "submissionEvidence"))
        assert(name.matches("scenario-submission-[0-9]{1,6}\\.md"))
        val submitted = json(name)
        for k <- Vector("returncode", "stdout", "stderr", "transactionCborSha256") do
          assertEquals(field(receipt, k), field(submitted, k))
        assertEquals(field(submitted, "transactionFileUnchanged"), Lit("true"))
        assertEquals(
          array(field(submitted, "command")).map(string),
          Vector(
            "cardano-cli",
            "conway",
            "transaction",
            "submit",
            "--tx-file",
            "/work/interval-" + label + ".signed",
            "--testnet-magic",
            "1082026",
            "--socket-path",
            "/work/env/socket/node3/sock"
          )
        )
      }
      test(label + " separately acquired negative state remains at the positive pre-state") {
        val in = load
        val referenceTip = array(json("pre-tips.md")).head
        for phase <- Vector("pre", "post") do
          val prefix = label + "-" + phase
          assertEquals(hex(prefix + "-utxo-cbor.md"), in.pre)
          for kind <- Vector("parameters", "ledger-state") do
            assertEquals(json(prefix + "-" + kind + ".md"), json("pre-" + kind + ".md"))
          val tips = array(json(prefix + "-tips.md"))
          assert(tips.size >= 2 && tips.size <= 32)
          for tip <- tips; key <- Vector("hash", "slot", "epoch", "era", "block") do
            assertEquals(field(tip, key), field(referenceTip, key))
      }
  }
