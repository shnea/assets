package kr.shnea.assets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

@RestController
class TransfersController {
    private final Transfers transfers;
    private final Members members;
    private final Library library;
    private final Storage storage;
    private final String appUrl;

    TransfersController(Transfers transfers,Members members,Library library,Storage storage,@Value("${assets.url}") String appUrl) {
        this.transfers=transfers;this.members=members;this.library=library;this.storage=storage;this.appUrl=appUrl;
    }

    @GetMapping("/api/backup")
    void backup(@AuthenticationPrincipal OidcUser user,HttpServletResponse response) throws IOException {
        var backup=transfers.snapshot(members.require(user).id());
        transfers.verifyOriginals(backup.files());
        download(response,"application/zip","assets-backup-"+java.time.LocalDate.now()+".zip");
        transfers.backup(backup,response.getOutputStream());
    }

    @GetMapping("/api/backup/check")
    Object check(@AuthenticationPrincipal OidcUser user) {
        var backup=transfers.snapshot(members.require(user).id());transfers.verifyOriginals(backup.files());
        return Map.of("ready",true);
    }

    @PostMapping(value="/api/backup/import",consumes="application/zip")
    Object restore(@AuthenticationPrincipal OidcUser user,HttpServletRequest request) throws IOException {
        return transfers.restore(members.require(user).id(),request.getInputStream());
    }

    @GetMapping("/api/assets/{id}/export")
    void export(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id,
        @RequestParam(defaultValue="markdown") String format,@RequestParam(required=false) Integer number,HttpServletResponse response) throws IOException {
        UUID owner=members.require(user).id();
        var asset=library.own(owner,id,false);
        var version=number==null?null:library.version(owner,id,number);
        var content=version==null?asset.draft():version.content();
        String suffix=version==null?"draft":"v"+version.number(),name="asset-"+id+"-"+suffix;
        if(format.equals("skill")) {
            if(version==null) throw new Failure(400,"SAVED_VERSION_REQUIRED","스킬로 내보낼 저장 버전을 선택해 주세요.");
            transfers.verifySkill(owner,id,version.number());
            download(response,"application/zip",name+"-skill.zip");
            transfers.skill(owner,id,version.number(),response.getOutputStream());return;
        }
        if(!Set.of("json","markdown").contains(format)) throw Failure.invalid();
        var files=version==null?storage.list(owner,id,null).stream().filter(f->f.state().equals("READY")).toList():storage.references(owner,id,version.number());
        if(format.equals("json")) {
            var exported=new LinkedHashMap<String,Object>();
            exported.put("format","shnea-assets-document");exported.put("version",1);exported.put("assetId",id);
            exported.put("savedVersion",version==null?null:version.number());exported.put("content",content);
            exported.put("createdAt",version==null?asset.updatedAt():version.createdAt());
            exported.put("files",files.stream().map(f->Map.of("id",f.id(),"platformId",f.platformId(),"name",f.name(),"size",f.size(),"sha256",f.sha256())).toList());
            download(response,"application/json",name+".json");response.getOutputStream().write(library.encode(exported).getBytes(StandardCharsets.UTF_8));
        } else {
            var mapping=new HashMap<UUID,String>();files.forEach(f->mapping.put(f.platformId(),appUrl+"/api/files/"+f.id()+"/download"));
            String md=transfers.markdown(content,version==null?"작성 중인 초안":"버전 "+version.number()+" · "+version.createdAt(),mapping::get);
            var attached=new StringBuilder("\n## 첨부자료\n\n");
            for(var file:files) attached.append("- [").append(Markdown.escape(file.name())).append("](").append(appUrl).append("/api/files/").append(file.id()).append("/download)\n");
            download(response,"text/markdown",name+".md");response.getOutputStream().write((md+(files.isEmpty()?"":attached)).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void download(HttpServletResponse response,String type,String name) {
        response.setContentType(type+(type.equals("application/zip")?"":"; charset=utf-8"));
        response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(name,StandardCharsets.UTF_8).build().toString());
        response.setHeader("Cache-Control","no-store");
    }
}
