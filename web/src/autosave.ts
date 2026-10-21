import {useCallback,useEffect,useRef,useState} from 'react';

// Serialize saves and keep edits made while a previous request is in flight.
export function useAutosave<T>(initial:T,save:(value:T)=>Promise<void>){
  const [value,setValue]=useState(initial);
  const [status,setStatus]=useState<'saved'|'pending'|'saving'|'error'>('saved');
  const [error,setError]=useState('');
  const latest=useRef(initial),generation=useRef(0),saved=useRef(0),busy=useRef<Promise<boolean>|null>(null),saveRef=useRef(save);
  saveRef.current=save;
  const change=useCallback((next:T)=>{latest.current=next;generation.current++;setValue(next);setStatus('pending');},[]);
  const flush=useCallback(async():Promise<boolean>=>{
    if(busy.current) return busy.current;
    const run=async()=>{
      try {
        while(saved.current<generation.current){
          setStatus('saving');const serial=generation.current,snapshot=latest.current;
          await saveRef.current(snapshot);saved.current=serial;
        }
        setStatus('saved');setError('');return true;
      }catch(e){setStatus('error');setError(e instanceof Error?e.message:'저장하지 못했습니다.');return false;}
    };
    busy.current=run();const result=await busy.current;busy.current=null;return result;
  },[]);
  useEffect(()=>{if(status!=='pending')return;const timer=setTimeout(()=>void flush(),800);return()=>clearTimeout(timer);},[value,status,flush]);
  useEffect(()=>{const guard=(event:BeforeUnloadEvent)=>{if(generation.current>saved.current){event.preventDefault();event.returnValue='';}};window.addEventListener('beforeunload',guard);return()=>window.removeEventListener('beforeunload',guard);},[]);
  return {value,change,flush,status,error,latest};
}
