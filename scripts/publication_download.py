# SPDX-License-Identifier: Apache-2.0
"""Retrieve actual content with bounded size, exact hashes and credential-stripping redirects."""
import hashlib
import urllib.parse
import urllib.request
from pathlib import Path

from publication_common import PublicationError, require, sha256


HOSTS = {"api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"}


def allowed(url, local=False):
    parsed = urllib.parse.urlsplit(url)
    require(not parsed.username and not parsed.password and not parsed.fragment,
            "download URLs must not carry credentials or fragments")
    require((parsed.scheme == "https" and parsed.hostname in HOSTS)
            or (local and parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost"}),
            "untrusted artifact download origin")
    return parsed


class DownloadRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self, local):
        self.local = local

    def redirect_request(self, request, fp, code, message, headers, newurl):
        before, after = urllib.parse.urlsplit(request.full_url), allowed(newurl, self.local)
        redirected = super().redirect_request(request, fp, code, message, headers, newurl)
        if (before.scheme, before.netloc) != (after.scheme, after.netloc):
            redirected.remove_header("Authorization")
        return redirected


def download(url, target, descriptor, token=None, local=False):
    origin = allowed(url, local)
    require(not (local and token), "loopback fixture downloads cannot receive credentials")
    sha256(descriptor["sha256"])
    require(type(descriptor["size"]) is int and descriptor["size"] > 0, "positive expected artifact size required")
    headers = {"Accept": "application/octet-stream"}
    if token:
        require(origin.hostname == "api.github.com", "credentials are only sent to the GitHub asset API")
        headers["Authorization"] = "Bearer " + token
    path = Path(target)
    path.parent.mkdir(parents=True, exist_ok=True)
    require(not path.exists() and not path.is_symlink(), "download destination must be fresh")
    request = urllib.request.Request(url, headers=headers)
    measured, count = hashlib.sha256(), 0
    with urllib.request.build_opener(DownloadRedirect(local)).open(request, timeout=120) as response, path.open("xb") as stream:
        allowed(response.url, local)
        for chunk in iter(lambda: response.read(1024 * 1024), b""):
            count += len(chunk)
            require(count <= descriptor["size"], "download exceeds pinned artifact size")
            measured.update(chunk)
            stream.write(chunk)
    require(count == descriptor["size"] and measured.hexdigest() == descriptor["sha256"],
            "downloaded bytes differ from pinned candidate")
    return path
