# SPDX-License-Identifier: Apache-2.0
# The driver of the writeBits canary (GHSA-vfw2-hhrc-87v5): the trial's driver with the canary's two policies, made by
# writebits_script.py, the protocol-11 PlutusV3 cost model that prices it, the setup that enacts that model, and its
# first command.
FROM scarab-at-driver:trial
RUN mkdir -p /opt/at/canary
COPY --chmod=0644 writebits-4096.plutus writebits-4097.plutus plutus-v3-cost-model.json /opt/at/canary/
COPY --chmod=0755 setup_cost_model /opt/at/canary/setup_cost_model
COPY --chmod=0755 first_submit_writebits /opt/antithesis/test/v1/main/first_submit_writebits
