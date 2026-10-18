// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import io.circe.{Decoder, HCursor, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.consensus.state.PBftState
import xyz.kd5ujc.scarab.ledger.{Block, ByronGenesisFile, TxIn}
import xyz.kd5ujc.scarab.ledger.byron.*

/** Actual unchanged Haskell registration, proposal/transaction admission and block-ledger APIs,
  * composed with authentic original-byte header/certificate crypto gates. This is controlled
  * synthetic state near the width boundaries, not a PBFT or protocol-adoption history.
  */
class ByronRegistrationFixtureSuite extends FunSuite:
  private enum Verdict:
    case Accepted, InvalidVersion, InvalidSignature, Unauthorised, DecodeFailure,
      TransactionFailure,
      OtherFailure, NotRun

  private given Decoder[Verdict] = Decoder.decodeString.emap { name =>
    Verdict.values.find(_.toString == name).toRight(s"unknown reference verdict $name")
  }

  private val directory = Path.of("fixtures/byron-version/full-registration")
  private def json(file: String): Json =
    parse(Files.readString(directory.resolve(file))).fold(e => fail(e.message), identity)
  private def field[A: Decoder](cursor: HCursor, name: String): A =
    cursor.get[A](name).fold(e => fail(e.message), identity)
  private def bytes(cursor: HCursor, name: String): Bytes =
    Bytes.fromHex(field[String](cursor, name)).fold(e => fail(e.toString), identity)
  private def verdict(cursor: HCursor, name: String): Verdict =
    cursor.downField(name).get[Verdict]("verdict").fold(e => fail(e.message), identity)
  private def version(parts: Vector[BigInt]): ByronVersion = parts match
    case Vector(major, minor, alt) =>
      ByronVersion.from(major, minor, alt).fold(e => fail(e.message), identity)
    case _ => fail("version must contain three components")
  private def versions(cursor: HCursor, name: String): Set[ByronVersion] =
    field[Vector[Vector[BigInt]]](cursor, name).map(version).toSet

  private val inputs = json("cases.json").as[Vector[Json]].fold(e => fail(e.message), identity)
  private val outcomes = field[Vector[Json]](json("reference.json").hcursor, "results")
  private val byName =
    outcomes.map(row => field[String](row.hcursor, "caseName") -> row.hcursor).toMap
  private val genesis = ByronGenesisFile
    .parse(Files.readString(directory.resolve("genesis.json")), None)
    .fold(e => fail(e.toString), identity)
  private val config = ByronLedger.Config(genesis, requiresNetworkMagic = true)
  private val initial = ByronLedger.initial(config).fold(e => fail(e.toString), identity)
  private val rules = ByronRulesConfig(config, 0.6, None)

  test(
    "corpus retains eleven transitions, both signature gates, authorization and eight width refusals"
  ) {
    val transitions = (0 until 11).map(i => s"transition-$i").toSet
    val widths = Set("major-65536", "minor-65536", "alt-256", "major-4294967297")
    val expected = transitions ++ widths.map("header-" + _) ++ widths.map("proposal-" + _) ++
      Set("invalid-proposal-signature", "unauthorised-proposer", "invalid-header-signature")
    assertEquals(inputs.map(row => field[String](row.hcursor, "name")).toSet, expected)
    assertEquals(inputs.size, expected.size)
    assertEquals(outcomes.size, expected.size)
    assertEquals(byName.keySet, expected)
    assertEquals(
      outcomes.count(row => verdict(row.hcursor, "compositeBlock") == Verdict.Accepted),
      6
    )
  }

  private def checkRegistration(
      result: Either[UpdateError, UpdateState],
      reference: Verdict,
      proposal: ByronProposal,
      adopted: ByronVersion
  ): Unit = reference match
    case Verdict.Accepted => assert(result.isRight, result.toString)
    case Verdict.InvalidVersion =>
      assertEquals(result, Left(UpdateError.InvalidProtocolVersion(proposal.version, adopted)))
    case Verdict.InvalidSignature => assertEquals(result, Left(UpdateError.InvalidSignature))
    case Verdict.Unauthorised =>
      assertEquals(result, Left(UpdateError.InvalidProposer(ByronCbor.keyHash(proposal.issuer))))
    case other => fail(s"unexpected reference registration outcome $other")

  inputs.foreach { input =>
    val row = input.hcursor
    val name = field[String](row, "name")
    val reference = byName.getOrElse(name, fail(s"missing reference outcome $name"))
    val rawProposal = bytes(row, "proposal")
    val rawBlock = bytes(row, "block")
    val adopted = version(field[Vector[BigInt]](row, "adopted"))
    val ledger = initial.copy(update = initial.update.copy(adoptedVersion = adopted))

    test(s"original signed proposal $name matches full reference registration and admission") {
      val wrapped = ByronCbor.of(0x82, 0x81) ++ rawProposal ++ ByronCbor.of(0x80)
      val decoded = Cbor.decode(wrapped).flatMap(UpdatePayload.decode)
      if verdict(reference, "proposalDecode") == Verdict.DecodeFailure then assert(decoded.isLeft)
      else
        val proposal =
          decoded
            .fold(e => fail(e.toString), identity)
            .proposal
            .fold(fail("missing proposal"))(identity)
        assertEquals(proposal.raw, rawProposal)
        assertEquals(proposal.id, Blake2b.hash256.hash(rawProposal))
        assertEquals(
          ByronSig.verify(
            proposal.issuer,
            proposal.signature,
            ByronSig.tag(ByronSig.SignUpdateProposal, config.magic) ++ proposal.signedBody
          ),
          field[Boolean](reference, "proposalSignature")
        )
        val env = ByronUpdate.Env(
          config.magicId,
          config.magic,
          config.k,
          1,
          genesis.genesisKeys.size,
          ledger.delegation.delegationMap
        )
        val registered = ByronUpdate.registerProposal(env, ledger.update, proposal)
        checkRegistration(registered, verdict(reference, "registration"), proposal, adopted)
        assertEquals(verdict(reference, "registration"), verdict(reference, "admission"))
        registered.foreach { state =>
          assertEquals(
            state.registeredProtocol.values.map(_.version).toSet,
            versions(reference, "registeredVersions")
          )
          assertEquals(
            state.registeredProtocol.values.map(_.version).toSet,
            versions(reference, "admissionVersions")
          )
          assertEquals(state.registrationSlot.get(proposal.id), Some(BigInt(1)))
        }
    }

    test(s"complete original block $name matches transaction, ledger and signature composition") {
      val decoded = Block.decode(rawBlock, config.epochSlots).flatMap {
        case b: Block.Byron => ByronBody.decode(b.body).map(body => b -> body)
        case _              => fail("expected main Byron block")
      }
      if verdict(reference, "blockDecode") == Verdict.DecodeFailure then assert(decoded.isLeft)
      else
        val (block, body) = decoded.fold(e => fail(e.toString), identity)
        val proposal = body.update.proposal.fold(fail("missing embedded proposal"))(identity)
        assertEquals(proposal.raw, rawProposal)
        assertEquals(body.txs.size, 1)
        val transaction = body.txs.headOption.fold(fail("missing transaction"))(identity)
        assertEquals(verdict(reference, "transactionAdmission"), Verdict.Accepted)
        val transactionResult =
          ByronLedger.applyTx(config, ledger.update.adoptedParams, ledger.utxo, transaction)
        assert(transactionResult.isRight, transactionResult.toString)
        val ticked = ByronLedger.tick(config, ledger, block.header.slot)
        val applied = ByronLedger.applyMain(config, ticked, block.header, block.size, body)
        verdict(reference, "ledgerBlock") match
          case Verdict.Accepted =>
            val after = applied.fold(e => fail(e.message), identity)
            assertEquals(
              after.update.registeredProtocol.values.map(_.version).toSet,
              versions(reference, "blockVersions")
            )
            assertEquals(
              initial.utxo.keys.forall(in => !after.utxo.contains(in)),
              field[Boolean](reference, "inputSpent")
            )
            assertEquals(
              after.utxo.contains(TxIn(transaction.tx.id, 0)),
              field[Boolean](reference, "outputPresent")
            )
          case failure =>
            checkRegistration(
              applied.left
                .map {
                  case ByronError.Update(error) => error
                  case other                    => fail(s"unexpected ledger refusal $other")
                }
                .map(_.update),
              failure,
              proposal,
              adopted
            )
        val state = ByronRules.State(
          Some(ByronTip(genesis.hash, 0, 0, boundary = true)),
          ledger,
          PBftState.empty
        )
        val composite = ByronRules.update(state, block, rules)
        assertEquals(composite.isRight, verdict(reference, "compositeBlock") == Verdict.Accepted)
        if name == "invalid-header-signature" then
          assertEquals(composite, Left(xyz.kd5ujc.scarab.ledger.ByronChainError.SignatureRejected))
          assertEquals(field[Boolean](reference, "headerSignature"), false)
          assertEquals(field[Boolean](reference, "certificateSignature"), true)
          assertEquals(verdict(reference, "ledgerBlock"), Verdict.Accepted)
          assertEquals(verdict(reference, "compositeBlock"), Verdict.InvalidSignature)
        else
          assertEquals(field[Boolean](reference, "headerSignature"), true)
          assertEquals(field[Boolean](reference, "certificateSignature"), true)
    }
  }
