import {createRoot} from 'react-dom/client';
import {useEffect,useRef,useState,type ReactNode} from 'react';
import {api,bytes,categories,date,emptyContent,setCsrf,title,type Asset,type Content,type Idea,type Session,type Usage,type Version} from './api';
import {useAutosave} from './autosave';
import {Attachments} from './FileDropzone';
import {ConnectionsPage,OperationsPage} from './settings';
import {AssetEditor} from './AssetEditor';
import {RichField,RichView,PlatformImageOrigin} from './RichField';
import {plain,type RichValue} from './documents';
import {ExportMenu,TransfersPage} from './TransfersPage';
import {CategoryOptions} from './categories';
import './styles.css';

type Guard={current:(()=>Promise<boolean>)|null};
export function useData<T>(load:()=>Promise<T>,deps:unknown[]){
  const [data,setData]=useState<T>(),[error,setError]=useState(''),[loading,setLoading]=useState(true),[serial,setSerial]=useState(0);
  useEffect(()=>{let active=true;setLoading(true);setError('');load().then(value=>{if(active)setData(value);}).catch(e=>{if(active)setError(e.message);}).finally(()=>{if(active)setLoading(false);});return()=>{active=false;};},[...deps,serial]);
  return {data,error,loading,reload:()=>setSerial(s=>s+1)};
}
export function Notice({children,error=false}:{children:ReactNode;error?:boolean}){return <div className={error?'notice error':'notice'} role={error?'alert':'status'}>{children}</div>;}
export function Loading(){return <p className="loading" role="status">불러오는 중…</p>;}
export function Heading({title:heading,description,actions}:{title:string;description:string;actions?:ReactNode}){return <header className="page-heading"><div><h1>{heading}</h1>{description&&<p>{description}</p>}</div>{actions&&<div className="heading-actions">{actions}</div>}</header>;}
function Logo(){return <img className="wordmark" src="/assets-wordmark.png" width="176" height="59" alt="assets · shnea SH 워드마크"/>;}
const go=(path:string)=>{window.location.hash=path;};

