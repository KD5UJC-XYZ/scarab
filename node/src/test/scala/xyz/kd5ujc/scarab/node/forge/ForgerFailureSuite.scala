// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import java.io.IOException
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.ledger.{ChainError, ForgeError, ForgeKeyProblem, NodeError, StorageError}
import xyz.kd5ujc.scarab.node.Fatal
import xyz.kd5ujc.scarab.node.mempool.Source

class ForgerFailureSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught with ForgeFixture:
  private def unreadable =
    ForgeError.KeyFile("kes", ForgeKeyProblem.Io(new java.nio.file.NoSuchFileException("kes")))

  private def source(readKeys: => IO[Either[ForgeError, HotKeySource.Candidate[IO]]]) =
    new HotKeySource[IO]:
      def read = readKeys
      def persist(key: Sum6KesSigning.SigningKey) = IO.pure(Persisted.Unchanged)

  private def leaderSlot(p: Pool): BigInt =
    (5000 until 5040)
      .find(slot =>
        Leadership
          .check(
            slot,
            epochNonce,
            p.creds.vrf,
            1000000,
            1000000,
            config.activeSlotsCoeff
          )
          .isRight
      )
      .map(BigInt(_))
      .getOrElse(fail("fixture pool leads no slot"))

  test("a forged block persistence failure retains its storage cause before publishing the tip") {
    val p = pool(kesStart = 50)
    val cause = StorageError.Io("volatile", new IOException("disk full"))
    for
      h <- withHandle(harness(p, ledgerWith(p, 1), storageFailure = Some(cause)))
      result <- h.forger.step(leaderSlot(p))
      events <- h.seen
      chain <- h.chain
      stats <- h.forger.stats.total
    yield
      assertEquals(result, Left(Fatal.Storage(cause)))
      assertEquals(chain.tipRef, None)
      assertEquals(stats.failed, 0L)
      assert(!events.exists(_.isInstanceOf[ForgeEvent.Adopted]))
      assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]))
  }

  // issue 567: our own evaluator's unavailability is no verdict on our block; the slot ends without
  // a block and the node goes on
  test("rule implementation unavailability ends the slot without a block, never a stop") {
    val p = pool(kesStart = 50)
    val cause = xyz.kd5ujc.scarab.ledger.LedgerFailure.Unavailable(
      xyz.kd5ujc.scarab.codec.Bytes.empty,
      xyz.kd5ujc.scarab.ledger.ScriptLocation.Phase1(xyz.kd5ujc.scarab.codec.Bytes.empty),
      xyz.kd5ujc.scarab.plutus.EvalFailure
        .Unsupported(
          xyz.kd5ujc.scarab.plutus.BackendFailure
            .BuiltinUnavailable(scalus.uplc.DefaultFun.VerifyEd25519Signature)
        )
    )
    withHandle {
      for
        h <- harness(
          p,
          ledgerWith(p, 1),
          ruleFailure = Some(xyz.kd5ujc.scarab.ledger.BlockFailure.Tx(cause))
        )
        result <- h.forger.step(leaderSlot(p))
        events <- h.seen
        chain <- h.chain
      yield
        assertEquals(result, Right(()))
        assertEquals(chain.tipRef, None)
        assert(!events.exists(_.isInstanceOf[ForgeEvent.Adopted]))
        assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]))
    }
  }

  /** A deposit of the parameters that is not a `CompactForm Coin`. */
  private val outOfRange = xyz.kd5ujc.scarab.ledger.state.VocabError
    .OutOfRange(xyz.kd5ujc.scarab.ledger.state.VocabError.Kind.CompactCoin, BigInt(1) << 64)

  // `compactCoinOrError` (cardano-ledger f649f975 `Coin.hs:184-188`) throws in the forging thread,
  // which is linked (ouroboros-consensus 82ecba32 `NodeKernel.hs:378-380`)
  test("a ledger state out of range is fatal: no recoverable forge failure, no later slot") {
    val p = pool(kesStart = 50)
    val fault = ChainError.StateOutOfRange(leaderSlot(p), outOfRange)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1), chainFailure = Some(fault))
        result <- h.forger.step(leaderSlot(p))
        events <- h.seen
        chain <- h.chain
        stats <- h.forger.stats.total
      yield
        assertEquals(result, Left(Fatal.Internal(fault)))
        assertEquals(chain.tipRef, None)
        assertEquals(stats.failed, 0L)
        assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]))
    }
  }

  test("a tick of the tip's state out of range is the node's stop; another tick failure is not") {
    val state = xyz.kd5ujc.scarab.ledger.rules.TickFailure.Vocab(outOfRange)
    assertEquals(Forger.tickFault(7, state), ChainError.StateOutOfRange(7, outOfRange))
    val input = xyz.kd5ujc.scarab.ledger.rules.TickFailure.Input(outOfRange)
    assertEquals(
      Forger.tickFault(7, input),
      ForgeError.LedgerViewUnavailable(7, xyz.kd5ujc.scarab.ledger.ForgeLedgerProblem.Tick(input))
    )
    for fatal <- Forger.scoped[IO, Unit](
        summon[Handle[IO, NodeError]].raise(Forger.tickFault(7, state))
      )
    yield assertEquals(fatal, Left(Fatal.Internal(ChainError.StateOutOfRange(7, outOfRange))))
  }

  test(
    "a deposit of the parameters out of range stops block selection, not skipping the transaction"
  ) {
    import xyz.kd5ujc.scarab.node.mempool.TxKit
    import xyz.kd5ujc.scarab.node.mempool.TxKit.*
    // a stake registration `[0, [0, key hash]]` (Conway `RegTxCert`, which takes `ppKeyDeposit`)
    val keyHash = xyz.kd5ujc.scarab.codec.Bytes.fromArray(Array.fill(28)(9.toByte))
    val certificate = head(4, 1) ++ head(4, 2) ++ head(0, 0) ++ head(4, 2) ++ head(0, 0) ++
      bstr(keyHash)
    val (raw, _) = TxKit.tx(
      Vector(xyz.kd5ujc.scarab.ledger.TestLedger.txIn(1)),
      Vector(bob.address() -> BigInt(99000000)),
      BigInt(1000000),
      Vector(alice),
      certificates = certificate
    )
    val base = TxKit.ledger(2).withParams(_.copy(keyDeposit = BigInt(1) << 64))
    val limit = BlockBuilder.Limits(65536, xyz.kd5ujc.scarab.plutus.ExUnits(14000000, 10000000000L))
    BlockBuilder.select(
      base,
      1001,
      1,
      geometry,
      globals,
      Vector(MempoolStateFixture.entry(raw)),
      limit
    ) match
      case Left(xyz.kd5ujc.scarab.ledger.LedgerFailure.StateOutOfRange(_, error)) =>
        assertEquals(error, outOfRange)
      case other => fail(s"expected the state out of range, got $other")
  }

  test("an actual rule rejection remains recoverable and retains its predicate failure") {
    val p = pool(kesStart = 50)
    val cause = xyz.kd5ujc.scarab.ledger.BlockFailure.HeaderDiffers
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1), ruleFailure = Some(cause))
        result <- h.forger.step(leaderSlot(p))
        events <- h.seen
        chain <- h.chain
      yield
        assertEquals(result, Right(()))
        assertEquals(chain.tipRef, None)
        val refused = events.collect {
          case ForgeEvent.Loop(ForgeTrace.ForgedInvalidBlock(_, _, error)) => error.failure
        }
        assertEquals(refused, Vector(cause))
        assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]), events.toString)
    }
  }

  test("a self-rejected block's transactions leave the pool so the next slot does not repeat it") {
    val p = pool(kesStart = 50)
    val cause = xyz.kd5ujc.scarab.ledger.BlockFailure.HeaderDiffers
    val (t1, _) = xyz.kd5ujc.scarab.node.mempool.TxKit.pay(1)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1), ruleFailure = Some(cause))
        _ <- h.mempool.add(t1, Source.Local)
        result <- h.forger.step(leaderSlot(p))
        held <- h.mempool.snapshot
      yield
        assertEquals(result, Right(()))
        assertEquals(held.entries, Vector.empty)
    }
  }

  // issue 567: a provider defect on our own block is no verdict: its transactions leave the pool so
  // the next leader slot does not repeat it, the slot ends without a block, and the node goes on
  test("a provider defect on our own block ends the slot and evicts its transactions (issue 567)") {
    val p = pool(kesStart = 50)
    val boom = new IllegalStateException("provider broke")
    val failing = new xyz.kd5ujc.scarab.ledger.Verdicts:
      export xyz.kd5ujc.scarab.ledger.Verdicts.Direct.{
        keyHash,
        opCert,
        takePrepared,
        tpraosVrf,
        witness
      }
      def praosVrf(
          input: xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate.Input,
          vkey: xyz.kd5ujc.scarab.codec.Bytes,
          proof: xyz.kd5ujc.scarab.codec.Bytes,
          output: xyz.kd5ujc.scarab.codec.Bytes
      ) = xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate.Result.InternalFailure(boom)
    val (t1, _) = xyz.kd5ujc.scarab.node.mempool.TxKit.pay(1)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1), verdicts = failing)
        _ <- h.mempool.add(t1, Source.Local)
        result <- h.forger.step(leaderSlot(p))
        events <- h.seen
        chain <- h.chain
        held <- h.mempool.snapshot
      yield
        assertEquals(result, Right(()))
        // the block's transaction is evicted, no empty block is tried, nothing is adopted
        assertEquals(held.entries.size, 0)
        assertEquals(chain.tipRef, None)
        assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]))
        assert(!events.exists(_.isInstanceOf[ForgeEvent.Adopted]))
    }
  }

  /** A key source whose write-back fails with a defect, counting its calls. */
  private def defective(cause: Throwable, calls: Ref[IO, Int]) =
    new HotKeySource[IO]:
      def read = IO.pure(Left(unreadable))
      def persist(key: Sum6KesSigning.SigningKey) =
        calls.update(_ + 1) *> IO.raiseError[Persisted](cause)

  test("a slot defect retains its cause without recording a recoverable forge failure") {
    val p = pool(kesStart = 50)
    val cause = new IllegalStateException("write-back bug")
    withHandle {
      for
        calls <- Ref.of[IO, Int](0)
        h <- harness(p, ledgerWith(p, 1), source = Some(defective(cause, calls)))
        // slot 5100 is KES period 51: the key evolves and is written back
        result <- h.forger.step(5100)
        events <- h.seen
        stats <- h.forger.stats.total
      yield
        assertEquals(result, Left(Fatal.Defect(cause)))
        assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]))
        assertEquals(stats.failed, 0L)
    }
  }

  test("run finishes on its first fatal outcome instead of attempting another slot") {
    val p = pool(kesStart = 50)
    val cause = new IllegalStateException("write-back bug")
    withHandle {
      for
        calls <- Ref.of[IO, Int](0)
        h <- harness(
          p,
          ledgerWith(p, 1),
          source = Some(defective(cause, calls)),
          clock = Some(IO.pure(java.time.Instant.EPOCH.plusSeconds(5100))),
          unbounded = true
        )
        result <- h.forger.run
        count <- calls.get
      yield
        assertEquals(result, Left(Fatal.Defect(cause)))
        assertEquals(count, 1)
    }
  }

  test("an intentional unreadable-key refusal keeps the credentials and permits another reload") {
    val p = pool(kesStart = 50)
    val refusal = unreadable
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1), source = Some(source(IO.pure(Left(refusal)))))
        first <- h.forger.reload
        second <- h.forger.reload
        current <- currentHot(h.forger.hot)
        events <- h.seen
      yield
        assertEquals(first, Right(()))
        assertEquals(second, Right(()))
        assertEquals(current, p.creds)
        assertEquals(
          events.collect { case ForgeEvent.KesReloadRefused(reason) => reason },
          Vector.fill(2)(ReloadRefusal.Unreadable(refusal))
        )
    }
  }

  test("reload defects, including synchronous source construction, stay fatal") {
    val p = pool(kesStart = 50)
    val cause = new IllegalStateException("key source bug")
    withHandle {
      List(source(IO.raiseError(cause)), source(throw cause)).traverse_ { keys =>
        for
          h <- harness(p, ledgerWith(p, 1), source = Some(keys))
          result <- h.forger.reload
          events <- h.seen
        yield
          assertEquals(result, Left(Fatal.Defect(cause)))
          assert(!events.exists(_.isInstanceOf[ForgeEvent.KesReloadRefused]))
      }
    }
  }

  test("reload distinguishes file absence, I/O failure and unexpected file-adapter defects") {
    val p = pool(kesStart = 50)
    val io = new IOException("filesystem unavailable")
    val bug = new IllegalStateException("file adapter bug")
    withHandle {
      List(io -> Fatal.Storage(StorageError.Io("kes", io)), bug -> Fatal.Defect(bug)).traverse_ {
        case (cause, expected) =>
          for
            h <- harness(
              p,
              ledgerWith(p, 1),
              source = Some(
                source(
                  IO.pure(
                    Left(
                      ForgeError.KeyFile("kes", ForgeKeyProblem.Io(cause))
                    )
                  )
                )
              )
            )
            result <- h.forger.reload
            events <- h.seen
          yield
            assertEquals(result, Left(expected))
            assert(!events.exists(_.isInstanceOf[ForgeEvent.KesReloadRefused]))
      }
    }
  }

  test("key persistence distinguishes filesystem failures, defects and durability invariants") {
    val cause = new IllegalStateException("key adapter bug")
    val invariant = ForgeError.KesFileFailure(
      "kes",
      xyz.kd5ujc.scarab.ledger.KesFileOperation.StageWrite,
      xyz.kd5ujc.scarab.ledger.KesFileFailureCause.NoWriteProgress
    )
    withHandle {
      List(
        ForgeError.KeyFile("kes", ForgeKeyProblem.Io(cause)) -> Fatal.Defect(cause),
        invariant -> Fatal.Internal(invariant)
      ).traverse_ { case (error, expected) =>
        val keys = new HotKeySource[IO]:
          def read = IO.pure(Left(unreadable))
          def persist(key: Sum6KesSigning.SigningKey) = IO.pure(Persisted.Failed(error))
        for
          fresh <- IO(pool(kesStart = 50))
          h <- harness(fresh, ledgerWith(fresh, 1), source = Some(keys))
          result <- h.forger.step(5100)
          _ <- Forger.scoped(h.forger.hot.locked(_ => IO.unit)).timeout(3.seconds)
          events <- h.seen
        yield
          assertEquals(result, Left(expected))
          assert(!events.exists(_.isInstanceOf[ForgeEvent.Forged]))
          assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]))
          assertEquals(
            events.collect { case ForgeEvent.KesPersistFailed(_, cause) => cause },
            Vector(error)
          )
      }
    }
  }

  test("typed crypto implementation defects are fatal rather than reload refusals") {
    val p = pool(kesStart = 50)
    val cause = new IllegalStateException("VRF primitive bug")
    val error = ForgeError.KeyFile(
      "vrf",
      ForgeKeyProblem.Vrf(
        xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover.Failure.InternalFailure(cause)
      )
    )
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1), source = Some(source(IO.pure(Left(error)))))
        result <- h.forger.reload
        events <- h.seen
      yield
        assertEquals(result, Left(Fatal.Defect(cause)))
        assert(!events.exists(_.isInstanceOf[ForgeEvent.KesReloadRefused]))
    }
  }

  test("a failed reload poisons an evolved key before another step in the same period") {
    val p = pool(kesStart = 50)
    val cause = new IOException("disk full after evolution")
    val error = ForgeError.KeyFile("kes", ForgeKeyProblem.Io(cause))
    val expected = Fatal.Storage(StorageError.Io("kes", cause))
    withHandle {
      for
        writes <- Ref.of[IO, Int](0)
        keys = new HotKeySource[IO]:
          def read = IO.pure(
            Right(
              HotKeySource.Candidate(
                p.creds.hot.copy(kes = p.creds.kes.copy),
                IO.unit
              )
            )
          )
          def persist(key: Sum6KesSigning.SigningKey) = writes.getAndUpdate(_ + 1).map {
            case 0 => Persisted.Unchanged
            case _ => Persisted.Failed(error)
          }
        h <- harness(
          p,
          ledgerWith(p, 1),
          source = Some(keys),
          clock = Some(IO.pure(java.time.Instant.EPOCH.plusSeconds(5100)))
        )
        reload <- h.forger.reload
        _ = assertEquals(p.creds.kes.period, 1)
        step <- h.forger.step(5101)
        again <- h.forger.reload
        events <- h.seen
        count <- writes.get
      yield
        assertEquals(reload, Left(expected))
        assertEquals(step, Left(expected))
        assertEquals(again, Left(expected))
        assertEquals(count, 2)
        assertEquals(
          events.collect { case ForgeEvent.KesPersistFailed(_, cause) => cause },
          Vector(error)
        )
        assert(!events.exists(_.isInstanceOf[ForgeEvent.Forged]))
        assert(!events.exists(_.isInstanceOf[ForgeEvent.Adopted]))
    }
  }

  test("a signer queued behind a failed persistence sees the poisoned key before it can sign") {
    val p = pool(kesStart = 50)
    val cause = new IOException("disk full")
    val error = ForgeError.KeyFile("kes", ForgeKeyProblem.Io(cause))
    val expected = Fatal.Storage(StorageError.Io("kes", cause))
    for
      reporting <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      signed <- Ref.of[IO, Int](0)
      events <- Ref.of[IO, Vector[ForgeEvent]](Vector.empty)
      source = new HotKeySource[IO]:
        def read = IO.pure(Left(unreadable))
        def persist(key: Sum6KesSigning.SigningKey) = IO.pure(Persisted.Failed(error))
      keys <- HotKeys.create[IO](
        p.creds,
        Some(source),
        config,
        event =>
          events.update(_ :+ event) *> (event match
            case _: ForgeEvent.KesPersistFailed => reporting.complete(()) *> release.get
            case _                              => IO.unit)
      )
      failing <- Forger.scoped(keys.update(51)).start
      _ <- reporting.get.timeout(3.seconds)
      signer <- Forger
        .scoped(
          keys.locked(c =>
            signed.update(_ + 1) *> IO(c.kes.sign(xyz.kd5ujc.scarab.codec.Bytes.empty))
          )
        )
        .start
      _ <- IO.cede
      _ <- release.complete(())
      result <- failing.joinWithNever.timeout(3.seconds)
      attempted <- signer.joinWithNever.timeout(3.seconds)
      read <- Forger.scoped(keys.current)
      count <- signed.get
      emitted <- events.get
    yield
      assertEquals(result, Left(expected))
      assertEquals(attempted, Left(expected))
      assertEquals(read, Left(expected))
      assertEquals(count, 0)
      assertEquals(
        emitted.collect { case ForgeEvent.KesPersistFailed(_, cause) => cause },
        Vector(error)
      )
  }

  test("FileNotFoundException for an existing directory is fatal storage, not file absence") {
    val p = pool(kesStart = 50)
    val directory = java.nio.file.Files.createTempDirectory("scarab-forger-fnf")
    val cause =
      try
        val stream = new java.io.FileInputStream(directory.toFile)
        stream.close()
        fail("FileInputStream accepted a directory")
      catch case e: java.io.FileNotFoundException => e
      finally java.nio.file.Files.delete(directory)
    withHandle {
      for
        h <- harness(
          p,
          ledgerWith(p, 1),
          source = Some(
            source(
              IO.pure(
                Left(
                  ForgeError.KeyFile("kes", ForgeKeyProblem.Io(cause))
                )
              )
            )
          )
        )
        result <- h.forger.reload
        events <- h.seen
      yield
        assertEquals(result, Left(Fatal.Storage(StorageError.Io("kes", cause))))
        assert(!events.exists(_.isInstanceOf[ForgeEvent.KesReloadRefused]))
    }
  }

  test("owned scopes retain storage and internal service failures as typed fatal values") {
    val cause = StorageError.Io("utxo", new IOException("disk full"))
    for
      storage <- Forger.scoped[IO, Unit](summon[Handle[IO, NodeError]].raise(cause))
      internal <- Forger.scoped[IO, Unit](
        summon[Handle[IO, NodeError]].raise(ChainError.NoIntersection)
      )
    yield
      assertEquals(storage, Left(Fatal.Storage(cause)))
      assertEquals(internal, Left(Fatal.Internal(ChainError.NoIntersection)))
  }

  test(
    "candidate selection applies a candidate again without decoding its scripts; invalidity is skipped"
  ) {
    val fixture = xyz.kd5ujc.scarab.ledger.PlutusCase.load(
      java.nio.file.Path.of("fixtures/preprod/plutus/tx-2edd684f.json")
    )
    val script =
      (fixture.tx.witnesses.scripts ++ fixture.references.values.flatMap(_.referenceScript))
        .find(_.kind.isPlutus)
        .getOrElse(fail("fixture has no Plutus script"))
    val code = script.plutus.getOrElse(fail("fixture discarded script bytes"))
    val (raw, id) = xyz.kd5ujc.scarab.node.mempool.TxKit.tx(
      Vector(xyz.kd5ujc.scarab.ledger.TestLedger.txIn(1)),
      Vector(xyz.kd5ujc.scarab.node.mempool.TxKit.bob.address() -> BigInt(99000000)),
      BigInt(1000000),
      Vector(xyz.kd5ujc.scarab.node.mempool.TxKit.alice),
      referenceScript = Some(script.kind -> code)
    )
    val tx = xyz.kd5ujc.scarab.node.mempool.MempoolState.parse(raw).fold(e => fail(e.message), _._2)
    val entry = xyz.kd5ujc.scarab.node.mempool.Entry(id, raw, tx, 0)
    val stopped = xyz.kd5ujc.scarab.plutus.BackendFailure
      .BuiltinUnavailable(scalus.uplc.DefaultFun.Bls12_381_G1_hashToGroup)
    def failing(
        reason: xyz.kd5ujc.scarab.plutus.BackendFailure
    ): xyz.kd5ujc.scarab.plutus.ScriptDecoder[cats.Id] =
      (_, _, _) => Left(xyz.kd5ujc.scarab.plutus.ScriptDecodeFailure.Unavailable(reason))
    val base = xyz.kd5ujc.scarab.node.mempool.TxKit.ledger(2)
    val limit = BlockBuilder.Limits(65536, xyz.kd5ujc.scarab.plutus.ExUnits(14000000, 10000000000L))
    // a candidate is applied again without the static checks (`reapplyTx`): its scripts are not
    // decoded again (`Babbage/Rules/Utxow.hs` 379), so a decoder that is unavailable now, or fails
    // on the transaction, gives no reason to stop or to skip it
    List(stopped, xyz.kd5ujc.scarab.plutus.BackendFailure.StackExhausted).foreach { reason =>
      assertEquals(
        BlockBuilder
          .select(base, 1001, 1, geometry, globals, Vector(entry), limit)(using
            failing(reason),
            xyz.kd5ujc.scarab.plutus.ScalusEvaluator.default
          )
          .map(_.map(_._1.id)),
        Right(Vector(id))
      )
    }
    val invalid = entry.copy(tx = tx.copy(body = tx.body.copy(fee = -1)))
    assertEquals(
      BlockBuilder.select(base, 1001, 1, geometry, globals, Vector(invalid), limit)(using
        xyz.kd5ujc.scarab.plutus.ScalusEvaluator.defaultDecoder,
        xyz.kd5ujc.scarab.plutus.ScalusEvaluator.default
      ),
      Right(Vector.empty)
    )
  }

  test("cancelling key persistence releases the key lock and remains cancellation") {
    val p = pool(kesStart = 50)
    withHandle {
      for
        entered <- Deferred[IO, Unit]
        released <- Deferred[IO, Unit]
        keys = new HotKeySource[IO]:
          def read = IO.pure(Left(unreadable))
          def persist(key: Sum6KesSigning.SigningKey) =
            (entered.complete(()) *> IO.never[Persisted]).guarantee(released.complete(()).void)
        h <- harness(p, ledgerWith(p, 1), source = Some(keys))
        fiber <- h.forger.step(5100).start
        _ <- entered.get.timeout(3.seconds)
        _ <- fiber.cancel
        outcome <- fiber.join
        _ <- released.get.timeout(3.seconds)
        blocked <- Forger.scoped(h.forger.hot.locked(_ => IO.unit)).timeout(3.seconds)
        events <- h.seen
      yield
        assertEquals(blocked, Left(Fatal.Internal(ForgeError.KesUpdateInterrupted)))
        assertEquals(outcome, Outcome.canceled[IO, Throwable, Either[Fatal, Unit]])
        assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]))
        assert(!events.exists(_.isInstanceOf[ForgeEvent.KesPersistFailed]))
    }
  }
