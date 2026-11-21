// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.data.{NonEmptyMap, NonEmptySet}
import munit.FunSuite
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.crypto.bls.VerificationKey
import xyz.kd5ujc.scarab.ledger.{Mismatch, TestLedger, TxEnv}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.*
import xyz.kd5ujc.scarab.ledger.era.rules.*
import xyz.kd5ujc.scarab.ledger.rules.{
  Convert,
  DijkstraCertsRule,
  DijkstraSts,
  Globals,
  Judgment,
  Test as RuleTest,
  Translate
}
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  AccountId,
  Accounts,
  BlsKey,
  BlsKeyState,
  Build,
  CertState,
  Coin,
  CompactCoin,
  Credential,
  DState,
  Dijkstra,
  Empty,
  EpochNo,
  KeyHash,
  Network,
  PState,
  PoolBlsKey,
  PoolParamsBlsKey,
  StakePool,
  StakePoolParams,
  Staking,
  Word64
}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** Dijkstra's POOL, CERT, CERTS and ENTITIES on the era types (`DijkstraPOOL`, `DijkstraCERT`,
  * `CERTS.conwayCertsTransition` with `CertRule.dijkstra`, `ENTITIES`; cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/{Pool,Cert,Certs,Entities}.hs`), where the
  * flat rules cannot judge or the oracle records nothing:
  *   - POOL's proof of possession, on the voting key and proof of a pool of the Leios devnet and on
  *     that key with the proof of another key, as the reference's genesis fixture records them
  *     (`fixtures/reference/leios-genesis`, which genesis reads without verifying); the static
  *     check skipped under `NonStatic`, and a key the decoder would not admit POOL's defect; the 16
  *     pool registrations of the Leios build's decoding corpus
  *     (`fixtures/reference/leios-testnet-blocks`) as the era decoder reads them, the one with a
  *     metadata hash of 70 bytes refused and the others admitted, which the reference's POOL did
  *     not judge;
  *   - POOL's count of each VRF key hash's holders over registrations and re-registrations, against
  *     the reference's own recomputation of it (`populateVRFKeyHashes`), which the reference's
  *     assertions use;
  *   - CERTS on the certificates of the testnet chain's transactions
  *     (`fixtures/reference/leios-testnet-chain-contexts`) against the flat rule, from the same
  *     empty state, and ENTITIES on each of the chain's 200 transactions against the verdict and
  *     state CERTS alone and ENTITIES' predicates give from that state;
  *   - ENTITIES' order of steps, on a chain transaction that withdraws from an account and
  *     unregisters it: the withdrawal applied before CERTS, a check of the current accounts only
  *     when nothing failed before it, and the balance a withdrawal past it leaves, which wraps in
  *     `Word64` as `subtractCompactCoin` does; and on a chain transaction that registers an
  *     account, given a direct deposit to it: the deposit checked and applied after CERTS;
  *   - each injection against the reference's composition, Dijkstra's DELEG's of Shelley's
  *     `AccountAlreadyRegistered` among them, and none for the constructors the reference calls
  *     impossible for ENTITIES or CERTS's 11.1 withdrawals' case, which has no value at Dijkstra;
  *   - a sub-transaction's SUBCERTS against CERTS through the reference's injection, and
  *     SUBENTITIES on the chain's sub-transactions and on crafted ones whose steps its failures or
  *     state show: the withdrawals applied whatever failed and before SUBCERTS, the direct deposits
  *     after it, the DReps' expiries a vote and a proposal update, and the failures of both
  *     networks' accounts in the reference's order; SUBCERTS's fold on a small stack (STK-3).
  *
  * Incomplete (CNF-2): the chain's transactions as the reference judged them, which needs the
  * chain's certificate state, not recorded; a refusal of ENTITIES or of a pool's voting key by the
  * reference, which no recording holds.
  */
class DijkstraCertsSuite extends FunSuite:
  private given BothLevels[TxLevel.Top] = BothLevels.Top
  private val b = EraTxBody.dijkstra
  private val c = EraCertState.dijkstra
  private given ConwayEraAccounts[Dijkstra] = c.accountsOf
  private given cats.Order[AccountAddress] =
    cats.Order.by[AccountAddress, flat.RewardAccount](Convert.rewardAccountBack)(using
      cats.Order.fromOrdering
    )
  private given Ordering[AccountAddress] = summon[cats.Order[AccountAddress]].toOrdering
  private given cats.Order[flat.RewardAccount] = cats.Order.fromOrdering

  private val leios: Globals =
    TestLedger.globals.copy(ledger =
      TestLedger.globals.ledger.copy(referenceBuild = ReferenceBuild.LeiosB004fe0c)
    )

  private val pp: PParams[Dijkstra] = testnetPParams
  private val epoch5 = EpochNo.fromUnsigned(5L)
  private val empty: CertState[Dijkstra] = Empty.certState[Dijkstra]

  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  // ---- POOL --------------------------------------------------------------------------------------

  private lazy val genesisKeys: Map[String, BlsKey] =
    val names = Set("bls-key-of-a-devnet-pool", "bls-key-proof-of-another-key")
    jsonLines("fixtures/reference/leios-genesis/cases.jsonl").flatMap { j =>
      val h = j.hcursor
      val v = h.downField("value")
      (
        h.get[String]("name").toOption.filter(names.contains),
        v.get[String]("blsPubKey").toOption,
        v.get[String]("blsPossessionProof").toOption
      ) match
        case (Some(name), Some(key), Some(proof)) => Some(name -> BlsKey(hex(key), hex(proof)))
        case _                                    => None
    }.toMap

  /** The key and proof of a pool of the Leios devnet. */
  private lazy val devnetKey = genesisKeys("bls-key-of-a-devnet-pool")

  /** The devnet pool's key with the proof of another key. */
  private lazy val otherProof = genesisKeys("bls-key-proof-of-another-key")

  private def flatParams(operator: Int, vrf: Int) = flat.PoolParams(
    Bytes(Vector.fill(28)(operator.toByte)),
    Bytes(Vector.fill(32)(vrf.toByte)),
    BigInt(0),
    BigInt(340000000),
    Rational(1, 100),
    flat.RewardAccount(0, flat.Credential.Key(Bytes(Vector.fill(28)(7.toByte)))),
    Set.empty,
    Bytes.empty,
    None
  )

  private def params(operator: Int, vrf: Int, key: Option[BlsKey] = None): StakePoolParams =
    Build.stakePoolParams(flatParams(operator, vrf), PoolParamsBlsKey.Leios(key))

  private def pool(
      state: PState,
      p: StakePoolParams,
      validation: TxEnv.Validation = TxEnv.Validation.All
  ) =
    ok(
      EraCertsRun.dijkstraPool(
        leios,
        PoolEnv[Dijkstra](epoch5, pp),
        state,
        PoolCert.RegPool(p),
        validation
      )
    )

  test("a pool registered with a devnet pool's voting key and its proof holds the key from now") {
    val p = params(9, 8, Some(devnetKey))
    pool(Empty.pState, p) match
      case Right((ps, events)) =>
        assertEquals(
          ps.stakePools.get(p.id).map(_.blsKey),
          Some(PoolBlsKey.Leios(Some(BlsKeyState(devnetKey, epoch5))))
        )
        assertEquals(events.toVector, Vector(PoolEvent.RegisterPool[Dijkstra](p.id)))
        assertEquals(ps.vrfKeyHashes.get(p.vrf).map(n => Word64.toBigInt(n.value)), Some(BigInt(1)))
      case Left(failures) => fail(s"refused: $failures")
  }

  test("a voting key with another key's proof is refused, and not checked under NonStatic") {
    val p = params(9, 8, Some(otherProof))
    assertEquals(
      pool(Empty.pState, p).left.map(_.toChain.toVector),
      Left(
        Vector(
          DijkstraPoolPredFailure.BlsKeyInvalidProofOfPossession[Dijkstra](p.id, otherProof)
        )
      )
    )
    assert(pool(Empty.pState, p, TxEnv.Validation.NonStatic).isRight)
    // a registration without a key has nothing to check
    assert(pool(Empty.pState, params(9, 8)).isRight)
  }

  test("the VRF key hash counts follow the reference's invariant over re-registrations") {
    val a = 9
    val steps = Vector(params(a, 1), params(a, 2), params(a, 3), params(a, 1), params(a, 4))
    val vrf = (n: Int) => params(a, n).vrf
    val counts = steps.scanLeft(Empty.pState) { (ps, p) =>
      pool(ps, p) match
        case Right((next, _)) => next
        case Left(failures)   => fail(s"$p refused: $failures")
    }
    counts.foreach(ps =>
      assertEquals(ps.vrfKeyHashes, Translate.populateVRFKeyHashes(ps).vrfKeyHashes, ps.toString)
    )
    def held(ps: PState) = ps.vrfKeyHashes.keySet
    assertEquals(held(counts(1)), Set(vrf(1)))
    assertEquals(held(counts(2)), Set(vrf(1), vrf(2)))
    assertEquals(held(counts(3)), Set(vrf(1), vrf(3)))
    assertEquals(held(counts(4)), Set(vrf(1)))
    assertEquals(held(counts(5)), Set(vrf(1), vrf(4)))
  }

  test(
    "a voting key the decoder would not admit is POOL's defect, and is not read under NonStatic"
  ) {
    val garbage = BlsKey(Bytes(Vector.fill(96)(0.toByte)), Bytes(Vector.fill(48)(0.toByte)))
    val p = params(9, 8, Some(garbage))
    val cause = VerificationKey.fromBytes(garbage.key).swap.getOrElse(fail("a key"))
    assertEquals(
      EraCertsRun
        .dijkstraPool(leios, PoolEnv[Dijkstra](epoch5, pp), Empty.pState, PoolCert.RegPool(p)),
      Left(Left(PoolDefect.BlsKeyUnverifiable(p.id, cause)))
    )
    assert(pool(Empty.pState, p, TxEnv.Validation.NonStatic).isRight)
  }

  /** The pool registrations of the Leios build's decoding corpus
    * (`fixtures/reference/leios-testnet-blocks`): each registration certificate at the top level of
    * a transaction the reference's decoder accepted, found by the constructor the era decoder reads
    * (body field 4, certificate tag 3), with the name of its transaction. The reference's POOL did
    * not judge them.
    */
  private lazy val recordedRegistrations: Vector[(String, StakePoolParams)] =
    jsonLines("fixtures/reference/leios-testnet-blocks/cases.jsonl").flatMap { j =>
      val h = j.hcursor
      val name = h.get[String]("name").getOrElse(fail("name"))
      val acceptedTx =
        h.get[String]("kind").contains("tx") && h.get[Boolean]("accepted").contains(true)
      Option.when(acceptedTx)(name).toVector.flatMap { n =>
        val tx = ok(DijkstraTx.inBlock(node(hex(h.get[String]("bytes").getOrElse(fail(n))))))
        b.certs[TxLevel.Top](tx.body).collect {
          case DijkstraTxCert.DijkstraTxCertPool(PoolCert.RegPool(p)) => n -> p
        }
      }
    }

  test("POOL judges every recorded registration as the era decoder reads it, a component check") {
    // their reward accounts are on Mainnet
    val mainnet = leios.copy(ledger = leios.ledger.copy(network = 1))
    val judged = recordedRegistrations.map { (name, p) =>
      (
        name,
        p,
        ok(
          EraCertsRun.dijkstraPool(
            mainnet,
            PoolEnv[Dijkstra](epoch5, pp),
            Empty.pState,
            PoolCert.RegPool(p)
          )
        ).left.map(_.toChain.toVector)
      )
    }
    assertEquals(recordedRegistrations.size, 16)
    // the decoder admits a metadata hash of any size; POOL refuses one past Blake2b-256's, and that
    // alone
    val tooBig = "tx pool metadata with a hash of 70 bytes"
    val tooBigPool = judged.collectFirst { case (`tooBig`, p, _) => p.id }.getOrElse(fail(tooBig))
    assertEquals(
      judged.collect { case (name, _, Left(fs)) => name -> fs },
      Vector(
        tooBig -> Vector(DijkstraPoolPredFailure.PoolMedataHashTooBig[Dijkstra](tooBigPool, 70))
      )
    )
    val keyed = judged.collect { case (name, p, Right((ps, _))) =>
      assertEquals(ps.stakePools.get(p.id).map(_.blsKey), Some(p.blsKey.registered(epoch5)), name)
      p.blsKey match
        case PoolParamsBlsKey.Leios(Some(_))                          => Some(name)
        case PoolParamsBlsKey.Leios(None) | PoolParamsBlsKey.Node11_1 => None
    }
    assertEquals(keyed.size, 15)
    // the four with a voting key, whose proofs of possession verify
    assertEquals(keyed.flatten.size, 4)
  }

  test("a VRF key hash another pool holds is refused, for a new pool and a re-registration") {
    val registered = pool(Empty.pState, params(9, 1)).map(_._1).getOrElse(fail("not registered"))
    val withB = pool(registered, params(10, 2)).map(_._1).getOrElse(fail("not registered"))
    val taken = (p: StakePoolParams) =>
      Left(Vector(DijkstraPoolPredFailure.VRFKeyHashAlreadyRegistered[Dijkstra](p.id, p.vrf)))
    assertEquals(pool(registered, params(10, 1)).left.map(_.toChain.toVector), taken(params(10, 1)))
    assertEquals(pool(withB, params(10, 1)).left.map(_.toChain.toVector), taken(params(10, 1)))
  }

  test("Dijkstra's CERTS tells RegisterPool and ReregisterPool, embedded in CERT's and CERTS's") {
    val p = params(9, 1)
    val reg = DijkstraTxCert.DijkstraTxCertPool(PoolCert.RegPool(p))
    val judged = EraCertsRun.dijkstraCerts(leios, certsEnv, empty, Vector(reg, reg))
    def told(e: PoolEvent[Dijkstra]): CertsEvent[Dijkstra] =
      ConwayCertsEvent.CertEvent(ConwayCertEvent.PoolEvent(e))
    assertEquals(
      ok(judged).map(_._2.toVector),
      Right(Vector(told(PoolEvent.RegisterPool(p.id)), told(PoolEvent.ReregisterPool(p.id))))
    )
  }

  // ---- the testnet's chain -----------------------------------------------------------------------

  private lazy val chain: Vector[io.circe.Json] = chainContextCases

  private def eraTx(j: io.circe.Json): DijkstraTx[TxLevel.Top] =
    ok(DijkstraTx.inBlock(node(hex(str(j, "bytes")))))

  private def utxoOf(j: io.circe.Json): UTxO[Dijkstra] =
    UTxO[Dijkstra](
      j.hcursor
        .get[Vector[(String, String)]]("utxo")
        .fold(e => fail(e.message), identity)
        .map((i, o) =>
          txIn(ok(xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn.decode(node(hex(i))))) ->
            ok(DijkstraTxOut.decode(node(hex(o))))
        )
        .toMap
    )

  /** The environment's transaction is the 11.1 build's field, read only by that build's base case,
    * which Dijkstra's CERTS does not have (`CertsBaseCase`): the suite gives it the chain's first.
    */
  private lazy val certsEnv: CertsEnv[Dijkstra] =
    CertsEnv[Dijkstra](eraTx(chain.head), pp, epoch5, None, Map.empty)

  private def annotated(j: io.circe.Json): DijkstraStAnnTx.TopTx =
    DijkstraStAnnTx.mkDijkstraStAnnTopTx(s => Right(s * 1000), pp, utxoOf(j), Map.empty, eraTx(j))

  private def entities(
      j: io.circe.Json,
      state: CertState[Dijkstra],
      original: Accounts[Dijkstra]
  ) =
    EraCertsRun.entities(
      leios,
      EntitiesEnv[Dijkstra](epoch5, pp, None, Map.empty, original),
      state,
      annotated(j)
    )

  private def accountsOf(state: CertState[Dijkstra]): Accounts[Dijkstra] =
    c.certDStateL.get(state).accounts

  private lazy val withCertificates =
    chain.filter(j => b.certs[TxLevel.Top](eraTx(j).body).nonEmpty)

  test("CERTS on the chain's certificates gives the flat rule's failures, from the same state") {
    // 8 of the 10 transactions with certificates hold them at the top level; the other two in a
    // sub-transaction, SUBCERTS' (D7c)
    assertEquals(withCertificates.size, 8)
    val flatStart = Empty.certState[Dijkstra]
    withCertificates.foreach { j =>
      val name = str(j, "name")
      val era = ok(
        EraCertsRun.dijkstraCerts(leios, certsEnv, empty, b.certs[TxLevel.Top](eraTx(j).body))
      ).left.map(_.toChain.toVector.map(DijkstraCertsStored.toStored))
      val flatTx =
        ok(xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTx.inBlock(node(hex(str(j, "bytes")))))
      val flatJudged = DijkstraSts.judge(DijkstraCertsRule, Judgment.Policy.Accumulate)(
        TestLedger.globals,
        flat.rules.CertEnv(flatTx.id, testnetStored, 5, None, Map.empty),
        flatStart,
        flatTx.body.certificates
      )
      flatJudged match
        case Right(Left(fs)) => assertEquals(era, Left(fs.toChain.toVector), name)
        case Right(Right(s)) => assertEquals(era.map(_._1), Right(s), name)
        case Left(halt)      => fail(s"$name: the flat rule halts: $halt")
    }
  }

  private lazy val network: Network =
    Network.of(BigInt(leios.networkId)).getOrElse(fail(s"network ${leios.networkId}"))

  private def failuresOf[A](test: RuleTest[A]): Vector[A] =
    RuleTest.failures(test).toVector.flatMap(_.toChain.toVector)

  private def balanceOf(cred: Credential[Staking], accounts: Accounts[Dijkstra]): Option[Coin] =
    EraAccounts
      .lookupAccountState[Dijkstra](cred, accounts)
      .map(summon[ConwayEraAccounts[Dijkstra]].balanceAccountStateL.get(_).toCoin)

  /** ENTITIES' verdict on `j` from the empty state, derived without its transition: its predicates
    * on the body's maps as the reference orders them (`Entities.hs:261-314`, cardano-ledger
    * 9df9f98c), the empty state as the accounts before and after the top level's withdrawals, which
    * change no registered account, CERTS alone on that state one level down for the sub-rule's
    * block, and the direct deposits checked against, then added by hand to, the accounts CERTS
    * leaves. The root's failures are reported last first, a sub-rule's block as it recorded it
    * (`Judgment.flatten`). Where CERTS refuses a transaction that also deposits, the accounts CERTS
    * leaves are not observable here, and the transaction is named instead.
    */
  private def expectedFromEmpty(
      j: io.circe.Json
  ): Either[Vector[EntitiesPredFailure[Dijkstra]], CertState[Dijkstra]] =
    val name = str(j, "name")
    val body = eraTx(j).body
    val none = accountsOf(empty)
    val top = ok(ENTITIES.keyed(b.withdrawals[TxLevel.Top](body)))
    val sub = b
      .subTransactions(body)
      .toVector
      .flatMap(st => ok(ENTITIES.keyed(b.withdrawals[TxLevel.Sub](st.body)(using BothLevels.Sub))))
      .groupMapReduce(_._1)(_._2)((x, y) => Coin(x.value + y.value))
    val deposits = ok(ENTITIES.keyed(b.directDeposits[TxLevel.Top](body)))
    val legacy = EraUTxO.dijkstra.plutusLegacyModeStAnnTx(annotated(j))
    val wrongNetwork = failuresOf(
      ShelleyUtxo.validateWrongNetworkWithdrawal[Dijkstra, TxLevel.Top](network, body)
    ).map(f => ok(ENTITIES.shelleyUtxoToDijkstraEntitiesPredFailure(f)))
    val checks = failuresOf(
      RuleTest.all(
        ENTITIES.validateWrongNetworkInDirectDeposit(network, deposits),
        ENTITIES.validateAccountBalanceIntervals(
          network,
          none,
          ok(ENTITIES.keyed(b.accountBalanceIntervals[TxLevel.Top](body)))
        ),
        ENTITIES.validateStartingAccountBalanceIntervals(
          network,
          none,
          ok(ENTITIES.keyed(b.startingAccountBalanceIntervals(body)))
        ),
        ENTITIES.validateWithdrawalsAgainstOriginalAccounts(
          legacy,
          top,
          SortedMap.from(sub),
          network,
          none
        )
      )
    )
    val current =
      if wrongNetwork.isEmpty && checks.isEmpty then
        failuresOf(ENTITIES.validateWithdrawalsAgainstCurrentAccounts(legacy, top, network, none))
      else Vector.empty
    val certs = ok(
      EraCertsRun.dijkstraCerts(leios, certsEnv, empty, b.certs[TxLevel.Top](body), depth = 1)
    )
    val (certsBlock, afterCerts) = certs match
      case Left(fs) if deposits.isEmpty => (fs.toChain.toVector, empty)
      case Left(fs)                     => fail(s"$name: CERTS refuses it and it deposits: $fs")
      case Right((state, _))            => (Vector.empty, state)
    val depositFailures = failuresOf(
      ENTITIES.validateMissingAccountsInDirectDeposits(deposits, network, accountsOf(afterCerts))
    )
    val failures =
      depositFailures.reverse ++ certsBlock.map(EntitiesPredFailure.CertsFailure[Dijkstra](_)) ++
        current.reverse ++ checks.reverse ++ wrongNetwork.reverse
    val deposited = deposits.foldLeft(accountsOf(afterCerts)) { case (accounts, (address, coin)) =>
      EraAccounts.adjustAccountState[Dijkstra](
        summon[ConwayEraAccounts[Dijkstra]].balanceAccountStateL
          .modify(_.add(ok(CompactCoin.fromCoin(coin)))),
        address.id.credential,
        accounts
      )
    }
    Either.cond(
      failures.isEmpty,
      c.certDStateL.andThen(DState.accounts[Accounts[Dijkstra]]).replace(deposited)(afterCerts),
      failures
    )

  test("ENTITIES gives on every chain transaction from the empty state what its parts derive") {
    assertEquals(chain.size, 200)
    val refused = chain.map { j =>
      val name = str(j, "name")
      val judged = ok(entities(j, empty, accountsOf(empty))).left
        .map(_.toChain.toVector)
        .map(_._1)
      assertEquals(judged, expectedFromEmpty(j), name)
      judged.isLeft
    }
    // the reference judged them on the chain's accounts, which the fixture does not record (CNF-2)
    assertEquals(refused.count(identity), 23)
  }

  /** The chain's first transaction that registers an account at the top level and has no
    * withdrawal, direct deposit or balance interval, and the registration's credential.
    */
  private lazy val registersAnAccount: (io.circe.Json, Credential[Staking]) =
    chain.iterator
      .flatMap { j =>
        val body = eraTx(j).body
        val plain = b.withdrawals[TxLevel.Top](body).isEmpty &&
          b.directDeposits[TxLevel.Top](body).isEmpty &&
          b.accountBalanceIntervals[TxLevel.Top](body).isEmpty &&
          b.startingAccountBalanceIntervals(body).isEmpty
        b.certs[TxLevel.Top](body)
          .collectFirst {
            case DijkstraTxCert.DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraRegCert(cred, _)) =>
              cred
            case DijkstraTxCert.DijkstraTxCertDeleg(
                  DijkstraDelegCert.DijkstraRegDelegCert(cred, _, _)
                ) =>
              cred
          }
          .filter(_ => plain)
          .map(j -> _)
      }
      .nextOption()
      .getOrElse(fail("no such transaction in the fixture"))

  /** The bytes of `j` 's transaction with a direct deposit of `amount` to `cred` 's account on the
    * node's network (body field 25), the rest of the transaction as it was read, encoded again.
    */
  private def withDeposit(j: io.circe.Json, cred: Credential[Staking], amount: BigInt): Bytes =
    val account = Convert.back(cred) match
      case flat.Credential.Key(h)    => f"${0xe0 | network.wire}%02x${h.hex}"
      case flat.Credential.Script(h) => f"${0xf0 | network.wire}%02x${h.hex}"
    def leaf(v: Value): Node = Node(v, Bytes.empty)
    val deposit = leaf(Value.UInt(25)) ->
      leaf(Value.Map(Vector(leaf(Value.ByteString(hex(account))) -> leaf(Value.UInt(amount)))))
    node(hex(str(j, "bytes"))).value match
      case Value.Arr(txBody +: rest) =>
        txBody.value match
          case Value.Map(fields) =>
            Cbor
              .encode(Value.Arr(leaf(Value.Map(fields :+ deposit)) +: rest))
              .fold(e => fail(s"$e"), identity)
          case other => fail(s"not a body: $other")
      case other => fail(s"not a transaction: $other")

  test("ENTITIES checks and adds a direct deposit after CERTS registers its account") {
    val (j, cred) = registersAnAccount
    val tx = ok(DijkstraTx.inBlock(node(withDeposit(j, cred, BigInt(1234567)))))
    assertEquals(
      b.directDeposits[TxLevel.Top](tx.body).values.toVector,
      Vector(Coin(BigInt(1234567)))
    )
    val stAnnTx =
      DijkstraStAnnTx.mkDijkstraStAnnTopTx(s => Right(s * 1000), pp, utxoOf(j), Map.empty, tx)
    // CERTS alone registers the account, with no balance
    val registered = ok(
      EraCertsRun.dijkstraCerts(leios, certsEnv, empty, b.certs[TxLevel.Top](tx.body))
    ).map(_._1).getOrElse(fail("CERTS refuses the registration"))
    assertEquals(balanceOf(cred, accountsOf(registered)), Some(Coin(0)))
    EraCertsRun.entities(
      leios,
      EntitiesEnv[Dijkstra](epoch5, pp, None, Map.empty, accountsOf(empty)),
      empty,
      stAnnTx
    ) match
      case Right(Right((after, _))) =>
        assertEquals(balanceOf(cred, accountsOf(after)), Some(Coin(BigInt(1234567))))
      case other => fail(s"not admitted: $other")
  }

  /** The chain's transaction that withdraws from an account and unregisters it and another. */
  private lazy val withdrawAndUnregister: io.circe.Json =
    chain.find(j => str(j, "name").startsWith("b7cdf8d2a463")).getOrElse(fail("not in the fixture"))

  private def account(cred: Credential[Staking], balance: BigInt)(
      accounts: Accounts[Dijkstra]
  ): Accounts[Dijkstra] =
    val registered = ConwayEraAccounts.registerConwayAccount[Dijkstra](
      cred,
      ok(CompactCoin.fromBigInt(BigInt(2000000))),
      None,
      accounts
    )
    EraAccounts.adjustAccountState[Dijkstra](
      summon[ConwayEraAccounts[Dijkstra]].balanceAccountStateL
        .replace(ok(CompactCoin.fromBigInt(balance))),
      cred,
      registered
    )

  private def withAccounts(f: Accounts[Dijkstra] => Accounts[Dijkstra]): CertState[Dijkstra] =
    c.certDStateL.andThen(DState.accounts[Accounts[Dijkstra]]).modify(f)(empty)

  test("ENTITIES applies a withdrawal before CERTS, and a withdrawal past the balance wraps") {
    val j = withdrawAndUnregister
    val body = eraTx(j).body
    val withdrawals = b.withdrawals[TxLevel.Top](body).toVector
    val (account1, amount) = withdrawals.headOption.getOrElse(fail("no withdrawal"))
    val creds = b.certs[TxLevel.Top](body).collect { case DijkstraTxCert.DijkstraTxCertDeleg(d) =>
      d.credential
    }
    assertEquals(creds.size, 2)
    val cred1 = ok(Convert.credential[Staking](account1.credential))
    val cred2 = creds.find(_ != cred1).getOrElse(fail("one credential"))
    def state(balance: BigInt) = withAccounts(account(cred1, balance).andThen(account(cred2, 0)))
    // the withdrawal drains the account, which then unregisters with no balance
    val drained = state(amount.value)
    entities(j, drained, accountsOf(drained)) match
      case Right(Right((after, _))) =>
        assertEquals(accountsOf(after), accountsOf(empty))
      case other => fail(s"not admitted: $other")
    // a withdrawal past the original balance: the current accounts are not checked, the balance
    // wraps, and DELEG reports the wrapped balance; the failures are in the order the reference
    // reports those of the rule it applies, the last first
    val short = state(amount.value - 1000000)
    val wrapped = Coin(BigInt(2).pow(64) - 1000000)
    val withdrawn = ok(Convert.accountAddress(account1))
    assertEquals(
      entities(j, short, accountsOf(short)).map(_.left.map(_.toChain.toVector)),
      Right(
        Left(
          Vector(
            EntitiesPredFailure.CertsFailure[Dijkstra](
              ConwayCertsPredFailure.CertFailure(
                ConwayCertPredFailure.DelegFailure(
                  ConwayDelegPredFailure.StakeKeyHasNonZeroAccountBalanceDELEG(wrapped)
                )
              )
            ),
            EntitiesPredFailure.WithdrawalAmountsExceedingOriginalBalance[Dijkstra](
              NonEmptyMap.one(withdrawn, Mismatch(amount, Coin(amount.value - 1000000)))
            )
          )
        )
      )
    )
    // the account the original accounts hold but the current ones lack is missing there, which
    // is checked as nothing failed before, and CERTS cannot unregister it
    assertEquals(
      entities(j, withAccounts(account(cred2, 0)), accountsOf(drained))
        .map(_.left.map(_.toChain.toVector)),
      Right(
        Left(
          Vector(
            EntitiesPredFailure.CertsFailure[Dijkstra](
              ConwayCertsPredFailure.CertFailure(
                ConwayCertPredFailure.DelegFailure(
                  ConwayDelegPredFailure.StakeKeyNotRegisteredDELEG(cred1)
                )
              )
            ),
            EntitiesPredFailure.WithdrawalAccountsMissing[Dijkstra](Map(withdrawn -> amount))
          )
        )
      )
    )
    // in either mode: the original accounts' balance is short and the current accounts lack the
    // account, which is not reported, as a check before failed
    val shortOriginal = accountsOf(short)
    assertEquals(
      entities(j, withAccounts(account(cred2, 0)), shortOriginal)
        .map(_.left.map(_.toChain.toVector)),
      Right(
        Left(
          Vector(
            EntitiesPredFailure.CertsFailure[Dijkstra](
              ConwayCertsPredFailure.CertFailure(
                ConwayCertPredFailure.DelegFailure(
                  ConwayDelegPredFailure.StakeKeyNotRegisteredDELEG(cred1)
                )
              )
            ),
            EntitiesPredFailure.WithdrawalAmountsExceedingOriginalBalance[Dijkstra](
              NonEmptyMap.one(withdrawn, Mismatch(amount, Coin(amount.value - 1000000)))
            )
          )
        )
      )
    )
  }

  // ---- the injections ----------------------------------------------------------------------------

  private val poolId = ok(KeyHash.fromBytes[StakePool](Bytes(Vector.fill(28)(9.toByte))))
  private val shelleyPool = ShelleyPoolPredFailure.StakePoolNotRegisteredOnKeyPOOL[Dijkstra](poolId)
  private val dijkstraPool =
    DijkstraPoolPredFailure.StakePoolNotRegisteredOnKeyPOOL[Dijkstra](poolId)
  private val drep = Credential.KeyHashObj(
    ok(
      KeyHash.fromBytes[xyz.kd5ujc.scarab.ledger.state.DRepRole](Bytes(Vector.fill(28)(3.toByte)))
    )
  )
  private val conwayGovCert = ConwayGovCertPredFailure.ConwayDRepNotRegistered[Dijkstra](drep)
  private val dijkstraGovCert = DijkstraGovCertPredFailure.DijkstraDRepNotRegistered[Dijkstra](drep)
  private val deleg = ConwayDelegPredFailure.IncorrectDepositDELEG[Dijkstra](Coin(1))

  private def inEntities(cert: ConwayCertPredFailure[Dijkstra]): EntitiesPredFailure[Dijkstra] =
    EntitiesPredFailure.CertsFailure(ConwayCertsPredFailure.CertFailure(cert))

  test("each injection into CERT, CERTS and ENTITIES equals the reference's composition") {
    def ent[F](f: F)(using i: InjectEntitiesFailure[F, Dijkstra]) = i.injectFailure(f)
    assertEquals(ent(shelleyPool), inEntities(ConwayCertPredFailure.PoolFailure(dijkstraPool)))
    assertEquals(ent(dijkstraPool), inEntities(ConwayCertPredFailure.PoolFailure(dijkstraPool)))
    assertEquals(
      ent(conwayGovCert),
      inEntities(ConwayCertPredFailure.GovCertFailure(dijkstraGovCert))
    )
    assertEquals(
      ent(dijkstraGovCert),
      inEntities(ConwayCertPredFailure.GovCertFailure(dijkstraGovCert))
    )
    assertEquals(ent(deleg), inEntities(ConwayCertPredFailure.DelegFailure(deleg)))
    val cert = ConwayCertPredFailure.DelegFailure[Dijkstra](deleg)
    assertEquals(ent(cert), inEntities(cert))
    val certs = ConwayCertsPredFailure.CertFailure[Dijkstra](cert)
    assertEquals(ent(certs), EntitiesPredFailure.CertsFailure[Dijkstra](certs))
    val own = EntitiesPredFailure.WithdrawalAccountsMissing[Dijkstra](Map.empty)
    assertEquals(ent(own), own)
    // Shelley's one constructor ENTITIES reaches, its accounts read into `AccountAddress`
    val mainnet = flat.RewardAccount(1, flat.Credential.Key(Bytes(Vector.fill(28)(4.toByte))))
    assertEquals(
      ENTITIES.shelleyUtxoToDijkstraEntitiesPredFailure(
        ShelleyUtxoPredFailure.WrongNetworkWithdrawal[Dijkstra](
          Network.Testnet,
          NonEmptySet.one(mainnet)
        )
      ),
      Right(
        new EntitiesPredFailure.WithdrawalAddressesWithWrongNetwork[Dijkstra](
          Network.Testnet,
          NonEmptySet.one(ok(Convert.accountAddress(mainnet)))
        )
      )
    )
  }

  test("Dijkstra's DELEG injects Shelley's AccountAlreadyRegistered as its own refusal of one") {
    val (j, cred) = registersAnAccount
    val registration = b.certs[TxLevel.Top](eraTx(j).body).collect {
      case c @ DijkstraTxCert.DijkstraTxCertDeleg(d) if d.credential == cred => c
    }
    // `Dijkstra/Rules/Deleg.hs:19-20` (cardano-ledger 9df9f98c): `DelegAccountAlreadyRegistered`
    val injected = summon[InjectDelegFailure[AccountAlreadyRegistered[Dijkstra], Dijkstra]]
      .injectFailure(AccountAlreadyRegistered[Dijkstra](cred))
    assertEquals(injected, ConwayDelegPredFailure.StakeKeyRegisteredDELEG[Dijkstra](cred))
    // the same as DELEG's own refusal of a second registration, through CERT and CERTS as LEDGER
    // composes it (`Dijkstra/Rules/Ledger.hs:113-119`, cardano-ledger 9df9f98c)
    assertEquals(
      ok(
        EraCertsRun.dijkstraCerts(leios, certsEnv, empty, registration ++ registration)
      ).left.map(_.toChain.toVector),
      Left(
        Vector(
          ConwayCertsPredFailure.CertFailure[Dijkstra](ConwayCertPredFailure.DelegFailure(injected))
        )
      )
    )
  }

  test("CERTS's withdrawals' case of the 11.1 build has no value at Dijkstra") {
    // the control: Conway's era types follow the 11.1 build's ledger, which has it
    assertEquals(
      compileErrors(
        "ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS[xyz.kd5ujc.scarab.ledger.state.Conway](" +
          "Map.empty[AccountAddress, Coin])"
      ),
      ""
    )
    // `newtype ConwayCertsPredFailure era = CertFailure ...` (`Conway/Rules/Certs.hs:111-114`,
    // cardano-ledger 9df9f98c)
    assert(
      compileErrors(
        "ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS[Dijkstra](" +
          "Map.empty[AccountAddress, Coin])"
      ).nonEmpty
    )
  }

  test("the constructors the reference calls impossible for ENTITIES have no injection") {
    // the controls: a constructor ENTITIES reaches has one, in the same form
    assertEquals(
      compileErrors("summon[InjectEntitiesFailure[DijkstraPoolPredFailure[Dijkstra], Dijkstra]]"),
      ""
    )
    assertEquals(
      compileErrors(
        "ENTITIES.shelleyUtxoToDijkstraEntitiesPredFailure(" +
          "ShelleyUtxoPredFailure.WrongNetworkWithdrawal[Dijkstra](Network.Testnet, " +
          "NonEmptySet.one(flat.RewardAccount(0, flat.Credential.Key(Bytes.empty)))))"
      ),
      ""
    )
    // Conway's LEDGER failure, impossible on every constructor (`Entities.hs:413-426`,
    // cardano-ledger 9df9f98c)
    assert(
      compileErrors(
        "summon[InjectEntitiesFailure[ConwayLedgerPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    // Shelley's UTXO failure but `WrongNetworkWithdrawal` (`Entities.hs:428-443`, cardano-ledger
    // 9df9f98c)
    assert(
      compileErrors(
        "summon[InjectEntitiesFailure[ShelleyUtxoPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "ENTITIES.shelleyUtxoToDijkstraEntitiesPredFailure(" +
          "ShelleyUtxoPredFailure.InputSetEmptyUTxO[Dijkstra]())"
      ).nonEmpty
    )
  }

  // ---- ENTITIES' predicates ----------------------------------------------------------------------

  private def address(n: Int, network: Network = Network.Testnet): AccountAddress =
    AccountAddress(
      network,
      AccountId(
        Credential.KeyHashObj(ok(KeyHash.fromBytes[Staking](Bytes(Vector.fill(28)(n.toByte)))))
      )
    )

  private def cred(n: Int): Credential[Staking] = address(n).id.credential

  private lazy val two: Accounts[Dijkstra] =
    accountsOf(withAccounts(account(cred(1), 10).andThen(account(cred(2), 0))))

  test("the balance intervals: another network, an account not registered, a balance outside") {
    import AccountBalanceInterval.*
    val intervals = SortedMap(
      address(1) -> AccountBalanceExact(Coin(10)),
      address(2) -> AccountBalanceLowerBound(Inclusive(Coin(1))),
      address(3) -> AccountBalanceUpperBound(Exclusive(Coin(5))),
      address(4, Network.Mainnet) -> AccountBalanceBothBounds(
        Inclusive(Coin(0)),
        Exclusive(Coin(1))
      )
    )
    assertEquals(
      ENTITIES
        .validateAccountBalanceIntervals(Network.Testnet, two, intervals)
        .swap
        .toOption
        .map(_.toChain.toVector.map(f => f: EntitiesPredFailure[Dijkstra])),
      Some(
        Vector(
          EntitiesPredFailure.WrongNetworkInAccountBalanceIntervals[Dijkstra](
            Network.Testnet,
            NonEmptySet.one(address(4, Network.Mainnet))
          ),
          EntitiesPredFailure.MissingAccountsInAccountBalanceIntervals[Dijkstra](
            NonEmptyMap.one(address(3), AccountBalanceUpperBound(Exclusive(Coin(5))))
          ),
          EntitiesPredFailure.BalancesOutsideAccountBalanceIntervals[Dijkstra](
            NonEmptyMap.one(address(2), (Coin(0), AccountBalanceLowerBound(Inclusive(Coin(1)))))
          )
        )
      )
    )
    assert(
      ENTITIES
        .validateStartingAccountBalanceIntervals(
          Network.Testnet,
          two,
          SortedMap(
            address(1) -> AccountBalanceBothBounds(Inclusive(Coin(10)), Exclusive(Coin(11)))
          )
        )
        .isValid
    )
  }

  test("the direct deposits: another network, an account not registered, and their sum") {
    val deposits = SortedMap(address(1) -> Coin(5), address(3) -> Coin(1))
    assertEquals(
      ENTITIES
        .validateMissingAccountsInDirectDeposits(deposits, Network.Testnet, two)
        .leftMap(_.map(f => f: EntitiesPredFailure[Dijkstra])),
      cats.data.Validated.invalidNec(
        EntitiesPredFailure.DirectDepositAccountsMissing[Dijkstra](SortedMap(address(3) -> Coin(1)))
      )
    )
    assertEquals(
      ENTITIES
        .validateWrongNetworkInDirectDeposit(
          Network.Testnet,
          SortedMap(address(1, Network.Mainnet) -> Coin(1))
        )
        .swap
        .toOption
        .map(_.toChain.toVector.map(f => f: EntitiesPredFailure[Dijkstra])),
      Some(
        Vector(
          EntitiesPredFailure.DirectDepositAddressesWithWrongNetwork[Dijkstra](
            Network.Testnet,
            NonEmptySet.one(address(1, Network.Mainnet))
          )
        )
      )
    )
    val after = ok(ENTITIES.applyDirectDeposits(deposits, two))
    assertEquals(
      EraAccounts
        .lookupAccountState[Dijkstra](cred(1), after)
        .map(
          summon[ConwayEraAccounts[Dijkstra]].balanceAccountStateL.get(_).toCoin
        ),
      Some(Coin(15))
    )
  }

  test("the withdrawals against the original accounts in either mode, and against the current") {
    val top = SortedMap(address(1) -> Coin(6), address(3) -> Coin(1))
    val sub = SortedMap(address(1) -> Coin(5))
    assertEquals(
      ENTITIES
        .validateWithdrawalsAgainstOriginalAccounts(
          PlutusLegacyMode.NotLegacy,
          top,
          sub,
          Network.Testnet,
          two
        )
        .swap
        .toOption
        .map(_.toChain.toVector),
      Some(
        Vector(
          EntitiesPredFailure.WithdrawalAccountsMissingFromOriginal[Dijkstra](
            Map(address(3) -> Coin(1))
          ),
          EntitiesPredFailure.WithdrawalAmountsExceedingOriginalBalance[Dijkstra](
            NonEmptyMap.one(address(1), Mismatch(Coin(11), Coin(10)))
          )
        )
      )
    )
    // the legacy mode reads the sub-transactions' withdrawals alone, and not the missing accounts
    assert(
      ENTITIES
        .validateWithdrawalsAgainstOriginalAccounts(
          PlutusLegacyMode.Legacy,
          top,
          sub,
          Network.Testnet,
          two
        )
        .isValid
    )
    assertEquals(
      ENTITIES
        .validateWithdrawalsAgainstCurrentAccounts(
          PlutusLegacyMode.Legacy,
          top,
          Network.Testnet,
          two
        )
        .swap
        .toOption
        .map(_.toChain.toVector),
      Some(
        Vector(
          EntitiesPredFailure.WithdrawalAccountsMissing[Dijkstra](Map(address(3) -> Coin(1))),
          EntitiesPredFailure.WithdrawalAmountsInexactInLegacyMode[Dijkstra](
            NonEmptyMap.one(address(1), Mismatch(Coin(6), Coin(10)))
          )
        )
      )
    )
  }

  // ---- a sub-transaction's certificates and SUBENTITIES ---------------------------------------

  private given BothLevels[TxLevel.Sub] = BothLevels.Sub

  private def subTxs(j: io.circe.Json): Vector[DijkstraTx[TxLevel.Sub]] =
    b.subTransactions(eraTx(j).body).toVector

  /** The chain's sub-transactions, with the transaction that holds them. */
  private lazy val chainSubTxs: Vector[(io.circe.Json, DijkstraTx[TxLevel.Sub])] =
    chain.flatMap(j => subTxs(j).map(j -> _))

  private def subCertsEnv(tx: DijkstraTx[TxLevel.Sub]): SubCertsEnv =
    SubCertsEnv(tx, pp, epoch5, None, Map.empty)

  /** CERTS's events as SUBCERTS's, as their embeddings make them. */
  private def subEvent(e: CertsEvent[Dijkstra]): SubCertsEvent[Dijkstra] = e match
    case ConwayCertsEvent.CertEvent(ConwayCertEvent.PoolEvent(p)) =>
      DijkstraSubCertsEvent(DijkstraSubCertEvent.SubPoolEvent(DijkstraSubPoolEvent(p)))
    case ConwayCertsEvent.CertEvent(ConwayCertEvent.DelegEvent(n))   => n
    case ConwayCertsEvent.CertEvent(ConwayCertEvent.GovCertEvent(n)) => n

  /** SUBCERTS on `certs` from `state` against CERTS's: SUBCERTS's failures are CERTS's through the
    * reference's `conwayToDijkstraSubCertsPredFailure`, in the same order, and its state and events
    * are CERTS's.
    */
  private def assertSubCertsAsCerts(
      certs: Vector[DijkstraTxCert],
      state: CertState[Dijkstra],
      clue: String
  ): Boolean =
    val sub = subCertsEnv(chainSubTxs.head._2)
    (
      EraCertsRun.dijkstraSubCerts(leios, sub, state, certs),
      EraCertsRun.dijkstraCerts(leios, certsEnv, state, certs)
    ) match
      case (Right(Left(subFs)), Right(Left(fs))) =>
        assertEquals(
          subFs.toChain.toVector,
          fs.toChain.toVector.map(DijkstraSUBCERTS.conwayToDijkstraSubCertsPredFailure),
          clue
        )
        false
      case (Right(Right((subState, subEvents))), Right(Right((st, events)))) =>
        assertEquals(subState, st, clue)
        assertEquals(subEvents.toVector, events.toVector.map(subEvent), clue)
        true
      case other => fail(s"$clue: $other")

  test("SUBCERTS gives CERTS's verdicts, states and events through the reference's injection") {
    val p = params(9, 1)
    val reg = DijkstraTxCert.DijkstraTxCertPool(PoolCert.RegPool(p))
    val (j, cred) = registersAnAccount
    val registration = b.certs[TxLevel.Top](eraTx(j).body).collect {
      case c @ DijkstraTxCert.DijkstraTxCertDeleg(d) if d.credential == cred => c
    }
    // a pool registered twice (its events), an account registered twice (DELEG's refusal), the
    // certificates of the chain's transactions that hold them at the top level and in a
    // sub-transaction, which the chain accepted
    val subCerts = chainSubTxs.map(_._2).filter(t => b.certs[TxLevel.Sub](t.body).nonEmpty)
    assertEquals(subCerts.size, 2)
    val cases = Vector(
      "a pool twice" -> Vector(reg, reg),
      "an account twice" -> (registration ++ registration)
    ) ++
      withCertificates.map(j => str(j, "name") -> b.certs[TxLevel.Top](eraTx(j).body)) ++
      subCerts.map(t => "a sub-transaction" -> b.certs[TxLevel.Sub](t.body))
    val admitted = cases.count((name, certs) => assertSubCertsAsCerts(certs, empty, name))
    // the two sub-transactions' registrations are admitted from the empty state
    assertEquals(
      subCerts.map(t =>
        EraCertsRun
          .dijkstraSubCerts(leios, subCertsEnv(t), empty, b.certs[TxLevel.Sub](t.body))
          .map(_.isRight)
      ),
      Vector(Right(true), Right(true))
    )
    assertEquals((withCertificates.size, cases.size, admitted), (8, 12, 9))
    // the second registration of an account: DELEG's refusal in SUBDELEG's newtype, as SUBDELEG's
    // injection of Shelley's `AccountAlreadyRegistered` gives it (`Dijkstra/Rules/SubDeleg.hs:57-58`,
    // cardano-ledger 9df9f98c), through SUBCERT and SUBCERTS
    assertEquals(
      ok(
        EraCertsRun.dijkstraSubCerts(
          leios,
          subCertsEnv(chainSubTxs.head._2),
          empty,
          registration ++ registration
        )
      ).left.map(_.toChain.toVector),
      Left(
        Vector(
          DijkstraSubCertsPredFailure[Dijkstra](
            DijkstraSubCertPredFailure.SubDelegFailure(
              summon[InjectSubDelegFailure[AccountAlreadyRegistered[Dijkstra], Dijkstra]]
                .injectFailure(AccountAlreadyRegistered[Dijkstra](cred))
            )
          )
        )
      )
    )
  }

  test("each injection of the sub-transaction rules is the newtype after the top-level rule's") {
    val (_, cred) = registersAnAccount
    val already = AccountAlreadyRegistered[Dijkstra](cred)
    assertEquals(
      summon[InjectSubDelegFailure[AccountAlreadyRegistered[Dijkstra], Dijkstra]]
        .injectFailure(already),
      DijkstraSubDelegPredFailure[Dijkstra](
        summon[InjectDelegFailure[AccountAlreadyRegistered[Dijkstra], Dijkstra]]
          .injectFailure(already)
      )
    )
    assertEquals(
      summon[InjectSubDelegFailure[ConwayDelegPredFailure[Dijkstra], Dijkstra]]
        .injectFailure(deleg),
      DijkstraSubDelegPredFailure[Dijkstra](InjectDeleg.dijkstra.injectFailure(deleg))
    )
    assertEquals(
      summon[InjectSubPoolFailure[ShelleyPoolPredFailure[Dijkstra], Dijkstra]]
        .injectFailure(shelleyPool),
      DijkstraSubPoolPredFailure[Dijkstra](InjectPool.dijkstra.injectFailure(shelleyPool))
    )
    assertEquals(
      summon[InjectSubPoolFailure[DijkstraPoolPredFailure[Dijkstra], Dijkstra]]
        .injectFailure(dijkstraPool),
      DijkstraSubPoolPredFailure[Dijkstra](InjectDijkstraPool.dijkstra.injectFailure(dijkstraPool))
    )
    assertEquals(
      summon[InjectSubGovCertFailure[ConwayGovCertPredFailure[Dijkstra], Dijkstra]]
        .injectFailure(conwayGovCert),
      DijkstraSubGovCertPredFailure[Dijkstra](InjectGovCert.dijkstra.injectFailure(conwayGovCert))
    )
    assertEquals(
      summon[InjectSubGovCertFailure[DijkstraGovCertPredFailure[Dijkstra], Dijkstra]]
        .injectFailure(dijkstraGovCert),
      DijkstraSubGovCertPredFailure[Dijkstra](dijkstraGovCert)
    )
    // CERT's and CERTS's failures as SUBCERT's and SUBCERTS's (`SubCert.hs:233-246`,
    // `SubCerts.hs:183-190`, cardano-ledger 9df9f98c), and SUBENTITIES' of CERTS's (`:194-195`)
    val certs = Vector(
      ConwayCertPredFailure.DelegFailure[Dijkstra](deleg) ->
        DijkstraSubCertPredFailure.SubDelegFailure[Dijkstra](
          DijkstraSubDelegPredFailure[Dijkstra](deleg)
        ),
      ConwayCertPredFailure.PoolFailure[Dijkstra](dijkstraPool) ->
        DijkstraSubCertPredFailure.SubPoolFailure[Dijkstra](
          DijkstraSubPoolPredFailure[Dijkstra](dijkstraPool)
        ),
      ConwayCertPredFailure.GovCertFailure[Dijkstra](dijkstraGovCert) ->
        DijkstraSubCertPredFailure.SubGovCertFailure[Dijkstra](
          DijkstraSubGovCertPredFailure[Dijkstra](dijkstraGovCert)
        )
    )
    certs.foreach { (cert, sub) =>
      assertEquals(
        summon[InjectSubCertFailure[ConwayCertPredFailure[Dijkstra], Dijkstra]].injectFailure(cert),
        sub
      )
      assertEquals(
        summon[InjectSubCertsFailure[ConwayCertsPredFailure[Dijkstra], Dijkstra]]
          .injectFailure(ConwayCertsPredFailure.CertFailure[Dijkstra](cert)),
        DijkstraSubCertsPredFailure[Dijkstra](sub)
      )
      assertEquals(
        summon[InjectSubEntitiesFailure[ConwayCertsPredFailure[Dijkstra], Dijkstra]]
          .injectFailure(ConwayCertsPredFailure.CertFailure[Dijkstra](cert)),
        SubEntitiesPredFailure.SubCertsFailure[Dijkstra](DijkstraSubCertsPredFailure[Dijkstra](sub))
      )
    }
  }

  test(
    "ENTITIES' failures SUBENTITIES reaches map as the reference's, the impossible ones do not"
  ) {
    val net = Network.Testnet
    val addrs = NonEmptySet.one(address(1))
    val missing = SortedMap(address(1) -> Coin(1))
    val interval = AccountBalanceInterval.AccountBalanceExact(Coin(3))
    // `entitiesToSubEntitiesPredFailure` (`Dijkstra/Rules/SubEntities.hs:317-338`, cardano-ledger
    // 9df9f98c) on the six constructors it maps
    val mapped = Vector[(DijkstraSubEntities.EntitiesFailure, SubEntitiesPredFailure[Dijkstra])](
      new EntitiesPredFailure.WithdrawalAddressesWithWrongNetwork[Dijkstra](net, addrs) ->
        SubEntitiesPredFailure.SubWithdrawalAddressesWithWrongNetwork(net, addrs),
      new EntitiesPredFailure.DirectDepositAddressesWithWrongNetwork[Dijkstra](net, addrs) ->
        SubEntitiesPredFailure.SubDirectDepositAddressesWithWrongNetwork(net, addrs),
      new EntitiesPredFailure.DirectDepositAccountsMissing[Dijkstra](missing) ->
        SubEntitiesPredFailure.SubDirectDepositAccountsMissing(missing),
      new EntitiesPredFailure.WrongNetworkInAccountBalanceIntervals[Dijkstra](net, addrs) ->
        SubEntitiesPredFailure.SubWrongNetworkInAccountBalanceIntervals(net, addrs),
      new EntitiesPredFailure.MissingAccountsInAccountBalanceIntervals[Dijkstra](
        NonEmptyMap.one(address(1), interval)
      ) -> SubEntitiesPredFailure.SubMissingAccountsInAccountBalanceIntervals(
        NonEmptyMap.one(address(1), interval)
      ),
      new EntitiesPredFailure.BalancesOutsideAccountBalanceIntervals[Dijkstra](
        NonEmptyMap.one(address(1), (Coin(2), interval))
      ) -> SubEntitiesPredFailure.SubBalancesOutsideAccountBalanceIntervals(
        NonEmptyMap.one(address(1), (Coin(2), interval))
      )
    )
    mapped.foreach((f, expected) =>
      assertEquals(
        summon[InjectSubEntitiesFailure[DijkstraSubEntities.EntitiesFailure, Dijkstra]]
          .injectFailure(f),
        expected
      )
    )
    // Shelley's, through ENTITIES' function (`:203-204`)
    val mainnet = flat.RewardAccount(1, flat.Credential.Key(Bytes(Vector.fill(28)(4.toByte))))
    assertEquals(
      DijkstraSubEntities.shelleyUtxoToDijkstraSubEntitiesPredFailure(
        new ShelleyUtxoPredFailure.WrongNetworkWithdrawal[Dijkstra](net, NonEmptySet.one(mainnet))
      ),
      Right(
        SubEntitiesPredFailure.SubWithdrawalAddressesWithWrongNetwork[Dijkstra](
          net,
          NonEmptySet.one(ok(Convert.accountAddress(mainnet)))
        )
      )
    )
    // the control: a constructor SUBENTITIES reaches has an injection, in the same form
    assertEquals(
      compileErrors(
        "summon[InjectSubEntitiesFailure[EntitiesPredFailure.DirectDepositAccountsMissing[Dijkstra], Dijkstra]]"
      ),
      ""
    )
    assert(
      compileErrors(
        "summon[InjectSubEntitiesFailure[EntitiesPredFailure.WithdrawalAccountsMissing[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubEntitiesFailure[EntitiesPredFailure.WithdrawalAmountsExceedingOriginalBalance[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubEntitiesFailure[EntitiesPredFailure.BalancesOutsideStartingAccountBalanceIntervals[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubEntitiesFailure[EntitiesPredFailure.CertsFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubEntitiesFailure[EntitiesPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubEntitiesFailure[ConwayLedgerPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubEntitiesFailure[ShelleyUtxoPredFailure.WrongNetworkWithdrawal[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
  }

  private def subEntities(
      tx: DijkstraTx[TxLevel.Sub],
      state: CertState[Dijkstra],
      original: Accounts[Dijkstra]
  ) =
    EraCertsRun.subEntities(
      leios,
      SubEntitiesEnv[Dijkstra](epoch5, pp, None, Map.empty, original),
      state,
      tx
    )

  private def subWithdrawals(tx: DijkstraTx[TxLevel.Sub]): SortedMap[AccountAddress, Coin] =
    ok(ENTITIES.keyed(b.withdrawals[TxLevel.Sub](tx.body)))

  /** What SUBENTITIES did with a chain sub-transaction from the empty state. */
  private enum SubEntitiesOutcome derives CanEqual:
    case Withdraws, Registers, Plain

  test("SUBENTITIES on every chain sub-transaction from the empty state, a component check") {
    // from the empty state, as the reference did not judge them: a sub-transaction that withdraws
    // is refused for the accounts the current and the original accounts lack, the root's list in
    // the reverse of their order; one that registers an account leaves SUBCERTS's state alone; the
    // rest leave the state as it was
    assertEquals(chainSubTxs.size, 38)
    val kinds = chainSubTxs.map { (j, tx) =>
      val name = str(j, "name")
      val withdrawals = subWithdrawals(tx)
      val certs = b.certs[TxLevel.Sub](tx.body)
      subEntities(tx, empty, accountsOf(empty)) match
        case Right(Left(fs)) if withdrawals.nonEmpty =>
          assertEquals(
            fs.toChain.toVector,
            Vector[SubEntitiesPredFailure[Dijkstra]](
              SubEntitiesPredFailure.SubWithdrawalAccountsMissing(withdrawals),
              SubEntitiesPredFailure.SubWithdrawalAccountsMissingFromOriginal(withdrawals)
            ),
            name
          )
          SubEntitiesOutcome.Withdraws
        case Right(Right((after, _))) if withdrawals.isEmpty && certs.nonEmpty =>
          assertEquals(
            Right(Right(after)),
            EraCertsRun
              .dijkstraSubCerts(leios, subCertsEnv(tx), empty, certs)
              .map(_.map(_._1)),
            name
          )
          assert(after != empty, name)
          SubEntitiesOutcome.Registers
        case Right(Right((after, _))) if withdrawals.isEmpty && certs.isEmpty =>
          assertEquals(after, empty, name)
          SubEntitiesOutcome.Plain
        case other => fail(s"$name: $other")
    }
    assertEquals(
      kinds.groupBy(identity).view.mapValues(_.size).toMap,
      Map(
        SubEntitiesOutcome.Withdraws -> 3,
        SubEntitiesOutcome.Registers -> 2,
        SubEntitiesOutcome.Plain -> 33
      )
    )
  }

  test("SUBENTITIES checks a withdrawal against the current and the original accounts") {
    val (_, tx) = chainSubTxs
      .find((_, t) => subWithdrawals(t).nonEmpty)
      .getOrElse(fail("no sub-transaction withdraws"))
    val withdrawals = subWithdrawals(tx)
    val registered =
      withAccounts(
        withdrawals.toVector
          .map((a, amount) => account(a.id.credential, amount.value))
          .reduce(_.andThen(_))
      )
    // registered in both, drained: admitted, the balance taken
    subEntities(tx, registered, accountsOf(registered)) match
      case Right(Right((after, _))) =>
        withdrawals.foreach((a, _) =>
          assertEquals(balanceOf(a.id.credential, accountsOf(after)), Some(Coin(0)))
        )
      case other => fail(s"$other")
    // registered now but not before the batch: only the original accounts lack it
    assertEquals(
      subEntities(tx, registered, accountsOf(empty)).map(_.left.map(_.toChain.toVector)),
      Right(
        Left(
          Vector[SubEntitiesPredFailure[Dijkstra]](
            SubEntitiesPredFailure.SubWithdrawalAccountsMissingFromOriginal(withdrawals)
          )
        )
      )
    )
  }

  // ---- SUBENTITIES' steps, on crafted sub-transactions ------------------------------------------

  private def leaf(v: Value): Node = Node(v, Bytes.empty)

  /** The chain's sub-transactions with the transaction that holds them and their index in it. */
  private lazy val chainSubTxsAt: Vector[(io.circe.Json, Int, DijkstraTx[TxLevel.Sub])] =
    chain.flatMap(j => subTxs(j).zipWithIndex.map((t, i) => (j, i, t)))

  /** The first chain sub-transaction with no withdrawal, certificate, direct deposit, balance
    * interval, vote or proposal, to carry the fields a case gives it.
    */
  private lazy val plainSub: (io.circe.Json, Int) =
    chainSubTxsAt
      .collectFirst {
        case (j, i, t)
            if b.withdrawals[TxLevel.Sub](t.body).isEmpty &&
              b.certs[TxLevel.Sub](t.body).isEmpty &&
              b.directDeposits[TxLevel.Sub](t.body).isEmpty &&
              b.accountBalanceIntervals[TxLevel.Sub](t.body).isEmpty &&
              b.votingProcedures[TxLevel.Sub](t.body).unVotingProcedures.isEmpty &&
              b.proposalProcedures[TxLevel.Sub](t.body).size == 0 =>
          (j, i)
      }
      .getOrElse(fail("no plain sub-transaction"))

  private def isKey(k: Node, keys: Set[Int]): Boolean = k.value match
    case Value.UInt(n) => keys.contains(n.toInt)
    case _             => false

  /** The sub-transaction `index` of `j` 's transaction with its body's `fields`, each in place of
    * the body's field of its key, the rest of the transaction as it was read, encoded again and
    * read by the era decoder. SUBENTITIES reads the body alone, so the witnesses need not hold.
    */
  private def withSubFields(
      j: io.circe.Json,
      index: Int,
      fields: Vector[(Int, Node)]
  ): DijkstraTx[TxLevel.Sub] =
    val keys = fields.map(_._1).toSet
    val added = fields.map((k, v) => leaf(Value.UInt(k)) -> v)
    def edited(subTx: Node): Node = subTx.value match
      case Value.Arr(subBody +: rest) =>
        subBody.value match
          case Value.Map(fs) =>
            leaf(
              Value.Arr(leaf(Value.Map(fs.filterNot((k, _) => isKey(k, keys)) ++ added)) +: rest)
            )
          case other => fail(s"not a body: $other")
      case other => fail(s"not a sub-transaction: $other")
    def inSubs(subTxs: Node): Node = subTxs.value match
      case Value.Tag(tag, inner) => leaf(Value.Tag(tag, inSubs(inner)))
      case Value.Arr(xs) if xs.indices.contains(index) =>
        leaf(Value.Arr(xs.updated(index, edited(xs(index)))))
      case other => fail(s"no sub-transaction $index: $other")
    val bytes = node(hex(str(j, "bytes"))).value match
      case Value.Arr(txBody +: rest) =>
        txBody.value match
          case Value.Map(fs) =>
            val withSubs = fs.map((k, v) => if isKey(k, Set(23)) then k -> inSubs(v) else k -> v)
            Cbor
              .encode(Value.Arr(leaf(Value.Map(withSubs)) +: rest))
              .fold(e => fail(s"$e"), identity)
          case other => fail(s"not a body: $other")
      case other => fail(s"not a transaction: $other")
    b.subTransactions(ok(DijkstraTx.inBlock(node(bytes))).body)
      .toVector
      .lift(index)
      .getOrElse(fail(s"no sub-transaction $index"))

  /** The top-level body fields of `j` 's transaction at `keys`, as they were encoded. */
  private def topFields(j: io.circe.Json, keys: Set[Int]): Vector[(Int, Node)] =
    node(hex(str(j, "bytes"))).value match
      case Value.Arr(txBody +: _) =>
        txBody.value match
          case Value.Map(fs) =>
            fs.collect {
              case (k, v) if isKey(k, keys) =>
                k.value match
                  case Value.UInt(n) => n.toInt -> v
                  case other         => fail(s"not a key: $other")
            }
          case other => fail(s"not a body: $other")
      case other => fail(s"not a transaction: $other")

  /** An account on the node's network as the body encodes it: its header and credential. */
  private def accountNode(cred: Credential[Staking]): Node =
    val account = Convert.back(cred) match
      case flat.Credential.Key(h)    => f"${0xe0 | network.wire}%02x${h.hex}"
      case flat.Credential.Script(h) => f"${0xf0 | network.wire}%02x${h.hex}"
    leaf(Value.ByteString(hex(account)))

  private def drepCred(n: Int): Credential[xyz.kd5ujc.scarab.ledger.state.DRepRole] =
    Credential.KeyHashObj(
      ok(
        KeyHash.fromBytes[xyz.kd5ujc.scarab.ledger.state.DRepRole](
          Bytes(Vector.fill(28)(n.toByte))
        )
      )
    )

  /** `empty` with the DReps `dreps`, each registered until its epoch, and `dormant` epochs. */
  private def withDReps(dreps: Map[Int, Long], dormant: Long): CertState[Dijkstra] =
    c.certVStateL.modify(v =>
      v.copy(
        dreps = v.dreps ++ (dreps.map((n, expiry) =>
          drepCred(n) -> xyz.kd5ujc.scarab.ledger.state.DRepState(
            EpochNo.fromUnsigned(expiry),
            None,
            CompactCoin.fromUnsigned(500000000L),
            xyz.kd5ujc.scarab.ledger.state.Sorted.emptySet
          )
        )),
        numDormantEpochs = EpochNo.fromUnsigned(dormant)
      )
    )(empty)

  private def expiries(state: CertState[Dijkstra]): Map[Int, EpochNo] =
    Vector(0x53, 0x54, 0x55)
      .flatMap(n => c.certVStateL.get(state).dreps.get(drepCred(n)).map(n -> _.expiry))
      .toMap

  test(
    "SUBENTITIES applies a withdrawal whatever failed, and SUBCERTS sees the balance it leaves"
  ) {
    // the chain's transaction that withdraws from an account and unregisters it and another, its
    // withdrawals and certificates given to a sub-transaction; the account withdrawn from holds
    // more than the withdrawal now and is not among the original accounts: the withdrawal is
    // refused there, still applied, and SUBDELEG reports the balance it leaves, neither the balance
    // before (the withdrawal skipped) nor zero (the account drained)
    val (host, index) = plainSub
    val tx = withSubFields(host, index, topFields(withdrawAndUnregister, Set(4, 5)))
    val withdrawals = subWithdrawals(tx)
    val (withdrawn, amount) = withdrawals.headOption.getOrElse(fail("no withdrawal"))
    assertEquals(withdrawals.size, 1)
    val creds =
      b.certs[TxLevel.Sub](tx.body).collect { case DijkstraTxCert.DijkstraTxCertDeleg(d) =>
        d.credential
      }
    val cred1 = withdrawn.id.credential
    val cred2 = creds.find(_ != cred1).getOrElse(fail("one credential"))
    assertEquals(creds.toSet, Set(cred1, cred2))
    val state = withAccounts(account(cred1, amount.value + 1000000).andThen(account(cred2, 0)))
    assertEquals(
      subEntities(tx, state, accountsOf(withAccounts(account(cred2, 0))))
        .map(_.left.map(_.toChain.toVector)),
      Right(
        Left(
          Vector[SubEntitiesPredFailure[Dijkstra]](
            SubEntitiesPredFailure.SubCertsFailure(
              DijkstraSubCertsPredFailure(
                DijkstraSubCertPredFailure.SubDelegFailure(
                  DijkstraSubDelegPredFailure(
                    ConwayDelegPredFailure.StakeKeyHasNonZeroAccountBalanceDELEG(Coin(1000000))
                  )
                )
              )
            ),
            SubEntitiesPredFailure.SubWithdrawalAccountsMissingFromOriginal(withdrawals)
          )
        )
      )
    )
  }

  test("SUBENTITIES checks and adds a direct deposit after SUBCERTS registers its account") {
    val (j, index, registering) = chainSubTxsAt
      .find((_, _, t) =>
        b.certs[TxLevel.Sub](t.body).nonEmpty && b.withdrawals[TxLevel.Sub](t.body).isEmpty
      )
      .getOrElse(fail("no sub-transaction registers an account"))
    val cred = b
      .certs[TxLevel.Sub](registering.body)
      .collectFirst {
        case DijkstraTxCert.DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraRegCert(c, _)) => c
        case DijkstraTxCert.DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraRegDelegCert(c, _, _)) =>
          c
      }
      .getOrElse(fail("no registration"))
    val deposit = leaf(Value.Map(Vector(accountNode(cred) -> leaf(Value.UInt(1234567)))))
    val tx = withSubFields(j, index, Vector(25 -> deposit))
    assertEquals(
      b.directDeposits[TxLevel.Sub](tx.body).values.toVector,
      Vector(Coin(BigInt(1234567)))
    )
    // SUBCERTS alone registers the account with no balance; checked before it, the deposit would
    // be refused, its account missing
    val registered = ok(
      EraCertsRun.dijkstraSubCerts(leios, subCertsEnv(tx), empty, b.certs[TxLevel.Sub](tx.body))
    ).map(_._1).getOrElse(fail("SUBCERTS refuses the registration"))
    assertEquals(balanceOf(cred, accountsOf(registered)), Some(Coin(0)))
    subEntities(tx, empty, accountsOf(empty)) match
      case Right(Right((after, _))) =>
        assertEquals(balanceOf(cred, accountsOf(after)), Some(Coin(BigInt(1234567))))
      case other => fail(s"not admitted: $other")
  }

  test("SUBENTITIES updates the expiry of a DRep that votes in the sub-transaction") {
    // a vote of the DRep 0x53 in a sub-transaction, under the testnet's `dRepActivity` (20, its
    // Conway genesis; the suite's parameters have none): it is active until the current epoch (5)
    // and that; the DRep 0x54, which does not vote, is left as it was
    val (host, index) = plainSub
    val voter = leaf(
      Value.Arr(
        Vector(leaf(Value.UInt(2)), leaf(Value.ByteString(Bytes(Vector.fill(28)(0x53.toByte)))))
      )
    )
    val actionId =
      leaf(
        Value.Arr(
          Vector(leaf(Value.ByteString(Bytes(Vector.fill(32)(0x45.toByte)))), leaf(Value.UInt(1)))
        )
      )
    val vote = leaf(Value.Arr(Vector(leaf(Value.UInt(1)), leaf(Value.Null))))
    val votes = leaf(Value.Map(Vector(voter -> leaf(Value.Map(Vector(actionId -> vote))))))
    val tx = withSubFields(host, index, Vector(19 -> votes))
    val state = withDReps(Map(0x53 -> 7L, 0x54 -> 7L), 0L)
    EraCertsRun.subEntities(
      leios,
      SubEntitiesEnv[Dijkstra](
        epoch5,
        era(testnetStored.copy(drepActivity = 20)),
        None,
        Map.empty,
        accountsOf(state)
      ),
      state,
      tx
    ) match
      case Right(Right((after, _))) =>
        assertEquals(
          expiries(after),
          Map(0x53 -> EpochNo.fromUnsigned(25L), 0x54 -> EpochNo.fromUnsigned(7L))
        )
      case other => fail(s"not admitted: $other")
  }

  test("SUBENTITIES moves every DRep's expiry up by the dormant epochs on a proposal") {
    // a proposal in a sub-transaction, three dormant epochs: each DRep's expiry moves up by them
    // unless that is before the current epoch (5), and the count is reset
    val (host, index) = plainSub
    val anchor = leaf(
      Value.Arr(
        Vector(
          leaf(Value.Text("https://example.invalid/a")),
          leaf(Value.ByteString(Bytes(Vector.fill(32)(0x44.toByte))))
        )
      )
    )
    val proposal = leaf(
      Value.Arr(
        Vector(
          leaf(Value.UInt(1000000)),
          accountNode(cred(0x76)),
          leaf(Value.Arr(Vector(leaf(Value.UInt(6))))),
          anchor
        )
      )
    )
    val tx = withSubFields(host, index, Vector(20 -> leaf(Value.Arr(Vector(proposal)))))
    assertEquals(b.proposalProcedures[TxLevel.Sub](tx.body).size, 1)
    val state = withDReps(Map(0x53 -> 7L, 0x54 -> 9L, 0x55 -> 1L), 3L)
    subEntities(tx, state, accountsOf(state)) match
      case Right(Right((after, _))) =>
        assertEquals(
          expiries(after),
          Map(
            0x53 -> EpochNo.fromUnsigned(10L),
            0x54 -> EpochNo.fromUnsigned(12L),
            0x55 -> EpochNo.fromUnsigned(1L)
          )
        )
        assertEquals(c.certVStateL.get(after).numDormantEpochs, EpochNo.fromUnsigned(0L))
      case other => fail(s"not admitted: $other")
  }

  test(
    "SUBENTITIES on withdrawals, deposits and intervals of both networks, in the reference's order"
  ) {
    // the script-context fixture's sub-transaction with withdrawals and direct deposits of key and
    // script accounts of both networks, and balance intervals of a script account of the other
    // network and a key account not registered (`fixtures/reference/leios-testnet-contexts`,
    // "sub-transaction scripts"); the key account of the node's network registered with 10 now
    // and before the batch
    val j = contextCases
      .find(c => str(c, "name") == "sub-transaction scripts")
      .getOrElse(fail("not in the fixture"))
    val tx =
      b.subTransactions(ok(DijkstraTx.inBlock(node(hex(str(j, "bytes"))))).body).toVector match
        case Vector(t) => t
        case other     => fail(s"not one sub-transaction: $other")
    val withdrawals = subWithdrawals(tx)
    val deposits = ok(ENTITIES.keyed(b.directDeposits[TxLevel.Sub](tx.body)))
    val intervals = ok(ENTITIES.keyed(b.accountBalanceIntervals[TxLevel.Sub](tx.body)))
    val registered = withdrawals.keysIterator
      .find(a =>
        a.networkId == Network.Testnet && (a.id.credential match
          case Credential.KeyHashObj(_) => true
          case _                        => false)
      )
      .getOrElse(fail("no key account of the node's network"))
    val state = withAccounts(account(registered.id.credential, 10))
    def other(m: SortedMap[AccountAddress, ?]) =
      NonEmptySet
        .fromSet(
          scala.collection.immutable.SortedSet
            .from(m.keysIterator.filter(_.networkId == Network.Mainnet))
        )
        .getOrElse(fail("no account of the other network"))
    val missing = withdrawals.filter((a, _) => a != registered)
    assertEquals((withdrawals.size, missing.size, other(withdrawals).length), (4, 3, 2))
    assertEquals(deposits, withdrawals)
    val (wrongInterval, unregistered) =
      intervals.partition((a, _) => a.networkId == Network.Mainnet)
    assertEquals((wrongInterval.size, unregistered.size), (1, 1))
    assertEquals(
      subEntities(tx, state, accountsOf(state)).map(_.left.map(_.toChain.toVector)),
      Right(
        Left(
          Vector[SubEntitiesPredFailure[Dijkstra]](
            SubEntitiesPredFailure.SubDirectDepositAccountsMissing(missing),
            SubEntitiesPredFailure.SubWithdrawalAccountsMissing(missing),
            SubEntitiesPredFailure.SubWithdrawalAccountsMissingFromOriginal(missing),
            SubEntitiesPredFailure.SubMissingAccountsInAccountBalanceIntervals(
              NonEmptyMap.fromMap(unregistered).getOrElse(fail("no interval"))
            ),
            SubEntitiesPredFailure.SubWrongNetworkInAccountBalanceIntervals(
              Network.Testnet,
              other(wrongInterval)
            ),
            SubEntitiesPredFailure.SubDirectDepositAddressesWithWrongNetwork(
              Network.Testnet,
              other(deposits)
            ),
            SubEntitiesPredFailure.SubWithdrawalAddressesWithWrongNetwork(
              Network.Testnet,
              other(withdrawals)
            )
          )
        )
      )
    )
  }

  test("SUBCERTS judges thousands of certificates on a small stack (STK-3)") {
    // the real fold, its judgment's interpretation and the flattening of its failures, on a thread
    // whose stack is far too small for a recursion over the certificates: an account registered
    // 5000 times, refused 4999 times; then registered and unregistered 2500 times, admitted
    val (j, cred) = registersAnAccount
    val registration = b.certs[TxLevel.Top](eraTx(j).body).collect {
      case c @ DijkstraTxCert.DijkstraTxCertDeleg(d) if d.credential == cred => c
    }
    val deposit = registration match
      case Vector(DijkstraTxCert.DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraRegCert(_, d))) => d
      case Vector(
            DijkstraTxCert.DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraRegDelegCert(_, _, d))
          ) =>
        d
      case other => fail(s"not one registration: $other")
    val unregistration =
      DijkstraTxCert.DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraUnRegCert(cred, deposit))
    val env = subCertsEnv(chainSubTxs.head._2)
    val again = DijkstraSubCertsPredFailure[Dijkstra](
      DijkstraSubCertPredFailure.SubDelegFailure(
        summon[InjectSubDelegFailure[AccountAlreadyRegistered[Dijkstra], Dijkstra]]
          .injectFailure(AccountAlreadyRegistered[Dijkstra](cred))
      )
    )
    val refused = xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack(
      EraCertsRun.dijkstraSubCerts(leios, env, empty, Vector.fill(5000)(registration).flatten)
    )
    assertEquals(ok(refused).left.map(_.toChain.toVector), Left(Vector.fill(4999)(again)))
    val admitted = xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack(
      EraCertsRun.dijkstraSubCerts(
        leios,
        env,
        empty,
        Vector.fill(2500)(registration :+ unregistration).flatten
      )
    )
    ok(admitted) match
      case Right((after, _)) => assertEquals(balanceOf(cred, accountsOf(after)), None)
      case Left(fs)          => fail(s"refused: $fs")
  }
