// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger.byron

import lab.cbor.{Bytes, Cbor}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.Gen
import ByronTestKit.*

/** Delegation certificates (scheduling, activation, the bimap) and the update system (proposal,
  * votes, confirmation, endorsement, candidate, adoption at an epoch, the transition to Shelley),
  * with real Ed25519 signatures over the Byron signing tags.
  */
class ByronUpdateSuite extends ScalaCheckSuite:
  private val k = BigInt(10)
  private val epochSlots = BigInt(100)
  private val gKeys = Vector.fill(4)(key())
  private val dKeys = Vector.fill(4)(key())
  private val magic = ByronCbor.uint(Magic)
  private val genesisKeys = gKeys.map(_.keyHash)
  private val delegation = gKeys.zip(dKeys).map((g, d) => g.keyHash -> d.keyHash).toMap

  // ---------------------------------------------------------------- certificates

  private def cert(issuer: Key, delegate: Key, epoch: Long, tamper: Boolean = false): ByronCert =
    val epochBytes = ByronCbor.uint(epoch)
    val signed =
      ByronCbor.bytes(ByronCbor.concat(ByronCbor.of(0x30, 0x30), delegate.extended, epochBytes))
    val sig = issuer.sign(ByronSig.tag(ByronSig.SignCertificate, magic) ++ signed)
    val s = if tamper then Bytes(sig.value.updated(0, (sig.value(0) ^ 1).toByte)) else sig
    Cbor
      .decode(
        Cb.arr(Cb.uint(epoch), Cb.bytes(issuer.extended), Cb.bytes(delegate.extended), Cb.bytes(s))
      )
      .flatMap(ByronCert.decode)
      .fold(e => fail(e), identity)

  private def delegationEnv(epoch: Long, slot: Long) =
    DelegationRules.Env(magic, genesisKeys.toSet, epoch, slot, k)

  private val fresh = DelegationRules.empty(genesisKeys)
  private val newDelegate = key()

  test("a certificate is valid under the certificate tag and the issuer's key only") {
    assert(cert(gKeys(0), newDelegate, 1).isValid(magic))
    assert(!cert(gKeys(0), newDelegate, 1, tamper = true).isValid(magic))
    assert(!cert(gKeys(0), newDelegate, 1).isValid(ByronCbor.uint(Magic + 1)))
  }

  test("a certificate takes effect 2k slots after the block that carries it, not before") {
    val c = cert(gKeys(0), newDelegate, 1)
    val scheduled =
      DelegationRules.schedule(delegationEnv(0, 5), fresh, c).fold(e => fail(e.message), identity)
    assertEquals(scheduled.scheduled.map(_.slot), Vector(BigInt(25)))
    val before = DelegationRules.tick(scheduled, 0, 24)
    assertEquals(before.delegationMap(gKeys(0).keyHash), gKeys(0).keyHash)
    val at = DelegationRules.tick(scheduled, 0, 25)
    assertEquals(at.delegationMap(gKeys(0).keyHash), newDelegate.keyHash)
    assert(at.scheduled.isEmpty)
    assertEquals(at.delegatorOf(newDelegate.keyHash), Some(gKeys(0).keyHash))
    assertEquals(at.delegatorOf(gKeys(0).keyHash), None) // the old delegate no longer signs
  }

  test(
    "scheduling rules: genesis delegators, this or the next epoch, once per epoch, once per slot"
  ) {
    val c = cert(gKeys(0), newDelegate, 1)
    val s1 = DelegationRules.schedule(delegationEnv(0, 5), fresh, c).toOption.get
    // a stranger
    assert(
      DelegationRules
        .schedule(delegationEnv(0, 5), fresh, cert(key(), newDelegate, 1))
        .left
        .exists(_.isInstanceOf[DelegationError.NonGenesisDelegator])
    )
    // epoch from the past or too far ahead
    assert(
      DelegationRules
        .schedule(delegationEnv(3, 5), fresh, cert(gKeys(1), newDelegate, 2))
        .left
        .exists(_.isInstanceOf[DelegationError.WrongEpoch])
    )
    assert(
      DelegationRules
        .schedule(delegationEnv(3, 5), fresh, cert(gKeys(1), newDelegate, 5))
        .left
        .exists(_.isInstanceOf[DelegationError.WrongEpoch])
    )
    // the same genesis key for the same epoch again
    assert(
      DelegationRules
        .schedule(delegationEnv(0, 6), s1, cert(gKeys(0), key(), 1))
        .left
        .exists(_.isInstanceOf[DelegationError.MultipleDelegationsForEpoch])
    )
    // the same slot for the same genesis key, different epochs
    assert(
      DelegationRules
        .schedule(delegationEnv(0, 5), s1, cert(gKeys(0), key(), 0))
        .left
        .exists(_.isInstanceOf[DelegationError.MultipleDelegationsForSlot])
    )
    // a bad signature
    assertEquals(
      DelegationRules
        .schedule(delegationEnv(0, 5), fresh, cert(gKeys(2), newDelegate, 1, tamper = true)),
      Left(DelegationError.InvalidCertificate)
    )
  }

  test("a key that already delegates for another genesis key cannot be delegated to again") {
    val shared = key()
    val one = DelegationRules
      .update(delegationEnv(0, 0), fresh, Vector(cert(gKeys(0), shared, 0)))
      .toOption
      .get
    // k = 10: activation at slot 20
    val a = DelegationRules.tick(one, 0, 20)
    assertEquals(a.delegatorOf(shared.keyHash), Some(gKeys(0).keyHash))
    val two = DelegationRules
      .update(delegationEnv(0, 21), a, Vector(cert(gKeys(1), shared, 1)))
      .toOption
      .get
    val b = DelegationRules.tick(two, 0, 41)
    assertEquals(b.delegatorOf(shared.keyHash), Some(gKeys(0).keyHash))
    assertEquals(b.delegationMap(gKeys(1).keyHash), gKeys(1).keyHash)
  }

  property("activation keeps the delegation map injective and over the genesis keys") {
    val keys = genesisKeys
    val hashes = Vector.fill(6)(key().keyHash)
    val entry = for
      slot <- Gen.choose(0, 40)
      g <- Gen.oneOf(keys)
      d <- Gen.oneOf(hashes ++ keys)
    yield ScheduledDelegation(slot, g, d)
    forAll(Gen.listOf(entry), Gen.choose(0, 40)) { (ds, now) =>
      val st = fresh.copy(scheduled = ds.toVector.sortBy(_.slot))
      val active = DelegationRules.activate(st, now).delegationMap
      active.keySet == keys.toSet && active.values.toSet.size == active.size
    }
  }

  // ---------------------------------------------------------------- the update system

  private val state0 = ByronUpdate.initial(params)

  private def env(slot: Long, map: Map[Bytes, Bytes] = delegation) =
    ByronUpdate.Env(Magic, magic, k, slot, gKeys.size, map)

  private def proposalBytes(
      issuer: Key,
      version: (Long, Long, Long),
      update: Bytes = Cb.arr(Seq.fill(14)(Cb.arr())*),
      software: (String, Long) = ("cardano-sl", 1),
      tamper: Boolean = false
  ): Bytes =
    val elements = Seq(
      Cb.arr(Cb.uint(version._1), Cb.uint(version._2), Cb.uint(version._3)),
      update,
      Cb.arr(Cb.text(software._1), Cb.uint(software._2)),
      Cb.map(),
      Cb.map()
    )
    val signed = ByronCbor.of(0x85) ++ ByronCbor.concat(elements*)
    val sig = issuer.sign(ByronSig.tag(ByronSig.SignUpdateProposal, magic) ++ signed)
    val s = if tamper then Bytes(sig.value.updated(1, (sig.value(1) ^ 1).toByte)) else sig
    Cb.arr((elements ++ Seq(Cb.bytes(issuer.extended), Cb.bytes(s)))*)

  private def voteBytes(voter: Key, proposalId: Bytes): Bytes =
    val id = Cb.bytes(proposalId)
    val sig = voter.sign(
      ByronSig.tag(ByronSig.SignUpdateVote, magic) ++ ByronCbor.of(0x82) ++ id ++ ByronCbor.of(0xf5)
    )
    Cb.arr(Cb.bytes(voter.extended), id, Cb.`true`, Cb.bytes(sig))

  private def payload(proposal: Option[Bytes], votes: Seq[Bytes]): UpdatePayload =
    val encoded = Cb.arr(proposal.fold(Cb.arr())(p => Cb.arr(p)), Cb.arr(votes*))
    Cbor.decode(encoded).flatMap(UpdatePayload.decode).fold(e => fail(e), identity)

  private val v1 = ByronVersion(1, 0, 0)
  private val v2 = ByronVersion(2, 0, 0)

  private def register(
      st: UpdateState,
      slot: Long,
      p: UpdatePayload,
      version: ByronVersion,
      by: Key
  ) =
    ByronUpdate.register(env(slot), st, p, version, by.keyHash)

  /** Propose `v`, vote with two delegates, and let the delegates endorse until it is a candidate.
    */
  private def adopt(
      st: UpdateState,
      version: (Long, Long, Long),
      from: Long,
      update: Bytes = Cb.arr(Seq.fill(14)(Cb.arr())*),
      software: Long = 1
  ): (UpdateState, Long) =
    val pb = proposalBytes(dKeys(0), version, update, software = ("cardano-sl", software))
    val p = payload(Some(pb), Nil)
    val id = p.proposal.get.id
    val v = ByronVersion(version._1, version._2, version._3)
    val s1 = register(st, from, p, ByronVersion.zero.copy(major = v.major - 1), dKeys(0))
      .fold(e => fail(e.message), identity)
    assertEquals(s1.registeredProtocol.keySet, Set(id))
    val s2 = register(
      s1,
      from + 1,
      payload(None, Seq(voteBytes(dKeys(0), id), voteBytes(dKeys(1), id))),
      ByronVersion.zero,
      dKeys(0)
    )
      .fold(e => fail(e.message), identity)
    assertEquals(s2.confirmed.get(id), Some(BigInt(from + 1)))
    // stable 2k = 20 slots after the confirmation; two endorsements are needed (floor(0.6 * 4))
    val endorseAt = from + 1 + 20
    val s3 =
      register(s2, endorseAt, payload(None, Nil), v, dKeys(0)).fold(e => fail(e.message), identity)
    assert(s3.candidates.isEmpty, "one endorsement is not enough")
    val s4 = register(s3, endorseAt + 1, payload(None, Nil), v, dKeys(1))
      .fold(e => fail(e.message), identity)
    assertEquals(s4.candidates.map(c => (c.slot, c.version)), Vector((BigInt(endorseAt + 1), v)))
    (s4, endorseAt + 1)

  test("proposal, votes, endorsements and the epoch boundary adopt a new protocol version") {
    val (s, candidateSlot) = adopt(state0, (1, 0, 0), from = 5)
    // not yet: 4k = 40 slots have to pass before an epoch starts after the candidate
    val tooSoon = ByronUpdate.registerEpoch(s, k, epochSlots, (candidateSlot + 39) / epochSlots)
    assertEquals(tooSoon.adoptedVersion, ByronVersion.zero)
    val firstEpoch = (candidateSlot + 40 + epochSlots - 1) / epochSlots
    val adopted = ByronUpdate.registerEpoch(s, k, epochSlots, firstEpoch)
    assertEquals(adopted.adoptedVersion, v1)
    assert(
      adopted.candidates.isEmpty && adopted.registeredProtocol.isEmpty && adopted.confirmed.isEmpty
    )
  }

  test("an adopted update applies its parameter changes") {
    // maxTxSize 4096 -> 8192 (and maxBlockSize unchanged): [scriptVersion .. maxTxSize at index 4]
    val update =
      Cb.arr((Seq.fill(4)(Cb.arr()) ++ Seq(Cb.arr(Cb.uint(8192))) ++ Seq.fill(9)(Cb.arr()))*)
    val (s, at) = adopt(state0, (1, 0, 0), from = 5, update)
    val adopted =
      ByronUpdate.registerEpoch(s, k, epochSlots, (at + 40 + epochSlots - 1) / epochSlots)
    assertEquals(adopted.adoptedParams.maxTxSize, BigInt(8192))
    assertEquals(adopted.adoptedParams.maxBlockSize, params.maxBlockSize)
  }

  test("proposal rules: proposer, signature, version succession, no duplicates, no null update") {
    val ok = payload(Some(proposalBytes(dKeys(0), (1, 0, 0))), Nil)
    assert(register(state0, 5, ok, ByronVersion.zero, dKeys(0)).isRight)
    // a key that is not a delegate
    assert(
      register(
        state0,
        5,
        payload(Some(proposalBytes(key(), (1, 0, 0))), Nil),
        ByronVersion.zero,
        dKeys(0)
      ).left.exists(_.isInstanceOf[UpdateError.InvalidProposer])
    )
    // a signature that does not verify
    assertEquals(
      register(
        state0,
        5,
        payload(Some(proposalBytes(dKeys(0), (1, 0, 0), tamper = true)), Nil),
        ByronVersion.zero,
        dKeys(0)
      ),
      Left(UpdateError.InvalidSignature)
    )
    // 0.0.0 -> 1.1.0 skips 1.0.0, 3.0.0 skips a major
    assert(
      register(
        state0,
        5,
        payload(Some(proposalBytes(dKeys(0), (1, 1, 0))), Nil),
        ByronVersion.zero,
        dKeys(0)
      ).left.exists(_.isInstanceOf[UpdateError.InvalidProtocolVersion])
    )
    assert(
      register(
        state0,
        5,
        payload(Some(proposalBytes(dKeys(0), (3, 0, 0))), Nil),
        ByronVersion.zero,
        dKeys(0)
      ).left.exists(_.isInstanceOf[UpdateError.InvalidProtocolVersion])
    )
    // the same version twice
    val once = register(state0, 5, ok, ByronVersion.zero, dKeys(0)).toOption.get
    val again = payload(Some(proposalBytes(dKeys(1), (1, 0, 0), software = ("cardano-sl", 2))), Nil)
    assert(
      register(once, 6, again, ByronVersion.zero, dKeys(0)).left
        .exists(_.isInstanceOf[UpdateError.DuplicateProtocolVersion])
    )
    // nothing changes: the adopted version and no software change
    val none = payload(Some(proposalBytes(dKeys(0), (0, 0, 0), software = ("cardano-sl", 0))), Nil)
    val withApp = state0.copy(appVersions = Map("cardano-sl" -> AppVersion(0, 0)))
    assertEquals(
      register(withApp, 5, none, ByronVersion.zero, dKeys(0)),
      Left(UpdateError.NullUpdateProposal)
    )
  }

  test("vote rules: registered proposal, a delegate, once per genesis key, a valid signature") {
    val p = payload(Some(proposalBytes(dKeys(0), (1, 0, 0))), Nil)
    val s = register(state0, 5, p, ByronVersion.zero, dKeys(0)).toOption.get
    val id = p.proposal.get.id
    assert(
      register(
        s,
        6,
        payload(None, Seq(voteBytes(dKeys(2), id))),
        ByronVersion.zero,
        dKeys(0)
      ).isRight
    )
    assert(
      register(
        s,
        6,
        payload(None, Seq(voteBytes(dKeys(2), Bytes.fromArray(Array.fill(32)(1.toByte))))),
        ByronVersion.zero,
        dKeys(0)
      ).left.exists(_.isInstanceOf[UpdateError.VotingProposalNotRegistered])
    )
    assert(
      register(s, 6, payload(None, Seq(voteBytes(key(), id))), ByronVersion.zero, dKeys(0)).left
        .exists(_.isInstanceOf[UpdateError.VoterNotDelegate])
    )
    val voted = register(
      s,
      6,
      payload(None, Seq(voteBytes(dKeys(2), id))),
      ByronVersion.zero,
      dKeys(0)
    ).toOption.get
    assert(
      register(
        voted,
        7,
        payload(None, Seq(voteBytes(dKeys(2), id))),
        ByronVersion.zero,
        dKeys(0)
      ).left.exists(_.isInstanceOf[UpdateError.VoteAlreadyCast])
    )
  }

  test(
    "the transition to Shelley is known once the version-2 candidate is stable, in the next epoch"
  ) {
    val (s1, at1) = adopt(state0, (1, 0, 0), from = 5)
    val adopted1 =
      ByronUpdate.registerEpoch(s1, k, epochSlots, (at1 + 40 + epochSlots - 1) / epochSlots)
    val genesis = ByronTestKit.genesis(gKeys, dKeys, k = k.toInt)
    val config = ByronLedger.Config(genesis, requiresNetworkMagic = true)
    val base = ByronLedger.initial(config).toOption.get
    val from = 400L
    val (s2, at2) = adopt(adopted1, (2, 0, 0), from = from, software = 2)
    def ledgerAt(lastSlot: Long, tipBlock: Long, becameBlock: Long) =
      base.copy(
        lastSlot = lastSlot,
        tipBlockNo = tipBlock,
        update = s2,
        candidateBlocks = Map(v2 -> BigInt(becameBlock))
      )
    // the epoch in which the update is adopted: the one after the epoch of candidate + 4k
    val expected = (BigInt(at2) + 40) / epochSlots + 1
    // newly a candidate: neither k blocks nor 2k slots have passed
    assertEquals(ByronLedger.transition(config, ledgerAt(at2 + 3, 100, 98)), None)
    // k = 10 blocks later it is stable
    assertEquals(ByronLedger.transition(config, ledgerAt(at2 + 3, 108, 98)), Some(expected))
    // or 2k = 20 slots later by the ledger's own clock
    assertEquals(ByronLedger.transition(config, ledgerAt(at2 + 20, 100, 98)), Some(expected))
    // a candidate for a version below 2 never triggers it
    assertEquals(
      ByronLedger.transition(config, base.copy(lastSlot = 10000, update = s1, tipBlockNo = 10000)),
      None
    )
  }

  test("parameter updates decode: optional fields are lists of zero or one element") {
    val update = Cb.arr(
      (Seq(Cb.arr(Cb.uint(1))) ++ Seq.fill(10)(Cb.arr()) ++ Seq(
        Cb.arr(Cb.arr(Cb.uint(1), Cb.uint(2), Cb.uint(3))),
        Cb.arr(),
        Cb.arr(Cb.uint(7))
      ))*
    )
    val decoded = Cbor.decode(update).flatMap(ByronParamsUpdate.decode).fold(e => fail(e), identity)
    assertEquals(decoded.scriptVersion, Some(BigInt(1)))
    assertEquals(decoded.softforkRule, Some(SoftforkRule(1, 2, 3)))
    assertEquals(decoded.unlockStakeEpoch, Some(BigInt(7)))
    assertEquals(decoded(params).scriptVersion, BigInt(1))
    assertEquals(decoded(params).maxBlockSize, params.maxBlockSize)
  }
