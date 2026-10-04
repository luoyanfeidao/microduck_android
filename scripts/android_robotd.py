"""Run the existing desktop Rust robotd against Android's USB-forwarded SimIo body."""
import argparse
import json
import math
import os
from pathlib import Path
import socket
import time

ROOT=Path(__file__).resolve().parents[1]

def rpc(path,method,params):
    with socket.socket(socket.AF_UNIX) as connection:
        connection.settimeout(5);connection.connect(str(path))
        with connection.makefile('rw') as stream:
            stream.write(json.dumps({'jsonrpc':'2.0','id':1,'method':method,'params':params})+'\n');stream.flush()
            answer=json.loads(stream.readline())
            if 'error' in answer:raise RuntimeError(answer['error'])
            return answer['result']

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--runtime-dir',type=Path,default=Path('/tmp/duck-android'))
    p.add_argument('--microduck',type=Path,default=ROOT.parent/'Pollen/microduck')
    p.add_argument('--rl',type=Path,default=ROOT.parent/'Pollen/microduck_rl')
    commands=p.add_subparsers(dest='command',required=True)
    run=commands.add_parser('run');run.add_argument('--body',default='127.0.0.1:17801')
    commands.add_parser('enable');commands.add_parser('disable')
    walk=commands.add_parser('walk');walk.add_argument('--vx',type=float,default=.30,help='Forward command in m/s (default: 0.30, verified walking baseline)')
    walk.add_argument('--vy',type=float,default=0.);walk.add_argument('--yaw',type=float,default=0.)
    walk.add_argument('--seconds',type=float,default=3.)
    args=p.parse_args();runtime=args.runtime_dir.resolve();endpoint=runtime/'robotd.sock'
    if len(str(endpoint).encode())>100:p.error('Use a short --runtime-dir such as /tmp/duck-android')
    if args.command=='run':
        robotd=args.microduck.resolve()/'target/debug/robotd'
        if not robotd.is_file():p.error('Build the desktop binary first: cd Pollen/microduck && cargo build -p robotd')
        if endpoint.exists():p.error('robotd.sock already exists; stop that daemon or select another --runtime-dir')
        libraries=list(args.rl.resolve().glob('.venv/lib/python*/site-packages/onnxruntime/capi/libonnxruntime*.dylib'))
        libraries+=list(args.rl.resolve().glob('.venv/lib/python*/site-packages/onnxruntime/capi/libonnxruntime.so*'))
        if not libraries:p.error('No desktop ONNX Runtime library in the RL .venv')
        policies=ROOT/'app/src/main/assets/policies'
        files={key:policies/name for key,name in [('stand','alpha_stand.onnx'),('walk','alpha_walking.onnx'),('roulade','roulade.onnx')]}
        # robotd's seated bring-up uses sitstand; its full policy set lives on the desktop.
        desktop=args.microduck.resolve()/'policies'
        files.update({key:desktop/name for key,name in [('sitstand','alpha_sitstand.onnx'),
            ('ground_pick','alpha_ground_pick.onnx'),('kick_left','ball_kick_left.onnx'),('kick_right','ball_kick_right.onnx')]})
        for path in files.values():
            if not path.is_file():p.error('Missing policy: '+str(path))
        runtime.mkdir(parents=True,exist_ok=True)
        params=runtime/'robotd.toml'
        params.write_text('[policy]\nenabled = true\n'+''.join(f'{key} = {json.dumps(str(path))}\n' for key,path in files.items()))
        env={**os.environ,'DUCK_RUNTIME_DIR':str(runtime),'ORT_DYLIB_PATH':str(libraries[0])}
        print('Using Android body '+args.body+'; enable via a second terminal. Ctrl-C stops robotd.',flush=True)
        os.execvpe(str(robotd),[str(robotd),'--sim',args.body,'--params',str(params),'--socket',str(endpoint)],env)
    elif args.command in ('enable','disable'):
        result=rpc(endpoint,'robot.enable',{'on':args.command=='enable'})
        if not result.get('accepted'):raise RuntimeError(result)
        print(json.dumps(result,ensure_ascii=False))
    else:
        for value,maximum in [(args.vx,.3),(args.vy,.15),(args.yaw,.5),(args.seconds,60)]:
            if not math.isfinite(value) or abs(value)>maximum:p.error('Velocity/duration outside the documented range')
        if args.seconds<=0:p.error('--seconds must be positive')
        until=time.monotonic()+args.seconds
        try:
            while time.monotonic()<until:
                rpc(endpoint,'robot.move',{'vx':args.vx,'vy':args.vy,'vyaw':args.yaw});time.sleep(.1)
        finally:rpc(endpoint,'robot.move',{'vx':0.,'vy':0.,'vyaw':0.})
        print('Walk command finished; standing command sent.')

if __name__=='__main__':main()
