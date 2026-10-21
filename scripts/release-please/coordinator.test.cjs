// SPDX-License-Identifier: Apache-2.0
'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {execFileSync} = require('node:child_process');
const {prepare, HistoryScm, git, blob, json, PROPOSAL, MANIFEST, REPOSITORY, TARGET} = require('./coordinator.cjs');

function fixture(t, baselineVersion = '0.1.0') {
  const repo = fs.mkdtempSync(path.join(os.tmpdir(), 'scarab-release-please-'));
  t.after(() => fs.rmSync(repo, {recursive: true, force: true}));
  git(repo, 'init', '-q', '-b', 'main'); git(repo, 'config', 'user.name', 'Proposal fixture');
  git(repo, 'config', 'user.email', 'fixture@example.invalid');
  const put = (file, content) => {fs.mkdirSync(path.dirname(path.join(repo, file)), {recursive: true}); fs.writeFileSync(path.join(repo, file), content);};
  put('CHANGELOG.md', '# Changelog\n\n## [Unreleased]\n\nFragments supply entries.\n\n### Added (legacy)\n\n- Retain legacy words.\n\n## [0.1.0] - 2026-10-01\n\n### Added\n\n- Prior history.\n');
  put('release-please-config.json', fs.readFileSync(path.join(__dirname, '../../release-please-config.json'), 'utf8'));
  put('changes/README.md', 'Fragment instructions.\n'); put(MANIFEST, json({'.': baselineVersion}));
  function save(message) {git(repo, 'add', '.'); git(repo, 'commit', '-qm', message); return git(repo, 'rev-parse', 'HEAD');}
  const base = save('chore: fixture baseline');
  put('changes/2-feature.md', '### Added (feature)\n\n- Exact feature prose.\n');
  const source = save('feat: add feature');
  const request = {repo, source, authority: {kind: 'bootstrap', source: base, version: baselineVersion, reason: 'Unpublished fixture planning baseline'},
    repository: REPOSITORY, target: TARGET, channel: 'prod', ordinal: null, date: '2026-10-21', expectedHead: null,
    python: process.env.SCARAB_RELEASE_PYTHON || 'python3'};
  return {repo, put, save, base, request};
}

class MemoryScm extends HistoryScm {
  constructor(request, state) {super(request); this.state = state || {open: null, next: 1, writes: 0};}
  async *pullRequestIterator(_target, status) {if (status === 'OPEN' && this.state.open) yield this.state.open;}
  async createPullRequest(pr, _target, _message, updates) {return this.store(pr, updates);}
  async updatePullRequest(number, pr) {assert.equal(number, this.state.open.number); return this.store(pr, pr.updates);}
  store(pr, updates) {
    const old = this.state.open;
    assert.equal(this.request.expectedHead, old ? old.headSha : null, 'expected-head lease');
    git(this.request.repo, 'checkout', '-q', '-B', 'fixture-preparation', this.request.source);
    for (const update of updates) {
      const value = update.updater.updateContent(blob(this.request.repo, this.request.source, update.path));
      const dest = path.join(this.request.repo, update.path);
      if (value === null) fs.rmSync(dest); else {fs.mkdirSync(path.dirname(dest), {recursive: true});fs.writeFileSync(dest, value);}
    }
    git(this.request.repo, 'add', '.');git(this.request.repo, 'commit', '-qm', 'chore: reviewed release proposal');
    const headSha = git(this.request.repo, 'rev-parse', 'HEAD');
    this.state.open = {...pr, number: old ? old.number : this.state.next++, headBranchName: pr.headBranchName || pr.headRefName,
      body: pr.body.toString(), title: pr.title.toString(), headSha};
    this.state.writes++;
    return this.state.open;
  }
}

async function propose(request, state) {
  const scm = new MemoryScm(request, state);
  const coordinator = await prepare(request, scm);
  const result = await coordinator.manifest.createPullRequests();
  return {scm, result, receipt: coordinator.getReceipt()};
}

