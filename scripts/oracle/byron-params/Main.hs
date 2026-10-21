-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings #-}
module Main where

import Cardano.Chain.Update
import Cardano.Chain.Common
import Cardano.Ledger.Binary (decodeFull', byronProtVer)
import Data.Aeson
import Data.Aeson.Types (parseEither)
import Data.Bifunctor (first)
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.List (unfoldr)
import Data.Ratio (numerator, denominator)
import Numeric (readHex)
import Numeric.Natural (Natural)
import System.Environment

data ObserverError = HexByte | OddHex | UnknownField Int | UnknownKind String | InvalidInput | InvalidOutput | JsonInput String deriving Show

hexBytes :: String -> Either ObserverError BS.ByteString
hexBytes input
 | odd (length input) = Left OddHex
 | otherwise = BS.pack <$> traverse byte pairs
 where
  pairs=unfoldr (\rest -> case rest of a:b:xs -> Just ([a,b],xs); _ -> Nothing) input
  byte pair=case readHex pair of [(n,"")] -> Right n; _ -> Left HexByte

errorJSON :: LovelaceError -> Value
errorJSON e = object $ ["nativeError" .= show e] ++ case e of
 LovelaceOverflow a -> ["constructor" .= ("LovelaceOverflow"::String),"operands" .= [toInteger a]]
 LovelaceTooLarge a -> ["constructor" .= ("LovelaceTooLarge"::String),"operands" .= [a]]
 LovelaceTooSmall a -> ["constructor" .= ("LovelaceTooSmall"::String),"operands" .= [a]]
 LovelaceUnderflow a b -> ["constructor" .= ("LovelaceUnderflow"::String),"operands" .= [toInteger a,toInteger b]]

feeJSON :: TxSizeLinear -> Natural -> Value
feeJSON policy size = object ["size" .= size,"result" .= case calculateTxSizeLinear policy size of
 Left err -> object ["accepted" .= False,"error" .= errorJSON err]
 Right fee -> object ["accepted" .= True,"lovelace" .= unsafeGetLovelace fee]]

observe :: Value -> Either ObserverError Value
observe (Object original) = do
 kind <- first JsonInput $ parseEither (.: "kind") original
 encoded <- first JsonInput $ parseEither (.: "cbor") original
 raw <- hexBytes encoded
 fields <- case (kind::String) of
  "parameters" -> do
   field <- first JsonInput $ parseEither (.: "field") original
   case decodeFull' byronProtVer raw of
    Left err -> pure $ object ["accepted" .= False,"nativeError" .= show err]
    Right p -> do
     value <- case (field::Int) of
      1 -> Right $ ppuSlotDuration p
      2 -> Right $ ppuMaxBlockSize p
      3 -> Right $ ppuMaxHeaderSize p
      4 -> Right $ ppuMaxTxSize p
      5 -> Right $ ppuMaxProposalSize p
      _ -> Left (UnknownField field)
     pure $ object ["accepted" .= True,"naturalValue" .= value,"completeNativeValue" .= show p]
  "fee" -> do
   sizes <- first JsonInput (parseEither (.: "sizes") original :: Either String [Natural])
   pure $ case decodeFull' byronProtVer raw of
    Left err -> object ["accepted" .= False,"nativeError" .= show err]
    Right (TxFeePolicyTxSizeLinear policy@(TxSizeLinear a b)) -> object
     ["accepted" .= True,"summandLovelace" .= unsafeGetLovelace a,
      "multiplierNumerator" .= numerator b,"multiplierDenominator" .= denominator b,
      "fees" .= map (feeJSON policy) sizes,"completeNativeValue" .= show policy]
  _ -> Left (UnknownKind kind)
 case fields of
  Object observed -> pure $ Object (observed `KM.union` original)
  _ -> Left InvalidOutput
observe _ = Left InvalidInput

main :: IO ()
main = do
 [input,output] <- getArgs
 rows <- BL.lines <$> BL.readFile input
 values <- mapM (either fail pure . eitherDecode) rows
 results <- mapM (either (fail . show) pure . observe) values
 BL.writeFile output $ BL.unlines $ map encode results
