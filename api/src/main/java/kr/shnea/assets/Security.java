package kr.shnea.assets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Value;

@Configuration
class Security {
    @Bean
    SecurityFilterChain web(HttpSecurity http, ClientRegistrationRepository clients,
            @Value("${assets.url}") String appUrl) throws Exception {
        var resolver=new DefaultOAuth2AuthorizationRequestResolver(clients,"/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var logout=new OidcClientInitiatedLogoutSuccessHandler(clients);
        logout.setPostLogoutRedirectUri(appUrl+"/");
        http.authorizeHttpRequests(a->a.requestMatchers("/api/health","/api/session","/oauth2/**","/callback","/error","/mcp","/mcp/**").permitAll().anyRequest().authenticated())
            .csrf(c->c.ignoringRequestMatchers("/mcp","/mcp/**"))
            .requestCache(c->c.disable())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,error)->{
                res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"code\":\"LOGIN_REQUIRED\",\"message\":\"로그인이 필요합니다.\"}");
            }))
            .oauth2Login(o->o.authorizationEndpoint(a->a.authorizationRequestResolver(resolver))
                .redirectionEndpoint(r->r.baseUri("/callback"))
                .defaultSuccessUrl(appUrl+"/",true)
                .failureUrl(appUrl+"/?login=failed"))
            .logout(l->l.logoutUrl("/api/logout").logoutSuccessHandler(logout))
            .headers(h->h.frameOptions(f->f.deny()));
        return http.build();
    }
}
