package ani.rss.auth;

import ani.rss.annotation.Auth;
import ani.rss.entity.Global;
import ani.rss.entity.web.Result;
import ani.rss.entity.web.ResultCode;
import ani.rss.exception.ResultException;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AuthAspect {
    @Before("@annotation(auth)")
    public void before(JoinPoint joinPoint, Auth auth) {
        HttpServletRequest request = Global.REQUEST.get();
        if (AuthUtil.test(request, auth)) {
            String renewed=AuthUtil.renewToken(request.getHeader("Authorization"));
            if(!renewed.isEmpty() && Global.RESPONSE.get()!=null) {
                Global.RESPONSE.get().setHeader("X-Renewed-Authorization",renewed);
            }
            // 鉴权通过
            return;
        }

        throw new ResultException(
                Result.error(r ->
                        r.setCode(401)
                                .setMessage("登录已失效")
                )
        );
    }
}
