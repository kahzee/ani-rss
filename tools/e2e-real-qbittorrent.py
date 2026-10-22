"""Real qBittorrent/NAS smoke test using only generated video/subtitle fixtures.

The supplied qBittorrent must see REMOTE_DOWNLOAD_ROOT at LOCAL_DOWNLOAD_ROOT.
Credentials are read from the environment, never from source or command arguments.
No existing torrents or media are modified. The report includes only test artifacts.
Requires requests, libtorrent and imageio-ffmpeg in the test Python environment.
"""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlsplit

import imageio_ffmpeg
import libtorrent as lt
import requests


ROOT = Path(os.environ["E2E_ROOT"]).resolve()
ROOT.mkdir(parents=True, exist_ok=True)
RUN = ROOT.name
HOST = os.environ["QBT_HOST"].rstrip("/")
USER = os.environ["QBT_USER"]
PASSWORD = os.environ["QBT_PASSWORD"]
LOCAL = Path(os.environ["LOCAL_DOWNLOAD_ROOT"]) / RUN
REMOTE = os.environ["REMOTE_DOWNLOAD_ROOT"].rstrip("/") + "/" + RUN
PLAYBACK = Path(os.environ["PLAYBACK_ROOT"]) / RUN
APP_PORT = int(os.environ.get("E2E_APP_PORT", "17790"))
HTTP_PORT = int(os.environ.get("E2E_HTTP_PORT", "18781"))
PEER_PORT = int(os.environ.get("E2E_PEER_PORT", "18882"))
LAN = os.environ["E2E_LAN_IP"]
PUBLIC_FIXTURES = os.environ.get("E2E_PUBLIC_FIXTURES_BASE", "").rstrip("/")
FIXTURE_TAG = os.environ.get("E2E_FIXTURE_TAG", RUN)
APP = f"http://127.0.0.1:{APP_PORT}"
SEEDS = ROOT / "seeds"
SEEDS.mkdir(exist_ok=True)
events = []
phase = 1
last_diagnostic = None


def record(name, **values):
    event = {"test": name, **values}
    events.append(event)
    print(json.dumps(event, ensure_ascii=False), flush=True)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def wait_until(check, description, seconds=150):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(2)
    raise AssertionError("Timeout: " + description)


qbt = requests.Session()
response = qbt.post(HOST + "/api/v2/auth/login", data={"username": USER, "password": PASSWORD}, timeout=10)
assert response.text.strip() == "Ok.", "qBittorrent authentication failed"
version = qbt.get(HOST + "/api/v2/app/version", timeout=10).text
baseline = {task["hash"] for task in qbt.get(HOST + "/api/v2/torrents/info", timeout=15).json()}
record("real_downloader_connected", version=version, existing_tasks=len(baseline))

seed_session = lt.session({"listen_interfaces": f"{LAN}:{PEER_PORT}", "enable_dht": False,
                           "enable_lsd": False, "enable_upnp": False, "enable_natpmp": False})
handles = []
resources = []


def make_release(label, group, episode, color, number):
    label = label + "-" + FIXTURE_TAG
    folder = SEEDS / label
    folder.mkdir(exist_ok=True)
    video = folder / f"sample-{episode:02}.mp4"
    subtitle = folder / f"sample-{episode:02}.chs.srt"
    subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", f"color=c={color}:s=320x180:r=24:d=30", "-f", "lavfi", "-i",
                    "sine=frequency=440:duration=30", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-movflags", "+faststart", "-shortest", str(video)], check=True)
    subtitle.write_text(f"1\n00:00:00,000 --> 00:00:29,500\n自动追番联调样片：{label}\n", encoding="utf-8")
    storage = lt.file_storage()
    for file in (video, subtitle):
        storage.add_file(f"{label}/{file.name}", file.stat().st_size)
    creator = lt.create_torrent(storage, 16384, flags=lt.create_torrent.v1_only)
    creator.set_creator("ani-rss-simple isolated integration test")
    creator.add_url_seed(PUBLIC_FIXTURES + "/" if PUBLIC_FIXTURES else f"http://{LAN}:{HTTP_PORT}/payload/")
    lt.set_piece_hashes(creator, str(SEEDS))
    metadata = lt.bencode(creator.generate())
    info = lt.torrent_info(metadata)
    torrent = ROOT / (label + ".torrent")
    torrent.write_bytes(metadata)
    # Make metadata available alongside payloads for LAN HTTP-seed tests as well.
    (SEEDS / torrent.name).write_bytes(metadata)
    handle = seed_session.add_torrent({"ti": info, "save_path": str(SEEDS), "flags": lt.torrent_flags.seed_mode})
    handles.append(handle)
    handle.resume()
    magnet = lt.make_magnet_uri(info) + f"&x.pe={LAN}:{PEER_PORT}"
    resource = {"id": number, "title": f"[{group}] 联调样片 - {episode:02} [CHS]", "type": "单集",
                "magnet": magnet, "createdAt": f"2026-10-22T00:{number:02}:00Z", "fansub": {"id": number, "name": group}}
    if PUBLIC_FIXTURES:
        resource["href"] = PUBLIC_FIXTURES + "/" + label + ".torrent"
    resources.append(resource)
    return {"hash": str(info.info_hash()), "video": video, "subtitle": subtitle, "torrent": torrent, "label": label}


