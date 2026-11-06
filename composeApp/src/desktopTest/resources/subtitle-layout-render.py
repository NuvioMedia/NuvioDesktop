#!/usr/bin/env python3
"""Render the production text stack and inspect every frame during cue growth."""
import ctypes as C
import ctypes.util
import json
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import zlib
from pathlib import Path

class Param(C.Structure):
    _fields_=[('type',C.c_int),('data',C.c_void_p)]
class Event(C.Structure):
    _fields_=[('id',C.c_int),('error',C.c_int),('userdata',C.c_uint64),('data',C.c_void_p)]
case=json.loads(sys.stdin.readline())
library=ctypes.util.find_library('mpv')
if not library and shutil.which('mpv'):
    prefix=Path(shutil.which('mpv')).resolve().parent.parent
    library=next((str(p) for p in (prefix/'lib').glob('libmpv*') if '.dylib' in p.name or '.so' in p.name),None)
if not library or not shutil.which('ffmpeg'):
    print('Optional render check requires libmpv and ffmpeg',file=sys.stderr);sys.exit(77)
lib=C.CDLL(library)
for name,result,args in [
 ('mpv_create',C.c_void_p,[]),('mpv_initialize',C.c_int,[C.c_void_p]),
 ('mpv_set_option_string',C.c_int,[C.c_void_p,C.c_char_p,C.c_char_p]),
 ('mpv_set_property_string',C.c_int,[C.c_void_p,C.c_char_p,C.c_char_p]),
 ('mpv_get_property_string',C.c_void_p,[C.c_void_p,C.c_char_p]),
 ('mpv_free',None,[C.c_void_p]),('mpv_command',C.c_int,[C.c_void_p,C.POINTER(C.c_char_p)]),
 ('mpv_wait_event',C.POINTER(Event),[C.c_void_p,C.c_double]),
 ('mpv_render_context_create',C.c_int,[C.POINTER(C.c_void_p),C.c_void_p,C.POINTER(Param)]),
 ('mpv_render_context_render',C.c_int,[C.c_void_p,C.POINTER(Param)]),
 ('mpv_render_context_update',C.c_uint64,[C.c_void_p]),
 ('mpv_render_context_free',None,[C.c_void_p]),('mpv_terminate_destroy',None,[C.c_void_p])]:
    fn=getattr(lib,name);fn.restype=result;fn.argtypes=args
probe=C.CDLL(case['probe']);probe.subtitle_observe.argtypes=[C.c_void_p,C.c_void_p]
def check(code):
    if code<0:raise RuntimeError(f'mpv error {code}')
handle=lib.mpv_create()
def get(name):
    pointer=lib.mpv_get_property_string(handle,name.encode())
    if not pointer:return ''
    try:return C.string_at(pointer).decode()
    finally:lib.mpv_free(pointer)
def put(name,value):check(lib.mpv_set_property_string(handle,name.encode(),str(value).encode()))
def command(*args):check(lib.mpv_command(handle,(C.c_char_p*(len(args)+1))(*[a.encode() for a in args],None)))
for key,value in {'config':'no','osd-level':'0','osd-bar':'no','vo':'libmpv','ao':'null','hwdec':'no',
                  'pause':'yes','sid':'no','secondary-sid':'no','terminal':'no','keep-open':'yes'}.items():
    check(lib.mpv_set_option_string(handle,key.encode(),value.encode()))
