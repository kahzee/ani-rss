# Generated download fixtures

These three four-second videos and Chinese SRT files were generated locally with
FFmpeg for transport and version-switching tests. They contain only solid colors,
a generated sine wave and test captions; no anime or personal media is included.

The v1 torrent metadata points its HTTP web seed at this repository's raw files.
`tools/e2e-real-qbittorrent.py` uses these fixtures with a real qBittorrent client
and a separately configured local/container path mapping. Credentials are read
from environment variables and are never included in fixtures or reports.
