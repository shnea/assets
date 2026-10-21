package kr.shnea.assets;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
class OperationsController {
    private final Operations operations; private final Members members;
    OperationsController(Operations operations,Members members) {this.operations=operations;this.members=members;}
    @GetMapping("/stats") Object stats(@AuthenticationPrincipal OidcUser user,@RequestParam(defaultValue="30") int days) {return operations.stats(members.require(user),days);}
    @GetMapping("/quotas") Object quotas(@AuthenticationPrincipal OidcUser user,@RequestParam(defaultValue="0") int offset) {return operations.quotas(members.require(user),offset);}
    @PutMapping("/quota-default") void defaults(@AuthenticationPrincipal OidcUser user,@RequestBody Operations.DefaultQuota input) {operations.defaults(members.require(user),input);}
    @PutMapping("/quotas/{id}") void quota(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id,@RequestBody Operations.MemberQuota input) {operations.quota(members.require(user),id,input);}
}
