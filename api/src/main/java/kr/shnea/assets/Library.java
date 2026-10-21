package kr.shnea.assets;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class Library {
    record Asset(UUID id, Content draft, long revision, int version, boolean favorite, Instant updatedAt) {}
    record Version(int number,Content content,String changeNote,Instant createdAt) {}
    record Page<T>(List<T> items,long total,int offset,boolean hasMore) {}
    record Draft(Content content,Long revision) {}
    record Save(Long revision,String changeNote) {}
    record Favorite(boolean favorite) {}
    private final JdbcTemplate db;
    private final Members members;
    private final JsonMapper json=new JsonMapper();
    Library(JdbcTemplate db,Members members) {this.db=db;this.members=members;}
    String encode(Object value) {return json.writeValueAsString(value);}
    Content decode(String value) {return json.readValue(value,Content.class);}
    Asset row(ResultSet rs,int n) throws SQLException {
        return new Asset(rs.getObject("id",UUID.class),decode(rs.getString("draft")),rs.getLong("revision"),rs.getInt("version"),rs.getBoolean("favorite"),rs.getTimestamp("updated_at").toInstant());
    }
    Asset own(UUID owner,UUID id,boolean lock) {
        var result=db.query("SELECT * FROM assets WHERE owner_id=? AND id=?"+(lock?" FOR UPDATE":""),this::row,owner,id);
        if(result.isEmpty()) throw Failure.missing();
        return result.getFirst();
    }
    @Transactional
    public Asset create(UUID owner,Content input) {
        if(input==null) throw Failure.invalid();
        var content=input.normalize();
        if(!content.fileIds().isEmpty()) throw Failure.invalid();
        UUID id=UUID.randomUUID();
        verifyFiles(owner,id,content);
        db.update("INSERT INTO assets(id,owner_id,draft) VALUES (?,?,?::jsonb)",id,owner,encode(content));
        members.event(owner,"ASSET_CREATE");
        return own(owner,id,false);
    }
    Page<Asset> search(UUID owner,String query,String category,String filter,int offset,int limit,boolean published) {
        if(offset<0 || limit<1 || limit>100 || query.length()>500 || category.length()>100) throw Failure.invalid();
        String source=published?"v.content":"a.draft";
        String from=" FROM assets a "+(published?"JOIN asset_versions v ON v.asset_id=a.id AND v.number=a.version ":"");
        String where=" WHERE a.owner_id=? AND (?='' OR position(lower(?) in lower("+source+"::text))>0) AND (?='' OR "+source+"->>'category'=?)";
        where+=switch(filter) {case "all"->"";case "favorites"->" AND a.favorite";case "drafts"->" AND a.version=0";case "unclassified"->" AND "+source+"->>'category'=''";default->throw Failure.invalid();};
        var args=new Object[]{owner,query,query,category,category};
        long total=db.queryForObject("SELECT count(*)"+from+where,Long.class,args);
        var paged=new ArrayList<Object>(Arrays.asList(args));paged.add(limit);paged.add(offset);
        String columns=published?"a.id, v.content AS draft, a.revision, a.version, a.favorite, v.created_at AS updated_at":"a.*";
        var items=db.query("SELECT "+columns+from+where+" ORDER BY a.updated_at DESC,a.id LIMIT ? OFFSET ?",this::row,paged.toArray());
        return new Page<>(items,total,offset,offset+items.size()<total);
    }
    Asset read(UUID owner,UUID id) {var asset=own(owner,id,false);members.event(owner,"ASSET_VIEW");return asset;}
    List<String> categories(UUID owner) {
        return db.queryForList("SELECT DISTINCT draft->>'category' AS category FROM assets WHERE owner_id=? AND coalesce(draft->>'category','')<>'' ORDER BY category",String.class,owner);
    }
    @Transactional
    public Asset update(UUID owner,UUID id,Draft input) {
        var asset=own(owner,id,true);
        if(input==null || input.content()==null || input.revision()==null) throw Failure.invalid();
        if(asset.revision()!=input.revision()) throw Failure.conflict();
        var content=input.content().normalize();
        verifyFiles(owner,id,content);
        db.update("UPDATE assets SET draft=?::jsonb,revision=revision+1,updated_at=now() WHERE id=? AND owner_id=?",encode(content),id,owner);
        members.event(owner,"ASSET_WRITE");
        return own(owner,id,false);
    }
    Set<UUID> verifyFiles(UUID owner,UUID asset,Content content) {
        var ids=new LinkedHashSet<UUID>();
        for(var file:content.fileIds()) ids.add(resolveFile(owner,asset,file,false));
        for(var file:content.inlineFiles()) ids.add(resolveFile(owner,asset,file,true));
        return ids;
    }
    UUID resolveFile(UUID owner,UUID asset,UUID file,boolean platform) {
        var rows=db.query("SELECT id FROM files WHERE "+(platform?"platform_file_id":"id")+"=? AND owner_id=? AND asset_id=? AND state='READY'",(rs,n)->rs.getObject(1,UUID.class),file,owner,asset);
        if(rows.size()!=1) throw new Failure(400,"FILE_NOT_AVAILABLE","자산에 연결된 업로드 완료 파일만 선택해 주세요.");
        return rows.getFirst();
    }
    @Transactional
    public Version save(UUID owner,UUID id,Save input) {
        var asset=own(owner,id,true);
        if(input==null || input.revision()==null) throw Failure.invalid();
        if(asset.revision()!=input.revision()) throw Failure.conflict();
        if(!asset.draft().hasContent()) throw new Failure(400,"CONTENT_REQUIRED","세 가지 질문 중 하나에 내용을 남겨 주세요.");
        if(asset.draft().pendingAttachments()) throw new Failure(409,"UPLOAD_PENDING","첨부 업로드를 완료한 뒤 버전으로 저장해 주세요.");
        var files=verifyFiles(owner,id,asset.draft());
        int next=asset.version()+1;
        db.update("INSERT INTO asset_versions(asset_id,number,content,change_note) VALUES (?,?,?::jsonb,?)",id,next,encode(asset.draft()),Content.text(input.changeNote(),500));
        for(var file:files) db.update("INSERT INTO asset_version_files(asset_id,number,file_id) VALUES (?,?,?)",id,next,file);
        db.update("UPDATE assets SET version=?,revision=revision+1,updated_at=now() WHERE id=? AND owner_id=?",next,id,owner);
        members.event(owner,"ASSET_WRITE");
        return version(owner,id,next);
    }
    List<Version> versions(UUID owner,UUID id) {
        own(owner,id,false);
        return db.query("SELECT number,content,change_note,created_at FROM asset_versions WHERE asset_id=? ORDER BY number DESC",(rs,n)->new Version(rs.getInt(1),decode(rs.getString(2)),rs.getString(3),rs.getTimestamp(4).toInstant()),id);
    }
    Version version(UUID owner,UUID id,int number) {
        own(owner,id,false);
        var rows=db.query("SELECT number,content,change_note,created_at FROM asset_versions WHERE asset_id=? AND number=?",(rs,n)->new Version(rs.getInt(1),decode(rs.getString(2)),rs.getString(3),rs.getTimestamp(4).toInstant()),id,number);
        if(rows.isEmpty()) throw Failure.missing();
        return rows.getFirst();
    }
    Asset favorite(UUID owner,UUID id,boolean value) {
        if(db.update("UPDATE assets SET favorite=? WHERE owner_id=? AND id=?",value,owner,id)!=1) throw Failure.missing();
        return own(owner,id,false);
    }
}
