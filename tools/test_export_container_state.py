import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("exporter", Path(__file__).with_name("export-container-state.py"))
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class ContainerStateTest(unittest.TestCase):
    def test_converts_paths_and_preserves_pins_and_downloader_namespace(self):
        state = {"settings": {"libraryRoot": "K:\\nvme\\resources", "playbackRoot": "K:\\sata\\playback",
                              "downloaderRoot": "/downloads/ani-rss-simple", "credential": "fixture-secret"},
                 "shows": [{"selected": {"1": "hash"}, "pinnedEpisodes": ["1"], "releases": [
                     {"downloadPath": "K:\\nvme\\resources\\123\\hash", "videoPath": "K:\\nvme\\resources\\123\\hash\\file.mkv",
                      "subtitles": ["K:\\nvme\\resources\\123\\hash\\file.ass"],
                      "publishedFiles": ["K:\\sata\\playback\\show\\season\\file.mkv"]}]}]}
        converted, old, new = exporter.convert(state)
        release = converted["shows"][0]["releases"][0]
        self.assertEqual("/resources/123/hash/file.mkv", release["videoPath"])
        self.assertEqual(["/playback/show/season/file.mkv"], release["publishedFiles"])
        self.assertEqual(["1"], converted["shows"][0]["pinnedEpisodes"])
        self.assertEqual("/downloads/ani-rss-simple", converted["settings"]["downloaderRoot"])
        self.assertNotEqual(old, new)
        self.assertEqual("K:\\nvme\\resources", state["settings"]["libraryRoot"])

    def test_rejects_escape_and_prefix_lookalikes(self):
        for value in ["C:\\private\\file.mkv", "K:\\media-other\\file.mkv", "K:\\media\\..\\private\\file.mkv", "K:\\media"]:
            with self.assertRaises(ValueError): exporter.relative(value, "K:\\media")

    def test_preserves_unicode_and_uses_java_name_uuid_scope(self):
        self.assertEqual("番剧/第一集.mkv", exporter.relative("/媒体/番剧/第一集.mkv", "/媒体"))
        self.assertEqual("90015098-3cd2-3fb0-9696-3f7d28e17f72", exporter.scope("abc"))


if __name__ == "__main__": unittest.main()
