// SPDX-License-Identifier: Apache-2.0
'use strict';
// The live PR writer against a stubbed GitHub API and a local bare Git remote behind the fixed GitHub URL,
// so create, update and error paths run the real writer without network access, credentials or the real repository.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {prepare, git, blob, sha256, json, REPOSITORY, TARGET} = require('./coordinator.cjs');
const {GitHubPrScm, verifyGeneratedHead, BRANCH} = require('./github-pr.cjs');

function writerFixture(t) {
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'scarab-pr-writer-'));
  const previous = process.env.GIT_CONFIG_GLOBAL;
  t.after(() => {
    if (previous === undefined) delete process.env.GIT_CONFIG_GLOBAL; else process.env.GIT_CONFIG_GLOBAL = previous;
    fs.rmSync(scratch, {recursive: true, force: true});
  });
  const repo = path.join(scratch, 'repo');fs.mkdirSync(repo);
  const remote = path.join(scratch, 'remote.git');git(scratch, 'init', '-q', '--bare', remote);
  fs.writeFileSync(path.join(scratch, 'gitconfig'), `[url "${remote}"]\n\tinsteadOf = https://github.com/${REPOSITORY}.git\n`);
  process.env.GIT_CONFIG_GLOBAL = path.join(scratch, 'gitconfig');
  git(repo, 'init', '-q', '-b', 'main');git(repo, 'config', 'user.name', 'Fixture');git(repo, 'config', 'user.email', 'fixture@example.invalid');
  const put = (file, content) => {fs.mkdirSync(path.dirname(path.join(repo, file)), {recursive: true});fs.writeFileSync(path.join(repo, file), content);};
  const save = message => {git(repo, 'add', '.');git(repo, 'commit', '-qm', message);return git(repo, 'rev-parse', 'HEAD');};
  put('changes/README.md', 'Readme');
  put('CHANGELOG.md', '# Changelog\n\n## [Unreleased]\n\nFragments.\n\n## [0.1.0] - 2026-10-01\n\n### Added\n\n- History.\n');
  put('.release-please-manifest.json', json({'.': '0.1.0'}));
  put('release-please-config.json', fs.readFileSync(path.join(__dirname, '../../release-please-config.json'), 'utf8'));
  const base = save('chore: baseline');
  const authority = {schemaVersion: 1, kind: 'bootstrap', source: base, version: '0.1.0', reason: 'Unpublished test fixture'};
  put('release-please-authority.json', json(authority));
  put('changes/1-fix.md', '### Fixed\n\n- A fix.\n');
  const source = save('fix: repaired');
  git(repo, 'push', '-q', remote, `${source}:refs/heads/main`);
  const request = {repo, source, authority, repository: REPOSITORY, target: TARGET, channel: 'prod', ordinal: null,
    date: '2026-10-21', expectedHead: null, python: process.env.SCARAB_RELEASE_PYTHON || 'python3'};
  const calls = [];
  const state = {tags: new Set(), mainSha: source, open: [], failure: {}, tagStatus: null};
  const fail = name => {if (state.failure[name]) throw Object.assign(new Error(`stub ${name} failed`), {status: state.failure[name]});};
  const api = {
    paginate: async (_fn, query) => {calls.push(['paginate', query]);return state.open;},
    rest: {
      git: {getRef: async query => {
        calls.push(['getRef', query.ref]);
        if (query.ref.startsWith('tags/')) {
          if (state.tagStatus) throw Object.assign(new Error('stub tag lookup refused'), {status: state.tagStatus});
          if (state.tags.has(query.ref)) return {data: {ref: `refs/${query.ref}`}};
          throw Object.assign(new Error('Not Found'), {status: 404});
        }
        return {data: {object: {sha: state.mainSha}}};
      }},
      pulls: {
        list: async () => {throw new Error('paginate is stubbed');},
        create: async fields => {calls.push(['create', fields]);fail('create');return {data: {number: 7}};},
        update: async fields => {calls.push(['update', fields]);fail('update');return {data: {number: fields.pull_number}};},
      },
      issues: {addLabels: async fields => {calls.push(['addLabels', fields]);fail('addLabels');return {data: []};}},
    },
  };
  const remoteHead = () => git(scratch, 'ls-remote', remote, `refs/heads/${BRANCH}`).split('\t')[0] || null;
  const run = async () => {
    const coordinator = await prepare(request, new GitHubPrScm(request, 'stub-token-not-a-secret', api));
    return coordinator.manifest.createPullRequests();
  };
  const writes = () => calls.filter(([kind]) => ['create', 'update', 'addLabels'].includes(kind));
  return {repo, remote, request, api, state, calls, remoteHead, run, writes, put, save};
}

