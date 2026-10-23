-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE AllowAmbiguousTypes #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}

-- | Executes the unchanged released @addShelleyInstantStake@ and @deleteShelleyInstantStake@
-- (cardano-ledger f649f975, @Shelley/State/Stake.hs:108-145@) on the cases of the input file, in
-- the order of @updateUTxOAndInstantStake@ (@Shelley/Rules/Utxo.hs:613-624@): the produced outputs
-- are added, then the consumed outputs are deleted. Each case runs in Shelley and in Babbage,
-- which share the instant stake type and these functions and differ in the era's @TxOut@. The
-- output coins are converted with the released @toCompact@, which is also measured on its own.
module Main (main) where

import Cardano.Crypto.Hash.Class (hashFromBytes, hashToBytes)
import Cardano.Ledger.Address (Addr, decodeAddr)
import Cardano.Ledger.Babbage (BabbageEra)
import Cardano.Ledger.BaseTypes (CertIx (..), TxIx (..))
import Cardano.Ledger.Coin (Coin (..), CompactForm (..))
import Cardano.Ledger.Compactible (toCompact)
import Cardano.Ledger.Core (EraTxOut, TxOut, Value, mkBasicTxOut)
import Cardano.Ledger.Credential (Credential (..), Ptr (..), SlotNo32 (..))
import Cardano.Ledger.Hashes (KeyHash (..), unsafeMakeSafeHash)
import Cardano.Ledger.Shelley (ShelleyEra)
import Cardano.Ledger.Shelley.State
  ( ShelleyInstantStake (..)
  , addShelleyInstantStake
  , deleteShelleyInstantStake
  )
import Cardano.Ledger.State (UTxO (..))
import Cardano.Ledger.TxIn (TxId (..), TxIn (..))
import Cardano.Ledger.Val (Val, inject)
import Data.Aeson (FromJSON (..), eitherDecode, encode, object, withObject, (.:), (.=))
import qualified Data.Aeson as Aeson
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import System.Environment (getArgs)
import System.Exit (die)

-- | A decimal string, since the cases include values above 2^53.
newtype Dec = Dec Integer

instance FromJSON Dec where
  parseJSON = Aeson.withText "decimal" $ \t -> case reads (Text.unpack t) of
    [(n, "")] -> pure (Dec n)
    _ -> fail "not a decimal integer"

newtype Hx = Hx BS.ByteString

instance FromJSON Hx where
  parseJSON = Aeson.withText "hex" $ \t ->
    either fail (pure . Hx) (Hex.decode (Text.encodeUtf8 t))

data CredEntry = CredEntry Hx Dec

instance FromJSON CredEntry where
  parseJSON = withObject "credential entry" $ \o -> CredEntry <$> o .: "keyHash" <*> o .: "coin"

data PtrEntry = PtrEntry Integer Integer Integer Dec

instance FromJSON PtrEntry where
  parseJSON = withObject "pointer entry" $ \o ->
    PtrEntry <$> o .: "slot" <*> o .: "txIx" <*> o .: "certIx" <*> o .: "coin"

data Out = Out Hx Integer Hx Dec

instance FromJSON Out where
  parseJSON = withObject "output" $ \o ->
    Out <$> o .: "txId" <*> o .: "index" <*> o .: "address" <*> o .: "coin"

data Case = Case
  { credentials :: [CredEntry]
  , pointers :: [PtrEntry]
  , produce :: [Out]
  , consume :: [Out]
  }

instance FromJSON Case where
  parseJSON = withObject "case" $ \o ->
    Case <$> o .: "credentials" <*> o .: "pointers" <*> o .: "produce" <*> o .: "consume"

data Inputs = Inputs {cases :: [(Aeson.Value, Case)], coinChecks :: [Integer]}

instance FromJSON Inputs where
  parseJSON = withObject "inputs" $ \o -> do
    raw <- o .: "cases"
    parsed <- traverse parseJSON raw
    Inputs (zip raw parsed) . map (\(Dec n) -> n) <$> o .: "coinChecks"

compact :: Integer -> IO (CompactForm Coin)
compact n = maybe (die ("coin out of range: " ++ show n)) pure (toCompact (Coin n))

hash28 :: Hx -> IO (KeyHash r)
hash28 (Hx bs) = maybe (die "bad key hash") (pure . KeyHash) (hashFromBytes bs)

mkPtr :: Integer -> Integer -> Integer -> Ptr
mkPtr s t c = Ptr (SlotNo32 (fromInteger s)) (TxIx (fromInteger t)) (CertIx (fromInteger c))

initial :: Case -> IO (ShelleyInstantStake era)
initial c = do
  creds <- traverse (\(CredEntry k (Dec n)) -> (,) <$> (KeyHashObj <$> hash28 k) <*> compact n) (credentials c)
  ptrs <- traverse (\(PtrEntry s t i (Dec n)) -> (,) (mkPtr s t i) <$> compact n) (pointers c)
  pure (ShelleyInstantStake (Map.fromList creds) (Map.fromList ptrs))

utxoOf :: forall era. (EraTxOut era, Val (Value era)) => [Out] -> IO (UTxO era)
utxoOf outs = UTxO . Map.fromList <$> traverse one outs
  where
    one (Out (Hx txid) ix (Hx addr) (Dec n)) = do
      h <- maybe (die "bad tx id") pure (hashFromBytes txid)
      a <- decodeAddr addr :: IO Addr
      pure (TxIn (TxId (unsafeMakeSafeHash h)) (TxIx (fromInteger ix)), mkBasicTxOut a (inject (Coin n)) :: TxOut era)

-- | The sequence of @updateUTxOAndInstantStake@: add the produced outputs, then delete the
-- consumed ones.
run :: forall era. (EraTxOut era, Val (Value era)) => Case -> IO Aeson.Value
run c = do
  start <- initial c
  added <- utxoOf @era (produce c)
  deleted <- utxoOf @era (consume c)
  let ShelleyInstantStake cs ps = deleteShelleyInstantStake deleted (addShelleyInstantStake added start)
  pure $
    object
      [ "credentials"
          .= [ object ["keyHash" .= hexOf (hashToBytes (unKeyHash k)), "coin" .= show c']
             | (KeyHashObj k, CompactCoin w) <- Map.toAscList cs
             , let c' = toInteger w
             ]
      , "pointers"
          .= [ object ["slot" .= toInteger s, "txIx" .= toInteger t, "certIx" .= toInteger i, "coin" .= show (toInteger w)]
             | (Ptr (SlotNo32 s) (TxIx t) (CertIx i), CompactCoin w) <- Map.toAscList ps
             ]
      ]
  where
    hexOf = Text.decodeUtf8 . Hex.encode

main :: IO ()
main = do
  path <- getArgs >>= \args -> case args of
    [p] -> pure p
    _ -> die "usage: scarab-instant-stake <inputs.json>"
  bytes <- LBS.readFile path
  inputs <- either die pure (eitherDecode bytes)
  rows <- traverse
    ( \(raw, c) -> do
        shelley <- run @ShelleyEra c
        babbage <- run @BabbageEra c
        pure (object ["input" .= raw, "shelley" .= shelley, "babbage" .= babbage, "eraAgree" .= (shelley == babbage)])
    )
    (cases inputs)
  let checks =
        [ object
            [ "coin" .= show n
            , "compact" .= fmap (\(CompactCoin w) -> show (toInteger w)) (toCompact (Coin n))
            ]
        | n <- coinChecks inputs
        ]
  LBS.putStr (encode (object ["cases" .= rows, "coinChecks" .= checks]))
  putStrLn ""
