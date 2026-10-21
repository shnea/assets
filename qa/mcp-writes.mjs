// Use the official MCP client against the isolated QA environment.
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {StreamableHTTPClientTransport} from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import {Member,base} from './smoke.mjs';

const alice=await new Member().login('alice'),bob=await new Member().login('bob');
const keys=[],clients=[];
async function connect(member,canWrite){
  const key=await member.api('/api/connections','POST',{name:'MCP 등록·버전 검증',canWrite,days:1});assert.equal(key.status,200);keys.push([member,key.data.id]);
  const client=new Client({name:'assets-write-verification',version:'1.0.0'});clients.push(client);
  await client.connect(new StreamableHTTPClientTransport(new URL(base+'/mcp'),{requestInit:{headers:{Authorization:'Bearer '+key.data.token}}}));
  return client;
}
const decode=result=>{assert.equal(result.isError,false,JSON.stringify(result));return JSON.parse(result.content[0].text);};
try{
  const reader=await connect(alice,false),writer=await connect(alice,true),other=await connect(bob,true);
  assert.equal((await reader.listTools()).tools.length,5);
  const tools=(await writer.listTools()).tools;assert.equal(tools.length,7);assert.equal(tools.find(t=>t.name==='add_asset_version').annotations.readOnlyHint,false);
  const marker=randomUUID(),skill='# Current skill\n\nRead the requirements, then apply the selected production workflow.';
  const input={requestId:randomUUID(),content:{title:'등록한 스킬 '+marker,work:'현재 사용하는 제작 스킬 보관',remember:skill,start:'저장한 사용법을 읽고 스킬을 호출한다.',tools:[{kind:'스킬',name:'Current skill',note:'선택한 SKILL.md에서 읽은 원문'}]}};
  assert.equal((await reader.callTool({name:'create_asset',arguments:input})).isError,true);
  const registered=decode(await writer.callTool({name:'create_asset',arguments:input}));assert.equal(registered.version,1);assert.equal(registered.replayed,false);
  const retried=decode(await writer.callTool({name:'create_asset',arguments:input}));assert.equal(retried.assetId,registered.assetId);assert.equal(retried.replayed,true);
  const append={requestId:randomUUID(),assetId:registered.assetId,expectedVersion:1,changes:{remember:skill+'\n\nUpdated invocation for the next tool version.'},changeNote:'새 도구 버전에 맞춘 사용법'};
  assert.equal(decode(await writer.callTool({name:'add_asset_version',arguments:append})).version,2);
  assert.equal(decode(await reader.callTool({name:'read_version',arguments:{assetId:registered.assetId,number:1}})).content.remember,skill);
  const latest=decode(await reader.callTool({name:'read_asset',arguments:{assetId:registered.assetId}}));assert.equal(latest.number,2);assert.equal(latest.content.tools[0].kind,'스킬');
  assert.equal((await other.callTool({name:'add_asset_version',arguments:{...append,requestId:randomUUID(),expectedVersion:2}})).isError,true);
  assert.equal((await writer.callTool({name:'add_asset_version',arguments:{...append,requestId:randomUUID()}})).isError,true);
  let asset=(await alice.api('/api/assets/'+registered.assetId)).data;assert.equal(asset.version,2);
  const webDraft=await alice.api('/api/assets/'+asset.id,'PUT',{revision:asset.revision,content:{...asset.draft,start:'웹에서 작성 중인 내용'}});assert.equal(webDraft.status,200);
  assert.equal((await writer.callTool({name:'add_asset_version',arguments:{...append,requestId:randomUUID(),expectedVersion:2}})).isError,true);
  assert.equal((await alice.api('/api/assets/'+asset.id)).data.draft.start,'웹에서 작성 중인 내용');
  assert.equal((await writer.callTool({name:'delete_asset',arguments:{assetId:asset.id}})).isError,true);
  assert.equal((await bob.api('/api/assets/'+asset.id)).status,404);
  console.log('PASS: official MCP SDK skill registration, version append, immutable history, retry deduplication, scoped keys, owner isolation and pending web draft protection');
}finally{
  for(const client of clients)await client.close();
  for(const [member,id] of keys)await member.api('/api/connections/'+id,'DELETE');
}
