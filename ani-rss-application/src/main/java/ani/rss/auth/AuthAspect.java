package ani.rss.auth;

import ani.rss.annotation.Auth;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AuthAspect {
    @Before("@annotation(auth)")
    public void before(JoinPoint joinPoint, Auth auth) {
        // This local deployment intentionally has no account, token or expiry gate.
    }
}
