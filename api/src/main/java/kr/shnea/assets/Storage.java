package kr.shnea.assets;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;

@Service
class Storage {
    record Usage(long usedBytes,long reservedBytes,long quotaBytes,long fileCount) {}
    record Begin(UUID requestId,UUID assetId,UUID ideaId,String name,Long size,String sha256) {}
    record File(UUID id,UUID ownerId,UUID assetId,UUID ideaId,String name,long size,String sha256,
                UUID uploadId,UUID platformId,long receivedBytes,String state,Instant expiresAt) {}
    private final JdbcTemplate db;
    private final Platform platform;
    private final Library library;
    private final Ideas ideas;
    private final TransactionTemplate tx;
    Storage(JdbcTemplate db,Platform platform,Library library,Ideas ideas,TransactionTemplate tx) {
        this.db=db;this.platform=platform;this.library=library;this.ideas=ideas;this.tx=tx;
    }
    Usage usage(UUID owner) {
        long quota=db.queryForObject("SELECT coalesce(quota_override,base_quota) FROM members WHERE id=?",Long.class,owner);
        return db.queryForObject("SELECT coalesce(sum(size_bytes) FILTER(WHERE state='READY'),0),coalesce(sum(size_bytes) FILTER(WHERE state='UPLOADING'),0),count(*) FILTER(WHERE state='READY') FROM files WHERE owner_id=?",
            (rs,n)->new Usage(rs.getLong(1),rs.getLong(2),quota,rs.getLong(3)),owner);
    }
    File row(ResultSet rs,int n) throws SQLException {
        return new File(rs.getObject("id",UUID.class),rs.getObject("owner_id",UUID.class),rs.getObject("asset_id",UUID.class),rs.getObject("idea_id",UUID.class),rs.getString("name"),rs.getLong("size_bytes"),rs.getString("sha256"),rs.getObject("platform_upload_id",UUID.class),rs.getObject("platform_file_id",UUID.class),rs.getLong("received_bytes"),rs.getString("state"),rs.getTimestamp("expires_at").toInstant());
    }
    File own(UUID owner,UUID id,boolean lock) {
        var rows=db.query("SELECT * FROM files WHERE owner_id=? AND id=?"+(lock?" FOR UPDATE":""),this::row,owner,id);
        if(rows.isEmpty()) throw Failure.missing();
        return rows.getFirst();
    }
    List<File> list(UUID owner,UUID asset,UUID idea) {
        if((asset==null)==(idea==null)) throw Failure.invalid();
        if(asset!=null) library.own(owner,asset,false); else ideas.own(owner,idea);
        return db.query("SELECT * FROM files WHERE owner_id=? AND "+(asset!=null?"asset_id":"idea_id")+"=? AND state IN ('READY','UPLOADING') ORDER BY created_at",this::row,owner,asset!=null?asset:idea);
    }
    List<File> references(UUID owner,UUID asset,int number) {
        library.version(owner,asset,number);
        return db.query("SELECT f.* FROM files f JOIN asset_version_files r ON r.file_id=f.id WHERE f.owner_id=? AND f.asset_id=? AND r.asset_id=? AND r.number=? AND f.state='READY'",this::row,owner,asset,asset,number);
    }
    Object editorViews(UUID owner,UUID platformId) {
        var rows=db.query("SELECT * FROM files WHERE owner_id=? AND platform_file_id=? AND state='READY'",this::row,owner,platformId);
        if(rows.isEmpty()) throw Failure.missing();
        var views=platform.views(platformId);
        if(!platformId.toString().equals(String.valueOf(views.get("fileId")))) throw new Failure(502,"FILE_VIEW_MISMATCH","파일 보기 정보를 확인해 주세요.");
        return views;
    }
    @Transactional
    public File begin(UUID owner,Begin input) {
        if(input.requestId()==null || input.size()==null || input.size()<0 || input.size()>5_000_000_000L || input.sha256()==null || !input.sha256().matches("[a-f0-9]{64}") || (input.assetId()==null)==(input.ideaId()==null)) throw Failure.invalid();
        String name=Content.text(input.name(),255);if(name.isBlank()) throw Failure.invalid();
        db.queryForObject("SELECT id FROM members WHERE id=? FOR UPDATE",UUID.class,owner);
        if(input.assetId()!=null) library.own(owner,input.assetId(),true);else ideas.own(owner,input.ideaId());
        var old=db.query("SELECT * FROM files WHERE id=?",this::row,input.requestId());
        if(!old.isEmpty()) {
            var previous=old.getFirst();
            if(!previous.ownerId().equals(owner) || !Objects.equals(previous.assetId(),input.assetId()) || !Objects.equals(previous.ideaId(),input.ideaId()) || !previous.name().equals(name) || previous.size()!=input.size() || !previous.sha256().equals(input.sha256())) throw Failure.conflict();
            return previous;
        }
        var usage=usage(owner);
        if(input.size()>usage.quotaBytes()-usage.usedBytes()-usage.reservedBytes()) throw new Failure(413,"QUOTA_EXCEEDED","저장 공간이 부족합니다. 기존 자료는 계속 읽을 수 있습니다.");
        var upload=platform.begin(input.requestId(),name,input.size(),input.sha256());
        UUID remote=UUID.fromString(upload.get("uploadId").toString());
        var expiry=Instant.parse(upload.get("expiresAt").toString());
        db.update("INSERT INTO files(id,owner_id,asset_id,idea_id,name,size_bytes,sha256,platform_upload_id,expires_at) VALUES (?,?,?,?,?,?,?,?,?)",
            input.requestId(),owner,input.assetId(),input.ideaId(),name,input.size(),input.sha256(),remote,Timestamp.from(expiry));
        return own(owner,input.requestId(),false);
    }
    @Transactional
    public File status(UUID owner,UUID id) {
        var file=own(owner,id,true);
        if(!file.state().equals("UPLOADING")) return file;
        var remote=platform.status(file.uploadId());
        applyStatus(file,remote);
        return own(owner,id,false);
    }
    void applyStatus(File file,Map<String,Object> remote) {
        String state=String.valueOf(remote.get("state"));
        if(state.equals("READY")) {
            UUID platformId=UUID.fromString(remote.get("fileId").toString());
            db.update("UPDATE files SET state='READY',platform_file_id=?,received_bytes=size_bytes WHERE id=?",platformId,file.id());
        } else if(Set.of("CANCELLED","EXPIRED").contains(state)) db.update("UPDATE files SET state='CANCELLED' WHERE id=?",file.id());
        else db.update("UPDATE files SET received_bytes=? WHERE id=?",((Number)remote.get("receivedBytes")).longValue(),file.id());
    }
    @Transactional
    public File append(UUID owner,UUID id,long offset,String hash,byte[] chunk) {
        var file=own(owner,id,true);
        if(!file.state().equals("UPLOADING") || file.expiresAt().isBefore(Instant.now())) throw new Failure(409,"UPLOAD_CLOSED","업로드를 새로 시작해 주세요.");
        if(chunk.length<1 || chunk.length>8_388_608 || offset<0 || offset>file.size()-chunk.length || hash==null || !hash.matches("[a-f0-9]{64}")) throw Failure.invalid();
        applyStatus(file,platform.append(file.uploadId(),offset,hash,chunk));
        return own(owner,id,false);
    }
    @Transactional
    public File complete(UUID owner,UUID id) {
        var file=own(owner,id,true);
        if(file.state().equals("READY")) return file;
        if(!file.state().equals("UPLOADING")) throw Failure.conflict();
        var remote=platform.complete(file.uploadId());
        if(((Number)remote.get("size")).longValue()!=file.size() || !file.sha256().equals(remote.get("sha256")) || !"PRIVATE".equals(remote.get("visibility")) || !"영구".equals(remote.get("retentionCode")))
            throw new Failure(502,"FILE_VERIFICATION_FAILED","업로드한 파일 정보를 확인하지 못했습니다.");
        UUID platformId=UUID.fromString(remote.get("fileId").toString());
        db.update("UPDATE files SET state='READY',platform_file_id=?,received_bytes=size_bytes WHERE id=?",platformId,id);
        return own(owner,id,false);
    }
    @Transactional
    public void cancel(UUID owner,UUID id) {
        var file=own(owner,id,true);
        if(file.state().equals("CANCELLED")) return;
        if(!file.state().equals("UPLOADING")) throw Failure.conflict();
        platform.cancel(file.uploadId());
        db.update("UPDATE files SET state='CANCELLED' WHERE id=?",id);
    }
    void download(UUID owner,UUID id,boolean mcp,String range,HttpServletResponse response) {
        var file=own(owner,id,false);
        if(!file.state().equals("READY") || (mcp && file.assetId()==null)) throw Failure.missing();
        if(mcp) {
            long published=db.queryForObject("SELECT count(*) FROM asset_version_files WHERE asset_id=? AND file_id=?",Long.class,file.assetId(),file.id());
            if(published==0) throw Failure.missing();
        }
        platform.download(file.platformId(),file.name(),range,response);
    }
    @Scheduled(fixedDelay=60000, initialDelay=60000)
    public void cleanup() {
        var expired=db.query("SELECT * FROM files WHERE state='UPLOADING' AND expires_at<now() ORDER BY expires_at LIMIT 20",this::row);
        for(var file:expired) {
            try {tx.executeWithoutResult(s->{
                var current=own(file.ownerId(),file.id(),true);
                if(current.state().equals("UPLOADING")) applyStatus(current,platform.status(current.uploadId()));
            });} catch(Failure ignored) { /* Keep the reservation until the platform confirms completion or expiry. */ }
        }
    }
}
