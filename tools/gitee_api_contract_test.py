#!/usr/bin/env python3
"""Live Gitee v5 contract test for the endpoints used by the Android transport."""

from __future__ import annotations

import argparse
import base64
import copy
import datetime as dt
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Any


class ContractFailure(RuntimeError):
    pass


@dataclass(frozen=True)
class ApiResponse:
    status: int
    body: Any
    headers: dict[str, str]


class GiteeApi:
    def __init__(self, base_url: str, token: str) -> None:
        self.base_url = base_url.rstrip("/")
        self._token = token

    def get(self, path: str, query: dict[str, str] | None = None) -> ApiResponse:
        authenticated_query = dict(query or {})
        authenticated_query["access_token"] = self._token
        url = f"{self.base_url}/{path.lstrip('/')}?{urllib.parse.urlencode(authenticated_query)}"
        return self._request(urllib.request.Request(url, headers={"Accept": "application/json"}))

    def post(self, path: str, payload: dict[str, Any]) -> ApiResponse:
        authenticated_payload = copy.deepcopy(payload)
        authenticated_payload["access_token"] = self._token
        request = urllib.request.Request(
            f"{self.base_url}/{path.lstrip('/')}",
            data=json.dumps(authenticated_payload, separators=(",", ":")).encode("utf-8"),
            headers={"Accept": "application/json", "Content-Type": "application/json"},
            method="POST",
        )
        return self._request(request)

    def _request(self, request: urllib.request.Request) -> ApiResponse:
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return self._decode(response.status, response.read(), response.headers.items())
        except urllib.error.HTTPError as error:
            return self._decode(error.code, error.read(), error.headers.items())
        except urllib.error.URLError as error:
            raise ContractFailure(f"Gitee network request failed: {type(error.reason).__name__}") from None

    def _decode(self, status: int, raw: bytes, headers: Any) -> ApiResponse:
        text = raw.decode("utf-8", errors="replace").replace(self._token, "<redacted>")
        try:
            body = json.loads(text) if text else None
        except json.JSONDecodeError:
            body = {"non_json_body": text[:512]}
        return ApiResponse(status=status, body=body, headers={str(k).lower(): str(v) for k, v in headers})


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractFailure(message)


def require_status(response: ApiResponse, expected: set[int], context: str) -> None:
    if response.status not in expected:
        raise ContractFailure(f"{context} returned HTTP {response.status}: {response.body!r}")


