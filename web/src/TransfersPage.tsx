import {useEffect,useRef,useState} from 'react';
import {api,bytes} from './api';
import {Heading,Notice} from './main';

type Restored={assets:number;ideas:number;files:number;firstAssetId:string|null};
type Guard={current:(()=>Promise<boolean>)|null};

export function TransfersPage({guard}:{guard:Guard}){
  const [file,setFile]=useState<File>(),[busy,setBusy]=useState(false),[checking,setChecking]=useState(false),[error,setError]=useState(''),[result,setResult]=useState<Restored>();
  const input=useRef<HTMLInputElement>(null);
  useEffect(()=>{
    if(!busy)return;
    const block=async()=>false;
    const unload=(event:BeforeUnloadEvent)=>{event.preventDefault();event.returnValue='';};
    guard.current=block;window.addEventListener('beforeunload',unload);
    return()=>{if(guard.current===block)guard.current=null;window.removeEventListener('beforeunload',unload);};
  },[busy,guard]);

  async function restore(){
    if(!file||busy)return;
    setBusy(true);setError('');setResult(undefined);
    try{
      const restored=await api<Restored>('/api/backup/import',{method:'POST',headers:{'Content-Type':'application/zip'},body:file});
      setResult(restored);setFile(undefined);if(input.current)input.current.value='';window.dispatchEvent(new Event('assets:storage-changed'));window.dispatchEvent(new Event('assets:categories-changed'));
    }catch(e){setError((e as Error).message);}finally{setBusy(false);}
  }

  async function download(){
    setChecking(true);setError('');
    try{await api('/api/backup/check');const link=document.createElement('a');link.href='/api/backup';link.download='';link.click();}
    catch(e){setError((e as Error).message);}finally{setChecking(false);}
  }

  return <div><Heading title="백업·가져오기" description="" actions={<a className="button" href="#/assets">내 자산</a>}/>
    <section className="paper"><h2>내 보관함 백업</h2><p>제작법·이전 버전·아이디어와 첨부 원본을 ZIP으로 저장합니다. 본문 JSON과 Markdown도 함께 담습니다.</p>
      <button disabled={busy||checking} onClick={()=>void download()}>{checking?'원본 확인 중…':'백업 ZIP 다운로드'}</button>
    </section>
    <section className="paper"><h2>백업 가져오기</h2><p>Assets에서 받은 백업 ZIP을 선택하세요. 기존 자료를 유지하고 새 복사본으로 추가합니다.</p>
      <div className="file-dropzone" aria-label="백업 ZIP 놓기" role="group" aria-busy={busy}
        onDragOver={event=>{if(event.dataTransfer.types.includes('Files'))event.preventDefault();}}
        onDrop={event=>{event.preventDefault();if(!busy){setFile(event.dataTransfer.files[0]);setError('');setResult(undefined);}}}>
        <strong>{file?file.name:'백업 ZIP을 끌어 놓으세요'}</strong>{file&&<span>{bytes(file.size)}</span>}
        <button disabled={busy} onClick={()=>input.current?.click()}>백업 파일 선택</button>
        <input ref={input} type="file" accept=".zip,application/zip" hidden disabled={busy} aria-label="백업 ZIP 선택" onChange={event=>{setFile(event.target.files?.[0]);setError('');setResult(undefined);}}/>
      </div>
      <div className="row-actions transfer-actions"><button className="primary" disabled={!file||busy} onClick={()=>void restore()}>{busy?'가져오는 중…':'새 복사본으로 가져오기'}</button></div>
      {busy&&<p role="status">원본 파일을 확인하고 보관함에 추가하고 있습니다.</p>}
      {error&&<Notice error>{error}</Notice>}
      {result&&<Notice>제작법 {result.assets}개 · 아이디어 {result.ideas}개 · 파일 {result.files}개를 가져왔습니다. {result.firstAssetId&&<a href={'#/assets/'+result.firstAssetId}>가져온 제작법 보기</a>}</Notice>}
    </section>
  </div>;
}

export function ExportMenu({assetId,number}:{assetId:string;number?:number}){
  const query=number===undefined?'':'&number='+number;
  const link=(format:string)=>`/api/assets/${assetId}/export?format=${format}${query}`;
  return <details className="export-menu"><summary className="button">내보내기</summary><div>
    <small>{number===undefined?'작성 중인 초안':'버전 '+number}</small>
    <a href={link('markdown')} download>Markdown</a><a href={link('json')} download>JSON</a>
    {number!==undefined&&<a href={link('skill')} download>에이전트 스킬 ZIP</a>}
  </div></details>;
}
