package kr.shnea.assets;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;

@RestController
class SessionController {
    private final Members members;
    private final String fileOrigin;
    private final String appUrl;
    SessionController(Members members,@Value("${assets.file-origin}") String fileOrigin,@Value("${assets.url}") String appUrl) {this.members=members;this.fileOrigin=fileOrigin;this.appUrl=appUrl.replaceAll("/+$","");}
    @GetMapping("/api/health") Object health() {return Map.of("status","ok");}
    @GetMapping("/api/session") Object session(@AuthenticationPrincipal OidcUser user, CsrfToken csrf) {
        if(user==null) return Map.of("authenticated",false,"csrfToken",csrf.getToken(),"appUrl",appUrl);
        return Map.of("authenticated",true,"member",members.require(user),"csrfToken",csrf.getToken(),"platformImageOrigin",fileOrigin,"appUrl",appUrl);
    }
}
