package kr.shnea.assets;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/connections")
class ConnectionsController {
    private final Connections connections; private final Members members;
    ConnectionsController(Connections connections,Members members) {this.connections=connections;this.members=members;}
    @GetMapping Object list(@AuthenticationPrincipal OidcUser user) {return connections.list(members.require(user).id());}
    @PostMapping Object create(@AuthenticationPrincipal OidcUser user,@RequestBody Connections.Create input) {return connections.create(members.require(user).id(),input);}
    @DeleteMapping("/{id}") void revoke(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id) {connections.revoke(members.require(user).id(),id);}
}
