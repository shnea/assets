// Local OIDC + file-contract fixture. Only compose.qa.yml starts this process.
import http from 'node:http';
import {generateKeyPairSync,randomUUID,randomBytes,createHash,sign} from 'node:crypto';
const issuer='http://identity:8080',browser='http://localhost:30172',callback='http://localhost:30171/callback';
const {privateKey,publicKey}=generateKeyPairSync('rsa',{modulusLength:2048});
const jwk={...publicKey.export({format:'jwk'}),kid:'local-qa',alg:'RS256',use:'sig'};
const codes=new Map(),tokens=new Map(),files=new Map(),requests=new Map(),viewTokens=new Map();
const hash=b=>createHash('sha256').update(b).digest('hex');
const escape=s=>String(s).replaceAll('&','&amp;').replaceAll('"','&quot;').replaceAll('<','&lt;');
function jwt(claims){const head=Buffer.from(JSON.stringify({alg:'RS256',kid:'local-qa',typ:'JWT'})).toString('base64url');const body=Buffer.from(JSON.stringify(claims)).toString('base64url');const value=head+'.'+body;return value+'.'+sign('RSA-SHA256',Buffer.from(value),privateKey).toString('base64url');}
function uploadView(f){return {uploadId:f.id,state:f.state,size:f.size,receivedBytes:f.data.length,maxChunkBytes:8388608,expiresAt:f.expiresAt,fileId:f.state==='READY'?f.id:null};}
function fileView(f){return {fileId:f.id,originalName:f.name,size:f.size,sha256:f.sha256,visibility:f.visibility,retentionCode:f.retentionCode};}
const server=http.createServer(async(req,res)=>{
  const url=new URL(req.url,'http://identity:8080');
  const json=(status,data)=>{res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(data));};
  const redirect=url=>{res.writeHead(302,{Location:url});res.end();};
  try{
    const chunks=[];let size=0;
    for await(const chunk of req){size+=chunk.length;if(size>9*1024*1024){json(413,{error:'fixture_request_limit'});return;}chunks.push(chunk);}
    const data=Buffer.concat(chunks);
    if(url.pathname==='/.well-known/openid-configuration')return json(200,{issuer,authorization_endpoint:browser+'/authorize',token_endpoint:issuer+'/token',jwks_uri:issuer+'/jwks',userinfo_endpoint:issuer+'/userinfo',end_session_endpoint:browser+'/logout',response_types_supported:['code'],subject_types_supported:['public'],id_token_signing_alg_values_supported:['RS256'],scopes_supported:['openid','profile','email'],token_endpoint_auth_methods_supported:['none'],code_challenge_methods_supported:['S256']});
    if(url.pathname==='/jwks')return json(200,{keys:[jwk]});
    if(url.pathname==='/authorize'){
      const params=req.method==='POST'?new URLSearchParams(data.toString()):url.searchParams;
      if(params.get('client_id')!=='app'||params.get('redirect_uri')!==callback||params.get('code_challenge_method')!=='S256')return json(400,{error:'invalid_request'});
      if(req.method==='GET'){
        res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store'});
        const hidden=[...params].map(([key,value])=>`<input type="hidden" name="${escape(key)}" value="${escape(value)}">`).join('');
        return res.end(`<html lang="ko"><meta charset="utf-8"><title>Assets · 격리된 로그인 검증</title><main><h1>테스트 계정으로 로그인</h1><p>실제 플랫폼 계정과 분리된 검증 환경입니다.</p><form method="post">${hidden}<button name="subject" value="alice">Alice</button> <button name="subject" value="bob">Bob</button> <button name="subject" value="admin">운영 관리자</button></form></main></html>`);
      }
      const subject=params.get('subject');if(!['alice','bob','admin'].includes(subject))return json(400,{error:'invalid_subject'});
      const code=randomUUID();codes.set(code,{subject,nonce:params.get('nonce'),challenge:params.get('code_challenge'),expires:Date.now()+120000});
      const target=new URL(callback);target.searchParams.set('code',code);target.searchParams.set('state',params.get('state'));return redirect(target.toString());
    }
    if(url.pathname==='/token'&&req.method==='POST'){
      const params=new URLSearchParams(data.toString()),code=params.get('code'),entry=codes.get(code);codes.delete(code);
      if(!entry||entry.expires<Date.now()||params.get('redirect_uri')!==callback||createHash('sha256').update(params.get('code_verifier')||'').digest('base64url')!==entry.challenge)return json(400,{error:'invalid_grant'});
      const token=randomBytes(32).toString('base64url');tokens.set(token,entry.subject);const now=Math.floor(Date.now()/1000);
      return json(200,{access_token:token,token_type:'Bearer',expires_in:3600,scope:'openid profile email',id_token:jwt({iss:issuer,sub:entry.subject,aud:'app',iat:now,exp:now+3600,nonce:entry.nonce,given_name:entry.subject==='admin'?'운영 관리자':entry.subject==='alice'?'앨리스':'밥',email:entry.subject+'@example.test'})});
    }
    if(url.pathname==='/userinfo'){
      const subject=tokens.get(req.headers.authorization?.replace('Bearer ',''));return subject?json(200,{sub:subject,given_name:subject==='admin'?'운영 관리자':subject==='alice'?'앨리스':'밥'}):json(401,{error:'invalid_token'});
    }
    if(url.pathname==='/logout')return redirect('http://localhost:30171/');
    const contentMatch=url.pathname.match(/^\/api\/v1\/files\/([^/]+)\/content\/(thumbnail|preview|original|download)$/);
    if(contentMatch){
      const f=files.get(contentMatch[1]),ticket=viewTokens.get(url.searchParams.get('token'));
      if(!f||f.state!=='READY'||!ticket||ticket.id!==f.id||ticket.expires<Date.now())return json(404,{error:'missing'});
      const image=/\.(png|jpe?g|webp|gif)$/i.test(f.name);
      res.writeHead(200,{'Content-Type':image?'image/png':'application/octet-stream','Content-Length':f.data.length,'Cache-Control':'no-store'});return res.end(f.data);
    }
    if(req.headers['x-platform-key']!=='qa-key')return json(401,{error:'key_required'});
    if(url.pathname==='/api/v1/integration/context')return json(200,{projectId:'00000000-0000-0000-0000-000000000001',environmentId:'00000000-0000-0000-0000-000000000002',issuer,scopes:['integration:read','files:read','files:write','files:delete']});
    if(url.pathname==='/api/v1/files/uploads'&&req.method==='POST'){
      const input=JSON.parse(data.toString());if(input.visibility!=='PRIVATE'||input.retentionCode!=='영구'||input.size>10*1024*1024)return json(400,{error:'fixture_contract'});
      let f=files.get(requests.get(input.requestId));if(!f){f={id:randomUUID(),name:input.originalName,size:input.size,sha256:input.sha256,visibility:input.visibility,retentionCode:input.retentionCode,state:'UPLOADING',data:Buffer.alloc(0),expiresAt:new Date(Date.now()+86400000).toISOString()};files.set(f.id,f);requests.set(input.requestId,f.id);}return json(201,uploadView(f));
    }
    const uploadMatch=url.pathname.match(/^\/api\/v1\/files\/uploads\/([^/]+)(\/complete)?$/);
    if(uploadMatch){
      const f=files.get(uploadMatch[1]);if(!f)return json(404,{error:'missing'});
      if(req.method==='GET')return json(200,uploadView(f));
      if(req.method==='DELETE'){if(f.state==='READY')return json(409,{error:'ready'});f.state='CANCELLED';res.writeHead(204);return res.end();}
      if(f.state!=='UPLOADING'&&f.state!=='READY')return json(409,{error:'closed'});
      if(req.method==='PATCH'){if(f.data.length!==Number(req.headers['upload-offset'])||hash(data)!==req.headers['x-chunk-sha256'])return json(409,{error:'offset_or_hash'});f.data=Buffer.concat([f.data,data]);return json(200,uploadView(f));}
      if(req.method==='POST'&&uploadMatch[2]){if(f.data.length!==f.size||hash(f.data)!==f.sha256)return json(400,{error:'hash_mismatch'});f.state='READY';return json(200,fileView(f));}
    }
    const ticketMatch=url.pathname.match(/^\/api\/v1\/files\/([^/]+)\/view-ticket$/);
    if(ticketMatch&&req.method==='POST'){
      const f=files.get(ticketMatch[1]);if(!f||f.state!=='READY')return json(404,{error:'missing'});
      const token=randomBytes(24).toString('base64url'),expires=Date.now()+300000;viewTokens.set(token,{id:f.id,expires});
      const root='/api/v1/files/'+f.id,query='?token='+token,image=/\.(png|jpe?g|webp|gif)$/i.test(f.name);
      return json(200,{fileId:f.id,kind:image?'IMAGE':'FILE',state:image?'READY':'UNSUPPORTED',originalUrl:root+'/content/original'+query,downloadUrl:root+'/content/download'+query,viewerUrl:root+'/view'+query,thumbnailUrl:image?root+'/content/thumbnail'+query:null,previewUrl:image?root+'/content/preview'+query:null,expiresAt:new Date(expires).toISOString()});
    }
    const fileMatch=url.pathname.match(/^\/api\/v1\/files\/([^/]+)(\/download)?$/);
    if(fileMatch){const f=files.get(fileMatch[1]);if(!f||f.state!=='READY')return json(404,{error:'missing'});if(req.method==='DELETE'){f.state='DELETED';res.writeHead(204);return res.end();}if(fileMatch[2]){res.writeHead(200,{'Content-Type':'application/octet-stream','Content-Length':f.data.length});return res.end(f.data);}return json(200,fileView(f));}
    json(404,{error:'missing'});
  }catch{if(!res.headersSent)json(500,{error:'fixture_failure'});else res.end();}
});
server.listen(8080,'0.0.0.0',()=>process.stdout.write('Isolated QA identity and file fixture ready\n'));
