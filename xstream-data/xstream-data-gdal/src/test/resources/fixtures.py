from osgeo import gdal,ogr,osr
import os,sys,struct
root=sys.argv[1];srs=osr.SpatialReference();srs.ImportFromEPSG(4326)
d=gdal.GetDriverByName('GTiff').Create(root+'/raster.tif',4,3,2,gdal.GDT_Int16)
d.SetProjection(srs.ExportToWkt());d.SetGeoTransform([100,.5,0,40,0,-.5])
for i in (1,2):
 b=d.GetRasterBand(i);b.WriteRaster(0,0,4,3,struct.pack('<12h',*[i*100+j for j in range(12)]));b.SetNoDataValue(100);b.SetScale(.1);b.SetOffset(2);b.SetUnitType('m')
d=None
d=gdal.GetDriverByName('MEM').Create('',2,2,1,gdal.GDT_Byte);d.GetRasterBand(1).WriteRaster(0,0,2,2,bytes([1,2,3,4]));gdal.GetDriverByName('PNG').CreateCopy(root+'/image.png',d);d=None
for driver,ext in [('GPKG','gpkg'),('GeoJSON','geojson'),('FlatGeobuf','fgb')]:
 d=ogr.GetDriverByName(driver).CreateDataSource(root+'/vector.'+ext)
 for name in (['points','empty'] if driver=='GPKG' else ['points']):
  l=d.CreateLayer(name,srs,ogr.wkbPoint)
  for n,t in [('value',ogr.OFTInteger64),('name',ogr.OFTString),('__fid',ogr.OFTString)]:l.CreateField(ogr.FieldDefn(n,t))
  if name=='empty':continue
  for i in range(3):
   f=ogr.Feature(l.GetLayerDefn());f.SetField('value',i+10);f.SetField('name','p'+str(i));f.SetField('__fid','original')
   g=ogr.Geometry(ogr.wkbPoint);g.AddPoint_2D(100+i,30+i);f.SetGeometry(g);l.CreateFeature(f)
 d=None
open(root+'/bad.vrt','w').write('<VRTDataset rasterXSize="1" rasterYSize="1"><VRTRasterBand dataType="Byte" band="1"><SimpleSource><SourceFilename>/etc/passwd</SourceFilename></SimpleSource></VRTRasterBand></VRTDataset>')
open(root+'/broken.tif','wb').write(b'II*\0broken')
d=gdal.GetDriverByName('GTiff').Create(root+'/complex.tif',1,1,1,gdal.GDT_CFloat32);d=None
open(root+'/unsupported.geojson','w').write('{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"items":[1,2]},"geometry":null}]}')
