package ani.rss.simple;

import ani.rss.annotation.Auth;
import ani.rss.entity.web.Result;
import ani.rss.simple.SimpleModel.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class SimpleTrackingController {
    private final SimpleTrackingService service;
    private final MikanDiscovery mikan;

    @Auth @PostMapping("/simple/discover")
    public Result<MikanDiscovery.Snapshot> discover() {
        mikan.ensureLoaded(); return Result.success(mikan.snapshot());
    }

    @Auth @PostMapping("/simple/follow-mikan")
    public Result<Show> followMikan(@RequestBody Map<String,String> request) throws Exception {
        return Result.success(service.follow(mikan.subject(request.get("mikanId"))));
    }

    @Auth @PostMapping("/simple/state")
    public Result<State> state() { return Result.success(service.state()); }

    @Auth @PostMapping("/simple/settings")
    public Result<Void> settings(@RequestBody Settings settings) throws Exception {
        service.settings(settings); service.refresh(); return Result.success();
    }

    @Auth @PostMapping("/simple/test")
    public Result<Void> test() throws Exception { return Result.success(service.testDownloader()); }

    @Auth @PostMapping("/simple/follow")
    public Result<Show> follow(@RequestBody FollowRequest request) throws Exception { return Result.success(service.follow(request.getSubjectId())); }

    @Auth @PostMapping("/simple/detail")
    public Result<Map<String, Object>> detail(@RequestParam("id") String id) throws Exception { return Result.success(service.detail(id)); }

    @Auth @PostMapping("/simple/preferences")
    public Result<Void> preferences(@RequestBody PreferenceRequest request) throws Exception {
        service.preferences(request); service.refresh(); return Result.success();
    }

    @Auth @PostMapping("/simple/choose")
    public Result<Void> choose(@RequestBody VersionRequest request) throws Exception { service.choose(request); return Result.success(); }

    @Auth @PostMapping("/simple/download")
    public Result<Void> download(@RequestBody VersionRequest request) throws Exception { service.requestDownload(request); return Result.success(); }

    @Auth @PostMapping("/simple/automatic")
    public Result<Void> automatic(@RequestBody AutoRequest request) throws Exception { service.automatic(request); return Result.success(); }

    @Auth @PostMapping("/simple/refresh")
    public Result<Void> refresh() { service.refresh(); return Result.success(); }
}
