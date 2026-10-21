package kr.shnea.assets;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class McpWrites {
    record Result(UUID assetId,int version,String path,boolean replayed) {}
    private static final Set<String> FIELDS=Set.of("title","category","work","remember","start","tags","tools","terms","references","fileIds","purpose","inputs","completion","troubleshooting","status","verifiedOn","environment");
    private final JdbcTemplate db;
    private final Library library;
    private final JsonMapper json=new JsonMapper();
    private static final TypeReference<LinkedHashMap<String,Object>> OBJECT=new TypeReference<>() {};

    McpWrites(JdbcTemplate db,Library library) {this.db=db;this.library=library;}

    @Transactional
    public Result write(Connections.Access access,String operation,JsonNode arguments) {
        if(!access.canWrite()) throw new Failure(403,"MCP_READ_ONLY","읽기 전용 연결입니다. 자산 등록·버전 추가를 허용한 연결 키를 사용해 주세요.");
        boolean create=operation.equals("create_asset");
        if(!create && !operation.equals("add_asset_version")) throw Failure.invalid();
        if(!arguments.isObject()) throw Failure.invalid();
        var input=json.convertValue(arguments,OBJECT);
        var allowed=create?Set.of("requestId","content","changeNote"):Set.of("requestId","assetId","expectedVersion","changes","changeNote");
        if(!allowed.containsAll(input.keySet())) throw Failure.invalid();
        UUID requestId=RichText.uuid(input.get("requestId")),owner=access.owner();
        String note=string(input.get("changeNote"),500);
        // Serialize retries for this member, including requests arriving through different keys.
        db.queryForObject("SELECT id FROM members WHERE id=? FOR NO KEY UPDATE",UUID.class,owner);
        String hash=db.queryForObject("SELECT encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex')",String.class,json.writeValueAsString(input));
        var prior=db.queryForList("SELECT operation,request_hash,asset_id,version FROM mcp_writes WHERE owner_id=? AND request_id=?",owner,requestId);
        if(!prior.isEmpty()) {
            var saved=prior.getFirst();
            if(!operation.equals(saved.get("operation")) || !hash.equals(saved.get("request_hash"))) throw new Failure(409,"REQUEST_REUSED","같은 요청 ID에 다른 내용을 사용할 수 없습니다. 새 작업에는 새 requestId를 사용해 주세요.");
            return result((UUID)saved.get("asset_id"),((Number)saved.get("version")).intValue(),true);
        }
        UUID assetId;
        Library.Version version;
        if(create) {
            Content content=content(input.get("content"),null,false);
            var asset=library.create(owner,content);
            assetId=asset.id();
            version=library.save(owner,assetId,new Library.Save(asset.revision(),note.isBlank()?"MCP에서 등록":note));
        } else {
            assetId=RichText.uuid(input.get("assetId"));
            var asset=library.own(owner,assetId,true);
            Object expected=input.get("expectedVersion");
            if(!(expected instanceof Number number) || number.longValue()<1 || number.longValue()>Integer.MAX_VALUE || number.doubleValue()!=number.longValue()) throw Failure.invalid();
            if(asset.version()!=number.intValue()) throw new Failure(409,"VERSION_CHANGED","새 버전이 이미 있습니다. 최신 저장 버전을 읽은 뒤 변경 내용을 다시 적용해 주세요.");
            var previous=library.version(owner,assetId,asset.version());
            if(!asset.draft().equals(previous.content())) throw new Failure(409,"DRAFT_PENDING","웹에서 작성 중인 초안이 있습니다. 초안을 먼저 버전으로 저장한 뒤 다시 시도해 주세요.");
            Content content=content(input.get("changes"),previous.content(),true);
            if(content.equals(previous.content())) throw new Failure(400,"NO_CHANGES","이전 버전과 다른 내용을 입력해 주세요.");
            var updated=library.update(owner,assetId,new Library.Draft(content,asset.revision()));
            version=library.save(owner,assetId,new Library.Save(updated.revision(),note.isBlank()?"MCP에서 버전 추가":note));
        }
        db.update("INSERT INTO mcp_writes(owner_id,request_id,operation,request_hash,asset_id,version) VALUES (?,?,?,?,?,?)",owner,requestId,operation,hash,assetId,version.number());
        return result(assetId,version.number(),false);
    }
    Result result(UUID id,int version,boolean replayed) {return new Result(id,version,"/#/assets/"+id,replayed);}
    String string(Object value,int limit) {if(value!=null && !(value instanceof String)) throw Failure.invalid();return Content.text((String)value,limit);}
    Content content(Object value,Content base,boolean filesAllowed) {
        if(!(value instanceof Map<?,?> fields) || fields.isEmpty() || !FIELDS.containsAll(fields.keySet()) || (!filesAllowed && fields.containsKey("fileIds"))) throw Failure.invalid();
        for(String field:List.of("title","category","status","verifiedOn","environment")) if(fields.containsKey(field)) string(fields.get(field),field.equals("environment")?2000:500);
        var merged=base==null?new LinkedHashMap<String,Object>():json.convertValue(base,OBJECT);
        for(var entry:fields.entrySet()) merged.put((String)entry.getKey(),entry.getValue());
        try {return json.convertValue(merged,Content.class).normalize();}
        catch(Failure error) {throw error;}
        catch(Exception error) {throw Failure.invalid();}
    }
}
