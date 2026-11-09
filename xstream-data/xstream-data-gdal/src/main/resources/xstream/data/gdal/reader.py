import os,sys,json,struct,base64,resource,math
resource.setrlimit(resource.RLIMIT_AS,(int(sys.argv[3])*1024*1024,)*2)
os.environ.update(GDAL_PAM_ENABLED='NO',GDAL_DISABLE_READDIR_ON_OPEN='EMPTY_DIR',GDAL_DRIVER_PATH='disable',PROJ_NETWORK='OFF',OGR_SQLITE_ALLOW_VIRTUAL_OGR='NO')
from osgeo import gdal,ogr
gdal.UseExceptions();gdal.PushErrorHandler('CPLQuietErrorHandler');gdal.SetCacheMax(16*1024*1024)
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
def finite(v):return v if v is None or math.isfinite(v) else str(v)
def fresh(base,names):
 while base in names:base='_'+base
 names.add(base);return base
raster=sys.argv[2]=='raster'
try:
 allowed=['GTiff','PNG','JPEG','BMP','GIF','AAIGrid','USGSDEM'] if raster else ['GPKG','GeoJSON','FlatGeobuf']
 ds=gdal.OpenEx(sys.argv[1],gdal.OF_READONLY|(gdal.OF_RASTER if raster else gdal.OF_VECTOR),allowed_drivers=allowed,sibling_files=[])
 if ds is None:raise ValueError('open failed')
 if any(os.path.realpath(f)!=os.path.realpath(sys.argv[1]) for f in (ds.GetFileList() or [])):raise ValueError('external resource')
 driver=ds.GetDriver().ShortName
 if raster:
  types={'Byte':'uint8','Int8':'int8','UInt16':'uint16','Int16':'int16','UInt32':'uint32','Int32':'int32','UInt64':'uint64','Int64':'int64','Float32':'float32','Float64':'float64'};bands=[]
  if ds.RasterCount>1024:raise ValueError('band limit')
  for i in range(1,ds.RasterCount+1):
   b=ds.GetRasterBand(i);bands.append(dict(index=i,encoding=types.get(gdal.GetDataTypeName(b.DataType)),description=b.GetDescription(),unit=b.GetUnitType(),scale=finite(b.GetScale()),offset=finite(b.GetOffset()),nodata=finite(b.GetNoDataValue()),color_interpretation=gdal.GetColorInterpretationName(b.GetColorInterpretation()),mask_flags=b.GetMaskFlags()))
  send(dict(driver=driver,width=ds.RasterXSize,height=ds.RasterYSize,crs_wkt=ds.GetProjectionRef(),geotransform=ds.GetGeoTransform(can_return_null=True),bands=bands))
 else:
  layers=[];supported={ogr.OFTInteger:'int32',ogr.OFTInteger64:'int64',ogr.OFTReal:'float64',ogr.OFTString:'utf8',ogr.OFTDate:'utf8',ogr.OFTTime:'utf8',ogr.OFTDateTime:'utf8'}
  if ds.GetLayerCount()>1024:raise ValueError('layer limit')
  for i in range(ds.GetLayerCount()):
   layer=ds.GetLayerByIndex(i);definition=layer.GetLayerDefn();fields=[];geometries=[];names=set();valid=True
   for j in range(definition.GetFieldCount()):
    f=definition.GetFieldDefn(j);name=f.GetName();kind=supported.get(f.GetType())
    if not name or name in names or kind is None:valid=False
    names.add(name);fields.append(dict(name=name,encoding=kind,native_type=f.GetTypeName(),boolean=f.GetType()==ogr.OFTInteger and f.GetSubType()==ogr.OFSTBoolean))
   fid=fresh('__fid',names)
   for j in range(definition.GetGeomFieldCount()):
    f=definition.GetGeomFieldDefn(j);srs=f.GetSpatialRef();geometries.append(dict(name=fresh('__geometry'+('' if j==0 else str(j)),names),source_name=f.GetName(),geometry_type=ogr.GeometryTypeToName(f.GetType()),crs_wkt='' if srs is None else srs.ExportToWkt()))
   layers.append(dict(index=i,name=layer.GetName(),fields=fields,fid=fid,geometries=geometries,supported=valid))
  send(dict(driver=driver,layers=layers))
 while True:
  req=request()
  if req is None:break
  if req['op']=='array' and raster:
   band=ds.GetRasterBand(req['band'])
   if req.get('mask',False):band=band.GetMaskBand()
   y,x=req['origin'];ny,nx=req['shape'];sy,sx=req['stride'];width=gdal.GetDataTypeSize(band.DataType)//8
   if min(y,x,ny,nx)<0 or min(sy,sx)<1 or y+(ny-1)*sy>=ds.RasterYSize or x+(nx-1)*sx>=ds.RasterXSize or nx*ny*width>16*1024*1024:raise ValueError('selection')
   chunks=[]
   for row in range(ny):
    if sx==1:chunks.append(band.ReadRaster(x,y+row*sy,nx,1,buf_type=band.DataType))
    else:
     for col in range(nx):chunks.append(band.ReadRaster(x+col*sx,y+row*sy,1,1,buf_type=band.DataType))
   raw=b''.join(chunks)
   if sys.byteorder!='little' and width>1:raw=b''.join(raw[i:i+width][::-1] for i in range(0,len(raw),width))
   send(dict(data=base64.b64encode(raw).decode()))
  elif req['op']=='records' and not raster:
   index=req['layer'];layer=ds.GetLayerByIndex(index);meta=layers[index]
   if not meta['supported']:raise ValueError('unsupported fields')
   layer.ResetReading();features=iter(layer);send(dict(ready=True))
  elif req['op']=='next' and not raster:
   rows=[];size=0;ended=False
   for _ in range(min(req['rows'],256)):
    feature=next(features,None)
    if feature is None:ended=True;break
    values=[feature.GetFID()]
    for j,f in enumerate(meta['fields']):
     v=feature.GetField(j)
     if v is not None and f['boolean']:v=bool(v)
     if isinstance(v,float) and not math.isfinite(v):v=str(v)
     values.append(v)
    for j in range(len(meta['geometries'])):
     geom=feature.GetGeomFieldRef(j);values.append(None if geom is None else base64.b64encode(geom.ExportToWkb(ogr.wkbNDR)).decode())
    length=len(json.dumps(values).encode())
    if length>1024*1024:raise ValueError('feature limit')
    rows.append(values);size+=length
    if size>=req['bytes']:break
   send(dict(rows=rows,end=ended))
  else:raise ValueError('operation')
except Exception:
 try:send(dict(error=True))
 except Exception:pass
 sys.exit(1)
