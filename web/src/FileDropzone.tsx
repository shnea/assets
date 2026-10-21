import {useEffect,useRef,useState,type ClipboardEvent,type DragEvent} from 'react';
import {api,bytes,type FileInfo} from './api';
import {uploadFile} from './files';

export function Attachments({assetId,ideaId,onAttach,selected,onBusyChange}:{assetId?:string;ideaId?:string;onAttach?:(id:string)=>void;selected?:string[];onBusyChange?:(busy:boolean)=>void}){
  const [files,setFiles]=useState<FileInfo[]>([]),[error,setError]=useState(''),[progress,setProgress]=useState(''),[busy,setBusy]=useState(false),[dragging,setDragging]=useState(false);
  const resume=useRef<FileInfo|null>(null),paused=useRef(false),input=useRef<HTMLInputElement>(null),dragDepth=useRef(0),running=useRef(false);
  const latestAttach=useRef(onAttach);latestAttach.current=onAttach;
  useEffect(()=>{onBusyChange?.(busy);return()=>onBusyChange?.(false);},[busy,onBusyChange]);
  const reload=()=>api<FileInfo[]>(`/api/files?${assetId?'assetId='+assetId:'ideaId='+ideaId}`).then(setFiles);
  useEffect(()=>{void reload().catch(e=>setError(e.message));},[assetId,ideaId]);
  async function acceptFiles(incoming:File[]){
    if(running.current||incoming.length===0)return;
    if(incoming.length>20){setError('한 번에 최대 20개까지 올릴 수 있어요.');return;}
    running.current=true;setBusy(true);setError('');paused.current=false;
    try{
      for(const file of incoming){
        const completed=await uploadFile(file,{assetId,ideaId,resume:resume.current??undefined,pause:()=>paused.current,progress:(percent,label)=>setProgress(`${file.name} · ${label} ${Math.round(percent)}%`)});
        if(completed.state==='READY'){latestAttach.current?.(completed.id);resume.current=null;}
        else {resume.current=completed;setProgress('일시 정지됨');break;}
      }
      await reload();
    }catch(e){setError(e instanceof Error?e.message:'파일을 올리지 못했습니다.');await reload().catch(()=>{});}
    finally{setBusy(false);running.current=false;if(input.current)input.current.value='';}
  }
  function paste(event:ClipboardEvent){const incoming=Array.from(event.clipboardData.files);if(incoming.length){event.preventDefault();void acceptFiles(incoming);}}
  function drag(event:DragEvent){if(event.dataTransfer.types.includes('Files')){event.preventDefault();event.dataTransfer.dropEffect='copy';}}
  function drop(event:DragEvent){event.preventDefault();setDragging(false);dragDepth.current=0;resume.current=null;void acceptFiles(Array.from(event.dataTransfer.files));}
  async function cancel(file:FileInfo){try{await api(`/api/uploads/${file.id}`,{method:'DELETE'});if(resume.current?.id===file.id)resume.current=null;await reload();}catch(e){setError((e as Error).message);}}
  return <section className="attachment-area"><h3>첨부파일</h3><div className={'file-dropzone '+(dragging?'dragging':'')} tabIndex={0} role="group" aria-label="파일 드롭 또는 이미지 붙여넣기" aria-busy={busy} onPaste={paste} onDragOver={drag} onDragEnter={event=>{if(event.dataTransfer.types.includes('Files')){event.preventDefault();dragDepth.current++;setDragging(true);}}} onDragLeave={()=>{dragDepth.current--;if(dragDepth.current<=0){dragDepth.current=0;setDragging(false);}}} onDrop={drop}>
    <span className="drop-symbol" aria-hidden="true">↥</span><strong>파일을 끌어 놓으세요</strong><span>복사한 이미지는 Ctrl+V로 붙여넣기</span><button disabled={busy} onClick={()=>{resume.current=null;input.current?.click();}}>파일 선택</button>
    <input ref={input} type="file" multiple hidden aria-label="첨부파일 선택" disabled={busy} onChange={event=>void acceptFiles(Array.from(event.target.files||[]))}/>
  </div>{busy&&<button className="text-button" onClick={()=>{paused.current=true;}}>일시 정지</button>}{progress&&<p role="status" className="small">{progress}</p>}{error&&<p role="alert" className="error">{error}</p>}
  <ul className="file-list">{files.map(file=><li key={file.id}><div><strong>{file.name}</strong><small>{bytes(file.size)} · {file.state==='READY'?'보관 중':`${bytes(file.receivedBytes)} 전송됨`}</small></div>{file.state==='READY'?<div className="row-actions">{onAttach&&!selected?.includes(file.id)&&<button onClick={()=>latestAttach.current?.(file.id)}>자산에 연결</button>}<a href={`/api/files/${file.id}/download`}>다운로드</a></div>:<div className="row-actions"><button disabled={busy} onClick={()=>{resume.current=file;input.current?.click();}}>이어서 올리기</button><button disabled={busy} onClick={()=>void cancel(file)}>취소</button></div>}</li>)}</ul></section>;
}