def require_object(value: Any, context: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ContractFailure(f"{context} was {type(value).__name__}, expected object")
    return value


def require_list(value: Any, context: str) -> list[Any]:
    if not isinstance(value, list):
        raise ContractFailure(f"{context} was {type(value).__name__}, expected array")
    return value


def require_string(value: Any, context: str) -> str:
    if not isinstance(value, str) or not value:
        raise ContractFailure(f"{context} was missing or not a non-empty string")
    return value


def commit_sha(body: Any, context: str) -> str:
    result = require_object(body, context)
    direct = result.get("sha")
    if isinstance(direct, str) and direct:
        return direct
    nested = result.get("commit")
    if isinstance(nested, dict):
        return require_string(nested.get("sha"), f"{context}.commit.sha")
    raise ContractFailure(f"{context} omitted commit SHA")


def create_commit(
    api: GiteeApi,
    repo_path: str,
    branch: str,
    message: str,
    actions: list[dict[str, Any]],
) -> str:
    response = api.post(
        f"{repo_path}/commits",
        {"branch": branch, "message": message, "actions": actions},
    )
    require_status(response, {200, 201}, "create commit")
    return commit_sha(response.body, "create commit response")


def create_branch(api: GiteeApi, repo_path: str, refs: str, branch: str) -> str:
    response = api.post(
        f"{repo_path}/branches",
        {"refs": refs, "branch_name": branch},
    )
    require_status(response, {201}, "create branch")
    created = require_object(response.body, "create branch response")
    require(created.get("name") == branch, "created branch name did not match")
    commit = require_object(created.get("commit"), "created branch commit")
    return require_string(commit.get("sha"), "created branch commit SHA")


def run_contract(args: argparse.Namespace) -> dict[str, Any]:
    token = os.environ.get("GITEE_TOKEN", "")
    if not token:
        raise ContractFailure("GITEE_TOKEN must be set in the environment")

    api = GiteeApi(args.base_url, token)
    repo_path = f"repos/{urllib.parse.quote(args.owner, safe='')}/{urllib.parse.quote(args.repository, safe='')}"
    encoded_branch = urllib.parse.quote(args.branch, safe="")

    repository_response = api.get(repo_path)
    require_status(repository_response, {200}, "repository lookup")
    repository = require_object(repository_response.body, "repository response")
    require(repository.get("path") == args.repository, "repository path did not match")
    require(repository.get("private") is True, "contract repository must be private")
    permissions = repository.get("permission") or repository.get("permissions")
    permissions = require_object(permissions, "repository permission")
    require(permissions.get("push") is True or permissions.get("admin") is True, "token lacks push permission")
    default_branch_kind = "null" if repository.get("default_branch") is None else "string"

    branch_before = api.get(f"{repo_path}/branches/{encoded_branch}")
    require_status(branch_before, {200, 404}, "initial branch lookup")
    branch_created_via_api = False
    if branch_before.status == 404 and isinstance(repository.get("default_branch"), str):
        create_branch(api, repo_path, repository["default_branch"], args.branch)
        branch_created_via_api = True

    run_id = uuid.uuid4().hex
    prefix = f".codex-gitee-api-test/{run_id}"
    text_bytes = f"Gitee API contract {run_id}\n".encode("utf-8")
    binary_bytes = bytes(index % 256 for index in range(args.binary_bytes))
    text_path = f"{prefix}/text.txt"
    binary_path = f"{prefix}/binary.bin"
    second_path = f"{prefix}/second.txt"

    first_sha = create_commit(
        api,
        repo_path,
        args.branch,
        f"test: Gitee API contract {run_id} part 1",
        [
            {"action": "create", "path": text_path, "content": text_bytes.decode("utf-8")},
            {
                "action": "create",
                "path": binary_path,
                "content": base64.b64encode(binary_bytes).decode("ascii"),
                "encoding": "base64",
            },
        ],
    )
    second_sha = create_commit(
        api,
        repo_path,
        args.branch,
        f"test: Gitee API contract {run_id} part 2",
        [{"action": "create", "path": second_path, "content": "branch advancement\n"}],
    )
    require(first_sha != second_sha, "branch did not advance across commits")

    branch_response = api.get(f"{repo_path}/branches/{encoded_branch}")
    require_status(branch_response, {200}, "branch lookup")
    branch = require_object(branch_response.body, "branch response")
    branch_commit = require_object(branch.get("commit"), "branch commit")
    require(branch_commit.get("sha") == second_sha, "branch head did not match second commit")

    page_one = api.get(f"{repo_path}/commits", {"sha": args.branch, "page": "1", "per_page": "1"})
    page_two = api.get(f"{repo_path}/commits", {"sha": args.branch, "page": "2", "per_page": "1"})
    require_status(page_one, {200}, "commit page 1")
    require_status(page_two, {200}, "commit page 2")
    page_one_items = require_list(page_one.body, "commit page 1")
    page_two_items = require_list(page_two.body, "commit page 2")
    require(len(page_one_items) == 1 and page_one_items[0].get("sha") == second_sha, "page 1 mismatch")
    require(len(page_two_items) == 1 and page_two_items[0].get("sha") == first_sha, "page 2 mismatch")

    commit_response = api.get(f"{repo_path}/commits/{second_sha}")
    require_status(commit_response, {200}, "commit lookup")
    require(commit_sha(commit_response.body, "commit response") == second_sha, "commit SHA mismatch")

    text_response = api.get(f"{repo_path}/contents/{text_path}", {"ref": args.branch})
    binary_response = api.get(f"{repo_path}/contents/{binary_path}", {"ref": args.branch})
    require_status(text_response, {200}, "text content lookup")
    require_status(binary_response, {200}, "binary content lookup")
    text_content = require_object(text_response.body, "text content response")
    binary_content = require_object(binary_response.body, "binary content response")
    decoded_text = base64.b64decode(require_string(text_content.get("content"), "text content"))
    decoded_binary = base64.b64decode(require_string(binary_content.get("content"), "binary content"))
    require(decoded_text == text_bytes, "text content bytes changed")
    require(decoded_binary == binary_bytes, "binary content bytes changed")

    tree_response = api.get(f"{repo_path}/git/trees/{second_sha}", {"recursive": "1"})
    require_status(tree_response, {200}, "recursive tree lookup")
    tree = require_object(tree_response.body, "tree response")
    entries = require_list(tree.get("tree"), "tree entries")
    paths = {entry.get("path") for entry in entries if isinstance(entry, dict)}
    require({text_path, binary_path, second_path}.issubset(paths), "recursive tree omitted contract files")

    blob_sha = require_string(text_content.get("sha"), "text blob SHA")
    blob_response = api.get(f"{repo_path}/git/blobs/{blob_sha}")
    require_status(blob_response, {200}, "blob lookup")
    blob = require_object(blob_response.body, "blob response")
    decoded_blob = base64.b64decode(require_string(blob.get("content"), "blob content"))
    require(decoded_blob == text_bytes, "blob bytes changed")

    missing_response = api.get(f"{repo_path}/contents/{prefix}/missing.txt", {"ref": args.branch})
    require_status(missing_response, {200, 404}, "missing content lookup")
    if missing_response.status == 200:
        require(
            missing_response.body is None or missing_response.body == [],
            "missing content returned HTTP 200 with a non-empty payload",
        )

    duplicate_response = api.post(
        f"{repo_path}/commits",
        {
            "branch": args.branch,
            "message": f"test: duplicate path rejection {run_id}",
            "actions": [{"action": "create", "path": text_path, "content": "duplicate\n"}],
        },
    )
    require_status(duplicate_response, {400, 409, 422}, "duplicate path rejection")

    invalid_api = GiteeApi(args.base_url, "invalid-contract-token")
    invalid_response = invalid_api.get(repo_path)
    require_status(invalid_response, {401, 403, 404}, "invalid token rejection")

    rate_headers = {
        name: repository_response.headers[name]
        for name in ("x-ratelimit-limit", "x-ratelimit-remaining", "x-ratelimit-reset")
        if name in repository_response.headers
    }
    return {
        "status": "passed",
        "owner": args.owner,
        "repository": args.repository,
        "branch": args.branch,
        "branch_created_via_api": branch_created_via_api,
        "default_branch_type": default_branch_kind,
        "first_commit_sha": first_sha,
        "second_commit_sha": second_sha,
        "binary_bytes": len(binary_bytes),
        "tree_entries": len(entries),
        "missing_content_status": missing_response.status,
        "missing_content_shape": "empty" if missing_response.status == 200 else "http-404",
        "duplicate_path_status": duplicate_response.status,
        "invalid_token_status": invalid_response.status,
        "rate_limit_headers": rate_headers,
    }


def parse_args() -> argparse.Namespace:
    today = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--owner", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--branch", default=f"codex/gitee-api-contract-{today}")
    parser.add_argument("--base-url", default="https://gitee.com/api/v5")
    parser.add_argument("--binary-bytes", type=int, default=4096)
    args = parser.parse_args()
    if args.binary_bytes < 1 or args.binary_bytes > 1024 * 1024:
        parser.error("--binary-bytes must be between 1 and 1048576")
    return args


def main() -> int:
    try:
        result = run_contract(parse_args())
    except ContractFailure as error:
        print(json.dumps({"status": "failed", "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
