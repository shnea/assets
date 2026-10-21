package kr.shnea.assets;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;

@Service
class Platform {
    private final RestClient client;
    private final String key,project,environment,issuer;
    Platform(@Value("${assets.platform-url}") String base,@Value("${assets.platform-key}") String key,
             @Value("${assets.platform-project}") String project,@Value("${assets.platform-environment}") String environment,
             @Value("${spring.security.oauth2.client.provider.platform.issuer-uri}") String issuer) {
        this.key=key;this.project=project;this.environment=environment;this.issuer=issuer;
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(http);factory.setReadTimeout(Duration.ofMinutes(10));
        client=RestClient.builder().baseUrl(base).requestFactory(factory).defaultHeader("X-Platform-Key",key).build();
    }
    void configured() {if(key.isBlank()) throw new Failure(503,"PLATFORM_NOT_CONFIGURED","파일 서비스 연결 설정이 필요합니다.");}
    @SuppressWarnings("unchecked")
    Map<String,Object> request(HttpMethod method,String path,Object body) {
        configured();
        try {
            var req=client.method(method).uri(path);
            if(body!=null) req.contentType(MediaType.APPLICATION_JSON).body(body);
            var result=req.retrieve().body(Map.class);
            return result==null?Map.of():result;
        } catch(RestClientResponseException e) {
            if(e.getStatusCode().value()==409) throw new Failure(409,"PLATFORM_CONFLICT","파일 상태가 변경되었습니다. 업로드 상태를 확인해 주세요.");
            throw new Failure(502,"PLATFORM_FILE_ERROR","파일 서비스 요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        } catch(RestClientException e) {throw new Failure(503,"PLATFORM_UNAVAILABLE","파일 서비스에 연결할 수 없습니다.");}
    }
    Map<String,Object> check() {
        var context=request(HttpMethod.GET,"/api/v1/integration/context",null);
        if(!project.equals(String.valueOf(context.get("projectId"))) || !environment.equals(String.valueOf(context.get("environmentId"))) || !issuer.equals(context.get("issuer")))
            throw new Failure(503,"PLATFORM_SCOPE_MISMATCH","플랫폼의 프로젝트·환경 연결을 확인해 주세요.");
        return Map.of("connected",true);
    }
    Map<String,Object> begin(UUID request,String name,long size,String hash) {
        check();
        return request(HttpMethod.POST,"/api/v1/files/uploads",Map.of("requestId",request,"originalName",name,"size",size,"sha256",hash,"visibility","PRIVATE","retentionCode","영구"));
    }
    Map<String,Object> status(UUID id) {return request(HttpMethod.GET,"/api/v1/files/uploads/"+id,null);}
    Map<String,Object> complete(UUID id) {return request(HttpMethod.POST,"/api/v1/files/uploads/"+id+"/complete",Map.of());}
    Map<String,Object> views(UUID id) {return request(HttpMethod.POST,"/api/v1/files/"+id+"/view-ticket",Map.of());}
    Map<String,Object> metadata(UUID id) {return request(HttpMethod.GET,"/api/v1/files/"+id,null);}
    void cancel(UUID id) {request(HttpMethod.DELETE,"/api/v1/files/uploads/"+id,null);}
    void delete(UUID id) {request(HttpMethod.DELETE,"/api/v1/files/"+id,null);}
    void copy(UUID id,java.io.OutputStream output) {
        configured();
        try {
            client.get().uri("/api/v1/files/"+id+"/download").exchange((req,res)->{
                if(!res.getStatusCode().is2xxSuccessful()) throw new Failure(502,"FILE_DOWNLOAD_FAILED","백업할 원본 파일을 가져오지 못했습니다.");
                res.getBody().transferTo(output);return null;
            });
        } catch(RestClientException e) {throw new Failure(502,"FILE_DOWNLOAD_FAILED","백업할 원본 파일을 가져오지 못했습니다.");}
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> append(UUID id,long offset,String hash,byte[] chunk) {
        configured();
        try {
            return client.patch().uri("/api/v1/files/uploads/"+id).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Upload-Offset",Long.toString(offset)).header("X-Chunk-SHA256",hash).contentLength(chunk.length).body(chunk).retrieve().body(Map.class);
        } catch(RestClientException e) {throw new Failure(502,"UPLOAD_INTERRUPTED","전송이 중단되었습니다. 상태를 확인한 뒤 다시 시도해 주세요.");}
    }
    void download(UUID id,String name,String range,HttpServletResponse response) {
        configured();
        try {
            var request=client.get().uri("/api/v1/files/"+id+"/download");
            if(range!=null) request.header("Range",range);
            request.exchange((req,res)->{
                if(!res.getStatusCode().is2xxSuccessful()) throw new Failure(502,"FILE_DOWNLOAD_FAILED","파일을 가져오지 못했습니다.");
                response.setStatus(res.getStatusCode().value());
                response.setContentType("application/octet-stream");
                response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(name,java.nio.charset.StandardCharsets.UTF_8).build().toString());
                for(String header:List.of("Content-Length","Content-Range","Accept-Ranges")) {
                    String value=res.getHeaders().getFirst(header);if(value!=null) response.setHeader(header,value);
                }
                response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
                res.getBody().transferTo(response.getOutputStream());return null;
            });
        } catch(RestClientException e) {throw new Failure(502,"FILE_DOWNLOAD_FAILED","파일을 가져오지 못했습니다.");}
    }
}