check(lib.mpv_initialize(handle))
probe.subtitle_observe(handle,C.cast(lib.mpv_observe_property,C.c_void_p))
ready=threading.Event();stop=threading.Event();lock=threading.Lock();frames=[];errors=[]
w,h=case['width'],case['height'];capture_transition=False;history=[];images={}
def renderer():
    context=C.c_void_p();api=C.create_string_buffer(b'sw')
    try:
        check(lib.mpv_render_context_create(C.byref(context),handle,(Param*2)(Param(1,C.cast(api,C.c_void_p)),Param(0,None))))
        size=(C.c_int*2)(w,h);fmt=C.create_string_buffer(b'rgb0');stride=C.c_size_t(w*4);block=C.c_int(0)
        allocation=C.create_string_buffer(w*h*4+63);address=(C.addressof(allocation)+63)&~63
        params=(Param*6)(Param(17,C.cast(size,C.c_void_p)),Param(18,C.cast(fmt,C.c_void_p)),
            Param(19,C.cast(C.byref(stride),C.c_void_p)),Param(20,address),Param(12,C.cast(C.byref(block),C.c_void_p)),Param(0,None))
        ready.set()
        while not stop.is_set():
            if not lib.mpv_render_context_update(context)&1:time.sleep(.005);continue
            C.memset(address,0,w*h*4);check(lib.mpv_render_context_render(context,params))
            data=C.string_at(address,w*h*4)
            with lock:frames[:]=[data]
            if capture_transition and len(history)<200:history.append(data)
    except BaseException as error:errors.append(error);ready.set()
    finally:
        if context:lib.mpv_render_context_free(context)
thread=threading.Thread(target=renderer);thread.start();assert ready.wait(5)
if errors:raise errors[0]
def caption_mask(data):
    return bytes(r>200 and g>200 and b>200 for r,g,b in zip(data[0::4],data[1::4],data[2::4]))
def inspect(label):
    time.sleep(.15)
    with lock:data=frames[0]
    images[label]=data;groups=[];mask=caption_mask(data)
    for y in range(h):
        count=sum(mask[y*w:(y+1)*w])
        if count and (not groups or y>groups[-1][1]+1):groups.append([y,y])
        elif count:groups[-1][1]=y
    raw=b''.join(b'\0'+b''.join(data[(y*w+x)*4:(y*w+x)*4+3] for x in range(w)) for y in range(h))
    def chunk(kind,body):return struct.pack('>I',len(body))+kind+body+struct.pack('>I',zlib.crc32(kind+body)&0xffffffff)
    Path(case['output']+'-'+label+'.png').write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))
    return groups

