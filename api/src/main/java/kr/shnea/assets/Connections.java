package kr.shnea.assets;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class Connections {
    record Create(String name,Integer days,Boolean canWrite) {}
    record Access(UUID owner,boolean canWrite) {}
    private final JdbcTemplate db;
    Connections(JdbcTemplate db) {this.db=db;}
    static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    Object list(UUID owner) {return db.queryForList("SELECT id,name,can_write,created_at,expires_at,last_used_at,revoked_at FROM mcp_connections WHERE owner_id=? ORDER BY (revoked_at IS NULL AND expires_at>now()) DESC,created_at DESC LIMIT 100",owner);}
    Object create(UUID owner,Create input) {
        String name=Content.text(input.name(),100);
        int days=input.days()==null?30:input.days();
        if(name.isBlank() || days<1 || days>90) throw Failure.invalid();
        if(db.queryForObject("SELECT count(*) FROM mcp_connections WHERE owner_id=? AND revoked_at IS NULL AND expires_at>now()",Long.class,owner)>=20)
            throw new Failure(409,"CONNECTION_LIMIT","사용하지 않는 연결을 해제한 뒤 추가해 주세요.");
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        String token="ast_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(days*86400L);
        boolean canWrite=Boolean.TRUE.equals(input.canWrite());
        db.update("INSERT INTO mcp_connections(id,owner_id,name,token_hash,expires_at,can_write) VALUES (?,?,?,?,?,?)",id,owner,name,hash(token),Timestamp.from(expires),canWrite);
        return Map.of("id",id,"token",token,"expiresAt",expires,"canWrite",canWrite);
    }
    void revoke(UUID owner,UUID id) {
        if(db.update("UPDATE mcp_connections SET revoked_at=coalesce(revoked_at,now()) WHERE id=? AND owner_id=?",id,owner)!=1) throw Failure.missing();
    }
    Access authorize(String header) {
        if(header==null || !header.matches("(?i:Bearer) ast_[A-Za-z0-9_-]{43}")) throw new Failure(401,"MCP_TOKEN_REQUIRED","에이전트 연결 키가 필요합니다.");
        var owners=db.query("SELECT owner_id,can_write FROM mcp_connections WHERE token_hash=? AND revoked_at IS NULL AND expires_at>now()",(rs,n)->new Access(rs.getObject(1,UUID.class),rs.getBoolean(2)),hash(header.substring(7)));
        if(owners.isEmpty()) throw new Failure(401,"MCP_TOKEN_INVALID","연결이 만료되었거나 해제되었습니다.");
        db.update("UPDATE mcp_connections SET last_used_at=now() WHERE token_hash=?",hash(header.substring(7)));
        return owners.getFirst();
    }
}
