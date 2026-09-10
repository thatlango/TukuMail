import {FormEvent,useEffect,useMemo,useState} from 'react';
import {mailApi,MessageDetail,MessageSummary} from './api';

type Session={token:string;email:string};
type Draft={to:string;cc:string;subject:string;body:string};
const emptyDraft:Draft={to:'',cc:'',subject:'',body:''};

export default function App(){
 const [session,setSession]=useState<Session|null>(()=>{try{return JSON.parse(sessionStorage.getItem('tukumail-session')||'null')}catch{return null}});
 const [messages,setMessages]=useState<MessageSummary[]>([]); const [selected,setSelected]=useState<MessageDetail|null>(null); const [loading,setLoading]=useState(false); const [error,setError]=useState(''); const [query,setQuery]=useState(''); const [compose,setCompose]=useState(false);
 const [draft,setDraft]=useState<Draft>(()=>{try{return JSON.parse(localStorage.getItem('tukumail-draft')||'null')||emptyDraft}catch{return emptyDraft}});
 useEffect(()=>localStorage.setItem('tukumail-draft',JSON.stringify(draft)),[draft]);
 useEffect(()=>{if(session)refresh()},[session?.token]);
 async function refresh(){if(!session)return;setLoading(true);setError('');try{setMessages(await mailApi.inbox(session.token))}catch(e){setError((e as Error).message)}finally{setLoading(false)}}
 async function open(m:MessageSummary){if(!session)return;setLoading(true);try{setSelected(await mailApi.message(session.token,m.id))}catch(e){setError((e as Error).message)}finally{setLoading(false)}}
 const visible=useMemo(()=>{const q=query.toLowerCase().trim();return q?messages.filter(m=>(m.from+' '+m.subject+' '+m.preview).toLowerCase().includes(q)):messages},[messages,query]);
 if(!session)return <Login onLogin={s=>{sessionStorage.setItem('tukumail-session',JSON.stringify(s));setSession(s)}}/>;
 async function signOut(){try{await mailApi.logout(session.token)}catch{}sessionStorage.removeItem('tukumail-session');setSession(null)}
 return <div className="app-shell">
   <aside className="rail">
    <div className="brand-mark" aria-label="TukuMail">T</div>
    <button className="compose-rail" onClick={()=>setCompose(true)}>＋ <span>Compose</span></button>
    <nav><Nav active icon="▣" label="Inbox" count={messages.filter(m=>!m.read).length}/><Nav icon="☆" label="Starred"/><Nav icon="⌁" label="Drafts"/><Nav icon="↗" label="Sent"/><Nav icon="!" label="Spam"/></nav>
    <div className="rail-bottom"><button className="text-button" onClick={signOut}>Sign out</button><div className="account-chip"><span>{initials(session.email)}</span><small>{session.email}</small></div></div>
   </aside>
   <main className="workspace">
    <header className="topbar"><div><p className="eyebrow">TukuMail</p><h1>Inbox</h1></div><div className="top-actions"><button className="icon-button" onClick={refresh} title="Refresh">↻</button><button className="avatar">{initials(session.email)}</button></div></header>
    <div className="search"><span>⌕</span><input value={query} onChange={e=>setQuery(e.target.value)} placeholder="Search mail"/><kbd>/</kbd></div>
    {error&&<div className="notice error">{error}<button onClick={()=>setError('')}>×</button></div>}
    <section className="mail-layout">
      <div className={'message-list '+(selected?'has-selection':'')}>
       <div className="list-meta"><span>{loading?'Syncing…':`${visible.length} messages`}</span><span className="sync-dot">● Up to date</span></div>
       {visible.length===0&&!loading?<Empty/>:visible.map(m=><button key={m.id} className={'message-row '+(!m.read?'unread ':'')+(selected?.id===m.id?'selected':'')} onClick={()=>open(m)}><span className="sender-avatar">{initials(m.from)}</span><span className="message-copy"><span className="row-top"><strong>{cleanSender(m.from)}</strong><time>{when(m.receivedAt)}</time></span><span className="subject">{m.subject}</span><span className="preview">{m.preview}</span></span></button>)}
      </div>
      <div className={'reader '+(!selected?'reader-empty':'')}>{selected?<Reader message={selected} close={()=>setSelected(null)} reply={()=>{setDraft({to:cleanAddress(selected.from),cc:'',subject:selected.subject.startsWith('Re:')?selected.subject:`Re: ${selected.subject}`,body:'\n\n'});setCompose(true)}}/>:<div><div className="reader-symbol">✉</div><h2>Select a message</h2><p>Choose an email to read it here.</p></div>}</div>
    </section>
   </main>
   <button className="mobile-compose" onClick={()=>setCompose(true)}>＋</button>
   <nav className="bottom-nav"><Nav active icon="▣" label="Inbox"/><Nav icon="⌕" label="Search"/><button onClick={()=>setCompose(true)}><span>＋</span><small>Compose</small></button><Nav icon="↗" label="Sent"/><Nav icon="☰" label="More"/></nav>
   {compose&&<Composer draft={draft} setDraft={setDraft} close={()=>setCompose(false)} send={async()=>{try{await mailApi.send(session.token,{to:split(draft.to),cc:split(draft.cc),subject:draft.subject,body:draft.body});setDraft(emptyDraft);setCompose(false);setError('');await refresh()}catch(e){setError((e as Error).message)}}}/>} 
  </div>
}

