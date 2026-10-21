// Exercise the installed Codex MCP client, without starting a model turn or changing user config.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {randomUUID} from 'node:crypto';
import {Member,base} from './smoke.mjs';

const launcher=process.argv[2];
const canWrite=process.argv.includes('--write');
if(!launcher)throw new Error('Pass the absolute path to the installed @openai/codex/bin/codex.js.');
const member=await new Member().login('alice');
const connection=await member.api('/api/connections','POST',{name:'Codex 클라이언트 검증',days:1,canWrite});
assert.equal(connection.status,200);
const serverName='assets_contract_'+randomUUID().replaceAll('-','');
const child=spawn(process.execPath,[launcher,'app-server','--listen','stdio://',
  '-c',`mcp_servers.${serverName}.url="${base}/mcp"`,
  '-c',`mcp_servers.${serverName}.bearer_token_env_var="ASSETS_QA_MCP_TOKEN"`],
  {env:{...process.env,ASSETS_QA_MCP_TOKEN:connection.data.token},stdio:['pipe','pipe','pipe'],windowsHide:true});
child.stderr.resume();
let serial=0;const pending=new Map();
const lines=createInterface({input:child.stdout});
lines.on('line',line=>{
  let value;try{value=JSON.parse(line);}catch{return;}
  const waiter=pending.get(value.id);if(!waiter)return;
  pending.delete(value.id);clearTimeout(waiter.timeout);
  if(value.error)waiter.reject(new Error(value.error.message));else waiter.resolve(value.result);
});
child.on('exit',()=>{for(const waiter of pending.values()){clearTimeout(waiter.timeout);waiter.reject(new Error('Codex client exited before verification completed.'));}pending.clear();});
const rpc=(method,params)=>new Promise((resolve,reject)=>{
  const id=++serial;const timeout=setTimeout(()=>{pending.delete(id);reject(new Error('Codex MCP client verification timed out.'));},45000);
  pending.set(id,{resolve,reject,timeout});child.stdin.write(JSON.stringify({id,method,params})+'\n');
});
try{
  await rpc('initialize',{clientInfo:{name:'assets-contract-verification',title:null,version:'1.0.0'},capabilities:{experimentalApi:true,requestAttestation:false}});
  child.stdin.write(JSON.stringify({method:'initialized'})+'\n');
  const status=await rpc('mcpServerStatus/list',{serverName,limit:10});
  const server=status.data.find(item=>item.name===serverName);assert.ok(server);assert.equal(server.toolsError,null);
  const names=Object.values(server.tools).map(tool=>tool.name).sort();
  const expected=['get_references','list_versions','read_asset','read_version','search_assets'];
  if(canWrite)expected.push('create_asset','add_asset_version');
  assert.deepEqual(names,expected.sort());
  assert.equal(server.serverInfo?.name,'shnea-assets');
  console.log(`PASS: installed Codex MCP client initialized Assets and discovered ${expected.length} scoped tools; no model turn or persistent configuration change`);
}finally{
  await member.api('/api/connections/'+connection.data.id,'DELETE');
  child.stdin.end();lines.close();child.kill();
}
