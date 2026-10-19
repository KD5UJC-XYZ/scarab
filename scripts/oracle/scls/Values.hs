-- SPDX-License-Identifier: Apache-2.0
-- Generated from the public pinned cardano-ledger-canonical-state source at
-- ledger f649f9751074d2ab3de033fc3912f29c9862c1f5 and CLS 8e2bba9442cb388089f53d708cc4aedcb47726ea.
-- Build as a separate executable in that repository with the recipe recorded in
-- fixtures/scls/namespace-value-vectors/conway-f649.json.
{-# LANGUAGE AllowAmbiguousTypes #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}

module Main where

import Cardano.Ledger.CanonicalState.Namespace.EntitiesAccounts.V0 (EntitiesAccountsOut)
import Cardano.Ledger.CanonicalState.Namespace.Blocks.V0 (BlockOut)
import Cardano.Ledger.CanonicalState.Namespace.EntitiesDReps.V0 (EntitiesDRepsOut)
import Cardano.Ledger.CanonicalState.Namespace.EntitiesStakePools.V0 (EntitiesStakePoolsOut)
import Cardano.Ledger.CanonicalState.Conway.Export ()
import Cardano.Ledger.CanonicalState.Conway.Import ()
import Cardano.Ledger.CanonicalState.Namespace.EntitiesCommittee.V0 (CanonicalCommitteeState)
import Cardano.Ledger.CanonicalState.Namespace.GovCommittee.V0 (GovCommitteeOut)
import Cardano.Ledger.CanonicalState.Namespace.GovProposals.Roots.V0 (GovProposalsRootsOut)
import Cardano.Ledger.CanonicalState.Namespace.GovProposals.V0 (GovProposalOut)
import Cardano.Ledger.CanonicalState.Namespace.GovPParams.V0 (GovPParamsOut)
import Cardano.Ledger.CanonicalState.Conway (CanonicalGovActionState)
import Cardano.Ledger.Conway (ConwayEra)
import Cardano.SCLS.CBOR.Canonical (getRawDecoder, getRawEncoding)
import Cardano.SCLS.CBOR.Canonical.Decoder (FromCanonicalCBOR (..))
import Cardano.SCLS.CBOR.Canonical.Encoder (ToCanonicalCBOR (..))
import Cardano.SCLS.Versioned (Versioned (..))
import Codec.CBOR.Read qualified as Read
import Codec.CBOR.Write (toStrictByteString)
import Data.ByteString qualified as BS
import Data.ByteString.Base16 qualified as Base16
import Data.ByteString.Char8 qualified as BSC
import Data.ByteString.Lazy qualified as BL
import Data.Proxy (Proxy (..))
import GHC.TypeLits (KnownSymbol)
import System.Exit (die)

hex :: String -> BS.ByteString
hex = either (error . show) id . Base16.decode . BSC.pack

canonicalValue :: forall ns a. (KnownSymbol ns, FromCanonicalCBOR ns a, ToCanonicalCBOR ns a) => BS.ByteString -> IO BS.ByteString
canonicalValue input = do
  (remaining, Versioned value) <- either (die . show) pure $
    Read.deserialiseFromBytes (getRawDecoder (fromCanonicalCBOR @ns @a)) (BL.fromStrict input)
  if BL.null remaining then pure () else die "typed canonical decoder left trailing bytes"
  pure . toStrictByteString . getRawEncoding $ toCanonicalCBOR (Proxy @ns) value

writeVector :: String -> BS.ByteString -> IO String
writeVector name value = pure (name <> "\t" <> BSC.unpack (Base16.encode value))

main :: IO ()
main = do
  let zeroHash = replicate 56 '0'
      zeroHash32 = replicate 64 '0'
      account drep = hex $ "a46762616c616e636507676465706f736974096f647265705f64656c65676174696f6e" <> drep <> "757374616b655f706f6f6c5f64656c65676174696f6ef6"
      keyDRep = "8200581c" <> zeroHash
      scriptDRep = "8201581c" <> zeroHash
      accountType = canonicalValue @"entities/accounts/v0" @EntitiesAccountsOut
  accountKey <- accountType (account keyDRep)
  accountScript <- accountType (account scriptDRep)
  let committeeValue = hex $ "82a18200581c" <> zeroHash <> "01d81e820102"
      committeeEntitiesValue = hex $ "a18200581c" <> zeroHash <> "82008201581c" <> zeroHash
  govCommittee <- canonicalValue @"gov/committee/v0" @GovCommitteeOut committeeValue
  committeeEntities <- canonicalValue @"entities/committee/v0" @CanonicalCommitteeState committeeEntitiesValue
  blockNatural <- canonicalValue @"blocks/v0" @BlockOut (hex "c249010000000000000000")
  let drepValue = hex $ "a466616e63686f72826375726c5820" <> zeroHash32 <> "6665787069727902676465706f736974056b64656c65676174696f6e73d90102818201581c" <> zeroHash
  drepState <- canonicalValue @"entities/dreps/v0" @EntitiesDRepsOut drepValue
  proposalRoot <- canonicalValue @"gov/proposals/roots/v0" @GovProposalsRootsOut (hex $ "825820" <> zeroHash32 <> "07")
  proposalState <- canonicalValue @"gov/proposals/v0" @(GovProposalOut CanonicalGovActionState) (hex "8207a66a647265705f766f746573a18201581c00000000000000000000000000000000000000000000000000000000016b70726f706f7365645f696e026d657870697265735f61667465720a6f636f6d6d69747465655f766f746573a18200581c0000000000000000000000000000000000000000000000000000000002707374616b655f706f6f6c5f766f746573a1581c00000000000000000000000000000000000000000000000000000000007270726f706f73616c5f70726f636564757265586f841805581de1000000000000000000000000000000000000000000000000000000008203825820000000000000000000000000000000000000000000000000000000000000000007826375726c58200000000000000000000000000000000000000000000000000000000000000000")
  pparamsNatural <- canonicalValue @"gov/pparams/v0" @(GovPParamsOut ConwayEra) (hex "b81f00010100021a00010000031940000419044c051a00061a800600071208186409d81e8200010ad81e82010a0bd81e82010a0e8209001000111910d612a20098a61a0003236119032c01011903e819023b00011903e8195e7104011903e818201a0001ca761928eb041959d818641959d818641959d818641959d818641959d818641959d81864186418641959d81864194c5118201a0002acfa182019b551041a000363151901ff00011a00015c3518201a000797751936f404021a0002ff941a0006ea7818dc0001011903e8196ff604021a0003bd081a00034ec5183e011a00102e0f19312a011a00032e801901a5011a0002da781903e819cf06011a00013a34182019a8f118201903e818201a00013aac0119e143041903e80a1a00030219189c011a00030219189c011a0003207c1901d9011a000330001901ff0119ccf3182019fd40182019ffd5182019581e18201940b318201a00012adf18201a0002ff941a0006ea7818dc0001011a00010f92192da7000119eabb18201a0002ff941a0006ea7818dc0001011a0002ff941a0006ea7818dc0001011a000c504e197712041a001d6af61a0001425b041a00040c660004001a00014fab18201a0003236119032c010119a0de18201a00033d7618201979f41820197fb8182019a95d1820197df718201995aa18201a0374f693194a1f0a0298fb1a000189b41901a401011903e818ad00011903e819ea350401192baf18201a000312591920a404193e801864193e801864193e801864193e801864193e801864193e80186418641864193e8018641a000170a718201a00020782182019f016041a0001194a18b2000119568718201a0001643519030104021a00014f581a0001e143191c893903831906b41903c018391a00014f580001011903e819a7a9040219779f197053184b011a000db464196a8f0119ca3f19022e011999101903e819ecb2011a00022a4718201a000144ce1820193bc318201a0001291101193371041956540a197147184a01197147184a0119a9151902280119aecd19021d0119843c18201a00010a9618201a00011aaa1820191c4b1820191cdf1820192d1a18201a00014f581a0001e143191c893903831906b41903c018391a00014f5800011a0001614219020700011a000122c118201a00014f581a0001e143191c893903831906b41903c018391a00014f580001011a00014f581a0001e143191c893903831906b41903c018391a00014f5800011a000e94721a0003414000021a0004213c19583c041a00163cad19fc3604194ff30104001a00022aa818201a000189b41901a401011a00013eff182019e86a1820194eae182019600c1820195108182019654d182019602f18201a0290f1e70a1a032e93af1937fd0a1a0298e40b1966c40a193e801864193e8018641a000eaf1f121a002a6e06061a0006be98011a0321aac7190eac121a00041699121a048e466e1922a4121a0327ec9a121a001e743c18241a0031410f0c1a000dbf9e011a09f2f6d31910d318241a0004578218241a096e44021967b518241a0473cee818241a13e62472011a0f23d40118481a00212c5618481a0022814619fc3b041a00032b00192076041a0013be0419702c183f00011a000f59d919aa6718fb00011382d81e82190241192710d81e821902d11a0098968014821b8000000000000000c24901000000000000000015821b8000000000000000c24901000000000000000016191388171896181803181985d81e820102d81e820102d81e820102d81e820102d81e820102181a8ad81e820001d81e820102d81e820001d81e820001d81e820102d81e820102d81e820102d81e820102d81e820102d81e820102181b00181c18c8181d01181e1a000f4240181f1a000f4240182018641821d81e820f01")
  let stakePoolValue = hex $
        "a3707374616b655f706f6f6c5f7374617465aa637672665820000000000000000000000000000000000000000000000000000000000000000064636f737403666d617267696ed81e82010a666f776e657273d9010281581c0000000000000000000000000000000000000000000000000000000066706c65646765046672656c617973838301190bb96a72656c61792e746573748400190bb9440a0000015020010db800000000000000000000000182026d72656c61792e6578616d706c65676465706f73697405686d65746164617461827468747470733a2f2f706f6f6c2e6578616d706c65582000000000000000000000000000000000000000000000000000000000000000006a6163636f756e745f69648200581c000000000000000000000000000000000000000000000000000000006a64656c656761746f7273d90102818201581c00000000000000000000000000000000000000000000000000000000717265746972696e675f65706f63685f6e6f0978186675747572655f7374616b655f706f6f6c5f706172616d73a9626964581c00000000000000000000000000000000000000000000000000000000637672665820000000000000000000000000000000000000000000000000000000000000000064636f737406666d617267696ed81e82010a666f776e657273d9010281581c0000000000000000000000000000000000000000000000000000000066706c65646765076672656c617973838301190bb96a72656c61792e746573748400190bb9440a0000015020010db800000000000000000000000182026d72656c61792e6578616d706c65686d65746164617461827468747470733a2f2f706f6f6c2e6578616d706c65582000000000000000000000000000000000000000000000000000000000000000006f6163636f756e745f61646472657373581de100000000000000000000000000000000000000000000000000000000"
  stakePool <- canonicalValue @"entities/stake_pools/v0" @EntitiesStakePoolsOut stakePoolValue
  rows <- sequence
    [ writeVector "block-natural-uint64-plus-one" blockNatural
    , writeVector "drep-anchor-and-delegations" drepState
    , writeVector "proposal-root-tuple-index-integer" proposalRoot
    , writeVector "proposal-procedure-onchain-raw-preservation" proposalState
    , writeVector "pparams-unbounded-execution-unit-natural" pparamsNatural
    , writeVector "stake-pool-current-future-relay-metadata-retirement" stakePool
    , writeVector "account-key-drep" accountKey
    , writeVector "account-script-drep" accountScript
    , writeVector "gov-committee-script-credential" govCommittee
    , writeVector "committee-entity-script-cold-key-hot" committeeEntities
    ]
  let output = "/probe/values-stakepools-out/haskell-nonempty-vectors.tsv"
  writeFile output (unlines rows)
  putStrLn ("wrote " <> output)
  mapM_ putStrLn rows
