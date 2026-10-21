import {plain,type RichValue} from './documents';
export type Tool = {kind:string;name:string;url:string;note:RichValue};
export type Term = {name:string;explanation:RichValue;example:RichValue};
export type Link = {label:string;url:string};
export type Content = {title:string;category:string;work:RichValue;remember:RichValue;start:RichValue;tags:string[];tools:Tool[];terms:Term[];references:Link[];fileIds:string[];purpose:RichValue;inputs:RichValue;completion:RichValue;troubleshooting:RichValue;status:string;verifiedOn:string;environment:string};
export type Asset = {id:string;draft:Content;revision:number;version:number;favorite:boolean;updatedAt:string};
export type Version = {number:number;content:Content;changeNote:string;createdAt:string};
export type Idea = {id:string;title:string;body:RichValue;revision:number;updatedAt:string};
export type FileInfo = {id:string;name:string;size:number;sha256:string;receivedBytes:number;state:string;expiresAt:string;platformId:string|null};
export type Usage = {usedBytes:number;reservedBytes:number;quotaBytes:number;fileCount:number};
export type Session = {authenticated:boolean;csrfToken:string;appUrl:string;platformImageOrigin?:string;member?:{id:string;displayName:string;admin:boolean}};
export const categories=['기획·설계','이미지 제작','영상 제작','3D·공간 제작','애니메이션·모션','인터랙션·체험','음성 제작','음악·사운드 제작','디자인·브랜딩','문서·프레젠테이션','데이터·시각화','자동화·AI 기능'];
export const emptyContent:Content={title:'',category:'',work:'',remember:'',start:'',tags:[],tools:[],terms:[],references:[],fileIds:[],purpose:'',inputs:'',completion:'',troubleshooting:'',status:'UNVERIFIED',verifiedOn:'',environment:''};
let csrfToken='';
export function setCsrf(value:string){csrfToken=value;}
export class ApiError extends Error {constructor(public status:number,public code:string,message:string){super(message);}}
export async function api<T>(path:string,options:RequestInit={}):Promise<T>{
  const headers=new Headers(options.headers);
  if(options.body && typeof options.body==='string') headers.set('Content-Type','application/json');
  if(options.method && !['GET','HEAD'].includes(options.method)) headers.set('X-CSRF-TOKEN',csrfToken);
  const response=await fetch(path,{...options,headers,credentials:'same-origin'});
  if(!response.ok){const error=await response.json().catch(()=>({}));throw new ApiError(response.status,error.code??'REQUEST_FAILED',error.message??'요청을 완료하지 못했습니다. 다시 시도해 주세요.');}
  if(response.status===204 || response.headers.get('Content-Length')==='0') return undefined as T;
  const text=await response.text();return text?JSON.parse(text) as T:undefined as T;
}
export function title(c:Content){return c.title||[c.work,c.remember,c.start].map(plain).find(Boolean)?.split('\n')[0].slice(0,40)||'이름 없는 제작법';}
export function date(value:string){return new Intl.DateTimeFormat('ko-KR',{month:'long',day:'numeric',hour:'2-digit',minute:'2-digit'}).format(new Date(value));}
export function bytes(value:number){return value>=1e9?`${(value/1e9).toFixed(1)} GB`:value>=1e6?`${(value/1e6).toFixed(1)} MB`:value>=1000?`${(value/1000).toFixed(1)} KB`:`${value} B`;}