function Login({onLogin}:{onLogin:(s:Session)=>void}){const[email,setEmail]=useState('');const[password,setPassword]=useState('');const[busy,setBusy]=useState(false);const[error,setError]=useState('');async function submit(e:FormEvent){e.preventDefault();setBusy(true);setError('');try{onLogin(await mailApi.login(email,password))}catch(err){setError((err as Error).message)}finally{setBusy(false)}}return <main className="login-page"><section className="login-card"><div className="login-mark">T</div><p className="eyebrow">TukuMail</p><h1>Your work email,<br/>without the clutter.</h1><p className="login-lead">Sign in with your organisation email address.</p><form onSubmit={submit}><label>Email address<input type="email" autoComplete="username" value={email} onChange={e=>setEmail(e.target.value)} placeholder="you@organisation.org" required/></label><label>Password<input type="password" autoComplete="current-password" value={password} onChange={e=>setPassword(e.target.value)} required/></label>{error&&<div className="notice error">{error}</div>}<button className="primary" disabled={busy}>{busy?'Signing in…':'Sign in'}</button></form><small>Protected by your organisation's TukuMail service.</small></section><section className="login-art"><div className="quote-card"><span>✦</span><p>One inbox for the work that matters.</p><small>Fast. Quiet. Familiar.</small></div></section></main>}
function Nav({icon,label,count,active=false}:{icon:string;label:string;count?:number;active?:boolean}){return <button className={active?'nav-active':''}><span>{icon}</span><small>{label}</small>{count? <b>{count}</b>:null}</button>}
function Reader({message,close,reply}:{message:MessageDetail;close:()=>void;reply:()=>void}){return <article><div className="reader-toolbar"><button onClick={close}>←</button><div><button>☆</button><button>⋮</button></div></div><p className="eyebrow">{when(message.receivedAt)}</p><h2>{message.subject}</h2><div className="from"><span className="sender-avatar">{initials(message.from)}</span><div><strong>{cleanSender(message.from)}</strong><small>to {message.to.join(', ')}</small></div></div><div className="message-body">{message.bodyText||'This message has no plain-text content.'}</div><button className="reply-button" onClick={reply}>↩ Reply</button></article>}
function Composer({draft,setDraft,close,send}:{draft:Draft;setDraft:(d:Draft)=>void;close:()=>void;send:()=>Promise<void>}){const[busy,setBusy]=useState(false);async function submit(e:FormEvent){e.preventDefault();setBusy(true);await send();setBusy(false)}return <div className="compose-backdrop" onMouseDown={close}><form className="composer" onSubmit={submit} onMouseDown={e=>e.stopPropagation()}><header><div><p className="eyebrow">New message</p><h2>Compose</h2></div><button type="button" onClick={close}>×</button></header><label>To<input value={draft.to} onChange={e=>setDraft({...draft,to:e.target.value})} placeholder="name@example.com" required/></label><label>Cc<input value={draft.cc} onChange={e=>setDraft({...draft,cc:e.target.value})}/></label><input className="subject-input" value={draft.subject} onChange={e=>setDraft({...draft,subject:e.target.value})} placeholder="Subject" required/><textarea value={draft.body} onChange={e=>setDraft({...draft,body:e.target.value})} placeholder="Write your message…" required/><footer><button className="primary" disabled={busy}>{busy?'Sending…':'Send'}</button><span>Draft saved automatically</span></footer></form></div>}
function Empty(){return <div className="empty"><span>✓</span><h2>You're all caught up</h2><p>New mail will appear here.</p></div>}
const split=(s:string)=>s.split(/[;,]/).map(x=>x.trim()).filter(Boolean);const initials=(s:string)=>cleanSender(s).split(/\s+|@/).filter(Boolean).slice(0,2).map(x=>x[0]?.toUpperCase()).join('')||'TM';const cleanSender=(s:string)=>s.replace(/<.*?>/,'').replace(/^"|"$/g,'').trim();const cleanAddress=(s:string)=>s.match(/<([^>]+)>/)?.[1]||s.trim();const when=(s:string)=>{const d=new Date(s),n=new Date();return d.toDateString()===n.toDateString()?d.toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'}):d.toLocaleDateString([],{month:'short',day:'numeric'})};