function App(){
  const session=useData<Session>(()=>api('/api/session'),[]),[path,setPath]=useState(location.hash.slice(1)||'/assets');
  const ownCategories=useData<string[]>(()=>session.data?.authenticated?api('/api/assets/categories'):Promise.resolve([]),[session.data?.authenticated]);
  useEffect(()=>{window.addEventListener('assets:categories-changed',ownCategories.reload);return()=>window.removeEventListener('assets:categories-changed',ownCategories.reload);},[]);
  const guard=useRef<(()=>Promise<boolean>)|null>(null),route=useRef(path);
  const [navigationError,setNavigationError]=useState('');
  useEffect(()=>{const changed=async()=>{
    const next=location.hash.slice(1)||'/assets';if(next===route.current)return;
    if(guard.current&&!await guard.current()){history.replaceState(null,'','#'+route.current);setNavigationError('진행 중인 작업이나 저장 오류를 확인한 뒤 다시 이동해 주세요.');return;}
    route.current=next;setPath(next);setNavigationError('');window.scrollTo({top:0});
  };window.addEventListener('hashchange',changed);return()=>window.removeEventListener('hashchange',changed);},[]);
  useEffect(()=>{if(session.data)setCsrf(session.data.csrfToken);},[session.data]);
  if(session.loading)return <Loading/>;
  if(session.error)return <main className="welcome"><Notice error>{session.error}</Notice><button onClick={session.reload}>다시 연결</button></main>;
  if(!session.data?.authenticated)return <main className="welcome"><Logo/><p className="eyebrow">MY CREATIVE LIBRARY</p><h1>한 번 해본 제작법,<br/>다음에도 꺼내 쓸 수 있도록.</h1><p>낯선 용어부터 도구를 연결했던 순서까지.<br/>나의 제작 경험을 가볍게 정리하고 오래 활용하세요.</p>
    {new URLSearchParams(location.search).get('login')==='failed'&&<Notice error>로그인을 완료하지 못했습니다. 다시 시도해 주세요.</Notice>}
    <a className="button primary" href={(session.data?.appUrl||location.origin)+"/oauth2/authorization/platform"}>로그인하고 내 보관함 열기 <span>↗</span></a><small>SHNEA 계정으로 시작해요. 내 자료는 나만 볼 수 있어요.</small>
    <div className="welcome-prompts"><span>01&nbsp; 어떤 작업이었나요?</span><span>02&nbsp; 다음에 기억할 것은?</span><span>03&nbsp; 다시 시작할 때 무엇부터?</span></div></main>;
  const member=session.data.member!;
  const categoryOptions=[...new Set([...categories,...(ownCategories.data||[])])];
  const url=new URL(path,'https://assets.local'),parts=url.pathname.split('/').filter(Boolean),section=parts[0]||'assets';
  const links=[['assets','내 자산','▦'],['ideas','아이디어 수첩','✎'],['connections','에이전트 연결','⇄']];
  async function logout(){if(guard.current&&!await guard.current())return;
    const form=document.createElement('form');form.method='POST';form.action='/api/logout';const input=document.createElement('input');input.name='_csrf';input.value=session.data!.csrfToken;form.append(input);document.body.append(form);form.submit();}
  return <PlatformImageOrigin.Provider value={session.data.platformImageOrigin||'https://platform.shnea.kr'}><CategoryOptions.Provider value={categoryOptions}><div className="app-shell"><aside className="sidebar"><a href="#/assets" className="brand"><Logo/></a>
    <nav aria-label="주 메뉴">{links.map(([key,label,icon])=><a key={key} href={'#/'+key} aria-current={section===key?'page':undefined}><span aria-hidden="true">{icon}</span>{label}</a>)}</nav>
    <div className="category-nav"><p>분야별로 찾아보기</p>{categoryOptions.map(category=><a href={'#/assets?category='+encodeURIComponent(category)} key={category} className={url.searchParams.get('category')===category?'active':''}>{category}</a>)}</div>
    {member.admin&&<nav className="admin-link"><a href="#/admin" aria-current={section==='admin'?'page':undefined}>운영 관리</a></nav>}
    <div className="sidebar-footer"><StorageSummary/><div className="account"><span className="avatar">{member.displayName.slice(0,1)}</span><div><strong>{member.displayName}</strong><small>개인 보관함</small></div><button className="text-button" onClick={()=>void logout()}>로그아웃</button></div></div>
  </aside><div className="content-shell"><header className="topbar"><a className="mobile-brand" href="#/assets"><Logo/></a><span className="breadcrumb">내 작업 공간 / {section==='admin'?'운영 관리':links.find(l=>l[0]===section)?.[1]||'내 자산'}</span><span className="chip private">나만 보기</span></header>
    <main id="main">{navigationError&&<Notice error>{navigationError}</Notice>}
      {section==='edit'&&parts[1]?<EditorLoader key={parts[1]} id={parts[1]} guard={guard}/>:
       section==='ideas'?<IdeasPage selected={parts[1]} guard={guard}/>:
       section==='connections'?<ConnectionsPage/>:
       section==='admin'?<OperationsPage allowed={member.admin}/>:
       section==='backup'?<TransfersPage guard={guard}/>:
       <LibraryPage category={url.searchParams.get('category')||''} initialQuery={url.searchParams.get('q')??undefined} categoryOptions={categoryOptions} selected={section==='assets'?parts[1]:undefined}/>}
    </main></div><nav className="bottom-nav" aria-label="모바일 메뉴">{links.map(([key,label,icon])=><a key={key} href={'#/'+key} aria-current={section===key?'page':undefined}><span aria-hidden="true">{icon}</span>{label}</a>)}{member.admin&&<a href="#/admin">운영 관리</a>}</nav></div></CategoryOptions.Provider></PlatformImageOrigin.Provider>;
}

function StorageSummary(){
  const {data,reload}=useData<Usage>(()=>api('/api/storage'),[]);
  useEffect(()=>{window.addEventListener('assets:storage-changed',reload);return()=>window.removeEventListener('assets:storage-changed',reload);},[]);
  if(!data)return null;
  return <div className="storage-summary"><small>내 저장 공간</small><div><strong>{bytes(data.usedBytes)}</strong><span> / {bytes(data.quotaBytes)}</span></div><meter min={0} max={Math.max(1,data.quotaBytes)} value={Math.min(data.usedBytes+data.reservedBytes,data.quotaBytes)}/>{data.reservedBytes>0&&<small>{bytes(data.reservedBytes)} 업로드 중</small>}</div>;
}

