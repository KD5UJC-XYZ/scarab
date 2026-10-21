#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');
const rp = require('release-please');
const {Version} = require('release-please/build/src/version');
const {buildVersioningStrategy} = require('release-please/build/src/factories/versioning-strategy-factory');

const ROOT = path.resolve(__dirname, '../..');
const POLICY = process.env.SCARAB_PROMOTION_POLICY || 'transition';
if (!['transition', 'dev-only-main'].includes(POLICY)) throw new Error('unknown promotion policy');
const TARGET = POLICY === 'transition' ? 'main' : 'dev';
const REPOSITORY = 'KD5UJC-XYZ/scarab';
const CHANNELS = {alpha: 'alpha', beta: 'beta', preprod: 'rc', prod: null};
const PROPOSAL = '.release/proposal.json';
const MANIFEST = '.release-please-manifest.json';
const sha256 = data => crypto.createHash('sha256').update(data).digest('hex');
const json = value => JSON.stringify(value, null, 2) + '\n';
function requireThat(condition, message) { if (!condition) throw new Error(message); }
function git(repo, ...args) { return execFileSync('git', ['-C', repo, ...args], {encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe']}).trimEnd(); }
function blob(repo, source, file) {
  const exists = git(repo, 'ls-tree', source, '--', file);
  return exists ? execFileSync('git', ['-C', repo, 'show', `${source}:${file}`], {encoding: 'utf8'}) : undefined;
}
function fullCommit(repo, value) {
  requireThat(typeof value === 'string' && /^[0-9a-f]{40}$/.test(value), 'require full lowercase commit SHA');
  requireThat(git(repo, 'rev-parse', `${value}^{commit}`) === value, 'not an exact commit');
}
function ancestor(repo, older, newer) { git(repo, 'merge-base', '--is-ancestor', older, newer); }
function stableVersion(value) {
  const version = Version.parse(value);
  requireThat(version.toString() === value && !version.preRelease && !version.build, 'baseline requires canonical stable version');
  return version;
}
function render(request, version) {
  return JSON.parse(execFileSync(request.python || 'python3', [path.join(ROOT, 'scripts/release_proposal_fragments.py'),
    '--repo', request.repo, '--source', request.source, '--baseline', request.authority.source,
    '--version', version, '--date', request.date], {encoding: 'utf8', maxBuffer: 16 * 1024 * 1024}));
}
function validateRequest(request) {
  requireThat(request.repository === REPOSITORY && request.target === TARGET, 'unapproved repository or integration target');
  requireThat(Object.hasOwn(CHANNELS, request.channel), 'unknown release channel');
  requireThat(request.channel === 'prod' ? request.ordinal === null : Number.isSafeInteger(request.ordinal) && request.ordinal >= 0,
    'prerelease ordinal must be a canonical nonnegative safe integer; production has none');
  fullCommit(request.repo, request.source);
  fullCommit(request.repo, request.authority.source);
  ancestor(request.repo, request.authority.source, request.source);
  stableVersion(request.authority.version);
  const marker = blob(request.repo, request.authority.source, MANIFEST);
  requireThat(JSON.parse(marker === undefined && request.authority.kind === 'bootstrap'
    ? JSON.stringify({'.': JSON.parse(fs.readFileSync(path.join(ROOT, 'release-please-config.json')))['initial-version']}) : marker)['.'] === request.authority.version,
    'baseline planning marker and approved version disagree');
  requireThat(request.authority.kind === 'bootstrap' || request.authority.kind === 'published-stable', 'unknown baseline authority');
  requireThat(request.authority.kind !== 'bootstrap' ||
    (typeof request.authority.reason === 'string' && request.authority.reason.trim().length > 0), 'bootstrap requires reviewed nonpublication rationale');
  if (request.authority.kind === 'published-stable') {
    requireThat(request.authority.verification, 'published baseline needs independent authenticated verification');
    requireThat(request.verifyPublished, 'published baseline verifier must be provided by the trusted control plane');
    request.verifyPublished(request.authority);
  } else {
    requireThat(!git(request.repo, 'ls-tree', '-r', '--name-only', request.authority.source, '--', '.release/changelog', '.release/proposals'),
      'bootstrap cannot account for previous release preparations');
  }
  requireThat(request.expectedHead === null || /^[0-9a-f]{40}$/.test(request.expectedHead), 'expected PR head must be full SHA or null');
}
function commits(request) {
  return git(request.repo, 'rev-list', '--first-parent', `${request.authority.source}..${request.source}`)
    .split('\n').filter(Boolean).map(sha => ({sha, message: git(request.repo, 'show', '-s', '--format=%B', sha),
      files: git(request.repo, 'diff-tree', '--root', '--no-commit-id', '--name-only', '-r', sha).split('\n').filter(Boolean)}));
}
function updater(beforeSha256, content) {
  return {updateContent(before) {
    requireThat((before === undefined ? null : sha256(before)) === beforeSha256, 'frozen file preimage changed');
    return content;
  }};
}

async function prepare(request, scm) {
  validateRequest(request);
  requireThat(rp.VERSION === '17.11.2', 'unexpected release-please version');
  const configBytes = fs.readFileSync(path.join(ROOT, 'release-please-config.json'));
  const config = JSON.parse(configBytes);
  requireThat(blob(request.repo, request.source, 'release-please-config.json') === configBytes.toString(),
    'candidate config differs from trusted control plane; rerun from reviewed integration');
  requireThat(config['skip-github-release'] === true && config['skip-changelog'] === true &&
    config['bump-minor-pre-major'] === true && config['bump-patch-for-minor-pre-major'] === false,
    'PR-only or pre-1.0 policy changed');
  requireThat(config['release-type'] === 'simple' && config['version-file'] === MANIFEST &&
    config.versioning === 'scarab-channels' && config['changelog-type'] === 'scarab-fragments' &&
    JSON.stringify(config.plugins) === JSON.stringify(['scarab-fragments']) &&
    JSON.stringify(config.packages) === JSON.stringify({'.': {}}) && !config['release-as'],
    'unsupported alternate proposal/rendering path');
  const history = commits(request);
  requireThat(history.length > 0, 'no commits after approved baseline');
  let frozen;
  let receipt;
  let updates;
  rp.registerVersioningStrategy('scarab-channels', options => {
    const standard = buildVersioningStrategy({...options, type: 'default'});
    return {bump(previous, changes) {
      requireThat(!changes.some(c => c.notes.some(note => note.title === 'RELEASE AS')), 'Release-As trailers require separate reviewed policy; not supported');
      const meaningful = changes.some(c => c.breaking || ['feat', 'feature', 'fix'].includes(c.type));
      requireThat(meaningful || request.maintenance === true, 'maintenance-only proposal requires explicit review');
      const next = standard.bump(previous, changes);
      const automatic = new Version(next.major, next.minor, next.patch,
        request.channel === 'prod' ? undefined : `${CHANNELS[request.channel]}.${request.ordinal}`);
      if (!request.nextVersion) return automatic;
      requireThat(typeof request.overrideReason === 'string' && request.overrideReason.trim(), 'version override needs explicit review rationale');
      const selected = Version.parse(request.nextVersion);
      requireThat(selected.toString() === request.nextVersion && !selected.build &&
        selected.preRelease === automatic.preRelease, 'version override must be canonical and match selected channel/ordinal');
      const components = v => [v.major, v.minor, v.patch];
      const first = components(selected).findIndex((value, index) => value !== components(automatic)[index]);
      requireThat(first === -1 || components(selected)[first] > components(automatic)[first], 'override cannot lower conventional proposal');
      return selected;
    }};
  });
  rp.registerChangelogNotes('scarab-fragments', () => ({async buildNotes(_changes, options) {
    frozen = render(request, options.version);
    requireThat(frozen.plan.channel === request.channel, 'proposed version and selected channel disagree');
    receipt = {schemaVersion: 1, state: 'proposed', tool: `release-please-${rp.VERSION}`, target: TARGET,
      repository: REPOSITORY, source: request.source, authority: request.authority, version: options.version,
      channel: request.channel, date: request.date, maintenance: request.maintenance === true,
      nextVersion: request.nextVersion || null, overrideReason: request.overrideReason || null, configSha256: sha256(configBytes),
      commits: history.map(c => ({sha: c.sha, messageSha256: sha256(c.message)})),
      fragmentPlan: frozen.plan, fragmentPlanSha256: frozen.planSha256,
      notesPath: frozen.notesPath, notesSha256: sha256(frozen.files[frozen.notesPath].content)};
    return `## [${options.version}]\n\nHuman-written notes: [${frozen.notesPath}](https://github.com/${REPOSITORY}/blob/release-please--branches--${TARGET}/${frozen.notesPath}).\n\nSource: ${request.source}. Baseline kind: ${request.authority.kind}. Preparation only; no tag or publication.`;
  }}));
  rp.registerPlugin('scarab-fragments', () => ({
    processCommits(changes) {
      requireThat(!changes.some(c => c.notes.some(note => note.title === 'RELEASE AS')), 'Release-As trailers require separate reviewed policy; not supported');
      return changes;
    },
    async preconfigure(strategies) { return strategies; },
    async run(candidates) {
      requireThat(candidates.length === 1 && frozen && receipt, 'require one coordinated root proposal');
      const candidate = candidates[0];
      const selectedVersion = candidate.pullRequest.version.toString();
      const versioned = `.release/proposals/${selectedVersion}.json`;
      requireThat(blob(request.repo, request.source, versioned) === undefined, 'proposal version already reserved');
      const files = {...frozen.files};
      files[MANIFEST] = {beforeSha256: sha256(blob(request.repo, request.source, MANIFEST)), content: json({'.': selectedVersion})};
      receipt.generatedFiles = Object.fromEntries(Object.entries(files).map(([file, value]) => [file, value.content === null ? null : sha256(value.content)]));
      receipt.pullRequestTitle = candidate.pullRequest.title.toString();
      receipt.pullRequestBodySha256 = sha256(candidate.pullRequest.body.toString());
      const bytes = json(receipt);
      for (const file of [PROPOSAL, versioned]) {
        const before = blob(request.repo, request.source, file);
        files[file] = {beforeSha256: before === undefined ? null : sha256(before), content: bytes};
      }
      updates = Object.entries(files).map(([file, value]) => ({path: file, createIfMissing: true,
        updater: updater(value.beforeSha256, value.content)}));
      candidate.pullRequest.updates = updates;
      return candidates;
    }
  }));
  const manifest = await rp.Manifest.fromManifest(scm, TARGET, 'release-please-config.json', MANIFEST,
    {lastReleaseSha: request.authority.source, bootstrapSha: request.authority.source,
      separatePullRequests: true, alwaysUpdate: false,
      labels: ['autorelease: pending'], logger: {info() {}, warn() {}, debug() {}, error() {}}});
  return {manifest, getReceipt: () => receipt, getUpdates: () => updates};
}

class HistoryScm {
  constructor(request) { this.request = request; this.repository = {owner: 'KD5UJC-XYZ', repo: 'scarab', defaultBranch: TARGET}; }
  async getFileJson(file) {
    if (file === 'release-please-config.json') return JSON.parse(fs.readFileSync(path.join(ROOT, file)));
    if (file === MANIFEST) return {'.': this.request.authority.version};
    throw new Error('unapproved control-plane config input');
  }
  async *releaseIterator() {}
  async *tagIterator() {}
  async *mergeCommitIterator() { for (const commit of commits(this.request)) yield commit; }
  async *pullRequestIterator() {}
  async createRelease() { throw new Error('publication is forbidden in PR preparation'); }
  async createFileOnNewBranch() { throw new Error('additional overflow branch is forbidden'); }
}

module.exports = {prepare, validateRequest, HistoryScm, sha256, json, git, blob, PROPOSAL, MANIFEST, ROOT, REPOSITORY, TARGET};
