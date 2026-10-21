import {emptyDocument,fromMarkdown,parseDocument,type EditorDocument} from '@shnea/editor';
export type RichValue=string|EditorDocument;
export function attachmentsPending(value:unknown):boolean{
  if(!value||typeof value!=='object')return false;
  if(Array.isArray(value))return value.some(attachmentsPending);
  const node=value as Record<string,unknown>;
  if(node.type==='attachment'&&node.attrs&&typeof node.attrs==='object'&&!(node.attrs as Record<string,unknown>).fileId)return true;
  return Object.values(node).some(attachmentsPending);
}
export function document(value:RichValue):EditorDocument{return typeof value==='string'?(value?fromMarkdown(value):emptyDocument()):parseDocument(value);}
export function plain(value:RichValue):string{
  if(typeof value==='string')return value;
  const parts:string[]=[];
  const visit=(node:EditorDocument['content'])=>{
    if(node.text)parts.push(node.text);
    if(node.type==='attachment')parts.push(`[${node.attrs?.name||'첨부파일'}]`);
    node.content?.forEach(visit);
    if(['paragraph','heading','listItem','codeBlock','hardBreak','tableRow','attachment'].includes(node.type||''))parts.push('\n');
  };
  visit(value.content);return parts.join('').trim();
}