function LibraryPage({category,selected,initialQuery,categoryOptions}:{category:string;selected?:string;initialQuery?:string;categoryOptions:string[]}){
  const [q,setQ]=useState(''),[query,setQuery]=useState(''),[filter,setFilter]=useState('all'),[offset,setOffset]=useState(0),[error,setError]=useState('');
  useEffect(()=>{if(initialQuery!==undefined){setQ(initialQuery);setOffset(0);}},[initialQuery]);
  useEffect(()=>{const timer=setTimeout(()=>{setQuery(q);setOffset(0);},250);return()=>clearTimeout(timer);},[q]);
  useEffect(()=>{setOffset(0);},[category,filter]);
  const result=useData<{items:Asset[];total:number;hasMore:boolean}>(()=>api(`/api/assets?q=${encodeURIComponent(query)}&category=${encodeURIComponent(category)}&filter=${filter}&offset=${offset}`),[query,category,filter,offset]);
  async function create(){try{setError('');const asset=await api<Asset>('/api/assets',{method:'POST',body:JSON.stringify(emptyContent)});go('/edit/'+asset.id);}catch(e){setError((e as Error).message);}}
  return <div className={'library '+(selected?'has-selection':'')}><Heading title={category||'내 자산'} description="" actions={<div className="row-actions"><a className="button" href="#/backup">백업·가져오기</a><button className="primary" onClick={()=>void create()}>+ 자산 만들기</button></div>}/>
    {error&&<Notice error>{error}</Notice>}<div className="search-row"><input type="search" value={q} onChange={e=>setQ(e.target.value)} aria-label="내 자산 검색" placeholder="작업, 용어, 도구 이름으로 내 자산 검색"/>
      <select aria-label="분야 선택" value={category} onChange={e=>go('/assets'+(e.target.value?'?category='+encodeURIComponent(e.target.value):''))}><option value="">모든 분야</option>{categoryOptions.map(c=><option key={c}>{c}</option>)}</select></div>
    <div className="filter-tabs" aria-label="자산 모아보기">{[['all','전체'],['favorites','즐겨찾기'],['drafts','작성 중'],['unclassified','미분류']].map(([key,label])=><button key={key} aria-pressed={filter===key} onClick={()=>setFilter(key)}>{label}</button>)}<span className="muted small">{result.data?.total??0}개의 제작법</span></div>
    {result.error&&<Notice error>{result.error}<button onClick={result.reload}>다시 시도</button></Notice>}
    <div className="browse-layout"><div className="browse-list">{result.loading?<Loading/>:result.data?.items.length?<><div className="asset-grid">{result.data.items.map(asset=><article className={'asset-card '+(selected===asset.id?'selected':'')} key={asset.id}>
      <div className="card-top"><span className={'chip tone-'+(categories.indexOf(asset.draft.category)%3)}>{asset.draft.category||'미분류'}</span><button className="favorite" aria-label={asset.favorite?'즐겨찾기 해제':'즐겨찾기 추가'} aria-pressed={asset.favorite} onClick={async()=>{try{await api(`/api/assets/${asset.id}/favorite`,{method:'PUT',body:JSON.stringify({favorite:!asset.favorite})});result.reload();}catch(e){setError((e as Error).message);}}}>{asset.favorite?'★':'☆'}</button></div>
      <a className="card-main" href={'#/assets/'+asset.id}><h2>{title(asset.draft)}</h2><p>{[asset.draft.work,asset.draft.remember,asset.draft.start].map(plain).find(Boolean)||'작성 중'}</p><small>{asset.draft.tools.map(t=>t.name).filter(Boolean).join(' · ')||'기억하고 싶은 제작 경험'}</small></a><footer>{asset.version?`버전 ${asset.version}`:'작성 중'}<span>· {date(asset.updatedAt)}</span></footer>
    </article>)}</div><div className="pagination"><button disabled={offset===0} onClick={()=>setOffset(Math.max(0,offset-24))}>이전</button><button disabled={!result.data.hasMore} onClick={()=>setOffset(offset+24)}>다음</button></div></>:
    <div className="empty-state"><span aria-hidden="true">↗</span><h2>{query||category?'아직 해당하는 제작법이 없어요.':'저장한 자산이 없습니다.'}</h2><p>{query||category?'다른 단어나 분야로 찾아보세요.':''}</p><button onClick={()=>void create()}>첫 자산 만들기</button></div>}</div>
      {selected&&<AssetReader key={selected} id={selected}/>}</div>
  </div>;
}