fallback1 = make_release("fallback-01", "TestFallback", 1, "blue", 1)
fallback2 = make_release("fallback-02", "TestFallback", 2, "green", 2)
preferred1 = make_release("preferred-01", "TestPreferred", 1, "red", 3)

if os.environ.get("E2E_PREPARE_ONLY") == "1":
    record("fixtures_prepared", seed_root=str(SEEDS), metadata_root=str(ROOT), public_base=PUBLIC_FIXTURES)
    raise SystemExit(0)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        route = urlsplit(self.path).path
        if route == "/seed-status":
            data = json.dumps([{"hash": str(h.info_hashes().v1), "state": str(h.status().state),
                                "paused": h.status().paused, "peers": h.status().num_peers,
                                "error": h.status().errc.message()} for h in handles]).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if route == "/resources":
            data = json.dumps({"status": "OK", "resources": resources[:2] if phase == 1 else resources,
                               "pagination": {"page": 1, "pageSize": 100, "complete": True}}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if not route.startswith("/payload/"):
            self.send_error(404)
            return
        path = (SEEDS / unquote(route[len("/payload/"):])).resolve()
        if not path.is_relative_to(SEEDS) or not path.is_file():
            self.send_error(404)
            return
        data = path.read_bytes()
        start, end = 0, len(data) - 1
        byte_range = self.headers.get("Range", "")
        if byte_range.startswith("bytes="):
            begin, finish = byte_range[6:].split("-", 1)
            start, end = int(begin or "0"), min(int(finish or end), end)
        self.send_response(206 if byte_range else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        if byte_range:
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        self.wfile.write(data[start:end + 1])


server = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
config = ROOT / "config"
config.mkdir(exist_ok=True)
settings = {"libraryRoot": str(LOCAL), "downloaderRoot": REMOTE, "playbackRoot": str(PLAYBACK),
            "downloadHost": HOST, "username": USER, "credential": PASSWORD,
            "trustedGroups": ["TestPreferred", "TestFallback"], "simultaneousDownloads": 1}
show = {"id": "987654321", "title": "自动追番联调样片", "folder": "自动追番联调样片 [bgm-987654321]", "totalEpisodes": 2}
(config / "simple-tracking.json").write_text(json.dumps({"settings": settings, "shows": [show]}, ensure_ascii=False), encoding="utf-8")
log = (ROOT / "application.log").open("w", encoding="utf-8")
app_process = subprocess.Popen([os.environ["E2E_JAVA"], "-Dfile.encoding=UTF-8", "-jar", os.environ["E2E_JAR"],
                                f"--config={config}", f"--server.port={APP_PORT}", "--server.address=127.0.0.1",
                                f"--simple.catalog-url=http://127.0.0.1:{HTTP_PORT}"], stdout=log, stderr=subprocess.STDOUT,
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
app = requests.Session()


def api(name, body=None):
    response = app.post(APP + "/api/" + name, json=body, timeout=70)
    response.raise_for_status()
    data = response.json()
    assert data["code"] == 200, f"{name}: {data.get('message')}"
    return data.get("data")


def started():
    try:
        return requests.post(APP + "/api/ping", timeout=1).status_code == 200
    except requests.RequestException:
        return False


def state():
    return api("simple/state")["shows"][0]


def tick():
    global last_diagnostic
    api("simple/refresh")
    # Give the real download client explicit seed peer addresses; no tracker or DHT is used.
    tasks = qbt.get(HOST + "/api/v2/torrents/info", params={"category": "ani-rss-simple"}, timeout=10).json()
    own = {item["hash"] for item in (fallback1, fallback2, preferred1)}
    for task in tasks:
        if task["hash"] not in own:
            continue
        assert task["save_path"].startswith(REMOTE + "/"), "Incorrect remote download path"
        qbt.post(HOST + "/api/v2/torrents/addPeers", data={"hashes": task["hash"], "peers": f"{LAN}:{PEER_PORT}"}, timeout=10)
        # Connecting from the seed also covers NASes that only expose incoming peer connections.
        listen_port = int(os.environ.get("E2E_NAS_PEER_PORT", "25000"))
        for handle in handles:
            if str(handle.info_hashes().v1) == task["hash"]:
                handle.connect_peer((urlsplit(HOST).hostname, listen_port))
    diagnostic = [(t["name"], t["state"], t["progress"]) for t in tasks if t["hash"] in own]
    if diagnostic != last_diagnostic:
        record("transfer_progress", tasks=diagnostic, seed_peers=[h.status().num_peers for h in handles])
        last_diagnostic = diagnostic
    return state()


try:
    wait_until(started, "application startup", 40)
    token = api("login", {"username": "admin", "password": hashlib.md5(b"admin").hexdigest()})
    app.headers["Authorization"] = token
    api("simple/test")
    record("application_connected_to_real_qbittorrent", remote_root=REMOTE, mounted_root=str(LOCAL))

    def backfilled():
        current = tick()
        return current if len(current["selected"]) == 2 else False
    first = wait_until(backfilled, "two generated episodes downloaded and published", 180)
    assert first["selected"] == {"1": fallback1["hash"], "2": fallback2["hash"]}
    for release in first["releases"]:
        if release["id"] not in {fallback1["hash"], fallback2["hash"]}:
            continue
        expected = fallback1 if release["id"] == fallback1["hash"] else fallback2
        assert digest(release["videoPath"]) == digest(expected["video"])
        assert any(path.endswith(".chs.srt") for path in release["publishedFiles"])
        assert all(Path(path).is_file() for path in release["publishedFiles"])
    record("real_download_backfill_and_normalized_files", episodes=2, original_hashes_verified=True, chinese_sidecars=True)
    phase = 2
    upgraded = wait_until(lambda: (tick()["selected"].get("1") == preferred1["hash"]), "preferred version upgrade", 180)
    current = state()
    old = next(r for r in current["releases"] if r["id"] == fallback1["hash"])
    new = next(r for r in current["releases"] if r["id"] == preferred1["hash"])
    assert digest(old["videoPath"]) == digest(fallback1["video"])
    assert digest(new["publishedFiles"][0]) == digest(preferred1["video"])
    record("preferred_version_upgraded_without_deleting_original", playback_hash_verified=True)
    api("simple/choose", {"showId": show["id"], "releaseId": fallback1["hash"]})
    current = tick()
    assert current["selected"]["1"] == fallback1["hash"] and "1" in current["pinnedEpisodes"]
    assert digest(old["publishedFiles"][0]) == digest(fallback1["video"])
    record("manual_switch_and_pin", original_restored=True)
    api("simple/automatic", {"showId": show["id"], "episode": "1"})
    wait_until(lambda: tick()["selected"].get("1") == preferred1["hash"], "resume automatic upgrade", 60)
    record("automatic_selection_restored", passed=True)
    after = {task["hash"] for task in qbt.get(HOST + "/api/v2/torrents/info", timeout=15).json()}
    assert baseline.issubset(after), "An unrelated task was removed"
    record("existing_tasks_preserved", count=len(baseline))
    report = {"passed": True, "run": RUN, "downloader_version": version, "events": events,
              "playback_root": str(PLAYBACK), "library_root": str(LOCAL)}
    (ROOT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    record("end_to_end_result", passed=True, report=str(ROOT / "report.json"))
finally:
    app_process.terminate()
    try:
        app_process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        app_process.kill()
    server.shutdown()
    log.close()
