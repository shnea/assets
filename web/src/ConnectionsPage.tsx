import {useState} from 'react';
import {api,date} from './api';
import {Heading,Loading,Notice,useData} from './main';

type Connection={id:string;name:string;created_at:string;expires_at:string;last_used_at:string|null;revoked_at:string|null};

export function ConnectionsPage(){
  const result=useData<Connection[]>(()=>api('/api/connections'),[]);
  const [name,setName]=useState('내 에이전트'),[days,setDays]=useState(30),[token,setToken]=useState('');
  const [error,setError]=useState(''),[message,setMessage]=useState(''),[busy,setBusy]=useState(false);
  const url=location.origin+'/mcp';

  async function create(){
    setBusy(true);setError('');setToken('');
    try{const data=await api<{token:string}>('/api/connections',{method:'POST',body:JSON.stringify({name,days})});setToken(data.token);result.reload();}
    catch(e){setError((e as Error).message);}finally{setBusy(false);}
  }
  async function revoke(id:string){
    try{await api('/api/connections/'+id,{method:'DELETE'});setToken('');result.reload();}catch(e){setError((e as Error).message);}
  }
  async function copy(value:string){
    try{await navigator.clipboard.writeText(value);setMessage('복사했어요.');}
    catch{setMessage('자동 복사하지 못했습니다. 내용을 선택해 복사해 주세요.');}
  }

  return <div><Heading title="에이전트 연결" description=""/>
    <section className="agent-intro paper"><span className="agent-icon" aria-hidden="true">⇄</span><div>
      <h2>MCP로 내 자산 연결</h2><p>MCP 주소와 연결 키로 사용하는 에이전트에 등록하세요.</p>
      <small className="green">읽기 전용 · 연결된 계정의 저장된 자산만 접근</small>
    </div></section>
    {(error||result.error)&&<Notice error>{error||result.error}</Notice>}{message&&<Notice>{message}</Notice>}
    <div className="connection-layout"><section className="paper"><h2>연결 정보</h2>
      <label>MCP 주소<input readOnly value={url}/></label><button className="text-button" onClick={()=>void copy(url)}>주소 복사</button>
      <h3>연결 키 만들기</h3><div className="field-grid">
        <label>연결 이름<input value={name} maxLength={100} placeholder="예: 노트북 · 작업용 에이전트" onChange={e=>setName(e.target.value)}/></label>
        <label>사용 기간<select value={days} onChange={e=>setDays(Number(e.target.value))}><option value={7}>7일</option><option value={30}>30일</option><option value={90}>90일</option></select></label>
      </div><button className="primary" disabled={busy||!name.trim()} onClick={()=>void create()}>{busy?'연결 준비 중…':'연결 키 만들기'}</button>
      {token&&<div className="token-result"><h3>연결 키는 지금 한 번만 표시됩니다.</h3>
        <p className="small">사용하는 에이전트의 MCP 인증 설정에 등록하세요.</p>
        <label>새 연결 키<input readOnly type="password" value={token}/></label><button onClick={()=>void copy(token)}>연결 키 복사</button>
      </div>}
      <details className="mcp-details"><summary>직접 설정하는 경우</summary><p className="muted small">연결 방식: Streamable HTTP · 인증 방식: Bearer</p>
        <pre>Authorization: Bearer &lt;연결 키&gt;</pre>
      </details>
      <p className="muted small">에이전트·기기별로 키를 구분할 수 있으며, 연결을 해제하면 이후 요청부터 접근이 중단됩니다.</p>
    </section><aside className="paper scope-panel"><h2>어떤 자료를 가져가나요?</h2>
      <ul><li>내 자산 검색과 저장된 내용 읽기</li><li>저장된 이전 버전 보기</li><li>자산에 연결된 참고자료와 파일</li></ul>
      <hr/><strong>아이디어 수첩은 제공하지 않아요.</strong><p>제목·내용·첨부파일 모두 제외됩니다.</p><p className="muted">자산 작성·수정·삭제 권한은 없어요.</p>
    </aside></div>
    <section className="paper connections-table"><h2>연결 내역</h2>{result.loading?<Loading/>:result.data?.length?<div className="table-scroll"><table>
      <thead><tr><th>연결 이름</th><th>권한</th><th>최근 사용</th><th>상태</th><th>관리</th></tr></thead>
      <tbody>{result.data.map(c=><tr key={c.id}><td>{c.name}</td><td>자산 읽기</td><td>{c.last_used_at?date(c.last_used_at):'아직 사용 전'}</td>
        <td>{c.revoked_at?'해제됨':new Date(c.expires_at)<new Date()?'만료됨':date(c.expires_at)+'까지'}</td>
        <td>{!c.revoked_at&&<button onClick={()=>void revoke(c.id)}>해제</button>}</td></tr>)}</tbody>
    </table></div>:<p className="muted">아직 연결된 에이전트가 없어요.</p>}</section>
  </div>;
}
