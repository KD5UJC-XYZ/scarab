// SPDX-License-Identifier: Apache-2.0
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {execFileSync} = require('node:child_process');
const {HistoryScm, git, blob, sha256, PROPOSAL, REPOSITORY, TARGET} = require('./coordinator.cjs');
const BRANCH = `release-please--branches--${TARGET}`;
const REMOTE = `https://github.com/${REPOSITORY}.git`;
function requireThat(condition, message) {if (!condition) throw new Error(message);}
function command(repo, args, env, input) {
  return execFileSync('git', ['-C', repo, ...args], {encoding: 'utf8', env: {...process.env, ...env}, input,
    stdio: ['pipe', 'pipe', 'pipe']}).trimEnd();
}
function pushWithLease(repo, remote, branch, commit, expected, env = {}) {
  requireThat(branch === BRANCH, 'unapproved PR branch');
  requireThat(/^[0-9a-f]{40}$/.test(commit) && (expected === null || /^[0-9a-f]{40}$/.test(expected)), 'invalid lease identity');
  command(repo, ['push', `--force-with-lease=refs/heads/${branch}:${expected || ''}`, '--', remote,
    `${commit}:refs/heads/${branch}`], env);
}
function commitUpdates(request, title, updates) {
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'scarab-release-index-'));
  const env = {GIT_INDEX_FILE: path.join(scratch, 'index'), GIT_AUTHOR_NAME: 'Scarab release preparation',
    GIT_AUTHOR_EMAIL: 'release-preparation@users.noreply.github.com', GIT_COMMITTER_NAME: 'Scarab release preparation',
    GIT_COMMITTER_EMAIL: 'release-preparation@users.noreply.github.com'};
  try {
    command(request.repo, ['read-tree', request.source], env);
    const paths = new Set();
    for (const update of updates) {
      const file = update.path;
      requireThat(!paths.has(file), 'duplicate PR update'); paths.add(file);
      requireThat(file === 'CHANGELOG.md' || file === '.release-please-manifest.json' || file === PROPOSAL ||
        /^changes\/[A-Za-z0-9][A-Za-z0-9_.-]*\.md$/.test(file) ||
        /^\.release\/(notes|changelog|proposals)\/[0-9][A-Za-z0-9.-]*\.(md|json)$/.test(file), 'unapproved generated path');
      const content = update.updater.updateContent(blob(request.repo, request.source, file));
      if (content === null) command(request.repo, ['update-index', '--force-remove', '--', file], env);
      else {
        requireThat(typeof content === 'string', 'generated content must be text or explicit deletion');
        const hash = command(request.repo, ['hash-object', '-w', '--stdin'], env, content);
        command(request.repo, ['update-index', '--add', '--cacheinfo', '100644', hash, file], env);
      }
    }
    requireThat(paths.has(PROPOSAL), 'missing preparation receipt');
    const tree = command(request.repo, ['write-tree'], env);
    return command(request.repo, ['commit-tree', tree, '-p', request.source], env, title + '\n');
  } finally {fs.rmSync(scratch, {recursive: true, force: true});}
}
function verifyGeneratedHead(repo, head) {
  const receiptBytes = blob(repo, head, PROPOSAL);
  requireThat(receiptBytes, 'existing PR has no preparation receipt; refuse overwrite');
  const receipt = JSON.parse(receiptBytes);
  requireThat(receipt.schemaVersion === 1 && receipt.state === 'proposed' && receipt.repository === REPOSITORY,
    'unrecognized existing preparation');
  const parents = git(repo, 'rev-list', '--parents', '-n', '1', head).split(' ');
  requireThat(parents.length === 2 && parents[1] === receipt.source, 'manual PR commits require review; refuse overwrite');
  const versioned = `.release/proposals/${receipt.version}.json`;
  const changed = git(repo, 'diff-tree', '--no-commit-id', '--name-only', '-r', receipt.source, head).split('\n').filter(Boolean).sort();
  const expected = [...Object.keys(receipt.generatedFiles), PROPOSAL, versioned].sort();
  requireThat(JSON.stringify(changed) === JSON.stringify(expected), 'unexpected PR diff; refuse manual edits');
  requireThat(blob(repo, head, versioned) === receiptBytes, 'proposal receipts disagree');
  for (const [file, hash] of Object.entries(receipt.generatedFiles)) {
    const bytes = blob(repo, head, file);
    requireThat((bytes === undefined ? null : sha256(bytes)) === hash, 'manual generated-file edits require review');
  }
  return receipt;
}
class GitHubPrScm extends HistoryScm {
  constructor(request, token, api) {
    super(request);
    requireThat(typeof token === 'string' && token.length > 0, 'GitHub App installation token required; no token fallback');
    this.api = api;
    this.auth = {GIT_CONFIG_COUNT: '1', GIT_CONFIG_KEY_0: 'http.https://github.com/.extraheader',
      GIT_CONFIG_VALUE_0: 'AUTHORIZATION: basic ' + Buffer.from(`x-access-token:${token}`).toString('base64'), GIT_TERMINAL_PROMPT: '0'};
  }
  async *pullRequestIterator(_target, status) {
    // The collector verifies merged pending stable receipts. GitHub labels are not publication authority.
    if (status !== 'OPEN') return;
    const all = await this.api.paginate(this.api.rest.pulls.list, {owner: 'KD5UJC-XYZ', repo: 'scarab', state: 'open', base: TARGET, per_page: 100});
    const matching = all.filter(pr => pr.head.ref === BRANCH);
    requireThat(matching.length <= 1, 'multiple preparation PRs; require review');
    for (const pr of matching) {
      requireThat(pr.head.repo.full_name === REPOSITORY, 'preparation PR must be same-repository');
      requireThat(pr.head.sha === this.request.expectedHead, 'existing PR requires exact reviewed expected head');
      command(this.request.repo, ['fetch', '--no-tags', REMOTE, pr.head.sha], this.auth);
      const receipt = verifyGeneratedHead(this.request.repo, pr.head.sha);
      requireThat(receipt.pullRequestBodySha256 === sha256(pr.body) && receipt.pullRequestTitle === pr.title,
        'manual PR description edits require review; refuse overwrite');
      requireThat(pr.labels.some(label => label.name === 'autorelease: pending'), 'preparation label removed; require review');
      yield {number: pr.number, title: pr.title, body: pr.body, headBranchName: pr.head.ref,
        baseBranchName: TARGET, labels: pr.labels.map(label => label.name), files: []};
    }
  }
  async write(pr, updates, number) {
    const base = await this.api.rest.git.getRef({owner: 'KD5UJC-XYZ', repo: 'scarab', ref: `heads/${TARGET}`});
    requireThat(base.data.object.sha === this.request.source, 'target advanced; freeze and review a new source');
    const branch = pr.headRefName || pr.headBranchName;
    requireThat(branch === BRANCH, 'unexpected release-please branch');
    const remoteHead = command(this.request.repo, ['ls-remote', REMOTE, `refs/heads/${branch}`], this.auth).split('\t')[0] || null;
    requireThat(remoteHead === this.request.expectedHead, 'remote PR head differs from explicit lease');
    if (remoteHead) {
      command(this.request.repo, ['fetch', '--no-tags', REMOTE, remoteHead], this.auth);
      verifyGeneratedHead(this.request.repo, remoteHead);
    }
    const head = commitUpdates(this.request, pr.title.toString(), updates);
    pushWithLease(this.request.repo, REMOTE, branch, head, this.request.expectedHead, this.auth);
    const fields = {owner: 'KD5UJC-XYZ', repo: 'scarab', title: pr.title.toString(), body: pr.body.toString()};
    const response = number
      ? await this.api.rest.pulls.update({...fields, pull_number: number})
      : await this.api.rest.pulls.create({...fields, base: TARGET, head: branch, draft: false});
    await this.api.rest.issues.addLabels({owner: 'KD5UJC-XYZ', repo: 'scarab', issue_number: response.data.number, labels: ['autorelease: pending']});
    return {number: response.data.number, headBranchName: branch, body: fields.body, title: fields.title, labels: ['autorelease: pending'], files: []};
  }
  async createPullRequest(pr, _target, _message, updates) {return this.write(pr, updates);}
  async updatePullRequest(number, pr) {return this.write(pr, pr.updates, number);}
  async removeIssueLabels() {throw new Error('automatic snooze override forbidden');}
}
module.exports = {GitHubPrScm, commitUpdates, verifyGeneratedHead, pushWithLease, BRANCH};
