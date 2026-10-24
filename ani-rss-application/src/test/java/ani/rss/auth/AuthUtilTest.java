package ani.rss.auth;

import ani.rss.entity.Global;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.jwt.JWTUtil;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AuthUtilTest {
    private Boolean multi;
    private Integer hours;
    private String version;
    @BeforeEach void setup(){
        multi=ConfigUtil.CONFIG.getMultiLoginForbidden();hours=ConfigUtil.CONFIG.getLoginEffectiveHours();version=ConfigUtil.CONFIG.getTokenId();
        ConfigUtil.CONFIG.setMultiLoginForbidden(false).setLoginEffectiveHours(720).setVerifyLoginIp(false);
        Global.REQUEST.set(new MockHttpServletRequest());AuthUtil.resetSessionId();
    }
    @AfterEach void restore(){ConfigUtil.CONFIG.setMultiLoginForbidden(multi).setLoginEffectiveHours(hours).setTokenId(version);Global.REQUEST.remove();AuthUtil.resetSessionId();}
    private String expiring(long expiry){
        Map<String,Object> p=new HashMap<>(JWTUtil.parseToken(AuthUtil.getToken()).getPayloads());p.put("expireTime",expiry);
        return JWTUtil.createToken(p,Base64.decode(ConfigUtil.CONFIG.getJwtKey()));
    }
    @Test void additionalLoginAndSessionResetKeepExistingTokensValid(){
        String first=AuthUtil.getToken(),second=AuthUtil.getToken();AuthUtil.resetSessionId();
        assertTrue(AuthUtil.verify(first));assertTrue(AuthUtil.verify(second));
    }
    @Test void explicitSingleSessionModeStillInvalidatesAnEarlierLogin(){
        ConfigUtil.CONFIG.setMultiLoginForbidden(true);String first=AuthUtil.getToken(),second=AuthUtil.getToken();
        assertFalse(AuthUtil.verify(first));assertTrue(AuthUtil.verify(second));
    }
    @Test void switchingToPersistentLoginAcceptsAnUnexpiredPreviousSession(){
        ConfigUtil.CONFIG.setMultiLoginForbidden(true);String first=AuthUtil.getToken();AuthUtil.resetSessionId();
        ConfigUtil.CONFIG.setMultiLoginForbidden(false);assertTrue(AuthUtil.verify(first));
    }
    @Test void renewalPreservesConcurrentTokensAndExtendsExpiry(){
        String old=expiring(System.currentTimeMillis()+TimeUnit.HOURS.toMillis(1));String renewed=AuthUtil.renewToken(old);
        assertFalse(renewed.isBlank());assertTrue(AuthUtil.verify(old));assertTrue(AuthUtil.verify(renewed));
        assertTrue(JWTUtil.parseToken(renewed).getPayloads().getLong("expireTime")>System.currentTimeMillis()+TimeUnit.DAYS.toMillis(29));
        assertEquals(JWTUtil.parseToken(old).getPayloads().getStr("sessionId"),JWTUtil.parseToken(renewed).getPayloads().getStr("sessionId"));
        assertEquals("",AuthUtil.renewToken(renewed));
    }
    @Test void expiredForgedOrRevokedTokensCannotRenew(){
        String expired=expiring(System.currentTimeMillis()-1000);assertFalse(AuthUtil.verify(expired));assertEquals("",AuthUtil.renewToken(expired));
        assertEquals("",AuthUtil.renewToken("invalid-token"));
        String token=AuthUtil.getToken();ConfigUtil.CONFIG.setTokenId(UUID.randomUUID().toString());
        assertFalse(AuthUtil.verify(token));assertEquals("",AuthUtil.renewToken(token));
    }
}
