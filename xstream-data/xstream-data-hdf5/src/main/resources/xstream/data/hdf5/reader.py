import sys,os,json,struct,base64,resource,math
resource.setrlimit(resource.RLIMIT_AS,(int(sys.argv[3])*1024*1024,)*2)
import h5py,numpy as np
MAX_ENTRIES=10000

def send(v):
 b=json.dumps(v,allow_nan=False,separators=(',',':')).encode()
 if len(b)>32*1024*1024:raise ValueError('message limit')
 sys.stdout.buffer.write(struct.pack('>I',len(b))+b);sys.stdout.buffer.flush()
def request():
 b=sys.stdin.buffer.read(4)
 if not b:return None
 n=struct.unpack('>I',b)[0]
 if n>65536:raise ValueError('request limit')
 b=sys.stdin.buffer.read(n)
 if len(b)!=n:raise ValueError('request length')
 return json.loads(b)
def value(v):
 if isinstance(v,np.ndarray):return value(v.tolist())
 if isinstance(v,np.generic):return value(v.item())
 if isinstance(v,(list,tuple)):return [value(x) for x in v]
 if isinstance(v,bytes):return {'base64':base64.b64encode(v).decode()}
 if isinstance(v,float) and not math.isfinite(v):return str(v)
 if v is None or isinstance(v,(str,int,float,bool)):return v
 return {'unsupported':True}
def attrs(obj):
 result={}
 for key in obj.attrs:
  if len(result)>=256:raise ValueError('attribute count limit')
  attr=obj.attrs.get_id(key);size=attr.get_storage_size()
  if size>65536:result[key]={'omitted':True,'storage_bytes':size};continue
  dtype=attr.dtype
  if dtype.fields is not None or h5py.check_dtype(ref=dtype) is not None:result[key]={'unsupported':True,'dtype':str(dtype)};continue
  result[key]={'dtype':str(dtype),'shape':list(attr.shape),'value':value(obj.attrs[key])}
 return result
try:
 with h5py.File(sys.argv[1],'r') as file:
  entries=[];datasets={};seen={h5py.h5o.get_info(file.id).addr}
  group_attrs={'/':attrs(file)}
  def visit(group,path,depth):
   if depth>64:raise ValueError('depth limit')
   for name in group:
    if len(entries)>=MAX_ENTRIES:raise ValueError('entry count limit')
    target=path.rstrip('/')+'/'+name
    link=group.get(name,getlink=True)
    if not isinstance(link,h5py.HardLink):entries.append(dict(path=target,kind='data',format='hdf5-link',supported=False));continue
    obj=group[name]
    if isinstance(obj,h5py.Group):
     address=h5py.h5o.get_info(obj.id).addr
     entries.append(dict(path=target,kind='folder',format='hdf5-group',supported=False))
     if address not in seen:
      seen.add(address);group_attrs[target]=attrs(obj);visit(obj,target,depth+1)
    elif isinstance(obj,h5py.Dataset):
     dtype=obj.dtype;encoding=None
     if dtype.fields is None and h5py.check_enum_dtype(dtype) is None and dtype.kind in 'iuf' and dtype.itemsize in (1,2,4,8) and (dtype.kind!='f' or dtype.itemsize in (4,8)):
      encoding={'i':'int','u':'uint','f':'float'}[dtype.kind]+str(dtype.itemsize*8)
     supported=encoding is not None and obj.shape is not None and not obj.is_virtual and not obj.external
     meta=dict(path=target,kind='data',format='hdf5-dataset',supported=supported,encoding=encoding,shape=[] if obj.shape is None else list(obj.shape),dtype=str(dtype))
     if supported:
      meta['attributes']=attrs(obj);meta['chunks']=obj.chunks;meta['compression']=obj.compression;meta['fill_value']=value(obj.fillvalue);meta['labels']=[d.label for d in obj.dims]
     entries.append(meta);datasets[target]=obj
  visit(file,'/',0)
  send(dict(entries=entries,group_attributes=group_attrs))
  while True:
   req=request()
   if req is None:break
   path=req['path'];dataset=datasets[path]
   if not any(e['path']==path and e['supported'] for e in entries):raise ValueError('unsupported dataset')
   origin=req['origin'];shape=req['shape'];stride=req['stride']
   if len(shape)!=len(dataset.shape):raise ValueError('rank')
   for o,n,s,d in zip(origin,shape,stride,dataset.shape):
    if o<0 or n<0 or s<1 or o+(n-1)*s>=d:raise ValueError('selection')
   if math.prod(shape)*dataset.dtype.itemsize>16*1024*1024:raise ValueError('chunk limit')
   selection=tuple(slice(o,o+n*s,s) for o,n,s in zip(origin,shape,stride))
   data=np.asarray(dataset[selection] if shape else dataset[()],dtype=dataset.dtype.newbyteorder('<'))
   send(dict(data=base64.b64encode(data.tobytes(order='C')).decode()))
except Exception:
 try:send(dict(error=True))
 except Exception:pass
 sys.exit(1)