function AssetReader({id}:{id:string}){
  const result=useData(async()=>{const [asset,versions]=await Promise.all([api<Asset>('/api/assets/'+id),api<Version[]>(`/api/assets/${id}/versions`)]);return {asset,versions};},[id]);
  const [number,setNumber]=useState<number>();
  if(result.loading)return <Loading/>;if(result.error)return <Notice error>{result.error}</Notice>;if(!result.data)return null;
  const {asset,versions}=result.data,version=versions.find(v=>v.number===(number??asset.version)),content=version?.content??asset.draft;
  return <section className="reader"><div className="reader-toolbar"><a href="#/assets">← 내 자산</a><div className="row-actions"><ExportMenu assetId={id} number={version?.number}/><a className="button" href={'#/edit/'+id}>수정하기</a></div></div><div className="reader-title"><span className="chip">{content.category||'미분류'}</span><h2>{title(content)}</h2><p className="muted small">{version?`버전 ${version.number} · ${date(version.createdAt)}`:'작성 중인 초안'}</p></div>
    <div className="reader-columns"><article className="paper"><ReadSection label="어떤 작업이었나요?" value={content.work} assetId={id}/><ReadSection label="다음에 기억할 것은?" value={content.remember} assetId={id}/><ReadSection label="다시 시작할 때 무엇부터?" value={content.start} assetId={id}/>
      {content.terms.length>0&&<section><h3>기억할 용어</h3>{content.terms.map((term,i)=><details key={i} className="term"><summary>{term.name}</summary><p className="small"><a href={"#/assets?q="+encodeURIComponent(term.name)}>같은 용어가 있는 제작법 찾기</a></p><RichView value={term.explanation} assetId={id}/>{plain(term.example)&&<RichView value={term.example} assetId={id}/>}</details>)}</section>}
      {content.tools.length>0&&<section><h3>사용한 도구와 연결</h3>{content.tools.map((tool,i)=><div className="reference" key={i}><span className="chip">{tool.kind||'도구'}</span>{tool.url?<a href={tool.url} target="_blank" rel="noreferrer">{tool.name} ↗</a>:<strong>{tool.name}</strong>}{plain(tool.note)&&<RichView value={tool.note} assetId={id}/>}</div>)}</section>}
      {([['목적과 사용 상황',content.purpose],['준비물과 입력 자료',content.inputs],['완료 기준',content.completion],['실패와 해결 방법',content.troubleshooting],['사용 환경',content.environment]] as const).map(([label,value])=><ReadSection key={label} label={label} value={value} assetId={id}/>)}
      {(content.references.length>0||content.fileIds.length>0)&&<section><h3>함께 보관한 자료</h3>{content.references.map((link,i)=><p key={i}><a href={link.url} target="_blank" rel="noreferrer">↗ {link.label||link.url}</a></p>)}<SavedFiles assetId={id} ids={content.fileIds}/></section>}
      {!!content.tags.length&&<div className="tags">{content.tags.map(tag=><span key={tag}>#{tag}</span>)}</div>}
    </article><aside className="version-panel"><h3>버전 기록 <span className="chip">{versions.length}개</span></h3>{versions.length?versions.map(v=><button key={v.number} className={'version-item '+(version?.number===v.number?'selected':'')} onClick={()=>setNumber(v.number)}><strong>버전 {v.number}{v.number===asset.version?' · 현재':''}</strong><span>{v.changeNote||'제작법 저장'}</span><small>{date(v.createdAt)}</small></button>):<p className="muted">아직 저장한 버전이 없어요.</p>}
      <div className="hint"><h3>도구가 바뀌었나요?</h3><p>이전 기록을 남겨두고 새 버전으로 제작법을 다듬어보세요.</p><small>이전 버전은 읽기 전용으로 열려요.</small></div></aside></div></section>;
}
function ReadSection({label,value,assetId}:{label:string;value:RichValue;assetId?:string}){return plain(value)?<section className="read-section"><h3>{label}</h3><RichView value={value} assetId={assetId}/></section>:null;}
function SavedFiles({assetId,ids}:{assetId:string;ids:string[]}){
  const {data,error}=useData<{id:string;name:string;size:number}[]>(()=>api('/api/files?assetId='+assetId),[assetId]);
  return <>{error&&<Notice error>{error}</Notice>}{data?.filter(f=>ids.includes(f.id)).map(f=><p key={f.id}><a href={`/api/files/${f.id}/download`}>{f.name}</a><small className="muted"> · {bytes(f.size)}</small></p>)}</>;
}

function EditorLoader({id,guard}:{id:string;guard:Guard}){
  const result=useData<Asset>(()=>api('/api/assets/'+id),[id]);
  if(result.loading)return <Loading/>;if(result.error)return <Notice error>{result.error}</Notice>;
  return result.data?<AssetEditor initial={result.data} guard={guard}/>:null;
}

function IdeasPage({selected,guard}:{selected?:string;guard:Guard}){
  const [q,setQ]=useState(''),[offset,setOffset]=useState(0),[error,setError]=useState('');
  const result=useData<{items:Idea[];total:number;hasMore:boolean}>(()=>api('/api/ideas?q='+encodeURIComponent(q)+'&offset='+offset),[q,offset]);
  async function create(){try{const idea=await api<Idea>('/api/ideas',{method:'POST',body:'{}'});setOffset(0);result.reload();go('/ideas/'+idea.id);}catch(e){setError((e as Error).message);}}
  return <div className={'ideas-page '+(selected?'has-selection':'')}><Heading title="아이디어 수첩" description="" actions={<button className="primary" onClick={()=>void create()}>+ 새 아이디어</button>}/><Notice>나만 보는 개인 수첩이에요. 제목·내용·파일은 에이전트에게 제공하지 않아요.</Notice>
    {(error||result.error)&&<Notice error>{error||result.error}</Notice>}<div className="ideas-layout"><section className="idea-list"><input type="search" value={q} onChange={e=>{setQ(e.target.value);setOffset(0);}} placeholder="수첩 안에서 찾기" aria-label="아이디어 검색"/>{result.loading?<Loading/>:result.data?.items.length?result.data.items.map(idea=><a className={'idea-item '+(idea.id===selected?'selected':'')} key={idea.id} href={'#/ideas/'+idea.id}><strong>{idea.title||plain(idea.body).split('\n')[0].slice(0,30)||'새로운 생각'}</strong><p>{plain(idea.body).slice(0,75)||'한 줄부터 적어보세요.'}</p><small>{date(idea.updatedAt)}</small></a>):<p className="muted">아직 적어둔 생각이 없어요.</p>}<div className="pagination"><button disabled={!offset} onClick={()=>setOffset(Math.max(0,offset-30))}>이전</button><button disabled={!result.data?.hasMore} onClick={()=>setOffset(offset+30)}>다음</button></div></section>{selected?<IdeaLoader key={selected} id={selected} guard={guard} onSaved={result.reload}/>:<div className="empty-state"><h2>문득 떠오른 생각을 남겨보세요.</h2><p>제목 없이 바로 적어도 괜찮아요.</p></div>}</div>
  </div>;
}
function IdeaLoader({id,guard,onSaved}:{id:string;guard:Guard;onSaved:()=>void}){
  const result=useData<Idea>(()=>api('/api/ideas/'+id),[id]);
  return result.loading?<Loading/>:result.error?<Notice error>{result.error}</Notice>:result.data?<IdeaEditor initial={result.data} guard={guard} onSaved={onSaved}/>:null;
}
function IdeaEditor({initial,guard,onSaved}:{initial:Idea;guard:Guard;onSaved:()=>void}){
  const revision=useRef(initial.revision),savedCallback=useRef(onSaved);savedCallback.current=onSaved;
  const note=useAutosave({title:initial.title,body:initial.body},async value=>{const saved=await api<Idea>('/api/ideas/'+initial.id,{method:'PUT',body:JSON.stringify({...value,revision:revision.current})});revision.current=saved.revision;savedCallback.current();});
  useEffect(()=>{guard.current=note.flush;return()=>{guard.current=null;};},[guard,note.flush]);
  return <section className="idea-editor"><a className="mobile-back" href="#/ideas">← 아이디어 목록</a><span className="save-state" role="status">{note.status==='saved'?'자동 저장됨':note.status==='error'?'저장 실패':'저장 중…'}</span>{note.error&&<Notice error>{note.error}<button onClick={()=>void note.flush()}>다시 저장</button></Notice>}
    <label className="sr-only" htmlFor="idea-title">아이디어 제목 · 선택</label><input id="idea-title" className="idea-title" value={note.value.title} maxLength={200} placeholder="떠오른 생각" onChange={e=>note.change({...note.latest.current,title:e.target.value})}/>
    <RichField label="아이디어 내용" documentKey={initial.id+':body'} value={note.value.body} ideaId={initial.id} onChange={value=>note.change({...note.latest.current,body:value})}/><details><summary>참고 파일 붙이기</summary><Attachments ideaId={initial.id}/></details>
  </section>;
}

createRoot(document.getElementById('root')!).render(<App/>);
