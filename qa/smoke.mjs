import assert from 'node:assert/strict';
import {createHash,randomUUID} from 'node:crypto';
import {mkdir,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {StreamableHTTPClientTransport} from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const base='http://localhost:30171';
class Member {
  cookies=new Map();csrf='';
  async request(url,options={}){
    const headers=new Headers(options.headers);
    if(this.cookies.size)headers.set('Cookie',[...this.cookies].map(([key,value])=>`${key}=${value}`).join('; '));
    const response=await fetch(url.startsWith('http')?url:base+url,{...options,headers,redirect:'manual'});
    for(const cookie of response.headers.getSetCookie()){const [pair]=cookie.split(';'),index=pair.indexOf('=');this.cookies.set(pair.slice(0,index),pair.slice(index+1));}
    return response;
  }
  async api(path,method='GET',body){
    const headers={'X-CSRF-TOKEN':this.csrf};if(body!==undefined)headers['Content-Type']='application/json';
    const response=await this.request(path,{method,headers,body:body===undefined?undefined:JSON.stringify(body)});
    const text=await response.text();const data=text?JSON.parse(text):null;
    return {status:response.status,data};
  }
  async login(subject){
    const initial=await this.api('/api/session');this.csrf=initial.data.csrfToken;
    const start=await this.request('/oauth2/authorization/platform');assert.equal(start.status,302);
    const authorize=new URL(start.headers.get('location'));
    assert.equal(authorize.searchParams.get('code_challenge_method'),'S256');
    const form=authorize.searchParams;form.set('subject',subject);
    const signed=await this.request('http://localhost:30172/authorize',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:form.toString()});
    assert.equal(signed.status,302);
    const callback=await this.request(signed.headers.get('location'));assert.equal(callback.status,302);
    assert.equal(callback.headers.get('location'),base+'/');
    const session=await this.api('/api/session');assert.equal(session.data.authenticated,true);this.csrf=session.data.csrfToken;
    this.id=session.data.member.id;return this;
  }
}
export {Member,base};
export async function runSmoke(){
const alice=await new Member().login('alice'),bob=await new Member().login('bob'),admin=await new Member().login('admin');
assert.equal((await admin.api('/api/admin/quotas/'+alice.id,'PUT',{bytes:null})).status,200);
const initialUsage=(await alice.api('/api/storage')).data.usedBytes;
const marker=randomUUID();
const category='개인 제작 분류 '+marker;
const created=await alice.api('/api/assets','POST',{title:'검증용 제작법 '+marker,work:'원래 작업',category,terms:[{name:'Previs',explanation:'제작 전 장면을 미리 구성해보는 작업',example:'카메라 이동을 먼저 확인한다.'}]});
assert.equal(created.status,201);let asset=created.data;
assert.ok((await alice.api('/api/assets/categories')).data.includes(category));
assert.ok(!(await bob.api('/api/assets/categories')).data.includes(category));
const idea=await alice.api('/api/ideas','POST',{body:'MCP에 제공하지 않는 아이디어 '+marker});assert.equal(idea.status,201);
const payload=Buffer.from('Private attachment '+marker),digest=createHash('sha256').update(payload).digest('hex');
let upload=await alice.api('/api/uploads','POST',{requestId:randomUUID(),assetId:asset.id,name:'검증용 첨부.txt',size:payload.length,sha256:digest});assert.equal(upload.status,200);
const chunk=await alice.request('/api/uploads/'+upload.data.id,{method:'PATCH',headers:{'Content-Type':'application/octet-stream','X-CSRF-TOKEN':alice.csrf,'Upload-Offset':'0','X-Chunk-SHA256':digest},body:payload});assert.equal(chunk.status,200);
upload=await alice.api('/api/uploads/'+upload.data.id+'/complete','POST');assert.equal(upload.status,200);
const fileId=upload.data.id;
const updated=await alice.api('/api/assets/'+asset.id,'PUT',{revision:asset.revision,content:{...asset.draft,fileIds:[fileId]}});assert.equal(updated.status,200);asset=updated.data;
assert.equal((await alice.api('/api/assets/'+asset.id+'/versions','POST',{revision:asset.revision,changeNote:'첨부파일과 함께 첫 저장'})).status,200);
asset=(await alice.api('/api/assets/'+asset.id)).data;
asset=(await alice.api('/api/assets/'+asset.id,'PUT',{revision:asset.revision,content:{...asset.draft,work:'새 도구로 바꾼 작업'}})).data;
assert.equal((await alice.api('/api/assets/'+asset.id+'/versions','POST',{revision:asset.revision,changeNote:'도구 전환'})).status,200);
assert.equal((await alice.api(`/api/assets/${asset.id}/versions/1`)).data.content.work,'원래 작업');
assert.equal((await alice.api('/api/storage')).data.usedBytes,initialUsage+payload.length);
for(const other of [bob,admin]){
  for(const path of [`/api/assets/${asset.id}`,`/api/assets/${asset.id}/versions/1`,`/api/ideas/${idea.data.id}`,`/api/files/${fileId}/download`,`/api/uploads/${fileId}`])assert.equal((await other.request(path)).status,404);
  assert.equal((await other.api('/api/assets?q='+marker)).data.total,0);
}
assert.equal((await bob.api('/api/admin/stats')).status,403);
const stats=await admin.api('/api/admin/stats');assert.equal(stats.status,200);
assert.ok(!JSON.stringify(stats.data).includes(marker));
assert.equal((await admin.api('/api/admin/quotas/'+alice.id,'PUT',{bytes:1})).status,200);
assert.equal((await alice.request(`/api/files/${fileId}/download`)).status,200);
assert.equal((await alice.api('/api/uploads','POST',{requestId:randomUUID(),assetId:asset.id,name:'초과한 파일',size:2,sha256:'0'.repeat(64)})).status,413);
assert.equal((await admin.api('/api/admin/quotas/'+alice.id,'PUT',{bytes:null})).status,200);
const connection=await alice.api('/api/connections','POST',{name:'Codex SDK 검증',days:7});assert.equal(connection.status,200);
const client=new Client({name:'assets-contract-check',version:'1.0.0'});
const transport=new StreamableHTTPClientTransport(new URL(base+'/mcp'),{requestInit:{headers:{Authorization:'Bearer '+connection.data.token}}});
await client.connect(transport);
const tools=await client.listTools();assert.equal(tools.tools.length,5);
const search=await client.callTool({name:'search_assets',arguments:{query:marker}});
const found=JSON.parse(search.content[0].text);assert.equal(found.total,1);assert.equal(found.items[0].draft.work,'새 도구로 바꾼 작업');
const first=await client.callTool({name:'read_version',arguments:{assetId:asset.id,number:1}});assert.equal(JSON.parse(first.content[0].text).content.work,'원래 작업');
const hidden=await client.callTool({name:'read_asset',arguments:{assetId:idea.data.id}});assert.equal(hidden.isError,true);
const references=await client.callTool({name:'get_references',arguments:{assetId:asset.id}});assert.equal(JSON.parse(references.content[0].text).files.length,1);
const agentFile=await fetch(base+'/mcp/files/'+fileId,{headers:{Authorization:'Bearer '+connection.data.token}});assert.equal(agentFile.status,200);assert.equal(await agentFile.text(),payload.toString());
await client.close();
assert.equal((await alice.api('/api/connections/'+connection.data.id,'DELETE')).status,200);
const denied=await fetch(base+'/mcp',{method:'POST',headers:{Authorization:'Bearer '+connection.data.token,'Content-Type':'application/json'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'ping'})});assert.equal(denied.status,401);
for(const format of ['markdown','json','skill']){
  const exported=await alice.request(`/api/assets/${asset.id}/export?format=${format}&number=1`);assert.equal(exported.status,200);
  const data=Buffer.from(await exported.arrayBuffer());
  if(format==='skill')assert.equal(data.subarray(0,2).toString(),'PK');else assert.ok(data.toString().includes('원래 작업'));
  for(const other of [bob,admin])assert.equal((await other.request(`/api/assets/${asset.id}/export?format=${format}&number=1`)).status,404);
}
const backupResponse=await alice.request('/api/backup');assert.equal(backupResponse.status,200);
const archive=Buffer.from(await backupResponse.arrayBuffer());assert.equal(archive.subarray(0,2).toString(),'PK');
await mkdir(new URL('../work/verification/',import.meta.url),{recursive:true});
await writeFile(new URL('../work/verification/qa-backup.zip',import.meta.url),archive);
const restoredResponse=await bob.request('/api/backup/import',{method:'POST',headers:{'Content-Type':'application/zip','X-CSRF-TOKEN':bob.csrf},body:archive});
const restoreText=await restoredResponse.text();assert.equal(restoredResponse.status,200,restoreText);const restored=JSON.parse(restoreText);assert.ok(restored.assets>0&&restored.ideas>0&&restored.files>0);
const copies=(await bob.api('/api/assets?q='+marker)).data;assert.equal(copies.total,1);const copy=copies.items[0];assert.notEqual(copy.id,asset.id);
const copyVersion=(await bob.api(`/api/assets/${copy.id}/versions/1`)).data;assert.equal(copyVersion.content.work,'원래 작업');
const copyFile=copyVersion.content.fileIds[0];assert.notEqual(copyFile,fileId);
assert.equal(await (await bob.request(`/api/files/${copyFile}/download`)).text(),payload.toString());
for(const other of [alice,admin])assert.equal((await other.request(`/api/assets/${copy.id}`)).status,404);
const countBefore=(await bob.api('/api/assets')).data.total;
assert.equal((await bob.request('/api/backup/import',{method:'POST',headers:{'Content-Type':'application/zip','X-CSRF-TOKEN':bob.csrf},body:Buffer.from('Invalid archive')})).status,400);
assert.equal((await bob.api('/api/assets')).data.total,countBefore);
console.log('PASS: signed OIDC + PKCE, owner isolation, immutable versions, private original round trip, quotas/admin boundaries, official MCP SDK + revocation, version-specific exports, ZIP backup/restore with new owner/file IDs');
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href)await runSmoke();
