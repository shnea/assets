package kr.shnea.assets;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class Ideas {
    record Idea(UUID id,String title,Object body,long revision,Instant updatedAt) {}
    record Edit(String title,Object body,Long revision) {}
    private final JdbcTemplate db;
    private final JsonMapper json=new JsonMapper();
    Ideas(JdbcTemplate db) {this.db=db;}
    Idea row(ResultSet rs,int n) throws SQLException {return new Idea(rs.getObject("id",UUID.class),rs.getString("title"),json.readValue(rs.getString("body"),Object.class),rs.getLong("revision"),rs.getTimestamp("updated_at").toInstant());}
    Idea own(UUID owner,UUID id) {
        var rows=db.query("SELECT * FROM ideas WHERE owner_id=? AND id=?",this::row,owner,id);
        if(rows.isEmpty()) throw Failure.missing();
        return rows.getFirst();
    }
    Library.Page<Idea> list(UUID owner,String q,int offset,int limit) {
        if(q.length()>500 || offset<0 || limit<1 || limit>100) throw Failure.invalid();
        String where=" FROM ideas WHERE owner_id=? AND (?='' OR position(lower(?) in lower(title||' '||body::text))>0)";
        long total=db.queryForObject("SELECT count(*)"+where,Long.class,owner,q,q);
        var items=db.query("SELECT *"+where+" ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",this::row,owner,q,q,limit,offset);
        return new Library.Page<>(items,total,offset,offset+items.size()<total);
    }
    Idea create(UUID owner,Edit input) {
        UUID id=UUID.randomUUID();
        var body=RichText.normalize(input.body(),200000);verifyFiles(owner,id,body);
        db.update("INSERT INTO ideas(id,owner_id,title,body) VALUES (?,?,?,?::jsonb)",id,owner,Content.text(input.title(),200),json.writeValueAsString(body));
        return own(owner,id);
    }
    @Transactional
    public Idea update(UUID owner,UUID id,Edit input) {
        own(owner,id);
        if(input.revision()==null) throw Failure.invalid();
        var body=RichText.normalize(input.body(),200000);verifyFiles(owner,id,body);
        if(db.update("UPDATE ideas SET title=?,body=?::jsonb,revision=revision+1,updated_at=now() WHERE owner_id=? AND id=? AND revision=?",Content.text(input.title(),200),json.writeValueAsString(body),owner,id,input.revision())!=1) throw Failure.conflict();
        return own(owner,id);
    }
    void verifyFiles(UUID owner,UUID idea,Object body) {
        for(var id:RichText.files(body)) if(db.queryForObject("SELECT count(*) FROM files WHERE owner_id=? AND idea_id=? AND platform_file_id=? AND state='READY'",Long.class,owner,idea,id)!=1) throw Failure.invalid();
    }
}
