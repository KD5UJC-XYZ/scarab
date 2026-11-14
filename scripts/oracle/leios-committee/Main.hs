-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE PackageImports #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
module Main (main) where

import Cardano.Ledger.Binary (decCBOR, decodeFullDecoder, natVersion, serialize')
import Cardano.Ledger.Dijkstra (DijkstraEra)
import Cardano.Ledger.State (SetSnapShot (..), SnapShots (..), mkSetSnapShot)
import Data.Aeson (FromJSON (..), Value, eitherDecode, encode, object, withObject, (.:), (.=))
import qualified Data.ByteString as BS
import qualified "base16-bytestring" Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy.Char8 as LBS
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import System.Exit (die)

data Case = Case Text.Text Text.Text

instance FromJSON Case where
  parseJSON = withObject "case" $ \o -> Case <$> o .: "name" <*> o .: "cbor"

hex :: BS.ByteString -> Text.Text
hex = Text.decodeUtf8 . Hex.encode

-- The unchanged decoder of the epoch state's snapshots, whose mark snapshot memoizes its Leios
-- committee (mkMarkSnapShot), and the unchanged rotation of that mark into the set position
-- (mkSetSnapShot), which SNAP performs at the next epoch boundary.
result :: BS.ByteString -> Value
result raw = case decodeFullDecoder (natVersion @12) "SnapShots" decCBOR (LBS.fromStrict raw) of
  Left err -> object ["accepted" .= False, "failure" .= show err]
  Right (snapshots :: SnapShots DijkstraEra) ->
    let set = mkSetSnapShot (ssStakeMark snapshots)
     in object
          [ "accepted" .= True
          , "set" .= hex (serialize' (natVersion @12) set)
          , "committee" .= hex (serialize' (natVersion @12) (ssLeiosCommittee set))
          ]

run :: Case -> IO Value
run (Case name raw) = do
  bytes <- either die pure (Hex.decode (Text.encodeUtf8 raw))
  pure $ object ["name" .= name, "reference" .= result bytes]

main :: IO ()
main = LBS.getContents >>= mapM_ (\line -> either die run (eitherDecode line) >>= LBS.putStrLn . encode) . LBS.lines
