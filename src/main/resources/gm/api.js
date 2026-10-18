export class ApiError extends Error { constructor(message,status=0) {super(message);this.status=status;} }
let csrf='';
export async function api(route,body) {
  const controller=new AbortController();
  const timer=setTimeout(()=>controller.abort(),25000);
  let response;
  try {
    response=await fetch('/gm/api/'+route,{method:body===undefined?'GET':'POST',credentials:'same-origin',
      headers:body===undefined?{}:{'Content-Type':'application/json','X-GM-CSRF':csrf},body:body===undefined?undefined:JSON.stringify(body),signal:controller.signal});
  } catch {throw new ApiError('连接中断或请求超时，请检查服务状态。');}
  finally {clearTimeout(timer);}
  const envelope=await response.json().catch(()=>{throw new ApiError('服务返回了无法读取的响应。',response.status);});
  if(!response.ok || !envelope.ok) {
    if(response.status===401 && route!=='login') window.dispatchEvent(new Event('gm-session-expired'));
    throw new ApiError(envelope.error || '请求失败。',response.status);
  }
  return envelope.data;
}
export async function session() { const data=await api('session');csrf=data.csrf; }
export async function login(key) { const data=await api('login',{key});csrf=data.csrf; }
export async function logout() {await api('logout',{});csrf='';}
const pendingKey='hkrpg.gm.pending';
export function pendingOperation() { try {return JSON.parse(sessionStorage.getItem(pendingKey)||'null');} catch {return null;} }
export function acknowledgePending() { sessionStorage.removeItem(pendingKey); }
export async function operate(operation) {
  const pending=pendingOperation();
  if(pending) throw new ApiError('上次操作结果尚未确认，请先点击“核对上次操作”。');
  const requestId=crypto.randomUUID?crypto.randomUUID():'10000000-1000-4000-8000-100000000000'.replace(/[018]/g,c=>(Number(c)^crypto.getRandomValues(new Uint8Array(1))[0]&15>>Number(c)/4).toString(16));
  const body={...operation,requestId};
  sessionStorage.setItem(pendingKey,JSON.stringify(body));
  return sendOperation(body);
}
export async function recoverOperation() {
  const pending=pendingOperation();
  if(!pending) return null;
  return sendOperation(pending);
}
async function sendOperation(body) {
  try {const result=await api('operate',body);sessionStorage.removeItem(pendingKey);return result;}
  catch(error) {
    if(error.status>=400 && error.status<500 && error.status!==409) sessionStorage.removeItem(pendingKey);
    throw error;
  }
}
