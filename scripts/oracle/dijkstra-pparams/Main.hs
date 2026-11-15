-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE PackageImports #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}

-- What cardano-ledger 9df9f98c (the revision cardano-node b004fe0c pins, the build of the public
-- Leios testnet) makes of a Dijkstra protocol-parameter update: each input line is a JSON object
-- with an `id` and an `update`, the hex of a `PParamsUpdate DijkstraEra`, which is decoded at
-- version 12 (`decodeFull'`); the output line has the `id` and either the decoder's refusal, as
-- `show` renders it, or `ppuWellFormed` of the update at protocol versions 12.0 and 10.0
-- (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/PParams.hs:1149-1175`) and its
-- `modifiedPPGroups` (`eras/conway/impl/src/Cardano/Ledger/Conway/PParams.hs:174-182`), each group
-- as its DRep group and its stake-pool group as `show` renders them.
module Main (main) where

import Cardano.Ledger.BaseTypes (ProtVer (..), natVersion)
import qualified Cardano.Ledger.Binary as Binary
import Cardano.Ledger.Conway.PParams (ConwayEraPParams (..), PPGroups (..))
import Cardano.Ledger.Core (PParamsUpdate)
import Cardano.Ledger.Dijkstra (DijkstraEra)
import Data.Aeson (Value, eitherDecode, encode, object, withObject, (.:), (.=))
import Data.Aeson.Types (parseEither)
import qualified Data.ByteString as BS
import qualified "base16-bytestring" Data.ByteString.Base16 as Hex
import qualified Data.ByteString.Lazy.Char8 as LBS
import qualified Data.Set as Set
import Data.Text (Text)
import qualified Data.Text.Encoding as T

readCase :: LBS.ByteString -> Either String (Text, BS.ByteString)
readCase line = do
  v <- eitherDecode line :: Either String Value
  (i, h) <- parseEither (withObject "case" (\o -> (,) <$> o .: "id" <*> o .: "update")) v
  bytes <- either (Left . show) Right (Hex.decode (T.encodeUtf8 h))
  pure (i, bytes)

verdict :: Text -> BS.ByteString -> Value
verdict i bytes =
  case Binary.decodeFull' (natVersion @12) bytes :: Either Binary.DecoderError (PParamsUpdate DijkstraEra) of
    Left e -> object ["id" .= i, "refused" .= show e]
    Right u ->
      object
        [ "id" .= i
        , "wellFormed12" .= ppuWellFormed (ProtVer (natVersion @12) 0) u
        , "wellFormed10" .= ppuWellFormed (ProtVer (natVersion @10) 0) u
        , "groups" .= [[show d, show s] | PPGroups d s <- Set.toList (modifiedPPGroups u)]
        ]

main :: IO ()
main = do
  input <- LBS.getContents
  mapM_
    ( \line -> case readCase line of
        Left e -> error e
        Right (i, bytes) -> LBS.putStrLn (encode (verdict i bytes))
    )
    (filter (not . LBS.null) (LBS.lines input))
