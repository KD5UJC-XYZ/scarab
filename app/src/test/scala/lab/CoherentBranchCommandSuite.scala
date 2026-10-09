// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.unsafe.implicits.global
import java.nio.file.Path
import lab.cbor.Bytes

class CoherentBranchCommandSuite extends munit.FunSuite:
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private val tip = s"""{"era":"Conway","hash":"${"12" * 32}","slot":12,"block":3,"epoch":0}"""
  private def originals: Map[String, Bytes] = Map(
    "post-utxo-cbor.md" -> raw("a0\n"),
    "post-ledger-state.md" -> raw(
      """{"lastEpoch":0,"stateBefore":{"esLState":{"utxoState":{"fees":100}}}}"""
    ),
    "post-protocol-state.md" -> raw(s"""{"lastSlot":12,"oCertCounters":{"${"34" * 28}":0}}"""),
    "post-tips.md" -> raw(s"[$tip,$tip]")
  )
  private def manifest(files: Map[String, Bytes]): Bytes = raw(
    "format\tcoherent-branch-oracle-v1\n" + CoherentBranchCommand.sources.toVector
      .sortBy(_._1)
      .map((key, file) => key + "\t" + ClusterHeaderObservation.sha256(files(file)).hex)
      .mkString("\n") + "\n"
  )
  private def bind(files: Map[String, Bytes]) =
    CoherentBranchCommand.bindOracle(manifest(files), files)
  test("success report cannot be manufactured or copied") {
    assert(
      compileErrors(
        "new lab.CoherentBranchCommand.Report(null, null, null, null, null, null, null, null)"
      ).nonEmpty
    )
    assert(compileErrors("val r: lab.CoherentBranchCommand.Report = null; r.copy()").nonEmpty)
  }
  test("strict four-source oracle owns pinned bytes and stable complete point") {
    val o = bind(originals).fold(fail(_), identity)
    assertEquals(o.utxo.hex, "a0")
    assertEquals(o.fees, BigInt(100))
    assertEquals(o.tip.slot, BigInt(12))
    assertEquals(o.tip.blockNo, BigInt(3))
    assertEquals(o.counters.size, 1)
    assertEquals(o.manifestDigest, ClusterHeaderObservation.sha256(manifest(originals)))
  }
  test("manifest duplicate, extra, missing, wrong format and malformed UTF-8 reject") {
    val m = new String(manifest(originals).toArray, "UTF-8")
    Vector(
      raw(m + "format\tcoherent-branch-oracle-v1\n"),
      raw(m + "extra\tvalue\n"),
      raw(m.linesIterator.filterNot(_.startsWith("postTipsSha256")).mkString("\n")),
      raw(m.replace("coherent-branch-oracle-v1", "coherent-branch-input-v1")),
      Bytes(Vector(0xc3.toByte, 0x28.toByte))
    ).foreach(b => assert(CoherentBranchCommand.bindOracle(b, originals).isLeft))
  }
  test("pins reject unapproved content changes and noncanonical digests") {
    val changed = originals.updated("post-utxo-cbor.md", raw("a1"))
    assert(CoherentBranchCommand.bindOracle(manifest(originals), changed).isLeft)
    val m = new String(manifest(originals).toArray, "UTF-8")
    val pin = ClusterHeaderObservation.sha256(originals("post-utxo-cbor.md")).hex
    assert(CoherentBranchCommand.bindOracle(raw(m.replace(pin, pin.toUpperCase)), originals).isLeft)
    assert(CoherentBranchCommand.bindOracle(manifest(originals), originals - "post-tips.md").isLeft)
  }
  test("manifest and original source size bounds reject before parsing") {
    assert(CoherentBranchCommand.bindOracle(raw("x" * 8193), originals).isLeft)
    assert(bind(originals.updated("post-utxo-cbor.md", raw("x" * 4194305))).isLeft)
    assert(bind(originals.updated("post-utxo-cbor.md", Bytes.empty)).isLeft)
  }
  test("rehashed unstable, undersized or oversized brackets and source disagreement reject") {
    Vector(
      s"[$tip]",
      Vector.fill(33)(tip).mkString("[", ",", "]"),
      s"[$tip,${tip.replace("\"slot\":12", "\"slot\":13")}]"
    )
      .foreach(s => assert(bind(originals.updated("post-tips.md", raw(s))).isLeft))
    assert(
      bind(
        originals.updated("post-protocol-state.md", raw("""{"lastSlot":13,"oCertCounters":{}}"""))
      ).isLeft
    )
    assert(
      bind(
        originals.updated(
          "post-ledger-state.md",
          raw("""{"lastEpoch":1,"stateBefore":{"esLState":{"utxoState":{"fees":100}}}}""")
        )
      ).isLeft
    )
  }
  test("rehashed duplicate JSON counters and oversized counters reject") {
    val key = "34" * 28
    assert(
      bind(
        originals.updated(
          "post-protocol-state.md",
          raw(s"""{"lastSlot":12,"oCertCounters":{"$key":0,"$key":1}}""")
        )
      ).isLeft
    )
    assert(
      bind(
        originals.updated(
          "post-protocol-state.md",
          raw(s"""{"lastSlot":12,"oCertCounters":{"$key":18446744073709551616}}""")
        )
      ).isLeft
    )
  }
  test("argument errors return failure without attempting observation") {
    CoherentBranchCommand.run(Nil).map(code => assertEquals(code.code, 2)).unsafeToFuture()
  }
  (sys.env.get("COHERENT_POSITIVE_INPUT"), sys.env.get("COHERENT_POSITIVE_ORACLE")) match
    case (Some(input), Some(oracle)) =>
      test(
        "unaltered private reference inputs independently publish then match external oracle and roll back/reapply"
      ) {
        CoherentBranchCommand
          .observe(Path.of(input), Path.of(oracle))
          .map { result =>
            val report = result.fold(e => fail(e.toString), identity)
            val rendered = ReferenceJson.parse(raw(CoherentBranchCommand.render(report)))
            assertEquals(
              ReferenceJson.field(rendered, "referencePostStateMatched"),
              ReferenceJson.Json.Lit("true")
            )
            assertEquals(
              ReferenceJson.field(rendered, "wholeTupleRollbackReapply"),
              ReferenceJson.Json.Lit("true")
            )
            assertEquals(
              ReferenceJson.field(rendered, "fullLedgerValidated"),
              ReferenceJson.Json.Lit("false")
            )
            assert(report.initialStateId != report.finalStateId)
          }
          .unsafeToFuture()
      }
      test("actual supported input reaches oracle rejection only after independent publication") {
        val missingOracle = Path.of(oracle).resolve("missing-oracle-order-regression")
        assert(java.nio.file.Files.notExists(missingOracle))
        CoherentBranchCommand
          .observe(Path.of(input), missingOracle)
          .map { result =>
            result match
              case Left(CoherentBranchCommand.Failure.Rejected("oracle", _)) => ()
              case other                                                     => fail(other.toString)
          }
          .unsafeToFuture()
      }
      sys.env.get("COHERENT_BRANCH_EVIDENCE").foreach { oldInput =>
        test(
          "unaltered Byron checkpoint remains ledger Unsupported before missing oracle is read"
        ) {
          val missingOracle = Path.of(oracle).resolve("missing-oracle-order-regression")
          assert(java.nio.file.Files.notExists(missingOracle))
          CoherentBranchCommand
            .observe(Path.of(oldInput), missingOracle)
            .map { result =>
              assertEquals(
                result.left.toOption,
                Some(
                  CoherentBranchCommand.Failure.Unsupported(
                    "ledger",
                    "unsupported payment address kind/network/length"
                  )
                )
              )
            }
            .unsafeToFuture()
        }
      }
    case _ => ()
