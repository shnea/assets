import {createContext,useContext,useMemo,useState} from 'react';
import {ShneaEditor,ShneaViewer} from '@shnea/editor/react';
import type {AttachmentAdapter,AttachmentViews} from '@shnea/editor';
import {api} from './api';
import {document,type RichValue} from './documents';
import {uploadFile} from './files';
import '@shnea/editor/style.css';

export const PlatformImageOrigin=createContext('https://platform.shnea.kr');
const appearance={fontSize:14,lineHeight:1.8,contentPadding:16,radius:10,colors:{background:'#ffffff',text:'#243b33',muted:'#6b7971',border:'#dee5dd',accent:'#25644d',raised:'#e7f0e5'}};
function useAttachments(assetId?:string,ideaId?:string){
  const origin=useContext(PlatformImageOrigin);
  return useMemo<AttachmentAdapter>(()=>({
    platformImageOrigin:origin,
    scope:()=>assetId?'asset:'+assetId:ideaId?'idea:'+ideaId:undefined,
    async upload(file,context){
      const result=await uploadFile(file,{assetId,ideaId,requestId:context.requestId,signal:context.signal,progress:context.progress});
      if(!result.platformId)throw new Error('완료된 파일 정보를 확인하지 못했습니다.');
      return {fileId:result.platformId,scope:context.scope,kind:context.kind,name:result.name,size:result.size};
    },
    async resolve(file,signal){
      const views=await api<AttachmentViews>(`/api/editor/files/${encodeURIComponent(file.fileId)}/views`,{signal});
      if(file.kind==='image')return views;
      const absolute=(value:string|null|undefined)=>value?new URL(value,origin).href:value;
      return {...views,originalUrl:absolute(views.originalUrl)!,downloadUrl:absolute(views.downloadUrl)!,viewerUrl:absolute(views.viewerUrl)!,previewUrl:absolute(views.previewUrl)??null,thumbnailUrl:absolute(views.thumbnailUrl)??null,streamUrl:absolute(views.streamUrl)};
    }
  }),[assetId,ideaId,origin]);
}
export function RichField({value,onChange,label,documentKey,assetId,ideaId,editable=true,compact=false}:{value:RichValue;onChange:(value:RichValue)=>void;label:string;documentKey:string;assetId?:string;ideaId?:string;editable?:boolean;compact?:boolean}){
  const attachments=useAttachments(assetId,ideaId),[error,setError]=useState('');
  const parsed=useMemo(()=>{try{return {value:document(value),error:''};}catch(e){return {value:null,error:(e as Error).message};}},[value]);
  if(parsed.error)return <p role="alert" className="error">{parsed.error}</p>;
  return <div className={'rich-field '+(compact?'compact-rich':'')}><ShneaEditor value={parsed.value} documentKey={documentKey} label={label} attachments={attachments} appearance={appearance} editable={editable} onChange={event=>{setError('');onChange(event.document);}} onError={event=>setError(event instanceof Error?event.message:'본문을 확인해 주세요.')}/>{error&&<p role="alert" className="error">{error}</p>}</div>;
}
export function RichView({value,assetId,ideaId}:{value:RichValue;assetId?:string;ideaId?:string}){
  const attachments=useAttachments(assetId,ideaId),[error,setError]=useState('');
  const parsed=useMemo(()=>{try{return document(value);}catch{return null;}},[value]);
  return <div className="asset-rich-view">{parsed?<ShneaViewer value={parsed} attachments={attachments} appearance={appearance} onError={e=>setError(e instanceof Error?e.message:'본문을 표시하지 못했습니다.')}/>:<p className="error" role="alert">본문 형식을 확인해 주세요.</p>}{error&&<p className="error" role="alert">{error}</p>}</div>;
}
