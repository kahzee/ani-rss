"""Offline, non-destructive Windows -> container state export. No credentials in output.

Stop the source service first. Mount the SAME physical library/playback directories
at /resources and /playback. This tool does not move media or change qBittorrent.
Default is a dry run; --write creates a NEW configuration directory only.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path, PureWindowsPath, PurePosixPath
import shutil
import uuid


def relative(value, root):
    cls = PureWindowsPath if "\\" in root or (len(root) > 1 and root[1] == ":") else PurePosixPath
    path, base = cls(value), cls(root)
    try:
        result = path.relative_to(base)
    except ValueError as exc:
        raise ValueError("Recorded path lies outside its configured root") from exc
    if ".." in result.parts or not result.parts:
        raise ValueError("Unsafe or root-only media path")
    return result.as_posix()


def scope(root):
    return str(uuid.UUID(bytes=hashlib.md5(root.encode("utf-8")).digest(), version=3))


def convert(original):
    state = copy.deepcopy(original)
    settings = state["settings"]
    library, playback = settings["libraryRoot"], settings["playbackRoot"]
    for show in state["shows"]:
        for release in show["releases"]:
            for key in ("downloadPath", "videoPath"):
                if release.get(key):
                    release[key] = "/resources/" + relative(release[key], library)
            release["subtitles"] = ["/resources/" + relative(p, library) for p in release.get("subtitles", [])]
            release["publishedFiles"] = ["/playback/" + relative(p, playback) for p in release.get("publishedFiles", [])]
            release["mediaSignature"] = ""  # Absolute paths changed; re-probe on the new host.
    settings["libraryRoot"], settings["playbackRoot"] = "/resources", "/playback"
    # A directory association belongs to this physical NAS root; preserve the recorded confirmation.
    state["downloadCheckedAt"] = 0
    return state, scope(playback), scope("/playback")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Source config directory, with the service stopped")
    parser.add_argument("destination", type=Path, help="New, nonexistent container config directory")
    parser.add_argument("--write", action="store_true", help="Export after reviewing the dry-run counts")
    args = parser.parse_args()
    source, destination = args.source.resolve(), args.destination.resolve()
    if destination.exists() or source == destination or source in destination.parents:
        raise ValueError("Destination must be a new directory outside source config")
    if list((source / "simple-playback").rglob("*.transaction.json")):
        raise ValueError("Pending playback transaction: restart/recover the source before exporting")
    state_file = source / "simple-tracking.json"
    raw = state_file.read_bytes()
    original = json.loads(raw)
    state, old_scope, new_scope = convert(original)
    # Validate original resources now, not just the JSON path syntax.
    resources = 0
    for show in original["shows"]:
        selected = set(show.get("selected", {}).values())
        for release in show["releases"]:
            if release.get("status") == "ready":
                for name in [release.get("videoPath")] + release.get("subtitles", []):
                    if not name or not Path(name).is_file() or Path(name).stat().st_size == 0:
                        raise ValueError("A ready resource is missing or empty")
                resources += 1
            if release["id"] in selected:
                if not release.get("publishedFiles") or any(not Path(p).is_file() for p in release["publishedFiles"]):
                    raise ValueError("A selected playback copy is missing; recover before export")
    summary = {"shows": len(state["shows"]), "readyVersions": resources,
               "publishedEpisodes": sum(len(s.get("selected", {})) for s in state["shows"]),
               "libraryMount": "/resources", "playbackMount": "/playback",
               "downloaderRootUnchanged": True, "mediaMoved": False, "written": False}
    if args.write:
        if state_file.read_bytes() != raw or list((source / "simple-playback").rglob("*.transaction.json")):
            raise ValueError("Source changed during export; stop the source service and retry")
        destination.mkdir(parents=True, exist_ok=False)
        try:
            target = destination / "simple-tracking.json"
            target.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
            target.chmod(0o600)
            metadata = source / "simple-playback" / old_scope
            if metadata.exists():
                shutil.copytree(metadata, destination / "simple-playback" / new_scope)
            # Preserve login and upstream configuration, but never copy upstream subscriptions.
            for name in ("config.v2.json", "simple-mikan.json"):
                if (source / name).exists():
                    shutil.copyfile(source / name, destination / name)
                    (destination / name).chmod(0o600)
            if state_file.read_bytes() != raw:
                raise ValueError("Source changed; exported folder is incomplete and must not be deployed")
            (destination / "export-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
            summary["written"] = True
        except Exception:
            # Leave source and partial destination untouched for review, never recursively delete.
            raise
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
