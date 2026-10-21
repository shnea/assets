import {useEffect,useState} from 'react';
import {api,bytes,date,type Usage} from './api';
import {Heading,Loading,Notice,useData} from './main';

export {ConnectionsPage} from './ConnectionsPage';

type Stats={days:number;registrations:number;newMembers:number;activeMembers:number;ASSET_CREATE:number;ASSET_WRITE:number;ASSET_VIEW:number;MCP_SUCCESS:number;MCP_FAILURE:number;storage:{files:number;bytes:number};registrationsByDay:{day:string;count:number}[];defaultQuota:number};
type Quota={id:string;display_name:string;base_quota:number;quota_override:number|null;used_bytes:number;reserved_bytes:number};
export function OperationsPage({allowed}:{allowed:boolean}){
  const [days,setDays]=useState(30),[offset,setOffset]=useState(0),[error,setError]=useState(''),[message,setMessage]=useState(''),[defaultGB,setDefaultGB]=useState(10),[applyExisting,setApplyExisting]=useState(false);
  const result=useData<Stats>(()=>allowed?api('/api/admin/stats?days='+days):Promise.reject(new Error('운영 권한이 필요합니다.')),[days,allowed]);
  const quotas=useData<Quota[]>(()=>allowed?api('/api/admin/quotas?offset='+offset):Promise.resolve([]),[allowed,offset]);
  const own=useData<Usage>(()=>api('/api/storage'),[]);
  useEffect(()=>{if(result.data)setDefaultGB(result.data.defaultQuota/1e9);},[result.data?.defaultQuota]);
  if(!allowed)return <Notice error>운영 권한이 필요합니다.</Notice>;
  async function defaults(){try{setError('');await api('/api/admin/quota-default',{method:'PUT',body:JSON.stringify({bytes:Math.round(defaultGB*1e9),applyExisting})});setMessage('기본 용량을 저장했어요.');result.reload();quotas.reload();}catch(e){setError((e as Error).message);}}
  const stats=result.data;
  const cards=stats?[['전체 가입자',stats.registrations,`최근 ${days}일 +${stats.newMembers}명`],['활성 회원',stats.activeMembers,`최근 ${days}일 이용 회원`],['자산 작성',stats.ASSET_CREATE,`수정·버전 저장 ${stats.ASSET_WRITE}회`],['자산 조회',stats.ASSET_VIEW,`최근 ${days}일 조회 건수`]]:[];
  const peak=Math.max(1,...(stats?.registrationsByDay.map(d=>Number(d.count))||[]));
  return <div><Heading title="운영 현황" description="" actions={<label className="range-label"><span className="sr-only">통계 기간</span><select value={days} onChange={e=>setDays(Number(e.target.value))}><option value={7}>최근 7일</option><option value={30}>최근 30일</option><option value={90}>최근 90일</option></select></label>}/>
    <Notice>운영자는 통계와 용량만 관리해요. 다른 회원의 자산·아이디어·파일 내용은 열 수 없어요.</Notice>
    {(error||result.error||quotas.error)&&<Notice error>{error||result.error||quotas.error}</Notice>}{message&&<Notice>{message}</Notice>}
    {result.loading?<Loading/>:<><div className="stats-grid">{cards.map(([label,value,caption])=><section className="stat-card paper" key={label}><span>{label}</span><strong>{Number(value).toLocaleString('ko-KR')}</strong><small>{caption}</small></section>)}</div><div className="connection-layout"><section className="paper"><h2>회원 가입 추이</h2><div className="bar-chart" role="img" aria-label={`최근 ${days}일 일별 신규 가입자 수`}>{stats?.registrationsByDay.length?stats.registrationsByDay.map(d=><div key={d.day} className="bar-column"><span>{d.count}</span><div className="bar" style={{height:Math.max(4,Number(d.count)/peak*120)}}/><small>{String(d.day).slice(5,10)}</small></div>):<p className="muted">이 기간에는 신규 가입이 없어요.</p>}</div></section><section className="paper"><h2>호출과 저장 공간</h2><dl className="metric-list"><div><dt>MCP 성공</dt><dd>{stats?.MCP_SUCCESS.toLocaleString()}회</dd></div><div><dt>MCP 실패</dt><dd>{stats?.MCP_FAILURE.toLocaleString()}회</dd></div><div><dt>전체 파일 사용량</dt><dd>{bytes(Number(stats?.storage.bytes||0))}</dd></div><div><dt>보관 파일 수</dt><dd>{stats?.storage.files}개</dd></div></dl></section></div></>}
    <section className="paper quota-panel"><h2>저장 공간 관리</h2><div className="quota-default"><label>기본 용량 <span className="inline-input"><input type="number" min={0} max={1000000} value={defaultGB} onChange={e=>setDefaultGB(Number(e.target.value))}/> GB</span></label><button onClick={()=>void defaults()}>기본값 저장</button><label className="checkbox"><input type="checkbox" checked={applyExisting} onChange={e=>setApplyExisting(e.target.checked)}/>기존 회원의 기본 한도에도 적용 · 회원별 예외는 유지</label></div><p className="muted small">기본값은 신규 회원부터 적용돼요. 현재 사용량보다 한도를 낮춰도 기존 파일은 삭제되지 않고 추가 업로드만 제한됩니다.</p>
      <div className="table-scroll"><table><thead><tr><th>회원</th><th>사용량 · 예약량</th><th>적용 한도</th><th>회원별 설정</th></tr></thead><tbody>{quotas.data?.map(q=><QuotaRow key={q.id} quota={q} onSaved={()=>{quotas.reload();own.reload();}} onError={setError}/>)}</tbody></table></div><div className="pagination"><button disabled={!offset} onClick={()=>setOffset(Math.max(0,offset-50))}>이전</button><button disabled={(quotas.data?.length||0)<50} onClick={()=>setOffset(offset+50)}>다음</button></div>
    </section>{own.data&&<p className="muted small">내 보관함 · {bytes(own.data.usedBytes)} / {bytes(own.data.quotaBytes)}</p>}
  </div>;
}
function QuotaRow({quota:q,onSaved,onError}:{quota:Quota;onSaved:()=>void;onError:(value:string)=>void}){
  const [gb,setGB]=useState((q.quota_override??q.base_quota)/1e9),[busy,setBusy]=useState(false);
  async function save(clear=false){setBusy(true);try{await api('/api/admin/quotas/'+q.id,{method:'PUT',body:JSON.stringify({bytes:clear?null:Math.round(gb*1e9)})});onError('');onSaved();}catch(e){onError((e as Error).message);}finally{setBusy(false);}}
  return <tr><td><strong>{q.display_name}</strong><small className="member-id">{q.id}</small></td><td>{bytes(Number(q.used_bytes))}<small className="member-id">예약 {bytes(Number(q.reserved_bytes))}</small></td><td>{bytes(q.quota_override??q.base_quota)}<small className="member-id">{q.quota_override==null?'기본값':'회원별 예외'}</small></td><td><div className="row-actions"><label className="quota-input"><span className="sr-only">{q.display_name}의 용량 · GB</span><input type="number" min={0} max={1000000} value={gb} onChange={e=>setGB(Number(e.target.value))}/> GB</label><button disabled={busy} onClick={()=>void save()}>적용</button><button disabled={busy||q.quota_override==null} onClick={()=>void save(true)}>기본값 사용</button></div></td></tr>;
}
