# SPDX-License-Identifier: Apache-2.0
"""Bounded transport and identity checks shared by publication and independent retrieval."""
import hashlib
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path, PurePosixPath


class PublicationError(ValueError):
    """An unmet publication invariant; callers must not continue to a later state."""
    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


def require(condition, detail):
    if not condition:
        raise PublicationError(detail)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def unique_object(pairs):
    result = dict(pairs)
    require(len(result) == len(pairs), "duplicate JSON keys are not an authority")
    return result


def decode_json(data):
    return json.loads(data, object_pairs_hook=unique_object,
                      parse_constant=lambda value: (_ for _ in ()).throw(PublicationError("non-finite JSON")))


def load_json(path):
    return decode_json(Path(path).read_bytes())


def safe_path(root, relative):
    require(isinstance(relative, str), "artifact path must be text")
    path = PurePosixPath(relative)
    require(relative and not path.is_absolute()
            and all(part not in {"", ".", ".."} for part in relative.split("/"))
            and all(re.fullmatch(r"[A-Za-z0-9_.-]+", part) for part in path.parts), "unsafe artifact path")
    target = Path(root).joinpath(*path.parts)
    require(not any(parent.is_symlink() for parent in [target, *target.parents]), "symlink artifact path")
    return target


def asset_name(relative):
    safe_path(Path("/unused"), relative)
    return digest(relative.encode())[:16] + "-" + PurePosixPath(relative).name


def sha256(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value), "full lowercase SHA256 required")
    return value


def commit(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{40}", value), "full lowercase source commit required")
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


class GitHub:
    """Never forward credentials to redirects; error messages exclude bodies and tokens."""
    def __init__(self, repository, token=None, base="https://api.github.com"):
        require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository), "invalid repository")
        parsed = urllib.parse.urlsplit(base)
        require(base == "https://api.github.com" or
                (parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost", "::1"}
                 and not token), "only GitHub HTTPS or unauthenticated loopback fixtures are permitted")
        self.repository, self.token, self.base = repository, token, base.rstrip("/")

    def request(self, method, path, payload=None, raw=None, content_type="application/json", upload_size=None):
        require(path.startswith("/") and not path.startswith("//"), "invalid GitHub API path")
        headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = canonical(payload) if payload is not None else raw
        if data is not None:
            headers["Content-Type"] = content_type
        if upload_size is not None:
            headers["Content-Length"] = str(upload_size)
        base = "https://uploads.github.com" if upload_size is not None and self.base == "https://api.github.com" else self.base
        request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.build_opener(NoRedirect).open(request, timeout=60) as response:
                body = response.read(16 * 1024 * 1024 + 1)
                require(len(body) <= 16 * 1024 * 1024, "oversized GitHub JSON response")
                return decode_json(body) if body else None
        except urllib.error.HTTPError as error:
            code = error.code
            error.close()
            raise PublicationError(f"GitHub {method} refused with HTTP {code}", status=code) from None

    def repo_path(self, suffix):
        return "/repos/" + self.repository + suffix

    def get(self, suffix):
        return self.request("GET", self.repo_path(suffix))

    def optional(self, suffix):
        try:
            return self.get(suffix)
        except PublicationError as error:
            if error.status == 404:
                return None
            raise

    def upload_asset(self, release_id, path, name):
        require(type(release_id) is int and release_id > 0, "invalid release ID")
        require(re.fullmatch(r"[A-Za-z0-9_.-]+", name), "invalid asset name")
        with Path(path).open("rb") as stream:
            return self.request("POST", self.repo_path(f"/releases/{release_id}/assets?name=" + name),
                                raw=stream, content_type="application/octet-stream", upload_size=Path(path).stat().st_size)

    def pages(self, suffix, key=None):
        rows = []
        for page in range(1, 101):
            separator = "&" if "?" in suffix else "?"
            response = self.get(suffix + separator + f"per_page=100&page={page}")
            batch = response[key] if key else response
            require(isinstance(batch, list) and len(batch) <= 100, "invalid paginated GitHub response")
            rows.extend(batch)
            if len(batch) < 100:
                return rows
        raise PublicationError("GitHub pagination budget exhausted")

