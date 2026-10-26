// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.consensus.state.PBftState
import xyz.kd5ujc.scarab.ledger.{Block, Header, TxIn}
import xyz.kd5ujc.scarab.ledger.byron.*
import xyz.kd5ujc.scarab.ledger.byron.ByronTestKit.*

/** Source-derived REF-07 header/registration cases; raw reference proposal-decoder verdicts are
  * retained separately by ByronProposalFixtureSuite. The pinned ledger f649f97 has
  * Word16/Word16/Word8 fields (ProtocolVersion.hs:27-32,65-68); registration checks lexical
  * increase and the next major/minor (Validation/Registration.hs:489-499). Every accepted block
  * below has a signed proposal, signed transaction, signed main header and matching body proofs;
  * the width checks precede registration and its version-transition rule.
  */
class ByronVersionSuite extends ScalaCheckSuite:
  private type Version = (Long, Long, Long)
  private val genesisKeys = Vector.fill(4)(key())
  private val delegates = Vector.fill(4)(key())
  private val holder = key()
  private val holderAddress = address(holder, Some(Magic))
  private val genesis = ByronTestKit.genesis(
    genesisKeys,
    delegates,
    nonAvvm = Vector(holderAddress -> BigInt(10000000))
  )
  private val config = ByronLedger.Config(genesis, requiresNetworkMagic = true)
  private val rulesConfig = ByronRulesConfig(config, 0.6, None)
  private val initial = ByronLedger.initial(config).fold(e => fail(e.toString), identity)
  private val transaction = aux(
    config,
    Seq((TxIn(Blake2b.hash256.hash(holderAddress), 0), holder, false)),
    Seq(holderAddress -> 9000000L)
  )

  private def version(v: Version): ByronVersion =
    ByronVersion.from(v._1, v._2, v._3).fold(e => fail(e.message), identity)

  private def encodedVersion(v: Version): Bytes =
    Cb.arr(ByronCbor.uint(v._1), ByronCbor.uint(v._2), ByronCbor.uint(v._3))

  private def proposal(v: Version): Bytes =
    val body = Seq(
      encodedVersion(v),
      Cb.arr(Seq.fill(14)(Cb.arr())*),
      Cb.arr(Cb.text("cardano-sl"), Cb.uint(1)),
      Cb.map(),
      Cb.map()
    )
    val signed = ByronCbor.of(0x85) ++ ByronCbor.concat(body*)
    val signature = delegates(0).sign(
      ByronSig.tag(ByronSig.SignUpdateProposal, config.magic) ++ signed
    )
    Cb.arr((body ++ Seq(Cb.bytes(delegates(0).extended), Cb.bytes(signature)))*)

  private def block(headerVersion: Version, proposalVersion: Version): Bytes =
    val updates = Cb.arr(Cb.arr(proposal(proposalVersion)), Cb.arr())
    val delegation = Cb.arr()
    val body = Cb.arr(Cb.indef(transaction.raw), Cb.arr(), delegation, updates)
    val proof = Cb.arr(
      Cb.arr(
        Cb.uint(1),
        Cb.bytes(ByronTx.merkleRoot(Vector(transaction.tx.raw))),
        Cb.bytes(Blake2b.hash256.hash(Cb.indef(transaction.rawWitnesses)))
      ),
      Cb.arr(),
      Cb.bytes(Blake2b.hash256.hash(delegation)),
      Cb.bytes(Blake2b.hash256.hash(updates))
    )
    val slot = Cb.arr(Cb.uint(0), Cb.uint(1))
    val difficulty = Cb.arr(Cb.uint(1))
    val extra = Cb.arr(
      encodedVersion(headerVersion),
      Cb.arr(Cb.text("cardano-sl"), Cb.uint(1)),
      Cb.map(),
      Cb.bytes(Blake2b.hash256.hash(Cb.arr()))
    )
    val previous = Cb.bytes(genesis.hash)
    val signed = Cb.arr(previous, proof, slot, difficulty, extra)
    val signature = delegates(0).sign(
      ByronCbor.of(0x30, 0x31) ++ genesisKeys(0).extended ++ ByronCbor.of(
        0x09
      ) ++ config.magic ++ signed
    )
    val certificate = genesis.delegations
      .find(_.issuer == genesisKeys(0).extended)
      .fold(fail("missing genesis certificate"))(_.cert)
    val proxy = Cb.arr(
      Cb.uint(0),
      Cb.bytes(genesisKeys(0).extended),
      Cb.bytes(delegates(0).extended),
      Cb.bytes(certificate)
    )
    val consensus = Cb.arr(
      slot,
      Cb.bytes(genesisKeys(0).extended),
      difficulty,
      Cb.arr(Cb.uint(2), Cb.arr(proxy, Cb.bytes(signature)))
    )
    val header = Cb.arr(Cb.uint(Magic), previous, proof, consensus, extra)
    Cb.arr(Cb.uint(1), Cb.arr(header, body, Cb.arr()))

  private def decoded(bytes: Bytes): Block.Byron =
    Block
      .decode(bytes, config.epochSlots)
      .fold(
        e => fail(e.toString),
        {
          case b: Block.Byron => b
          case _              => fail("expected Byron block")
        }
      )

  private def state(adopted: Version): ByronRules.State =
    ByronRules.State(
      Some(ByronTip(genesis.hash, 0, 0, boundary = true)),
      initial.copy(update = initial.update.copy(adoptedVersion = version(adopted))),
      PBftState.empty
    )

  private val limits = Vector[(Version, Version)](
    (65534L, 0L, 0L) -> (65535L, 0L, 0L),
    (0L, 65534L, 0L) -> (0L, 65535L, 0L),
    (0L, 0L, 0L) -> (1L, 0L, 255L)
  )

  limits.foreach { (adopted, proposed) =>
    test(
      s"header and signed proposal accept boundary $proposed through block/transaction validation"
    ) {
      val b = decoded(block(proposed, proposed))
      assertEquals(b.header.blockVersion, version(proposed))
      assertEquals(
        Header.byronFromRaw(b.header.raw, false, config.epochSlots),
        Right(b.header.withoutSizeHint)
      )
      val (after, check) =
        ByronRules.update(state(adopted), b, rulesConfig).fold(e => fail(e.toString), identity)
      assertEquals(
        check,
        ByronCheck.Verified(
          ByronRules.scheduledLeader(genesis, 1) == genesisKeys(0).keyHash,
          txs = 1
        )
      )
      assertEquals(
        after.ledger.update.registeredProtocol.values.map(_.version).toSet,
        Set(version(proposed))
      )
      assert(!after.ledger.utxo.contains(TxIn(Blake2b.hash256.hash(holderAddress), 0)))
    }
  }

  private val overflows =
    Vector[Version]((65536L, 0L, 0L), (0L, 65536L, 0L), (1L, 0L, 256L), (4294967297L, 0L, 0L))
  overflows.foreach { proposed =>
    test(s"header rejects out-of-range version $proposed at block decoding") {
      assert(Block.decode(block(proposed, (1, 0, 0)), config.epochSlots).isLeft)
    }
    test(s"signed proposal rejects out-of-range version $proposed before registration") {
      val b = decoded(block((0, 0, 0), proposed))
      assert(ByronRules.update(state((0, 0, 0)), b, rulesConfig).isLeft)
      assert(ByronBody.decode(b.body).isLeft)
    }
  }

  test("registration retains legal next-major and next-minor transitions, ignoring alt") {
    Vector[(Version, Version)](
      (0L, 0L, 255L) -> (0L, 1L, 0L),
      (0L, 65535L, 255L) -> (1L, 0L, 0L),
      (65535L, 65534L, 0L) -> (65535L, 65535L, 255L)
    ).foreach { (adopted, proposed) =>
      assert(
        ByronRules.update(state(adopted), decoded(block(proposed, proposed)), rulesConfig).isRight
      )
    }
  }

  test("registration rejects alt-only, major jumps, nonzero minor with next major, and wrapping") {
    Vector[(Version, Version)](
      (0L, 0L, 0L) -> (0L, 0L, 1L),
      (0L, 0L, 0L) -> (2L, 0L, 0L),
      (0L, 0L, 0L) -> (1L, 1L, 0L),
      (0L, 65535L, 0L) -> (0L, 0L, 1L),
      (65535L, 0L, 0L) -> (0L, 0L, 0L)
    ).foreach { (adopted, proposed) =>
      val b = decoded(block(proposed, proposed))
      val body = ByronBody.decode(b.body).fold(e => fail(e.toString), identity)
      val ticked = ByronLedger.tick(config, state(adopted).ledger, b.header.slot)
      assertEquals(
        ByronLedger.applyMain(config, ticked, b.header, b.size, body),
        Left(
          ByronError.Update(UpdateError.InvalidProtocolVersion(version(proposed), version(adopted)))
        )
      )
    }
  }

  private val referenceTransitions =
    parse(Files.readString(Path.of("fixtures/byron-version/registration.json")))
      .flatMap(_.hcursor.get[Vector[io.circe.Json]]("cases"))
      .fold(e => fail(e.getMessage), identity)

  referenceTransitions.foreach { json =>
    val row = json.hcursor
    def components(field: String): Version =
      row.get[Vector[Long]](field).fold(e => fail(e.message), identity) match
        case Vector(major, minor, alt) => (major, minor, alt)
        case _                         => fail("fixture version must have three components")
    val adopted = components("adopted")
    val proposed = components("proposed")
    val accepted = row.get[Boolean]("accepted").fold(e => fail(e.message), identity)

    test(s"signed registration $adopted -> $proposed agrees with executed Haskell predicate") {
      val b = decoded(block(proposed, proposed))
      val result = ByronRules.update(state(adopted), b, rulesConfig)
      if accepted then assert(result.isRight, result.toString)
      else
        val body = ByronBody.decode(b.body).fold(e => fail(e.toString), identity)
        val ticked = ByronLedger.tick(config, state(adopted).ledger, b.header.slot)
        assertEquals(
          ByronLedger.applyMain(config, ticked, b.header, b.size, body),
          Left(
            ByronError.Update(
              UpdateError.InvalidProtocolVersion(version(proposed), version(adopted))
            )
          )
        )
    }
  }

  property("smart construction and CBOR decoding agree on bounded components") {
    forAll(Gen.choose(0L, 70000L), Gen.choose(0L, 70000L), Gen.choose(0L, 300L)) {
      (major, minor, alt) =>
        val from = ByronVersion.from(major, minor, alt)
        val decode = Cbor
          .decode(encodedVersion((major, minor, alt)))
          .fold(e => fail(e.toString), ByronVersion.decode)
        from == decode && from.isRight == (major <= 65535 && minor <= 65535 && alt <= 255)
    }
  }

  test("smart construction rejects negative and arbitrarily large components with closed errors") {
    import ByronVersion.{Error, Field}
    def rejects(major: BigInt, minor: BigInt, alt: BigInt, field: Field, value: BigInt): Unit =
      val expected: Either[Error.OutOfRange, ByronVersion] = Left(Error.OutOfRange(field, value))
      assertEquals(ByronVersion.from(major, minor, alt), expected)
    rejects(-1, 0, 0, Field.Major, -1)
    rejects(0, -1, 0, Field.Minor, -1)
    rejects(0, 0, -1, Field.Alt, -1)
    val huge = (BigInt(1) << 100) + 1
    rejects(huge, 0, 0, Field.Major, huge)
  }
