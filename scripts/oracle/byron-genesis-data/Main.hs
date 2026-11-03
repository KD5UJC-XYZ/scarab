-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
module Main (main) where

import Cardano.Chain.Common (TxFeePolicy (..), TxSizeLinear (..), lovelacePortionToRational, lovelaceToInteger, unBlockCount, unKeyHash)
import Cardano.Chain.Delegation (Certificate, delegateVK, epoch, issuerVK, signature)
import Cardano.Chain.Genesis (GenesisAvvmBalances (..), GenesisData (..), GenesisDelegation (..), GenesisKeyHashes (..), GenesisNonAvvmBalances (..))
import Cardano.Chain.Slotting (EpochNumber (..), SlotNumber (..))
import Cardano.Chain.Update (ProtocolParameters (..), SoftforkRule (..))
import Cardano.Crypto.Hashing (abstractHashToBytes, hashRaw, hashToBytes)
import Cardano.Crypto.ProtocolMagic (ProtocolMagicId (..))
import Cardano.Crypto.Signing (Signature (..), VerificationKey (..))
import qualified Cardano.Crypto.Wallet as CC
import Cardano.Prelude (SchemaError)
import Data.Aeson (FromJSON (..), Value, eitherDecode, encode, object, withObject, (.:), (.:?), (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Base16 as B16
import qualified Data.ByteString.Lazy as LBS
import qualified Data.Map.Strict as M
import Data.Ratio (denominator, numerator)
import qualified Data.Set as S
import qualified Data.Text as T
import qualified Data.Text.Encoding as TE
import Data.Time.Clock.POSIX (utcTimeToPOSIXSeconds)
import System.Environment (getArgs)
import System.Exit (die)
import Text.JSON.Canonical (fromJSON, parseCanonicalJSON, renderCanonicalJSON)

-- One measurement: a name and the bytes of a whole genesis file, given as text (encoded as UTF-8)
-- or, for bytes that are not UTF-8, as hex.
data Case = Case String BS.ByteString

instance FromJSON Case where
  parseJSON = withObject "case" $ \o -> do
    name <- o .: "name"
    hexed <- o .:? "hex"
    case hexed of
      Just h -> either fail (pure . Case name) (B16.decode (TE.encodeUtf8 h))
      Nothing -> Case name . TE.encodeUtf8 <$> o .: "text"

hex :: BS.ByteString -> T.Text
hex = TE.decodeUtf8 . B16.encode

-- Each field of `GenesisData` as `FromJSON GenesisData` (Genesis/Data.hs:85-102) produced it.
-- Integers are decimal strings; the balances, which the #728 corpus covers entry by entry, are
-- given by their count after `Map.fromList`.
project :: GenesisData -> Value
project gd =
  object
    [ "bootStakeholders" .= [hex (abstractHashToBytes (unKeyHash k)) | k <- S.toAscList (unGenesisKeyHashes (gdGenesisKeyHashes gd))]
    , "heavyDelegation" .= [delegation k c | (k, c) <- M.toList (unGenesisDelegation (gdHeavyDelegation gd))]
    , "startTime" .= show (round (utcTimeToPOSIXSeconds (gdStartTime gd)) :: Integer)
    , "nonAvvmBalances" .= M.size (unGenesisNonAvvmBalances (gdNonAvvmBalances gd))
    , "blockVersionData" .= parameters (gdProtocolParameters gd)
    , "k" .= show (unBlockCount (gdK gd))
    , "protocolMagic" .= show (unProtocolMagicId (gdProtocolMagicId gd))
    , "avvmDistr" .= M.size (unGenesisAvvmBalances (gdAvvmDistr gd))
    ]
  where
    delegation k (c :: Certificate) =
      object
        [ "key" .= hex (abstractHashToBytes (unKeyHash k))
        , "omega" .= show (getEpochNumber (epoch c))
        , "issuerPk" .= hex (CC.unXPub (unVerificationKey (issuerVK c)))
        , "delegatePk" .= hex (CC.unXPub (unVerificationKey (delegateVK c)))
        , "cert" .= let Signature s = signature c in hex (CC.unXSignature s)
        ]
    portion p = show (round (lovelacePortionToRational p * 1e15) :: Integer)
    parameters pp =
      object
        [ "scriptVersion" .= show (ppScriptVersion pp)
        , "slotDuration" .= show (ppSlotDuration pp)
        , "maxBlockSize" .= show (ppMaxBlockSize pp)
        , "maxHeaderSize" .= show (ppMaxHeaderSize pp)
        , "maxTxSize" .= show (ppMaxTxSize pp)
        , "maxProposalSize" .= show (ppMaxProposalSize pp)
        , "mpcThd" .= portion (ppMpcThd pp)
        , "heavyDelThd" .= portion (ppHeavyDelThd pp)
        , "updateVoteThd" .= portion (ppUpdateVoteThd pp)
        , "updateProposalThd" .= portion (ppUpdateProposalThd pp)
        , "updateImplicit" .= show (unSlotNumber (ppUpdateProposalTTL pp))
        , "softforkRule"
            .= let r = ppSoftforkRule pp
                in object ["initThd" .= portion (srInitThd r), "minThd" .= portion (srMinThd r), "thdDecrement" .= portion (srThdDecrement r)]
        , "txFeePolicy"
            .= let TxFeePolicyTxSizeLinear (TxSizeLinear a b) = ppTxFeePolicy pp
                in object ["summand" .= show (lovelaceToInteger a), "multiplierNumerator" .= show (numerator b), "multiplierDenominator" .= show (denominator b)]
        , "unlockStakeEpoch" .= show (getEpochNumber (ppUnlockStakeEpoch pp))
        ]

main :: IO ()
main = do
  args <- getArgs
  cases <- case args of
    [p] -> (LBS.readFile p >>= either die pure . eitherDecode) :: IO [Case]
    _ -> die "one input JSON path required"
  LBS.putStr (encode (map run cases))
  where
    -- `readGenesisData` (Genesis/Data.hs:169-181): `parseCanonicalJSON`, then `fromJSON`, and the
    -- hash `hashRaw` of `renderCanonicalJSON` of what was parsed
    run (Case n bytes) = case parseCanonicalJSON (LBS.fromStrict bytes) of
      Left err -> object ["name" .= n, "accepted" .= False, "failure" .= err, "hash" .= (Nothing :: Maybe T.Text)]
      Right json ->
        let h = "hash" .= Just (hex (hashToBytes (hashRaw (renderCanonicalJSON json))))
         in case fromJSON json :: Either SchemaError GenesisData of
              Left err -> object ["name" .= n, "accepted" .= False, "failure" .= show err, h]
              Right gd -> object ["name" .= n, "accepted" .= True, "result" .= project gd, h]
