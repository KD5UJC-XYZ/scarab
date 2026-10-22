#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const {parseArgs} = require('node:util');
const {execFileSync} = require('node:child_process');
const {Octokit} = require('@octokit/rest');
const {prepare, HistoryScm, ROOT, REPOSITORY, TARGET, json, blob} = require('./coordinator.cjs');
const {GitHubPrScm} = require('./github-pr.cjs');

async function main() {
  const {values} = parseArgs({options: {source: {type: 'string'}, channel: {type: 'string'}, date: {type: 'string'},
    ordinal: {type: 'string'}, authority: {type: 'string', default: 'release-please-authority.json'},
    'expected-head': {type: 'string'}, maintenance: {type: 'boolean', default: false},
    'next-version': {type: 'string'}, 'override-reason': {type: 'string'},
    gh: {type: 'string'}, repo: {type: 'string', default: ROOT}, python: {type: 'string', default: 'python3'},
    output: {type: 'string'}, validate: {type: 'boolean', default: false}, write: {type: 'boolean', default: false}, help: {type: 'boolean', default: false}}});
  if (values.help) {
    console.log('Prepare a release-please PR or local preview: --source FULL40 --channel alpha|beta|preprod|prod --date YYYY-MM-DD --output NEWDIR [--ordinal N] [--write --expected-head FULL40]. Authority comes from reviewed control-plane files, not publication labels.');
    return;
  }
  if ((!values.output && !values.validate) || !/^(?:0|[1-9][0-9]*)$/.test(values.ordinal || '0')) throw new Error('require new output directory and canonical ordinal');
  if (path.isAbsolute(values.authority) || values.authority.split('/').some(part => part === '..') ||
    values.authority !== 'release-please-authority.json') throw new Error('unapproved authority path');
  const authorityPath = path.join(ROOT, values.authority);
  if (fs.lstatSync(authorityPath).isSymbolicLink()) throw new Error('authority cannot be a symlink');
  const authority = JSON.parse(fs.readFileSync(authorityPath));
  if (authority.schemaVersion !== 1) throw new Error('unsupported authority schema');
  const request = {repo: path.resolve(values.repo), repository: REPOSITORY, target: TARGET,
    source: values.source, channel: values.channel, date: values.date, authority,
    ordinal: values.channel === 'prod' ? null : Number(values.ordinal || '0'), expectedHead: values['expected-head'] || null,
    maintenance: values.maintenance, nextVersion: values['next-version'], overrideReason: values['override-reason'], python: values.python};
  if (authority.kind === 'published-stable' && (!values.gh || !path.isAbsolute(values.gh))) throw new Error('published authority requires the absolute pinned gh executable');
  request.verifyPublished = () => execFileSync(values.python, [path.join(ROOT, 'scripts/release_proposal_authority.py'),
    '--root', ROOT, '--authority', authorityPath, '--gh', values.gh || 'gh'], {encoding: 'utf8'});
  if (values.validate) {await prepare(request, new HistoryScm(request)); console.log('Approved inputs validated without PR writes'); return;}
  const token = process.env.RELEASE_PR_TOKEN;
  if (values.write && !token) throw new Error('GitHub App installation token required; GITHUB_TOKEN is never a fallback');
  const scm = values.write ? new GitHubPrScm(request, token, new Octokit({auth: token})) : new HistoryScm(request);
  const coordinator = await prepare(request, scm);
  const output = path.resolve(values.output);fs.mkdirSync(output, {recursive: false});
  console.log(json(await execute(coordinator, request, output, values.write)));

}
async function execute(coordinator, request, output, write) {
  let result;
  let complete = false;
  try {
    result = write ? await coordinator.manifest.createPullRequests() : await coordinator.manifest.buildPullRequests();
    if (!coordinator.getReceipt()) throw new Error('release-please produced no proposal');
    complete = true;
  } finally {
    const receipt = coordinator.getReceipt();
    if (receipt) {
      fs.writeFileSync(path.join(output, 'proposal.json'), json(receipt));
      fs.writeFileSync(path.join(output, 'patch.json'), json(coordinator.getUpdates().map(update => ({path: update.path,
        content: update.updater.updateContent(blob(request.repo, request.source, update.path))}))));
    }
    const summary = {mode: write ? 'pr-only' : 'preview', state: complete ? 'prepared' : 'incomplete',
      version: receipt ? receipt.version : null, source: request.source,
      pullRequests: complete && write ? result.map(pr => pr.number) : [], publication: 'not-performed'};
    fs.writeFileSync(path.join(output, 'result.json'), json(summary));
  }
  return JSON.parse(fs.readFileSync(path.join(output, 'result.json')));
}
module.exports = {execute};
if (require.main === module) main().catch(error => {console.error(error.message);process.exitCode = 1;});
