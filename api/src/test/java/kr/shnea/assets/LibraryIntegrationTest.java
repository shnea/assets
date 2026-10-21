package kr.shnea.assets;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(LibraryIntegrationTest.IdentityConfig.class)
class LibraryIntegrationTest {
    @TestConfiguration static class IdentityConfig {
        @Bean ClientRegistrationRepository clients() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("platform").clientId("app")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:30170/callback").scope("openid","profile")
                .authorizationUri("https://identity.example/authorize").tokenUri("https://identity.example/token")
                .jwkSetUri("https://identity.example/certs").issuerUri("https://identity.example/realm/assets")
                .userInfoUri("https://identity.example/userinfo").userNameAttributeName("sub").clientName("Platform").build());
        }
    }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate db;
    @Autowired Storage storage;
    @Autowired Transfers transfers;
    @Autowired McpWrites mcpWrites;
    @Autowired Connections connections;
    @MockitoBean Platform platform;
    MockMvc mvc;
    JsonMapper json=new JsonMapper();
    static final String ISSUER="https://identity.example/realm/assets";
    org.springframework.test.web.servlet.request.RequestPostProcessor as(String subject) {
        return oidcLogin().idToken(t->t.issuer(ISSUER).subject(subject).claim("given_name",subject));
    }
    String body(String title,String work) {return json.writeValueAsString(Map.of("title",title,"work",work));}
    String content(String response,String field) {return json.readTree(response).path(field).asText();}
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        db.execute("TRUNCATE transfer_cleanup,usage_events,mcp_connections,files,asset_versions,assets,ideas,members CASCADE");
        db.update("UPDATE settings SET default_quota=10000000000 WHERE id=1");
    }
    @Test void identityIsolationVersionsAndAdministratorCannotReadOthers() throws Exception {
        mvc.perform(get("/api/assets")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/assets").with(as("alice")).contentType("application/json").content(body("비공개 제목","처음 작업"))).andExpect(status().isForbidden());
        var created=mvc.perform(post("/api/assets").with(as("alice")).with(csrf()).contentType("application/json").content(body("비공개 제목","처음 작업")))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=content(created,"id");
        mvc.perform(post("/api/assets/"+id+"/versions").with(as("alice")).with(csrf()).contentType("application/json").content("{\"revision\":1,\"changeNote\":\"처음 저장\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.number").value(1));
        mvc.perform(put("/api/assets/"+id).with(as("alice")).with(csrf()).contentType("application/json")
            .content("{\"revision\":2,\"content\":"+body("변경한 제목","바뀐 작업")+"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
        mvc.perform(get("/api/assets/"+id+"/versions/1").with(as("alice")))
            .andExpect(jsonPath("$.content.work").value("처음 작업"));
        for(String other:List.of("bob","admin")) {
            mvc.perform(get("/api/assets?q=비공개").with(as(other))).andExpect(jsonPath("$.total").value(0));
            for(String path:List.of("","/versions","/versions/1"))
                mvc.perform(get("/api/assets/"+id+path).with(as(other))).andExpect(status().isNotFound());
            mvc.perform(put("/api/assets/"+id).with(as(other)).with(csrf()).contentType("application/json").content("{\"revision\":3,\"content\":"+body("공격","덮어쓰기")+"}"))
                .andExpect(status().isNotFound());
        }
        mvc.perform(put("/api/assets/"+id).with(as("alice")).with(csrf()).contentType("application/json").content("{\"revision\":1,\"content\":"+body("오래된 탭","이전 내용")+"}"))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/session").with(as("admin"))).andExpect(jsonPath("$.member.admin").value(true));
        mvc.perform(get("/api/session").with(as("bob"))).andExpect(jsonPath("$.member.admin").value(false));
    }
    @Test void ideasArePrivateAndDoNotAppearInAssetSearch() throws Exception {
        var response=mvc.perform(post("/api/ideas").with(as("alice")).with(csrf()).contentType("application/json").content("{\"body\":\"아직 정리하지 않은 생각\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=content(response,"id");
        mvc.perform(get("/api/assets?q=생각").with(as("alice"))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/ideas/"+id).with(as("bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/ideas/"+id).with(as("admin"))).andExpect(status().isNotFound());
    }
    @Test void personalCategoriesAndIdeaPagesRemainOwnerScopedPastOneHundredRecords() throws Exception {
        mvc.perform(post("/api/assets").with(as("alice")).with(csrf()).contentType("application/json").content("{\"work\":\"도예 제작\",\"category\":\"나의 도예\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/assets").with(as("bob")).with(csrf()).contentType("application/json").content("{\"work\":\"다른 회원 제작\",\"category\":\"Bob의 분류\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/assets/categories").with(as("alice"))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0]").value("나의 도예"));
        mvc.perform(get("/api/assets/categories").with(as("admin"))).andExpect(jsonPath("$.length()").value(0));
        UUID owner=db.queryForObject("SELECT id FROM members WHERE subject='alice'",UUID.class);
        var rows=new ArrayList<Object[]>();
        for(int i=0;i<106;i++)rows.add(new Object[]{UUID.randomUUID(),owner,"아이디어 "+i,json.writeValueAsString("기억할 내용 "+i)});
        db.batchUpdate("INSERT INTO ideas(id,owner_id,title,body) VALUES (?,?,?,?::jsonb)",rows);
        var first=mvc.perform(get("/api/ideas?limit=100").with(as("alice"))).andExpect(jsonPath("$.total").value(106)).andExpect(jsonPath("$.items.length()").value(100)).andExpect(jsonPath("$.hasMore").value(true)).andReturn().getResponse().getContentAsString();
        var last=mvc.perform(get("/api/ideas?limit=100&offset=100").with(as("alice"))).andExpect(jsonPath("$.items.length()").value(6)).andExpect(jsonPath("$.hasMore").value(false)).andReturn().getResponse().getContentAsString();
        var ids=new HashSet<String>();
        for(var item:json.readTree(first).path("items"))assertThat(ids.add(item.path("id").asText())).isTrue();
        for(var item:json.readTree(last).path("items"))assertThat(ids.add(item.path("id").asText())).isTrue();
        assertThat(ids).hasSize(106);
        for(String other:List.of("bob","admin"))mvc.perform(get("/api/ideas?q=기억할").with(as(other))).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/ideas?offset=-1").with(as("alice"))).andExpect(status().isBadRequest());
    }
    @Test void loginUsesPkceAndUnknownOwnersAndDangerousUrlsAreRejected() throws Exception {
        mvc.perform(get("/api/session")).andExpect(jsonPath("$.appUrl").value("http://localhost:30170"));
        var login=mvc.perform(get("/oauth2/authorization/platform")).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(login).contains("code_challenge=","code_challenge_method=S256","state=","nonce=");
        mvc.perform(get("/callback?code=forged&state=unknown")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/api/assets").with(as("alice")).with(csrf()).contentType("application/json").content("{\"ownerId\":\"forged\",\"work\":\"내용\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/assets").with(as("alice")).with(csrf()).contentType("application/json").content("{\"work\":\"내용\",\"references\":[{\"url\":\"javascript:alert(1)\"}]}"))
            .andExpect(status().isBadRequest());
    }
    @Test void activeConnectionRemainsVisibleAfterManyRevokedKeys() throws Exception {
        var active=mvc.perform(post("/api/connections").with(as("alice")).with(csrf()).contentType("application/json").content("{\"name\":\"이전 기기\"}")).andReturn().getResponse().getContentAsString();
        UUID id=UUID.fromString(content(active,"id")),owner=db.queryForObject("SELECT id FROM members WHERE subject='alice'",UUID.class);
        db.update("UPDATE mcp_connections SET created_at=now()-interval '7 days' WHERE id=?",id);
        var rows=new ArrayList<Object[]>();
        for(int i=0;i<110;i++)rows.add(new Object[]{UUID.randomUUID(),owner,"종료한 기기 "+i,Connections.hash(UUID.randomUUID().toString())});
        db.batchUpdate("INSERT INTO mcp_connections(id,owner_id,name,token_hash,expires_at,revoked_at) VALUES (?,?,?,?,now()+interval '1 day',now())",rows);
        var listed=mvc.perform(get("/api/connections").with(as("alice"))).andExpect(jsonPath("$[0].id").value(id.toString())).andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain("token_hash",content(active,"token"));
    }
    @Test void concurrentUploadsReserveQuotaAndLoweringQuotaPreservesFiles() throws Exception {
        var created=mvc.perform(post("/api/assets").with(as("alice")).with(csrf()).contentType("application/json").content(body("용량 검사","내용")))
            .andReturn().getResponse().getContentAsString();
        UUID asset=UUID.fromString(content(created,"id"));
        UUID owner=db.queryForObject("SELECT id FROM members WHERE subject='alice'",UUID.class);
        db.update("UPDATE members SET quota_override=5 WHERE id=?",owner);
        when(platform.begin(any(),anyString(),anyLong(),anyString())).thenAnswer(inv->Map.of("uploadId",UUID.randomUUID(),"expiresAt",java.time.Instant.now().plusSeconds(86400).toString()));
        String hash="0".repeat(64);
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks=new ArrayList<java.util.concurrent.Future<Object>>();
            for(int i=0;i<2;i++) tasks.add(pool.submit(()->{
                gate.await();
                try {return storage.begin(owner,new Storage.Begin(UUID.randomUUID(),asset,null,"비공개 첨부",4L,hash));}
                catch(Failure e) {return e.code;}
            }));
            gate.countDown();
            var results=List.of(tasks.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS),tasks.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().filter(Storage.File.class::isInstance).count()).isEqualTo(1);
            assertThat(results).contains("QUOTA_EXCEEDED");
        }
        assertThat(storage.usage(owner).reservedBytes()).isEqualTo(4);
        var file=storage.list(owner,asset,null).getFirst();
        UUID remote=UUID.randomUUID();
        when(platform.complete(file.uploadId())).thenReturn(Map.of("fileId",remote,"size",4,"sha256",hash,"visibility","PRIVATE","retentionCode","영구"));
        storage.complete(owner,file.id());storage.complete(owner,file.id());
        assertThat(storage.usage(owner).usedBytes()).isEqualTo(4);
        assertThat(storage.usage(owner).reservedBytes()).isZero();
        mvc.perform(put("/api/admin/quotas/"+owner).with(as("admin")).with(csrf()).contentType("application/json").content("{\"bytes\":1}"))
            .andExpect(status().isOk());
        assertThat(storage.usage(owner).usedBytes()).isEqualTo(4);
        assertThat(storage.usage(owner).quotaBytes()).isEqualTo(1);
        mvc.perform(get("/api/files/"+file.id()+"/download").with(as("alice"))).andExpect(status().isOk());
        for(String other:List.of("bob","admin")) {
            mvc.perform(get("/api/files/"+file.id()+"/download").with(as(other))).andExpect(status().isNotFound());
            mvc.perform(get("/api/uploads/"+file.id()).with(as(other))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/admin/stats").with(as("alice"))).andExpect(status().isForbidden());
        var stats=mvc.perform(get("/api/admin/stats").with(as("admin"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(stats).doesNotContain("용량 검사","비공개 첨부",hash,"subject","issuer");
    }
    @Test void mcpReadsSavedVersionsOnlyRejectsIdeasOtherOwnersAndRevokedConnections() throws Exception {
        var created=mvc.perform(post("/api/assets").with(as("alice")).with(csrf()).contentType("application/json").content(body("저장된 자산","공개한 적 없는 개인 작업")))
            .andReturn().getResponse().getContentAsString();
        String id=content(created,"id");
        mvc.perform(post("/api/assets/"+id+"/versions").with(as("alice")).with(csrf()).contentType("application/json").content("{\"revision\":1}"));
        mvc.perform(put("/api/assets/"+id).with(as("alice")).with(csrf()).contentType("application/json").content("{\"revision\":2,\"content\":"+body("아직 저장 안 한 초안","초안 비밀")+"}"));
        var idea=mvc.perform(post("/api/ideas").with(as("alice")).with(csrf()).contentType("application/json").content("{\"body\":\"아이디어 비밀\"}"))
            .andReturn().getResponse().getContentAsString();
        String ideaId=content(idea,"id");
        String tokenResponse=mvc.perform(post("/api/connections").with(as("alice")).with(csrf()).contentType("application/json").content("{\"name\":\"Codex\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token=content(tokenResponse,"token");
        String bearer="Bearer "+token;
        String rpc="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"search_assets\",\"arguments\":{}}}";
        var result=mvc.perform(post("/mcp").header("Authorization",bearer).contentType("application/json").content(rpc))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(result).contains("저장된 자산").doesNotContain("초안 비밀","아이디어 비밀");
        mvc.perform(post("/mcp").contentType("application/json").content(rpc)).andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").header("Authorization",bearer).header("Origin","https://evil.example").contentType("application/json").content(rpc)).andExpect(status().isForbidden());
        mvc.perform(post("/mcp").header("Authorization",bearer).contentType("application/json").content(rpc.replace("search_assets","read_asset").replace("\"arguments\":{}","\"arguments\":{\"assetId\":\""+ideaId+"\"}")))
            .andExpect(jsonPath("$.result.isError").value(true));
        String bobToken=mvc.perform(post("/api/connections").with(as("bob")).with(csrf()).contentType("application/json").content("{\"name\":\"Codex\"}"))
            .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/mcp").header("Authorization","Bearer "+content(bobToken,"token")).contentType("application/json").content(rpc.replace("search_assets","read_asset").replace("\"arguments\":{}","\"arguments\":{\"assetId\":\""+id+"\"}")))
            .andExpect(jsonPath("$.result.isError").value(true));
        mvc.perform(delete("/api/connections/"+content(tokenResponse,"id")).with(as("alice")).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/mcp").header("Authorization",bearer).contentType("application/json").content(rpc)).andExpect(status().isUnauthorized());
        assertThat(db.queryForList("SELECT token_hash FROM mcp_connections").toString()).doesNotContain(token);
    }
    String connection(String subject,boolean write) throws Exception {
        return mvc.perform(post("/api/connections").with(as(subject)).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("name","검증 연결","canWrite",write))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.canWrite").value(write)).andReturn().getResponse().getContentAsString();
    }
    tools.jackson.databind.JsonNode mcp(String token,String name,Object arguments) throws Exception {
        var request=Map.of("jsonrpc","2.0","id",1,"method","tools/call","params",Map.of("name",name,"arguments",arguments));
        return json.readTree(mvc.perform(post("/mcp").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(request)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("result");
    }
    @Test void mcpCreatesSkillsAndAppendsVersionsWithScopedKeysAndSafeRetries() throws Exception {
        String reader=content(connection("alice",false),"token");
        String writerResponse=connection("alice",true),writer=content(writerResponse,"token");
        var input=Map.of("requestId",UUID.randomUUID(),"content",Map.of("title","내 제작 스킬","work","화면 요구사항 정리","remember","# SKILL.md\n사용할 때 지켜야 하는 순서","start","스킬을 호출한다.","tools",List.of(Map.of("kind","스킬","name","제작 스킬","note","로컬 SKILL.md에서 선택한 내용"))));
        assertThat(mcp(reader,"create_asset",input).path("isError").asBoolean()).isTrue();
        String list="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        for(var key:List.of(Map.entry(reader,5),Map.entry(writer,7))) mvc.perform(post("/mcp").header("Authorization","Bearer "+key.getKey()).contentType("application/json").content(list)).andExpect(jsonPath("$.result.tools.length()").value(key.getValue()));
        var created=mcp(writer,"create_asset",input);assertThat(created.path("isError").asBoolean()).isFalse();
        var saved=created.path("structuredContent");UUID id=UUID.fromString(saved.path("assetId").asText());
        assertThat(saved.path("version").asInt()).isEqualTo(1);assertThat(saved.path("replayed").asBoolean()).isFalse();
        var retry=mcp(writer,"create_asset",input).path("structuredContent");assertThat(retry.path("assetId").asText()).isEqualTo(id.toString());assertThat(retry.path("replayed").asBoolean()).isTrue();
        var reused=new LinkedHashMap<String,Object>(input);reused.put("changeNote","다른 요청");assertThat(mcp(writer,"create_asset",reused).path("isError").asBoolean()).isTrue();
        var append=Map.of("requestId",UUID.randomUUID(),"assetId",id,"expectedVersion",1,"changes",Map.of("remember","새 도구에 맞춘 스킬 본문"),"changeNote","도구 변경");
        assertThat(mcp(reader,"add_asset_version",append).path("isError").asBoolean()).isTrue();
        var added=mcp(writer,"add_asset_version",append);assertThat(added.path("isError").asBoolean()).isFalse();assertThat(added.path("structuredContent").path("version").asInt()).isEqualTo(2);
        assertThat(mcp(writer,"add_asset_version",append).path("structuredContent").path("replayed").asBoolean()).isTrue();
        var old=mcp(reader,"read_version",Map.of("assetId",id,"number",1));assertThat(old.toString()).contains("SKILL.md").doesNotContain("새 도구에 맞춘");
        var latest=mcp(reader,"read_asset",Map.of("assetId",id));assertThat(latest.toString()).contains("새 도구에 맞춘 스킬 본문","화면 요구사항 정리","제작 스킬");
        var stale=new LinkedHashMap<String,Object>(append);stale.put("requestId",UUID.randomUUID());assertThat(mcp(writer,"add_asset_version",stale).path("isError").asBoolean()).isTrue();
        for(String other:List.of("bob","admin")) {
            assertThat(mcp(content(connection(other,true),"token"),"add_asset_version",Map.of("requestId",UUID.randomUUID(),"assetId",id,"expectedVersion",2,"changes",Map.of("work","타인 변경"))).path("isError").asBoolean()).isTrue();
            mvc.perform(get("/api/assets/"+id).with(as(other))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/assets/"+id).with(as("alice"))).andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.draft.status").value("UNVERIFIED"));
        assertThat(db.queryForObject("SELECT count(*) FROM assets",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM asset_versions",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM usage_events WHERE kind='ASSET_CREATE'",Integer.class)).isEqualTo(1);
        assertThat(mcp(writer,"delete_asset",Map.of("assetId",id)).path("isError").asBoolean()).isTrue();
        mvc.perform(delete("/api/connections/"+content(writerResponse,"id")).with(as("alice")).with(csrf()));
        mvc.perform(post("/mcp").header("Authorization","Bearer "+writer).contentType("application/json").content(list)).andExpect(status().isUnauthorized());
    }
    @Test void mcpWritesAreAtomicPreserveAttachmentsAndRejectPendingWebDrafts() throws Exception {
        String writer=content(connection("alice",true),"token");
        var initial=mcp(writer,"create_asset",Map.of("requestId",UUID.randomUUID(),"content",Map.of("work","처음"))).path("structuredContent");
        String id=initial.path("assetId").asText();UUID owner=db.queryForObject("SELECT id FROM members WHERE subject='alice'",UUID.class);
        UUID file=UUID.randomUUID(),remote=UUID.randomUUID();
        db.update("INSERT INTO files(id,owner_id,asset_id,name,size_bytes,sha256,state,platform_file_id) VALUES (?,?,?,'보존할 파일',1,?,'READY',?)",file,owner,UUID.fromString(id),"0".repeat(64),remote);
        var document=rich("첨부와 서식 보존",remote.toString(),"asset:"+id);
        mvc.perform(put("/api/assets/"+id).with(as("alice")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("revision",2,"content",Map.of("work",document,"fileIds",List.of(file)))))).andExpect(status().isOk());
        var append=Map.of("requestId",UUID.randomUUID(),"assetId",id,"expectedVersion",1,"changes",Map.of("start","새 버전"));
        assertThat(mcp(writer,"add_asset_version",append).path("isError").asBoolean()).isTrue();
        mvc.perform(post("/api/assets/"+id+"/versions").with(as("alice")).with(csrf()).contentType("application/json").content("{\"revision\":3}")).andExpect(status().isOk());
        var valid=new LinkedHashMap<String,Object>(append);valid.put("expectedVersion",2);
        assertThat(mcp(writer,"add_asset_version",valid).path("structuredContent").path("version").asInt()).isEqualTo(3);
        assertThat(storage.references(owner,UUID.fromString(id),2)).hasSize(1);assertThat(storage.references(owner,UUID.fromString(id),3)).hasSize(1);
        var current=mcp(writer,"read_asset",Map.of("assetId",id));assertThat(current.toString()).contains(remote.toString(),file.toString(),"shnea-editor","bold");
        int assets=db.queryForObject("SELECT count(*) FROM assets",Integer.class),versions=db.queryForObject("SELECT count(*) FROM asset_versions",Integer.class);
        for(var invalid:List.of(Map.of("title","내용 없는 자산"),Map.of("work","잘못된 첨부","fileIds",List.of(file)),Map.of("work","잘못된 소유자","ownerId",owner),Map.of("work","잘못된 링크","references",List.of(Map.of("url","file:///local/SKILL.md")))))
            assertThat(mcp(writer,"create_asset",Map.of("requestId",UUID.randomUUID(),"content",invalid)).path("isError").asBoolean()).isTrue();
        for(var changes:List.of(Map.of("work","","start",""),Map.of("fileIds",List.of(UUID.randomUUID())),Map.of("work",rich("잘못된 인라인",UUID.randomUUID().toString(),"asset:"+id))))
            assertThat(mcp(writer,"add_asset_version",Map.of("requestId",UUID.randomUUID(),"assetId",id,"expectedVersion",3,"changes",changes)).path("isError").asBoolean()).isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM assets",Integer.class)).isEqualTo(assets);assertThat(db.queryForObject("SELECT count(*) FROM asset_versions",Integer.class)).isEqualTo(versions);
        mvc.perform(get("/api/assets/"+id).with(as("alice"))).andExpect(jsonPath("$.revision").value(6));
    }
    @Test void concurrentMcpRetriesCreateOnceAndVersionRacesDoNotOverwrite() throws Exception {
        var access=connections.authorize("Bearer "+content(connection("alice",true),"token"));
        var input=json.valueToTree(Map.of("requestId",UUID.randomUUID(),"content",Map.of("work","동시 등록")));
        var gate=new java.util.concurrent.CountDownLatch(1);
        UUID id;
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks=new ArrayList<java.util.concurrent.Future<McpWrites.Result>>();
            for(int i=0;i<2;i++) tasks.add(pool.submit(()->{gate.await();return mcpWrites.write(access,"create_asset",input);}));
            gate.countDown();var first=tasks.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS);var second=tasks.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first.assetId()).isEqualTo(second.assetId());assertThat(first.replayed()).isNotEqualTo(second.replayed());id=first.assetId();
        }
        var nextGate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks=new ArrayList<java.util.concurrent.Future<Object>>();
            for(int i=0;i<2;i++) {
                var next=json.valueToTree(Map.of("requestId",UUID.randomUUID(),"assetId",id,"expectedVersion",1,"changes",Map.of("work","동시 버전 "+i)));
                tasks.add(pool.submit(()->{nextGate.await();try{return mcpWrites.write(access,"add_asset_version",next);}catch(Failure error){return error.code;}}));
            }
            nextGate.countDown();var results=List.of(tasks.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS),tasks.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().filter(McpWrites.Result.class::isInstance).count()).isEqualTo(1);assertThat(results).contains("VERSION_CHANGED");
        }
        assertThat(db.queryForObject("SELECT count(*) FROM assets",Integer.class)).isEqualTo(1);assertThat(db.queryForObject("SELECT count(*) FROM asset_versions",Integer.class)).isEqualTo(2);
    }
    Map<String,Object> rich(String text,String remoteId,String scope) {
        var children=new ArrayList<Object>();
        children.add(Map.of("type","paragraph","content",List.of(Map.of("type","text","text",text,"marks",List.of(Map.of("type","bold"))))));
        if(remoteId!=null) children.add(Map.of("type","attachment","attrs",Map.of("id",UUID.randomUUID().toString(),"fileId",remoteId,"scope",scope,"kind","image","name","보관한 이미지.png","size",1)));
        return Map.of("format","shnea-editor","version",3,"content",Map.of("type","doc","content",children));
    }
    @Test void richDocumentsPreserveInlineFilesAndCannotReferenceIdeasOrOtherMembers() throws Exception {
        var first=mvc.perform(post("/api/assets").with(as("alice")).with(csrf()).contentType("application/json").content(body("에디터 기록","처음"))).andReturn().getResponse().getContentAsString();
        String id=content(first,"id");UUID owner=db.queryForObject("SELECT id FROM members WHERE subject='alice'",UUID.class);
        UUID local=UUID.randomUUID(),remote=UUID.randomUUID();
        db.update("INSERT INTO files(id,owner_id,asset_id,name,size_bytes,sha256,state,platform_file_id) VALUES (?,?,?,'보관한 이미지.png',1,?,'READY',?)",local,owner,UUID.fromString(id),"0".repeat(64),remote);
        var document=rich("서식과 이미지가 있는 제작법",remote.toString(),"asset:"+id);
        var edit=Map.of("revision",1,"content",Map.of("work",document));
        mvc.perform(put("/api/assets/"+id).with(as("alice")).with(csrf()).contentType("application/json").content(json.writeValueAsString(edit))).andExpect(status().isOk());
        mvc.perform(post("/api/assets/"+id+"/versions").with(as("alice")).with(csrf()).contentType("application/json").content("{\"revision\":2}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content.work.format").value("shnea-editor"));
        assertThat(storage.references(owner,UUID.fromString(id),1)).hasSize(1);
        assertThat(storage.usage(owner).usedBytes()).isEqualTo(1);
        when(platform.views(remote)).thenReturn(Map.of("fileId",remote.toString(),"kind","IMAGE","state","READY"));
        mvc.perform(get("/api/editor/files/"+remote+"/views").with(as("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.fileId").value(remote.toString()));
        for(String other:List.of("bob","admin")) mvc.perform(get("/api/editor/files/"+remote+"/views").with(as(other))).andExpect(status().isNotFound());
        var second=mvc.perform(post("/api/assets").with(as("bob")).with(csrf()).contentType("application/json").content(body("다른 회원","내용"))).andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/assets/"+content(second,"id")).with(as("bob")).with(csrf()).contentType("application/json").content(json.writeValueAsString(edit))).andExpect(status().isBadRequest());
        var idea=mvc.perform(post("/api/ideas").with(as("alice")).with(csrf()).contentType("application/json").content("{}"))
            .andReturn().getResponse().getContentAsString();
        UUID ideaId=UUID.fromString(content(idea,"id")),ideaRemote=UUID.randomUUID();
        db.update("INSERT INTO files(id,owner_id,idea_id,name,size_bytes,sha256,state,platform_file_id) VALUES (?,?,?,'수첩의 이미지.png',1,?,'READY',?)",UUID.randomUUID(),owner,ideaId,"1".repeat(64),ideaRemote);
        mvc.perform(put("/api/assets/"+id).with(as("alice")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("revision",3,"content",Map.of("work",rich("수첩 자료 참조",ideaRemote.toString(),"idea:"+ideaId))))))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/assets/"+id).with(as("alice")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("revision",3,"content",Map.of("work",rich("업로드 진행 중","","asset:"+id))))))
            .andExpect(status().isOk());
        mvc.perform(post("/api/assets/"+id+"/versions").with(as("alice")).with(csrf()).contentType("application/json").content("{\"revision\":4}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UPLOAD_PENDING"));
        mvc.perform(get("/api/assets/"+id+"/versions/1").with(as("alice"))).andExpect(jsonPath("$.content.work.content.content[0].content[0].marks[0].type").value("bold"));
    }

    Map<String,byte[]> unzip(byte[] bytes) throws Exception {
        var entries=new LinkedHashMap<String,byte[]>();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes),java.nio.charset.StandardCharsets.UTF_8)) {
            for(java.util.zip.ZipEntry entry;(entry=zip.getNextEntry())!=null;) entries.put(entry.getName(),zip.readAllBytes());
        }
        return entries;
    }
    byte[] zip(Transfers.Backup backup,Map<String,byte[]> entries) throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(bytes)) {
            Transfers.entry(zip,"manifest.json",json.writeValueAsBytes(backup));
            for(var entry:entries.entrySet()) Transfers.entry(zip,entry.getKey(),entry.getValue());
        }
        return bytes.toByteArray();
    }
    void uploadFixture() {
        var buffers=new HashMap<UUID,java.io.ByteArrayOutputStream>();var hashes=new HashMap<UUID,String>();var sizes=new HashMap<UUID,Long>();
        when(platform.begin(any(),anyString(),anyLong(),anyString())).thenAnswer(inv->{UUID id=UUID.randomUUID();buffers.put(id,new java.io.ByteArrayOutputStream());hashes.put(id,inv.getArgument(3));sizes.put(id,inv.getArgument(2));return Map.of("uploadId",id,"expiresAt",java.time.Instant.now().plusSeconds(86400).toString());});
        when(platform.append(any(),anyLong(),anyString(),any())).thenAnswer(inv->{UUID id=inv.getArgument(0);buffers.get(id).write((byte[])inv.getArgument(3));return Map.of("state","UPLOADING","receivedBytes",buffers.get(id).size());});
        when(platform.complete(any())).thenAnswer(inv->{UUID id=inv.getArgument(0);return Map.of("fileId",UUID.randomUUID(),"size",sizes.get(id),"sha256",hashes.get(id),"visibility","PRIVATE","retentionCode","영구");});
    }
    @Test void backupAndSkillExportsRestoreHistoryIdeasAndOriginalsWithNewOwnedIds() throws Exception {
        byte[] original={42};String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
        var created=mvc.perform(post("/api/assets").with(as("alice")).with(csrf()).contentType("application/json").content(body("백업할 제작법","처음"))).andReturn().getResponse().getContentAsString();
        UUID asset=UUID.fromString(content(created,"id")),local=UUID.randomUUID(),remote=UUID.randomUUID();
        UUID owner=db.queryForObject("SELECT id FROM members WHERE subject='alice'",UUID.class);
        db.update("INSERT INTO files(id,owner_id,asset_id,name,size_bytes,sha256,state,platform_file_id) VALUES (?,?,?,'image.png',1,?,'READY',?)",local,owner,asset,hash,remote);
        mvc.perform(put("/api/assets/"+asset).with(as("alice")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("revision",1,"content",Map.of("work",rich("원본과 서식",remote.toString(),"asset:"+asset),"fileIds",List.of(local)))))).andExpect(status().isOk());
        mvc.perform(post("/api/assets/"+asset+"/versions").with(as("alice")).with(csrf()).contentType("application/json").content("{\"revision\":2,\"changeNote\":\"첫 버전\"}")).andExpect(status().isOk());
        mvc.perform(put("/api/assets/"+asset).with(as("alice")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("revision",3,"content",Map.of("work","첨부 없는 두 번째 버전"))))).andExpect(status().isOk());
        mvc.perform(post("/api/assets/"+asset+"/versions").with(as("alice")).with(csrf()).contentType("application/json").content("{\"revision\":4}")).andExpect(status().isOk());
        var idea=mvc.perform(post("/api/ideas").with(as("alice")).with(csrf()).contentType("application/json").content("{\"title\":\"수첩 백업\"}")).andReturn().getResponse().getContentAsString();
        UUID ideaId=UUID.fromString(content(idea,"id")),ideaRemote=UUID.randomUUID();
        db.update("INSERT INTO files(id,owner_id,idea_id,name,size_bytes,sha256,state,platform_file_id) VALUES (?,?,?,'idea.png',1,?,'READY',?)",UUID.randomUUID(),owner,ideaId,hash,ideaRemote);
        mvc.perform(put("/api/ideas/"+ideaId).with(as("alice")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("revision",1,"title","수첩 백업","body",rich("수첩의 서식",ideaRemote.toString(),"idea:"+ideaId))))).andExpect(status().isOk());
        mvc.perform(post("/api/assets").with(as("bob")).with(csrf()).contentType("application/json").content(body("Bob의 기존 자료","유지할 내용"))).andExpect(status().isCreated());
        doAnswer(inv->{((java.io.OutputStream)inv.getArgument(1)).write(original);return null;}).when(platform).copy(any(),any());
        when(platform.metadata(any())).thenReturn(Map.of("size",1,"sha256",hash,"visibility","PRIVATE"));
        byte[] archive=mvc.perform(get("/api/backup").with(as("alice"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var entries=unzip(archive);assertThat(entries.keySet()).contains("manifest.json","files/"+local,"assets/"+asset+"/version-1.md");
        assertThat(new String(entries.get("manifest.json"),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("Bob의 기존 자료","ownerId","issuer","subject");
        assertThat(new String(entries.get("assets/"+asset+"/version-1.md"),java.nio.charset.StandardCharsets.UTF_8)).contains("**원본과 서식**","../../files/"+local);
        var skill=unzip(mvc.perform(get("/api/assets/"+asset+"/export?format=skill&number=1").with(as("alice"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        String root="asset-"+asset+"/";
        assertThat(new String(skill.get(root+"SKILL.md"),java.nio.charset.StandardCharsets.UTF_8)).startsWith("---\nname: asset-").contains("assets-version: \"1\"","references/asset.md");
        assertThat(skill.get(root+"assets/"+local+".png")).isEqualTo(original);
        var secondSkill=unzip(mvc.perform(get("/api/assets/"+asset+"/export?format=skill&number=2").with(as("alice"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(secondSkill.keySet()).noneMatch(path->path.contains("/assets/"));
        for(String other:List.of("bob","admin")) for(String format:List.of("json","markdown","skill")) mvc.perform(get("/api/assets/"+asset+"/export?format="+format+"&number=1").with(as(other))).andExpect(status().isNotFound());
        uploadFixture();
        String restored=mvc.perform(post("/api/backup/import").with(as("bob")).with(csrf()).contentType("application/zip").content(archive)).andExpect(status().isOk()).andExpect(jsonPath("$.assets").value(1)).andExpect(jsonPath("$.ideas").value(1)).andExpect(jsonPath("$.files").value(2)).andReturn().getResponse().getContentAsString();
        UUID copy=UUID.fromString(content(restored,"firstAssetId")),bob=db.queryForObject("SELECT id FROM members WHERE subject='bob'",UUID.class);
        assertThat(copy).isNotEqualTo(asset);
        assertThat(db.queryForObject("SELECT count(*) FROM assets WHERE owner_id=?",Long.class,bob)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM assets WHERE owner_id=?",Long.class,owner)).isEqualTo(1);
        var refs=storage.references(bob,copy,1);assertThat(refs).hasSize(1);assertThat(refs.getFirst().id()).isNotEqualTo(local);assertThat(refs.getFirst().platformId()).isNotEqualTo(remote);
        assertThat(storage.references(bob,copy,2)).isEmpty();assertThat(storage.usage(bob).usedBytes()).isEqualTo(2);
        var restoredVersion=mvc.perform(get("/api/assets/"+copy+"/versions/1").with(as("bob"))).andExpect(jsonPath("$.changeNote").value("첫 버전")).andReturn().getResponse().getContentAsString();
        assertThat(restoredVersion).contains(refs.getFirst().platformId().toString(),"asset:"+copy).doesNotContain(remote.toString(),"asset:"+asset);
        mvc.perform(get("/api/assets/"+copy).with(as("alice"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/assets/"+asset+"/versions/1").with(as("alice"))).andExpect(jsonPath("$.content.work.content.content[1].attrs.fileId").value(remote.toString()));
        when(platform.metadata(any())).thenReturn(Map.of());
        mvc.perform(get("/api/backup").with(as("alice"))).andExpect(status().is(502)).andExpect(jsonPath("$.code").value("BACKUP_FILE_UNAVAILABLE"));
    }

    @Test void invalidOrOverQuotaBackupDoesNotChangeDataAndFailedUploadsAreQueuedForCleanup() throws Exception {
        mvc.perform(get("/api/session").with(as("alice"))).andExpect(status().isOk());
        UUID owner=db.queryForObject("SELECT id FROM members WHERE subject='alice'",UUID.class),asset=UUID.randomUUID(),file=UUID.randomUUID(),remote=UUID.randomUUID();
        Content content=json.readValue(body("가져올 제목","가져올 내용"),Content.class).normalize();
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new byte[]{42}));
        var backup=new Transfers.Backup("shnea-assets",1,java.time.Instant.now(),List.of(new Transfers.SavedAsset(asset,content,false,0,java.time.Instant.now(),List.of())),List.of(),List.of(new Transfers.SavedFile(file,remote,asset,null,"image.png",1,hash)));
        byte[] archive=zip(backup,Map.of("files/"+file,new byte[]{42}));
        mvc.perform(post("/api/backup/import").with(as("alice")).contentType("application/zip").content(archive)).andExpect(status().isForbidden());
        db.update("UPDATE members SET quota_override=0 WHERE id=?",owner);
        mvc.perform(post("/api/backup/import").with(as("alice")).with(csrf()).contentType("application/zip").content(archive)).andExpect(status().is(413));
        assertThat(db.queryForObject("SELECT count(*) FROM assets",Long.class)).isZero();verify(platform,never()).begin(any(),anyString(),anyLong(),anyString());
        db.update("UPDATE members SET quota_override=null WHERE id=?",owner);uploadFixture();
        byte[] corrupt=zip(backup,Map.of("files/"+file,new byte[]{41}));
        mvc.perform(post("/api/backup/import").with(as("alice")).with(csrf()).contentType("application/zip").content(corrupt)).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT count(*) FROM assets",Long.class)).isZero();assertThat(db.queryForObject("SELECT count(*) FROM files",Long.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM transfer_cleanup",Long.class)).isEqualTo(1);
        when(platform.status(any())).thenReturn(Map.of("state","UPLOADING"));transfers.cleanup();
        verify(platform).cancel(any());assertThat(db.queryForObject("SELECT count(*) FROM transfer_cleanup",Long.class)).isZero();
        mvc.perform(post("/api/backup/import").with(as("alice")).with(csrf()).contentType("application/zip").content(zip(backup,Map.of("../escape",new byte[]{42})))).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT count(*) FROM assets",Long.class)).isZero();
        var textOnly=new Transfers.Backup("shnea-assets",1,java.time.Instant.now(),backup.assets(),List.of(),List.of());
        var stream=new java.io.FilterInputStream(new java.io.ByteArrayInputStream(zip(textOnly,Map.of()))) {
            @Override public void close() throws java.io.IOException {throw new java.io.IOException("transport close failed");}
        };
        var imported=transfers.restore(owner,stream);assertThat(imported.assets()).isEqualTo(1);
        UUID tracked=UUID.randomUUID();
        db.update("INSERT INTO files(id,owner_id,asset_id,name,size_bytes,sha256,state,platform_upload_id,platform_file_id) VALUES (?,?,?,'kept.txt',1,?,'READY',?,?)",UUID.randomUUID(),owner,imported.firstAssetId(),hash,tracked,UUID.randomUUID());
        db.update("INSERT INTO transfer_cleanup(upload_id) VALUES (?)",tracked);transfers.cleanup();
        verify(platform,times(1)).cancel(any());verify(platform,never()).delete(any());
        assertThat(storage.usage(owner).usedBytes()).isEqualTo(1);
    }
}
