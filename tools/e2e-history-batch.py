"""Isolated real qBittorrent test: eight existing episodes, one twelve-episode torrent.

Generated media only. Reads the existing local config for downloader credentials;
never prints credentials. Does not delete, pause or alter unrelated torrents.
Requires requests and libtorrent; see the existing E2E Python environment.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlsplit
import requests
import libtorrent as lt

REPO = Path(__file__).resolve().parents[1]
ROOT = Path(os.environ["E2E_ROOT"]).resolve()
ROOT.mkdir(parents=True, exist_ok=False)
settings = json.loads((REPO / "config/simple-tracking.json").read_text(encoding="utf-8"))["settings"]
RUN = ROOT.name
LOCAL = Path(os.environ["LOCAL_DOWNLOAD_ROOT"]) / RUN
REMOTE = os.environ["REMOTE_DOWNLOAD_ROOT"].rstrip("/") + "/" + RUN
PLAYBACK = Path(os.environ["PLAYBACK_ROOT"]) / RUN
LAN = os.environ["E2E_LAN_IP"]
PORT = 17793
APP = "http://127.0.0.1:17792"
SEEDS = ROOT / "seeds"
pack_name = "history-batch-" + RUN
folder = SEEDS / pack_name
folder.mkdir(parents=True)
sample = Path(os.environ["E2E_SAMPLE_VIDEO"])
for ep in range(1, 13):
    shutil.copyfile(sample, folder / f"Proof S01E{ep:02d}.mp4")
    (folder / f"Proof S01E{ep:02d}.chs.srt").write_text(f"1\n00:00:00,000 --> 00:00:03,800\n历史追番联调：第{ep}集\n", encoding="utf-8")
storage = lt.file_storage()
for path in sorted(folder.iterdir()): storage.add_file(f"{pack_name}/{path.name}", path.stat().st_size)
creator = lt.create_torrent(storage, 16384, flags=lt.create_torrent.v1_only)
creator.add_url_seed(f"http://{LAN}:{PORT}/payload/")
lt.set_piece_hashes(creator, str(SEEDS))
metadata = lt.bencode(creator.generate())
info = lt.torrent_info(metadata)
HASH = str(info.info_hash())
(SEEDS / "proof.torrent").write_bytes(metadata)
resource = {"id": 1, "title": "[TestHistory] 历史追番联调样片 [01-12] [CHS]", "type": "合集",
            "magnet": lt.make_magnet_uri(info), "href": f"http://{LAN}:{PORT}/payload/proof.torrent",
            "fansub": {"id": 1, "name": "TestHistory"}, "createdAt": "2020-01-01T00:00:00Z"}

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        route = urlsplit(self.path).path
        if route == "/resources":
            data = json.dumps({"resources": [resource]}).encode()
        elif route.startswith("/payload/"):
            path = (SEEDS / unquote(route[9:])).resolve()
            if not path.is_relative_to(SEEDS) or not path.is_file(): return self.send_error(404)
            data = path.read_bytes()
        else: return self.send_error(404)
        start, end = 0, len(data) - 1
        byte_range = self.headers.get("Range", "")
        if byte_range.startswith("bytes="):
            begin, finish = byte_range[6:].split("-", 1)
            start, end = int(begin or 0), min(int(finish or end), end)
        self.send_response(206 if byte_range else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        if byte_range: self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
        self.send_header("Content-Length", str(end-start+1)); self.end_headers()
        self.wfile.write(data[start:end+1])

server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
config = ROOT / "config"
config.mkdir()
(config / "history").mkdir()
with sqlite3.connect(config / "history/catalog.db") as db:
    db.execute("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)")
    db.execute("INSERT INTO meta VALUES('nextCheckAt',?)", (str(int(time.time()*1000)+31*86400000),))
settings.update(libraryRoot=str(LOCAL), downloaderRoot=REMOTE, playbackRoot=str(PLAYBACK),
                trustedGroups=["TestHistory"], initialGroup="", simultaneousDownloads=10)
show = {"id": "987654322", "title": "历史追番联调样片", "folder": "历史追番联调样片 [bgm-987654322]", "totalEpisodes": 12,
        "historical": True, "episodeMetadataAt": int(time.time()*1000),
        "episodeIds": {str(i): f"proof-{i}" for i in range(1, 13)},
        "episodeNumbers": {str(i): i for i in range(1, 13)}, "selected": {}, "releases": []}
for ep in range(1, 9):
    rid = "existing" + str(ep)
    source = LOCAL / show["id"] / rid
    source.mkdir(parents=True)
    video, sub = source / "original.mp4", source / "original.chs.srt"
    shutil.copyfile(sample, video); shutil.copyfile(folder / f"Proof S01E{ep:02d}.chs.srt", sub)
    season = PLAYBACK / show["folder"] / "历史追番联调样片 第01季"
    season.mkdir(parents=True, exist_ok=True)
    published = [season / f"历史追番联调样片 S01E{ep:02d}.mp4", season / f"历史追番联调样片 S01E{ep:02d}.chs.srt"]
    shutil.copyfile(video, published[0]); shutil.copyfile(sub, published[1])
    show["selected"][str(ep)] = rid
    show["releases"].append({"id": rid, "title": f"历史追番联调样片 - {ep:02d} [CHS]", "group": "TestHistory", "episode": ep,
                             "chinese": True, "status": "ready", "downloadPath": str(source), "videoPath": str(video), "subtitles": [str(sub)],
                             "publishedFiles": [str(p) for p in published]})
(config / "simple-tracking.json").write_text(json.dumps({"settings": settings, "shows": [show]}, ensure_ascii=False), encoding="utf-8")
qbt = requests.Session()
assert qbt.post(settings["downloadHost"]+"/api/v2/auth/login", data={"username":settings["username"],"password":settings["credential"]}, timeout=10).text.strip()=="Ok."
baseline = {t["hash"] for t in qbt.get(settings["downloadHost"]+"/api/v2/torrents/info",timeout=20).json()}
process = None
log = (ROOT / "application.log").open("w",encoding="utf-8")
def start():
    return subprocess.Popen([os.environ["E2E_JAVA"], "-Dfile.encoding=UTF-8", "-Dsimple.ffprobe="+os.environ["E2E_FFPROBE"], "-jar",str(REPO / "ani-rss-application/target/ani-rss.jar"),
                             f"--config={config}","--server.port=17792","--server.address=127.0.0.1",f"--simple.catalog-url=http://127.0.0.1:{PORT}"],stdout=log,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
session = requests.Session()
def api(name, body=None):
    result=session.post(APP+"/api/"+name,json=body or {},timeout=20).json()
    assert result["code"]==200,(name,result.get("message"))
    return result.get("data")
def login():
    for _ in range(40):
        try:
            token=api("login",{"username":"admin","password":hashlib.md5(b"admin").hexdigest()});session.headers["Authorization"]=token;return
        except (requests.RequestException,ValueError):time.sleep(1)
    raise AssertionError("App startup timeout")
def wait_complete():
    last=None
    for _ in range(100):
        api("simple/refresh")
        time.sleep(2)
        state=api("simple/state");s=state["shows"][0]
        own=qbt.get(settings["downloadHost"]+"/api/v2/torrents/info",params={"hashes":HASH},timeout=10).json()
        summary=(len(s["selected"]),[(t["state"],round(t["progress"],3)) for t in own])
        if summary!=last: print(summary,flush=True);last=summary
        if len(s["selected"])==12:return state
    raise AssertionError("Batch backfill timeout; inspect isolated application.log")
try:
    process=start();login();state=wait_complete();actual=state["shows"][0]
    assert all(actual["selected"][str(i)]=="existing"+str(i) for i in range(1,9))
    priorities=qbt.get(settings["downloadHost"]+"/api/v2/torrents/files",params={"hash":HASH},timeout=10).json()
    for f in priorities:
        for i in range(1,9):
            if f"S01E{i:02d}." in f["name"]: assert f["priority"]==0,"Existing episode was requested again"
    hashes=[]
    for ep in range(9,13):
        r=next(r for r in actual["releases"] if r["id"]==actual["selected"][str(ep)])
        assert r["torrentHash"]==HASH and r["mediaSignature"]
        video=next(p for p in r["publishedFiles"] if p.endswith(".mp4"))
        digest=hashlib.sha256(Path(video).read_bytes()).hexdigest();assert digest==hashlib.sha256(sample.read_bytes()).hexdigest();hashes.append(digest)
    process.terminate();process.wait(timeout=20);session=requests.Session();process=start();login();time.sleep(5)
    restarted=api("simple/state");assert len(restarted["shows"][0]["selected"])==12
    after=qbt.get(settings["downloadHost"]+"/api/v2/torrents/info",timeout=20).json()
    assert baseline.issubset({t["hash"] for t in after})
    report={"existingEpisodes":8,"expectedEpisodes":12,"publishedEpisodes":12,"downloadedMissingEpisodes":[9,10,11,12],"torrentHash":HASH,
            "oneSharedTask":len(restarted["downloads"])==1,"existingEightNotRequestedAgain":True,"publishedHashesMatch":True,"restartPreservesSelections":True,"originalTasksPreserved":True,
            "media":"self-generated four-second sample with Chinese sidecars","transport":"real NAS qBittorrent with LAN HTTP web seed; not public swarm availability"}
    (ROOT / "result.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8");print(json.dumps(report,ensure_ascii=False),flush=True)
finally:
    if process and process.poll() is None: process.terminate();process.wait(timeout=20)
    server.shutdown();log.close()
