package ani.rss.simple;

import ani.rss.simple.SimpleModel.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchMapperTest {
    Show show(){Show s=new Show();s.setId("123");s.setTitle("历史番剧");s.setSeason(2);s.setTotalEpisodes(12);for(int i=1;i<=12;i++){s.getEpisodeIds().put(""+i,"ep-"+i);s.getEpisodeNumbers().put(""+i,(double)i);s.getEpisodeNumbers().put(""+(i+12),(double)i);}return s;}
    Release pack(){Release r=new Release();r.setId("a".repeat(40));r.setBatch(true);r.setGroup("ANi");r.setChinese(true);r.setTitle("历史番剧 S02 Complete [CHS]");return r;}
    DownloadTask task(String...names){DownloadTask t=new DownloadTask();t.setHash("a".repeat(40));t.setPath("/resources/_torrents/"+t.getHash());int i=0;for(String name:names){MediaFile f=new MediaFile();f.setIndex(i++*3+2);f.setName(name);f.setSize(10);t.getFiles().add(f);}return t;}
    @Test void mapsTwelveFilesToOneTorrentAndPairsOnlyMatchingSubtitle(){String[] names=new String[13];for(int i=1;i<=12;i++)names[i-1]="Season 2/番剧 S02E"+String.format("%02d",i)+".mkv";names[12]="Season 2/番剧 S02E01.chs.ass";
        var versions=BatchMapper.map(show(),pack(),task(names));assertEquals(12,versions.size());assertEquals(1,versions.stream().map(Release::getTorrentHash).distinct().count());assertEquals(List.of(38),versions.getFirst().getSubtitleIndices());assertEquals("ep-1",versions.getFirst().getEpisodeId());}
    @Test void excludesWrongSeasonSpecialsAndMultiEpisodeVideos(){assertTrue(BatchMapper.map(show(),pack(),task("S01E01.mkv","NCOP 01.mkv","SP01.mkv","S02E01-E02.mkv","01-02.mkv")).isEmpty());assertTrue(BatchMapper.special("番 [SP01] [CHS]"));assertTrue(BatchMapper.special("番 NCOP 01"));assertFalse(BatchMapper.special("[ANi] 番 - 01 [CHS]"));}
    @Test void rejectsDuplicateEpisodeClaimsAndUnknownDirectory(){assertTrue(BatchMapper.map(show(),pack(),task("A S02E01.mkv","B S02E01.mkv")).isEmpty());Show unknown=show();unknown.getEpisodeIds().clear();assertTrue(BatchMapper.map(unknown,pack(),task("01.mp4")).isEmpty());}
    @Test void bareNumbersAndCumulativeNumbersUseActualDirectory(){var versions=BatchMapper.map(show(),pack(),task("01.mkv","番剧 - 14.mp4"));assertEquals(List.of(1d,2d),versions.stream().map(Release::getEpisode).toList());}
    @Test void fileIndicesMustBeReturnedByDownloaderNotGuessedFromArrayPosition(){JsonArray rows=JsonParser.parseString("[{\"index\":17,\"name\":\"01.mkv\",\"size\":12,\"progress\":0.5}]").getAsJsonArray();assertEquals(17,BatchMapper.files(rows).getFirst().getIndex());rows.get(0).getAsJsonObject().remove("index");assertThrows(IllegalArgumentException.class,()->BatchMapper.files(rows));}
}
