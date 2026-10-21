# SPDX-License-Identifier: Apache-2.0
"""GHCR identity reads; staging is public-capable, aliases never replace different bytes."""
import base64
import os
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

from publication_common import NoRedirect, PublicationError, decode_json, require


def resolve(repository, reference):
    require(re.fullmatch(r'ghcr.io/[a-z0-9_.-]+/[a-z0-9_./-]+', repository), 'invalid reviewed GHCR repository')
    require(re.fullmatch(r'[A-Za-z0-9_.-]+|sha256:[0-9a-f]{64}', reference), 'invalid OCI reference')
    path = repository[len('ghcr.io/'):]
    request = urllib.request.Request('https://ghcr.io/token?' + urllib.parse.urlencode({
        'service': 'ghcr.io', 'scope': 'repository:' + path + ':pull'}))
    if os.environ.get('REGISTRY_TOKEN', os.environ.get('GH_TOKEN')):
        require(os.environ.get('GITHUB_ACTOR'), 'explicit registry actor required')
        credential = (os.environ['GITHUB_ACTOR'] + ':' + os.environ.get('REGISTRY_TOKEN', os.environ.get('GH_TOKEN'))).encode()
        request.add_header('Authorization', 'Basic ' + base64.b64encode(credential).decode())
    opener = urllib.request.build_opener(NoRedirect)
    with opener.open(request, timeout=60) as response:
        token = decode_json(response.read(1024 * 1024))['token']
    request = urllib.request.Request('https://ghcr.io/v2/' + path + '/manifests/' + reference, method='HEAD', headers={
        'Authorization': 'Bearer ' + token,
        'Accept': 'application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json'})
    try:
        with opener.open(request, timeout=60) as response:
            result = response.headers.get('Docker-Content-Digest', '')
            require(re.fullmatch(r'sha256:[0-9a-f]{64}', result), 'registry omitted content digest')
            return result
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise PublicationError('registry identity request failed', status=error.code) from None


def copy(oras, source, repository, tag, expected, from_layout=False):
    previous = resolve(repository, tag)
    require(previous is None or previous == expected, 'existing OCI tag identifies other bytes; never overwrite')
    if previous is None:
        command = [str(oras), 'cp'] + (['--from-oci-layout'] if from_layout else [])
        subprocess.run(command + [source, repository + ':' + tag], check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    require(resolve(repository, tag) == expected, 'staged/aliased OCI bytes differ from verified manifest')