test('actual release-please updates one open PR for arrivals, then proposes a second stable release', async t => {
  const f = fixture(t); let first = await propose(f.request);
  assert.equal(first.result.length, 1); assert.equal(first.receipt.version, '0.2.0');
  assert.equal(first.receipt.state, 'proposed');
  assert.equal(blob(f.repo, first.scm.state.open.headSha, 'changes/2-feature.md'), undefined);
  assert.match(blob(f.repo, first.scm.state.open.headSha, 'CHANGELOG.md'), /Exact feature prose/);
  const loaded = JSON.parse(execFileSync(f.request.python, [path.join(__dirname, '../release_proposal.py'), '--repo', f.repo,
    '--cut', first.scm.state.open.headSha], {encoding: 'utf8'}));
  assert.equal(loaded.version, '0.2.0');
  git(f.repo, 'checkout', '-q', 'main');
  f.put('changes/3-arrival.md', '### Fixed\n\n- Arrived while the PR was open.\n');
  f.request.source = f.save('fix: arrive during review');f.request.expectedHead = first.scm.state.open.headSha;
  const updated = await propose(f.request, first.scm.state);
  assert.equal(updated.scm.state.open.number, first.result[0].number);
  assert.equal(updated.scm.state.writes, 2);assert.equal(updated.receipt.fragmentPlan.inputs.length, 2);
  assert.match(blob(f.repo, updated.scm.state.open.headSha, 'CHANGELOG.md'), /Arrived while the PR was open/);
  f.request.expectedHead = updated.scm.state.open.headSha;
  const repeated = await propose(f.request, updated.scm.state);
  assert.equal(repeated.result.length, 0);assert.equal(repeated.scm.state.writes, 2);
  const published = updated.scm.state.open.headSha;
  git(f.repo, 'checkout', '-q', 'main');git(f.repo, 'merge', '--ff-only', published);
  f.put('changes/4-next.md', '### Fixed\n\n- A second release.\n');
  f.request.source = f.save('fix: next release');
  f.request.authority = {kind: 'published-stable', source: published, version: '0.2.0', verification: {fixture: 'test verification seam; no publication performed'}};
  f.request.verifyPublished = authority => assert.equal(authority.source, published);
  f.request.expectedHead = null;
  const second = await propose(f.request);
  assert.equal(second.receipt.version, '0.2.1');assert.equal(second.receipt.fragmentPlan.inputs.length, 1);
  const changelog = blob(f.repo, second.scm.state.open.headSha, 'CHANGELOG.md');
  assert.equal(changelog.match(/## \[0\.2\.0\]/g).length, 1);assert.equal(changelog.match(/Retain legacy words/g).length, 1);
  assert.match(changelog, /A second release/);
});

test('actual conventional-commit strategy enforces pre-1.0 policy and all four channel suffixes', async t => {
  for (const [message, version] of [['fix: repaired', '0.1.1'], ['feat: compatible feature', '0.2.0'], ['feat!: incompatible', '0.2.0']]) {
    for (const [channel, suffix] of [['alpha', '-alpha.0'], ['beta', '-beta.0'], ['preprod', '-rc.0'], ['prod', '']]) {
      const f = fixture(t);git(f.repo, 'reset', '--soft', f.base);f.request.source = f.save(message);
      f.request.channel = channel;f.request.ordinal = channel === 'prod' ? null : 0;
      const {manifest, getReceipt} = await prepare(f.request, new HistoryScm(f.request));
      const proposals = await manifest.buildPullRequests();
      assert.equal(proposals[0].version.toString(), version + suffix);
      const receipt = getReceipt();assert.equal(receipt.channel, channel);
      if (channel !== 'prod') {
        assert.equal(Object.hasOwn(receipt.generatedFiles, 'changes/2-feature.md'), false);
        assert.equal(Object.hasOwn(receipt.generatedFiles, 'CHANGELOG.md'), false);
      }
    }
  }
});

test('missing authority, bad channel, maintenance ambiguity and Release-As overrides refuse before PR writes', async t => {
  const f = fixture(t);
  for (const change of [{channel: 'preprod', ordinal: null}, {target: 'release/prod'},
    {authority: {...f.request.authority, kind: 'published-stable'}}, {authority: {...f.request.authority, reason: ''}}]) {
    await assert.rejects(() => prepare({...f.request, ...change}, new HistoryScm(f.request)));
  }
  git(f.repo, 'reset', '--soft', f.base);f.request.source = f.save('chore: maintenance');
  let coordinator = await prepare(f.request, new HistoryScm(f.request));
  await assert.rejects(() => coordinator.manifest.buildPullRequests(), /maintenance/);
  coordinator = await prepare({...f.request, maintenance: true}, new HistoryScm(f.request));
  assert.equal((await coordinator.manifest.buildPullRequests())[0].version.toString(), '0.1.1');
  git(f.repo, 'reset', '--soft', f.base);f.request.source = f.save('fix: sneaky\n\nRelease-As: 9.0.0');
  coordinator = await prepare(f.request, new HistoryScm(f.request));
  await assert.rejects(() => coordinator.manifest.buildPullRequests(), /Release-As/);
});

test('pending stable consolidation remains visible to RC, blocks another stable, and restoration keeps version reserved', async t => {
  const f = fixture(t);const first = await propose(f.request);
  const prepared = first.scm.state.open.headSha;
  git(f.repo, 'checkout', '-q', 'main');git(f.repo, 'merge', '--ff-only', prepared);
  f.put('changes/3-later.md', '### Fixed\n\n- Later unpublished work.\n');
  f.request.source = f.save('fix: later unpublished work');f.request.expectedHead = null;
  await assert.rejects(() => prepare({...f.request, authority: {...f.request.authority, source: prepared, version: '0.2.0'}},
    new HistoryScm(f.request)), /bootstrap cannot account/);
  let coordinator = await prepare(f.request, new HistoryScm(f.request));
  await assert.rejects(() => coordinator.manifest.buildPullRequests(), /Command failed/);
  coordinator = await prepare({...f.request, channel: 'preprod', ordinal: 1}, new HistoryScm(f.request));
  assert.equal((await coordinator.manifest.buildPullRequests())[0].version.toString(), '0.2.0-rc.1');
  assert.equal(coordinator.getReceipt().fragmentPlan.inputs.length, 2);
  const python = f.request.python;
  execFileSync(python, [path.join(__dirname, '../release_fragments.py'), '--repo', f.repo,
    'restore', '.release/changelog/0.2.0.json', '--baseline', f.base]);
  f.request.source = f.save('chore: restore abandoned preparation');
  coordinator = await prepare(f.request, new HistoryScm(f.request));
  await assert.rejects(() => coordinator.manifest.buildPullRequests(), /Command failed/);
  coordinator = await prepare({...f.request, nextVersion: '0.2.1', overrideReason: 'Reserved abandoned0.2.0; reviewed recovery'}, new HistoryScm(f.request));
  assert.equal((await coordinator.manifest.buildPullRequests())[0].version.toString(), '0.2.1');
  assert.equal(coordinator.getReceipt().nextVersion, '0.2.1');
});

test('reviewed recovery override cannot lower a conventional minor proposal or change channel', async t => {
  const f = fixture(t);
  for (const change of [{nextVersion: '0.1.9', overrideReason: 'too low'}, {nextVersion: '0.2.1'},
    {nextVersion: '0.2.1-beta.1', overrideReason: 'wrong channel'}, {nextVersion: '0.02.1', overrideReason: 'noncanonical'}]) {
    const coordinator = await prepare({...f.request, ...change}, new HistoryScm(f.request));
    await assert.rejects(() => coordinator.manifest.buildPullRequests());
  }
  const coordinator = await prepare({...f.request, nextVersion: '0.2.1', overrideReason: 'Reviewed reserved-version recovery'}, new HistoryScm(f.request));
  assert.equal((await coordinator.manifest.buildPullRequests())[0].version.toString(), '0.2.1');
});


test('actual versioning strategy advances the major for breaking changes after1.0', async t => {
  for (const [message, version] of [['fix: repaired', '1.2.4'], ['feat: compatible feature', '1.3.0'], ['feat!: incompatible', '2.0.0']]) {
    const f = fixture(t, '1.2.3');git(f.repo, 'reset', '--soft', f.base);f.request.source = f.save(message);
    const coordinator = await prepare(f.request, new HistoryScm(f.request));
    assert.equal((await coordinator.manifest.buildPullRequests())[0].version.toString(), version);
  }
});

test('malformed fragment fails the actual collector before any PR update', async t => {
  const f = fixture(t);f.put('changes/3-invalid.md', 'Unstructured prose must not silently disappear.\n');
  f.request.source = f.save('fix: invalid news');const scm = new MemoryScm(f.request);
  const coordinator = await prepare(f.request, scm);
  await assert.rejects(() => coordinator.manifest.createPullRequests(), /Command failed/);
  assert.equal(scm.state.writes, 0);assert.equal(scm.state.open, null);
});


test('a PR transport failure retains the exact generated patch and an incomplete result', async t => {
  const f = fixture(t);const scm = new MemoryScm(f.request);
  scm.createPullRequest = async () => {throw new Error('injected PR transport failure after proposal generation');};
  const coordinator = await prepare(f.request, scm);
  const output = fs.mkdtempSync(path.join(os.tmpdir(), 'scarab-proposal-evidence-'));
  t.after(() => fs.rmSync(output, {recursive: true, force: true}));
  const {execute} = require('./run.cjs');
  await assert.rejects(() => execute(coordinator, f.request, output, true), /injected PR transport failure/);
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(output, 'proposal.json'))), coordinator.getReceipt());
  const patch = JSON.parse(fs.readFileSync(path.join(output, 'patch.json')));
  assert.equal(patch.find(change => change.path === 'changes/2-feature.md').content, null);
  const summary = JSON.parse(fs.readFileSync(path.join(output, 'result.json')));
  assert.equal(summary.state, 'incomplete');assert.equal(summary.publication, 'not-performed');
  assert.deepEqual(summary.pullRequests, []);
});