def escape(text):return text.replace('\\','\\\u2060').replace('{','\\{').replace('\n','\\N')
try:
    with tempfile.TemporaryDirectory() as directory:
        root=Path(directory);paths=[]
        for index,text in enumerate([case['primary'],case['secondary']]):
            path=root/f'{index}.srt';paths.append(path)
            prefix='1\n00:00:00,000 --> 00:00:09,000\n'
            if case['transition'] and index==0:
                prefix='1\n00:00:00,000 --> 00:00:03,000\nPRIMARY SUBTITLE\n\n2\n00:00:03,000 --> 00:00:09,000\n'
            path.write_text(prefix+text+'\n')
        media=root/'video.mkv'
        args=['ffmpeg','-v','error','-f','lavfi','-i',f"color=c=black:s=640x{case['videoHeight']}:r=24:d=10"]
        for path in paths:args+=['-i',str(path)]
        args+=['-map','0:v','-map','1:s','-map','2:s','-c:v','mpeg4','-q:v','2','-c:s','srt',str(media)]
        subprocess.run(args,check=True,timeout=20)
        command('loadfile',str(media));deadline=time.monotonic()+5;loaded=False
        while time.monotonic()<deadline:
            if lib.mpv_wait_event(handle,.01).contents.id==8:loaded=True;break
        assert loaded,'Fixture did not finish loading'
        put('sid','1');put('secondary-sid','2')
        for key,value in case['properties'].items():put(key,value)
        put('sub-ass-override','strip');put('secondary-sub-ass-override','strip')
        while lib.mpv_wait_event(handle,0).contents.id:pass
        command('seek','2','absolute+exact')
        deadline=time.monotonic()+5;restarted=False
        while time.monotonic()<deadline:
            if lib.mpv_wait_event(handle,.01).contents.id==21:restarted=True;break
        assert restarted,'Fixture seek did not finish'
        deadline=time.monotonic()+5
        # Compare decoded text, since the SRT decoder can normalize markup and escapes.
        while time.monotonic()<deadline and (not get('sub-text') or not get('secondary-sub-text') or not get('osd-dimensions/w')):time.sleep(.01)
        assert get('sub-text') and get('secondary-sub-text'),json.dumps({key:get(key) for key in ['sid','secondary-sid','sub-text','secondary-sub-text','time-pos','pause','duration']})
        if case['transition']:assert get('sub-text')=='PRIMARY SUBTITLE'
        style=['font','font-size','bold','italic','color','outline-color','outline-size','back-color','shadow-color',
               'shadow-offset','border-style','fonts-dir','margin-x']
        keys=['sid','secondary-sid','sub-text','secondary-sub-text','sub-scale','sub-pos','sub-margin-y',
              'osd-dimensions/w','osd-dimensions/h','sub-visibility','secondary-sub-visibility','osd-scale','osd-scale-by-window']
        keys += ['sub-'+suffix for suffix in style]+['osd-'+suffix for suffix in style]
        for phase in range(2 if case['transition'] else 1):
            if phase:
                while lib.mpv_wait_event(handle,0).contents.id:pass
                history.clear();capture_transition=True;put('pause','no');changed=time.monotonic();notified=False
                while time.monotonic()-changed<2:
                    event=lib.mpv_wait_event(handle,.005).contents
                    if event.id==22 and event.userdata==3100 and get('sub-text')==case['primary']:
                        notified=True;break
                assert notified,'New cue must notify layout without playback polling'
            print(json.dumps({key:get(key) for key in keys}),flush=True)
            update=json.loads(sys.stdin.readline())
            for key,value in update['properties'].items():put(key,value)
            for args in update['commands']:command(*args)
            overlay=next(args for args in update['commands'] if args[0]=='osd-overlay' and args[2]=='ass-events')
            combined=inspect(f'{phase}-combined');capture_transition=False;put('pause','yes')
            assert get('sub-visibility')=='no' and get('secondary-sub-visibility')=='no','Native tracks must stay hidden while the combined stack renders'
            data=overlay[3];anchor=data[:data.index('}')+1];primary_text=escape(get('sub-text'));secondary_text=escape(get('secondary-sub-text'))
            primary_only=anchor+'{\\alpha&HFF&}'+secondary_text+'\\N{\\alpha&H00&}'+primary_text
            secondary_only=anchor+secondary_text+'\\N{\\alpha&HFF&}'+primary_text
            command(*overlay[:3],primary_only,*overlay[4:]);primary=inspect(f'{phase}-primary-only')
            command(*overlay[:3],secondary_only,*overlay[4:]);secondary=inspect(f'{phase}-secondary-only')
            command(*overlay)
            assert primary and secondary,'Both text cues must render'
            assert secondary[-1][1]<primary[0][0],f'OVERLAP: secondary {secondary} / primary {primary}'
            gap=primary[0][0]-secondary[-1][1]
            font_height=float(case['properties']['sub-font-size'])*float(case['properties']['sub-scale'])*h/720
            assert gap<=max(h*.05,font_height*.5),f'EXCESSIVE GAP: {gap}px at {h}px with {font_height}px text'
            checked=0
            if phase:
                primary_image=caption_mask(images[f'{phase}-primary-only']);expected=caption_mask(images[f'{phase}-combined'])
                a=primary[-1][0]*w;b=(primary[-1][1]+1)*w;marker=primary_image[a:b]
                for frame in history:
                    frame=caption_mask(frame)
                    if frame[a:b]==marker:
                        checked+=1
                        assert frame==expected,'First frame showing the new primary cue still had old secondary placement'
                assert checked,'No frames containing the new two-line primary were captured'
            print(json.dumps({'primary':primary,'secondary':secondary,'transitionFramesChecked':checked}),flush=True)
finally:
    stop.set();thread.join(5);lib.mpv_terminate_destroy(handle)