// The open pull request GitHub would list for the head the writer pushed.
function openPullRequest(f, head, overrides = {}) {
  const [, written] = f.calls.find(([kind]) => kind === 'create' || kind === 'update');
  return {number: 7, title: written.title, body: written.body, head: {ref: BRANCH, sha: head, repo: {full_name: REPOSITORY}},
    labels: [{name: 'autorelease: pending'}], ...overrides};
}

test('PR writer creates the proposal branch and pull request once, with the pending label', async t => {
  const f = writerFixture(t);
  const result = await f.run();
  assert.equal(result.length, 1);
  assert.deepEqual(f.writes().map(([kind]) => kind), ['create', 'addLabels']);
  const [, created] = f.calls.find(([kind]) => kind === 'create');
  assert.deepEqual({owner: created.owner, repo: created.repo, base: created.base, head: created.head, draft: created.draft},
    {owner: 'KD5UJC-XYZ', repo: 'scarab', base: TARGET, head: BRANCH, draft: false});
  assert.deepEqual(f.calls.find(([kind]) => kind === 'addLabels')[1],
    {owner: 'KD5UJC-XYZ', repo: 'scarab', issue_number: 7, labels: ['autorelease: pending']});
  const head = f.remoteHead();
  assert.match(head, /^[0-9a-f]{40}$/);
  const receipt = verifyGeneratedHead(f.repo, head);
  assert.equal(receipt.version, '0.1.1');
  assert.equal(receipt.pullRequestTitle, created.title);
  assert.equal(receipt.pullRequestBodySha256, sha256(created.body));
  assert.equal(git(f.repo, 'rev-parse', `${head}^`), f.request.source);
  // Only reads, a branch, a pull request and a label: no release, tag or publication call exists in the stub.
  assert.ok(f.calls.every(([kind]) => ['paginate', 'getRef', 'create', 'addLabels'].includes(kind)));
  assert.ok(f.calls.some(([kind, ref]) => kind === 'getRef' && ref === 'tags/v0.1.1'));
});

test('PR writer updates the existing pull request at the reviewed head, and a repeated run writes nothing', async t => {
  const f = writerFixture(t);
  await f.run();
  const first = f.remoteHead();
  f.state.open = [openPullRequest(f, first)];
  // A fragment arrives while the proposal is open.
  f.put('changes/2-arrival.md', '### Fixed\n\n- Arrived during review.\n');
  f.request.source = f.save('fix: arrive during review');
  f.state.mainSha = f.request.source;
  f.request.expectedHead = first;
  f.calls.length = 0;
  await f.run();
  assert.deepEqual(f.writes().map(([kind]) => kind), ['update', 'addLabels']);
  assert.equal(f.writes()[0][1].pull_number, 7);
  const second = f.remoteHead();
  assert.notEqual(second, first);
  assert.equal(git(f.repo, 'rev-parse', `${second}^`), f.request.source);
  assert.match(blob(f.repo, second, 'CHANGELOG.md'), /Arrived during review/);
  // Repeating on the same source and head: release-please finds nothing to change and no API write happens.
  const [, updated] = f.calls.find(([kind]) => kind === 'update');
  f.state.open = [openPullRequest(f, second, {title: updated.title, body: updated.body})];
  f.request.expectedHead = second;
  f.calls.length = 0;
  assert.equal((await f.run()).length, 0);
  assert.deepEqual(f.writes(), []);
  assert.equal(f.remoteHead(), second);
});

