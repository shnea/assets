package kr.shnea.assets;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assets")
class LibraryController {
    private final Library library;
    private final Members members;
    LibraryController(Library library,Members members) {this.library=library;this.members=members;}
    @GetMapping("/categories") Object categories(@AuthenticationPrincipal OidcUser user) {return library.categories(members.require(user).id());}
    @GetMapping Object list(@AuthenticationPrincipal OidcUser user,@RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="") String category,@RequestParam(defaultValue="all") String filter,
        @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="24") int limit) {
        return library.search(members.require(user).id(),q,category,filter,offset,limit,false);
    }
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object create(@AuthenticationPrincipal OidcUser user,@RequestBody Content input) {return library.create(members.require(user).id(),input);}
    @GetMapping("/{id}") Object read(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id) {return library.read(members.require(user).id(),id);}
    @PutMapping("/{id}") Object update(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id,@RequestBody Library.Draft input) {return library.update(members.require(user).id(),id,input);}
    @PutMapping("/{id}/favorite") Object favorite(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id,@RequestBody Library.Favorite input) {return library.favorite(members.require(user).id(),id,input.favorite());}
    @GetMapping("/{id}/versions") Object versions(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id) {return library.versions(members.require(user).id(),id);}
    @PostMapping("/{id}/versions") Object save(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id,@RequestBody Library.Save input) {return library.save(members.require(user).id(),id,input);}
    @GetMapping("/{id}/versions/{number}") Object version(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id,@PathVariable int number) {return library.version(members.require(user).id(),id,number);}
}
