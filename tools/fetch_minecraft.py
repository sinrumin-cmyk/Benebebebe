#!/usr/bin/env python3
"""Downloads the pinned Minecraft version from Mojang's public servers into a launcher-style game folder
(versions/, libraries/, assets/), the layout horizon-prepare reads, and the server jar.
Needs your own copy of the game: Minecraft Java Edition must be bought on your account.

usage: fetch_minecraft.py RESEARCH_LOCK_JSON MINECRAFT_DIR
"""
import hashlib, json, os, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
ASSETS = "https://resources.download.minecraft.net/"


def get(url, tries=4):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "horizon-android"}), timeout=60) as r:
                return r.read()
        except Exception as e:
            if i == tries - 1:
                raise
            time.sleep(2 * (i + 1))


def sha1(data):
    return hashlib.sha1(data).hexdigest()


def fetch_file(url, path, want_sha1=None):
    """Download to path unless it is already there with the right hash."""
    if os.path.isfile(path) and (want_sha1 is None or sha1(open(path, "rb").read()) == want_sha1):
        return False
    data = get(url)
    if want_sha1 and sha1(data) != want_sha1:
        raise RuntimeError(f"hash mismatch: {url}")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".part"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)
    return True


def main():
    lock = json.load(open(sys.argv[1]))["minecraft"]
    root = os.path.expanduser(sys.argv[2])
    version = lock["version"]
    print(f"Version {version}")

    manifest = json.loads(get(MANIFEST))
    entry = next((v for v in manifest["versions"] if v["id"] == version), None)
    if not entry:
        sys.exit(f"{version} is not in Mojang's version manifest")
    vjson_bytes = get(entry["url"])
    accepted = [lock["manifest_sha1"], *lock.get("accepted_manifest_sha1", [])]
    if sha1(vjson_bytes) not in accepted:
        print("WARNING: the version JSON is not one the lock knows; prepare may refuse it.")
    vdir = os.path.join(root, "versions", version)
    os.makedirs(vdir, exist_ok=True)
    open(os.path.join(vdir, version + ".json"), "wb").write(vjson_bytes)
    vjson = json.loads(vjson_bytes)

    dl = vjson["downloads"]
    jobs = [(dl["client"]["url"], os.path.join(vdir, version + ".jar"), dl["client"]["sha1"]),
            (dl["server"]["url"], os.path.join(root, f"server-{version}.jar"), dl["server"]["sha1"])]
    for lib in vjson.get("libraries", []):
        art = lib.get("downloads", {}).get("artifact")
        if art:
            jobs.append((art["url"], os.path.join(root, "libraries", art["path"]), art["sha1"]))

    idx = vjson["assetIndex"]
    index_path = os.path.join(root, "assets", "indexes", idx["id"] + ".json")
    fetch_file(idx["url"], index_path, idx["sha1"])
    objects = json.load(open(index_path))["objects"]
    for obj in objects.values():
        h = obj["hash"]
        jobs.append((ASSETS + h[:2] + "/" + h, os.path.join(root, "assets", "objects", h[:2], h), h))

    print(f"{len(jobs)} files (client, server, libraries, {len(objects)} assets)")
    done = fetched = 0
    with ThreadPoolExecutor(16) as pool:
        for new in pool.map(lambda j: fetch_file(*j), jobs):
            done += 1
            fetched += new
            if done % 500 == 0 or done == len(jobs):
                print(f"  {done}/{len(jobs)} checked, {fetched} downloaded", flush=True)
    print("Server jar:", os.path.join(root, f"server-{version}.jar"))


if __name__ == "__main__":
    main()
