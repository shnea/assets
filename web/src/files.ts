import {createSHA256} from 'hash-wasm';
import {api,type FileInfo} from './api';
type UploadOptions={assetId?:string;ideaId?:string;requestId?:string;resume?:FileInfo;signal?:AbortSignal;pause?:()=>boolean;progress?:(percent:number,label:string)=>void};
export async function uploadFile(file:File,options:UploadOptions):Promise<FileInfo>{
  const {signal,progress}=options;let upload:FileInfo|undefined=options.resume;
  const check=()=>signal?.throwIfAborted();
  try{
    if(file.size>5e9)throw new Error('파일 하나는 최대 5GB까지 보관할 수 있어요.');
    const hasher=await createSHA256();hasher.init();
    for(let offset=0;offset<file.size;offset+=4*1024*1024){check();hasher.update(new Uint8Array(await file.slice(offset,offset+4*1024*1024).arrayBuffer()));progress?.(Math.min(20,(offset+4*1024*1024)/Math.max(1,file.size)*20),'원본 확인 중');}
    check();if(options.pause?.())throw new DOMException('원본 확인을 멈췄습니다.','AbortError');const hash=hasher.digest('hex');
    if(upload&&(upload.size!==file.size||upload.sha256!==hash))throw new Error('처음 올린 파일과 같은 원본을 선택해 주세요.');
    if(!upload)upload=await api<FileInfo>('/api/uploads',{method:'POST',body:JSON.stringify({requestId:options.requestId||crypto.randomUUID(),assetId:options.assetId,ideaId:options.ideaId,name:file.name,size:file.size,sha256:hash}),signal});
    window.dispatchEvent(new Event('assets:storage-changed'));
    upload=await api<FileInfo>('/api/uploads/'+upload.id,{signal});
    if(upload.state==='READY')return upload;
    for(let offset=upload.receivedBytes;offset<file.size;){
      if(options.pause?.())return upload;
      check();const chunk=await file.slice(offset,offset+8*1024*1024).arrayBuffer();
      const digest=await crypto.subtle.digest('SHA-256',chunk);
      const chunkHash=Array.from(new Uint8Array(digest),v=>v.toString(16).padStart(2,'0')).join('');
      upload=await api<FileInfo>('/api/uploads/'+upload.id,{method:'PATCH',headers:{'Content-Type':'application/octet-stream','Upload-Offset':String(offset),'X-Chunk-SHA256':chunkHash},body:chunk,signal});
      offset=upload.receivedBytes;progress?.(20+offset/Math.max(1,file.size)*75,'업로드 중');
    }
    check();if(options.pause?.())return upload;progress?.(95,'파일 확인 중');
    const completed=await api<FileInfo>('/api/uploads/'+upload.id+'/complete',{method:'POST',signal});
    progress?.(100,'업로드 완료');window.dispatchEvent(new Event('assets:storage-changed'));return completed;
  }catch(error){
    if(signal?.aborted&&upload?.state==='UPLOADING')await api('/api/uploads/'+upload.id,{method:'DELETE'}).catch(()=>{});
    window.dispatchEvent(new Event('assets:storage-changed'));
    throw error;
  }
}
