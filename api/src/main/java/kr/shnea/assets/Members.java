package kr.shnea.assets;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class Members {
    record Member(UUID id, String displayName, boolean admin) {}
    private final JdbcTemplate db;
    private final String adminIssuer, adminSubject;
    Members(JdbcTemplate db, @Value("${assets.admin-issuer}") String issuer, @Value("${assets.admin-subject}") String subject) {
        this.db=db; this.adminIssuer=issuer; this.adminSubject=subject;
    }
    @Transactional
    public Member require(OidcUser user) {
        if(user==null) throw new Failure(401,"LOGIN_REQUIRED","로그인이 필요합니다.");
        String issuer=user.getIssuer().toString(), subject=user.getSubject();
        String name=Optional.ofNullable(user.getGivenName()).filter(v->!v.isBlank()).orElse("내 계정");
        if(name.length()>160) name=name.substring(0,160);
        db.update("INSERT INTO members(id,issuer,subject,display_name,base_quota) SELECT ?,?,?,?,default_quota FROM settings WHERE id=1 ON CONFLICT(issuer,subject) DO UPDATE SET display_name=excluded.display_name",UUID.randomUUID(),issuer,subject,name);
        UUID id=db.queryForObject("SELECT id FROM members WHERE issuer=? AND subject=?",UUID.class,issuer,subject);
        return new Member(id,name,!adminSubject.isBlank() && !adminIssuer.isBlank() && adminIssuer.equals(issuer) && adminSubject.equals(subject));
    }
    void admin(Member member) { if(!member.admin()) throw new Failure(403,"ADMIN_REQUIRED","운영 권한이 필요합니다."); }
    void event(UUID member,String kind) { db.update("INSERT INTO usage_events(member_id,kind) VALUES (?,?)",member,kind); }
}
