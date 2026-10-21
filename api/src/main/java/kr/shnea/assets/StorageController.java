package kr.shnea.assets;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

@RestController
class StorageController {
    private final Storage storage; private final Members members; private final Platform platform;
    StorageController(Storage storage,Members members,Platform platform) {this.storage=storage;this.members=members;this.platform=platform;}
    @GetMapping("/api/storage") Object usage(@AuthenticationPrincipal OidcUser user) {return storage.usage(members.require(user).id());}
    @GetMapping("/api/editor/files/{id}/views") Object views(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id) {return storage.editorViews(members.require(user).id(),id);}
    @GetMapping("/api/platform/status") Object platform(@AuthenticationPrincipal OidcUser user) {members.require(user);return platform.check();}
    @GetMapping("/api/files") Object files(@AuthenticationPrincipal OidcUser user,@RequestParam(required=false) UUID assetId,@RequestParam(required=false) UUID ideaId) {return storage.list(members.require(user).id(),assetId,ideaId);}
    @PostMapping("/api/uploads") Object begin(@AuthenticationPrincipal OidcUser user,@RequestBody Storage.Begin input) {return storage.begin(members.require(user).id(),input);}
    @GetMapping("/api/uploads/{id}") Object status(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id) {return storage.status(members.require(user).id(),id);}
    @PatchMapping(value="/api/uploads/{id}",consumes="application/octet-stream") Object append(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id,@RequestHeader("Upload-Offset") long offset,@RequestHeader("X-Chunk-SHA256") String hash,HttpServletRequest request) throws IOException {
        long size=request.getContentLengthLong();
        if(size<1 || size>8_388_608) throw Failure.invalid();
        var chunk=request.getInputStream().readNBytes((int)size+1);
        if(chunk.length!=size) throw Failure.invalid();
        return storage.append(members.require(user).id(),id,offset,hash,chunk);
    }
    @PostMapping("/api/uploads/{id}/complete") Object complete(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id) {return storage.complete(members.require(user).id(),id);}
    @DeleteMapping("/api/uploads/{id}") void cancel(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id) {storage.cancel(members.require(user).id(),id);}
    @GetMapping("/api/files/{id}/download") void download(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id,@RequestHeader(value="Range",required=false) String range,HttpServletResponse response) {storage.download(members.require(user).id(),id,false,range,response);}
}
