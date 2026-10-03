#!/usr/bin/env python3
"""Bundle only dependencies of the desktop duck-body default scene, without changing sources."""
from pathlib import Path
import hashlib,json,shutil,xml.etree.ElementTree as ET
PROJECT=Path(__file__).resolve().parents[1]
SOURCE=PROJECT.parent/'Pollen/microduck_rl/src/mjlab_microduck/robot/microduck'
DEST=PROJECT/'app/src/main/assets/model'
needed=set()
def visit(relative):
    relative=Path(relative)
    path=(SOURCE/relative).resolve()
    if not path.is_relative_to(SOURCE.resolve()): raise ValueError(relative)
    if relative in needed: return
    needed.add(relative)
    tree=ET.parse(path)
    compiler=tree.find('compiler')
    meshdir=compiler.get('meshdir','') if compiler is not None else ''
    texturedir=compiler.get('texturedir','') if compiler is not None else ''
    for node in tree.iter():
        if node.tag=='include': visit(relative.parent/node.attrib['file'])
        elif node.tag in ('mesh','texture','hfield') and 'file' in node.attrib:
            folder=meshdir if node.tag=='mesh' else texturedir
            needed.add(relative.parent/folder/node.attrib['file'])
visit('scene.xml')
records=[]
for relative in sorted(needed):
    source=SOURCE/relative
    if not source.is_file(): raise FileNotFoundError(source)
    destination=DEST/relative; destination.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(source,destination)
    records.append({'path':relative.as_posix(),'sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'bytes':source.stat().st_size})
manifest={'entry':'scene.xml','source_root':str(SOURCE.relative_to(PROJECT.parent)),
          'mujoco_version':'3.10.0','physics_timestep':0.005,'files':records}
(DEST/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'assets':len(records),'bytes':sum(r['bytes'] for r in records)}))
policy_dest=PROJECT/'app/src/main/assets/policies'
policy_dest.mkdir(parents=True,exist_ok=True)
policies=[]
for name in ('alpha_stand','alpha_walking'):
    source=PROJECT.parent/'Pollen/microduck/policies'/(name+'.onnx')
    shutil.copyfile(source,policy_dest/source.name)
    policies.append({'name':name,'file':source.name,'sha256':hashlib.sha256(source.read_bytes()).hexdigest()})
(policy_dest/'manifest.json').write_text(json.dumps({'policies':policies},indent=2)+'\n')
