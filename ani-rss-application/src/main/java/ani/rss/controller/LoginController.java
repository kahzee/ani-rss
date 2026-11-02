package ani.rss.controller;

import ani.rss.entity.web.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Compatibility response for older frontends; local mode has no login flow. */
@RestController
public class LoginController extends BaseController {
    @PostMapping("/login")
    public Result<Void> login() {
        return new Result<>(410, "本地模式已移除登录，请刷新页面");
    }
}
