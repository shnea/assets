package kr.shnea.assets;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class Operations {
    record DefaultQuota(Long bytes,boolean applyExisting) {}
    record MemberQuota(Long bytes) {}
    private final JdbcTemplate db;
    private final Members members;
    Operations(JdbcTemplate db,Members members) {this.db=db;this.members=members;}
    Object stats(Members.Member actor,int days) {
        members.admin(actor);if(days<1 || days>365) throw Failure.invalid();
        var start=java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(days*86400L));
        var result=new LinkedHashMap<String,Object>();
        result.put("days",days);
        result.put("registrations",db.queryForObject("SELECT count(*) FROM members",Long.class));
        result.put("newMembers",db.queryForObject("SELECT count(*) FROM members WHERE created_at>=?",Long.class,start));
        result.put("activeMembers",db.queryForObject("SELECT count(DISTINCT member_id) FROM usage_events WHERE occurred_at>=? AND kind<>'MCP_FAILURE'",Long.class,start));
        var events=db.queryForList("SELECT kind,count(*) AS count FROM usage_events WHERE occurred_at>=? GROUP BY kind",start);
        for(var kind:List.of("ASSET_CREATE","ASSET_WRITE","ASSET_VIEW","MCP_SUCCESS","MCP_FAILURE"))
            result.put(kind,events.stream().filter(e->kind.equals(e.get("kind"))).map(e->e.get("count")).findFirst().orElse(0L));
        result.put("storage",db.queryForMap("SELECT count(*) AS files,coalesce(sum(size_bytes),0) AS bytes FROM files WHERE state='READY'"));
        result.put("registrationsByDay",db.queryForList("SELECT created_at::date AS day,count(*) AS count FROM members WHERE created_at>=? GROUP BY created_at::date ORDER BY day",start));
        result.put("defaultQuota",db.queryForObject("SELECT default_quota FROM settings WHERE id=1",Long.class));
        return result;
    }
    Object quotas(Members.Member actor,int offset) {
        members.admin(actor);if(offset<0) throw Failure.invalid();
        return db.queryForList("SELECT m.id,m.display_name,m.base_quota,m.quota_override,coalesce(sum(f.size_bytes) FILTER(WHERE f.state='READY'),0) AS used_bytes,coalesce(sum(f.size_bytes) FILTER(WHERE f.state='UPLOADING'),0) AS reserved_bytes FROM members m LEFT JOIN files f ON f.owner_id=m.id GROUP BY m.id ORDER BY m.created_at DESC,m.id LIMIT 50 OFFSET ?",offset);
    }
    static void valid(Long bytes) {if(bytes==null || bytes<0 || bytes>1_000_000_000_000_000L) throw Failure.invalid();}
    @Transactional public void defaults(Members.Member actor,DefaultQuota input) {
        members.admin(actor);valid(input.bytes());
        db.update("UPDATE settings SET default_quota=? WHERE id=1",input.bytes());
        if(input.applyExisting()) db.update("UPDATE members SET base_quota=?",input.bytes());
    }
    @Transactional public void quota(Members.Member actor,UUID id,MemberQuota input) {
        members.admin(actor);if(input.bytes()!=null) valid(input.bytes());
        if(db.update("UPDATE members SET quota_override=?,base_quota=(SELECT default_quota FROM settings WHERE id=1) WHERE id=?",input.bytes(),id)!=1) throw Failure.missing();
    }
}
