'use strict';
// Exercise real controller JavaScript: no native codec/browser claim, only asynchronous session behavior.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
async function main(){
  const nodes=new Map(),timers=new Map(),events={},requests=[],peers=[];
  let timerId=0,heldResolve,holdPoll=false,now=1000;
  function node(id){if(!nodes.has(id))nodes.set(id,{value:'',checked:false,disabled:false,textContent:'',hidden:true,
    pause(){},play(){return Promise.resolve()},removeAttribute(k){delete this[k]}});return nodes.get(id)}
  const buttons=['stand','forward','pause'].map(op=>({dataset:{op}}));
  class Peer{
    constructor(){this.iceGatheringState='complete';this.connectionState='new';this.listeners=new Map();peers.push(this)}
    addTransceiver(kind,options){assert.equal(kind,'video');assert.equal(options.direction,'recvonly');return{setCodecPreferences:codecs=>assert.equal(codecs[0].mimeType,'video/H264')}}
    async createOffer(){return {type:'offer',sdp:'v=0\r\nrecvonly-offer'}}
    async setLocalDescription(value){this.localDescription=value}
    async setRemoteDescription(value){this.remoteDescription=value;this.ontrack({track:{kind:'video'}})}
    async addIceCandidate(value){this.candidate=value}
    async getStats(){return new Map([['in',{type:'inbound-rtp',kind:'video',framesDecoded:42,codecId:'codec'}],['codec',{mimeType:'video/H264'}]])}
    addEventListener(event,callback){this.listeners.set(event,callback)}removeEventListener(event){this.listeners.delete(event)}
    close(){this.closed=true;this.connectionState='closed';this.onconnectionstatechange?.()}
  }
  const response=(status,data)=>({status,ok:status===200,json:async()=>data});
  const context=vm.createContext({
    document:{hidden:false,getElementById:node,querySelectorAll:()=>buttons,addEventListener:(event,fn)=>events[event]=fn},
    location:{hash:'',pathname:'/'},history:{replaceState(){}},URLSearchParams,AbortController,Uint8Array,
    crypto:require('node:crypto').webcrypto,RTCPeerConnection:Peer,MediaStream:class{constructor(tracks){this.tracks=tracks}},
    RTCRtpReceiver:{getCapabilities:()=>({codecs:[{mimeType:'video/VP8'},{mimeType:'video/H264'}]})},
    URL:{revokeObjectURL(){}},performance:{now:()=>now},
    setTimeout(fn,ms){const id=++timerId;timers.set(id,{fn,ms});return id},clearTimeout:id=>timers.delete(id),setInterval(){},
    fetch:async(url,options)=>{
      requests.push({url,options});
      if(url==='/api/state')return response(200,{app_stage:'M8',finite:true});
      const body=JSON.parse(options.body);
      if(url==='/api/command')return response(200,{op:body.op});
      assert.equal(url,'/api/rtc');
      if(body.op==='poll'){
        const data={answer:{type:'answer',sdp:'v=0\r\nanswer'},candidates:[{candidate:'native-ice'}],next:1,state:'connected',error:''};
        if(holdPoll)return new Promise(resolve=>{heldResolve=()=>resolve(response(200,data))});
        return response(200,data);
      }
      return response(200,{ok:true});
    }
  });
  const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/controller.html'),'utf8');
  vm.runInContext(html.split('<script>')[1].split('</script>')[0],context);
  const flush=async()=>{for(let i=0;i<80;i++)await Promise.resolve()};
  node('videoMode').value='rtc';node('token').value='0042';
  await node('connect').onclick();await flush();assert.equal(peers.length,0);
  node('videoToggle').checked=true;await node('videoToggle').onchange();await flush();
  assert.equal(peers.length,1);assert.equal(peers[0].remoteDescription.type,'answer');
  assert.equal(peers[0].candidate.candidate,'native-ice');assert(!node('rtcVideo').hidden);
  assert(node('videoStatus').textContent.includes('video/H264'));assert(node('videoStatus').textContent.includes('42'));
  const first=JSON.parse(requests.find(r=>r.url==='/api/rtc'&&JSON.parse(r.options.body).op==='open').options.body);
  assert.match(first.id,/^[0-9a-f]{32}$/);assert(requests.every(r=>r.options.headers.Authorization==='Bearer 0042'));
  assert(!requests.some(r=>r.url==='/api/frame'));
  // Held signaling does not block commands; late answer must not revive a stopped/new session.
  holdPoll=true;await node('videoMode').onchange();await flush();const second=peers[1];
  await buttons[1].onclick();await flush();assert(requests.some(r=>r.url==='/api/command'&&JSON.parse(r.options.body).op==='walk'));
  node('videoToggle').checked=false;await node('videoToggle').onchange();await flush();
  assert(second.closed);assert(peers[0].closed);assert.equal(node('rtcVideo').srcObject,null);assert(node('rtcVideo').hidden);
  const late=heldResolve;holdPoll=false;node('videoToggle').checked=true;await node('videoToggle').onchange();await flush();
  const newStream=node('rtcVideo').srcObject;late();await flush();assert.equal(node('rtcVideo').srcObject,newStream);
  assert.equal(second.remoteDescription,undefined);
  context.document.hidden=true;events.visibilitychange();await flush();assert(peers[2].closed);
  assert.equal(node('rtcVideo').srcObject,null);assert.equal(timers.size,0);
  assert(requests.some(r=>r.options.keepalive&&r.url==='/api/command'&&JSON.parse(r.options.body).op==='pause'));
  const closes=requests.filter(r=>r.url==='/api/rtc'&&JSON.parse(r.options.body).op==='close');
  assert.equal(closes.length,3);assert.equal(new Set(closes.map(r=>JSON.parse(r.options.body).id)).size,3);
  console.log(JSON.stringify({video_opt_in:true,recvonly_no_microphone:true,h264_preference:true,
    offer_answer_ice_and_stats:true,token_header:true,held_signaling_does_not_block_control:true,
    stale_answer_ignored_after_replacement:true,close_matches_each_viewer:true,background_closes_peer_and_pauses:true}));
}
main().catch(error=>{console.error(error);process.exitCode=1});
