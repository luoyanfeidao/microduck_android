'use strict';
// Execute the packaged browser script with fake DOM/network/timers; no third-party dependency.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
async function main(){
  const nodes=new Map(),timers=new Map(),events={},requests=[],created=new Set(),revoked=new Set();
  let timerId=0,urlId=0,now=0,mode='ok',heldResolve;
  function node(id){if(!nodes.has(id))nodes.set(id,{value:'',checked:false,disabled:false,textContent:'',src:'',removeAttribute(k){delete this[k]}});return nodes.get(id)}
  const buttons=['stand','forward','pause'].map(op=>({dataset:{op},disabled:false}));
  const response=(status,data,headers={})=>({status,ok:status===200,headers:{get:key=>headers[key]||null},
    json:async()=>data,blob:async()=>({jpeg:true})});
  const context=vm.createContext({
    document:{hidden:false,getElementById:node,querySelectorAll:()=>buttons,addEventListener:(event,fn)=>events[event]=fn},
    location:{hash:'',pathname:'/'},history:{replaceState(){}},URLSearchParams,AbortController,
    performance:{now:()=>now},Image:class{constructor(){this.naturalWidth=640;this.naturalHeight=480}async decode(){}},
    URL:{createObjectURL(){const url='blob:test-'+(++urlId);created.add(url);return url},revokeObjectURL:url=>revoked.add(url)},
    setTimeout(fn,ms){const id=++timerId;timers.set(id,{fn,ms});return id},clearTimeout:id=>timers.delete(id),setInterval(){},
    fetch:async(url,options)=>{
      requests.push({url,options});
      if(url==='/api/frame'){
        if(mode==='held')return new Promise((resolve,reject)=>{
          heldResolve=()=>resolve(response(200,null,{'Content-Type':'image/jpeg','X-Frame-Sequence':String(urlId+1),'X-Frame-Age-Ms':'12'}));
          options.signal.addEventListener('abort',()=>reject(Object.assign(Error('aborted'),{name:'AbortError'})));
        });
        if(mode==='unavailable')return response(503,{error:'Waiting'});
        if(mode==='unauthorized')return response(401,{error:'token'});
        return response(200,null,{'Content-Type':'image/jpeg','X-Frame-Sequence':String(urlId+1),'X-Frame-Age-Ms':'12'});
      }
      if(url==='/api/state')return response(200,{app_stage:'M5',running:false,finite:true});
      return response(200,{op:JSON.parse(options.body).op});
    }
  });
  const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/controller.html'),'utf8');
  vm.runInContext(html.split('<script>')[1].split('</script>')[0],context);
  const flush=async()=>{for(let i=0;i<40;i++)await Promise.resolve()};
  assert(node('videoToggle').disabled);
  node('token').value='a'.repeat(32);await node('connect').onclick();await flush();
  assert(!node('videoToggle').disabled);assert(!requests.some(r=>r.url==='/api/frame'));
  node('videoToggle').checked=true;node('videoToggle').onchange();await flush();
  assert(node('video').src.startsWith('blob:'));assert(node('videoStatus').textContent.includes('640×480'));
  assert(requests.find(r=>r.url==='/api/frame').options.headers.Authorization==='Bearer '+'a'.repeat(32));
  // A held picture request cannot block a control command/heartbeat exchange.
  mode='held';node('videoToggle').onchange();await flush();
  const count=requests.length;await buttons[1].onclick();await flush();
  assert(requests.slice(count).some(r=>r.url==='/api/command'&&JSON.parse(r.options.body).op==='walk'));
  node('videoToggle').checked=false;node('videoToggle').onchange();await flush();
  assert(!node('video').src);assert.equal(timers.size,0);
  // Deliver a late HTTP success from a stopped session; it must not revive the picture.
  heldResolve();await flush();assert(!node('video').src);
  mode='ok';node('videoToggle').checked=true;node('videoToggle').onchange();await flush();
  context.document.hidden=true;events.visibilitychange();await flush();
  assert(!node('video').src);assert.equal(timers.size,0);
  assert(requests.some(r=>r.options.keepalive&&JSON.parse(r.options.body).op==='pause'));
  context.document.hidden=false;events.visibilitychange();await flush();assert(node('video').src);
  mode='unavailable';node('videoToggle').onchange();await flush();assert(node('videoStatus').textContent.includes('等待画面'));
  assert([...timers.values()].some(t=>t.ms===500));
  mode='unauthorized';node('videoToggle').onchange();await flush();
  assert(node('videoToggle').disabled);assert(!node('video').src);assert.equal(timers.size,0);
  assert([...created].every(url=>revoked.has(url)));
  console.log(JSON.stringify({video_opt_in:true,bearer_header:true,slow_video_does_not_block_controls:true,
    stop_and_hidden_cancel_requests:true,late_frame_does_not_revive_view:true,background_pause:true,
    unavailable_retries:true,expired_token_disconnects:true,blob_urls_released:true}));
}
main().catch(error=>{console.error(error);process.exitCode=1});
