// SPDX-License-Identifier: Apache-2.0
'use strict';
const {test} = require('node:test');
const {execFileSync} = require('node:child_process');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {prepare, HistoryScm, git, blob, sha256, json, REPOSITORY, TARGET, PROPOSAL} = require('./coordinator.cjs');
const {GitHubPrScm, commitUpdates, verifyGeneratedHead, pushWithLease, requireLiveTagAbsent, BRANCH} = require('./github-pr.cjs');

test('real Git lease rejects a concurrently changed PR head and refuses manual edits', async t => {
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'scarab-proposal-lease-'));
  t.after(() => fs.rmSync(scratch, {recursive: true, force: true}));
  const repo = path.join(scratch, 'repo');fs.mkdirSync(repo);
  const remote = path.join(scratch, 'remote.git');git(scratch, 'init', '-q', '--bare', remote);
  git(repo, 'init', '-q');git(repo, 'config', 'user.name', 'Fixture');git(repo, 'config', 'user.email', 'fixture@example.invalid');
  fs.mkdirSync(path.join(repo, 'changes'));fs.writeFileSync(path.join(repo, 'changes/README.md'), 'Readme');
  fs.writeFileSync(path.join(repo, 'CHANGELOG.md'), '# Changelog\n\n## [Unreleased]\n\nFragments.\n\n## [0.1.0] - 2026-10-01\n\n### Added\n\n- History.\n');
  fs.writeFileSync(path.join(repo, '.release-please-manifest.json'), json({'.': '0.1.0'}));
  fs.writeFileSync(path.join(repo, 'release-please-config.json'), fs.readFileSync(path.join(__dirname, '../../release-please-config.json')));
  git(repo, 'add', '.');git(repo, 'commit', '-qm', 'chore: baseline');const base = git(repo, 'rev-parse', 'HEAD');
  // The reviewed authority file lives in the source, so it names the baseline commit before it.
  const authority = {schemaVersion: 1, kind: 'bootstrap', source: base, version: '0.1.0', reason: 'Unpublished test fixture'};
  fs.writeFileSync(path.join(repo, 'release-please-authority.json'), json(authority));
  fs.writeFileSync(path.join(repo, 'changes/1-fix.md'), '### Fixed\n\n- A fix.\n');git(repo, 'add', '.');git(repo, 'commit', '-qm', 'fix: repaired');
  const source = git(repo, 'rev-parse', 'HEAD');
  const request = {repo, source, authority,
    repository: REPOSITORY, target: TARGET, channel: 'prod', ordinal: null, date: '2026-10-21', expectedHead: null,
    python: process.env.SCARAB_RELEASE_PYTHON || 'python3'};
  const coordinator = await prepare(request, new HistoryScm(request));
  const proposal = (await coordinator.manifest.buildPullRequests())[0];
  const head = commitUpdates(request, proposal.title.toString(), proposal.updates);
  assert.equal(verifyGeneratedHead(repo, head).version, '0.1.1');
  const python = request.python;
  const loader = path.join(__dirname, '../release_proposal.py');
  assert.equal(JSON.parse(execFileSync(python, [loader, '--repo', repo, '--cut', head], {encoding: 'utf8'})).version, '0.1.1');
  git(repo, 'checkout', '-q', head);
  fs.appendFileSync(path.join(repo, '.release/changelog/0.1.1.md'), 'Unreviewed manual notes.\n');
  git(repo, 'add', '.');git(repo, 'commit', '--amend', '--no-edit', '-q');
  const altered = git(repo, 'rev-parse', 'HEAD');
  assert.throws(() => verifyGeneratedHead(repo, altered), /manual generated-file/);
  assert.throws(() => execFileSync(python, [loader, '--repo', repo, '--cut', altered], {stdio: 'pipe'}));
  git(repo, 'reset', '--hard', head);
  pushWithLease(repo, remote, BRANCH, head, null);
  // A human adds a commit while automation is preparing its update.
  git(repo, 'checkout', '-q', head);fs.writeFileSync(path.join(repo, 'manual.txt'), 'retain human work');
  git(repo, 'add', '.');git(repo, 'commit', '-qm', 'docs: human edit');const manual = git(repo, 'rev-parse', 'HEAD');
  pushWithLease(repo, remote, BRANCH, manual, head);
  assert.throws(() => verifyGeneratedHead(repo, manual), /manual PR commits/);
  assert.throws(() => pushWithLease(repo, remote, BRANCH, head, head));
  assert.equal(git(scratch, 'ls-remote', remote, `refs/heads/${BRANCH}`).split('\t')[0], manual);
  assert.throws(() => pushWithLease(repo, remote, 'main', head, manual), /unapproved/);
});

test('PR writer fails closed without App token and refuses publication capability', async () => {
  const request = {repo: '.', repository: REPOSITORY};
  assert.throws(() => new GitHubPrScm(request, '', {}), /installation token/);
  await assert.rejects(() => new HistoryScm(request).createRelease(), /forbidden/);
  await assert.rejects(() => new HistoryScm(request).createFileOnNewBranch(), /forbidden/);
});


test('live exact-tag lookup accepts only404 absence and preserves other failures', async () => {
  const calls = [];
  const api = outcome => ({rest: {git: {getRef: async query => {calls.push(query);return outcome();}}}});
  await requireLiveTagAbsent(api(() => {throw Object.assign(new Error('missing'), {status: 404});}), '0.2.0-rc.1');
  assert.deepEqual(calls[0], {owner: 'KD5UJC-XYZ', repo: 'scarab', ref: 'tags/v0.2.0-rc.1'});
  await assert.rejects(() => requireLiveTagAbsent(api(() => ({data: {ref: 'refs/tags/v0.2.0'}})), '0.2.0'), /already reserved/);
  for (const status of [403, 500, undefined]) {
    const failure = Object.assign(new Error('do not treat this as absence'), {status});
    await assert.rejects(() => requireLiveTagAbsent(api(() => {throw failure;}), '0.2.0'), error => error === failure);
  }
});
