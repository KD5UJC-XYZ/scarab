// SPDX-License-Identifier: Apache-2.0
package lab {
  import cats.effect.IO
  import lab.cbor.{Bytes, Cbor, Node, Value as V}
  import lab.header.{PraosCertificateState as C, PraosNonceEvolution as N, PraosEligibility as E}
  import lab.ledger.{
    ConwayEpochBoundary as B,
    ConwayRewardStart as R,
    ConwayStake as S,
    ConwayEmptyGovernance as G,
    ConwayNonMyopic as NM
  }
  import lab.vrf.{EphemeralPublicVrf, PraosVrfCertificate as Vrf, PraosLeaderThreshold as Leader}
  import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
  import org.bouncycastle.crypto.signers.Ed25519Signer
  import ReferenceJson.Json as J

  /** Entirely generated synthetic supplied state plus exact signed empty-block originals. No
    * captures, filesystem reads, network calls, native execution, or production secrets.
    */
  private[lab] object SyntheticBoundaryCompositionFixture:
    private def get[A](e: Either[?, A]): A =
      e.fold(e => throw new IllegalArgumentException(e.toString), identity)
    private def b(n: Int, size: Int = 32) = Bytes(Vector.fill(size)(n.toByte))
    private def raw(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
    private def node(v: V) = Node(v, Bytes.empty)
    private def array(xs: V*) = V.Arr(xs.toVector.map(node))
    private def encoded(v: V) = get(Cbor.encode(v))
    private def bs(b: Bytes) = V.ByteString(b)
    private def obj(xs: (String, J)*) = J.Obj(xs.toMap)
    private def num(n: BigInt) = J.Num(n.toString)
    private def str(s: String) = J.Str(s)
    private def arr(xs: J*) = J.Arr(xs.toVector)
    private val empty = obj()
    private val nul = J.Lit("null")
    private def json(j: J) = SyntheticRewardProjection.encode(j)
    private def sign(key: Ed25519PrivateKeyParameters, message: Bytes): Bytes =
      val signer = new Ed25519Signer()
      signer.init(true, key)
      val bytes = message.toArray
      signer.update(bytes, 0, bytes.length)
      Bytes.fromArray(signer.generateSignature())
    private val cold = new Ed25519PrivateKeyParameters(b(11).toArray, 0)
    private val leaf = new Ed25519PrivateKeyParameters(b(12).toArray, 0)
    private val coldPublic = Bytes.fromArray(cold.generatePublicKey().getEncoded())
    private val leafPublic = Bytes.fromArray(leaf.generatePublicKey().getEncoded())
    val issuer: Bytes = Blake2b.hash224.hash(coldPublic)
    private val vrfHash = Blake2b.hash256.hash(EphemeralPublicVrf.publicKey)
    private val kesPairs = (0 until 6).foldLeft(Vector.empty[Bytes] -> leafPublic) {
      case ((done, child), _) =>
        val pair = Bytes(child.value ++ child.value)
        (done :+ pair, Blake2b.hash256.hash(pair))
    }
    private val kesRoot = kesPairs._2
    private val opcertSignature = sign(cold, Bytes(kesRoot.value ++ Vector.fill(16)(0.toByte)))
    val epochLength: BigInt = 40
    val rewardWindow: BigInt = 4
    val slots: Vector[BigInt] = ((1 to 20) ++ (40 to 44)).map(BigInt(_)).toVector
    val pin: Bytes = b(77)
    val anchorHash: Bytes = b(88)
    val secondPool = b(22, 28)
    val secondAccount = S.Credential(false, b(23, 28))
    private val accountHash = b(13, 28)
    private val accountName = "keyHash-" + accountHash.hex
    private val accountCredential = obj("keyHash" -> str(accountHash.hex))
    private val one = obj("numerator" -> num(1), "denominator" -> num(1))
    private val zero = obj("numerator" -> num(0), "denominator" -> num(1))
    private val pool = obj(
      "spsVrf" -> str(vrfHash.hex),
      "spsPledge" -> num(0),
      "spsCost" -> num(0),
      "spsMargin" -> zero,
      "spsAccountId" -> accountCredential,
      "spsOwners" -> arr(),
      "spsDelegators" -> arr(accountCredential),
      "spsDeposit" -> num(0)
    )
    private val snapshot = obj(
      "activeStake" -> obj(
        accountName -> obj("swdStake" -> num(100), "swdDelegation" -> str(issuer.hex))
      ),
      "stakePoolsSnapShot" -> obj(
        issuer.hex -> obj(
          "stake" -> num(100),
          "stakeRatio" -> one,
          "selfDelegatedOwners" -> arr(),
          "selfDelegatedOwnersStake" -> num(0),
          "vrf" -> str(vrfHash.hex),
          "pledge" -> num(0),
          "cost" -> num(0),
          "margin" -> zero,
          "numDelegators" -> num(1),
          "accountId" -> accountCredential
        ),
        secondPool.hex -> obj(
          "stake" -> num(0),
          "stakeRatio" -> zero,
          "selfDelegatedOwners" -> arr(),
          "selfDelegatedOwnersStake" -> num(0),
          "vrf" -> str(b(24).hex),
          "pledge" -> num(0),
          "cost" -> num(0),
          "margin" -> zero,
          "numDelegators" -> num(1),
          "accountId" -> obj("keyHash" -> str(secondAccount.hash.hex))
        )
      )
    )
    private val tip = obj(
      "era" -> str("Conway"),
      "hash" -> str(anchorHash.hex),
      "slot" -> num(0),
      "block" -> num(0),
      "epoch" -> num(0)
    )
    private val secondCredential = obj("keyHash" -> str(secondAccount.hash.hex))
    private val secondPoolJson = obj(
      "spsVrf" -> str(b(24).hex),
      "spsPledge" -> num(0),
      "spsCost" -> num(0),
      "spsMargin" -> zero,
      "spsAccountId" -> secondCredential,
      "spsOwners" -> arr(),
      "spsDelegators" -> arr(secondCredential),
      "spsDeposit" -> num(0)
    )
    private val ledgerJson = obj(
      "lastEpoch" -> num(0),
      "stakeDistrib" -> obj(
        "pdTotalActiveStake" -> num(100),
        "unPoolDistr" -> obj(
          issuer.hex -> obj(
            "individualPoolStakeVrf" -> str(vrfHash.hex),
            "individualPoolStake" -> one,
            "individualTotalPoolStake" -> num(100)
          )
        )
      ),
      "stateBefore" -> obj(
        "esLState" -> obj(
          "delegationState" -> obj(
            "dstate" -> obj(
              "accounts" -> obj(
                accountName -> obj(
                  "balance" -> num(100),
                  "reward" -> num(100),
                  "deposit" -> num(0),
                  "spool" -> str(issuer.hex)
                ),
                ("keyHash-" + secondAccount.hash.hex) -> obj(
                  "balance" -> num(50),
                  "reward" -> num(50),
                  "deposit" -> num(0),
                  "spool" -> str(secondPool.hex)
                )
              )
            ),
            "pstate" -> obj(
              "stakePools" -> obj(issuer.hex -> pool, secondPool.hex -> secondPoolJson),
              "futureStakePoolParams" -> empty,
              "retiring" -> empty
            )
          ),
          "utxoState" -> obj("fees" -> num(10), "stake" -> obj("credentials" -> empty))
        ),
        "esSnapshots" -> obj(
          "pstakeMark" -> snapshot,
          "pstakeSet" -> snapshot,
          "pstakeGo" -> obj("activeStake" -> empty, "stakePoolsSnapShot" -> empty),
          "feeSS" -> num(10)
        )
      )
    )
    private val originals = Map(
      "transfer-genesis.md" -> json(
        obj(
          "networkId" -> str("Testnet"),
          "networkMagic" -> num(1082026),
          "epochLength" -> num(epochLength),
          "slotsPerKESPeriod" -> num(1000),
          "maxKESEvolutions" -> num(64),
          "activeSlotsCoeff" -> num(1),
          "securityParam" -> num(1),
          "systemStart" -> str("2026-10-09T00:00:00Z"),
          "slotLength" -> num(1),
          "updateQuorum" -> num(2),
          "maxLovelaceSupply" -> num(1000000),
          "protocolParams" -> empty,
          "genDelegs" -> empty,
          "initialFunds" -> empty,
          "staking" -> obj("pools" -> empty, "stake" -> empty)
        )
      ),
      "pre-tips.md" -> json(arr(tip, tip)),
      "pre-ledger-state.md" -> json(ledgerJson),
      "pre-protocol-state.md" -> json(
        obj(
          "lastSlot" -> num(0),
          "oCertCounters" -> obj(issuer.hex -> num(0)),
          "epochNonce" -> nul,
          "evolvingNonce" -> nul,
          "candidateNonce" -> nul,
          "labNonce" -> nul,
          "lastEpochBlockNonce" -> nul,
          "previousEpochNonce" -> nul
        )
      ),
      "pre-parameters.md" -> json(
        obj(
          "protocolVersion" -> obj("major" -> num(9), "minor" -> num(0)),
          "txFeePerByte" -> num(44),
          "txFeeFixed" -> num(155381),
          "maxTxSize" -> num(16384),
          "utxoCostPerByte" -> num(4310)
        )
      ),
      "pre-utxo.md" -> json(empty),
      "pre-utxo-cbor.md" -> raw("a0\n")
    )
    private val manifest = raw(
      "format\t" + SequenceInput.ProfileId + "\n" + SequenceInput.sources.toVector
        .sortBy(_._1)
        .map((key, name) => key + "\t" + ClusterHeaderObservation.sha256(originals(name)).hex)
        .mkString("\n") + "\n"
    )
    lazy val context: SequenceInput.Context = get(SequenceInput.bind(manifest, originals))
    lazy val stakeSeed: ConwayStakeSeed.Prepared = get(
      ConwayStakeSeed.decode(
        originals("pre-ledger-state.md"),
        context.sourcePins("preLedgerSha256"),
        get(Bytes.fromHex("a0")),
        ClusterHeaderObservation.sha256(get(Bytes.fromHex("a0"))),
        epochLength
      )
    )
    private def ratio(n: BigInt, d: BigInt): V = V.Tag(30, node(array(V.UInt(n), V.UInt(d))))
    val parameterOriginal: Bytes = encoded(
      array(
        Vector[V](
          V.UInt(44),
          V.UInt(155381),
          V.UInt(90112),
          V.UInt(16384),
          V.UInt(1100),
          V.UInt(0),
          V.UInt(0),
          V.UInt(18),
          V.UInt(1),
          ratio(0, 1),
          ratio(0, 1),
          ratio(1, 5),
          array(V.UInt(9), V.UInt(0)),
          V.UInt(170000000),
          V.UInt(4310),
          V.Map(Vector.empty),
          array(ratio(1, 10), ratio(1, 100)),
          array(V.UInt(1000), V.UInt(2000)),
          array(V.UInt(3000), V.UInt(4000)),
          V.UInt(5000),
          V.UInt(150),
          V.UInt(3),
          array(Vector.fill(5)(ratio(1, 2))*),
          array(Vector.fill(10)(ratio(1, 2))*),
          V.UInt(0),
          V.UInt(10),
          V.UInt(6),
          V.UInt(0),
          V.UInt(0),
          V.UInt(20),
          ratio(15, 1)
        )*
      )
    )
    private def sha(x: Bytes) = ClusterHeaderObservation.sha256(x)
    val previousParameterOriginal: Bytes =
      val fields = get(Cbor.decode(parameterOriginal)).value match
        case V.Arr(xs) => xs
        case _         => throw new IllegalArgumentException("parameter record")
      encoded(V.Arr(fields.updated(0, node(V.UInt(43)))))
    lazy val prepared = get(
      NativeSeedParameters.decode(
        previousParameterOriginal,
        parameterOriginal,
        originals("transfer-genesis.md"),
        sha(originals("transfer-genesis.md")),
        0,
        0,
        1082026
      )
    )
    lazy val typedGlobals = get(GovernanceGlobals.bind(prepared, prepared.bindingId, 0, 0, 1082026))
    lazy val checkedParameters = get(
      GovernanceParameterPayload.decode(parameterOriginal, sha(parameterOriginal))
    )
    lazy val checkedPrevious = get(
      GovernanceParameterPayload.decode(previousParameterOriginal, sha(previousParameterOriginal))
    )
    lazy val parameters = prepared.previous.rewards
    lazy val globals = prepared.globals
    lazy val profile: CoherentSequence.SyntheticRewardProfile = get(
      CoherentSequence.syntheticRewardProfile(
        parameters,
        globals,
        rewardWindow,
        Some(false),
        Some(0),
        Some(false),
        Some(false),
        Some(0),
        Some(false),
        Some(false)
      )
    )
    lazy val initialPots: B.Pots = B.Pots(0, 999840, 10, 1000000)
    def runtime: IO[CoherentSequence.Runtime[IO]] = CoherentSequence
      .createWithSyntheticRewards[IO](
        context,
        stakeSeed,
        profile,
        initialPots,
        Map.empty,
        Map.empty,
        pin
      )
      .map(get(_))

    lazy val roles = get(
      GovernanceParameterPayload.bindRoles(
        checkedPrevious,
        checkedParameters,
        checkedPrevious.sha256,
        checkedParameters.sha256,
        parameters,
        parameters,
        context.ledger.environment.feeParameters,
        context.ledger.environment.minimumOutputParameters
      )
    )
    private def credential(c: S.Credential): V =
      array(V.UInt(if c.script then 1 else 0), bs(c.hash))
    private def set(xs: Vector[V]): V = V.Tag(258, node(array(xs*)))
    lazy val checkedPools: Map[Bytes, GovernancePoolPayload.Checked] = stakeSeed.context.pools.map {
      (id, p) =>
        val bytes = encoded(
          array(
            bs(p.vrf),
            V.UInt(p.pledge),
            V.UInt(p.cost),
            ratio(p.margin.numerator, p.margin.denominator),
            credential(p.rewardAccount),
            set(p.owners.toVector.sortBy(_.hex).map(bs)),
            array(),
            array(),
            V.UInt(p.deposit),
            set(p.delegators.toVector.sortBy(c => (c.script, c.hash.hex)).map(credential))
          )
        )
        id -> get(GovernancePoolPayload.decode(bytes, sha(bytes)))
    }
    lazy val nonMyopic = get(
      NM.state(
        Map(
          issuer -> get(NM.likelihood(Vector.fill(NM.Samples)(0x3f800000))),
          secondPool -> get(NM.likelihood(Vector.fill(NM.Samples)(0x40000000)))
        ),
        9
      )
    )
    lazy val governance: G.Input =
      val roots = G.Purpose.values.map(_ -> Option.empty[G.ActionId]).toMap
      val constitution = G.Constitution(G.Anchor("", b(0)), None)
      val parameters = G.Parameters(
        checkedParameters.payload,
        checkedPrevious.payload,
        G.FutureParameters.NoUpdate
      )
      val enact =
        G.Enact(None, constitution, parameters.current, parameters.previous, 0, Map.empty, roots)
      val old = G.OldDRep.Complete(
        G.CompletedSnapshot(Vector.empty, Map.empty, Map.empty, Map.empty),
        G.Ratify(enact, Vector.empty, Set.empty, false)
      )
      val accounts = stakeSeed.context.accounts.map((c, a) =>
        c -> G.Account(a.balance, a.deposit, a.delegation, Some(G.Vote.AlwaysAbstain))
      )
      val mark = stakeSeed.snapshots.mark
      G.Input(
        0,
        4,
        Map.empty,
        None,
        Map(S.Credential(false, b(63, 28)) -> G.Authorization.Hot(S.Credential(false, b(64, 28)))),
        constitution,
        parameters,
        roots,
        Map.empty,
        old,
        accounts,
        stakeSeed.instantaneous,
        G.PoolDistribution(
          mark.total,
          mark.pools.map((id, p) => id -> G.PoolShare(p.coin, p.ratio, p.vrf))
        ),
        checkedPools.map((id, p) => id -> G.Pool(p.payload, p.pool.deposit)),
        Map.empty,
        Map.empty,
        Map.empty,
        0,
        0,
        G.Deposits(
          accounts.map((c, a) => c -> a.deposit),
          checkedPools.map((id, p) => id -> p.pool.deposit),
          Map.empty,
          Map.empty,
          0
        ),
        get(SyntheticBoundaryState.typedGlobals(typedGlobals))
      )
    lazy val boundaryProfile = get(
      CoherentSequence.syntheticBoundaryProfile(roles, checkedPools, typedGlobals)
    )
    def boundaryRuntime: IO[CoherentSequence.Runtime[IO]] = CoherentSequence
      .createWithSyntheticBoundary[IO](
        context,
        stakeSeed,
        boundaryProfile,
        governance,
        nonMyopic,
        initialPots,
        Map.empty,
        Map.empty,
        pin
      )
      .map(get(_))

    private val components =
      Vector(encoded(array()), encoded(array()), encoded(V.Map(Vector.empty)), encoded(array()))
    private val bodyHash =
      Blake2b.hash256.hash(Bytes(components.flatMap(x => Blake2b.hash256.hash(x).value)))
    private def original(header: Bytes): BoundedChainFollower.Original =
      val envelope = encoded(array(V.UInt(6), V.Tag(24, node(bs(header)))))
      val block = Bytes(
        Vector(0x82.toByte, 0x07.toByte, 0x85.toByte) ++ header.value ++ components.flatMap(_.value)
      )
      BoundedChainFollower.Original(envelope, block)
    private def combine(a: N.Nonce, b: N.Nonce): N.Nonce = (a, b) match
      case (N.Nonce.Neutral, x) => x
      case (x, N.Nonce.Neutral) => x
      case (N.Nonce.Hash(x), N.Nonce.Hash(y)) =>
        N.Nonce.Hash(Blake2b.hash256.hash(Bytes(x.value ++ y.value)))
    lazy val blocks: Vector[SequenceInput.Block] = signed(slots)
    def signed(requestedSlots: Vector[BigInt]): Vector[SequenceInput.Block] =
      var certContext = context.certificates
      var nonceContext = context.nonces.context
      var cert = context.certificateSeed
      var nonce = context.nonces.seed
      val fraction = get(Leader.Fraction.checked(1, 1))
      requestedSlots.map { slot =>
        val crossing = slot / epochLength != cert.tip.slot / epochLength
        val oldCertContext = certContext; val oldNonceContext = nonceContext
        if crossing then
          certContext = get(C.Context.checkedSuccessor(certContext, pin, certContext.registrations))
          nonceContext = get(N.Context.checkedSuccessor(nonceContext, certContext))
        val epochNonce =
          if crossing then combine(nonce.fields.candidate, nonce.fields.lastEpochBlock)
          else nonce.fields.epoch
        val vrfNonce = epochNonce match
          case N.Nonce.Neutral => Vrf.NeutralNonce
          case N.Nonce.Hash(b) => get(Vrf.Hash32.fromBytes(b))
        val input = get(Vrf.Input.create(get(Vrf.Slot.fromBigInt(slot)), vrfNonce))
        val (output, proof) = EphemeralPublicVrf.prove(get(Vrf.alpha(input)))
        val body = array(
          V.UInt(cert.tip.blockNo + 1),
          V.UInt(slot),
          bs(cert.tip.hash),
          bs(coldPublic),
          bs(EphemeralPublicVrf.publicKey),
          array(bs(output), bs(proof)),
          V.UInt(components.map(_.size).sum),
          bs(bodyHash),
          array(bs(kesRoot), V.UInt(0), V.UInt(0), bs(opcertSignature)),
          array(V.UInt(11), V.UInt(2))
        )
        val kes = Bytes(sign(leaf, encoded(body)).value ++ kesPairs._1.flatMap(_.value))
        val h = encoded(array(body, bs(kes)))
        val block = get(SequenceInput.block(original(h)))
        require(
          get(lab.chain.CardanoBodyCommitment.inspect(block.original.block)).bodyCommitmentMatched
        )
        val step = get(
          if crossing then
            C.applySuccessorHeader(oldCertContext, certContext, cert, h, block.header.hash)
          else C.applyHeader(certContext, cert, h, block.header.hash)
        )
        val nonceStep = get(
          if crossing then N.applySuccessorHeader(oldNonceContext, nonceContext, nonce, step)
          else N.applyHeader(nonceContext, nonce, step)
        )
        val eligibility = get(
          if crossing then
            E.Context.checkedSuccessor(
              oldCertContext,
              certContext,
              cert,
              slot / epochLength,
              epochLength,
              vrfNonce,
              fraction,
              Map(issuer -> fraction),
              pin
            )
          else
            E.Context.checked(
              certContext,
              cert,
              slot / epochLength,
              epochLength,
              vrfNonce,
              fraction,
              Map(issuer -> fraction),
              pin
            )
        )
        get(E.check(eligibility, Vector(step)))
        cert = step.after; nonce = nonceStep.after
        block
      }

    /** Valid parsed empty body with different original encoding: signed header remains intact. */
    def invalidBody(block: SequenceInput.Block): SequenceInput.Block =
      val changed = Bytes(
        Vector(0x82.toByte, 0x07.toByte, 0x85.toByte) ++ block.header.raw.value ++
          Vector(0x9f.toByte, 0xff.toByte) ++ components.drop(1).flatMap(_.value)
      )
      get(SequenceInput.block(BoundedChainFollower.Original(block.original.envelope, changed)))

    /** Structural original identity remains consistent; only the KES signature is corrupted. */
    def invalidSignature(block: SequenceInput.Block): SequenceInput.Block =
      val raw = block.header.raw
      val bad = Bytes(raw.value.updated(raw.size - 1, (raw.value.last ^ 1).toByte))
      get(SequenceInput.block(original(bad)))
}
