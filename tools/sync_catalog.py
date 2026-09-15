"""Refresh the public catalogue from NRF release manifests; no third-party packages."""
import json
import os
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def get(url):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "NRF-catalogue"}
    token = os.environ.get("GITHUB_TOKEN")
    # Never forward an API token to release asset redirects.
    if token and url.startswith("https://api.github.com/"):
        headers["Authorization"] = "Bearer " + token
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
        data = response.read(1024 * 1024 + 1)
    if len(data) > 1024 * 1024:
        raise ValueError("Manifest too large")
    return json.loads(data)

def project(repo):
    release = get(f"https://api.github.com/repos/NimbyRails-France/{repo}/releases/latest")
    manifest = next(a for a in release["assets"] if a["name"] == "project.json")
    value = get(manifest["browser_download_url"])
    if value.get("id") != repo or value.get("kind") != repo:
        raise ValueError("Wrong project identity")
    if not re.fullmatch(r"\d{1,4}\.\d{1,4}\.\d{1,4}", value.get("version", "")):
        raise ValueError("Invalid version")
    if release["tag_name"] != "v" + value["version"]:
        raise ValueError("Manifest does not match release tag")
    asset = next(a for a in release["assets"] if a["browser_download_url"] == value.get("url"))
    if asset["size"] != value.get("size") or not re.fullmatch(r"[0-9a-f]{64}", value.get("sha256", "")):
        raise ValueError("Invalid size or digest")
    if asset.get("digest") and asset["digest"] != "sha256:" + value["sha256"]:
        raise ValueError("GitHub asset digest differs")
    if not value.get("gameSha256") or any(not re.fullmatch(r"[0-9a-f]{64}", h) for h in value["gameSha256"]):
        raise ValueError("Missing supported game hashes")
    return value

def main():
    path = ROOT / "catalog.json"
    catalogue = json.loads(path.read_text(encoding="utf-8-sig"))
    old = {p["id"]: p for p in catalogue["projects"]}
    for repo in ("sdk", "tco"):
        value = project(repo)
        if repo in old and tuple(map(int, value["version"].split("."))) < tuple(map(int, old[repo]["version"].split("."))):
            raise ValueError("Refusing catalogue downgrade")
        old[repo] = value
    catalogue["projects"] = list(old.values())
    path.write_text(json.dumps(catalogue, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

if __name__ == "__main__":
    main()
