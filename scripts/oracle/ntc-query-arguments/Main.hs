-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE AllowAmbiguousTypes #-}
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE DeriveGeneric #-}
{-# LANGUAGE FlexibleContexts #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
-- The arguments of the node-to-client ledger queries, read with the decoders the reference's
-- query decoder uses for them (`decodeShelleyQuery`,
-- `ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Query.hs:916-995`,
-- ouroboros-consensus 82ecba32): `fromCBOR` of cardano-binary for some, `LC.fromEraCBOR @era`
-- (the ledger's `decCBOR` at the era's lowest protocol version, 9 for Conway) for the others.
-- Each case is one argument's bytes and the decoder it is read with; the verdict, and the value
-- read rendered as sorted text, are written one JSON line per case (#709).
module Main (main) where

import qualified Cardano.Binary as Plain
import qualified Cardano.Crypto.Hash as Hash
import Cardano.Ledger.Address (Addr, serialiseAddr)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Coin (Coin (..))
import Cardano.Ledger.Credential (Credential (..))
import Cardano.Ledger.DRep (DRep (..))
import Cardano.Ledger.Hashes (ScriptHash (..), extractHash)
import Cardano.Ledger.Keys (KeyHash (..), KeyRole (..))
import Cardano.Ledger.TxIn (TxId (..), TxIn (..))
import Data.Aeson (FromJSON, Value, eitherDecode, encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy.Char8 as LBS
import Data.List (sort)
import qualified Data.Set as Set
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import GHC.Generics (Generic)
import System.Environment (getArgs)
import System.Exit (die)

data Case = Case {name :: String, decoder :: String, bytes :: Text.Text}
  deriving (Generic)

instance FromJSON Case

-- | How a value read is written down, to compare with what the node reads.
class Shown a where
  shown :: a -> Text.Text

hexOf :: BS.ByteString -> Text.Text
hexOf = Text.decodeUtf8 . Hex.encode

instance Shown (KeyHash r) where
  shown (KeyHash h) = hexOf (Hash.hashToBytes h)

instance Shown (Credential r) where
  shown (KeyHashObj h) = "key:" <> shown h
  shown (ScriptHashObj (ScriptHash h)) = "script:" <> hexOf (Hash.hashToBytes h)

instance (Shown a, Shown b) => Shown (Either a b) where
  shown (Left a) = "left:" <> shown a
  shown (Right b) = "right:" <> shown b

instance Shown Coin where
  shown (Coin c) = Text.pack (show c)

instance Shown TxIn where
  shown (TxIn (TxId h) ix) = hexOf (Hash.hashToBytes (extractHash h)) <> "#" <> Text.pack (show (fromEnum ix))

instance Shown DRep where
  shown (DRepCredential c) = shown c
  shown DRepAlwaysAbstain = "abstain"
  shown DRepAlwaysNoConfidence = "noconfidence"

-- | `MemberStatus` of cardano-ledger-api (`Cardano/Ledger/Api/State/Query/Governance.hs:34-48`,
-- cardano-ledger f649f975), which this image does not build: the same three constructors, and the
-- same decoder, `decodeEnumBounded` of cardano-ledger-binary.
data MemberStatus = Active | Expired | Unrecognized
  deriving (Show, Eq, Ord, Enum, Bounded)

instance Binary.DecCBOR MemberStatus where
  decCBOR = Binary.decodeEnumBounded

instance Shown MemberStatus where
  shown = Text.pack . show

instance Shown Addr where
  shown = hexOf . serialiseAddr

-- | The verdict and the elements read, sorted.
verdict :: Shown a => Either String [a] -> Value
verdict (Left failure) = object ["accepted" .= False, "failure" .= failure]
verdict (Right xs) = object ["accepted" .= True, "values" .= sort (map shown xs)]

-- | `fromCBOR` of cardano-binary, as `decodeShelleyQuery` reads a plain argument.
plain :: forall a. Plain.FromCBOR a => BS.ByteString -> Either String a
plain raw = either (Left . show) Right (Plain.decodeFull' raw)

-- | `LC.fromEraCBOR @ConwayEra`: the ledger's decoder at Conway's lowest protocol version.
conway :: forall a. Binary.DecCBOR a => BS.ByteString -> Either String a
conway raw = either (Left . show) Right (Binary.decodeFull' (Binary.natVersion @9) raw)




run :: String -> BS.ByteString -> Either String Value
run d raw = case d of
  "plain-pools" -> Right $ verdict (Set.toList <$> plain @(Set.Set (KeyHash StakePool)) raw)
  "plain-maybe-pools" -> Right $ case plain @(Maybe (Set.Set (KeyHash StakePool))) raw of
    Left failure -> object ["accepted" .= False, "failure" .= failure]
    Right Nothing -> object ["accepted" .= True, "nothing" .= True]
    Right (Just s) -> object ["accepted" .= True, "values" .= sort (map shown (Set.toList s))]
  "plain-staking" -> Right $ verdict (Set.toList <$> plain @(Set.Set (Credential Staking)) raw)
  "plain-dreps" -> Right $ verdict (Set.toList <$> plain @(Set.Set (Credential DRepRole)) raw)
  "plain-cold" -> Right $ verdict (Set.toList <$> plain @(Set.Set (Credential ColdCommitteeRole)) raw)
  "plain-hot" -> Right $ verdict (Set.toList <$> plain @(Set.Set (Credential HotCommitteeRole)) raw)
  "plain-nonmyopic" ->
    Right $ verdict (Set.toList <$> plain @(Set.Set (Either Coin (Credential Staking))) raw)
  "era-addresses" -> Right $ verdict (Set.toList <$> conway @(Set.Set Addr) raw)
  "era-staking" -> Right $ verdict (Set.toList <$> conway @(Set.Set (Credential Staking)) raw)
  "era-txins" -> Right $ verdict (Set.toList <$> conway @(Set.Set TxIn) raw)
  "era-dreps" -> Right $ verdict (Set.toList <$> conway @(Set.Set DRep) raw)
  "era-pools" -> Right $ verdict (Set.toList <$> conway @(Set.Set (KeyHash StakePool)) raw)
  "era-statuses" -> Right $ verdict (Set.toList <$> conway @(Set.Set MemberStatus) raw)
  "era-pool" -> Right $ verdict (pure <$> conway @(KeyHash StakePool) raw)
  _ -> Left ("unknown decoder " <> d)

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  mapM_
    ( \c -> do
        raw <- either die pure (Hex.decode (Text.encodeUtf8 (bytes c)))
        result <- either die pure (run (decoder c) raw)
        LBS.putStrLn (encode (object ["name" .= name c, "decoder" .= decoder c, "result" .= result]))
    )
    cases