test('PR writer refuses each unsafe precondition before any push or API write', async t => {
  const refusals = {
    'reserved live tag': [f => f.state.tags.add('tags/v0.1.1'), /already reserved/],
    'target advanced after the frozen source': [f => {f.state.mainSha = 'f'.repeat(40);}, /target advanced/],
    'remote branch exists but no lease was given': [f => git(f.repo, 'push', '-q', f.remote, `${f.request.source}:refs/heads/${BRANCH}`),
      /remote PR head differs/],
    'live tag lookup refused (not absence)': [f => {f.state.tagStatus = 403;}, /tag lookup refused/],
  };
  for (const [name, [arrange, pattern]] of Object.entries(refusals)) {
    const f = writerFixture(t);
    arrange(f);
    const before = f.remoteHead();
    await assert.rejects(() => f.run(), pattern, name);
    assert.deepEqual(f.writes(), [], name);
    assert.equal(f.remoteHead(), before, name);
  }
});

test('PR writer raises API errors after the push and leaves a branch that a retry can lease against', async t => {
  const f = writerFixture(t);
  f.state.failure = {create: 422};
  await assert.rejects(() => f.run(), /stub create failed/);
  assert.deepEqual(f.writes().map(([kind]) => kind), ['create']);
  assert.equal(verifyGeneratedHead(f.repo, f.remoteHead()).version, '0.1.1');
  const g = writerFixture(t);
  g.state.failure = {addLabels: 500};
  await assert.rejects(() => g.run(), /stub addLabels failed/);
});

test('PR writer refuses to overwrite an existing pull request that a person changed', async t => {
  const f = writerFixture(t);
  await f.run();
  const head = f.remoteHead();
  f.request.expectedHead = head;
  const intact = openPullRequest(f, head);
  const cases = {
    'description edited': [openPullRequest(f, head, {body: 'Edited by a person.'}), /description edits/],
    'title edited': [openPullRequest(f, head, {title: 'Edited title'}), /description edits/],
    'label removed': [openPullRequest(f, head, {labels: []}), /label removed/],
    'fork head': [openPullRequest(f, head, {head: {ref: BRANCH, sha: head, repo: {full_name: 'someone/scarab'}}}), /same-repository/],
    'head differs from the reviewed lease': [openPullRequest(f, 'a'.repeat(40)), /exact reviewed expected head/],
  };
  for (const [name, [open, pattern]] of Object.entries(cases)) {
    f.state.open = [open];
    f.calls.length = 0;
    await assert.rejects(() => f.run(), pattern, name);
    assert.deepEqual(f.writes(), [], name);
    assert.equal(f.remoteHead(), head, name);
  }
  f.state.open = [intact, {...intact, number: 8}];
  f.calls.length = 0;
  await assert.rejects(() => f.run(), /multiple preparation PRs/);
  assert.deepEqual(f.writes(), []);
});

test('PR writer refuses a manual commit on the proposal branch even with the matching lease', async t => {
  const f = writerFixture(t);
  await f.run();
  git(f.repo, 'checkout', '-q', f.remoteHead());
  f.put('manual.txt', 'human work');
  const manual = f.save('docs: human edit');
  git(f.repo, 'push', '-q', f.remote, `${manual}:refs/heads/${BRANCH}`);
  git(f.repo, 'checkout', '-q', 'main');
  f.request.expectedHead = manual;
  f.state.open = [openPullRequest(f, manual)];
  f.calls.length = 0;
  await assert.rejects(() => f.run(), /manual PR commits/);
  assert.deepEqual(f.writes(), []);
  assert.equal(f.remoteHead(), manual);
});
