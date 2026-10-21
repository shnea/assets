package kr.shnea.assets;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ideas")
class IdeasController {
    private final Ideas ideas;
    private final Members members;
    IdeasController(Ideas ideas,Members members) {this.ideas=ideas;this.members=members;}
    @GetMapping Object list(@AuthenticationPrincipal OidcUser user,@RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="30") int limit) {return ideas.list(members.require(user).id(),q,offset,limit);}
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object create(@AuthenticationPrincipal OidcUser user,@RequestBody Ideas.Edit input) {return ideas.create(members.require(user).id(),input);}
    @GetMapping("/{id}") Object read(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id) {return ideas.own(members.require(user).id(),id);}
    @PutMapping("/{id}") Object edit(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id,@RequestBody Ideas.Edit input) {return ideas.update(members.require(user).id(),id,input);}
}
